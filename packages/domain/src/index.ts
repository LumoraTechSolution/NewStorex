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
 * `money.ts` holds the narrow slice the M0 spike needed. Still to come: the full `Money`
 * type (M1-01), inclusive/exclusive modes across a whole cart (M1-02), discounts and the
 * LKR rounding policy (M1-03), and property-based tests (M1-04).
 */

export * from './money';
