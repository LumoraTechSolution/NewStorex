package com.lumora.pos.stock;

import com.lumora.pos.outbox.OutboxWriter;
import com.lumora.pos.shop.LocalShop;
import com.lumora.pos.shop.LocalShop.Branch;
import com.lumora.pos.user.UserService.Operator;
import com.lumora.pos.web.RejectedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counting the shelves (M3-06).
 *
 * <h2>The difference, never the level</h2>
 *
 * Completing a stocktake writes {@code counted − system} as a STOCKTAKE movement. It does not set
 * anything, because there is nothing to set: on hand is Σ movements. Overwriting would leave a
 * figure that is right today and a history that cannot explain it — and the discrepancy is the
 * entire reason anybody counts.
 *
 * <p>It is also the arithmetically correct answer, which is the part worth being explicit about
 * because "set it to what I counted" sounds obviously right. Counting takes time and the shop keeps
 * trading. System says 20, you count 17, two are sold before you finish: the difference (−3) applied
 * to a system now at 18 gives 15, and real stock is 15. Overwriting to 17 is wrong by exactly the
 * sales that happened while somebody walked round with a clipboard. Deltas compose; levels do not.
 *
 * <h2>Two phases</h2>
 *
 * {@link #open} starts a count that writes nothing. {@link #count} records what was found, one line
 * per product, and stamps what the system said at that moment. {@link #complete} writes every
 * movement in one transaction. A shopkeeper part-way through four hundred products has to be able
 * to stop, and a design that wrote a movement per line would leave the shop half-adjusted with no
 * way to tell which half.
 *
 * <h2>Only what was counted moves</h2>
 *
 * Counting one shelf is normal. A product with no line gets no movement — the same principle as
 * M3-03's "an import is not a sync", and the reason a partial count cannot accidentally zero a shop.
 */
@Service
public class StocktakeService {

    private final JdbcTemplate jdbc;
    private final LocalShop shop;
    private final StockAdjustmentService stock;
    private final OutboxWriter outbox;

    public StocktakeService(
            JdbcTemplate jdbc, LocalShop shop, StockAdjustmentService stock, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.shop = shop;
        this.stock = stock;
        this.outbox = outbox;
    }

    // -------------------------------------------------------------------------- shapes

    /**
     * One counted product.
     *
     * <p>{@code varianceQty} is derived here rather than stored (see V113): a third copy of
     * {@code counted − system} is a third place for the three to disagree.
     */
    public record StocktakeLine(
            int lineNo,
            UUID productClientUuid,
            String sku,
            String productName,
            int countedQty,
            int systemQty,
            Instant countedAt) {

        public int varianceQty() {
            return countedQty - systemQty;
        }
    }

    public record StocktakeRow(
            long id,
            UUID clientUuid,
            String status,
            String note,
            Instant startedAt,
            String startedByName,
            Instant completedAt,
            String completedByName,
            int lineCount,
            int countedShort,
            int countedOver,
            int netVarianceQty,
            List<StocktakeLine> lines) {}

    // --------------------------------------------------------------------------- open

    @Transactional
    public StocktakeRow open(
            long tenantId, String branchCode, UUID clientUuid, String note, Operator operator) {
        Branch branch = shop.branch(branchCode);

        // A retried request is the same count. Checked before the open-count guard below, or a
        // double-pressed Start would report "one is already open" about itself.
        List<Long> same =
                jdbc.queryForList(
                        "SELECT id FROM stocktakes WHERE tenant_id = ? AND client_uuid = ?",
                        Long.class,
                        tenantId,
                        clientUuid);
        if (!same.isEmpty()) {
            return byId(tenantId, same.get(0));
        }

        current(tenantId, branchCode)
                .ifPresent(
                        open -> {
                            throw new RejectedException(
                                    "A stocktake is already open at this branch, started by "
                                            + open.startedByName()
                                            + ". Finish or abandon it before starting another.");
                        });

        Long id =
                jdbc.queryForObject(
                        """
                        INSERT INTO stocktakes (
                            client_uuid, tenant_id, branch_id, status, note, started_by)
                        VALUES (?, ?, ?, 'OPEN', ?, ?)
                        RETURNING id
                        """,
                        Long.class,
                        clientUuid,
                        tenantId,
                        branch.id(),
                        blankToNull(note),
                        operator.id());
        return byId(tenantId, id);
    }

    @Transactional(readOnly = true)
    public Optional<StocktakeRow> current(long tenantId, String branchCode) {
        Branch branch = shop.branch(branchCode);
        return jdbc
                .queryForList(
                        """
                        SELECT id FROM stocktakes
                         WHERE tenant_id = ? AND branch_id = ? AND status = 'OPEN'
                        """,
                        Long.class,
                        tenantId,
                        branch.id())
                .stream()
                .findFirst()
                .map(id -> byId(tenantId, id));
    }

    // -------------------------------------------------------------------------- counting

    /**
     * Records what was found on the shelf for one product.
     *
     * <p>Counting the same product again replaces the line rather than adding a second one — a
     * recount is what a person does when they are not sure, and two lines for one product would
     * make the variance ambiguous. The system figure is re-stamped with it, because the correct
     * comparison is against what the system said when the shelf was last actually looked at.
     */
    @Transactional
    public StocktakeRow count(
            long tenantId, long stocktakeId, UUID productClientUuid, int countedQty, Operator operator) {
        StocktakeRow stocktake = byId(tenantId, stocktakeId);
        requireOpen(stocktake);

        if (countedQty < 0) {
            throw new RejectedException(
                    "A counted quantity cannot be negative. Count what is on the shelf — zero is a"
                            + " real answer.");
        }

        long branchId = branchIdOf(stocktakeId);
        long productId = productId(tenantId, productClientUuid);
        int systemQty = stock.onHand(tenantId, branchId, productClientUuid);

        Integer nextLine =
                jdbc.queryForObject(
                        "SELECT COALESCE(max(line_no), 0) + 1 FROM stocktake_items WHERE stocktake_id = ?",
                        Integer.class,
                        stocktakeId);

        jdbc.update(
                """
                INSERT INTO stocktake_items (
                    stocktake_id, line_no, product_id, counted_qty, system_qty, counted_at, counted_by)
                VALUES (?, ?, ?, ?, ?, now(), ?)
                ON CONFLICT (stocktake_id, product_id) DO UPDATE
                    SET counted_qty = excluded.counted_qty,
                        system_qty  = excluded.system_qty,
                        counted_at  = excluded.counted_at,
                        counted_by  = excluded.counted_by
                """,
                stocktakeId,
                nextLine,
                productId,
                countedQty,
                systemQty,
                operator.id());

        return byId(tenantId, stocktakeId);
    }

    /** Removes a line somebody entered by mistake. Only possible while the count is still open. */
    @Transactional
    public StocktakeRow uncount(long tenantId, long stocktakeId, UUID productClientUuid) {
        StocktakeRow stocktake = byId(tenantId, stocktakeId);
        requireOpen(stocktake);

        jdbc.update(
                """
                DELETE FROM stocktake_items
                 WHERE stocktake_id = ?
                   AND product_id = (SELECT id FROM products WHERE tenant_id = ? AND client_uuid = ?)
                """,
                stocktakeId,
                tenantId,
                productClientUuid);
        return byId(tenantId, stocktakeId);
    }

    // ------------------------------------------------------------------------ completing

    /**
     * Writes the variances and closes the count.
     *
     * <p>One transaction, and one movement per line whose variance is not zero. A line that agreed
     * with the system produces nothing: {@code stock_movements} has a {@code qty_delta <> 0} check,
     * and a movement of zero would be a row that says nothing happened, which is exactly what
     * happened.
     */
    @Transactional
    public StocktakeRow complete(long tenantId, long stocktakeId, Operator operator) {
        StocktakeRow stocktake = byId(tenantId, stocktakeId);
        requireOpen(stocktake);

        if (stocktake.lines().isEmpty()) {
            throw new RejectedException(
                    "Nothing has been counted yet. Count at least one product, or abandon this"
                            + " stocktake.");
        }

        long branchId = branchIdOf(stocktakeId);
        List<Map<String, Object>> movementPayloads = new ArrayList<>();

        for (StocktakeLine line : stocktake.lines()) {
            int variance = line.varianceQty();
            if (variance == 0) {
                continue;
            }

            UUID movementUuid = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO stock_movements (
                        client_uuid, tenant_id, branch_id, product_id,
                        qty_delta, reason, ref_type, ref_id, created_by)
                    VALUES (?, ?, ?,
                            (SELECT id FROM products WHERE tenant_id = ? AND client_uuid = ?),
                            ?, 'STOCKTAKE', 'stocktake', ?, ?)
                    """,
                    movementUuid,
                    tenantId,
                    branchId,
                    tenantId,
                    line.productClientUuid(),
                    variance,
                    stocktakeId,
                    operator.id());

            Map<String, Object> movement = new LinkedHashMap<>();
            movement.put("clientUuid", movementUuid);
            movement.put("productClientUuid", line.productClientUuid());
            movement.put("qtyDelta", variance);
            movement.put("reason", "STOCKTAKE");
            movementPayloads.add(movement);
        }

        jdbc.update(
                """
                UPDATE stocktakes SET status = 'COMPLETED', completed_at = now(), completed_by = ?
                 WHERE tenant_id = ? AND id = ?
                """,
                operator.id(),
                tenantId,
                stocktakeId);

        StocktakeRow completed = byId(tenantId, stocktakeId);

        // Same transaction as the movements (§A). Note the movements travel even when there are
        // none of them: a stocktake that found everything correct is still a fact the console
        // should know, and an empty list is how it says so.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientUuid", completed.clientUuid());
        payload.put("branchCode", branchCodeOf(branchId));
        payload.put("countedAt", completed.completedAt().toString());
        payload.put("lineCount", completed.lineCount());
        payload.put("netVarianceQty", completed.netVarianceQty());
        payload.put("movements", movementPayloads);
        outbox.enqueue(tenantId, "stocktake", completed.clientUuid(), payload);

        return completed;
    }

    /** Gives up on a count that wrote nothing. Not a delete — the attempt itself is history. */
    @Transactional
    public StocktakeRow abandon(long tenantId, long stocktakeId, Operator operator) {
        StocktakeRow stocktake = byId(tenantId, stocktakeId);
        requireOpen(stocktake);

        jdbc.update(
                """
                UPDATE stocktakes SET status = 'ABANDONED', completed_at = now(), completed_by = ?
                 WHERE tenant_id = ? AND id = ?
                """,
                operator.id(),
                tenantId,
                stocktakeId);
        return byId(tenantId, stocktakeId);
    }

    // -------------------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public List<StocktakeRow> recent(long tenantId, int limit) {
        int capped = Math.min(Math.max(limit, 1), 100);
        List<Long> ids =
                jdbc.queryForList(
                        """
                        SELECT id FROM stocktakes
                         WHERE tenant_id = ?
                         ORDER BY started_at DESC, id DESC
                         LIMIT ?
                        """,
                        Long.class,
                        tenantId,
                        capped);
        List<StocktakeRow> rows = new ArrayList<>(ids.size());
        for (Long id : ids) {
            rows.add(byId(tenantId, id));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public StocktakeRow byId(long tenantId, long stocktakeId) {
        Header header;
        try {
            header =
                    jdbc.queryForObject(
                            """
                            SELECT s.client_uuid, s.status, s.note, s.started_at, s.completed_at,
                                   started.display_name AS started_by,
                                   finished.display_name AS completed_by
                              FROM stocktakes s
                              JOIN users started ON started.id = s.started_by
                              LEFT JOIN users finished ON finished.id = s.completed_by
                             WHERE s.tenant_id = ? AND s.id = ?
                            """,
                            (rs, row) ->
                                    new Header(
                                            rs.getObject("client_uuid", UUID.class),
                                            rs.getString("status"),
                                            rs.getString("note"),
                                            rs.getTimestamp("started_at").toInstant(),
                                            rs.getTimestamp("completed_at") == null
                                                    ? null
                                                    : rs.getTimestamp("completed_at").toInstant(),
                                            rs.getString("started_by"),
                                            rs.getString("completed_by")),
                            tenantId,
                            stocktakeId);
        } catch (EmptyResultDataAccessException e) {
            throw new RejectedException("No such stocktake");
        }

        List<StocktakeLine> lines =
                jdbc.query(
                        """
                        SELECT i.line_no, i.counted_qty, i.system_qty, i.counted_at,
                               p.client_uuid AS product_client_uuid, p.sku, p.name
                          FROM stocktake_items i
                          JOIN products p ON p.id = i.product_id
                         WHERE i.stocktake_id = ?
                         ORDER BY i.line_no
                        """,
                        (rs, row) ->
                                new StocktakeLine(
                                        rs.getInt("line_no"),
                                        rs.getObject("product_client_uuid", UUID.class),
                                        rs.getString("sku"),
                                        rs.getString("name"),
                                        rs.getInt("counted_qty"),
                                        rs.getInt("system_qty"),
                                        rs.getTimestamp("counted_at").toInstant()),
                        stocktakeId);

        return new StocktakeRow(
                stocktakeId,
                header.clientUuid(),
                header.status(),
                header.note(),
                header.startedAt(),
                header.startedByName(),
                header.completedAt(),
                header.completedByName(),
                lines.size(),
                (int) lines.stream().filter(line -> line.varianceQty() < 0).count(),
                (int) lines.stream().filter(line -> line.varianceQty() > 0).count(),
                lines.stream().mapToInt(StocktakeLine::varianceQty).sum(),
                lines);
    }

    /** The header row on its own, so the mapper does not have to build the totals it cannot see. */
    private record Header(
            UUID clientUuid,
            String status,
            String note,
            Instant startedAt,
            Instant completedAt,
            String startedByName,
            String completedByName) {}

    // --------------------------------------------------------------------------- guards

    private static void requireOpen(StocktakeRow stocktake) {
        if (!"OPEN".equals(stocktake.status())) {
            throw new RejectedException(
                    "This stocktake is "
                            + stocktake.status().toLowerCase(Locale.ROOT)
                            + " and cannot be changed. Start a new one to count again.");
        }
    }

    private long branchIdOf(long stocktakeId) {
        return jdbc.queryForObject(
                "SELECT branch_id FROM stocktakes WHERE id = ?", Long.class, stocktakeId);
    }

    private String branchCodeOf(long branchId) {
        return jdbc.queryForObject("SELECT code FROM branches WHERE id = ?", String.class, branchId);
    }

    private long productId(long tenantId, UUID productClientUuid) {
        List<Long> found =
                jdbc.queryForList(
                        "SELECT id FROM products WHERE tenant_id = ? AND client_uuid = ?",
                        Long.class,
                        tenantId,
                        productClientUuid);
        if (found.isEmpty()) {
            throw new RejectedException("Unknown product: " + productClientUuid);
        }
        return found.get(0);
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
