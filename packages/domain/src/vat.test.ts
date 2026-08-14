import fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import { addVatMinor, extractVatMinor, grossForMinor, taxForMinor, taxStamp } from './vat';

describe('extractVatMinor', () => {
  it('extracts VAT from a tax-inclusive price', () => {
    // 900.00 inclusive of 18% contains 137.28 of VAT: 90000 * 1800 / 11800.
    expect(extractVatMinor(90000, 1800)).toBe(13728);
  });

  it('does not multiply the rate onto the price', () => {
    // The wrong answer — 90000 * 0.18 — is 16200, a 24.72 overstatement per line, in the
    // shop's favour, on a figure the revenue authority checks.
    expect(extractVatMinor(90000, 1800)).not.toBe(16200);
  });

  it('is zero at a zero rate', () => {
    expect(extractVatMinor(90000, 0)).toBe(0);
  });

  it('never returns more than the amount it came out of', () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 0, max: 100_000_000 }),
        fc.integer({ min: 0, max: 10_000 }),
        (total, rateBp) => {
          const vat = extractVatMinor(total, rateBp);
          expect(vat).toBeLessThanOrEqual(total);
          expect(Number.isInteger(vat)).toBe(true);
          expect(vat).toBeGreaterThanOrEqual(0);
        },
      ),
    );
  });

  it('rejects a negative amount or rate rather than returning a plausible wrong number', () => {
    expect(() => extractVatMinor(-1, 1800)).toThrow(RangeError);
    expect(() => extractVatMinor(100, -1)).toThrow(RangeError);
  });
});

describe('addVatMinor', () => {
  it('adds VAT to a tax-exclusive amount', () => {
    expect(addVatMinor(10000, 1800)).toBe(1800);
  });
});

describe('taxForMinor', () => {
  it('extracts when inclusive and adds when exclusive', () => {
    expect(taxForMinor(11800, 1800, 'INCLUSIVE')).toBe(1800);
    expect(taxForMinor(10000, 1800, 'EXCLUSIVE')).toBe(1800);
  });
});

describe('grossForMinor', () => {
  it('is the identity for an inclusive price', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 10_000_000 }), (amount) => {
        expect(grossForMinor(amount, taxStamp('INCLUSIVE', 1800))).toBe(amount);
      }),
    );
  });

  it('grosses up an exclusive price', () => {
    expect(grossForMinor(10000, taxStamp('EXCLUSIVE', 1800))).toBe(11800);
  });

  it('produces a gross whose extracted VAT never exceeds the tax that was added', () => {
    // Round-tripping net -> gross -> extracted can lose a cent to truncation, but it must
    // never gain one: that would be tax the shop never collected.
    fc.assert(
      fc.property(
        fc.integer({ min: 0, max: 10_000_000 }),
        fc.constantFrom(0, 800, 1500, 1800, 2500),
        (net, rateBp) => {
          const gross = grossForMinor(net, taxStamp('EXCLUSIVE', rateBp));
          const added = addVatMinor(net, rateBp);
          expect(extractVatMinor(gross, rateBp)).toBeLessThanOrEqual(added);
          expect(gross).toBe(net + added);
        },
      ),
    );
  });
});

describe('taxStamp', () => {
  it('refuses an unknown mode or a fractional rate', () => {
    // @ts-expect-error — the guard exists for values arriving from JSON, which is untyped.
    expect(() => taxStamp('SOMETIMES', 1800)).toThrow(RangeError);
    expect(() => taxStamp('INCLUSIVE', 18.5)).toThrow(RangeError);
  });
});
