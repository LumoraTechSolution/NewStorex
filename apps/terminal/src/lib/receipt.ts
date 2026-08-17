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

export interface ReceiptTender {
  readonly kind: TenderKind;
  readonly amountMinor: number;
}

export interface ReceiptData {
  readonly storeName: string;
  readonly tagline: string;
  readonly branchName: string;
  readonly branchCode: string;
  readonly terminalCode: string;
  readonly invoiceNumber: string;
  /** ISO instant. Printed as the shop PC's local time — the till's clock is the shop's clock. */
  readonly soldAt: string;
  readonly lines: readonly ReceiptLine[];
  readonly subtotalMinor: number;
  readonly discountMinor: number;
  readonly taxMinor: number;
  readonly taxRateBp: number;
  readonly taxMode: 'INCLUSIVE' | 'EXCLUSIVE';
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
  chunks.push(esc.bold(false), esc.line(data.tagline), esc.line());

  chunks.push(esc.align('left'));
  chunks.push(esc.line(twoColumn(data.branchName, `Till ${data.terminalCode}`, width)));
  chunks.push(esc.line(`Invoice: ${data.invoiceNumber}`));
  chunks.push(esc.line(formatSoldAt(data.soldAt)));
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
  chunks.push(
    esc.line(twoColumn(taxLabel(data.taxMode, data.taxRateBp), formatMinor(data.taxMinor), width)),
  );
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

// ------------------------------------------------------------------------------- formatting

function taxLabel(mode: 'INCLUSIVE' | 'EXCLUSIVE', rateBp: number): string {
  const pct = (rateBp / 100).toFixed(rateBp % 100 === 0 ? 0 : 2);
  return `VAT ${pct}% (${mode === 'INCLUSIVE' ? 'incl.' : 'excl.'})`;
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
