import { describe, expect, it } from 'vitest';

import { cartTotals } from './cart';
import {
  allocateRefundTenders,
  assertRefundTendersAllowed,
  refundableQty,
  refundableTenders,
  refundLineAmounts,
  summariseRefund,
  type RefundableSaleLine,
  type RefundLineRequest,
} from './refund';
import { taxStamp } from './vat';

function saleLine(over: Partial<RefundableSaleLine> = {}): RefundableSaleLine {
  return {
    lineNo: 1,
    productClientUuid: '00000000-0000-4000-8000-000000000101',
    name: 'Ceylon Tea 400g',
    qty: 3,
    unitPriceMinor: 45_000,
    chargedMinor: 135_000,
    taxMinor: 20_593,
    taxMode: 'INCLUSIVE',
    taxRateBp: 1800,
    alreadyRefundedQty: 0,
    ...over,
  };
}

function request(over: Partial<RefundLineRequest> = {}): RefundLineRequest {
  return { saleLineNo: 1, qty: 1, reasonCode: 'CHANGED_MIND', restock: true, ...over };
}

describe('refundableQty', () => {
  it('is what the line has left', () => {
    expect(refundableQty(saleLine({ qty: 3, alreadyRefundedQty: 1 }))).toBe(2);
    expect(refundableQty(saleLine({ qty: 3, alreadyRefundedQty: 3 }))).toBe(0);
  });

  it('shouts rather than clamps if the ledger is already past the cap', () => {
    expect(() => refundableQty(saleLine({ qty: 3, alreadyRefundedQty: 4 }))).toThrow(
      /refunded against a qty/,
    );
  });
});

describe('refundLineAmounts', () => {
  it('refunds what was charged, not the current shelf price', () => {
    // Line charged 1,242.00 for 3 units after a discount — a third of it is 414.00, and the
    // product's own 450.00 price has nothing to do with what goes back.
    const line = saleLine({ chargedMinor: 124_200, taxMinor: 18_946 });
    expect(refundLineAmounts(line, request({ qty: 1 })).refundTotalMinor).toBe(41_400);
  });

  it('refuses more units than the line has left', () => {
    expect(() =>
      refundLineAmounts(saleLine({ alreadyRefundedQty: 2 }), request({ qty: 2 })),
    ).toThrow(/1 of 3 remain/);
  });

  it('refuses a fractional or zero quantity', () => {
    expect(() => refundLineAmounts(saleLine(), request({ qty: 0 }))).toThrow(/at least 1/);
    expect(() => refundLineAmounts(saleLine(), request({ qty: 1.5 }))).toThrow(/whole number/);
  });

  it('carries the rate the sale was stamped with, not today’s', () => {
    // M1-05: a sale made at 15% is refunded at 15% even after the rate moves to 18%.
    const line = saleLine({ taxRateBp: 1500, taxMode: 'INCLUSIVE' });
    const amounts = refundLineAmounts(line, request());
    expect(amounts.taxRateBp).toBe(1500);
    expect(amounts.taxMode).toBe('INCLUSIVE');
  });

  it('keeps the per-line reason and restock flag (M2-08, M2-10)', () => {
    const amounts = refundLineAmounts(
      saleLine(),
      request({ reasonCode: 'DAMAGED', restock: false }),
    );
    expect(amounts.reasonCode).toBe('DAMAGED');
    expect(amounts.restock).toBe(false);
  });
});

describe('partial returns sum back to the whole', () => {
  /**
   * The property the cumulative apportionment exists for. A line returned in pieces must give
   * back exactly what returning it in one go would — otherwise the shop pockets a rounding
   * error on every split return and nobody can explain the difference.
   */
  function returnInPieces(charged: number, tax: number, qty: number, pieces: readonly number[]) {
    let alreadyRefundedQty = 0;
    let gross = 0;
    let vat = 0;
    for (const piece of pieces) {
      const amounts = refundLineAmounts(
        saleLine({ qty, chargedMinor: charged, taxMinor: tax, alreadyRefundedQty }),
        request({ qty: piece }),
      );
      gross += amounts.refundTotalMinor;
      vat += amounts.taxMinor;
      alreadyRefundedQty += piece;
    }
    return { gross, vat };
  }

  it('one at a time equals all at once, for an amount that does not divide evenly', () => {
    // 1000 over 3 units is 333.33… — the case naive per-unit rounding gets wrong by a cent.
    expect(returnInPieces(1000, 152, 3, [1, 1, 1])).toEqual({ gross: 1000, vat: 152 });
    expect(returnInPieces(1000, 152, 3, [3])).toEqual({ gross: 1000, vat: 152 });
    expect(returnInPieces(1000, 152, 3, [1, 2])).toEqual({ gross: 1000, vat: 152 });
    expect(returnInPieces(1000, 152, 3, [2, 1])).toEqual({ gross: 1000, vat: 152 });
  });

  it('holds across every split of every line up to 12 units, for awkward amounts', () => {
    const amounts = [1, 7, 99, 100, 101, 999, 1_000, 12_345, 124_200, 999_999];
    for (const charged of amounts) {
      const tax = Math.floor((charged * 1800) / 11800); // 18% extracted, as the sale did it
      for (let qty = 1; qty <= 12; qty++) {
        const whole = returnInPieces(charged, tax, qty, [qty]);
        expect(whole).toEqual({ gross: charged, vat: tax });

        // Every way of splitting the line into two returns.
        for (let first = 1; first < qty; first++) {
          expect(returnInPieces(charged, tax, qty, [first, qty - first])).toEqual({
            gross: charged,
            vat: tax,
          });
        }

        // And one unit at a time.
        expect(returnInPieces(charged, tax, qty, Array<number>(qty).fill(1))).toEqual({
          gross: charged,
          vat: tax,
        });
      }
    }
  });

  it('never apportions more tax than gross', () => {
    for (let qty = 1; qty <= 8; qty++) {
      for (let charged = 1; charged <= 400; charged++) {
        const tax = Math.floor((charged * 1800) / 11800);
        for (let already = 0; already < qty; already++) {
          const amounts = refundLineAmounts(
            saleLine({ qty, chargedMinor: charged, taxMinor: tax, alreadyRefundedQty: already }),
            request({ qty: 1 }),
          );
          expect(amounts.taxMinor).toBeLessThanOrEqual(amounts.refundTotalMinor);
        }
      }
    }
  });
});

describe('summariseRefund', () => {
  const lines = [
    saleLine({ lineNo: 1 }),
    saleLine({ lineNo: 2, qty: 2, chargedMinor: 50_000, taxMinor: 7_627 }),
  ];

  it('totals the returned lines', () => {
    const summary = summariseRefund(lines, [
      request({ saleLineNo: 1, qty: 1 }),
      request({ saleLineNo: 2, qty: 2, reasonCode: 'DAMAGED', restock: false }),
    ]);
    expect(summary.lines).toHaveLength(2);
    expect(summary.totalMinor).toBe(45_000 + 50_000);
    expect(summary.taxMinor).toBe(6_864 + 7_627);
  });

  it('refuses a line the sale does not have', () => {
    expect(() => summariseRefund(lines, [request({ saleLineNo: 9 })])).toThrow(/no line 9/);
  });

  it('refuses the same line twice — the two would each price against the same remainder', () => {
    expect(() =>
      summariseRefund(lines, [request({ saleLineNo: 1 }), request({ saleLineNo: 1 })]),
    ).toThrow(/appears twice/);
  });

  it('refuses an empty refund', () => {
    expect(() => summariseRefund(lines, [])).toThrow(/at least one line/);
  });

  it('reverses a real cart exactly when every line comes back', () => {
    // End to end against the sale path: price a mixed-rate cart with an order discount, then
    // return all of it. The refund must equal the sale to the cent, including its VAT.
    const totals = cartTotals({
      tax: taxStamp('INCLUSIVE', 1800),
      orderDiscountMinor: 7_777,
      lines: [
        { productClientUuid: 'a', qty: 3, unitPriceMinor: 45_000 },
        { productClientUuid: 'b', qty: 2, unitPriceMinor: 25_000, tax: taxStamp('INCLUSIVE', 0) },
        { productClientUuid: 'c', qty: 7, unitPriceMinor: 18_500, lineDiscountMinor: 3_000 },
      ],
    });

    const refundable: RefundableSaleLine[] = totals.lines.map((line) => ({
      lineNo: line.lineNo,
      productClientUuid: line.productClientUuid,
      name: line.productClientUuid,
      qty: line.qty,
      unitPriceMinor: line.unitPriceMinor,
      chargedMinor: line.netMinor,
      taxMinor: line.taxMinor,
      taxMode: line.taxMode,
      taxRateBp: line.taxRateBp,
      alreadyRefundedQty: 0,
    }));

    const summary = summariseRefund(
      refundable,
      refundable.map((line) => request({ saleLineNo: line.lineNo, qty: line.qty })),
    );
    expect(summary.totalMinor).toBe(totals.totalMinor);
    expect(summary.taxMinor).toBe(totals.taxMinor);
  });
});

describe('refundableTenders', () => {
  it('nets change off the cash a sale recorded', () => {
    // The customer handed over 500.00 for a 320.00 basket and took 180.00 back. Only 320.00
    // ever stayed in the drawer, and only 320.00 can come out of it.
    const [cash] = refundableTenders([{ kind: 'CASH', amountMinor: 50_000 }], 18_000, []);
    expect(cash!.paidMinor).toBe(32_000);
    expect(cash!.refundableMinor).toBe(32_000);
  });

  it('subtracts what earlier refunds already sent back', () => {
    const [card] = refundableTenders([{ kind: 'CARD', amountMinor: 100_000 }], 0, [
      { kind: 'CARD', amountMinor: 30_000 },
    ]);
    expect(card!.refundableMinor).toBe(70_000);
  });

  it('drops a kind that is fully refunded rather than reporting it as negative', () => {
    const [card] = refundableTenders([{ kind: 'CARD', amountMinor: 100_000 }], 0, [
      { kind: 'CARD', amountMinor: 100_000 },
    ]);
    expect(card!.refundableMinor).toBe(0);
  });

  it('refuses a sale claiming more change than its cash', () => {
    expect(() => refundableTenders([{ kind: 'CARD', amountMinor: 50_000 }], 100, [])).toThrow(
      /only cash makes change/,
    );
  });
});

describe('allocateRefundTenders — Gate M2', () => {
  it('sends the money back the way it came', () => {
    const available = refundableTenders([{ kind: 'CARD', amountMinor: 100_000 }], 0, []);
    expect(allocateRefundTenders(40_000, available).tenders).toEqual([
      { kind: 'CARD', amountMinor: 40_000 },
    ]);
  });

  it('cannot pay a card sale in cash — there is no allocation to construct', () => {
    const cardOnly = refundableTenders([{ kind: 'CARD', amountMinor: 100_000 }], 0, []);
    const allocation = allocateRefundTenders(100_000, cardOnly);
    expect(allocation.tenders.map((t) => t.kind)).toEqual(['CARD']);
    expect(allocation.cashPayableMinor).toBe(0);
  });

  it('exhausts non-cash first on a split sale', () => {
    // Card 200.00 + cash 300.00. A 250.00 return reverses the card fully and takes 50.00 from
    // the drawer — the reversal a bank statement makes sense of, and it leaves cash where the
    // shift count expects it.
    const available = refundableTenders(
      [
        { kind: 'CARD', amountMinor: 20_000 },
        { kind: 'CASH', amountMinor: 30_000 },
      ],
      0,
      [],
    );
    expect(allocateRefundTenders(25_000, available).tenders).toEqual([
      { kind: 'CARD', amountMinor: 20_000 },
      { kind: 'CASH', amountMinor: 5_000 },
    ]);
  });

  it('rounds only the cash portion, and only at the end (M1-03)', () => {
    const available = refundableTenders([{ kind: 'CASH', amountMinor: 100_000 }], 0, []);
    const allocation = allocateRefundTenders(45_050, available);
    expect(allocation.tenders).toEqual([{ kind: 'CASH', amountMinor: 45_050 }]);
    expect(allocation.cashPayableMinor).toBe(45_100);
    expect(allocation.roundingAdjustmentMinor).toBe(50);
  });

  it('refuses to give back more than the tenders took', () => {
    const available = refundableTenders([{ kind: 'CASH', amountMinor: 10_000 }], 0, []);
    expect(() => allocateRefundTenders(20_000, available)).toThrow(
      /exceeds what the sale's tenders/,
    );
  });

  it('refuses a refund of nothing', () => {
    expect(() => allocateRefundTenders(0, [])).toThrow(/not a refund/);
  });
});

describe('assertRefundTendersAllowed — the same rules applied to someone else’s answer', () => {
  const available = refundableTenders(
    [
      { kind: 'CARD', amountMinor: 20_000 },
      { kind: 'CASH', amountMinor: 30_000 },
    ],
    0,
    [],
  );

  it('accepts a hand-split refund that respects both caps', () => {
    expect(() =>
      assertRefundTendersAllowed(
        25_000,
        [
          { kind: 'CARD', amountMinor: 10_000 },
          { kind: 'CASH', amountMinor: 15_000 },
        ],
        available,
      ),
    ).not.toThrow();
  });

  it('rejects a tender the sale never took — the till UI is not the only gate', () => {
    expect(() =>
      assertRefundTendersAllowed(10_000, [{ kind: 'WALLET', amountMinor: 10_000 }], available),
    ).toThrow(/the sale was not paid with it/);
  });

  it('rejects more than a kind can still return', () => {
    expect(() =>
      assertRefundTendersAllowed(30_000, [{ kind: 'CARD', amountMinor: 30_000 }], available),
    ).toThrow(/still refundable/);
  });

  it('rejects an allocation that does not settle the refund exactly', () => {
    expect(() =>
      assertRefundTendersAllowed(25_000, [{ kind: 'CASH', amountMinor: 20_000 }], available),
    ).toThrow(/settled in full/);
  });

  it('rejects a duplicated or empty tender line', () => {
    expect(() =>
      assertRefundTendersAllowed(
        20_000,
        [
          { kind: 'CASH', amountMinor: 10_000 },
          { kind: 'CASH', amountMinor: 10_000 },
        ],
        available,
      ),
    ).toThrow(/appears twice/);
    expect(() =>
      assertRefundTendersAllowed(
        20_000,
        [
          { kind: 'CASH', amountMinor: 20_000 },
          { kind: 'CARD', amountMinor: 0 },
        ],
        available,
      ),
    ).toThrow(/is zero/);
  });

  it('accepts what allocateRefundTenders produced, for any refund the sale can cover', () => {
    for (let amount = 100; amount <= 50_000; amount += 100) {
      const allocation = allocateRefundTenders(amount, available);
      expect(() => assertRefundTendersAllowed(amount, allocation.tenders, available)).not.toThrow();
    }
  });
});
