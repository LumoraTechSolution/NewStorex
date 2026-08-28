/**
 * The printed receipt (M1-13).
 *
 * Pure formatting: given the sale as it was actually recorded, produces the exact ESC/POS byte
 * buffer to hand a printer. No I/O, no clock of its own — `soldAt` and every figure come from the
 * caller, which is what lets a resend of an already-committed sale (M1-11's idempotent retry)
 * reprint the *original* receipt instead of a new one stamped with whatever time the retry
 * happened to run at.
 *
 * Money is formatted with `formatMinor` from `@lumora/domain`, the same function the screen uses
 * — a receipt whose own arithmetic looks different from the till's is a receipt a customer argues
 * with, and the fastest way to get there is a second money formatter.
 */

import { formatMinor, type TenderKind } from '@lumora/domain';

import * as esc from './escpos';

export interface ReceiptLine {
  readonly name: string;
  readonly qty: number;
  readonly unitPriceMinor: number;
  readonly lineTotalMinor: number;
}

/**
 * One rate's worth of the sale, structurally the domain's `TaxBreakdownEntry` but with plain
 * numbers — like every other figure on {@link ReceiptData}.
 *
 * The domain's `Minor` brand exists to stop a float entering the money path. Nothing is
 * computed here: the receipt renders a sale that was priced, committed and possibly read
 * back out of JSON, and demanding branded values would mean re-branding every figure on a
 * reprint for a guarantee the numbers already carry by having been through `cartTotals`.
 * A branded value assigns straight into `number`, so a caller holding real domain values
 * just passes them.
 */
export interface ReceiptTaxBreakdown {
  readonly mode: 'INCLUSIVE' | 'EXCLUSIVE';
  readonly rateBp: number;
  readonly grossMinor: number;
  readonly exVatMinor: number;
  readonly taxMinor: number;
}

export interface ReceiptTender {
  readonly kind: TenderKind;
  readonly amountMinor: number;
}

export interface ReceiptData {
  readonly storeName: string;
  readonly tagline: string;
  /**
   * The shop's street address, printed under the tagline (M5-09).
   *
   * Required, and required as a *value* rather than an optional field: a tax invoice has to
   * carry the supplier's address, so a caller that has not thought about it should fail to
   * compile. Newlines split it into printed lines; anything longer than the paper is wrapped
   * at word boundaries here rather than left to the printer, which breaks mid-word. An empty
   * string prints nothing, which is the escape hatch for a shop that has not set one yet.
   */
  readonly storeAddress: string;
  readonly branchName: string;
  readonly branchCode: string;
  readonly terminalCode: string;
  readonly invoiceNumber: string;
  /** ISO instant. Printed as the shop PC's local time — the till's clock is the shop's clock. */
  readonly soldAt: string;
  /**
   * The customer attached to the sale (M3-11), or `null` for the walk-in that most sales are.
   *
   * The label prints either way. Left blank it is a line somebody can write a name on, which
   * is what a customer asking for a tax invoice after the fact actually needs; omitting the
   * line entirely would mean reprinting the receipt to get one.
   */
  readonly customerName: string | null;
  readonly lines: readonly ReceiptLine[];
  readonly subtotalMinor: number;
  readonly discountMinor: number;
  readonly taxMinor: number;
  /** The sale's default stamp — what a line inherited where it carried none (M1-05). */
  readonly taxRateBp: number;
  readonly taxMode: 'INCLUSIVE' | 'EXCLUSIVE';
  /**
   * What was charged at each rate (M1-18). Required, not optional: a receipt is the
   * document the revenue authority reads, and a caller that forgot to pass this should
   * fail to compile rather than quietly print a sale with its VAT summary missing.
   */
  readonly taxBreakdown: readonly ReceiptTaxBreakdown[];
  readonly totalMinor: number;
  readonly tenders: readonly ReceiptTender[];
  readonly roundingAdjustmentMinor: number;
  readonly changeMinor: number;
}

/** 42 columns is Font A on 80mm paper — the most common thermal width in the field. */
const DEFAULT_WIDTH = 42;

const TENDER_LABEL: Record<TenderKind, string> = {
  CASH: 'CASH',
  CARD: 'CARD',
  WALLET: 'WALLET',
  STORE_CREDIT: 'STORE CREDIT',
};

export function buildReceipt(data: ReceiptData, width = DEFAULT_WIDTH): Uint8Array {
  const chunks: Uint8Array[] = [esc.init(), esc.align('center')];

  chunks.push(
    esc.bold(true),
    esc.doubleSize(true),
    esc.line(data.storeName),
    esc.doubleSize(false),
  );
  chunks.push(esc.bold(false), esc.line(data.tagline));
  for (const text of wrap(data.storeAddress, width)) {
    chunks.push(esc.line(text));
  }
  chunks.push(esc.line());

  chunks.push(esc.align('left'));
  chunks.push(esc.line(twoColumn(data.branchName, `Till ${data.terminalCode}`, width)));
  chunks.push(esc.line(`Invoice: ${data.invoiceNumber}`));
  chunks.push(esc.line(formatSoldAt(data.soldAt)));
  chunks.push(esc.line(customerLine(data.customerName, width)));
  chunks.push(esc.line(divider(width)));

  for (const line of data.lines) {
    chunks.push(esc.line(truncate(line.name, width)));
    const qtyPrice = `  ${line.qty} x ${formatMinor(line.unitPriceMinor)}`;
    chunks.push(esc.line(twoColumn(qtyPrice, formatMinor(line.lineTotalMinor), width)));
  }
  chunks.push(esc.line(divider(width)));

  chunks.push(esc.line(twoColumn('Subtotal', formatMinor(data.subtotalMinor), width)));
  if (data.discountMinor > 0) {
    chunks.push(esc.line(twoColumn('Discount', `-${formatMinor(data.discountMinor)}`, width)));
  }
  for (const text of taxSummary(data, width)) {
    chunks.push(esc.line(text));
  }
  chunks.push(esc.line(divider(width)));

  chunks.push(esc.bold(true));
  chunks.push(esc.line(twoColumn('TOTAL', formatMinor(data.totalMinor), width)));
  chunks.push(esc.bold(false));
  chunks.push(esc.line());

  for (const tender of data.tenders) {
    chunks.push(
      esc.line(twoColumn(TENDER_LABEL[tender.kind], formatMinor(tender.amountMinor), width)),
    );
  }
  if (data.roundingAdjustmentMinor !== 0) {
    chunks.push(esc.line(twoColumn('Rounding', formatSigned(data.roundingAdjustmentMinor), width)));
  }
  if (data.changeMinor > 0) {
    chunks.push(esc.line(twoColumn('Change', formatMinor(data.changeMinor), width)));
  }

  chunks.push(esc.line());
  chunks.push(esc.align('center'));
  chunks.push(esc.line('Thank you for shopping with us'));
  chunks.push(esc.line(data.tagline));
  chunks.push(esc.feed(3));
  chunks.push(esc.cut(true));

  return esc.concatBytes(chunks);
}

/**
 * The receipt and the drawer kick as one buffer. A shop's drawer cable plugs into the printer,
 * not the PC, so opening it is one more command in the same write — never a second connection.
 */
export function buildReceiptWithDrawerKick(data: ReceiptData, width = DEFAULT_WIDTH): Uint8Array {
  return esc.concatBytes([buildReceipt(data, width), esc.openDrawer()]);
}

// ----------------------------------------------------------------------------- VAT summary

/**
 * The tax block (M1-18).
 *
 * Two shapes, for one reason each.
 *
 * **Every receipt prints the net.** A tax invoice has to show the amount excluding tax, the
 * tax, and the total as three separate figures. Until now this receipt printed the VAT and
 * the total and left the net to be inferred by subtraction, which is not the same as
 * stating it. Under an inclusive regime the net is never written on anything else, so if
 * the receipt does not say it, nothing does.
 *
 * **A mixed sale prints a table.** One row per rate, because the whole point of separating
 * an exempt line from a standard-rated one is that the two sums are legible apart. A single
 * "VAT" figure spanning both rates answers a question nobody asked. Almost every sale in a
 * shop is one rate, so the common case stays two lines rather than paying a table's height
 * in paper for a table with one row in it.
 *
 * The rate is printed even when it is zero. "0%" is a statement that the line was
 * considered and found exempt; a blank is an omission, and they look identical afterwards.
 */
function taxSummary(data: ReceiptData, width: number): string[] {
  if (data.taxBreakdown.length > 1) {
    return mixedTaxSummary(data, width);
  }

  // The sale's own stamp, unless the single breakdown entry disagrees — it is the line-level
  // truth and the sale-level pair is only the default the lines inherited.
  const only = data.taxBreakdown[0];
  const mode = only?.mode ?? data.taxMode;
  const rateBp = only?.rateBp ?? data.taxRateBp;
  const exVatMinor = only?.exVatMinor ?? data.totalMinor - data.taxMinor;

  return [
    twoColumn('Net (excl. VAT)', formatMinor(exVatMinor), width),
    twoColumn(taxLabel(mode, rateBp), formatMinor(data.taxMinor), width),
  ];
}

/**
 * The mixed-rate table.
 *
 * Columns are derived from the paper width rather than fixed, because the same code prints
 * to 80mm Font A (42 columns) and to a 58mm roll (32), and a money table that overflows its
 * width wraps into nonsense. What gives way on narrow paper is the **Gross** column: it is
 * exactly Net + VAT, both of which are in the row already, so it is the only figure the
 * reader can reconstruct without doing arithmetic the receipt should have done for them.
 *
 * Within a width the columns are fixed, not measured against the amounts. A table whose
 * columns move with its contents stops being a table — these figures are read down the
 * column, not across the row.
 */
const RATE_COLUMN = 5;

/** The narrowest paper that can hold Net, VAT and Gross side by side and still be read. */
const GROSS_COLUMN_MIN_WIDTH = 40;

function tableColumns(width: number): { columns: number[]; showGross: boolean } {
  const showGross = width >= GROSS_COLUMN_MIN_WIDTH;
  const count = showGross ? 3 : 2;
  const rest = Math.max(count * 6, width - RATE_COLUMN);
  const each = Math.floor(rest / count);

  // The last column absorbs the remainder, so the row is exactly `width` wide and its right
  // edge lines up with the Subtotal and TOTAL figures above and below it.
  const columns = Array.from({ length: count }, (_, i) =>
    i === count - 1 ? rest - each * (count - 1) : each,
  );
  return { columns, showGross };
}

function mixedTaxSummary(data: ReceiptData, width: number): string[] {
  const { showGross } = tableColumns(width);

  const amounts = (entry: ReceiptTaxBreakdown): string[] => {
    const cells = [formatMinor(entry.exVatMinor), formatMinor(entry.taxMinor)];
    return showGross ? [...cells, formatMinor(entry.grossMinor)] : cells;
  };

  // A total row, so the block reconciles on the paper rather than in the reader's head. Its
  // gross is the sale total printed just below and its VAT is the sale's tax — stated here
  // anyway, because a column of figures that stops without a sum invites the reader to add
  // it up themselves and disagree.
  const exVatMinor = data.taxBreakdown.reduce((sum, e) => sum + e.exVatMinor, 0);
  const grossMinor = data.taxBreakdown.reduce((sum, e) => sum + e.grossMinor, 0);
  const totals = [formatMinor(exVatMinor), formatMinor(data.taxMinor)];

  return [
    'VAT SUMMARY',
    tableRow('Rate', showGross ? ['Net', 'VAT', 'Gross'] : ['Net', 'VAT'], width),
    ...data.taxBreakdown.map((entry) => tableRow(ratePercent(entry.rateBp), amounts(entry), width)),
    ruleRow(width),
    tableRow('', showGross ? [...totals, formatMinor(grossMinor)] : totals, width),
  ];
}

function tableRow(rate: string, amounts: string[], width: number): string {
  const { columns } = tableColumns(width);
  return rate.padStart(RATE_COLUMN) + amounts.map((v, i) => v.padStart(columns[i]!)).join('');
}

/** Underlines each amount column, so the rule sits exactly under the figures it totals. */
function ruleRow(width: number): string {
  const { columns } = tableColumns(width);
  return ' '.repeat(RATE_COLUMN) + columns.map((c) => '-'.repeat(c - 1).padStart(c)).join('');
}

function ratePercent(rateBp: number): string {
  return `${(rateBp / 100).toFixed(rateBp % 100 === 0 ? 0 : 2)}%`;
}

// ------------------------------------------------------------------------------- formatting

function taxLabel(mode: 'INCLUSIVE' | 'EXCLUSIVE', rateBp: number): string {
  return `VAT ${ratePercent(rateBp)} (${mode === 'INCLUSIVE' ? 'incl.' : 'excl.'})`;
}

function formatSigned(amountMinor: number): string {
  return amountMinor > 0 ? `+${formatMinor(amountMinor)}` : formatMinor(amountMinor);
}

function formatSoldAt(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return new Intl.DateTimeFormat('en-GB', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);
}

/**
 * `Customer: <name>`, or the bare label when nobody is attached.
 *
 * Clipped rather than wrapped: a name long enough to overflow 42 columns is a data-entry
 * accident, and a second line of it would push the whole header down on every receipt that
 * had one.
 */
function customerLine(name: string | null, width: number): string {
  const label = 'Customer:';
  if (name === null || name.trim().length === 0) return label;
  return truncate(`${label} ${name.trim()}`, width);
}

/**
 * Word-wraps to the paper width, splitting on explicit newlines first so a two-line address
 * stays two lines. A word longer than the paper is hard-broken — there is nowhere else for it
 * to go, and dropping it would lose part of an address.
 */
function wrap(text: string, width: number): string[] {
  const out: string[] = [];
  for (const paragraph of text.split('\n')) {
    let current = '';
    for (const word of paragraph.trim().split(/\s+/).filter(Boolean)) {
      if (current.length === 0) {
        current = word;
      } else if (current.length + 1 + word.length <= width) {
        current += ` ${word}`;
      } else {
        out.push(current);
        current = word;
      }
      while (current.length > width) {
        out.push(current.slice(0, width));
        current = current.slice(width);
      }
    }
    if (current.length > 0) out.push(current);
  }
  return out;
}

function truncate(s: string, width: number): string {
  return s.length > width ? s.slice(0, width) : s;
}

function divider(width: number): string {
  return '-'.repeat(width);
}

/** Right-aligns `right`, gives everything else to `left`, and clips `left` if it would not fit. */
function twoColumn(left: string, right: string, width: number): string {
  const leftWidth = Math.max(1, width - right.length - 1);
  const clippedLeft = left.length > leftWidth ? left.slice(0, leftWidth) : left;
  const gap = Math.max(1, width - clippedLeft.length - right.length);
  return clippedLeft + ' '.repeat(gap) + right;
}
