package com.lumora.pos.report;

import com.lumora.pos.shift.ShiftService;
import com.lumora.pos.web.RejectedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the Z-report (M2-11).
 *
 * <h2>It is a read, and only ever a read</h2>
 *
 * Nothing here writes. The reconciliation happened at close and was frozen then (V107); this
 * assembles what was stored plus the sales and refunds the shift owns. Reprinting a Z-report a
 * month later must produce the same paper as the first print, which it cannot do if any figure is
 * recomputed against a database that has since moved on.
 *
 * <h2>It refuses to print an open shift</h2>
 *
 * A Z-report of a shift still trading would contain the expected cash figure — which is precisely
 * what M2-02 says the person about to count the drawer must not see. The blind count would then
 * have a printer-shaped hole in it. So the report exists for closed shifts only, and the preview a
 * cashier legitimately wants before counting is the list of cash movements, which reveals nothing
 * about the total.
 *
 * <p>Local, like everything else: this runs on the shop PC against the shop's own database and
 * needs no network. A shop that cannot close its till because the internet is down is the product
 * this one replaces.
 */
@Service
public class ZReportService {

    private final JdbcTemplate jdbc;
    private final ShiftService shifts;

    public ZReportService(JdbcTemplate jdbc, ShiftService shifts) {
        this.jdbc = jdbc;
        this.shifts = shifts;
    }

    @Transactional(readOnly = true)
    public ZReport forShift(long shiftId) {
        Header header = header(shiftId);

        if (!"CLOSED".equals(header.status())) {
            throw new RejectedException(
                    "Shift %d is still open. A Z-report carries the expected cash figure, and showing that "
                            .formatted(shiftId)
                            + "before the drawer is counted would defeat the blind count — close the shift first.");
        }

        // Frozen at close, read back verbatim. See the class comment.
        ShiftService.CashDrawer drawer = shifts.expectedCash(shiftId);

        return new ZReport(
                shiftId,
                header.branchCode(),
                header.terminalCode(),
                header.status(),
                header.openedAt(),
                header.closedAt(),
                header.openingFloatMinor(),
                scalarInt("SELECT count(*) FROM sales WHERE shift_id = ?", shiftId),
                scalarLong("SELECT COALESCE(SUM(subtotal_minor), 0) FROM sales WHERE shift_id = ?", shiftId),
                scalarLong("SELECT COALESCE(SUM(discount_minor), 0) FROM sales WHERE shift_id = ?", shiftId),
                scalarLong("SELECT COALESCE(SUM(tax_minor), 0) FROM sales WHERE shift_id = ?", shiftId),
                tendersByKind(shiftId),
                taxByRate(shiftId),
                scalarInt("SELECT count(*) FROM refunds WHERE shift_id = ?", shiftId),
                scalarLong("SELECT COALESCE(SUM(total_minor), 0) FROM refunds WHERE shift_id = ?", shiftId),
                scalarLong("SELECT COALESCE(SUM(tax_minor), 0) FROM refunds WHERE shift_id = ?", shiftId),
                drawer.cashSalesMinor(),
                drawer.cashChangeMinor(),
                drawer.cashRoundingMinor(),
                drawer.cashMovementsMinor(),
                drawer.cashRefundsMinor(),
                drawer.cashRefundRoundingMinor(),
                cashMovementsByReason(shiftId),
                header.expectedCashMinor(),
                header.countedCashMinor(),
                header.varianceMinor(),
                header.varianceReason(),
                header.varianceNote(),
                closingCount(shiftId));
    }

    // ------------------------------------------------------------------ the sections

    private List<ZReport.TenderTotal> tendersByKind(long shiftId) {
        return jdbc.query(
                """
                SELECT p.kind, SUM(p.amount_minor) AS amount_minor, count(*) AS line_count
                  FROM sale_payments p JOIN sales s ON s.id = p.sale_id
                 WHERE s.shift_id = ?
                 GROUP BY p.kind ORDER BY p.kind
                """,
                (rs, row) ->
                        new ZReport.TenderTotal(
                                rs.getString("kind"), rs.getLong("amount_minor"), rs.getInt("line_count")),
                shiftId);
    }

    /**
     * Per-rate takings (M1-18).
     *
     * <p>Grouped on {@code sale_items}, never on {@code sales}: since M1-18 a basket may mix rates,
     * and the sale-level stamp is the cart default rather than a summary of what was charged. A
     * report grouped on the sale would put an exempt loaf in the 18% band and declare tax on it.
     */
    private List<ZReport.TaxBand> taxByRate(long shiftId) {
        return jdbc.query(
                """
                SELECT i.tax_rate_bp, i.tax_mode,
                       SUM(i.line_total_minor) AS gross_minor,
                       SUM(i.tax_minor)        AS tax_minor
                  FROM sale_items i JOIN sales s ON s.id = i.sale_id
                 WHERE s.shift_id = ?
                 GROUP BY i.tax_rate_bp, i.tax_mode
                 ORDER BY i.tax_rate_bp
                """,
                (rs, row) ->
                        new ZReport.TaxBand(
                                rs.getInt("tax_rate_bp"),
                                rs.getString("tax_mode"),
                                rs.getLong("gross_minor"),
                                rs.getLong("tax_minor")),
                shiftId);
    }

    private List<ZReport.CashMovementTotal> cashMovementsByReason(long shiftId) {
        return jdbc.query(
                """
                SELECT kind, reason_code, SUM(amount_minor) AS amount_minor, count(*) AS movement_count
                  FROM cash_movements WHERE shift_id = ?
                 GROUP BY kind, reason_code ORDER BY kind, reason_code
                """,
                (rs, row) ->
                        new ZReport.CashMovementTotal(
                                rs.getString("kind"),
                                rs.getString("reason_code"),
                                rs.getLong("amount_minor"),
                                rs.getInt("movement_count")),
                shiftId);
    }

    private List<ZReport.DenominationLine> closingCount(long shiftId) {
        return jdbc.query(
                """
                SELECT denomination_minor, qty FROM shift_counts
                 WHERE shift_id = ? AND phase = 'CLOSE'
                 ORDER BY denomination_minor DESC
                """,
                (rs, row) ->
                        new ZReport.DenominationLine(
                                rs.getLong("denomination_minor"),
                                rs.getInt("qty"),
                                rs.getLong("denomination_minor") * rs.getInt("qty")),
                shiftId);
    }

    // ------------------------------------------------------------------ helpers

    private Header header(long shiftId) {
        List<Header> found =
                jdbc.query(
                        """
                        SELECT s.status, b.code AS branch_code, s.terminal_code, s.opened_at, s.closed_at,
                               s.opening_float_minor, s.expected_cash_minor, s.counted_cash_minor,
                               s.variance_minor, s.variance_reason, s.variance_note
                          FROM shifts s JOIN branches b ON b.id = s.branch_id
                         WHERE s.id = ?
                        """,
                        (rs, row) ->
                                new Header(
                                        rs.getString("status"),
                                        rs.getString("branch_code"),
                                        rs.getString("terminal_code"),
                                        rs.getTimestamp("opened_at").toInstant(),
                                        instantOrNull(rs.getTimestamp("closed_at")),
                                        rs.getLong("opening_float_minor"),
                                        rs.getObject("expected_cash_minor", Long.class),
                                        rs.getObject("counted_cash_minor", Long.class),
                                        rs.getObject("variance_minor", Long.class),
                                        rs.getString("variance_reason"),
                                        rs.getString("variance_note")),
                        shiftId);
        if (found.isEmpty()) {
            throw new RejectedException("No such shift: " + shiftId);
        }
        return found.get(0);
    }

    private static Instant instantOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private long scalarLong(String sql, long shiftId) {
        Long value = jdbc.queryForObject(sql, Long.class, shiftId);
        return value == null ? 0L : value;
    }

    private int scalarInt(String sql, long shiftId) {
        Integer value = jdbc.queryForObject(sql, Integer.class, shiftId);
        return value == null ? 0 : value;
    }

    private record Header(
            String status,
            String branchCode,
            String terminalCode,
            Instant openedAt,
            Instant closedAt,
            long openingFloatMinor,
            Long expectedCashMinor,
            Long countedCashMinor,
            Long varianceMinor,
            String varianceReason,
            String varianceNote) {}
}
