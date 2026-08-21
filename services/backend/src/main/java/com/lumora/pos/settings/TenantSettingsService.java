package com.lumora.pos.settings;

import com.lumora.pos.web.RejectedException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-shop policy: the cash variance threshold (D1) and the manager PIN (M2-07).
 *
 * <h2>D1, resolved</h2>
 *
 * The variance threshold is per-tenant and always was going to have to be. A jeweller counting
 * LKR 400,000 of takings and a grocer counting LKR 12,000 do not mean the same thing by "the
 * drawer is out": a fixed LKR 100 is noise to one and an obstruction to the other, and a gate that
 * fires on every shift is a gate cashiers learn to click through. The default is LKR 100.00, which
 * suits the grocer, and the row exists so the jeweller can change it without a migration.
 *
 * <h2>An unset PIN is a closed gate, not an open one</h2>
 *
 * {@code manager_pin_hash} is NULL until a shop sets one, and {@link #verifyManagerPin} refuses
 * every refund while it is. That is the direction a default must fail in: a shop that never got
 * round to configuring a manager PIN gets refunds it cannot process — visible, fixable, and
 * loudly wrong — rather than refunds anybody can authorise, which looks exactly like working
 * software right up until the money is gone.
 *
 * <p>BCrypt rather than a plain hash because a PIN is four to six digits: the entire keyspace is a
 * few hundred thousand entries, and a fast hash would fall to a laptop in seconds. BCrypt's work
 * factor is what makes the local database file being readable — which, on a shop PC, it is — not
 * the same thing as the PIN being known. This is not a substitute for M3-09's real auth; it is the
 * smallest thing that is not actively misleading until M3-08 brings users.
 */
@Service
public class TenantSettingsService {

    /** LKR 100.00. The grocer's number — see the class comment on why it is only a default. */
    public static final long DEFAULT_VARIANCE_THRESHOLD_MINOR = 10_000L;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public TenantSettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The threshold above which closing a shift needs a reason (M2-04).
     *
     * <p>Falls back to the default when a shop has no settings row rather than failing: a till that
     * cannot close a shift because nobody ran a configuration step is a till that cannot be
     * reconciled, which is worse than one reconciled against a sensible default.
     */
    @Transactional(readOnly = true)
    public long cashVarianceThresholdMinor(long tenantId) {
        List<Long> found =
                jdbc.queryForList(
                        "SELECT cash_variance_threshold_minor FROM tenant_settings WHERE tenant_id = ?",
                        Long.class,
                        tenantId);
        return found.isEmpty() ? DEFAULT_VARIANCE_THRESHOLD_MINOR : found.get(0);
    }

    /**
     * M2-07. Throws unless the PIN matches the shop's manager PIN.
     *
     * <p>Deliberately not a boolean. A method returning true/false invites {@code if (ok) { … }}
     * with no else, and a refund authorised by a forgotten branch is precisely the bug this gate
     * exists to prevent. The only way past this call is for the PIN to be right.
     */
    @Transactional(readOnly = true)
    public void verifyManagerPin(long tenantId, String pin) {
        if (pin == null || pin.isBlank()) {
            throw new RejectedException("A manager PIN is required to authorise a refund");
        }

        List<String> hashes =
                jdbc.queryForList(
                        """
                        SELECT manager_pin_hash FROM tenant_settings
                         WHERE tenant_id = ? AND manager_pin_hash IS NOT NULL
                        """,
                        String.class,
                        tenantId);
        if (hashes.isEmpty()) {
            throw new RejectedException(
                    "No manager PIN has been set for this shop, so no refund can be authorised. "
                            + "Set one in the back office before processing returns.");
        }
        if (!encoder.matches(pin, hashes.get(0))) {
            throw new RejectedException("Manager PIN not recognised");
        }
    }

    /**
     * Sets or replaces the shop's manager PIN.
     *
     * <p>Used by the dev seed today and by the back office from M3-08. There is deliberately no
     * HTTP endpoint for it in M2: an unauthenticated loopback API that can set the very credential
     * guarding refunds would be a gate with its own handle on the outside.
     */
    @Transactional
    public void setManagerPin(long tenantId, String pin) {
        if (pin == null || pin.length() < 4) {
            throw new RejectedException("A manager PIN must be at least 4 digits");
        }
        jdbc.update(
                """
                INSERT INTO tenant_settings (tenant_id, manager_pin_hash) VALUES (?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET manager_pin_hash = excluded.manager_pin_hash
                """,
                tenantId,
                encoder.encode(pin));
    }
}
