package com.lumora.pos.sale;

import com.lumora.pos.invoice.InvoiceNumberAllocator;
import com.lumora.pos.outbox.OutboxWriter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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

    /**
     * Until users exist (M3-08) every movement is attributed to the seeded operator. This is a
     * placeholder for a real id, not a null-object — the column is NOT NULL because "who did this"
     * is the point of an audit trail.
     */
    private static final long SEEDED_OPERATOR_ID = 1L;

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final InvoiceNumberAllocator invoiceNumbers;

    public SaleService(JdbcTemplate jdbc, OutboxWriter outbox, InvoiceNumberAllocator invoiceNumbers) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.invoiceNumbers = invoiceNumbers;
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

        Branch branch = resolveBranch(request.branchCode());
        Instant soldAt = request.soldAt() != null ? request.soldAt() : Instant.now();
        String invoiceNumber =
                invoiceNumbers.allocate(
                        branch.tenantId(), branch.id(), branch.code(), request.terminalCode());

        long saleId;
        try {
            saleId = insertSale(request, branch, invoiceNumber, soldAt);
        } catch (DuplicateKeyException raced) {
            // Two identical requests in flight at once; the unique index on client_uuid settled
            // it. Whichever lost re-reads the winner's sale.
            SaleResponse winner = findByClientUuid(request.clientUuid());
            if (winner != null) {
                return winner;
            }
            throw raced;
        }

        List<Map<String, Object>> linePayloads = insertLinesAndMovements(request, branch, saleId);
        outbox.enqueue(
                branch.tenantId(),
                "sale",
                request.clientUuid(),
                buildPayload(request, invoiceNumber, soldAt, linePayloads));

        return new SaleResponse(
                request.clientUuid(), saleId, invoiceNumber, request.totalMinor(), soldAt, false);
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
    }

    // ----------------------------------------------------------------------- writes

    private long insertSale(
            CreateSaleRequest request, Branch branch, String invoiceNumber, Instant soldAt) {
        return jdbc.queryForObject(
                """
                INSERT INTO sales (
                    client_uuid, tenant_id, branch_id, terminal_code, invoice_number,
                    subtotal_minor, discount_minor, tax_minor, total_minor,
                    tax_mode, tax_rate_bp, sold_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                Timestamp.from(soldAt));
    }

    private List<Map<String, Object>> insertLinesAndMovements(
            CreateSaleRequest request, Branch branch, long saleId) {

        List<Map<String, Object>> payloads = new java.util.ArrayList<>();
        int lineNo = 0;

        for (CreateSaleRequest.Line line : request.lines()) {
            lineNo++;
            long productId = resolveProductId(line.productClientUuid());

            jdbc.update(
                    """
                    INSERT INTO sale_items (
                        sale_id, product_id, line_no, qty,
                        unit_price_minor, discount_minor, tax_minor, line_total_minor)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    saleId,
                    productId,
                    lineNo,
                    line.qty(),
                    line.unitPriceMinor(),
                    line.discountMinor(),
                    line.taxMinor(),
                    line.lineTotalMinor());

            // Stock leaves the shelf. Negative, because stock on hand is the sum of these
            // rows and never a stored level.
            jdbc.update(
                    """
                    INSERT INTO stock_movements (
                        client_uuid, tenant_id, branch_id, product_id,
                        qty_delta, reason, ref_type, ref_id, created_by)
                    VALUES (?, ?, ?, ?, ?, 'SALE', 'sale', ?, ?)
                    """,
                    UUID.randomUUID(),
                    branch.tenantId(),
                    branch.id(),
                    productId,
                    -line.qty(),
                    saleId,
                    SEEDED_OPERATOR_ID);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("productClientUuid", line.productClientUuid());
            payload.put("lineNo", lineNo);
            payload.put("qty", line.qty());
            payload.put("unitPriceMinor", line.unitPriceMinor());
            payload.put("discountMinor", line.discountMinor());
            payload.put("taxMinor", line.taxMinor());
            payload.put("lineTotalMinor", line.lineTotalMinor());
            payloads.add(payload);
        }

        return payloads;
    }

    private Map<String, Object> buildPayload(
            CreateSaleRequest request,
            String invoiceNumber,
            Instant soldAt,
            List<Map<String, Object>> lines) {

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
        payload.put("lines", lines);
        return payload;
    }

    // ---------------------------------------------------------------------- lookups

    private SaleResponse findByClientUuid(UUID clientUuid) {
        List<SaleResponse> found =
                jdbc.query(
                        """
                        SELECT id, invoice_number, total_minor, sold_at
                        FROM sales WHERE client_uuid = ?
                        """,
                        (rs, row) ->
                                new SaleResponse(
                                        clientUuid,
                                        rs.getLong("id"),
                                        rs.getString("invoice_number"),
                                        rs.getLong("total_minor"),
                                        rs.getTimestamp("sold_at").toInstant(),
                                        true),
                        clientUuid);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * A shop PC holds exactly one tenant — that is what "desktop" means. Branch codes are only
     * unique within a tenant, so the lookup is scoped to that one, and the invariant is asserted
     * rather than assumed: a second tenant here means something upstream is wrong, and finding out
     * at the next sale with a confusing error is worse than finding out now.
     *
     * <p>When the cloud needs this logic (M4-01) the tenant comes from the request's authenticated
     * context instead. This method is the seam where that changes.
     */
    private Branch resolveBranch(String branchCode) {
        long tenantId = resolveSoleTenantId();
        try {
            return jdbc.queryForObject(
                    "SELECT id, tenant_id, code FROM branches WHERE tenant_id = ? AND code = ?",
                    (rs, row) -> new Branch(rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("code")),
                    tenantId,
                    branchCode);
        } catch (EmptyResultDataAccessException e) {
            throw new SaleRejectedException("Unknown branch code: " + branchCode);
        }
    }

    private long resolveSoleTenantId() {
        List<Long> tenantIds = jdbc.queryForList("SELECT id FROM tenants ORDER BY id", Long.class);
        if (tenantIds.size() != 1) {
            throw new IllegalStateException(
                    "A desktop database must contain exactly one tenant, found "
                            + tenantIds.size()
                            + ". Reset it with `pnpm db:reset` and reseed with `pnpm db:seed`.");
        }
        return tenantIds.get(0);
    }

    private long resolveProductId(UUID productClientUuid) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM products WHERE client_uuid = ?", Long.class, productClientUuid);
        } catch (EmptyResultDataAccessException e) {
            throw new SaleRejectedException("Unknown product: " + productClientUuid);
        }
    }

    private record Branch(long id, long tenantId, String code) {}
}
