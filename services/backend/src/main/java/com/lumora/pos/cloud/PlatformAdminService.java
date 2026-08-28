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
 * Lumora's own staff accounts (M4-08).
 *
 * <p>A near-twin of {@link ConsoleUserService} — the same BCrypt cost, the same normalisation, the
 * same constant-time-ish "one empty for every reason" — over a different table, and the duplication
 * is on purpose. Merging them would mean one table holding both a shop's owner and the staff who
 * can suspend that shop, distinguished by a column; from then on every tenant-scoped query is one
 * forgotten predicate away from crossing that line, and the failure is a silent success. V208 made
 * exactly this argument for keeping console users out of the till-synced {@code users} table, and
 * it applies with more force one level up.
 */
@Service
@Profile("cloud")
public class PlatformAdminService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    /**
     * Longer than the owner's twelve. This credential can licence, suspend and re-key every shop on
     * the system, so it is worth more to an attacker than any single console account by roughly the
     * number of tenants.
     */
    private static final int MIN_PASSWORD_LENGTH = 16;

    private final JdbcTemplate jdbc;

    public PlatformAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public PlatformAdmin create(String email, String password, String displayName) {
        String normalised = normalise(email);
        if (normalised.indexOf('@') < 1) {
            throw new RejectedException("That does not look like an email address.");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new RejectedException(
                    "A platform password must be at least %d characters."
                            .formatted(MIN_PASSWORD_LENGTH));
        }
        if (displayName == null || displayName.isBlank()) {
            throw new RejectedException("A platform admin needs a name — the audit trail shows it.");
        }
        if (!findByEmail(normalised).isEmpty()) {
            throw new RejectedException("There is already a platform admin for " + normalised + ".");
        }

        long id =
                jdbc.queryForObject(
                        """
                        INSERT INTO platform_admins (email, password_hash, display_name)
                        VALUES (?, ?, ?) RETURNING id
                        """,
                        Long.class,
                        normalised,
                        encoder.encode(password),
                        displayName.trim());
        return new PlatformAdmin(id, normalised, displayName.trim(), true);
    }

    /**
     * Empty for a wrong password, an unknown email and a deactivated account alike, with the dummy
     * hash verified on the unknown-email path so the two cost the same. The reasoning is
     * {@link ConsoleUserService#authenticate}'s, and matters more here: the set of Lumora staff
     * emails is small and guessable, and confirming one exists is a real step towards taking it.
     */
    @Transactional(readOnly = true)
    public Optional<PlatformAdmin> authenticate(String email, String password) {
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
        if (!candidate.active()) {
            return Optional.empty();
        }
        return Optional.of(candidate.toAdmin());
    }

    /**
     * Changes a password, and requires the current one to do it.
     *
     * <p>Re-checking the old password matters more than it looks: a platform session lasts a
     * working day, so without this, a laptop left unlocked for five minutes is a permanent account
     * takeover rather than a session that expires this evening.
     *
     * <p>Revoking the account's other sessions is the other half of that, and belongs to whoever
     * owns sessions — {@link PlatformAuthController} does it immediately after this returns, because
     * "change my password" is what somebody does when they think a session has been taken, and
     * leaving the rest live answers the wrong half of the question.
     */
    @Transactional
    public void changePassword(long adminId, String currentPassword, String newPassword) {
        String storedHash;
        try {
            storedHash =
                    jdbc.queryForObject(
                            "SELECT password_hash FROM platform_admins WHERE id = ? AND active",
                            String.class,
                            adminId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new RejectedException("That account is no longer active.");
        }
        if (currentPassword == null || !encoder.matches(currentPassword, storedHash)) {
            throw new RejectedException("The current password is not right.");
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new RejectedException(
                    "A platform password must be at least %d characters."
                            .formatted(MIN_PASSWORD_LENGTH));
        }
        if (newPassword.equals(currentPassword)) {
            throw new RejectedException("That is the password you already have.");
        }
        jdbc.update(
                "UPDATE platform_admins SET password_hash = ? WHERE id = ?",
                encoder.encode(newPassword),
                adminId);
    }

    @Transactional
    public void recordLogin(long adminId) {
        jdbc.update("UPDATE platform_admins SET last_login_at = now() WHERE id = ?", adminId);
    }

    /** Whether anybody can sign in at all. What {@link PlatformBootstrap} asks at startup. */
    @Transactional(readOnly = true)
    public boolean noneExist() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM platform_admins", Long.class);
        return count == null || count == 0;
    }

    @Transactional(readOnly = true)
    public List<PlatformAdmin> list() {
        return jdbc.query(
                """
                SELECT id, email, display_name, active FROM platform_admins
                 ORDER BY active DESC, email
                """,
                (rs, row) ->
                        new PlatformAdmin(
                                rs.getLong("id"),
                                rs.getString("email"),
                                rs.getString("display_name"),
                                rs.getBoolean("active")));
    }

    private List<Candidate> findByEmail(String normalisedEmail) {
        return jdbc.query(
                """
                SELECT id, email, display_name, password_hash, active
                  FROM platform_admins WHERE email = ?
                """,
                (rs, row) ->
                        new Candidate(
                                rs.getLong("id"),
                                rs.getString("email"),
                                rs.getString("display_name"),
                                rs.getString("password_hash"),
                                rs.getBoolean("active")),
                normalisedEmail);
    }

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** See {@link ConsoleUserService} — a real hash of a value nobody knows. */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private record Candidate(
            long id, String email, String displayName, String passwordHash, boolean active) {

        PlatformAdmin toAdmin() {
            return new PlatformAdmin(id, email, displayName, active);
        }
    }

    public record PlatformAdmin(long id, String email, String displayName, boolean active) {}
}
