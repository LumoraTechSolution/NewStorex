/**
 * The printed Z-report and credit note (M2-11, M2-06).
 *
 * Pure formatting, like `receipt.ts`: given figures that were already recorded, produce the ESC/POS
 * bytes. No clock of its own and no arithmetic beyond laying out sums that arrived computed — the
 * one exception being the derivation block, which subtracts figures the backend sent individually
 * precisely so the paper can show its working.
 *
 * ## Why the Z-report shows every term
 *
 * A variance with no visible derivation is a number nobody trusts and everybody overrides, which is
 * the same as not having one. So the paper carries the float, the cash taken, the change given, the
 * rounding residual, every pay-out and drop, and only then the expected figure — in the order the
 * arithmetic runs, so a shopkeeper with a pen can check it.
 *
 * ## Why the credit note is a separate document
 *
 * It is a tax document in its own right. It carries its own number from its own sequence, names the
 * invoice it reverses, and shows VAT per rate — the same per-rate summary the invoice showed,
 * because a customer returning goods needs a document their accountant can post against the one
 * they were given.
 */

import { formatMinor, type TenderKind } from '@lumora/domain';

import * as esc from './escpos';

/** 42 columns is Font A on 80mm paper — the same width `receipt.ts` uses. */
const DEFAULT_WIDTH = 42;

// -------------------------------------------------------------------------------- Z-report

export interface ZReportData {
  readonly storeName: string;
  readonly tagline: string;
  readonly branchCode: string;
  readonly terminalCode: string;
  readonly shiftId: number;
  readonly openedAt: string;
  readonly closedAt: string;

  readonly saleCount: number;
  readonly grossSalesMinor: number;
  readonly discountMinor: number;
  readonly taxMinor: number;
  readonly tendersByKind: readonly { kind: string; amountMinor: number; lineCount: number }[];
  readonly taxByRate: readonly { rateBp: number; grossMinor: number; taxMinor: number }[];

  readonly refundCount: number;
  readonly refundTotalMinor: number;

  readonly openingFloatMinor: number;
  readonly cashSalesMinor: number;
  readonly cashChangeMinor: number;
  readonly cashRoundingMinor: number;
  readonly cashMovementsMinor: number;
  readonly cashRefundsMinor: number;
  readonly cashRefundRoundingMinor: number;
  readonly cashMovementsByReason: readonly {
    kind: string;
    reasonCode: string;
    amountMinor: number;
  }[];

  readonly expectedCashMinor: number;
  readonly countedCashMinor: number;
  readonly varianceMinor: number;
  readonly varianceReason: string | null;
  readonly closingCount: readonly {
    denominationMinor: number;
    qty: number;
    subtotalMinor: number;
  }[];
}

export function buildZReport(data: ZReportData, width = DEFAULT_WIDTH): Uint8Array {
  const chunks: Uint8Array[] = [esc.init(), esc.align('center')];

  chunks.push(esc.bold(true), esc.line('Z-REPORT'), esc.bold(false));
  chunks.push(esc.line(data.storeName));
  chunks.push(esc.line());

  chunks.push(esc.align('left'));
  chunks.push(esc.line(twoColumn(data.branchCode, `Till ${data.terminalCode}`, width)));
  chunks.push(esc.line(`Shift:  ${data.shiftId}`));
  chunks.push(esc.line(`Opened: ${formatStamp(data.openedAt)}`));
  chunks.push(esc.line(`Closed: ${formatStamp(data.closedAt)}`));
  chunks.push(esc.line(divider(width)));

  // ---- Sales
  chunks.push(esc.bold(true), esc.line('SALES'), esc.bold(false));
  chunks.push(esc.line(twoColumn('Transactions', String(data.saleCount), width)));
  chunks.push(esc.line(twoColumn('Gross', formatMinor(data.grossSalesMinor), width)));
  if (data.discountMinor > 0) {
    chunks.push(esc.line(twoColumn('Discounts', `-${formatMinor(data.discountMinor)}`, width)));
  }
  chunks.push(esc.line(twoColumn('VAT', formatMinor(data.taxMinor), width)));

  for (const band of data.taxByRate) {
    chunks.push(
      esc.line(twoColumn(`  at ${ratePercent(band.rateBp)}`, formatMinor(band.taxMinor), width)),
    );
  }
  chunks.push(esc.line());

  // ---- Tenders
  chunks.push(esc.bold(true), esc.line('TENDERS'), esc.bold(false));
  for (const tender of data.tendersByKind) {
    chunks.push(
      esc.line(
        twoColumn(`${tender.kind} (${tender.lineCount})`, formatMinor(tender.amountMinor), width),
      ),
    );
  }
  chunks.push(esc.line());

  // ---- Returns
  if (data.refundCount > 0) {
    chunks.push(esc.bold(true), esc.line('RETURNS'), esc.bold(false));
    chunks.push(esc.line(twoColumn('Credit notes', String(data.refundCount), width)));
    chunks.push(esc.line(twoColumn('Refunded', `-${formatMinor(data.refundTotalMinor)}`, width)));
    chunks.push(esc.line());
  }

  // ---- The drawer, term by term. This block is the report's reason to exist.
  chunks.push(esc.bold(true), esc.line('CASH DRAWER'), esc.bold(false));
  chunks.push(esc.line(twoColumn('Opening float', formatMinor(data.openingFloatMinor), width)));
  chunks.push(esc.line(twoColumn('Cash taken', formatMinor(data.cashSalesMinor), width)));
  chunks.push(esc.line(twoColumn('Change given', `-${formatMinor(data.cashChangeMinor)}`, width)));
  if (data.cashRoundingMinor !== 0) {
    chunks.push(esc.line(twoColumn('Cash rounding', formatSigned(data.cashRoundingMinor), width)));
  }
  for (const movement of data.cashMovementsByReason) {
    chunks.push(
      esc.line(
        twoColumn(`  ${humanise(movement.reasonCode)}`, formatSigned(movement.amountMinor), width),
      ),
    );
  }
  if (data.cashRefundsMinor > 0) {
    chunks.push(
      esc.line(twoColumn('Cash refunds', `-${formatMinor(data.cashRefundsMinor)}`, width)),
    );
  }
  if (data.cashRefundRoundingMinor !== 0) {
    chunks.push(
      esc.line(twoColumn('Refund rounding', formatSigned(-data.cashRefundRoundingMinor), width)),
    );
  }
  chunks.push(esc.line(divider(width)));
  chunks.push(esc.line(twoColumn('EXPECTED', formatMinor(data.expectedCashMinor), width)));
  chunks.push(esc.line(twoColumn('COUNTED', formatMinor(data.countedCashMinor), width)));

  chunks.push(esc.bold(true));
  chunks.push(
    esc.line(
      twoColumn(
        data.varianceMinor === 0 ? 'BALANCED' : data.varianceMinor > 0 ? 'OVER' : 'SHORT',
        formatSigned(data.varianceMinor),
        width,
      ),
    ),
  );
  chunks.push(esc.bold(false));
  if (data.varianceReason) {
    chunks.push(esc.line(`Reason: ${humanise(data.varianceReason)}`));
  }
  chunks.push(esc.line());

  // ---- The count itself. One missing 5000 note reads very differently from a hundred coins.
  chunks.push(esc.bold(true), esc.line('COUNTED, NOTE BY NOTE'), esc.bold(false));
  for (const row of data.closingCount) {
    if (row.qty === 0) continue;
    chunks.push(
      esc.line(
        twoColumn(
          `  ${(row.denominationMinor / 100).toLocaleString('en-LK')} x ${row.qty}`,
          formatMinor(row.subtotalMinor),
          width,
        ),
      ),
    );
  }

  chunks.push(esc.line());
  chunks.push(esc.align('center'));
  chunks.push(esc.line('Counted by ______________________'));
  chunks.push(esc.line());
  chunks.push(esc.line('Checked by ______________________'));
  chunks.push(esc.line());
  chunks.push(esc.line(data.tagline));
  chunks.push(esc.feed(3));
  chunks.push(esc.cut(true));

  return esc.concatBytes(chunks);
}

// ----------------------------------------------------------------------------- credit note

export interface CreditNoteData {
  readonly storeName: string;
  readonly tagline: string;
  readonly branchCode: string;
  readonly terminalCode: string;
  readonly creditNoteNumber: string;
  /** The invoice this reverses. A credit note that does not name one is not a credit note. */
  readonly saleInvoiceNumber: string;
  readonly refundedAt: string;
  readonly lines: readonly {
    name: string;
    qty: number;
    refundTotalMinor: number;
    taxRateBp: number;
  }[];
  readonly totalMinor: number;
  readonly taxMinor: number;
  readonly roundingAdjustmentMinor: number;
  readonly tenders: readonly { kind: TenderKind; amountMinor: number }[];
}

export function buildCreditNote(data: CreditNoteData, width = DEFAULT_WIDTH): Uint8Array {
  const chunks: Uint8Array[] = [esc.init(), esc.align('center')];

  chunks.push(
    esc.bold(true),
    esc.doubleSize(true),
    esc.line(data.storeName),
    esc.doubleSize(false),
  );
  chunks.push(esc.line('CREDIT NOTE'), esc.bold(false));
  chunks.push(esc.line(data.tagline));
  chunks.push(esc.line());

  chunks.push(esc.align('left'));
  chunks.push(esc.line(twoColumn(data.branchCode, `Till ${data.terminalCode}`, width)));
  chunks.push(esc.line(`Credit note: ${data.creditNoteNumber}`));
  // The link that makes this document mean anything (M2-06).
  chunks.push(esc.line(`Against invoice: ${data.saleInvoiceNumber}`));
  chunks.push(esc.line(formatStamp(data.refundedAt)));
  chunks.push(esc.line(divider(width)));

  for (const line of data.lines) {
    chunks.push(esc.line(truncate(line.name, width)));
    chunks.push(
      esc.line(twoColumn(`  ${line.qty} returned`, formatMinor(line.refundTotalMinor), width)),
    );
  }
  chunks.push(esc.line(divider(width)));

  chunks.push(esc.bold(true));
  chunks.push(esc.line(twoColumn('REFUNDED', formatMinor(data.totalMinor), width)));
  chunks.push(esc.bold(false));
  chunks.push(esc.line(twoColumn('  of which VAT', formatMinor(data.taxMinor), width)));
  chunks.push(esc.line());

  for (const tender of data.tenders) {
    chunks.push(
      esc.line(twoColumn(`Back to ${tender.kind}`, formatMinor(tender.amountMinor), width)),
    );
  }
  if (data.roundingAdjustmentMinor !== 0) {
    chunks.push(esc.line(twoColumn('Rounding', formatSigned(data.roundingAdjustmentMinor), width)));
  }

  chunks.push(esc.line());
  chunks.push(esc.align('center'));
  chunks.push(esc.line('Customer signature'));
  chunks.push(esc.line());
  chunks.push(esc.line('______________________________'));
  chunks.push(esc.feed(3));
  chunks.push(esc.cut(true));

  return esc.concatBytes(chunks);
}

/**
 * The credit note and the drawer kick, when cash is actually going back across the counter.
 *
 * Conditional, unlike the sale receipt's: a refund that goes back entirely to a card moves no cash,
 * and popping the drawer for it would put a cashier's hands in the till for no reason — which is
 * both a shrinkage risk and exactly the kind of habit an audit trail cannot see.
 */
export function buildCreditNoteWithDrawerKick(
  data: CreditNoteData,
  width = DEFAULT_WIDTH,
): Uint8Array {
  const note = buildCreditNote(data, width);
  const paysCash = data.tenders.some((t) => t.kind === 'CASH');
  return paysCash ? esc.concatBytes([note, esc.openDrawer()]) : note;
}

// ------------------------------------------------------------------------------- formatting

function ratePercent(rateBp: number): string {
  return `${(rateBp / 100).toFixed(rateBp % 100 === 0 ? 0 : 2)}%`;
}

function humanise(code: string): string {
  const lower = code.replace(/_/g, ' ').toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

function formatSigned(amountMinor: number): string {
  return amountMinor > 0 ? `+${formatMinor(amountMinor)}` : formatMinor(amountMinor);
}

function formatStamp(iso: string): string {
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
