package com.lumora.pos.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.stock.GoodsReceiptService.ReceiptLine;
import com.lumora.pos.stock.StocktakeService.StocktakeRow;
import com.lumora.pos.stock.SupplierService.SupplierRow;
import com.lumora.pos.testfixtures.ShopFixture;
import com.lumora.pos.testfixtures.ShopFixture.Shop;
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
 * Counting the shelves (M3-06).
 *
 * <p>Almost everything here is one claim: a stocktake writes the <em>difference</em>. The test that
 * matters most is {@link #salesDuringTheCountAreNotUndoneByCompletingIt} — it is the one that
 * distinguishes "we chose the honest design" from "we chose the correct one", and the one that would
 * fail the day somebody decides setting the level would be simpler.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
@Transactional
class StocktakeTest {

    @Autowired StocktakeService stocktakes;
    @Autowired StockAdjustmentService stock;
    @Autowired GoodsReceiptService receipts;
    @Autowired SupplierService suppliers;
    @Autowired UserService users;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    private Operator manager(Shop shop) {
        return users.authenticate(shop.tenantId(), ShopFixture.MANAGER_CODE, ShopFixture.MANAGER_PIN);
    }

    private StocktakeRow openCount(Shop shop) {
        return stocktakes.open(
                shop.tenantId(), shop.branchCode(), UUID.randomUUID(), null, manager(shop));
    }

    /** Puts a known quantity on the shelf the way a shop would, so on hand starts somewhere real. */
    private void stockUp(Shop shop, UUID productUuid, int qty) {
        SupplierRow from =
                suppliers.create(
                        shop.tenantId(),
                        UUID.randomUUID(),
                        "Supplier-" + UUID.randomUUID().toString().substring(0, 8),
                        null);
        receipts.receive(
                shop.tenantId(),
                shop.branchCode(),
                UUID.randomUUID(),
                from.id(),
                null,
                null,
                List.of(new ReceiptLine(productUuid, qty, 30_000)),
                manager(shop));
    }

    /** A sale's worth of stock leaving, written the way SaleService writes it. */
    private void sell(Shop shop, UUID productUuid, int qty) {
        jdbc.update(
                """
                INSERT INTO stock_movements (
                    client_uuid, tenant_id, branch_id, product_id, qty_delta, reason, created_by)
                SELECT ?, ?, ?, p.id, ?, 'SALE', ?
                  FROM products p WHERE p.client_uuid = ?
                """,
                UUID.randomUUID(),
                shop.tenantId(),
                shop.branchId(),
                -qty,
                shop.managerId(),
                productUuid);
    }

    private int onHand(Shop shop, UUID productUuid) {
        return stock.onHand(shop.tenantId(), shop.branchId(), productUuid);
    }

    // ------------------------------------------------------------------ the whole point

    /**
     * The claim the whole design rests on, and the reason "just set it to what I counted" is wrong
     * rather than merely dishonest.
     *
     * <p>System says 20. Somebody counts 17 — three really are missing. While they are still
     * walking round, two more are sold. Completing the count applies −3 to a system now at 18 and
     * lands on 15, which is exactly what is on the shelf. Overwriting to 17 would have silently
     * undone the two sales.
     */
    @Test
    void salesDuringTheCountAreNotUndoneByCompletingIt() {
        Shop shop = fixtures.seed();
        stockUp(shop, shop.productUuid(), 20);

        StocktakeRow count = openCount(shop);
        stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 17, manager(shop));

        // The shop keeps trading while the counting happens.
        sell(shop, shop.productUuid(), 2);
        assertThat(onHand(shop, shop.productUuid())).isEqualTo(18);

        stocktakes.complete(shop.tenantId(), count.id(), manager(shop));

        assertThat(onHand(shop, shop.productUuid()))
                .as("the difference composes with the sales; overwriting to 17 would not")
                .isEqualTo(15);
    }

    /**
     * The shortfall survives as its own movement.
     *
     * <p>Shrinkage is what the owner is counting for, so it has to be a row somebody can find —
     * not the absence of a row implied by a level that quietly changed.
     */
    @Test
    void theVarianceIsWrittenAsItsOwnStocktakeMovement() {
        Shop shop = fixtures.seed();
        stockUp(shop, shop.productUuid(), 20);

        StocktakeRow count = openCount(shop);
        stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 17, manager(shop));
        StocktakeRow completed = stocktakes.complete(shop.tenantId(), count.id(), manager(shop));

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.netVarianceQty()).isEqualTo(-3);
        assertThat(completed.countedShort()).isEqualTo(1);

        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT qty_delta FROM stock_movements
                                 WHERE reason = 'STOCKTAKE' AND ref_type = 'stocktake' AND ref_id = ?
                                """,
                                Integer.class,
                                count.id()))
                .isEqualTo(-3);
    }

    /** A line that agrees with the system produces no movement — nothing happened. */
    @Test
    void aCountThatMatchesWritesNoMovement() {
        Shop shop = fixtures.seed();
        stockUp(shop, shop.productUuid(), 12);

        StocktakeRow count = openCount(shop);
        stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 12, manager(shop));
        StocktakeRow completed = stocktakes.complete(shop.tenantId(), count.id(), manager(shop));

        assertThat(completed.lineCount()).isEqualTo(1);
        assertThat(completed.netVarianceQty()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM stock_movements WHERE ref_type = 'stocktake' AND ref_id = ?",
                                Integer.class,
                                count.id()))
                .isZero();
        assertThat(onHand(shop, shop.productUuid())).isEqualTo(12);
    }

    /** Finding more than expected is a positive variance, not an error. */
    @Test
    void countingMoreThanExpectedAddsStock() {
        Shop shop = fixtures.seed();
        stockUp(shop, shop.productUuid(), 5);

        StocktakeRow count = openCount(shop);
        stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 8, manager(shop));
        StocktakeRow completed = stocktakes.complete(shop.tenantId(), count.id(), manager(shop));

        assertThat(completed.countedOver()).isEqualTo(1);
        assertThat(onHand(shop, shop.productUuid())).isEqualTo(8);
    }

    /**
     * Counting one shelf must not zero the rest of the shop.
     *
     * <p>Partial counts are the normal case — nobody counts four hundred products in one go — and a
     * design that treated an absent line as "zero found" would empty a shop the first time somebody
     * counted the spirits.
     */
    @Test
    void productsThatWereNotCountedAreLeftAlone() {
        Shop shop = fixtures.seed();
        stockUp(shop, shop.productUuid(), 10);
        stockUp(shop, shop.exemptUuid(), 7);

        StocktakeRow count = openCount(shop);
        stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 9, manager(shop));
        stocktakes.complete(shop.tenantId(), count.id(), manager(shop));

        assertThat(onHand(shop, shop.productUuid())).isEqualTo(9);
        assertThat(onHand(shop, shop.exemptUuid())).as("never counted, never touched").isEqualTo(7);
    }

    // --------------------------------------------------------------------- while open

    /** An open stocktake writes nothing at all. Counting is not adjusting. */
    @Test
    void anOpenStocktakeMovesNoStock() {
        Shop shop = fixtures.seed();
        stockUp(shop, shop.productUuid(), 20);

        StocktakeRow count = openCount(shop);
        stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 3, manager(shop));

        assertThat(onHand(shop, shop.productUuid()))
                .as("nothing moves until the count is completed")
                .isEqualTo(20);
    }

    /** A recount replaces the line, and re-stamps what the system said when the shelf was seen. */
    @Test
    void countingTheSameProductAgainReplacesTheLine() {
        Shop shop = fixtures.seed();
        stockUp(shop, shop.productUuid(), 20);

        StocktakeRow count = openCount(shop);
        stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 5, manager(shop));
        StocktakeRow after = stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 17, manager(shop));

        assertThat(after.lines()).hasSize(1);
        assertThat(after.lines().get(0).countedQty()).isEqualTo(17);
        assertThat(after.lines().get(0).varianceQty()).isEqualTo(-3);
    }

    @Test
    void aLineEnteredByMistakeCanBeRemovedWhileTheCountIsOpen() {
        Shop shop = fixtures.seed();
        stockUp(shop, shop.productUuid(), 20);

        StocktakeRow count = openCount(shop);
        stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 5, manager(shop));
        StocktakeRow after = stocktakes.uncount(shop.tenantId(), count.id(), shop.productUuid());

        assertThat(after.lines()).isEmpty();
    }

    /** Zero is a real count: the shelf was empty. It must not be read as "not counted". */
    @Test
    void countingZeroIsARealAnswer() {
        Shop shop = fixtures.seed();
        stockUp(shop, shop.productUuid(), 4);

        StocktakeRow count = openCount(shop);
        stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 0, manager(shop));
        stocktakes.complete(shop.tenantId(), count.id(), manager(shop));

        assertThat(onHand(shop, shop.productUuid())).isZero();
    }

    @Test
    void aNegativeCountIsRefused() {
        Shop shop = fixtures.seed();
        StocktakeRow count = openCount(shop);

        assertThatThrownBy(
                        () -> stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), -1, manager(shop)))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("cannot be negative");
    }

    // ------------------------------------------------------------------------ lifecycle

    /**
     * One count at a time per branch.
     *
     * <p>Two open stocktakes means two people counting the same shelf into different documents, and
     * whichever completes second writes a variance against a system figure the first already moved.
     */
    @Test
    void onlyOneStocktakeMayBeOpenAtABranch() {
        Shop shop = fixtures.seed();
        openCount(shop);

        assertThatThrownBy(() -> openCount(shop))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("already open");
    }

    /** A retried Start is the same count, not a refusal about itself. */
    @Test
    void theSameClientUuidTwiceIsOneStocktake() {
        Shop shop = fixtures.seed();
        UUID clientUuid = UUID.randomUUID();

        StocktakeRow first =
                stocktakes.open(shop.tenantId(), shop.branchCode(), clientUuid, null, manager(shop));
        StocktakeRow again =
                stocktakes.open(shop.tenantId(), shop.branchCode(), clientUuid, null, manager(shop));

        assertThat(again.id()).isEqualTo(first.id());
    }

    /** Abandoning frees the branch and writes no movements. The attempt itself stays on record. */
    @Test
    void anAbandonedStocktakeMovesNothingAndFreesTheBranch() {
        Shop shop = fixtures.seed();
        stockUp(shop, shop.productUuid(), 10);

        StocktakeRow count = openCount(shop);
        stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 2, manager(shop));
        StocktakeRow abandoned = stocktakes.abandon(shop.tenantId(), count.id(), manager(shop));

        assertThat(abandoned.status()).isEqualTo("ABANDONED");
        assertThat(onHand(shop, shop.productUuid())).isEqualTo(10);
        assertThat(stocktakes.current(shop.tenantId(), shop.branchCode())).isEmpty();
        // Not deleted — somebody started a count and gave up, and that is worth being able to see.
        assertThat(stocktakes.byId(shop.tenantId(), count.id()).status()).isEqualTo("ABANDONED");
    }

    /** A completed count is a document. Recounting means starting a new one. */
    @Test
    void aCompletedStocktakeCannotBeChanged() {
        Shop shop = fixtures.seed();
        stockUp(shop, shop.productUuid(), 10);

        StocktakeRow count = openCount(shop);
        stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 9, manager(shop));
        stocktakes.complete(shop.tenantId(), count.id(), manager(shop));

        assertThatThrownBy(
                        () -> stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 8, manager(shop)))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("cannot be changed");

        assertThatThrownBy(() -> stocktakes.complete(shop.tenantId(), count.id(), manager(shop)))
                .isInstanceOf(RejectedException.class);
    }

    @Test
    void completingWithNothingCountedIsRefused() {
        Shop shop = fixtures.seed();
        StocktakeRow count = openCount(shop);

        assertThatThrownBy(() -> stocktakes.complete(shop.tenantId(), count.id(), manager(shop)))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("Nothing has been counted");
    }

    // ---------------------------------------------------------------------------- sync

    /**
     * The outbox row goes in with the movements (§A).
     *
     * <p>A stocktake that found everything correct still enqueues, with an empty movement list —
     * "we counted and it was right" is a fact the console should have, and its absence is
     * indistinguishable from a count that never happened.
     */
    @Test
    void aCompletedStocktakeCannotExistWithoutItsSyncRecord() {
        Shop shop = fixtures.seed();
        stockUp(shop, shop.productUuid(), 20);

        StocktakeRow count = openCount(shop);
        stocktakes.count(shop.tenantId(), count.id(), shop.productUuid(), 17, manager(shop));
        StocktakeRow completed = stocktakes.complete(shop.tenantId(), count.id(), manager(shop));

        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT payload -> 'movements' -> 0 ->> 'qtyDelta' FROM outbox
                                 WHERE aggregate = 'stocktake' AND aggregate_id = ?
                                """,
                                String.class,
                                completed.clientUuid()))
                .isEqualTo("-3");

        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT payload -> 'movements' -> 0 ->> 'reason' FROM outbox
                                 WHERE aggregate = 'stocktake' AND aggregate_id = ?
                                """,
                                String.class,
                                completed.clientUuid()))
                .isEqualTo("STOCKTAKE");
    }
}
