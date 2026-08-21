'use client';

import { countedTotalMinor, formatMinor, LKR_DENOMINATIONS_MINOR } from '@lumora/domain';
import { useCallback, useEffect, useState } from 'react';

/**
 * Counting a drawer, note by note (M2-02).
 *
 * ## Why a per-denomination grid and not one amount field
 *
 * A float or a drawer total typed as a single figure is a number nobody checked. The count is the
 * evidence behind a variance, and an owner reading a LKR 5,000 shortfall needs to know whether it
 * was one missing note or a hundred missing coins — which a total cannot tell them. So the shape of
 * the screen is the shape of the audit trail.
 *
 * ## What the counter is allowed to see
 *
 * The running total of <em>what they counted</em>, and nothing else. That is not a leak: they are
 * holding the money, so they could add it up themselves. What is missing is what the drawer was
 * supposed to hold, and that figure does not exist on the client at all — the status endpoint has
 * no field for it (M2-02). The counter finds out whether they were right only after they submit.
 *
 * ## Keyboard only
 *
 * Arrows move, digits type the quantity for the selected row, Enter or Tab moves down. That is the
 * whole interaction. Denominations are listed largest first because that is the order a cashier
 * counts in — the screen should not make them re-sort a drawer they have already stacked.
 */
export type DenominationCount = { denominationMinor: number; qty: number };

/** The face value in rupees, for the row label. */
function faceLabel(denominationMinor: number): string {
  return (denominationMinor / 100).toLocaleString('en-LK');
}

/**
 * Read-only by design: {@link useDenominationKeys} owns every mutation.
 *
 * The alternative — an `onChange` here as well — would mean two ways to change a count and two
 * places for the running total to be computed differently. There is nothing on this screen to
 * click, so a view that only renders is not a limitation.
 */
export function DenominationCounter({
  counts,
  selected,
  label,
}: {
  counts: readonly DenominationCount[];
  selected: number;
  label: string;
}) {
  const qtyFor = useCallback(
    (denominationMinor: number) =>
      counts.find((c) => c.denominationMinor === denominationMinor)?.qty ?? 0,
    [counts],
  );

  return (
    <div className="flex flex-col gap-2">
      <h3 className="text-ink-3 text-xs uppercase tracking-wider">{label}</h3>
      <ul className="flex flex-col gap-1" aria-label={label}>
        {LKR_DENOMINATIONS_MINOR.map((face, index) => {
          const qty = qtyFor(face);
          const isSelected = index === selected;
          return (
            <li key={face}>
              <div
                aria-current={isSelected}
                className={`flex items-center justify-between rounded border px-3 py-1 text-sm ${
                  isSelected ? 'border-accent' : 'border-hair'
                }`}
              >
                <span
                  className={`lum-money w-20 text-right ${isSelected ? 'text-ink' : 'text-ink-3'}`}
                >
                  {faceLabel(face)}
                </span>
                <span className="text-ink-3">×</span>
                <span
                  className={`lum-money w-16 text-right ${qty > 0 ? 'text-ink' : 'text-ink-3'} ${
                    isSelected ? 'text-accent font-semibold' : ''
                  }`}
                >
                  {qty}
                </span>
                <span className="lum-money text-ink-2 w-28 text-right">
                  {qty > 0 ? formatMinor(face * qty) : ''}
                </span>
              </div>
            </li>
          );
        })}
      </ul>
      <div className="border-hair flex items-baseline justify-between border-t pt-2">
        <span className="text-ink-3 text-sm">Counted</span>
        <span className="lum-money text-ink text-2xl font-semibold">
          {formatMinor(countedTotalMinor(counts))}
        </span>
      </div>
    </div>
  );
}

/**
 * The keyboard behaviour, factored out so both the open and close screens get exactly the same one.
 *
 * <p>Digits accumulate rather than replace, so typing "12" on a row means twelve notes and not one
 * then two. Backspace removes a digit. Returns the handler for the caller's own key listener rather
 * than installing one, because these screens are modal and the caller already owns the keyboard.
 */
export function useDenominationKeys(
  counts: readonly DenominationCount[],
  setCounts: (next: DenominationCount[]) => void,
  selected: number,
  setSelected: (next: number) => void,
) {
  const [buffer, setBuffer] = useState('');

  // A fresh row starts a fresh number. Without this, moving off a row and back would append to
  // whatever was typed before it, and a recount would silently multiply.
  useEffect(() => setBuffer(''), [selected]);

  const setQty = useCallback(
    (qty: number) => {
      const face = LKR_DENOMINATIONS_MINOR[selected]!;
      const rest = counts.filter((c) => c.denominationMinor !== face);
      setCounts(
        qty > 0
          ? [...rest, { denominationMinor: face, qty }].sort(
              (a, b) => b.denominationMinor - a.denominationMinor,
            )
          : rest,
      );
    },
    [counts, selected, setCounts],
  );

  /** Returns true when the key was consumed, so the caller can fall through for everything else. */
  return useCallback(
    (event: KeyboardEvent): boolean => {
      if (event.key >= '0' && event.key <= '9' && event.key.length === 1) {
        const next = (buffer + event.key).slice(0, 4);
        setBuffer(next);
        setQty(Number(next));
        return true;
      }
      switch (event.key) {
        case 'Backspace': {
          const next = buffer.slice(0, -1);
          setBuffer(next);
          setQty(next === '' ? 0 : Number(next));
          return true;
        }
        case 'ArrowUp':
          setSelected(Math.max(0, selected - 1));
          return true;
        case 'ArrowDown':
        case 'Enter':
        case 'Tab':
          setSelected(Math.min(LKR_DENOMINATIONS_MINOR.length - 1, selected + 1));
          return true;
        default:
          return false;
      }
    },
    [buffer, selected, setQty, setSelected],
  );
}
