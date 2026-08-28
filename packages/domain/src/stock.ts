/**
 * Stock adjustments (M3-05).
 *
 * <h2>Why the reason is the whole feature</h2>
 *
 * An adjustment is the one place a shopkeeper can change stock without a document behind it — no
 * sale, no delivery, no customer. That makes it both necessary and the exact shape of the hole
 * somebody walks stock out through. The reason code is what turns "on hand dropped by 40" into
 * "40 went out as DAMAGED, entered by Nimal, on Tuesday", and it is the only reason M3-10 can ever
 * show an owner what shrinkage actually costs them.
 *
 * <h2>Nobody types a minus</h2>
 *
 * The sign belongs to the reason, not to the typist — the same rule `signedCashMovementMinor`
 * follows for the drawer. A shopkeeper entering "5 damaged" types 5, and DAMAGED is what makes it
 * −5. Asking a person to remember the sign means one day they forget, and stock moves by twice the
 * amount in the wrong direction with nothing to flag it.
 */

/** Why stock moved without a sale or a delivery behind it. */
export const STOCK_ADJUSTMENT_REASONS = [
  'DAMAGED',
  'EXPIRED',
  'THEFT',
  'OWN_USE',
  'RETURN_TO_SUPPLIER',
  'FOUND',
  'COUNT_CORRECTION',
  'OTHER',
] as const;

export type StockAdjustmentReason = (typeof STOCK_ADJUSTMENT_REASONS)[number];

/**
 * Which way each reason can move stock.
 *
 * `OUT` and `IN` are fixed: damaged goods never increase a shelf, and found goods never decrease
 * one. `EITHER` exists for the two cases where the direction genuinely is the shopkeeper's to
 * state — a miscount can go both ways, and `OTHER` is by definition unconstrained.
 */
export const ADJUSTMENT_DIRECTION: Record<StockAdjustmentReason, 'OUT' | 'IN' | 'EITHER'> = {
  DAMAGED: 'OUT',
  EXPIRED: 'OUT',
  THEFT: 'OUT',
  OWN_USE: 'OUT',
  RETURN_TO_SUPPLIER: 'OUT',
  FOUND: 'IN',
  COUNT_CORRECTION: 'EITHER',
  OTHER: 'EITHER',
};

/** What each reason means, in the words the form shows. */
export const ADJUSTMENT_REASON_LABEL: Record<StockAdjustmentReason, string> = {
  DAMAGED: 'Damaged or broken',
  EXPIRED: 'Past its date',
  THEFT: 'Missing or stolen',
  OWN_USE: 'Used by the shop',
  RETURN_TO_SUPPLIER: 'Sent back to the supplier',
  FOUND: 'Found — was on the shelf, not in the system',
  COUNT_CORRECTION: 'Correcting a miscount',
  OTHER: 'Something else',
};

/**
 * `OTHER` has to say what it was.
 *
 * Without it `OTHER` becomes the default everybody picks, and the reason code stops carrying any
 * information at all — the same rule M2-04 applies to an unexplained cash variance.
 */
export function adjustmentNeedsNote(reason: StockAdjustmentReason): boolean {
  return reason === 'OTHER';
}

/**
 * Turns the quantity somebody typed — always positive, they are entering "how many" — into the
 * signed delta the movement stores.
 *
 * `increase` is only consulted for an `EITHER` reason. Passing it for a fixed-direction reason is
 * not an error to correct silently: `DAMAGED` with `increase: true` means the form and the reason
 * disagree, and guessing which one the shopkeeper meant is how stock moves the wrong way.
 */
export function signedAdjustmentQty(
  reason: StockAdjustmentReason,
  qty: number,
  increase?: boolean,
): number {
  if (!Number.isInteger(qty)) {
    throw new RangeError(`A stock quantity must be a whole number, got ${qty}`);
  }
  if (qty <= 0) {
    throw new RangeError('A stock adjustment needs a quantity of at least 1');
  }

  const direction = ADJUSTMENT_DIRECTION[reason];

  if (direction === 'EITHER') {
    if (increase === undefined) {
      throw new RangeError(
        `${reason} can add or remove stock, so the direction has to be stated explicitly`,
      );
    }
    return increase ? qty : -qty;
  }

  const wantsIncrease = direction === 'IN';
  if (increase !== undefined && increase !== wantsIncrease) {
    throw new RangeError(
      `${reason} always ${wantsIncrease ? 'adds' : 'removes'} stock — it cannot do the opposite`,
    );
  }
  return wantsIncrease ? qty : -qty;
}

/**
 * Stock on hand: the sum of everything that ever moved.
 *
 * There is no level anywhere in this system and there never will be (see V100). This is the whole
 * calculation, and it being one line is the point — addition is commutative, so two offline tills
 * reconcile with no conflict logic and a redelivered batch that inserts nothing changes nothing.
 */
export function onHandFromMovements(qtyDeltas: readonly number[]): number {
  return qtyDeltas.reduce((total, delta) => total + delta, 0);
}
