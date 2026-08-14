/**
 * Money, in integer minor units (M1-01).
 *
 * A `Minor` is a whole number of cents. Never a float: 0.1 + 0.2 is not 0.3 in binary
 * floating point, and a till that is a cent out on some sales is worse than one that is
 * obviously broken, because nobody notices for months.
 *
 * ## Why a branded number and not a class
 *
 * `Minor` is a `number` at runtime carrying a phantom brand at compile time. That buys
 * the thing a plain alias cannot: a raw `number` will not typecheck where money is
 * expected, so a price that arrived from JSON has to pass through {@link minor} — which
 * is exactly the boundary where a float would otherwise slip in unnoticed.
 *
 * It is deliberately not a class. These values are serialised straight into the sale
 * payload and the outbox row; a class would need marshalling on both sides of the wire,
 * and the first time someone forgot, the bug would be a wrong number rather than a
 * crash. A branded number is JSON-native and `JSON.stringify`s to itself.
 *
 * Arithmetic lives in functions rather than operators because that is the only way to
 * keep the guards on. Every operation below re-asserts integrality, so a bad value fails
 * at the point it is created rather than three screens later on a receipt.
 */

declare const minorBrand: unique symbol;

/**
 * An amount in the currency's minor unit — cents for LKR. Always an integer, and always
 * constructed through {@link minor} rather than cast.
 */
export type Minor = number & { readonly [minorBrand]: 'Minor' };

/** Zero, for folds and for the empty cart. */
export const ZERO_MINOR = 0 as Minor;

/**
 * The trust boundary. Everything entering the money path from JSON, a form, or a
 * database row goes through here.
 */
export function minor(value: number): Minor {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    throw new RangeError(`Money must be a number, got ${String(value)}`);
  }
  if (!Number.isFinite(value)) {
    throw new RangeError(`Money must be finite, got ${value}`);
  }
  if (!Number.isInteger(value)) {
    throw new RangeError(
      `Money must be an integer number of minor units, got ${value}. ` +
        'A fractional value here means a float reached the money path.',
    );
  }
  if (!Number.isSafeInteger(value)) {
    // Past 2^53 integer arithmetic silently stops being exact, which is the one failure
    // mode this whole module exists to prevent.
    throw new RangeError(`Money ${value} is beyond the exact-integer range`);
  }
  // Normalise negative zero. `-0` arises naturally from rounding a small negative amount
  // (a refund of 0.30 rounds to -0), and it is not a value money has: it compares
  // unequal to 0 under Object.is and Map keys, so a zero rounding delta would look like
  // a different figure from no rounding delta.
  return (value === 0 ? 0 : value) as Minor;
}

/** Same as {@link minor}, and additionally refuses a negative amount. */
export function nonNegativeMinor(value: number, name = 'amount'): Minor {
  const amount = minor(value);
  if (amount < 0) {
    throw new RangeError(`${name} must not be negative, got ${value}`);
  }
  return amount;
}

// ------------------------------------------------------------------------- arithmetic

export function addMinor(a: Minor, b: Minor): Minor {
  return minor(a + b);
}

export function subtractMinor(a: Minor, b: Minor): Minor {
  return minor(a - b);
}

export function negateMinor(a: Minor): Minor {
  return minor(-a);
}

export function absMinor(a: Minor): Minor {
  return minor(Math.abs(a));
}

/** Money times a count. Only ever by an integer — a fractional multiplier is a price change. */
export function multiplyMinor(amount: Minor, count: number): Minor {
  if (!Number.isInteger(count)) {
    throw new RangeError(`Money may only be multiplied by an integer count, got ${count}`);
  }
  return minor(amount * count);
}

export function sumMinor(amounts: readonly Minor[]): Minor {
  return amounts.reduce<Minor>((total, amount) => addMinor(total, amount), ZERO_MINOR);
}

export function compareMinor(a: Minor, b: Minor): -1 | 0 | 1 {
  return a < b ? -1 : a > b ? 1 : 0;
}

export function maxMinor(a: Minor, b: Minor): Minor {
  return a >= b ? a : b;
}

export function minMinor(a: Minor, b: Minor): Minor {
  return a <= b ? a : b;
}

/** What a line of the cart comes to, before any discount. */
export function lineTotalMinor(unitPriceMinor: number, qty: number): Minor {
  const price = nonNegativeMinor(unitPriceMinor, 'unitPriceMinor');
  if (!Number.isInteger(qty) || qty < 1) {
    throw new RangeError(`qty must be a whole number of at least 1, got ${qty}`);
  }
  return multiplyMinor(price, qty);
}

// ---------------------------------------------------------------------- text and back

/**
 * Formats minor units for display. Grouping only — no currency symbol, no rounding.
 *
 * Rendered in the tabular monospace face (`.lum-money`) everywhere it appears, so digits
 * line up down a column. That is register vernacular, not decoration: a cashier scanning
 * a column for the odd figure is doing it by shape.
 */
export function formatMinor(amountMinor: number): string {
  const amount = minor(amountMinor);
  const negative = amount < 0;
  const absolute = Math.abs(amount);
  const major = Math.floor(absolute / 100);
  const cents = absolute % 100;
  const grouped = major.toLocaleString('en-LK');
  return `${negative ? '-' : ''}${grouped}.${cents.toString().padStart(2, '0')}`;
}

/**
 * Parses a typed amount — "450", "450.5", "1,250.75" — into minor units.
 *
 * Done by string surgery rather than `parseFloat`, which ESLint blocks in this package.
 * That is not pedantry: `parseFloat('0.29') * 100` is 28.999999999999996, so the obvious
 * implementation loses a cent on a value a cashier will type every day.
 *
 * Returns `null` on anything unparseable, because the caller is usually a keystroke
 * handler where a half-typed amount is normal and an exception is not.
 */
export function parseAmountToMinor(input: string): Minor | null {
  const trimmed = input.trim().replace(/,/g, '');
  if (trimmed === '') return null;

  const match = /^(-)?(\d*)(?:\.(\d{0,2}))?$/.exec(trimmed);
  if (!match) return null;

  const [, sign, whole, fraction] = match;
  if (whole === '' && (fraction === undefined || fraction === '')) return null;

  const rupees = whole === '' ? 0 : Number(whole);
  const cents = Number((fraction ?? '').padEnd(2, '0'));
  const total = rupees * 100 + cents;
  return minor(sign === '-' ? -total : total);
}
