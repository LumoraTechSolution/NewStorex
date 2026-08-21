import { describe, expect, it } from 'vitest';

import { decodeEscPos as decode, escPosLines as textLines } from './escposDecode';
import { buildReceipt, buildReceiptWithDrawerKick, type ReceiptData } from './receipt';

const BASE: ReceiptData = {
  storeName: 'StoreX',
  tagline: 'Powered by Lumora Tech',
  branchName: 'Kandy Main',
  branchCode: 'KND',
  terminalCode: 'T1',
  invoiceNumber: 'KND-T1-000042',
  soldAt: '2026-08-17T09:12:09.000Z',
  lines: [
    { name: 'Ceylon Tea 400g', qty: 2, unitPriceMinor: 45000, lineTotalMinor: 90000 },
    { name: 'Samba Rice 5kg', qty: 1, unitPriceMinor: 285000, lineTotalMinor: 285000 },
  ],
  subtotalMinor: 375000,
  discountMinor: 0,
  taxMinor: 57102,
  taxRateBp: 1800,
  taxMode: 'INCLUSIVE',
  taxBreakdown: [
    { mode: 'INCLUSIVE', rateBp: 1800, grossMinor: 375000, exVatMinor: 317898, taxMinor: 57102 },
  ],
  totalMinor: 375000,
  tenders: [{ kind: 'CASH', amountMinor: 400000 }],
  roundingAdjustmentMinor: 0,
  changeMinor: 25000,
};

/**
 * The basket M1-18 exists for: bread at 0% next to arrack at 18%, which the till refused
 * outright until per-line rates landed. Figures come straight from `cartTotals`.
 */
const MIXED: ReceiptData = {
  ...BASE,
  lines: [
    { name: 'Bread 450g', qty: 2, unitPriceMinor: 25000, lineTotalMinor: 50000 },
    { name: 'Arrack 750ml', qty: 1, unitPriceMinor: 450000, lineTotalMinor: 450000 },
  ],
  subtotalMinor: 500000,
  taxMinor: 68644,
  totalMinor: 500000,
  taxBreakdown: [
    { mode: 'INCLUSIVE', rateBp: 0, grossMinor: 50000, exVatMinor: 50000, taxMinor: 0 },
    { mode: 'INCLUSIVE', rateBp: 1800, grossMinor: 450000, exVatMinor: 381356, taxMinor: 68644 },
  ],
  tenders: [{ kind: 'CASH', amountMinor: 500000 }],
  changeMinor: 0,
};

describe('buildReceipt', () => {
  it('starts with the ESC/POS initialise command', () => {
    const bytes = buildReceipt(BASE);
    expect(Array.from(bytes.slice(0, 2))).toEqual([0x1b, 0x40]);
  });

  it('ends with a partial cut', () => {
    const bytes = buildReceipt(BASE);
    // The cut command is GS V 1 — assert it appears as the tail of the buffer.
    const tail = Array.from(bytes.slice(-3));
    expect(tail).toEqual([0x1d, 0x56, 1]);
  });

  it('prints the store identity and branch/terminal/invoice header', () => {
    const printed = decode(buildReceipt(BASE));
    expect(printed).toContain('StoreX');
    expect(printed).toContain('Powered by Lumora Tech');
    expect(printed).toContain('Kandy Main');
    expect(printed).toContain('Till T1');
    expect(printed).toContain('Invoice: KND-T1-000042');
  });

  it('renders every line item with its quantity, unit price and line total', () => {
    const printed = decode(buildReceipt(BASE));
    expect(printed).toContain('Ceylon Tea 400g');
    expect(printed).toContain('2 x 450.00');
    expect(printed).toContain('900.00');
    expect(printed).toContain('Samba Rice 5kg');
    expect(printed).toContain('2,850.00');
  });

  it('omits the discount line when there is no discount, and shows it when there is one', () => {
    expect(decode(buildReceipt(BASE))).not.toContain('Discount');

    const discounted: ReceiptData = { ...BASE, discountMinor: 5000, totalMinor: 370000 };
    expect(decode(buildReceipt(discounted))).toContain('-50.00');
  });

  it('prints the tax label with mode and rate, and the total', () => {
    const printed = decode(buildReceipt(BASE));
    expect(printed).toContain('VAT 18% (incl.)');
    expect(printed).toContain('TOTAL');
    expect(printed).toContain('3,750.00');
  });

  it('states the net excluding VAT rather than leaving it to be inferred', () => {
    // A tax invoice has to show net, tax and total as three separate figures. Under an
    // inclusive regime the net appears on nothing else, so if the receipt omits it the
    // customer has no document that states it at all.
    const printed = decode(buildReceipt(BASE));
    expect(printed).toContain('Net (excl. VAT)');
    expect(printed).toContain('3,178.98');
  });

  it('keeps a single-rate sale to two lines rather than printing a one-row table', () => {
    const printed = decode(buildReceipt(BASE));
    expect(printed).not.toContain('VAT SUMMARY');
  });

  it('lists every tender line by kind', () => {
    const split: ReceiptData = {
      ...BASE,
      tenders: [
        { kind: 'CARD', amountMinor: 300000 },
        { kind: 'CASH', amountMinor: 100000 },
      ],
    };
    const printed = decode(buildReceipt(split));
    expect(printed).toContain('CARD');
    expect(printed).toContain('CASH');
  });

  it('shows change only when it is owed', () => {
    expect(decode(buildReceipt(BASE))).toContain('Change');
    expect(decode(buildReceipt({ ...BASE, changeMinor: 0 }))).not.toContain('Change');
  });

  it('shows the rounding adjustment, signed, only when it is non-zero', () => {
    expect(decode(buildReceipt(BASE))).not.toContain('Rounding');

    const roundedUp: ReceiptData = { ...BASE, roundingAdjustmentMinor: 50 };
    expect(decode(buildReceipt(roundedUp))).toContain('Rounding');
    expect(decode(buildReceipt(roundedUp))).toContain('+0.50');

    const roundedDown: ReceiptData = { ...BASE, roundingAdjustmentMinor: -50 };
    expect(decode(buildReceipt(roundedDown))).toContain('-0.50');
  });

  it('formats the sale time as the shop reads it, not raw ISO', () => {
    const printed = decode(buildReceipt(BASE));
    expect(printed).not.toContain('2026-08-17T09:12:09.000Z');
    expect(printed).toMatch(/17 Aug 2026/);
  });

  it('never lets a text line exceed the configured width', () => {
    for (const data of [BASE, MIXED]) {
      for (const width of [32, 42]) {
        const lines = textLines(buildReceipt(data, width)).filter((l) => l.length > 0);
        for (const l of lines) {
          expect(l.length).toBeLessThanOrEqual(width);
        }
      }
    }
  });

  it('keeps the VAT table inside a 58mm roll by dropping the redundant Gross column', () => {
    // Gross is Net + VAT, both of which are in the row — the only column a reader can
    // reconstruct, so the only one that may go when the paper cannot hold four.
    const narrow = decode(buildReceipt(MIXED, 32));
    expect(narrow).toContain('VAT SUMMARY');
    expect(narrow).not.toContain('Gross');
    expect(decode(buildReceipt(MIXED, 42))).toContain('Gross');
  });

  it('truncates an overlong product name instead of breaking the layout', () => {
    const longName = 'X'.repeat(80);
    const withLongName: ReceiptData = {
      ...BASE,
      lines: [{ name: longName, qty: 1, unitPriceMinor: 100, lineTotalMinor: 100 }],
    };
    const lines = textLines(buildReceipt(withLongName, 42)).filter((l) => l.length > 0);
    for (const l of lines) {
      expect(l.length).toBeLessThanOrEqual(42);
    }
  });
});

describe('buildReceiptWithDrawerKick', () => {
  it('prints a VAT summary when the sale mixes rates', () => {
    const printed = decode(buildReceipt(MIXED));
    expect(printed).toContain('VAT SUMMARY');
    // The rate is printed even at zero: "0%" says the line was considered and found
    // exempt, where a blank says nothing and looks identical to an omission.
    expect(printed).toContain('0%');
    expect(printed).toContain('18%');
    expect(printed).toContain('686.44');
    expect(printed).toContain('3,813.56');
  });

  it('gives the VAT summary a row per rate that adds up to the sale', () => {
    const rows = textLines(buildReceipt(MIXED));
    const at = rows.findIndex((l) => l.includes('VAT SUMMARY'));
    expect(at).toBeGreaterThan(-1);

    // Header, one row per rate, a rule, then the totals.
    expect(rows[at + 1]).toContain('Rate');
    expect(rows[at + 2]!.trimStart().startsWith('0%')).toBe(true);
    expect(rows[at + 3]!.trimStart().startsWith('18%')).toBe(true);
    expect(rows[at + 4]).toMatch(/^\s+-+/);

    const totals = rows[at + 5]!;
    expect(totals).toContain('4,313.56'); // net: 500.00 exempt + 3,813.56 standard
    expect(totals).toContain('686.44'); // and the sale's tax, unchanged
    expect(totals).toContain('5,000.00'); // gross, which is the TOTAL below
  });

  it('right-aligns the VAT table on the same edge as the total', () => {
    // The figures are read down the column. If the table's right edge floated free of the
    // Subtotal and TOTAL above and below it, none of them would line up.
    const rows = textLines(buildReceipt(MIXED, 42)).filter((l) => l.length > 0);
    const at = rows.findIndex((l) => l.includes('VAT SUMMARY'));
    for (const row of rows.slice(at + 1, at + 6)) {
      expect(row.length).toBe(42);
    }
  });

  it('appends the drawer pulse after the receipt, over the same buffer', () => {
    const receiptOnly = buildReceipt(BASE);
    const withDrawer = buildReceiptWithDrawerKick(BASE);

    expect(withDrawer.length).toBe(receiptOnly.length + 5); // ESC p m t1 t2
    expect(Array.from(withDrawer.slice(0, receiptOnly.length))).toEqual(Array.from(receiptOnly));
    expect(Array.from(withDrawer.slice(-5))).toEqual([0x1b, 0x70, 0, 25, 250]);
  });
});
