/**
 * Tendering a sale (M1-11): multi-tender, split payment, change.
 *
 * ## Only cash makes change
 *
 * A card or wallet charge is exact — there is no receipt either side holds for "the terminal
 * overcharged you by 40 cents, here is your change on the card." So a non-cash tender line may
 * never exceed what is still owed, and the only thing that produces change is cash handed over
 * in excess of what cash needs to settle.
 *
 * ## Rounding still belongs to the payment, not the sale
 *
 * {@link roundCashMinor} (M1-03) already decided that cash settles to the nearest rupee while the
 * sale total stays exact. This module is what that policy looks like once a sale can be split
 * across several tenders: only the *cash-covered remainder* — the total less whatever non-cash
 * tenders already paid — gets rounded, never the sale total or a card line.
 */

import {
  addMinor,
  maxMinor,
  nonNegativeMinor,
  subtractMinor,
  sumMinor,
  ZERO_MINOR,
  type Minor,
} from './money';
import { cashRoundingDeltaMinor, payableMinor, tenderRounds, type TenderKind } from './rounding';

export type { TenderKind } from './rounding';

export interface TenderLine {
  readonly kind: TenderKind;
  /**
   * For CASH, what the customer physically handed over — may exceed what is owed, producing
   * change. For every other kind, the exact amount charged; it may never exceed what is owed,
   * since nothing but cash can hand change back.
   */
  readonly amountMinor: Minor;
}

export interface TenderSummary {
  readonly totalDueMinor: Minor;
  /** Sum of every non-cash line. Never rounded — the customer disputes anything else. */
  readonly nonCashAppliedMinor: Minor;
  /** Sum of every cash line, before change is taken out. */
  readonly cashReceivedMinor: Minor;
  /** What cash still owes, before rounding — `totalDue − nonCashApplied`. */
  readonly cashOwedMinor: Minor;
  /** {@link cashOwedMinor} rounded to the nearest rupee — what the drawer must actually collect. */
  readonly cashPayableMinor: Minor;
  /** `cashPayable − cashOwed`. Positive: the customer paid more because of rounding. */
  readonly roundingAdjustmentMinor: Minor;
  /** What the till hands back. Zero unless cash received exceeds what cash owes. */
  readonly changeDueMinor: Minor;
  /** What is still unpaid. Zero once the sale is fully tendered. */
  readonly remainingDueMinor: Minor;
  /** `remainingDueMinor === 0` — the sale can be committed. */
  readonly settled: boolean;
}

/**
 * Folds a set of tender lines against the sale total.
 *
 * Pure and order-independent: it does not matter whether the cashier entered card first or cash
 * first, only the totals per kind. Throws rather than silently clamping if a non-cash line (or
 * their sum) overshoots what is owed — that is a cashier or UI bug, not a sale the domain can
 * price, and the till should say so immediately rather than quietly wrong-footing the change.
 */
export function summariseTender(
  totalDueMinor: number,
  tenders: readonly TenderLine[],
): TenderSummary {
  const totalDue = nonNegativeMinor(totalDueMinor, 'totalDueMinor');

  const nonCashLines = tenders.filter((t) => !tenderRounds(t.kind));
  const cashLines = tenders.filter((t) => tenderRounds(t.kind));

  const nonCashAppliedMinor = sumMinor(
    nonCashLines.map((t) => nonNegativeMinor(t.amountMinor, `${t.kind} amountMinor`)),
  );
  if (nonCashAppliedMinor > totalDue) {
    throw new RangeError(
      `Non-cash tenders total ${nonCashAppliedMinor} but only ${totalDue} is owed — ` +
        'card and wallet payments cannot make change.',
    );
  }

  const cashReceivedMinor = sumMinor(
    cashLines.map((t) => nonNegativeMinor(t.amountMinor, 'CASH amountMinor')),
  );

  const cashOwedMinor = subtractMinor(totalDue, nonCashAppliedMinor);
  const cashPayableMinor = payableMinor(cashOwedMinor, 'CASH');
  const roundingAdjustmentMinor = cashRoundingDeltaMinor(cashOwedMinor);

  const changeDueMinor =
    cashReceivedMinor > cashPayableMinor
      ? subtractMinor(cashReceivedMinor, cashPayableMinor)
      : ZERO_MINOR;
  const remainingDueMinor =
    cashPayableMinor > cashReceivedMinor
      ? subtractMinor(cashPayableMinor, cashReceivedMinor)
      : ZERO_MINOR;

  return {
    totalDueMinor: totalDue,
    nonCashAppliedMinor,
    cashReceivedMinor,
    cashOwedMinor,
    cashPayableMinor,
    roundingAdjustmentMinor,
    changeDueMinor,
    remainingDueMinor,
    settled: remainingDueMinor === ZERO_MINOR,
  };
}

/**
 * What to put in the amount field when a cashier picks a tender kind, before they type anything.
 *
 * For CASH this is the rounded balance still owed — pressing Enter on the suggestion tenders
 * exact cash, which is the common case and the fast path Gate M1 is timed on. For everything
 * else it is the exact unrounded remainder, clamped at zero: a card can only ever cover what is
 * left, never round it up or down.
 */
export function suggestedTenderAmountMinor(
  totalDueMinor: number,
  tendersSoFar: readonly TenderLine[],
  kind: TenderKind,
): Minor {
  const summary = summariseTender(totalDueMinor, tendersSoFar);
  if (tenderRounds(kind)) {
    return summary.remainingDueMinor;
  }
  const appliedSoFar = addMinor(summary.nonCashAppliedMinor, summary.cashReceivedMinor);
  const unroundedRemainder = subtractMinor(summary.totalDueMinor, appliedSoFar);
  return maxMinor(unroundedRemainder, ZERO_MINOR);
}
