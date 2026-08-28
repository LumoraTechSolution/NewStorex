package com.lumora.pos.cloud;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The super-admin API: tenants, plans, licences and feature flags (M4-08).
 *
 * <h2>What this can and cannot touch</h2>
 *
 * Every method here requires {@link AuthenticatedPrincipal.Kind#PLATFORM}, and that kind carries no
 * tenant — so a staff session cannot fall through into a tenant-scoped read or write by accident.
 * Going the other way is closed by the same mechanism: a platform session on the sync endpoint or
 * the console's reports is a 403, because those require {@code TILL} and {@code CONSOLE}.
 *
 * <p>The result is a wall worth stating plainly: <b>Lumora staff can create a shop, licence it,
 * suspend it and issue it a key — and cannot ring up a sale in it, move its stock, or read a figure
 * through the owner's endpoints.</b> Administering a business is not the same power as operating
 * one, and the two are different credential kinds rather than different branches in one.
 *
 * <p>Every tenant is named in the path, never inferred. That is the opposite of
 * {@link ConsoleReportController}, where taking the tenant from a parameter would be the bug — here
 * it is the only honest way to say which shop is meant, and each write re-checks that the shop
 * exists before it changes anything.
 */
@RestController
@RequestMapping("/api/platform")
@Profile("cloud")
public class PlatformController {

    private final TenantAdminService tenants;
    private final LicenceService licences;
    private final PlatformAuditService audit;
    private final PlatformSessionService sessions;

    public PlatformController(
            TenantAdminService tenants,
            LicenceService licences,
            PlatformAuditService audit,
            PlatformSessionService sessions) {
        this.tenants = tenants;
        this.licences = licences;
        this.audit = audit;
        this.sessions = sessions;
    }

    // ------------------------------------------------------------------ catalogue

    @GetMapping("/plans")
    public List<LicenceService.Plan> plans(
            HttpServletRequest request,
            @RequestParam(defaultValue = "false") boolean includeRetired) {
        requireAdmin(request);
        return licences.plans(includeRetired);
    }

    @GetMapping("/flags")
    public List<LicenceService.FeatureFlag> flags(HttpServletRequest request) {
        requireAdmin(request);
        return licences.knownFlags();
    }

    // ------------------------------------------------------------------ tenants

    @GetMapping("/tenants")
    public List<TenantAdminService.TenantSummary> listTenants(HttpServletRequest request) {
        requireAdmin(request);
        return tenants.listTenants();
    }

    @GetMapping("/tenants/{tenantId}")
    public TenantAdminService.TenantDetail tenant(
            HttpServletRequest request, @PathVariable long tenantId) {
        requireAdmin(request);
        return tenants.tenantDetail(tenantId);
    }

    /**
     * Signs a shop up. The endpoint that clears M4-08's actual blocker — before this, an owner
     * account could be created only by running Java.
     *
     * @return the till token and a reminder that it is shown exactly once
     */
    @PostMapping("/tenants")
    public NewTenantResponse createTenant(
            HttpServletRequest request, @Valid @RequestBody CreateTenantRequest body) {
        long adminId = requireAdmin(request);
        TenantAdminService.NewTenant created =
                tenants.createTenant(
                        body.name(),
                        body.planCode(),
                        body.licenceDays(),
                        body.ownerEmail(),
                        body.ownerPassword(),
                        body.ownerName(),
                        body.terminalLabel(),
                        adminId);
        return new NewTenantResponse(
                created.tenantId(),
                created.clientUuid().toString(),
                created.name(),
                created.ownerEmail(),
                created.tillToken(),
                created.licenceExpiresAt().toString(),
                "This token is shown once. Put it in the till's LUMORA_CLOUD_TOKEN and it cannot be"
                        + " read back — a lost one is reissued, never recovered.");
    }

    @PostMapping("/tenants/{tenantId}/suspend")
    public Ok suspend(
            HttpServletRequest request, @PathVariable long tenantId, @RequestBody(required = false) WhyRequest body) {
        long adminId = requireAdmin(request);
        tenants.setActive(tenantId, false, body == null ? "" : body.why(), adminId);
        return new Ok("Suspended. The till stops syncing and the owner is signed out.");
    }

    @PostMapping("/tenants/{tenantId}/resume")
    public Ok resume(
            HttpServletRequest request, @PathVariable long tenantId, @RequestBody(required = false) WhyRequest body) {
        long adminId = requireAdmin(request);
        tenants.setActive(tenantId, true, body == null ? "" : body.why(), adminId);
        return new Ok("Resumed. A queued outbox drains on the next tick.");
    }

    // ------------------------------------------------------------------ licences

    @PostMapping("/tenants/{tenantId}/licence")
    public LicenceService.Licence grantLicence(
            HttpServletRequest request,
            @PathVariable long tenantId,
            @Valid @RequestBody GrantLicenceRequest body) {
        long adminId = requireAdmin(request);
        return tenants.grantLicence(tenantId, body.planCode(), body.days(), body.note(), adminId);
    }

    // ------------------------------------------------------------------ feature flags

    /** {@code enabled} null clears the override, putting this shop back on its plan's answer. */
    @PostMapping("/tenants/{tenantId}/flags")
    public List<String> setFlag(
            HttpServletRequest request,
            @PathVariable long tenantId,
            @Valid @RequestBody SetFlagRequest body) {
        long adminId = requireAdmin(request);
        tenants.setFlag(tenantId, body.flagCode(), body.enabled(), body.note(), adminId);
        return List.copyOf(licences.effectiveFlags(tenantId));
    }

    // ------------------------------------------------------------------ credentials

    @PostMapping("/tenants/{tenantId}/credentials")
    public IssuedToken issueToken(
            HttpServletRequest request,
            @PathVariable long tenantId,
            @Valid @RequestBody IssueTokenRequest body) {
        long adminId = requireAdmin(request);
        return new IssuedToken(
                tenants.issueTillToken(tenantId, body.label(), adminId),
                "Shown once. It cannot be read back.");
    }

    @PostMapping("/tenants/{tenantId}/credentials/{credentialId}/revoke")
    public Ok revokeToken(
            HttpServletRequest request,
            @PathVariable long tenantId,
            @PathVariable long credentialId) {
        long adminId = requireAdmin(request);
        tenants.revokeTillToken(tenantId, credentialId, adminId);
        return new Ok("Revoked. That machine stops syncing; the shop's history is untouched.");
    }

    // ------------------------------------------------------------------ owners

    @PostMapping("/tenants/{tenantId}/owners")
    public TenantAdminService.Owner addOwner(
            HttpServletRequest request,
            @PathVariable long tenantId,
            @Valid @RequestBody AddOwnerRequest body) {
        long adminId = requireAdmin(request);
        ConsoleUserService.ConsoleUser owner =
                tenants.addOwner(tenantId, body.email(), body.password(), body.displayName(), adminId);
        return new TenantAdminService.Owner(
                owner.id(), owner.email(), owner.displayName(), owner.active(), null);
    }

    @PostMapping("/tenants/{tenantId}/owners/{consoleUserId}/deactivate")
    public Ok deactivateOwner(
            HttpServletRequest request,
            @PathVariable long tenantId,
            @PathVariable long consoleUserId) {
        long adminId = requireAdmin(request);
        tenants.setOwnerActive(tenantId, consoleUserId, false, adminId);
        return new Ok("Deactivated, and every live session of theirs is signed out.");
    }

    @PostMapping("/tenants/{tenantId}/owners/{consoleUserId}/activate")
    public Ok activateOwner(
            HttpServletRequest request,
            @PathVariable long tenantId,
            @PathVariable long consoleUserId) {
        long adminId = requireAdmin(request);
        tenants.setOwnerActive(tenantId, consoleUserId, true, adminId);
        return new Ok("Reactivated. They will need to sign in again.");
    }

    // ------------------------------------------------------------------ audit

    @GetMapping("/audit")
    public List<PlatformAuditService.Entry> auditTrail(
            HttpServletRequest request, @RequestParam(defaultValue = "50") int limit) {
        requireAdmin(request);
        return audit.recent(limit);
    }

    /**
     * Asserts the credential kind and resolves the person behind it.
     *
     * <p>The session id on the principal is not the admin id, and the audit trail wants the second.
     * Looked up rather than carried on the principal because a session outliving its admin is
     * exactly the case the {@code active} re-check exists to catch, and one lookup on an
     * administrative write is not a cost worth optimising away.
     */
    private long requireAdmin(HttpServletRequest request) {
        AuthenticatedPrincipal principal =
                CloudPrincipals.require(request, AuthenticatedPrincipal.Kind.PLATFORM);
        return sessions
                .adminIdForSession(principal.credentialId())
                .orElseThrow(() -> new IllegalStateException("Session vanished mid-request"));
    }

    // ------------------------------------------------------------------ shapes

    public record CreateTenantRequest(
            @NotBlank String name,
            String planCode,
            Integer licenceDays,
            @NotBlank String ownerEmail,
            @NotBlank String ownerPassword,
            @NotBlank String ownerName,
            String terminalLabel) {}

    public record NewTenantResponse(
            long tenantId,
            String clientUuid,
            String name,
            String ownerEmail,
            String tillToken,
            String licenceExpiresAt,
            String warning) {}

    public record GrantLicenceRequest(@NotBlank String planCode, Integer days, String note) {}

    public record SetFlagRequest(@NotBlank String flagCode, Boolean enabled, String note) {}

    public record IssueTokenRequest(@NotBlank String label) {}

    public record AddOwnerRequest(
            @NotBlank String email, @NotBlank String password, @NotBlank String displayName) {}

    public record IssuedToken(String token, String warning) {}

    public record WhyRequest(String why) {}

    public record Ok(String detail) {}
}
