import fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import {
  addMinor,
  formatMinor,
  lineTotalMinor,
  minor,
  multiplyMinor,
  parseAmountToMinor,
  subtractMinor,
  sumMinor,
  ZERO_MINOR,
} from './money';

describe('minor', () => {
  it('accepts a whole number of cents', () => {
    expect(minor(45000)).toBe(45000);
  });

  it('refuses a fractional value, which would mean a float reached the money path', () => {
    expect(() => minor(450.5)).toThrow(RangeError);
  });

  it('refuses NaN and Infinity rather than letting them poison a total', () => {
    expect(() => minor(Number.NaN)).toThrow(RangeError);
    expect(() => minor(Number.POSITIVE_INFINITY)).toThrow(RangeError);
  });

  it('refuses values past the exact-integer range', () => {
    // Beyond 2^53 addition silently stops being exact, which is the failure this module exists to prevent.
    expect(() => minor(Number.MAX_SAFE_INTEGER + 2)).toThrow(RangeError);
  });
});

describe('arithmetic', () => {
  it('multiplies only by whole counts', () => {
    expect(multiplyMinor(minor(45000), 3)).toBe(135000);
    expect(() => multiplyMinor(minor(45000), 1.5)).toThrow(RangeError);
  });

  it('sums an empty list to zero', () => {
    expect(sumMinor([])).toBe(ZERO_MINOR);
  });

  it('adds and subtracts exactly where a float would not', () => {
    // 0.1 + 0.2 !== 0.3 in binary floating point. In minor units it is just 10 + 20.
    expect(addMinor(minor(10), minor(20))).toBe(30);
    expect(subtractMinor(minor(30), minor(10))).toBe(20);
  });
});

describe('lineTotalMinor', () => {
  it('multiplies in integer minor units', () => {
    expect(lineTotalMinor(45000, 2)).toBe(90000);
  });

  it('refuses a fractional price', () => {
    expect(() => lineTotalMinor(450.5, 1)).toThrow(RangeError);
  });

  it('refuses a quantity below one', () => {
    expect(() => lineTotalMinor(45000, 0)).toThrow(RangeError);
  });
});

describe('formatMinor', () => {
  it('always shows two decimal places', () => {
    expect(formatMinor(45000)).toBe('450.00');
    expect(formatMinor(45050)).toBe('450.50');
    expect(formatMinor(45005)).toBe('450.05');
  });

  it('groups thousands and keeps the sign', () => {
    expect(formatMinor(285000)).toBe('2,850.00');
    expect(formatMinor(-45050)).toBe('-450.50');
  });
});

describe('parseAmountToMinor', () => {
  it('parses what a cashier actually types', () => {
    expect(parseAmountToMinor('450')).toBe(45000);
    expect(parseAmountToMinor('450.5')).toBe(45050);
    expect(parseAmountToMinor('450.50')).toBe(45050);
    expect(parseAmountToMinor('1,250.75')).toBe(125075);
    expect(parseAmountToMinor('.5')).toBe(50);
  });

  it('parses the value parseFloat gets wrong', () => {
    // parseFloat('0.29') * 100 is 28.999999999999996, so the obvious implementation
    // loses a cent on an amount that comes up every day.
    expect(parseAmountToMinor('0.29')).toBe(29);
  });

  it('returns null for a half-typed or unparseable amount instead of throwing', () => {
    for (const input of ['', '   ', 'abc', '4.5.6', '450.567', '1e3']) {
      expect(parseAmountToMinor(input)).toBeNull();
    }
  });

  it('round-trips through formatMinor for every representable amount', () => {
    fc.assert(
      fc.property(fc.integer({ min: -99_999_999, max: 99_999_999 }), (cents) => {
        expect(parseAmountToMinor(formatMinor(cents))).toBe(cents);
      }),
    );
  });
});
