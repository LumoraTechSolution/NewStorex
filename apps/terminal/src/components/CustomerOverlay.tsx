'use client';

import { formatMinor } from '@lumora/domain';
import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * F6 — who this sale is for (M3-11).
 *
 * <h2>The number is the search box</h2>
 *
 * The shopkeeper asks "number?" and the customer says it. So the field takes digits and matches
 * them as a prefix, and typing letters instead searches names — one box, decided by what was typed.
 * A pair of fields would be more explicit and would also be a pair of fields, and the person using
 * this has a queue behind them and exactly one thing in their head.
 *
 * <h2>Not on file is a two-key answer, not a dead end</h2>
 *
 * The commonest moment this overlay exists for is a customer who has never been recorded. If the
 * search finds nobody, the same screen offers to save the number that was just typed with a name —
 * because the alternative is a cashier who learns that F6 usually fails and stops pressing it, and
 * a customer list that never fills up.
 *
 * <h2>Attaching changes nothing about the money</h2>
 *
 * No price moves, no discount appears, no total changes. That is a v1 decision and the screen says
 * so plainly: the moment a customer can change what is charged, a mis-tap here stops being a wrong
 * name on a receipt and becomes a pricing error.
 *
 * <h2>Keyboard only, like everything else on this screen</h2>
 *
 * Gate M1 is twenty sales without a mouse and this is on the selling path, so it behaves like the
 * tender and refund overlays: type, arrows, Enter, Escape. There is no control here a pointer can
 * reach that the keyboard cannot.
 */
export interface Customer {
  id: number;
  clientUuid: string;
  name: string;
  phone: string | null;
  saleCount: number;
  spentMinor: number;
  lastSeenAt: string | null;
}

type Step = 'SEARCH' | 'NEW';

export function CustomerOverlay({
  attached,
  branchCode,
  terminalCode,
  onAttach,
  onClose,
}: {
  /** Who is on the sale already, so the overlay can offer to take them off it. */
  attached: Customer | null;
  branchCode: string;
  terminalCode: string;
  onAttach: (customer: Customer | null) => void;
  onClose: () => void;
}) {
  const [step, setStep] = useState<Step>('SEARCH');
  const [query, setQuery] = useState('');
  const [name, setName] = useState('');
  const [results, setResults] = useState<Customer[]>([]);
  const [selected, setSelected] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  /**
   * The search that is in flight, so a slow response for "077" cannot land after a fast one for
   * "0771234" and repopulate the list with the wrong people.
   */
  const generation = useRef(0);

  useEffect(() => {
    if (step !== 'SEARCH') return;
    const typed = query.trim();
    if (typed.length < 3) {
      setResults([]);
      setSelected(0);
      return;
    }
    const mine = ++generation.current;
    const timer = setTimeout(() => {
      void fetch(`/api/customers?q=${encodeURIComponent(typed)}`, { cache: 'no-store' })
        .then(async (response) => {
          const body = await response.json();
          if (!response.ok) throw new Error(body.detail ?? `HTTP ${response.status}`);
          if (generation.current !== mine) return;
          setResults(body as Customer[]);
          setSelected(0);
          setError(null);
        })
        .catch((e: unknown) => {
          if (generation.current === mine) {
            setError(e instanceof Error ? e.message : String(e));
          }
        });
      // 150ms: long enough that typing a ten-digit number is one request rather than eight,
      // short enough that the list is there before the digits stop.
    }, 150);
    return () => clearTimeout(timer);
  }, [query, step]);

  const save = useCallback(async () => {
    if (name.trim().length === 0) {
      setError('A name is needed — the number alone is not a customer record anybody can read.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const response = await fetch('/api/customers', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          // Generated here, so a retried request is one customer — the same contract as a sale.
          clientUuid: crypto.randomUUID(),
          branchCode,
          terminalCode,
          name: name.trim(),
          phone: query.trim(),
        }),
      });
      const body = await response.json();
      if (!response.ok) throw new Error(body.detail ?? `HTTP ${response.status}`);
      onAttach(body as Customer);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [branchCode, name, onAttach, query, terminalCode]);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.ctrlKey || event.altKey || event.metaKey) return;

      if (event.key === 'Escape') {
        event.preventDefault();
        if (step === 'NEW') {
          setStep('SEARCH');
          setError(null);
        } else {
          onClose();
        }
        return;
      }

      if (step === 'NEW') {
        if (event.key === 'Enter') {
          event.preventDefault();
          if (!busy) void save();
          return;
        }
        if (event.key === 'Backspace') {
          event.preventDefault();
          setName((current) => current.slice(0, -1));
          return;
        }
        if (event.key.length === 1) {
          event.preventDefault();
          setName((current) => current + event.key);
        }
        return;
      }

      // F6 again takes the customer off the sale, which is the same key that put them on it.
      // A separate "remove" key would be a key nobody remembers.
      if (event.key === 'F6') {
        event.preventDefault();
        if (attached) onAttach(null);
        else onClose();
        return;
      }

      if (event.key === 'ArrowDown') {
        event.preventDefault();
        setSelected((i) => Math.min(i + 1, Math.max(results.length - 1, 0)));
        return;
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault();
        setSelected((i) => Math.max(i - 1, 0));
        return;
      }

      if (event.key === 'Enter') {
        event.preventDefault();
        const chosen = results[selected];
        if (chosen) {
          onAttach(chosen);
        } else if (query.trim().length >= 3) {
          // Nobody found: the number that was typed becomes the new customer's number.
          setStep('NEW');
          setError(null);
        }
        return;
      }

      if (event.key === 'Backspace') {
        event.preventDefault();
        setQuery((current) => current.slice(0, -1));
        return;
      }

      if (event.key.length === 1) {
        event.preventDefault();
        setQuery((current) => current + event.key);
      }
    }
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [attached, busy, onAttach, onClose, query, results, save, selected, step]);

  return (
    <div className="bg-page/95 absolute inset-0 z-20 flex items-start justify-center p-8">
      <div className="border-hair bg-surface flex w-full max-w-2xl flex-col gap-4 rounded-lg border p-6">
        <header className="flex flex-wrap items-baseline justify-between gap-3">
          <h2 className="text-ink text-lg font-semibold">Customer</h2>
          <p className="text-ink-3 text-sm">Nothing about the price changes.</p>
        </header>

        {attached && step === 'SEARCH' && (
          <p className="border-accent text-ink-2 border-l-2 px-3 py-2 text-sm">
            On this sale: <span className="text-ink font-semibold">{attached.name}</span>
            {attached.phone && <span className="lum-money text-ink-3 ml-2">{attached.phone}</span>}
            <span className="text-ink-3 ml-2">· F6 to take them off</span>
          </p>
        )}

        {step === 'SEARCH' ? (
          <>
            <div className="flex flex-col gap-1">
              <span className="text-ink-3 text-xs uppercase tracking-wider">
                Number, or part of a name
              </span>
              <span
                aria-label="Customer search"
                role="textbox"
                aria-readonly="true"
                className="border-hair bg-page text-ink min-h-touch lum-money flex items-center rounded border px-4 text-lg"
              >
                {query || <span className="text-ink-3">…</span>}
              </span>
            </div>

            {error && (
              <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
                {error}
              </p>
            )}

            {query.trim().length < 3 ? (
              <p className="text-ink-3 text-sm">Three characters or more to search.</p>
            ) : results.length === 0 ? (
              <p className="text-ink-2 text-sm">
                Nobody on file for <span className="lum-money">{query.trim()}</span>. Press Enter to
                add them.
              </p>
            ) : (
              <ul className="flex flex-col gap-1" aria-label="Customers">
                {results.map((customer, index) => (
                  <li
                    key={customer.clientUuid}
                    aria-current={index === selected}
                    className={`flex items-baseline justify-between gap-3 rounded px-3 py-2 ${
                      index === selected ? 'bg-page text-ink' : 'text-ink-2'
                    }`}
                  >
                    <span>
                      {index === selected && <span aria-hidden="true">▸ </span>}
                      {customer.name}
                      {customer.phone && (
                        <span className="lum-money text-ink-3 ml-3">{customer.phone}</span>
                      )}
                    </span>
                    <span className="text-ink-3 text-sm">
                      {customer.saleCount === 0
                        ? 'first visit'
                        : `${customer.saleCount} × ${formatMinor(customer.spentMinor)}`}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </>
        ) : (
          <>
            <p className="text-ink-2 text-sm">
              New customer on <span className="lum-money">{query.trim()}</span>.
            </p>
            <div className="flex flex-col gap-1">
              <span className="text-ink-3 text-xs uppercase tracking-wider">Name</span>
              <span
                aria-label="Customer name"
                role="textbox"
                aria-readonly="true"
                className="border-hair bg-page text-ink min-h-touch flex items-center rounded border px-4 text-lg"
              >
                {name || <span className="text-ink-3">…</span>}
              </span>
            </div>
            {error && (
              <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
                {error}
              </p>
            )}
          </>
        )}

        <footer className="border-hair text-ink-3 flex flex-wrap justify-between gap-3 border-t pt-3 text-xs">
          {step === 'SEARCH' ? (
            <>
              <span>↑↓ choose</span>
              <span>Enter {results.length === 0 ? 'add' : 'attach'}</span>
              <span>F6 {attached ? 'remove' : 'close'}</span>
              <span>Esc back to the sale</span>
            </>
          ) : (
            <>
              <span>type the name</span>
              <span>Enter {busy ? 'saving…' : 'save and attach'}</span>
              <span>Esc back to the search</span>
            </>
          )}
        </footer>
      </div>
    </div>
  );
}
