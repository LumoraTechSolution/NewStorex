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
                            tax_mode, tax_rate_bp, sold_at, rounding_adjustment_minor, change_minor)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        optionalNumber(payload, "changeMinor"));

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
            jdbc.update(
                    """
                    INSERT INTO sale_items (
                        sale_id, line_no, product_client_uuid, qty,
                        unit_price_minor, discount_minor, tax_minor, line_total_minor)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    saleId,
                    (int) number(line, "lineNo"),
                    UUID.fromString(text(line, "productClientUuid")),
                    (int) number(line, "qty"),
                    number(line, "unitPriceMinor"),
                    number(line, "discountMinor"),
                    number(line, "taxMinor"),
                    number(line, "lineTotalMinor"));
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

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
