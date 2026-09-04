package com.lumora.pos.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.lumora.pos.stock.StockOnHandService.LowStockRow;
import com.lumora.pos.testfixtures.ShopFixture;
import com.lumora.pos.testfixtures.ShopFixture.Shop;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the shop is about to run out of (M3-15).
 *
 * <p>Every other report in this system answers a question about the past. This one is the only one
 * about tomorrow, and the thing it has to get right is not the arithmetic — {@code qty <= point} is
 * not hard — but <em>what it stays quiet about</em>. An alert list that fills with rows nobody asked
 * for is one a shopkeeper stops opening, and then the one row that mattered is missed. So most of
 * what is asserted here is silence: the unwatched product, the discontinued line, the product that
 * is merely low rather than low against a threshold somebody set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
@Transactional
class LowStockTest {

    @Autowired StockOnHandService onHand;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    private void move(Shop shop, UUID productUuid, int qtyDelta, String reason) {
        jdbc.update(
                """
                INSERT INTO stock_movements (
                    client_uuid, tenant_id, branch_id, product_id, qty_delta, reason, created_by)
                SELECT ?, ?, ?, p.id, ?, ?, ?
                  FROM products p WHERE p.client_uuid = ?
                """,
                UUID.randomUUID(),
                shop.tenantId(),
                shop.branchId(),
                qtyDelta,
                reason,
                shop.managerId(),
                productUuid);
    }

    private void watch(Shop shop, UUID productUuid, Integer reorderPoint) {
        jdbc.update(
                "UPDATE products SET reorder_point = ? WHERE tenant_id = ? AND client_uuid = ?",
                reorderPoint,
                shop.tenantId(),
                productUuid);
    }

    private List<LowStockRow> low(Shop shop) {
        return onHand.lowStock(shop.tenantId(), shop.branchId());
    }

    // ------------------------------------------------------------------- what it reports

    @Test
    void aWatchedProductBelowItsThresholdIsReported() {
        Shop shop = fixtures.seed();
        watch(shop, shop.productUuid(), 10);
        move(shop, shop.productUuid(), 3, "RECEIVE");

        assertThat(low(shop))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.productClientUuid()).isEqualTo(shop.productUuid());
                            assertThat(row.qtyOnHand()).isEqualTo(3);
                            assertThat(row.reorderPoint()).isEqualTo(10);
                            assertThat(row.shortBy()).isEqualTo(7);
                        });
    }

    /**
     * "Reorder at five" means five is already the moment to reorder, not four.
     *
     * <p>The off-by-one that would make a shopkeeper's own number mean something other than what
     * they wrote — and they would only find out by running out.
     */
    @Test
    void sittingExactlyOnTheThresholdCounts() {
        Shop shop = fixtures.seed();
        watch(shop, shop.productUuid(), 5);
        move(shop, shop.productUuid(), 5, "RECEIVE");

        assertThat(low(shop)).singleElement().satisfies(row -> assertThat(row.shortBy()).isZero());
    }

    /** A shelf that went below zero is the worst case, not an excluded one. */
    @Test
    void aNegativeShelfIsReported() {
        Shop shop = fixtures.seed();
        watch(shop, shop.productUuid(), 4);
        move(shop, shop.productUuid(), -2, "SALE");

        assertThat(low(shop)).singleElement().satisfies(row -> assertThat(row.qtyOnHand()).isEqualTo(-2));
    }

    /**
     * Zero is a real threshold, and the reason the column is nullable rather than defaulted.
     *
     * <p>A shopkeeper who sets 0 is saying "tell me the moment this is empty". If 0 were also the
     * way of saying "not watched", the one product they cared most about would be the one product
     * the screen stayed silent about.
     */
    @Test
    void aThresholdOfZeroIsWatchedAndFiresWhenEmpty() {
        Shop shop = fixtures.seed();
        watch(shop, shop.productUuid(), 0);

        assertThat(low(shop))
                .as("on hand 0, threshold 0 — empty is exactly what was asked about")
                .singleElement()
                .satisfies(row -> assertThat(row.reorderPoint()).isZero());

        move(shop, shop.productUuid(), 1, "RECEIVE");
        assertThat(low(shop)).as("one on the shelf is no longer empty").isEmpty();
    }

    // ----------------------------------------------------------------- what it stays quiet about

    /**
     * The decision the whole screen rests on. An unwatched product never appears, however low.
     *
     * <p>This looks like a gap and is the feature: without it the list fills with things that are
     * legitimately near zero — the slow line, the one-off, the item ordered in for a customer — and
     * a list that is mostly noise is one nobody reads.
     */
    @Test
    void anUnwatchedProductIsNeverReportedHoweverLowItGets() {
        Shop shop = fixtures.seed();
        move(shop, shop.productUuid(), -50, "SALE");

        assertThat(low(shop))
                .as("nobody asked to be told about this product")
                .isEmpty();
    }

    /** Being out of something you no longer sell is not a problem to act on. */
    @Test
    void aDiscontinuedProductIsNotReported() {
        Shop shop = fixtures.seed();
        watch(shop, shop.productUuid(), 10);
        jdbc.update(
                "UPDATE products SET active = false WHERE tenant_id = ? AND client_uuid = ?",
                shop.tenantId(),
                shop.productUuid());

        assertThat(low(shop)).isEmpty();
    }

    @Test
    void aWatchedProductWithPlentyOnTheShelfIsNotReported() {
        Shop shop = fixtures.seed();
        watch(shop, shop.productUuid(), 5);
        move(shop, shop.productUuid(), 40, "RECEIVE");

        assertThat(low(shop)).isEmpty();
    }

    // ------------------------------------------------------------------------ how it sorts

    /**
     * Units sold, not sales made, and only sales.
     *
     * <p>This is what turns a list into an order. Two products both three short are not the same
     * problem if one sells forty a month and the other four — so a delivery booked in must not
     * inflate the figure that decides which one a shopkeeper reorders first.
     */
    @Test
    void unitsSoldCountsSalesAndNotDeliveries() {
        Shop shop = fixtures.seed();
        watch(shop, shop.productUuid(), 10);
        move(shop, shop.productUuid(), 100, "RECEIVE");
        move(shop, shop.productUuid(), -6, "SALE");
        move(shop, shop.productUuid(), -4, "SALE");
        move(shop, shop.productUuid(), -85, "SALE");

        assertThat(low(shop))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.qtyOnHand()).isEqualTo(5);
                            assertThat(row.soldLast30Days())
                                    .as("95 units sold; the 100 received is not a sale")
                                    .isEqualTo(95);
                        });
    }

    /** The most short comes first, because that is the one that runs out first. */
    @Test
    void theShortestIsListedFirst() {
        Shop shop = fixtures.seed();
        watch(shop, shop.productUuid(), 5);
        watch(shop, shop.exemptUuid(), 100);
        move(shop, shop.productUuid(), 4, "RECEIVE");

        assertThat(low(shop))
                .extracting(LowStockRow::productClientUuid)
                .as("exempt is 100 short, product is 1 short")
                .containsExactly(shop.exemptUuid(), shop.productUuid());
    }

    // ------------------------------------------------------------------------ the ground rule

    /** Nothing stores "low". It is a comparison against Σ movements, evaluated when asked (§A). */
    @Test
    void lowIsRecomputedFromMovementsWithNoStoredFlag() {
        Shop shop = fixtures.seed();
        watch(shop, shop.productUuid(), 5);
        move(shop, shop.productUuid(), 2, "RECEIVE");
        assertThat(low(shop)).hasSize(1);

        move(shop, shop.productUuid(), 20, "RECEIVE");
        assertThat(low(shop))
                .as("a delivery lands and the alert clears itself — no refresh step to forget")
                .isEmpty();
    }
}
