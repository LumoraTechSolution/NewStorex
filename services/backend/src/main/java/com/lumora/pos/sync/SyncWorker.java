package com.lumora.pos.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the outbox to the cloud.
 *
 * <p>Small, boring and restartable. Its most important property is that nothing in the shop waits
 * for it: a sale committed hours ago is already final, already on a receipt, already in the till.
 * All this does is carry a copy upstream, and it can fail every time for a week without a cashier
 * noticing.
 *
 * <p>The one rule it must never break is that a row is acked only once the cloud says it has the
 * row. Acking optimistically would silently lose a shop's takings.
 */
@Component
@Profile("desktop")
public class SyncWorker {

    private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);

    private final JdbcTemplate jdbc;
    private final CloudSyncClient cloud;
    private final SyncProperties properties;
    private final SyncStatus status;
    private final ObjectMapper objectMapper;

    public SyncWorker(
            JdbcTemplate jdbc,
            CloudSyncClient cloud,
            SyncProperties properties,
            SyncStatus status,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.cloud = cloud;
        this.properties = properties;
        this.status = status;
        this.objectMapper = objectMapper;
    }

    // ISO-8601, not "10s": @Scheduled parses its strings itself and only understands the
    // Duration.parse form, unlike the @ConfigurationProperties binding used everywhere else.
    // The initial delay lets the till finish starting before the first network call.
    @Scheduled(
            fixedDelayString = "${lumora.sync.interval:PT10S}",
            initialDelayString = "${lumora.sync.initial-delay:PT15S}")
    public void scheduledDrain() {
        if (!properties.enabled()) {
            return;
        }
        try {
            drainOnce();
        } catch (Exception e) {
            // A drain must never die. If this thread stops, the shop stops syncing forever
            // and nothing visibly breaks until someone asks where the reports went.
            log.error("Outbox drain failed unexpectedly", e);
            status.recordFailure(e.getMessage());
        }
    }

    /**
     * One pass. Returns how many rows the cloud accepted.
     *
     * <p>Deliberately not {@code @Transactional}: the HTTP call sits in the middle, and holding a
     * database transaction open across a network round trip is how a slow cloud turns into a locked
     * till.
     */
    public int drainOnce() {
        List<PendingRow> pending = readPending();
        if (pending.isEmpty()) {
            return 0;
        }

        Tenant tenant;
        try {
            tenant = readTenant();
        } catch (EmptyResultDataAccessException e) {
            log.warn("Nothing to sync as: this database has no tenant yet");
            return 0;
        }

        SyncBatch batch =
                new SyncBatch(
                        tenant.clientUuid(),
                        tenant.name(),
                        pending.stream()
                                .map(r -> new SyncBatch.Item(r.aggregate(), r.aggregateId(), r.payload()))
                                .toList());

        SyncBatchResult result;
        try {
            result = cloud.push(batch);
        } catch (CloudSyncClient.CloudUnreachableException e) {
            // Expected, routine, and not an error the shop should ever see. Back off and
            // try later; the rows stay exactly where they are.
            log.debug("Cloud unreachable, {} rows stay pending: {}", pending.size(), e.getMessage());
            backOff(pending, e.getMessage());
            status.recordFailure(e.getMessage());
            return 0;
        }

        ackAccepted(pending, result.accepted());
        recordRejections(pending, result.rejected());
        status.recordSuccess();

        log.info(
                "Drained {} accepted, {} rejected, {} still pending",
                result.accepted().size(),
                result.rejected().size(),
                pendingCount());
        return result.accepted().size();
    }

    public int pendingCount() {
        Integer n =
                jdbc.queryForObject("SELECT count(*) FROM outbox WHERE acked_at IS NULL", Integer.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------------- reads

    private List<PendingRow> readPending() {
        return jdbc.query(
                """
                SELECT id, aggregate, aggregate_id, payload::text AS payload, attempts
                FROM outbox
                WHERE acked_at IS NULL AND next_attempt_at <= now()
                ORDER BY created_at
                LIMIT ?
                """,
                (rs, row) ->
                        new PendingRow(
                                rs.getLong("id"),
                                rs.getString("aggregate"),
                                rs.getObject("aggregate_id", UUID.class),
                                readTree(rs.getString("payload")),
                                rs.getInt("attempts")),
                properties.batchSize());
    }

    private Tenant readTenant() {
        return jdbc.queryForObject(
                "SELECT client_uuid, name FROM tenants ORDER BY id LIMIT 1",
                (rs, row) -> new Tenant(rs.getObject("client_uuid", UUID.class), rs.getString("name")));
    }

    // ------------------------------------------------------------------------ writes

    private void ackAccepted(List<PendingRow> pending, List<UUID> accepted) {
        if (accepted.isEmpty()) {
            return;
        }
        List<Long> ids =
                pending.stream().filter(r -> accepted.contains(r.aggregateId())).map(PendingRow::id).toList();
        if (ids.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>();
        for (Long id : ids) {
            args.add(new Object[] {id});
        }
        jdbc.batchUpdate(
                "UPDATE outbox SET acked_at = now(), last_error = NULL WHERE id = ? AND acked_at IS NULL",
                args);
    }

    /**
     * A rejected row will not succeed unchanged, so retrying it at speed is pointless. It is left
     * unacked on the longest backoff with the reason recorded — visible, not silently discarded. A
     * proper dead-letter path is a later concern; losing a sale quietly is not acceptable at any
     * milestone.
     */
    private void recordRejections(List<PendingRow> pending, List<SyncBatchResult.Rejection> rejections) {
        for (SyncBatchResult.Rejection rejection : rejections) {
            pending.stream()
                    .filter(r -> r.aggregateId().equals(rejection.aggregateId()))
                    .findFirst()
                    .ifPresent(
                            row -> {
                                log.warn(
                                        "Cloud rejected {} {}: {}",
                                        row.aggregate(),
                                        row.aggregateId(),
                                        rejection.reason());
                                jdbc.update(
                                        """
                                        UPDATE outbox
                                        SET attempts = attempts + 1, last_error = ?, next_attempt_at = now() + ?::interval
                                        WHERE id = ?
                                        """,
                                        rejection.reason(),
                                        toInterval(properties.backoffMax()),
                                        row.id());
                            });
        }
    }

    private void backOff(List<PendingRow> pending, String error) {
        List<Object[]> args = new ArrayList<>();
        for (PendingRow row : pending) {
            args.add(
                    new Object[] {error, toInterval(properties.backoffFor(row.attempts() + 1)), row.id()});
        }
        jdbc.batchUpdate(
                """
                UPDATE outbox
                SET attempts = attempts + 1, last_error = ?, next_attempt_at = now() + ?::interval
                WHERE id = ?
                """,
                args);
    }

    // ----------------------------------------------------------------------- helpers

    private static String toInterval(Duration duration) {
        return duration.toMillis() + " milliseconds";
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Outbox row holds unreadable JSON", e);
        }
    }

    private record PendingRow(
            long id, String aggregate, UUID aggregateId, JsonNode payload, int attempts) {}

    private record Tenant(UUID clientUuid, String name) {}

    /** Exposed for the status endpoint so it can report how stale the queue is. */
    public Instant oldestPendingAt() {
        return jdbc.queryForObject(
                "SELECT min(created_at) FROM outbox WHERE acked_at IS NULL",
                (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant());
    }
}
