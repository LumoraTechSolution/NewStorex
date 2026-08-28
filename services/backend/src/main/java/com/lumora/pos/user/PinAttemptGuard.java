package com.lumora.pos.user;

import com.lumora.pos.web.RejectedException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * How fast somebody may keep guessing (M3-13).
 *
 * <h2>It cools off; it does not lock out</h2>
 *
 * The task is named "lockout" and this is deliberately not one. A lock that a person has to be
 * released from is a denial of service anybody can trigger: six wrong PINs at the owner's code and
 * the shop cannot authorise a refund until somebody who is not on the premises unlocks it. On a
 * till that failure is worse than the one it prevents — brute force needs an attacker standing at
 * the counter for days; locking the owner out takes fifteen seconds and no skill at all.
 *
 * <p>So the period escalates and then ends by itself. A cashier who mistyped waits a few seconds. A
 * search of the keyspace gets roughly two attempts a minute, which turns M3-08's quarter of an hour
 * into several days of continuous work at a keypad in somebody's shop. Nothing needs releasing.
 *
 * <h2>Recorded in its own transaction, which is the whole reason this is a separate bean</h2>
 *
 * A failed attempt <em>throws</em>. If the counter were incremented inside the authentication
 * transaction, the throw would roll the increment back and the throttle would count to one forever
 * — a bug that passes every test written against a single attempt and fails only the thing it
 * exists to stop. {@link Propagation#REQUIRES_NEW} suspends the caller's transaction and commits
 * this on its own.
 *
 * <p>It is a separate class for the same reason: Spring's proxy does not intercept a method calling
 * another method on itself, so {@code REQUIRES_NEW} on a private helper inside {@code UserService}
 * would silently do nothing at all.
 */
@Component
public class PinAttemptGuard {

    /**
     * Failures allowed before any wait at all.
     *
     * <p>Four, because a shopkeeper mistyping their own PIN twice in a row is an ordinary morning
     * and being made to wait for it teaches people to distrust the till. The fifth is where an
     * honest mistake stops being the likeliest explanation.
     */
    private static final int FREE_ATTEMPTS = 4;

    /**
     * How long the wait is after {@code n} consecutive failures.
     *
     * <p>Doubling from five seconds and capped at two minutes. The cap matters: an unbounded
     * backoff eventually becomes the lockout this class exists not to be, and by two minutes the
     * arithmetic has already done its work — ten thousand combinations at thirty per hour is over a
     * year of somebody standing at a keypad.
     */
    private static Duration waitAfter(int failures) {
        int over = failures - FREE_ATTEMPTS;
        if (over <= 0) {
            return Duration.ZERO;
        }
        long seconds = 5L << Math.min(over - 1, 5); // 5, 10, 20, 40, 80, 160 → capped below
        return Duration.ofSeconds(Math.min(seconds, 120));
    }

    /**
     * A quiet spell that long makes the next failure the first one again.
     *
     * <p>Consecutive is the word doing the work. A PIN mistyped this morning should not shorten
     * anybody's patience this afternoon, and an attacker who waits fifteen minutes between guesses
     * has already been slowed to something no keyspace survives.
     */
    private static final Duration DECAY = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;

    public PinAttemptGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Throws if this code is still cooling off.
     *
     * <p>Called before the PIN is compared. That order is not a shortcut — it also means a code
     * being throttled costs the same whether or not anybody holds it, because the counter is keyed
     * on what was typed rather than on a user. V109 went to some trouble to make a wrong code and a
     * wrong PIN indistinguishable, including a BCrypt comparison against an unsatisfiable hash so
     * the timings match; a throttle that applied only to real codes would give all of that back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void requireNotCoolingOff(long tenantId, String code) {
        Instant lockedUntil = lockedUntil(tenantId, code);
        if (lockedUntil == null) {
            return;
        }
        long seconds = Math.max(1, Duration.between(Instant.now(), lockedUntil).toSeconds());
        throw new RejectedException(
                "Too many wrong PINs. Try again in "
                        + describe(seconds)
                        + ". Nobody has been locked out — the wait clears on its own.");
    }

    /**
     * Records a failure and returns the wait it earned.
     *
     * <p>Deliberately does not throw. The caller has its own message — "that user code and PIN were
     * not recognised" — and replacing it here with a throttle message on the fifth attempt would
     * tell an attacker exactly when the counter tripped. The wait is applied to the <em>next</em>
     * attempt, which is where it belongs and where it says nothing new.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(long tenantId, String code) {
        String normalised = normalise(code);
        Instant now = Instant.now();

        int failures = nextFailureCount(tenantId, normalised, now);
        Duration wait = waitAfter(failures);
        OffsetDateTime lockedUntil = wait.isZero() ? null : now.plus(wait).atOffset(ZoneOffset.UTC);

        jdbc.update(
                """
                INSERT INTO pin_attempts
                       (tenant_id, code, failures, first_failed_at, last_failed_at, locked_until)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, code) DO UPDATE SET
                    failures = excluded.failures,
                    first_failed_at = CASE WHEN excluded.failures = 1
                                           THEN excluded.first_failed_at
                                           ELSE pin_attempts.first_failed_at END,
                    last_failed_at = excluded.last_failed_at,
                    locked_until = excluded.locked_until
                """,
                tenantId,
                normalised,
                failures,
                now.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC),
                lockedUntil);
    }

    /**
     * Clears the counter. Run on every successful authentication.
     *
     * <p>Unconditionally, rather than only when a counter exists: the extra statement costs nothing
     * on a loopback database and the alternative is a read that has to be right about when the
     * write is needed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(long tenantId, String code) {
        jdbc.update(
                "DELETE FROM pin_attempts WHERE tenant_id = ? AND code = ?",
                tenantId,
                normalise(code));
    }

    /** When this code may next be tried, or null when it may be tried now. Exposed for tests. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Instant lockedUntil(long tenantId, String code) {
        List<OffsetDateTime> found =
                jdbc.query(
                        "SELECT locked_until FROM pin_attempts WHERE tenant_id = ? AND code = ?",
                        (rs, row) -> rs.getObject("locked_until", OffsetDateTime.class),
                        tenantId,
                        normalise(code));
        if (found.isEmpty() || found.get(0) == null) {
            return null;
        }
        Instant lockedUntil = found.get(0).toInstant();
        // A lapsed timestamp is the same answer as no timestamp, so nothing sweeps the table.
        return lockedUntil.isAfter(Instant.now()) ? lockedUntil : null;
    }

    // ------------------------------------------------------------------------- internals

    private int nextFailureCount(long tenantId, String normalised, Instant now) {
        List<OffsetDateTime> lastFailed =
                jdbc.query(
                        "SELECT last_failed_at FROM pin_attempts WHERE tenant_id = ? AND code = ?",
                        (rs, row) -> rs.getObject("last_failed_at", OffsetDateTime.class),
                        tenantId,
                        normalised);
        if (lastFailed.isEmpty() || lastFailed.get(0) == null) {
            return 1;
        }
        if (Duration.between(lastFailed.get(0).toInstant(), now).compareTo(DECAY) > 0) {
            return 1;
        }
        Integer failures =
                jdbc.queryForObject(
                        "SELECT failures FROM pin_attempts WHERE tenant_id = ? AND code = ?",
                        Integer.class,
                        tenantId,
                        normalised);
        return (failures == null ? 0 : failures) + 1;
    }

    /** Upper-cased, exactly as {@code users.code} is — a throttle reset by changing case is none. */
    private static String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    /** "8 seconds", "2 minutes" — a shopkeeper reads a wait, not a timestamp. */
    private static String describe(long seconds) {
        if (seconds < 60) {
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        long minutes = (seconds + 59) / 60;
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
