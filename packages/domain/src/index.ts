/**
 * @lumora/domain — the money path.
 *
 * Everything in this package is pure: no I/O, no clock, no randomness, no framework
 * imports. Both apps and the receipt renderer import from here, which is the whole
 * point — if VAT extraction is implemented twice, the console and the receipt will
 * eventually disagree by a rupee and no test will tell you.
 *
 * Rules that hold for every export added here (ROADMAP §A):
 *   - Money is integer minor units. Never a float.
 *   - VAT is extracted from inclusive prices: vat = total * rate / (1 + rate).
 *     It is never multiplied onto them.
 *   - Balances are always the sum of entries, never a stored level.
 *
 * | Module        | Holds                                                      | Task  |
 * | ------------- | ---------------------------------------------------------- | ----- |
 * | `money.ts`    | the branded `Minor` type, arithmetic, formatting, parsing  | M1-01 |
 * | `vat.ts`      | extraction and addition, the `TaxStamp`                    | M1-02 |
 * | `cart.ts`     | line and order discounts, apportionment, cart totals       | M1-03 |
 * | `rounding.ts` | the LKR cash rounding policy                               | M1-03 |
 * | `tender.ts`   | multi-tender, split payment, change calculation            | M1-11 |
 * | `cashup.ts`   | denomination counts, expected cash, shift variance         | M2-01 |
 * | `refund.ts`   | partial-return apportionment, refund tender rules          | M2-06 |
 * | `csv.ts`      | reading a product list out of a spreadsheet                | M3-03 |
 * | `stock.ts`    | adjustment reasons, their direction, on hand as a sum      | M3-05 |
 *
 * The invariants these uphold are stated once, in `cart.ts`, because they are the
 * contract the backend's checksum enforces. Property-based tests in `cart.test.ts` assert
 * them across generated carts rather than chosen examples.
 */

export * from './money';
export * from './vat';
export * from './cart';
export * from './rounding';
export * from './tender';
export * from './cashup';
export * from './refund';
export * from './csv';
export * from './stock';
