/**
 * Money, in integer minor units.
 *
 * A `Minor` is a whole number of cents. Never a float: 0.1 + 0.2 is not 0.3 in binary
 * floating point, and a till that is a cent out on some sales is worse than one that is
 * obviously broken, because nobody notices for months.
 *
 * This is the narrow slice the M0 spike needs. M1-01 introduces the full `Money` type,
 * M1-03 adds discounts and the LKR rounding policy, and M1-04 covers the lot with
 * property-based tests. Everything added here must keep the same two properties: integer
 * arithmetic only, and no I/O.
 */

/** An amount in the currency's minor unit — cents for LKR. Always an integer. */
export type Minor = number;

/** A tax rate in basis points. 18% VAT is 1800. Integers keep extraction exact. */
export type BasisPoints = number;

export type TaxMode = 'INCLUSIVE' | 'EXCLUSIVE';

function assertInteger(value: number, name: string): void {
  if (!Number.isInteger(value)) {
    throw new RangeError(`${name} must be an integer number of minor units, got ${value}`);
  }
}

function assertNonNegative(value: number, name: string): void {
  if (value < 0) {
    throw new RangeError(`${name} must not be negative, got ${value}`);
  }
}

/** What a line of the cart comes to, before any order-level discount. */
export function lineTotalMinor(unitPriceMinor: Minor, qty: number): Minor {
  assertInteger(unitPriceMinor, 'unitPriceMinor');
  assertInteger(qty, 'qty');
  assertNonNegative(unitPriceMinor, 'unitPriceMinor');
  if (qty < 1) {
    throw new RangeError(`qty must be at least 1, got ${qty}`);
  }
  return unitPriceMinor * qty;
}

/**
 * The VAT contained in a tax-inclusive amount.
 *
 * `vat = total × rate ÷ (1 + rate)`, which with basis points is exactly
 * `total × bp ÷ (10000 + bp)` — all integer, no floating point anywhere.
 *
 * VAT is *extracted* from an inclusive price, never multiplied onto it. Multiplying a
 * shelf price by the rate answers a different question and gives a different, wrong,
 * number.
 */
export function extractVatMinor(totalMinor: Minor, rateBp: BasisPoints): Minor {
  assertInteger(totalMinor, 'totalMinor');
  assertInteger(rateBp, 'rateBp');
  assertNonNegative(totalMinor, 'totalMinor');
  assertNonNegative(rateBp, 'rateBp');

  // Truncation, not rounding: the extracted tax can never exceed the amount it came out
  // of, which is the invariant the backend's checksum relies on.
  return Math.floor((totalMinor * rateBp) / (10000 + rateBp));
}

/** The VAT to add to a tax-exclusive amount. */
export function addVatMinor(netMinor: Minor, rateBp: BasisPoints): Minor {
  assertInteger(netMinor, 'netMinor');
  assertInteger(rateBp, 'rateBp');
  assertNonNegative(netMinor, 'netMinor');
  assertNonNegative(rateBp, 'rateBp');

  return Math.floor((netMinor * rateBp) / 10000);
}

/** The tax embodied in an amount, whichever way the price is quoted. */
export function taxForMinor(amountMinor: Minor, rateBp: BasisPoints, mode: TaxMode): Minor {
  return mode === 'INCLUSIVE'
    ? extractVatMinor(amountMinor, rateBp)
    : addVatMinor(amountMinor, rateBp);
}

/** Formats minor units for display. Grouping only — no currency symbol, no rounding. */
export function formatMinor(amountMinor: Minor): string {
  assertInteger(amountMinor, 'amountMinor');
  const negative = amountMinor < 0;
  const absolute = Math.abs(amountMinor);
  const major = Math.floor(absolute / 100);
  const cents = absolute % 100;
  const grouped = major.toLocaleString('en-LK');
  return `${negative ? '-' : ''}${grouped}.${cents.toString().padStart(2, '0')}`;
}
