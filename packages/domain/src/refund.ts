/**
 * Returns and refunds (M2-06 … M2-10).
 *
 * ## A refund is priced from what was charged, never from today's price
 *
 * The only correct amount to give back for a returned unit is the amount that unit actually
 * contributed to the sale — after its line discount, after its share of the order discount, at
 * the tax rate stamped on it that day (M1-05, M1-18). The product's current shelf price is
 * irrelevant and using it is how a shop loses money on every price rise and cheats a customer on
 * every price cut. So everything here takes the recorded sale line as its input and never a
 * product.
 *
 * ## Partial returns have to sum back to the whole
 *
 * The hard property, and the reason this module exists rather than a multiply in a service:
 * returning 1 unit of 3, then later the other 2, must give back **exactly** what returning all 3
 * at once would. Naive per-unit division fails this — `round(net/3) × 3` is off by a cent or two
 * for most values, and the shop keeps the difference on a rounding error nobody can explain.
 *
 * The fix is to apportion cumulatively rather than per unit. For a line of `qty` units charged
 * `netMinor`, define
 *
 *     f(j) = floor(netMinor × j ÷ qty)          — what the first j units are worth, together
 *
 * and a return of `k` units when `r` have already gone back is `f(r + k) − f(r)`. Because `f` is
 * a single expression evaluated at two points, the intermediate rounding cancels: the sum over
 * any sequence of partial returns of the whole line telescopes to `f(qty) − f(0)`, which is
 * `netMinor` exactly. Same construction for the tax.
 *
 * This is the same idea as the largest-remainder apportionment in `cart.ts`, arrived at from the
 * other end: there, shares must sum to a known total; here, they must sum back to it no matter
 * how many transactions they are spread across.
 *
 * ## What this module does not decide
 *
 * Whether the refund is *allowed* — the manager PIN (M2-07), and whether the sale exists at all
 * (M2-06) — is not money math and is not here. It lives in `RefundService`, which is the only
 * thing that can check a hash or read a database. What is here is the second half of Gate M2:
 * {@link allocateRefundTenders} cannot produce a refund to a tender the sale never took.
 */

import {
  addMinor,
  minor,
  nonNegativeMinor,
  subtractMinor,
  sumMinor,
  ZERO_MINOR,
  type Minor,
} from './money';
import { cashRoundingDeltaMinor, roundCashMinor, type TenderKind } from './rounding';
import type { BasisPoints, TaxMode } from './vat';

/** Why a unit came back (M2-08). Required per line; `OTHER` requires a written note. */
export const REFUND_REASONS = [
  'DAMAGED',
  'FAULTY',
  'WRONG_ITEM',
  'EXPIRED',
  'NOT_AS_DESCRIBED',
  'CHANGED_MIND',
  'PRICING_ERROR',
  'OTHER',
] as const;

export type RefundReason = (typeof REFUND_REASONS)[number];

/**
 * Reasons whose goods do not go back on the shelf.
 *
 * A default for the UI to preselect, not a rule the domain enforces — a "damaged" item that
 * turned out to be fine is a judgement the person holding it makes, and hardcoding it would have
 * them lying about the reason to correct the stock. M2-10 keeps `restock` an explicit per-line
 * flag for exactly that reason.
 */
export const REASONS_NOT_NORMALLY_RESTOCKED: readonly RefundReason[] = [
  'DAMAGED',
  'FAULTY',
  'EXPIRED',
] as const;

/**
 * A line of the original sale, exactly as it was recorded, plus how much of it has already been
 * given back by earlier refunds.
 */
export interface RefundableSaleLine {
  /** `sale_items.line_no` — 1-based, and the handle a refund request refers to. */
  readonly lineNo: number;
  readonly productClientUuid: string;
  readonly name: string;
  readonly qty: number;
  readonly unitPriceMinor: number;
  /**
   * What was actually charged for this line: the domain's `CartLineTotals.netMinor`, gross and
   * after every discount. Not `lineTotalMinor`, which is before the order discount — refunding
   * that would give back money the customer never paid.
   */
  readonly chargedMinor: number;
  /** The VAT inside {@link chargedMinor}. */
  readonly taxMinor: number;
  readonly taxMode: TaxMode;
  readonly taxRateBp: BasisPoints;
  /** Σ qty over every refund_item already written against this line. */
  readonly alreadyRefundedQty: number;
}

/** How many units of a line may still come back. */
export function refundableQty(line: RefundableSaleLine): number {
  const remaining = line.qty - line.alreadyRefundedQty;
  if (remaining < 0) {
    // Only reachable if something wrote a refund past the cap. Louder than clamping to zero,
    // because a negative here means the ledger is already wrong and hiding it helps nobody.
    throw new RangeError(
      `Line ${line.lineNo} has ${line.alreadyRefundedQty} units refunded against a qty of ${line.qty}`,
    );
  }
  return remaining;
}

/**
 * The cumulative apportionment described in the module header: what the first `j` units of a line
 * are worth together.
 *
 * `Math.floor` on a product of safe integers. `qty` is small and `amountMinor` is a shop's line
 * total, so the product stays far inside the exact-integer range that {@link minor} guards.
 */
function cumulativeShareMinor(amountMinor: number, qty: number, j: number): Minor {
  return minor(Math.floor((amountMinor * j) / qty));
}

export interface RefundLineRequest {
  /** Which line of the sale — `sale_items.line_no`. */
  readonly saleLineNo: number;
  readonly qty: number;
  readonly reasonCode: RefundReason;
  /** M2-10. Whether these units return to sellable stock. */
  readonly restock: boolean;
  readonly note?: string;
}

export interface RefundLineAmounts {
  readonly saleLineNo: number;
  readonly productClientUuid: string;
  readonly name: string;
  readonly qty: number;
  readonly unitPriceMinor: Minor;
  /** What is handed back for these units, gross. */
  readonly refundTotalMinor: Minor;
  /** The VAT inside it, at the rate the sale was stamped with. */
  readonly taxMinor: Minor;
  readonly taxMode: TaxMode;
  readonly taxRateBp: BasisPoints;
  readonly reasonCode: RefundReason;
  readonly restock: boolean;
  readonly note?: string;
}

/**
 * Prices one line of a return.
 *
 * Takes the units already returned into account by construction rather than by subtraction: the
 * refund is the difference between two evaluations of the same cumulative function, which is what
 * makes repeated partial returns of the same line add up exactly.
 */
export function refundLineAmounts(
  line: RefundableSaleLine,
  request: RefundLineRequest,
): RefundLineAmounts {
  if (!Number.isInteger(request.qty) || request.qty < 1) {
    throw new RangeError(`Refund qty must be a whole number of at least 1, got ${request.qty}`);
  }
  const remaining = refundableQty(line);
  if (request.qty > remaining) {
    throw new RangeError(
      `Cannot return ${request.qty} of line ${line.lineNo}: ${remaining} of ${line.qty} remain` +
        (line.alreadyRefundedQty > 0 ? ` (${line.alreadyRefundedQty} already returned)` : ''),
    );
  }

  const charged = nonNegativeMinor(line.chargedMinor, 'chargedMinor');
  const tax = nonNegativeMinor(line.taxMinor, 'taxMinor');
  const already = line.alreadyRefundedQty;
  const upTo = already + request.qty;

  const refundTotalMinor = subtractMinor(
    cumulativeShareMinor(charged, line.qty, upTo),
    cumulativeShareMinor(charged, line.qty, already),
  );
  const taxMinor = subtractMinor(
    cumulativeShareMinor(tax, line.qty, upTo),
    cumulativeShareMinor(tax, line.qty, already),
  );

  // Both shares are floors of the same shape, so this can only be violated if a line's tax sits
  // within a rounding step of its gross — which no real VAT rate produces (18% inclusive puts
  // tax at ~15% of gross). Asserted rather than clamped: clamping would silently break the
  // telescoping property above, and a line where this fires is a corrupt sale row, not a
  // rounding case to absorb.
  if (taxMinor > refundTotalMinor) {
    throw new RangeError(
      `Refund of line ${line.lineNo} apportioned tax ${taxMinor} above its gross ${refundTotalMinor} — ` +
        `the sale line (charged ${charged}, tax ${tax}, qty ${line.qty}) cannot be right`,
    );
  }

  return {
    saleLineNo: line.lineNo,
    productClientUuid: line.productClientUuid,
    name: line.name,
    qty: request.qty,
    unitPriceMinor: nonNegativeMinor(line.unitPriceMinor, 'unitPriceMinor'),
    refundTotalMinor,
    taxMinor,
    taxMode: line.taxMode,
    taxRateBp: line.taxRateBp,
    reasonCode: request.reasonCode,
    restock: request.restock,
    ...(request.note !== undefined ? { note: request.note } : {}),
  };
}

export interface RefundSummary {
  readonly lines: readonly RefundLineAmounts[];
  /** Σ of the lines' gross. What the customer is owed, exactly. */
  readonly totalMinor: Minor;
  /** Σ of the lines' VAT. What the credit note declares. */
  readonly taxMinor: Minor;
}

/**
 * Prices a whole return.
 *
 * Each requested line is matched to the recorded sale line by `line_no`. A request naming a line
 * the sale does not have is rejected rather than skipped: silently dropping it would produce a
 * refund smaller than the screen showed, and the cashier would hand back the screen's figure.
 */
export function summariseRefund(
  saleLines: readonly RefundableSaleLine[],
  requests: readonly RefundLineRequest[],
): RefundSummary {
  if (requests.length === 0) {
    throw new RangeError('A refund must return at least one line');
  }

  const byLineNo = new Map(saleLines.map((line) => [line.lineNo, line]));
  const seen = new Set<number>();

  const lines = requests.map((request) => {
    const line = byLineNo.get(request.saleLineNo);
    if (!line) {
      throw new RangeError(`The sale has no line ${request.saleLineNo}`);
    }
    // Two entries for one line would each be priced against the same `alreadyRefundedQty` and
    // together give back more than the line holds. Merging them silently would be a guess at
    // what the cashier meant when they have two different reason codes.
    if (seen.has(request.saleLineNo)) {
      throw new RangeError(
        `Line ${request.saleLineNo} appears twice in one refund — combine the quantities`,
      );
    }
    seen.add(request.saleLineNo);
    return refundLineAmounts(line, request);
  });

  return {
    lines,
    totalMinor: sumMinor(lines.map((l) => l.refundTotalMinor)),
    taxMinor: sumMinor(lines.map((l) => l.taxMinor)),
  };
}

// ------------------------------------------------------------------ tenders (M2-09)

export interface TenderTotal {
  readonly kind: TenderKind;
  readonly amountMinor: number;
}

export interface RefundableTender {
  readonly kind: TenderKind;
  /** What this kind actually paid towards the sale, net of any change if it was cash. */
  readonly paidMinor: Minor;
  /** What earlier refunds have already sent back to it. */
  readonly alreadyRefundedMinor: Minor;
  /** `paid − alreadyRefunded`. The ceiling for this refund. */
  readonly refundableMinor: Minor;
}

/**
 * What each tender kind can still take back.
 *
 * Cash is the one kind whose recorded tender is not what it paid: `sale_payments` stores what was
 * physically handed over, which for cash includes the change that came straight back out. Netting
 * the change off here is what stops a customer who paid a 500-rupee note for a 320-rupee basket
 * being refundable for 500.
 */
export function refundableTenders(
  saleTenders: readonly TenderTotal[],
  changeMinor: number,
  alreadyRefunded: readonly TenderTotal[],
): RefundableTender[] {
  const change = nonNegativeMinor(changeMinor, 'changeMinor');

  const paidByKind = new Map<TenderKind, Minor>();
  for (const tender of saleTenders) {
    const amount = nonNegativeMinor(tender.amountMinor, `${tender.kind} amountMinor`);
    paidByKind.set(tender.kind, addMinor(paidByKind.get(tender.kind) ?? ZERO_MINOR, amount));
  }

  if (change > ZERO_MINOR) {
    const cashPaid = paidByKind.get('CASH') ?? ZERO_MINOR;
    if (change > cashPaid) {
      throw new RangeError(
        `Sale recorded change of ${change} against cash tenders of ${cashPaid} — only cash makes change`,
      );
    }
    paidByKind.set('CASH', subtractMinor(cashPaid, change));
  }

  const refundedByKind = new Map<TenderKind, Minor>();
  for (const tender of alreadyRefunded) {
    const amount = nonNegativeMinor(tender.amountMinor, `${tender.kind} refunded amountMinor`);
    refundedByKind.set(
      tender.kind,
      addMinor(refundedByKind.get(tender.kind) ?? ZERO_MINOR, amount),
    );
  }

  return [...paidByKind.entries()]
    .filter(([, paid]) => paid > ZERO_MINOR)
    .map(([kind, paid]) => {
      const alreadyRefundedMinor = refundedByKind.get(kind) ?? ZERO_MINOR;
      return {
        kind,
        paidMinor: paid,
        alreadyRefundedMinor,
        refundableMinor:
          paid > alreadyRefundedMinor ? subtractMinor(paid, alreadyRefundedMinor) : ZERO_MINOR,
      };
    });
}

export interface RefundTenderAllocation {
  readonly tenders: readonly TenderTotal[];
  /** Σ of the allocation, before cash rounding. Equals the refund total. */
  readonly totalMinor: Minor;
  /** The cash portion, rounded to what a drawer can actually pay out (M1-03). */
  readonly cashPayableMinor: Minor;
  /** `cashPayable − cash allocated`. Signed; the shop absorbs it either way. */
  readonly roundingAdjustmentMinor: Minor;
}

/**
 * Decides how a refund goes back — Gate M2's second half.
 *
 * **Money goes back the way it came.** A card sale refunded in cash is the oldest way to empty a
 * drawer with a receipt in your hand, and the rule that stops it is not a warning on a screen: it
 * is that no allocation to a kind the sale never took can be constructed here at all.
 *
 * Order matters and is deliberate. Non-cash kinds are exhausted first, cash last. A customer who
 * paid part card and part cash and returns one item should see it go back to the card — that is
 * the reversal a bank statement makes sense of, and it keeps cash in the drawer where the shift
 * count expects it.
 *
 * Only the cash portion is rounded, and only at the end, exactly as `summariseTender` rounds only
 * the cash-covered remainder of a sale.
 */
export function allocateRefundTenders(
  refundTotalMinor: number,
  available: readonly RefundableTender[],
): RefundTenderAllocation {
  const total = nonNegativeMinor(refundTotalMinor, 'refundTotalMinor');
  if (total === ZERO_MINOR) {
    throw new RangeError('A refund of zero is not a refund');
  }

  const capacity = sumMinor(available.map((t) => t.refundableMinor));
  if (capacity < total) {
    throw new RangeError(
      `Refund of ${total} exceeds what the sale's tenders can still return (${capacity}). ` +
        'A refund can never send back more than a tender took, and never to a tender the sale did not use.',
    );
  }

  const order = [...available].sort((a, b) => rank(a.kind) - rank(b.kind));

  const tenders: TenderTotal[] = [];
  let remaining: Minor = total;
  let cashAllocated: Minor = ZERO_MINOR;

  for (const candidate of order) {
    if (remaining === ZERO_MINOR) break;
    const take = candidate.refundableMinor < remaining ? candidate.refundableMinor : remaining;
    if (take === ZERO_MINOR) continue;

    tenders.push({ kind: candidate.kind, amountMinor: take });
    remaining = subtractMinor(remaining, take);
    if (candidate.kind === 'CASH') cashAllocated = take;
  }

  return {
    tenders,
    totalMinor: total,
    cashPayableMinor: roundCashMinor(cashAllocated),
    roundingAdjustmentMinor: cashRoundingDeltaMinor(cashAllocated),
  };
}

/** Cash last, so a mixed sale reverses to the card first. */
function rank(kind: TenderKind): number {
  return kind === 'CASH' ? 1 : 0;
}

/**
 * Checks an allocation the caller built itself — a cashier splitting a refund by hand.
 *
 * {@link allocateRefundTenders} is the automatic path and is correct by construction. This is the
 * same rules applied to someone else's answer, and it is what `RefundService` runs on whatever
 * arrives over HTTP: the till's UI must never be the only thing standing between a card sale and
 * a cash refund.
 */
export function assertRefundTendersAllowed(
  refundTotalMinor: number,
  proposed: readonly TenderTotal[],
  available: readonly RefundableTender[],
): void {
  const total = nonNegativeMinor(refundTotalMinor, 'refundTotalMinor');
  const byKind = new Map(available.map((t) => [t.kind, t]));

  let allocated: Minor = ZERO_MINOR;
  const seen = new Set<TenderKind>();

  for (const tender of proposed) {
    const amount = nonNegativeMinor(tender.amountMinor, `${tender.kind} amountMinor`);
    if (amount === ZERO_MINOR) {
      throw new RangeError(`Refund line for ${tender.kind} is zero — omit it instead`);
    }
    if (seen.has(tender.kind)) {
      throw new RangeError(`${tender.kind} appears twice in one refund — combine the amounts`);
    }
    seen.add(tender.kind);

    const capacity = byKind.get(tender.kind);
    if (!capacity) {
      throw new RangeError(
        `Cannot refund ${amount} to ${tender.kind}: the sale was not paid with it. ` +
          'A refund goes back the way it came.',
      );
    }
    if (amount > capacity.refundableMinor) {
      throw new RangeError(
        `Cannot refund ${amount} to ${tender.kind}: only ${capacity.refundableMinor} of the ` +
          `${capacity.paidMinor} it paid is still refundable`,
      );
    }
    allocated = addMinor(allocated, amount);
  }

  if (allocated !== total) {
    throw new RangeError(
      `Refund tenders total ${allocated} but the refund is ${total} — a refund is settled in full or not recorded`,
    );
  }
}
