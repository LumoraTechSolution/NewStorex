import { minor, parseAmountToMinor, type Minor } from './money';

/**
 * Reading a product list out of a spreadsheet (M3-03).
 *
 * <h2>Why this is in the domain package and not in the backend</h2>
 *
 * Because a CSV holds prices as text — `285.00`, `1,250.75` — and turning that into minor units is
 * money math. §A says money math lives in exactly one place. A Java parser doing the same decimal
 * shift would be the second implementation, and the day the two disagreed about `0.005` the
 * evidence would be a catalogue that priced correctly on screen and wrongly on a receipt.
 *
 * So the split is: **text becomes rows here**, and rows are checked against the catalogue on the
 * backend, which is the only side that knows what SKUs and barcodes already exist. Neither half can
 * do the other's job, and only one of them touches money.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * It does not decide whether a row is *acceptable* — only whether it is *readable*. "This SKU is
 * already used", "that barcode belongs to another product", "no such category" all need the
 * database. A row that parses cleanly here can still be refused, and the preview is where the
 * shopkeeper sees that.
 */

/** A cell reference a person can find in their spreadsheet: 1-based, counting the header. */
export interface CsvProblem {
  /** The line in the file, as the spreadsheet numbers it. Header is line 1. */
  readonly line: number;
  readonly message: string;
}

/** One parsed product row, in the shape the import endpoint takes. */
export interface ImportRow {
  readonly line: number;
  readonly sku: string;
  readonly name: string;
  readonly priceMinor: Minor;
  readonly taxMode: 'INCLUSIVE' | 'EXCLUSIVE';
  /** Basis points. 18% is 1800 — the same two-decimal shift a price gets. */
  readonly taxRateBp: number;
  /** Null means "no category", which is a legitimate state, not a missing value. */
  readonly category: string | null;
  readonly barcodes: readonly string[];
}

export interface ParsedImport {
  readonly rows: readonly ImportRow[];
  /** Rows that could not be read. A file with any of these is not importable. */
  readonly problems: readonly CsvProblem[];
  /**
   * Things that are not errors but change what the import will do, and that a person has to see
   * before confirming. A missing `vat` column zero-rating an entire catalogue is the example this
   * exists for: perfectly valid, quietly catastrophic.
   */
  readonly warnings: readonly string[];
}

// ------------------------------------------------------------------ the file format

/**
 * Header names, normalised.
 *
 * <p>Normalisation folds case and treats spaces, underscores and hyphens as the same thing, because
 * `Unit Price`, `unit_price` and `unit-price` all come out of real spreadsheets and refusing two of
 * them is a support call. The aliases beyond that are kept short on purpose — every extra one is a
 * guess about what a column means, and guessing wrong about which column is the price is the worst
 * outcome this feature has.
 */
const COLUMNS = {
  sku: ['sku', 'code', 'productcode', 'itemcode'],
  name: ['name', 'productname', 'description'],
  price: ['price', 'unitprice', 'sellingprice'],
  vat: ['vat', 'vatpercent', 'taxrate', 'vatrate'],
  taxMode: ['taxmode', 'priceincludesvat'],
  category: ['category', 'group', 'department'],
  barcodes: ['barcode', 'barcodes'],
} as const;

/** Several barcodes in one cell. Not a comma — that is the column separator. */
const BARCODE_SEPARATOR = '|';

function normaliseHeader(header: string): string {
  return header
    .trim()
    .toLowerCase()
    .replace(/[\s_-]+/g, '')
    .replace(/[%.]/g, '');
}

// ------------------------------------------------------------------------- parsing

/**
 * RFC 4180, by hand.
 *
 * <p>Hand-rolled rather than a dependency: this is the only CSV in the product, the format is
 * forty lines of state machine, and it is covered by its own tests. What it does handle is the part
 * people actually hit — a quoted field containing a comma, a newline, or a doubled quote — because
 * `Rice, 5kg` in a product name is not an edge case, it is Tuesday.
 *
 * <p>Returns raw cells. Blank lines are dropped, and a line's number is its position in the
 * original file so the preview can point at a row the shopkeeper can find.
 */
export function parseCsv(text: string): { line: number; cells: string[] }[] {
  const rows: { line: number; cells: string[] }[] = [];
  let cells: string[] = [];
  let field = '';
  let quoted = false;
  let line = 1;
  let lineStart = 1;
  let sawContent = false;

  const endField = () => {
    cells.push(field);
    field = '';
  };
  const endRow = () => {
    endField();
    // A trailing newline produces one empty cell, which is not a row.
    if (sawContent) rows.push({ line: lineStart, cells });
    cells = [];
    sawContent = false;
    lineStart = line;
  };

  // The BOM Excel writes on every "CSV UTF-8" save. Left in, it becomes part of the first
  // header name and the sku column silently goes missing.
  const source = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;

  for (let i = 0; i < source.length; i++) {
    // charAt rather than [i]: under noUncheckedIndexedAccess the index signature is
    // string | undefined, and every branch below would need a guard for a case the loop
    // bound already rules out.
    const char = source.charAt(i);

    if (quoted) {
      if (char === '"') {
        if (source[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          quoted = false;
        }
      } else {
        if (char === '\n') line++;
        field += char;
      }
      continue;
    }

    if (char === '"') {
      quoted = true;
      sawContent = true;
    } else if (char === ',') {
      endField();
      sawContent = true;
    } else if (char === '\r') {
      // Swallowed; the \n that follows ends the row.
    } else if (char === '\n') {
      line++;
      endRow();
      lineStart = line;
    } else {
      field += char;
      if (char.trim() !== '') sawContent = true;
    }
  }
  endRow();

  return rows;
}

// ------------------------------------------------------------------- interpretation

/**
 * Turns a CSV file into rows the import endpoint understands.
 *
 * <p>Every failure is collected rather than thrown. A shopkeeper with a 400-line file needs to see
 * all forty broken rows at once; a parser that stops at the first one turns a five-minute fix into
 * forty round trips through a spreadsheet.
 */
export function parseProductCsv(text: string): ParsedImport {
  const raw = parseCsv(text);
  const problems: CsvProblem[] = [];
  const warnings: string[] = [];

  if (raw.length === 0) {
    return { rows: [], problems: [{ line: 1, message: 'The file is empty.' }], warnings };
  }

  const header = raw[0]!.cells.map(normaliseHeader);
  const indexOf = (names: readonly string[]) =>
    header.findIndex((cell) => names.includes(cell as never));

  const at = {
    sku: indexOf(COLUMNS.sku),
    name: indexOf(COLUMNS.name),
    price: indexOf(COLUMNS.price),
    vat: indexOf(COLUMNS.vat),
    taxMode: indexOf(COLUMNS.taxMode),
    category: indexOf(COLUMNS.category),
    barcodes: indexOf(COLUMNS.barcodes),
  };

  const missing = (['sku', 'name', 'price'] as const).filter((key) => at[key] === -1);
  if (missing.length > 0) {
    return {
      rows: [],
      problems: [
        {
          line: 1,
          message:
            `The file needs a column for ${missing.join(', ')}. ` +
            `Found: ${raw[0]!.cells.map((c) => c.trim()).join(', ') || '(nothing)'}.`,
        },
      ],
      warnings,
    };
  }

  // Absent columns take a default, and each default is announced. Silence here is how an entire
  // catalogue ends up zero-rated by a file that looked fine.
  if (at.vat === -1) {
    warnings.push(
      'There is no VAT column, so every product in this file will be zero-rated. Add a "vat" ' +
        'column with the percentage if that is wrong.',
    );
  }
  if (at.taxMode === -1) {
    warnings.push('There is no tax mode column, so every price is treated as VAT-inclusive.');
  }
  if (at.barcodes === -1) {
    warnings.push(
      'There is no barcode column, so these products will not scan until one is added.',
    );
  }

  const rows: ImportRow[] = [];
  const cell = (cells: string[], index: number) =>
    index === -1 ? '' : (cells[index] ?? '').trim();

  for (const { line, cells } of raw.slice(1)) {
    const sku = cell(cells, at.sku);
    const name = cell(cells, at.name);
    const priceText = cell(cells, at.price);

    if (sku === '' && name === '' && priceText === '') {
      continue; // A blank row in the middle of a spreadsheet is not a mistake worth reporting.
    }

    let broken = false;
    const fail = (message: string) => {
      problems.push({ line, message });
      broken = true;
    };

    if (sku === '') fail('No product code.');
    if (name === '') fail('No name.');

    const priceMinor = parseAmountToMinor(priceText);
    if (priceMinor === null) {
      fail(`Could not read the price "${priceText}". Write it as 285 or 285.00.`);
    } else if (priceMinor < 0) {
      fail(`The price ${priceText} is negative.`);
    }

    let taxRateBp = 0;
    if (at.vat !== -1) {
      const vatText = cell(cells, at.vat);
      // An empty VAT cell is zero-rated, which is a real thing for bread and rice. It is the
      // absent *column* that is suspicious, and that is warned about above.
      const parsed = vatText === '' ? minor(0) : parseAmountToMinor(vatText);
      if (parsed === null) {
        fail(`Could not read the VAT rate "${vatText}". Write it as 18 or 2.5.`);
      } else if (parsed < 0) {
        fail(`The VAT rate ${vatText} is negative.`);
      } else if (parsed > 10_000) {
        fail(`A VAT rate of ${vatText}% is a typo — write 18, not 1800.`);
      } else {
        taxRateBp = parsed;
      }
    }

    let taxMode: 'INCLUSIVE' | 'EXCLUSIVE' = 'INCLUSIVE';
    if (at.taxMode !== -1) {
      const modeText = cell(cells, at.taxMode).toUpperCase();
      if (modeText === 'EXCLUSIVE') taxMode = 'EXCLUSIVE';
      else if (modeText !== 'INCLUSIVE' && modeText !== '')
        fail(`Tax mode must be INCLUSIVE or EXCLUSIVE, not "${modeText}".`);
    }

    const categoryText = cell(cells, at.category);
    const barcodeText = cell(cells, at.barcodes);
    const barcodes = barcodeText
      .split(BARCODE_SEPARATOR)
      .map((code) => code.trim())
      .filter((code) => code !== '');

    const seen = new Set<string>();
    for (const barcode of barcodes) {
      if (seen.has(barcode)) fail(`The barcode ${barcode} is on this row twice.`);
      seen.add(barcode);
    }

    if (broken) continue;

    rows.push({
      line,
      sku,
      name,
      priceMinor: priceMinor as Minor,
      taxMode,
      taxRateBp,
      category: categoryText === '' ? null : categoryText,
      barcodes,
    });
  }

  // Caught here rather than by the database, because the database would report the second write
  // failing and this can name both lines the shopkeeper has to reconcile.
  const byKey = new Map<string, number>();
  for (const row of rows) {
    const key = row.sku.toLowerCase();
    const first = byKey.get(key);
    if (first !== undefined) {
      problems.push({
        line: row.line,
        message: `The product code ${row.sku} is also on line ${first}. Each code may appear once.`,
      });
    } else {
      byKey.set(key, row.line);
    }
  }

  const barcodeOwner = new Map<string, number>();
  for (const row of rows) {
    for (const barcode of row.barcodes) {
      const first = barcodeOwner.get(barcode);
      if (first !== undefined) {
        problems.push({
          line: row.line,
          message: `The barcode ${barcode} is also on line ${first}. A barcode belongs to one product.`,
        });
      } else {
        barcodeOwner.set(barcode, row.line);
      }
    }
  }

  return { rows, problems, warnings };
}
