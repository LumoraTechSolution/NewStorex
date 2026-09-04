package com.lumora.pos.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.lumora.pos.refund.CreateRefundRequest;
import com.lumora.pos.refund.RefundService;
import com.lumora.pos.report.SalesReportService.ClosedShift;
import com.lumora.pos.report.SalesReportService.DaySales;
import com.lumora.pos.report.SalesReportService.TenderTotal;
import com.lumora.pos.report.SalesReportService.TopProduct;
import com.lumora.pos.sale.CreateSaleRequest;
import com.lumora.pos.sale.SaleResponse;
import com.lumora.pos.sale.SaleService;
import com.lumora.pos.shift.CloseShiftRequest;
import com.lumora.pos.shift.DenominationCount;
import com.lumora.pos.shift.OpenShiftRequest;
import com.lumora.pos.shift.ShiftResponse;
import com.lumora.pos.shift.ShiftService;
import com.lumora.pos.testfixtures.ShopFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Local reports (M3-10).
 *
 * <h2>Every test owns a day</h2>
 *
 * The reports are tenant-scoped, and a desktop database holds exactly one tenant — so scoping these
 * tests by branch, the way the rest of the suite does, would not separate them at all. They are
 * separated by <em>date</em> instead: each takes a day of its own, far enough back that no other
 * test has traded there. A report that quietly summed another test's sales would still be green on
 * every assertion that used a range, which is precisely the bug worth designing against.
 *
 * <p>Not {@code @Transactional}, for the same reason {@code ShiftLifecycleTest} is not: these need
 * the services' own transactions to commit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class SalesReportTest {

    @Autowired SalesReportService reports;
    @Autowired SaleService sales;
    @Autowired RefundService refunds;
    @Autowired ShiftService shifts;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    /**
     * Days are handed out from here so no two tests can pick the same one.
     *
     * <p>Five at a time, not one: the tests that reason about a boundary need the day after theirs
     * to be empty too, and handing out consecutive days makes one test's data the next test's
     * midnight. That failed exactly once and read as a broken half-open interval, which is the
     * wrong thing to go and fix.
     */
    private static final int DAYS_PER_TEST = 5;

    private static final AtomicInteger DAYS_AGO = new AtomicInteger(300);

    // ---------------------------------------------------------------------- day sales

    @Test
    void aDayReportsWhatWasSoldAndWhatWentBack() {
        ShopFixture.Shop shop = openShop();
        LocalDate day = ownDay();

        ringUpCash(shop, day, 2, 45_000);
        ringUpCash(shop, day, 1, 45_000);
        SaleResponse returned = ringUpCash(shop, day, 1, 45_000);
        refund(shop, day, returned, 45_000, 6_864);

        DaySales report = reports.day(shop.tenantId(), day);

        assertThat(report.date()).isEqualTo(day);
        assertThat(report.saleCount()).isEqualTo(3);
        assertThat(report.grossMinor()).isEqualTo(180_000);
        assertThat(report.refundCount()).isEqualTo(1);
        assertThat(report.refundTotalMinor()).isEqualTo(45_000);

        // Gross and returns are reported separately and the net is the third figure. A day with
        // three sales and one return must not read as a day with two sales.
        assertThat(report.netTakingsMinor()).isEqualTo(135_000);
    }

    /**
     * The day is half-open, so midnight belongs to exactly one of the two days it touches.
     *
     * <p>The obvious alternative — {@code BETWEEN} — is inclusive at both ends, which counts the
     * midnight sale twice across a two-day report and is invisible on any day that happens not to
     * have one. That is the shape of bug a shopkeeper finds in April, in a total that is off by one
     * sale, with no way to work out which.
     */
    @Test
    void midnightBelongsToTheDayThatStartsThenAndNotTheOneThatEnds() {
        ShopFixture.Shop shop = openShop();
        LocalDate day = ownDay();
        LocalDate next = day.plusDays(1);

        ringUpAt(shop, at(day, LocalTime.of(23, 59, 59)), 1, 45_000);
        ringUpAt(shop, at(next, LocalTime.MIDNIGHT), 1, 45_000);

        assertThat(reports.day(shop.tenantId(), day).saleCount()).isEqualTo(1);
        assertThat(reports.day(shop.tenantId(), next).saleCount()).isEqualTo(1);
    }

    /** A day with nothing in it is a day of zeros, not an error and not an empty response. */
    @Test
    void aDayWithNoTradingIsAllZeros() {
        ShopFixture.Shop shop = fixtures.seed();
        DaySales report = reports.day(shop.tenantId(), ownDay());

        assertThat(report.saleCount()).isZero();
        assertThat(report.grossMinor()).isZero();
        assertThat(report.netTakingsMinor()).isZero();
        assertThat(report.tenders()).isEmpty();
        assertThat(report.hours()).isEmpty();
    }

    /**
     * Each tender kind reports what was taken and what was handed back on one row.
     *
     * <p>One row and not two lists: a CARD refund has to reach the same line as the CARD takings,
     * and lining up two lists in the UI is where that stops being reliable.
     */
    @Test
    void tendersReportTakingsAndReturnsOnTheSameRow() {
        ShopFixture.Shop shop = openShop();
        LocalDate day = ownDay();

        ringUpCash(shop, day, 1, 45_000);
        SaleResponse card = ringUpCard(shop, day, 1, 45_000);
        refundTo(shop, day, card, 45_000, 6_864, "CARD");

        List<TenderTotal> tenders = reports.day(shop.tenantId(), day).tenders();

        TenderTotal cash = tenderNamed(tenders, "CASH");
        assertThat(cash.takenMinor()).isEqualTo(45_000);
        assertThat(cash.givenBackMinor()).isZero();

        TenderTotal cardTotal = tenderNamed(tenders, "CARD");
        assertThat(cardTotal.takenMinor()).isEqualTo(45_000);
        assertThat(cardTotal.givenBackMinor()).isEqualTo(45_000);
        assertThat(cardTotal.netMinor()).isZero();
    }

    /** Quiet hours are absent rather than zero-filled — the screen decides what a gap looks like. */
    @Test
    void hoursCoverOnlyTheHoursThatTraded() {
        ShopFixture.Shop shop = openShop();
        LocalDate day = ownDay();

        ringUpAt(shop, at(day, LocalTime.of(9, 15)), 1, 45_000);
        ringUpAt(shop, at(day, LocalTime.of(9, 40)), 1, 45_000);
        ringUpAt(shop, at(day, LocalTime.of(17, 5)), 1, 45_000);

        List<SalesReportService.HourTotal> hours = reports.day(shop.tenantId(), day).hours();

        assertThat(hours).hasSize(2);
        assertThat(hours.get(0).hour()).isEqualTo(9);
        assertThat(hours.get(0).saleCount()).isEqualTo(2);
        assertThat(hours.get(0).grossMinor()).isEqualTo(90_000);
        assertThat(hours.get(1).hour()).isEqualTo(17);
    }

    // -------------------------------------------------------------------- top products

    /**
     * Returned units come off the count.
     *
     * <p>A line sold ten times and returned nine is not a product that sells, and a ranking that
     * says otherwise would be acted on — reordered, given shelf space — which makes it worse than
     * no ranking at all.
     */
    @Test
    void topProductsCountsWhatStayedSold() {
        ShopFixture.Shop shop = openShop();
        LocalDate day = ownDay();

        SaleResponse bulk = ringUpCash(shop, day, 5, 45_000);
        ringUpProduct(shop, at(day, LocalTime.NOON), shop.exemptUuid(), 3, 25_000);
        // Four of the five go back, leaving one — fewer than the other product's three.
        refundQty(shop, day, bulk, 4, 180_000, 27_457);

        List<TopProduct> top = reports.topProducts(shop.tenantId(), day, day, 10);

        assertThat(top).hasSize(2);
        assertThat(top.get(0).name()).isEqualTo("Bread 450g");
        assertThat(top.get(0).qtyNet()).isEqualTo(3);

        TopProduct tea = top.get(1);
        assertThat(tea.qtySold()).isEqualTo(5);
        assertThat(tea.qtyReturned()).isEqualTo(4);
        assertThat(tea.qtyNet()).isEqualTo(1);
    }

    /** A product nobody bought in the window is absent, not a zero row. */
    @Test
    void topProductsLeavesOutWhatDidNotSell() {
        ShopFixture.Shop shop = openShop();
        LocalDate day = ownDay();
        ringUpCash(shop, day, 1, 45_000);

        List<TopProduct> top = reports.topProducts(shop.tenantId(), day, day, 10);

        assertThat(top).hasSize(1);
        assertThat(top.get(0).name()).isEqualTo("Tea 400g");
    }

    /** The range is inclusive of both named days, because that is what a person means by it. */
    @Test
    void topProductsSpansTheWholeRangeItWasGiven() {
        ShopFixture.Shop shop = openShop();
        LocalDate first = ownDay();
        LocalDate last = first.plusDays(2);

        ringUpCash(shop, first, 1, 45_000);
        ringUpCash(shop, last, 2, 45_000);

        assertThat(reports.topProducts(shop.tenantId(), first, last, 10).get(0).qtyNet()).isEqualTo(3);
        assertThat(reports.topProducts(shop.tenantId(), first, first, 10).get(0).qtyNet()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------- Z-history

    /**
     * A closed shift appears with the figures that were frozen at close.
     *
     * <p>Read back rather than recomputed, so this row and the Z-report it opens cannot disagree —
     * the same reasoning that made {@code ZReportService} a pure read.
     */
    @Test
    void closedShiftsCarryTheVarianceThatWasFrozenAtClose() {
        ShopFixture.Shop shop = fixtures.seed();
        ShiftResponse shift = openShift(shop);
        LocalDate day = ownDay();
        ringUpCash(shop, day, 1, 45_000);

        // Counted short by LKR 100 against a float of 5,000 plus a 450 sale, rounded to 500.
        shifts.close(
                shift.id(),
                new CloseShiftRequest(
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                        null,
                        null,
                        List.of(new DenominationCount(100_000L, 5), new DenominationCount(10_000L, 4)),
                        null,
                        null));

        ClosedShift row = onlyShift(reports.closedShifts(shop.tenantId(), null, null, null, null, 50), shift.id());

        assertThat(row.branchCode()).isEqualTo(shop.branchCode());
        assertThat(row.openedByName()).isEqualTo("Fixture Manager");
        assertThat(row.closedByName()).isEqualTo("Fixture Manager");
        assertThat(row.countedCashMinor()).isEqualTo(540_000);
        assertThat(row.varianceMinor())
                .isEqualTo(row.countedCashMinor() - row.expectedCashMinor());
        assertThat(row.saleCount()).isEqualTo(1);
        assertThat(row.salesTotalMinor()).isEqualTo(45_000);
    }

    /**
     * An open shift is not on the list, and the reason is M2-02 rather than tidiness.
     *
     * <p>Every row here carries {@code expectedCashMinor}. Listing an open shift would hand the
     * person about to count the drawer the exact figure the blind count exists to keep from them —
     * through a report screen, which is a door the cash-up flow cannot see.
     */
    @Test
    void anOpenShiftIsNotListed() {
        ShopFixture.Shop shop = fixtures.seed();
        ShiftResponse open = openShift(shop);

        assertThat(reports.closedShifts(shop.tenantId(), null, null, null, null, 200))
                .noneMatch(row -> row.id() == open.id());
    }

    // ------------------------------------------------------------------------- helpers

    /** A day nothing else in this class has traded on, with four empty ones after it. */
    private static LocalDate ownDay() {
        return LocalDate.now().minusDays(DAYS_AGO.addAndGet(DAYS_PER_TEST));
    }

    private static Instant at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(ZoneId.systemDefault()).toInstant();
    }

    private ShopFixture.Shop openShop() {
        ShopFixture.Shop shop = fixtures.seed();
        openShift(shop);
        return shop;
    }

    private ShiftResponse openShift(ShopFixture.Shop shop) {
        return shifts.open(
                new OpenShiftRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                        null,
                        500_000L,
                        List.of(new DenominationCount(100_000L, 5))));
    }

    private SaleResponse ringUpCash(ShopFixture.Shop shop, LocalDate day, int qty, long unitMinor) {
        return ringUpAt(shop, at(day, LocalTime.of(10, 30)), qty, unitMinor);
    }

    private SaleResponse ringUpAt(ShopFixture.Shop shop, Instant soldAt, int qty, long unitMinor) {
        return ringUpProduct(shop, soldAt, shop.productUuid(), qty, unitMinor);
    }

    private SaleResponse ringUpProduct(
            ShopFixture.Shop shop, Instant soldAt, UUID product, int qty, long unitMinor) {
        return commit(shop, soldAt, product, qty, unitMinor, "CASH");
    }

    private SaleResponse ringUpCard(ShopFixture.Shop shop, LocalDate day, int qty, long unitMinor) {
        return commit(shop, at(day, LocalTime.of(11, 0)), shop.productUuid(), qty, unitMinor, "CARD");
    }

    private SaleResponse commit(
            ShopFixture.Shop shop,
            Instant soldAt,
            UUID product,
            int qty,
            long unitMinor,
            String tender) {
        long total = unitMinor * qty;
        long tax = total * 1800 / 11800;
        boolean cash = "CASH".equals(tender);
        long rounding = cash ? roundToRupee(total) - total : 0;

        return sales.commit(
                new CreateSaleRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        soldAt,
                        "INCLUSIVE",
                        1800,
                        total,
                        0L,
                        tax,
                        total,
                        List.of(new CreateSaleRequest.Line(product, qty, unitMinor, 0L, tax, total)),
                        rounding,
                        0L,
                        List.of(new CreateSaleRequest.Tender(tender, total + rounding)), null));
    }

    private void refund(
            ShopFixture.Shop shop, LocalDate day, SaleResponse sale, long totalMinor, long taxMinor) {
        refundTo(shop, day, sale, totalMinor, taxMinor, "CASH");
    }

    private void refundTo(
            ShopFixture.Shop shop,
            LocalDate day,
            SaleResponse sale,
            long totalMinor,
            long taxMinor,
            String kind) {
        refundLines(shop, day, sale, 1, totalMinor, taxMinor, kind);
    }

    private void refundQty(
            ShopFixture.Shop shop,
            LocalDate day,
            SaleResponse sale,
            int qty,
            long totalMinor,
            long taxMinor) {
        refundLines(shop, day, sale, qty, totalMinor, taxMinor, "CASH");
    }

    private void refundLines(
            ShopFixture.Shop shop,
            LocalDate day,
            SaleResponse sale,
            int qty,
            long totalMinor,
            long taxMinor,
            String kind) {
        refunds.commit(
                new CreateRefundRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        sale.invoiceNumber(),
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                        at(day, LocalTime.of(16, 0)),
                        totalMinor,
                        taxMinor,
                        0L,
                        List.of(
                                new CreateRefundRequest.Line(
                                        1, qty, totalMinor, taxMinor, "CHANGED_MIND", null, true)),
                        List.of(new CreateRefundRequest.Tender(kind, totalMinor))));
    }

    private static long roundToRupee(long amountMinor) {
        return Math.floorDiv(amountMinor + 50, 100) * 100;
    }

    private static TenderTotal tenderNamed(List<TenderTotal> tenders, String kind) {
        return tenders.stream()
                .filter(t -> t.kind().equals(kind))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + kind + " row in " + tenders));
    }

    private static ClosedShift onlyShift(List<ClosedShift> rows, long shiftId) {
        return rows.stream()
                .filter(row -> row.id() == shiftId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Shift " + shiftId + " was not listed"));
    }
}
