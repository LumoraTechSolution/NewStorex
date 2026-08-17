import fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import { minor } from './money';
import { summariseTender, suggestedTenderAmountMinor, type TenderLine } from './tender';

describe('summariseTender', () => {
  it('settles exact cash with no change and no rounding', () => {
    const tenders: TenderLine[] = [{ kind: 'CASH', amountMinor: minor(45000) }];
    const summary = summariseTender(45000, tenders);

    expect(summary.settled).toBe(true);
    expect(summary.changeDueMinor).toBe(0);
    expect(summary.remainingDueMinor).toBe(0);
    expect(summary.roundingAdjustmentMinor).toBe(0);
  });

  it('rounds the cash remainder to the nearest rupee, up', () => {
    // 450.50 owed in cash rounds to 451.00 payable.
    const summary = summariseTender(45050, [{ kind: 'CASH', amountMinor: minor(45050) }]);

    expect(summary.cashPayableMinor).toBe(45100);
    expect(summary.roundingAdjustmentMinor).toBe(50);
    // The customer only handed over the unrounded amount, so the rounding delta (0.50) is
    // still owed.
    expect(summary.remainingDueMinor).toBe(50);
    expect(summary.settled).toBe(false);
  });

  it('gives change when cash received exceeds the rounded amount owed', () => {
    // Total 50.00, customer hands over a 1000 note.
    const summary = summariseTender(5000, [{ kind: 'CASH', amountMinor: minor(100000) }]);

    expect(summary.settled).toBe(true);
    // Change (950.00) is larger than the sale total (50.00) — must not be assumed impossible.
    expect(summary.changeDueMinor).toBe(95000);
    expect(summary.changeDueMinor).toBeGreaterThan(summary.totalDueMinor);
  });

  it('splits a sale across card and cash', () => {
    const summary = summariseTender(90000, [
      { kind: 'CARD', amountMinor: minor(60000) },
      { kind: 'CASH', amountMinor: minor(30000) },
    ]);

    expect(summary.nonCashAppliedMinor).toBe(60000);
    expect(summary.cashOwedMinor).toBe(30000);
    expect(summary.settled).toBe(true);
    expect(summary.changeDueMinor).toBe(0);
  });

  it('rounds only the cash-covered remainder in a split sale, never the card line', () => {
    // Total 100.50: card takes 100.00 exactly, cash covers the odd 0.50, which rounds to 1.00.
    const summary = summariseTender(10050, [
      { kind: 'CARD', amountMinor: minor(10000) },
      { kind: 'CASH', amountMinor: minor(100) },
    ]);

    expect(summary.nonCashAppliedMinor).toBe(10000);
    expect(summary.cashOwedMinor).toBe(50);
    expect(summary.cashPayableMinor).toBe(100);
    expect(summary.remainingDueMinor).toBe(0);
    expect(summary.settled).toBe(true);
  });

  it('reports what is still owed when nothing has been tendered yet', () => {
    const summary = summariseTender(45000, []);
    expect(summary.remainingDueMinor).toBe(45000);
    expect(summary.settled).toBe(false);
  });

  it('refuses a non-cash total that overshoots what is owed', () => {
    expect(() => summariseTender(45000, [{ kind: 'CARD', amountMinor: minor(45001) }])).toThrow(
      /cannot make change/,
    );
  });

  it('refuses two card lines that together overshoot, even though neither does alone', () => {
    expect(() =>
      summariseTender(45000, [
        { kind: 'CARD', amountMinor: minor(30000) },
        { kind: 'WALLET', amountMinor: minor(20000) },
      ]),
    ).toThrow(/cannot make change/);
  });

  it('is order-independent — only the totals per kind matter', () => {
    const cashFirst = summariseTender(90000, [
      { kind: 'CASH', amountMinor: minor(30000) },
      { kind: 'CARD', amountMinor: minor(60000) },
    ]);
    const cardFirst = summariseTender(90000, [
      { kind: 'CARD', amountMinor: minor(60000) },
      { kind: 'CASH', amountMinor: minor(30000) },
    ]);
    expect(cashFirst).toEqual(cardFirst);
  });

  it('reconciles: what was handed over, less change, equals the total plus the rounding adjustment, once settled', () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 0, max: 500_000 }),
        fc.integer({ min: 0, max: 500_000 }),
        fc.integer({ min: 0, max: 1_000_000 }),
        (nonCash, cash, totalDueRaw) => {
          const totalDue = totalDueRaw;
          fc.pre(nonCash <= totalDue); // a valid, non-throwing scenario only
          const tenders: TenderLine[] = [
            { kind: 'CARD', amountMinor: minor(nonCash) },
            { kind: 'CASH', amountMinor: minor(cash) },
          ];
          const summary = summariseTender(totalDue, tenders);
          const handedOver = nonCash + cash;

          if (summary.settled) {
            expect(handedOver - summary.changeDueMinor).toBe(
              totalDue + summary.roundingAdjustmentMinor,
            );
          } else {
            expect(summary.changeDueMinor).toBe(0);
          }
        },
      ),
    );
  });
});

describe('suggestedTenderAmountMinor', () => {
  it('suggests the exact total for the first cash line', () => {
    expect(suggestedTenderAmountMinor(45000, [], 'CASH')).toBe(45000);
  });

  it('suggests the rounded cash remainder after a card line', () => {
    const tendersSoFar: TenderLine[] = [{ kind: 'CARD', amountMinor: minor(10000) }];
    // 10050 - 10000 = 50 owed in cash, which rounds to 100.
    expect(suggestedTenderAmountMinor(10050, tendersSoFar, 'CASH')).toBe(100);
  });

  it('suggests the exact unrounded remainder for a non-cash line', () => {
    expect(suggestedTenderAmountMinor(45000, [], 'CARD')).toBe(45000);
  });

  it('never suggests a negative amount once the sale is already covered', () => {
    const tendersSoFar: TenderLine[] = [{ kind: 'CASH', amountMinor: minor(100000) }];
    expect(suggestedTenderAmountMinor(5000, tendersSoFar, 'CARD')).toBe(0);
    expect(suggestedTenderAmountMinor(5000, tendersSoFar, 'CASH')).toBe(0);
  });
});
