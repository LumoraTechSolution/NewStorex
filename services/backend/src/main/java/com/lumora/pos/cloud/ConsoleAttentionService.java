package com.lumora.pos.cloud;

import com.lumora.pos.web.RejectedException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening a cash variance, and saying somebody looked at it (M6-10).
 *
 * <h2>The screen this exists to fix</h2>
 *
 * M4-07's feed counts eleven variances and offers nothing to do about any of them. No way to open
 * one, no way to say "I checked, it was my mistake", no way to make it go away — so it counts to
 * eleven, then twelve, then twenty, and the owner stops looking. An alert nobody can clear is not an
 * alert; it is wallpaper, and it kills the one screen the console exists for.
 *
 * <h2>A service of its own because this one writes</h2>
 *
 * {@link ConsoleReportService} is read-only and every method on it says so. This is the console's
 * <b>only</b> write, and keeping it in its own class is what stops that fact from becoming a
 * footnote — see {@code V214} on why the widening is acceptable and {@link AuthenticatedPrincipal}
 * for what a console session may still not do.
 *
 * <h2>The detail is the denominations</h2>
 *
 * "Rs. 5,000 short" is the alarm. What an owner actually wants next is whether it was one note or a
 * hundred coins, because those are different conversations with a different person. {@code
 * shift_counts} has carried that since V203 and nothing has ever read it.
 */
@Service
@Profile("cloud")
public class ConsoleAttentionService {

    private final JdbcTemplate jdbc;

    public ConsoleAttentionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One denomination in a count: what was counted, and how many of them. */
    public record CountedDenomination(String phase, long denominationMinor, int qty) {}

    /**
     * Everything held about one variance.
     *
     * @param acknowledgedAt null until somebody has looked at it. The presence of this field is
     *     what lets the same endpoint serve both the open list and the reviewed one.
     */
    public record VarianceDetail(
            String shiftClientUuid,
            String branchCode,
            String terminalCode,
            Instant openedAt,
            Instant closedAt,
            long openingFloatMinor,
            long countedCashMinor,
            long expectedCashMinor,
            long varianceMinor,
            String varianceReason,
            String varianceNote,
            List<CountedDenomination> counts,
            Instant acknowledgedAt,
            String acknowledgedBy,
            String acknowledgementNote) {}

    @Transactional(readOnly = true)
    public VarianceDetail detail(long tenantId, UUID shiftClientUuid) {
        List<VarianceDetail> found =
                jdbc.query(
                        """
                        SELECT s.client_uuid, s.branch_code, s.terminal_code, s.opened_at, s.closed_at,
                               s.opening_float_minor, s.counted_cash_minor, s.expected_cash_minor,
                               s.variance_minor, s.variance_reason, s.variance_note,
                               a.acknowledged_at, a.note AS ack_note, u.display_name AS ack_by
                          FROM shifts s
                          LEFT JOIN shift_acknowledgements a
                                 ON a.tenant_id = s.tenant_id AND a.shift_client_uuid = s.client_uuid
                          LEFT JOIN console_users u ON u.id = a.acknowledged_by
                         WHERE s.tenant_id = ? AND s.client_uuid = ?
                        """,
                        (rs, row) ->
                                new VarianceDetail(
                                        rs.getString("client_uuid"),
                                        rs.getString("branch_code"),
                                        rs.getString("terminal_code"),
                                        instant(rs.getTimestamp("opened_at")),
                                        instant(rs.getTimestamp("closed_at")),
                                        rs.getLong("opening_float_minor"),
                                        rs.getLong("counted_cash_minor"),
                                        rs.getLong("expected_cash_minor"),
                                        rs.getLong("variance_minor"),
                                        rs.getString("variance_reason"),
                                        rs.getString("variance_note"),
                                        List.of(),
                                        instant(rs.getTimestamp("acknowledged_at")),
                                        rs.getString("ack_by"),
                                        rs.getString("ack_note")),
                        tenantId,
                        shiftClientUuid);

        if (found.isEmpty()) {
            // The same answer for "no such shift" and "another shop's shift", deliberately. A
            // distinguishable 404 tells a caller which uuids exist somewhere else on the system.
            throw new RejectedException("No such shift");
        }

        VarianceDetail shift = found.get(0);
        List<CountedDenomination> counts =
                jdbc.query(
                        """
                        SELECT c.phase, c.denomination_minor, c.qty
                          FROM shift_counts c
                          JOIN shifts s ON s.id = c.shift_id
                         WHERE s.tenant_id = ? AND s.client_uuid = ?
                         ORDER BY c.phase, c.denomination_minor DESC
                        """,
                        (rs, row) ->
                                new CountedDenomination(
                                        rs.getString("phase"),
                                        rs.getLong("denomination_minor"),
                                        rs.getInt("qty")),
                        tenantId,
                        shiftClientUuid);

        return new VarianceDetail(
                shift.shiftClientUuid(),
                shift.branchCode(),
                shift.terminalCode(),
                shift.openedAt(),
                shift.closedAt(),
                shift.openingFloatMinor(),
                shift.countedCashMinor(),
                shift.expectedCashMinor(),
                shift.varianceMinor(),
                shift.varianceReason(),
                shift.varianceNote(),
                counts,
                shift.acknowledgedAt(),
                shift.acknowledgedBy(),
                shift.acknowledgementNote());
    }

    /**
     * Records that somebody looked at a variance.
     *
     * <p>Idempotent, and quietly so. A double-pressed button and a retried request are the same act,
     * and the first acknowledgement is the one that counts — replacing it would rewrite who reviewed
     * a variance and when, which is the only thing this row is for.
     *
     * <p>The shift must exist <em>and belong to this shop</em>, checked before the insert rather
     * than left to a foreign key. There is no foreign key here on purpose (see {@code V214}), so
     * without this check a console session could write an acknowledgement for any uuid at all.
     */
    @Transactional
    public VarianceDetail acknowledge(
            long tenantId, UUID shiftClientUuid, long consoleSessionId, String note) {
        detail(tenantId, shiftClientUuid); // throws if it is not this shop's shift

        // The row names a person; the request carries a session. Resolved here rather than by
        // widening AuthenticatedPrincipal, whose `credentialId` means "the credential presented"
        // for all three kinds and would stop meaning one thing if this one started meaning a user.
        // Scoped by tenant as well as by id: a session id is not a capability to write into
        // whichever shop the path happens to name.
        Long consoleUserId =
                jdbc.queryForObject(
                        "SELECT console_user_id FROM console_sessions WHERE id = ? AND tenant_id = ?",
                        Long.class,
                        consoleSessionId,
                        tenantId);
        if (consoleUserId == null) {
            throw new RejectedException("That session is no longer valid");
        }

        try {
            jdbc.update(
                    """
                    INSERT INTO shift_acknowledgements
                        (tenant_id, shift_client_uuid, acknowledged_by, note)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (tenant_id, shift_client_uuid) DO NOTHING
                    """,
                    tenantId,
                    shiftClientUuid,
                    consoleUserId,
                    note == null || note.isBlank() ? null : note.trim());
        } catch (DuplicateKeyException alreadyReviewed) {
            // Belt and braces with the ON CONFLICT above: two phones pressing at once.
        }
        return detail(tenantId, shiftClientUuid);
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
