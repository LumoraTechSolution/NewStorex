package com.lumora.pos.stock;

import com.lumora.pos.web.RejectedException;

/**
 * Why stock moved without a sale or a delivery behind it (M3-05).
 *
 * <h2>Mirrored, deliberately</h2>
 *
 * The same list exists in {@code @lumora/domain}'s {@code STOCK_ADJUSTMENT_REASONS}, which is where
 * the screen reads it from. This is not the money-math duplication §A forbids — there is no
 * arithmetic here, only a vocabulary — and the backend cannot delegate it: a reason code arrives
 * over HTTP from something this process does not control, so it has to be checked on the way in.
 * A test asserts the two lists agree, which is what stops the duplication becoming a divergence.
 *
 * <h2>The direction is part of the reason</h2>
 *
 * Damaged goods never increase a shelf and found goods never decrease one. Holding that here rather
 * than trusting the sign that arrived means a client that sends {@code DAMAGED, +5} is refused
 * instead of quietly putting stock on a shelf that is actually empty — which the next stocktake
 * would report as shrinkage that never happened.
 */
public enum AdjustmentReason {
    DAMAGED(Direction.OUT),
    EXPIRED(Direction.OUT),
    THEFT(Direction.OUT),
    OWN_USE(Direction.OUT),
    RETURN_TO_SUPPLIER(Direction.OUT),
    FOUND(Direction.IN),
    /** A miscount genuinely goes both ways. */
    COUNT_CORRECTION(Direction.EITHER),
    /** Unconstrained by definition, and the one reason that must carry a note. */
    OTHER(Direction.EITHER);

    public enum Direction {
        OUT,
        IN,
        EITHER
    }

    private final Direction direction;

    AdjustmentReason(Direction direction) {
        this.direction = direction;
    }

    public Direction direction() {
        return direction;
    }

    /** {@code OTHER} has to say what it was, or it becomes the reason everybody picks. */
    public boolean needsNote() {
        return this == OTHER;
    }

    /**
     * Parses a reason as it arrived from the screen.
     *
     * <p>Throws a {@link RejectedException} rather than an {@code IllegalArgumentException}: an
     * unknown reason is the caller's problem to fix and will never succeed on a retry, which is
     * exactly what a 422 means to the terminal.
     */
    public static AdjustmentReason of(String code) {
        if (code != null) {
            for (AdjustmentReason reason : values()) {
                if (reason.name().equals(code.trim())) {
                    return reason;
                }
            }
        }
        throw new RejectedException("Not a stock adjustment reason: " + code);
    }

    /**
     * Checks the sign a caller supplied against what the reason allows.
     *
     * <p>Takes the already-signed delta rather than a magnitude and a flag, because that is what
     * the wire carries and this is the boundary. The screen builds the sign with {@code
     * signedAdjustmentQty}; this is the half that assumes the screen might be lying.
     */
    public void requireConsistent(int qtyDelta) {
        if (qtyDelta == 0) {
            throw new RejectedException("A stock adjustment of zero is not a movement");
        }
        if (direction == Direction.OUT && qtyDelta > 0) {
            throw new RejectedException(
                    name() + " always removes stock — it cannot be used to add any");
        }
        if (direction == Direction.IN && qtyDelta < 0) {
            throw new RejectedException(name() + " always adds stock — it cannot be used to remove any");
        }
    }
}
