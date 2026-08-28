package com.lumora.pos.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.cash.CashMovementService;
import com.lumora.pos.cash.CreateCashMovementRequest;
import com.lumora.pos.report.ZReport;
import com.lumora.pos.report.ZReportService;
import com.lumora.pos.sale.CreateSaleRequest;
import com.lumora.pos.sale.SaleResponse;
import com.lumora.pos.sale.SaleService;
import com.lumora.pos.testfixtures.ShopFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The cash accountability layer, end to end (M2-01 … M2-05, M2-11).
 *
 * <p>Not {@code @Transactional}, for the same reason {@code SaleCommitTest} is not: these need the
 * services' own transactions to really commit, and a test-managed one wrapping them would mask the
 * behaviour under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class ShiftLifecycleTest {

    @Autowired ShiftService shifts;
    @Autowired SaleService sales;
    @Autowired CashMovementService cashMovements;
    @Autowired ZReportService reports;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    /** LKR 5,000 float: five 1000 notes. */
    private static final List<DenominationCount> FLOAT_5000 =
            List.of(new DenominationCount(100_000L, 5));

    @Test
    void openingAShiftCountsTheFloatAndEnqueuesIt() {
        ShopFixture.Shop shop = fixtures.seed();
        UUID shiftUuid = UUID.randomUUID();

        ShiftResponse shift = shifts.open(openRequest(shop, shiftUuid));

        assertThat(shift.status()).isEqualTo("OPEN");
        assertThat(shift.openingFloatMinor()).isEqualTo(500_000);
        assertThat(shift.expectedCashMinor()).isNull();

        // The denomination detail is evidence, not decoration: a float typed as one figure is a
        // number nobody checked, and the whole shift's variance rests on it.
        assertThat(count("SELECT count(*) FROM shift_counts WHERE shift_id = ? AND phase = 'OPEN'", shift.id()))
                .isEqualTo(1);

        // M2-12.
        assertThat(count(
                        "SELECT count(*) FROM outbox WHERE aggregate = 'shift' AND aggregate_id = ?", shiftUuid))
                .isEqualTo(1);
    }

    @Test
    void aTerminalMayOnlyHaveOneShiftOpen() {
        ShopFixture.Shop shop = fixtures.seed();
        shifts.open(openRequest(shop, UUID.randomUUID()));

        assertThatThrownBy(() -> shifts.open(openRequest(shop, UUID.randomUUID())))
                .hasMessageContaining("already has an open shift");
    }

    @Test
    void reopeningWithTheSameUuidIsTheSameShift() {
        ShopFixture.Shop shop = fixtures.seed();
        UUID shiftUuid = UUID.randomUUID();

        ShiftResponse first = shifts.open(openRequest(shop, shiftUuid));
        ShiftResponse retry = shifts.open(openRequest(shop, shiftUuid));

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(count("SELECT count(*) FROM shifts WHERE client_uuid = ?", shiftUuid)).isEqualTo(1);
    }

    @Test
    void theFloatChecksumMustAgreeWithTheDenominations() {
        ShopFixture.Shop shop = fixtures.seed();
        OpenShiftRequest bad =
                new OpenShiftRequest(
                        UUID.randomUUID(), shop.branchCode(), "T1",
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN, null, 999_999L, FLOAT_5000);

        assertThatThrownBy(() -> shifts.open(bad)).hasMessageContaining("does not match the counted");
    }

    @Test
    void anUnlistedDenominationIsRefusedRatherThanSummed() {
        ShopFixture.Shop shop = fixtures.seed();
        // 5000 minor units is the LKR 50 note; a screen sending rupees would mean LKR 5000 and
        // read the drawer a hundredfold light. 25000 is nothing at all.
        OpenShiftRequest bad =
                new OpenShiftRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                        null,
                        null,
                        List.of(new DenominationCount(25_000L, 1)));

        assertThatThrownBy(() -> shifts.open(bad)).hasMessageContaining("not a circulating");
    }

    // ------------------------------------------------------------------- the blind count

    /**
     * M2-02's actual enforcement.
     *
     * <p>Not "the screen does not show it" — the endpoint a trading terminal polls has no field
     * for it, so there is nothing to leak whatever the UI does. If this ever fails because someone
     * added the figure "just for the manager view", the milestone has been undone.
     */
    @Test
    void theStatusATradingTerminalSeesCannotCarryExpectedCash() {
        ShopFixture.Shop shop = fixtures.seed();
        shifts.open(openRequest(shop, UUID.randomUUID()));
        ringUp(shop, 45_000, 50_000);

        ShiftStatusResponse status = shifts.status(shop.branchCode(), "T1");

        assertThat(status.open()).isTrue();
        assertThat(status.saleCount()).isEqualTo(1);
        // Activity, not money. Neither field narrows down the drawer total.
        assertThat(ShiftStatusResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("expectedCashMinor", "expectedCash", "varianceMinor");
    }

    @Test
    void aZReportOfAnOpenShiftIsRefused() {
        ShopFixture.Shop shop = fixtures.seed();
        ShiftResponse shift = shifts.open(openRequest(shop, UUID.randomUUID()));

        // Otherwise the blind count has a printer-shaped hole in it.
        assertThatThrownBy(() -> reports.forShift(shift.id()))
                .hasMessageContaining("defeat the blind count");
    }

    // ----------------------------------------------------------------- expected cash

    @Test
    void expectedCashIsTheSumOfEveryEntry() {
        ShopFixture.Shop shop = fixtures.seed();
        ShiftResponse shift = shifts.open(openRequest(shop, UUID.randomUUID()));

        // A 450.00 sale settled with a 500.00 note: 500.00 in, 50.00 back out.
        ringUp(shop, 45_000, 50_000);
        // A second, exact.
        ringUp(shop, 45_000, 45_000);
        // 4,000.00 to the safe.
        cashMovements.record(
                new CreateCashMovementRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        null,
                        "DROP",
                        400_000L,
                        "SAFE_DROP",
                        null));
        // 1,000.00 of change put in.
        cashMovements.record(
                new CreateCashMovementRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        null,
                        "PAY_IN",
                        100_000L,
                        "CHANGE_FLOAT",
                        null));

        ShiftService.CashDrawer drawer = shifts.expectedCash(shift.id());

        assertThat(drawer.cashSalesMinor()).isEqualTo(95_000);
        assertThat(drawer.cashChangeMinor()).isEqualTo(5_000);
        assertThat(drawer.cashMovementsMinor()).isEqualTo(-300_000); // signed, no CASE
        assertThat(drawer.expectedMinor()).isEqualTo(500_000 + 95_000 - 5_000 - 300_000);
    }

    @Test
    void aPayOutCannotBeRecordedAsCashArriving() {
        ShopFixture.Shop shop = fixtures.seed();
        shifts.open(openRequest(shop, UUID.randomUUID()));

        cashMovements.record(
                new CreateCashMovementRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        null,
                        "PAY_OUT",
                        25_000L,
                        "SUPPLIER_PAYMENT",
                        null));

        // The cashier typed a positive 250.00; the sign came from the kind.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT amount_minor FROM cash_movements WHERE kind = 'PAY_OUT' ORDER BY id DESC LIMIT 1",
                                Long.class))
                .isEqualTo(-25_000);
    }

    @Test
    void aCashMovementNeedsAShiftAndAReason() {
        ShopFixture.Shop shop = fixtures.seed();

        assertThatThrownBy(
                        () ->
                                cashMovements.record(
                                        new CreateCashMovementRequest(
                                                UUID.randomUUID(),
                                                shop.branchCode(),
                                                "T1",
                                                null,
                                                "DROP",
                                                1_000L,
                                                "SAFE_DROP",
                                                null)))
                .hasMessageContaining("No shift is open");

        shifts.open(openRequest(shop, UUID.randomUUID()));
        assertThatThrownBy(
                        () ->
                                cashMovements.record(
                                        new CreateCashMovementRequest(
                                                UUID.randomUUID(),
                                                shop.branchCode(),
                                                "T1",
                                                null,
                                                "PAY_OUT",
                                                1_000L,
                                                "OTHER",
                                                "  ")))
                .hasMessageContaining("requires a note");
    }

    // ------------------------------------------------------------------------ close

    @Test
    void aBalancedShiftClosesWithNoReason() {
        ShopFixture.Shop shop = fixtures.seed();
        ShiftResponse shift = shifts.open(openRequest(shop, UUID.randomUUID()));
        ringUp(shop, 45_000, 45_000);

        // Float 5,000 + 450 cash = 5,450.00 — five 1000s, four 100s, one 50.
        ShiftResponse closed =
                shifts.close(
                        shift.id(),
                        new CloseShiftRequest(
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                                null,
                                null,
                                List.of(
                                        new DenominationCount(100_000L, 5),
                                        new DenominationCount(10_000L, 4),
                                        new DenominationCount(5_000L, 1)),
                                null,
                                null));

        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.countedCashMinor()).isEqualTo(545_000);
        assertThat(closed.expectedCashMinor()).isEqualTo(545_000);
        assertThat(closed.varianceMinor()).isZero();
    }

    @Test
    void aVarianceOverTheThresholdDemandsAReason() {
        ShopFixture.Shop shop = fixtures.seed();
        ShiftResponse shift = shifts.open(openRequest(shop, UUID.randomUUID()));

        // Counted 4,000.00 against a 5,000.00 float — 1,000.00 short, far over the LKR 100 default.
        List<DenominationCount> shortCount = List.of(new DenominationCount(100_000L, 4));

        assertThatThrownBy(
                        () -> shifts.close(shift.id(), new CloseShiftRequest(
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,null, null, shortCount, null, null)))
                .hasMessageContaining("a reason code is required");

        ShiftResponse closed =
                shifts.close(
                        shift.id(),
                        new CloseShiftRequest(
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,null, null, shortCount, "MISCOUNT", "recounted twice"));
        assertThat(closed.varianceMinor()).isEqualTo(-100_000);
        assertThat(closed.varianceReason()).isEqualTo("MISCOUNT");
    }

    /**
     * D1: the threshold is per-tenant, and the same variance is acceptable to one shop and not to
     * another. A hardcoded LKR 100 would be noise to a jeweller and an obstruction to a grocer.
     */
    @Test
    void theThresholdIsPerTenant() {
        ShopFixture.Shop shop = fixtures.seed();
        jdbc.update(
                """
                INSERT INTO tenant_settings (tenant_id, cash_variance_threshold_minor) VALUES (?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET cash_variance_threshold_minor = excluded.cash_variance_threshold_minor
                """,
                shop.tenantId(),
                200_000L);

        ShiftResponse shift = shifts.open(openRequest(shop, UUID.randomUUID()));
        ShiftResponse closed =
                shifts.close(
                        shift.id(),
                        new CloseShiftRequest(
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                                null, null, List.of(new DenominationCount(100_000L, 4)), null, null));

        // 1,000.00 short, and this shop's threshold is 2,000.00 — no reason needed.
        assertThat(closed.varianceMinor()).isEqualTo(-100_000);
        assertThat(closed.varianceReason()).isNull();
    }

    @Test
    void aReasonOfOtherNeedsAnActualNote() {
        ShopFixture.Shop shop = fixtures.seed();
        ShiftResponse shift = shifts.open(openRequest(shop, UUID.randomUUID()));

        assertThatThrownBy(
                        () ->
                                shifts.close(
                                        shift.id(),
                                        new CloseShiftRequest(
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                                                null,
                                                null,
                                                List.of(new DenominationCount(100_000L, 4)),
                                                "OTHER",
                                                "   ")))
                .hasMessageContaining("requires a note");
    }

    @Test
    void closingTwiceReturnsTheFiguresTheFirstCloseFroze() {
        ShopFixture.Shop shop = fixtures.seed();
        ShiftResponse shift = shifts.open(openRequest(shop, UUID.randomUUID()));

        ShiftResponse first =
                shifts.close(
                        shift.id(),
                        new CloseShiftRequest(
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                                null, null, List.of(new DenominationCount(100_000L, 5)), null, null));
        // A different count entirely: the resend must not recount, because a Z-report may already
        // have been printed against the first one.
        ShiftResponse again =
                shifts.close(
                        shift.id(),
                        new CloseShiftRequest(
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                                null, null, List.of(new DenominationCount(100_000L, 1)), null, null));

        assertThat(again.countedCashMinor()).isEqualTo(first.countedCashMinor());
        assertThat(again.expectedCashMinor()).isEqualTo(first.expectedCashMinor());
    }

    /**
     * The freeze the V107 header describes.
     *
     * <p>The stored figure must not move when the underlying rows change, because a Z-report has
     * already been printed, signed and filed against it.
     */
    @Test
    void theExpectedFigureIsFrozenAtClose() {
        ShopFixture.Shop shop = fixtures.seed();
        ShiftResponse shift = shifts.open(openRequest(shop, UUID.randomUUID()));
        ringUp(shop, 45_000, 45_000);

        ShiftResponse closed =
                shifts.close(
                        shift.id(),
                        new CloseShiftRequest(
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                                null,
                                null,
                                List.of(
                                        new DenominationCount(100_000L, 5),
                                        new DenominationCount(10_000L, 4),
                                        new DenominationCount(5_000L, 1)),
                                null,
                                null));

        // Something changes the shift's rows after the fact — the shape a late refund takes.
        jdbc.update(
                """
                INSERT INTO cash_movements (client_uuid, tenant_id, branch_id, shift_id, kind,
                                            amount_minor, reason_code, created_by)
                VALUES (?, ?, ?, ?, 'DROP', -50000, 'SAFE_DROP', ?)
                """,
                UUID.randomUUID(),
                shop.tenantId(),
                shop.branchId(),
                shift.id(),
                shop.managerId());

        Long stored =
                jdbc.queryForObject(
                        "SELECT expected_cash_minor FROM shifts WHERE id = ?", Long.class, shift.id());
        assertThat(stored).isEqualTo(closed.expectedCashMinor());
    }

    // --------------------------------------------------------------------- Z-report

    @Test
    void theZReportShowsTheWholeDerivation() {
        ShopFixture.Shop shop = fixtures.seed();
        ShiftResponse shift = shifts.open(openRequest(shop, UUID.randomUUID()));
        ringUp(shop, 45_000, 50_000);
        cashMovements.record(
                new CreateCashMovementRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        null,
                        "DROP",
                        100_000L,
                        "BANK_DROP",
                        null));

        shifts.close(
                shift.id(),
                new CloseShiftRequest(
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                        null,
                        null,
                        List.of(new DenominationCount(100_000L, 4), new DenominationCount(10_000L, 4)),
                        null,
                        null));

        ZReport report = reports.forShift(shift.id());

        assertThat(report.status()).isEqualTo("CLOSED");
        assertThat(report.saleCount()).isEqualTo(1);
        assertThat(report.openingFloatMinor()).isEqualTo(500_000);
        assertThat(report.cashSalesMinor()).isEqualTo(50_000);
        assertThat(report.cashChangeMinor()).isEqualTo(5_000);
        assertThat(report.cashMovementsMinor()).isEqualTo(-100_000);
        assertThat(report.countedCashMinor()).isEqualTo(440_000);
        // Every term is on the report, and they reconcile to the variance it prints.
        assertThat(
                        report.openingFloatMinor()
                                + report.cashSalesMinor()
                                + report.cashRoundingMinor()
                                + report.cashMovementsMinor()
                                - report.cashChangeMinor()
                                - report.cashRefundsMinor()
                                - report.cashRefundRoundingMinor())
                .isEqualTo(report.expectedCashMinor());
        assertThat(report.countedCashMinor() - report.expectedCashMinor()).isEqualTo(report.varianceMinor());

        // The closing count travels with it — one missing 5000 note reads very differently from
        // a hundred missing coins.
        assertThat(report.closingCount()).hasSize(2);
        assertThat(report.tendersByKind()).extracting(ZReport.TenderTotal::kind).containsExactly("CASH");
        assertThat(report.taxByRate()).extracting(ZReport.TaxBand::rateBp).containsExactly(1800);
    }

    // ------------------------------------------------------------------------ helpers

    private OpenShiftRequest openRequest(ShopFixture.Shop shop, UUID clientUuid) {
        return new OpenShiftRequest(clientUuid, shop.branchCode(), "T1",
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN, null, 500_000L, FLOAT_5000);
    }

    /** One line, settled in cash, with change if the tender exceeds the total. */
    private SaleResponse ringUp(ShopFixture.Shop shop, long totalMinor, long cashMinor) {
        long taxMinor = totalMinor * 1800 / 11800;
        long changeMinor = Math.max(0, cashMinor - roundToRupee(totalMinor));
        long roundingMinor = roundToRupee(totalMinor) - totalMinor;

        return sales.commit(
                new CreateSaleRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        totalMinor,
                        0L,
                        taxMinor,
                        totalMinor,
                        List.of(
                                new CreateSaleRequest.Line(
                                        shop.productUuid(), 1, totalMinor, 0L, taxMinor, totalMinor)),
                        roundingMinor,
                        changeMinor,
                        List.of(new CreateSaleRequest.Tender("CASH", cashMinor)), null));
    }

    private static long roundToRupee(long amountMinor) {
        return Math.floorDiv(amountMinor + 50, 100) * 100;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
