'use client';

import { formatMinor } from '@lumora/domain';
import { useEffect, useRef, useState } from 'react';

import type { Product } from '@/lib/useCart';

/** How long to wait after the last keystroke before asking the backend. */
const DEBOUNCE_MS = 150;

/** Numbered hits, so a keyboard user picks with one keypress. Nine is what 1-9 addresses. */
const MAX_HITS = 9;

/**
 * Find an item by name (M6, filling the F3 slot the bar has always reserved).
 *
 * ## Why this exists
 *
 * The scan field already searches: type a name, press Enter, and `onQuery` finds it. But
 * that path is built around a gun — the field is `inputMode="none"` so no on-screen keyboard
 * appears, and the results arrive as a modal picker that closes on the first choice. On a
 * touchscreen till with no keyboard attached, there is otherwise **no way at all** to sell a
 * product whose barcode will not scan or which never had one: loose produce, a bag of nails,
 * anything sold by the each from an open tray.
 *
 * This is that way. It is deliberately not a product grid: a shop's catalogue does not fit on
 * a screen, and picking a shelf from a wall of buttons is slower than typing three letters
 * once there are more than about thirty of them.
 *
 * ## Focus, and why this overlay is different
 *
 * Every other overlay on the till leaves the caret alone, because the scan field wants it and
 * `ScanField.reclaim()` takes it back on any `focusin`. This one **needs** the caret: it is a
 * text box a person types into. That works only because the parent gates the scan field off
 * with `interactionsBlocked()` while this is open — `reclaim()` returns early when disabled,
 * so the caret stays here. If that gating is ever removed, the symptom is that characters
 * appear in the scan field instead of this box, which reads as a keyboard bug rather than a
 * focus one.
 */
export function SearchOverlay({
  lookup,
  onPick,
  onCancel,
}: {
  /** The same catalogue lookup the scan field uses — one search path, not two. */
  lookup: (query: string) => Promise<{ exactMatch: boolean; products: Product[] }>;
  onPick: (product: Product) => void;
  onCancel: () => void;
}) {
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<Product[]>([]);
  const [selected, setSelected] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // Debounced, and the result of a stale request is discarded rather than rendered: typing
  // "milk" fires four searches and they can land out of order, which without this shows the
  // hits for "mil" under the word "milk".
  useEffect(() => {
    const text = query.trim();
    if (text === '') {
      setHits([]);
      setError(null);
      return;
    }
    let cancelled = false;
    const timer = setTimeout(() => {
      lookup(text)
        .then((result) => {
          if (cancelled) return;
          setHits(result.products.slice(0, MAX_HITS));
          setSelected(0);
          setError(result.products.length === 0 ? `Nothing matches "${text}"` : null);
        })
        .catch((e: unknown) => {
          if (cancelled) return;
          setHits([]);
          setError(e instanceof Error ? e.message : String(e));
        });
    }, DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query, lookup]);

  // Capture phase, like every other overlay, so the global F-keys never also see these.
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault();
        onCancel();
        return;
      }
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        setSelected((s) => {
          const next = s + (event.key === 'ArrowDown' ? 1 : -1);
          return Math.max(0, Math.min(hits.length - 1, next));
        });
        return;
      }
      if (event.key === 'Enter') {
        event.preventDefault();
        const product = hits[selected];
        if (product) onPick(product);
        return;
      }
      // 1-9 pick directly, the same as the item picker — but only with a modifier-free digit
      // and only when the caret is not mid-word, because a product called "7up" has to be
      // typeable. Alt is what makes it unambiguous.
      if (event.altKey && /^[1-9]$/.test(event.key)) {
        event.preventDefault();
        const product = hits[Number(event.key) - 1];
        if (product) onPick(product);
      }
    }
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [hits, selected, onPick, onCancel]);

  return (
    <div className="bg-page/90 absolute inset-0 z-20 flex items-start justify-center p-8">
      <div className="border-hair bg-surface flex w-full max-w-2xl flex-col gap-4 rounded-lg border p-6">
        <header className="flex items-baseline justify-between">
          <h2 className="text-ink-3 text-xs uppercase tracking-wider">Find an item</h2>
          <span className="text-ink-3 text-xs">Esc cancel</span>
        </header>

        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Type part of a name"
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="off"
          spellCheck={false}
          aria-label="Find an item"
          className="border-accent bg-page text-ink min-h-touch placeholder:text-ink-3 rounded-lg border px-4 text-lg outline-none"
        />

        {error && (
          <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
            {error}
          </p>
        )}

        <ul className="flex flex-col gap-1" aria-label="Search results">
          {hits.map((product, index) => (
            <li key={product.clientUuid}>
              <button
                type="button"
                tabIndex={-1}
                onClick={() => onPick(product)}
                // `aria-current`, not `aria-selected`: a plain button has no selected state
                // in the accessibility tree, and this is "the one Enter would add" anyway.
                aria-current={index === selected}
                className={`min-h-touch flex w-full items-center justify-between rounded border px-4 text-left ${
                  index === selected ? 'border-accent bg-page' : 'border-hair'
                }`}
              >
                <span>
                  <span className="text-accent mr-3 font-semibold">{index + 1}</span>
                  {product.name}
                  <span className="text-ink-3 ml-2 text-xs">{product.sku}</span>
                </span>
                <span className="lum-money">{formatMinor(product.priceMinor)}</span>
              </button>
            </li>
          ))}
        </ul>

        <footer className="border-hair text-ink-3 flex flex-wrap gap-x-4 gap-y-1 border-t pt-3 text-xs">
          <span>↑ ↓ move</span>
          <span>Enter add</span>
          <span>Alt+1–9 pick</span>
          <span>Tap a row to add it</span>
        </footer>
      </div>
    </div>
  );
}
