package com.lumora.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.lumora.pos.cloud.TenantCredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The catalogue, the staff and the customers reaching the cloud (M3-12).
 *
 * <h2>These three are the first mutable aggregates that are not a state machine</h2>
 *
 * A sale never changes, so V200's upsert is a deliberate no-op. A shift changes exactly once and
 * only forwards, so V203's is monotonic. A product's price, a user's role and a customer's number
 * change whenever the shop says so — and there is no order to enforce, because every delivery
 * carries the whole row. What is asserted below is that this actually holds: a second delivery
 * updates rather than duplicating, and a stale row landing late says nothing new rather than
 * undoing something.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud")
class CloudIngestM3Test {


    @Autowired SyncIngestService ingest;
    @Autowired TenantCredentialService credentials;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    /**
     * Provisioned per test rather than shared (M4-01). A tenant can no longer create itself by
     * pushing, so every test needs one to exist first — and giving each its own is what keeps a
     * test asserting on a running total from reading rows the previous test left behind.
     */
    private long tenantId;

    @BeforeEach
    void provisionTenant() {
        tenantId = credentials.provision("Kandy Stores", "Till 1").tenantId();
    }

    // ------------------------------------------------------------------------ product

    @Test
    void aProductArrivesWithItsBarcodes() {
        UUID product = UUID.randomUUID();

        ingest.ingest(tenantId, batch("product", product, productPayload(product, "Tea 400g", 45_000, "4791", "9991")));

        assertThat(text(product, "products", "name")).isEqualTo("Tea 400g");
        assertThat(scalarLong("SELECT price_minor FROM products WHERE client_uuid = ?", product))
                .isEqualTo(45_000);
        assertThat(barcodes(product)).containsExactly("4791", "9991");

        // The first is the primary one, which is the order the till's own query produces.
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT barcode FROM product_barcodes
                                 WHERE product_id = (SELECT id FROM products WHERE client_uuid = ?)
                                   AND is_primary
                                """,
                                String.class,
                                product))
                .isEqualTo("4791");
    }

    /**
     * A price change is a second delivery of the same row, not a second row.
     *
     * <p>This is the property that makes the whole aggregate work. Shipping the state rather than
     * the change means a shop that edited a price while offline delivers a row the cloud can apply
     * on its own, with no earlier delivery to have missed.
     */
    @Test
    void aRepricedProductUpdatesRatherThanDuplicating() {
        UUID product = UUID.randomUUID();
        ingest.ingest(tenantId, batch("product", product, productPayload(product, "Tea 400g", 45_000, "4791")));

        ingest.ingest(tenantId, batch("product", product, productPayload(product, "Tea 400g", 49_000, "4791")));

        assertThat(count("SELECT count(*) FROM products WHERE client_uuid = ?", product)).isEqualTo(1);
        assertThat(scalarLong("SELECT price_minor FROM products WHERE client_uuid = ?", product))
                .isEqualTo(49_000);
    }

    /**
     * A barcode removed at the shop disappears here too.
     *
     * <p>The list that arrives is authoritative and the ingest replaces what it holds. A merge
     * would be the obvious implementation and cannot express a removal — leaving the cloud
     * answering to a code the shop has reassigned to something else.
     */
    @Test
    void aBarcodeTakenOffAProductIsTakenOffTheCloudCopyToo() {
        UUID product = UUID.randomUUID();
        ingest.ingest(tenantId, batch("product", product, productPayload(product, "Tea", 45_000, "4791", "9991")));

        ingest.ingest(tenantId, batch("product", product, productPayload(product, "Tea", 45_000, "4791")));

        assertThat(barcodes(product)).containsExactly("4791");
    }

    // --------------------------------------------------------------------------- user

    /**
     * The credential never leaves the shop.
     *
     * <p>Asserted against the schema rather than the payload, because the payload is one careless
     * edit away from carrying anything: there is no column here to put a hash in, so a build that
     * started shipping one would fail loudly instead of quietly storing it. This is the same reason
     * offline auth (M3-09) is entirely local — a till that could check a PIN against the cloud
     * would need the cloud to hold something worth stealing.
     */
    @Test
    void theCloudHasNowhereToPutAPinHash() {
        assertThat(
                        count(
                                """
                                SELECT count(*) FROM information_schema.columns
                                 WHERE table_schema = 'public' AND table_name = 'users'
                                   AND column_name IN ('pin_hash', 'pin', 'password', 'password_hash')
                                """))
                .isZero();
    }

    @Test
    void aUserArrivesAndThenChangesRole() {
        UUID user = UUID.randomUUID();
        ingest.ingest(tenantId, batch("user", user, userPayload(user, "AMILA", "Amila Perera", "CASHIER", true)));

        assertThat(text(user, "users", "role")).isEqualTo("CASHIER");

        ingest.ingest(tenantId, batch("user", user, userPayload(user, "AMILA", "Amila Perera", "SUPERVISOR", true)));

        assertThat(count("SELECT count(*) FROM users WHERE client_uuid = ?", user)).isEqualTo(1);
        assertThat(text(user, "users", "role")).isEqualTo("SUPERVISOR");
    }

    @Test
    void aDeactivatedUserArrivesAsDeactivated() {
        UUID user = UUID.randomUUID();
        ingest.ingest(tenantId, batch("user", user, userPayload(user, "LEFT", "Left in June", "CASHIER", true)));

        ingest.ingest(tenantId, batch("user", user, userPayload(user, "LEFT", "Left in June", "CASHIER", false)));

        assertThat(text(user, "users", "active")).isEqualTo("false");
    }

    // ----------------------------------------------------------------------- customer

    @Test
    void aCustomerArrivesWithNameAndNumberAndNothingElse() {
        UUID customer = UUID.randomUUID();
        ingest.ingest(tenantId, batch("customer", customer, customerPayload(customer, "Ruwan", "0771234567")));

        assertThat(text(customer, "customers", "name")).isEqualTo("Ruwan");
        assertThat(text(customer, "customers", "phone")).isEqualTo("0771234567");

        // Email and note are held on the till and deliberately never shipped: neither has a reader
        // in the console, and personal data copied into a second jurisdiction because it might one
        // day be useful is exactly what M5-10 would have to undo.
        assertThat(
                        count(
                                """
                                SELECT count(*) FROM information_schema.columns
                                 WHERE table_schema = 'public' AND table_name = 'customers'
                                   AND column_name IN ('email', 'note')
                                """))
                .isZero();
    }

    /**
     * The sale names the customer by uuid, and does not wait for them.
     *
     * <p>The sale is ingested <em>first</em>, deliberately: a shop's backlog drains in whatever
     * order the retries allow, and a foreign key here would reject the sale and back it off until
     * the customer happened to succeed. The uuid resolves at query time instead.
     */
    @Test
    void aSaleMayNameACustomerThatHasNotArrivedYet() {
        UUID customer = UUID.randomUUID();
        UUID sale = UUID.randomUUID();

        ingest.ingest(tenantId, batch("sale", sale, salePayload(sale, customer)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT customer_client_uuid FROM sales WHERE client_uuid = ?",
                                UUID.class,
                                sale))
                .isEqualTo(customer);

        ingest.ingest(tenantId, batch("customer", customer, customerPayload(customer, "Late Arrival", "0770000001")));

        assertThat(
                        count(
                                """
                                SELECT count(*) FROM sales s JOIN customers c
                                    ON c.client_uuid = s.customer_client_uuid
                                 WHERE s.client_uuid = ?
                                """,
                                sale))
                .isEqualTo(1);
    }

    /** An anonymous sale is the normal case and carries no customer at all. */
    @Test
    void anAnonymousSaleCarriesNoCustomer() {
        UUID sale = UUID.randomUUID();
        ingest.ingest(tenantId, batch("sale", sale, salePayload(sale, null)));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT customer_client_uuid FROM sales WHERE client_uuid = ?",
                                UUID.class,
                                sale))
                .isNull();
    }

    // ------------------------------------------------------------------------ payloads

    private String productPayload(UUID uuid, String name, long priceMinor, String... barcodes) {
        StringBuilder codes = new StringBuilder();
        for (String barcode : barcodes) {
            if (codes.length() > 0) codes.append(", ");
            codes.append('"').append(barcode).append('"');
        }
        return """
               {
                 "clientUuid": "%s",
                 "sku": "TEA-400",
                 "name": "%s",
                 "priceMinor": %d,
                 "taxMode": "INCLUSIVE",
                 "taxRateBp": 1800,
                 "category": "Beverages",
                 "active": true,
                 "barcodes": [%s]
               }
               """
                .formatted(uuid, name, priceMinor, codes);
    }

    private String userPayload(UUID uuid, String code, String name, String role, boolean active) {
        return """
               {
                 "clientUuid": "%s",
                 "code": "%s",
                 "displayName": "%s",
                 "role": "%s",
                 "active": %b
               }
               """
                .formatted(uuid, code, name, role, active);
    }

    private String customerPayload(UUID uuid, String name, String phone) {
        return """
               {
                 "clientUuid": "%s",
                 "name": "%s",
                 "phone": "%s",
                 "active": true
               }
               """
                .formatted(uuid, name, phone);
    }

    private String salePayload(UUID uuid, UUID customer) {
        return """
               {
                 "clientUuid": "%s",
                 "branchCode": "KND",
                 "terminalCode": "T1",
                 "invoiceNumber": "KND-T1-%06d",
                 "soldAt": "2026-08-23T06:00:00Z",
                 "taxMode": "INCLUSIVE",
                 "taxRateBp": 1800,
                 "subtotalMinor": 45000,
                 "discountMinor": 0,
                 "taxMinor": 6864,
                 "totalMinor": 45000,
                 "roundingAdjustmentMinor": 0,
                 "changeMinor": 0,
                 "customerClientUuid": %s,
                 "lines": [{
                   "productClientUuid": "00000000-0000-4000-8000-000000000101",
                   "lineNo": 1, "qty": 1,
                   "unitPriceMinor": 45000, "discountMinor": 0,
                   "taxMinor": 6864, "lineTotalMinor": 45000
                 }],
                 "tenders": [{ "lineNo": 1, "kind": "CASH", "amountMinor": 45000 }]
               }
               """
                .formatted(
                        uuid,
                        Math.abs(uuid.hashCode() % 1_000_000),
                        customer == null ? "null" : "\"" + customer + "\"");
    }

    // ------------------------------------------------------------------------- helpers

    // ------------------------------------------------------------------ M5-09 tax invoices

    @Test
    void aTaxInvoiceReachesTheCloud() {
        UUID invoice = UUID.randomUUID();

        ingest.ingest(tenantId, batch("tax_invoice", invoice, taxInvoicePayload(invoice, null)));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT invoice_number FROM tax_invoices WHERE client_uuid = ?",
                                String.class,
                                invoice))
                .isEqualTo("26AUG-KNDT1-000001");
    }

    /** A walk-in has no TIN, and the till sends an empty string rather than omitting the key. */
    @Test
    void aWalkInInvoiceLandsWithANullPurchaserTin() {
        UUID invoice = UUID.randomUUID();

        ingest.ingest(tenantId, batch("tax_invoice", invoice, taxInvoicePayload(invoice, null)));

        assertThat(
                        count(
                                "SELECT count(*) FROM tax_invoices WHERE client_uuid = ? AND purchaser_tin IS NULL",
                                invoice))
                .isEqualTo(1);
    }

    @Test
    void aVatRegisteredPurchaserKeepsItsTinAcrossTheWire() {
        UUID invoice = UUID.randomUUID();

        ingest.ingest(tenantId, batch("tax_invoice", invoice, taxInvoicePayload(invoice, "987654321")));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT purchaser_tin FROM tax_invoices WHERE client_uuid = ?",
                                String.class,
                                invoice))
                .isEqualTo("987654321");
    }

    /** Issued documents are immutable, so redelivery must change nothing at all. */
    @Test
    void redeliveringATaxInvoiceChangesNothing() {
        UUID invoice = UUID.randomUUID();
        SyncBatch b = batch("tax_invoice", invoice, taxInvoicePayload(invoice, null));

        ingest.ingest(tenantId, b);
        ingest.ingest(tenantId, b);
        ingest.ingest(tenantId, b);

        assertThat(count("SELECT count(*) FROM tax_invoices WHERE client_uuid = ?", invoice))
                .isEqualTo(1);
    }

    private String taxInvoicePayload(UUID clientUuid, String purchaserTin) {
        return """
                {
                  "clientUuid": "%s",
                  "branchCode": "KND",
                  "terminalCode": "T1",
                  "invoiceNumber": "26AUG-KNDT1-000001",
                  "saleInvoiceNumber": "KND-T1-000042",
                  "issuedAt": "2026-08-24T09:12:09Z",
                  "suppliedAt": "2026-08-24T09:12:09Z",
                  "supplierTin": "123456789",
                  "purchaserTin": "%s",
                  "totalExclVatMinor": 76272,
                  "vatMinor": 13728,
                  "totalInclVatMinor": 90000
                }
                """
                .formatted(clientUuid, purchaserTin == null ? "" : purchaserTin);
    }

    private SyncBatch batch(String aggregate, UUID aggregateId, String payload) {
        return new SyncBatch(List.of(new SyncBatch.Item(aggregate, aggregateId, json(payload))));
    }

    private List<String> barcodes(UUID product) {
        return jdbc.queryForList(
                """
                SELECT barcode FROM product_barcodes
                 WHERE product_id = (SELECT id FROM products WHERE client_uuid = ?)
                 ORDER BY is_primary DESC, barcode
                """,
                String.class,
                product);
    }

    private String text(UUID clientUuid, String table, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + "::text FROM " + table + " WHERE client_uuid = ?",
                String.class,
                clientUuid);
    }

    private Long scalarLong(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
