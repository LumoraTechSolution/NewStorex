package com.lumora.pos.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumora.pos.stock.GoodsReceiptService.ReceiptLine;
import com.lumora.pos.stock.StockAdjustmentService.AdjustmentRow;
import com.lumora.pos.stock.SupplierService.SupplierRow;
import com.lumora.pos.testfixtures.ShopFixture;
import com.lumora.pos.testfixtures.ShopFixture.Shop;
import com.lumora.pos.user.UserService;
import com.lumora.pos.user.UserService.Operator;
import com.lumora.pos.web.RejectedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock adjustments (M3-05).
 *
 * <p>An adjustment is the one way to move stock with no sale, no customer and no supplier behind
 * it, which makes it both the correction path the rest of M3 depends on and the exact shape of the
 * hole somebody walks goods out through. Most of this file is therefore about the two things that
 * keep it honest: a reason that is always present, and a sign that comes from the reason rather
 * than from whoever is typing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"desktop", "test"})
@Transactional
class StockAdjustmentTest {

    @Autowired StockAdjustmentService adjustments;
    @Autowired GoodsReceiptService receipts;
    @Autowired SupplierService suppliers;
    @Autowired UserService users;
    @Autowired ShopFixture fixtures;
    @Autowired JdbcTemplate jdbc;

    private Operator manager(Shop shop) {
        return users.authenticate(shop.tenantId(), ShopFixture.MANAGER_CODE, ShopFixture.MANAGER_PIN);
    }

    private AdjustmentRow adjust(Shop shop, int qtyDelta, String reason, String note) {
        return adjustments.adjust(
                shop.tenantId(),
                shop.branchCode(),
                UUID.randomUUID(),
                shop.productUuid(),
                qtyDelta,
                reason,
                note,
                manager(shop));
    }

    /** Puts a known quantity on the shelf, the way a shop actually would. */
    private void stockUp(Shop shop, int qty) {
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
                List.of(new ReceiptLine(shop.productUuid(), qty, 30_000)),
                manager(shop));
    }

    private int onHand(Shop shop) {
        return adjustments.onHand(shop.tenantId(), shop.branchId(), shop.productUuid());
    }

    // -------------------------------------------------------------------- the happy path

    @Test
    void anAdjustmentMovesStockAndRecordsWhoWhyAndWhereItLanded() {
        Shop shop = fixtures.seed();
        stockUp(shop, 20);

        AdjustmentRow row = adjust(shop, -3, "DAMAGED", null);

        assertThat(row.qtyDelta()).isEqualTo(-3);
        assertThat(row.reason()).isEqualTo(AdjustmentReason.DAMAGED);
        assertThat(row.byName()).isEqualTo("Fixture Manager");
        // The field that makes the adjustment checkable rather than something to be trusted.
        assertThat(row.onHandAfter()).isEqualTo(17);
        assertThat(onHand(shop)).isEqualTo(17);
    }

    /**
     * The correction path M3-04 depends on.
     *
     * <p>A goods receipt is immutable, which is only tenable because a miscount can be corrected
     * here — and the point is that <em>both</em> facts survive. The 100 that was keyed in is still
     * on the record next to the −90 that fixed it.
     */
    @Test
    void aMiskeyedDeliveryIsCorrectedWithoutErasingTheMistake() {
        Shop shop = fixtures.seed();
        stockUp(shop, 100); // meant 10

        adjust(shop, -90, "COUNT_CORRECTION", "Delivery keyed in as 100, actually 10");

        assertThat(onHand(shop)).isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT count(*) FROM stock_movements m
                                  JOIN products p ON p.id = m.product_id
                                 WHERE p.client_uuid = ? AND m.branch_id = ?
                                """,
                                Integer.class,
                                shop.productUuid(),
                                shop.branchId()))
                .as("the receipt's movement and the correction both survive")
                .isEqualTo(2);
    }

    /** Found stock goes up. The only inward reason, and it has to actually work. */
    @Test
    void foundStockIncreasesTheShelf() {
        Shop shop = fixtures.seed();
        stockUp(shop, 5);

        assertThat(adjust(shop, 2, "FOUND", null).onHandAfter()).isEqualTo(7);
    }

    /**
     * On hand may go negative, and is not clamped.
     *
     * <p>A sale rung up before its delivery was booked in really does leave a shelf at −2. Clamping
     * to zero would hide the exact discrepancy the shopkeeper needs to see.
     */
    @Test
    void onHandIsAllowedToGoNegative() {
        Shop shop = fixtures.seed();
        stockUp(shop, 1);

        assertThat(adjust(shop, -3, "THEFT", null).onHandAfter()).isEqualTo(-2);
    }

    // ------------------------------------------------------------------------- the sign

    /**
     * The refusal this whole design exists for.
     *
     * <p>A DAMAGED adjustment that added stock would put goods on a shelf that is actually empty,
     * and the next stocktake would report shrinkage that never happened — from a row that looks
     * exactly like a legitimate movement.
     */
    @Test
    void anOutwardReasonCannotBeUsedToAddStock() {
        Shop shop = fixtures.seed();
        stockUp(shop, 10);

        assertThatThrownBy(() -> adjust(shop, 5, "DAMAGED", null))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("always removes stock");

        assertThat(onHand(shop)).isEqualTo(10);
    }

    @Test
    void anInwardReasonCannotBeUsedToRemoveStock() {
        Shop shop = fixtures.seed();
        assertThatThrownBy(() -> adjust(shop, -5, "FOUND", null))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("always adds stock");
    }

    /** A miscount genuinely goes both ways, so neither direction is refused. */
    @Test
    void aCorrectionMayGoEitherWay() {
        Shop shop = fixtures.seed();
        stockUp(shop, 10);

        assertThat(adjust(shop, 4, "COUNT_CORRECTION", null).onHandAfter()).isEqualTo(14);
        assertThat(adjust(shop, -6, "COUNT_CORRECTION", null).onHandAfter()).isEqualTo(8);
    }

    @Test
    void anAdjustmentOfZeroIsRefused() {
        Shop shop = fixtures.seed();
        assertThatThrownBy(() -> adjust(shop, 0, "COUNT_CORRECTION", null))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("not a movement");
    }

    // ------------------------------------------------------------------------ the reason

    /**
     * The database refuses an ADJUST with no reason, independently of the service.
     *
     * <p>V112's CHECK is the last line. Asserting it here rather than trusting the service is the
     * difference between "our code always sets it" and "a row without one cannot exist".
     */
    @Test
    void theDatabaseItselfRefusesAnAdjustmentWithNoReason() {
        Shop shop = fixtures.seed();

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO stock_movements (
                                            client_uuid, tenant_id, branch_id, product_id,
                                            qty_delta, reason, created_by)
                                        SELECT ?, ?, ?, p.id, -5, 'ADJUST', ?
                                          FROM products p WHERE p.client_uuid = ?
                                        """,
                                        UUID.randomUUID(),
                                        shop.tenantId(),
                                        shop.branchId(),
                                        shop.managerId(),
                                        shop.productUuid()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUnknownReasonIsRefused() {
        Shop shop = fixtures.seed();
        assertThatThrownBy(() -> adjust(shop, -1, "BECAUSE", null))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("Not a stock adjustment reason");
    }

    /**
     * {@code OTHER} has to say what it was.
     *
     * <p>Without the rule, OTHER becomes the reason everybody picks and the code stops carrying any
     * information — the same argument M2-04 makes about an unexplained cash variance.
     */
    @Test
    void otherWithoutANoteIsRefused() {
        Shop shop = fixtures.seed();
        stockUp(shop, 10);

        assertThatThrownBy(() -> adjust(shop, -2, "OTHER", "   "))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("what it was");

        assertThat(adjust(shop, -2, "OTHER", "Used for the window display").onHandAfter()).isEqualTo(8);
    }

    /**
     * The Java enum and the TypeScript list are the same vocabulary.
     *
     * <p>They are duplicated deliberately — the screen cannot import Java and the backend cannot
     * trust the screen — so this is what stops the duplication becoming a divergence. A reason
     * added to one side alone fails here.
     */
    @Test
    void theReasonListMatchesTheOneTheScreenOffers() throws Exception {
        Path domain = Path.of("../../packages/domain/src/stock.ts");
        assertThat(domain).as("the domain module the screen reads its reasons from").exists();
        String source = Files.readString(domain);

        String block =
                source.substring(
                        source.indexOf("STOCK_ADJUSTMENT_REASONS = ["),
                        source.indexOf("] as const", source.indexOf("STOCK_ADJUSTMENT_REASONS = [")));

        for (AdjustmentReason reason : AdjustmentReason.values()) {
            assertThat(block)
                    .as("%s is offered by the screen", reason)
                    .contains("'" + reason.name() + "'");
        }
        // And nothing the screen offers is unknown here, which is the direction that would let a
        // shopkeeper pick a reason the backend then refuses.
        long offered = block.chars().filter(c -> c == '\'').count() / 2;
        assertThat(offered).isEqualTo(AdjustmentReason.values().length);
    }

    // ---------------------------------------------------------------------------- sync

    /**
     * The outbox row goes in with the movement (§A).
     *
     * <p>An adjustment the cloud never hears about is an on-hand figure that disagrees between the
     * till and the console from that moment on, with nothing to suggest which one is right.
     */
    @Test
    void anAdjustmentCannotExistWithoutItsSyncRecord() {
        Shop shop = fixtures.seed();
        stockUp(shop, 10);
        AdjustmentRow row = adjust(shop, -4, "EXPIRED", null);

        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT payload -> 'movements' -> 0 ->> 'qtyDelta' FROM outbox
                                 WHERE aggregate = 'stock_adjustment' AND aggregate_id = ?
                                """,
                                String.class,
                                row.clientUuid()))
                .isEqualTo("-4");

        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT payload ->> 'reasonCode' FROM outbox
                                 WHERE aggregate = 'stock_adjustment' AND aggregate_id = ?
                                """,
                                String.class,
                                row.clientUuid()))
                .isEqualTo("EXPIRED");
    }

    /** A retried request is the same adjustment. A double-pressed Save must not move stock twice. */
    @Test
    void theSameClientUuidTwiceIsOneAdjustment() {
        Shop shop = fixtures.seed();
        stockUp(shop, 10);
        UUID clientUuid = UUID.randomUUID();

        for (int attempt = 0; attempt < 2; attempt++) {
            adjustments.adjust(
                    shop.tenantId(),
                    shop.branchCode(),
                    clientUuid,
                    shop.productUuid(),
                    -3,
                    "DAMAGED",
                    null,
                    manager(shop));
        }

        assertThat(onHand(shop)).isEqualTo(7);
    }

    // -------------------------------------------------------------------------- reading

    /**
     * A past adjustment shows the figure that followed <em>it</em>, not today's.
     *
     * <p>Without the {@code id <= } bound in the subquery every row in the list would report the
     * current level under a column headed "on hand after", which is worse than showing nothing.
     */
    @Test
    void theListShowsWhatTheShelfHeldAfterEachAdjustment() {
        Shop shop = fixtures.seed();
        stockUp(shop, 20);

        adjust(shop, -5, "DAMAGED", null); // 15
        adjust(shop, -5, "EXPIRED", null); // 10

        List<AdjustmentRow> recent = adjustments.recent(shop.tenantId(), 25);
        List<AdjustmentRow> mine =
                recent.stream().filter(r -> r.productClientUuid().equals(shop.productUuid())).toList();

        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).onHandAfter()).as("newest first").isEqualTo(10);
        assertThat(mine.get(1).onHandAfter()).isEqualTo(15);
    }
}
