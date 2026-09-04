package com.lumora.pos.crash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.sale.CreateSaleRequest;
import com.lumora.pos.sale.SaleResponse;
import com.lumora.pos.sale.SaleService;
import com.lumora.pos.shift.CloseShiftRequest;
import com.lumora.pos.shift.DenominationCount;
import com.lumora.pos.shift.OpenShiftRequest;
import com.lumora.pos.shift.ShiftService;
import com.lumora.pos.testfixtures.ShopFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * What survives the process dying halfway through (M5-07, M5-08).
 *
 * <h2>Why a test can assert this at all</h2>
 *
 * "Kill the process between writes" sounds like it needs a killed process. It does not, and a test
 * that actually killed a JVM would prove less: it could only ever sample one instant, chosen by
 * whatever the scheduler happened to do that run, and it would be flaky in the specific way that
 * teaches people to re-run rather than read.
 *
 * <p>What a crash between two writes <em>is</em>, to Postgres, is a transaction that never
 * committed. The connection drops, the server rolls back, and the question that matters — is there
 * a half-written sale on disk — is decided entirely by whether those writes shared one transaction.
 * So these tests break a write in the middle of the sequence and assert on what is left, which is
 * the same state a power cut produces and is reproducible every run.
 *
 * <p>The failure is injected with a database trigger, the technique {@code SaleCommitTest} already
 * uses for the outbox. A trigger fails the statement inside the server, exactly where a disk error
 * or a full volume would, rather than at some boundary a mock chose.
 *
 * <h2>What this deliberately does not claim</h2>
 *
 * Nothing here proves Postgres itself survives a power cut — that is fsync and WAL, and it is the
 * database's job rather than this codebase's. What is being tested is that <b>this application
 * groups its writes so that Postgres's guarantee is worth having</b>: a sale that is atomic
 * survives; six statements on autocommit would not, whatever the WAL did.
 *
 * <p>The real power cut, on real hardware, with the drawer open, is Gate M3's cable-pull and the
 * pilot. This is what makes attempting those reasonable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class CrashSafetyTest {

    @Autowired SaleService sales;
    @Autowired ShiftService shifts;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    private static final List<DenominationCount> FLOAT_5000 =
            List.of(new DenominationCount(100_000L, 5));

    @AfterEach
    void repair() {
        // Unconditionally, and in an @AfterEach rather than at the end of each test: a test that
        // fails partway would otherwise leave a trigger that fails every later test in the class
        // for a reason none of them mention.
        dropTrigger("trg_crash_sale_items", "fail_crash_write");
        dropTrigger("trg_crash_stock_movements", "fail_crash_write");
        dropTrigger("trg_crash_shift_counts", "fail_crash_write");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_crash_write()");
    }

    // ------------------------------------------------------------------ M5-07: the sale

    /**
     * A sale interrupted after its header is written leaves <b>nothing</b> — not a header with no
     * lines, and not a stock movement for goods that were never sold.
     *
     * <p>The header is written first and the lines follow, so failing on {@code sale_items} is
     * precisely the "between writes" moment M5-07 names. If these were separate transactions the
     * row in {@code sales} would still be there afterwards: an invoice number issued against a sale
     * with no contents, which reconciles to nothing and which an auditor reads as a gap.
     */
    @Test
    void aSaleInterruptedPartWayThroughLeavesNothingBehind() {
        ShopFixture.Shop shop = fixtures.seed();
        shifts.open(openRequest(shop, UUID.randomUUID()));

        UUID saleUuid = UUID.randomUUID();
        failOnInsertTo("sale_items");

        assertThatThrownBy(() -> sales.commit(saleRequest(shop, saleUuid, 45_000L)))
                .hasMessageContaining("simulated");

        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", saleUuid)).isZero();
        assertThat(count("SELECT count(*) FROM outbox WHERE aggregate_id = ?", saleUuid)).isZero();
    }

    /**
     * The stock movement and the sale stand or fall together.
     *
     * <p>This is the orphan M5-07 names, and it is the more dangerous direction: stock on hand is
     * {@code Σ movements} (§A — movements, not balances), so a movement that outlived its sale does
     * not merely look untidy. It permanently changes what the shop believes is on the shelf, and
     * nothing reconciles it back because there is no sale to reconcile against.
     */
    @Test
    void aFailedSaleLeavesNoStockMovementForGoodsThatNeverLeft() {
        ShopFixture.Shop shop = fixtures.seed();
        shifts.open(openRequest(shop, UUID.randomUUID()));

        long before = stockOnHand(shop);
        UUID saleUuid = UUID.randomUUID();

        // Fails after the sale, its lines *and* the stock movement have been written — the last
        // possible moment inside the transaction, which is the hardest one to get right.
        failOnInsertTo("stock_movements");

        assertThatThrownBy(() -> sales.commit(saleRequest(shop, saleUuid, 45_000L)))
                .hasMessageContaining("simulated");

        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", saleUuid)).isZero();
        assertThat(stockOnHand(shop)).isEqualTo(before);
    }

    /**
     * An interrupted sale does not burn its invoice number.
     *
     * <p>The number is allocated inside the caller's transaction — {@code InvoiceNumberAllocator}
     * has no transaction of its own, deliberately — so a rolled-back sale gives the number back.
     * The alternative is a sequence with holes in it that correspond to nothing, and an auditor
     * reading 1047, 1048, 1050 is entitled to ask what 1049 was.
     */
    @Test
    void anInterruptedSaleDoesNotBurnAnInvoiceNumber() {
        ShopFixture.Shop shop = fixtures.seed();
        shifts.open(openRequest(shop, UUID.randomUUID()));

        long nextBefore = nextInvoiceSeq(shop);

        failOnInsertTo("sale_items");
        assertThatThrownBy(() -> sales.commit(saleRequest(shop, UUID.randomUUID(), 45_000L)))
                .hasMessageContaining("simulated");
        repair();

        assertThat(nextInvoiceSeq(shop)).isEqualTo(nextBefore);

        // And the number is genuinely still available: the next real sale takes it.
        SaleResponse recovered = sales.commit(saleRequest(shop, UUID.randomUUID(), 45_000L));
        assertThat(recovered.invoiceNumber()).endsWith("%06d".formatted(nextBefore));
    }

    /**
     * The till recovers to a working state rather than to a locked one.
     *
     * <p>The point of M5-07 is not only that nothing is corrupt — it is that the shop can carry on
     * selling. A cashier whose till died mid-sale re-rings it, and that must simply work.
     */
    @Test
    void theNextSaleAfterACrashSucceedsNormally() {
        ShopFixture.Shop shop = fixtures.seed();
        shifts.open(openRequest(shop, UUID.randomUUID()));

        failOnInsertTo("sale_items");
        assertThatThrownBy(() -> sales.commit(saleRequest(shop, UUID.randomUUID(), 45_000L)))
                .hasMessageContaining("simulated");
        repair();

        UUID retried = UUID.randomUUID();
        SaleResponse response = sales.commit(saleRequest(shop, retried, 45_000L));

        assertThat(response.invoiceNumber()).isNotBlank();
        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", retried)).isEqualTo(1);
        // The outbox row is written in the same transaction as the sale (§A), so a recovered sale
        // is a synced sale. A sale the cloud never hears about is the failure the outbox exists for.
        assertThat(count("SELECT count(*) FROM outbox WHERE aggregate_id = ?", retried)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ M5-08: the shift

    /**
     * A power cut mid-sale leaves the shift open and tradeable.
     *
     * <p>The shift is the thing a cashier cannot recreate: it holds the opening float somebody
     * counted note by note at the start of the day. A crashed sale that took the shift with it
     * would mean recounting a drawer that has since been traded from, and the day's variance would
     * be meaningless.
     */
    @Test
    void aCrashDuringASaleLeavesTheShiftOpenAndUsable() {
        ShopFixture.Shop shop = fixtures.seed();
        UUID shiftUuid = UUID.randomUUID();
        shifts.open(openRequest(shop, shiftUuid));

        failOnInsertTo("sale_items");
        assertThatThrownBy(() -> sales.commit(saleRequest(shop, UUID.randomUUID(), 45_000L)))
                .hasMessageContaining("simulated");
        repair();

        assertThat(shifts.status(shop.branchCode(), "T1").open()).isTrue();
        assertThat(
                        count(
                                "SELECT count(*) FROM shifts WHERE client_uuid = ? AND status = 'OPEN'",
                                shiftUuid))
                .isEqualTo(1);
        assertThat(openingFloat(shiftUuid)).isEqualTo(500_000L);
    }

    /**
     * An interrupted close leaves the shift open rather than half-closed.
     *
     * <p>Half-closed is the state that has no recovery: {@code ux_shifts_one_open_per_terminal}
     * means a shift stuck in some in-between status would block every future shift on that
     * terminal, and the till would refuse to trade the next morning with an error about a shift
     * nobody can find. Open is recoverable — you close it again.
     */
    @Test
    void anInterruptedCloseLeavesTheShiftOpenRatherThanHalfClosed() {
        ShopFixture.Shop shop = fixtures.seed();
        UUID shiftUuid = UUID.randomUUID();
        shifts.open(openRequest(shop, shiftUuid));
        ringUp(shop, 45_000L);

        long shiftId = shiftId(shiftUuid);
        // The closing denomination count is written as part of closing, so failing here is an
        // interruption partway through the close itself.
        failOnInsertTo("shift_counts");

        assertThatThrownBy(() -> shifts.close(shiftId, closeRequest()))
                .hasMessageContaining("simulated");
        repair();

        assertThat(statusOf(shiftUuid)).isEqualTo("OPEN");
        assertThat(shifts.status(shop.branchCode(), "T1").open()).isTrue();
    }

    /**
     * And the shift can then actually be closed, which is the half that matters to the shopkeeper
     * standing there at eight in the evening.
     */
    @Test
    void aShiftWhoseCloseWasInterruptedCanStillBeClosed() {
        ShopFixture.Shop shop = fixtures.seed();
        UUID shiftUuid = UUID.randomUUID();
        shifts.open(openRequest(shop, shiftUuid));
        ringUp(shop, 45_000L);

        long shiftId = shiftId(shiftUuid);
        failOnInsertTo("shift_counts");
        assertThatThrownBy(() -> shifts.close(shiftId, closeRequest())).hasMessageContaining("simulated");
        repair();

        shifts.close(shiftId, closeRequest());

        assertThat(statusOf(shiftUuid)).isEqualTo("CLOSED");
        assertThat(shifts.status(shop.branchCode(), "T1").open()).isFalse();
        // The sale rung up before the interruption is still in the shift it belonged to. A close
        // that lost it would understate the day's takings against a drawer that holds the cash.
        assertThat(count("SELECT count(*) FROM sales WHERE shift_id = ?", shiftId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Makes the next insert into {@code table} fail inside the server.
     *
     * <p>A trigger rather than a mock: it fails the statement where a disk error or a full volume
     * would, so the transaction is aborted by Postgres itself rather than by a stub deciding to
     * throw. That distinction is the whole point — what is being tested is Postgres's rollback,
     * and a mock would test the mock.
     */
    private void failOnInsertTo(String table) {
        jdbc.execute(
                """
                CREATE OR REPLACE FUNCTION fail_crash_write() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'simulated crash during write'; END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute(
                "CREATE TRIGGER trg_crash_%s BEFORE INSERT ON %s FOR EACH ROW EXECUTE FUNCTION fail_crash_write()"
                        .formatted(table, table));
    }

    private void dropTrigger(String trigger, String ignoredFunction) {
        String table = trigger.replace("trg_crash_", "");
        jdbc.execute("DROP TRIGGER IF EXISTS %s ON %s".formatted(trigger, table));
    }

    private OpenShiftRequest openRequest(ShopFixture.Shop shop, UUID clientUuid) {
        return new OpenShiftRequest(
                clientUuid,
                shop.branchCode(),
                "T1",
                ShopFixture.MANAGER_CODE,
                ShopFixture.MANAGER_PIN,
                null,
                500_000L,
                FLOAT_5000);
    }

    private CloseShiftRequest closeRequest() {
        return new CloseShiftRequest(
                ShopFixture.MANAGER_CODE,
                ShopFixture.MANAGER_PIN,
                null,
                // Deliberately not reconciled to the penny. These tests are about what survives a
                // crash, and a variance reason is how a real close of a real drawer looks.
                500_000L,
                FLOAT_5000,
                "MISCOUNT",
                "Closed after an interrupted attempt");
    }

    private SaleResponse ringUp(ShopFixture.Shop shop, long totalMinor) {
        return sales.commit(saleRequest(shop, UUID.randomUUID(), totalMinor));
    }

    private CreateSaleRequest saleRequest(ShopFixture.Shop shop, UUID clientUuid, long totalMinor) {
        long taxMinor = totalMinor * 1800 / 11800;
        long rounded = Math.floorDiv(totalMinor + 50, 100) * 100;
        return new CreateSaleRequest(
                clientUuid,
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
                rounded - totalMinor,
                0L,
                List.of(new CreateSaleRequest.Tender("CASH", rounded)),
                null);
    }

    private long stockOnHand(ShopFixture.Shop shop) {
        Long sum =
                jdbc.queryForObject(
                        """
                        SELECT coalesce(sum(m.qty_delta), 0) FROM stock_movements m
                          JOIN products p ON p.id = m.product_id
                         WHERE p.client_uuid = ?
                        """,
                        Long.class,
                        shop.productUuid());
        return sum == null ? 0 : sum;
    }

    private long nextInvoiceSeq(ShopFixture.Shop shop) {
        // queryForList rather than queryForObject: before the first sale on a terminal there is no
        // counter row at all — the block is created lazily by InvoiceNumberAllocator — and
        // queryForObject throws on an empty result rather than returning null. "No row" is a real
        // and expected state here, not an error.
        List<Long> found =
                jdbc.queryForList(
                        """
                        SELECT next_seq FROM invoice_counters
                         WHERE tenant_id = ? AND branch_id = ? AND terminal_code = 'T1'
                           AND doc_type = 'INVOICE'
                        """,
                        Long.class,
                        shop.tenantId(),
                        shop.branchId());
        // Nothing issued on this terminal yet, so the next number is the first one.
        return found.isEmpty() ? 1 : found.get(0);
    }

    private long shiftId(UUID clientUuid) {
        return jdbc.queryForObject("SELECT id FROM shifts WHERE client_uuid = ?", Long.class, clientUuid);
    }

    private String statusOf(UUID clientUuid) {
        return jdbc.queryForObject(
                "SELECT status FROM shifts WHERE client_uuid = ?", String.class, clientUuid);
    }

    private long openingFloat(UUID clientUuid) {
        return jdbc.queryForObject(
                "SELECT opening_float_minor FROM shifts WHERE client_uuid = ?", Long.class, clientUuid);
    }

    private int count(String sql, Object... args) {
        Integer found = jdbc.queryForObject(sql, Integer.class, args);
        return found == null ? 0 : found;
    }
}
