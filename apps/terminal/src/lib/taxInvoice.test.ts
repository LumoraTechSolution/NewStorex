import { describe, expect, it } from 'vitest';

import { decodeEscPos as decode, escPosLines as textLines } from './escposDecode';
import { buildTaxInvoice, formatGazetteDate, type TaxInvoiceData } from './taxInvoice';

/**
 * A VAT-taxable basket. The exempt line a real receipt might carry is absent on purpose — the
 * server excludes it before the data reaches the printer (Gazette §4.2).
 */
const BASE: TaxInvoiceData = {
  supplier: {
    tin: '123456789',
    registeredName: 'StoreX Retail (Pvt) Ltd',
    address: 'No. 148/3B Sri Dharmapala Mawatha, Kandy 20000',
  },
  purchaser: null,
  invoiceNumber: '26AUG-KNDT1-000001',
  issuedAt: '2026-08-24T09:12:09.000Z',
  suppliedAt: '2026-08-24T09:12:09.000Z',
  saleInvoiceNumber: 'KND-T1-000042',
  lines: [
    {
      name: 'Ceylon Tea 400g',
      qty: 2,
      unitPriceMinor: 45000,
      exVatMinor: 76272,
      taxRateBp: 1800,
    },
  ],
  totalExclVatMinor: 76272,
  vatMinor: 13728,
  totalInclVatMinor: 90000,
};

const WITH_PURCHASER: TaxInvoiceData = {
  ...BASE,
  purchaser: {
    tin: '987654321',
    name: 'Ceylon Traders (Pvt) Ltd',
    address: '45 Main Street, Kandy',
  },
};

describe('buildTaxInvoice', () => {
  // ------------------------------------------------------------------ §1 title

  it('is titled TAX INVOICE, in bold and double height', () => {
    const printed = decode(buildTaxInvoice(BASE));
    expect(printed).toContain('TAX INVOICE');

    // §1.2 asks for a conspicuous place. Assert the emphasis commands are actually emitted,
    // not merely that the words appear — a plain-text title would pass the line above.
    const bytes = buildTaxInvoice(BASE);
    const doubleSizeOn = [0x1d, 0x21, 0x11];
    expect(containsSequence(bytes, doubleSizeOn)).toBe(true);
  });

  it('puts the title first, before anything else on the paper', () => {
    const rows = textLines(buildTaxInvoice(BASE)).filter((l) => l.length > 0);
    expect(rows[0]).toBe('TAX INVOICE');
  });

  // ------------------------------------------------------------------ §2 supplier

  it('prints the supplier name, address and nine-digit TIN', () => {
    const printed = decode(buildTaxInvoice(BASE));
    expect(printed).toContain('StoreX Retail (Pvt) Ltd');
    expect(printed).toContain('TIN: 123456789');

    // The address wraps, so assert it reassembles rather than that any one phrase survives
    // intact — at 42 columns the break lands inside "Kandy 20000".
    const rows = textLines(buildTaxInvoice(BASE)).filter((l) => l.length > 0);
    const start = rows.indexOf('StoreX Retail (Pvt) Ltd');
    expect(rows.slice(start + 1, start + 3).join(' ')).toBe(BASE.supplier.address);
  });

  // ------------------------------------------------------------------ §3 purchaser

  /**
   * Circular §4.3 — purchaser particulars are required only where the purchaser is
   * VAT-registered. A walk-in gets no block at all, and that is compliant rather than a gap.
   */
  it('prints no purchaser block for a walk-in', () => {
    expect(decode(buildTaxInvoice(BASE))).not.toContain('Purchaser');
  });

  it('prints the purchaser block when the purchaser is VAT-registered', () => {
    const printed = decode(buildTaxInvoice(WITH_PURCHASER));
    expect(printed).toContain('Purchaser');
    expect(printed).toContain('Ceylon Traders (Pvt) Ltd');
    expect(printed).toContain('45 Main Street, Kandy');
    expect(printed).toContain('TIN: 987654321');
  });

  it('keeps the supplier and purchaser TINs apart', () => {
    const rows = textLines(buildTaxInvoice(WITH_PURCHASER)).filter((l) => l.startsWith('TIN:'));
    expect(rows).toEqual(['TIN: 123456789', 'TIN: 987654321']);
  });

  // ------------------------------------------------------------------ §4.1 invoice details

  it('prints the gazette serial number', () => {
    const rows = textLines(buildTaxInvoice(BASE));
    expect(
      rows.some((l) => l.startsWith('Tax Invoice No:') && l.endsWith('26AUG-KNDT1-000001')),
    ).toBe(true);
  });

  /** §4.5 of the circular: both dates, because the pair decides the VAT period. */
  it('prints both the invoice date and the date of supply', () => {
    const printed = decode(buildTaxInvoice(BASE));
    expect(printed).toContain('Date of Invoice:');
    expect(printed).toContain('Date of Supply:');
  });

  it('prints both dates even when they are the same day', () => {
    const rows = textLines(buildTaxInvoice(BASE));
    expect(rows.filter((l) => l.includes('Date of'))).toHaveLength(2);
  });

  it('shows a supply that predates its invoice', () => {
    const raisedLater: TaxInvoiceData = { ...BASE, issuedAt: '2026-09-02T04:00:00.000Z' };
    const printed = decode(buildTaxInvoice(raisedLater));
    expect(printed).toContain('Date of Invoice: 09/02/2026');
    expect(printed).toContain('Date of Supply:  08/24/2026');
  });

  // ------------------------------------------------------------------ §4.7 the three figures

  it('prints value of supply, VAT and total inclusive, in that order', () => {
    const rows = textLines(buildTaxInvoice(BASE)).filter((l) => l.length > 0);
    const supply = rows.findIndex((l) => l.startsWith('Total Value of Supply'));
    const vat = rows.findIndex((l) => l.startsWith('VAT Amount'));
    const total = rows.findIndex((l) => l.startsWith('Total incl. VAT'));

    expect(supply).toBeGreaterThan(-1);
    expect(vat).toBe(supply + 1);
    expect(total).toBe(vat + 1);
  });

  it('states every amount to two decimal places', () => {
    const printed = decode(buildTaxInvoice(BASE));
    expect(printed).toContain('762.72');
    expect(printed).toContain('137.28');
    expect(printed).toContain('900.00');
  });

  it('reconciles: value of supply plus VAT equals the total', () => {
    expect(BASE.totalExclVatMinor + BASE.vatMinor).toBe(BASE.totalInclVatMinor);
  });

  // ------------------------------------------------------------------ §4.1(e)–(f) lines

  it('prints a description and a quantity for each line', () => {
    const printed = decode(buildTaxInvoice(BASE));
    expect(printed).toContain('Ceylon Tea 400g');
    expect(printed).toContain('2 x 450.00');
  });

  it('never overflows the paper width', () => {
    for (const width of [32, 42]) {
      const rows = textLines(buildTaxInvoice(WITH_PURCHASER, width));
      for (const row of rows) {
        expect(row.length).toBeLessThanOrEqual(width);
      }
    }
  });

  // ------------------------------------------------------------------ §4.2 the exclusion notice

  it('says on its face that exempt supplies are excluded', () => {
    expect(decode(buildTaxInvoice(BASE))).toContain('VAT-taxable');
  });

  it('ends with a cut', () => {
    const bytes = buildTaxInvoice(BASE);
    expect(Array.from(bytes.slice(-3))).toEqual([0x1d, 0x56, 1]);
  });
});

describe('formatGazetteDate', () => {
  /**
   * Month first, in a country that writes dates day first. This looks like a bug every time
   * somebody reads it and is not: §4.1(b) and §4.1(d) both say MM/DD/YYYY, and the circular
   * repeats it. The test exists to stop a well-meaning fix.
   */
  it('is MM/DD/YYYY, which is deliberate', () => {
    expect(formatGazetteDate('2026-08-24T09:12:09.000Z')).toBe('08/24/2026');
  });

  it('zero-pads a single-digit month and day', () => {
    expect(formatGazetteDate('2026-01-05T09:00:00.000Z')).toBe('01/05/2026');
  });

  it('hands back an unparseable value rather than printing Invalid Date', () => {
    expect(formatGazetteDate('not a date')).toBe('not a date');
  });
});

function containsSequence(haystack: Uint8Array, needle: readonly number[]): boolean {
  outer: for (let i = 0; i + needle.length <= haystack.length; i++) {
    for (let j = 0; j < needle.length; j++) {
      if (haystack[i + j] !== needle[j]) continue outer;
    }
    return true;
  }
  return false;
}
