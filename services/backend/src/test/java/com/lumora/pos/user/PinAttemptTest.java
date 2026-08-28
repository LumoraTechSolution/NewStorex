package com.lumora.pos.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.testfixtures.ShopFixture;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rate-limited guessing (M3-13).
 *
 * <h2>The tests never sleep</h2>
 *
 * Waiting out a real cooling-off period would put minutes into the suite for no extra coverage. The
 * clock is moved instead — the row's {@code locked_until} is aged, which is exactly what the passage
 * of time does to it — and what gets asserted is the behaviour on either side of that boundary.
 *
 * <h2>Every test gets its own code</h2>
 *
 * The counter is keyed on the typed code and the shop is shared across the suite, so two tests
 * sharing a code would be one test's failures counting against the other's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class PinAttemptTest {

    @Autowired UserService users;
    @Autowired PinAttemptGuard attempts;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    private static final AtomicInteger UNIQUE = new AtomicInteger();
    private static final String PIN = "4821";

    /** Four, and the fifth is where an honest mistake stops being the likeliest explanation. */
    private static final int FREE_ATTEMPTS = 4;

    // ----------------------------------------------------------------- the honest mistake

    /**
     * Mistyping a PIN a couple of times costs nothing.
     *
     * <p>A shopkeeper fumbling their own PIN twice in a row is an ordinary morning, and being made
     * to wait for it teaches people to distrust the till — which is how a till ends up with its PIN
     * written on a sticky note beside it.
     */
    @Test
    void aCoupleOfWrongPinsCostNothing() {
        Shop shop = shopWithUser();

        for (int i = 0; i < FREE_ATTEMPTS; i++) {
            assertThatThrownBy(() -> users.authenticate(shop.tenantId(), shop.code(), "0000"))
                    .hasMessageContaining("not recognised");
        }

        assertThat(attempts.lockedUntil(shop.tenantId(), shop.code())).isNull();

        // And the right PIN still works — the counter is not a tripwire, it is a speed limit.
        assertThat(users.authenticate(shop.tenantId(), shop.code(), PIN).code())
                .isEqualTo(shop.code());
    }

    /** Getting it right clears the count, so this afternoon starts from zero again. */
    @Test
    void gettingItRightClearsTheCount() {
        Shop shop = shopWithUser();
        failTimes(shop, FREE_ATTEMPTS);

        users.authenticate(shop.tenantId(), shop.code(), PIN);

        assertThat(failuresFor(shop)).isZero();
        assertThat(attempts.lockedUntil(shop.tenantId(), shop.code())).isNull();
    }

    // ------------------------------------------------------------------- the brute force

    @Test
    void theFifthWrongPinStartsACoolingOffPeriod() {
        Shop shop = shopWithUser();

        failTimes(shop, FREE_ATTEMPTS + 1);

        assertThat(attempts.lockedUntil(shop.tenantId(), shop.code())).isNotNull();
        assertThatThrownBy(() -> users.authenticate(shop.tenantId(), shop.code(), "0000"))
                .hasMessageContaining("Too many wrong PINs");
    }

    /**
     * The correct PIN is refused while cooling off, and that is the point.
     *
     * <p>A throttle that let the right answer through would be no throttle at all — an attacker
     * working the keyspace is, by definition, going to type the right answer eventually.
     */
    @Test
    void evenTheRightPinWaits() {
        Shop shop = shopWithUser();
        failTimes(shop, FREE_ATTEMPTS + 1);

        assertThatThrownBy(() -> users.authenticate(shop.tenantId(), shop.code(), PIN))
                .hasMessageContaining("Too many wrong PINs");
    }

    /**
     * The wait grows with each further failure.
     *
     * <p>Asserted as an increase rather than against fixed seconds: the schedule is a judgement
     * call that may be retuned, and a test pinned to "10 seconds" would fail on a change that broke
     * nothing. What must not change is that guessing gets slower.
     */
    @Test
    void theWaitGetsLongerTheLongerSomebodyKeepsGuessing() {
        Shop shop = shopWithUser();

        failTimes(shop, FREE_ATTEMPTS + 1);
        long firstWait = remainingSeconds(shop);

        expireTheWait(shop);
        failTimes(shop, 3);
        long laterWait = remainingSeconds(shop);

        assertThat(laterWait).isGreaterThan(firstWait);
    }

    /**
     * The wait ends by itself. Nobody has to be released.
     *
     * <p>This is the whole design decision. A lock a person has to lift is a denial of service any
     * passer-by can trigger against the owner's own code — six wrong PINs and the shop cannot
     * authorise a refund until somebody who is not there does something about it. On a till that is
     * the worse failure of the two.
     */
    @Test
    void theWaitEndsOnItsOwnAndNobodyIsLockedOut() {
        Shop shop = shopWithUser();
        failTimes(shop, FREE_ATTEMPTS + 1);

        expireTheWait(shop);

        assertThat(attempts.lockedUntil(shop.tenantId(), shop.code())).isNull();
        assertThat(users.authenticate(shop.tenantId(), shop.code(), PIN).code())
                .isEqualTo(shop.code());
    }

    /** And the refusal says so, because a shopkeeper who thinks they are locked out phones somebody. */
    @Test
    void theRefusalSaysNobodyIsLockedOut() {
        Shop shop = shopWithUser();
        failTimes(shop, FREE_ATTEMPTS + 1);

        assertThatThrownBy(() -> users.authenticate(shop.tenantId(), shop.code(), PIN))
                .hasMessageContaining("Nobody has been locked out");
    }

    // -------------------------------------------------------------- what it must not leak

    /**
     * A code nobody holds is throttled exactly like a real one.
     *
     * <p>V109 went to some trouble to make a wrong code and a wrong PIN indistinguishable, down to
     * running the BCrypt comparison against an unsatisfiable hash so even the timing matches. A
     * throttle that only applied to real codes would give all of that back: five attempts at a
     * guessed code, and whether it slowed down tells you whether it exists.
     */
    @Test
    void aCodeNobodyHoldsIsThrottledTheSameWay() {
        Shop shop = shopWithUser();
        String invented = "GHOST" + UNIQUE.incrementAndGet();

        for (int i = 0; i < FREE_ATTEMPTS + 1; i++) {
            assertThatThrownBy(() -> users.authenticate(shop.tenantId(), invented, "0000"))
                    .hasMessageContaining("not recognised");
        }

        assertThat(attempts.lockedUntil(shop.tenantId(), invented)).isNotNull();
    }

    /**
     * The message does not change on the attempt that trips the throttle.
     *
     * <p>Saying "that was your fifth" would tell an attacker exactly where the counter is. The wait
     * applies to the <em>next</em> attempt, where it says nothing they did not already know.
     */
    @Test
    void theAttemptThatTripsItSaysNothingNew() {
        Shop shop = shopWithUser();
        failTimes(shop, FREE_ATTEMPTS);

        assertThatThrownBy(() -> users.authenticate(shop.tenantId(), shop.code(), "0000"))
                .hasMessage("That user code and PIN were not recognised");
    }

    /** Changing case does not reset anything — the counter is keyed the way `users.code` is. */
    @Test
    void theCounterIsNotResetByTypingTheCodeInLowerCase() {
        Shop shop = shopWithUser();
        failTimes(shop, FREE_ATTEMPTS + 1);

        assertThatThrownBy(
                        () ->
                                users.authenticate(
                                        shop.tenantId(),
                                        shop.code().toLowerCase(java.util.Locale.ROOT),
                                        "0000"))
                .hasMessageContaining("Too many wrong PINs");
    }

    // ---------------------------------------------------------------------- the rollback

    /**
     * The count survives the exception that carries it.
     *
     * <p>The failure path throws. If the increment ran inside the authentication transaction the
     * throw would roll it back and the counter would reach one and stay there — a bug that passes
     * every test written against a single attempt and fails only the thing the class exists for.
     * Five failures must therefore be five rows' worth of count, and this asserts the stored number
     * rather than the resulting behaviour so it fails for the right reason.
     */
    @Test
    void everyFailureIsCountedDespiteTheThrow() {
        Shop shop = shopWithUser();

        failTimes(shop, 3);

        assertThat(failuresFor(shop)).isEqualTo(3);
    }

    // ------------------------------------------------------------------------- helpers

    private record Shop(long tenantId, String code) {}

    private Shop shopWithUser() {
        ShopFixture.Shop shop = fixtures.seed();
        String code = "ATT" + UNIQUE.incrementAndGet();
        users.create(shop.tenantId(), UUID.randomUUID(), code, "Attempt probe", Role.CASHIER, PIN);
        return new Shop(shop.tenantId(), code);
    }

    /**
     * Fails deliberately, {@code times} times, without sleeping.
     *
     * <p>The wait earned by the previous failure is cleared <em>before</em> the next attempt rather
     * than after it, so the loop can keep going and the last failure's wait is still standing when
     * the loop returns — which is what every caller is about to assert on. Only {@code
     * locked_until} is touched; {@code failures} keeps climbing, and that is the number under test.
     */
    private void failTimes(Shop shop, int times) {
        for (int i = 0; i < times; i++) {
            if (i > 0) {
                expireTheWait(shop);
            }
            try {
                users.authenticate(shop.tenantId(), shop.code(), "0000");
            } catch (RuntimeException expected) {
                // Both refusals are expected — "not recognised" while free attempts remain, "too
                // many" once the throttle is active. What is under test here is the counting.
            }
        }
    }

    /** Ages {@code locked_until} into the past — what the passage of time does to it. */
    private void expireTheWait(Shop shop) {
        jdbc.update(
                "UPDATE pin_attempts SET locked_until = ? WHERE tenant_id = ? AND code = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                shop.tenantId(),
                shop.code());
    }

    private long remainingSeconds(Shop shop) {
        java.time.Instant until = attempts.lockedUntil(shop.tenantId(), shop.code());
        assertThat(until).as("expected a cooling-off period").isNotNull();
        return java.time.Duration.between(java.time.Instant.now(), until).toSeconds();
    }

    private int failuresFor(Shop shop) {
        Integer failures =
                jdbc.queryForObject(
                        "SELECT COALESCE(max(failures), 0) FROM pin_attempts WHERE tenant_id = ? AND code = ?",
                        Integer.class,
                        shop.tenantId(),
                        shop.code());
        return failures == null ? 0 : failures;
    }
}
