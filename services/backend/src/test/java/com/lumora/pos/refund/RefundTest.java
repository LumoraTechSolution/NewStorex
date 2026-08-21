package com.lumora.pos.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.sale.CreateSaleRequest;
import com.lumora.pos.sale.SaleResponse;
import com.lumora.pos.sale.SaleService;
import com.lumora.pos.shift.DenominationCount;
import com.lumora.pos.shift.OpenShiftRequest;
import com.lumora.pos.shift.ShiftService;
import com.lumora.pos.testfixtures.ShopFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Returns, and Gate M2 (M2-06 … M2-10).
 *
 * <p>The gate is one sentence with two halves: a refund <b>cannot</b> be issued without an original
 * receipt, and <b>cannot</b> be paid to a different tender. Both are asserted here against the real
 * database, because both are the kind of rule that a UI can appear to enforce while the API happily
 * does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class RefundTest {

    @Autowired RefundService refunds;
    @Autowired SaleService sales;
    @Autowired ShiftService shifts;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    // --------------------------------------------------------------- Gate M2, first half

    @Test
    void aRefundCannotBeIssuedWithoutAnOriginalReceipt() {
        openShop();

        // There is no API that takes an amount and gives money back. The only way in is a
        // receipt, and an unknown one ends the flow.
        assertThatThrownBy(() -> refunds.lookup("KND-T9-999999"))
                .hasMessageContaining("No sale found for invoice");
    }

    @Test
    void theLookupReportsWhatEachLineAndTenderCanStillReturn() {
        ShopFixture.Shop shop = openShop();
        // 1,350.00 basket, paid with 1,400.00 — so 50.00 came straight back as change and was
        // never the shop's to refund.
        Sale sale = ringUpCash(shop, 3, 45_000, 140_000);

        RefundableSaleResponse found = refunds.lookup(sale.invoiceNumber());

        assertThat(found.lines()).hasSize(1);
        assertThat(found.lines().get(0).qty()).isEqualTo(3);
        assertThat(found.lines().get(0).refundableQty()).isEqualTo(3);
        assertThat(found.lines().get(0).chargedMinor()).isEqualTo(135_000);

        // Cash is netted against the change: the 500.00 note was never all the shop's.
        assertThat(found.tenders()).hasSize(1);
        assertThat(found.tenders().get(0).kind()).isEqualTo("CASH");
        assertThat(found.tenders().get(0).paidMinor()).isEqualTo(135_000);
        assertThat(found.tenders().get(0).refundableMinor()).isEqualTo(135_000);
    }

    // -------------------------------------------------------------- Gate M2, second half

    @Test
    void aCardSaleCannotBeRefundedInCash() {
        ShopFixture.Shop shop = openShop();
        Sale sale = ringUpCard(shop, 1, 45_000);

        assertThatThrownBy(() -> refunds.commit(refundRequest(shop, sale, 1, 45_000, 6_864, "CASH")))
                .hasMessageContaining("was not paid with it")
                .hasMessageContaining("goes back the way it came");
    }

    @Test
    void aRefundCannotExceedWhatItsTenderTook() {
        ShopFixture.Shop shop = openShop();
        // Split: 200.00 on the card, the rest cash.
        Sale sale = ringUpSplit(shop, 45_000, 20_000, 25_000);

        // The card only ever took 200.00.
        assertThatThrownBy(() -> refunds.commit(refundRequest(shop, sale, 1, 45_000, 6_864, "CARD")))
                .hasMessageContaining("still refundable");
    }

    @Test
    void aSplitSaleRefundsToBothTenders() {
        ShopFixture.Shop shop = openShop();
        Sale sale = ringUpSplit(shop, 45_000, 20_000, 25_000);

        CreateRefundRequest request =
                new CreateRefundRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        sale.invoiceNumber(),
                        ShopFixture.MANAGER_PIN,
                        null,
                        45_000L,
                        6_864L,
                        0L,
                        List.of(line(1, 1, 45_000, 6_864)),
                        List.of(
                                new CreateRefundRequest.Tender("CARD", 20_000L),
                                new CreateRefundRequest.Tender("CASH", 25_000L)));

        RefundResponse refund = refunds.commit(request);

        assertThat(refund.totalMinor()).isEqualTo(45_000);
        assertThat(count("SELECT count(*) FROM refund_payments WHERE refund_id = ?", refund.id()))
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ manager PIN (M2-07)

    @Test
    void aRefundNeedsTheManagerPin() {
        ShopFixture.Shop shop = openShop();
        Sale sale = ringUpCash(shop, 1, 45_000, 45_000);

        CreateRefundRequest wrongPin =
                new CreateRefundRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        sale.invoiceNumber(),
                        "0000",
                        null,
                        45_000L,
                        6_864L,
                        0L,
                        List.of(line(1, 1, 45_000, 6_864)),
                        List.of(new CreateRefundRequest.Tender("CASH", 45_000L)));

        assertThatThrownBy(() -> refunds.commit(wrongPin)).hasMessageContaining("not recognised");
        assertThat(count("SELECT count(*) FROM refunds WHERE client_uuid = ?", wrongPin.clientUuid()))
                .isZero();
    }

    /** An unconfigured gate is a closed gate. The direction a default has to fail in. */
    @Test
    void aShopWithNoManagerPinCanRefundNothing() {
        ShopFixture.Shop shop = openShop();
        Sale sale = ringUpCash(shop, 1, 45_000, 45_000);

        // Captured, not reconstructed: the hash belongs to whatever cost factor the encoder is
        // configured with, and a constant here would tie the test to today's.
        String hash =
                jdbc.queryForObject(
                        "SELECT manager_pin_hash FROM tenant_settings WHERE tenant_id = ?",
                        String.class,
                        shop.tenantId());
        jdbc.update("UPDATE tenant_settings SET manager_pin_hash = NULL WHERE tenant_id = ?", shop.tenantId());

        try {
            assertThatThrownBy(() -> refunds.commit(refundRequest(shop, sale, 1, 45_000, 6_864, "CASH")))
                    .hasMessageContaining("No manager PIN has been set");
        } finally {
            // Every fixture shares the one tenant a desktop database may hold, so this has to go
            // back or it takes the rest of the class down with it.
            jdbc.update(
                    "UPDATE tenant_settings SET manager_pin_hash = ? WHERE tenant_id = ?",
                    hash,
                    shop.tenantId());
        }
    }

    // ------------------------------------------------------------------ partials (M2-08)

    @Test
    void aPartialReturnLeavesTheRestReturnable() {
        ShopFixture.Shop shop = openShop();
        Sale sale = ringUpCash(shop, 3, 45_000, 135_000);

        refunds.commit(refundRequest(shop, sale, 1, 45_000, 6_864, "CASH"));

        RefundableSaleResponse after = refunds.lookup(sale.invoiceNumber());
        assertThat(after.lines().get(0).refundableQty()).isEqualTo(2);
        assertThat(after.lines().get(0).refundableMinor()).isEqualTo(90_000);
        assertThat(after.tenders().get(0).refundableMinor()).isEqualTo(90_000);
    }

    @Test
    void aLineCannotGoBackTwice() {
        ShopFixture.Shop shop = openShop();
        Sale sale = ringUpCash(shop, 2, 45_000, 90_000);

        refunds.commit(refundRequest(shop, sale, 2, 90_000, 13_728, "CASH"));

        assertThatThrownBy(() -> refunds.commit(refundRequest(shop, sale, 1, 45_000, 6_864, "CASH")))
                .hasMessageContaining("0 of 2 remain");
    }

    /**
     * The money cap, which the quantity cap does not imply.
     *
     * <p>Returning the right number of units for the wrong amount is precisely what a quantity
     * check alone waves through.
     */
    @Test
    void aLineCannotRefundMoreMoneyThanItWasCharged() {
        ShopFixture.Shop shop = openShop();
        Sale sale = ringUpCash(shop, 1, 45_000, 45_000);

        assertThatThrownBy(() -> refunds.commit(refundRequest(shop, sale, 1, 90_000, 13_728, "CASH")))
                .hasMessageContaining("still refundable");
    }

    @Test
    void theLineTotalsMustAddUpToTheRefund() {
        ShopFixture.Shop shop = openShop();
        Sale sale = ringUpCash(shop, 1, 45_000, 45_000);

        CreateRefundRequest mismatched =
                new CreateRefundRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        sale.invoiceNumber(),
                        ShopFixture.MANAGER_PIN,
                        null,
                        40_000L, // not what the line says
                        6_864L,
                        0L,
                        List.of(line(1, 1, 45_000, 6_864)),
                        List.of(new CreateRefundRequest.Tender("CASH", 40_000L)));

        assertThatThrownBy(() -> refunds.commit(mismatched)).hasMessageContaining("but totalMinor is");
    }

    // ------------------------------------------------------------------ restock (M2-10)

    @Test
    void onlyRestockedLinesWriteAReturnMovement() {
        ShopFixture.Shop shop = openShop();
        Sale sale = ringUpCash(shop, 2, 45_000, 90_000);

        // Damaged: the money goes back, the goods do not go on the shelf.
        RefundResponse damaged =
                refunds.commit(
                        new CreateRefundRequest(
                                UUID.randomUUID(),
                                shop.branchCode(),
                                "T1",
                                sale.invoiceNumber(),
                                ShopFixture.MANAGER_PIN,
                                null,
                                45_000L,
                                6_864L,
                                0L,
                                List.of(
                                        new CreateRefundRequest.Line(
                                                1, 1, 45_000L, 6_864L, "DAMAGED", null, false)),
                                List.of(new CreateRefundRequest.Tender("CASH", 45_000L))));

        assertThat(count(
                        "SELECT count(*) FROM stock_movements WHERE ref_type = 'refund' AND ref_id = ?",
                        damaged.id()))
                .isZero();

        // Unwanted: back it goes, as a positive movement.
        RefundResponse unwanted = refunds.commit(refundRequest(shop, sale, 1, 45_000, 6_864, "CASH"));
        Integer qtyDelta =
                jdbc.queryForObject(
                        "SELECT qty_delta FROM stock_movements WHERE ref_type = 'refund' AND ref_id = ?",
                        Integer.class,
                        unwanted.id());
        assertThat(qtyDelta).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the document

    @Test
    void aRefundIsANewDocumentAndNeverAnEditToTheSale() {
        ShopFixture.Shop shop = openShop();
        Sale sale = ringUpCash(shop, 2, 45_000, 90_000);

        RefundResponse refund = refunds.commit(refundRequest(shop, sale, 1, 45_000, 6_864, "CASH"));

        // The invoice is untouched: it is what the customer was given and what the revenue
        // authority will be shown.
        assertThat(jdbc.queryForObject("SELECT total_minor FROM sales WHERE id = ?", Long.class, sale.id()))
                .isEqualTo(90_000);
        assertThat(jdbc.queryForObject(
                        "SELECT qty FROM sale_items WHERE sale_id = ? AND line_no = 1", Integer.class, sale.id()))
                .isEqualTo(2);

        // And the credit note is visibly not an invoice, from its own sequence (V108).
        assertThat(refund.creditNoteNumber()).contains("-CN-");
        assertThat(refund.creditNoteNumber()).isNotEqualTo(sale.invoiceNumber());
    }

    @Test
    void creditNotesAndInvoicesCountSeparately() {
        ShopFixture.Shop shop = openShop();
        Sale first = ringUpCash(shop, 1, 45_000, 45_000);
        Sale second = ringUpCash(shop, 1, 45_000, 45_000);

        RefundResponse refund = refunds.commit(refundRequest(shop, second, 1, 45_000, 6_864, "CASH"));

        // An auditor reading the invoice sequence must be able to conclude that a gap is a
        // missing invoice, not a refund that borrowed a number.
        assertThat(first.invoiceNumber()).endsWith("-000001");
        assertThat(second.invoiceNumber()).endsWith("-000002");
        assertThat(refund.creditNoteNumber()).endsWith("-CN-000001");
    }

    @Test
    void resendingARefundReturnsTheSameCreditNote() {
        ShopFixture.Shop shop = openShop();
        Sale sale = ringUpCash(shop, 2, 45_000, 90_000);

        CreateRefundRequest request = refundRequest(shop, sale, 1, 45_000, 6_864, "CASH");
        RefundResponse first = refunds.commit(request);
        RefundResponse retry = refunds.commit(request);

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.creditNoteNumber()).isEqualTo(first.creditNoteNumber());
        assertThat(retry.alreadyExisted()).isTrue();
        assertThat(count("SELECT count(*) FROM refunds WHERE sale_id = ?", sale.id())).isEqualTo(1);
    }

    @Test
    void theRefundReachesTheCloudAsItsOwnAggregate() {
        ShopFixture.Shop shop = openShop();
        Sale sale = ringUpCash(shop, 1, 45_000, 45_000);
        CreateRefundRequest request = refundRequest(shop, sale, 1, 45_000, 6_864, "CASH");
        refunds.commit(request);

        // M2-12, and the reference the cloud resolves by: a refund may arrive before its sale.
        assertThat(jsonField(request.clientUuid(), "saleClientUuid")).isEqualTo(sale.clientUuid().toString());
        assertThat(jsonField(request.clientUuid(), "totalMinor")).isEqualTo("45000");
        assertThat(count(
                        "SELECT count(*) FROM outbox WHERE aggregate = 'refund' AND aggregate_id = ? AND acked_at IS NULL",
                        request.clientUuid()))
                .isEqualTo(1);
    }

    @Test
    void aRefundNeedsAnOpenShift() {
        ShopFixture.Shop shop = openShop();
        Sale sale = ringUpCash(shop, 1, 45_000, 45_000);
        jdbc.update(
                "UPDATE shifts SET status = 'CLOSED', closed_at = now(), closed_by = 1, counted_cash_minor = 0,"
                        + " expected_cash_minor = 0, variance_minor = 0"
                        + " WHERE branch_id = ? AND status = 'OPEN'",
                shop.branchId());

        assertThatThrownBy(() -> refunds.commit(refundRequest(shop, sale, 1, 45_000, 6_864, "CASH")))
                .hasMessageContaining("No shift is open");
    }

    // ------------------------------------------------------------------------ helpers

    private ShopFixture.Shop openShop() {
        ShopFixture.Shop shop = fixtures.seed();
        shifts.open(
                new OpenShiftRequest(
                        UUID.randomUUID(),
                        shop.branchCode(),
                        "T1",
                        null,
                        500_000L,
                        List.of(new DenominationCount(100_000L, 5))));
        return shop;
    }

    private CreateRefundRequest refundRequest(
            ShopFixture.Shop shop, Sale sale, int qty, long totalMinor, long taxMinor, String kind) {
        return new CreateRefundRequest(
                UUID.randomUUID(),
                shop.branchCode(),
                "T1",
                sale.invoiceNumber(),
                ShopFixture.MANAGER_PIN,
                null,
                totalMinor,
                taxMinor,
                0L,
                List.of(line(1, qty, totalMinor, taxMinor)),
                List.of(new CreateRefundRequest.Tender(kind, totalMinor)));
    }

    private static CreateRefundRequest.Line line(int saleLineNo, int qty, long totalMinor, long taxMinor) {
        return new CreateRefundRequest.Line(
                saleLineNo, qty, totalMinor, taxMinor, "CHANGED_MIND", null, true);
    }

    private Sale ringUpCash(ShopFixture.Shop shop, int qty, long unitMinor, long cashMinor) {
        return ringUp(shop, qty, unitMinor, List.of(new CreateSaleRequest.Tender("CASH", cashMinor)));
    }

    private Sale ringUpCard(ShopFixture.Shop shop, int qty, long unitMinor) {
        return ringUp(
                shop, qty, unitMinor, List.of(new CreateSaleRequest.Tender("CARD", unitMinor * qty)));
    }

    private Sale ringUpSplit(ShopFixture.Shop shop, long unitMinor, long cardMinor, long cashMinor) {
        return ringUp(
                shop,
                1,
                unitMinor,
                List.of(
                        new CreateSaleRequest.Tender("CARD", cardMinor),
                        new CreateSaleRequest.Tender("CASH", cashMinor)));
    }

    private Sale ringUp(
            ShopFixture.Shop shop, int qty, long unitMinor, List<CreateSaleRequest.Tender> tenders) {
        long total = unitMinor * qty;
        long tax = total * 1800 / 11800;
        long tendered = tenders.stream().mapToLong(CreateSaleRequest.Tender::amountMinor).sum();
        boolean hasCash = tenders.stream().anyMatch(t -> "CASH".equals(t.kind()));
        long rounding = hasCash ? roundToRupee(total) - total : 0;
        long change = Math.max(0, tendered - total - rounding);

        UUID clientUuid = UUID.randomUUID();
        SaleResponse response =
                sales.commit(
                        new CreateSaleRequest(
                                clientUuid,
                                shop.branchCode(),
                                "T1",
                                null,
                                "INCLUSIVE",
                                1800,
                                total,
                                0L,
                                tax,
                                total,
                                List.of(
                                        new CreateSaleRequest.Line(
                                                shop.productUuid(), qty, unitMinor, 0L, tax, total)),
                                rounding,
                                change,
                                tenders));
        return new Sale(response.id(), clientUuid, response.invoiceNumber());
    }

    private static long roundToRupee(long amountMinor) {
        return Math.floorDiv(amountMinor + 50, 100) * 100;
    }

    private String jsonField(UUID aggregateId, String field) {
        return jdbc.queryForObject(
                "SELECT payload->>? FROM outbox WHERE aggregate = 'refund' AND aggregate_id = ?",
                String.class,
                field,
                aggregateId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private record Sale(long id, UUID clientUuid, String invoiceNumber) {}
}
