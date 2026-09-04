package com.lumora.pos.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.lumora.pos.report.SalesReportService.ClosedShift;
import com.lumora.pos.shift.CloseShiftRequest;
import com.lumora.pos.shift.DenominationCount;
import com.lumora.pos.shift.OpenShiftRequest;
import com.lumora.pos.shift.ShiftResponse;
import com.lumora.pos.shift.ShiftService;
import com.lumora.pos.testfixtures.ShopFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Reaching the thirty-first shift (M6-11).
 *
 * <p>Until this, the history returned the newest thirty and there was no way to ask for anything
 * older. A shop trades for years; that list is not a list, it is a window with no handle.
 *
 * <h2>Why the cursor test is the one that matters</h2>
 *
 * The obvious implementation is {@code OFFSET}, and it is wrong rather than slow: this list grows at
 * the top, so a shift closing between two requests pushes every row down one and the second page
 * repeats a shift the reader has already seen. A duplicate in a list somebody is reconciling reads
 * as two events rather than one. {@link #loadingMoreCannotRepeatAShiftWhenOneClosesInBetween} is
 * that scenario written down.
 *
 * <h2>The tests trade two hundred days ago, and that is not decoration</h2>
 *
 * {@code ShopFixture.seed()} gives each test a fresh <em>branch</em> under the one tenant a desktop
 * database is allowed to have, and this report is deliberately shop-wide rather than per-branch. So
 * every other test class's shifts are in these results too. Backdating into a window nothing else
 * uses is what makes an exact assertion possible at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class ShiftHistoryTest {

    @Autowired SalesReportService reports;
    @Autowired ShiftService shifts;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    /** Far enough back that no other test class has traded there. See the class note. */
    private static final Instant WINDOW = Instant.now().minus(200, ChronoUnit.DAYS);

    // ------------------------------------------------------------------------------ the range

    @Test
    void aDateRangeReturnsOnlyTheShiftsInsideIt() {
        ShopFixture.Shop shop = fixtures.seed();
        long old = closedShiftAt(shop, WINDOW.minus(10, ChronoUnit.DAYS));
        long inside = closedShiftAt(shop, WINDOW);
        long recent = closedShiftAt(shop, WINDOW.plus(10, ChronoUnit.DAYS));

        List<Long> ids =
                idsOf(
                        reports.closedShifts(
                                shop.tenantId(), day(WINDOW).minusDays(1), day(WINDOW).plusDays(1),
                                null, null, 100));

        assertThat(ids).contains(inside).doesNotContain(old, recent);
    }

    /**
     * Half-open ranges, because both halves are things a shopkeeper actually asks.
     *
     * <p>"Everything since the first" and "everything before last Friday" are ordinary questions,
     * and a range that required both ends would turn each of them into arithmetic.
     */
    @Test
    void eitherEndOfTheRangeMayBeLeftOpen() {
        ShopFixture.Shop shop = fixtures.seed();
        long old = closedShiftAt(shop, WINDOW.minus(10, ChronoUnit.DAYS));
        long recent = closedShiftAt(shop, WINDOW);

        assertThat(
                        idsOf(
                                reports.closedShifts(
                                        shop.tenantId(), day(WINDOW).minusDays(1), null, null, null, 500)))
                .contains(recent)
                .doesNotContain(old);
        assertThat(
                        idsOf(
                                reports.closedShifts(
                                        shop.tenantId(), null, day(WINDOW).minusDays(1), null, null, 500)))
                .contains(old)
                .doesNotContain(recent);
    }

    /** The range is inclusive at both ends, in the shop's own day rather than in UTC. */
    @Test
    void aSingleDayIncludesThatWholeDay() {
        ShopFixture.Shop shop = fixtures.seed();
        long shift = closedShiftAt(shop, WINDOW);

        assertThat(idsOf(reports.closedShifts(shop.tenantId(), day(WINDOW), day(WINDOW), null, null, 100)))
                .contains(shift);
    }

    // ----------------------------------------------------------------------------- the cursor

    @Test
    void theCursorContinuesAfterTheLastRowSeen() {
        ShopFixture.Shop shop = fixtures.seed();
        Instant base = WINDOW.plus(30, ChronoUnit.DAYS);
        long oldest = closedShiftAt(shop, base);
        long middle = closedShiftAt(shop, base.plus(1, ChronoUnit.HOURS));
        long newest = closedShiftAt(shop, base.plus(2, ChronoUnit.HOURS));

        List<ClosedShift> firstPage = page(shop, base, null, null, 2);
        assertThat(idsOf(firstPage)).containsExactly(newest, middle);

        ClosedShift last = firstPage.get(firstPage.size() - 1);
        assertThat(idsOf(page(shop, base, last.closedAt(), last.id(), 2))).containsExactly(oldest);
    }

    /**
     * The scenario {@code OFFSET} gets wrong.
     *
     * <p>A shift closes between the first request and the second, pushing every row down one. With
     * an offset the second page would start one row too early and repeat the shift at the boundary.
     * A keyset cursor asks for what is after a specific row, so it cannot.
     */
    @Test
    void loadingMoreCannotRepeatAShiftWhenOneClosesInBetween() {
        ShopFixture.Shop shop = fixtures.seed();
        Instant base = WINDOW.plus(60, ChronoUnit.DAYS);
        long oldest = closedShiftAt(shop, base);
        long middle = closedShiftAt(shop, base.plus(1, ChronoUnit.HOURS));
        long newest = closedShiftAt(shop, base.plus(2, ChronoUnit.HOURS));

        List<ClosedShift> firstPage = page(shop, base, null, null, 2);
        assertThat(idsOf(firstPage)).containsExactly(newest, middle);
        ClosedShift last = firstPage.get(firstPage.size() - 1);

        // The shop keeps trading while somebody is reading the report.
        long whileReading = closedShiftAt(shop, base.plus(3, ChronoUnit.HOURS));

        List<Long> nextPage = idsOf(page(shop, base, last.closedAt(), last.id(), 2));

        // Exactly the one remaining row: nothing repeated from the first page, and the shift that
        // closed in between has not been dealt into the middle of somebody's reconciliation.
        assertThat(nextPage).containsExactly(oldest);
        assertThat(nextPage).doesNotContain(newest, middle, whileReading);
    }

    /** Half a cursor is a query that means something else, so it is treated as none at all. */
    @Test
    void aCursorWithOnlyOneHalfIsIgnoredRatherThanReturningNothing() {
        ShopFixture.Shop shop = fixtures.seed();
        Instant base = WINDOW.plus(90, ChronoUnit.DAYS);
        long shift = closedShiftAt(shop, base);

        assertThat(idsOf(page(shop, base, Instant.now(), null, 100))).contains(shift);
        assertThat(idsOf(page(shop, base, null, 1L, 100))).contains(shift);
    }

    /** No arguments still means what it always meant: the newest first. */
    @Test
    void askingForNothingInParticularStillReturnsTheNewestFirst() {
        ShopFixture.Shop shop = fixtures.seed();
        Instant base = WINDOW.plus(120, ChronoUnit.DAYS);
        long older = closedShiftAt(shop, base);
        long newer = closedShiftAt(shop, base.plus(1, ChronoUnit.HOURS));

        List<Long> everything =
                idsOf(reports.closedShifts(shop.tenantId(), null, null, null, null, 1000));

        // Ordering asserted between two rows this test owns, rather than by claiming the whole
        // table: every other class in the suite closes shifts into the same tenant.
        assertThat(everything).contains(newer, older);
        assertThat(everything.indexOf(newer)).isLessThan(everything.indexOf(older));
    }

    // ------------------------------------------------------------------------------- helpers

    /**
     * Opens a shift, closes it, and backdates both timestamps.
     *
     * <p>Backdated with an UPDATE rather than by faking a clock: the service is what owns the
     * transition and mocking time around it would test the mock. What is under test here is the
     * query, and the query needs rows spread across days.
     */
    private long closedShiftAt(ShopFixture.Shop shop, Instant closedAt) {
        ShiftResponse open =
                shifts.open(
                        new OpenShiftRequest(
                                UUID.randomUUID(),
                                shop.branchCode(),
                                "T1",
                                ShopFixture.MANAGER_CODE,
                                ShopFixture.MANAGER_PIN,
                                null,
                                500_000L,
                                List.of(new DenominationCount(100_000L, 5))));
        shifts.close(
                open.id(),
                new CloseShiftRequest(
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                        null,
                        null,
                        List.of(new DenominationCount(100_000L, 5)),
                        null,
                        null));
        jdbc.update(
                "UPDATE shifts SET opened_at = ?, closed_at = ? WHERE id = ?",
                java.sql.Timestamp.from(closedAt.minus(8, ChronoUnit.HOURS)),
                java.sql.Timestamp.from(closedAt),
                open.id());
        return open.id();
    }

    /** One page, bounded to the day either side of {@code base} so only this test's rows appear. */
    private List<ClosedShift> page(
            ShopFixture.Shop shop, Instant base, Instant beforeClosedAt, Long beforeId, int limit) {
        return reports.closedShifts(
                shop.tenantId(),
                day(base).minusDays(1),
                day(base).plusDays(1),
                beforeClosedAt,
                beforeId,
                limit);
    }

    private static LocalDate day(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneId.systemDefault());
    }

    private static List<Long> idsOf(List<ClosedShift> rows) {
        return rows.stream().map(ClosedShift::id).toList();
    }

}
