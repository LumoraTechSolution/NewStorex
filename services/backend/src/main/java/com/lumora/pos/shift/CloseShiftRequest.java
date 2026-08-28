package com.lumora.pos.shift;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;

/**
 * Closing a till: the blind count, and the reason if it did not balance (M2-02, M2-04).
 *
 * <p>The count arrives in the <em>same</em> request that asks what the drawer should have held.
 * That is the blind count's mechanism, not an accident of API design: there is no round trip in
 * which a cashier could learn the target and adjust the count to meet it. {@code ShiftService}
 * computes the expected figure for the first time after this payload is already fixed.
 *
 * @param operatorCode whoever is counting the drawer, and {@code operatorPin} their PIN (M3-08).
 *     Not required to be the person who opened the shift — a shift outlives the person who started
 *     it often enough that forcing a match would leave tills nobody can close. The handover is
 *     recorded in {@code closed_by} rather than hidden.
 * @param closingCount what was physically in the drawer, note by note.
 * @param countedCashMinor optional checksum over {@code closingCount}, exactly like the opening
 *     float. Never the count itself — a total with no denominations behind it is unauditable.
 * @param varianceReason required only when the variance exceeds the tenant's threshold (D1), which
 *     the caller cannot know in advance because it would have to be told the expected figure to
 *     work it out. So the flow is: submit the count, be told a reason is needed, submit again with
 *     one. That second round trip is the cost of the count being blind, and it is worth it.
 * @param varianceNote free text. Mandatory when the reason is {@code OTHER}, which is otherwise a
 *     way to record nothing while appearing to comply.
 */
public record CloseShiftRequest(
        @NotBlank String operatorCode,
        @NotBlank String operatorPin,
        Instant closedAt,
        Long countedCashMinor,
        @NotEmpty @Valid List<DenominationCount> closingCount,
        @Pattern(regexp = "MISCOUNT|FLOAT_ERROR|UNRECORDED_PAYOUT|CHANGE_GIVEN_WRONG|THEFT_SUSPECTED|OTHER")
                String varianceReason,
        String varianceNote) {}
