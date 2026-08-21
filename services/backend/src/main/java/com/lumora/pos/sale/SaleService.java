package com.lumora.pos.sale;

import com.lumora.pos.invoice.InvoiceNumberAllocator;
import com.lumora.pos.outbox.OutboxWriter;
import com.lumora.pos.shift.ShiftService;
import com.lumora.pos.shop.LocalShop;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits a sale to the local database, where it is final.
 *
 * <p>Everything below happens in <strong>one</strong> transaction: the sale, its lines, the stock
 * movements it causes, and the outbox row that will eventually carry it to the cloud. They commit
 * together or not at all, so a sale can never exist without its sync record — and nothing here
 * waits on the network. The receipt prints and the drawer opens the moment this returns.
 *
 * <p>Written with {@link JdbcTemplate} rather than JPA so the transaction's contents are visible in
 * one place. This is the one method in the system where "which statements are in this transaction"
 * is the entire design.
 */
@Service
public class SaleService {

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final InvoiceNumberAllocator invoiceNumbers;
    private final LocalShop shop;
    private final ShiftService shifts;

    public SaleService(
            JdbcTemplate jdbc,
            OutboxWriter outbox,
            InvoiceNumberAllocator invoiceNumbers,
            LocalShop shop,
            ShiftService shifts) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.invoiceNumbers = invoiceNumbers;
        this.shop = shop;
        this.shifts = shifts;
    }

    @Transactional
    public SaleResponse commit(CreateSaleRequest request) {
        SaleResponse existing = findByClientUuid(request.clientUuid());
        if (existing != null) {
            // A retry. The terminal resent because it never saw the response, not because it
            // wants a second sale. Returning the original is what makes the till's retry safe.
            return existing;
        }

        assertTotalsAreSelfConsistent(request);

        LocalShop.Branch branch = shop.branch(request.branchCode());

        // M2-01. The till does not sell unreconciled: a sale outside a shift is cash that
        // nothing counts at the end of the day, which is the hole M2 exists to close. This
        // rejects rather than attaching nothing — and it costs the offline guarantee nothing,
        // because opening a shift is entirely local. §A is about the network being on the
        // critical path of nothing, not about the till having no workflow.
        long shiftId =
                shifts.requireOpenShiftId(branch.tenantId(), branch.id(), request.terminalCode());

        Instant soldAt = request.soldAt() != null ? request.soldAt() : Instant.now();
        String invoiceNumber =
                invoiceNumbers.allocate(
                        branch.tenantId(), branch.id(), branch.code(), request.terminalCode());

        // Two identical requests in flight at once are settled by the unique index on
        // client_uuid, and the loser is deliberately not caught here. Postgres aborts the whole
        // transaction on a constraint violation, so a catch block cannot read the winner back —
        // the recovery query fails with 25P02 and the real error is replaced by a confusing one.
        // Letting it roll back is the only correct recovery: the terminal retries, and the
        // findByClientUuid at the top of this method returns the winner's sale. Found while
        // building M2-01, which needed the same shape and could not make it work either.
        long saleId = insertSale(request, branch, invoiceNumber, soldAt, shiftId);

        LinesAndMovements written = insertLinesAndMovements(request, branch, saleId);
        List<Map<String, Object>> tenderPayloads = insertPayments(request, saleId);
        outbox.enqueue(
                branch.tenantId(),
                "sale",
                request.clientUuid(),
                buildPayload(
                        request,
                        invoiceNumber,
                        soldAt,
                        written.lines(),
                        written.movements(),
                        tenderPayloads,
                        shiftClientUuid(shiftId)));

        return new SaleResponse(
                request.clientUuid(),
                saleId,
                invoiceNumber,
                request.totalMinor(),
                request.changeMinor(),
                soldAt,
                false);
    }

    // ------------------------------------------------------------------ consistency

    /**
     * A checksum, not a second opinion.
     *
     * <p>The money was computed by {@code @lumora/domain} on the terminal and is what the receipt
     * printed. Recomputing VAT here would be exactly the duplicated implementation the architecture
     * exists to prevent. What this does check is that the numbers add up — which catches a
     * malformed request or a serialisation bug without ever disagreeing about rounding.
     */
    private void assertTotalsAreSelfConsistent(CreateSaleRequest request) {
        long lineSum = request.lines().stream().mapToLong(CreateSaleRequest.Line::lineTotalMinor).sum();

        if (lineSum != request.subtotalMinor()) {
            throw new SaleRejectedException(
                    "Line totals sum to %d but subtotalMinor is %d".formatted(lineSum, request.subtotalMinor()));
        }
        if (request.subtotalMinor() - request.discountMinor() != request.totalMinor()) {
            throw new SaleRejectedException(
                    "subtotalMinor %d less discountMinor %d is not totalMinor %d"
                            .formatted(request.subtotalMinor(), request.discountMinor(), request.totalMinor()));
        }
        if (request.taxMinor() > request.totalMinor()) {
            throw new SaleRejectedException(
                    "taxMinor %d exceeds totalMinor %d".formatted(request.taxMinor(), request.totalMinor()));
        }

        // Since M1-18 the lines are the only thing that can say what tax a sale carried:
        // with more than one rate in the basket there is no single rate to recompute it
        // from. cartTotals sums the lines to get the sale's tax, so this identity holds by
        // construction on the terminal — and a payload where it does not is one this
        // service cannot record faithfully, whatever it does with it.
        long lineTaxSum = request.lines().stream().mapToLong(CreateSaleRequest.Line::taxMinor).sum();
        if (lineTaxSum != request.taxMinor()) {
            throw new SaleRejectedException(
                    "Line taxes sum to %d but taxMinor is %d".formatted(lineTaxSum, request.taxMinor()));
        }

        for (CreateSaleRequest.Line line : request.lines()) {
            // A line's tax comes out of what was charged for it, which is at most its own
            // total. Cheap, and it localises a bad line instead of only failing in aggregate.
            if (line.taxMinor() > line.lineTotalMinor()) {
                throw new SaleRejectedException(
                        "Line for product %s has taxMinor %d exceeding lineTotalMinor %d"
                                .formatted(line.productClientUuid(), line.taxMinor(), line.lineTotalMinor()));
            }
            // Half a stamp is a bug at the sender, not a shape to interpret: inheriting the
            // missing half would silently pair one sale's mode with another line's rate.
            if ((line.taxMode() == null) != (line.taxRateBp() == null)) {
                throw new SaleRejectedException(
                        "Line for product %s carries taxMode=%s and taxRateBp=%s — send both or neither"
                                .formatted(line.productClientUuid(), line.taxMode(), line.taxRateBp()));
            }
        }

        // Same idea, for how the sale was paid. summariseTender (@lumora/domain) guarantees
        // sum(tenders) - change == total + roundingAdjustment for any settled tender; this is
        // that identity checked, not re-derived. roundingAdjustmentMinor may be negative (cash
        // rounded down), so it is added rather than subtracted like the others.
        long tenderedMinor =
                request.tenders().stream().mapToLong(CreateSaleRequest.Tender::amountMinor).sum();
        long expectedMinor = request.totalMinor() + request.roundingAdjustmentMinor();
        if (tenderedMinor - request.changeMinor() != expectedMinor) {
            throw new SaleRejectedException(
                    "tenders sum to %d less changeMinor %d, but totalMinor %d plus roundingAdjustmentMinor %d is %d"
                            .formatted(
                                    tenderedMinor,
                                    request.changeMinor(),
                                    request.totalMinor(),
                                    request.roundingAdjustmentMinor(),
                                    expectedMinor));
        }

        // Only cash can be handed back — the checksum above cannot see *which* tender kind
        // produced the change, only that some total of it was recorded.
        if (request.changeMinor() > 0
                && request.tenders().stream().noneMatch(t -> "CASH".equals(t.kind()))) {
            throw new SaleRejectedException(
                    "changeMinor %d is positive but no CASH tender was recorded"
                            .formatted(request.changeMinor()));
        }
    }

    // ----------------------------------------------------------------------- writes

    private long insertSale(
            CreateSaleRequest request,
            LocalShop.Branch branch,
            String invoiceNumber,
            Instant soldAt,
            long shiftId) {
        return jdbc.queryForObject(
                """
                INSERT INTO sales (
                    client_uuid, tenant_id, branch_id, terminal_code, invoice_number,
                    subtotal_minor, discount_minor, tax_minor, total_minor,
                    tax_mode, tax_rate_bp, sold_at, rounding_adjustment_minor, change_minor,
                    shift_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                request.clientUuid(),
                branch.tenantId(),
                branch.id(),
                request.terminalCode(),
                invoiceNumber,
                request.subtotalMinor(),
                request.discountMinor(),
                request.taxMinor(),
                request.totalMinor(),
                request.taxMode(),
                request.taxRateBp(),
                Timestamp.from(soldAt),
                request.roundingAdjustmentMinor(),
                request.changeMinor(),
                shiftId);
    }

    /** One row per tender line, in the same order the cashier entered them (M1-11). */
    private List<Map<String, Object>> insertPayments(CreateSaleRequest request, long saleId) {
        List<Map<String, Object>> payloads = new java.util.ArrayList<>();
        int lineNo = 0;

        for (CreateSaleRequest.Tender tender : request.tenders()) {
            lineNo++;
            jdbc.update(
                    "INSERT INTO sale_payments (sale_id, line_no, kind, amount_minor) VALUES (?, ?, ?, ?)",
                    saleId,
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

    private LinesAndMovements insertLinesAndMovements(
            CreateSaleRequest request, LocalShop.Branch branch, long saleId) {

        List<Map<String, Object>> payloads = new java.util.ArrayList<>();
        List<Map<String, Object>> movements = new java.util.ArrayList<>();
        int lineNo = 0;

        for (CreateSaleRequest.Line line : request.lines()) {
            lineNo++;
            long productId = resolveProductId(line.productClientUuid());

            // Resolved once, here, and then used for both the row and the payload — so the
            // rate the cloud stores is the rate the shop PC stored, never re-derived at the
            // far end from a sale-level default that may not apply to this line (M1-18).
            String lineTaxMode = line.effectiveTaxMode(request);
            Integer lineTaxRateBp = line.effectiveTaxRateBp(request);

            jdbc.update(
                    """
                    INSERT INTO sale_items (
                        sale_id, product_id, line_no, qty,
                        unit_price_minor, discount_minor, tax_minor, line_total_minor,
                        tax_mode, tax_rate_bp)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    saleId,
                    productId,
                    lineNo,
                    line.qty(),
                    line.unitPriceMinor(),
                    line.discountMinor(),
                    line.taxMinor(),
                    line.lineTotalMinor(),
                    lineTaxMode,
                    lineTaxRateBp);

            // Stock leaves the shelf. Negative, because stock on hand is the sum of these
            // rows and never a stored level.
            //
            // The uuid is generated here and then carried in the outbox payload rather than
            // being minted again cloud-side: it is the idempotency key the cloud upserts on,
            // so a redelivered batch has to arrive bearing the *same* one. A movement invented
            // at the far end would be a new row on every retry, and stock on hand — being the
            // sum of these — would drift down by a sale's worth each time.
            UUID movementUuid = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO stock_movements (
                        client_uuid, tenant_id, branch_id, product_id,
                        qty_delta, reason, ref_type, ref_id, created_by)
                    VALUES (?, ?, ?, ?, ?, 'SALE', 'sale', ?, ?)
                    """,
                    movementUuid,
                    branch.tenantId(),
                    branch.id(),
                    productId,
                    -line.qty(),
                    saleId,
                    LocalShop.SEEDED_OPERATOR_ID);

            Map<String, Object> movement = new LinkedHashMap<>();
            movement.put("clientUuid", movementUuid);
            movement.put("productClientUuid", line.productClientUuid());
            movement.put("qtyDelta", -line.qty());
            movement.put("reason", "SALE");
            movements.add(movement);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("productClientUuid", line.productClientUuid());
            payload.put("lineNo", lineNo);
            payload.put("qty", line.qty());
            payload.put("unitPriceMinor", line.unitPriceMinor());
            payload.put("discountMinor", line.discountMinor());
            payload.put("taxMinor", line.taxMinor());
            payload.put("lineTotalMinor", line.lineTotalMinor());
            payload.put("taxMode", lineTaxMode);
            payload.put("taxRateBp", lineTaxRateBp);
            payloads.add(payload);
        }

        return new LinesAndMovements(payloads, movements);
    }

    private Map<String, Object> buildPayload(
            CreateSaleRequest request,
            String invoiceNumber,
            Instant soldAt,
            List<Map<String, Object>> lines,
            List<Map<String, Object>> movements,
            List<Map<String, Object>> tenders,
            UUID shiftClientUuid) {

        Map<String, Object> payload = new HashMap<>();
        payload.put("clientUuid", request.clientUuid());
        payload.put("branchCode", request.branchCode());
        payload.put("terminalCode", request.terminalCode());
        payload.put("invoiceNumber", invoiceNumber);
        payload.put("soldAt", soldAt.toString());
        payload.put("taxMode", request.taxMode());
        payload.put("taxRateBp", request.taxRateBp());
        payload.put("subtotalMinor", request.subtotalMinor());
        payload.put("discountMinor", request.discountMinor());
        payload.put("taxMinor", request.taxMinor());
        payload.put("totalMinor", request.totalMinor());
        payload.put("roundingAdjustmentMinor", request.roundingAdjustmentMinor());
        payload.put("changeMinor", request.changeMinor());
        payload.put("lines", lines);
        payload.put("movements", movements);
        payload.put("tenders", tenders);
        payload.put("shiftClientUuid", shiftClientUuid);
        return payload;
    }

    // ---------------------------------------------------------------------- lookups

    private UUID shiftClientUuid(long shiftId) {
        return jdbc.queryForObject("SELECT client_uuid FROM shifts WHERE id = ?", UUID.class, shiftId);
    }

    private SaleResponse findByClientUuid(UUID clientUuid) {
        List<SaleResponse> found =
                jdbc.query(
                        """
                        SELECT id, invoice_number, total_minor, change_minor, sold_at
                        FROM sales WHERE client_uuid = ?
                        """,
                        (rs, row) ->
                                new SaleResponse(
                                        clientUuid,
                                        rs.getLong("id"),
                                        rs.getString("invoice_number"),
                                        rs.getLong("total_minor"),
                                        rs.getLong("change_minor"),
                                        rs.getTimestamp("sold_at").toInstant(),
                                        true),
                        clientUuid);
        return found.isEmpty() ? null : found.get(0);
    }

    private long resolveProductId(UUID productClientUuid) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM products WHERE client_uuid = ?", Long.class, productClientUuid);
        } catch (EmptyResultDataAccessException e) {
            throw new SaleRejectedException("Unknown product: " + productClientUuid);
        }
    }

    /**
     * What one pass over the cart wrote: the sale lines, and the stock movements they caused. The
     * two are produced together and must stay together — a line whose movement went missing is the
     * failure mode this record exists to make impossible to introduce by accident.
     */
    private record LinesAndMovements(
            List<Map<String, Object>> lines, List<Map<String, Object>> movements) {}
}
