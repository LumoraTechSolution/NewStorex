import { describe, expect, it } from 'vitest';

import { addVatMinor, extractVatMinor, formatMinor, lineTotalMinor, taxForMinor } from './money';

describe('lineTotalMinor', () => {
  it('multiplies in integer minor units', () => {
    expect(lineTotalMinor(45000, 2)).toBe(90000);
  });

  it('refuses a fractional price, which would mean a float crept in', () => {
    expect(() => lineTotalMinor(450.5, 1)).toThrow(RangeError);
  });

  it('refuses a quantity below one', () => {
    expect(() => lineTotalMinor(45000, 0)).toThrow(RangeError);
  });
});

describe('extractVatMinor', () => {
  it('extracts VAT from a tax-inclusive price', () => {
    // 900.00 inclusive of 18% contains 137.28 of VAT: 90000 * 1800 / 11800.
    expect(extractVatMinor(90000, 1800)).toBe(13728);
  });

  it('never returns more than the amount it came out of', () => {
    for (const total of [1, 7, 99, 45000, 1_234_567]) {
      expect(extractVatMinor(total, 1800)).toBeLessThanOrEqual(total);
    }
  });

  it('is zero at a zero rate', () => {
    expect(extractVatMinor(90000, 0)).toBe(0);
  });

  it('does not multiply the rate onto the price', () => {
    // The wrong answer — 90000 * 0.18 — is 16200. Extraction gives less, because the
    // tax is already inside the price.
    expect(extractVatMinor(90000, 1800)).not.toBe(16200);
  });

  it('always returns an integer', () => {
    for (const total of [1, 2, 3, 17, 333, 99999]) {
      expect(Number.isInteger(extractVatMinor(total, 1800))).toBe(true);
    }
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

describe('formatMinor', () => {
  it('renders cents as two decimal places', () => {
    expect(formatMinor(90000)).toBe('900.00');
    expect(formatMinor(5)).toBe('0.05');
    expect(formatMinor(1234567)).toBe('12,345.67');
  });

  it('handles negatives, for refunds and change', () => {
    expect(formatMinor(-4550)).toBe('-45.50');
  });
});
