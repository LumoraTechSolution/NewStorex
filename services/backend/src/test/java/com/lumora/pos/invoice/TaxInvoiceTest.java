package com.lumora.pos.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.sale.CreateSaleRequest;
import com.lumora.pos.sale.SaleResponse;
import com.lumora.pos.sale.SaleService;
import com.lumora.pos.settings.TenantSettingsService;
import com.lumora.pos.shift.DenominationCount;
import com.lumora.pos.shift.OpenShiftRequest;
import com.lumora.pos.shift.ShiftService;
import com.lumora.pos.testfixtures.ShopFixture;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The IRD tax invoice (M5-09).
 *
 * <p>Every assertion here cites the clause of Gazette 2481/22 or Circular SEC/2026/E/03 it comes
 * from. That is not decoration: the next person to read this file will be holding the gazette, and
 * a test that says only "invoice has a TIN" cannot be checked against it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class TaxInvoiceTest {

    private static final TenantSettingsService.SupplierIdentity SUPPLIER =
            new TenantSettingsService.SupplierIdentity(
                    "123456789", "StoreX Retail (Pvt) Ltd", "No. 148/3B Peradeniya Road, Kandy");

    @Autowired TaxInvoiceService taxInvoices;
    @Autowired SaleService sales;
    @Autowired ShiftService shifts;
    @Autowired TenantSettingsService settings;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    /**
     * A desktop database holds exactly one tenant, so {@code ShopFixture.seed()} hands every test
     * in this class the same one — and with it whatever supplier details the previous test set.
     * The two tests that assert an <em>unconfigured</em> shop would then pass or fail on run
     * order. Cleared here so each test starts from "not configured" and says so explicitly when
     * it wants otherwise.
     */
    @BeforeEach
    void forgetAnyVatRegistrationDetails() {
        jdbc.update(
                """
                UPDATE tenant_settings
                   SET supplier_tin = NULL, supplier_registered_name = NULL, supplier_address = NULL
                """);
    }

    // ------------------------------------------------------------ §4.1(a) the serial number

    /**
     * Gazette §4.1(a): {@code YYMMM-QQQQ-XXXXX}. The month is the month of <em>issue</em>, and the
     * QQQQ code carries branch and terminal — which is what keeps §A's per-terminal blocks intact
     * under the new format.
     */
    @Test
    void theSerialNumberFollowsTheGazetteFormat() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);
        String saleNumber = ringUpTaxable(shop);

        TaxInvoiceService.TaxInvoice invoice = taxInvoices.issue(saleNumber, null);

        ZonedDateTime issued = invoice.issuedAt().atZone(ZoneId.systemDefault());
        String expectedPrefix =
                "%02d%s".formatted(
                        issued.getYear() % 100,
                        issued.getMonth()
                                .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                                .toUpperCase(java.util.Locale.ROOT));

        assertThat(invoice.invoiceNumber()).startsWith(expectedPrefix + "-");
        assertThat(invoice.invoiceNumber()).contains("-" + shop.branchCode().toUpperCase() + "T1-");
    }

    /** §4.1(a)(v): no spaces, at most forty characters. */
    @Test
    void theSerialNumberHasNoSpacesAndFitsFortyCharacters() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);

        String number = taxInvoices.issue(ringUpTaxable(shop), null).invoiceNumber();

        assertThat(number).doesNotContain(" ").hasSizeLessThanOrEqualTo(40);
    }

    /** §4.1(a)(iv): "shall consist solely of numerical characters". */
    @Test
    void theSequencePartIsDigitsOnly() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);

        String number = taxInvoices.issue(ringUpTaxable(shop), null).invoiceNumber();
        String sequence = number.substring(number.lastIndexOf('-') + 1);

        assertThat(sequence).matches("^[0-9]+$");
    }

    /**
     * A tax invoice must not consume a receipt number. Same rule V108 applies to credit notes, and
     * for the same reason: a gap in a sequence has to mean something is missing.
     */
    @Test
    void issuingOneDoesNotAdvanceTheReceiptSequence() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);
        ringUpTaxable(shop);

        long receiptSeqBefore = nextSeq(shop, "INVOICE");
        taxInvoices.issue(ringUpTaxable(shop), null);
        long receiptSeqAfter = nextSeq(shop, "INVOICE");

        // One sale was rung up between the two reads, so the receipt sequence moves by exactly
        // one — the tax invoice contributes nothing to it.
        assertThat(receiptSeqAfter - receiptSeqBefore).isEqualTo(1);
        assertThat(nextSeq(shop, "TAX_INVOICE")).isGreaterThan(1);
    }

    @Test
    void twoInvoicesOnOneTerminalTakeConsecutiveNumbers() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);

        String first = taxInvoices.issue(ringUpTaxable(shop), null).invoiceNumber();
        String second = taxInvoices.issue(ringUpTaxable(shop), null).invoiceNumber();

        long firstSeq = Long.parseLong(first.substring(first.lastIndexOf('-') + 1));
        long secondSeq = Long.parseLong(second.substring(second.lastIndexOf('-') + 1));
        assertThat(secondSeq).isEqualTo(firstSeq + 1);
    }

    // ------------------------------------------------------------------ §2 supplier details

    @Test
    void theSupplierTinNameAndAddressAreStamped() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);

        TaxInvoiceService.TaxInvoice invoice = taxInvoices.issue(ringUpTaxable(shop), null);

        assertThat(invoice.supplier().tin()).isEqualTo("123456789");
        assertThat(invoice.supplier().registeredName()).isEqualTo("StoreX Retail (Pvt) Ltd");
        assertThat(invoice.supplier().address()).isEqualTo("No. 148/3B Peradeniya Road, Kandy");
    }

    /**
     * An unconfigured shop cannot produce a legal document. Refusing is the whole point: an invoice
     * carrying a guessed TIN is filed by the purchaser and claimed against.
     */
    @Test
    void aShopWithNoVatRegistrationDetailsCannotIssueOne() {
        ShopFixture.Shop shop = openShop();
        String saleNumber = ringUpTaxable(shop);

        assertThatThrownBy(() -> taxInvoices.issue(saleNumber, null))
                .hasMessageContaining("no VAT registration details");
    }

    /** Two out of three is not a partial success — all three print on the face of the document. */
    @Test
    void aHalfConfiguredShopIsTreatedAsUnconfigured() {
        ShopFixture.Shop shop = openShop();
        settings.setSupplierIdentity(
                shop.tenantId(),
                new TenantSettingsService.SupplierIdentity("123456789", "StoreX Retail (Pvt) Ltd", null));
        String saleNumber = ringUpTaxable(shop);

        assertThatThrownBy(() -> taxInvoices.issue(saleNumber, null))
                .hasMessageContaining("no VAT registration details");
    }

    // ------------------------------------------------------------------ §3 purchaser details

    /**
     * The single most important thing the secondary sources had wrong.
     *
     * <p>Gazette §3.1 reads as though purchaser particulars are always required. Circular §4.3 is
     * the operative wording: they are required <i>"where the purchaser is VAT-registered"</i>. A
     * consumer has no TIN, and a till that demanded one could not serve a queue.
     */
    @Test
    void aWalkInNeedsNoPurchaserDetails() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);

        TaxInvoiceService.TaxInvoice invoice = taxInvoices.issue(ringUpTaxable(shop), null);

        assertThat(invoice.purchaser()).isNull();
        assertThat(invoice.invoiceNumber()).isNotBlank();
    }

    @Test
    void aVatRegisteredPurchaserIsRecordedInFull() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);
        TaxInvoiceService.Purchaser purchaser =
                new TaxInvoiceService.Purchaser("987654321", "Ceylon Traders (Pvt) Ltd", "45 Main St, Kandy");

        TaxInvoiceService.TaxInvoice invoice = taxInvoices.issue(ringUpTaxable(shop), purchaser);

        assertThat(invoice.purchaser()).isEqualTo(purchaser);
    }

    /** The database refuses a TIN with no name — half a purchaser block is not a purchaser. */
    @Test
    void aPurchaserTinWithoutANameIsRefused() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);
        String saleNumber = ringUpTaxable(shop);

        assertThatThrownBy(
                        () ->
                                taxInvoices.issue(
                                        saleNumber,
                                        new TaxInvoiceService.Purchaser("987654321", null, null)))
                .isInstanceOf(Exception.class);
    }

    // ------------------------------------------------- §4.2 / Circular §4.8 taxable supplies only

    /**
     * The rule that forced the whole design: a tax invoice carries only supplies subject to VAT.
     *
     * <p>The fixture's basket is deliberately mixed — tea at 18% and bread at 0%. The invoice must
     * show the tea alone, which means its total is smaller than the sale's, and that is correct
     * rather than a rounding bug.
     */
    @Test
    void exemptLinesAreLeftOffTheTaxInvoice() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);
        String saleNumber = ringUpMixed(shop);

        TaxInvoiceService.TaxInvoice invoice = taxInvoices.issue(saleNumber, null);

        assertThat(invoice.lines()).hasSize(1);
        assertThat(invoice.lines().get(0).name()).isEqualTo("Tea 400g");
        assertThat(invoice.lines()).allSatisfy(line -> assertThat(line.taxRateBp()).isGreaterThan(0));

        // 90,000 of tea, not the 140,000 the basket came to.
        assertThat(invoice.totalInclVatMinor()).isEqualTo(90_000L);
    }

    @Test
    void aBasketOfNothingButExemptGoodsCannotBeInvoiced() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);
        String saleNumber = ringUpExemptOnly(shop);

        assertThatThrownBy(() -> taxInvoices.issue(saleNumber, null))
                .hasMessageContaining("no VAT-taxable lines");
    }

    // ------------------------------------------------------------------ §4.7 the three figures

    @Test
    void netPlusVatEqualsTheTotal() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);

        TaxInvoiceService.TaxInvoice invoice = taxInvoices.issue(ringUpTaxable(shop), null);

        assertThat(invoice.totalExclVatMinor() + invoice.vatMinor())
                .isEqualTo(invoice.totalInclVatMinor());
    }

    // ------------------------------------------------------------------ §4.1(b),(d) the two dates

    @Test
    void theDateOfSupplyIsTheSaleNotTheIssueTime() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);
        String saleNumber = ringUpTaxable(shop);

        Instant soldAt =
                jdbc.queryForObject(
                        "SELECT sold_at FROM sales WHERE invoice_number = ?",
                        (rs, row) -> rs.getTimestamp(1).toInstant(),
                        saleNumber);

        TaxInvoiceService.TaxInvoice invoice = taxInvoices.issue(saleNumber, null);

        assertThat(invoice.suppliedAt()).isEqualTo(soldAt);
        assertThat(invoice.issuedAt()).isAfterOrEqualTo(soldAt);
    }

    // ------------------------------------------------------------------ issuing rules

    /** Pressing the key twice must reprint, never create a second document for one supply. */
    @Test
    void issuingTwiceReturnsTheSameInvoice() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);
        String saleNumber = ringUpTaxable(shop);

        TaxInvoiceService.TaxInvoice first = taxInvoices.issue(saleNumber, null);
        TaxInvoiceService.TaxInvoice second = taxInvoices.issue(saleNumber, null);

        assertThat(second.invoiceNumber()).isEqualTo(first.invoiceNumber());
        assertThat(second.clientUuid()).isEqualTo(first.clientUuid());
        assertThat(count("SELECT count(*) FROM tax_invoices WHERE invoice_number = ?", first.invoiceNumber()))
                .isEqualTo(1);
    }

    /** Same construction M2-06 used for refunds: the only way in starts from a real receipt. */
    @Test
    void anUnknownSaleGetsNoTaxInvoice() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);

        assertThatThrownBy(() -> taxInvoices.issue("KND-T9-999999", null))
                .hasMessageContaining("No sale found for invoice");
    }

    /** §A's first rule — the sync record is written in the same transaction as the document. */
    @Test
    void theInvoiceReachesTheOutboxInTheSameTransaction() {
        ShopFixture.Shop shop = openShop();
        setSupplier(shop);

        TaxInvoiceService.TaxInvoice invoice = taxInvoices.issue(ringUpTaxable(shop), null);

        assertThat(
                        count(
                                "SELECT count(*) FROM outbox WHERE aggregate = 'tax_invoice' AND aggregate_id = ?",
                                invoice.clientUuid()))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------------- helpers

    /** Seeded shop with a shift open — SaleService refuses to trade without one (M2-01). */
    private ShopFixture.Shop openShop() {
        ShopFixture.Shop shop = fixtures.seed();
        shifts.open(
                new OpenShiftRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        ShopFixture.MANAGER_CODE,
                        ShopFixture.MANAGER_PIN,
                        null,
                        500_000L,
                        List.of(new DenominationCount(100_000L, 5))));
        return shop;
    }

    private void setSupplier(ShopFixture.Shop shop) {
        settings.setSupplierIdentity(shop.tenantId(), SUPPLIER);
    }

    /**
     * Both lines carry their own rate stamp rather than inheriting the sale's (M1-18). Without
     * that the bread would inherit 18% and the mixed-basket test would be asserting nothing.
     */
    private static CreateSaleRequest.Line tea(ShopFixture.Shop shop) {
        return new CreateSaleRequest.Line(
                shop.productUuid(), 2, 45_000L, 0L, 13_728L, 90_000L, "INCLUSIVE", 1800);
    }

    private static CreateSaleRequest.Line bread(ShopFixture.Shop shop) {
        return new CreateSaleRequest.Line(
                shop.exemptUuid(), 2, 25_000L, 0L, 0L, 50_000L, "INCLUSIVE", 0);
    }

    /** Two tea at 450.00, 18% inclusive — 900.00 gross, 137.28 of it VAT. */
    private String ringUpTaxable(ShopFixture.Shop shop) {
        return commit(
                shop,
                List.of(tea(shop)),
                90_000L,
                13_728L);
    }

    /** Tea at 18% next to bread at 0% — the basket §4.2 will not allow onto one tax invoice. */
    private String ringUpMixed(ShopFixture.Shop shop) {
        return commit(
                shop,
                List.of(tea(shop), bread(shop)),
                140_000L,
                13_728L);
    }

    private String ringUpExemptOnly(ShopFixture.Shop shop) {
        return commit(
                shop,
                List.of(bread(shop)),
                50_000L,
                0L);
    }

    private String commit(
            ShopFixture.Shop shop, List<CreateSaleRequest.Line> lines, long total, long tax) {
        SaleResponse response =
                sales.commit(
                        new CreateSaleRequest(
                                UUID.randomUUID(),
                                shop.branchCode(),
                                "T1",
                                null,
                                "INCLUSIVE",
                                1800,
                                total,
                                0L,
                                tax,
                                total,
                                lines,
                                0L,
                                0L,
                                List.of(new CreateSaleRequest.Tender("CASH", total)),
                                null));
        return response.invoiceNumber();
    }

    private long nextSeq(ShopFixture.Shop shop, String docType) {
        List<Long> found =
                jdbc.queryForList(
                        """
                        SELECT next_seq FROM invoice_counters
                         WHERE tenant_id = ? AND branch_id = ? AND terminal_code = 'T1' AND doc_type = ?
                        """,
                        Long.class,
                        shop.tenantId(),
                        shop.branchId(),
                        docType);
        return found.isEmpty() ? 1 : found.get(0);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
