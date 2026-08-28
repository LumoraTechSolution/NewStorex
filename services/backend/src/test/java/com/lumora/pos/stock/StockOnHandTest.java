package com.lumora.pos.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.lumora.pos.stock.StockOnHandService.OnHandRow;
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
 * Stock on hand (M3-07).
 *
 * <p>Two claims are worth testing and they pull in opposite directions. The figure must be the sum
 * of the movements with nothing cached in between — so it cannot be stale, and there is no refresh
 * anybody can forget. And it must be fast enough to put on a screen — so the plan is asserted, not
 * assumed, because "we added an index" and "the query uses it" are different statements and only
 * the second one helps.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
@Transactional
class StockOnHandTest {

    @Autowired StockOnHandService onHand;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    /** Writes a movement the way the sale path does, without going through a service. */
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

    /** An ADJUST needs a reason code — V112's CHECK refuses one without. */
    private void adjust(Shop shop, UUID productUuid, int qtyDelta, String reasonCode) {
        jdbc.update(
                """
                INSERT INTO stock_movements (
                    client_uuid, tenant_id, branch_id, product_id,
                    qty_delta, reason, reason_code, created_by)
                SELECT ?, ?, ?, p.id, ?, 'ADJUST', ?, ?
                  FROM products p WHERE p.client_uuid = ?
                """,
                UUID.randomUUID(),
                shop.tenantId(),
                shop.branchId(),
                qtyDelta,
                reasonCode,
                shop.managerId(),
                productUuid);
    }

    // -------------------------------------------------------------------------- the sum

    /** Every kind of movement counts, and none of them is a special case. */
    @Test
    void onHandIsTheSumOfEveryKindOfMovement() {
        Shop shop = fixtures.seed();

        move(shop, shop.productUuid(), 24, "RECEIVE");
        move(shop, shop.productUuid(), -3, "SALE");
        move(shop, shop.productUuid(), 1, "RETURN");
        adjust(shop, shop.productUuid(), -2, "DAMAGED");
        move(shop, shop.productUuid(), -4, "STOCKTAKE");

        assertThat(onHand.onHand(shop.tenantId(), shop.branchId(), shop.productUuid())).isEqualTo(16);
    }

    /**
     * There is no cache, so there is nothing to invalidate.
     *
     * <p>The figure changes the instant a movement lands, with no refresh step in between. That is
     * the property a materialised view or a summary table would have cost, and the reason neither
     * was used.
     */
    @Test
    void theFigureFollowsAMovementImmediatelyWithNoRefreshStep() {
        Shop shop = fixtures.seed();
        move(shop, shop.productUuid(), 10, "RECEIVE");
        assertThat(onHand.onHand(shop.tenantId(), shop.branchId(), shop.productUuid())).isEqualTo(10);

        move(shop, shop.productUuid(), -4, "SALE");
        assertThat(onHand.onHand(shop.tenantId(), shop.branchId(), shop.productUuid())).isEqualTo(6);
    }

    /** A product that never moved is zero here and has no last-moved date. */
    @Test
    void aProductThatNeverMovedIsZeroRatherThanMissing() {
        Shop shop = fixtures.seed();

        OnHandRow row = rowFor(shop, shop.productUuid());
        assertThat(row.qtyOnHand()).isZero();
        assertThat(row.lastMovedAt())
                .as("never moved is a different fact from moved to zero")
                .isNull();
    }

    /** Moved to zero is not the same as never moved, and the list can tell them apart. */
    @Test
    void movedToZeroIsDistinguishableFromNeverMoved() {
        Shop shop = fixtures.seed();
        move(shop, shop.productUuid(), 5, "RECEIVE");
        move(shop, shop.productUuid(), -5, "SALE");

        OnHandRow moved = rowFor(shop, shop.productUuid());
        assertThat(moved.qtyOnHand()).isZero();
        assertThat(moved.lastMovedAt()).isNotNull();

        assertThat(rowFor(shop, shop.exemptUuid()).lastMovedAt()).isNull();
    }

    /** Negative is a real state and is not clamped — it is what a stocktake exists to find. */
    @Test
    void onHandMayBeNegative() {
        Shop shop = fixtures.seed();
        move(shop, shop.productUuid(), -2, "SALE");

        assertThat(onHand.onHand(shop.tenantId(), shop.branchId(), shop.productUuid())).isEqualTo(-2);
    }

    /** Branches do not bleed into each other, which is what makes the figure per-shelf. */
    @Test
    void aMovementAtAnotherBranchDoesNotCountHere() {
        Shop here = fixtures.seed();
        Shop elsewhere = fixtures.seed();

        move(here, here.productUuid(), 7, "RECEIVE");
        move(elsewhere, here.productUuid(), 100, "RECEIVE");

        assertThat(onHand.onHand(here.tenantId(), here.branchId(), here.productUuid())).isEqualTo(7);
    }

    // -------------------------------------------------------------------------- the list

    /**
     * The rows a shopkeeper needs come first.
     *
     * <p>Below zero, then out of stock, then everything else. Somebody opening this screen is
     * looking for a problem, and making them scroll an alphabetical list to find one is making them
     * not look.
     */
    @Test
    void theListPutsTheProblemsFirst() {
        Shop shop = fixtures.seed();
        UUID neverMoved = seedProduct(shop, "ZZZ-UNTOUCHED", "Zinc Bucket");

        move(shop, shop.productUuid(), -3, "SALE"); // below zero
        move(shop, shop.exemptUuid(), 10, "RECEIVE"); // healthy
        // neverMoved is left alone, so it sits at zero — and its name sorts last alphabetically,
        // which is what makes the assertion below about the ordering rather than about the alphabet.

        List<OnHandRow> rows = onHand.all(shop.tenantId(), shop.branchId(), false);

        assertThat(rows.get(0).productClientUuid())
                .as("the shelf that is below zero is the first thing anybody needs to see")
                .isEqualTo(shop.productUuid());

        int outOfStock = indexOfFirst(rows, row -> row.productClientUuid().equals(neverMoved));
        int healthy = indexOfFirst(rows, row -> row.productClientUuid().equals(shop.exemptUuid()));
        assertThat(outOfStock)
                .as("out of stock outranks in stock, even against the alphabet")
                .isLessThan(healthy);
    }

    private UUID seedProduct(Shop shop, String sku, String name) {
        UUID uuid = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO products (client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp)
                VALUES (?, ?, ?, ?, 10000, 'INCLUSIVE', 0)
                """,
                uuid,
                shop.tenantId(),
                sku + "-" + UUID.randomUUID().toString().substring(0, 6),
                name);
        return uuid;
    }

    private static int indexOfFirst(
            List<OnHandRow> rows, java.util.function.Predicate<OnHandRow> match) {
        for (int i = 0; i < rows.size(); i++) {
            if (match.test(rows.get(i))) {
                return i;
            }
        }
        throw new AssertionError("no row matched");
    }

    @Test
    void theSummaryCountsWhatIsWrong() {
        Shop shop = fixtures.seed();
        move(shop, shop.productUuid(), -3, "SALE");
        move(shop, shop.exemptUuid(), 10, "RECEIVE");

        List<OnHandRow> rows = onHand.all(shop.tenantId(), shop.branchId(), false);
        StockOnHandService.OnHandSummary summary = onHand.summarise(rows);

        assertThat(summary.belowZero()).isEqualTo(1);
        assertThat(summary.products()).isEqualTo(rows.size());
    }

    /** A discontinued product is off the list unless somebody asks — it still has stock to clear. */
    @Test
    void discontinuedProductsAreHiddenUnlessAskedFor() {
        Shop shop = fixtures.seed();
        move(shop, shop.productUuid(), 5, "RECEIVE");
        jdbc.update(
                "UPDATE products SET active = false WHERE client_uuid = ?", shop.productUuid());

        assertThat(onHand.all(shop.tenantId(), shop.branchId(), false))
                .extracting(OnHandRow::productClientUuid)
                .doesNotContain(shop.productUuid());

        assertThat(onHand.all(shop.tenantId(), shop.branchId(), true))
                .extracting(OnHandRow::productClientUuid)
                .contains(shop.productUuid());
    }

    // --------------------------------------------------------------------------- speed

    /**
     * The index is actually used, which is a different claim from having created it.
     *
     * <p><b>Getting this test honest took two attempts and both failures were the planner being
     * right.</b> First against 500 movements: Postgres scans a table that fits in two pages because
     * that genuinely is cheaper. Then against 20,000 movements <em>all belonging to one product</em>:
     * still a scan, and still correct — every row matched the filter, so an index adds work. What
     * the index is actually for is the shape a shop really has, which is many products with a few
     * dozen movements each. Twenty thousand movements spread over four hundred products is roughly a
     * year of a small shop, and there the index wins by two orders of magnitude.
     *
     * <p><b>Why it does not assert "Index Only Scan".</b> That needs the visibility map, which is
     * set by VACUUM — and these rows live in a transaction that is rolled back, so they have never
     * been vacuumed and never will be. On a real till, with committed movements and autovacuum done,
     * the same query is index-only. What is asserted here is the part that would actually regress:
     * that the covering index is reached at all rather than the table being read.
     */
    @Test
    void summingOnHandUsesTheIndexRatherThanScanningTheTable() {
        Shop shop = fixtures.seed();
        String prefix = "PERF-" + UUID.randomUUID().toString().substring(0, 8);

        jdbc.update(
                """
                INSERT INTO products (client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp)
                SELECT gen_random_uuid(), ?, ? || '-' || g, 'Perf product ' || g, 10000, 'INCLUSIVE', 0
                  FROM generate_series(1, 400) g
                """,
                shop.tenantId(),
                prefix);

        jdbc.update(
                """
                INSERT INTO stock_movements (
                    client_uuid, tenant_id, branch_id, product_id, qty_delta, reason, created_by)
                SELECT gen_random_uuid(), ?, ?, p.id, 1, 'SALE', ?
                  FROM products p, generate_series(1, 50)
                 WHERE p.tenant_id = ? AND p.sku LIKE ? || '-%'
                """,
                shop.tenantId(),
                shop.branchId(),
                shop.managerId(),
                shop.tenantId(),
                prefix);
        jdbc.execute("ANALYZE stock_movements");

        String plan =
                String.join(
                        "\n",
                        jdbc.queryForList(
                                """
                                EXPLAIN SELECT sum(qty_delta) FROM stock_movements
                                 WHERE tenant_id = ? AND branch_id = ? AND product_id =
                                       (SELECT id FROM products WHERE client_uuid = ?)
                                """,
                                String.class,
                                shop.tenantId(),
                                shop.branchId(),
                                shop.productUuid()));

        assertThat(plan)
                .as("summing on hand should reach the movements through the covering index:%n%s", plan)
                .contains("ix_stock_movements_on_hand")
                .doesNotContain("Seq Scan on stock_movements");
    }

    /**
     * The index carries {@code qty_delta}, which is what makes the scan index-only on a real till.
     *
     * <p>Asserted from the index definition rather than from a plan, because the plan cannot show
     * it here (see above) and this is the part a careless migration would actually break: reordering
     * the key columns or dropping the INCLUDE leaves a query that still works and quietly reads the
     * heap for every movement.
     */
    @Test
    void theIndexCoversTheQuantityColumn() {
        String definition =
                jdbc.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_stock_movements_on_hand'",
                        String.class);

        assertThat(definition)
                .contains("(tenant_id, branch_id, product_id)")
                .contains("INCLUDE (qty_delta)");
    }

    /**
     * V100's index is gone rather than left beside its replacement.
     *
     * <p>Every line of every sale writes a movement, so a redundant index is a cost paid on the one
     * path that must never slow down.
     */
    @Test
    void theSupersededIndexIsGone() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT indexname FROM pg_indexes WHERE tablename = 'stock_movements'",
                                String.class))
                .contains("ix_stock_movements_on_hand")
                .doesNotContain("ix_stock_movements_product");
    }

    // ------------------------------------------------------------------------- helpers

    private OnHandRow rowFor(Shop shop, UUID productUuid) {
        return onHand.all(shop.tenantId(), shop.branchId(), true).stream()
                .filter(row -> row.productClientUuid().equals(productUuid))
                .findFirst()
                .orElseThrow(() -> new AssertionError("product missing from the on-hand list"));
    }
}
