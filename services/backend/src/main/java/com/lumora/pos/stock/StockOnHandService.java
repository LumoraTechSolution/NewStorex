package com.lumora.pos.stock;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What is on the shelf (M3-07).
 *
 * <h2>One definition of the sum</h2>
 *
 * Every query here goes through the {@code stock_on_hand} view (V114). That is the point of having
 * it: before this class, three different places had written their own {@code sum(qty_delta)} —
 * {@code GoodsReceiptTest}, {@code StockAdjustmentService} and the e2e helper — and three copies of
 * a calculation is three chances for one to quietly filter differently. The view is the calculation;
 * this is the only Java that reads it.
 *
 * <h2>Nothing is cached, because there is nothing to cache</h2>
 *
 * On hand is not stored anywhere and no code updates a level (§A). The view sums the movements when
 * asked, and V114's covering index makes that an index-only scan. A shopkeeper reading this screen
 * is reading the movements, not a figure somebody maintained — so it cannot be stale, and there is
 * no refresh step for anyone to forget.
 *
 * <h2>A product with no movements is zero, not missing</h2>
 *
 * The view says nothing about a product that has never moved, which is correct — nothing happened.
 * Turning that into a zero is presentation, so it happens here in a LEFT JOIN rather than in the
 * view, which would then have to know what a product is.
 */
@Service
public class StockOnHandService {

    private final JdbcTemplate jdbc;

    public StockOnHandService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One product's shelf.
     *
     * <p>{@code lastMovedAt} is null for a product that has never moved, which is a different fact
     * from "moved to zero" and worth being able to tell apart on a stocktake list.
     */
    public record OnHandRow(
            UUID productClientUuid,
            String sku,
            String productName,
            String categoryName,
            int qtyOnHand,
            Instant lastMovedAt,
            boolean active) {}

    /**
     * The quantity for one product, or zero if it has never moved.
     *
     * <p>Deliberately allowed to come back negative. A sale rung up before its delivery was booked
     * in really does leave a shelf at −2, and clamping to zero would hide the exact discrepancy a
     * stocktake exists to find.
     */
    @Transactional(readOnly = true)
    public int onHand(long tenantId, long branchId, UUID productClientUuid) {
        Integer qty =
                jdbc.queryForObject(
                        """
                        SELECT COALESCE(
                                 (SELECT h.qty_on_hand
                                    FROM stock_on_hand h
                                    JOIN products p ON p.id = h.product_id
                                   WHERE h.tenant_id = ? AND h.branch_id = ? AND p.client_uuid = ?),
                                 0)
                        """,
                        Integer.class,
                        tenantId,
                        branchId,
                        productClientUuid);
        return qty == null ? 0 : qty;
    }

    /**
     * Every product and what is on its shelf, including the ones that have never moved.
     *
     * <p>Ordered so the rows that need attention come first: below zero, then out of stock, then
     * the rest by name. A shopkeeper opening this screen is looking for a problem, and making them
     * scroll an alphabetical list to find one is making them not look.
     */
    @Transactional(readOnly = true)
    public List<OnHandRow> all(long tenantId, long branchId, boolean includeDiscontinued) {
        return jdbc.query(
                """
                SELECT p.client_uuid, p.sku, p.name, p.active, c.name AS category_name,
                       COALESCE(h.qty_on_hand, 0) AS qty_on_hand,
                       h.last_moved_at
                  FROM products p
                  LEFT JOIN stock_on_hand h
                         ON h.product_id = p.id AND h.tenant_id = p.tenant_id AND h.branch_id = ?
                  LEFT JOIN product_categories c ON c.id = p.category_id
                 WHERE p.tenant_id = ? AND (p.active OR ?)
                 ORDER BY (COALESCE(h.qty_on_hand, 0) < 0) DESC,
                          (COALESCE(h.qty_on_hand, 0) = 0) DESC,
                          p.name
                """,
                ROW,
                branchId,
                tenantId,
                includeDiscontinued);
    }

    // ---------------------------------------------------------------------------- low stock

    /**
     * A product that has fallen to or below the threshold somebody set for it.
     *
     * <p>{@code soldLast30Days} is what turns a list into an order. Two products both three units
     * short are not the same problem if one sells forty a month and the other four, and sorting by
     * how short they are alone would bury the urgent one. It counts units sold, not sales.
     */
    public record LowStockRow(
            UUID productClientUuid,
            String sku,
            String productName,
            String categoryName,
            int qtyOnHand,
            int reorderPoint,
            int soldLast30Days,
            Instant lastMovedAt) {

        /** How many units short of the threshold. Always positive — a row is only here if it is. */
        public int shortBy() {
            return reorderPoint - qtyOnHand;
        }
    }

    /**
     * What the shop is about to run out of.
     *
     * <h2>Only watched products, and that is why the list is trustworthy</h2>
     *
     * A product with no {@code reorder_point} never appears, however low it goes. That reads like a
     * gap and is the feature: an alert list nobody curated fills with things that are legitimately
     * near zero — the slow line, the one-off, the item ordered in for a customer — and a list that
     * is mostly noise is one a shopkeeper stops opening. Which is the same way the console's
     * attention feed fails when it cannot be cleared. Watching a product is a decision, so the
     * screen offers to set a threshold rather than inventing one.
     *
     * <h2>Inclusive, because reorder point means "order at"</h2>
     *
     * {@code <=}, not {@code <}. A shopkeeper who writes 5 means "when I am down to five, reorder",
     * not "when I am down to four".
     *
     * <h2>Discontinued products are never here</h2>
     *
     * Being out of something you have stopped selling is not a problem to solve, and this list
     * exists to be acted on.
     *
     * <p>Nothing is stored (§A). On hand is the {@code stock_on_hand} view summing movements, and
     * "low" is this comparison, evaluated now.
     */
    @Transactional(readOnly = true)
    public List<LowStockRow> lowStock(long tenantId, long branchId) {
        return jdbc.query(
                """
                SELECT p.client_uuid, p.sku, p.name, p.reorder_point, c.name AS category_name,
                       COALESCE(h.qty_on_hand, 0) AS qty_on_hand,
                       h.last_moved_at,
                       COALESCE(sold.units, 0) AS sold_last_30_days
                  FROM products p
                  LEFT JOIN stock_on_hand h
                         ON h.product_id = p.id AND h.tenant_id = p.tenant_id AND h.branch_id = ?
                  LEFT JOIN product_categories c ON c.id = p.category_id
                  LEFT JOIN (
                       SELECT m.product_id, sum(-m.qty_delta)::integer AS units
                         FROM stock_movements m
                        WHERE m.tenant_id = ?
                          AND m.branch_id = ?
                          AND m.reason = 'SALE'
                          AND m.created_at > now() - interval '30 days'
                        GROUP BY m.product_id
                  ) sold ON sold.product_id = p.id
                 WHERE p.tenant_id = ?
                   AND p.active
                   AND p.reorder_point IS NOT NULL
                   AND COALESCE(h.qty_on_hand, 0) <= p.reorder_point
                 ORDER BY (p.reorder_point - COALESCE(h.qty_on_hand, 0)) DESC,
                          COALESCE(sold.units, 0) DESC,
                          p.name
                """,
                LOW_STOCK,
                branchId,
                tenantId,
                branchId,
                tenantId);
    }

    private static final RowMapper<LowStockRow> LOW_STOCK =
            (rs, row) ->
                    new LowStockRow(
                            rs.getObject("client_uuid", UUID.class),
                            rs.getString("sku"),
                            rs.getString("name"),
                            rs.getString("category_name"),
                            rs.getInt("qty_on_hand"),
                            rs.getInt("reorder_point"),
                            rs.getInt("sold_last_30_days"),
                            rs.getTimestamp("last_moved_at") == null
                                    ? null
                                    : rs.getTimestamp("last_moved_at").toInstant());

    /** How the whole shop looks in one line, for the top of the screen. */
    public record OnHandSummary(int products, int belowZero, int outOfStock, int totalUnits) {}

    @Transactional(readOnly = true)
    public OnHandSummary summarise(List<OnHandRow> rows) {
        return new OnHandSummary(
                rows.size(),
                (int) rows.stream().filter(row -> row.qtyOnHand() < 0).count(),
                (int) rows.stream().filter(row -> row.qtyOnHand() == 0).count(),
                rows.stream().mapToInt(OnHandRow::qtyOnHand).sum());
    }

    private static final RowMapper<OnHandRow> ROW =
            (rs, row) ->
                    new OnHandRow(
                            rs.getObject("client_uuid", UUID.class),
                            rs.getString("sku"),
                            rs.getString("name"),
                            rs.getString("category_name"),
                            rs.getInt("qty_on_hand"),
                            rs.getTimestamp("last_moved_at") == null
                                    ? null
                                    : rs.getTimestamp("last_moved_at").toInstant(),
                            rs.getBoolean("active"));
}
