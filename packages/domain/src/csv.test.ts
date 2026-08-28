import { describe, expect, it } from 'vitest';

import { parseCsv, parseProductCsv } from './csv';

/**
 * Reading a product list out of a spreadsheet (M3-03).
 *
 * <p>The tests worth having here are the ones about files a real shop produces: a name with a comma
 * in it, a BOM from Excel, a price with a thousands separator, a blank row someone left at the
 * bottom. The happy path is one test; the rest of this file is the reasons a shopkeeper's first
 * attempt fails.
 */
describe('parseCsv — the file format', () => {
  it('reads a quoted field containing the separator', () => {
    const rows = parseCsv('sku,name\nRICE-5,"Samba Rice, 5kg"');
    expect(rows[1]!.cells).toEqual(['RICE-5', 'Samba Rice, 5kg']);
  });

  it('reads a doubled quote as one quote, and a newline inside a field', () => {
    const rows = parseCsv('a,b\n1,"say ""hi""\nagain"');
    expect(rows[1]!.cells).toEqual(['1', 'say "hi"\nagain']);
  });

  /** Excel's "CSV UTF-8" writes one. Left in, it becomes part of the first header name. */
  it('strips the byte order mark Excel writes', () => {
    const rows = parseCsv('﻿sku,name\nA,B');
    expect(rows[0]!.cells[0]).toBe('sku');
  });

  it('handles CRLF and does not invent a row for a trailing newline', () => {
    const rows = parseCsv('sku,name\r\nA,B\r\n');
    expect(rows).toHaveLength(2);
  });

  /** Line numbers are what the preview points at, so they have to match the spreadsheet. */
  it('numbers lines as the spreadsheet does, header first', () => {
    const rows = parseCsv('sku\nA\nB');
    expect(rows.map((r) => r.line)).toEqual([1, 2, 3]);
  });
});

describe('parseProductCsv — turning cells into products', () => {
  const HEADER = 'sku,name,price,vat,category,barcodes';

  it('reads a well-formed file', () => {
    const parsed = parseProductCsv(
      `${HEADER}\nTEA-400,Ceylon Tea 400g,450.00,18,Beverages,4791234567890`,
    );

    expect(parsed.problems).toEqual([]);
    expect(parsed.rows).toEqual([
      {
        line: 2,
        sku: 'TEA-400',
        name: 'Ceylon Tea 400g',
        priceMinor: 45_000,
        taxMode: 'INCLUSIVE',
        taxRateBp: 1800,
        category: 'Beverages',
        barcodes: ['4791234567890'],
      },
    ]);
  });

  /**
   * The reason this parser is in the money package at all.
   *
   * `parseFloat('4.29') * 100` is 428.99999999999994, so a naive import prices this at 428 or 429
   * depending on how it rounds — on every line of a four-hundred-line file.
   */
  it('converts a price with cents exactly', () => {
    const parsed = parseProductCsv(`${HEADER}\nA,Jam,4.29,0,,`);
    expect(parsed.rows[0]!.priceMinor).toBe(429);
  });

  it('accepts a thousands separator, because spreadsheets export them', () => {
    const parsed = parseProductCsv(`${HEADER}\nA,Rice,"2,850.50",18,,`);
    expect(parsed.rows[0]!.priceMinor).toBe(285_050);
  });

  /** Basis points are a percentage with two decimals — the same shift a price gets. */
  it('reads a fractional VAT rate as basis points', () => {
    const parsed = parseProductCsv(`${HEADER}\nA,Thing,100,2.5,,`);
    expect(parsed.rows[0]!.taxRateBp).toBe(250);
  });

  it('splits several barcodes out of one cell', () => {
    const parsed = parseProductCsv(`${HEADER}\nA,Milk Powder,1390,18,,4791234567937|8901234567895`);
    expect(parsed.rows[0]!.barcodes).toEqual(['4791234567937', '8901234567895']);
  });

  it('treats an empty category cell as no category rather than a blank name', () => {
    const parsed = parseProductCsv(`${HEADER}\nA,Nails,5,0,,`);
    expect(parsed.rows[0]!.category).toBeNull();
  });

  it('normalises header names across case, spaces, underscores and hyphens', () => {
    const parsed = parseProductCsv('SKU,Product Name,Unit_Price\nA,Thing,10');
    expect(parsed.problems).toEqual([]);
    expect(parsed.rows[0]!.name).toBe('Thing');
  });

  it('ignores a blank row left at the bottom of a spreadsheet', () => {
    const parsed = parseProductCsv(`${HEADER}\nA,Thing,10,0,,\n,,,,,\n`);
    expect(parsed.rows).toHaveLength(1);
    expect(parsed.problems).toEqual([]);
  });

  // ------------------------------------------------------------------ what it refuses

  it('refuses a file with no price column and says what it found', () => {
    const parsed = parseProductCsv('sku,name\nA,Thing');
    expect(parsed.rows).toEqual([]);
    expect(parsed.problems[0]!.message).toContain('price');
    expect(parsed.problems[0]!.message).toContain('Found: sku, name');
  });

  /**
   * Every broken row is reported, not just the first.
   *
   * A shopkeeper with forty bad rows needs to see forty; stopping at the first turns one fix into
   * forty round trips through a spreadsheet.
   */
  it('collects every broken row rather than stopping at the first', () => {
    const parsed = parseProductCsv(
      `${HEADER}\n,No Code,10,0,,\nB,,10,0,,\nC,Bad Price,abc,0,,\nD,Fine,10,0,,`,
    );

    expect(parsed.problems.map((p) => p.line)).toEqual([2, 3, 4]);
    // The one good row still parses, so the preview can show what would happen to it.
    expect(parsed.rows.map((r) => r.sku)).toEqual(['D']);
  });

  it('names both lines when a product code appears twice', () => {
    const parsed = parseProductCsv(`${HEADER}\nTEA,First,10,0,,\nTEA,Second,20,0,,`);
    expect(parsed.problems).toHaveLength(1);
    expect(parsed.problems[0]!.line).toBe(3);
    expect(parsed.problems[0]!.message).toContain('also on line 2');
  });

  it('names both lines when a barcode appears on two products', () => {
    const parsed = parseProductCsv(`${HEADER}\nA,First,10,0,,999\nB,Second,20,0,,999`);
    expect(parsed.problems[0]!.message).toContain('also on line 2');
  });

  it('refuses a VAT rate written in basis points by mistake', () => {
    const parsed = parseProductCsv(`${HEADER}\nA,Thing,10,1800,,`);
    expect(parsed.problems[0]!.message).toContain('typo');
  });

  it('refuses a negative price', () => {
    const parsed = parseProductCsv(`${HEADER}\nA,Thing,-10,0,,`);
    expect(parsed.problems[0]!.message).toContain('negative');
  });

  // ---------------------------------------------------------------------- warnings

  /**
   * The warning this whole mechanism exists for.
   *
   * A file with no VAT column is perfectly valid and zero-rates the entire catalogue. Nothing is
   * wrong with any row, so without a warning the preview shows 400 clean products and the shop
   * finds out at the next VAT return.
   */
  it('warns loudly when there is no VAT column', () => {
    const parsed = parseProductCsv('sku,name,price\nA,Thing,10');
    expect(parsed.problems).toEqual([]);
    expect(parsed.rows[0]!.taxRateBp).toBe(0);
    expect(parsed.warnings.join(' ')).toContain('zero-rated');
  });

  it('warns when there is no barcode column, because nothing will scan', () => {
    const parsed = parseProductCsv('sku,name,price,vat\nA,Thing,10,18');
    expect(parsed.warnings.join(' ')).toContain('not scan');
  });

  /** An empty VAT *cell* is zero-rated on purpose — bread and rice really are. */
  it('does not warn about an empty VAT cell when the column exists', () => {
    const parsed = parseProductCsv(`${HEADER}\nA,Bread,25,,,`);
    expect(parsed.rows[0]!.taxRateBp).toBe(0);
    expect(parsed.warnings.join(' ')).not.toContain('zero-rated');
  });

  it('reports an empty file rather than returning nothing quietly', () => {
    expect(parseProductCsv('').problems[0]!.message).toContain('empty');
  });
});
