package com.lumora.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The cloud half of the M0 loop.
 *
 * <p>Runs against its own database: the cloud schema shares table names with the desktop one but is
 * a different shape, so migrating both into `lumora_test` would leave whichever Flyway ran last
 * quietly wiping the other's tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud")
class CloudIngestTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    /**
     * Its own product, not the one {@link #saleItem} uses: the movement tests assert on a running
     * sum, so they must not be reading rows some other test in this class left behind.
     */
    private static final UUID PRODUCT = UUID.fromString("00000000-0000-4000-8000-000000000155");

    @Autowired SyncIngestService ingest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void aBatchLandsAsASaleWithItsLines() {
        UUID saleUuid = UUID.randomUUID();

        SyncBatchResult result = ingest.ingest(batchWith(saleUuid, "KND-T1-000001"));

        assertThat(result.accepted()).containsExactly(saleUuid);
        assertThat(result.rejected()).isEmpty();

        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", saleUuid)).isEqualTo(1);
        Integer lines =
                jdbc.queryForObject(
                        "SELECT count(*) FROM sale_items WHERE sale_id = (SELECT id FROM sales WHERE client_uuid = ?)",
                        Integer.class,
                        saleUuid);
        assertThat(lines).isEqualTo(1);
    }

    /**
     * The property the entire retry design rests on. The drain must never need to know whether its
     * last attempt got through before the connection dropped.
     */
    @Test
    void redeliveringTheSameBatchChangesNothing() {
        UUID saleUuid = UUID.randomUUID();
        SyncBatch batch = batchWith(saleUuid, "KND-T1-000002");

        ingest.ingest(batch);
        int salesAfterFirst = count("SELECT count(*) FROM sales");
        int itemsAfterFirst = count("SELECT count(*) FROM sale_items");

        SyncBatchResult second = ingest.ingest(batch);
        SyncBatchResult third = ingest.ingest(batch);

        assertThat(second.accepted()).containsExactly(saleUuid);
        assertThat(third.accepted()).containsExactly(saleUuid);
        assertThat(count("SELECT count(*) FROM sales")).isEqualTo(salesAfterFirst);
        assertThat(count("SELECT count(*) FROM sale_items")).isEqualTo(itemsAfterFirst);
    }

    /** One poisonous aggregate must cost exactly itself, not the batch around it. */
    @Test
    void oneBadItemDoesNotStrandTheGoodOnesBehindIt() {
        UUID good = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        UUID alsoGood = UUID.randomUUID();

        SyncBatch batch =
                new SyncBatch(
                        TENANT,
                        "Kandy Stores",
                        List.of(
                                saleItem(good, "KND-T1-000010"),
                                new SyncBatch.Item("sale", bad, json("{\"branchCode\":\"KND\"}")),
                                saleItem(alsoGood, "KND-T1-000011")));

        SyncBatchResult result = ingest.ingest(batch);

        assertThat(result.accepted()).containsExactly(good, alsoGood);
        assertThat(result.rejected()).hasSize(1);
        assertThat(result.rejected().get(0).aggregateId()).isEqualTo(bad);
        assertThat(result.rejected().get(0).reason()).contains("terminalCode");

        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", good)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", alsoGood)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", bad)).isZero();
    }

    @Test
    void anUnknownAggregateKindIsRejectedNotCrashed() {
        UUID id = UUID.randomUUID();
        SyncBatch batch =
                new SyncBatch(
                        TENANT,
                        "Kandy Stores",
                        List.of(new SyncBatch.Item("loyalty_adjustment", id, json("{}"))));

        SyncBatchResult result = ingest.ingest(batch);

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected().get(0).reason()).contains("Unsupported aggregate kind");
    }

    /**
     * M1-11: tenders, the rounding adjustment and change ride inside the same sale payload and
     * land in their own table, the same way lines do.
     */
    @Test
    void aBatchWithSplitTendersLandsInSalePayments() {
        UUID saleUuid = UUID.randomUUID();
        String payload =
                """
                {
                  "clientUuid": "%s",
                  "branchCode": "KND",
                  "terminalCode": "T1",
                  "invoiceNumber": "KND-T1-000030",
                  "soldAt": "2026-08-12T04:30:00Z",
                  "taxMode": "INCLUSIVE",
                  "taxRateBp": 1800,
                  "subtotalMinor": 90000,
                  "discountMinor": 0,
                  "taxMinor": 13728,
                  "totalMinor": 90000,
                  "roundingAdjustmentMinor": 0,
                  "changeMinor": 10000,
                  "lines": [{
                    "productClientUuid": "00000000-0000-4000-8000-000000000101",
                    "lineNo": 1, "qty": 2,
                    "unitPriceMinor": 45000, "discountMinor": 0,
                    "taxMinor": 13728, "lineTotalMinor": 90000
                  }],
                  "tenders": [
                    { "lineNo": 1, "kind": "CARD", "amountMinor": 60000 },
                    { "lineNo": 2, "kind": "CASH", "amountMinor": 40000 }
                  ]
                }
                """
                        .formatted(saleUuid);

        SyncBatchResult result =
                ingest.ingest(new SyncBatch(TENANT, "Kandy Stores", List.of(new SyncBatch.Item("sale", saleUuid, json(payload)))));

        assertThat(result.accepted()).containsExactly(saleUuid);

        Long saleId = jdbc.queryForObject("SELECT id FROM sales WHERE client_uuid = ?", Long.class, saleUuid);
        assertThat(jdbc.queryForObject("SELECT change_minor FROM sales WHERE id = ?", Long.class, saleId))
                .isEqualTo(10000L);

        List<String> kinds =
                jdbc.queryForList(
                        "SELECT kind FROM sale_payments WHERE sale_id = ? ORDER BY line_no",
                        String.class,
                        saleId);
        assertThat(kinds).containsExactly("CARD", "CASH");
    }

    /** M1-15: the stock a sale moved has to arrive with it, or the cloud's on-hand is fiction. */
    @Test
    void aSaleCarriesItsStockMovementsIntoTheCloud() {
        UUID saleUuid = UUID.randomUUID();
        UUID movementUuid = UUID.randomUUID();

        SyncBatchResult result =
                ingest.ingest(batchWithMovements(saleUuid, "KND-T1-000040", movementUuid));

        assertThat(result.accepted()).containsExactly(saleUuid);

        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT branch_code, qty_delta, reason, occurred_at FROM stock_movements WHERE client_uuid = ?",
                        movementUuid);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("branch_code", "KND")
                .containsEntry("qty_delta", -2)
                .containsEntry("reason", "SALE");
        // The movement happened when the sale did, not when the network came back.
        assertThat(rows.get(0).get("occurred_at").toString()).startsWith("2026-08-12");
    }

    /**
     * The one that matters. On hand is Σ qty_delta, so a redelivered batch that inserted its
     * movements again would not duplicate a visible row so much as silently change the answer to
     * "how much stock is there" — the failure mode that has no error message.
     */
    @Test
    void redeliveringABatchDoesNotMoveStockTwice() {
        UUID saleUuid = UUID.randomUUID();
        UUID movementUuid = UUID.randomUUID();
        SyncBatch batch = batchWithMovements(saleUuid, "KND-T1-000041", movementUuid);

        ingest.ingest(batch);
        int onHandAfterFirst = onHandOf(PRODUCT);

        ingest.ingest(batch);
        ingest.ingest(batch);

        assertThat(count("SELECT count(*) FROM stock_movements WHERE client_uuid = ?", movementUuid))
                .isEqualTo(1);
        assertThat(onHandOf(PRODUCT)).isEqualTo(onHandAfterFirst);
    }

    /**
     * A till older than M1-15 sends no movements at all. Its sale must still ingest — the same
     * tolerance the M1-11 tender fields get — because rejecting the batch would strand every sale
     * behind it in the shop's outbox.
     */
    @Test
    void aSaleWithoutMovementsStillIngests() {
        UUID saleUuid = UUID.randomUUID();

        SyncBatchResult result = ingest.ingest(batchWith(saleUuid, "KND-T1-000042"));

        assertThat(result.accepted()).containsExactly(saleUuid);
        assertThat(result.rejected()).isEmpty();
    }

    /** The same rule as the shop PC: the cloud holds movements, never a level to keep in step. */
    @Test
    void theCloudSchemaStoresNoStockLevel() {
        Integer levelColumns =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND column_name IN ('quantity_on_hand', 'qty_on_hand', 'stock_level',
                                              'stock_on_hand', 'balance', 'current_stock')
                        """,
                        Integer.class);
        assertThat(levelColumns).isZero();
    }

    @Test
    void theTenantIsCreatedOnFirstSight() {
        ingest.ingest(batchWith(UUID.randomUUID(), "KND-T1-000020"));
        assertThat(count("SELECT count(*) FROM tenants WHERE client_uuid = ?", TENANT)).isEqualTo(1);
    }

    // -------------------------------------------------------------------- helpers

    // ------------------------------------------------------------------ M1-18: per-line rates

    @Test
    void aMixedRateSaleKeepsEachLineRateAcrossTheWire() {
        UUID saleUuid = UUID.randomUUID();

        ingest.ingest(mixedRateBatch(saleUuid, "KND-T1-000200"));

        List<Map<String, Object>> items =
                jdbc.queryForList(
                        "SELECT line_no, tax_mode, tax_rate_bp FROM sale_items WHERE sale_id ="
                                + " (SELECT id FROM sales WHERE client_uuid = ?) ORDER BY line_no",
                        saleUuid);
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("tax_rate_bp", 0);
        assertThat(items.get(1)).containsEntry("tax_rate_bp", 1800);

        // What the cloud can now answer and could not before: what was sold at each rate.
        // Deriving it from sales.tax_rate_bp would have put the whole basket at 18%.
        List<Map<String, Object>> byRate =
                jdbc.queryForList(
                        """
                        SELECT tax_rate_bp, sum(tax_minor)::bigint AS tax FROM sale_items
                        WHERE sale_id = (SELECT id FROM sales WHERE client_uuid = ?)
                        GROUP BY tax_rate_bp ORDER BY tax_rate_bp
                        """,
                        saleUuid);
        assertThat(byRate.get(0)).containsEntry("tax", 0L);
        assertThat(byRate.get(1)).containsEntry("tax", 6864L);
    }

    /**
     * A till older than M1-18 sends lines with no stamp, because until M1-18 a cart could only
     * hold one rate. The sale's own stamp is not a fallback there, it is exactly what that till
     * meant — so the row is still correct, not merely non-null. Same principle as {@link
     * #aSaleWithoutMovementsStillIngests()}: a shop's backlog must never need an upgrade first.
     */
    @Test
    void aSaleFromATillWithoutPerLineRatesInheritsTheSaleRate() {
        UUID saleUuid = UUID.randomUUID();

        ingest.ingest(batchWith(saleUuid, "KND-T1-000201"));

        Map<String, Object> item =
                jdbc.queryForMap(
                        "SELECT tax_mode, tax_rate_bp FROM sale_items WHERE sale_id ="
                                + " (SELECT id FROM sales WHERE client_uuid = ?)",
                        saleUuid);
        assertThat(item).containsEntry("tax_mode", "INCLUSIVE").containsEntry("tax_rate_bp", 1800);
    }

    /** Two lines at different rates: bread at 0%, tea at 18%. */
    private SyncBatch mixedRateBatch(UUID saleUuid, String invoiceNumber) {
        String payload =
                """
                {
                  "clientUuid": "%s",
                  "branchCode": "KND",
                  "terminalCode": "T1",
                  "invoiceNumber": "%s",
                  "soldAt": "2026-08-12T04:30:00Z",
                  "taxMode": "INCLUSIVE",
                  "taxRateBp": 1800,
                  "subtotalMinor": 95000,
                  "discountMinor": 0,
                  "taxMinor": 6864,
                  "totalMinor": 95000,
                  "lines": [{
                    "productClientUuid": "%s",
                    "lineNo": 1, "qty": 2,
                    "unitPriceMinor": 25000, "discountMinor": 0,
                    "taxMinor": 0, "lineTotalMinor": 50000,
                    "taxMode": "INCLUSIVE", "taxRateBp": 0
                  }, {
                    "productClientUuid": "%s",
                    "lineNo": 2, "qty": 1,
                    "unitPriceMinor": 45000, "discountMinor": 0,
                    "taxMinor": 6864, "lineTotalMinor": 45000,
                    "taxMode": "INCLUSIVE", "taxRateBp": 1800
                  }]
                }
                """
                        .formatted(saleUuid, invoiceNumber, PRODUCT, PRODUCT);
        return new SyncBatch(
                TENANT, "Kandy Stores", List.of(new SyncBatch.Item("sale", saleUuid, json(payload))));
    }

    private SyncBatch batchWith(UUID saleUuid, String invoiceNumber) {
        return new SyncBatch(TENANT, "Kandy Stores", List.of(saleItem(saleUuid, invoiceNumber)));
    }

    /** The M1-15 shape: the same sale, now carrying the movement its line caused. */
    private SyncBatch batchWithMovements(UUID saleUuid, String invoiceNumber, UUID movementUuid) {
        String payload =
                """
                {
                  "clientUuid": "%s",
                  "branchCode": "KND",
                  "terminalCode": "T1",
                  "invoiceNumber": "%s",
                  "soldAt": "2026-08-12T04:30:00Z",
                  "taxMode": "INCLUSIVE",
                  "taxRateBp": 1800,
                  "subtotalMinor": 90000,
                  "discountMinor": 0,
                  "taxMinor": 13728,
                  "totalMinor": 90000,
                  "lines": [{
                    "productClientUuid": "%s",
                    "lineNo": 1, "qty": 2,
                    "unitPriceMinor": 45000, "discountMinor": 0,
                    "taxMinor": 13728, "lineTotalMinor": 90000
                  }],
                  "movements": [{
                    "clientUuid": "%s",
                    "productClientUuid": "%s",
                    "qtyDelta": -2,
                    "reason": "SALE"
                  }]
                }
                """
                        .formatted(saleUuid, invoiceNumber, PRODUCT, movementUuid, PRODUCT);
        return new SyncBatch(
                TENANT, "Kandy Stores", List.of(new SyncBatch.Item("sale", saleUuid, json(payload))));
    }

    private int onHandOf(UUID productClientUuid) {
        return count(
                "SELECT coalesce(sum(qty_delta), 0) FROM stock_movements WHERE product_client_uuid = ?",
                productClientUuid);
    }

    private SyncBatch.Item saleItem(UUID saleUuid, String invoiceNumber) {
        String payload =
                """
                {
                  "clientUuid": "%s",
                  "branchCode": "KND",
                  "terminalCode": "T1",
                  "invoiceNumber": "%s",
                  "soldAt": "2026-08-12T04:30:00Z",
                  "taxMode": "INCLUSIVE",
                  "taxRateBp": 1800,
                  "subtotalMinor": 90000,
                  "discountMinor": 0,
                  "taxMinor": 13728,
                  "totalMinor": 90000,
                  "lines": [{
                    "productClientUuid": "00000000-0000-4000-8000-000000000101",
                    "lineNo": 1, "qty": 2,
                    "unitPriceMinor": 45000, "discountMinor": 0,
                    "taxMinor": 13728, "lineTotalMinor": 90000
                  }]
                }
                """
                        .formatted(saleUuid, invoiceNumber);
        return new SyncBatch.Item("sale", saleUuid, json(payload));
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
