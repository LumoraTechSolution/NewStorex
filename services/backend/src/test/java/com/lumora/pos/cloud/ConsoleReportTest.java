package com.lumora.pos.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * What the console reads back (M4-05 … M4-07).
 *
 * <p>The cross-tenant assertions here are the point of the class. Every one of these queries takes a
 * tenant, and a leak in any of them would show one shop's takings to another owner — which is the
 * worst thing this product could do and would look, on the screen, exactly like it working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud")
class ConsoleReportTest {

    private static final String ZONE = "Asia/Colombo";

    @Autowired ConsoleReportService reports;
    @Autowired TenantCredentialService tenants;
    @Autowired JdbcTemplate jdbc;

    private long mine;
    private long theirs;

    @BeforeEach
    void twoShops() {
        mine = tenants.provision("Kandy Stores", "Till 1").tenantId();
        theirs = tenants.provision("Galle Stores", "Till 1").tenantId();
    }

    // ------------------------------------------------------------------------------- today

    @Test
    void todaySumsOnlyTodaysSales() {
        sale(mine, "KND", "T1", 90_000L, "today");
        sale(mine, "KND", "T1", 45_000L, "today");
        sale(mine, "KND", "T1", 10_000L, "yesterday");

        ConsoleReportService.Today today = reports.today(mine, ZONE);

        assertThat(today.totalMinor()).isEqualTo(135_000L);
        assertThat(today.saleCount()).isEqualTo(2);
    }

    @Test
    void todayIsZeroForAShopThatHasSoldNothing() {
        ConsoleReportService.Today today = reports.today(mine, ZONE);

        assertThat(today.totalMinor()).isZero();
        assertThat(today.saleCount()).isZero();
        // Null rather than a fabricated timestamp — "nothing has arrived" is information.
        assertThat(today.lastSyncAt()).isNull();
    }

    /** The assertion this whole class exists for. */
    @Test
    void todayNeverIncludesAnotherShopsTakings() {
        sale(mine, "KND", "T1", 90_000L, "today");
        sale(theirs, "GAL", "T1", 500_000L, "today");

        assertThat(reports.today(mine, ZONE).totalMinor()).isEqualTo(90_000L);
        assertThat(reports.today(theirs, ZONE).totalMinor()).isEqualTo(500_000L);
    }

    @Test
    void todayReportsWhenTheDataLastArrived() {
        sale(mine, "KND", "T1", 90_000L, "today");

        assertThat(reports.today(mine, ZONE).lastSyncAt()).isNotNull();
    }

    // ------------------------------------------------------------------------------- trend

    @Test
    void theTrendReturnsOneRowPerDayIncludingEmptyOnes() {
        sale(mine, "KND", "T1", 90_000L, "today");

        List<ConsoleReportService.DailyTotal> trend = reports.dailyTotals(mine, ZONE, 7);

        assertThat(trend).hasSize(7);
        // A closed Sunday must appear as a zero. Omitting it draws a chart that says the opposite
        // of what happened.
        assertThat(trend.subList(0, 6)).allSatisfy(d -> assertThat(d.totalMinor()).isZero());
        assertThat(trend.get(6).totalMinor()).isEqualTo(90_000L);
    }

    @Test
    void theTrendIsOldestFirst() {
        List<ConsoleReportService.DailyTotal> trend = reports.dailyTotals(mine, ZONE, 5);

        List<LocalDate> days = trend.stream().map(ConsoleReportService.DailyTotal::day).toList();
        assertThat(days).isSorted();
    }

    @Test
    void theTrendIsBoundedSoOneRequestCannotAskForEverything() {
        assertThat(reports.dailyTotals(mine, ZONE, 100_000)).hasSize(365);
        assertThat(reports.dailyTotals(mine, ZONE, 0)).hasSize(1);
    }

    @Test
    void theTrendNeverIncludesAnotherShop() {
        sale(theirs, "GAL", "T1", 500_000L, "today");

        assertThat(reports.dailyTotals(mine, ZONE, 3))
                .allSatisfy(d -> assertThat(d.totalMinor()).isZero());
    }

    // ---------------------------------------------------------------------------- branches

    @Test
    void branchesSplitTodayByBranchCode() {
        sale(mine, "KND", "T1", 90_000L, "today");
        sale(mine, "KND", "T2", 10_000L, "today");
        sale(mine, "COL", "T1", 250_000L, "today");

        List<ConsoleReportService.BranchTotal> branches = reports.branchTotals(mine, ZONE);

        assertThat(branches).hasSize(2);
        // Biggest first — the owner's eye goes to the top of the list.
        assertThat(branches.get(0).branchCode()).isEqualTo("COL");
        assertThat(branches.get(0).totalMinor()).isEqualTo(250_000L);
        assertThat(branches.get(1).totalMinor()).isEqualTo(100_000L);
    }

    @Test
    void branchesNeverIncludeAnotherShopsBranches() {
        sale(theirs, "GAL", "T1", 500_000L, "today");

        assertThat(reports.branchTotals(mine, ZONE)).isEmpty();
    }

    // --------------------------------------------------------------------------- attention

    @Test
    void attentionListsShiftsThatClosedOutOfBalance() {
        closedShift(mine, "KND", "T1", 25_000L);
        closedShift(mine, "KND", "T1", 500L);

        List<ConsoleReportService.CashVariance> flagged = reports.cashVariances(mine, 14, 10_000L);

        assertThat(flagged).hasSize(1);
        assertThat(flagged.get(0).varianceMinor()).isEqualTo(25_000L);
    }

    /** D1: a drawer that is over is not good news — it usually means a sale nobody rang up. */
    @Test
    void aDrawerThatIsOverIsFlaggedJustLikeOneThatIsShort() {
        closedShift(mine, "KND", "T1", 25_000L);
        closedShift(mine, "KND", "T2", -25_000L);

        assertThat(reports.cashVariances(mine, 14, 10_000L)).hasSize(2);
    }

    @Test
    void anOpenShiftIsNotAVariance() {
        openShift(mine, "KND", "T1");

        assertThat(reports.cashVariances(mine, 14, 0L)).isEmpty();
    }

    @Test
    void attentionNeverIncludesAnotherShopsShifts() {
        closedShift(theirs, "GAL", "T1", 99_000L);

        assertThat(reports.cashVariances(mine, 14, 10_000L)).isEmpty();
    }

    // ------------------------------------------------------------------------ recent sales

    @Test
    void recentSalesAreNewestFirstAndBounded() {
        sale(mine, "KND", "T1", 10_000L, "yesterday");
        sale(mine, "KND", "T1", 20_000L, "today");

        List<ConsoleReportService.RecentSale> recent = reports.recentSales(mine, 10);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).totalMinor()).isEqualTo(20_000L);
        assertThat(reports.recentSales(mine, 100_000)).hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void recentSalesNeverIncludeAnotherShop() {
        sale(theirs, "GAL", "T1", 500_000L, "today");

        assertThat(reports.recentSales(mine, 50)).isEmpty();
    }

    // ------------------------------------------------------------------------------- helpers

    /**
     * @param when {@code today} or {@code yesterday}, in the shop's own zone rather than the
     *     server's — which is the distinction the queries are built around.
     */
    private void sale(long tenantId, String branch, String terminal, long totalMinor, String when) {
        String soldAt =
                switch (when) {
                    case "today" -> "now()";
                    case "yesterday" -> "now() - interval '1 day'";
                    default -> throw new IllegalArgumentException(when);
                };
        jdbc.update(
                """
                INSERT INTO sales (
                    client_uuid, tenant_id, branch_code, terminal_code, invoice_number,
                    subtotal_minor, discount_minor, tax_minor, total_minor,
                    tax_mode, tax_rate_bp, sold_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, 'INCLUSIVE', 1800, %s)
                """
                        .formatted(soldAt),
                UUID.randomUUID(),
                tenantId,
                branch,
                terminal,
                "%s-%s-%s".formatted(branch, terminal, UUID.randomUUID()),
                totalMinor,
                totalMinor);
    }

    private void closedShift(long tenantId, String branch, String terminal, long varianceMinor) {
        jdbc.update(
                """
                INSERT INTO shifts (
                    client_uuid, tenant_id, branch_code, terminal_code,
                    status, opened_at, opening_float_minor, closed_at, counted_cash_minor,
                    expected_cash_minor, variance_minor)
                VALUES (?, ?, ?, ?, 'CLOSED', now() - interval '8 hours', 500000, now(), ?, ?, ?)
                """,
                UUID.randomUUID(),
                tenantId,
                branch,
                terminal,
                500_000L + varianceMinor,
                500_000L,
                varianceMinor);
    }

    private void openShift(long tenantId, String branch, String terminal) {
        jdbc.update(
                """
                INSERT INTO shifts (
                    client_uuid, tenant_id, branch_code, terminal_code, status, opened_at, opening_float_minor)
                VALUES (?, ?, ?, ?, 'OPEN', now(), 500000)
                """,
                UUID.randomUUID(),
                tenantId,
                branch,
                terminal);
    }
}
