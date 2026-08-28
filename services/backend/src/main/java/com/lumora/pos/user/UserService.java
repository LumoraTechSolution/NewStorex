package com.lumora.pos.user;

import com.lumora.pos.outbox.OutboxWriter;
import com.lumora.pos.web.RejectedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is acting, and whether they are allowed to (M3-08).
 *
 * <h2>The shape of the API is the gate</h2>
 *
 * {@link #authorise} does not return a boolean. It returns the operator or it throws. A method
 * returning true/false invites {@code if (allowed) { … }} with no else, and the missing else is
 * exactly how an authorisation gate ends up not gating anything — the same reasoning that shaped
 * {@code TenantSettingsService.verifyManagerPin}, which this replaces.
 *
 * <p>It also returns the {@link Operator} rather than just permitting the call, because every
 * gated action has an audit column to fill in. Handing back who passed the gate means the caller
 * cannot record a different person than the one who authorised, and does not have to look them up
 * a second time to find out.
 *
 * <h2>Guessing is rate-limited, and never locked out</h2>
 *
 * M3-08 shipped with BCrypt's cost as the only thing rating a guess — about a quarter of an hour
 * for a four-digit PIN — and said so rather than hiding it. {@link PinAttemptGuard} (M3-13) closes
 * that: consecutive failures earn an escalating cooling-off period, keyed on the code that was
 * typed rather than on a user, so codes nobody holds are throttled identically and the throttle
 * cannot become an oracle for which codes are real.
 *
 * <p>It is a cool-off and never a lock. A lock somebody has to be released from is a denial of
 * service any passer-by can trigger against the owner's own code, and on a till that is the worse
 * failure of the two.
 */
@Service
public class UserService {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private final OutboxWriter outbox;
    private final PinAttemptGuard attempts;

    public UserService(JdbcTemplate jdbc, OutboxWriter outbox, PinAttemptGuard attempts) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.attempts = attempts;
    }

    /**
     * A user who has just proved who they are.
     *
     * <p>Deliberately not the full row: no hash, no timestamps. This is what gets passed around a
     * request and written into audit columns, and a credential hash has no business travelling
     * with it.
     */
    public record Operator(long id, String code, String displayName, Role role) {
        public boolean can(Permission permission) {
            return role.can(permission);
        }
    }

    /** A user as the back office lists them. */
    public record UserRow(
            long id, UUID clientUuid, String code, String displayName, Role role, boolean active) {}

    // ------------------------------------------------------------------ authentication

    /**
     * Identifies by code, authenticates by PIN, then checks the permission. Throws unless all
     * three succeed.
     *
     * <p>The three failures deliberately produce two different messages. A wrong code and a wrong
     * PIN both say "not recognised", because saying which half was wrong tells an attacker that a
     * code exists and turns one unknown into two separate searches. A correct login without the
     * permission says so plainly — at that point the person is known, and telling a cashier that
     * they personally may not authorise refunds is the whole point of the message.
     */
    @Transactional(readOnly = true)
    public Operator authorise(long tenantId, String code, String pin, Permission permission) {
        Operator operator = authenticate(tenantId, code, pin);
        if (!operator.can(permission)) {
            throw new RejectedException(
                    operator.displayName()
                            + " is a "
                            + operator.role().name().toLowerCase(Locale.ROOT)
                            + " and cannot "
                            + permission.describe()
                            + ". Ask someone with a higher role to authorise this.");
        }
        return operator;
    }

    /** Identifies and authenticates, without asking what for. Throws unless the PIN is right. */
    @Transactional(readOnly = true)
    public Operator authenticate(long tenantId, String code, String pin) {
        if (code == null || code.isBlank() || pin == null || pin.isBlank()) {
            throw new RejectedException("A user code and PIN are required");
        }

        // Before the comparison, not after: a code still cooling off costs nothing to refuse, and
        // refusing it here means a real code and an invented one behave identically (M3-13).
        attempts.requireNotCoolingOff(tenantId, code);

        List<Credential> found =
                jdbc.query(
                        """
                        SELECT id, code, display_name, role, pin_hash
                          FROM users
                         WHERE tenant_id = ? AND code = ? AND active
                        """,
                        (rs, row) ->
                                new Credential(
                                        rs.getLong("id"),
                                        rs.getString("code"),
                                        rs.getString("display_name"),
                                        rs.getString("role"),
                                        rs.getString("pin_hash")),
                        tenantId,
                        normaliseCode(code));

        // The BCrypt comparison runs even when no such user exists, against a hash that cannot
        // match. Skipping it would make "no such code" measurably faster than "wrong PIN", which
        // is a timing oracle for which codes are real.
        String hash = found.isEmpty() ? UNSATISFIABLE_HASH : found.get(0).pinHash();
        boolean matches = encoder.matches(pin, hash);

        if (found.isEmpty() || !matches) {
            // Recorded in its own transaction, because this line throws and a rollback would undo
            // the count — a throttle that can only ever reach one. See PinAttemptGuard.
            attempts.recordFailure(tenantId, code);
            // The message does not change on the attempt that trips the throttle. Saying "that was
            // your fifth" would tell an attacker exactly where the counter is; the wait applies to
            // the next attempt, which is where it says nothing new.
            throw new RejectedException("That user code and PIN were not recognised");
        }

        attempts.recordSuccess(tenantId, code);
        Credential credential = found.get(0);
        return new Operator(
                credential.id(),
                credential.code(),
                credential.displayName(),
                Role.of(credential.role()));
    }

    /**
     * BCrypt-shaped and the hash of nothing, so {@code matches} against it is always false and
     * always costs what a real comparison costs. The same string V109 gives a tenant that reached
     * M3-08 without ever setting a manager PIN.
     */
    private static final String UNSATISFIABLE_HASH =
            "$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";

    private record Credential(
            long id, String code, String displayName, String role, String pinHash) {}

    // -------------------------------------------------------------------- administration

    @Transactional(readOnly = true)
    public List<UserRow> list(long tenantId) {
        return jdbc.query(
                """
                SELECT id, client_uuid, code, display_name, role, active
                  FROM users
                 WHERE tenant_id = ?
                 ORDER BY active DESC, code
                """,
                (rs, row) ->
                        new UserRow(
                                rs.getLong("id"),
                                rs.getObject("client_uuid", UUID.class),
                                rs.getString("code"),
                                rs.getString("display_name"),
                                Role.of(rs.getString("role")),
                                rs.getBoolean("active")),
                tenantId);
    }

    @Transactional(readOnly = true)
    public UserRow byId(long tenantId, long userId) {
        try {
            return jdbc.queryForObject(
                    """
                    SELECT id, client_uuid, code, display_name, role, active
                      FROM users WHERE tenant_id = ? AND id = ?
                    """,
                    (rs, row) ->
                            new UserRow(
                                    rs.getLong("id"),
                                    rs.getObject("client_uuid", UUID.class),
                                    rs.getString("code"),
                                    rs.getString("display_name"),
                                    Role.of(rs.getString("role")),
                                    rs.getBoolean("active")),
                    tenantId,
                    userId);
        } catch (EmptyResultDataAccessException e) {
            throw new RejectedException("No such user");
        }
    }

    /**
     * Creates a user. The caller must already have passed a {@link Permission#MANAGE_USERS} gate.
     *
     * <p>{@code clientUuid} comes from the caller so that creating the same user twice — a retried
     * request, a double-pressed key — is one user, on the same terms as every other aggregate in
     * this system.
     */
    @Transactional
    public UserRow create(
            long tenantId, UUID clientUuid, String code, String displayName, Role role, String pin) {
        String normalised = normaliseCode(code);
        requireSaneName(displayName);
        requireSanePin(pin);

        List<Long> existing =
                jdbc.queryForList(
                        "SELECT id FROM users WHERE tenant_id = ? AND code = ?",
                        Long.class,
                        tenantId,
                        normalised);
        if (!existing.isEmpty()) {
            throw new RejectedException(
                    "A user with the code " + normalised + " already exists in this shop");
        }

        Long id =
                jdbc.queryForObject(
                        """
                        INSERT INTO users (client_uuid, tenant_id, code, display_name, role, pin_hash)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (client_uuid) DO UPDATE SET client_uuid = excluded.client_uuid
                        RETURNING id
                        """,
                        Long.class,
                        clientUuid,
                        tenantId,
                        normalised,
                        displayName.trim(),
                        role.name(),
                        encoder.encode(pin));
        UserRow created = byId(tenantId, id);
        enqueue(tenantId, created);
        return created;
    }

    /** Renames a user or changes their role. Never touches the PIN — see {@link #setPin}. */
    @Transactional
    public UserRow update(long tenantId, long userId, String displayName, Role role) {
        requireSaneName(displayName);
        UserRow before = byId(tenantId, userId);
        refuseSelfDemotionToLastOwner(tenantId, before, role);

        jdbc.update(
                "UPDATE users SET display_name = ?, role = ? WHERE tenant_id = ? AND id = ?",
                displayName.trim(),
                role.name(),
                tenantId,
                userId);
        UserRow updated = byId(tenantId, userId);
        enqueue(tenantId, updated);
        return updated;
    }

    /**
     * Sets or replaces a user's PIN.
     *
     * <p>Separate from {@link #update} because it is a different act with a different risk. An
     * endpoint that takes a whole user object and quietly re-hashes whatever is in the {@code pin}
     * field is one forgotten field away from resetting somebody's PIN during a rename.
     */
    @Transactional
    public void setPin(long tenantId, long userId, String pin) {
        // No outbox row (M3-12), and that is the point rather than an omission: the cloud holds no
        // credential, so nothing it stores about this person has changed. Enqueueing here would
        // ship a row whose only difference is invisible to the receiver, and it would put a PIN
        // change on a queue — which is one careless payload edit away from putting the PIN on it.
        requireSanePin(pin);
        byId(tenantId, userId); // 404s before touching anything
        jdbc.update(
                "UPDATE users SET pin_hash = ? WHERE tenant_id = ? AND id = ?",
                encoder.encode(pin),
                tenantId,
                userId);
    }

    /**
     * Deactivates or reinstates a user. There is no delete.
     *
     * <p>Every audit column in this schema is a foreign key to this table. Deleting a user would
     * either fail against those constraints or, worse, be made to succeed by loosening them — and
     * then "who authorised that refund" stops having an answer for exactly the people most likely
     * to have left in a hurry.
     */
    @Transactional
    public UserRow setActive(long tenantId, long userId, boolean active) {
        UserRow user = byId(tenantId, userId);
        if (!active) {
            refuseRemovingTheLastOwner(tenantId, user);
        }
        jdbc.update(
                "UPDATE users SET active = ? WHERE tenant_id = ? AND id = ?",
                active,
                tenantId,
                userId);
        UserRow updated = byId(tenantId, userId);
        enqueue(tenantId, updated);
        return updated;
    }

    // -------------------------------------------------------------------------- guards

    /**
     * A shop must keep at least one active owner.
     *
     * <p>{@link Permission#MANAGE_USERS} belongs to OWNER alone, so the last owner leaving takes
     * the ability to appoint a replacement with them. The lockout is total and the only way out is
     * a database edit on the shop's PC, which is not a support call anybody wants to run.
     */
    private void refuseRemovingTheLastOwner(long tenantId, UserRow user) {
        if (user.role() != Role.OWNER || !user.active()) {
            return;
        }
        if (activeOwnerCount(tenantId) <= 1) {
            throw new RejectedException(
                    "This is the shop's only active owner. Appoint another owner first — "
                            + "otherwise nobody is left who can create users.");
        }
    }

    private void refuseSelfDemotionToLastOwner(long tenantId, UserRow before, Role after) {
        if (before.role() == Role.OWNER && after != Role.OWNER && activeOwnerCount(tenantId) <= 1) {
            throw new RejectedException(
                    "This is the shop's only active owner. Appoint another owner before "
                            + "changing this one's role.");
        }
    }

    private int activeOwnerCount(long tenantId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE tenant_id = ? AND role = 'OWNER' AND active",
                        Integer.class,
                        tenantId);
        return count == null ? 0 : count;
    }

    /** Upper-cases, because {@code users.code} is stored upper-case and a CHECK says so. */
    private static String normaliseCode(String code) {
        String trimmed = code.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.length() > 16) {
            throw new RejectedException("A user code must be 1 to 16 characters");
        }
        return trimmed;
    }

    private static void requireSaneName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new RejectedException("A user needs a name");
        }
    }

    /**
     * Four digits minimum, and nothing about the composition beyond that.
     *
     * <p>No rule against 1234 or 0000. A PIN policy on a shop till pushes staff to write the PIN
     * somewhere near the till, which defeats it far more thoroughly than a weak PIN does — and the
     * mitigations that matter here are BCrypt's cost and the fact that the API is loopback-only.
     */
    private static void requireSanePin(String pin) {
        if (pin == null || pin.length() < 4) {
            throw new RejectedException("A PIN must be at least 4 digits");
        }
        if (!pin.chars().allMatch(Character::isDigit)) {
            throw new RejectedException("A PIN must be digits only — it is typed on a keypad");
        }
    }

    // ------------------------------------------------------------------------- sync (M3-12)

    /**
     * Puts the user on the outbox, in the caller's transaction.
     *
     * <h2>Never the hash</h2>
     *
     * The payload carries the code, the name, the role and whether they are active. It does not
     * carry {@code pin_hash} and there is no configuration under which it could. The cloud's reason
     * for holding users at all is so the console can print "authorised by Kumari" instead of an id;
     * a credential it does not need is a credential that can leak from somewhere the shop does not
     * control. It is also why M3-09 authenticates entirely locally — a till that could check a PIN
     * against the cloud would need the cloud to hold something worth stealing.
     *
     * <p>The whole row goes each time rather than a diff, for the same reason the product's does:
     * shipping state instead of change makes redelivery a no-op and arrival order irrelevant.
     */
    private void enqueue(long tenantId, UserRow user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientUuid", user.clientUuid().toString());
        payload.put("code", user.code());
        payload.put("displayName", user.displayName());
        payload.put("role", user.role().name());
        payload.put("active", user.active());
        outbox.enqueue(tenantId, "user", user.clientUuid(), payload);
    }
}
