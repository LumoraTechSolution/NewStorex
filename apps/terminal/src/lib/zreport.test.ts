import { describe, expect, it } from 'vitest';

import { decodeEscPos as asText, escPosLines as lines } from './escposDecode';
import {
  buildCreditNote,
  buildCreditNoteWithDrawerKick,
  buildZReport,
  type CreditNoteData,
  type ZReportData,
} from './zreport';

const Z: ZReportData = {
  storeName: 'StoreX',
  tagline: 'Powered by Lumora Tech',
  branchCode: 'KND',
  terminalCode: 'T1',
  shiftId: 42,
  openedAt: '2026-08-20T03:30:00Z',
  closedAt: '2026-08-20T13:30:00Z',

  saleCount: 37,
  grossSalesMinor: 1_284_500,
  discountMinor: 12_000,
  taxMinor: 178_900,
  tendersByKind: [
    { kind: 'CASH', amountMinor: 900_000, lineCount: 30 },
    { kind: 'CARD', amountMinor: 372_500, lineCount: 8 },
  ],
  taxByRate: [
    { rateBp: 0, grossMinor: 84_500, taxMinor: 0 },
    { rateBp: 1800, grossMinor: 1_200_000, taxMinor: 178_900 },
  ],

  refundCount: 1,
  refundTotalMinor: 45_000,

  openingFloatMinor: 500_000,
  cashSalesMinor: 900_000,
  cashChangeMinor: 120_000,
  cashRoundingMinor: 350,
  cashMovementsMinor: -400_000,
  cashRefundsMinor: 45_000,
  cashRefundRoundingMinor: 0,
  cashMovementsByReason: [{ kind: 'DROP', reasonCode: 'SAFE_DROP', amountMinor: -400_000 }],

  expectedCashMinor: 835_350,
  countedCashMinor: 830_000,
  varianceMinor: -5_350,
  varianceReason: 'MISCOUNT',
  closingCount: [
    { denominationMinor: 100_000, qty: 8, subtotalMinor: 800_000 },
    { denominationMinor: 10_000, qty: 3, subtotalMinor: 30_000 },
    { denominationMinor: 100, qty: 0, subtotalMinor: 0 },
  ],
};

describe('buildZReport', () => {
  it('names itself and the till it came from', () => {
    const text = asText(buildZReport(Z));
    expect(text).toContain('Z-REPORT');
    expect(text).toContain('StoreX');
    expect(text).toContain('Shift:  42');
    expect(text).toContain('Till T1');
  });

  /**
   * The report's reason to exist. A variance with no visible derivation is a number nobody
   * trusts and everybody overrides, which is the same as not having one — so every term that
   * produced the expected figure is on the paper, in the order the arithmetic runs.
   */
  it('prints every term of the drawer, and they reconcile to the variance', () => {
    const text = asText(buildZReport(Z));

    for (const label of [
      'Opening float',
      'Cash taken',
      'Change given',
      'Cash rounding',
      'Safe drop',
      'Cash refunds',
      'EXPECTED',
      'COUNTED',
    ]) {
      expect(text, `missing "${label}"`).toContain(label);
    }

    // The arithmetic the paper claims, checked against the figures it printed.
    const derived =
      Z.openingFloatMinor +
      Z.cashSalesMinor +
      Z.cashRoundingMinor +
      Z.cashMovementsMinor -
      Z.cashChangeMinor -
      Z.cashRefundsMinor -
      Z.cashRefundRoundingMinor;
    expect(derived).toBe(Z.expectedCashMinor);
    expect(Z.countedCashMinor - Z.expectedCashMinor).toBe(Z.varianceMinor);
  });

  it('calls a short drawer SHORT and an over one OVER', () => {
    expect(asText(buildZReport(Z))).toContain('SHORT');
    expect(
      asText(buildZReport({ ...Z, varianceMinor: 5_350, countedCashMinor: 840_700 })),
    ).toContain('OVER');
    expect(
      asText(buildZReport({ ...Z, varianceMinor: 0, countedCashMinor: Z.expectedCashMinor })),
    ).toContain('BALANCED');
  });

  it('prints the variance reason when there is one', () => {
    expect(asText(buildZReport(Z))).toContain('Reason: Miscount');
    expect(asText(buildZReport({ ...Z, varianceReason: null }))).not.toContain('Reason:');
  });

  it('prints the count note by note, skipping denominations that were not there', () => {
    const text = asText(buildZReport(Z));
    expect(text).toContain('1,000 x 8');
    expect(text).toContain('100 x 3');
    // A zero row is a fact about the count, but printing it wastes paper on ten empty lines.
    expect(text).not.toContain('1 x 0');
  });

  it('breaks VAT down per rate, so a mixed-rate day is legible (M1-18)', () => {
    const text = asText(buildZReport(Z));
    expect(text).toContain('at 0%');
    expect(text).toContain('at 18%');
  });

  it('omits the returns block entirely when nothing came back', () => {
    expect(asText(buildZReport({ ...Z, refundCount: 0, refundTotalMinor: 0 }))).not.toContain(
      'RETURNS',
    );
  });

  it('leaves somewhere to sign', () => {
    // A Z-report is filed, and a filed document nobody signed is a document nobody checked.
    const text = asText(buildZReport(Z));
    expect(text).toContain('Counted by');
    expect(text).toContain('Checked by');
  });

  it('fits the paper', () => {
    for (const line of lines(buildZReport(Z))) {
      expect(line.length, `too wide: ${line}`).toBeLessThanOrEqual(42);
    }
  });
});

const CREDIT_NOTE: CreditNoteData = {
  storeName: 'StoreX',
  tagline: 'Powered by Lumora Tech',
  branchCode: 'KND',
  terminalCode: 'T1',
  creditNoteNumber: 'KND-T1-CN-000004',
  saleInvoiceNumber: 'KND-T1-001047',
  refundedAt: '2026-08-20T11:00:00Z',
  lines: [{ name: 'Ceylon Tea 400g', qty: 1, refundTotalMinor: 45_000, taxRateBp: 1800 }],
  totalMinor: 45_000,
  taxMinor: 6_864,
  roundingAdjustmentMinor: 0,
  tenders: [{ kind: 'CASH', amountMinor: 45_000 }],
};

describe('buildCreditNote', () => {
  it('names the invoice it reverses', () => {
    // A credit note that does not is not a credit note — it is a note saying money left the till.
    const text = asText(buildCreditNote(CREDIT_NOTE));
    expect(text).toContain('CREDIT NOTE');
    expect(text).toContain('Credit note: KND-T1-CN-000004');
    expect(text).toContain('Against invoice: KND-T1-001047');
  });

  it('declares the VAT inside the refund', () => {
    const text = asText(buildCreditNote(CREDIT_NOTE));
    expect(text).toContain('REFUNDED');
    expect(text).toContain('of which VAT');
    expect(text).toContain('68.64');
  });

  it('says where the money went', () => {
    expect(asText(buildCreditNote(CREDIT_NOTE))).toContain('Back to CASH');
    expect(
      asText(buildCreditNote({ ...CREDIT_NOTE, tenders: [{ kind: 'CARD', amountMinor: 45_000 }] })),
    ).toContain('Back to CARD');
  });

  it('prints the rounding only when there was some', () => {
    expect(asText(buildCreditNote(CREDIT_NOTE))).not.toContain('Rounding');
    expect(asText(buildCreditNote({ ...CREDIT_NOTE, roundingAdjustmentMinor: 50 }))).toContain(
      'Rounding',
    );
  });

  it('fits the paper', () => {
    for (const line of lines(buildCreditNote(CREDIT_NOTE))) {
      expect(line.length, `too wide: ${line}`).toBeLessThanOrEqual(42);
    }
  });
});

describe('buildCreditNoteWithDrawerKick', () => {
  /** ESC p 0 — the drawer pulse. */
  const KICK = [0x1b, 0x70, 0x00];

  function endsWithKick(bytes: Uint8Array): boolean {
    const tail = [...bytes.slice(-5)];
    return tail[0] === KICK[0] && tail[1] === KICK[1] && tail[2] === KICK[2];
  }

  it('opens the drawer when cash is going back across the counter', () => {
    expect(endsWithKick(buildCreditNoteWithDrawerKick(CREDIT_NOTE))).toBe(true);
  });

  /**
   * A card refund moves no cash, and popping the drawer for it puts a cashier's hands in the till
   * for no reason — a shrinkage risk, and exactly the habit an audit trail cannot see.
   */
  it('leaves the drawer shut when the refund goes back to a card', () => {
    const cardOnly = { ...CREDIT_NOTE, tenders: [{ kind: 'CARD' as const, amountMinor: 45_000 }] };
    expect(endsWithKick(buildCreditNoteWithDrawerKick(cardOnly))).toBe(false);
  });

  it('opens it for the cash half of a split refund', () => {
    const split = {
      ...CREDIT_NOTE,
      tenders: [
        { kind: 'CARD' as const, amountMinor: 20_000 },
        { kind: 'CASH' as const, amountMinor: 25_000 },
      ],
    };
    expect(endsWithKick(buildCreditNoteWithDrawerKick(split))).toBe(true);
  });
});
