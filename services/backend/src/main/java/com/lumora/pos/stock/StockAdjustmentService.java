package com.lumora.pos.stock;

import com.lumora.pos.outbox.OutboxWriter;
import com.lumora.pos.shop.LocalShop;
import com.lumora.pos.shop.LocalShop.Branch;
import com.lumora.pos.user.UserService.Operator;
import com.lumora.pos.web.RejectedException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing stock without a document behind it (M3-05).
 *
 * <h2>The correction path the rest of the milestone leans on</h2>
 *
 * A goods receipt is immutable (M3-04) and a sale is never edited (M2-06), which is only tenable
 * because this exists. A delivery keyed in as 100 instead of 10 is fixed by a {@code
 * COUNT_CORRECTION} of −90 — leaving the miscount <em>and</em> the correction on the record, both
 * attributed, instead of one plausible number that hides the fact anything ever went wrong.
 *
 * <h2>It is also the hole, which is why the reason is mandatory</h2>
 *
 * This is the one way to move stock with no sale, no customer and no supplier. That makes it the
 * shape of the gap somebody walks goods out through, and a reason code is what turns "on hand
 * dropped by 40" into something an owner can read. The database refuses an ADJUST with no reason
 * (V112); this refuses one whose direction contradicts its reason, and one that says OTHER without
 * saying what.
 *
 * <h2>On hand is still a sum</h2>
 *
 * {@link #onHand} now reads the {@code stock_on_hand} view (M3-07), which is that same sum with a
 * covering index behind it. Still no cache and still no stored level — the view cannot be stale
 * because it is not a copy.
 */
@Service
public class StockAdjustmentService {

    private final JdbcTemplate jdbc;
    private final LocalShop shop;
    private final StockOnHandService onHandService;
    private final OutboxWriter outbox;

    public StockAdjustmentService(
            JdbcTemplate jdbc, LocalShop shop, StockOnHandService onHandService, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.shop = shop;
        this.onHandService = onHandService;
        this.outbox = outbox;
    }

    /**
     * An adjustment, as the screen shows it back.
     *
     * <p>{@code onHandAfter} is the field that matters. The shopkeeper has just changed a number
     * they could not see, and telling them where it landed is the difference between an adjustment
     * they can check and one they have to trust.
     */
    public record AdjustmentRow(
            UUID clientUuid,
            UUID productClientUuid,
            String sku,
            String productName,
            int qtyDelta,
            AdjustmentReason reason,
            String note,
            String byName,
            Instant at,
            int onHandAfter) {}

    // ------------------------------------------------------------------------ adjusting

    @Transactional
    public AdjustmentRow adjust(
            long tenantId,
            String branchCode,
            UUID clientUuid,
            UUID productClientUuid,
            int qtyDelta,
            String reasonCode,
            String note,
            Operator operator) {

        // A retried request is the same adjustment, not a second one. On this screen a
        // double-pressed Save would move stock twice, and nothing later in the day makes that
        // obvious — the same reason a goods receipt checks first.
        List<UUID> existing =
                jdbc.queryForList(
                        "SELECT client_uuid FROM stock_movements WHERE tenant_id = ? AND client_uuid = ?",
                        UUID.class,
                        tenantId,
                        clientUuid);
        if (!existing.isEmpty()) {
            return byClientUuid(tenantId, clientUuid);
        }

        AdjustmentReason reason = AdjustmentReason.of(reasonCode);
        reason.requireConsistent(qtyDelta);

        String cleanNote = blankToNull(note);
        if (reason.needsNote() && cleanNote == null) {
            throw new RejectedException(
                    "Choosing \"something else\" means writing down what it was — otherwise the"
                            + " reason records nothing.");
        }

        Branch branch = shop.branch(branchCode);
        long productId = productId(tenantId, productClientUuid);

        jdbc.update(
                """
                INSERT INTO stock_movements (
                    client_uuid, tenant_id, branch_id, product_id,
                    qty_delta, reason, reason_code, note, created_by)
                VALUES (?, ?, ?, ?, ?, 'ADJUST', ?, ?, ?)
                """,
                clientUuid,
                tenantId,
                branch.id(),
                productId,
                qtyDelta,
                reason.name(),
                cleanNote,
                operator.id());

        AdjustmentRow saved = byClientUuid(tenantId, clientUuid);

        // Same transaction as the movement (§A). An adjustment the cloud never hears about is an
        // on-hand figure that disagrees between the till and the console from that moment on.
        Map<String, Object> movement = new LinkedHashMap<>();
        movement.put("clientUuid", clientUuid);
        movement.put("productClientUuid", productClientUuid);
        movement.put("qtyDelta", qtyDelta);
        movement.put("reason", "ADJUST");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientUuid", clientUuid);
        payload.put("branchCode", branch.code());
        payload.put("reasonCode", reason.name());
        payload.put("note", cleanNote);
        payload.put("adjustedAt", saved.at().toString());
        payload.put("movements", List.of(movement));
        outbox.enqueue(tenantId, "stock_adjustment", clientUuid, payload);

        return saved;
    }

    // -------------------------------------------------------------------------- reading

    /**
     * Stock on hand for one product at one branch.
     *
     * <p>Delegates to {@link StockOnHandService}, which reads the {@code stock_on_hand} view. This
     * method held its own {@code SELECT sum(qty_delta)} until M3-07 and the comment said so — one
     * calculation in one place is the whole reason the view exists, and a second copy here would be
     * the first place for the two to disagree.
     */
    @Transactional(readOnly = true)
    public int onHand(long tenantId, long branchId, UUID productClientUuid) {
        return onHandService.onHand(tenantId, branchId, productClientUuid);
    }

    /** Recent adjustments, newest first — the list a shopkeeper scans to spot a wrong one. */
    @Transactional(readOnly = true)
    public List<AdjustmentRow> recent(long tenantId, int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        return jdbc.query(
                SELECT_ADJUSTMENT
                        + """
                         WHERE m.tenant_id = ? AND m.reason = 'ADJUST'
                         ORDER BY m.created_at DESC, m.id DESC
                         LIMIT ?
                        """,
                (rs, row) ->
                        new AdjustmentRow(
                                rs.getObject("client_uuid", UUID.class),
                                rs.getObject("product_client_uuid", UUID.class),
                                rs.getString("sku"),
                                rs.getString("product_name"),
                                rs.getInt("qty_delta"),
                                AdjustmentReason.of(rs.getString("reason_code")),
                                rs.getString("note"),
                                rs.getString("by_name"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getInt("on_hand_after")),
                tenantId,
                capped);
    }

    private AdjustmentRow byClientUuid(long tenantId, UUID clientUuid) {
        return jdbc.queryForObject(
                SELECT_ADJUSTMENT + " WHERE m.tenant_id = ? AND m.client_uuid = ?",
                (rs, row) ->
                        new AdjustmentRow(
                                rs.getObject("client_uuid", UUID.class),
                                rs.getObject("product_client_uuid", UUID.class),
                                rs.getString("sku"),
                                rs.getString("product_name"),
                                rs.getInt("qty_delta"),
                                AdjustmentReason.of(rs.getString("reason_code")),
                                rs.getString("note"),
                                rs.getString("by_name"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getInt("on_hand_after")),
                tenantId,
                clientUuid);
    }

    // ------------------------------------------------------------------------- guards

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

    /**
     * The adjustment, plus what the shelf held once it landed.
     *
     * <p>{@code x.id <= m.id} is what makes the name honest: without it the subquery would sum the
     * product's whole history and every row in a list of past adjustments would show <em>today's</em>
     * figure under a column headed "on hand after". Ordering by {@code id} rather than {@code
     * created_at} because a bigserial is strictly monotonic while two movements can share a
     * timestamp, and a tie would make the running figure depend on which row the planner reached
     * first.
     */
    private static final String SELECT_ADJUSTMENT =
            """
            SELECT m.client_uuid, m.qty_delta, m.reason_code, m.note, m.created_at,
                   p.client_uuid AS product_client_uuid, p.sku, p.name AS product_name,
                   u.display_name AS by_name,
                   (SELECT COALESCE(sum(x.qty_delta), 0)
                      FROM stock_movements x
                     WHERE x.product_id = m.product_id
                       AND x.branch_id = m.branch_id
                       AND x.id <= m.id)
                       AS on_hand_after
              FROM stock_movements m
              JOIN products p ON p.id = m.product_id
              JOIN users u ON u.id = m.created_by
            """;
}
