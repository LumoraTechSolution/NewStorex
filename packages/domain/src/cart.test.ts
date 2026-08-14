/**
 * M1-04 — totals must reconcile to the cent across *every* path.
 *
 * These are properties, not examples. A hand-picked cart proves the case someone thought
 * of; the money bugs that survive to production are the ones nobody thought to type in —
 * a 3-way split of a 10.00 discount, a line that is entirely discounted, a rate that
 * divides badly. fast-check generates those.
 *
 * The four invariants below are exactly what `SaleService.assertTotalsAreSelfConsistent`
 * enforces on the backend. If a property here fails, the till rejects its own sale with a
 * customer waiting — so they are asserted here, where the failure is a red test.
 */

import fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import { cartTotals, emptyCartTotals, type CartLineInput } from './cart';
import { taxStamp, type TaxMode } from './vat';

const STAMP = taxStamp('INCLUSIVE', 1800);

const lineArb: fc.Arbitrary<CartLineInput> = fc
  .record({
    productClientUuid: fc.uuid(),
    qty: fc.integer({ min: 1, max: 20 }),
    unitPriceMinor: fc.integer({ min: 0, max: 1_000_000 }),
    lineDiscountPermille: fc.integer({ min: 0, max: 1000 }),
  })
  .map(({ productClientUuid, qty, unitPriceMinor, lineDiscountPermille }) => ({
    productClientUuid,
    qty,
    unitPriceMinor,
    // Expressed as a fraction of the line so it can never exceed it.
    lineDiscountMinor: Math.floor((unitPriceMinor * qty * lineDiscountPermille) / 1000),
  }));

interface CartSpec {
  readonly lines: readonly CartLineInput[];
  readonly discountPermille: number;
  readonly mode: TaxMode;
  readonly rateBp: number;
}

const cartArb: fc.Arbitrary<CartSpec> = fc.record({
  lines: fc.array(lineArb, { minLength: 1, maxLength: 8 }),
  discountPermille: fc.integer({ min: 0, max: 1000 }),
  mode: fc.constantFrom<TaxMode>('INCLUSIVE', 'EXCLUSIVE'),
  rateBp: fc.constantFrom(0, 800, 1500, 1800, 2500),
});

/** Builds the cart twice: once undiscounted to learn the subtotal, then for real. */
function build(spec: CartSpec) {
  const stamp = taxStamp(spec.mode, spec.rateBp);
  const undiscounted = cartTotals({ lines: spec.lines, tax: stamp });
  const orderDiscountMinor = Math.floor(
    (undiscounted.subtotalMinor * spec.discountPermille) / 1000,
  );
  return cartTotals({ lines: spec.lines, orderDiscountMinor, tax: stamp });
}

describe('cart totals — the backend checksum invariants', () => {
  it('line totals always sum to the subtotal', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        const totals = build(spec);
        const lineSum = totals.lines.reduce((sum, l) => sum + l.lineTotalMinor, 0);
        expect(lineSum).toBe(totals.subtotalMinor);
      }),
    );
  });

  it('subtotal less discount is always exactly the total', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        const totals = build(spec);
        expect(totals.subtotalMinor - totals.discountMinor).toBe(totals.totalMinor);
      }),
    );
  });

  it('tax never exceeds the total', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        const totals = build(spec);
        expect(totals.taxMinor).toBeLessThanOrEqual(totals.totalMinor);
      }),
    );
  });

  it('every figure is a non-negative integer', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        const totals = build(spec);
        for (const value of [
          totals.subtotalMinor,
          totals.discountMinor,
          totals.taxMinor,
          totals.totalMinor,
        ]) {
          expect(Number.isInteger(value)).toBe(true);
          expect(value).toBeGreaterThanOrEqual(0);
        }
      }),
    );
  });
});

describe('cart totals — internal reconciliation', () => {
  it('the apportioned shares sum to exactly the order discount', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        const totals = build(spec);
        const shares = totals.lines.reduce((sum, l) => sum + l.orderDiscountShareMinor, 0);
        // The whole point of largest-remainder: not "close to", exactly.
        expect(shares).toBe(totals.discountMinor);
      }),
    );
  });

  it('line nets sum to the total', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        const totals = build(spec);
        const nets = totals.lines.reduce((sum, l) => sum + l.netMinor, 0);
        expect(nets).toBe(totals.totalMinor);
      }),
    );
  });

  it('the tax column adds up to the tax printed at the foot of the receipt', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        const totals = build(spec);
        const taxes = totals.lines.reduce((sum, l) => sum + l.taxMinor, 0);
        expect(taxes).toBe(totals.taxMinor);
      }),
    );
  });

  it('no line is charged more tax than it is worth', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        const totals = build(spec);
        for (const line of totals.lines) {
          expect(line.taxMinor).toBeLessThanOrEqual(line.netMinor);
          expect(line.netMinor).toBeGreaterThanOrEqual(0);
        }
      }),
    );
  });

  it('is deterministic — a reprinted receipt matches the original', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        expect(build(spec)).toEqual(build(spec));
      }),
    );
  });

  it('stamps the mode and rate on every result', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        const totals = build(spec);
        expect(totals.taxMode).toBe(spec.mode);
        expect(totals.taxRateBp).toBe(spec.rateBp);
      }),
    );
  });
});

describe('cart totals — worked examples', () => {
  it('matches the M0 sale the terminal actually rang up', () => {
    // KND-T1-000015: one Ceylon Tea at 450.00, 18% inclusive, VAT 68.64.
    const totals = cartTotals({
      lines: [{ productClientUuid: 'p1', qty: 1, unitPriceMinor: 45000 }],
      tax: STAMP,
    });
    expect(totals.subtotalMinor).toBe(45000);
    expect(totals.totalMinor).toBe(45000);
    expect(totals.taxMinor).toBe(6864);
  });

  it('splits an indivisible discount without losing a cent', () => {
    // 10.00 across three equal lines is 3.333... each. Naive rounding discounts 9.99.
    const line = { productClientUuid: 'p', qty: 1, unitPriceMinor: 10000 };
    const totals = cartTotals({
      lines: [line, line, line],
      orderDiscountMinor: 1000,
      tax: STAMP,
    });
    expect(totals.lines.map((l) => l.orderDiscountShareMinor)).toEqual([334, 333, 333]);
    expect(totals.discountMinor).toBe(1000);
    expect(totals.totalMinor).toBe(29000);
  });

  it('grosses up an exclusive price so qty x unit price still equals the line total', () => {
    // 100.00 net at 18% is 118.00 gross; three of them is 354.00.
    const totals = cartTotals({
      lines: [{ productClientUuid: 'p', qty: 3, unitPriceMinor: 10000 }],
      tax: taxStamp('EXCLUSIVE', 1800),
    });
    expect(totals.lines[0]!.unitPriceMinor).toBe(11800);
    expect(totals.lines[0]!.lineTotalMinor).toBe(35400);
    expect(totals.lines[0]!.qty * totals.lines[0]!.unitPriceMinor).toBe(
      totals.lines[0]!.lineTotalMinor,
    );
  });

  it('leaves an inclusive cart untouched — the common case is exact', () => {
    const totals = cartTotals({
      lines: [{ productClientUuid: 'p', qty: 3, unitPriceMinor: 11800 }],
      tax: STAMP,
    });
    expect(totals.totalMinor).toBe(35400);
    expect(totals.taxMinor).toBe(5400);
  });

  it('refuses a discount larger than the cart', () => {
    expect(() =>
      cartTotals({
        lines: [{ productClientUuid: 'p', qty: 1, unitPriceMinor: 1000 }],
        orderDiscountMinor: 2000,
        tax: STAMP,
      }),
    ).toThrow(RangeError);
  });

  it('refuses an order discount on an empty cart', () => {
    expect(() => cartTotals({ lines: [], orderDiscountMinor: 100, tax: STAMP })).toThrow(
      RangeError,
    );
  });

  it('gives an empty cart zeroes and still stamps the tax', () => {
    const totals = emptyCartTotals(STAMP);
    expect(totals.totalMinor).toBe(0);
    expect(totals.taxRateBp).toBe(1800);
  });
});
