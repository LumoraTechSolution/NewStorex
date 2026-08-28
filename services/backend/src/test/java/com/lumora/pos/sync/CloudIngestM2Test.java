package com.lumora.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.lumora.pos.cloud.TenantCredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Shifts, cash movements and refunds reaching the cloud (M2-12).
 *
 * <p>Its own class rather than more methods on {@code CloudIngestTest}: that one is about the M0
 * loop and the sale aggregate, and this is about the three that arrived with M2 — including the
 * first aggregate in the system that is <em>not</em> immutable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud")
class CloudIngestM2Test {

    private static final UUID PRODUCT = UUID.fromString("00000000-0000-4000-8000-000000000166");

    @Autowired SyncIngestService ingest;
    @Autowired TenantCredentialService credentials;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    /**
     * Provisioned per test rather than shared (M4-01). A tenant can no longer create itself by
     * pushing, so every test needs one to exist first — and giving each its own is what keeps a
     * test asserting on a running total from reading rows the previous test left behind.
     */
    private long tenantId;

    @BeforeEach
    void provisionTenant() {
        tenantId = credentials.provision("Kandy Stores", "Till 1").tenantId();
    }

    // ------------------------------------------------------------------------- shift

    @Test
    void aShiftArrivesOpenAndThenCloses() {
        UUID shiftUuid = UUID.randomUUID();

        ingest.ingest(tenantId, batch("shift", shiftUuid, openShiftPayload(shiftUuid)));
        assertThat(status(shiftUuid)).isEqualTo("OPEN");
        assertThat(scalarLong("SELECT expected_cash_minor FROM shifts WHERE client_uuid = ?", shiftUuid))
                .isNull();

        ingest.ingest(tenantId, batch("shift", shiftUuid, closedShiftPayload(shiftUuid)));
        assertThat(status(shiftUuid)).isEqualTo("CLOSED");
        assertThat(scalarLong("SELECT variance_minor FROM shifts WHERE client_uuid = ?", shiftUuid))
                .isEqualTo(-15_000);
        assertThat(count("SELECT count(*) FROM shifts WHERE client_uuid = ?", shiftUuid)).isEqualTo(1);
    }

    /**
     * The failure the V203 header exists for.
     *
     * <p>The open row can fail, be backed off, and land <em>after</em> the close row. Without the
     * monotonic guard that would reopen a shift the shop has already counted, signed and filed —
     * and the console would show a till still trading hours after it went home.
     */
    @Test
    void anOpenRowArrivingAfterACloseCannotReopenTheShift() {
        UUID shiftUuid = UUID.randomUUID();

        ingest.ingest(tenantId, batch("shift", shiftUuid, closedShiftPayload(shiftUuid)));
        SyncBatchResult late = ingest.ingest(tenantId, batch("shift", shiftUuid, openShiftPayload(shiftUuid)));

        // Accepted, not rejected: the delivery succeeded, it simply had nothing left to say.
        assertThat(late.accepted()).containsExactly(shiftUuid);
        assertThat(status(shiftUuid)).isEqualTo("CLOSED");
        assertThat(scalarLong("SELECT variance_minor FROM shifts WHERE client_uuid = ?", shiftUuid))
                .isEqualTo(-15_000);
    }

    @Test
    void theDenominationCountTravelsWithTheShift() {
        UUID shiftUuid = UUID.randomUUID();
        ingest.ingest(tenantId, batch("shift", shiftUuid, closedShiftPayload(shiftUuid)));

        // One missing 5000 note reads very differently from a hundred missing coins, and the
        // console cannot ask an offline till for the detail later.
        assertThat(count(
                        """
                        SELECT count(*) FROM shift_counts
                         WHERE shift_id = (SELECT id FROM shifts WHERE client_uuid = ?)
                        """,
                        shiftUuid))
                .isEqualTo(2);
    }

    // ----------------------------------------------------------------- cash movement

    @Test
    void aCashMovementKeepsItsSign() {
        UUID shiftUuid = UUID.randomUUID();
        UUID movementUuid = UUID.randomUUID();
        ingest.ingest(tenantId, batch("shift", shiftUuid, openShiftPayload(shiftUuid)));

        ingest.ingest(tenantId, batch("cash_movement", movementUuid, cashMovementPayload(movementUuid, shiftUuid)));

        assertThat(scalarLong("SELECT amount_minor FROM cash_movements WHERE client_uuid = ?", movementUuid))
                .isEqualTo(-400_000);
    }

    @Test
    void redeliveringACashMovementAddsNothing() {
        UUID shiftUuid = UUID.randomUUID();
        UUID movementUuid = UUID.randomUUID();
        SyncBatch batch = batch("cash_movement", movementUuid, cashMovementPayload(movementUuid, shiftUuid));

        ingest.ingest(tenantId, batch);
        ingest.ingest(tenantId, batch);
        ingest.ingest(tenantId, batch);

        assertThat(count("SELECT count(*) FROM cash_movements WHERE client_uuid = ?", movementUuid))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------ refund

    @Test
    void aRefundLandsWithItsLinesTendersAndRestockMovement() {
        UUID refundUuid = UUID.randomUUID();
        UUID saleUuid = UUID.randomUUID();
        UUID movementUuid = UUID.randomUUID();

        SyncBatchResult result =
                ingest.ingest(tenantId, 
                        batch("refund", refundUuid, refundPayload(refundUuid, saleUuid, movementUuid, true)));

        assertThat(result.accepted()).containsExactly(refundUuid);
        assertThat(refundField(refundUuid, "sale_client_uuid")).isEqualTo(saleUuid.toString());
        assertThat(count(
                        "SELECT count(*) FROM refund_items WHERE refund_id = (SELECT id FROM refunds WHERE client_uuid = ?)",
                        refundUuid))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM refund_payments WHERE refund_id = (SELECT id FROM refunds WHERE client_uuid = ?)",
                        refundUuid))
                .isEqualTo(1);

        // Stock comes back as a positive movement, exactly as the sale wrote a negative one.
        assertThat(scalarLong("SELECT qty_delta FROM stock_movements WHERE client_uuid = ?", movementUuid))
                .isEqualTo(1);
    }

    /**
     * A refund may reach the cloud before the sale it reverses.
     *
     * <p>Rejecting it for that would mean a shop's backlog could only ever drain in one order,
     * which is not a property an outbox with per-row backoff has.
     */
    @Test
    void aRefundIsAcceptedEvenWithNoSaleForItYet() {
        UUID refundUuid = UUID.randomUUID();
        UUID orphanSale = UUID.randomUUID();

        SyncBatchResult result =
                ingest.ingest(tenantId, 
                        batch("refund", refundUuid, refundPayload(refundUuid, orphanSale, UUID.randomUUID(), true)));

        assertThat(result.rejected()).isEmpty();
        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", orphanSale)).isZero();
        assertThat(count("SELECT count(*) FROM refunds WHERE client_uuid = ?", refundUuid)).isEqualTo(1);
    }

    @Test
    void aDamagedLineBringsNoStockBack() {
        UUID refundUuid = UUID.randomUUID();
        int before = count("SELECT count(*) FROM stock_movements WHERE product_client_uuid = ?", PRODUCT);

        ingest.ingest(tenantId, 
                batch("refund", refundUuid, refundPayload(refundUuid, UUID.randomUUID(), null, false)));

        // Damaged goods are not inventory. A RETURN movement here would tell the owner they hold
        // stock they cannot sell.
        assertThat(count("SELECT count(*) FROM stock_movements WHERE product_client_uuid = ?", PRODUCT))
                .isEqualTo(before);
    }

    @Test
    void redeliveringARefundChangesNothing() {
        UUID refundUuid = UUID.randomUUID();
        UUID movementUuid = UUID.randomUUID();
        SyncBatch batch =
                batch("refund", refundUuid, refundPayload(refundUuid, UUID.randomUUID(), movementUuid, true));

        ingest.ingest(tenantId, batch);
        ingest.ingest(tenantId, batch);
        ingest.ingest(tenantId, batch);

        assertThat(count(
                        "SELECT count(*) FROM refund_items WHERE refund_id = (SELECT id FROM refunds WHERE client_uuid = ?)",
                        refundUuid))
                .isEqualTo(1);
        // Stock on hand is Σ these rows, so a movement re-added on every retry would drift the
        // level upwards by a return's worth each time.
        assertThat(count("SELECT count(*) FROM stock_movements WHERE client_uuid = ?", movementUuid))
                .isEqualTo(1);
    }

    // ----------------------------------------------------------------------- payloads

    private String openShiftPayload(UUID shiftUuid) {
        return """
               {
                 "clientUuid": "%s",
                 "branchCode": "KND",
                 "terminalCode": "T1",
                 "status": "OPEN",
                 "openedAt": "2026-08-20T03:30:00Z",
                 "openingFloatMinor": 500000,
                 "closedAt": null,
                 "countedCashMinor": null,
                 "expectedCashMinor": null,
                 "varianceMinor": null,
                 "varianceReason": null,
                 "varianceNote": null,
                 "counts": [{"phase": "OPEN", "denominationMinor": 100000, "qty": 5}]
               }
               """
                .formatted(shiftUuid);
    }

    private String closedShiftPayload(UUID shiftUuid) {
        return """
               {
                 "clientUuid": "%s",
                 "branchCode": "KND",
                 "terminalCode": "T1",
                 "status": "CLOSED",
                 "openedAt": "2026-08-20T03:30:00Z",
                 "openingFloatMinor": 500000,
                 "closedAt": "2026-08-20T13:30:00Z",
                 "countedCashMinor": 830000,
                 "expectedCashMinor": 845000,
                 "varianceMinor": -15000,
                 "varianceReason": "MISCOUNT",
                 "varianceNote": "recounted twice",
                 "counts": [
                   {"phase": "OPEN", "denominationMinor": 100000, "qty": 5},
                   {"phase": "CLOSE", "denominationMinor": 100000, "qty": 8}
                 ]
               }
               """
                .formatted(shiftUuid);
    }

    private String cashMovementPayload(UUID movementUuid, UUID shiftUuid) {
        return """
               {
                 "clientUuid": "%s",
                 "branchCode": "KND",
                 "shiftClientUuid": "%s",
                 "kind": "DROP",
                 "amountMinor": -400000,
                 "reasonCode": "SAFE_DROP",
                 "note": null,
                 "occurredAt": "2026-08-20T09:00:00Z"
               }
               """
                .formatted(movementUuid, shiftUuid);
    }

    private String refundPayload(UUID refundUuid, UUID saleUuid, UUID movementUuid, boolean restock) {
        String movement =
                movementUuid == null
                        ? "null"
                        : """
                          {"clientUuid": "%s", "productClientUuid": "%s", "qtyDelta": 1, "reason": "RETURN"}
                          """
                                .formatted(movementUuid, PRODUCT);
        return """
               {
                 "clientUuid": "%s",
                 "branchCode": "KND",
                 "terminalCode": "T1",
                 "shiftClientUuid": "%s",
                 "saleClientUuid": "%s",
                 "saleInvoiceNumber": "KND-T1-000042",
                 "creditNoteNumber": "KND-T1-CN-%06d",
                 "totalMinor": 45000,
                 "taxMinor": 6864,
                 "roundingAdjustmentMinor": 0,
                 "refundedAt": "2026-08-20T11:00:00Z",
                 "lines": [{
                   "lineNo": 1,
                   "saleLineNo": 1,
                   "productClientUuid": "%s",
                   "qty": 1,
                   "unitPriceMinor": 45000,
                   "refundTotalMinor": 45000,
                   "taxMinor": 6864,
                   "taxMode": "INCLUSIVE",
                   "taxRateBp": 1800,
                   "reasonCode": "%s",
                   "note": null,
                   "restock": %s,
                   "movement": %s
                 }],
                 "tenders": [{"lineNo": 1, "kind": "CASH", "amountMinor": 45000}]
               }
               """
                .formatted(
                        refundUuid,
                        UUID.randomUUID(),
                        saleUuid,
                        Math.abs(refundUuid.hashCode() % 900_000) + 1,
                        PRODUCT,
                        restock ? "CHANGED_MIND" : "DAMAGED",
                        restock,
                        movement);
    }

    // ------------------------------------------------------------------------ helpers

    // ---------------------------------------------------------------- goods receipts

    /**
     * A delivery reaches the cloud as movements (M3-04).
     *
     * <p>The point of the test is that the outbox row <em>drains</em>. An aggregate kind the ingest
     * does not know is rejected and backed off forever, which is the M2 trap recorded in §G — the
     * till looks fine, the cloud silently never gets the shop's stock, and the symptom reads like a
     * code bug in the sync loop.
     */
    @Test
    void aGoodsReceiptArrivesAsStockGoingOnTheShelf() {
        UUID receiptUuid = UUID.randomUUID();
        UUID movementUuid = UUID.randomUUID();

        var result = ingest.ingest(tenantId, batch("goods_receipt", receiptUuid, receiptPayload(receiptUuid, movementUuid)));

        assertThat(result.rejected()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT qty_delta FROM stock_movements WHERE client_uuid = ?",
                                Integer.class,
                                movementUuid))
                .isEqualTo(24);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason FROM stock_movements WHERE client_uuid = ?",
                                String.class,
                                movementUuid))
                .isEqualTo("RECEIVE");
    }

    /**
     * Redelivery adds nothing.
     *
     * <p>On hand is Σ qty_delta, so a batch that arrives twice and inserts twice does not duplicate
     * a row somebody can spot — it silently doubles the shop's stock.
     */
    @Test
    void aRedeliveredGoodsReceiptDoesNotAddTheStockAgain() {
        UUID receiptUuid = UUID.randomUUID();
        UUID movementUuid = UUID.randomUUID();
        String payload = receiptPayload(receiptUuid, movementUuid);

        ingest.ingest(tenantId, batch("goods_receipt", receiptUuid, payload));
        ingest.ingest(tenantId, batch("goods_receipt", receiptUuid, payload));

        assertThat(count("SELECT count(*) FROM stock_movements WHERE client_uuid = ?", movementUuid))
                .isEqualTo(1);
    }

    private String receiptPayload(UUID receiptUuid, UUID movementUuid) {
        return """
               {
                 "clientUuid": "%s",
                 "branchCode": "KND",
                 "supplierName": "Keells",
                 "reference": "DN-1001",
                 "receivedAt": "2026-08-22T04:15:00Z",
                 "totalCostMinor": 720000,
                 "movements": [
                   {
                     "clientUuid": "%s",
                     "productClientUuid": "%s",
                     "qtyDelta": 24,
                     "reason": "RECEIVE"
                   }
                 ]
               }
               """
                .formatted(receiptUuid, movementUuid, PRODUCT);
    }

    private SyncBatch batch(String aggregate, UUID aggregateId, String payload) {
        return new SyncBatch(List.of(new SyncBatch.Item(aggregate, aggregateId, json(payload))));
    }

    private String status(UUID shiftUuid) {
        return jdbc.queryForObject(
                "SELECT status FROM shifts WHERE client_uuid = ?", String.class, shiftUuid);
    }

    private String refundField(UUID refundUuid, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + "::text FROM refunds WHERE client_uuid = ?", String.class, refundUuid);
    }

    private Long scalarLong(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
