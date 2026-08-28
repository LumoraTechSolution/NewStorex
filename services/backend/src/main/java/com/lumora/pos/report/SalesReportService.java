package com.lumora.pos.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three questions a shopkeeper asks their own till (M3-10): what did we take today, what sells,
 * and what happened on each shift.
 *
 * <h2>Reads, on this machine, over this machine's data</h2>
 *
 * Nothing here writes and nothing here calls anything. The owner console (M4-05) will answer bigger
 * versions of the same questions across branches and dates, from the cloud; these exist because the
 * shop must be able to answer them with the internet down, which is the state the shop is in exactly
 * when it most wants to know whether the drawer is right.
 *
 * <h2>A "day" is the shop PC's day</h2>
 *
 * There is no timezone column. The desktop backend runs on the counter, so the machine's own clock
 * <em>is</em> the shop's clock, and asking a shopkeeper to configure a timezone for the room they
 * are standing in would be a setting with exactly one correct value and one way to get it wrong. The
 * boundaries are computed once, here, and passed as absolute instants — so the SQL never has to
 * agree about time with anything.
 *
 * <p>Where this changes: the cloud reads the same rows for a shop it is not standing in, so M4-05
 * has to store the branch's zone. That is a cloud problem and this is where the seam is.
 *
 * <h2>Refunds are subtracted, never netted into a sale</h2>
 *
 * Every figure below reports gross takings and returns as two numbers, and the net as a third.
 * Folding a refund into the sale it came from would make a day with ten sales and ten returns look
 * identical to a quiet day, and the difference between those two days is the entire reason anybody
 * reads a report.
 */
@Service
public class SalesReportService {

    private final JdbcTemplate jdbc;

    public SalesReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ----------------------------------------------------------------------- day sales

    /**
     * What the shop took on one calendar day.
     *
     * @param date the shop PC's calendar day. Today is the overwhelmingly common answer and the
     *     one the screen opens on; yesterday is the second.
     */
    @Transactional(readOnly = true)
    public DaySales day(long tenantId, LocalDate date) {
        Window window = Window.of(date);

        Totals sales =
                jdbc.queryForObject(
                        """
                        SELECT count(*)                                 AS n,
                               COALESCE(sum(total_minor), 0)            AS gross,
                               COALESCE(sum(discount_minor), 0)         AS discount,
                               COALESCE(sum(tax_minor), 0)              AS tax
                          FROM sales
                         WHERE tenant_id = ? AND sold_at >= ? AND sold_at < ?
                        """,
                        (rs, row) ->
                                new Totals(
                                        rs.getInt("n"),
                                        rs.getLong("gross"),
                                        rs.getLong("discount"),
                                        rs.getLong("tax")),
                        tenantId,
                        window.from(),
                        window.to());

        Totals refunds =
                jdbc.queryForObject(
                        """
                        SELECT count(*)                      AS n,
                               COALESCE(sum(total_minor), 0) AS gross,
                               0                             AS discount,
                               COALESCE(sum(tax_minor), 0)   AS tax
                          FROM refunds
                         WHERE tenant_id = ? AND refunded_at >= ? AND refunded_at < ?
                        """,
                        (rs, row) ->
                                new Totals(
                                        rs.getInt("n"),
                                        rs.getLong("gross"),
                                        rs.getLong("discount"),
                                        rs.getLong("tax")),
                        tenantId,
                        window.from(),
                        window.to());

        return new DaySales(
                date,
                sales.count(),
                sales.gross(),
                sales.discount(),
                sales.tax(),
                refunds.count(),
                refunds.gross(),
                refunds.tax(),
                sales.gross() - refunds.gross(),
                tenders(tenantId, window),
                hours(tenantId, window));
    }

    /**
     * Money in by tender kind, with refunds of that kind taken back off it.
     *
     * <p>Both halves come from one query rather than two lists the caller has to line up. A CARD
     * row that exists in sales but not in refunds and a CARD row that exists in both must be the
     * same row on the screen, and merging two lists in the UI is where that stops being true.
     */
    private List<TenderTotal> tenders(long tenantId, Window window) {
        return jdbc.query(
                """
                SELECT kind,
                       COALESCE(sum(taken), 0)     AS taken,
                       COALESCE(sum(given_back), 0) AS given_back
                  FROM (
                        SELECT p.kind, p.amount_minor AS taken, 0 AS given_back
                          FROM sale_payments p JOIN sales s ON s.id = p.sale_id
                         WHERE s.tenant_id = ? AND s.sold_at >= ? AND s.sold_at < ?
                        UNION ALL
                        SELECT p.kind, 0 AS taken, p.amount_minor AS given_back
                          FROM refund_payments p JOIN refunds r ON r.id = p.refund_id
                         WHERE r.tenant_id = ? AND r.refunded_at >= ? AND r.refunded_at < ?
                       ) movements
                 GROUP BY kind
                 ORDER BY kind
                """,
                (rs, row) ->
                        new TenderTotal(
                                rs.getString("kind"),
                                rs.getLong("taken"),
                                rs.getLong("given_back"),
                                rs.getLong("taken") - rs.getLong("given_back")),
                tenantId,
                window.from(),
                window.to(),
                tenantId,
                window.from(),
                window.to());
    }

    /**
     * Takings by hour of the shop's day.
     *
     * <p>Only hours that traded appear. Padding the quiet ones with zeros would be presentation
     * dressed as data, and the screen can draw a gap where it wants one — it knows how wide the day
     * is and this does not.
     */
    private List<HourTotal> hours(long tenantId, Window window) {
        return jdbc.query(
                """
                SELECT extract(hour from sold_at AT TIME ZONE ?)::int AS hour,
                       count(*)                                       AS n,
                       COALESCE(sum(total_minor), 0)                  AS gross
                  FROM sales
                 WHERE tenant_id = ? AND sold_at >= ? AND sold_at < ?
                 GROUP BY 1
                 ORDER BY 1
                """,
                (rs, row) ->
                        new HourTotal(rs.getInt("hour"), rs.getInt("n"), rs.getLong("gross")),
                ZoneId.systemDefault().getId(),
                tenantId,
                window.from(),
                window.to());
    }

    // -------------------------------------------------------------------- top products

    /**
     * What actually sells, over a range of days.
     *
     * <p>Ranked by quantity rather than by money, because the question this answers is "what do I
     * need to keep on the shelf". A revenue ranking answers a different and also useful question,
     * and the response carries both figures so the screen can offer it without a second query.
     *
     * <p>Returned units are subtracted. A line sold ten times and returned nine is not a product
     * that sells, and a report that says otherwise is worse than no report — it would be acted on.
     */
    @Transactional(readOnly = true)
    public List<TopProduct> topProducts(long tenantId, LocalDate from, LocalDate to, int limit) {
        Window window = Window.between(from, to);
        return jdbc.query(
                """
                WITH sold AS (
                    SELECT i.product_id,
                           sum(i.qty)               AS qty,
                           sum(i.line_total_minor)  AS revenue
                      FROM sale_items i JOIN sales s ON s.id = i.sale_id
                     WHERE s.tenant_id = ? AND s.sold_at >= ? AND s.sold_at < ?
                     GROUP BY i.product_id
                ), returned AS (
                    SELECT si.product_id,
                           sum(ri.qty)                AS qty,
                           sum(ri.refund_total_minor) AS revenue
                      FROM refund_items ri
                      JOIN refunds r ON r.id = ri.refund_id
                      JOIN sale_items si ON si.id = ri.sale_item_id
                     WHERE r.tenant_id = ? AND r.refunded_at >= ? AND r.refunded_at < ?
                     GROUP BY si.product_id
                )
                SELECT p.client_uuid, p.sku, p.name,
                       sold.qty                                          AS qty_sold,
                       COALESCE(returned.qty, 0)                         AS qty_returned,
                       sold.qty - COALESCE(returned.qty, 0)              AS qty_net,
                       sold.revenue - COALESCE(returned.revenue, 0)      AS revenue_net
                  FROM sold
                  JOIN products p ON p.id = sold.product_id
                  LEFT JOIN returned ON returned.product_id = sold.product_id
                 ORDER BY qty_net DESC, revenue_net DESC, p.name
                 LIMIT ?
                """,
                (rs, row) ->
                        new TopProduct(
                                rs.getObject("client_uuid", java.util.UUID.class),
                                rs.getString("sku"),
                                rs.getString("name"),
                                rs.getInt("qty_sold"),
                                rs.getInt("qty_returned"),
                                rs.getInt("qty_net"),
                                rs.getLong("revenue_net")),
                tenantId,
                window.from(),
                window.to(),
                tenantId,
                window.from(),
                window.to(),
                limit);
    }

    // ------------------------------------------------------------------------ Z-history

    /**
     * Every shift that has been closed, newest first.
     *
     * <p>This is the index into {@link ZReportService}: each row names a shift whose full Z-report
     * can be reprinted, and carries the three figures somebody scanning the list is looking for —
     * what was counted, what was expected, and the difference. The variance is stored rather than
     * recomputed (V107 froze it at close), so a row here and the Z-report it opens cannot disagree.
     *
     * <p>Open shifts are excluded, and not as a tidiness measure: an open shift has an expected-cash
     * figure, and putting it on a list anybody can open would give the person about to count the
     * drawer the number M2-02 exists to keep from them.
     */
    @Transactional(readOnly = true)
    public List<ClosedShift> closedShifts(long tenantId, int limit) {
        return jdbc.query(
                """
                SELECT s.id, b.code AS branch_code, s.terminal_code,
                       s.opened_at, s.closed_at,
                       opener.display_name AS opened_by_name,
                       closer.display_name AS closed_by_name,
                       s.opening_float_minor, s.counted_cash_minor, s.expected_cash_minor,
                       s.variance_minor, s.variance_reason, s.variance_note,
                       (SELECT count(*) FROM sales x WHERE x.shift_id = s.id)   AS sale_count,
                       (SELECT COALESCE(sum(x.total_minor), 0) FROM sales x
                         WHERE x.shift_id = s.id)                               AS sales_total_minor,
                       (SELECT count(*) FROM refunds x WHERE x.shift_id = s.id) AS refund_count,
                       (SELECT COALESCE(sum(x.total_minor), 0) FROM refunds x
                         WHERE x.shift_id = s.id)                               AS refunds_total_minor
                  FROM shifts s
                  JOIN branches b ON b.id = s.branch_id
                  JOIN users opener ON opener.id = s.opened_by
                  LEFT JOIN users closer ON closer.id = s.closed_by
                 WHERE s.tenant_id = ? AND s.status = 'CLOSED'
                 ORDER BY s.closed_at DESC
                 LIMIT ?
                """,
                (rs, row) ->
                        new ClosedShift(
                                rs.getLong("id"),
                                rs.getString("branch_code"),
                                rs.getString("terminal_code"),
                                rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
                                rs.getObject("closed_at", OffsetDateTime.class).toInstant(),
                                rs.getString("opened_by_name"),
                                rs.getString("closed_by_name"),
                                rs.getLong("opening_float_minor"),
                                rs.getLong("counted_cash_minor"),
                                rs.getLong("expected_cash_minor"),
                                rs.getLong("variance_minor"),
                                rs.getString("variance_reason"),
                                rs.getString("variance_note"),
                                rs.getInt("sale_count"),
                                rs.getLong("sales_total_minor"),
                                rs.getInt("refund_count"),
                                rs.getLong("refunds_total_minor")),
                tenantId,
                limit);
    }

    // -------------------------------------------------------------------------- payloads

    /**
     * @param netTakingsMinor gross less refunds. Named "takings" and not "revenue" because that is
     *     the word used across the counter, and because it is deliberately not an accounting figure
     *     — no cost of goods, no cash movements.
     */
    public record DaySales(
            LocalDate date,
            int saleCount,
            long grossMinor,
            long discountMinor,
            long taxMinor,
            int refundCount,
            long refundTotalMinor,
            long refundTaxMinor,
            long netTakingsMinor,
            List<TenderTotal> tenders,
            List<HourTotal> hours) {}

    public record TenderTotal(String kind, long takenMinor, long givenBackMinor, long netMinor) {}

    public record HourTotal(int hour, int saleCount, long grossMinor) {}

    public record TopProduct(
            java.util.UUID clientUuid,
            String sku,
            String name,
            int qtySold,
            int qtyReturned,
            int qtyNet,
            long revenueNetMinor) {}

    public record ClosedShift(
            long id,
            String branchCode,
            String terminalCode,
            Instant openedAt,
            Instant closedAt,
            String openedByName,
            String closedByName,
            long openingFloatMinor,
            long countedCashMinor,
            long expectedCashMinor,
            long varianceMinor,
            String varianceReason,
            String varianceNote,
            int saleCount,
            long salesTotalMinor,
            int refundCount,
            long refundsTotalMinor) {}

    private record Totals(int count, long gross, long discount, long tax) {}

    /**
     * A half-open interval of absolute time, from the shop PC's calendar.
     *
     * <p>Half-open — {@code >= from AND < to} — so a sale rung up at 23:59:59.999 belongs to one day
     * and a sale at midnight belongs to the next, with no instant claimed twice and none lost. The
     * alternative, {@code BETWEEN}, is inclusive at both ends and silently double-counts midnight.
     */
    private record Window(OffsetDateTime from, OffsetDateTime to) {

        static Window of(LocalDate date) {
            return between(date, date);
        }

        static Window between(LocalDate from, LocalDate to) {
            ZoneId zone = ZoneId.systemDefault();
            return new Window(
                    from.atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC),
                    to.plusDays(1)
                            .atStartOfDay(zone)
                            .toOffsetDateTime()
                            .withOffsetSameInstant(ZoneOffset.UTC));
        }
    }
}
