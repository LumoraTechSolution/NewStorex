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
 * Signing in and out of the owner console (M4-05).
 *
 * <p>{@code /login} is the one path {@link TenantAuthFilter} lets through without a credential, for
 * the obvious reason. Everything else here needs the session it hands back.
 */
@RestController
@RequestMapping("/api/console/auth")
@Profile("cloud")
public class ConsoleAuthController {

    private final ConsoleSessionService sessions;
    private final LicenceService licences;

    public ConsoleAuthController(ConsoleSessionService sessions, LicenceService licences) {
        this.sessions = sessions;
        this.licences = licences;
    }

    /**
     * @return 200 with a token, or 401 with nothing that says which half was wrong. A message
     *     distinguishing "no such account" from "wrong password" hands anyone holding a list of
     *     email addresses a way to find out which are real.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<ConsoleSessionService.SignedIn> signedIn =
                sessions.signIn(request.email(), request.password());

        if (signedIn.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginFailed("That email and password do not match an account."));
        }

        ConsoleSessionService.SignedIn session = signedIn.get();
        return ResponseEntity.ok(
                new LoginResponse(
                        session.token(),
                        session.expiresAt(),
                        session.user().email(),
                        session.user().displayName()));
    }

    /**
     * Who the current session belongs to. What the console calls on load to decide where to go.
     *
     * <p>Carries the shop's licence state since M4-08, and that is the half of a decision made in
     * V209. A lapsed licence stops the till syncing but deliberately does <em>not</em> lock the
     * owner out of the console — cutting somebody off from their own takings is taking their data
     * hostage, and it removes the only screen that could tell them what is wrong. That is only
     * defensible if the screen actually tells them, so the state travels here rather than being
     * left as a silence the owner has to interpret from figures that stopped moving.
     */
    @GetMapping("/me")
    public Me me(HttpServletRequest request) {
        AuthenticatedPrincipal principal =
                CloudPrincipals.require(request, AuthenticatedPrincipal.Kind.CONSOLE);
        ConsoleSessionService.ConsoleSession session =
                sessions
                        .verify(bearer(request))
                        .orElseThrow(() -> new IllegalStateException("Session vanished mid-request"));

        Optional<LicenceService.Licence> licence = licences.current(principal.tenantId());
        return new Me(
                session.email(),
                session.displayName(),
                principal.tenantId(),
                session.expiresAt(),
                licence.isPresent(),
                licence.map(l -> l.planCode()).orElse(null),
                licence.map(l -> l.expiresAt()).orElse(null));
    }

    /** Ends this session only. "Sign out everywhere" is a separate act and not built yet. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        AuthenticatedPrincipal principal =
                CloudPrincipals.require(request, AuthenticatedPrincipal.Kind.CONSOLE);
        sessions.signOut(principal.credentialId(), "signed out");
        return ResponseEntity.noContent().build();
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header == null ? null : header.substring("Bearer ".length()).trim();
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record LoginResponse(String token, Instant expiresAt, String email, String displayName) {}

    public record LoginFailed(String detail) {}

    /**
     * @param licensed false means the till has stopped syncing. The console still works and the
     *     figures are still real — they are simply as fresh as the last drain before the lapse.
     */
    public record Me(
            String email,
            String displayName,
            long tenantId,
            Instant expiresAt,
            boolean licensed,
            String planCode,
            Instant licenceExpiresAt) {}
}
