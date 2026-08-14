/**
 * VAT (M1-02, M1-05).
 *
 * The rule that must never bend: VAT is **extracted** from an inclusive price, never
 * multiplied onto it. `vat = total × rate ÷ (1 + rate)`, which in basis points is exactly
 * `total × bp ÷ (10000 + bp)` — integer throughout, no floating point anywhere.
 *
 * Multiplying a shelf price by the rate answers a different question. On LKR 900 at 18%
 * it gives 162.00 instead of 137.28: a 24.72 error per line, in the shop's favour, on
 * every sale, in a figure the revenue authority checks.
 */

import { minor, nonNegativeMinor, type Minor } from './money';

/** A tax rate in basis points. 18% VAT is 1800. Integers keep extraction exact. */
export type BasisPoints = number;

/**
 * Whether a quoted price already contains its tax.
 *
 * Sri Lankan retail quotes inclusive — the shelf price is what the customer pays — so
 * `INCLUSIVE` is the normal case and `EXCLUSIVE` exists for trade counters that quote net.
 */
export type TaxMode = 'INCLUSIVE' | 'EXCLUSIVE';

/**
 * The mode and rate in force when a sale was rung up (M1-05).
 *
 * Stamped onto the sale rather than read from configuration at display time. When the
 * rate changes — and it does — every historical receipt must still reprint with the
 * numbers it was issued with. A receipt that recalculates itself at today's rate is a
 * different document from the one the customer holds.
 */
export interface TaxStamp {
  readonly mode: TaxMode;
  readonly rateBp: BasisPoints;
}

export function assertRateBp(rateBp: number): BasisPoints {
  if (!Number.isInteger(rateBp) || rateBp < 0) {
    throw new RangeError(`Tax rate must be a whole number of basis points, got ${rateBp}`);
  }
  return rateBp;
}

export function taxStamp(mode: TaxMode, rateBp: number): TaxStamp {
  if (mode !== 'INCLUSIVE' && mode !== 'EXCLUSIVE') {
    throw new RangeError(`Unknown tax mode: ${String(mode)}`);
  }
  return { mode, rateBp: assertRateBp(rateBp) };
}

/**
 * The VAT contained in a tax-inclusive amount.
 *
 * Truncated, not rounded: the extracted tax can never exceed the amount it came out of,
 * which is the invariant the backend's checksum relies on (`taxMinor <= totalMinor`).
 * Rounding up could break it by a cent on the right input, and the sale would be
 * rejected at the till with the customer waiting.
 */
export function extractVatMinor(totalMinor: number, rateBp: number): Minor {
  const total = nonNegativeMinor(totalMinor, 'totalMinor');
  const rate = assertRateBp(rateBp);
  return minor(Math.floor((total * rate) / (10000 + rate)));
}

/** The VAT to add to a tax-exclusive amount. */
export function addVatMinor(netMinor: number, rateBp: number): Minor {
  const net = nonNegativeMinor(netMinor, 'netMinor');
  const rate = assertRateBp(rateBp);
  return minor(Math.floor((net * rate) / 10000));
}

/**
 * The tax embodied in an amount, whichever way the price is quoted.
 *
 * Note the asymmetry, which is not a bug: given `INCLUSIVE` the argument is the gross and
 * the answer is already inside it; given `EXCLUSIVE` the argument is the net and the
 * answer sits on top of it.
 */
export function taxForMinor(amountMinor: number, rateBp: number, mode: TaxMode): Minor {
  return mode === 'INCLUSIVE'
    ? extractVatMinor(amountMinor, rateBp)
    : addVatMinor(amountMinor, rateBp);
}

/**
 * The tax-inclusive amount for a quoted amount under a given stamp.
 *
 * Under `INCLUSIVE` the quoted price is already gross and this is the identity. Under
 * `EXCLUSIVE` the tax is added. Cart totals normalise to gross before anything else
 * happens — see `cart.ts` for why that keeps one invariant instead of two.
 */
export function grossForMinor(amountMinor: number, stamp: TaxStamp): Minor {
  const amount = nonNegativeMinor(amountMinor, 'amountMinor');
  return stamp.mode === 'INCLUSIVE' ? amount : minor(amount + addVatMinor(amount, stamp.rateBp));
}
