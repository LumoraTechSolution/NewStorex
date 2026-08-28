package com.lumora.pos.cash;

import com.lumora.pos.outbox.OutboxWriter;
import com.lumora.pos.shift.ShiftService;
import com.lumora.pos.shop.LocalShop;
import com.lumora.pos.web.RejectedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash in and out of the drawer other than by a sale (M2-05).
 *
 * <p>Three kinds, one table, one arithmetic. A pay-in is the owner putting change in; a pay-out is
 * paying a supplier from the till; a drop is cash going to the safe or the bank. What they have in
 * common is that the drawer's contents changed without a sale, and a shift that cannot account for
 * that is a shift whose variance means nothing.
 *
 * <h2>The sign belongs to the kind</h2>
 *
 * The request carries a positive magnitude — a cashier types how much, not which way — and this
 * service applies the sign. The column then holds a signed value that a CHECK ties to the kind
 * (V107), so expected cash is a plain {@code SUM} with no {@code CASE} and a pay-out that adds to
 * the drawer cannot be represented at all.
 *
 * <p>Mirrors {@code signedCashMovementMinor} in {@code @lumora/domain}, which the terminal uses to
 * show the cashier the effect before they commit. Both are one conditional, and the constraint in
 * the database is what makes them agree rather than either being trusted.
 */
@Service
public class CashMovementService {

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final LocalShop shop;
    private final ShiftService shifts;

    public CashMovementService(
            JdbcTemplate jdbc, OutboxWriter outbox, LocalShop shop, ShiftService shifts) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.shop = shop;
        this.shifts = shifts;
    }

    @Transactional
    public CashMovementResponse record(CreateCashMovementRequest request) {
        CashMovementResponse existing = findByClientUuid(request.clientUuid());
        if (existing != null) {
            return existing;
        }

        if ("OTHER".equals(request.reasonCode())
                && (request.note() == null || request.note().isBlank())) {
            throw new RejectedException(
                    "A cash movement with reason OTHER requires a note saying what it was for");
        }

        LocalShop.Branch branch = shop.branch(request.branchCode());
        ShiftService.OpenShift shift =
                shifts.requireOpenShift(branch.tenantId(), branch.id(), request.terminalCode());
        long shiftId = shift.id();

        long signedMinor = signed(request.kind(), request.amountMinor());
        Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : Instant.now();

        // Not wrapped in a catch for a duplicate client_uuid: a constraint violation aborts the
        // transaction, so there would be nothing left to read the winner back with. The retry at
        // the top of this method is what resolves a race — see SaleService for the same note.
        long id =
                    jdbc.queryForObject(
                            """
                            INSERT INTO cash_movements (
                                client_uuid, tenant_id, branch_id, shift_id,
                                kind, amount_minor, reason_code, note, created_by, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            RETURNING id
                            """,
                            Long.class,
                            request.clientUuid(),
                            branch.tenantId(),
                            branch.id(),
                            shiftId,
                            request.kind(),
                            signedMinor,
                            request.reasonCode(),
                            request.note(),
                            shift.operatorId(),
                            Timestamp.from(occurredAt));

        // M2-12. Same transaction as the row it describes, like every other aggregate: a drop to
        // the safe that reached the cloud without its shift, or a shift whose drops never arrived,
        // would each show the owner a drawer that never existed.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientUuid", request.clientUuid());
        payload.put("branchCode", branch.code());
        payload.put("shiftClientUuid", shiftClientUuid(shiftId));
        payload.put("kind", request.kind());
        payload.put("amountMinor", signedMinor);
        payload.put("reasonCode", request.reasonCode());
        payload.put("note", request.note());
        payload.put("occurredAt", occurredAt.toString());
        outbox.enqueue(branch.tenantId(), "cash_movement", request.clientUuid(), payload);

        return new CashMovementResponse(
                id,
                request.clientUuid(),
                shiftId,
                request.kind(),
                signedMinor,
                request.reasonCode(),
                request.note(),
                occurredAt,
                false);
    }

    /** What a shift's drawer has taken in and paid out, newest first. Reads the sign as stored. */
    @Transactional(readOnly = true)
    public List<CashMovementResponse> forShift(long shiftId) {
        return jdbc.query(
                """
                SELECT id, client_uuid, shift_id, kind, amount_minor, reason_code, note, created_at
                  FROM cash_movements WHERE shift_id = ? ORDER BY created_at DESC, id DESC
                """,
                (rs, row) ->
                        new CashMovementResponse(
                                rs.getLong("id"),
                                rs.getObject("client_uuid", UUID.class),
                                rs.getLong("shift_id"),
                                rs.getString("kind"),
                                rs.getLong("amount_minor"),
                                rs.getString("reason_code"),
                                rs.getString("note"),
                                rs.getTimestamp("created_at").toInstant(),
                                false),
                shiftId);
    }

    private static long signed(String kind, long amountMinor) {
        if (amountMinor <= 0) {
            throw new RejectedException("A cash movement must be a positive amount — the kind carries the sign");
        }
        return "PAY_IN".equals(kind) ? amountMinor : -amountMinor;
    }

    private UUID shiftClientUuid(long shiftId) {
        return jdbc.queryForObject("SELECT client_uuid FROM shifts WHERE id = ?", UUID.class, shiftId);
    }

    private CashMovementResponse findByClientUuid(UUID clientUuid) {
        List<CashMovementResponse> found =
                jdbc.query(
                        """
                        SELECT id, client_uuid, shift_id, kind, amount_minor, reason_code, note, created_at
                          FROM cash_movements WHERE client_uuid = ?
                        """,
                        (rs, row) ->
                                new CashMovementResponse(
                                        rs.getLong("id"),
                                        rs.getObject("client_uuid", UUID.class),
                                        rs.getLong("shift_id"),
                                        rs.getString("kind"),
                                        rs.getLong("amount_minor"),
                                        rs.getString("reason_code"),
                                        rs.getString("note"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        true),
                        clientUuid);
        return found.isEmpty() ? null : found.get(0);
    }
}
