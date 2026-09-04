package com.lumora.pos.cloud;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The half of the console that was designed and never built (M6-12).
 *
 * <p>{@link ConsoleReportService} has carried a comment since M4-07 admitting it: the attention feed
 * was meant to be cash variance <em>and</em> stock variance, "and the stock half joins it when the
 * console has a stock screen to link to". Until now an owner away from the shop could see the money
 * and not the goods — which is half a business.
 *
 * <h2>Still Σ movements, exactly as on the till</h2>
 *
 * There is no stored level here and there must never be one. §A's second rule is that a balance is
 * always the sum of the movements that produced it, and the cloud gets to hold that rule for free:
 * it receives movements, so two branches reconcile by addition and no arrival order can produce a
 * wrong number. A cached level would be a second source of truth that drifts from the till's, and
 * the drift would show up as an owner and a shopkeeper reading different figures off two screens.
 *
 * <h2>Blank and zero are different instructions, here as well</h2>
 *
 * {@code reorder_point} is nullable with no default (V120, mirrored in V211). NULL means "not
 * watched" and 0 means "tell me when it is empty", and folding them together would lose exactly the
 * line a shopkeeper cares most about. The low-stock query therefore asks {@code IS NOT NULL} rather
 * than {@code > 0}, and an unwatched product is never listed however low it goes.
 *
 * <h2>Summed across branches, deliberately</h2>
 *
 * v1 is one shop with one till, so there is nothing to split. When there is, the split belongs
 * beside {@code branchTotals} as a separate question rather than as a column added here — "what is
 * on the shelf" and "which shelf" are different screens for an owner standing somewhere else.
 */
@Service
@Profile("cloud")
public class ConsoleStockService {

    private final JdbcTemplate jdbc;

    public ConsoleStockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param onHand Σ of every movement this shop has sent for the product. Can be negative, and a
     *     negative is worth showing rather than clamping: it means a sale was rung up for stock the
     *     shop never recorded receiving, which is a real thing to go and look at.
     * @param reorderPoint null when the product is not watched.
     */
    public record StockLine(
            String productClientUuid,
            String sku,
            String name,
            String category,
            long onHand,
            Integer reorderPoint,
            long priceMinor) {}

    /**
     * What the shop is about to run out of, most urgent first.
     *
     * <p>Ordered by the gap rather than by the level: a product two below its threshold is more
     * urgent than one that is merely at zero and was never expected to move. That is the same
     * ordering the till's own low-stock report uses (M3-15), and the two screens agreeing matters
     * more than either ordering being optimal.
     */
    @Transactional(readOnly = true)
    public List<StockLine> lowStock(long tenantId, int limit) {
        return jdbc.query(
                """
                SELECT p.client_uuid, p.sku, p.name, p.category, p.reorder_point, p.price_minor,
                       COALESCE(m.on_hand, 0) AS on_hand
                  FROM products p
                  LEFT JOIN (SELECT product_client_uuid, sum(qty_delta) AS on_hand
                               FROM stock_movements
                              WHERE tenant_id = ?
                              GROUP BY product_client_uuid) m
                         ON m.product_client_uuid = p.client_uuid
                 WHERE p.tenant_id = ?
                   AND p.active
                   AND p.reorder_point IS NOT NULL
                   AND COALESCE(m.on_hand, 0) <= p.reorder_point
                 ORDER BY COALESCE(m.on_hand, 0) - p.reorder_point, p.name
                 LIMIT ?
                """,
                ConsoleStockService::readLine,
                tenantId,
                tenantId,
                Math.max(1, Math.min(limit, 200)));
    }

    /**
     * On hand for everything the shop sells, newest concern first.
     *
     * @param query a piece of a name or an SKU. Empty lists everything, which for a grocery is a
     *     few hundred rows and is why the limit is not optional.
     */
    @Transactional(readOnly = true)
    public List<StockLine> onHand(long tenantId, String query, int limit) {
        String needle = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase() + "%";
        return jdbc.query(
                """
                SELECT p.client_uuid, p.sku, p.name, p.category, p.reorder_point, p.price_minor,
                       COALESCE(m.on_hand, 0) AS on_hand
                  FROM products p
                  LEFT JOIN (SELECT product_client_uuid, sum(qty_delta) AS on_hand
                               FROM stock_movements
                              WHERE tenant_id = ?
                              GROUP BY product_client_uuid) m
                         ON m.product_client_uuid = p.client_uuid
                 WHERE p.tenant_id = ?
                   AND p.active
                   AND (?::text IS NULL
                        OR lower(p.name) LIKE ?::text
                        OR lower(p.sku) LIKE ?::text)
                 ORDER BY p.name
                 LIMIT ?
                """,
                ConsoleStockService::readLine,
                tenantId,
                tenantId,
                needle,
                needle,
                needle,
                Math.max(1, Math.min(limit, 500)));
    }

    private static StockLine readLine(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        int reorderPoint = rs.getInt("reorder_point");
        return new StockLine(
                rs.getString("client_uuid"),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getLong("on_hand"),
                // wasNull() rather than a zero check: 0 is a real threshold meaning "tell me when
                // it is empty", and reading it as "not watched" is the exact bug V120's comment
                // was written to prevent.
                rs.wasNull() ? null : reorderPoint,
                rs.getLong("price_minor"));
    }
}
