'use client';

import {
  formatMinor,
  minor,
  suggestedTenderAmountMinor,
  summariseTender,
  type TenderKind,
  type TenderLine,
} from '@lumora/domain';
import { useCallback, useEffect, useMemo, useState } from 'react';

/**
 * The tender overlay (M1-11): multi-tender, split payment, change.
 *
 * Modal, like the item picker it borrows its layout from — while it is open, the till's
 * global F-keys are gated off by the caller so this screen's own listener is the only one
 * that answers to them. `Enter` and `F12` deliberately do different things here: Enter tenders
 * the amount on screen as a line, F12 completes the sale. That mirrors F12's meaning
 * everywhere else in the app (commit) rather than inventing a second "the big button" key.
 *
 * STORE_CREDIT is a real `TenderKind` in `@lumora/domain` but is not offered here — there is
 * no customer or credit-balance concept anywhere in the app yet. It arrives with that feature,
 * not before it.
 */
const TENDER_KINDS: readonly { kind: TenderKind; label: string }[] = [
  { kind: 'CASH', label: 'Cash' },
  { kind: 'CARD', label: 'Card' },
  { kind: 'WALLET', label: 'Wallet' },
];

/** Digits build the amount from the right, like a calculator — no decimal key needed. */
const MAX_BUFFER_DIGITS = 9;

export type TenderOutcome = {
  tenders: readonly TenderLine[];
  roundingAdjustmentMinor: number;
  changeMinor: number;
};

export function TenderOverlay({
  totalDueMinor,
  busy,
  onCancel,
  onConfirm,
}: {
  totalDueMinor: number;
  busy: boolean;
  onCancel: () => void;
  onConfirm: (outcome: TenderOutcome) => void;
}) {
  const [tenders, setTenders] = useState<TenderLine[]>([]);
  const [kind, setKind] = useState<TenderKind>('CASH');
  const [buffer, setBuffer] = useState('');
  const [error, setError] = useState<string | null>(null);

  const summary = useMemo(() => summariseTender(totalDueMinor, tenders), [totalDueMinor, tenders]);

  const suggestedMinor = useMemo(
    () => suggestedTenderAmountMinor(totalDueMinor, tenders, kind),
    [totalDueMinor, tenders, kind],
  );
  const enteredMinor = buffer === '' ? suggestedMinor : minor(Number(buffer));

  const cycleKind = useCallback(() => {
    setError(null);
    setBuffer('');
    setKind((current) => {
      const i = TENDER_KINDS.findIndex((t) => t.kind === current);
      return TENDER_KINDS[(i + 1) % TENDER_KINDS.length]!.kind;
    });
  }, []);

  const addDigit = useCallback((digit: string) => {
    setError(null);
    setBuffer((current) => (current.length >= MAX_BUFFER_DIGITS ? current : current + digit));
  }, []);

  /** Backspace clears a typed digit, or — once the buffer is empty — undoes the last line. */
  const backspace = useCallback(() => {
    setError(null);
    setBuffer((current) => {
      if (current.length > 0) return current.slice(0, -1);
      setTenders((lines) => lines.slice(0, -1));
      return current;
    });
  }, []);

  const commitLine = useCallback(() => {
    if (enteredMinor <= 0 || busy) return;
    const next: TenderLine = { kind, amountMinor: enteredMinor };
    try {
      // The fold is the validation: summariseTender throws on a split that cannot happen
      // (e.g. a card line larger than what is left owed) instead of silently clamping it.
      summariseTender(totalDueMinor, [...tenders, next]);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      return;
    }
    setError(null);
    setTenders((current) => [...current, next]);
    setBuffer('');
  }, [busy, enteredMinor, kind, tenders, totalDueMinor]);

  const complete = useCallback(() => {
    if (!summary.settled || busy) return;
    onConfirm({
      tenders,
      roundingAdjustmentMinor: summary.roundingAdjustmentMinor,
      changeMinor: summary.changeDueMinor,
    });
  }, [busy, onConfirm, summary, tenders]);

  // Local, and only while this overlay is mounted — the caller gates the till's global
  // F-keys off so this is the only listener answering while tendering is on screen.
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key.length === 1 && event.key >= '0' && event.key <= '9') {
        event.preventDefault();
        addDigit(event.key);
        return;
      }
      switch (event.key) {
        case 'Backspace':
          event.preventDefault();
          backspace();
          return;
        case 'Tab':
          event.preventDefault();
          cycleKind();
          return;
        case 'Enter':
          event.preventDefault();
          commitLine();
          return;
        case 'F12':
          event.preventDefault();
          complete();
          return;
        case 'Escape':
          event.preventDefault();
          onCancel();
          return;
        default:
          return;
      }
    }
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [addDigit, backspace, commitLine, complete, cycleKind, onCancel]);

  return (
    <div className="bg-page/90 absolute inset-0 flex items-center justify-center p-8">
      <div className="border-hair bg-surface flex w-full max-w-lg flex-col gap-4 rounded-lg border p-6">
        <header className="flex items-baseline justify-between">
          <h2 className="text-ink-3 text-xs uppercase tracking-wider">Tender</h2>
          <span className="lum-money text-ink-3 text-sm">Due {formatMinor(totalDueMinor)}</span>
        </header>

        {tenders.length > 0 && (
          <ul className="flex flex-col gap-1" aria-label="Tender lines entered so far">
            {tenders.map((line, index) => (
              <li key={index} className="flex items-center justify-between text-sm">
                <span className="text-ink-2">{labelFor(line.kind)}</span>
                <span className="lum-money text-ink-2">{formatMinor(line.amountMinor)}</span>
              </li>
            ))}
          </ul>
        )}

        <div className="flex gap-2" role="group" aria-label="Tender kind">
          {TENDER_KINDS.map((t) => (
            <span
              key={t.kind}
              className={`min-h-touch flex flex-1 items-center justify-center rounded border px-2 text-sm ${
                t.kind === kind ? 'border-accent text-accent' : 'border-hair text-ink-3'
              }`}
            >
              {t.label}
            </span>
          ))}
        </div>

        <div className="border-hair rounded border p-4 text-right">
          <div
            className={`lum-money text-4xl font-semibold ${buffer === '' ? 'text-ink-3' : 'text-ink'}`}
          >
            {formatMinor(enteredMinor)}
          </div>
          {buffer === '' && (
            <p className="text-ink-3 mt-1 text-xs">Suggested — press Enter to tender this amount</p>
          )}
        </div>

        {error && (
          <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
            {error}
          </p>
        )}

        <div className="flex items-baseline justify-between">
          {summary.settled ? (
            summary.changeDueMinor > 0 ? (
              <>
                <span className="text-ok text-sm">Change due</span>
                <span className="lum-money text-ok text-3xl font-semibold">
                  {formatMinor(summary.changeDueMinor)}
                </span>
              </>
            ) : (
              <span className="text-ok text-sm">Fully tendered</span>
            )
          ) : (
            <>
              <span className="text-ink-3 text-sm">Remaining</span>
              <span className="lum-money text-ink text-3xl font-semibold">
                {formatMinor(summary.remainingDueMinor)}
              </span>
            </>
          )}
        </div>

        <footer className="border-hair text-ink-3 flex flex-wrap justify-between gap-x-3 gap-y-1 border-t pt-3 text-xs">
          <span>Tab kind</span>
          <span>Enter tender</span>
          <span>Backspace undo</span>
          <span className={summary.settled ? 'text-accent font-semibold' : ''}>
            F12 {busy ? 'working…' : 'complete'}
          </span>
          <span>Esc cancel</span>
        </footer>
      </div>
    </div>
  );
}

function labelFor(kind: TenderKind): string {
  return TENDER_KINDS.find((t) => t.kind === kind)?.label ?? kind;
}
