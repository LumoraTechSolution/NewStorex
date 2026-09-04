package com.lumora.pos.shift;

import com.lumora.pos.outbox.OutboxWriter;
import com.lumora.pos.settings.TenantSettingsService;
import com.lumora.pos.shop.LocalShop;
import com.lumora.pos.user.Permission;
import com.lumora.pos.user.UserService;
import com.lumora.pos.web.RejectedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening and closing a till (M2-01 … M2-04).
 *
 * <p>This is the accountability layer, and it earns its keep entirely at close. Everything before
 * that is bookkeeping so that one number at the end — the variance — means something.
 *
 * <h2>The blind count is enforced here, not on the screen</h2>
 *
 * M2-02 says the person counting never sees what the drawer should hold. A UI that simply chooses
 * not to display the figure is not a blind count; it is a blind count until someone opens the
 * network tab, or until a later refactor passes one more field into a component. So {@link
 * #status} — the only thing a terminal can call while a shift is open — has no expected-cash field
 * to leak. The figure is computed for the first time inside {@link #close}, after the count has
 * already been submitted and is therefore beyond changing.
 *
 * <p>That ordering is the mechanism. The count arrives in the same request that asks for the
 * answer, so there is no round trip in which a cashier could learn the target and adjust.
 *
 * <h2>Expected cash is a query, never a column</h2>
 *
 * {@link #expectedCash} is a fold over the shift's own rows: the float, the cash tenders, the
 * change handed back, the rounding residual (M1-03), the signed cash movements, and the cash that
 * went back out as refunds. Nothing anywhere holds a running total. The one figure that is stored
 * is the result at close, and it is stored precisely because a Z-report must reprint identically
 * forever — see the V107 header.
 */
@Service
public class ShiftService {

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final LocalShop shop;
    private final TenantSettingsService settings;
    private final UserService users;

    public ShiftService(
            JdbcTemplate jdbc,
            OutboxWriter outbox,
            LocalShop shop,
            TenantSettingsService settings,
            UserService users) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.shop = shop;
        this.settings = settings;
        this.users = users;
    }

    // ------------------------------------------------------------------------- open

    @Transactional
    public ShiftResponse open(OpenShiftRequest request) {
        Optional<ShiftResponse> existing = findByClientUuid(request.clientUuid());
        if (existing.isPresent()) {
            // A retry: the terminal resent because it never saw the response. Returning the
            // original is what makes the till's retry safe — the same contract as a sale.
            return existing.get();
        }

        LocalShop.Branch branch = shop.branch(request.branchCode());

        long floatMinor = countedTotal(request.openingCount());
        if (request.openingFloatMinor() != null && request.openingFloatMinor() != floatMinor) {
            throw new RejectedException(
                    "Opening float %d does not match the counted denominations (%d)"
                            .formatted(request.openingFloatMinor(), floatMinor));
        }

        // Checked before the insert, not caught after it. Postgres aborts the whole
        // transaction on a constraint violation, so nothing can be read back inside the catch
        // block — the recovery query fails with 25P02 and the useful message is lost. The
        // partial unique index is still what *guarantees* one open shift per terminal; this
        // is what makes the ordinary case say so in words a cashier can act on.
        List<Long> alreadyOpen =
                jdbc.queryForList(
                        """
                        SELECT id FROM shifts
                        WHERE tenant_id = ? AND branch_id = ? AND terminal_code = ? AND status = 'OPEN'
                        """,
                        Long.class,
                        branch.tenantId(),
                        branch.id(),
                        request.terminalCode());
        if (!alreadyOpen.isEmpty()) {
            throw new RejectedException(
                    "Terminal %s at %s already has an open shift — close it before opening another"
                            .formatted(request.terminalCode(), request.branchCode()));
        }

        // M3-08. Who is taking the till. Authenticated before the float is written, so a shift
        // never exists without a named person answering for the drawer it just opened.
        UserService.Operator operator =
                users.authorise(
                        branch.tenantId(),
                        request.operatorCode(),
                        request.operatorPin(),
                        Permission.RUN_SHIFT);

        // A genuine race — two identical requests in flight at once — still lands on the unique
        // index and is deliberately not caught. The transaction rolls back and the terminal's
        // retry finds the winner at the top of this method, which is the only correct recovery:
        // an aborted transaction has nothing left to return.
        long shiftId =
                jdbc.queryForObject(
                        """
                        INSERT INTO shifts (
                            client_uuid, tenant_id, branch_id, terminal_code, status,
                            opened_at, opened_by, opening_float_minor)
                        VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?)
                        RETURNING id
                        """,
                        Long.class,
                        request.clientUuid(),
                        branch.tenantId(),
                        branch.id(),
                        request.terminalCode(),
                        Timestamp.from(request.openedAt() != null ? request.openedAt() : Instant.now()),
                        operator.id(),
                        floatMinor);

        insertCounts(shiftId, "OPEN", request.openingCount());
        enqueueShift(branch.tenantId(), request.clientUuid(), shiftId);

        return load(shiftId);
    }

    // ----------------------------------------------------------------------- status

    /**
     * What the terminal shows while a shift is running.
     *
     * <p>Deliberately missing an expected-cash field. See the class comment: this is the blind
     * count's actual enforcement, and adding "just for the manager screen" would undo it.
     */
    @Transactional(readOnly = true)
    public ShiftStatusResponse status(String branchCode, String terminalCode) {
        LocalShop.Branch branch = shop.branch(branchCode);

        List<ShiftStatusResponse> open =
                jdbc.query(
                        """
                        SELECT s.id, s.client_uuid, s.opened_at, s.opening_float_minor,
                               u.display_name AS operator_name
                        FROM shifts s
                        JOIN users u ON u.id = s.opened_by
                        WHERE s.tenant_id = ? AND s.branch_id = ? AND s.terminal_code = ?
                          AND s.status = 'OPEN'
                        """,
                        (rs, row) ->
                                new ShiftStatusResponse(
                                        true,
                                        rs.getLong("id"),
                                        rs.getObject("client_uuid", UUID.class),
                                        rs.getTimestamp("opened_at").toInstant(),
                                        rs.getLong("opening_float_minor"),
                                        countSales(rs.getLong("id")),
                                        countCashMovements(rs.getLong("id")),
                                        // An inner join, not a left one: `opened_by` is NOT NULL and
                                        // references `users`, and there is no delete on that table —
                                        // M3-08 deactivates instead, precisely so history keeps
                                        // resolving. A missing row here would be a broken invariant,
                                        // and hiding it behind a null name would hide that too.
                                        rs.getString("operator_name")),
                        branch.tenantId(),
                        branch.id(),
                        terminalCode);

        return open.isEmpty() ? ShiftStatusResponse.closed() : open.get(0);
    }

    /**
     * The open shift a sale or a refund must attach itself to.
     *
     * <p>Returns the id or rejects. There is no "sell without a shift" path: cash that arrives
     * outside a shift is cash nothing reconciles, which is the hole this milestone exists to
     * close. Opening a shift is entirely local, so requiring one costs nothing against §A — the
     * network is still on the critical path of nothing.
     */
    public OpenShift requireOpenShift(long tenantId, long branchId, String terminalCode) {
        List<OpenShift> found =
                jdbc.query(
                        """
                        SELECT id, opened_by FROM shifts
                        WHERE tenant_id = ? AND branch_id = ? AND terminal_code = ? AND status = 'OPEN'
                        """,
                        (rs, row) -> new OpenShift(rs.getLong("id"), rs.getLong("opened_by")),
                        tenantId,
                        branchId,
                        terminalCode);
        if (found.isEmpty()) {
            throw new RejectedException(
                    "No shift is open on terminal %s — open one before trading".formatted(terminalCode));
        }
        return found.get(0);
    }

    /**
     * The open shift, and who is answerable for it.
     *
     * <p>{@code operatorId} is the shift's {@code opened_by}, and it is what every sale, cash
     * movement and stock movement made during the shift is attributed to (M3-08). The shift is
     * the session: the till already refuses to trade without one, the person who opened it
     * authenticated to do so, and a single till has one person behind it at a time. That is a
     * true statement about a v1 shop and it retires the placeholder operator id that every
     * {@code created_by} column carried from V100 until now.
     *
     * <p>It stops being true the moment two cashiers share a till on one shift, which is what
     * M3-09's real sessions are for. Until then this attributes to the person who counted the
     * float, which is both defensible and checkable — rather than to nobody, which was neither.
     */
    public record OpenShift(long id, long operatorId) {}

    // ------------------------------------------------------------------------ close

    @Transactional
    public ShiftResponse close(long shiftId, CloseShiftRequest request) {
        ShiftRow shift = loadRow(shiftId);
        if (!"OPEN".equals(shift.status())) {
            // Idempotent rather than an error. A close that timed out on the terminal is
            // resent, and the second attempt must show the same Z-report figures rather than
            // recomputing them against a drawer that has since been emptied.
            return load(shiftId);
        }

        // M3-08. Whoever counts the drawer signs for the count. Deliberately not required to be
        // the same person who opened the shift — a shift very often outlives the person who
        // started it, and forcing a match would leave a till nobody can close when someone goes
        // home sick. `opened_by` and `closed_by` are separate columns precisely so the handover
        // is recorded rather than hidden.
        UserService.Operator closer =
                users.authorise(
                        shift.tenantId(),
                        request.operatorCode(),
                        request.operatorPin(),
                        Permission.RUN_SHIFT);

        long countedMinor = countedTotal(request.closingCount());
        if (request.countedCashMinor() != null && request.countedCashMinor() != countedMinor) {
            throw new RejectedException(
                    "Counted cash %d does not match the counted denominations (%d)"
                            .formatted(request.countedCashMinor(), countedMinor));
        }

        // First — and only — time the expected figure exists. The count above is already fixed.
        CashDrawer drawer = expectedCash(shiftId);
        long expectedMinor = drawer.expectedMinor();
        long varianceMinor = countedMinor - expectedMinor;

        long thresholdMinor = settings.cashVarianceThresholdMinor(shift.tenantId());
        boolean requiresReason = Math.abs(varianceMinor) > thresholdMinor;

        // M2-04. Checked against the absolute variance: a drawer over is usually a sale nobody
        // rang up, which is the worse problem and the one a shortfall-only gate waves through.
        if (requiresReason && (request.varianceReason() == null || request.varianceReason().isBlank())) {
            throw new RejectedException(
                    "Variance of %d exceeds the threshold of %d — a reason code is required to close"
                            .formatted(varianceMinor, thresholdMinor));
        }
        if ("OTHER".equals(request.varianceReason())
                && (request.varianceNote() == null || request.varianceNote().isBlank())) {
            throw new RejectedException("A variance reason of OTHER requires a note saying what happened");
        }

        jdbc.update(
                """
                UPDATE shifts
                   SET status = 'CLOSED',
                       closed_at = ?,
                       closed_by = ?,
                       counted_cash_minor = ?,
                       expected_cash_minor = ?,
                       variance_minor = ?,
                       variance_reason = ?,
                       variance_note = ?
                 WHERE id = ? AND status = 'OPEN'
                """,
                Timestamp.from(request.closedAt() != null ? request.closedAt() : Instant.now()),
                closer.id(),
                countedMinor,
                expectedMinor,
                varianceMinor,
                request.varianceReason(),
                request.varianceNote(),
                shiftId);

        insertCounts(shiftId, "CLOSE", request.closingCount());
        enqueueShift(shift.tenantId(), shift.clientUuid(), shiftId);

        return load(shiftId);
    }

    // ------------------------------------------------------------- expected cash (Σ)

    /**
     * Every term that decides what the drawer should hold, each a sum over the shift's own rows.
     *
     * <p>The terms are returned separately rather than pre-added because the Z-report has to
     * <em>show</em> the derivation. A variance with no visible arithmetic behind it is a number
     * nobody trusts and everybody overrides — which is the same as not having one.
     *
     * <p>Mirrors {@code expectedCashMinor} in {@code @lumora/domain}. The duplication is real and
     * is confined to an addition: the terms here are SQL aggregates that only a database can
     * produce, and shipping them to the terminal to be summed would put the network on the
     * critical path of closing a till. The money <em>math</em> — VAT, rounding, apportionment —
     * remains in one place; this is a fold over figures the domain already produced.
     */
    @Transactional(readOnly = true)
    public CashDrawer expectedCash(long shiftId) {
        long openingFloatMinor =
                jdbc.queryForObject("SELECT opening_float_minor FROM shifts WHERE id = ?", Long.class, shiftId);

        // Cash tenders across the shift's sales, and the change that went straight back out.
        // COALESCE, because a shift with no sales yet must read zero rather than null.
        Long cashSalesMinor =
                jdbc.queryForObject(
                        """
                        SELECT COALESCE(SUM(p.amount_minor), 0)
                          FROM sale_payments p JOIN sales s ON s.id = p.sale_id
                         WHERE s.shift_id = ? AND p.kind = 'CASH'
                        """,
                        Long.class,
                        shiftId);

        // Only sales that actually took cash contribute change or a rounding residual: a
        // card-only sale has neither, and its columns are zero anyway. Filtering on the
        // payment kind would double-count a split sale that has two cash lines.
        Long cashChangeMinor =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(change_minor), 0) FROM sales WHERE shift_id = ?",
                        Long.class,
                        shiftId);
        Long cashRoundingMinor =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(rounding_adjustment_minor), 0) FROM sales WHERE shift_id = ?",
                        Long.class,
                        shiftId);

        // Already signed in the column (V107): pay-ins positive, pay-outs and drops negative.
        // A plain SUM with no CASE is the whole reason the sign lives there.
        Long cashMovementsMinor =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(amount_minor), 0) FROM cash_movements WHERE shift_id = ?",
                        Long.class,
                        shiftId);

        Long cashRefundsMinor =
                jdbc.queryForObject(
                        """
                        SELECT COALESCE(SUM(p.amount_minor), 0)
                          FROM refund_payments p JOIN refunds r ON r.id = p.refund_id
                         WHERE r.shift_id = ? AND p.kind = 'CASH'
                        """,
                        Long.class,
                        shiftId);
        Long cashRefundRoundingMinor =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(rounding_adjustment_minor), 0) FROM refunds WHERE shift_id = ?",
                        Long.class,
                        shiftId);

        return new CashDrawer(
                openingFloatMinor,
                cashSalesMinor,
                cashChangeMinor,
                cashRoundingMinor,
                cashMovementsMinor,
                cashRefundsMinor,
                cashRefundRoundingMinor);
    }

    /** The terms of the drawer, and their sum. Identical arithmetic to the domain's version. */
    public record CashDrawer(
            long openingFloatMinor,
            long cashSalesMinor,
            long cashChangeMinor,
            long cashRoundingMinor,
            long cashMovementsMinor,
            long cashRefundsMinor,
            long cashRefundRoundingMinor) {

        public long expectedMinor() {
            return openingFloatMinor
                    + cashSalesMinor
                    + cashRoundingMinor
                    + cashMovementsMinor
                    - cashChangeMinor
                    - cashRefundsMinor
                    - cashRefundRoundingMinor;
        }
    }

    // ----------------------------------------------------------------------- counts

    /**
     * Adds up a physical count, and refuses anything that is not money.
     *
     * <p>The denominations mirror {@code LKR_DENOMINATIONS_MINOR} in {@code @lumora/domain}. An
     * unlisted face value is rejected rather than summed: the failure this catches in the field is
     * a screen sending rupees where minor units are expected, which would silently read the drawer
     * a hundredfold light and hand a cashier the blame for it.
     */
    private long countedTotal(List<DenominationCount> counts) {
        if (counts == null || counts.isEmpty()) {
            throw new RejectedException("A drawer count is required — an empty count is not a count of zero");
        }

        long total = 0;
        List<Long> seen = new ArrayList<>();
        for (DenominationCount entry : counts) {
            if (!LKR_DENOMINATIONS_MINOR.contains(entry.denominationMinor())) {
                throw new RejectedException(
                        "%d is not a circulating LKR denomination".formatted(entry.denominationMinor()));
            }
            if (seen.contains(entry.denominationMinor())) {
                throw new RejectedException(
                        "Denomination %d was counted twice".formatted(entry.denominationMinor()));
            }
            seen.add(entry.denominationMinor());
            total += entry.denominationMinor() * entry.qty();
        }
        return total;
    }

    /** Notes 5000/1000/500/100/50/20 and coins 10/5/2/1, in minor units. No sub-rupee: see M1-03. */
    private static final List<Long> LKR_DENOMINATIONS_MINOR =
            List.of(500_000L, 100_000L, 50_000L, 10_000L, 5_000L, 2_000L, 1_000L, 500L, 200L, 100L);

    private void insertCounts(long shiftId, String phase, List<DenominationCount> counts) {
        for (DenominationCount entry : counts) {
            jdbc.update(
                    """
                    INSERT INTO shift_counts (shift_id, phase, denomination_minor, qty)
                    VALUES (?, ?, ?, ?)
                    """,
                    shiftId,
                    phase,
                    entry.denominationMinor(),
                    entry.qty());
        }
    }

    // ----------------------------------------------------------------------- outbox

    /**
     * A shift syncs twice — once on open, once on close (M2-12).
     *
     * <p>Every aggregate before this one was immutable, so its outbox row was written once and the
     * cloud's upsert was a no-op on redelivery. A shift genuinely changes, so both deliveries carry
     * the full current state and the cloud's upsert is a real update — made monotonic there, so
     * that an open row arriving after a close row (possible: the open one may have failed and been
     * backed off) cannot reopen a reconciled shift. See the V203 header.
     */
    private void enqueueShift(long tenantId, UUID clientUuid, long shiftId) {
        outbox.enqueue(tenantId, "shift", clientUuid, buildPayload(shiftId));
    }

    private Map<String, Object> buildPayload(long shiftId) {
        Map<String, Object> payload =
                jdbc.queryForObject(
                        """
                        SELECT s.client_uuid, b.code AS branch_code, s.terminal_code, s.status,
                               s.opened_at, s.opening_float_minor, s.closed_at,
                               s.counted_cash_minor, s.expected_cash_minor, s.variance_minor,
                               s.variance_reason, s.variance_note,
                               opener.client_uuid  AS opened_by_client_uuid,
                               opener.display_name AS opened_by_name,
                               closer.client_uuid  AS closed_by_client_uuid,
                               closer.display_name AS closed_by_name
                          FROM shifts s
                          JOIN branches b ON b.id = s.branch_id
                          JOIN users opener ON opener.id = s.opened_by
                          LEFT JOIN users closer ON closer.id = s.closed_by
                         WHERE s.id = ?
                        """,
                        (rs, row) -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("clientUuid", rs.getObject("client_uuid", UUID.class));
                            p.put("branchCode", rs.getString("branch_code"));
                            p.put("terminalCode", rs.getString("terminal_code"));
                            p.put("status", rs.getString("status"));
                            p.put("openedAt", rs.getTimestamp("opened_at").toInstant().toString());
                            p.put("openingFloatMinor", rs.getLong("opening_float_minor"));
                            Timestamp closedAt = rs.getTimestamp("closed_at");
                            p.put("closedAt", closedAt == null ? null : closedAt.toInstant().toString());
                            p.put("countedCashMinor", rs.getObject("counted_cash_minor", Long.class));
                            p.put("expectedCashMinor", rs.getObject("expected_cash_minor", Long.class));
                            p.put("varianceMinor", rs.getObject("variance_minor", Long.class));
                            p.put("varianceReason", rs.getString("variance_reason"));
                            p.put("varianceNote", rs.getString("variance_note"));
                            // Who was on the till (M6-13). Absent from this payload until now, so
                            // the cloud has never known it and every shift already up there never
                            // will - which is why the columns it lands in are nullable.
                            //
                            // The uuid *and* the name, not one or the other. The uuid is the
                            // identity that survives a rename and joins to the synced user; the
                            // name is what the console shows when that user has not arrived yet,
                            // because the shift and the user are separate aggregates with no
                            // ordering between them. A console showing a blank where a cashier's
                            // name belongs, until some later batch happened to drain, would look
                            // broken rather than pending.
                            p.put(
                                    "openedByClientUuid",
                                    rs.getObject("opened_by_client_uuid", UUID.class));
                            p.put("openedByName", rs.getString("opened_by_name"));
                            p.put(
                                    "closedByClientUuid",
                                    rs.getObject("closed_by_client_uuid", UUID.class));
                            p.put("closedByName", rs.getString("closed_by_name"));
                            return p;
                        },
                        shiftId);

        // The denomination detail goes with it. An owner reading a LKR 5,000 shortfall in the
        // console wants to know whether it was one note or a hundred coins, and asking the till
        // for it later is exactly the round trip an offline-first design cannot make.
        List<Map<String, Object>> counts =
                jdbc.query(
                        """
                        SELECT phase, denomination_minor, qty FROM shift_counts
                         WHERE shift_id = ? ORDER BY phase, denomination_minor DESC
                        """,
                        (rs, row) -> {
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("phase", rs.getString("phase"));
                            c.put("denominationMinor", rs.getLong("denomination_minor"));
                            c.put("qty", rs.getInt("qty"));
                            return c;
                        },
                        shiftId);
        payload.put("counts", counts);
        return payload;
    }

    // ---------------------------------------------------------------------- lookups

    private Optional<ShiftResponse> findByClientUuid(UUID clientUuid) {
        List<Long> ids =
                jdbc.queryForList("SELECT id FROM shifts WHERE client_uuid = ?", Long.class, clientUuid);
        return ids.isEmpty() ? Optional.empty() : Optional.of(load(ids.get(0)));
    }

    @Transactional(readOnly = true)
    public ShiftResponse load(long shiftId) {
        return jdbc.queryForObject(
                """
                SELECT s.id, s.client_uuid, b.code AS branch_code, s.terminal_code, s.status,
                       s.opened_at, s.opening_float_minor, s.closed_at,
                       s.counted_cash_minor, s.expected_cash_minor, s.variance_minor,
                       s.variance_reason, s.variance_note
                  FROM shifts s JOIN branches b ON b.id = s.branch_id
                 WHERE s.id = ?
                """,
                (rs, row) ->
                        new ShiftResponse(
                                rs.getLong("id"),
                                rs.getObject("client_uuid", UUID.class),
                                rs.getString("branch_code"),
                                rs.getString("terminal_code"),
                                rs.getString("status"),
                                rs.getTimestamp("opened_at").toInstant(),
                                rs.getLong("opening_float_minor"),
                                rs.getTimestamp("closed_at") == null
                                        ? null
                                        : rs.getTimestamp("closed_at").toInstant(),
                                rs.getObject("counted_cash_minor", Long.class),
                                rs.getObject("expected_cash_minor", Long.class),
                                rs.getObject("variance_minor", Long.class),
                                rs.getString("variance_reason"),
                                rs.getString("variance_note")),
                shiftId);
    }

    private ShiftRow loadRow(long shiftId) {
        List<ShiftRow> rows =
                jdbc.query(
                        "SELECT id, client_uuid, tenant_id, branch_id, terminal_code, status FROM shifts WHERE id = ?",
                        (rs, row) ->
                                new ShiftRow(
                                        rs.getLong("id"),
                                        rs.getObject("client_uuid", UUID.class),
                                        rs.getLong("tenant_id"),
                                        rs.getLong("branch_id"),
                                        rs.getString("terminal_code"),
                                        rs.getString("status")),
                        shiftId);
        if (rows.isEmpty()) {
            throw new RejectedException("No such shift: " + shiftId);
        }
        return rows.get(0);
    }

    private int countSales(long shiftId) {
        return jdbc.queryForObject("SELECT count(*) FROM sales WHERE shift_id = ?", Integer.class, shiftId);
    }

    private int countCashMovements(long shiftId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM cash_movements WHERE shift_id = ?", Integer.class, shiftId);
    }

    record ShiftRow(long id, UUID clientUuid, long tenantId, long branchId, String terminalCode, String status) {}
}
