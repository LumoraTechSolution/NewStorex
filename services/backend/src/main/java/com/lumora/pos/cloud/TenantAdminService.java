package com.lumora.pos.cloud;

import com.lumora.pos.web.RejectedException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signing a shop up, and everything a member of staff can do to one afterwards (M4-08).
 *
 * <h2>Why creating a tenant is one call and not four</h2>
 *
 * A usable shop needs four things that were, until this class, four separate acts: the tenant row,
 * a licence period, an owner who can sign in, and a token for the till. A shop missing any one of
 * them looks created and does not work — an owner with no licence syncs nothing, a tenant with no
 * owner is a database row nobody can reach, and a token issued against an unlicensed tenant is a
 * key that authenticates as nothing. Worse, each of those is a state somebody has to notice.
 *
 * <p>So {@link #createTenant} does all four in one transaction, and either a working shop exists or
 * none of it does. The plaintext token and the owner's password exist only in its return value.
 *
 * <h2>Every write here is audited in its own transaction</h2>
 *
 * These are the acts that reach across shops, so each one writes a {@code platform_audit} row
 * beside the change rather than after it. See {@link PlatformAuditService} on why that propagation
 * is asserted rather than trusted.
 */
@Service
@Profile("cloud")
public class TenantAdminService {

    private final JdbcTemplate jdbc;
    private final TenantCredentialService credentials;
    private final ConsoleUserService consoleUsers;
    private final ConsoleSessionService consoleSessions;
    private final LicenceService licences;
    private final PlatformAuditService audit;

    public TenantAdminService(
            JdbcTemplate jdbc,
            TenantCredentialService credentials,
            ConsoleUserService consoleUsers,
            ConsoleSessionService consoleSessions,
            LicenceService licences,
            PlatformAuditService audit) {
        this.jdbc = jdbc;
        this.credentials = credentials;
        this.consoleUsers = consoleUsers;
        this.consoleSessions = consoleSessions;
        this.licences = licences;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ creating a shop

    /**
     * Creates a shop that works: tenant, licence, owner login, and the till's first token.
     *
     * @param planCode null falls back to the trial plan.
     * @param licenceDays null falls back to the trial length.
     * @return the two secrets, each returned exactly once and recoverable from nowhere.
     */
    @Transactional
    public NewTenant createTenant(
            String name,
            String planCode,
            Integer licenceDays,
            String ownerEmail,
            String ownerPassword,
            String ownerName,
            String terminalLabel,
            long actingAdminId) {

        if (name == null || name.isBlank()) {
            throw new RejectedException("A shop needs a name.");
        }

        String plan = planCode == null || planCode.isBlank() ? LicenceService.DEFAULT_PLAN_CODE : planCode;
        int days = licenceDays == null ? LicenceService.DEFAULT_TRIAL_DAYS : licenceDays;
        if (days < 1) {
            throw new RejectedException("A licence has to run for at least a day.");
        }

        // Checked before anything is written. The alternative is a unique-violation halfway through
        // that rolls back correctly but reports itself as a 500 rather than as the thing the person
        // at the screen can fix.
        licences.planIdForCode(plan);

        UUID clientUuid = UUID.randomUUID();
        long tenantId =
                jdbc.queryForObject(
                        "INSERT INTO tenants (client_uuid, name) VALUES (?, ?) RETURNING id",
                        Long.class,
                        clientUuid,
                        name.trim());

        Instant expiresAt = Instant.now().plus(days, ChronoUnit.DAYS);
        licences.grant(tenantId, plan, null, expiresAt, "Created with the tenant.", actingAdminId);

        ConsoleUserService.ConsoleUser owner =
                consoleUsers.create(tenantId, ownerEmail, ownerPassword, ownerName);

        String label = terminalLabel == null || terminalLabel.isBlank() ? "Till 1" : terminalLabel.trim();
        String token = credentials.issueToken(tenantId, label);

        audit.record(
                actingAdminId,
                "tenant.create",
                tenantId,
                Map.of("name", name.trim(), "plan", plan, "owner", owner.email(), "terminal", label));

        return new NewTenant(tenantId, clientUuid, name.trim(), owner.email(), token, expiresAt);
    }

    // ------------------------------------------------------------------ suspension

    /**
     * The blunt instrument, and distinct from a lapsed licence on purpose.
     *
     * <p>A lapsed licence stops ingest and leaves the owner reading the console, because they need
     * somewhere to be told why. Suspension stops both — it is for a shop that should not be using
     * the product at all, not for one that is late paying. V209's header has the argument.
     *
     * <p>Suspending also revokes the owner's live sessions. Without that, a suspension takes effect
     * only when their current session happens to expire, which for a seven-day console token could
     * be a week.
     */
    @Transactional
    public void setActive(long tenantId, boolean active, String why, long actingAdminId) {
        requireTenant(tenantId);
        jdbc.update("UPDATE tenants SET active = ? WHERE id = ?", active, tenantId);

        if (!active) {
            for (Long consoleUserId :
                    jdbc.queryForList(
                            "SELECT id FROM console_users WHERE tenant_id = ?", Long.class, tenantId)) {
                consoleSessions.signOutAll(consoleUserId, "tenant suspended");
            }
        }

        audit.record(
                actingAdminId,
                active ? "tenant.resume" : "tenant.suspend",
                tenantId,
                Map.of("why", why == null ? "" : why));
    }

    // ------------------------------------------------------------------ licences and flags

    @Transactional
    public LicenceService.Licence grantLicence(
            long tenantId, String planCode, Integer days, String note, long actingAdminId) {
        requireTenant(tenantId);
        if (days == null || days < 1) {
            throw new RejectedException("A licence has to run for at least a day.");
        }

        // From the end of the current period when there is one, so renewing early adds time rather
        // than throwing away what is left of the month somebody has already paid for.
        Instant startsAt =
                licences.current(tenantId).map(LicenceService.Licence::expiresAt).orElse(Instant.now());
        if (startsAt.isBefore(Instant.now())) {
            startsAt = Instant.now();
        }

        LicenceService.Licence granted =
                licences.grant(
                        tenantId,
                        planCode,
                        startsAt,
                        startsAt.plus(days, ChronoUnit.DAYS),
                        note,
                        actingAdminId);

        audit.record(
                actingAdminId,
                "licence.grant",
                tenantId,
                Map.of("plan", granted.planCode(), "days", days, "until", granted.expiresAt().toString()));
        return granted;
    }

    @Transactional
    public void setFlag(
            long tenantId, String flagCode, Boolean enabled, String note, long actingAdminId) {
        requireTenant(tenantId);
        licences.setOverride(tenantId, flagCode, enabled, note, actingAdminId);
        audit.record(
                actingAdminId,
                "flag.set",
                tenantId,
                Map.of("flag", flagCode, "enabled", enabled == null ? "cleared" : enabled.toString()));
    }

    // ------------------------------------------------------------------ credentials and owners

    @Transactional
    public String issueTillToken(long tenantId, String label, long actingAdminId) {
        requireTenant(tenantId);
        String safeLabel = label == null || label.isBlank() ? "Till" : label.trim();
        String token = credentials.issueToken(tenantId, safeLabel);
        audit.record(actingAdminId, "credential.issue", tenantId, Map.of("label", safeLabel));
        return token;
    }

    @Transactional
    public void revokeTillToken(long tenantId, long credentialId, long actingAdminId) {
        // Scoped to the tenant in the path rather than trusting the id alone, so a mistyped
        // credential id cannot revoke a different shop's till.
        Integer owned =
                jdbc.queryForObject(
                        "SELECT count(*) FROM tenant_api_credentials WHERE id = ? AND tenant_id = ?",
                        Integer.class,
                        credentialId,
                        tenantId);
        if (owned == null || owned == 0) {
            throw new RejectedException("That credential does not belong to this shop.");
        }
        credentials.revoke(credentialId);
        audit.record(actingAdminId, "credential.revoke", tenantId, Map.of("credentialId", credentialId));
    }

    @Transactional
    public ConsoleUserService.ConsoleUser addOwner(
            long tenantId, String email, String password, String displayName, long actingAdminId) {
        requireTenant(tenantId);
        ConsoleUserService.ConsoleUser owner =
                consoleUsers.create(tenantId, email, password, displayName);
        audit.record(actingAdminId, "owner.create", tenantId, Map.of("email", owner.email()));
        return owner;
    }

    @Transactional
    public void setOwnerActive(long tenantId, long consoleUserId, boolean active, long actingAdminId) {
        Integer owned =
                jdbc.queryForObject(
                        "SELECT count(*) FROM console_users WHERE id = ? AND tenant_id = ?",
                        Integer.class,
                        consoleUserId,
                        tenantId);
        if (owned == null || owned == 0) {
            throw new RejectedException("That owner does not belong to this shop.");
        }
        jdbc.update("UPDATE console_users SET active = ? WHERE id = ?", active, consoleUserId);
        if (!active) {
            // Same reasoning as suspension: deactivating has to end the sessions that already
            // exist, or it means nothing until one of them expires.
            consoleSessions.signOutAll(consoleUserId, "owner deactivated");
        }
        audit.record(
                actingAdminId,
                active ? "owner.activate" : "owner.deactivate",
                tenantId,
                Map.of("consoleUserId", consoleUserId));
    }

    // ------------------------------------------------------------------ reading

    /**
     * The estate, in one query per shop's worth of detail.
     *
     * <p>{@code lastSyncAt} is the most recent arrival across the aggregates a till pushes, not the
     * credential's {@code last_seen_at}: the difference between them is exactly the interesting
     * failure — a till that authenticates every ten seconds and has sent nothing for a week.
     */
    @Transactional(readOnly = true)
    public List<TenantSummary> listTenants() {
        return jdbc.query(
                """
                SELECT t.id, t.client_uuid, t.name, t.active, t.created_at,
                       l.plan_code, l.expires_at AS licence_expires_at,
                       (SELECT max(received_at) FROM sales s WHERE s.tenant_id = t.id) AS last_sync_at,
                       (SELECT count(*) FROM sales s WHERE s.tenant_id = t.id) AS sale_count,
                       (SELECT count(*) FROM console_users c WHERE c.tenant_id = t.id AND c.active) AS owner_count,
                       (SELECT count(*) FROM tenant_api_credentials c
                         WHERE c.tenant_id = t.id AND c.revoked_at IS NULL) AS terminal_count
                  FROM tenants t
                  LEFT JOIN LATERAL (
                        SELECT p.code AS plan_code, tl.expires_at
                          FROM tenant_licences tl JOIN plans p ON p.id = tl.plan_id
                         WHERE tl.tenant_id = t.id AND tl.starts_at <= now() AND tl.expires_at > now()
                         ORDER BY tl.starts_at DESC, tl.id DESC
                         LIMIT 1
                  ) l ON true
                 ORDER BY t.name
                """,
                (rs, row) ->
                        new TenantSummary(
                                rs.getLong("id"),
                                rs.getObject("client_uuid", UUID.class),
                                rs.getString("name"),
                                rs.getBoolean("active"),
                                rs.getString("plan_code"),
                                rs.getTimestamp("licence_expires_at") == null
                                        ? null
                                        : rs.getTimestamp("licence_expires_at").toInstant(),
                                rs.getTimestamp("last_sync_at") == null
                                        ? null
                                        : rs.getTimestamp("last_sync_at").toInstant(),
                                rs.getLong("sale_count"),
                                rs.getInt("owner_count"),
                                rs.getInt("terminal_count"),
                                rs.getTimestamp("created_at").toInstant()));
    }

    @Transactional(readOnly = true)
    public TenantDetail tenantDetail(long tenantId) {
        TenantSummary summary =
                listTenants().stream()
                        .filter(t -> t.id() == tenantId)
                        .findFirst()
                        .orElseThrow(() -> new RejectedException("There is no shop with that id."));

        List<Terminal> terminals =
                jdbc.query(
                        """
                        SELECT id, label, token_prefix, created_at, last_seen_at, revoked_at
                          FROM tenant_api_credentials WHERE tenant_id = ?
                         ORDER BY revoked_at NULLS FIRST, created_at
                        """,
                        (rs, row) ->
                                new Terminal(
                                        rs.getLong("id"),
                                        rs.getString("label"),
                                        rs.getString("token_prefix"),
                                        rs.getTimestamp("last_seen_at") == null
                                                ? null
                                                : rs.getTimestamp("last_seen_at").toInstant(),
                                        rs.getTimestamp("revoked_at") != null),
                        tenantId);

        List<Owner> owners =
                jdbc.query(
                        """
                        SELECT id, email, display_name, active, last_login_at
                          FROM console_users WHERE tenant_id = ? ORDER BY active DESC, email
                        """,
                        (rs, row) ->
                                new Owner(
                                        rs.getLong("id"),
                                        rs.getString("email"),
                                        rs.getString("display_name"),
                                        rs.getBoolean("active"),
                                        rs.getTimestamp("last_login_at") == null
                                                ? null
                                                : rs.getTimestamp("last_login_at").toInstant()),
                        tenantId);

        return new TenantDetail(
                summary,
                terminals,
                owners,
                licences.history(tenantId),
                licences.overrides(tenantId),
                List.copyOf(licences.effectiveFlags(tenantId)));
    }

    /** Present and readable, or a rejection naming the id. Never a silent no-op on a missing shop. */
    private void requireTenant(long tenantId) {
        try {
            jdbc.queryForObject("SELECT id FROM tenants WHERE id = ?", Long.class, tenantId);
        } catch (EmptyResultDataAccessException e) {
            throw new RejectedException("There is no shop with the id " + tenantId + ".");
        }
    }

    /** Used by the bootstrap path, which has no session to act as. */
    Optional<Long> anyTenantId() {
        return jdbc.queryForList("SELECT id FROM tenants ORDER BY id LIMIT 1", Long.class).stream()
                .findFirst();
    }

    // ------------------------------------------------------------------ shapes

    public record NewTenant(
            long tenantId,
            UUID clientUuid,
            String name,
            String ownerEmail,
            String tillToken,
            Instant licenceExpiresAt) {}

    public record TenantSummary(
            long id,
            UUID clientUuid,
            String name,
            boolean active,
            String planCode,
            Instant licenceExpiresAt,
            Instant lastSyncAt,
            long saleCount,
            int ownerCount,
            int terminalCount,
            Instant createdAt) {

        /**
         * What the estate list colours a row by: suspended, lapsed, or fine.
         *
         * <p>{@code @JsonProperty} is load-bearing. Jackson serialises a record's <em>components</em>
         * and nothing else, so without it this method is invisible on the wire and the screen shows
         * an undefined state for every shop — which is exactly how it failed the first time.
         */
        @com.fasterxml.jackson.annotation.JsonProperty("state")
        public String state() {
            if (!active) {
                return "SUSPENDED";
            }
            return licenceExpiresAt == null ? "UNLICENSED" : "LIVE";
        }
    }

    public record Terminal(
            long id, String label, String tokenPrefix, Instant lastSeenAt, boolean revoked) {}

    public record Owner(
            long id, String email, String displayName, boolean active, Instant lastLoginAt) {}

    public record TenantDetail(
            TenantSummary tenant,
            List<Terminal> terminals,
            List<Owner> owners,
            List<LicenceService.Licence> licenceHistory,
            List<LicenceService.Override> overrides,
            List<String> effectiveFlags) {}
}
