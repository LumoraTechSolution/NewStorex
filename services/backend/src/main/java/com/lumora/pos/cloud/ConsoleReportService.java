package com.lumora.pos.cloud;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the owner console reads (M4-05, M4-06, M4-07).
 *
 * <p>The first code in this project that reads the cloud rather than writing to it. Until now the
 * cloud had exactly one endpoint and it was {@code POST /api/sync/batch}.
 *
 * <h2>Every query takes a tenant, and none of them finds one</h2>
 *
 * The tenant is a parameter on every method here, resolved from the caller's session by the filter.
 * Nothing in this class looks a tenant up, defaults one, or falls back to "the only one" — the
 * shape that makes a cross-tenant leak possible is a query that can run without being told whose
 * data it is, so there isn't one.
 *
 * <h2>The shop's day, not the server's</h2>
 *
 * A shop in Kandy closing at 9pm wants "today" to mean its own calendar day. The server may be
 * anywhere, so every date boundary is computed in an explicit zone passed in by the caller rather
 * than from the JVM default. Getting this wrong shows up as takings that reset at 5:30am, which is
 * the sort of bug an owner reports as "the app is broken" and nobody can reproduce.
 */
@Service
@Profile("cloud")
public class ConsoleReportService {

    private final JdbcTemplate jdbc;

    public ConsoleReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The headline: what the shop has taken today, and whether the figure can be trusted.
     *
     * <p>{@code lastSyncAt} is the honest part. This is a cloud reading an outbox's output, so the
     * number is only as fresh as the last drain — and a till that stopped syncing at lunchtime
     * would otherwise show a plausible, wrong, quietly shrinking total. The console shows the
     * timestamp beside the money for exactly that reason.
     */
    @Transactional(readOnly = true)
    public Today today(long tenantId, String zone) {
        return jdbc.queryForObject(
                """
                SELECT coalesce(sum(total_minor), 0) AS total_minor,
                       count(*)                      AS sale_count,
                       max(received_at)              AS last_sync_at
                  FROM sales
                 WHERE tenant_id = ?
                   AND (sold_at AT TIME ZONE ?)::date = (now() AT TIME ZONE ?)::date
                """,
                (rs, row) ->
                        new Today(
                                rs.getLong("total_minor"),
                                rs.getInt("sale_count"),
                                rs.getTimestamp("last_sync_at") == null
                                        ? null
                                        : rs.getTimestamp("last_sync_at").toInstant()),
                tenantId,
                zone,
                zone);
    }

    /**
     * A daily series for the trend, most recent last.
     *
     * <p>Generated from a date series and left-joined, so a day the shop was shut appears as a zero
     * rather than being absent. A chart that silently omits empty days draws a flat line through a
     * closed Sunday and tells the owner the opposite of what happened.
     */
    @Transactional(readOnly = true)
    public List<DailyTotal> dailyTotals(long tenantId, String zone, int days) {
        int bounded = Math.max(1, Math.min(days, 365));
        return jdbc.query(
                """
                WITH span AS (
                    SELECT generate_series(
                        (now() AT TIME ZONE ?)::date - make_interval(days => ? - 1),
                        (now() AT TIME ZONE ?)::date,
                        interval '1 day')::date AS day
                )
                SELECT span.day,
                       coalesce(sum(s.total_minor), 0) AS total_minor,
                       count(s.id)                     AS sale_count
                  FROM span
                  LEFT JOIN sales s
                    ON s.tenant_id = ?
                   AND (s.sold_at AT TIME ZONE ?)::date = span.day
                 GROUP BY span.day
                 ORDER BY span.day
                """,
                (rs, row) ->
                        new DailyTotal(
                                rs.getObject("day", LocalDate.class),
                                rs.getLong("total_minor"),
                                rs.getInt("sale_count")),
                zone,
                bounded,
                zone,
                tenantId,
                zone);
    }

    /** Today, split by branch. One row per branch that has ever sold anything. */
    @Transactional(readOnly = true)
    public List<BranchTotal> branchTotals(long tenantId, String zone) {
        return jdbc.query(
                """
                SELECT branch_code,
                       coalesce(sum(total_minor), 0) AS total_minor,
                       count(*)                      AS sale_count,
                       max(received_at)              AS last_sync_at
                  FROM sales
                 WHERE tenant_id = ?
                   AND (sold_at AT TIME ZONE ?)::date = (now() AT TIME ZONE ?)::date
                 GROUP BY branch_code
                 ORDER BY total_minor DESC
                """,
                (rs, row) ->
                        new BranchTotal(
                                rs.getString("branch_code"),
                                rs.getLong("total_minor"),
                                rs.getInt("sale_count"),
                                rs.getTimestamp("last_sync_at") == null
                                        ? null
                                        : rs.getTimestamp("last_sync_at").toInstant()),
                tenantId,
                zone,
                zone);
    }

    /**
     * The attention feed (M4-07) — shifts that closed out of balance.
     *
     * <p>Cash variance and stock variance are the same pattern, which is why M4-07 puts them on one
     * screen: <em>something does not add up, look here</em>. Only the cash half exists so far, and
     * the stock half joins it when the console has a stock screen to link to.
     *
     * <p>The threshold is applied to the absolute variance. A drawer that is over is not good news
     * — it usually means a sale nobody rang up (D1).
     */
    @Transactional(readOnly = true)
    public List<CashVariance> cashVariances(
            long tenantId, int days, long thresholdMinor, boolean reviewed) {
        int bounded = Math.max(1, Math.min(days, 365));
        return jdbc.query(
                """
                SELECT s.client_uuid, s.branch_code, s.terminal_code, s.closed_at,
                       s.variance_minor, s.variance_reason,
                       a.acknowledged_at, u.display_name AS ack_by
                  FROM shifts s
                  LEFT JOIN shift_acknowledgements a
                         ON a.tenant_id = s.tenant_id AND a.shift_client_uuid = s.client_uuid
                  LEFT JOIN console_users u ON u.id = a.acknowledged_by
                 WHERE s.tenant_id = ?
                   AND s.closed_at IS NOT NULL
                   AND s.closed_at > now() - make_interval(days => ?)
                   AND abs(s.variance_minor) >= ?
                   AND (a.id IS NOT NULL) = ?
                 ORDER BY s.closed_at DESC
                 LIMIT 50
                """,
                (rs, row) ->
                        new CashVariance(
                                rs.getString("client_uuid"),
                                rs.getString("branch_code"),
                                rs.getString("terminal_code"),
                                rs.getTimestamp("closed_at").toInstant(),
                                rs.getLong("variance_minor"),
                                rs.getString("variance_reason"),
                                rs.getTimestamp("acknowledged_at") == null
                                        ? null
                                        : rs.getTimestamp("acknowledged_at").toInstant(),
                                rs.getString("ack_by")),
                tenantId,
                bounded,
                thresholdMinor,
                reviewed);
    }

    /**
     * Who was on a till, and what they took (M6-13).
     *
     * @param operator the person's display name, or null when the shift predates M6-13 and the
     *     cloud was never told. Rendered as "not recorded" rather than hidden: a day's takings that
     *     did not add up because a row was quietly dropped is worse than an unnamed row.
     * @param shiftCount usually one. More than one means somebody came back after a break, or
     *     worked two tills, and collapsing that into a single row would hide a handover.
     */
    public record OperatorDay(
            String operatorClientUuid,
            String operator,
            int shiftCount,
            int saleCount,
            long totalMinor,
            long varianceMinor,
            /**
             * True while one of this person's shifts today is still open (M6-14).
             *
             * <p>What makes the console's first line possible: <em>Open · Nimal since 9:04</em>. An
             * owner opens this to find out whether the shop is alright, and "who is behind the
             * counter right now" is that question. Derived rather than stored — a shift that has
             * not been delivered closed is a shift that is open.
             */
            boolean onNow,
            /** When this person first opened a till today. */
            Instant openedAt) {}

    /**
     * The end of a day, by the person who worked it.
     *
     * <h2>The join goes through the shift, not through the sale</h2>
     *
     * A sale carries no operator and deliberately never will: the person is a property of the
     * <em>shift</em>, which is what M2 made the unit of accountability, and stamping a cashier onto
     * every sale line would be a second place for the same fact to be wrong. So the sales are
     * attributed by `shift_client_uuid`, which V200 already indexes.
     *
     * <h2>Grouped by the person, not by the shift</h2>
     *
     * "Nimal took Rs. 84,000" is the answer somebody wants. Two rows because Nimal took a break
     * would be arithmetic homework — so shifts are counted rather than listed, and a count above one
     * is the visible trace of a handover for anybody who cares.
     *
     * <h2>The variance rides along</h2>
     *
     * Takings and drawer accuracy are the same conversation with a member of staff, and reading them
     * off two screens is how one of them gets forgotten. Summed across the person's shifts: two
     * shifts each Rs. 100 short is a pattern, and one Rs. 200 short is an incident.
     */
    @Transactional(readOnly = true)
    public List<OperatorDay> operatorDay(long tenantId, LocalDate day, String zone) {
        return jdbc.query(
                """
                SELECT s.opened_by_client_uuid,
                       COALESCE(u.display_name, s.opened_by_name)          AS operator,
                       count(DISTINCT s.client_uuid)                       AS shift_count,
                       count(x.id)                                         AS sale_count,
                       COALESCE(sum(x.total_minor), 0)                     AS total_minor,
                       COALESCE(sum(DISTINCT s.variance_minor), 0)         AS variance_minor,
                       bool_or(s.status <> 'CLOSED')                       AS on_now,
                       min(s.opened_at)                                    AS opened_at
                  FROM shifts s
                  LEFT JOIN users u
                         ON u.tenant_id = s.tenant_id
                        AND u.client_uuid = s.opened_by_client_uuid
                  LEFT JOIN sales x
                         ON x.tenant_id = s.tenant_id
                        AND x.shift_client_uuid = s.client_uuid
                 WHERE s.tenant_id = ?
                   AND (s.opened_at AT TIME ZONE ?)::date = ?
                 GROUP BY s.opened_by_client_uuid, COALESCE(u.display_name, s.opened_by_name)
                 ORDER BY total_minor DESC
                """,
                (rs, row) ->
                        new OperatorDay(
                                rs.getString("opened_by_client_uuid"),
                                rs.getString("operator"),
                                rs.getInt("shift_count"),
                                rs.getInt("sale_count"),
                                rs.getLong("total_minor"),
                                rs.getLong("variance_minor"),
                                rs.getBoolean("on_now"),
                                rs.getTimestamp("opened_at") == null
                                        ? null
                                        : rs.getTimestamp("opened_at").toInstant()),
                tenantId,
                zone,
                day);
    }

    /**
     * One quarter hour of a trading day (M6-14).
     *
     * @param minuteOfDay the start of the slot, in the shop's own clock. 0 is midnight.
     * @param usualSaleCount what a normal one of this weekday does in this slot, averaged over the
     *     four weeks behind it. A fraction on purpose: rounding it to an integer would flatten the
     *     quiet half of every day to zero and make a slow morning look like a closed one.
     * @param usualTotalMinor what a normal one of this weekday <em>takes</em> in this slot, on the
     *     same average. It rides beside the count because the sentence above the graphic is in
     *     money — "Rs 12,400 ahead of a normal Monday by this hour" — and a console that computed
     *     that from a daily total scaled by the time of day would be inventing an intraday shape it
     *     had not been given. Minor units, and a fraction for the same reason the count is one.
     */
    public record PulseSlot(
            int minuteOfDay,
            int saleCount,
            long totalMinor,
            double usualSaleCount,
            double usualTotalMinor) {}

    /**
     * The shape of a trading day, against the shape of a normal one.
     *
     * <h2>Why a shop needs this and a total does not give it</h2>
     *
     * "Rs 84,300 by two o'clock" is unreadable on its own — an owner cannot tell a good Monday from
     * a bad one without knowing what a Monday does. Two profiles across the day answer it at a
     * glance and answer a second question nobody asked but everybody wants: <em>when</em> the shop
     * is busy, which is the only input to deciding when a second person is worth paying for.
     *
     * <h2>The baseline is the same weekday, not the last fortnight</h2>
     *
     * A grocery's Saturday and its Tuesday are different businesses. Averaging across a fortnight
     * would compare today to a blur and tell an owner their Saturday was exceptional every week.
     * Four weeks back, same weekday, and the average is over the days that actually traded — a shop
     * closed on two of the last four Mondays is compared against the two it opened.
     *
     * <h2>Quarter hours</h2>
     *
     * Ninety-six slots a day. Finer than that and a small grocery's ones and twos become noise;
     * coarser and the lunchtime peak smears into the afternoon it is distinguishable from.
     */
    @Transactional(readOnly = true)
    public List<PulseSlot> pulse(long tenantId, LocalDate day, String zone) {
        Map<Integer, long[]> today = new HashMap<>();
        jdbc.query(
                """
                SELECT (floor((extract(hour from local.ts) * 60 + extract(minute from local.ts)) / 15)
                        * 15)::int                       AS slot,
                       count(*)                          AS n,
                       COALESCE(sum(local.total_minor), 0) AS minor
                  FROM (SELECT (s.sold_at AT TIME ZONE ?) AS ts, s.total_minor
                          FROM sales s
                         WHERE s.tenant_id = ?
                           AND (s.sold_at AT TIME ZONE ?)::date = ?) AS local
                 GROUP BY 1
                """,
                // Braces, and no return value. An expression lambda here matches both
                // RowCallbackHandler and ResultSetExtractor and the call will not compile.
                (rs) -> {
                    today.put(
                            rs.getInt("slot"),
                            new long[] {rs.getLong("n"), rs.getLong("minor")});
                },
                zone,
                tenantId,
                zone,
                day);

        Map<Integer, double[]> usual = new HashMap<>();
        jdbc.query(
                """
                SELECT slot, avg(n) AS usual, avg(minor) AS usual_minor
                  FROM (SELECT local.day AS day,
                               (floor((extract(hour from local.ts) * 60
                                       + extract(minute from local.ts)) / 15) * 15)::int AS slot,
                               count(*) AS n,
                               COALESCE(sum(local.total_minor), 0) AS minor
                          FROM (SELECT (s.sold_at AT TIME ZONE ?) AS ts,
                                       (s.sold_at AT TIME ZONE ?)::date AS day,
                                       s.total_minor
                                  FROM sales s
                                 WHERE s.tenant_id = ?
                                   AND (s.sold_at AT TIME ZONE ?)::date <  ?
                                   AND (s.sold_at AT TIME ZONE ?)::date >= ?::date - 28
                                   AND extract(dow from (s.sold_at AT TIME ZONE ?))
                                       = extract(dow from ?::date)) AS local
                         GROUP BY 1, 2) AS per_day
                 GROUP BY slot
                """,
                (rs) -> {
                    usual.put(
                            rs.getInt("slot"),
                            new double[] {rs.getDouble("usual"), rs.getDouble("usual_minor")});
                },
                zone,
                zone,
                tenantId,
                zone,
                day,
                zone,
                day,
                zone,
                day);

        List<PulseSlot> slots = new ArrayList<>(96);
        for (int minute = 0; minute < 24 * 60; minute += 15) {
            long[] mine = today.getOrDefault(minute, EMPTY_SLOT);
            double[] normal = usual.getOrDefault(minute, EMPTY_USUAL);
            slots.add(new PulseSlot(minute, (int) mine[0], mine[1], normal[0], normal[1]));
        }
        return slots;
    }

    private static final long[] EMPTY_SLOT = {0, 0};

    private static final double[] EMPTY_USUAL = {0d, 0d};

    /** The most recent sales, for an owner who wants to see the shop ticking over. */
    @Transactional(readOnly = true)
    public List<RecentSale> recentSales(long tenantId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return jdbc.query(
                """
                SELECT invoice_number, branch_code, terminal_code, total_minor, sold_at
                  FROM sales
                 WHERE tenant_id = ?
                 ORDER BY sold_at DESC
                 LIMIT ?
                """,
                (rs, row) ->
                        new RecentSale(
                                rs.getString("invoice_number"),
                                rs.getString("branch_code"),
                                rs.getString("terminal_code"),
                                rs.getLong("total_minor"),
                                rs.getTimestamp("sold_at").toInstant()),
                tenantId,
                bounded);
    }

    // ---------------------------------------------------------------------------------- types

    /** @param lastSyncAt null when nothing has arrived today — which is itself worth showing. */
    public record Today(long totalMinor, int saleCount, Instant lastSyncAt) {}

    public record DailyTotal(LocalDate day, long totalMinor, int saleCount) {}

    public record BranchTotal(
            String branchCode, long totalMinor, int saleCount, Instant lastSyncAt) {}

    public record CashVariance(
            String shiftClientUuid,
            String branchCode,
            String terminalCode,
            Instant closedAt,
            long varianceMinor,
            String varianceReason,
            /**
             * When somebody in the shop's office said they had looked at this, or null (M6-10).
             *
             * <p>On the row rather than only on the detail, so a reviewed list can say who cleared
             * each one without a second request per row. An alert that can be cleared and then
             * cannot be found again is a different kind of useless.
             */
            java.time.Instant acknowledgedAt,
            String acknowledgedBy) {}

    public record RecentSale(
            String invoiceNumber,
            String branchCode,
            String terminalCode,
            long totalMinor,
            Instant soldAt) {}
}
