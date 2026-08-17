import { describe, expect, it } from 'vitest';

import { buildReceipt, buildReceiptWithDrawerKick, type ReceiptData } from './receipt';

/**
 * Strips ESC/POS *command* bytes rather than merely non-printable ones — several commands
 * (`ESC E`, `ESC a`, `ESC d`, `GS !`, `GS V`) have a parameter byte that lands inside the
 * printable ASCII range (e.g. `ESC E 0x01` includes 0x45, `'E'`), so filtering by byte value
 * alone would splice stray letters into the decoded text. Each command's exact length is
 * skipped instead, leaving only the bytes `escpos.ts` meant as receipt content.
 */
function decode(bytes: Uint8Array): string {
  const b = Array.from(bytes);
  let out = '';
  let i = 0;
  while (i < b.length) {
    const byte = b[i];
    if (byte === 0x1b) {
      // ESC @ (2) | ESC a n / ESC E n / ESC d n (3) | ESC p m t1 t2 (5)
      const cmd = b[i + 1];
      i += cmd === 0x40 ? 2 : cmd === 0x70 ? 5 : 3;
      continue;
    }
    if (byte === 0x1d) {
      // GS ! n | GS V m (3)
      i += 3;
      continue;
    }
    if (byte === 0x0a) {
      out += '\n';
      i++;
      continue;
    }
    if (byte !== undefined && byte >= 0x20 && byte <= 0x7e) {
      out += String.fromCharCode(byte);
    }
    i++;
  }
  return out;
}

/** Text lines only — the printable content a cashier or a test can actually read. */
function textLines(bytes: Uint8Array): string[] {
  return decode(bytes).split('\n');
}

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
  totalMinor: 375000,
  tenders: [{ kind: 'CASH', amountMinor: 400000 }],
  roundingAdjustmentMinor: 0,
  changeMinor: 25000,
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
    const width = 32;
    const lines = textLines(buildReceipt(BASE, width)).filter((l) => l.length > 0);
    for (const l of lines) {
      expect(l.length).toBeLessThanOrEqual(width);
    }
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
  it('appends the drawer pulse after the receipt, over the same buffer', () => {
    const receiptOnly = buildReceipt(BASE);
    const withDrawer = buildReceiptWithDrawerKick(BASE);

    expect(withDrawer.length).toBe(receiptOnly.length + 5); // ESC p m t1 t2
    expect(Array.from(withDrawer.slice(0, receiptOnly.length))).toEqual(Array.from(receiptOnly));
    expect(Array.from(withDrawer.slice(-5))).toEqual([0x1b, 0x70, 0, 25, 250]);
  });
});
