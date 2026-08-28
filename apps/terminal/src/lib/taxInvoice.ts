/**
 * The IRD tax invoice (M5-09).
 *
 * Gazette Extraordinary **2481/22** of 27 March 2026, in force since 1 July 2026, read together
 * with IRD Circular **SEC/2026/E/03** of 20 May 2026. Every heading below cites the clause it
 * satisfies, because the next person to touch this file will be checking it against the gazette
 * and not against this comment.
 *
 * This is **not** the receipt. `receipt.ts` prints what every customer gets; this prints the
 * separate legal document a purchaser asks for. The split is forced by §4.2: a tax invoice may
 * carry only supplies that are subject to VAT, and a grocery basket mixes exempt and standard-rated
 * goods constantly. The server has already excluded the exempt lines by the time the data reaches
 * here — this module renders what it is given and decides nothing about what belongs.
 *
 * Same money rule as everywhere else: `formatMinor` from `@lumora/domain`, which is also what
 * satisfies §4.7's "two decimal places".
 */

import { formatMinor } from '@lumora/domain';

import * as esc from './escpos';

/** Gazette §2.1 — top left-hand corner, from the VAT registration certificate. */
export interface TaxInvoiceSupplier {
  readonly tin: string;
  readonly registeredName: string;
  readonly address: string;
}

/**
 * Gazette §3.1, as narrowed by Circular §4.3.
 *
 * `null` for a walk-in consumer, which is the ordinary case and is compliant: the circular requires
 * these particulars only *"where the purchaser is VAT-registered"*. A till that demanded a TIN from
 * every shopper could not serve a queue.
 */
export interface TaxInvoicePurchaser {
  readonly tin: string;
  readonly name: string;
  readonly address: string;
}

export interface TaxInvoiceLine {
  /** §4.1(e) — "avoid vague terms such as items, products, services". */
  readonly name: string;
  /** §4.1(f). */
  readonly qty: number;
  readonly unitPriceMinor: number;
  /** The gazette's own column heading is "Amount Excluding VAT". */
  readonly exVatMinor: number;
  readonly taxRateBp: number;
}

export interface TaxInvoiceData {
  readonly supplier: TaxInvoiceSupplier;
  readonly purchaser: TaxInvoicePurchaser | null;
  /** §4.1(a) — `YYMMM-QQQQ-XXXXX`, allocated on the till. */
  readonly invoiceNumber: string;
  /** ISO instant. §4.1(b) — when the invoice was issued. */
  readonly issuedAt: string;
  /** ISO instant. §4.1(d) — when the goods or services were supplied. */
  readonly suppliedAt: string;
  /** The till receipt this was raised against. Not required by the gazette; §5.1 permits it. */
  readonly saleInvoiceNumber: string;
  readonly lines: readonly TaxInvoiceLine[];
  readonly totalExclVatMinor: number;
  readonly vatMinor: number;
  readonly totalInclVatMinor: number;
}

const DEFAULT_WIDTH = 42;

const EXEMPT_NOTICE =
  'This tax invoice covers VAT-taxable supplies only. Any exempt items on the receipt are excluded, as required.';

export function buildTaxInvoice(data: TaxInvoiceData, width = DEFAULT_WIDTH): Uint8Array {
  const chunks: Uint8Array[] = [esc.init()];

  // §1.1–1.2: "prominently titled TAX INVOICE ... using bold or highlighted text". Double height
  // as well as bold, because §1.2 asks for a conspicuous place and this is a 42-column receipt
  // printer — bold alone at this size is not conspicuous, it is merely darker.
  chunks.push(esc.align('center'), esc.bold(true), esc.doubleSize(true));
  chunks.push(esc.line('TAX INVOICE'));
  chunks.push(esc.doubleSize(false), esc.bold(false), esc.align('left'), esc.line());

  // §2.1 — supplier. The gazette puts this top left; on a 42-column roll there is only left.
  chunks.push(esc.bold(true), esc.line('Supplier'), esc.bold(false));
  for (const text of wrap(data.supplier.registeredName, width)) chunks.push(esc.line(text));
  for (const text of wrap(data.supplier.address, width)) chunks.push(esc.line(text));
  chunks.push(esc.line(`TIN: ${data.supplier.tin}`));
  chunks.push(esc.line());

  // §3.1 with Circular §4.3 — printed only when there is a VAT-registered purchaser.
  if (data.purchaser !== null) {
    chunks.push(esc.bold(true), esc.line('Purchaser'), esc.bold(false));
    for (const text of wrap(data.purchaser.name, width)) chunks.push(esc.line(text));
    for (const text of wrap(data.purchaser.address, width)) chunks.push(esc.line(text));
    chunks.push(esc.line(`TIN: ${data.purchaser.tin}`));
    chunks.push(esc.line());
  }

  // §4.1(a), (b), (d). Both dates always print — the distinction decides which VAT period the
  // supply falls in, so a single date would be an omission even when the two are equal.
  const details: readonly (readonly [string, string])[] = [
    ['Tax Invoice No', data.invoiceNumber],
    ['Date of Invoice', formatGazetteDate(data.issuedAt)],
    ['Date of Supply', formatGazetteDate(data.suppliedAt)],
    ['Receipt Ref', data.saleInvoiceNumber],
  ];
  // One label column, so the two dates line up under each other. An auditor comparing the
  // invoice date against the supply date is doing it by eye, down the page.
  const labelWidth = Math.max(...details.map(([label]) => label.length));
  for (const [label, value] of details) {
    for (const text of labelled(label, value, width, labelWidth)) {
      chunks.push(esc.line(text));
    }
  }
  chunks.push(esc.line(divider(width)));

  // §4.1(e)–(g). The specimen's five columns do not fit 42 characters, so the description takes
  // its own line and the quantity, unit price and amount share the next — the same shape the
  // receipt uses, which is the shape a cashier already reads.
  chunks.push(esc.line(twoColumn('Description', 'Amount excl. VAT', width)));
  for (const line of data.lines) {
    for (const text of wrap(line.name, width)) chunks.push(esc.line(text));
    const qtyPrice = `  ${line.qty} x ${formatMinor(line.unitPriceMinor)} @ ${ratePercent(line.taxRateBp)}`;
    chunks.push(esc.line(twoColumn(qtyPrice, formatMinor(line.exVatMinor), width)));
  }
  chunks.push(esc.line(divider(width)));

  // §4.7 — the three figures, in the gazette's own order and close to its own wording.
  chunks.push(
    esc.line(twoColumn('Total Value of Supply', formatMinor(data.totalExclVatMinor), width)),
  );
  chunks.push(esc.line(twoColumn('VAT Amount', formatMinor(data.vatMinor), width)));
  chunks.push(esc.bold(true));
  chunks.push(esc.line(twoColumn('Total incl. VAT', formatMinor(data.totalInclVatMinor), width)));
  chunks.push(esc.bold(false));
  chunks.push(esc.line(divider(width)));

  // §4.2 / Circular §4.8, said out loud. A purchaser holding this next to a till receipt for a
  // larger amount needs to know why the two disagree, and the answer is that the exempt goods
  // are legally not allowed on this document.
  for (const text of wrap(EXEMPT_NOTICE, width)) {
    chunks.push(esc.line(text));
  }

  chunks.push(esc.feed(3));
  chunks.push(esc.cut(true));

  return esc.concatBytes(chunks);
}

// ------------------------------------------------------------------------------- formatting

/**
 * `MM/DD/YYYY`, per §4.1(b) and (d).
 *
 * That is a month-first format in a country that writes dates day-first, so it looks like a bug
 * and is not: the gazette specifies it twice, in both clauses, and the circular repeats it.
 * Built from the parts rather than through `Intl` with a US locale, because a locale would also
 * bring US separators, era handling and eventually a surprise.
 */
export function formatGazetteDate(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  return `${mm}/${dd}/${date.getFullYear()}`;
}

function ratePercent(rateBp: number): string {
  return `${(rateBp / 100).toFixed(rateBp % 100 === 0 ? 0 : 2)}%`;
}

function divider(width: number): string {
  return '-'.repeat(width);
}

/** Word-wraps to the paper, hard-breaking a word that is longer than the paper itself. */
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

/**
 * `Label: value` on one line where it fits, and on two where it does not.
 *
 * A tax invoice serial can be forty characters (§4.1(a)(v)) and 58mm paper is thirty-two, so the
 * pair genuinely cannot always share a line. Wrapping the value onto its own indented line keeps
 * the label readable; clipping would lose part of the number that identifies the document.
 */
function labelled(label: string, value: string, width: number, labelWidth = 0): string[] {
  const oneLine = `${`${label}:`.padEnd(labelWidth + 1)} ${value}`;
  if (oneLine.length <= width) return [oneLine];
  // No room to align on this paper — the value gets its own indented line rather than being
  // clipped, because half a serial number identifies nothing.
  return [`${label}:`, ...wrap(value, width - 2).map((l) => `  ${l}`)];
}

/** Right-aligns `right`, gives everything else to `left`, and clips `left` if it would not fit. */
function twoColumn(left: string, right: string, width: number): string {
  const leftWidth = Math.max(1, width - right.length - 1);
  const clippedLeft = left.length > leftWidth ? left.slice(0, leftWidth) : left;
  const gap = Math.max(1, width - clippedLeft.length - right.length);
  return clippedLeft + ' '.repeat(gap) + right;
}
