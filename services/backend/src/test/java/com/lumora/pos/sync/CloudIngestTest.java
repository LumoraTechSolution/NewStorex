package com.lumora.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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

    @Test
    void theTenantIsCreatedOnFirstSight() {
        ingest.ingest(batchWith(UUID.randomUUID(), "KND-T1-000020"));
        assertThat(count("SELECT count(*) FROM tenants WHERE client_uuid = ?", TENANT)).isEqualTo(1);
    }

    // -------------------------------------------------------------------- helpers

    private SyncBatch batchWith(UUID saleUuid, String invoiceNumber) {
        return new SyncBatch(TENANT, "Kandy Stores", List.of(saleItem(saleUuid, invoiceNumber)));
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
