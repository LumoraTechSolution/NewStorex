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

/**
 * The drain, with the cloud under the test's control.
 *
 * <p>What matters here is the failure path. Success is easy and rare; a shop's connection dropping
 * mid-batch is neither, and every one of those cases has to leave the outbox in a state the next
 * pass can recover from.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class SyncWorkerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000bb");

    @Autowired SyncWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired ControllableCloud cloud;
    @Autowired SyncStatus status;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ControllableCloud controllableCloud() {
            return new ControllableCloud();
        }
    }

    /** A cloud the test can unplug. */
    static class ControllableCloud implements CloudSyncClient {
        boolean reachable = true;
        List<UUID> rejectAll = new ArrayList<>();
        int pushCount = 0;
        SyncBatch lastBatch;

        @Override
        public SyncBatchResult push(SyncBatch batch) {
            pushCount++;
            lastBatch = batch;
            if (!reachable) {
                throw new CloudUnreachableException("connection refused (test)", null);
            }
            List<UUID> accepted = new ArrayList<>();
            List<SyncBatchResult.Rejection> rejected = new ArrayList<>();
            for (SyncBatch.Item item : batch.items()) {
                if (rejectAll.contains(item.aggregateId())) {
                    rejected.add(new SyncBatchResult.Rejection(item.aggregateId(), "malformed (test)"));
                } else {
                    accepted.add(item.aggregateId());
                }
            }
            return new SyncBatchResult(accepted, rejected);
        }
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM tenants WHERE client_uuid = ?", TENANT);
        jdbc.update("INSERT INTO tenants (client_uuid, name) VALUES (?, 'Kandy Stores')", TENANT);
        cloud.reachable = true;
        cloud.rejectAll.clear();
        cloud.pushCount = 0;
    }

    @Test
    void aSuccessfulDrainAcksExactlyWhatTheCloudAccepted() {
        UUID a = enqueue();
        UUID b = enqueue();

        int accepted = worker.drainOnce();

        assertThat(accepted).isEqualTo(2);
        assertThat(worker.pendingCount()).isZero();
        assertThat(ackedCount(a)).isEqualTo(1);
        assertThat(ackedCount(b)).isEqualTo(1);
        assertThat(status.online()).isTrue();
    }

    /**
     * The cable-pull case. Nothing is lost, nothing is acked, and the shop is entirely unaware.
     */
    @Test
    void anUnreachableCloudLeavesEveryRowPending() {
        UUID a = enqueue();
        UUID b = enqueue();
        cloud.reachable = false;

        int accepted = worker.drainOnce();

        assertThat(accepted).isZero();
        assertThat(worker.pendingCount()).isEqualTo(2);
        assertThat(ackedCount(a)).isZero();
        assertThat(ackedCount(b)).isZero();
        assertThat(status.online()).isFalse();
        assertThat(status.lastError()).contains("connection refused");
    }

    @Test
    void aFailedAttemptIsRecordedAndBackedOff() {
        enqueue();
        cloud.reachable = false;

        worker.drainOnce();

        Integer attempts = jdbc.queryForObject("SELECT attempts FROM outbox LIMIT 1", Integer.class);
        String error = jdbc.queryForObject("SELECT last_error FROM outbox LIMIT 1", String.class);
        Boolean dueLater =
                jdbc.queryForObject("SELECT next_attempt_at > now() FROM outbox LIMIT 1", Boolean.class);

        assertThat(attempts).isEqualTo(1);
        assertThat(error).contains("connection refused");
        assertThat(dueLater).as("a failed row must not be retried on the very next tick").isTrue();
    }

    @Test
    void rowsInBackoffAreNotPickedUpAgainImmediately() {
        enqueue();
        cloud.reachable = false;
        worker.drainOnce();

        cloud.reachable = true;
        int accepted = worker.drainOnce();

        assertThat(accepted).as("still inside its backoff window").isZero();
        assertThat(cloud.pushCount).as("the worker should not even have called the cloud").isEqualTo(1);
        assertThat(worker.pendingCount()).isEqualTo(1);
    }

    @Test
    void aRowBecomesEligibleAgainOnceItsBackoffElapses() {
        enqueue();
        cloud.reachable = false;
        worker.drainOnce();

        // Bring the clock forward rather than sleeping through a real backoff.
        jdbc.update("UPDATE outbox SET next_attempt_at = now() - interval '1 second'");
        cloud.reachable = true;

        assertThat(worker.drainOnce()).isEqualTo(1);
        assertThat(worker.pendingCount()).isZero();
    }

    @Test
    void aRejectedRowIsKeptWithItsReasonRatherThanDiscarded() {
        UUID bad = enqueue();
        cloud.rejectAll.add(bad);

        worker.drainOnce();

        assertThat(worker.pendingCount()).as("a rejected sale must never be silently dropped").isEqualTo(1);
        String error = jdbc.queryForObject("SELECT last_error FROM outbox LIMIT 1", String.class);
        assertThat(error).contains("malformed");
    }

    @Test
    void aPartiallyAcceptedBatchAcksOnlyTheAcceptedRows() {
        UUID good = enqueue();
        UUID bad = enqueue();
        cloud.rejectAll.add(bad);

        worker.drainOnce();

        assertThat(ackedCount(good)).isEqualTo(1);
        assertThat(ackedCount(bad)).isZero();
        assertThat(worker.pendingCount()).isEqualTo(1);
    }

    @Test
    void theBatchCarriesTheTenantSoTheCloudKnowsWhoseSaleItIs() {
        enqueue();
        worker.drainOnce();

        assertThat(cloud.lastBatch.tenantClientUuid()).isEqualTo(TENANT);
        assertThat(cloud.lastBatch.tenantName()).isEqualTo("Kandy Stores");
    }

    @Test
    void anEmptyOutboxDoesNotCallTheCloudAtAll() {
        assertThat(worker.drainOnce()).isZero();
        assertThat(cloud.pushCount).isZero();
    }

    // ------------------------------------------------------------------- helpers

    private UUID enqueue() {
        UUID aggregateId = UUID.randomUUID();
        long tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE client_uuid = ?", Long.class, TENANT);
        jdbc.update(
                """
                INSERT INTO outbox (tenant_id, aggregate, aggregate_id, payload)
                VALUES (?, 'sale', ?, '{"totalMinor": 45000}'::jsonb)
                """,
                tenantId,
                aggregateId);
        return aggregateId;
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
