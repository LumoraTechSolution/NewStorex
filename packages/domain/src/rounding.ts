/**
 * LKR cash rounding (M1-03).
 *
 * ## The decision
 *
 * **Cash tenders round to the nearest LKR 1.00. Everything else takes the exact amount.**
 * Half rounds away from zero, so 450.50 goes to 451.00 and −450.50 to −451.00.
 *
 * Sri Lanka's circulating coinage is Rs 1, 2, 5 and 10. Sub-rupee coins are effectively
 * gone from tills, so a cash total of LKR 450.50 cannot actually be settled — the cashier
 * rounds it by hand today, off the books and inconsistently. Card and wallet payments have
 * no such problem and must not be rounded: the customer is charged what the receipt says.
 *
 * ## Round the tender, not the sale
 *
 * This is the part that is easy to get wrong and expensive to undo.
 *
 * The rounding adjustment belongs to the **payment**, never to the sale. The sale's
 * subtotal, discount, tax and total stay exact, and a separate rounding line reconciles
 * the drawer. Two reasons, both hard requirements rather than preferences:
 *
 * 1. **Tax integrity.** Declared VAT is derived from the sale total. If rounding moved
 *    the total, every cash sale would declare VAT on an amount the customer was never
 *    invoiced for, and the IRD invoice format (hard date April 2026) would be reporting
 *    a figure that does not match the sale record.
 * 2. **The backend checksum.** `SaleService.assertTotalsAreSelfConsistent` asserts
 *    `subtotal − discount == total` exactly. Rounding the sale total breaks that
 *    invariant on any sale ending in something other than a round rupee — which is most
 *    of them — and the till would reject its own sales.
 *
 * So a sale of 450.50 settled in cash records total 450.50, cash received 451.00, and a
 * rounding adjustment of 0.50. The drawer balances, the receipt shows the adjustment, and
 * the tax figure is untouched.
 *
 * The increment is a constant here rather than configuration. If a future currency or a
 * demonetised Rs 1 coin needs a different step, that is a deliberate change with a
 * migration for historical sales — not a per-tenant setting someone can get wrong.
 */

import { minor, subtractMinor, type Minor } from './money';

/** LKR 1.00, in minor units. The smallest coin a cash drawer can actually settle. */
export const CASH_ROUNDING_INCREMENT_MINOR = 100;

/** Payment instruments that cannot settle a fraction of a rupee. */
export type TenderKind = 'CASH' | 'CARD' | 'WALLET' | 'STORE_CREDIT';

export function tenderRounds(kind: TenderKind): boolean {
  return kind === 'CASH';
}

/**
 * The amount actually payable for a tender of this kind.
 *
 * Non-cash returns the amount untouched — rounding a card payment would charge the
 * customer a figure that is on no document either of you holds.
 */
export function payableMinor(amountMinor: number, kind: TenderKind): Minor {
  const amount = minor(amountMinor);
  return tenderRounds(kind) ? roundCashMinor(amount) : amount;
}

/**
 * Rounds to the nearest whole rupee, halves away from zero.
 *
 * Away from zero rather than JavaScript's `Math.round`, which rounds −0.5 towards zero
 * and would make a refund round differently from the sale it reverses. A return must give
 * back exactly what was taken.
 */
export function roundCashMinor(amountMinor: number): Minor {
  const amount = minor(amountMinor);
  const step = CASH_ROUNDING_INCREMENT_MINOR;
  const sign = amount < 0 ? -1 : 1;
  const magnitude = Math.abs(amount);
  const rounded = Math.floor((magnitude + step / 2) / step) * step;
  return minor(sign * rounded);
}

/**
 * What the rounding gave away or gained — `rounded − exact`.
 *
 * Positive means the customer paid up to 0.50 more than the sale; negative means the shop
 * absorbed it. Over a day these very nearly cancel, and the residual is a real figure the
 * cash-up screen (M2) has to account for rather than hide.
 */
export function cashRoundingDeltaMinor(exactMinor: number): Minor {
  const exact = minor(exactMinor);
  return subtractMinor(roundCashMinor(exact), exact);
}
