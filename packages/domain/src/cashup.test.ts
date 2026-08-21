import { describe, expect, it } from 'vitest';

import {
  breakIntoDenominations,
  countedTotalMinor,
  expectedCashMinor,
  LKR_DENOMINATIONS_MINOR,
  netCashMovementMinor,
  reconcileCash,
  signedCashMovementMinor,
  type CashDrawerEntries,
} from './cashup';

/** A drawer that balances exactly. Individual tests move one term at a time. */
const BALANCED: CashDrawerEntries = {
  openingFloatMinor: 500_000, // LKR 5,000 float
  cashSalesMinor: 1_240_000,
  cashChangeMinor: 180_000,
  cashRoundingMinor: 350,
  cashMovementsMinor: 0,
  cashRefundsMinor: 0,
  cashRefundRoundingMinor: 0,
};

const BALANCED_EXPECTED = 500_000 + 1_240_000 + 350 - 180_000;

describe('countedTotalMinor', () => {
  it('adds up a physical count', () => {
    expect(
      countedTotalMinor([
        { denominationMinor: 500_000, qty: 2 }, // 2 × LKR 5000
        { denominationMinor: 100_000, qty: 3 }, // 3 × LKR 1000
        { denominationMinor: 5_000, qty: 4 }, // 4 × LKR 50
        { denominationMinor: 100, qty: 7 }, // 7 × LKR 1
      ]),
    ).toBe(1_000_000 + 300_000 + 20_000 + 700);
  });

  it('is zero for an empty drawer, counted or not', () => {
    expect(countedTotalMinor([])).toBe(0);
    expect(
      countedTotalMinor(LKR_DENOMINATIONS_MINOR.map((d) => ({ denominationMinor: d, qty: 0 }))),
    ).toBe(0);
  });

  it('refuses a denomination that does not circulate', () => {
    // The case this catches in the field is a screen sending rupees where minor units are
    // expected: 5000 would be the LKR 50 note, so the drawer would silently read 100× light.
    expect(() => countedTotalMinor([{ denominationMinor: 25_000, qty: 1 }])).toThrow(
      /not a circulating/,
    );
  });

  it('refuses the same denomination counted twice', () => {
    expect(() =>
      countedTotalMinor([
        { denominationMinor: 10_000, qty: 2 },
        { denominationMinor: 10_000, qty: 3 },
      ]),
    ).toThrow(/counted twice/);
  });

  it('refuses a fractional or negative count', () => {
    expect(() => countedTotalMinor([{ denominationMinor: 10_000, qty: 1.5 }])).toThrow(
      /whole number/,
    );
    expect(() => countedTotalMinor([{ denominationMinor: 10_000, qty: -1 }])).toThrow(
      /whole number/,
    );
  });
});

describe('expectedCashMinor', () => {
  it('is float plus cash taken, less change handed back', () => {
    expect(expectedCashMinor(BALANCED)).toBe(BALANCED_EXPECTED);
  });

  it('adds the cash-rounding residual rather than hiding it', () => {
    // M1-03 left this figure for M2 to account for. If it were dropped, every shift with
    // cash sales would show a small unexplained variance and cashiers would learn to ignore
    // the number entirely — which is the failure mode, not the rounding.
    const withoutRounding = expectedCashMinor({ ...BALANCED, cashRoundingMinor: 0 });
    expect(BALANCED_EXPECTED - withoutRounding).toBe(350);
  });

  it('takes cash movements at their stored sign, with no branch on kind', () => {
    const drop = expectedCashMinor({ ...BALANCED, cashMovementsMinor: -400_000 });
    const payIn = expectedCashMinor({ ...BALANCED, cashMovementsMinor: 400_000 });
    expect(drop).toBe(BALANCED_EXPECTED - 400_000);
    expect(payIn).toBe(BALANCED_EXPECTED + 400_000);
  });

  it('subtracts cash refunds and the rounding on them', () => {
    expect(
      expectedCashMinor({ ...BALANCED, cashRefundsMinor: 45_000, cashRefundRoundingMinor: 50 }),
    ).toBe(BALANCED_EXPECTED - 45_000 - 50);
  });

  it('may go negative when more was dropped than taken', () => {
    // Unusual, not impossible — and clamping it at zero would hide the one shift worth
    // looking at.
    expect(expectedCashMinor({ ...BALANCED, cashMovementsMinor: -9_000_000 })).toBeLessThan(0);
  });

  it('refuses a negative figure where only a magnitude makes sense', () => {
    expect(() => expectedCashMinor({ ...BALANCED, cashSalesMinor: -1 })).toThrow(
      /must not be negative/,
    );
    expect(() => expectedCashMinor({ ...BALANCED, openingFloatMinor: -1 })).toThrow(
      /must not be negative/,
    );
  });
});

describe('reconcileCash', () => {
  const THRESHOLD = 10_000; // LKR 100

  it('reports a balanced drawer', () => {
    const r = reconcileCash(500_000, 500_000, THRESHOLD);
    expect(r.varianceMinor).toBe(0);
    expect(r.direction).toBe('BALANCED');
    expect(r.requiresReason).toBe(false);
  });

  it('signs the variance as counted less expected', () => {
    expect(reconcileCash(500_000, 495_000, THRESHOLD).varianceMinor).toBe(-5_000);
    expect(reconcileCash(500_000, 505_000, THRESHOLD).varianceMinor).toBe(5_000);
    expect(reconcileCash(500_000, 495_000, THRESHOLD).direction).toBe('SHORT');
    expect(reconcileCash(500_000, 505_000, THRESHOLD).direction).toBe('OVER');
  });

  it('requires a reason for over as readily as for short', () => {
    // A drawer LKR 500 over usually means a sale nobody rang up, which is the worse problem.
    // A threshold that only looked at shortfalls would wave it through every time.
    expect(reconcileCash(500_000, 550_000, THRESHOLD).requiresReason).toBe(true);
    expect(reconcileCash(500_000, 450_000, THRESHOLD).requiresReason).toBe(true);
  });

  it('treats the threshold as inclusive — exactly on it is still acceptable', () => {
    expect(reconcileCash(500_000, 510_000, THRESHOLD).requiresReason).toBe(false);
    expect(reconcileCash(500_000, 510_001, THRESHOLD).requiresReason).toBe(true);
  });

  it('honours a per-tenant threshold rather than a constant (D1)', () => {
    // The jeweller and the grocer from §E, differing by two orders of magnitude on the same
    // variance.
    const variance = { expected: 40_000_000, counted: 40_020_000 };
    expect(reconcileCash(variance.expected, variance.counted, 10_000).requiresReason).toBe(true);
    expect(reconcileCash(variance.expected, variance.counted, 500_000).requiresReason).toBe(false);
  });

  it('reconciles a drawer counted note by note against its entries', () => {
    const expected = expectedCashMinor(BALANCED);
    const counted = countedTotalMinor([
      { denominationMinor: 500_000, qty: 3 },
      { denominationMinor: 10_000, qty: 5 },
      { denominationMinor: 5_000, qty: 6 },
      { denominationMinor: 100, qty: 3 },
    ]);
    const r = reconcileCash(expected, counted, THRESHOLD);
    expect(r.countedMinor).toBe(1_580_300);
    expect(r.varianceMinor).toBe(counted - expected);
  });
});

describe('signedCashMovementMinor', () => {
  it('puts the sign on the kind, not on the typist', () => {
    expect(signedCashMovementMinor('PAY_IN', 50_000)).toBe(50_000);
    expect(signedCashMovementMinor('PAY_OUT', 50_000)).toBe(-50_000);
    expect(signedCashMovementMinor('DROP', 50_000)).toBe(-50_000);
  });

  it('refuses a negative amount — the cashier types how much, never which way', () => {
    expect(() => signedCashMovementMinor('PAY_OUT', -50_000)).toThrow(/must not be negative/);
  });

  it('refuses a movement of nothing', () => {
    expect(() => signedCashMovementMinor('DROP', 0)).toThrow(/not a movement/);
  });
});

describe('netCashMovementMinor', () => {
  it('sums signed movements with no case analysis', () => {
    expect(netCashMovementMinor([100_000, -400_000, -50_000, 25_000])).toBe(-325_000);
  });

  it('is zero for a shift where nothing moved', () => {
    expect(netCashMovementMinor([])).toBe(0);
  });
});

describe('breakIntoDenominations', () => {
  it('makes an amount from the fewest notes and coins', () => {
    // LKR 6,783.00 → one each of 5000/1000/500, two 100s, then 50 + 20 + 10 + 2 + 1.
    expect(breakIntoDenominations(678_300)).toEqual([
      { denominationMinor: 500_000, qty: 1 },
      { denominationMinor: 100_000, qty: 1 },
      { denominationMinor: 50_000, qty: 1 },
      { denominationMinor: 10_000, qty: 2 },
      { denominationMinor: 5_000, qty: 1 },
      { denominationMinor: 2_000, qty: 1 },
      { denominationMinor: 1_000, qty: 1 },
      { denominationMinor: 200, qty: 1 },
      { denominationMinor: 100, qty: 1 },
    ]);
  });

  it('round-trips through countedTotalMinor for every whole rupee up to LKR 2,000', () => {
    for (let rupees = 0; rupees <= 2000; rupees++) {
      const amount = rupees * 100;
      expect(countedTotalMinor(breakIntoDenominations(amount))).toBe(amount);
    }
  });

  it('refuses a sub-rupee amount instead of coming up short', () => {
    expect(() => breakIntoDenominations(45_050)).toThrow(/whole rupees/);
  });
});
