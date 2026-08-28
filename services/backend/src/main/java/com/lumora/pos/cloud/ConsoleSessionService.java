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
 * Signs an owner in and hands back a bearer token (M4-05).
 *
 * <p>The token is opaque — 256 random bits, stored as a hash — rather than a JWT like the till's
 * M3-09 sessions. V208's header has the argument: the till needed a self-describing token because
 * its verifier works with the cable unplugged, and here the verifier is always beside the database.
 * What that buys is revocation, which is the one thing an owner whose phone was stolen actually
 * needs.
 */
@Service
@Profile("cloud")
public class ConsoleSessionService {

    private static final int TOKEN_BYTES = 32;

    /** Marks a console session apart from V205's {@code lum_} till token at a glance. */
    private static final String TOKEN_PREFIX = "lums_";

    private final JdbcTemplate jdbc;
    private final ConsoleUserService users;
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;

    public ConsoleSessionService(
            JdbcTemplate jdbc,
            ConsoleUserService users,
            @Value("${lumora.console.session-ttl:P7D}") Duration ttl) {
        this.jdbc = jdbc;
        this.users = users;
        this.ttl = ttl;
    }

    /**
     * @return the token, or empty if the credentials were not good. One empty for every reason —
     *     see {@link ConsoleUserService#authenticate}.
     */
    @Transactional
    public Optional<SignedIn> signIn(String email, String password) {
        Optional<ConsoleUserService.ConsoleUser> user = users.authenticate(email, password);
        if (user.isEmpty()) {
            return Optional.empty();
        }

        ConsoleUserService.ConsoleUser owner = user.get();
        byte[] entropy = new byte[TOKEN_BYTES];
        random.nextBytes(entropy);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        Instant expiresAt = Instant.now().plus(ttl);

        jdbc.update(
                """
                INSERT INTO console_sessions (token_hash, console_user_id, tenant_id, expires_at)
                VALUES (?, ?, ?, ?)
                """,
                TenantCredentialService.hash(token),
                owner.id(),
                owner.tenantId(),
                java.sql.Timestamp.from(expiresAt));

        users.recordLogin(owner.id());
        return Optional.of(new SignedIn(token, expiresAt, owner));
    }

    /**
     * The tenant a session token is confined to, or empty if it is not a live one.
     *
     * <p>The user's own {@code active} flag and the tenant's are re-read here rather than trusted
     * from sign-in time. A session that outlived the account it belongs to is exactly the case this
     * design exists to close: deactivate somebody and the next request fails, not the next week's.
     */
    @Transactional(readOnly = true)
    public Optional<ConsoleSession> verify(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    jdbc.queryForObject(
                            """
                            SELECT s.id, s.console_user_id, s.tenant_id, s.expires_at,
                                   c.email, c.display_name
                              FROM console_sessions s
                              JOIN console_users c ON c.id = s.console_user_id
                              JOIN tenants t ON t.id = s.tenant_id
                             WHERE s.token_hash = ?
                               AND s.revoked_at IS NULL
                               AND s.expires_at > now()
                               AND c.active
                               AND t.active
                            """,
                            (rs, row) ->
                                    new ConsoleSession(
                                            rs.getLong("id"),
                                            rs.getLong("console_user_id"),
                                            rs.getLong("tenant_id"),
                                            rs.getString("email"),
                                            rs.getString("display_name"),
                                            rs.getTimestamp("expires_at").toInstant()),
                            TenantCredentialService.hash(presentedToken)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Bumped after a verified request, so a shop can see which sessions are actually in use. */
    public void touch(long sessionId) {
        jdbc.update("UPDATE console_sessions SET last_seen_at = now() WHERE id = ?", sessionId);
    }

    /** Signing out. Revoked, not deleted — "when did that session end" is an audit question. */
    @Transactional
    public void signOut(long sessionId, String why) {
        jdbc.update(
                """
                UPDATE console_sessions SET revoked_at = now(), revoked_why = ?
                 WHERE id = ? AND revoked_at IS NULL
                """,
                why,
                sessionId);
    }

    /** Every session for one account — "sign out everywhere", and what a password change implies. */
    @Transactional
    public int signOutAll(long consoleUserId, String why) {
        return jdbc.update(
                """
                UPDATE console_sessions SET revoked_at = now(), revoked_why = ?
                 WHERE console_user_id = ? AND revoked_at IS NULL
                """,
                why,
                consoleUserId);
    }

    public record SignedIn(
            String token, Instant expiresAt, ConsoleUserService.ConsoleUser user) {}

    public record ConsoleSession(
            long sessionId,
            long consoleUserId,
            long tenantId,
            String email,
            String displayName,
            Instant expiresAt) {}
}
