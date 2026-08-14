import fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import {
  CASH_ROUNDING_INCREMENT_MINOR,
  cashRoundingDeltaMinor,
  payableMinor,
  roundCashMinor,
  tenderRounds,
} from './rounding';

describe('roundCashMinor', () => {
  it('rounds to the nearest whole rupee', () => {
    expect(roundCashMinor(45000)).toBe(45000);
    expect(roundCashMinor(45049)).toBe(45000);
    expect(roundCashMinor(45051)).toBe(45100);
  });

  it('rounds a half away from zero, so a refund reverses its sale exactly', () => {
    expect(roundCashMinor(45050)).toBe(45100);
    expect(roundCashMinor(-45050)).toBe(-45100);
    // Math.round(-0.5) is -0, which would refund a rupee less than was taken.
    expect(roundCashMinor(-45050)).toBe(-roundCashMinor(45050));
  });

  it('always lands on the increment', () => {
    fc.assert(
      fc.property(fc.integer({ min: -10_000_000, max: 10_000_000 }), (amount) => {
        // Math.abs because `-45100 % 100` is -0 in JavaScript, and toBe compares with
        // Object.is. That is the modulo's quirk, not the rounding's.
        expect(Math.abs(roundCashMinor(amount) % CASH_ROUNDING_INCREMENT_MINOR)).toBe(0);
      }),
    );
  });

  it('never moves an amount by more than half a rupee', () => {
    fc.assert(
      fc.property(fc.integer({ min: -10_000_000, max: 10_000_000 }), (amount) => {
        expect(Math.abs(roundCashMinor(amount) - amount)).toBeLessThanOrEqual(
          CASH_ROUNDING_INCREMENT_MINOR / 2,
        );
      }),
    );
  });

  it('is idempotent', () => {
    fc.assert(
      fc.property(fc.integer({ min: -10_000_000, max: 10_000_000 }), (amount) => {
        expect(roundCashMinor(roundCashMinor(amount))).toBe(roundCashMinor(amount));
      }),
    );
  });
});

describe('payableMinor', () => {
  it('rounds cash and leaves everything else exact', () => {
    expect(payableMinor(45050, 'CASH')).toBe(45100);
    // A card charge must match the receipt to the cent — the customer disputes anything else.
    expect(payableMinor(45050, 'CARD')).toBe(45050);
    expect(payableMinor(45050, 'WALLET')).toBe(45050);
    expect(payableMinor(45050, 'STORE_CREDIT')).toBe(45050);
  });

  it('only cash rounds', () => {
    expect(tenderRounds('CASH')).toBe(true);
    for (const kind of ['CARD', 'WALLET', 'STORE_CREDIT'] as const) {
      expect(tenderRounds(kind)).toBe(false);
    }
  });
});

describe('cashRoundingDeltaMinor', () => {
  it('reports what the drawer gained or gave away', () => {
    expect(cashRoundingDeltaMinor(45051)).toBe(49); // customer paid 0.49 more
    expect(cashRoundingDeltaMinor(45049)).toBe(-49); // shop absorbed 0.49
    expect(cashRoundingDeltaMinor(45000)).toBe(0);
  });

  it('always reconciles the exact amount to the rounded one', () => {
    fc.assert(
      fc.property(fc.integer({ min: -10_000_000, max: 10_000_000 }), (exact) => {
        // This is the identity the cash-up screen depends on (M2): the drawer is the
        // sale totals plus the rounding deltas, with nothing unexplained.
        expect(exact + cashRoundingDeltaMinor(exact)).toBe(roundCashMinor(exact));
      }),
    );
  });
});
