package com.lumora.pos.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The M0 spike's whole point: a sale commits locally, completely, and carries its own sync record.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}. These tests need {@link SaleService}'s
 * own transaction to really commit and really roll back — a test-managed transaction wrapping it
 * would mask exactly the behaviour under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class SaleCommitTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    /** A desktop database holds exactly one tenant. Every test in this class shares it. */
    private static final UUID SOLE_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    @Autowired SaleService sales;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aSaleWritesItsLinesMovementsAndOutboxRowTogether() {
        Fixture fixture = seed();
        UUID saleUuid = UUID.randomUUID();

        SaleResponse response = sales.commit(request(saleUuid, fixture, 2, 45000));

        assertThat(response.alreadyExisted()).isFalse();
        assertThat(response.invoiceNumber()).isEqualTo(fixture.branchCode() + "-T1-000001");
        assertThat(response.totalMinor()).isEqualTo(90000);

        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", saleUuid)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sale_items WHERE sale_id = ?", response.id())).isEqualTo(1);

        // Stock left the shelf: one negative movement, linked back to the sale.
        Integer qtyDelta =
                jdbc.queryForObject(
                        "SELECT qty_delta FROM stock_movements WHERE ref_type = 'sale' AND ref_id = ?",
                        Integer.class,
                        response.id());
        assertThat(qtyDelta).isEqualTo(-2);

        // And the sync record exists, unacked, keyed on the same uuid the cloud upserts on.
        assertThat(count("SELECT count(*) FROM outbox WHERE aggregate = 'sale' AND aggregate_id = ? AND acked_at IS NULL", saleUuid))
                .isEqualTo(1);
    }

    @Test
    void theOutboxPayloadCarriesEnoughToRebuildTheSale() {
        Fixture fixture = seed();
        UUID saleUuid = UUID.randomUUID();
        sales.commit(request(saleUuid, fixture, 1, 45000));

        // Queried through jsonb operators rather than string matching: what matters is that the
        // cloud can read these fields, not how Postgres chose to lay the text out.
        assertThat(jsonField(saleUuid, "invoiceNumber")).isEqualTo(fixture.branchCode() + "-T1-000001");
        assertThat(jsonField(saleUuid, "totalMinor")).isEqualTo("45000");
        assertThat(jsonField(saleUuid, "taxRateBp")).isEqualTo("1800");
        assertThat(jsonField(saleUuid, "taxMode")).isEqualTo("INCLUSIVE");
        assertThat(jsonField(saleUuid, "clientUuid")).isEqualTo(saleUuid.toString());

        // The rate is stamped on the payload, so a receipt reprinted after a VAT change
        // still reproduces the sale as it was rung up.
        String firstLineProduct =
                jdbc.queryForObject(
                        "SELECT payload->'lines'->0->>'productClientUuid' FROM outbox WHERE aggregate_id = ?",
                        String.class,
                        saleUuid);
        assertThat(firstLineProduct).isEqualTo(fixture.productUuid().toString());
    }

    /**
     * The guarantee the architecture rests on: a sale can never exist without its sync record. If
     * the outbox write fails, the sale must not survive either.
     */
    @Test
    void aFailedOutboxWriteTakesTheWholeSaleWithIt() {
        Fixture fixture = seed();
        UUID saleUuid = UUID.randomUUID();

        breakOutboxInserts();
        try {
            assertThatThrownBy(() -> sales.commit(request(saleUuid, fixture, 1, 45000)))
                    .isInstanceOf(Exception.class);
        } finally {
            repairOutboxInserts();
        }

        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", saleUuid))
                .as("the sale must have rolled back with its outbox row")
                .isZero();
        assertThat(count("SELECT count(*) FROM stock_movements WHERE ref_type = 'sale' AND ref_id IN (SELECT id FROM sales WHERE client_uuid = ?)", saleUuid))
                .as("no orphan stock movement")
                .isZero();
    }

    @Test
    void resendingTheSameSaleReturnsTheOriginalAndDoesNotDuplicateIt() {
        Fixture fixture = seed();
        UUID saleUuid = UUID.randomUUID();

        SaleResponse first = sales.commit(request(saleUuid, fixture, 1, 45000));
        SaleResponse retry = sales.commit(request(saleUuid, fixture, 1, 45000));

        assertThat(retry.alreadyExisted()).isTrue();
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.invoiceNumber()).isEqualTo(first.invoiceNumber());

        assertThat(count("SELECT count(*) FROM sales WHERE client_uuid = ?", saleUuid)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox WHERE aggregate_id = ?", saleUuid))
                .as("a retry must not enqueue the sale twice")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM stock_movements WHERE ref_type = 'sale' AND ref_id = ?", first.id()))
                .as("a retry must not move stock twice")
                .isEqualTo(1);
    }

    /**
     * M1-15. Two things at once, because they are the same guarantee seen from either end: every
     * line moves its own stock, and the uuid on each of those movements is the one the outbox
     * carries. The second half is what makes the cloud's upsert idempotent — a movement whose uuid
     * were minted cloud-side would land again on every redelivery, and stock on hand, being the sum
     * of these rows, would walk away from the truth by one sale per retry.
     */
    @Test
    void everyLineMovesItsOwnStockAndTheOutboxCarriesThoseExactUuids() {
        Fixture fixture = seed();
        UUID second = seedAnotherProduct(fixture);
        UUID saleUuid = UUID.randomUUID();

        SaleResponse response = sales.commit(twoLineRequest(saleUuid, fixture, second));

        List<Map<String, Object>> movements =
                jdbc.queryForList(
                        """
                        SELECT p.client_uuid AS product, m.qty_delta, m.reason, m.client_uuid
                        FROM stock_movements m JOIN products p ON p.id = m.product_id
                        WHERE m.ref_type = 'sale' AND m.ref_id = ?
                        ORDER BY m.id
                        """,
                        response.id());

        assertThat(movements).as("one movement per cart line").hasSize(2);
        assertThat(movements.get(0))
                .containsEntry("product", fixture.productUuid())
                .containsEntry("qty_delta", -2)
                .containsEntry("reason", "SALE");
        assertThat(movements.get(1)).containsEntry("product", second).containsEntry("qty_delta", -3);

        List<String> inOutbox =
                jdbc.queryForList(
                        """
                        SELECT jsonb_array_elements(payload->'movements')->>'clientUuid'
                        FROM outbox WHERE aggregate_id = ?
                        """,
                        String.class,
                        saleUuid);
        List<String> written = movements.stream().map(m -> m.get("client_uuid").toString()).toList();
        assertThat(inOutbox)
                .as("the cloud must upsert on the same key the till wrote locally")
                .containsExactlyInAnyOrderElementsOf(written);
    }

    /**
     * The ground rule, asserted as arithmetic rather than as a schema shape (that part is
     * {@code MinimalSchemaTest.noTableStoresAStockLevel}): on hand is Σ movements, so selling the
     * same product twice subtracts twice with nothing to keep in step.
     */
    @Test
    void stockOnHandIsTheSumOfMovements() {
        Fixture fixture = seed();

        sales.commit(request(UUID.randomUUID(), fixture, 2, 45000));
        sales.commit(request(UUID.randomUUID(), fixture, 5, 45000));

        Integer onHand =
                jdbc.queryForObject(
                        """
                        SELECT coalesce(sum(m.qty_delta), 0) FROM stock_movements m
                        JOIN products p ON p.id = m.product_id WHERE p.client_uuid = ?
                        """,
                        Integer.class,
                        fixture.productUuid());
        assertThat(onHand).isEqualTo(-7);
    }

    @Test
    void invoiceNumbersRunPerTerminalAndDoNotCollide() {
        Fixture fixture = seed();
        // seed() opens a shift on T1 only; a second terminal is a second shift (M2-01), which is
        // exactly the point — the unique index is scoped to (tenant, branch, terminal).
        openShiftOn(fixture, "T2");

        SaleResponse t1a = sales.commit(requestOn(UUID.randomUUID(), fixture, "T1"));
        SaleResponse t1b = sales.commit(requestOn(UUID.randomUUID(), fixture, "T1"));
        SaleResponse t2a = sales.commit(requestOn(UUID.randomUUID(), fixture, "T2"));

        assertThat(t1a.invoiceNumber()).isEqualTo(fixture.branchCode() + "-T1-000001");
        assertThat(t1b.invoiceNumber()).isEqualTo(fixture.branchCode() + "-T1-000002");
        // T2 counts on its own — that is what lets terminals issue numbers offline
        // without coordinating.
        assertThat(t2a.invoiceNumber()).isEqualTo(fixture.branchCode() + "-T2-000001");
    }

    @Test
    void totalsThatDoNotAddUpAreRejected() {
        Fixture fixture = seed();

        CreateSaleRequest wrong =
                new CreateSaleRequest(
                        UUID.randomUUID(),
                        fixture.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        45000L,
                        0L,
                        6864L,
                        99999L, // does not equal subtotal - discount
                        List.of(new CreateSaleRequest.Line(fixture.productUuid(), 1, 45000L, 0L, 6864L, 45000L)),
                        0L,
                        0L,
                        List.of(new CreateSaleRequest.Tender("CASH", 99999L)), null);

        assertThatThrownBy(() -> sales.commit(wrong))
                .isInstanceOf(SaleRejectedException.class)
                .hasMessageContaining("is not totalMinor");
    }

    @Test
    void aSplitSaleWritesOnePaymentRowPerTenderInOrder() {
        Fixture fixture = seed();
        UUID saleUuid = UUID.randomUUID();
        long lineTotal = 90000;

        CreateSaleRequest request =
                new CreateSaleRequest(
                        saleUuid,
                        fixture.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        lineTotal,
                        0L,
                        lineTotal * 1800 / 11800,
                        lineTotal,
                        List.of(
                                new CreateSaleRequest.Line(
                                        fixture.productUuid(), 2, 45000L, 0L, lineTotal * 1800 / 11800, lineTotal)),
                        0L,
                        0L,
                        List.of(
                                new CreateSaleRequest.Tender("CARD", 60000L),
                                new CreateSaleRequest.Tender("CASH", 30000L)), null);

        SaleResponse response = sales.commit(request);

        assertThat(response.changeMinor()).isZero();
        List<Map<String, Object>> payments =
                jdbc.queryForList(
                        "SELECT line_no, kind, amount_minor FROM sale_payments WHERE sale_id = ? ORDER BY line_no",
                        response.id());
        assertThat(payments).hasSize(2);
        assertThat(payments.get(0)).containsEntry("kind", "CARD").containsEntry("amount_minor", 60000L);
        assertThat(payments.get(1)).containsEntry("kind", "CASH").containsEntry("amount_minor", 30000L);
    }

    @Test
    void cashOverpaymentRecordsChangeAndTheOutboxPayloadCarriesIt() {
        Fixture fixture = seed();
        UUID saleUuid = UUID.randomUUID();
        long lineTotal = 45000;

        CreateSaleRequest request =
                new CreateSaleRequest(
                        saleUuid,
                        fixture.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        lineTotal,
                        0L,
                        lineTotal * 1800 / 11800,
                        lineTotal,
                        List.of(
                                new CreateSaleRequest.Line(
                                        fixture.productUuid(), 1, lineTotal, 0L, lineTotal * 1800 / 11800, lineTotal)),
                        0L,
                        55000L, // customer handed over 1000.00 against a 450.00 sale
                        List.of(new CreateSaleRequest.Tender("CASH", 100000L)), null);

        SaleResponse response = sales.commit(request);

        assertThat(response.changeMinor()).isEqualTo(55000L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT change_minor FROM sales WHERE id = ?", Long.class, response.id()))
                .isEqualTo(55000L);
        assertThat(jsonField(saleUuid, "changeMinor")).isEqualTo("55000");
    }

    @Test
    void tendersThatDoNotCoverTheTotalAreRejected() {
        Fixture fixture = seed();
        CreateSaleRequest request =
                new CreateSaleRequest(
                        UUID.randomUUID(),
                        fixture.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        45000L,
                        0L,
                        6864L,
                        45000L,
                        List.of(new CreateSaleRequest.Line(fixture.productUuid(), 1, 45000L, 0L, 6864L, 45000L)),
                        0L,
                        0L,
                        List.of(new CreateSaleRequest.Tender("CASH", 30000L)), null); // short by 150.00

        assertThatThrownBy(() -> sales.commit(request))
                .isInstanceOf(SaleRejectedException.class)
                .hasMessageContaining("tenders sum to");
    }

    @Test
    void changeCannotBeClaimedWithoutACashTender() {
        Fixture fixture = seed();
        CreateSaleRequest request =
                new CreateSaleRequest(
                        UUID.randomUUID(),
                        fixture.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        45000L,
                        0L,
                        6864L,
                        45000L,
                        List.of(new CreateSaleRequest.Line(fixture.productUuid(), 1, 45000L, 0L, 6864L, 45000L)),
                        0L,
                        10000L, // a card cannot hand back change
                        List.of(new CreateSaleRequest.Tender("CARD", 55000L)), null);

        assertThatThrownBy(() -> sales.commit(request))
                .isInstanceOf(SaleRejectedException.class)
                .hasMessageContaining("no CASH tender was recorded");
    }

    /**
     * A shop PC holding two tenants means something upstream went wrong. Failing loudly at the next
     * sale beats silently picking one of them and attributing a shop's takings to the wrong owner.
     */
    @Test
    void aSecondTenantOnAShopPcIsRefusedRatherThanGuessedAt() {
        Fixture fixture = seed();
        UUID intruder = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (client_uuid, name) VALUES (?, 'Somebody Else')", intruder);

        try {
            assertThatThrownBy(() -> sales.commit(request(UUID.randomUUID(), fixture, 1, 45000)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exactly one tenant");
        } finally {
            jdbc.update("DELETE FROM tenants WHERE client_uuid = ?", intruder);
        }
    }

    @Test
    void anUnknownProductIsRejected() {
        Fixture fixture = seed();
        UUID ghost = UUID.randomUUID();

        CreateSaleRequest request =
                new CreateSaleRequest(
                        UUID.randomUUID(),
                        fixture.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        45000L,
                        0L,
                        6864L,
                        45000L,
                        List.of(new CreateSaleRequest.Line(ghost, 1, 45000L, 0L, 6864L, 45000L)),
                        0L,
                        0L,
                        List.of(new CreateSaleRequest.Tender("CASH", 45000L)), null);

        assertThatThrownBy(() -> sales.commit(request))
                .isInstanceOf(SaleRejectedException.class)
                .hasMessageContaining("Unknown product");
    }

    // ------------------------------------------------------------------- helpers

    private CreateSaleRequest request(UUID saleUuid, Fixture fixture, int qty, long unitPrice) {
        long lineTotal = unitPrice * qty;
        return new CreateSaleRequest(
                saleUuid,
                fixture.branchCode(),
                "T1",
                null,
                "INCLUSIVE",
                1800,
                lineTotal,
                0L,
                lineTotal * 1800 / 11800, // VAT extracted from an inclusive price
                lineTotal,
                List.of(
                        new CreateSaleRequest.Line(
                                fixture.productUuid(), qty, unitPrice, 0L, lineTotal * 1800 / 11800, lineTotal)),
                0L,
                0L,
                List.of(new CreateSaleRequest.Tender("CASH", lineTotal)), null);
    }

    /** Two lines, two products, different quantities — so a movement cannot pass by coincidence. */
    private CreateSaleRequest twoLineRequest(UUID saleUuid, Fixture fixture, UUID secondProduct) {
        long firstTotal = 90000; // 2 × 450.00
        long secondTotal = 36000; // 3 × 120.00
        long subtotal = firstTotal + secondTotal;
        // Summed from the lines, not extracted from the subtotal. Extraction truncates, so the
        // two differ by a cent here (19,219 against 19,220) — and the lines are what the
        // customer was charged. cartTotals has always summed them; before M1-18 the backend
        // did not check it, so this fixture could disagree with the terminal and still pass.
        long tax = firstTotal * 1800 / 11800 + secondTotal * 1800 / 11800;
        return new CreateSaleRequest(
                saleUuid,
                fixture.branchCode(),
                "T1",
                null,
                "INCLUSIVE",
                1800,
                subtotal,
                0L,
                tax,
                subtotal,
                List.of(
                        new CreateSaleRequest.Line(
                                fixture.productUuid(), 2, 45000L, 0L, firstTotal * 1800 / 11800, firstTotal),
                        new CreateSaleRequest.Line(
                                secondProduct, 3, 12000L, 0L, secondTotal * 1800 / 11800, secondTotal)),
                0L,
                0L,
                List.of(new CreateSaleRequest.Tender("CASH", subtotal)), null);
    }

    private CreateSaleRequest requestOn(UUID saleUuid, Fixture fixture, String terminalCode) {
        CreateSaleRequest base = request(saleUuid, fixture, 1, 45000);
        return new CreateSaleRequest(
                base.clientUuid(),
                base.branchCode(),
                terminalCode,
                base.soldAt(),
                base.taxMode(),
                base.taxRateBp(),
                base.subtotalMinor(),
                base.discountMinor(),
                base.taxMinor(),
                base.totalMinor(),
                base.lines(),
                base.roundingAdjustmentMinor(),
                base.changeMinor(),
                base.tenders(), null);
    }

    // ------------------------------------------------------------------ M1-18: per-line rates

    @Test
    void aCartMixingRatesStoresEachLineUnderItsOwnStamp() {
        Fixture fixture = seed();
        UUID exemptProduct = seedExemptProduct(fixture);
        UUID saleUuid = UUID.randomUUID();

        // Bread at 0% beside tea at 18% - the basket the till used to refuse outright.
        long breadTotal = 50000;
        long teaTotal = 45000;
        long teaTax = teaTotal * 1800 / 11800;

        CreateSaleRequest request =
                new CreateSaleRequest(
                        saleUuid,
                        fixture.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        breadTotal + teaTotal,
                        0L,
                        teaTax,
                        breadTotal + teaTotal,
                        List.of(
                                new CreateSaleRequest.Line(
                                        exemptProduct, 2, 25000L, 0L, 0L, breadTotal, "INCLUSIVE", 0),
                                new CreateSaleRequest.Line(
                                        fixture.productUuid(), 1, 45000L, 0L, teaTax, teaTotal, "INCLUSIVE", 1800)),
                        0L,
                        0L,
                        List.of(new CreateSaleRequest.Tender("CASH", breadTotal + teaTotal)), null);

        SaleResponse response = sales.commit(request);

        List<Map<String, Object>> items =
                jdbc.queryForList(
                        "SELECT line_no, tax_mode, tax_rate_bp, tax_minor FROM sale_items WHERE sale_id = ?"
                                + " ORDER BY line_no",
                        response.id());
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("tax_rate_bp", 0).containsEntry("tax_minor", 0L);
        assertThat(items.get(1)).containsEntry("tax_rate_bp", 1800).containsEntry("tax_minor", teaTax);

        // The sale keeps the cart default, which is NOT a summary of the lines. Reading it as
        // the sale's rate on a mixed basket is the mistake that column's comment warns about.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT tax_rate_bp FROM sales WHERE id = ?", Integer.class, response.id()))
                .isEqualTo(1800);

        // The per-rate summary a tax invoice needs is a GROUP BY over the lines, and nothing
        // but the lines can produce it.
        List<Map<String, Object>> byRate =
                jdbc.queryForList(
                        """
                        SELECT tax_rate_bp, sum(line_total_minor)::bigint AS gross, sum(tax_minor)::bigint AS tax
                        FROM sale_items WHERE sale_id = ? GROUP BY tax_rate_bp ORDER BY tax_rate_bp
                        """,
                        response.id());
        assertThat(byRate).hasSize(2);
        assertThat(byRate.get(0)).containsEntry("gross", breadTotal).containsEntry("tax", 0L);
        assertThat(byRate.get(1)).containsEntry("gross", teaTotal).containsEntry("tax", teaTax);
    }

    @Test
    void theOutboxPayloadCarriesTheRateEachLineWasChargedAt() {
        Fixture fixture = seed();
        UUID exemptProduct = seedExemptProduct(fixture);
        UUID saleUuid = UUID.randomUUID();
        long teaTax = 45000L * 1800 / 11800;

        sales.commit(
                new CreateSaleRequest(
                        saleUuid,
                        fixture.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        95000L,
                        0L,
                        teaTax,
                        95000L,
                        List.of(
                                new CreateSaleRequest.Line(
                                        exemptProduct, 2, 25000L, 0L, 0L, 50000L, "INCLUSIVE", 0),
                                new CreateSaleRequest.Line(
                                        fixture.productUuid(), 1, 45000L, 0L, teaTax, 45000L, "INCLUSIVE", 1800)),
                        0L,
                        0L,
                        List.of(new CreateSaleRequest.Tender("CASH", 95000L)), null));

        // Same reasoning as M1-15's movement uuids: what the cloud stores has to be what the
        // till stored. A rate re-derived at the far end from the sale's default would put 18%
        // on the exempt line, and the cloud's reports would disagree with the printed receipt.
        String lines = jsonField(saleUuid, "lines");
        assertThat(lines).contains("taxRateBp\": 0").contains("taxRateBp\": 1800");
    }

    @Test
    void aLineWithNoStampOfItsOwnInheritsTheSaleStamp() {
        Fixture fixture = seed();
        UUID saleUuid = UUID.randomUUID();

        // The pre-M1-18 payload shape, which a terminal that has not been upgraded still
        // sends. It meant "the sale's rate", and that is exactly what it still gets.
        sales.commit(request(saleUuid, fixture, 1, 45000));

        Map<String, Object> item =
                jdbc.queryForMap(
                        "SELECT tax_mode, tax_rate_bp FROM sale_items WHERE sale_id ="
                                + " (SELECT id FROM sales WHERE client_uuid = ?)",
                        saleUuid);
        assertThat(item).containsEntry("tax_mode", "INCLUSIVE").containsEntry("tax_rate_bp", 1800);
    }

    @Test
    void lineTaxesThatDoNotSumToTheSaleTaxAreRejected() {
        Fixture fixture = seed();

        // The invariant that becomes load-bearing with more than one rate: there is no single
        // rate left to recompute a sale's tax from, so the lines are the only thing that can
        // say what it is. 6,864 was the right answer; 9,999 is not.
        CreateSaleRequest wrong =
                new CreateSaleRequest(
                        UUID.randomUUID(),
                        fixture.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        45000L,
                        0L,
                        9999L,
                        45000L,
                        List.of(new CreateSaleRequest.Line(fixture.productUuid(), 1, 45000L, 0L, 6864L, 45000L)),
                        0L,
                        0L,
                        List.of(new CreateSaleRequest.Tender("CASH", 45000L)), null);

        assertThatThrownBy(() -> sales.commit(wrong))
                .isInstanceOf(SaleRejectedException.class)
                .hasMessageContaining("Line taxes sum to");
    }

    @Test
    void aLineTaxedMoreThanItIsWorthIsRejected() {
        Fixture fixture = seed();
        UUID second = seedAnotherProduct(fixture);

        // Every sale-level figure here is consistent — 2,000 of tax on a 45,000 sale adds up
        // fine in aggregate. It is only per line that the sale is nonsense: 20.00 of VAT
        // charged on a 10.00 line. Checking the total alone would let this through, which is
        // the whole reason for a per-line guard once lines can differ (M1-18).
        CreateSaleRequest wrong =
                new CreateSaleRequest(
                        UUID.randomUUID(),
                        fixture.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        45000L,
                        0L,
                        2000L,
                        45000L,
                        List.of(
                                new CreateSaleRequest.Line(second, 1, 1000L, 0L, 2000L, 1000L),
                                new CreateSaleRequest.Line(fixture.productUuid(), 1, 44000L, 0L, 0L, 44000L)),
                        0L,
                        0L,
                        List.of(new CreateSaleRequest.Tender("CASH", 45000L)), null);

        assertThatThrownBy(() -> sales.commit(wrong))
                .isInstanceOf(SaleRejectedException.class)
                .hasMessageContaining("exceeding lineTotalMinor");
    }

    @Test
    void aLineCarryingHalfATaxStampIsRejectedRatherThanHalfInherited() {
        Fixture fixture = seed();

        // Inheriting the missing half would pair this sale's INCLUSIVE with some other rate,
        // silently. A sender that got one field right and the other wrong has a bug, and this
        // is where it should surface.
        CreateSaleRequest wrong =
                new CreateSaleRequest(
                        UUID.randomUUID(),
                        fixture.branchCode(),
                        "T1",
                        null,
                        "INCLUSIVE",
                        1800,
                        45000L,
                        0L,
                        6864L,
                        45000L,
                        List.of(
                                new CreateSaleRequest.Line(
                                        fixture.productUuid(), 1, 45000L, 0L, 6864L, 45000L, null, 1800)),
                        0L,
                        0L,
                        List.of(new CreateSaleRequest.Tender("CASH", 45000L)), null);

        assertThatThrownBy(() -> sales.commit(wrong))
                .isInstanceOf(SaleRejectedException.class)
                .hasMessageContaining("send both or neither");
    }

    private Integer count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private String jsonField(UUID aggregateId, String field) {
        return jdbc.queryForObject(
                "SELECT payload->>? FROM outbox WHERE aggregate_id = ?", String.class, field, aggregateId);
    }

    private void breakOutboxInserts() {
        jdbc.execute(
                """
                CREATE OR REPLACE FUNCTION fail_outbox_insert() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'simulated outbox failure'; END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute(
                "CREATE TRIGGER trg_fail_outbox BEFORE INSERT ON outbox"
                        + " FOR EACH ROW EXECUTE FUNCTION fail_outbox_insert()");
    }

    private void repairOutboxInserts() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_outbox ON outbox");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_outbox_insert()");
    }

    /**
     * One tenant for the whole class, then a unique branch and product per test.
     *
     * <p>The single tenant is not tidiness — it is the invariant a desktop database holds, and
     * {@code SaleService} now asserts it. Branch and product stay unique per test because this
     * class commits for real and would otherwise collide with its own leftovers.
     */
    /**
     * M2-01: a sale outside a shift is cash nothing reconciles, so the till refuses it.
     *
     * <p>Worth stating explicitly because it is a behaviour change to a path that was working:
     * every terminal now has to open a shift before it can trade. It costs the offline guarantee
     * nothing — opening a shift is entirely local — but a till that silently stopped selling
     * would be the worst possible way to discover this rule.
     */
    @Test
    void aSaleIsRefusedWhenNoShiftIsOpen() {
        Fixture fixture = seed();
        jdbc.update(
                "UPDATE shifts SET status = 'CLOSED', closed_at = now(), closed_by = ?,"
                        + " counted_cash_minor = 0, expected_cash_minor = 0, variance_minor = 0"
                        + " WHERE tenant_id = ? AND terminal_code = 'T1' AND status = 'OPEN'",
                fixture.operatorId(),
                fixture.tenantId());

        assertThatThrownBy(() -> sales.commit(request(UUID.randomUUID(), fixture, 1, 45000)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No shift is open");
    }

    /** The shift a sale was rung up on is recorded on it — that link is what a Z-report reads. */
    @Test
    void aSaleRecordsTheShiftItWasRungUpOn() {
        Fixture fixture = seed();
        UUID saleUuid = UUID.randomUUID();
        SaleResponse response = sales.commit(request(saleUuid, fixture, 1, 45000));

        Long shiftId =
                jdbc.queryForObject("SELECT shift_id FROM sales WHERE id = ?", Long.class, response.id());
        assertThat(shiftId).isNotNull();

        // And it travels to the cloud, so the console can group a day's takings by till.
        assertThat(jsonField(saleUuid, "shiftClientUuid"))
                .isEqualTo(
                        jdbc.queryForObject(
                                "SELECT client_uuid::text FROM shifts WHERE id = ?", String.class, shiftId));
    }

    private void openShiftOn(Fixture fixture, String terminalCode) {
        jdbc.update(
                """
                INSERT INTO shifts (client_uuid, tenant_id, branch_id, terminal_code, status,
                                    opened_by, opening_float_minor)
                SELECT ?, ?, id, ?, 'OPEN', ?, 500000
                  FROM branches WHERE tenant_id = ? AND code = ?
                """,
                UUID.randomUUID(),
                fixture.tenantId(),
                terminalCode,
                fixture.operatorId(),
                fixture.tenantId(),
                fixture.branchCode());
    }

    /**
     * The one user this class's shifts are opened by, created on first use.
     *
     * <p>Its own rather than {@code ShopFixture}'s, because this class deliberately builds its
     * fixture by hand — the sale path is what is under test and a shared helper that grows a
     * feature would quietly change what these tests exercise.
     */
    private long operator(long tenantId) {
        List<Long> existing =
                jdbc.queryForList(
                        "SELECT id FROM users WHERE tenant_id = ? AND code = 'SALETEST'",
                        Long.class,
                        tenantId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return jdbc.queryForObject(
                """
                INSERT INTO users (client_uuid, tenant_id, code, display_name, role, pin_hash)
                VALUES (?, ?, 'SALETEST', 'Sale Test Operator', 'CASHIER', 'not-a-real-hash')
                RETURNING id
                """,
                Long.class,
                UUID.randomUUID(),
                tenantId);
    }

    private Fixture seed() {
        int n = UNIQUE.incrementAndGet();
        String branchCode = "B%02d".formatted(n);

        jdbc.update(
                """
                INSERT INTO tenants (client_uuid, name) VALUES (?, 'Kandy Stores')
                ON CONFLICT (client_uuid) DO NOTHING
                """,
                SOLE_TENANT);
        long tenantId =
                jdbc.queryForObject(
                        "SELECT id FROM tenants WHERE client_uuid = ?", Long.class, SOLE_TENANT);

        jdbc.update(
                "INSERT INTO branches (client_uuid, tenant_id, code, name) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(),
                tenantId,
                branchCode,
                "Branch " + n);

        UUID productUuid = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO products (client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp)
                VALUES (?, ?, ?, 'Tea 400g', 45000, 'INCLUSIVE', 1800)
                """,
                productUuid,
                tenantId,
                "SKU-%03d".formatted(n));

        // M2-01. The till does not sell unreconciled, so every fixture opens a shift on T1.
        // The rejection when one is not open is asserted on its own below.
        long operatorId = operator(tenantId);

        jdbc.update(
                """
                INSERT INTO shifts (client_uuid, tenant_id, branch_id, terminal_code, status,
                                    opened_by, opening_float_minor)
                SELECT ?, ?, id, 'T1', 'OPEN', ?, 500000
                  FROM branches WHERE tenant_id = ? AND code = ?
                """,
                UUID.randomUUID(),
                tenantId,
                operatorId,
                tenantId,
                branchCode);

        return new Fixture(tenantId, branchCode, productUuid, operatorId);
    }

    /** A zero-rated product, so a cart can genuinely mix tax treatments (M1-18). */
    private UUID seedExemptProduct(Fixture fixture) {
        UUID productUuid = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO products (client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp)
                VALUES (?, ?, ?, 'Bread 450g', 25000, 'INCLUSIVE', 0)
                """,
                productUuid,
                fixture.tenantId(),
                "SKU-%03d-X".formatted(UNIQUE.get()));
        return productUuid;
    }

    /** A second product on the same tenant, for carts that need more than one line. */
    private UUID seedAnotherProduct(Fixture fixture) {
        UUID productUuid = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO products (client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp)
                VALUES (?, ?, ?, 'Sugar 1kg', 12000, 'INCLUSIVE', 1800)
                """,
                productUuid,
                fixture.tenantId(),
                "SKU-%03d-B".formatted(UNIQUE.get()));
        return productUuid;
    }

    /**
     * {@code operatorId} rather than the literal 1 these seeds used to carry.
     *
     * <p>Before V109 there was no {@code users} table and every {@code opened_by} / {@code
     * created_by} column held a placeholder. Now they are foreign keys, so the id has to name
     * somebody who exists — and "whichever user another test class happened to commit first"
     * only works while the class execution order holds.
     */
    private record Fixture(long tenantId, String branchCode, UUID productUuid, long operatorId) {}
}
