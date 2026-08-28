package com.lumora.pos.cloud;

import com.lumora.pos.web.RejectedException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's account on the console (M4-05).
 *
 * <p>Separate from the {@code users} a till syncs up, and V208's header explains why at length: a
 * shop PC must not be able to grant or revoke console access by pushing an outbox row.
 */
@Service
@Profile("cloud")
public class ConsoleUserService {

    /**
     * Cost 10, matching the till's PINs.
     *
     * <p>Unlike V205's machine tokens this hashes something a person chose, so the work factor is
     * doing real work rather than adding latency for nothing.
     */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    /**
     * Long enough that a password is not the weak link, and stated as a minimum rather than a
     * composition rule. Character-class requirements push people towards {@code Password1!} and
     * away from length, which is the only property that reliably helps.
     */
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final JdbcTemplate jdbc;

    public ConsoleUserService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates an owner account. The seam M4-08's super-admin screen will call.
     *
     * @throws RejectedException if the email is taken, malformed, or the password is too short
     */
    @Transactional
    public ConsoleUser create(long tenantId, String email, String password, String displayName) {
        String normalised = normalise(email);
        if (normalised.indexOf('@') < 1) {
            throw new RejectedException("That does not look like an email address.");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new RejectedException(
                    "A console password must be at least %d characters.".formatted(MIN_PASSWORD_LENGTH));
        }
        if (!findByEmail(normalised).isEmpty()) {
            throw new RejectedException("There is already an account for " + normalised + ".");
        }

        long id =
                jdbc.queryForObject(
                        """
                        INSERT INTO console_users (tenant_id, email, password_hash, display_name)
                        VALUES (?, ?, ?, ?) RETURNING id
                        """,
                        Long.class,
                        tenantId,
                        normalised,
                        encoder.encode(password),
                        displayName);
        return new ConsoleUser(id, tenantId, normalised, displayName, true);
    }

    /**
     * Checks an email and password.
     *
     * <p>Empty for a wrong password, an unknown email, a deactivated account and a suspended
     * tenant alike — the caller turns all four into one message. Distinguishing them tells someone
     * holding a list of emails which ones are real, which is the first step of every credential
     * stuffing run.
     *
     * <p>The hash is verified even when no account was found, against a fixed dummy. Otherwise the
     * unknown-email path returns in microseconds and the wrong-password path takes BCrypt's
     * hundred milliseconds, and the difference is a membership oracle anyone can measure.
     */
    @Transactional(readOnly = true)
    public Optional<ConsoleUser> authenticate(String email, String password) {
        if (password == null) {
            return Optional.empty();
        }
        List<Candidate> found = findByEmail(normalise(email));
        if (found.isEmpty()) {
            encoder.matches(password, DUMMY_HASH);
            return Optional.empty();
        }

        Candidate candidate = found.get(0);
        if (!encoder.matches(password, candidate.passwordHash())) {
            return Optional.empty();
        }
        if (!candidate.active() || !candidate.tenantActive()) {
            return Optional.empty();
        }
        return Optional.of(candidate.toUser());
    }

    @Transactional
    public void recordLogin(long consoleUserId) {
        jdbc.update("UPDATE console_users SET last_login_at = now() WHERE id = ?", consoleUserId);
    }

    private List<Candidate> findByEmail(String normalisedEmail) {
        return jdbc.query(
                """
                SELECT c.id, c.tenant_id, c.email, c.display_name, c.password_hash,
                       c.active, t.active AS tenant_active
                  FROM console_users c
                  JOIN tenants t ON t.id = c.tenant_id
                 WHERE c.email = ?
                """,
                (rs, row) ->
                        new Candidate(
                                rs.getLong("id"),
                                rs.getLong("tenant_id"),
                                rs.getString("email"),
                                rs.getString("display_name"),
                                rs.getString("password_hash"),
                                rs.getBoolean("active"),
                                rs.getBoolean("tenant_active")),
                normalisedEmail);
    }

    /** Lower-cased and trimmed, which is also the form the unique index enforces. */
    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * A real BCrypt hash of a value nobody knows, so the no-such-user path costs the same as the
     * wrong-password path. Generated once and pinned — computing one at startup would be the same
     * thing with a slower boot.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private record Candidate(
            long id,
            long tenantId,
            String email,
            String displayName,
            String passwordHash,
            boolean active,
            boolean tenantActive) {

        ConsoleUser toUser() {
            return new ConsoleUser(id, tenantId, email, displayName, active);
        }
    }

    public record ConsoleUser(
            long id, long tenantId, String email, String displayName, boolean active) {}
}
