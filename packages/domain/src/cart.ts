/**
 * Cart totals (M1-03), stamped with the tax in force (M1-05), per line (M1-18).
 *
 * ## Everything is normalised to gross first
 *
 * The first thing {@link cartTotals} does is convert each quoted unit price to its
 * tax-inclusive equivalent, under that line's own stamp. After that single step there is no
 * `INCLUSIVE` / `EXCLUSIVE` branch anywhere below and no per-line rate to thread through —
 * tax is always *extracted*, discounts always apply to gross amounts, and a cart mixing an
 * 18% line with an exempt one goes down the same single code path as any other.
 *
 * That is what made M1-18 small. The alternative — carrying the rate down through
 * apportionment and the discount arithmetic — would have put a tax branch inside the part
 * of this file that is hardest to get right, to no purpose: once a line is gross, its rate
 * matters again only at the moment of extraction, and once more when the summary is grouped.
 *
 * That matters more than it looks. Two paths through money code means the rarely-used one
 * is the one nobody notices is wrong, and `EXCLUSIVE` is the trade-counter case that gets
 * exercised once a month. `INCLUSIVE` is the identity here, so the common Sri Lankan
 * retail case is untouched and exact.
 *
 * Grossing happens **per unit** rather than per line, so `qty × unitPrice = lineTotal`
 * still holds on the receipt. A receipt whose own arithmetic visibly does not work is a
 * receipt a customer argues with.
 *
 * ## What the backend expects
 *
 * `SaleService.assertTotalsAreSelfConsistent` is a checksum, not a second opinion — it
 * never recomputes VAT, it only asserts the figures agree:
 *
 *   - `Σ line.lineTotalMinor == subtotalMinor`
 *   - `subtotalMinor − discountMinor == totalMinor`
 *   - `taxMinor <= totalMinor`
 *
 * So `lineTotalMinor` is the line **before** the order-level discount, and the order
 * discount lives only at sale level. Each line still carries its apportioned share, and
 * its tax is computed on the amount actually charged for it — see below.
 *
 * Since M1-18 the checksum also requires `Σ line.taxMinor == taxMinor`. With one rate that
 * was merely true; with several it is the *definition* — there is no single rate left to
 * recompute a sale's tax from, so the lines are the only thing that can say what it is.
 */

import {
  addMinor,
  minor,
  multiplyMinor,
  nonNegativeMinor,
  subtractMinor,
  sumMinor,
  ZERO_MINOR,
  type Minor,
} from './money';
import {
  extractVatMinor,
  grossForMinor,
  type BasisPoints,
  type TaxMode,
  type TaxStamp,
} from './vat';

export interface CartLineInput {
  readonly productClientUuid: string;
  readonly qty: number;
  /** The quoted unit price: gross under `INCLUSIVE`, net under `EXCLUSIVE`. */
  readonly unitPriceMinor: number;
  /** A discount on this line alone, in gross minor units. */
  readonly lineDiscountMinor?: number;
  /**
   * This line's own tax treatment (M1-18). Defaults to {@link CartInput.tax}.
   *
   * Products carry a rate each — `products.tax_mode` / `products.tax_rate_bp` — so a basket
   * of groceries and a bottle of arrack is two rates, and so is anything zero-rated next to
   * anything standard. Before M1-18 the whole cart took one stamp and such a basket was
   * *refused* at the till rather than priced with the wrong rate on the exempt line.
   */
  readonly tax?: TaxStamp;
}

export interface CartInput {
  readonly lines: readonly CartLineInput[];
  /** A discount on the whole order, apportioned across lines. */
  readonly orderDiscountMinor?: number;
  /** The stamp for lines that do not carry their own. */
  readonly tax: TaxStamp;
}

export interface CartLineTotals {
  readonly productClientUuid: string;
  /** 1-based, matching the `line_no` the backend writes. */
  readonly lineNo: number;
  readonly qty: number;
  /** Tax-inclusive unit price, whatever mode the sale was quoted in. */
  readonly unitPriceMinor: Minor;
  /** This line's own discount, plus its apportioned share of the order discount. */
  readonly discountMinor: Minor;
  /** Gross, less this line's own discount, **before** the order discount. Sums to the subtotal. */
  readonly lineTotalMinor: Minor;
  /** This line's share of the order discount. */
  readonly orderDiscountShareMinor: Minor;
  /** What was actually charged for this line. Sums to the total. */
  readonly netMinor: Minor;
  /** The VAT contained in {@link netMinor}. Sums to the sale's tax. */
  readonly taxMinor: Minor;
  /** The treatment this line was actually priced under — its own, or the cart's default. */
  readonly taxMode: TaxMode;
  readonly taxRateBp: BasisPoints;
}

/**
 * One rate's worth of a sale (M1-18).
 *
 * A tax invoice has to show the net amount, the tax on it and the total separately, and a
 * sale mixing rates has to keep the taxable and non-taxable parts apart — so this is the
 * shape the receipt's VAT summary and the console's reporting both read. One entry per
 * distinct stamp, ordered by rate so the summary reads the same way on every receipt.
 *
 * Note {@link exVatMinor} is **not** {@link CartLineTotals.netMinor}. "Net" means two
 * different things a line apart: there it is the gross actually charged after discounts,
 * here it is the amount with the tax taken out. Hence the blunter name.
 */
export interface TaxBreakdownEntry {
  readonly mode: TaxMode;
  readonly rateBp: BasisPoints;
  /** Charged, tax inclusive, for the lines under this stamp. Sums to the sale's total. */
  readonly grossMinor: Minor;
  /** {@link grossMinor} less {@link taxMinor} — the tax invoice's "net amount". */
  readonly exVatMinor: Minor;
  /** Sums to the sale's tax. */
  readonly taxMinor: Minor;
}

export interface CartTotals {
  readonly lines: readonly CartLineTotals[];
  readonly subtotalMinor: Minor;
  /** The order-level discount only. Line discounts are already inside the subtotal. */
  readonly discountMinor: Minor;
  readonly taxMinor: Minor;
  readonly totalMinor: Minor;
  /**
   * The cart's **default** stamp — the one applied to lines that brought none of their own
   * (M1-05). Since M1-18 it is no longer the whole story: on a mixed cart the authority is
   * the per-line stamp, and {@link taxBreakdown} is what a receipt or a report should read.
   * It stays because `sales.tax_mode` / `sales.tax_rate_bp` record it, and because a
   * historical receipt must reprint with the rate it was issued under, not today's.
   */
  readonly taxMode: TaxStamp['mode'];
  readonly taxRateBp: TaxStamp['rateBp'];
  /**
   * One entry per distinct stamp in the cart, ordered by rate. Empty only for an empty cart.
   * More than one entry means the sale mixed rates.
   */
  readonly taxBreakdown: readonly TaxBreakdownEntry[];
}

/** The empty cart, so a component never has to special-case "nothing scanned yet". */
export function emptyCartTotals(tax: TaxStamp): CartTotals {
  return {
    lines: [],
    subtotalMinor: ZERO_MINOR,
    discountMinor: ZERO_MINOR,
    taxMinor: ZERO_MINOR,
    totalMinor: ZERO_MINOR,
    taxMode: tax.mode,
    taxRateBp: tax.rateBp,
    taxBreakdown: [],
  };
}

export function cartTotals(input: CartInput): CartTotals {
  const { tax } = input;
  if (input.lines.length === 0) {
    if ((input.orderDiscountMinor ?? 0) !== 0) {
      throw new RangeError('An empty cart cannot carry an order discount');
    }
    return emptyCartTotals(tax);
  }

  // Pass one: normalise to gross and apply line-level discounts. Each line grosses up under
  // its *own* stamp, which is the whole of M1-18 — after this step the mixed cart is a list
  // of gross amounts like any other, and everything below is rate-agnostic again.
  const base = input.lines.map((line, index) => {
    if (!Number.isInteger(line.qty) || line.qty < 1) {
      throw new RangeError(`Line ${index + 1}: qty must be a whole number of at least 1`);
    }
    const stamp = line.tax ?? tax;
    const unitPriceMinor = grossForMinor(line.unitPriceMinor, stamp);
    const gross = multiplyMinor(unitPriceMinor, line.qty);
    const lineDiscount = nonNegativeMinor(line.lineDiscountMinor ?? 0, 'lineDiscountMinor');
    if (lineDiscount > gross) {
      throw new RangeError(
        `Line ${index + 1}: discount ${lineDiscount} exceeds the line total ${gross}`,
      );
    }
    return {
      line,
      stamp,
      unitPriceMinor,
      lineDiscount,
      lineTotal: subtractMinor(gross, lineDiscount),
    };
  });

  const subtotalMinor = sumMinor(base.map((b) => b.lineTotal));
  const discountMinor = nonNegativeMinor(input.orderDiscountMinor ?? 0, 'orderDiscountMinor');
  if (discountMinor > subtotalMinor) {
    throw new RangeError(`Order discount ${discountMinor} exceeds the subtotal ${subtotalMinor}`);
  }

  // Pass two: apportion the order discount so the parts sum to exactly the whole.
  const shares = apportion(
    discountMinor,
    base.map((b) => b.lineTotal),
  );

  const lines: CartLineTotals[] = base.map((b, index) => {
    const share = shares[index]!;
    const netMinor = subtractMinor(b.lineTotal, share);
    return {
      productClientUuid: b.line.productClientUuid,
      lineNo: index + 1,
      qty: b.line.qty,
      unitPriceMinor: b.unitPriceMinor,
      discountMinor: addMinor(b.lineDiscount, share),
      lineTotalMinor: b.lineTotal,
      orderDiscountShareMinor: share,
      netMinor,
      // Always extraction: everything above is gross by now, whatever the sale was quoted in.
      taxMinor: extractVatMinor(netMinor, b.stamp.rateBp),
      taxMode: b.stamp.mode,
      taxRateBp: b.stamp.rateBp,
    };
  });

  return {
    lines,
    subtotalMinor,
    discountMinor,
    // Summed per line rather than taken on the total, so the receipt's tax column adds up
    // to the tax figure printed at the foot of it.
    taxMinor: sumMinor(lines.map((l) => l.taxMinor)),
    totalMinor: subtractMinor(subtotalMinor, discountMinor),
    taxMode: tax.mode,
    taxRateBp: tax.rateBp,
    taxBreakdown: breakdown(lines),
  };
}

/**
 * Groups the priced lines by the stamp each was charged under (M1-18).
 *
 * Summed from the lines rather than recomputed from the group's gross, which matters
 * because extraction truncates: extracting once from a 126,000 group is not always what
 * extracting from a 90,000 line and a 36,000 line comes to, and the two differ by a cent
 * often enough to matter. The lines are what the customer was charged, so the lines are
 * what the summary adds up — that is also why `Σ entry.taxMinor` is exactly the sale's tax
 * and the receipt's foot agrees with its own summary block.
 */
function breakdown(lines: readonly CartLineTotals[]): TaxBreakdownEntry[] {
  const groups = new Map<
    string,
    { mode: TaxMode; rateBp: BasisPoints; gross: number; tax: number }
  >();

  for (const line of lines) {
    const key = `${line.taxMode}:${line.taxRateBp}`;
    const group = groups.get(key) ?? {
      mode: line.taxMode,
      rateBp: line.taxRateBp,
      gross: 0,
      tax: 0,
    };
    group.gross += line.netMinor;
    group.tax += line.taxMinor;
    groups.set(key, group);
  }

  return (
    [...groups.values()]
      // By rate, so anything exempt or zero-rated leads and the summary reads the same on
      // every receipt. Mode breaks the tie only so the order is total rather than merely
      // consistent-by-luck; a cart mixing modes at one rate is pathological but not illegal.
      .sort((a, b) => a.rateBp - b.rateBp || a.mode.localeCompare(b.mode))
      .map((group) => ({
        mode: group.mode,
        rateBp: group.rateBp,
        grossMinor: minor(group.gross),
        exVatMinor: subtractMinor(minor(group.gross), minor(group.tax)),
        taxMinor: minor(group.tax),
      }))
  );
}

/**
 * Splits `amount` across `weights` so the parts sum to exactly `amount`.
 *
 * Largest remainder: floor every share, then hand the leftover cents to the lines with
 * the biggest discarded fractions. The naive alternative — rounding each share
 * independently — loses or invents up to one cent per line, and a 3-line cart with a
 * 10.00 discount ends up discounting 9.99. That cent then fails the backend checksum and
 * the sale is rejected with a customer waiting.
 *
 * Ties go to the earlier line, so the same cart always splits the same way. A discount
 * that moved between lines on re-render would make a reprinted receipt differ from the
 * original.
 */
function apportion(amount: Minor, weights: readonly Minor[]): Minor[] {
  if (amount === 0) return weights.map(() => ZERO_MINOR);

  const totalWeight = sumMinor(weights);
  if (totalWeight <= 0) {
    // Every line is free, so there is nothing to apportion against. Reachable only via a
    // discount on a fully comped cart, which the caller should have rejected.
    throw new RangeError('Cannot apportion a discount across lines with no value');
  }

  const exact = weights.map((weight) => (amount * weight) / totalWeight);
  const floors = exact.map((value) => Math.floor(value));
  let remainder = amount - floors.reduce((sum, value) => sum + value, 0);

  const order = exact
    .map((value, index) => ({ index, fraction: value - Math.floor(value) }))
    .sort((a, b) => b.fraction - a.fraction || a.index - b.index);

  const shares = floors.slice();
  for (const { index } of order) {
    if (remainder <= 0) break;
    shares[index]! += 1;
    remainder -= 1;
  }

  return shares.map((share) => minor(share));
}
