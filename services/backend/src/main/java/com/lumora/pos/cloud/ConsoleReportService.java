package com.lumora.pos.cloud;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
    public List<CashVariance> cashVariances(long tenantId, int days, long thresholdMinor) {
        int bounded = Math.max(1, Math.min(days, 365));
        return jdbc.query(
                """
                SELECT client_uuid, branch_code, terminal_code, closed_at,
                       variance_minor, variance_reason
                  FROM shifts
                 WHERE tenant_id = ?
                   AND closed_at IS NOT NULL
                   AND closed_at > now() - make_interval(days => ?)
                   AND abs(variance_minor) >= ?
                 ORDER BY closed_at DESC
                 LIMIT 50
                """,
                (rs, row) ->
                        new CashVariance(
                                rs.getString("client_uuid"),
                                rs.getString("branch_code"),
                                rs.getString("terminal_code"),
                                rs.getTimestamp("closed_at").toInstant(),
                                rs.getLong("variance_minor"),
                                rs.getString("variance_reason")),
                tenantId,
                bounded,
                thresholdMinor);
    }

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
            String varianceReason) {}

    public record RecentSale(
            String invoiceNumber,
            String branchCode,
            String terminalCode,
            long totalMinor,
            Instant soldAt) {}
}
