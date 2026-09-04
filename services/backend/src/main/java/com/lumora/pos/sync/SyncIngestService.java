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
 * <p>Every write is an upsert keyed on {@code (tenant_id, client_uuid)}, so redelivering a batch
 * changes nothing. The tenant is part of that key rather than the uuid standing alone (V206): a
 * uuid identifies an aggregate <em>within a shop</em>, and a global key would let one shop's row be
 * the conflict target for another's. That is the property the whole retry design leans on: the drain never has to know
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

    /**
     * @param tenantId resolved from the request's bearer token by {@code TenantAuthFilter}, never
     *     from the batch. Passed in rather than looked up here so this service has no way to
     *     consult the payload for it (M4-01).
     */
    public SyncBatchResult ingest(long tenantId, SyncBatch batch) {
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
                        case "goods_receipt" -> ingestGoodsReceipt(tenantId, item.payload());
                        case "stock_adjustment" ->
                                ingestMovementsAt(tenantId, item.payload(), text(item.payload(), "adjustedAt"));
                        case "stocktake" ->
                                ingestMovementsAt(tenantId, item.payload(), text(item.payload(), "countedAt"));
                        // M3-12. Unlike everything above, these three are mutable: a price
                        // changes, somebody is promoted, a customer corrects their number. Each
                        // delivery carries the whole row, so the upsert is a real UPDATE and the
                        // last arrival wins — no state machine, nothing to order.
                        case "product" -> upsertProduct(tenantId, item.aggregateId(), item.payload());
                        case "user" -> upsertUser(tenantId, item.aggregateId(), item.payload());
                        case "customer" ->
                                upsertCustomer(tenantId, item.aggregateId(), item.payload());
                        // M5-09. Immutable like a sale: once issued, a tax invoice is a legal
                        // document that cannot be edited, only cancelled by a credit note.
                        case "tax_invoice" ->
                                upsertTaxInvoice(tenantId, item.aggregateId(), item.payload());
                        default ->
                                throw new IllegalArgumentException(
                                        "Unsupported aggregate kind: " + item.aggregate());
                    }
                });
    }

    // ------------------------------------------------------------------- tax invoice

    /**
     * The IRD tax invoice (M5-09).
     *
     * <p>{@code DO NOTHING} rather than {@code DO UPDATE}: an issued tax invoice is immutable, so
     * a redelivery has nothing to say that the stored row does not already contain. The same
     * treatment cash movements get, and for the same reason.
     */
    private void upsertTaxInvoice(long tenantId, UUID clientUuid, JsonNode payload) {
        String purchaserTin = text(payload, "purchaserTin");
        jdbc.update(
                """
                INSERT INTO tax_invoices (
                    client_uuid, tenant_id, branch_code, terminal_code, invoice_number,
                    sale_invoice_number, issued_at, supplied_at, supplier_tin, purchaser_tin,
                    total_excl_vat_minor, vat_minor, total_incl_vat_minor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, client_uuid) DO NOTHING
                """,
                clientUuid,
                tenantId,
                text(payload, "branchCode"),
                text(payload, "terminalCode"),
                text(payload, "invoiceNumber"),
                text(payload, "saleInvoiceNumber"),
                Timestamp.from(Instant.parse(text(payload, "issuedAt"))),
                Timestamp.from(Instant.parse(text(payload, "suppliedAt"))),
                text(payload, "supplierTin"),
                // The till sends an empty string for a walk-in, because the outbox payload is a
                // flat map. Empty and absent mean the same thing here: no VAT-registered purchaser.
                purchaserTin == null || purchaserTin.isBlank() ? null : purchaserTin,
                number(payload, "totalExclVatMinor"),
                number(payload, "vatMinor"),
                number(payload, "totalInclVatMinor"));
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
                            shift_client_uuid, customer_client_uuid)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, client_uuid) DO UPDATE SET client_uuid = excluded.client_uuid
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
                        optionalUuid(payload, "shiftClientUuid"),
                        // Null for the overwhelming majority of sales, and for every till that
                        // predates M3-11. Same tolerance as the two fields above.
                        optionalUuid(payload, "customerClientUuid"));

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
        ingestMovementsAt(tenantId, payload, text(payload, "soldAt"));
    }

    /**
     * The same insert for every document that moves stock.
     *
     * <p>Parameterised on the timestamp only, because that is genuinely all that differs: a sale
     * says {@code soldAt} and a goods receipt says {@code receivedAt}, and the rows they write are
     * otherwise identical. Two copies of this would be two places for the {@code DO NOTHING} to be
     * forgotten, and the one that was forgotten would double a shop's stock on a retry.
     */
    private void ingestMovementsAt(long tenantId, JsonNode payload, String occurredAtText) {
        JsonNode movements = payload.get("movements");
        if (movements == null || !movements.isArray()) {
            // A till older than M1-15 sends none. Its sale still ingests — the same tolerance
            // the M1-11 tender fields get above — and its stock simply never reached the cloud.
            return;
        }

        String branchCode = text(payload, "branchCode");
        Timestamp occurredAt = Timestamp.from(Instant.parse(occurredAtText));

        for (JsonNode movement : movements) {
            jdbc.update(
                    """
                    INSERT INTO stock_movements (
                        client_uuid, tenant_id, branch_code, product_client_uuid,
                        qty_delta, reason, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, client_uuid) DO NOTHING
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

    // ---------------------------------------------------------------- goods receipts

    /**
     * A delivery (M3-04). Only its <em>movements</em> land here.
     *
     * <h2>Why the document itself is not stored cloud-side yet</h2>
     *
     * What the cloud needs from a goods receipt today is the one thing §A insists on: the movements
     * that change stock on hand. There is no cloud reader for the document — the owner console's
     * purchase and margin reporting is M4-06 — and a table with no reader is a schema decision made
     * without the question that should shape it. The movements carry their own {@code client_uuid}
     * and insert with {@code DO NOTHING}, so redelivery adds nothing and the outbox row drains
     * cleanly rather than backing off forever as an unsupported kind.
     *
     * <p>When the console does need the document, it arrives as a cloud migration and a widening of
     * this method. Nothing about the till changes, because the payload already carries the supplier,
     * the reference and the total.
     */
    private void ingestGoodsReceipt(long tenantId, JsonNode payload) {
        ingestMovementsAt(tenantId, payload, text(payload, "receivedAt"));
    }

    // Note the stock_adjustment case above takes the same path with no wrapper of its own. Its
    // reason code and note stay on the till for now, for the same reason a goods receipt's document
    // does: stock on hand is what the console needs today, and the shrinkage report that wants the
    // reasons is M4-06. What must not happen is the outbox row having nowhere to go (see below).

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
                    expected_cash_minor, variance_minor, variance_reason, variance_note,
                    opened_by_client_uuid, opened_by_name,
                    closed_by_client_uuid, closed_by_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, client_uuid) DO UPDATE SET
                    status              = excluded.status,
                    closed_at           = excluded.closed_at,
                    counted_cash_minor  = excluded.counted_cash_minor,
                    expected_cash_minor = excluded.expected_cash_minor,
                    variance_minor      = excluded.variance_minor,
                    variance_reason     = excluded.variance_reason,
                    variance_note       = excluded.variance_note,
                    -- M6-13. Only the closer moves on the second delivery; the opener was already
                    -- correct and COALESCE keeps an older till's payload, which carries neither,
                    -- from blanking what a newer one already told us.
                    opened_by_client_uuid = COALESCE(excluded.opened_by_client_uuid,
                                                     shifts.opened_by_client_uuid),
                    opened_by_name        = COALESCE(excluded.opened_by_name, shifts.opened_by_name),
                    closed_by_client_uuid = COALESCE(excluded.closed_by_client_uuid,
                                                     shifts.closed_by_client_uuid),
                    closed_by_name        = COALESCE(excluded.closed_by_name, shifts.closed_by_name)
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
                optionalText(payload, "varianceNote"),
                // M6-13, and tolerant reads on purpose: a till that has not been updated sends a
                // payload without these, and its shifts must keep syncing rather than start
                // failing. Absent means "not recorded", which is the truth.
                optionalUuid(payload, "openedByClientUuid"),
                optionalText(payload, "openedByName"),
                optionalUuid(payload, "closedByClientUuid"),
                optionalText(payload, "closedByName"));

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
                ON CONFLICT (tenant_id, client_uuid) DO NOTHING
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
                        ON CONFLICT (tenant_id, client_uuid) DO UPDATE SET client_uuid = excluded.client_uuid
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
                    ON CONFLICT (tenant_id, client_uuid) DO NOTHING
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

    // ------------------------------------------------- catalogue, staff, customers (M3-12)

    /**
     * The product as the shop holds it now.
     *
     * <h2>The whole row, so order does not matter</h2>
     *
     * Every other aggregate here was immutable and its upsert is a deliberate no-op. A product is
     * not: a price changes and the row is delivered again. Because each delivery carries the entire
     * state rather than a change, redelivery is still free and arrival order still does not matter
     * — two edits made minutes apart by the same shop converge on whichever landed second, which is
     * the shop's own latest word either way.
     *
     * <p>The one thing this must never be used for is re-deriving what an old sale charged. The
     * price on a sale line was stamped at the time (M1-05); this column moves.
     */
    private void upsertProduct(long tenantId, UUID clientUuid, JsonNode payload) {
        Long productId =
                jdbc.queryForObject(
                        """
                        INSERT INTO products (
                            client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp,
                            category, reorder_point, active)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, client_uuid) DO UPDATE SET
                            sku = excluded.sku,
                            name = excluded.name,
                            price_minor = excluded.price_minor,
                            tax_mode = excluded.tax_mode,
                            tax_rate_bp = excluded.tax_rate_bp,
                            category = excluded.category,
                            reorder_point = excluded.reorder_point,
                            active = excluded.active
                        RETURNING id
                        """,
                        Long.class,
                        clientUuid,
                        tenantId,
                        text(payload, "sku"),
                        text(payload, "name"),
                        number(payload, "priceMinor"),
                        text(payload, "taxMode"),
                        (int) number(payload, "taxRateBp"),
                        optionalText(payload, "category"),
                        // optionalLongOrNull, not optionalNumber: NULL means "not watched" and 0 is
                        // a real threshold meaning "tell me when it is empty" (V120). Collapsing
                        // the two would silently start watching every product a payload predating
                        // M3-15 ever sent.
                        optionalLongOrNull(payload, "reorderPoint"),
                        payload.path("active").asBoolean(true));

        // Replaced wholesale rather than merged: a barcode removed at the shop has to disappear
        // here too, and a merge has no way to say so. The list that arrived is the truth.
        jdbc.update("DELETE FROM product_barcodes WHERE product_id = ?", productId);
        JsonNode barcodes = payload.path("barcodes");
        for (int i = 0; i < barcodes.size(); i++) {
            jdbc.update(
                    "INSERT INTO product_barcodes (product_id, barcode, is_primary) VALUES (?, ?, ?)",
                    productId,
                    barcodes.get(i).asText(),
                    i == 0);
        }
    }

    /**
     * Staff, so the console can name whoever did something.
     *
     * <p>There is no {@code pin_hash} column to write into and the payload carries none. The cloud
     * holds no credential, which is why offline auth (M3-09) is entirely local — a till that could
     * check a PIN against the cloud would need the cloud to hold something worth stealing.
     */
    private void upsertUser(long tenantId, UUID clientUuid, JsonNode payload) {
        jdbc.update(
                """
                INSERT INTO users (client_uuid, tenant_id, code, display_name, role, active)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, client_uuid) DO UPDATE SET
                    code = excluded.code,
                    display_name = excluded.display_name,
                    role = excluded.role,
                    active = excluded.active
                """,
                clientUuid,
                tenantId,
                text(payload, "code"),
                text(payload, "displayName"),
                text(payload, "role"),
                payload.path("active").asBoolean(true));
    }

    /**
     * Customers, by name and number only.
     *
     * <p>No email and no note: neither has a reader in the console, and personal data copied into a
     * second jurisdiction because it might one day be useful is exactly what M5-10 will have to
     * undo. The phone is not unique here either — uniqueness is a rule the shop enforces on its own
     * database, and the cloud rejecting a row over it would stall a backlog on a conflict only the
     * shop can resolve.
     */
    private void upsertCustomer(long tenantId, UUID clientUuid, JsonNode payload) {
        jdbc.update(
                """
                INSERT INTO customers (client_uuid, tenant_id, name, phone, active, erased_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, client_uuid) DO UPDATE SET
                    name = excluded.name,
                    phone = excluded.phone,
                    active = excluded.active,
                    erased_at = excluded.erased_at
                """,
                clientUuid,
                tenantId,
                text(payload, "name"),
                optionalText(payload, "phone"),
                payload.path("active").asBoolean(true),
                // M5-10. Absent on every payload written before V123 and on every customer who was
                // never erased, so the tolerant read rather than a required field - a till that has
                // not been updated must not start failing to sync its customers.
                optionalTimestamp(payload, "erasedAt"));
    }
}
