package com.lumora.pos.customer;

import com.lumora.pos.outbox.OutboxWriter;
import com.lumora.pos.web.RejectedException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Named customers (M3-11).
 *
 * <h2>The phone number is the record</h2>
 *
 * A shop asks "number?" and types digits on the same keypad it rings up sales on. So the phone is
 * the lookup key, it is unique per shop, and it is {@link #normalisePhone normalised} to digits
 * before anything is done with it — because one person will be typed as {@code 077 123 4567},
 * {@code 0771234567} and {@code +94 77 123 4567} across three visits, and a column that keeps them
 * as typed is three rows for one customer with a unique index that never fires.
 *
 * <h2>Attaching a customer changes nothing about the money</h2>
 *
 * Nothing here is consulted by pricing, tax or totals, and that is a v1 decision worth being
 * explicit about: the moment a customer can change what is charged, a mis-tap at the till stops
 * being a wrong name on a receipt and becomes a pricing error, and the receipt and the report have
 * to agree about which customer before either can be trusted. Loyalty and credit are movement
 * tables hanging off this one, and they arrive with the question of what they are allowed to move.
 *
 * <h2>The name and the number reach the cloud; nothing else does</h2>
 *
 * The outbox payload (M3-12) carries the name, the phone and whether they are active. It does not
 * carry the email or the note. Neither has a reader in the console, PDPA (M5-10) is coming, and a
 * column shipped only because something might one day read it is personal data copied into a second
 * jurisdiction for a reason nobody wrote down. Adding either later is one line; un-copying it is
 * not.
 */
@Service
public class CustomerService {

    private final JdbcTemplate jdbc;

    private final OutboxWriter outbox;

    public CustomerService(JdbcTemplate jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /** A customer as every screen shows them. */
    public record CustomerRow(
            long id,
            UUID clientUuid,
            String name,
            String phone,
            String email,
            String note,
            boolean active,
            int saleCount,
            long spentMinor,
            Instant lastSeenAt) {}

    /** One line of somebody's purchase history. */
    public record CustomerSale(
            long saleId, String invoiceNumber, Instant soldAt, long totalMinor, int itemCount) {}

    // ------------------------------------------------------------------------- reading

    /**
     * Search by name or by a leading run of digits.
     *
     * <p>One box, two searches, decided by what was typed: digits are a phone prefix, anything else
     * is a name substring. Two fields would be more explicit and would also be two fields, and the
     * cashier holding a queue has one thing in their head — either the number or the name.
     */
    @Transactional(readOnly = true)
    public List<CustomerRow> search(long tenantId, String query, boolean includeInactive, int limit) {
        String typed = query == null ? "" : query.trim();

        if (typed.isEmpty()) {
            return jdbc.query(
                    listSql("AND (? OR c.active) ORDER BY c.name LIMIT ?"),
                    CustomerService::readRow,
                    tenantId,
                    includeInactive,
                    limit);
        }

        String digits = normaliseOrNull(typed);
        if (digits != null) {
            return jdbc.query(
                    listSql("AND (? OR c.active) AND c.phone LIKE ? ORDER BY c.phone LIMIT ?"),
                    CustomerService::readRow,
                    tenantId,
                    includeInactive,
                    digits + "%",
                    limit);
        }

        return jdbc.query(
                listSql("AND (? OR c.active) AND lower(c.name) LIKE ? ORDER BY c.name LIMIT ?"),
                CustomerService::readRow,
                tenantId,
                includeInactive,
                "%" + typed.toLowerCase(Locale.ROOT) + "%",
                limit);
    }

    @Transactional(readOnly = true)
    public CustomerRow byId(long tenantId, long customerId) {
        try {
            return jdbc.queryForObject(
                    listSql("AND c.id = ?"), CustomerService::readRow, tenantId, customerId);
        } catch (EmptyResultDataAccessException e) {
            throw new RejectedException("No such customer");
        }
    }

    /** Resolves the uuid a till sends with a sale. Refuses an inactive one rather than accepting it. */
    @Transactional(readOnly = true)
    public long idForClientUuid(long tenantId, UUID clientUuid) {
        List<Boolean> found =
                jdbc.query(
                        "SELECT active FROM customers WHERE tenant_id = ? AND client_uuid = ?",
                        (rs, row) -> rs.getBoolean("active"),
                        tenantId,
                        clientUuid);
        if (found.isEmpty()) {
            throw new RejectedException("That customer is not on file at this shop");
        }
        if (!found.get(0)) {
            throw new RejectedException("That customer is no longer active");
        }
        return jdbc.queryForObject(
                "SELECT id FROM customers WHERE tenant_id = ? AND client_uuid = ?",
                Long.class,
                tenantId,
                clientUuid);
    }

    /**
     * What somebody has bought, newest first.
     *
     * <p>A query over {@code sales}, not a list stored on the customer. A stored list would be a
     * second copy of a fact the sales table already holds, and §A's objection to a stored balance is
     * an objection to any stored copy — one write path forgets, and then two screens disagree about
     * a customer standing at the counter.
     */
    @Transactional(readOnly = true)
    public List<CustomerSale> history(long tenantId, long customerId, int limit) {
        byId(tenantId, customerId); // 404s before returning an empty list that looks like "nothing"
        return jdbc.query(
                """
                SELECT s.id, s.invoice_number, s.sold_at, s.total_minor,
                       (SELECT COALESCE(sum(i.qty), 0) FROM sale_items i WHERE i.sale_id = s.id)
                           AS item_count
                  FROM sales s
                 WHERE s.tenant_id = ? AND s.customer_id = ?
                 ORDER BY s.sold_at DESC
                 LIMIT ?
                """,
                (rs, row) ->
                        new CustomerSale(
                                rs.getLong("id"),
                                rs.getString("invoice_number"),
                                rs.getObject("sold_at", OffsetDateTime.class).toInstant(),
                                rs.getLong("total_minor"),
                                rs.getInt("item_count")),
                tenantId,
                customerId,
                limit);
    }

    // ------------------------------------------------------------------------- writing

    /**
     * Creates a customer.
     *
     * <p>{@code clientUuid} comes from the caller, as it does for every aggregate here: creating the
     * same customer twice — a retried request, a double-pressed key — is one customer.
     */
    @Transactional
    public CustomerRow create(
            long tenantId,
            UUID clientUuid,
            String name,
            String phone,
            String email,
            String note,
            long createdBy) {
        String digits = requireSaneOptionalPhone(phone);
        requireSaneName(name);

        try {
            Long id =
                    jdbc.queryForObject(
                            """
                            INSERT INTO customers (client_uuid, tenant_id, name, phone, email, note, created_by)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (client_uuid) DO UPDATE SET client_uuid = excluded.client_uuid
                            RETURNING id
                            """,
                            Long.class,
                            clientUuid,
                            tenantId,
                            name.trim(),
                            digits,
                            blankToNull(email),
                            blankToNull(note),
                            createdBy);
            CustomerRow created = byId(tenantId, id);
            enqueue(tenantId, created);
            return created;
        } catch (DuplicateKeyException clash) {
            // The only other unique index is the phone. Naming it beats surfacing a constraint
            // name, and the shop's next move is to search for the number they just typed.
            throw new RejectedException(
                    "A customer with the number " + formatPhone(digits) + " is already on file");
        }
    }

    @Transactional
    public CustomerRow update(
            long tenantId, long customerId, String name, String phone, String email, String note) {
        String digits = requireSaneOptionalPhone(phone);
        requireSaneName(name);
        byId(tenantId, customerId);

        try {
            jdbc.update(
                    """
                    UPDATE customers SET name = ?, phone = ?, email = ?, note = ?
                     WHERE tenant_id = ? AND id = ?
                    """,
                    name.trim(),
                    digits,
                    blankToNull(email),
                    blankToNull(note),
                    tenantId,
                    customerId);
        } catch (DuplicateKeyException clash) {
            throw new RejectedException(
                    "A customer with the number " + formatPhone(digits) + " is already on file");
        }
        CustomerRow updated = byId(tenantId, customerId);
        enqueue(tenantId, updated);
        return updated;
    }

    /**
     * Deactivates or reinstates. There is no delete.
     *
     * <p>Sales reference this row. A delete would either fail against the FK or, worse, be made to
     * succeed by loosening it — and an invoice that used to name somebody and now names nobody is a
     * worse record than one naming a customer who has left. Erasure under PDPA (M5-10) is a
     * deliberate act that has to decide what happens to the invoices; a delete button would make
     * that decision by accident.
     */
    @Transactional
    public CustomerRow setActive(long tenantId, long customerId, boolean active) {
        byId(tenantId, customerId);
        jdbc.update(
                "UPDATE customers SET active = ? WHERE tenant_id = ? AND id = ?",
                active,
                tenantId,
                customerId);
        CustomerRow updated = byId(tenantId, customerId);
        enqueue(tenantId, updated);
        return updated;
    }

    // ------------------------------------------------------------------------- helpers

    /**
     * The one SELECT every read here shares, with the caller's tail appended.
     *
     * <p>The counts are subqueries rather than a GROUP BY so that a customer who has bought nothing
     * is a row with zeros instead of no row at all — the case that matters, because somebody just
     * created is exactly who the shopkeeper is looking at.
     */
    private static String listSql(String tail) {
        return """
               SELECT c.id, c.client_uuid, c.name, c.phone, c.email, c.note, c.active,
                      (SELECT count(*) FROM sales s WHERE s.customer_id = c.id)        AS sale_count,
                      (SELECT COALESCE(sum(s.total_minor), 0) FROM sales s
                        WHERE s.customer_id = c.id)                                    AS spent_minor,
                      (SELECT max(s.sold_at) FROM sales s WHERE s.customer_id = c.id)  AS last_seen_at
                 FROM customers c
                WHERE c.tenant_id = ?
               """
                + tail;
    }

    private static CustomerRow readRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        OffsetDateTime lastSeen = rs.getObject("last_seen_at", OffsetDateTime.class);
        return new CustomerRow(
                rs.getLong("id"),
                rs.getObject("client_uuid", UUID.class),
                rs.getString("name"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getString("note"),
                rs.getBoolean("active"),
                rs.getInt("sale_count"),
                rs.getLong("spent_minor"),
                lastSeen == null ? null : lastSeen.toInstant());
    }

    /**
     * Digits, and nothing else.
     *
     * <p>A leading {@code +94} is dropped along with everything else that is not a digit, which
     * makes {@code +94 77 123 4567} and {@code 0771234567} two different strings — 94771234567 and
     * 0771234567. That is a real limitation and it is the honest one: turning a country code into a
     * leading zero is a rule about Sri Lankan numbering that would be wrong for the first shop that
     * has an overseas customer, and a lookup that quietly rewrites what was typed is worse than one
     * that finds nothing and lets the shopkeeper try again.
     */
    public static String normalisePhone(String phone) {
        if (phone == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder(phone.length());
        for (char c : phone.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    /** Normalised digits, or null when nothing was typed. */
    private static String normaliseOrNull(String phone) {
        String digits = normalisePhone(phone);
        return digits == null || digits.isEmpty() ? null : digits;
    }

    private static String requireSaneOptionalPhone(String phone) {
        String digits = normaliseOrNull(phone);
        if (digits == null) {
            return null;
        }
        if (digits.length() < 6 || digits.length() > 15) {
            throw new RejectedException(
                    "A phone number needs 6 to 15 digits — " + phone + " has " + digits.length());
        }
        return digits;
    }

    private static void requireSaneName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new RejectedException("A customer needs a name");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** Only ever used inside a message, so a shopkeeper reads back what they typed. */
    private static String formatPhone(String digits) {
        return digits == null ? "(none)" : digits;
    }

    // ------------------------------------------------------------------------- sync (M3-12)

    /**
     * Puts the customer on the outbox, in the caller's transaction.
     *
     * <p>The whole row each time rather than a diff — shipping state instead of change makes
     * redelivery a no-op and arrival order irrelevant, which is what an offline shop's backlog
     * needs. Email and note are deliberately absent; see the class comment.
     */
    private void enqueue(long tenantId, CustomerRow customer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientUuid", customer.clientUuid().toString());
        payload.put("name", customer.name());
        payload.put("phone", customer.phone());
        payload.put("active", customer.active());
        outbox.enqueue(tenantId, "customer", customer.clientUuid(), payload);
    }
}
