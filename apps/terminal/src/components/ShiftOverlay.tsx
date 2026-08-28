'use client';

import {
  CASH_MOVEMENT_REASONS,
  countedTotalMinor,
  formatMinor,
  signedCashMovementMinor,
  VARIANCE_REASONS,
  type CashMovementKind,
  type CashMovementReason,
  type VarianceReason,
} from '@lumora/domain';
import { useCallback, useEffect, useState } from 'react';

import {
  DenominationCounter,
  useDenominationKeys,
  type DenominationCount,
} from '@/components/DenominationCounter';
import { OperatorPrompt } from '@/components/OperatorPrompt';
import type { ShiftStatus } from '@/lib/useShift';
import { useOperator } from '@/lib/useOperator';

/**
 * Cash up (M2-01 … M2-05, M2-11) — everything behind F10.
 *
 * ## One overlay, four screens
 *
 * `OPEN` when no shift is running, and otherwise a menu leading to `MOVEMENT` or `CLOSE`. They
 * share a shell because they share a keyboard: this is a modal, the caller has gated the till's
 * global F-keys off, and having one listener rather than four is what keeps Escape and F12 meaning
 * the same thing on every screen.
 *
 * ## Signing is its own screen, not a field on the count
 *
 * `SIGN` follows both `OPEN` and `CLOSE` (M3-08). It is separate for a mechanical reason and a
 * better one. Mechanically, the denomination counter owns the digit keys, so a PIN field sharing
 * that screen would be typing into the notes column. The better reason is that it puts the name
 * after the count rather than beside it: whoever signs is signing for a figure that is already
 * fixed, which is the same argument the blind count itself rests on.
 *
 * ## The close screen is where the milestone lives
 *
 * It takes the count and submits it. It never asks the backend what the drawer should hold first —
 * there is no endpoint that would answer, and that is deliberate (M2-02). The expected figure and
 * the variance arrive in the response, after the count is already fixed and beyond changing.
 *
 * When the variance is over the shop's threshold the backend refuses and says so, and the screen
 * comes back asking for a reason with the count intact. That second round trip is the cost of the
 * count being blind, and it is worth paying: the alternative is telling the counter the target
 * before they count.
 */
type Screen = 'OPEN' | 'MENU' | 'MOVEMENT' | 'CLOSE' | 'SIGN';

const MOVEMENT_KINDS: readonly { kind: CashMovementKind; label: string }[] = [
  { kind: 'DROP', label: 'Drop to safe' },
  { kind: 'PAY_OUT', label: 'Pay out' },
  { kind: 'PAY_IN', label: 'Pay in' },
];

const REASON_LABEL: Record<string, string> = {
  BANK_DROP: 'Bank drop',
  SAFE_DROP: 'Safe drop',
  SUPPLIER_PAYMENT: 'Supplier payment',
  PETTY_CASH: 'Petty cash',
  CHANGE_FLOAT: 'Change float',
  OWNER_DRAW: 'Owner draw',
  MISCOUNT: 'Miscount',
  FLOAT_ERROR: 'Float was wrong',
  UNRECORDED_PAYOUT: 'Unrecorded pay-out',
  CHANGE_GIVEN_WRONG: 'Change given wrong',
  THEFT_SUSPECTED: 'Theft suspected',
  OTHER: 'Other',
};

export type ClosedShift = {
  id: number;
  countedCashMinor: number;
  expectedCashMinor: number;
  varianceMinor: number;
  varianceReason: string | null;
};

export function ShiftOverlay({
  status,
  branchCode,
  terminalCode,
  onDone,
  onClosed,
  onCancel,
}: {
  status: ShiftStatus | null;
  branchCode: string;
  terminalCode: string;
  /** Refetch the shift status — the till's ability to trade may have just changed. */
  onDone: () => void;
  /** A shift closed: the caller prints the Z-report. */
  onClosed: (shift: ClosedShift) => void;
  onCancel: () => void;
}) {
  const [screen, setScreen] = useState<Screen>(status?.open ? 'MENU' : 'OPEN');
  const [counts, setCounts] = useState<DenominationCount[]>([]);
  const [selected, setSelected] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Close-screen state. Only ever set after the backend has refused a close for want of a reason
  // — never before, because filling it in first would mean the screen knew the variance.
  const [varianceReason, setVarianceReason] = useState<VarianceReason | null>(null);
  const [reasonRequired, setReasonRequired] = useState(false);

  // Movement-screen state.
  const [movementKind, setMovementKind] = useState<CashMovementKind>('DROP');
  const [movementReason, setMovementReason] = useState<CashMovementReason>('SAFE_DROP');
  const [amountBuffer, setAmountBuffer] = useState('');

  const denominationKeys = useDenominationKeys(counts, setCounts, selected, setSelected);

  // M3-08. Who is answerable for this drawer. `signing` remembers which action the SIGN
  // screen is about to perform, so one screen serves both open and close.
  const operator = useOperator();
  const [signing, setSigning] = useState<'OPEN' | 'CLOSE'>('OPEN');

  const goTo = useCallback((next: Screen) => {
    setScreen(next);
    setCounts([]);
    setSelected(0);
    setAmountBuffer('');
    setVarianceReason(null);
    setReasonRequired(false);
    setError(null);
  }, []);

  // ------------------------------------------------------------------------- actions

  const openShift = useCallback(async () => {
    if (busy || counts.length === 0) return;
    setBusy(true);
    setError(null);
    try {
      const response = await fetch('/api/shifts', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          clientUuid: crypto.randomUUID(),
          branchCode,
          terminalCode,
          operatorCode: operator.code,
          operatorPin: operator.pin,
          openingFloatMinor: countedTotalMinor(counts),
          openingCount: counts,
        }),
      });
      const body = await response.json();
      if (!response.ok) throw new Error(body.detail ?? `HTTP ${response.status}`);
      onDone();
      onCancel();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [branchCode, busy, counts, onCancel, onDone, operator, terminalCode]);

  const closeShift = useCallback(async () => {
    if (busy || counts.length === 0 || !status?.shiftId) return;
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(`/api/shifts/${status.shiftId}/close`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          operatorCode: operator.code,
          operatorPin: operator.pin,
          countedCashMinor: countedTotalMinor(counts),
          closingCount: counts,
          varianceReason,
          // The reason picker is the whole note in v1. A free-text field on a till keyboard is
          // a field nobody fills in, and "OTHER with nothing said" is what M2-04 exists to stop
          // — so OTHER is not offered here at all until there is somewhere sensible to type.
          varianceNote: null,
        }),
      });
      const body = await response.json();
      if (!response.ok) {
        if (typeof body.detail === 'string' && body.detail.includes('reason code is required')) {
          // The blind count's second round trip. The count stays exactly as entered.
          setReasonRequired(true);
          setError('The drawer is out by more than this shop allows. Pick a reason to close.');
          return;
        }
        throw new Error(body.detail ?? `HTTP ${response.status}`);
      }
      onDone();
      onClosed(body as ClosedShift);
      onCancel();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [busy, counts, onCancel, onClosed, onDone, operator, status, varianceReason]);

  const recordMovement = useCallback(async () => {
    const amountMinor = amountBuffer === '' ? 0 : Number(amountBuffer);
    if (busy || amountMinor <= 0) return;
    setBusy(true);
    setError(null);
    try {
      const response = await fetch('/api/cash-movements', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          clientUuid: crypto.randomUUID(),
          branchCode,
          terminalCode,
          kind: movementKind,
          // Always the magnitude. The backend applies the sign from the kind, which is the same
          // rule signedCashMovementMinor states on this side — shown to the cashier below so
          // they can see the effect before committing, never sent.
          amountMinor,
          reasonCode: movementReason,
          note: null,
        }),
      });
      const body = await response.json();
      if (!response.ok) throw new Error(body.detail ?? `HTTP ${response.status}`);
      onDone();
      goTo('MENU');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [amountBuffer, branchCode, busy, goTo, movementKind, movementReason, onDone, terminalCode]);

  // ------------------------------------------------------------------------ keyboard

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault();
        if (screen === 'SIGN') {
          // Back to the count exactly as it was. A mistyped code must not cost somebody a
          // drawer they have already counted note by note.
          operator.reset();
          setScreen(signing === 'OPEN' ? 'OPEN' : 'CLOSE');
        } else if (screen === 'MOVEMENT' || (screen === 'CLOSE' && status?.open)) {
          goTo('MENU');
        } else {
          onCancel();
        }
        return;
      }

      if (screen === 'MENU') {
        if (event.key === '1') {
          event.preventDefault();
          goTo('MOVEMENT');
        } else if (event.key === '2') {
          event.preventDefault();
          goTo('CLOSE');
        }
        return;
      }

      if (screen === 'OPEN' || screen === 'CLOSE') {
        if (event.key === 'F12') {
          event.preventDefault();
          if (counts.length === 0) return;
          // The count is fixed from here. Nothing on the SIGN screen can change it, which is
          // what makes signing mean anything.
          setSigning(screen === 'OPEN' ? 'OPEN' : 'CLOSE');
          setError(null);
          operator.reset();
          setScreen('SIGN');
          return;
        }
        // Only once the backend has asked for one. Before that there is nothing to attribute,
        // because nobody on this side knows there is a variance.
        if (reasonRequired && event.key >= '1' && event.key <= '9') {
          const index = Number(event.key) - 1;
          const choices = VARIANCE_REASONS.filter((r) => r !== 'OTHER');
          if (index < choices.length) {
            event.preventDefault();
            setVarianceReason(choices[index]!);
            return;
          }
        }
        if (denominationKeys(event)) event.preventDefault();
        return;
      }

      if (screen === 'SIGN') {
        if (event.key === 'Enter' && operator.field === 'CODE') {
          event.preventDefault();
          operator.advance();
          return;
        }
        if (event.key === 'Enter' || event.key === 'F12') {
          event.preventDefault();
          if (operator.ready) void (signing === 'OPEN' ? openShift() : closeShift());
          return;
        }
        operator.onKey(event);
        return;
      }

      if (screen === 'MOVEMENT') {
        if (event.key >= '0' && event.key <= '9' && event.key.length === 1) {
          event.preventDefault();
          setAmountBuffer((current) => (current.length >= 9 ? current : current + event.key));
          return;
        }
        switch (event.key) {
          case 'Backspace':
            event.preventDefault();
            setAmountBuffer((current) => current.slice(0, -1));
            return;
          case 'Tab': {
            event.preventDefault();
            const i = MOVEMENT_KINDS.findIndex((k) => k.kind === movementKind);
            setMovementKind(MOVEMENT_KINDS[(i + 1) % MOVEMENT_KINDS.length]!.kind);
            return;
          }
          case 'r':
          case 'R': {
            event.preventDefault();
            const i = CASH_MOVEMENT_REASONS.indexOf(movementReason);
            setMovementReason(CASH_MOVEMENT_REASONS[(i + 1) % CASH_MOVEMENT_REASONS.length]!);
            return;
          }
          case 'F12':
            event.preventDefault();
            void recordMovement();
            return;
          default:
            return;
        }
      }
    }
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [
    closeShift,
    counts,
    denominationKeys,
    goTo,
    movementKind,
    movementReason,
    onCancel,
    openShift,
    operator,
    reasonRequired,
    recordMovement,
    screen,
    signing,
    status,
  ]);

  // -------------------------------------------------------------------------- render

  return (
    <div className="bg-page/90 absolute inset-0 flex items-center justify-center p-8">
      <div className="border-hair bg-surface flex max-h-full w-full max-w-lg flex-col gap-4 overflow-y-auto rounded-lg border p-6">
        {screen === 'MENU' && <Menu status={status} />}

        {(screen === 'OPEN' || screen === 'CLOSE') && (
          <>
            <header className="flex items-baseline justify-between">
              <h2 className="text-ink-3 text-xs uppercase tracking-wider">
                {screen === 'OPEN'
                  ? 'Open shift — count the float'
                  : 'Close shift — count the drawer'}
              </h2>
            </header>
            <DenominationCounter
              counts={counts}
              selected={selected}
              label={screen === 'OPEN' ? 'Opening float' : 'Closing count'}
            />
            {reasonRequired && (
              <div className="flex flex-col gap-2">
                <h3 className="text-ink-3 text-xs uppercase tracking-wider">
                  Why is the drawer out? Press its number.
                </h3>
                <ul className="flex flex-col gap-1">
                  {VARIANCE_REASONS.filter((r) => r !== 'OTHER').map((reason, index) => (
                    <li
                      key={reason}
                      className={`flex items-center gap-3 rounded border px-3 py-1 text-sm ${
                        reason === varianceReason
                          ? 'border-accent text-accent'
                          : 'border-hair text-ink-2'
                      }`}
                    >
                      <span className="text-accent font-semibold">{index + 1}</span>
                      {REASON_LABEL[reason] ?? reason}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </>
        )}

        {screen === 'SIGN' && (
          <>
            <header>
              <h2 className="text-ink-3 text-xs uppercase tracking-wider">
                {signing === 'OPEN'
                  ? 'Open shift — who is on the till?'
                  : 'Close shift — sign the count'}
              </h2>
            </header>
            <OperatorPrompt
              operator={operator}
              label={
                signing === 'OPEN'
                  ? 'Every sale on this shift is recorded against you.'
                  : 'You are signing for the count you just entered.'
              }
            />
            {/*
              The counted total, and deliberately nothing to compare it against. On a close this
              screen is one keypress from submitting, and showing the expected figure here would
              undo M2-02 at the last possible moment.
            */}
            <div className="flex items-baseline justify-between">
              <span className="text-ink-3 text-sm">
                {signing === 'OPEN' ? 'Opening float' : 'Counted'}
              </span>
              <span className="lum-money text-ink text-2xl font-semibold">
                {formatMinor(countedTotalMinor(counts))}
              </span>
            </div>
          </>
        )}

        {screen === 'MOVEMENT' && (
          <Movement kind={movementKind} reason={movementReason} amountBuffer={amountBuffer} />
        )}

        {error && (
          <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
            {error}
          </p>
        )}

        <footer className="border-hair text-ink-3 flex flex-wrap justify-between gap-x-3 gap-y-1 border-t pt-3 text-xs">
          {screen === 'MENU' && (
            <>
              <span>1 Cash in/out</span>
              <span>2 Close shift</span>
              <span>Esc back</span>
            </>
          )}
          {(screen === 'OPEN' || screen === 'CLOSE') && (
            <>
              <span>↑↓ row</span>
              <span>digits count</span>
              <span>Enter next</span>
              <span className={counts.length > 0 ? 'text-accent font-semibold' : ''}>
                F12 sign &amp; {screen === 'OPEN' ? 'open' : 'close'}
              </span>
              <span>Esc back</span>
            </>
          )}
          {screen === 'SIGN' && (
            <>
              <span>user code, then PIN</span>
              <span>Tab switch</span>
              <span className={operator.ready ? 'text-accent font-semibold' : ''}>
                Enter {busy ? 'working…' : signing === 'OPEN' ? 'open shift' : 'close shift'}
              </span>
              <span>Esc back to the count</span>
            </>
          )}
          {screen === 'MOVEMENT' && (
            <>
              <span>Tab kind</span>
              <span>R reason</span>
              <span>digits amount</span>
              <span className="text-accent font-semibold">F12 {busy ? 'working…' : 'record'}</span>
              <span>Esc back</span>
            </>
          )}
        </footer>
      </div>
    </div>
  );
}

function Menu({ status }: { status: ShiftStatus | null }) {
  return (
    <>
      <header>
        <h2 className="text-ink-3 text-xs uppercase tracking-wider">Cash up</h2>
      </header>
      <dl className="flex flex-col gap-1 text-sm">
        <Row label="Opened" value={status?.openedAt ? formatTime(status.openedAt) : '—'} />
        <Row
          label="Float"
          value={
            status?.openingFloatMinor !== null && status?.openingFloatMinor !== undefined
              ? formatMinor(status.openingFloatMinor)
              : '—'
          }
        />
        <Row label="Sales" value={String(status?.saleCount ?? 0)} />
        <Row label="Cash movements" value={String(status?.cashMovementCount ?? 0)} />
      </dl>
      {/*
        Note what is not here: what the drawer should hold. The endpoint behind this screen has
        no such field (M2-02), so the count on the next screen is blind whatever this renders.
      */}
      <ul className="flex flex-col gap-1">
        <MenuItem number={1} label="Cash in / out / drop" />
        <MenuItem number={2} label="Close shift and count the drawer" />
      </ul>
    </>
  );
}

function MenuItem({ number, label }: { number: number; label: string }) {
  return (
    <li className="border-hair min-h-touch flex items-center gap-3 rounded border px-4 text-sm">
      <span className="text-accent font-semibold">{number}</span>
      {label}
    </li>
  );
}

function Movement({
  kind,
  reason,
  amountBuffer,
}: {
  kind: CashMovementKind;
  reason: CashMovementReason;
  amountBuffer: string;
}) {
  const amountMinor = amountBuffer === '' ? 0 : Number(amountBuffer);
  // Shown, not sent. The cashier types how much; this is the effect on the drawer that the
  // backend will record, so the sign is never something they have to get right themselves.
  const signedMinor = amountMinor > 0 ? signedCashMovementMinor(kind, amountMinor) : 0;

  return (
    <>
      <header>
        <h2 className="text-ink-3 text-xs uppercase tracking-wider">Cash in / out</h2>
      </header>

      <div className="flex gap-2" role="group" aria-label="Movement kind">
        {MOVEMENT_KINDS.map((k) => (
          <span
            key={k.kind}
            className={`min-h-touch flex flex-1 items-center justify-center rounded border px-2 text-center text-sm ${
              k.kind === kind ? 'border-accent text-accent' : 'border-hair text-ink-3'
            }`}
          >
            {k.label}
          </span>
        ))}
      </div>

      <div className="border-hair rounded border p-4 text-right">
        <div
          className={`lum-money text-4xl font-semibold ${amountMinor > 0 ? 'text-ink' : 'text-ink-3'}`}
        >
          {formatMinor(amountMinor)}
        </div>
        <p className={`mt-1 text-xs ${signedMinor < 0 ? 'text-danger' : 'text-ok'}`}>
          {signedMinor === 0
            ? 'Type the amount'
            : `Drawer ${signedMinor > 0 ? 'gains' : 'loses'} ${formatMinor(Math.abs(signedMinor))}`}
        </p>
      </div>

      <div className="flex items-baseline justify-between text-sm">
        <span className="text-ink-3">Reason</span>
        <span className="text-ink-2">{REASON_LABEL[reason] ?? reason}</span>
      </div>
    </>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between">
      <dt className="text-ink-3">{label}</dt>
      <dd className="lum-money text-ink-2">{value}</dd>
    </div>
  );
}

function formatTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : new Intl.DateTimeFormat('en-GB', {
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      }).format(date);
}
