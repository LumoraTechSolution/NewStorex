package com.lumora.pos.invoice;

import com.lumora.pos.outbox.OutboxWriter;
import com.lumora.pos.settings.TenantSettingsService;
import com.lumora.pos.shop.LocalShop;
import com.lumora.pos.web.RejectedException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues an IRD tax invoice against a sale that has already been rung up (M5-09).
 *
 * <p>Gazette Extraordinary 2481/22 of 27 March 2026, in force since 1 July 2026, with IRD Circular
 * SEC/2026/E/03 of 20 May 2026 as its explanatory note. Where the two appear to differ the circular
 * is the operative reading — see {@link #purchaserOf} on the one place that matters.
 *
 * <p><b>On request, not on every sale.</b> The till prints its ordinary receipt for every
 * transaction; this produces the separate legal document a purchaser asks for. That is not a
 * shortcut, it is what makes the shop workable: §4.2 forbids putting exempt supplies on a tax
 * invoice, and a grocery basket mixes exempt and standard-rated goods constantly. Forcing every
 * receipt into this format would mean splitting half the baskets in the shop across two documents.
 *
 * <p>Like {@code RefundService}, the only way in starts from a committed sale. There is no method
 * here that takes an amount and prints a tax invoice for it.
 */
@Service
public class TaxInvoiceService {

    private final JdbcTemplate jdbc;
    private final LocalShop shop;
    private final InvoiceNumberAllocator allocator;
    private final TenantSettingsService settings;
    private final OutboxWriter outbox;

    public TaxInvoiceService(
            JdbcTemplate jdbc,
            LocalShop shop,
            InvoiceNumberAllocator allocator,
            TenantSettingsService settings,
            OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.shop = shop;
        this.allocator = allocator;
        this.settings = settings;
        this.outbox = outbox;
    }

    /**
     * Issues one, or returns the one already issued.
     *
     * <p>Re-issuing is a reprint, never a second document. Two tax invoices for one supply is the
     * error the numbering rules exist to make visible, and a cashier pressing a key twice must not
     * be able to create one.
     */
    @Transactional
    public TaxInvoice issue(String saleInvoiceNumber, Purchaser purchaser) {
        long tenantId = shop.soleTenantId();
        SaleRow sale = findSale(tenantId, saleInvoiceNumber);

        List<TaxInvoice> existing = findBySale(sale.id());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        // Refused rather than defaulted. An invoice carrying a placeholder TIN is worse than no
        // invoice: the purchaser files it and claims input credit against a number that is not
        // the shop's.
        TenantSettingsService.SupplierIdentity supplier =
                settings
                        .supplierIdentity(tenantId)
                        .orElseThrow(
                                () ->
                                        new RejectedException(
                                                "This shop has no VAT registration details set — a tax invoice needs the"
                                                        + " supplier's TIN, registered name and address before it can be issued."));

        Totals totals = taxableTotals(sale.id());
        if (totals.grossMinor() == 0) {
            // Every line was exempt or zero-rated, so there is no taxable supply to invoice.
            // §4.2 leaves nothing that could legally go on the document.
            throw new RejectedException(
                    "Sale %s has no VAT-taxable lines, so it cannot be put on a tax invoice."
                            .formatted(saleInvoiceNumber));
        }

        Instant issuedAt = Instant.now();
        String number =
                allocator.allocate(
                        tenantId,
                        sale.branchId(),
                        sale.branchCode(),
                        sale.terminalCode(),
                        InvoiceNumberAllocator.DocType.TAX_INVOICE,
                        issuedAt);

        UUID clientUuid = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO tax_invoices (
                    client_uuid, tenant_id, branch_id, branch_code, terminal_code, sale_id,
                    invoice_number, issued_at, supplied_at,
                    supplier_tin, supplier_registered_name, supplier_address,
                    purchaser_tin, purchaser_name, purchaser_address,
                    total_excl_vat_minor, vat_minor, total_incl_vat_minor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                clientUuid,
                tenantId,
                sale.branchId(),
                sale.branchCode(),
                sale.terminalCode(),
                sale.id(),
                number,
                java.sql.Timestamp.from(issuedAt),
                java.sql.Timestamp.from(sale.soldAt()),
                supplier.tin(),
                supplier.registeredName(),
                supplier.address(),
                purchaser == null ? null : purchaser.tin(),
                purchaser == null ? null : purchaser.name(),
                purchaser == null ? null : purchaser.address(),
                totals.exVatMinor(),
                totals.vatMinor(),
                totals.grossMinor());

        TaxInvoice issued =
                new TaxInvoice(
                        clientUuid,
                        number,
                        issuedAt,
                        sale.soldAt(),
                        saleInvoiceNumber,
                        supplier,
                        purchaser,
                        totals.exVatMinor(),
                        totals.vatMinor(),
                        totals.grossMinor(),
                        taxableLines(sale.id()));

        // Same transaction as the row above — §A's first rule. A tax invoice that exists on the
        // till but never reaches the cloud is a document the owner's records do not know about.
        outbox.enqueue(tenantId, "tax_invoice", clientUuid, outboxPayload(issued, sale));
        return issued;
    }

    /** What was already issued against a sale, so the till can reprint rather than re-issue. */
    @Transactional(readOnly = true)
    public List<TaxInvoice> findBySaleInvoiceNumber(String saleInvoiceNumber) {
        long tenantId = shop.soleTenantId();
        return findBySale(findSale(tenantId, saleInvoiceNumber).id());
    }

    // ------------------------------------------------------------------------------ internals

    private SaleRow findSale(long tenantId, String saleInvoiceNumber) {
        try {
            return jdbc.queryForObject(
                    """
                    SELECT s.id, s.branch_id, b.code AS branch_code, s.terminal_code, s.sold_at
                      FROM sales s
                      JOIN branches b ON b.id = s.branch_id
                     WHERE s.tenant_id = ? AND s.invoice_number = ?
                    """,
                    (rs, row) ->
                            new SaleRow(
                                    rs.getLong("id"),
                                    rs.getLong("branch_id"),
                                    rs.getString("branch_code"),
                                    rs.getString("terminal_code"),
                                    rs.getTimestamp("sold_at").toInstant()),
                    tenantId,
                    saleInvoiceNumber);
        } catch (EmptyResultDataAccessException e) {
            throw new RejectedException("No sale found for invoice " + saleInvoiceNumber);
        }
    }

    /**
     * The VAT-taxable subset of the basket, which for a mixed sale is smaller than the sale.
     *
     * <p>"Taxable" is read here as {@code tax_rate_bp > 0}. That conflates zero-rated supplies —
     * which <em>are</em> taxable and do belong on a tax invoice — with exempt ones, which do not.
     * The schema carries a rate and no supply classification, so the distinction is not available
     * to make. It costs nothing in a Sri Lankan retail shop, where zero-rated supply is essentially
     * export, and it would have to be fixed before this served an exporter. Recorded rather than
     * hidden.
     */
    private Totals taxableTotals(long saleId) {
        return jdbc.queryForObject(
                """
                SELECT coalesce(sum(line_total_minor - tax_minor), 0) AS ex_vat,
                       coalesce(sum(tax_minor), 0)                    AS vat,
                       coalesce(sum(line_total_minor), 0)             AS gross
                  FROM sale_items
                 WHERE sale_id = ? AND tax_rate_bp > 0
                """,
                (rs, row) ->
                        new Totals(rs.getLong("ex_vat"), rs.getLong("vat"), rs.getLong("gross")),
                saleId);
    }

    private List<Line> taxableLines(long saleId) {
        return jdbc.query(
                """
                SELECT si.line_no, p.name, si.qty,
                       si.unit_price_minor, si.tax_minor, si.line_total_minor, si.tax_rate_bp
                  FROM sale_items si
                  JOIN products p ON p.id = si.product_id
                 WHERE si.sale_id = ? AND si.tax_rate_bp > 0
                 ORDER BY si.line_no
                """,
                (rs, row) ->
                        new Line(
                                rs.getInt("line_no"),
                                rs.getString("name"),
                                rs.getInt("qty"),
                                rs.getLong("unit_price_minor"),
                                rs.getLong("line_total_minor") - rs.getLong("tax_minor"),
                                rs.getLong("tax_minor"),
                                rs.getInt("tax_rate_bp")),
                saleId);
    }

    private List<TaxInvoice> findBySale(long saleId) {
        return jdbc.query(
                """
                SELECT ti.*, s.invoice_number AS sale_invoice_number
                  FROM tax_invoices ti
                  JOIN sales s ON s.id = ti.sale_id
                 WHERE ti.sale_id = ?
                 ORDER BY ti.id
                """,
                (rs, row) ->
                        new TaxInvoice(
                                rs.getObject("client_uuid", UUID.class),
                                rs.getString("invoice_number"),
                                rs.getTimestamp("issued_at").toInstant(),
                                rs.getTimestamp("supplied_at").toInstant(),
                                rs.getString("sale_invoice_number"),
                                new TenantSettingsService.SupplierIdentity(
                                        rs.getString("supplier_tin"),
                                        rs.getString("supplier_registered_name"),
                                        rs.getString("supplier_address")),
                                purchaserOf(
                                        rs.getString("purchaser_tin"),
                                        rs.getString("purchaser_name"),
                                        rs.getString("purchaser_address")),
                                rs.getLong("total_excl_vat_minor"),
                                rs.getLong("vat_minor"),
                                rs.getLong("total_incl_vat_minor"),
                                taxableLines(saleId)),
                saleId);
    }

    /**
     * Null for a walk-in, and that is compliant.
     *
     * <p>Gazette §3.1 lists the purchaser's TIN, name and address among the invoice's particulars
     * and reads as though they are always required. Circular SEC/2026/E/03 §4.3 is the operative
     * wording: <i>"Where the purchaser is VAT-registered, the following must be stated"</i>. A
     * consumer has no TIN, and a till that demanded one would be unable to serve a queue.
     */
    private static Purchaser purchaserOf(String tin, String name, String address) {
        return tin == null ? null : new Purchaser(tin, name, address);
    }

    private static Map<String, Object> outboxPayload(TaxInvoice invoice, SaleRow sale) {
        return Map.ofEntries(
                Map.entry("clientUuid", invoice.clientUuid().toString()),
                Map.entry("branchCode", sale.branchCode()),
                Map.entry("terminalCode", sale.terminalCode()),
                Map.entry("invoiceNumber", invoice.invoiceNumber()),
                Map.entry("saleInvoiceNumber", invoice.saleInvoiceNumber()),
                Map.entry("issuedAt", invoice.issuedAt().toString()),
                Map.entry("suppliedAt", invoice.suppliedAt().toString()),
                Map.entry("supplierTin", invoice.supplier().tin()),
                Map.entry("purchaserTin", invoice.purchaser() == null ? "" : invoice.purchaser().tin()),
                Map.entry("totalExclVatMinor", invoice.totalExclVatMinor()),
                Map.entry("vatMinor", invoice.vatMinor()),
                Map.entry("totalInclVatMinor", invoice.totalInclVatMinor()));
    }

    // ---------------------------------------------------------------------------------- types

    private record SaleRow(
            long id, long branchId, String branchCode, String terminalCode, Instant soldAt) {}

    private record Totals(long exVatMinor, long vatMinor, long grossMinor) {}

    /** Gazette §3.1. All three together or none — see {@code ck_tax_invoices_purchaser_all_or_nothing}. */
    public record Purchaser(String tin, String name, String address) {}

    public record Line(
            int lineNo,
            String name,
            int qty,
            long unitPriceMinor,
            long exVatMinor,
            long taxMinor,
            int taxRateBp) {}

    public record TaxInvoice(
            UUID clientUuid,
            String invoiceNumber,
            Instant issuedAt,
            Instant suppliedAt,
            String saleInvoiceNumber,
            TenantSettingsService.SupplierIdentity supplier,
            Purchaser purchaser,
            long totalExclVatMinor,
            long vatMinor,
            long totalInclVatMinor,
            List<Line> lines) {}
}
