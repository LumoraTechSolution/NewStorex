package com.lumora.pos.shift;

import java.time.Instant;
import java.util.UUID;

/**
 * What the terminal may know about a shift while it is running.
 *
 * <p>There is no expected-cash field here, and that absence is M2-02's enforcement rather than an
 * oversight. A blind count that relies on the screen choosing not to display a figure it was sent
 * is blind only until somebody opens the network tab. This is the only shift endpoint a trading
 * terminal calls, so the figure is not merely hidden — it has not been computed.
 *
 * <p>Adding it "just for the manager view" would undo the milestone. A manager who wants the number
 * before the count closes the shift and reads the Z-report, which is the audited path.
 *
 * @param saleCount and {@code cashMovementCount} are activity, not money. They tell a cashier the
 *     till is theirs and roughly how busy it has been, and neither narrows down the drawer total.
 */
public record ShiftStatusResponse(
        boolean open,
        Long shiftId,
        UUID clientUuid,
        Instant openedAt,
        Long openingFloatMinor,
        Integer saleCount,
        Integer cashMovementCount) {

    public static ShiftStatusResponse closed() {
        return new ShiftStatusResponse(false, null, null, null, null, null, null);
    }
}
