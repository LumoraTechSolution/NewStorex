package com.lumora.pos.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumora.pos.entitlement.EntitlementStore;
import com.lumora.pos.shop.LocalShop;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
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
    private final EntitlementStore entitlements;
    private final LocalShop shop;

    /**
     * When the downward pull last reached the cloud (M4-09). In memory rather than a column: it
     * paces requests within one run of the process, and a restart legitimately pulling once more
     * than strictly needed costs one HTTP call. A column would be a write on every tick to record
     * something nothing durable depends on.
     */
    private final AtomicReference<Instant> lastEntitlementPullAt = new AtomicReference<>();

    public SyncWorker(
            JdbcTemplate jdbc,
            CloudSyncClient cloud,
            SyncProperties properties,
            SyncStatus status,
            ObjectMapper objectMapper,
            EntitlementStore entitlements,
            LocalShop shop) {
        this.jdbc = jdbc;
        this.cloud = cloud;
        this.properties = properties;
        this.status = status;
        this.objectMapper = objectMapper;
        this.entitlements = entitlements;
        this.shop = shop;
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

        // The downward half (M4-09), on the same tick and deliberately after the drain — pushing a
        // shop's sales is the urgent job and must not queue behind a licence lookup.
        //
        // Outside the drain's try, and with its own, because the two are independent: the outbox
        // failing is no reason to stop asking about the licence, and asking about the licence
        // failing is no reason to log an outbox error. It is also outside the `pending.isEmpty()`
        // return inside drainOnce, which is the whole reason it is not folded into that method —
        // a shop with nothing to send is still a shop whose licence can lapse overnight.
        try {
            pullEntitlementIfDue();
        } catch (Exception e) {
            // Debug, not warn. An unreachable cloud is the routine state of this product and the
            // cached answer still governs, so there is nothing here for a shopkeeper to act on.
            log.debug("Entitlement pull failed, the cached answer stands: {}", e.getMessage());
        }
    }

    /**
     * Refreshes the entitlement if enough time has passed, and reports whether it did.
     *
     * <p>Rate-limited against {@code lumora.sync.entitlement-interval} rather than run on every
     * tick: the drain runs every ten seconds because an unsent sale is urgent, and a licence date
     * is not. Nothing on the till waits for this to have happened — {@link EntitlementStore} treats
     * a missing or old answer as full capability — so being late is free and being frequent is not.
     */
    public boolean pullEntitlementIfDue() {
        Instant last = lastEntitlementPullAt.get();
        if (last != null && last.plus(properties.entitlementInterval()).isAfter(Instant.now())) {
            return false;
        }
        return pullEntitlementNow();
    }

    /**
     * Asks the cloud and stores the answer. Public so a test can drive it, and so a future
     * "check now" button has something to call that does not wait out the interval.
     *
     * <p>An unactivated till returns false without a request: with no token the cloud cannot tell
     * which shop is asking, and a 401 logged every five minutes on a machine that is working
     * exactly as intended is noise that teaches people to ignore the log.
     */
    public boolean pullEntitlementNow() {
        if (!properties.hasToken()) {
            return false;
        }
        Entitlement entitlement = cloud.fetchEntitlement();
        entitlements.record(shop.soleTenantId(), entitlement);
        lastEntitlementPullAt.set(Instant.now());

        if (!entitlement.licensed()) {
            // Warn, because this one *is* actionable and it is the only way the shop finds out.
            // The sale path is untouched; what stops is the shop's data reaching the cloud.
            log.warn(
                    "The licence for this shop is not current (plan {}, expired {}). Sales are still"
                            + " final locally and are queueing until it is renewed.",
                    entitlement.planCode(),
                    entitlement.licenceExpiresAt());
        }
        return true;
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

        // Which shop this is no longer travels in the batch — since M4-01 the cloud reads it
        // from the bearer token, so a till with no token configured cannot push at all and
        // should say so here rather than as a 401 on every retry.
        if (!properties.hasToken()) {
            log.warn(
                    "Nothing to sync with: no lumora.sync.token is configured, {} rows stay pending",
                    pending.size());
            return 0;
        }

        SyncBatch batch =
                new SyncBatch(
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

    /** Exposed for the status endpoint so it can report how stale the queue is. */
    public Instant oldestPendingAt() {
        return jdbc.queryForObject(
                "SELECT min(created_at) FROM outbox WHERE acked_at IS NULL",
                (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant());
    }
}
