package com.lumora.pos.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.stock.GoodsReceiptService.ReceiptLine;
import com.lumora.pos.stock.GoodsReceiptService.ReceiptRow;
import com.lumora.pos.stock.SupplierService.SupplierRow;
import com.lumora.pos.testfixtures.ShopFixture;
import com.lumora.pos.testfixtures.ShopFixture.Shop;
import com.lumora.pos.user.Role;
import com.lumora.pos.user.UserService;
import com.lumora.pos.user.UserService.Operator;
import com.lumora.pos.web.RejectedException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppliers and goods received (M3-04).
 *
 * <p>The assertion that matters most is the one about stock on hand being {@code Σ movements}: this
 * is the first task that could plausibly have incremented a level instead, and the test that proves
 * it did not is the same test that will fail the day somebody adds a {@code quantity_on_hand}
 * column and starts maintaining it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
@Transactional
class GoodsReceiptTest {

    @Autowired GoodsReceiptService receipts;
    @Autowired SupplierService suppliers;
    @Autowired UserService users;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Operator manager(Shop shop) {
        return users.authenticate(shop.tenantId(), ShopFixture.MANAGER_CODE, ShopFixture.MANAGER_PIN);
    }

    private SupplierRow supplier(Shop shop) {
        return suppliers.create(shop.tenantId(), UUID.randomUUID(), unique("Keells"), "071 555 0000");
    }

    private ReceiptRow receive(Shop shop, SupplierRow from, String reference, ReceiptLine... lines) {
        return receipts.receive(
                shop.tenantId(),
                shop.branchCode(),
                UUID.randomUUID(),
                from.id(),
                reference,
                null,
                List.of(lines),
                manager(shop));
    }

    /** On hand, computed the only way this schema allows. */
    private int onHand(Shop shop, UUID productClientUuid) {
        Integer sum =
                jdbc.queryForObject(
                        """
                        SELECT COALESCE(sum(m.qty_delta), 0)
                          FROM stock_movements m
                          JOIN products p ON p.id = m.product_id
                         WHERE m.tenant_id = ? AND m.branch_id = ? AND p.client_uuid = ?
                        """,
                        Integer.class,
                        shop.tenantId(),
                        shop.branchId(),
                        productClientUuid);
        return sum == null ? 0 : sum;
    }

    // ------------------------------------------------------------------- the happy path

    /**
     * The first movement in this system that puts stock <em>on</em> a shelf.
     *
     * <p>Everything before it took stock off — SALE in M1, RETURN in M2 — so this is the first time
     * "stock on hand" has had anything true to say.
     */
    @Test
    void aDeliveryAddsStockAsMovementsAndNeverAsALevel() {
        Shop shop = fixtures.seed();
        assertThat(onHand(shop, shop.productUuid())).isZero();

        ReceiptRow receipt =
                receive(shop, supplier(shop), "DN-1001", new ReceiptLine(shop.productUuid(), 24, 30_000));

        assertThat(onHand(shop, shop.productUuid())).isEqualTo(24);
        assertThat(receipt.totalQty()).isEqualTo(24);
        assertThat(receipt.totalCostMinor()).isEqualTo(24 * 30_000L);

        // RECEIVE, positive, and pointing back at the document that caused it.
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT reason FROM stock_movements
                                 WHERE ref_type = 'goods_receipt' AND ref_id = ?
                                """,
                                String.class,
                                receipt.id()))
                .isEqualTo("RECEIVE");
    }

    /**
     * The column that must not exist, asserted rather than assumed.
     *
     * <p>A goods receipt is the single most tempting place in the whole schema to keep a running
     * level, and V100 says not to. This is the test that fails the day somebody adds one.
     */
    @Test
    void thereIsStillNoStockLevelColumnOnProducts() {
        List<String> levelish =
                jdbc.queryForList(
                        """
                        SELECT column_name FROM information_schema.columns
                         WHERE table_name = 'products'
                           AND (column_name LIKE '%on_hand%'
                             OR column_name LIKE '%quantity%'
                             OR column_name LIKE '%stock%')
                        """,
                        String.class);
        assertThat(levelish)
                .as("stock on hand is the sum of stock_movements and is never a stored column")
                .isEmpty();
    }

    /** Two deliveries and a sale reconcile by addition, with no special case anywhere. */
    @Test
    void onHandIsTheSumOfEverythingThatEverMoved() {
        Shop shop = fixtures.seed();
        SupplierRow from = supplier(shop);

        receive(shop, from, "DN-2001", new ReceiptLine(shop.productUuid(), 10, 30_000));
        receive(shop, from, "DN-2002", new ReceiptLine(shop.productUuid(), 5, 31_000));

        // A sale's worth leaving, written the way SaleService writes it.
        jdbc.update(
                """
                INSERT INTO stock_movements (
                    client_uuid, tenant_id, branch_id, product_id, qty_delta, reason, created_by)
                SELECT ?, ?, ?, p.id, -3, 'SALE', ?
                  FROM products p WHERE p.client_uuid = ?
                """,
                UUID.randomUUID(),
                shop.tenantId(),
                shop.branchId(),
                shop.managerId(),
                shop.productUuid());

        assertThat(onHand(shop, shop.productUuid())).isEqualTo(12);
    }

    /** Cost is recorded per line and multiplies out; the shelf price is untouched. */
    @Test
    void costIsRecordedAndNeverBecomesTheShelfPrice() {
        Shop shop = fixtures.seed();
        long priceBefore =
                jdbc.queryForObject(
                        "SELECT price_minor FROM products WHERE client_uuid = ?",
                        Long.class,
                        shop.productUuid());

        ReceiptRow receipt =
                receive(shop, supplier(shop), "DN-3001", new ReceiptLine(shop.productUuid(), 6, 28_000));

        assertThat(receipt.lines()).singleElement().satisfies(line -> {
            assertThat(line.unitCostMinor()).isEqualTo(28_000);
            assertThat(line.lineCostMinor()).isEqualTo(168_000);
        });

        // A delivery that repriced the shelf would be the supplier setting the shop's margin.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT price_minor FROM products WHERE client_uuid = ?",
                                Long.class,
                                shop.productUuid()))
                .isEqualTo(priceBefore);
    }

    // --------------------------------------------------------------------------- sync

    /**
     * The outbox row is written in the same transaction as the movements (§A).
     *
     * <p>A delivery that exists locally without its sync record is stock the cloud never hears
     * about, and the till and the console then disagree about on hand forever after.
     */
    @Test
    void aDeliveryCannotExistWithoutItsSyncRecord() {
        Shop shop = fixtures.seed();
        ReceiptRow receipt =
                receive(shop, supplier(shop), "DN-4001", new ReceiptLine(shop.productUuid(), 8, 25_000));

        // Read through jsonb operators rather than matching the serialised text: Postgres renders
        // jsonb with its own spacing, and an assertion on that is testing the driver.
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT payload -> 'movements' -> 0 ->> 'reason' FROM outbox
                                 WHERE aggregate = 'goods_receipt' AND aggregate_id = ?
                                """,
                                String.class,
                                receipt.clientUuid()))
                .isEqualTo("RECEIVE");

        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT payload -> 'movements' -> 0 ->> 'qtyDelta' FROM outbox
                                 WHERE aggregate = 'goods_receipt' AND aggregate_id = ?
                                """,
                                String.class,
                                receipt.clientUuid()))
                .isEqualTo("8");

        // The movement's uuid travels, because it is the key the cloud upserts on. Minted at the
        // far end instead, a redelivered batch would add the stock again.
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT payload -> 'movements' -> 0 ->> 'clientUuid' FROM outbox
                                 WHERE aggregate = 'goods_receipt' AND aggregate_id = ?
                                """,
                                String.class,
                                receipt.clientUuid()))
                .isEqualTo(
                        jdbc.queryForObject(
                                """
                                SELECT client_uuid::text FROM stock_movements
                                 WHERE ref_type = 'goods_receipt' AND ref_id = ?
                                """,
                                String.class,
                                receipt.id()));
    }

    // ------------------------------------------------------------------------ refusals

    /**
     * The commonest stock error there is: one delivery note keyed in by two people.
     *
     * <p>Nothing on screen suggests anything happened, and every quantity on the note is doubled.
     */
    @Test
    void theSameDeliveryNoteFromOneSupplierCannotBeEnteredTwice() {
        Shop shop = fixtures.seed();
        SupplierRow from = supplier(shop);
        receive(shop, from, "DN-5001", new ReceiptLine(shop.productUuid(), 12, 30_000));

        assertThatThrownBy(
                        () -> receive(shop, from, "DN-5001", new ReceiptLine(shop.productUuid(), 12, 30_000)))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("already booked in");

        assertThat(onHand(shop, shop.productUuid())).isEqualTo(12);
    }

    /** A market purchase has no delivery note, and two of them are not duplicates. */
    @Test
    void deliveriesWithNoReferenceDoNotCollide() {
        Shop shop = fixtures.seed();
        SupplierRow from = supplier(shop);

        receive(shop, from, null, new ReceiptLine(shop.productUuid(), 3, 30_000));
        receive(shop, from, "", new ReceiptLine(shop.productUuid(), 4, 30_000));

        assertThat(onHand(shop, shop.productUuid())).isEqualTo(7);
    }

    /**
     * A retried request is the same delivery, and the answer is the original document.
     *
     * <p>On this screen a double-pressed Save is a doubled shelf, which nothing later in the day
     * would make obvious.
     */
    @Test
    void thesameClientUuidTwiceIsOneDelivery() {
        Shop shop = fixtures.seed();
        SupplierRow from = supplier(shop);
        UUID clientUuid = UUID.randomUUID();

        ReceiptRow first =
                receipts.receive(
                        shop.tenantId(),
                        shop.branchCode(),
                        clientUuid,
                        from.id(),
                        "DN-6001",
                        null,
                        List.of(new ReceiptLine(shop.productUuid(), 9, 30_000)),
                        manager(shop));
        ReceiptRow again =
                receipts.receive(
                        shop.tenantId(),
                        shop.branchCode(),
                        clientUuid,
                        from.id(),
                        "DN-6001",
                        null,
                        List.of(new ReceiptLine(shop.productUuid(), 9, 30_000)),
                        manager(shop));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(onHand(shop, shop.productUuid())).isEqualTo(9);
    }

    @Test
    void oneProductCannotBeOnTheSameDeliveryTwice() {
        Shop shop = fixtures.seed();
        assertThatThrownBy(
                        () ->
                                receive(
                                        shop,
                                        supplier(shop),
                                        "DN-7001",
                                        new ReceiptLine(shop.productUuid(), 5, 30_000),
                                        new ReceiptLine(shop.productUuid(), 3, 30_000)))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("twice");
    }

    /** Taking stock off is an adjustment with a reason (M3-05), not a negative delivery. */
    @Test
    void aNegativeQuantityIsRefusedAndPointsAtAdjustments() {
        Shop shop = fixtures.seed();
        assertThatThrownBy(
                        () -> receive(shop, supplier(shop), "DN-8001", new ReceiptLine(shop.productUuid(), -5, 30_000)))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("stock adjustment");
    }

    @Test
    void anEmptyDeliveryIsRefused() {
        Shop shop = fixtures.seed();
        SupplierRow from = supplier(shop);
        assertThatThrownBy(
                        () ->
                                receipts.receive(
                                        shop.tenantId(),
                                        shop.branchCode(),
                                        UUID.randomUUID(),
                                        from.id(),
                                        "DN-9001",
                                        null,
                                        List.of(),
                                        manager(shop)))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("at least one line");
    }

    @Test
    void aRetiredSupplierCannotDeliver() {
        Shop shop = fixtures.seed();
        SupplierRow from = supplier(shop);
        suppliers.update(shop.tenantId(), from.id(), from.name(), from.contact(), false);

        assertThatThrownBy(
                        () -> receive(shop, from, "DN-9100", new ReceiptLine(shop.productUuid(), 5, 30_000)))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("retired");
    }

    /**
     * Receiving does not need an open shift.
     *
     * <p>Selling does (M2-01) — a sale outside a shift is cash nothing reconciles. Stock is not
     * cash, goods arrive before anyone has counted a float, and a system that refuses the delivery
     * until somebody opens a till is a system people work around.
     */
    @Test
    void aDeliveryIsAcceptedWithNoShiftOpen() {
        Shop shop = fixtures.seed();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM shifts WHERE branch_id = ? AND status = 'OPEN'",
                                Integer.class,
                                shop.branchId()))
                .isZero();

        receive(shop, supplier(shop), "DN-9200", new ReceiptLine(shop.productUuid(), 4, 30_000));
        assertThat(onHand(shop, shop.productUuid())).isEqualTo(4);
    }

    // ------------------------------------------------------------------------ suppliers

    @Test
    void aSupplierNameCannotBeUsedTwiceInAnyCase() {
        Shop shop = fixtures.seed();
        String name = unique("Cargills");
        suppliers.create(shop.tenantId(), UUID.randomUUID(), name, null);

        assertThatThrownBy(
                        () -> suppliers.create(shop.tenantId(), UUID.randomUUID(), name.toUpperCase(), null))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("already a supplier");
    }

    /** The count is what tells an owner whether retiring a supplier loses anything. */
    @Test
    void aSupplierCarriesHowManyDeliveriesCameFromThem() {
        Shop shop = fixtures.seed();
        SupplierRow from = supplier(shop);
        receive(shop, from, "DN-9300", new ReceiptLine(shop.productUuid(), 2, 30_000));
        receive(shop, from, "DN-9301", new ReceiptLine(shop.productUuid(), 2, 30_000));

        assertThat(suppliers.byId(shop.tenantId(), from.id()).receiptCount()).isEqualTo(2);
    }

    /** A cashier holds no MANAGE_STOCK, and the gate is the permission rather than the screen. */
    @Test
    void aCashierMayNotReceiveGoods() {
        assertThat(Role.CASHIER.can(com.lumora.pos.user.Permission.MANAGE_STOCK)).isFalse();
        assertThat(Role.SUPERVISOR.can(com.lumora.pos.user.Permission.MANAGE_STOCK)).isFalse();
        assertThat(Role.MANAGER.can(com.lumora.pos.user.Permission.MANAGE_STOCK)).isTrue();
    }
}
