/**
 * Cash reconciliation (M2-01 … M2-05).
 *
 * ## The blind count is a property of what you are shown, not of what you compute
 *
 * M2-02 requires that the person counting the drawer never sees what it is supposed to hold.
 * That cannot be a rule inside a function — a pure module has no screen. What this module does
 * instead is make the blind version the *easy* one: {@link countedTotalMinor} takes a count and
 * nothing else, and {@link expectedCashMinor} takes the shift's entries and nothing else. The
 * two only meet in {@link reconcileCash}, which is called once, after the count is submitted.
 * There is no function here that could accidentally hand a counting screen the answer.
 *
 * The real enforcement is server-side, in the shift status endpoint, which does not return
 * expected cash while the shift is open. That is where a determined UI bug would otherwise get
 * the figure. This module's job is to not be the second way.
 *
 * ## Expected cash is Σ entries — no exceptions, no stored level
 *
 * §A again: balances are the sum of movements. Every term in {@link expectedCashMinor} is a
 * sum over rows the shift owns, and the function is a fold with no memory. A shift that ran for
 * nine hours and a shift that ran for one are the same computation.
 *
 * ## Why rounding shows up here
 *
 * M1-03 decided cash settles to the nearest rupee while the sale total stays exact, and noted
 * that the residual "is a real figure the cash-up screen (M2) has to account for rather than
 * hide". This is that screen's arithmetic. The drawer physically contains the *rounded* amounts,
 * so expected cash adds the rounding adjustments back in; leave them out and every shift shows a
 * small unexplained variance that trains cashiers to ignore the number.
 */

import {
  absMinor,
  addMinor,
  minor,
  multiplyMinor,
  nonNegativeMinor,
  subtractMinor,
  sumMinor,
  ZERO_MINOR,
  type Minor,
} from './money';

/**
 * What is actually in a Sri Lankan cash drawer, in minor units, largest first.
 *
 * Notes: 5000, 1000, 500, 100, 50, 20. Coins: 10, 5, 2, 1.
 *
 * There is no sub-rupee entry, and that is the same decision as `CASH_ROUNDING_INCREMENT_MINOR`
 * in `rounding.ts` seen from the other side: cent coins are out of circulation, a cashier cannot
 * count what is not in the drawer, and a count sheet with a "50c" row would invite someone to
 * reconcile a figure the rounding policy has already disposed of.
 *
 * Largest first because that is the order a cashier counts in and the order a count sheet is
 * printed in. The screen should not reorder it.
 */
export const LKR_DENOMINATIONS_MINOR: readonly number[] = [
  500_000, 100_000, 50_000, 10_000, 5_000, 2_000, 1_000, 500, 200, 100,
] as const;

export interface DenominationCount {
  /** Face value in minor units — 500000 is the LKR 5000 note. */
  readonly denominationMinor: number;
  /** How many of them. Zero is a legitimate, meaningful entry: it was counted and there were none. */
  readonly qty: number;
}

/**
 * Adds up a physical count.
 *
 * Takes the count and only the count. This is the blind half of M2-02 and the signature is the
 * guarantee: there is nothing to pass that could reveal what the drawer was supposed to hold.
 *
 * Unknown denominations are rejected rather than summed. A count containing a 250-rupee note is
 * not a drawer that is over — it is a corrupt payload or a UI sending face values in the wrong
 * unit, and quietly totalling it would turn that bug into a variance a cashier gets blamed for.
 */
export function countedTotalMinor(counts: readonly DenominationCount[]): Minor {
  const seen = new Set<number>();

  return sumMinor(
    counts.map((entry) => {
      const face = minor(entry.denominationMinor);
      if (!LKR_DENOMINATIONS_MINOR.includes(face)) {
        throw new RangeError(
          `${face} is not a circulating LKR denomination — expected one of ${LKR_DENOMINATIONS_MINOR.join(', ')}`,
        );
      }
      if (seen.has(face)) {
        throw new RangeError(`Denomination ${face} was counted twice`);
      }
      seen.add(face);

      if (!Number.isInteger(entry.qty) || entry.qty < 0) {
        throw new RangeError(
          `qty for denomination ${face} must be a whole number ≥ 0, got ${entry.qty}`,
        );
      }
      return multiplyMinor(face, entry.qty);
    }),
  );
}

/**
 * Every term that decides what the drawer should hold.
 *
 * Each is a sum over the shift's own rows. Named separately rather than pre-summed because the
 * cash-up screen and the Z-report both have to *show* the breakdown — a variance with no visible
 * derivation is a number nobody trusts and everybody overrides.
 */
export interface CashDrawerEntries {
  /** What the drawer started with. */
  readonly openingFloatMinor: number;
  /** Σ of every CASH tender line across the shift's sales — what was physically handed over. */
  readonly cashSalesMinor: number;
  /**
   * Σ of the change handed back. Subtracted, because {@link cashSalesMinor} is what came in
   * before change went out, not what stayed.
   */
  readonly cashChangeMinor: number;
  /**
   * Σ of the cash-rounding adjustments on the shift's sales (M1-03). Positive means customers
   * paid up over the exact totals, and that surplus is sitting in the drawer.
   */
  readonly cashRoundingMinor: number;
  /**
   * Σ of `cash_movements.amount_minor` — already signed, pay-ins positive and pay-outs and drops
   * negative (V107). Added, never branched on.
   */
  readonly cashMovementsMinor: number;
  /** Σ of every CASH refund line, as a positive magnitude. Subtracted here. */
  readonly cashRefundsMinor: number;
  /** Σ of the cash-rounding adjustments on those refunds. Signed, and subtracted with them. */
  readonly cashRefundRoundingMinor: number;
}

/**
 * What the drawer should hold, from the entries alone.
 *
 * May legitimately come out negative — a shift that dropped more to the safe than it took is
 * unusual but not impossible, and clamping it at zero would hide exactly the case worth seeing.
 */
export function expectedCashMinor(entries: CashDrawerEntries): Minor {
  const cashIn = sumMinor([
    nonNegativeMinor(entries.openingFloatMinor, 'openingFloatMinor'),
    nonNegativeMinor(entries.cashSalesMinor, 'cashSalesMinor'),
    minor(entries.cashRoundingMinor),
    minor(entries.cashMovementsMinor),
  ]);
  const cashOut = sumMinor([
    nonNegativeMinor(entries.cashChangeMinor, 'cashChangeMinor'),
    nonNegativeMinor(entries.cashRefundsMinor, 'cashRefundsMinor'),
    minor(entries.cashRefundRoundingMinor),
  ]);
  return subtractMinor(cashIn, cashOut);
}

export type VarianceDirection = 'BALANCED' | 'OVER' | 'SHORT';

export interface CashReconciliation {
  readonly expectedMinor: Minor;
  readonly countedMinor: Minor;
  /** `counted − expected`. Positive is over, negative is short. */
  readonly varianceMinor: Minor;
  readonly direction: VarianceDirection;
  /** The tenant's configured threshold (D1), echoed so the UI never has to fetch it twice. */
  readonly thresholdMinor: Minor;
  /** `|variance| > threshold`. M2-04 makes a reason mandatory when this is true. */
  readonly requiresReason: boolean;
}

/**
 * The one place expected and counted meet.
 *
 * Compared on the **absolute** variance. A drawer LKR 500 over is not a happy accident: it is
 * most often a sale that was never rung up, which is a worse problem than being LKR 500 short
 * and the one a threshold that only looked at shortfalls would wave through every time.
 */
export function reconcileCash(
  expectedMinor: number,
  countedMinor: number,
  thresholdMinor: number,
): CashReconciliation {
  const expected = minor(expectedMinor);
  const counted = nonNegativeMinor(countedMinor, 'countedMinor');
  const threshold = nonNegativeMinor(thresholdMinor, 'thresholdMinor');
  const variance = subtractMinor(counted, expected);

  return {
    expectedMinor: expected,
    countedMinor: counted,
    varianceMinor: variance,
    direction: variance === ZERO_MINOR ? 'BALANCED' : variance > 0 ? 'OVER' : 'SHORT',
    thresholdMinor: threshold,
    requiresReason: absMinor(variance) > threshold,
  };
}

/** The reasons a variance may be attributed to (M2-04). `OTHER` requires a written note. */
export const VARIANCE_REASONS = [
  'MISCOUNT',
  'FLOAT_ERROR',
  'UNRECORDED_PAYOUT',
  'CHANGE_GIVEN_WRONG',
  'THEFT_SUSPECTED',
  'OTHER',
] as const;

export type VarianceReason = (typeof VARIANCE_REASONS)[number];

/** Why cash moved in or out of the drawer other than by a sale (M2-05). */
export const CASH_MOVEMENT_REASONS = [
  'BANK_DROP',
  'SAFE_DROP',
  'SUPPLIER_PAYMENT',
  'PETTY_CASH',
  'CHANGE_FLOAT',
  'OWNER_DRAW',
  'OTHER',
] as const;

export type CashMovementReason = (typeof CASH_MOVEMENT_REASONS)[number];

export type CashMovementKind = 'PAY_IN' | 'PAY_OUT' | 'DROP';

/**
 * Turns an amount a cashier typed — always positive, they are entering "how much" — into the
 * signed value the table stores (V107).
 *
 * The sign belongs to the kind, not to the typist. A cashier who has to remember to type a minus
 * for a pay-out will one day forget, and the drawer will reconcile to a figure that is wrong by
 * twice the amount.
 */
export function signedCashMovementMinor(kind: CashMovementKind, amountMinor: number): Minor {
  const amount = nonNegativeMinor(amountMinor, 'amountMinor');
  if (amount === ZERO_MINOR) {
    throw new RangeError('A cash movement of zero is not a movement');
  }
  return kind === 'PAY_IN' ? amount : minor(-amount);
}

/**
 * Suggests how to make up an amount from the fewest notes and coins.
 *
 * Used for the change display, not for reconciliation — nothing here feeds a variance. Greedy is
 * exact for this denomination set (each is a whole multiple of the next), so the obvious loop is
 * also the optimal one.
 */
export function breakIntoDenominations(amountMinor: number): DenominationCount[] {
  let remaining = nonNegativeMinor(amountMinor, 'amountMinor') as number;
  const out: DenominationCount[] = [];

  for (const face of LKR_DENOMINATIONS_MINOR) {
    const qty = Math.floor(remaining / face);
    if (qty > 0) {
      out.push({ denominationMinor: face, qty });
      remaining -= qty * face;
    }
  }

  if (remaining !== 0) {
    // Only reachable for a sub-rupee amount, which the rounding policy says cannot be paid
    // out of a drawer. Silence here would be a change display that is quietly short.
    throw new RangeError(
      `${amountMinor} cannot be made from circulating denominations — ${remaining} left over. ` +
        'Cash amounts must be whole rupees; round with roundCashMinor first.',
    );
  }
  return out;
}

/** Convenience for the Z-report: the drawer's net movement from pay-ins, pay-outs and drops. */
export function netCashMovementMinor(amountsMinor: readonly number[]): Minor {
  return amountsMinor.reduce<Minor>((total, amount) => addMinor(total, minor(amount)), ZERO_MINOR);
}
