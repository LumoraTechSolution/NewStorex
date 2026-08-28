package com.lumora.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * A till that has not been activated yet (M4-01).
 *
 * <p>Its own class because the state under test is a configuration property, and the thing being
 * asserted is that the drain notices it before opening a connection. Without the check the shop
 * would push as nobody, collect a 401 per batch, and back off — reaching the same outcome by way of
 * an error every ten seconds and a log nobody can distinguish from a real outage.
 *
 * <p>The rows must survive. Not being activated is a temporary state on a machine that is otherwise
 * working perfectly, and the sales rung up before activation belong in the cloud once it happens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
@TestPropertySource(properties = "lumora.sync.token=")
class SyncWorkerWithoutTokenTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000bc");

    @Autowired SyncWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired SilentCloud cloud;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        SilentCloud silentCloud() {
            return new SilentCloud();
        }
    }

    /** A cloud that records being called, which in this test it never should be. */
    static class SilentCloud implements CloudSyncClient {
        int pushCount = 0;
        int entitlementCount = 0;

        @Override
        public Entitlement fetchEntitlement() {
            entitlementCount++;
            return Entitlement.unlicensed();
        }

        @Override
        public SyncBatchResult push(SyncBatch batch) {
            pushCount++;
            return new SyncBatchResult(
                    batch.items().stream().map(SyncBatch.Item::aggregateId).toList(), List.of());
        }
    }

    @Test
    void anUnactivatedTillQueuesInsteadOfPushingAsNobody() {
        List<UUID> queued = enqueue();

        int drained = worker.drainOnce();

        assertThat(drained).isZero();
        assertThat(cloud.pushCount).isZero();
        // Still there, unacked, waiting for the shop to be activated.
        for (UUID aggregateId : queued) {
            assertThat(ackedCount(aggregateId)).isZero();
        }
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM tenants WHERE client_uuid = ?", TENANT);
        jdbc.update("INSERT INTO tenants (client_uuid, name) VALUES (?, 'Kandy Stores')", TENANT);
        cloud.pushCount = 0;
    }

    private List<UUID> enqueue() {
        long tenantId =
                jdbc.queryForObject("SELECT id FROM tenants WHERE client_uuid = ?", Long.class, TENANT);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            UUID aggregateId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO outbox (tenant_id, aggregate, aggregate_id, payload) VALUES (?, 'sale', ?, '{}'::jsonb)",
                    tenantId,
                    aggregateId);
            ids.add(aggregateId);
        }
        return ids;
    }

    private int ackedCount(UUID aggregateId) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM outbox WHERE aggregate_id = ? AND acked_at IS NOT NULL",
                        Integer.class,
                        aggregateId);
        return n == null ? 0 : n;
    }
}
