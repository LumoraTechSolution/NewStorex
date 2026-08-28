package com.lumora.pos.cloud;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns the bearer token on a cloud request into the tenant it may touch (M4-01, extended by
 * M4-05).
 *
 * <p>A filter rather than a check inside each controller, because the failure mode of the second is
 * silence: a new cloud endpoint would simply be unauthenticated and nothing would say so. Registered
 * across {@code /api/*}, this denies by default — the next endpoint is protected before it is
 * written.
 *
 * <p>Three credential kinds now reach it. A till presents V205's machine token; an owner presents
 * V208's console session; Lumora staff present V209's platform session. The first two resolve to a
 * tenant and the third deliberately does not, and {@link AuthenticatedPrincipal} records which
 * arrived so an endpoint can require one — see that class on why they are not interchangeable.
 *
 * <p>Deliberately not {@code spring-boot-starter-security}. The starter installs a filter chain
 * across the whole application, and the same jar runs the shop PC's loopback API under the {@code
 * desktop} profile, where M3-09 already settled authentication. See the note in {@code pom.xml}.
 */
public class TenantAuthFilter extends OncePerRequestFilter {

    /** Where the authenticated principal is left for the controller. */
    public static final String PRINCIPAL_ATTRIBUTE = TenantAuthFilter.class.getName() + ".principal";

    /**
     * Paths that must work without a credential, because they are how one is obtained.
     *
     * <p>An explicit allowlist of exact paths rather than a prefix. A prefix exemption on
     * {@code /api/console/auth} would also exempt whatever gets added under it later, which is how
     * an unauthenticated route appears without anyone deciding to add one.
     */
    private static final Set<String> ANONYMOUS_PATHS =
            Set.of("/api/console/auth/login", "/api/platform/auth/login");

    /**
     * Paths a till may reach with a valid credential whose <b>licence has lapsed</b> (M4-09).
     *
     * <p>Nothing here is unauthenticated: the token is still checked, the credential must still be
     * unrevoked and the tenant must still be active. The single relaxation is the licence window,
     * and it exists because of a circularity — the lapsed shop is the one that most needs to be
     * told it has lapsed, and the strict check makes exactly that shop unable to ask.
     *
     * <p>An allowlist of exact paths, for the same reason {@code ANONYMOUS_PATHS} is one: a prefix
     * would silently extend the relaxation to whatever gets added underneath it later. The one
     * entry here answers with the licence state and nothing else — no ledger, no takings — so an
     * unlicensed shop learns when its licence ended and gains no other access at all.
     */
    private static final Set<String> LAPSED_TILL_PATHS = Set.of("/api/sync/entitlement");

    private static final Logger log = LoggerFactory.getLogger(TenantAuthFilter.class);
    private static final String BEARER = "Bearer ";

    private final TenantCredentialService tillCredentials;
    private final ConsoleSessionService consoleSessions;
    private final PlatformSessionService platformSessions;

    public TenantAuthFilter(
            TenantCredentialService tillCredentials,
            ConsoleSessionService consoleSessions,
            PlatformSessionService platformSessions) {
        this.tillCredentials = tillCredentials;
        this.consoleSessions = consoleSessions;
        this.platformSessions = platformSessions;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // A preflight is the browser asking whether it may send a request, and it carries no
        // Authorization header by design — demanding one here would answer 401 to the question
        // "may I authenticate?", which no client can recover from. CorsFilter is ordered ahead of
        // this and normally answers preflights itself; this is the belt to that pair of braces,
        // because the failure if the ordering ever changes is total and looks like "Failed to
        // fetch" rather than like an auth problem.
        if (CorsUtils.isPreFlightRequest(request)) {
            chain.doFilter(request, response);
            return;
        }

        if (ANONYMOUS_PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        Optional<String> token = bearerToken(request);
        Optional<AuthenticatedPrincipal> principal = token.flatMap(this::resolve);

        // Second chance, and only for the entitlement feed: a till whose licence ran out fails the
        // strict resolve above and is exactly the caller this endpoint is for. Deliberately tried
        // after the strict path rather than instead of it, so a licensed till takes the normal
        // route and this costs a lapsed one an extra probe it is in no hurry over.
        if (principal.isEmpty() && LAPSED_TILL_PATHS.contains(request.getRequestURI())) {
            principal =
                    token.flatMap(tillCredentials::authenticateEvenIfLapsed)
                            .map(
                                    t ->
                                            AuthenticatedPrincipal.ofTenant(
                                                    AuthenticatedPrincipal.Kind.TILL,
                                                    t.tenantId(),
                                                    t.credentialId(),
                                                    "till credential " + t.credentialId()));
        }

        if (principal.isEmpty()) {
            // One response for a missing token, an unknown one, a revoked one, an expired session
            // and a suspended tenant. Distinguishing them would tell an unauthenticated caller
            // which of those exist.
            log.warn("Rejected unauthenticated {} {}", request.getMethod(), request.getRequestURI());
            unauthorized(response);
            return;
        }

        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
        try {
            chain.doFilter(request, response);
        } finally {
            // After the response, so a slow write never delays the request. Best-effort: recording
            // that a credential was used must not fail a request that already succeeded.
            try {
                touch(principal.get());
            } catch (RuntimeException e) {
                log.warn("Could not record last_seen_at for {}", principal.get().label(), e);
            }
        }
    }

    /**
     * Tries all three credential tables.
     *
     * <p>Order is not significant and the prefixes are not consulted: a token's prefix is chosen by
     * whoever sends it, so branching on it would let a caller steer which table is searched. Three
     * unique-index probes is the honest cost of having three kinds of credential, and the cost is
     * paid only by a request that is failing anyway — the first probe hits for a valid token of the
     * commonest kind.
     */
    private Optional<AuthenticatedPrincipal> resolve(String token) {
        Optional<AuthenticatedPrincipal> till =
                tillCredentials
                        .authenticate(token)
                        .map(
                                t ->
                                        AuthenticatedPrincipal.ofTenant(
                                                AuthenticatedPrincipal.Kind.TILL,
                                                t.tenantId(),
                                                t.credentialId(),
                                                "till credential " + t.credentialId()));
        if (till.isPresent()) {
            return till;
        }

        Optional<AuthenticatedPrincipal> console =
                consoleSessions
                        .verify(token)
                        .map(
                                s ->
                                        AuthenticatedPrincipal.ofTenant(
                                                AuthenticatedPrincipal.Kind.CONSOLE,
                                                s.tenantId(),
                                                s.sessionId(),
                                                s.email()));
        if (console.isPresent()) {
            return console;
        }

        return platformSessions
                .verify(token)
                .map(s -> AuthenticatedPrincipal.ofPlatform(s.sessionId(), s.email()));
    }

    private void touch(AuthenticatedPrincipal principal) {
        switch (principal.kind()) {
            case TILL -> tillCredentials.touch(principal.credentialId());
            case CONSOLE -> consoleSessions.touch(principal.credentialId());
            case PLATFORM -> platformSessions.touch(principal.credentialId());
        }
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        return Optional.of(header.substring(BEARER.length()).trim());
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // Required of a 401 by RFC 9110, and the only part of the response that tells a client what
        // it was supposed to present. Carries no realm: a realm here would name the tenant, which
        // is the one thing an unauthenticated caller must not learn.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter()
                .write(
                        """
                        {"type":"about:blank","title":"Unauthorized",\
                        "status":401,"detail":"A valid tenant API token is required."}\
                        """);
    }
}
