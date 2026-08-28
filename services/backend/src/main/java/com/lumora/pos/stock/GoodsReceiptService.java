package com.lumora.pos.stock;

import com.lumora.pos.outbox.OutboxWriter;
import com.lumora.pos.shop.LocalShop;
import com.lumora.pos.shop.LocalShop.Branch;
import com.lumora.pos.stock.SupplierService.SupplierRow;
import com.lumora.pos.user.UserService.Operator;
import com.lumora.pos.web.RejectedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods arriving (M3-04).
 *
 * <p>The first thing in this system that puts stock <em>on</em> a shelf. Every movement until now
 * took it off — SALE in M1, RETURN in M2 — so stock on hand has been a number that only ever went
 * down, and M3-07's "on hand is Σ movements" has had nothing true to say.
 *
 * <h2>The receipt does not touch a level, because there isn't one</h2>
 *
 * This is the obvious place to increment a {@code quantity_on_hand} column, and it is the reason
 * V100 says not to add one. The increment is wrong within a week: a receipt entered twice, a
 * correction applied to the level and not the history, two tills receiving while offline. Instead
 * each line writes a RECEIVE movement, on hand stays {@code Σ qty_delta}, and redelivery of the
 * same receipt adds nothing because every movement carries its own {@code client_uuid}.
 *
 * <h2>Cost is recorded and never becomes a price</h2>
 *
 * {@code unitCostMinor} is what the shop paid. Nothing here writes it to {@code
 * products.price_minor}. A delivery that repriced the shelf would be the supplier setting the
 * shop's margin, and the shopkeeper would learn about it from a customer.
 *
 * <h2>Immutable</h2>
 *
 * There is no edit and no delete. Same argument as a refund not editing a sale (M2-06): the receipt
 * says what was checked in, and rewriting it destroys the only evidence of what the shop believed
 * at the time. A receipt entered wrongly is corrected by an ADJUST movement with a reason (M3-05),
 * which leaves the miscount <em>and</em> the correction on the record.
 */
@Service
public class GoodsReceiptService {

    private final JdbcTemplate jdbc;
    private final LocalShop shop;
    private final SupplierService suppliers;
    private final OutboxWriter outbox;

    public GoodsReceiptService(
            JdbcTemplate jdbc, LocalShop shop, SupplierService suppliers, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.shop = shop;
        this.suppliers = suppliers;
        this.outbox = outbox;
    }

    // -------------------------------------------------------------------------- shapes

    /** One line of a delivery note, as the screen sends it. */
    public record ReceiptLine(UUID productClientUuid, int qty, long unitCostMinor) {}

    public record ReceiptLineRow(
            int lineNo,
            UUID productClientUuid,
            String sku,
            String productName,
            int qty,
            long unitCostMinor,
            long lineCostMinor) {}

    public record ReceiptRow(
            long id,
            UUID clientUuid,
            String supplierName,
            long supplierId,
            String reference,
            Instant receivedAt,
            String note,
            String receivedByName,
            int lineCount,
            int totalQty,
            long totalCostMinor,
            List<ReceiptLineRow> lines) {}

    // ------------------------------------------------------------------------ receiving

    /**
     * Books a delivery in.
     *
     * <p>Deliberately does <strong>not</strong> require an open shift. Selling does (M2-01) because
     * a sale outside a shift is cash nothing reconciles; stock is not cash, goods arrive at seven
     * in the morning, and a system that refuses the delivery until somebody counts a float is a
     * system people work around.
     *
     * <p>{@code clientUuid} comes from the caller, as everywhere else, so a double-pressed Save is
     * one delivery rather than two — which on this screen would be a doubled shelf.
     */
    @Transactional
    public ReceiptRow receive(
            long tenantId,
            String branchCode,
            UUID clientUuid,
            long supplierId,
            String reference,
            String note,
            List<ReceiptLine> lines,
            Operator operator) {

        // A retry of a request that already landed is the same receipt, not a second delivery.
        // Checked before anything else so the answer is the original document, not a refusal.
        List<Long> existing =
                jdbc.queryForList(
                        "SELECT id FROM goods_receipts WHERE tenant_id = ? AND client_uuid = ?",
                        Long.class,
                        tenantId,
                        clientUuid);
        if (!existing.isEmpty()) {
            return byId(tenantId, existing.get(0));
        }

        SupplierRow supplier = suppliers.requireActive(tenantId, supplierId);
        Branch branch = shop.branch(branchCode);
        String cleanReference = blankToNull(reference);
        requireLines(lines);
        refuseDuplicateReference(tenantId, supplierId, cleanReference);

        Long receiptId =
                jdbc.queryForObject(
                        """
                        INSERT INTO goods_receipts (
                            client_uuid, tenant_id, branch_id, supplier_id, reference, note, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                        Long.class,
                        clientUuid,
                        tenantId,
                        branch.id(),
                        supplierId,
                        cleanReference,
                        blankToNull(note),
                        operator.id());

        List<Map<String, Object>> movementPayloads = new ArrayList<>();
        int lineNo = 0;

        for (ReceiptLine line : lines) {
            lineNo++;
            long productId = productId(tenantId, line.productClientUuid());

            jdbc.update(
                    """
                    INSERT INTO goods_receipt_items
                        (goods_receipt_id, line_no, product_id, qty, unit_cost_minor)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    receiptId,
                    lineNo,
                    productId,
                    line.qty(),
                    line.unitCostMinor());

            // Positive: stock arriving. The uuid is minted here and carried in the payload rather
            // than invented cloud-side, for the same reason a sale's movements are (M1-15) — it is
            // the idempotency key the cloud upserts on, and a movement renamed at the far end
            // would add stock again on every redelivery.
            UUID movementUuid = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO stock_movements (
                        client_uuid, tenant_id, branch_id, product_id,
                        qty_delta, reason, ref_type, ref_id, created_by)
                    VALUES (?, ?, ?, ?, ?, 'RECEIVE', 'goods_receipt', ?, ?)
                    """,
                    movementUuid,
                    tenantId,
                    branch.id(),
                    productId,
                    line.qty(),
                    receiptId,
                    operator.id());

            Map<String, Object> movement = new LinkedHashMap<>();
            movement.put("clientUuid", movementUuid);
            movement.put("productClientUuid", line.productClientUuid());
            movement.put("qtyDelta", line.qty());
            movement.put("reason", "RECEIVE");
            movementPayloads.add(movement);
        }

        ReceiptRow saved = byId(tenantId, receiptId);

        // Same transaction as the rows above (§A). A delivery that exists locally without its sync
        // record is stock the cloud will never hear about, and the shop's on-hand figures would
        // disagree between the till and the console forever after.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientUuid", clientUuid);
        payload.put("branchCode", branch.code());
        payload.put("supplierName", supplier.name());
        payload.put("reference", cleanReference);
        payload.put("receivedAt", saved.receivedAt().toString());
        payload.put("totalCostMinor", saved.totalCostMinor());
        payload.put("movements", movementPayloads);
        outbox.enqueue(tenantId, "goods_receipt", clientUuid, payload);

        return saved;
    }

    // -------------------------------------------------------------------------- reading

    /** Recent deliveries, newest first. Headers only — the lines are on the detail. */
    @Transactional(readOnly = true)
    public List<ReceiptRow> recent(long tenantId, int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        List<Long> ids =
                jdbc.queryForList(
                        """
                        SELECT id FROM goods_receipts
                         WHERE tenant_id = ?
                         ORDER BY received_at DESC, id DESC
                         LIMIT ?
                        """,
                        Long.class,
                        tenantId,
                        capped);
        List<ReceiptRow> rows = new ArrayList<>(ids.size());
        for (Long id : ids) {
            rows.add(byId(tenantId, id));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public ReceiptRow byId(long tenantId, long receiptId) {
        ReceiptRow header;
        try {
            header =
                    jdbc.queryForObject(
                            """
                            SELECT g.id, g.client_uuid, g.supplier_id, s.name AS supplier_name,
                                   g.reference, g.received_at, g.note, u.display_name AS received_by
                              FROM goods_receipts g
                              JOIN suppliers s ON s.id = g.supplier_id
                              JOIN users u ON u.id = g.created_by
                             WHERE g.tenant_id = ? AND g.id = ?
                            """,
                            (rs, row) ->
                                    new ReceiptRow(
                                            rs.getLong("id"),
                                            rs.getObject("client_uuid", UUID.class),
                                            rs.getString("supplier_name"),
                                            rs.getLong("supplier_id"),
                                            rs.getString("reference"),
                                            rs.getTimestamp("received_at").toInstant(),
                                            rs.getString("note"),
                                            rs.getString("received_by"),
                                            0,
                                            0,
                                            0,
                                            List.of()),
                            tenantId,
                            receiptId);
        } catch (EmptyResultDataAccessException e) {
            throw new RejectedException("No such goods receipt");
        }

        List<ReceiptLineRow> lines =
                jdbc.query(
                        """
                        SELECT i.line_no, i.qty, i.unit_cost_minor,
                               p.client_uuid AS product_client_uuid, p.sku, p.name
                          FROM goods_receipt_items i
                          JOIN products p ON p.id = i.product_id
                         WHERE i.goods_receipt_id = ?
                         ORDER BY i.line_no
                        """,
                        (rs, row) ->
                                new ReceiptLineRow(
                                        rs.getInt("line_no"),
                                        rs.getObject("product_client_uuid", UUID.class),
                                        rs.getString("sku"),
                                        rs.getString("name"),
                                        rs.getInt("qty"),
                                        rs.getLong("unit_cost_minor"),
                                        rs.getLong("qty") * rs.getLong("unit_cost_minor")),
                        receiptId);

        int totalQty = lines.stream().mapToInt(ReceiptLineRow::qty).sum();
        long totalCost = lines.stream().mapToLong(ReceiptLineRow::lineCostMinor).sum();

        return new ReceiptRow(
                header.id(),
                header.clientUuid(),
                header.supplierName(),
                header.supplierId(),
                header.reference(),
                header.receivedAt(),
                header.note(),
                header.receivedByName(),
                lines.size(),
                totalQty,
                totalCost,
                lines);
    }

    // --------------------------------------------------------------------------- guards

    private void requireLines(List<ReceiptLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new RejectedException("A delivery needs at least one line");
        }

        LinkedHashSet<UUID> seen = new LinkedHashSet<>();
        for (ReceiptLine line : lines) {
            if (line.productClientUuid() == null) {
                throw new RejectedException("Every line needs a product");
            }
            if (!seen.add(line.productClientUuid())) {
                throw new RejectedException(
                        "The same product is on this delivery twice. Add the quantities together"
                                + " on one line.");
            }
            if (line.qty() <= 0) {
                throw new RejectedException(
                        "A received quantity must be at least 1. To take stock off, use a stock"
                                + " adjustment.");
            }
            if (line.unitCostMinor() < 0) {
                throw new RejectedException("A cost cannot be negative");
            }
        }
    }

    /**
     * The constraint that stops the commonest stock error there is.
     *
     * <p>One delivery note keyed in by two people doubles every quantity on it, with nothing on
     * screen to suggest anything happened. The unique index would refuse the second one anyway;
     * this checks first so the message names the delivery note rather than an index.
     */
    private void refuseDuplicateReference(long tenantId, long supplierId, String reference) {
        if (reference == null) {
            return;
        }
        List<Long> clash =
                jdbc.queryForList(
                        """
                        SELECT id FROM goods_receipts
                         WHERE tenant_id = ? AND supplier_id = ? AND btrim(reference) = btrim(?)
                        """,
                        Long.class,
                        tenantId,
                        supplierId,
                        reference);
        if (!clash.isEmpty()) {
            throw new RejectedException(
                    "Delivery note "
                            + reference
                            + " from this supplier is already booked in. Check the stock movements"
                            + " before entering it again — a delivery keyed in twice doubles every"
                            + " quantity on it.");
        }
    }

    private long productId(long tenantId, UUID productClientUuid) {
        List<Long> found =
                jdbc.queryForList(
                        "SELECT id FROM products WHERE tenant_id = ? AND client_uuid = ?",
                        Long.class,
                        tenantId,
                        productClientUuid);
        if (found.isEmpty()) {
            throw new RejectedException("Unknown product on this delivery: " + productClientUuid);
        }
        return found.get(0);
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
