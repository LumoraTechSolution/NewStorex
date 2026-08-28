package com.lumora.pos.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.customer.CustomerService.CustomerRow;
import com.lumora.pos.customer.CustomerService.CustomerSale;
import com.lumora.pos.sale.CreateSaleRequest;
import com.lumora.pos.sale.SaleResponse;
import com.lumora.pos.sale.SaleService;
import com.lumora.pos.shift.DenominationCount;
import com.lumora.pos.shift.OpenShiftRequest;
import com.lumora.pos.shift.ShiftService;
import com.lumora.pos.testfixtures.ShopFixture;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Basic customer records (M3-11).
 *
 * <p>The interesting cases are all about identity: that one person typed three ways is one row,
 * that a sale can name somebody without any of the money changing, and that nothing is ever
 * deleted. The CRUD itself is not interesting and is covered only where a rule hangs off it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class CustomerTest {

    @Autowired CustomerService customers;
    @Autowired SaleService sales;
    @Autowired ShiftService shifts;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    /** Phone numbers are unique per shop and the shop is shared, so each test gets its own block. */
    private static final AtomicInteger NUMBERS = new AtomicInteger(700_000);

    // ------------------------------------------------------------------------- identity

    /**
     * One person typed three ways is one customer.
     *
     * <p>This is the whole reason the column is normalised. A shop will type {@code 077 123 4567},
     * {@code 0771234567} and {@code 077-123-4567} for the same person across three visits, and a
     * column that keeps them as typed is three rows with a unique index that never fires — which
     * looks like working software until somebody asks what a customer has spent.
     */
    @Test
    void oneNumberTypedThreeWaysIsOneCustomer() {
        ShopFixture.Shop shop = fixtures.seed();
        String digits = nextNumber();
        CustomerRow saved = create(shop, "Nadeeka", spaced(digits));

        assertThat(saved.phone()).isEqualTo(digits);

        assertThatThrownBy(() -> create(shop, "Nadeeka again", digits))
                .hasMessageContaining("already on file");
        assertThatThrownBy(() -> create(shop, "Nadeeka once more", hyphenated(digits)))
                .hasMessageContaining("already on file");
    }

    /**
     * Several customers may have no number at all.
     *
     * <p>The unique index is partial for exactly this: a walk-in who wants their name on an invoice
     * for the office is a real customer with nothing to look them up by. A plain unique index would
     * allow one such customer per shop, and the workaround staff would find is typing 0000000 —
     * which then collides on the second one.
     */
    @Test
    void moreThanOneCustomerMayHaveNoNumber() {
        ShopFixture.Shop shop = fixtures.seed();

        CustomerRow first = create(shop, "Walk-in A", null);
        CustomerRow second = create(shop, "Walk-in B", "   ");

        assertThat(first.phone()).isNull();
        assertThat(second.phone()).isNull();
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    /** Creating the same customer twice — a retry, a double-pressed key — is one customer. */
    @Test
    void theSameClientUuidIsTheSameCustomer() {
        ShopFixture.Shop shop = fixtures.seed();
        UUID uuid = UUID.randomUUID();
        String digits = nextNumber();

        CustomerRow first =
                customers.create(shop.tenantId(), uuid, "Retry", digits, null, null, shop.managerId());
        CustomerRow retry =
                customers.create(shop.tenantId(), uuid, "Retry", digits, null, null, shop.managerId());

        assertThat(retry.id()).isEqualTo(first.id());
    }

    @Test
    void aNumberThatIsTooShortToBeOneIsRefused() {
        ShopFixture.Shop shop = fixtures.seed();

        assertThatThrownBy(() -> create(shop, "Typo", "0771"))
                .hasMessageContaining("6 to 15 digits");
    }

    @Test
    void aCustomerNeedsAName() {
        ShopFixture.Shop shop = fixtures.seed();

        assertThatThrownBy(() -> create(shop, "   ", nextNumber()))
                .hasMessageContaining("needs a name");
    }

    // --------------------------------------------------------------------------- search

    /**
     * One box, two searches: digits are a phone prefix, anything else is a name.
     *
     * <p>Decided by what was typed rather than by a second field, because the cashier holding a
     * queue has one thing in their head — either the number or the name.
     */
    @Test
    void digitsSearchTheNumberAndLettersSearchTheName() {
        ShopFixture.Shop shop = fixtures.seed();
        String digits = nextNumber();
        CustomerRow saved = create(shop, "Chaminda Silva " + digits, digits);

        assertThat(idsOf(customers.search(shop.tenantId(), digits.substring(0, 6), false, 25)))
                .contains(saved.id());
        assertThat(idsOf(customers.search(shop.tenantId(), "chaminda silva " + digits, false, 25)))
                .contains(saved.id());
    }

    /**
     * A deactivated customer is off the till's list, and still on the back office's when asked for.
     *
     * <p>Offering a deactivated customer at the till would make deactivation a suggestion; hiding
     * them from the back office too would make reinstating one impossible.
     */
    @Test
    void aDeactivatedCustomerLeavesTheTillsListButNotTheShops() {
        ShopFixture.Shop shop = fixtures.seed();
        String digits = nextNumber();
        CustomerRow saved = create(shop, "Left town", digits);

        customers.setActive(shop.tenantId(), saved.id(), false);

        assertThat(idsOf(customers.search(shop.tenantId(), digits, false, 25))).isEmpty();
        assertThat(idsOf(customers.search(shop.tenantId(), digits, true, 25))).contains(saved.id());
    }

    // ----------------------------------------------------------------------- on the sale

    /**
     * A sale can name a customer, and naming one changes none of the money.
     *
     * <p>Asserted rather than assumed, because "attaching a customer must not reprice" is the kind
     * of rule that is true until somebody adds a discount to it. The totals are compared against
     * the same basket rung up anonymously.
     */
    @Test
    void aSaleCanNameACustomerAndTheMoneyIsUnchanged() {
        ShopFixture.Shop shop = openShop();
        CustomerRow customer = create(shop, "Regular", nextNumber());

        SaleResponse anonymous = ringUp(shop, null);
        SaleResponse named = ringUp(shop, customer.clientUuid());

        assertThat(named.totalMinor()).isEqualTo(anonymous.totalMinor());
        assertThat(customerIdOf(named)).isEqualTo(customer.id());
        assertThat(customerIdOf(anonymous)).isNull();
    }

    /** A till whose list is a few minutes stale cannot attach somebody the shop has deactivated. */
    @Test
    void aDeactivatedCustomerCannotBeAttachedToASale() {
        ShopFixture.Shop shop = openShop();
        CustomerRow customer = create(shop, "Gone", nextNumber());
        customers.setActive(shop.tenantId(), customer.id(), false);

        assertThatThrownBy(() -> ringUp(shop, customer.clientUuid()))
                .hasMessageContaining("no longer active");
    }

    /** A uuid this shop has never seen is refused rather than quietly dropped. */
    @Test
    void anUnknownCustomerIsRefusedRatherThanIgnored() {
        ShopFixture.Shop shop = openShop();

        assertThatThrownBy(() -> ringUp(shop, UUID.randomUUID()))
                .hasMessageContaining("not on file");
    }

    /**
     * A purchase history is a query over sales, not a list kept on the customer.
     *
     * <p>A stored list would be a second copy of a fact {@code sales} already holds, and one write
     * path forgetting it is two screens disagreeing about somebody standing at the counter.
     */
    @Test
    void theHistoryIsWhateverTheSalesTableSays() {
        ShopFixture.Shop shop = openShop();
        CustomerRow customer = create(shop, "Buys weekly", nextNumber());

        assertThat(customers.history(shop.tenantId(), customer.id(), 50)).isEmpty();

        ringUp(shop, customer.clientUuid());
        ringUp(shop, customer.clientUuid());

        List<CustomerSale> history = customers.history(shop.tenantId(), customer.id(), 50);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).invoiceNumber()).isNotBlank();

        // And the counts on the row itself come from the same place.
        CustomerRow reread = customers.byId(shop.tenantId(), customer.id());
        assertThat(reread.saleCount()).isEqualTo(2);
        assertThat(reread.spentMinor()).isEqualTo(2 * 45_000);
        assertThat(reread.lastSeenAt()).isNotNull();
    }

    /** Somebody who has never bought anything is a row of zeros, not a missing row. */
    @Test
    void aNewCustomerHasZerosRatherThanNothing() {
        ShopFixture.Shop shop = fixtures.seed();
        CustomerRow customer = create(shop, "Just signed up", nextNumber());

        assertThat(customer.saleCount()).isZero();
        assertThat(customer.spentMinor()).isZero();
        assertThat(customer.lastSeenAt()).isNull();
    }

    /**
     * There is no delete, and the sale that names somebody survives their leaving.
     *
     * <p>An invoice that used to name a customer and now names nobody is a worse record than one
     * naming somebody who has gone. Erasure under PDPA (M5-10) is a deliberate act that has to
     * decide what happens to the invoices — not something a delete button does by accident.
     */
    @Test
    void deactivatingKeepsTheSalesThatNameThem() {
        ShopFixture.Shop shop = openShop();
        CustomerRow customer = create(shop, "Moving away", nextNumber());
        SaleResponse sale = ringUp(shop, customer.clientUuid());

        customers.setActive(shop.tenantId(), customer.id(), false);

        assertThat(customerIdOf(sale)).isEqualTo(customer.id());
        assertThat(customers.byId(shop.tenantId(), customer.id()).active()).isFalse();
        assertThat(customers.history(shop.tenantId(), customer.id(), 50)).hasSize(1);
    }

    // ------------------------------------------------------------------------- helpers

    private static String nextNumber() {
        return "07" + NUMBERS.incrementAndGet();
    }

    private static String spaced(String digits) {
        return digits.substring(0, 3) + " " + digits.substring(3, 6) + " " + digits.substring(6);
    }

    private static String hyphenated(String digits) {
        return digits.substring(0, 3) + "-" + digits.substring(3);
    }

    private CustomerRow create(ShopFixture.Shop shop, String name, String phone) {
        return customers.create(
                shop.tenantId(), UUID.randomUUID(), name, phone, null, null, shop.managerId());
    }

    private static List<Long> idsOf(List<CustomerRow> rows) {
        return rows.stream().map(CustomerRow::id).toList();
    }

    private Long customerIdOf(SaleResponse sale) {
        return jdbc.queryForObject(
                "SELECT customer_id FROM sales WHERE id = ?", Long.class, sale.id());
    }

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

    /** One unit of the fixture's tea, settled in cash, optionally for somebody. */
    private SaleResponse ringUp(ShopFixture.Shop shop, UUID customerClientUuid) {
        long total = 45_000;
        long tax = total * 1800 / 11800;
        long rounding = Math.floorDiv(total + 50, 100) * 100 - total;

        return sales.commit(
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
                        List.of(
                                new CreateSaleRequest.Line(
                                        shop.productUuid(), 1, total, 0L, tax, total)),
                        rounding,
                        0L,
                        List.of(new CreateSaleRequest.Tender("CASH", total + rounding)),
                        customerClientUuid));
    }
}
