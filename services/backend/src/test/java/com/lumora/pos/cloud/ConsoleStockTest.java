package com.lumora.pos.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import com.lumora.pos.cloud.ConsoleStockService.StockLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Stock, seen from somewhere else (M6-12).
 *
 * <p>Two things are being asserted and only one of them is the query. The first is that on hand is
 * <b>Σ movements</b> here exactly as it is on the till, because the moment the two tiers compute a
 * level differently an owner and a shopkeeper read different numbers off two screens and neither can
 * say which is wrong. The second is that NULL and 0 stay different instructions — an unwatched
 * product is never listed however low it goes, and that is the feature rather than a gap.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"cloud", "test"})
@TestPropertySource(
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5444/lumora_test_cloud")
class ConsoleStockTest {

    @Autowired ConsoleStockService stock;
    @Autowired TenantCredentialService tenants;
    @Autowired JdbcTemplate jdbc;

    private long mine;
    private long theirs;

    @BeforeEach
    void twoShops() {
        mine = tenants.provision("Kandy Stores", "Till 1").tenantId();
        theirs = tenants.provision("Galle Stores", "Till 1").tenantId();
    }

    // --------------------------------------------------------------------------- Σ movements

    @Test
    void onHandIsTheSumOfEveryMovement() {
        UUID tea = product(mine, "TEA", "Ceylon tea 400g", 12);
        received(mine, tea, 24);
        sold(mine, tea, -3);
        sold(mine, tea, -1);

        assertThat(only(stock.onHand(mine, "tea", 50)).onHand()).isEqualTo(20);
    }

    @Test
    void aProductWithNoMovementsIsZeroRatherThanAbsent() {
        product(mine, "NEW", "Just added", 5);

        StockLine line = only(stock.onHand(mine, "Just added", 50));

        assertThat(line.onHand()).isZero();
        assertThat(line.reorderPoint()).isEqualTo(5);
    }

    /**
     * A negative is shown rather than clamped.
     *
     * <p>It means a sale was rung up for stock the shop never recorded receiving, which is a real
     * thing to go and look at. Clamping to zero would hide the only evidence of it.
     */
    @Test
    void aNegativeOnHandIsReportedAsItIs() {
        UUID ghost = product(mine, "GHOST", "Never received", 0);
        sold(mine, ghost, -2);

        assertThat(only(stock.onHand(mine, "Never received", 50)).onHand()).isEqualTo(-2);
    }

    // ------------------------------------------------------------------------ blank versus zero

    @Test
    void anUnwatchedProductIsNeverLowHoweverEmpty() {
        UUID unwatched = product(mine, "UNW", "Not watched", null);
        sold(mine, unwatched, -5);

        assertThat(stock.lowStock(mine, 50)).noneMatch(line -> line.sku().equals("UNW"));
    }

    /** Zero is a real threshold: tell me when it is empty. */
    @Test
    void aZeroThresholdIsWatchedAndFiresAtEmpty() {
        UUID watched = product(mine, "ZERO", "Watched at zero", 0);
        received(mine, watched, 2);

        assertThat(stock.lowStock(mine, 50)).noneMatch(line -> line.sku().equals("ZERO"));

        sold(mine, watched, -2);

        assertThat(stock.lowStock(mine, 50)).anyMatch(line -> line.sku().equals("ZERO"));
    }

    @Test
    void aProductAtItsThresholdIsAlreadyLow() {
        UUID rice = product(mine, "RICE", "Rice 5kg", 4);
        received(mine, rice, 4);

        assertThat(stock.lowStock(mine, 50)).anyMatch(line -> line.sku().equals("RICE"));
    }

    /** Most urgent first: the gap below the threshold, not the level. */
    @Test
    void theWorstGapIsListedFirst() {
        UUID slightly = product(mine, "SLIGHT", "Slightly low", 10);
        received(mine, slightly, 9);
        UUID badly = product(mine, "BADLY", "Badly low", 10);
        received(mine, badly, 1);

        List<StockLine> low = stock.lowStock(mine, 50);

        assertThat(low.get(0).sku()).isEqualTo("BADLY");
        assertThat(skus(low)).containsExactly("BADLY", "SLIGHT");
    }

    @Test
    void aDeactivatedProductIsNotChasedForReordering() {
        UUID gone = product(mine, "GONE", "Discontinued", 5);
        jdbc.update("UPDATE products SET active = false WHERE client_uuid = ?", gone);

        assertThat(stock.lowStock(mine, 50)).noneMatch(line -> line.sku().equals("GONE"));
        assertThat(stock.onHand(mine, "Discontinued", 50)).isEmpty();
    }

    // ------------------------------------------------------------------------------- searching

    @Test
    void theSearchMatchesNameOrSkuAndIgnoresCase() {
        product(mine, "CEY-400", "Ceylon tea 400g", null);

        assertThat(skus(stock.onHand(mine, "ceylon", 50))).contains("CEY-400");
        assertThat(skus(stock.onHand(mine, "CEY-4", 50))).contains("CEY-400");
        assertThat(stock.onHand(mine, "nothing like this", 50)).isEmpty();
    }

    // ------------------------------------------------------------------------------ isolation

    /** One shop's shelves are never in another owner's answer. */
    @Test
    void neitherListEverReachesAnotherShop() {
        UUID theirTea = product(theirs, "THEIRS", "Their tea", 5);
        sold(theirs, theirTea, -5);

        assertThat(stock.lowStock(mine, 50)).noneMatch(line -> line.sku().equals("THEIRS"));
        assertThat(stock.onHand(mine, "Their tea", 50)).isEmpty();
        assertThat(skus(stock.lowStock(theirs, 50))).contains("THEIRS");
    }

    /**
     * And a movement belonging to another shop never lands in this shop's total.
     *
     * <p>The subquery that sums movements is filtered by tenant as well as the outer query. Without
     * that, two shops selling products with the same client_uuid — which cannot happen today and is
     * one schema change away from happening — would pool their stock.
     */
    @Test
    void anotherShopsMovementsNeverCountTowardsThisShopsTotal() {
        UUID tea = product(mine, "TEA", "Ceylon tea 400g", 12);
        received(mine, tea, 10);
        // The same product uuid, delivered by the other shop. Contrived, and the point.
        jdbc.update(
                """
                INSERT INTO stock_movements
                    (client_uuid, tenant_id, branch_code, product_client_uuid, qty_delta, reason, occurred_at)
                VALUES (?, ?, 'GAL', ?, 999, 'GOODS_RECEIPT', now())
                """,
                UUID.randomUUID(),
                theirs,
                tea);

        assertThat(only(stock.onHand(mine, "tea", 50)).onHand()).isEqualTo(10);
    }

    // -------------------------------------------------------------------------------- helpers

    private UUID product(long tenantId, String sku, String name, Integer reorderPoint) {
        UUID clientUuid = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO products
                    (client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp,
                     category, active, reorder_point)
                VALUES (?, ?, ?, ?, 45000, 'INCLUSIVE', 1800, 'Grocery', true, ?)
                """,
                clientUuid,
                tenantId,
                sku,
                name,
                reorderPoint);
        return clientUuid;
    }

    private void received(long tenantId, UUID product, int qty) {
        movement(tenantId, product, qty, "GOODS_RECEIPT");
    }

    private void sold(long tenantId, UUID product, int qtyDelta) {
        movement(tenantId, product, qtyDelta, "SALE");
    }

    private void movement(long tenantId, UUID product, int qtyDelta, String reason) {
        jdbc.update(
                """
                INSERT INTO stock_movements
                    (client_uuid, tenant_id, branch_code, product_client_uuid, qty_delta, reason, occurred_at)
                VALUES (?, ?, 'KND', ?, ?, ?, now())
                """,
                UUID.randomUUID(),
                tenantId,
                product,
                qtyDelta,
                reason);
    }

    private static StockLine only(List<StockLine> lines) {
        assertThat(lines).hasSize(1);
        return lines.get(0);
    }

    private static List<String> skus(List<StockLine> lines) {
        return lines.stream().map(StockLine::sku).toList();
    }
}
