package com.lumora.pos.customer;

import com.lumora.pos.outbox.OutboxWriter;
import com.lumora.pos.web.RejectedException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two things a person may ask a shop to do with their data (M5-10).
 *
 * <h2>PDPA No. 9 of 2022, and what it actually requires of a grocery</h2>
 *
 * Two rights reach this system. A person may ask for a copy of what is held about them, and may ask
 * for it to be erased. Penalties run to Rs. 10M per instance, which is a number that ends most
 * shops — so this is not a checkbox, and the thing that has to be right is what it refuses to do as
 * much as what it does.
 *
 * <h2>Erasure is anonymisation, and that is the answer rather than a shortcut</h2>
 *
 * A shop must keep its financial records. Deleting the customer row would fail against the foreign
 * key from {@code sales}, and making it succeed by loosening that key would rewrite the shop's own
 * trading history to make a person go away — the totals in a Z-report already printed would stop
 * reconciling. So the personal data is destroyed and the transaction is not: name, phone, email,
 * note, TIN and address are overwritten, and the row keeps its id and its {@code client_uuid} so
 * that every sale still points at the same anonymous party it always pointed at.
 *
 * <h2>What it will not erase, and why somebody has to be told</h2>
 *
 * An issued <b>tax invoice</b> snapshots the purchaser's name, TIN and address at the moment it was
 * printed, because that is what the document must show (Gazette 2481/22). The purchaser filed it
 * and claimed input credit against it; a shop does not get to unilaterally revoke a statutory
 * document somebody else is relying on. Those snapshots stay, the erasure result says how many
 * there are, and {@code docs/pdpa.md} is what a shopkeeper reads to whoever asked.
 *
 * <h2>An erasure that stops at the shop PC is not an erasure</h2>
 *
 * The cloud holds a copy of the name and the phone. The customer aggregate is sent whole rather
 * than as a diff, so re-enqueueing the blanked row overwrites what is up there through exactly the
 * path that put it there. It goes into the same transaction as the erasure itself — a shop cannot
 * end up having told the person yes while the cloud still holds their number.
 */
@Service
@Profile("desktop")
public class CustomerPrivacyService {

    /**
     * What replaces the name.
     *
     * <p>The column is {@code NOT NULL} with a non-empty check, so something has to go here, and
     * every screen that lists customers will show it. A marker that reads as a state rather than as
     * a person is the point: "Erased customer" in a list tells a shopkeeper what happened, where an
     * empty-looking row reads as corruption.
     */
    public static final String ERASED_NAME = "Erased customer";

    private final JdbcTemplate jdbc;
    private final CustomerService customers;
    private final OutboxWriter outbox;

    public CustomerPrivacyService(
            JdbcTemplate jdbc, CustomerService customers, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.customers = customers;
        this.outbox = outbox;
    }

    // ------------------------------------------------------------------------- the export

    /** Everything the shop holds about one person, in the order somebody would read it. */
    public record DataExport(
            Instant exportedAt,
            CustomerService.CustomerRow customer,
            String tin,
            String address,
            Instant createdAt,
            List<ExportedSale> sales,
            List<ExportedRefund> refunds,
            List<ExportedTaxInvoice> taxInvoices) {}

    public record ExportedSale(
            String invoiceNumber, Instant soldAt, long totalMinor, List<ExportedLine> lines) {}

    public record ExportedLine(
            String product, int qty, long unitPriceMinor, long discountMinor, long lineTotalMinor) {}

    public record ExportedRefund(
            String creditNoteNumber, String againstInvoice, Instant refundedAt, long totalMinor) {}

    public record ExportedTaxInvoice(
            String number, Instant issuedAt, String purchaserName, String purchaserTin, String purchaserAddress) {}

    /**
     * Assembles the copy a person is entitled to ask for.
     *
     * <p>Line items are included rather than summarised. "You spent Rs. 4,200 across nine visits" is
     * a summary of the data; the right is to the data, and what a shop actually holds is that this
     * person bought these things on these days.
     *
     * <p>An erased customer can still be exported, and the export will be almost empty. That is the
     * correct answer to "what do you hold about me" after an erasure, and refusing the request
     * would leave the person unable to check that the shop did what it said.
     */
    @Transactional(readOnly = true)
    public DataExport export(long tenantId, long customerId) {
        CustomerService.CustomerRow customer = customers.byId(tenantId, customerId);

        Map<String, Object> extra =
                jdbc.queryForMap(
                        "SELECT tin, address, created_at FROM customers WHERE tenant_id = ? AND id = ?",
                        tenantId,
                        customerId);

        List<ExportedSale> sales =
                jdbc.query(
                        """
                        SELECT s.id, s.invoice_number, s.sold_at, s.total_minor
                          FROM sales s
                         WHERE s.tenant_id = ? AND s.customer_id = ?
                         ORDER BY s.sold_at
                        """,
                        (rs, row) ->
                                new ExportedSale(
                                        rs.getString("invoice_number"),
                                        instant(rs.getObject("sold_at", OffsetDateTime.class)),
                                        rs.getLong("total_minor"),
                                        linesOf(rs.getLong("id"))),
                        tenantId,
                        customerId);

        List<ExportedRefund> refunds =
                jdbc.query(
                        """
                        SELECT r.credit_note_number, r.refunded_at, r.total_minor, s.invoice_number
                          FROM refunds r
                          JOIN sales s ON s.id = r.sale_id
                         WHERE r.tenant_id = ? AND s.customer_id = ?
                         ORDER BY r.refunded_at
                        """,
                        (rs, row) ->
                                new ExportedRefund(
                                        rs.getString("credit_note_number"),
                                        rs.getString("invoice_number"),
                                        instant(rs.getObject("refunded_at", OffsetDateTime.class)),
                                        rs.getLong("total_minor")),
                        tenantId,
                        customerId);

        List<ExportedTaxInvoice> taxInvoices = taxInvoicesFor(tenantId, customerId);

        return new DataExport(
                Instant.now(),
                customer,
                (String) extra.get("tin"),
                (String) extra.get("address"),
                createdAt(extra.get("created_at")),
                sales,
                refunds,
                taxInvoices);
    }

    // ------------------------------------------------------------------------- the erasure

    /**
     * @param taxInvoicesRetained how many statutory documents still name this person. Returned
     *     rather than logged, because the shopkeeper has to be able to say the number out loud to
     *     whoever asked — and for almost every grocery customer it is zero, which is the answer
     *     that ends the conversation.
     */
    public record Erased(long customerId, Instant erasedAt, int taxInvoicesRetained) {}

    /**
     * Destroys the personal data on one customer, permanently.
     *
     * <p>Not idempotent by design. A second erasure is refused rather than quietly succeeding,
     * because the two requests mean different things — the first is an instruction and the second
     * is somebody checking, and answering the second with "done" hides that it was already done on
     * a different date by a different person.
     */
    @Transactional
    public Erased erase(long tenantId, long customerId, long erasedBy) {
        CustomerService.CustomerRow before = customers.byId(tenantId, customerId);
        if (before.erasedAt() != null) {
            throw new RejectedException(
                    "This customer's data was already erased on " + before.erasedAt());
        }

        int retained = taxInvoicesFor(tenantId, customerId).size();
        Instant now = Instant.now();

        // Overwritten in place rather than set to NULL where the column allows it. The distinction
        // matters to anybody reading the row later: NULL is "never given", and this is "given and
        // then destroyed", which is what erased_at records.
        jdbc.update(
                """
                UPDATE customers
                   SET name = ?, phone = NULL, email = NULL, note = NULL,
                       tin = NULL, address = NULL,
                       active = false,
                       erased_at = ?, erased_by = ?, updated_at = now()
                 WHERE tenant_id = ? AND id = ?
                """,
                ERASED_NAME,
                java.sql.Timestamp.from(now),
                erasedBy,
                tenantId,
                customerId);

        // The same path that put the name in the cloud, carrying the blank. In this transaction:
        // a shop must not be able to tell somebody yes while their number is still up there.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientUuid", before.clientUuid().toString());
        payload.put("name", ERASED_NAME);
        payload.put("phone", null);
        payload.put("active", false);
        payload.put("erasedAt", now.toString());
        outbox.enqueue(tenantId, "customer", before.clientUuid(), payload);

        return new Erased(customerId, now, retained);
    }

    // ------------------------------------------------------------------------- helpers

    private List<ExportedLine> linesOf(long saleId) {
        return jdbc.query(
                """
                SELECT p.name, i.qty, i.unit_price_minor, i.discount_minor, i.line_total_minor
                  FROM sale_items i
                  JOIN products p ON p.id = i.product_id
                 WHERE i.sale_id = ?
                 ORDER BY i.line_no
                """,
                (rs, row) ->
                        new ExportedLine(
                                rs.getString("name"),
                                rs.getInt("qty"),
                                rs.getLong("unit_price_minor"),
                                rs.getLong("discount_minor"),
                                rs.getLong("line_total_minor")),
                saleId);
    }

    /**
     * Every tax invoice that names this person, by whichever route it names them.
     *
     * <p>Two routes, and missing either would understate the number a shopkeeper has to quote: the
     * document may be linked through the sale it was issued against, and it separately carries its
     * own snapshot of the purchaser's details, which is what actually survives an erasure.
     */
    private List<ExportedTaxInvoice> taxInvoicesFor(long tenantId, long customerId) {
        return jdbc.query(
                """
                SELECT t.invoice_number, t.issued_at,
                       t.purchaser_name, t.purchaser_tin, t.purchaser_address
                  FROM tax_invoices t
                  JOIN sales s ON s.id = t.sale_id
                 WHERE t.tenant_id = ? AND s.customer_id = ?
                 ORDER BY t.issued_at
                """,
                (rs, row) ->
                        new ExportedTaxInvoice(
                                rs.getString("invoice_number"),
                                instant(rs.getObject("issued_at", OffsetDateTime.class)),
                                rs.getString("purchaser_name"),
                                rs.getString("purchaser_tin"),
                                rs.getString("purchaser_address")),
                tenantId,
                customerId);
    }

    /**
     * The one timestamp here that does not arrive as an OffsetDateTime.
     *
     * queryForMap hands back whatever the driver produced rather than a type we asked for, and for
     * a timestamptz that is a java.sql.Timestamp. The row mappers elsewhere in this class ask for
     * OffsetDateTime explicitly and get it; this one cannot, so it converts what it is given.
     */
    private static Instant createdAt(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        return null;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

}
