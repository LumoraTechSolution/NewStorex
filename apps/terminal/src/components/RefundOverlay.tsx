'use client';

import {
  allocateRefundTenders,
  formatMinor,
  REASONS_NOT_NORMALLY_RESTOCKED,
  REFUND_REASONS,
  refundableTenders,
  summariseRefund,
  type RefundableSaleLine,
  type RefundLineRequest,
  type RefundReason,
  type TenderKind,
} from '@lumora/domain';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { OperatorPrompt } from '@/components/OperatorPrompt';
import { useOperator } from '@/lib/useOperator';

/**
 * The returns desk (M2-06 … M2-10) — everything behind F9.
 *
 * ## The receipt is the only way in
 *
 * The first screen asks for an invoice number and does nothing else. There is no path from here to
 * a refund without one, and the backend has none either: `refunds.sale_id` is NOT NULL and the only
 * code that writes it starts from this lookup. That is Gate M2's first half, and the screen and the
 * service agree about it because neither can express the alternative.
 *
 * ## The money is computed here, and capped there
 *
 * Amounts come from `@lumora/domain` — the cumulative apportionment that makes repeated partial
 * returns of one line sum back to exactly what a single full return would give. The backend does
 * not recompute them (a second implementation is the thing the architecture exists to prevent); it
 * checks them against what the line was charged and what earlier refunds already took. So a bug on
 * this side can produce a refund that is refused, never one that is quietly wrong.
 *
 * ## Tenders are not a choice
 *
 * `allocateRefundTenders` decides where the money goes, from what the sale actually took. The
 * cashier is shown the answer, not asked for it — a card sale refunded in cash is the oldest way to
 * empty a drawer with a receipt in your hand, and the safest UI for that rule is one with no
 * control to get wrong.
 */
type Step = 'LOOKUP' | 'LINES' | 'PIN';

/** What the backend's lookup returns. Structurally the domain's shape, with plain numbers. */
type LookupResponse = {
  saleId: number;
  invoiceNumber: string;
  soldAt: string;
  totalMinor: number;
  lines: {
    saleItemId: number;
    lineNo: number;
    productClientUuid: string;
    name: string;
    qty: number;
    unitPriceMinor: number;
    chargedMinor: number;
    taxMinor: number;
    taxMode: 'INCLUSIVE' | 'EXCLUSIVE';
    taxRateBp: number;
    alreadyRefundedQty: number;
    alreadyRefundedMinor: number;
  }[];
  tenders: {
    kind: TenderKind;
    paidMinor: number;
    alreadyRefundedMinor: number;
    refundableMinor: number;
  }[];
};

export type RefundOutcome = {
  creditNoteNumber: string;
  saleInvoiceNumber: string;
  totalMinor: number;
  taxMinor: number;
  roundingAdjustmentMinor: number;
  refundedAt: string;
  lines: { name: string; qty: number; refundTotalMinor: number; taxRateBp: number }[];
  tenders: { kind: TenderKind; amountMinor: number }[];
};

export function RefundOverlay({
  branchCode,
  terminalCode,
  onDone,
  onCancel,
}: {
  branchCode: string;
  terminalCode: string;
  onDone: (outcome: RefundOutcome) => void;
  onCancel: () => void;
}) {
  const [step, setStep] = useState<Step>('LOOKUP');
  const [invoiceBuffer, setInvoiceBuffer] = useState('');
  const [sale, setSale] = useState<LookupResponse | null>(null);
  const [selected, setSelected] = useState(0);
  const [qtyBuffer, setQtyBuffer] = useState('');
  const [picked, setPicked] = useState<Map<number, RefundLineRequest>>(new Map());
  // M3-08. A named user with AUTHORISE_REFUND, not a shop-wide PIN.
  const manager = useOperator();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // ------------------------------------------------------------------- domain shapes

  const domainLines = useMemo<RefundableSaleLine[]>(
    () =>
      (sale?.lines ?? []).map((line) => ({
        lineNo: line.lineNo,
        productClientUuid: line.productClientUuid,
        name: line.name,
        qty: line.qty,
        unitPriceMinor: line.unitPriceMinor,
        chargedMinor: line.chargedMinor,
        taxMinor: line.taxMinor,
        taxMode: line.taxMode,
        taxRateBp: line.taxRateBp,
        alreadyRefundedQty: line.alreadyRefundedQty,
      })),
    [sale],
  );

  /**
   * Recomputed from scratch on every keystroke rather than accumulated.
   *
   * A running total maintained by hand is a running total that drifts the first time a line is
   * removed, and this one ends up on a tax document.
   */
  const summary = useMemo(() => {
    if (picked.size === 0) return null;
    try {
      return summariseRefund(domainLines, [...picked.values()]);
    } catch (e) {
      return { error: e instanceof Error ? e.message : String(e) } as const;
    }
  }, [domainLines, picked]);

  const allocation = useMemo(() => {
    if (!sale || !summary || 'error' in summary) return null;
    try {
      const available = refundableTenders(
        sale.tenders.map((t) => ({ kind: t.kind, amountMinor: t.paidMinor })),
        // Already netted server-side, so nothing more to take off here — passing the change
        // again would subtract it twice.
        0,
        sale.tenders.map((t) => ({ kind: t.kind, amountMinor: t.alreadyRefundedMinor })),
      );
      return allocateRefundTenders(summary.totalMinor, available);
    } catch (e) {
      return { error: e instanceof Error ? e.message : String(e) } as const;
    }
  }, [sale, summary]);

  // ------------------------------------------------------------------------- actions

  const lookup = useCallback(async () => {
    if (busy || invoiceBuffer.trim() === '') return;
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(
        `/api/refunds/lookup?invoiceNumber=${encodeURIComponent(invoiceBuffer.trim())}`,
        { cache: 'no-store' },
      );
      const body = await response.json();
      if (!response.ok) throw new Error(body.detail ?? `HTTP ${response.status}`);
      setSale(body as LookupResponse);
      setSelected(0);
      setPicked(new Map());
      setStep('LINES');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [busy, invoiceBuffer]);

  const commit = useCallback(async () => {
    if (busy || !sale || !summary || 'error' in summary || !allocation || 'error' in allocation)
      return;
    setBusy(true);
    setError(null);
    try {
      const response = await fetch('/api/refunds', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          clientUuid: crypto.randomUUID(),
          branchCode,
          terminalCode,
          invoiceNumber: sale.invoiceNumber,
          managerCode: manager.code,
          managerPin: manager.pin,
          totalMinor: summary.totalMinor,
          taxMinor: summary.taxMinor,
          roundingAdjustmentMinor: allocation.roundingAdjustmentMinor,
          lines: summary.lines.map((line) => ({
            saleLineNo: line.saleLineNo,
            qty: line.qty,
            refundTotalMinor: line.refundTotalMinor,
            taxMinor: line.taxMinor,
            reasonCode: line.reasonCode,
            note: line.note ?? null,
            restock: line.restock,
          })),
          tenders: allocation.tenders.map((t) => ({ kind: t.kind, amountMinor: t.amountMinor })),
        }),
      });
      const body = await response.json();
      if (!response.ok) throw new Error(body.detail ?? `HTTP ${response.status}`);

      onDone({
        creditNoteNumber: body.creditNoteNumber,
        saleInvoiceNumber: body.saleInvoiceNumber,
        totalMinor: body.totalMinor,
        taxMinor: body.taxMinor,
        roundingAdjustmentMinor: body.roundingAdjustmentMinor,
        refundedAt: body.refundedAt,
        lines: summary.lines.map((line) => ({
          name: line.name,
          qty: line.qty,
          refundTotalMinor: line.refundTotalMinor,
          taxRateBp: line.taxRateBp,
        })),
        tenders: allocation.tenders.map((t) => ({ kind: t.kind, amountMinor: t.amountMinor })),
      });
    } catch (e) {
      // Back to an empty code and PIN with the return intact: a refusal must not cost the
      // cashier the whole basket they just entered. Both fields clear, not just the PIN — the
      // refusal does not say which half was wrong, so leaving the code standing would suggest it
      // was the right one.
      manager.reset();
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [allocation, branchCode, busy, manager, onDone, sale, summary, terminalCode]);

  const toggleLine = useCallback(() => {
    const line = sale?.lines[selected];
    if (!line) return;
    setError(null);
    setPicked((current) => {
      const next = new Map(current);
      if (next.has(line.lineNo)) {
        next.delete(line.lineNo);
        return next;
      }
      const remaining = line.qty - line.alreadyRefundedQty;
      if (remaining <= 0) {
        setError(`Line ${line.lineNo} has already been returned in full`);
        return next;
      }
      const qty = qtyBuffer === '' ? remaining : Math.min(Number(qtyBuffer), remaining);
      if (qty <= 0) return next;
      next.set(line.lineNo, {
        saleLineNo: line.lineNo,
        qty,
        reasonCode: 'CHANGED_MIND',
        restock: true,
      });
      return next;
    });
    setQtyBuffer('');
  }, [qtyBuffer, sale, selected]);

  /** Cycles the reason for the selected line, and moves `restock` with it (M2-10). */
  const cycleReason = useCallback(() => {
    const line = sale?.lines[selected];
    if (!line) return;
    setPicked((current) => {
      const entry = current.get(line.lineNo);
      if (!entry) return current;
      const i = REFUND_REASONS.indexOf(entry.reasonCode);
      const reason = REFUND_REASONS[(i + 1) % REFUND_REASONS.length]! as RefundReason;
      const next = new Map(current);
      next.set(line.lineNo, {
        ...entry,
        reasonCode: reason,
        // A default the cashier can override, never a rule: a "damaged" item that turned out to
        // be fine is a judgement the person holding it makes.
        restock: !REASONS_NOT_NORMALLY_RESTOCKED.includes(reason),
      });
      return next;
    });
  }, [sale, selected]);

  const toggleRestock = useCallback(() => {
    const line = sale?.lines[selected];
    if (!line) return;
    setPicked((current) => {
      const entry = current.get(line.lineNo);
      if (!entry) return current;
      const next = new Map(current);
      next.set(line.lineNo, { ...entry, restock: !entry.restock });
      return next;
    });
  }, [sale, selected]);

  // ------------------------------------------------------------------------ keyboard

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault();
        if (step === 'PIN') {
          manager.reset();
          setStep('LINES');
        } else if (step === 'LINES') {
          setStep('LOOKUP');
          setSale(null);
        } else {
          onCancel();
        }
        return;
      }

      if (step === 'LOOKUP') {
        // A barcode gun works here too: an invoice number is characters then Enter, which is
        // exactly what a scanner sends.
        if (event.key.length === 1 && !event.ctrlKey && !event.altKey && !event.metaKey) {
          event.preventDefault();
          setInvoiceBuffer((current) => (current.length >= 32 ? current : current + event.key));
        } else if (event.key === 'Backspace') {
          event.preventDefault();
          setInvoiceBuffer((current) => current.slice(0, -1));
        } else if (event.key === 'Enter') {
          event.preventDefault();
          void lookup();
        }
        return;
      }

      if (step === 'LINES') {
        if (event.key >= '0' && event.key <= '9' && event.key.length === 1) {
          event.preventDefault();
          setQtyBuffer((current) => (current.length >= 3 ? current : current + event.key));
          return;
        }
        switch (event.key) {
          case 'ArrowUp':
            event.preventDefault();
            setSelected((i) => Math.max(0, i - 1));
            setQtyBuffer('');
            return;
          case 'ArrowDown':
            event.preventDefault();
            setSelected((i) => Math.min((sale?.lines.length ?? 1) - 1, i + 1));
            setQtyBuffer('');
            return;
          case 'Enter':
            event.preventDefault();
            toggleLine();
            return;
          case 'r':
          case 'R':
            event.preventDefault();
            cycleReason();
            return;
          case 's':
          case 'S':
            event.preventDefault();
            toggleRestock();
            return;
          case 'Backspace':
            event.preventDefault();
            setQtyBuffer((current) => current.slice(0, -1));
            return;
          case 'F12':
            event.preventDefault();
            if (summary && !('error' in summary) && allocation && !('error' in allocation)) {
              setError(null);
              setStep('PIN');
            }
            return;
          default:
            return;
        }
      }

      if (step === 'PIN') {
        // Enter is not the hook's to take: on the code field it means "now the PIN", and only on
        // the PIN does it mean "authorise". F12 always submits, matching tender.
        if (event.key === 'Enter' && manager.field === 'CODE') {
          event.preventDefault();
          manager.advance();
          return;
        }
        if (event.key === 'Enter' || event.key === 'F12') {
          event.preventDefault();
          if (manager.ready) void commit();
          return;
        }
        manager.onKey(event);
      }
    }
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [
    allocation,
    commit,
    cycleReason,
    lookup,
    manager,
    onCancel,
    sale,
    step,
    summary,
    toggleLine,
    toggleRestock,
  ]);

  // -------------------------------------------------------------------------- render

  return (
    <div className="bg-page/90 absolute inset-0 flex items-center justify-center p-8">
      <div className="border-hair bg-surface flex max-h-full w-full max-w-2xl flex-col gap-4 overflow-y-auto rounded-lg border p-6">
        <header className="flex items-baseline justify-between">
          <h2 className="text-ink-3 text-xs uppercase tracking-wider">Return</h2>
          {sale && <span className="lum-money text-ink-3 text-sm">{sale.invoiceNumber}</span>}
        </header>

        {step === 'LOOKUP' && (
          <>
            <p className="text-ink-3 text-sm">
              Type or scan the invoice number from the customer&rsquo;s receipt. A return needs the
              receipt it came from.
            </p>
            <div className="border-hair rounded border p-4">
              <div className={`lum-money text-3xl ${invoiceBuffer ? 'text-ink' : 'text-ink-3'}`}>
                {invoiceBuffer || 'KND-T1-000000'}
              </div>
            </div>
          </>
        )}

        {step === 'LINES' && sale && (
          <>
            <ul className="flex flex-col gap-1" aria-label="Sale lines">
              {sale.lines.map((line, index) => {
                const entry = picked.get(line.lineNo);
                const remaining = line.qty - line.alreadyRefundedQty;
                return (
                  <li
                    key={line.lineNo}
                    aria-current={index === selected}
                    className={`flex items-center gap-3 rounded border px-3 py-2 text-sm ${
                      index === selected ? 'border-accent' : 'border-hair'
                    } ${remaining === 0 ? 'opacity-50' : ''}`}
                  >
                    <span className={`flex-1 ${entry ? 'text-ink' : 'text-ink-2'}`}>
                      {line.name}
                    </span>
                    <span className="lum-money text-ink-3 w-24 text-right">
                      {remaining} of {line.qty} left
                    </span>
                    {entry ? (
                      <>
                        <span className="text-accent w-16 text-right font-semibold">
                          ×{entry.qty}
                        </span>
                        <span className="text-ink-3 w-32 text-right text-xs">
                          {entry.reasonCode.replace(/_/g, ' ').toLowerCase()}
                        </span>
                        <span
                          className={`w-20 text-right text-xs ${entry.restock ? 'text-ok' : 'text-danger'}`}
                        >
                          {entry.restock ? 'restock' : 'no restock'}
                        </span>
                      </>
                    ) : (
                      <span className="text-ink-3 w-[17rem] text-right text-xs">
                        {index === selected && qtyBuffer ? `qty ${qtyBuffer} — Enter` : ''}
                      </span>
                    )}
                  </li>
                );
              })}
            </ul>

            {summary && 'error' in summary && (
              <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
                {summary.error}
              </p>
            )}

            {summary && !('error' in summary) && (
              <div className="border-hair flex flex-col gap-1 border-t pt-3">
                <div className="flex items-baseline justify-between">
                  <span className="text-ink-3 text-sm">Refund</span>
                  <span className="lum-money text-ink text-3xl font-semibold">
                    {formatMinor(summary.totalMinor)}
                  </span>
                </div>
                {allocation && 'error' in allocation ? (
                  <p
                    role="alert"
                    className="border-danger text-danger border-l-2 px-3 py-2 text-sm"
                  >
                    {allocation.error}
                  </p>
                ) : (
                  allocation?.tenders.map((tender) => (
                    <div key={tender.kind} className="flex items-baseline justify-between text-sm">
                      <span className="text-ink-3">back to {tender.kind.toLowerCase()}</span>
                      <span className="lum-money text-ink-2">
                        {formatMinor(tender.amountMinor)}
                      </span>
                    </div>
                  ))
                )}
                {allocation &&
                  !('error' in allocation) &&
                  allocation.roundingAdjustmentMinor !== 0 && (
                    <div className="flex items-baseline justify-between text-xs">
                      <span className="text-ink-3">cash rounding</span>
                      <span className="lum-money text-ink-3">
                        {formatMinor(allocation.roundingAdjustmentMinor)}
                      </span>
                    </div>
                  )}
              </div>
            )}
          </>
        )}

        {step === 'PIN' && (
          <>
            <OperatorPrompt
              operator={manager}
              label="A supervisor or manager must authorise this refund."
            />
            {summary && !('error' in summary) && (
              <div className="flex items-baseline justify-between">
                <span className="text-ink-3 text-sm">Refunding</span>
                <span className="lum-money text-ink text-2xl font-semibold">
                  {formatMinor(summary.totalMinor)}
                </span>
              </div>
            )}
          </>
        )}

        {error && (
          <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
            {error}
          </p>
        )}

        <footer className="border-hair text-ink-3 flex flex-wrap justify-between gap-x-3 gap-y-1 border-t pt-3 text-xs">
          {step === 'LOOKUP' && (
            <>
              <span>type or scan the invoice</span>
              <span className="text-accent font-semibold">
                Enter {busy ? 'looking…' : 'find sale'}
              </span>
              <span>Esc cancel</span>
            </>
          )}
          {step === 'LINES' && (
            <>
              <span>↑↓ line</span>
              <span>digits qty</span>
              <span>Enter add/remove</span>
              <span>R reason</span>
              <span>S restock</span>
              <span
                className={
                  summary && !('error' in summary) ? 'text-accent font-semibold' : 'opacity-50'
                }
              >
                F12 refund
              </span>
              <span>Esc back</span>
            </>
          )}
          {step === 'PIN' && (
            <>
              <span>user code, then PIN</span>
              <span>Tab switch</span>
              <span className="text-accent font-semibold">
                Enter {busy ? 'working…' : 'authorise'}
              </span>
              <span>Esc back</span>
            </>
          )}
        </footer>
      </div>
    </div>
  );
}
