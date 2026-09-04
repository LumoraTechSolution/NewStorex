package com.lumora.pos.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.customer.CustomerService.CustomerRow;
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
 * Export and erasure (M5-10, PDPA No. 9 of 2022).
 *
 * <p>Almost every assertion here is about what <em>survives</em>. Erasure is easy to write and easy
 * to get catastrophically wrong in one of two opposite directions: destroy too little and the shop
 * is liable, destroy too much and the shop's own accounts stop reconciling and an already-printed
 * Z-report becomes a lie. So the tests ring up real sales first, and then check the money is still
 * there and the person is not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
class CustomerPrivacyTest {

    @Autowired CustomerService customers;
    @Autowired CustomerPrivacyService privacy;
    @Autowired SaleService sales;
    @Autowired ShiftService shifts;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    /** Phone numbers are unique per shop and the shop is shared, so each test takes its own. */
    private static final AtomicInteger NUMBERS = new AtomicInteger(800_000);

    // --------------------------------------------------------------------------- the export

    @Test
    void theExportCarriesWhatWasBoughtAndNotJustWhatWasSpent() {
        ShopFixture.Shop shop = openShop();
        CustomerRow nadeeka = create(shop, "Nadeeka Perera", nextNumber());
        ringUp(shop, nadeeka.clientUuid());
        ringUp(shop, nadeeka.clientUuid());

        CustomerPrivacyService.DataExport export = privacy.export(shop.tenantId(), nadeeka.id());

        assertThat(export.customer().name()).isEqualTo("Nadeeka Perera");
        assertThat(export.sales()).hasSize(2);
        // The line items, not a total. The right is to the data, and the data is that this person
        // bought these things.
        assertThat(export.sales().get(0).lines()).isNotEmpty();
        assertThat(export.sales().get(0).lines().get(0).qty()).isEqualTo(1);
        assertThat(export.sales().get(0).invoiceNumber()).isNotBlank();
    }

    @Test
    void theExportOfSomebodyWhoHasBoughtNothingIsStillAnAnswer() {
        ShopFixture.Shop shop = fixtures.seed();
        CustomerRow walkIn = create(shop, "Never came back", nextNumber());

        CustomerPrivacyService.DataExport export = privacy.export(shop.tenantId(), walkIn.id());

        assertThat(export.customer().name()).isEqualTo("Never came back");
        assertThat(export.sales()).isEmpty();
        assertThat(export.refunds()).isEmpty();
        assertThat(export.taxInvoices()).isEmpty();
    }

    /**
     * An export never reaches across shops.
     *
     * <p>This is the most concentrated personal data the system produces. On a till it is one
     * tenant by construction, which is exactly why the assertion is worth having: the day the same
     * service is called from somewhere multi-tenant, this fails rather than leaking.
     */
    @Test
    void anExportCannotBeAskedForUnderTheWrongShop() {
        ShopFixture.Shop shop = openShop();
        CustomerRow nadeeka = create(shop, "Nadeeka Perera", nextNumber());

        assertThatThrownBy(() -> privacy.export(shop.tenantId() + 1, nadeeka.id()))
                .hasMessageContaining("No such customer");
    }

    // -------------------------------------------------------------------------- the erasure

    @Test
    void erasureDestroysEveryPersonalFieldAndKeepsTheRow() {
        ShopFixture.Shop shop = openShop();
        CustomerRow nadeeka =
                customers.create(
                        shop.tenantId(),
                        UUID.randomUUID(),
                        "Nadeeka Perera",
                        nextNumber(),
                        "nadeeka@example.lk",
                        "prefers the small loaf",
                        shop.managerId());

        privacy.erase(shop.tenantId(), nadeeka.id(), shop.managerId());

        CustomerRow after = customers.byId(shop.tenantId(), nadeeka.id());
        assertThat(after.id()).isEqualTo(nadeeka.id());
        assertThat(after.clientUuid()).isEqualTo(nadeeka.clientUuid());
        assertThat(after.name()).isEqualTo(CustomerPrivacyService.ERASED_NAME);
        assertThat(after.phone()).isNull();
        assertThat(after.email()).isNull();
        assertThat(after.note()).isNull();
        assertThat(after.active()).isFalse();
        assertThat(after.erasedAt()).isNotNull();

        // Nothing of the person is left in the row itself, checked against the columns rather than
        // through the service that just wrote them.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT tin IS NULL AND address IS NULL FROM customers WHERE id = ?",
                                Boolean.class,
                                nadeeka.id()))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT erased_by FROM customers WHERE id = ?", Long.class, nadeeka.id()))
                .isEqualTo(shop.managerId());
    }

    /**
     * The assertion that stops this feature from being a disaster.
     *
     * <p>A shop's takings must not move because somebody exercised a right. If erasure deleted the
     * row, or cascaded, a Z-report printed last week would stop reconciling — and the shopkeeper
     * would find out during an audit.
     */
    @Test
    void theSalesSurviveTheErasureIntact() {
        ShopFixture.Shop shop = openShop();
        CustomerRow nadeeka = create(shop, "Nadeeka Perera", nextNumber());
        SaleResponse first = ringUp(shop, nadeeka.clientUuid());
        SaleResponse second = ringUp(shop, nadeeka.clientUuid());
        long takingsBefore = takings(shop);

        privacy.erase(shop.tenantId(), nadeeka.id(), shop.managerId());

        assertThat(takings(shop)).isEqualTo(takingsBefore);
        assertThat(customerIdOf(first)).isEqualTo(nadeeka.id());
        assertThat(customerIdOf(second)).isEqualTo(nadeeka.id());
        // And the history is still readable, because the sales are still there.
        assertThat(customers.history(shop.tenantId(), nadeeka.id(), 50)).hasSize(2);
    }

    /**
     * The till must never offer an erased person again, on any of the three search branches.
     *
     * <p>There is no clause in the query doing this: erasure deactivates, and {@code setActive}
     * refuses to undo an erasure, so the active-only search the till uses cannot return one. This
     * asserts that chain rather than the absence of a WHERE — if somebody ever makes an erased row
     * reactivatable, this is what fails.
     */
    @Test
    void anErasedCustomerIsNeverOfferedAtTheTill() {
        ShopFixture.Shop shop = fixtures.seed();
        String number = nextNumber();
        CustomerRow nadeeka = create(shop, "Nadeeka Perera", number);

        privacy.erase(shop.tenantId(), nadeeka.id(), shop.managerId());

        assertThat(idsOf(customers.search(shop.tenantId(), null, false, 200)))
                .doesNotContain(nadeeka.id());
        assertThat(idsOf(customers.search(shop.tenantId(), "Nadeeka", false, 200)))
                .doesNotContain(nadeeka.id());
        assertThat(idsOf(customers.search(shop.tenantId(), number, false, 200)))
                .doesNotContain(nadeeka.id());
    }

    /**
     * The back office can still see that it happened.
     *
     * <p>Hiding the row entirely would leave a shopkeeper unable to answer "did you erase my
     * details", which is the question that follows the request. Nothing personal is on the row any
     * more, so there is nothing to leak by showing it.
     */
    @Test
    void theBackOfficeCanStillSeeThatAnErasureHappened() {
        ShopFixture.Shop shop = fixtures.seed();
        CustomerRow nadeeka = create(shop, "Nadeeka Perera", nextNumber());

        privacy.erase(shop.tenantId(), nadeeka.id(), shop.managerId());

        List<CustomerRow> listed = customers.search(shop.tenantId(), null, true, 200);
        assertThat(idsOf(listed)).contains(nadeeka.id());
        CustomerRow shown =
                listed.stream().filter(r -> r.id() == nadeeka.id()).findFirst().orElseThrow();
        assertThat(shown.name()).isEqualTo(CustomerPrivacyService.ERASED_NAME);
        assertThat(shown.erasedAt()).isNotNull();
        assertThat(shown.phone()).isNull();
    }

    @Test
    void anErasedCustomerCannotBeEditedOrReinstated() {
        ShopFixture.Shop shop = fixtures.seed();
        CustomerRow nadeeka = create(shop, "Nadeeka Perera", nextNumber());
        privacy.erase(shop.tenantId(), nadeeka.id(), shop.managerId());

        assertThatThrownBy(
                        () ->
                                customers.update(
                                        shop.tenantId(), nadeeka.id(), "Nadeeka Perera", "0771234567", null, null))
                .hasMessageContaining("was erased");
        assertThatThrownBy(() -> customers.setActive(shop.tenantId(), nadeeka.id(), true))
                .hasMessageContaining("was erased");
    }

    @Test
    void erasingTwiceIsRefusedRatherThanQuietlyAccepted() {
        ShopFixture.Shop shop = fixtures.seed();
        CustomerRow nadeeka = create(shop, "Nadeeka Perera", nextNumber());
        privacy.erase(shop.tenantId(), nadeeka.id(), shop.managerId());

        assertThatThrownBy(() -> privacy.erase(shop.tenantId(), nadeeka.id(), shop.managerId()))
                .hasMessageContaining("already erased");
    }

    /**
     * An erasure that stops at the shop PC is not an erasure.
     *
     * <p>The cloud holds the name and the number. The outbox row written here is the only thing
     * that will ever overwrite them, and it is written in the same transaction — so a shop cannot
     * tell somebody yes while their number is still up there.
     */
    @Test
    void theErasureIsQueuedForTheCloudWithTheBlankedRow() {
        ShopFixture.Shop shop = fixtures.seed();
        CustomerRow nadeeka = create(shop, "Nadeeka Perera", nextNumber());

        privacy.erase(shop.tenantId(), nadeeka.id(), shop.managerId());

        String payload =
                jdbc.queryForObject(
                        """
                        SELECT payload::text FROM outbox
                         WHERE aggregate = 'customer' AND aggregate_id = ?
                         ORDER BY id DESC LIMIT 1
                        """,
                        String.class,
                        nadeeka.clientUuid());
        assertThat(payload).contains(CustomerPrivacyService.ERASED_NAME);
        assertThat(payload).doesNotContain("Nadeeka");
        assertThat(payload).contains("\"erasedAt\"");
    }

    @Test
    void anErasedPersonCanStillAskWhatIsHeldAboutThem() {
        ShopFixture.Shop shop = openShop();
        CustomerRow nadeeka = create(shop, "Nadeeka Perera", nextNumber());
        ringUp(shop, nadeeka.clientUuid());
        privacy.erase(shop.tenantId(), nadeeka.id(), shop.managerId());

        CustomerPrivacyService.DataExport export = privacy.export(shop.tenantId(), nadeeka.id());

        assertThat(export.customer().name()).isEqualTo(CustomerPrivacyService.ERASED_NAME);
        assertThat(export.customer().phone()).isNull();
        assertThat(export.tin()).isNull();
        assertThat(export.address()).isNull();
        // The purchases are still listed, because they are still held — anonymously, and honestly.
        assertThat(export.sales()).hasSize(1);
    }

    // ------------------------------------------------------------------------------ helpers

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

    private long takings(ShopFixture.Shop shop) {
        Long total =
                jdbc.queryForObject(
                        "SELECT COALESCE(sum(total_minor), 0) FROM sales WHERE tenant_id = ?",
                        Long.class,
                        shop.tenantId());
        return total == null ? 0L : total;
    }

    private static String nextNumber() {
        return "077" + NUMBERS.incrementAndGet();
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

    /** One unit of the fixture's tea, settled in cash, for somebody. */
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
