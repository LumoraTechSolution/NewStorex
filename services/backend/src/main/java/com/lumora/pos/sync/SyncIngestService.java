package com.lumora.pos.sync;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Receives what a shop's outbox pushed.
 *
 * <p>Every write is an upsert keyed on the aggregate's {@code client_uuid}, so redelivering a batch
 * changes nothing. That is the property the whole retry design leans on: the drain never has to know
 * whether its last attempt got through before the connection dropped, because sending it again is
 * free.
 *
 * <p>Each item commits in its own transaction. One poisonous aggregate then costs exactly itself
 * rather than the batch around it.
 */
@Service
@Profile("cloud")
public class SyncIngestService {

    private static final Logger log = LoggerFactory.getLogger(SyncIngestService.class);

    private final JdbcTemplate jdbc;

    /**
     * Explicit rather than {@code @Transactional} on a private method: Spring's annotation works
     * through a proxy, and a call from inside this class never crosses it. The per-item transaction
     * would silently not exist, which is exactly the kind of bug that only shows up as a whole batch
     * disappearing because of one bad row.
     */
    private final TransactionTemplate perItemTransaction;

    public SyncIngestService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.perItemTransaction = new TransactionTemplate(transactionManager);
        this.perItemTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public SyncBatchResult ingest(SyncBatch batch) {
        long tenantId = upsertTenant(batch.tenantClientUuid(), batch.tenantName());

        List<UUID> accepted = new ArrayList<>();
        List<SyncBatchResult.Rejection> rejected = new ArrayList<>();

        for (SyncBatch.Item item : batch.items()) {
            try {
                ingestOne(tenantId, item);
                accepted.add(item.aggregateId());
            } catch (Exception e) {
                // Deliberately broad. Anything thrown here means this aggregate will not
                // succeed unchanged, and the shop needs to hear that about this row only.
                log.warn("Rejected {} {}: {}", item.aggregate(), item.aggregateId(), e.toString());
                rejected.add(new SyncBatchResult.Rejection(item.aggregateId(), describe(e)));
            }
        }

        return new SyncBatchResult(accepted, rejected);
    }

    private void ingestOne(long tenantId, SyncBatch.Item item) {
        perItemTransaction.executeWithoutResult(
                status -> {
                    switch (item.aggregate()) {
                        case "sale" -> upsertSale(tenantId, item.aggregateId(), item.payload());
                        case "shift" -> upsertShift(tenantId, item.aggregateId(), item.payload());
                        case "cash_movement" ->
                                upsertCashMovement(tenantId, item.aggregateId(), item.payload());
                        case "refund" -> upsertRefund(tenantId, item.aggregateId(), item.payload());
                        default ->
                                throw new IllegalArgumentException(
                                        "Unsupported aggregate kind: " + item.aggregate());
                    }
                });
    }

    // ------------------------------------------------------------------------ tenant

    private long upsertTenant(UUID clientUuid, String name) {
        // Self-registering on first sight keeps the M0 spike honest end to end. From M4-08 a
        // tenant is provisioned by super-admin and an unknown one is a rejection, not a row.
        jdbc.update(
                """
                INSERT INTO tenants (client_uuid, name) VALUES (?, ?)
                ON CONFLICT (client_uuid) DO UPDATE SET name = excluded.name
                """,
                clientUuid,
                name);
        return jdbc.queryForObject(
                "SELECT id FROM tenants WHERE client_uuid = ?", Long.class, clientUuid);
    }

    // -------------------------------------------------------------------------- sale

    private void upsertSale(long tenantId, UUID clientUuid, JsonNode payload) {
        Long saleId =
                jdbc.queryForObject(
                        """
                        INSERT INTO sales (
                            client_uuid, tenant_id, branch_code, terminal_code, invoice_number,
                            subtotal_minor, discount_minor, tax_minor, total_minor,
                            tax_mode, tax_rate_bp, sold_at, rounding_adjustment_minor, change_minor,
                            shift_client_uuid)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (client_uuid) DO UPDATE SET client_uuid = excluded.client_uuid
                        RETURNING id
                        """,
                        Long.class,
                        clientUuid,
                        tenantId,
                        text(payload, "branchCode"),
                        text(payload, "terminalCode"),
                        text(payload, "invoiceNumber"),
                        number(payload, "subtotalMinor"),
                        number(payload, "discountMinor"),
                        number(payload, "taxMinor"),
                        number(payload, "totalMinor"),
                        text(payload, "taxMode"),
                        (int) number(payload, "taxRateBp"),
                        Timestamp.from(Instant.parse(text(payload, "soldAt"))),
                        // Older tills predate M1-11 and never sent these; treat their absence as
                        // the settled-in-full-with-no-cash case rather than rejecting the batch.
                        optionalNumber(payload, "roundingAdjustmentMinor"),
                        optionalNumber(payload, "changeMinor"),
                        // Null for a till that predates M2-01, whose sales genuinely had no
                        // shift. Same tolerance the two fields above get.
                        optionalUuid(payload, "shiftClientUuid"));

        // Movements are keyed on their own uuid, so they are ingested before the immutability
        // check below rather than after it. Two reasons: the upsert is already a no-op on
        // redelivery without needing that check, and a sale ingested by a build that predates
        // this code can still have its movements backfilled when the shop resends. They carry no
        // sale_id — a movement in the cloud is a fact about a product at a branch at a time, and
        // returns and goods receipts will write the same table without a sale to hang from.
        ingestMovements(tenantId, payload);

        // A sale is immutable once rung up: the lines and tenders cannot have changed, so on
        // redelivery there is nothing to do. Rewriting them would only risk turning a no-op
        // into an edit.
        Integer existingLines =
                jdbc.queryForObject(
                        "SELECT count(*) FROM sale_items WHERE sale_id = ?", Integer.class, saleId);
        if (existingLines != null && existingLines > 0) {
            return;
        }

        JsonNode lines = payload.get("lines");
        if (lines == null || !lines.isArray() || lines.isEmpty()) {
            throw new IllegalArgumentException("Sale payload carries no lines");
        }

        for (JsonNode line : lines) {
            // A till older than M1-18 sends no per-line stamp, because until M1-18 a cart
            // could only hold one rate — so the sale's own stamp is not a fallback here, it
            // is precisely what that till meant by the line. Same reasoning as the movements
            // in M1-15: accept the older shape rather than making an upgrade a precondition
            // for a shop's backlog reaching the cloud at all.
            JsonNode lineTaxMode = line.get("taxMode");
            JsonNode lineTaxRateBp = line.get("taxRateBp");

            jdbc.update(
                    """
                    INSERT INTO sale_items (
                        sale_id, line_no, product_client_uuid, qty,
                        unit_price_minor, discount_minor, tax_minor, line_total_minor,
                        tax_mode, tax_rate_bp)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    saleId,
                    (int) number(line, "lineNo"),
                    UUID.fromString(text(line, "productClientUuid")),
                    (int) number(line, "qty"),
                    number(line, "unitPriceMinor"),
                    number(line, "discountMinor"),
                    number(line, "taxMinor"),
                    number(line, "lineTotalMinor"),
                    lineTaxMode != null && !lineTaxMode.isNull()
                            ? lineTaxMode.asText()
                            : text(payload, "taxMode"),
                    lineTaxRateBp != null && !lineTaxRateBp.isNull()
                            ? (int) lineTaxRateBp.asLong()
                            : (int) number(payload, "taxRateBp"));
        }

        JsonNode tenders = payload.get("tenders");
        if (tenders != null && tenders.isArray()) {
            for (JsonNode tender : tenders) {
                jdbc.update(
                        "INSERT INTO sale_payments (sale_id, line_no, kind, amount_minor) VALUES (?, ?, ?, ?)",
                        saleId,
                        (int) number(tender, "lineNo"),
                        text(tender, "kind"),
                        number(tender, "amountMinor"));
            }
        }
    }

    // --------------------------------------------------------------------- movements

    /**
     * Stock, as movements rather than a level (§A). The cloud never computes a balance from a
     * column anyone updates — on hand is {@code Σ qty_delta}, which is why redelivering a batch has
     * to add nothing rather than add the same numbers again.
     *
     * <p>{@code DO NOTHING} rather than {@code DO UPDATE}: a movement that already landed is
     * historical fact, and the only correct response to being told about it twice is silence.
     */
    private void ingestMovements(long tenantId, JsonNode payload) {
        JsonNode movements = payload.get("movements");
        if (movements == null || !movements.isArray()) {
            // A till older than M1-15 sends none. Its sale still ingests — the same tolerance
            // the M1-11 tender fields get above — and its stock simply never reached the cloud.
            return;
        }

        String branchCode = text(payload, "branchCode");
        Timestamp occurredAt = Timestamp.from(Instant.parse(text(payload, "soldAt")));

        for (JsonNode movement : movements) {
            jdbc.update(
                    """
                    INSERT INTO stock_movements (
                        client_uuid, tenant_id, branch_code, product_client_uuid,
                        qty_delta, reason, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (client_uuid) DO NOTHING
                    """,
                    UUID.fromString(text(movement, "clientUuid")),
                    tenantId,
                    branchCode,
                    UUID.fromString(text(movement, "productClientUuid")),
                    (int) number(movement, "qtyDelta"),
                    text(movement, "reason"),
                    occurredAt);
        }
    }

    // ------------------------------------------------------------------------- shift

    /**
     * The one aggregate that is <strong>not</strong> immutable (M2-12).
     *
     * <p>Everything else here is written once and redelivery is a no-op. A shift arrives twice —
     * when it opens, and again when it closes carrying the count, the variance and the reason — so
     * this upsert is a real update, and delivery order suddenly matters. It is not guaranteed: if
     * the open row fails and gets backed off while the close row goes through, the open row lands
     * afterwards.
     *
     * <p>Hence the {@code WHERE shifts.status <> 'CLOSED'} guard. The update is monotonic — a
     * closed shift never reopens — so any arrival order converges on the same state. That is the
     * same property that makes redelivery free everywhere else, extended to an aggregate that can
     * legitimately change.
     */
    private void upsertShift(long tenantId, UUID clientUuid, JsonNode payload) {
        jdbc.update(
                """
                INSERT INTO shifts (
                    client_uuid, tenant_id, branch_code, terminal_code, status,
                    opened_at, opening_float_minor, closed_at, counted_cash_minor,
                    expected_cash_minor, variance_minor, variance_reason, variance_note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (client_uuid) DO UPDATE SET
                    status              = excluded.status,
                    closed_at           = excluded.closed_at,
                    counted_cash_minor  = excluded.counted_cash_minor,
                    expected_cash_minor = excluded.expected_cash_minor,
                    variance_minor      = excluded.variance_minor,
                    variance_reason     = excluded.variance_reason,
                    variance_note       = excluded.variance_note
                WHERE shifts.status <> 'CLOSED'
                """,
                clientUuid,
                tenantId,
                text(payload, "branchCode"),
                text(payload, "terminalCode"),
                text(payload, "status"),
                Timestamp.from(Instant.parse(text(payload, "openedAt"))),
                number(payload, "openingFloatMinor"),
                optionalTimestamp(payload, "closedAt"),
                optionalLongOrNull(payload, "countedCashMinor"),
                optionalLongOrNull(payload, "expectedCashMinor"),
                optionalLongOrNull(payload, "varianceMinor"),
                optionalText(payload, "varianceReason"),
                optionalText(payload, "varianceNote"));

        // The denomination detail. Deleted and rewritten rather than merged: the close count is
        // a different phase from the open one, both arrive complete, and "what the drawer held"
        // is not something two deliveries should ever combine into a third answer.
        Long shiftId = jdbc.queryForObject("SELECT id FROM shifts WHERE client_uuid = ?", Long.class, clientUuid);
        JsonNode counts = payload.get("counts");
        if (counts != null && counts.isArray()) {
            jdbc.update("DELETE FROM shift_counts WHERE shift_id = ?", shiftId);
            for (JsonNode count : counts) {
                jdbc.update(
                        """
                        INSERT INTO shift_counts (shift_id, phase, denomination_minor, qty)
                        VALUES (?, ?, ?, ?)
                        """,
                        shiftId,
                        text(count, "phase"),
                        number(count, "denominationMinor"),
                        (int) number(count, "qty"));
            }
        }
    }

    // ----------------------------------------------------------------- cash movement

    /** Immutable, like a sale: cash moved, and being told about it twice changes nothing. */
    private void upsertCashMovement(long tenantId, UUID clientUuid, JsonNode payload) {
        jdbc.update(
                """
                INSERT INTO cash_movements (
                    client_uuid, tenant_id, branch_code, shift_client_uuid,
                    kind, amount_minor, reason_code, note, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (client_uuid) DO NOTHING
                """,
                clientUuid,
                tenantId,
                text(payload, "branchCode"),
                UUID.fromString(text(payload, "shiftClientUuid")),
                text(payload, "kind"),
                // Already signed on the till (V107). Stored as it arrives, so Σ over the column
                // is the drawer's movement with no reader having to know which kinds subtract.
                number(payload, "amountMinor"),
                text(payload, "reasonCode"),
                optionalText(payload, "note"),
                Timestamp.from(Instant.parse(text(payload, "occurredAt"))));
    }

    // ------------------------------------------------------------------------ refund

    /**
     * A credit note (M2-12).
     *
     * <p>The sale it reverses is referenced by uuid and is <em>not</em> a foreign key: a refund can
     * legitimately reach the cloud before the sale does, and rejecting it for that would mean a
     * shop's backlog could only ever drain in one order. Same rule as everywhere else here —
     * aggregates arrive independently and are resolved at query time.
     */
    private void upsertRefund(long tenantId, UUID clientUuid, JsonNode payload) {
        Long refundId =
                jdbc.queryForObject(
                        """
                        INSERT INTO refunds (
                            client_uuid, tenant_id, branch_code, terminal_code,
                            shift_client_uuid, sale_client_uuid, credit_note_number,
                            total_minor, tax_minor, rounding_adjustment_minor, refunded_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (client_uuid) DO UPDATE SET client_uuid = excluded.client_uuid
                        RETURNING id
                        """,
                        Long.class,
                        clientUuid,
                        tenantId,
                        text(payload, "branchCode"),
                        text(payload, "terminalCode"),
                        UUID.fromString(text(payload, "shiftClientUuid")),
                        UUID.fromString(text(payload, "saleClientUuid")),
                        text(payload, "creditNoteNumber"),
                        number(payload, "totalMinor"),
                        number(payload, "taxMinor"),
                        optionalNumber(payload, "roundingAdjustmentMinor"),
                        Timestamp.from(Instant.parse(text(payload, "refundedAt"))));

        // Restock movements go in before the immutability check below, for the same reason the
        // sale's do (M1-15): they are keyed on their own uuid, so the upsert is already a no-op
        // on redelivery, and a refund ingested by an older build can still have its movements
        // backfilled when the shop resends.
        ingestRefundMovements(tenantId, payload);

        // A credit note is issued once and never changes, so redelivery has nothing to do.
        Integer existingLines =
                jdbc.queryForObject(
                        "SELECT count(*) FROM refund_items WHERE refund_id = ?", Integer.class, refundId);
        if (existingLines != null && existingLines > 0) {
            return;
        }

        JsonNode lines = payload.get("lines");
        if (lines == null || !lines.isArray() || lines.isEmpty()) {
            throw new IllegalArgumentException("Refund payload carries no lines");
        }
        for (JsonNode line : lines) {
            jdbc.update(
                    """
                    INSERT INTO refund_items (
                        refund_id, line_no, sale_line_no, product_client_uuid, qty,
                        unit_price_minor, refund_total_minor, tax_minor,
                        tax_mode, tax_rate_bp, reason_code, note, restock)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    refundId,
                    (int) number(line, "lineNo"),
                    (int) number(line, "saleLineNo"),
                    UUID.fromString(text(line, "productClientUuid")),
                    (int) number(line, "qty"),
                    number(line, "unitPriceMinor"),
                    number(line, "refundTotalMinor"),
                    number(line, "taxMinor"),
                    text(line, "taxMode"),
                    (int) number(line, "taxRateBp"),
                    text(line, "reasonCode"),
                    optionalText(line, "note"),
                    bool(line, "restock"));
        }

        JsonNode tenders = payload.get("tenders");
        if (tenders != null && tenders.isArray()) {
            for (JsonNode tender : tenders) {
                jdbc.update(
                        "INSERT INTO refund_payments (refund_id, line_no, kind, amount_minor) VALUES (?, ?, ?, ?)",
                        refundId,
                        (int) number(tender, "lineNo"),
                        text(tender, "kind"),
                        number(tender, "amountMinor"));
            }
        }
    }

    /**
     * Stock coming back (M2-10).
     *
     * <p>Positive deltas, exactly as the sale wrote negative ones, so stock on hand goes on being
     * Σ entries with no special case for returns. Only restocked lines carry a movement — a damaged
     * item is not inventory, and a RETURN row for it would tell the owner they hold stock they
     * cannot sell.
     */
    private void ingestRefundMovements(long tenantId, JsonNode payload) {
        JsonNode lines = payload.get("lines");
        if (lines == null || !lines.isArray()) {
            return;
        }

        String branchCode = text(payload, "branchCode");
        Timestamp occurredAt = Timestamp.from(Instant.parse(text(payload, "refundedAt")));

        for (JsonNode line : lines) {
            JsonNode movement = line.get("movement");
            if (movement == null || movement.isNull()) {
                continue;
            }
            jdbc.update(
                    """
                    INSERT INTO stock_movements (
                        client_uuid, tenant_id, branch_code, product_client_uuid,
                        qty_delta, reason, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (client_uuid) DO NOTHING
                    """,
                    UUID.fromString(text(movement, "clientUuid")),
                    tenantId,
                    branchCode,
                    UUID.fromString(text(movement, "productClientUuid")),
                    (int) number(movement, "qtyDelta"),
                    text(movement, "reason"),
                    occurredAt);
        }
    }

    // ------------------------------------------------------------------------ helpers

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Payload is missing '" + field + "'");
        }
        return value.asText();
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Payload field '" + field + "' is not a number");
        }
        return value.asLong();
    }

    /** Like {@link #number}, but 0 when the field is absent — for fields older payloads lack. */
    private static long optionalNumber(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? 0L : value.asLong();
    }

    private static java.util.UUID optionalUuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : UUID.fromString(value.asText());
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException("Payload field '" + field + "' is not a boolean");
        }
        return value.asBoolean();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long optionalLongOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asLong();
    }

    private static java.sql.Timestamp optionalTimestamp(JsonNode node, String field) {
        String value = optionalText(node, field);
        return value == null ? null : Timestamp.from(Instant.parse(value));
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
