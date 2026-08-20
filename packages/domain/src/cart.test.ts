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
import { taxStamp, type TaxMode, type TaxStamp } from './vat';

const STAMP = taxStamp('INCLUSIVE', 1800);

/**
 * Sometimes absent, so the same generated cart exercises both the M1-18 per-line stamp and
 * the M1-05 fall-back to the cart's own — a cart where *every* line overrode would never
 * test the default, and every property below has to hold either way.
 */
const stampArb: fc.Arbitrary<TaxStamp | undefined> = fc.option(
  fc
    .record({
      mode: fc.constantFrom<TaxMode>('INCLUSIVE', 'EXCLUSIVE'),
      // 0 is exempt/zero-rated, and mixing it with a standard rate is the exact basket
      // M1-18 exists for: a loaf of bread and a bottle of arrack.
      rateBp: fc.constantFrom(0, 800, 1500, 1800, 2500),
    })
    .map(({ mode, rateBp }) => taxStamp(mode, rateBp)),
  { nil: undefined },
);

const lineArb: fc.Arbitrary<CartLineInput> = fc
  .record({
    productClientUuid: fc.uuid(),
    qty: fc.integer({ min: 1, max: 20 }),
    unitPriceMinor: fc.integer({ min: 0, max: 1_000_000 }),
    lineDiscountPermille: fc.integer({ min: 0, max: 1000 }),
    tax: stampArb,
  })
  .map(({ productClientUuid, qty, unitPriceMinor, lineDiscountPermille, tax }) => ({
    productClientUuid,
    qty,
    unitPriceMinor,
    // Expressed as a fraction of the line so it can never exceed it.
    lineDiscountMinor: Math.floor((unitPriceMinor * qty * lineDiscountPermille) / 1000),
    ...(tax ? { tax } : {}),
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

describe('tax breakdown — mixed rates in one cart (M1-18)', () => {
  it('the breakdown gross adds up to the total the customer paid', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        const totals = build(spec);
        const gross = totals.taxBreakdown.reduce((sum, e) => sum + e.grossMinor, 0);
        expect(gross).toBe(totals.totalMinor);
      }),
    );
  });

  it('the breakdown tax adds up to the tax printed at the foot', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        const totals = build(spec);
        const tax = totals.taxBreakdown.reduce((sum, e) => sum + e.taxMinor, 0);
        expect(tax).toBe(totals.taxMinor);
      }),
    );
  });

  it('every entry reconciles: net plus tax is gross', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        for (const entry of build(spec).taxBreakdown) {
          // The one identity a tax invoice actually asserts, and the reason exVatMinor is
          // derived here rather than re-extracted from the group.
          expect(entry.exVatMinor + entry.taxMinor).toBe(entry.grossMinor);
          expect(entry.exVatMinor).toBeGreaterThanOrEqual(0);
        }
      }),
    );
  });

  it('holds exactly one entry per distinct stamp, ordered by rate', () => {
    fc.assert(
      fc.property(cartArb, (spec) => {
        const totals = build(spec);
        const keys = totals.taxBreakdown.map((e) => `${e.mode}:${e.rateBp}`);
        expect(new Set(keys).size).toBe(keys.length);

        const distinct = new Set(totals.lines.map((l) => `${l.taxMode}:${l.taxRateBp}`));
        expect(new Set(keys)).toEqual(distinct);

        const rates = totals.taxBreakdown.map((e) => e.rateBp);
        expect(rates).toEqual([...rates].sort((a, b) => a - b));
      }),
    );
  });

  it('prices each line at its own rate, never the first line rate', () => {
    // The bug M1-18 was raised for. Bread is exempt, arrack is 18%; before this, the cart
    // took one stamp and either refused outright or would have charged VAT on the bread.
    const totals = cartTotals({
      lines: [
        {
          productClientUuid: 'bread',
          qty: 2,
          unitPriceMinor: 25000,
          tax: taxStamp('INCLUSIVE', 0),
        },
        {
          productClientUuid: 'arrack',
          qty: 1,
          unitPriceMinor: 450000,
          tax: taxStamp('INCLUSIVE', 1800),
        },
      ],
      tax: taxStamp('INCLUSIVE', 1800),
    });

    expect(totals.lines[0]!.taxMinor).toBe(0);
    // 4500.00 inclusive at 18% -> 4500.00 x 1800 / 11800.
    expect(totals.lines[1]!.taxMinor).toBe(Math.floor((450000 * 1800) / 11800));
    expect(totals.totalMinor).toBe(500000);
    expect(totals.taxMinor).toBe(68644);

    expect(totals.taxBreakdown).toEqual([
      { mode: 'INCLUSIVE', rateBp: 0, grossMinor: 50000, exVatMinor: 50000, taxMinor: 0 },
      {
        mode: 'INCLUSIVE',
        rateBp: 1800,
        grossMinor: 450000,
        exVatMinor: 450000 - 68644,
        taxMinor: 68644,
      },
    ]);
  });

  it('falls back to the cart stamp for a line that carries none', () => {
    const totals = cartTotals({
      lines: [
        { productClientUuid: 'a', qty: 1, unitPriceMinor: 45000 },
        { productClientUuid: 'b', qty: 1, unitPriceMinor: 45000, tax: taxStamp('INCLUSIVE', 0) },
      ],
      tax: taxStamp('INCLUSIVE', 1800),
    });

    expect(totals.lines[0]!.taxRateBp).toBe(1800);
    expect(totals.lines[1]!.taxRateBp).toBe(0);
    // The sale still records the cart's default, which is what `sales.tax_rate_bp` holds.
    expect(totals.taxRateBp).toBe(1800);
    expect(totals.taxBreakdown).toHaveLength(2);
  });

  it('groups a single-rate cart into exactly one entry', () => {
    const totals = cartTotals({
      lines: [
        { productClientUuid: 'a', qty: 1, unitPriceMinor: 45000 },
        { productClientUuid: 'b', qty: 3, unitPriceMinor: 12000 },
      ],
      tax: taxStamp('INCLUSIVE', 1800),
    });

    expect(totals.taxBreakdown).toHaveLength(1);
    expect(totals.taxBreakdown[0]!.grossMinor).toBe(totals.totalMinor);
    expect(totals.taxBreakdown[0]!.taxMinor).toBe(totals.taxMinor);
  });

  it('apportions an order discount across rates, and the summary still reconciles', () => {
    // The nasty one: a whole-order discount reduces an exempt line and a taxed line alike,
    // and the tax on the taxed line has to fall with it. Largest-remainder apportionment
    // plus per-line extraction is what keeps the parts summing to the whole here.
    const totals = cartTotals({
      lines: [
        {
          productClientUuid: 'bread',
          qty: 1,
          unitPriceMinor: 33333,
          tax: taxStamp('INCLUSIVE', 0),
        },
        { productClientUuid: 'tea', qty: 1, unitPriceMinor: 33333 },
        { productClientUuid: 'soap', qty: 1, unitPriceMinor: 33334 },
      ],
      orderDiscountMinor: 10000,
      tax: taxStamp('INCLUSIVE', 1800),
    });

    expect(totals.totalMinor).toBe(90000);
    expect(totals.taxBreakdown.reduce((sum, e) => sum + e.grossMinor, 0)).toBe(totals.totalMinor);
    expect(totals.taxBreakdown.reduce((sum, e) => sum + e.taxMinor, 0)).toBe(totals.taxMinor);
    expect(totals.taxBreakdown[0]!.taxMinor).toBe(0);
  });

  it('gives an empty cart no breakdown at all', () => {
    // Not one zeroed entry: nothing was sold, so there is no rate to report.
    expect(emptyCartTotals(STAMP).taxBreakdown).toEqual([]);
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
