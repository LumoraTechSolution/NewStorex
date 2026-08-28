package com.lumora.pos.cloud;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signs a member of Lumora staff in (M4-08).
 *
 * <p>Opaque, hashed and revocable, exactly like V208's console sessions and for the same reason —
 * the verifier is always beside the database, so a signature buys nothing and revocation is the
 * whole point.
 *
 * <p>What differs is the clock. The default TTL here is <b>eight hours</b> against the owner's
 * seven days, because the two sessions are worth wildly different amounts: an owner's leaks one
 * shop's takings, a staff session can suspend every shop on the system and mint an owner login for
 * any of them. A working day is long enough that nobody is re-authenticating between tasks and
 * short enough that a laptop left on a train is not a standing key.
 */
@Service
@Profile("cloud")
public class PlatformSessionService {

    private static final int TOKEN_BYTES = 32;

    /** Distinct from {@code lum_} and {@code lums_} so a leaked token is identifiable on sight. */
    private static final String TOKEN_PREFIX = "lump_";

    private final JdbcTemplate jdbc;
    private final PlatformAdminService admins;
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;

    public PlatformSessionService(
            JdbcTemplate jdbc,
            PlatformAdminService admins,
            @Value("${lumora.platform.session-ttl:PT8H}") Duration ttl) {
        this.jdbc = jdbc;
        this.admins = admins;
        this.ttl = ttl;
    }

    @Transactional
    public Optional<SignedIn> signIn(String email, String password) {
        Optional<PlatformAdminService.PlatformAdmin> found = admins.authenticate(email, password);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        PlatformAdminService.PlatformAdmin admin = found.get();
        byte[] entropy = new byte[TOKEN_BYTES];
        random.nextBytes(entropy);
        String token =
                TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        Instant expiresAt = Instant.now().plus(ttl);

        jdbc.update(
                """
                INSERT INTO platform_sessions (token_hash, platform_admin_id, expires_at)
                VALUES (?, ?, ?)
                """,
                TenantCredentialService.hash(token),
                admin.id(),
                java.sql.Timestamp.from(expiresAt));

        admins.recordLogin(admin.id());
        return Optional.of(new SignedIn(token, expiresAt, admin));
    }

    /**
     * The staff member a token belongs to, or empty if it is not a live session.
     *
     * <p>{@code active} is re-read on every request rather than trusted from sign-in: revoking
     * somebody's access has to mean their next request fails, not their next week's. That is the
     * single thing this design exists to buy over a JWT.
     */
    @Transactional(readOnly = true)
    public Optional<PlatformSession> verify(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    jdbc.queryForObject(
                            """
                            SELECT s.id, s.platform_admin_id, s.expires_at, a.email, a.display_name
                              FROM platform_sessions s
                              JOIN platform_admins a ON a.id = s.platform_admin_id
                             WHERE s.token_hash = ?
                               AND s.revoked_at IS NULL
                               AND s.expires_at > now()
                               AND a.active
                            """,
                            (rs, row) ->
                                    new PlatformSession(
                                            rs.getLong("id"),
                                            rs.getLong("platform_admin_id"),
                                            rs.getString("email"),
                                            rs.getString("display_name"),
                                            rs.getTimestamp("expires_at").toInstant()),
                            TenantCredentialService.hash(presentedToken)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void touch(long sessionId) {
        jdbc.update("UPDATE platform_sessions SET last_seen_at = now() WHERE id = ?", sessionId);
    }

    /** The admin behind a live session id, for stamping the audit trail. */
    @Transactional(readOnly = true)
    public Optional<Long> adminIdForSession(long sessionId) {
        try {
            return Optional.ofNullable(
                    jdbc.queryForObject(
                            "SELECT platform_admin_id FROM platform_sessions WHERE id = ?",
                            Long.class,
                            sessionId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Transactional
    public void signOut(long sessionId, String why) {
        jdbc.update(
                """
                UPDATE platform_sessions SET revoked_at = now(), revoked_why = ?
                 WHERE id = ? AND revoked_at IS NULL
                """,
                why,
                sessionId);
    }

    /** Every session for one account — what a password change implies. */
    @Transactional
    public int signOutAll(long adminId, String why) {
        return jdbc.update(
                """
                UPDATE platform_sessions SET revoked_at = now(), revoked_why = ?
                 WHERE platform_admin_id = ? AND revoked_at IS NULL
                """,
                why,
                adminId);
    }

    public record SignedIn(
            String token, Instant expiresAt, PlatformAdminService.PlatformAdmin admin) {}

    public record PlatformSession(
            long sessionId, long adminId, String email, String displayName, Instant expiresAt) {}
}
