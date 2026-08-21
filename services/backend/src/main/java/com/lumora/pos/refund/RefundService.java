package com.lumora.pos.refund;

import com.lumora.pos.invoice.InvoiceNumberAllocator;
import com.lumora.pos.outbox.OutboxWriter;
import com.lumora.pos.settings.TenantSettingsService;
import com.lumora.pos.shift.ShiftService;
import com.lumora.pos.shop.LocalShop;
import com.lumora.pos.web.RejectedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Returns (M2-06 … M2-10), and both halves of Gate M2.
 *
 * <h2>What is checked here, and what is trusted</h2>
 *
 * The same split as {@code SaleService}, for the same reason. The <em>amounts</em> arrive already
 * computed by {@code @lumora/domain} on the terminal — apportioning a partial return so repeated
 * partials sum back to the whole is delicate arithmetic, and implementing it a second time in Java
 * is exactly the duplication the architecture exists to prevent. What this service does is enforce
 * the things arithmetic cannot:
 *
 * <ol>
 *   <li><b>The sale exists.</b> A refund with no receipt behind it is how a drawer becomes an ATM.
 *       {@code refunds.sale_id} is NOT NULL and this is the only path that writes it (M2-06).
 *   <li><b>A manager allowed it.</b> {@code TenantSettingsService.verifyManagerPin} throws unless
 *       the PIN is right, and refuses outright when no PIN has been configured (M2-07).
 *   <li><b>Nothing goes back twice.</b> Neither more units than the line holds, nor more money than
 *       the line was charged — both compared against what earlier refunds already took (M2-08).
 *   <li><b>Money goes back the way it came.</b> Every tender kind must have appeared on the sale,
 *       and none may exceed what it paid less what it has already refunded (M2-09).
 * </ol>
 *
 * Points 3 and 4 are <em>caps</em>, not recomputations: comparisons against figures the sale
 * already stores. That is what makes them enforceable here without a second money implementation,
 * and it is why the till's UI is never the only thing standing between a card sale and a cash
 * refund.
 *
 * <h2>The sale is never edited</h2>
 *
 * Nothing here touches {@code sales} or {@code sale_items}. A return writes a new document that
 * points at the sale; the invoice stays exactly as it was issued, because it is what the customer
 * was given and what the revenue authority will be shown.
 */
@Service
public class RefundService {

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final LocalShop shop;
    private final ShiftService shifts;
    private final TenantSettingsService settings;
    private final InvoiceNumberAllocator numbers;

    public RefundService(
            JdbcTemplate jdbc,
            OutboxWriter outbox,
            LocalShop shop,
            ShiftService shifts,
            TenantSettingsService settings,
            InvoiceNumberAllocator numbers) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.shop = shop;
        this.shifts = shifts;
        this.settings = settings;
        this.numbers = numbers;
    }

    // ------------------------------------------------------------------ lookup (M2-06)

    /**
     * Finds the sale a customer is returning against, by the number on their receipt.
     *
     * <p>This is the <em>only</em> way into a refund. There is no "refund without a receipt" path
     * and adding one would defeat the milestone: the receipt is what ties money leaving the drawer
     * to money that once entered it.
     *
     * <p>Each line comes back with how much of it is still returnable, and each tender with how
     * much it can still take. Those are the caps {@link #commit} enforces, sent to the terminal so
     * the cashier is shown a screen that cannot ask for something the backend will refuse.
     */
    @Transactional(readOnly = true)
    public RefundableSaleResponse lookup(String invoiceNumber) {
        long tenantId = shop.soleTenantId();

        List<SaleRow> sales =
                jdbc.query(
                        """
                        SELECT s.id, s.client_uuid, s.invoice_number, s.sold_at, s.total_minor,
                               s.tax_minor, s.change_minor, b.code AS branch_code, s.terminal_code
                          FROM sales s JOIN branches b ON b.id = s.branch_id
                         WHERE s.tenant_id = ? AND s.invoice_number = ?
                        """,
                        (rs, row) ->
                                new SaleRow(
                                        rs.getLong("id"),
                                        rs.getObject("client_uuid", UUID.class),
                                        rs.getString("invoice_number"),
                                        rs.getTimestamp("sold_at").toInstant(),
                                        rs.getLong("total_minor"),
                                        rs.getLong("tax_minor"),
                                        rs.getLong("change_minor"),
                                        rs.getString("branch_code"),
                                        rs.getString("terminal_code")),
                        tenantId,
                        invoiceNumber);
        if (sales.isEmpty()) {
            throw new RejectedException("No sale found for invoice " + invoiceNumber);
        }
        SaleRow sale = sales.get(0);

        return new RefundableSaleResponse(
                sale.id(),
                sale.clientUuid(),
                sale.invoiceNumber(),
                sale.branchCode(),
                sale.terminalCode(),
                sale.soldAt(),
                sale.totalMinor(),
                sale.taxMinor(),
                refundableLines(sale.id()),
                refundableTenders(sale.id(), sale.changeMinor()));
    }

    /**
     * The sale's lines, each with what it was actually charged and what remains returnable.
     *
     * <p>{@code chargedMinor} is derived rather than stored: {@code unit_price × qty − discount}.
     * The identity holds because {@code sale_items.discount_minor} carries the line's own discount
     * <em>and</em> its apportioned share of the order discount (M1-03), while {@code
     * line_total_minor} is only net of the former. So gross less the whole discount is exactly the
     * amount that was charged — the domain's {@code CartLineTotals.netMinor} — and summing it over
     * a sale gives {@code sales.total_minor} back. Refunding {@code line_total_minor} instead would
     * hand back the order discount the customer never paid.
     */
    private List<RefundableSaleResponse.Line> refundableLines(long saleId) {
        return jdbc.query(
                """
                SELECT i.id, i.line_no, i.qty, i.unit_price_minor, i.discount_minor, i.tax_minor,
                       i.tax_mode, i.tax_rate_bp, p.client_uuid AS product_client_uuid, p.name,
                       (i.unit_price_minor * i.qty - i.discount_minor) AS charged_minor,
                       COALESCE((SELECT SUM(ri.qty) FROM refund_items ri WHERE ri.sale_item_id = i.id), 0)
                           AS already_refunded_qty,
                       COALESCE((SELECT SUM(ri.refund_total_minor) FROM refund_items ri WHERE ri.sale_item_id = i.id), 0)
                           AS already_refunded_minor
                  FROM sale_items i JOIN products p ON p.id = i.product_id
                 WHERE i.sale_id = ?
                 ORDER BY i.line_no
                """,
                (rs, row) ->
                        new RefundableSaleResponse.Line(
                                rs.getLong("id"),
                                rs.getInt("line_no"),
                                rs.getObject("product_client_uuid", UUID.class),
                                rs.getString("name"),
                                rs.getInt("qty"),
                                rs.getLong("unit_price_minor"),
                                rs.getLong("charged_minor"),
                                rs.getLong("tax_minor"),
                                rs.getString("tax_mode"),
                                rs.getInt("tax_rate_bp"),
                                rs.getInt("already_refunded_qty"),
                                rs.getLong("already_refunded_minor")),
                saleId);
    }

    /**
     * What each tender kind can still take back — the M2-09 cap.
     *
     * <p>Cash is netted against the change the sale handed out. {@code sale_payments} records what
     * was physically put on the counter, which for cash includes the note that came straight back
     * as change; without this, a customer who paid a 5,000 note for a 3,200 basket would appear
     * refundable for 5,000.
     */
    private List<RefundableSaleResponse.Tender> refundableTenders(long saleId, long changeMinor) {
        List<RefundableSaleResponse.Tender> tenders =
                jdbc.query(
                        """
                        SELECT p.kind,
                               SUM(p.amount_minor) AS paid_minor,
                               COALESCE((SELECT SUM(rp.amount_minor)
                                           FROM refund_payments rp JOIN refunds r ON r.id = rp.refund_id
                                          WHERE r.sale_id = ? AND rp.kind = p.kind), 0) AS refunded_minor
                          FROM sale_payments p
                         WHERE p.sale_id = ?
                         GROUP BY p.kind
                         ORDER BY p.kind
                        """,
                        (rs, row) -> {
                            String kind = rs.getString("kind");
                            long paid = rs.getLong("paid_minor");
                            if ("CASH".equals(kind)) {
                                paid -= changeMinor;
                            }
                            long refunded = rs.getLong("refunded_minor");
                            return new RefundableSaleResponse.Tender(
                                    kind, paid, refunded, Math.max(0, paid - refunded));
                        },
                        saleId,
                        saleId);
        // A cash line that only ever existed to make change contributes nothing refundable.
        return tenders.stream().filter(t -> t.paidMinor() > 0).toList();
    }

    // ------------------------------------------------------------------ commit

    @Transactional
    public RefundResponse commit(CreateRefundRequest request) {
        RefundResponse existing = findByClientUuid(request.clientUuid());
        if (existing != null) {
            // A retry. The terminal resent because it never saw the response, not because the
            // customer is returning the goods twice.
            return existing;
        }

        LocalShop.Branch branch = shop.branch(request.branchCode());

        // M2-07, before anything is read or written. An unset PIN refuses here, not later.
        settings.verifyManagerPin(branch.tenantId(), request.managerPin());

        // M2-06. The sale is looked up by the number on the receipt, and its absence ends this.
        RefundableSaleResponse sale = lookup(request.invoiceNumber());
        long shiftId = shifts.requireOpenShiftId(branch.tenantId(), branch.id(), request.terminalCode());

        assertLinesAreReturnable(sale, request);
        assertTendersAreAllowed(sale, request);

        String creditNoteNumber =
                numbers.allocate(
                        branch.tenantId(),
                        branch.id(),
                        branch.code(),
                        request.terminalCode(),
                        InvoiceNumberAllocator.DocType.CREDIT_NOTE);
        Instant refundedAt = request.refundedAt() != null ? request.refundedAt() : Instant.now();

        // No catch for a duplicate client_uuid: the violation aborts the transaction and a
        // recovery read inside a catch block cannot run. The retry at the top of this method is
        // what resolves a race — see SaleService.
        long refundId =
                    jdbc.queryForObject(
                            """
                            INSERT INTO refunds (
                                client_uuid, tenant_id, branch_id, terminal_code, shift_id, sale_id,
                                credit_note_number, total_minor, tax_minor, rounding_adjustment_minor,
                                authorised_by, created_by, refunded_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            RETURNING id
                            """,
                            Long.class,
                            request.clientUuid(),
                            branch.tenantId(),
                            branch.id(),
                            request.terminalCode(),
                            shiftId,
                            sale.saleId(),
                            creditNoteNumber,
                            request.totalMinor(),
                            request.taxMinor(),
                            request.roundingAdjustmentMinor(),
                            LocalShop.SEEDED_OPERATOR_ID,
                            LocalShop.SEEDED_OPERATOR_ID,
                            Timestamp.from(refundedAt));

        List<Map<String, Object>> linePayloads = insertLinesAndMovements(request, sale, branch, refundId);
        List<Map<String, Object>> tenderPayloads = insertPayments(request, refundId);

        outbox.enqueue(
                branch.tenantId(),
                "refund",
                request.clientUuid(),
                buildPayload(
                        request,
                        sale,
                        branch,
                        shiftId,
                        creditNoteNumber,
                        refundedAt,
                        linePayloads,
                        tenderPayloads));

        return new RefundResponse(
                refundId,
                request.clientUuid(),
                creditNoteNumber,
                sale.invoiceNumber(),
                request.totalMinor(),
                request.taxMinor(),
                request.roundingAdjustmentMinor(),
                refundedAt,
                false);
    }

    // ------------------------------------------------------------------ the caps

    /** M2-08: neither more units than the line holds, nor more money than it was charged. */
    private void assertLinesAreReturnable(RefundableSaleResponse sale, CreateRefundRequest request) {
        Map<Integer, RefundableSaleResponse.Line> byLineNo = new LinkedHashMap<>();
        for (RefundableSaleResponse.Line line : sale.lines()) {
            byLineNo.put(line.lineNo(), line);
        }

        Set<Integer> seen = new HashSet<>();
        long lineTotalSum = 0;
        long lineTaxSum = 0;

        for (CreateRefundRequest.Line requested : request.lines()) {
            RefundableSaleResponse.Line line = byLineNo.get(requested.saleLineNo());
            if (line == null) {
                throw new RejectedException(
                        "Sale %s has no line %d".formatted(sale.invoiceNumber(), requested.saleLineNo()));
            }
            // Two entries for one line would each be checked against the same remaining quantity
            // and together take more than the line holds.
            if (!seen.add(requested.saleLineNo())) {
                throw new RejectedException(
                        "Line %d appears twice in one refund — combine the quantities"
                                .formatted(requested.saleLineNo()));
            }

            int remainingQty = line.qty() - line.alreadyRefundedQty();
            if (requested.qty() > remainingQty) {
                throw new RejectedException(
                        "Cannot return %d of line %d: %d of %d remain"
                                .formatted(requested.qty(), line.lineNo(), remainingQty, line.qty()));
            }

            // The money cap, separate from the quantity cap and not implied by it. Returning the
            // right number of units for the wrong amount is the failure a quantity check alone
            // waves through.
            long remainingMinor = line.chargedMinor() - line.alreadyRefundedMinor();
            if (requested.refundTotalMinor() > remainingMinor) {
                throw new RejectedException(
                        "Cannot refund %d against line %d: only %d of the %d charged is still refundable"
                                .formatted(
                                        requested.refundTotalMinor(),
                                        line.lineNo(),
                                        remainingMinor,
                                        line.chargedMinor()));
            }
            if (requested.taxMinor() > requested.refundTotalMinor()) {
                throw new RejectedException(
                        "Line %d refunds tax %d against a gross of %d"
                                .formatted(line.lineNo(), requested.taxMinor(), requested.refundTotalMinor()));
            }
            if ("OTHER".equals(requested.reasonCode())
                    && (requested.note() == null || requested.note().isBlank())) {
                throw new RejectedException(
                        "Line %d has reason OTHER and needs a note saying why".formatted(line.lineNo()));
            }

            lineTotalSum += requested.refundTotalMinor();
            lineTaxSum += requested.taxMinor();
        }

        // The checksum, exactly as SaleService does it: the figures must agree with each other.
        // Never a second opinion about what they should have been.
        if (lineTotalSum != request.totalMinor()) {
            throw new RejectedException(
                    "Refund lines sum to %d but totalMinor is %d".formatted(lineTotalSum, request.totalMinor()));
        }
        if (lineTaxSum != request.taxMinor()) {
            throw new RejectedException(
                    "Refund line taxes sum to %d but taxMinor is %d".formatted(lineTaxSum, request.taxMinor()));
        }
    }

    /**
     * M2-09, and the half of Gate M2 that money can be stolen through.
     *
     * <p>A card sale refunded in cash is the oldest way to empty a drawer with a receipt in your
     * hand. The rule is not a warning on a screen: a tender kind the sale never took has no
     * capacity here at all, so no such refund can be recorded.
     */
    private void assertTendersAreAllowed(RefundableSaleResponse sale, CreateRefundRequest request) {
        Map<String, RefundableSaleResponse.Tender> byKind = new LinkedHashMap<>();
        for (RefundableSaleResponse.Tender tender : sale.tenders()) {
            byKind.put(tender.kind(), tender);
        }

        Set<String> seen = new HashSet<>();
        long allocated = 0;
        long cashAllocated = 0;

        for (CreateRefundRequest.Tender tender : request.tenders()) {
            if (!seen.add(tender.kind())) {
                throw new RejectedException(
                        "%s appears twice in one refund — combine the amounts".formatted(tender.kind()));
            }

            RefundableSaleResponse.Tender capacity = byKind.get(tender.kind());
            if (capacity == null) {
                throw new RejectedException(
                        "Cannot refund %d to %s: sale %s was not paid with it. A refund goes back the way it came."
                                .formatted(tender.amountMinor(), tender.kind(), sale.invoiceNumber()));
            }
            if (tender.amountMinor() > capacity.refundableMinor()) {
                throw new RejectedException(
                        "Cannot refund %d to %s: only %d of the %d it paid is still refundable"
                                .formatted(
                                        tender.amountMinor(),
                                        tender.kind(),
                                        capacity.refundableMinor(),
                                        capacity.paidMinor()));
            }

            allocated += tender.amountMinor();
            if ("CASH".equals(tender.kind())) {
                cashAllocated += tender.amountMinor();
            }
        }

        if (allocated != request.totalMinor()) {
            throw new RejectedException(
                    "Refund tenders total %d but the refund is %d — a refund is settled in full or not recorded"
                            .formatted(allocated, request.totalMinor()));
        }

        // The rounding residual is bounded rather than recomputed — the same stance as the rest
        // of this class. Cash settles to the nearest rupee (M1-03), so an adjustment is always
        // under one, and only a refund that actually pays out cash can have one at all.
        long rounding = request.roundingAdjustmentMinor();
        if (Math.abs(rounding) >= 100) {
            throw new RejectedException(
                    "roundingAdjustmentMinor %d is a whole rupee or more — cash rounds to the nearest rupee"
                            .formatted(rounding));
        }
        if (rounding != 0 && cashAllocated == 0) {
            throw new RejectedException(
                    "roundingAdjustmentMinor is %d but no cash was refunded — only cash rounds".formatted(rounding));
        }
    }

    // ------------------------------------------------------------------ writes

    private List<Map<String, Object>> insertLinesAndMovements(
            CreateRefundRequest request,
            RefundableSaleResponse sale,
            LocalShop.Branch branch,
            long refundId) {

        Map<Integer, RefundableSaleResponse.Line> byLineNo = new LinkedHashMap<>();
        for (RefundableSaleResponse.Line line : sale.lines()) {
            byLineNo.put(line.lineNo(), line);
        }

        List<Map<String, Object>> payloads = new ArrayList<>();
        int lineNo = 0;

        for (CreateRefundRequest.Line requested : request.lines()) {
            lineNo++;
            RefundableSaleResponse.Line saleLine = byLineNo.get(requested.saleLineNo());

            jdbc.update(
                    """
                    INSERT INTO refund_items (
                        refund_id, line_no, sale_item_id, qty, unit_price_minor,
                        refund_total_minor, tax_minor, tax_mode, tax_rate_bp,
                        reason_code, note, restock)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    refundId,
                    lineNo,
                    saleLine.saleItemId(),
                    requested.qty(),
                    saleLine.unitPriceMinor(),
                    requested.refundTotalMinor(),
                    requested.taxMinor(),
                    // Carried from the sale line, never from the product: M1-05 says a historical
                    // document reprints under the rate it was issued at, and the product's rate
                    // may have changed since.
                    saleLine.taxMode(),
                    saleLine.taxRateBp(),
                    requested.reasonCode(),
                    requested.note(),
                    requested.restock());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lineNo", lineNo);
            payload.put("saleLineNo", saleLine.lineNo());
            payload.put("productClientUuid", saleLine.productClientUuid());
            payload.put("qty", requested.qty());
            payload.put("unitPriceMinor", saleLine.unitPriceMinor());
            payload.put("refundTotalMinor", requested.refundTotalMinor());
            payload.put("taxMinor", requested.taxMinor());
            payload.put("taxMode", saleLine.taxMode());
            payload.put("taxRateBp", saleLine.taxRateBp());
            payload.put("reasonCode", requested.reasonCode());
            payload.put("note", requested.note());
            payload.put("restock", requested.restock());

            // M2-10. Only what is going back on the shelf. A damaged item is not inventory, and a
            // RETURN movement for it would tell the owner they have stock they cannot sell —
            // which is why restock is a per-line flag and not a property of the refund.
            //
            // The uuid is minted here and carried in the payload rather than invented cloud-side,
            // for the same reason M1-15 gave for sale movements: it is the idempotency key the
            // cloud upserts on, so a redelivered batch must arrive bearing the same one. A
            // movement invented at the far end would be a new row on every retry, and stock on
            // hand — being Σ entries — would drift upwards by a return's worth each time.
            if (Boolean.TRUE.equals(requested.restock())) {
                UUID movementUuid = UUID.randomUUID();
                jdbc.update(
                        """
                        INSERT INTO stock_movements (
                            client_uuid, tenant_id, branch_id, product_id,
                            qty_delta, reason, ref_type, ref_id, created_by)
                        VALUES (?, ?, ?, (SELECT product_id FROM sale_items WHERE id = ?),
                                ?, 'RETURN', 'refund', ?, ?)
                        """,
                        movementUuid,
                        branch.tenantId(),
                        branch.id(),
                        saleLine.saleItemId(),
                        requested.qty(),
                        refundId,
                        LocalShop.SEEDED_OPERATOR_ID);

                Map<String, Object> movement = new LinkedHashMap<>();
                movement.put("clientUuid", movementUuid);
                movement.put("productClientUuid", saleLine.productClientUuid());
                movement.put("qtyDelta", requested.qty());
                movement.put("reason", "RETURN");
                payload.put("movement", movement);
            }

            payloads.add(payload);
        }

        return payloads;
    }

    private List<Map<String, Object>> insertPayments(CreateRefundRequest request, long refundId) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        int lineNo = 0;

        for (CreateRefundRequest.Tender tender : request.tenders()) {
            lineNo++;
            jdbc.update(
                    "INSERT INTO refund_payments (refund_id, line_no, kind, amount_minor) VALUES (?, ?, ?, ?)",
                    refundId,
                    lineNo,
                    tender.kind(),
                    tender.amountMinor());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lineNo", lineNo);
            payload.put("kind", tender.kind());
            payload.put("amountMinor", tender.amountMinor());
            payloads.add(payload);
        }

        return payloads;
    }

    private Map<String, Object> buildPayload(
            CreateRefundRequest request,
            RefundableSaleResponse sale,
            LocalShop.Branch branch,
            long shiftId,
            String creditNoteNumber,
            Instant refundedAt,
            List<Map<String, Object>> lines,
            List<Map<String, Object>> tenders) {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientUuid", request.clientUuid());
        payload.put("branchCode", branch.code());
        payload.put("terminalCode", request.terminalCode());
        payload.put("shiftClientUuid", shiftClientUuid(shiftId));
        // By uuid, not by invoice number: the cloud keys everything on client_uuid, and a refund
        // that reached it before its sale still has to resolve to the right row once the sale
        // lands.
        payload.put("saleClientUuid", sale.saleClientUuid());
        payload.put("saleInvoiceNumber", sale.invoiceNumber());
        payload.put("creditNoteNumber", creditNoteNumber);
        payload.put("totalMinor", request.totalMinor());
        payload.put("taxMinor", request.taxMinor());
        payload.put("roundingAdjustmentMinor", request.roundingAdjustmentMinor());
        payload.put("refundedAt", refundedAt.toString());
        payload.put("lines", lines);
        payload.put("tenders", tenders);
        return payload;
    }

    // ------------------------------------------------------------------ lookups

    private UUID shiftClientUuid(long shiftId) {
        return jdbc.queryForObject("SELECT client_uuid FROM shifts WHERE id = ?", UUID.class, shiftId);
    }

    private RefundResponse findByClientUuid(UUID clientUuid) {
        List<RefundResponse> found =
                jdbc.query(
                        """
                        SELECT r.id, r.client_uuid, r.credit_note_number, s.invoice_number,
                               r.total_minor, r.tax_minor, r.rounding_adjustment_minor, r.refunded_at
                          FROM refunds r JOIN sales s ON s.id = r.sale_id
                         WHERE r.client_uuid = ?
                        """,
                        (rs, row) ->
                                new RefundResponse(
                                        rs.getLong("id"),
                                        rs.getObject("client_uuid", UUID.class),
                                        rs.getString("credit_note_number"),
                                        rs.getString("invoice_number"),
                                        rs.getLong("total_minor"),
                                        rs.getLong("tax_minor"),
                                        rs.getLong("rounding_adjustment_minor"),
                                        rs.getTimestamp("refunded_at").toInstant(),
                                        true),
                        clientUuid);
        return found.isEmpty() ? null : found.get(0);
    }

    private record SaleRow(
            long id,
            UUID clientUuid,
            String invoiceNumber,
            Instant soldAt,
            long totalMinor,
            long taxMinor,
            long changeMinor,
            String branchCode,
            String terminalCode) {}
}
