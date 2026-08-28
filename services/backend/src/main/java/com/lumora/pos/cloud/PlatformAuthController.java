package com.lumora.pos.cloud;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lumora staff signing in and out (M4-08).
 *
 * <p>A separate endpoint from the owner's, not a role check inside it. One login route serving both
 * would mean the response tells an attacker which kind of account an email belongs to, and it would
 * put the code that decides "is this person staff?" on the hot path of every owner's sign-in. Two
 * routes over two tables keeps the question from being asked at all.
 */
@RestController
@RequestMapping("/api/platform/auth")
@Profile("cloud")
public class PlatformAuthController {

    private final PlatformSessionService sessions;
    private final PlatformAdminService admins;

    public PlatformAuthController(PlatformSessionService sessions, PlatformAdminService admins) {
        this.sessions = sessions;
        this.admins = admins;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<PlatformSessionService.SignedIn> signedIn =
                sessions.signIn(request.email(), request.password());

        if (signedIn.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginFailed("That email and password do not match an account."));
        }

        PlatformSessionService.SignedIn session = signedIn.get();
        return ResponseEntity.ok(
                new LoginResponse(
                        session.token(),
                        session.expiresAt(),
                        session.admin().email(),
                        session.admin().displayName()));
    }

    @GetMapping("/me")
    public Me me(HttpServletRequest request) {
        AuthenticatedPrincipal principal =
                CloudPrincipals.require(request, AuthenticatedPrincipal.Kind.PLATFORM);
        PlatformSessionService.PlatformSession session =
                sessions
                        .verify(bearer(request))
                        .orElseThrow(() -> new IllegalStateException("Session vanished mid-request"));
        return new Me(session.email(), session.displayName(), session.expiresAt(), principal.label());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        AuthenticatedPrincipal principal =
                CloudPrincipals.require(request, AuthenticatedPrincipal.Kind.PLATFORM);
        sessions.signOut(principal.credentialId(), "signed out");
        return ResponseEntity.noContent().build();
    }

    /**
     * Changes the signed-in admin's own password. What the bootstrap account is told to do first.
     *
     * <p>Deliberately only their own — there is no endpoint for setting somebody else's, because a
     * platform admin who can silently re-password a colleague can act as them, and the audit trail
     * would then name the wrong person for everything that followed.
     *
     * <p>Ends <b>every</b> session for the account, this one included, so the caller has to sign in
     * again with the new password. Sparing the current session would be friendlier and would mean
     * that somebody who changed their password because they believed a session had been stolen kept
     * one live token afterwards — and if the belief was right, the wrong party might be holding it.
     * A second sign-in is a small price for the guarantee that nothing survives the change.
     */
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(
            HttpServletRequest request, @Valid @RequestBody ChangePasswordRequest body) {
        AuthenticatedPrincipal principal =
                CloudPrincipals.require(request, AuthenticatedPrincipal.Kind.PLATFORM);
        long adminId =
                sessions
                        .adminIdForSession(principal.credentialId())
                        .orElseThrow(() -> new IllegalStateException("Session vanished mid-request"));

        admins.changePassword(adminId, body.currentPassword(), body.newPassword());
        sessions.signOutAll(adminId, "password changed");
        return ResponseEntity.noContent().build();
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header == null ? null : header.substring("Bearer ".length()).trim();
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record LoginResponse(String token, Instant expiresAt, String email, String displayName) {}

    public record LoginFailed(String detail) {}

    public record Me(String email, String displayName, Instant expiresAt, String label) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword, @NotBlank String newPassword) {}
}
