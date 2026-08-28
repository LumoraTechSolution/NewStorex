import { describe, expect, it } from 'vitest';

import {
  ADJUSTMENT_DIRECTION,
  ADJUSTMENT_REASON_LABEL,
  adjustmentNeedsNote,
  onHandFromMovements,
  signedAdjustmentQty,
  STOCK_ADJUSTMENT_REASONS,
  type StockAdjustmentReason,
} from './stock';

/**
 * Stock adjustments (M3-05).
 *
 * <p>Almost every test here is about the sign. An adjustment is the one stock change with no
 * document behind it, and the failure that matters is not a crash — it is stock moving the wrong
 * way by a plausible amount, which nothing downstream can tell from a real movement.
 */
describe('signedAdjustmentQty — the sign belongs to the reason', () => {
  it('makes every outward reason negative from a positive quantity', () => {
    for (const reason of STOCK_ADJUSTMENT_REASONS) {
      if (ADJUSTMENT_DIRECTION[reason] !== 'OUT') continue;
      expect(signedAdjustmentQty(reason, 5)).toBe(-5);
    }
  });

  it('makes an inward reason positive', () => {
    expect(signedAdjustmentQty('FOUND', 3)).toBe(3);
  });

  /**
   * The mistake this function exists to prevent.
   *
   * A form that let DAMAGED add stock would be a form where a typo puts goods on a shelf that is
   * actually empty, and the next stocktake reports shrinkage that never happened.
   */
  it('refuses a direction that contradicts the reason', () => {
    expect(() => signedAdjustmentQty('DAMAGED', 5, true)).toThrow(/cannot do the opposite/);
    expect(() => signedAdjustmentQty('FOUND', 5, false)).toThrow(/cannot do the opposite/);
  });

  it('accepts a direction that merely agrees with the reason', () => {
    expect(signedAdjustmentQty('DAMAGED', 5, false)).toBe(-5);
    expect(signedAdjustmentQty('FOUND', 5, true)).toBe(5);
  });

  /** A miscount genuinely goes both ways, so the caller has to say which. */
  it('requires an explicit direction for a reason that can go either way', () => {
    expect(signedAdjustmentQty('COUNT_CORRECTION', 4, true)).toBe(4);
    expect(signedAdjustmentQty('COUNT_CORRECTION', 4, false)).toBe(-4);
    expect(() => signedAdjustmentQty('COUNT_CORRECTION', 4)).toThrow(/stated explicitly/);
    expect(() => signedAdjustmentQty('OTHER', 4)).toThrow(/stated explicitly/);
  });

  it('refuses zero, because a movement of nothing is not a movement', () => {
    expect(() => signedAdjustmentQty('DAMAGED', 0)).toThrow(/at least 1/);
  });

  /** The typist enters "how many". A negative here means the caller already applied a sign. */
  it('refuses a negative quantity rather than quietly taking its absolute value', () => {
    expect(() => signedAdjustmentQty('DAMAGED', -5)).toThrow(/at least 1/);
  });

  /** Whole units. Selling by weight would need a deliberate decision, not a quiet float. */
  it('refuses a fractional quantity', () => {
    expect(() => signedAdjustmentQty('DAMAGED', 1.5)).toThrow(/whole number/);
  });
});

describe('the reason list itself', () => {
  /** Every reason has to be renderable and directed, or the form cannot draw it. */
  it('gives every reason a direction and a label', () => {
    for (const reason of STOCK_ADJUSTMENT_REASONS) {
      expect(ADJUSTMENT_DIRECTION[reason as StockAdjustmentReason]).toBeDefined();
      expect(ADJUSTMENT_REASON_LABEL[reason as StockAdjustmentReason]).toBeTruthy();
    }
  });

  /**
   * `OTHER` must say what it was.
   *
   * Without that rule OTHER becomes the reason everybody picks, and the code stops carrying
   * information — the same argument M2-04 makes about an unexplained cash variance.
   */
  it('requires a note only for OTHER', () => {
    expect(adjustmentNeedsNote('OTHER')).toBe(true);
    for (const reason of STOCK_ADJUSTMENT_REASONS) {
      if (reason === 'OTHER') continue;
      expect(adjustmentNeedsNote(reason)).toBe(false);
    }
  });

  /** Shrinkage reporting (M3-10) needs at least one reason that means goods were lost. */
  it('keeps the reasons that shrinkage reporting depends on', () => {
    expect(STOCK_ADJUSTMENT_REASONS).toContain('DAMAGED');
    expect(STOCK_ADJUSTMENT_REASONS).toContain('THEFT');
    expect(STOCK_ADJUSTMENT_REASONS).toContain('EXPIRED');
  });
});

describe('onHandFromMovements', () => {
  it('is the sum, and nothing else', () => {
    expect(onHandFromMovements([24, -3, -1, 5])).toBe(25);
  });

  it('is zero for a product that has never moved', () => {
    expect(onHandFromMovements([])).toBe(0);
  });

  /**
   * Order does not matter, which is the property the whole architecture rests on: two offline
   * tills reconcile by addition with no conflict logic at all.
   */
  it('does not depend on the order the movements arrived in', () => {
    const movements = [24, -3, 10, -1, -30, 5];
    const reversed = [...movements].reverse();
    expect(onHandFromMovements(reversed)).toBe(onHandFromMovements(movements));
  });

  /** Negative on hand is a real state — a sale entered before its delivery was booked in. */
  it('can go negative rather than clamping at zero', () => {
    expect(onHandFromMovements([-2])).toBe(-2);
  });
});
