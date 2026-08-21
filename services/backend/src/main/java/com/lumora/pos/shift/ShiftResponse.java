package com.lumora.pos.shift;

import java.time.Instant;
import java.util.UUID;

/**
 * A shift as recorded.
 *
 * <p>Everything from {@code countedCashMinor} down is null while the shift is open and set once it
 * closes — the same all-or-nothing the {@code ck_shifts_closed_is_complete} constraint enforces in
 * the schema. This type is returned from {@code open} and {@code close}; the thing a terminal
 * polls while trading is {@link ShiftStatusResponse}, which deliberately cannot carry the expected
 * figure at all.
 */
public record ShiftResponse(
        long id,
        UUID clientUuid,
        String branchCode,
        String terminalCode,
        String status,
        Instant openedAt,
        long openingFloatMinor,
        Instant closedAt,
        Long countedCashMinor,
        Long expectedCashMinor,
        Long varianceMinor,
        String varianceReason,
        String varianceNote) {}
