'use client';

import { useEffect } from 'react';

/**
 * The key map (M6, filling F1 — the first slot the bar has carried unassigned since M1-07).
 *
 * It exists because of the touch layer, not despite it. Once every action has a button, the
 * keyboard shortcuts stop being the only way to do things and start being the *fast* way —
 * and a fast way nobody knows about is worth nothing. A cashier who has been tapping `PAY`
 * for a week can look here once and start pressing F12 instead.
 *
 * The rows are the function bar in its own order, deliberately: the bar is the thing a
 * cashier looks at, so reading down this list matches reading along the bottom of the screen.
 * Unassigned keys are listed as unassigned rather than omitted, because "F5 does nothing yet"
 * is the answer to the question somebody opened this to ask.
 */
const KEYS: readonly { key: string; what: string }[] = [
  { key: 'F1', what: 'This list' },
  { key: 'F2', what: 'Add one to the selected line' },
  { key: 'F3', what: 'Find an item by name' },
  { key: 'F4', what: 'Void the selected line' },
  { key: 'F5', what: 'Not assigned yet' },
  { key: 'F6', what: 'Attach a customer to this sale' },
  { key: 'F7', what: 'Not assigned yet' },
  { key: 'F8', what: 'Clear the whole cart' },
  { key: 'F9', what: 'Return against a receipt' },
  { key: 'F10', what: 'Cash up, or open a shift' },
  { key: 'F11', what: 'Not assigned yet' },
  { key: 'F12', what: 'Take payment' },
];

const OTHER: readonly { key: string; what: string }[] = [
  { key: '↑ ↓', what: 'Move between cart lines — or tap one' },
  { key: '+ −', what: 'Change the quantity of the selected line' },
  { key: 'Ctrl+B', what: 'Back office' },
  { key: 'Ctrl+I', what: 'Issue a tax invoice for the last sale' },
  { key: 'Esc', what: 'Close whatever is open' },
];

export function HelpOverlay({ onClose }: { onClose: () => void }) {
  // Its own listener, capture phase, like every other overlay: the parent gates the global
  // keys off while this is open, so nothing else is left to answer Escape.
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape' || event.key === 'F1') {
        event.preventDefault();
        onClose();
      }
    }
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [onClose]);

  return (
    <div className="bg-page/90 absolute inset-0 z-20 flex items-start justify-center overflow-y-auto p-8">
      <div className="border-hair bg-surface flex w-full max-w-3xl flex-col gap-4 rounded-lg border p-6">
        <header className="flex items-baseline justify-between">
          <h2 className="text-ink-3 text-xs uppercase tracking-wider">Keys</h2>
          <span className="text-ink-3 text-xs">Esc close</span>
        </header>

        <p className="text-ink-2 text-sm">
          Every one of these is also a button on screen. The keys are simply quicker once your hands
          know them.
        </p>

        <div className="grid gap-x-8 gap-y-1 sm:grid-cols-2">
          {KEYS.map((row) => (
            <Row key={row.key} label={row.key} what={row.what} />
          ))}
        </div>

        <div className="border-hair grid gap-x-8 gap-y-1 border-t pt-4 sm:grid-cols-2">
          {OTHER.map((row) => (
            <Row key={row.key} label={row.key} what={row.what} />
          ))}
        </div>

        {/* A finger needs a way out too — Esc is no use to somebody with no keyboard, which
            is exactly the person most likely to have opened this. */}
        <button
          type="button"
          tabIndex={-1}
          onClick={onClose}
          className="border-hair text-ink min-h-touch rounded-lg border text-sm font-semibold"
        >
          Close
        </button>
      </div>
    </div>
  );
}

function Row({ label, what }: { label: string; what: string }) {
  const assigned = what !== 'Not assigned yet';
  return (
    <div className="flex items-baseline gap-3 py-1">
      <span
        className={`lum-money w-16 shrink-0 text-sm font-bold ${
          assigned ? 'text-accent' : 'text-ink-3'
        }`}
      >
        {label}
      </span>
      <span className={`text-sm ${assigned ? 'text-ink' : 'text-ink-3'}`}>{what}</span>
    </div>
  );
}
