package com.lumora.pos.cash;

import java.time.Instant;
import java.util.UUID;

/**
 * A recorded cash movement.
 *
 * @param amountMinor signed as stored: positive for a pay-in, negative for a pay-out or a drop. The
 *     terminal is echoed the signed value rather than the magnitude it sent, so a screen listing a
 *     shift's movements is showing exactly what the drawer arithmetic will use.
 */
public record CashMovementResponse(
        long id,
        UUID clientUuid,
        long shiftId,
        String kind,
        long amountMinor,
        String reasonCode,
        String note,
        Instant occurredAt,
        boolean alreadyExisted) {}
