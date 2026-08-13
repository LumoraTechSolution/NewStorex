'use client';

import { extractVatMinor, formatMinor, lineTotalMinor } from '@lumora/domain';
import { useCallback, useEffect, useState } from 'react';

import { SyncStatusStrip } from '@/components/SyncStatusStrip';

type Product = {
  clientUuid: string;
  sku: string;
  barcode: string | null;
  name: string;
  priceMinor: number;
  taxMode: 'INCLUSIVE' | 'EXCLUSIVE';
  taxRateBp: number;
};

type Committed = {
  invoiceNumber: string;
  totalMinor: number;
  alreadyExisted: boolean;
};

/**
 * The M0 spike's sale screen: pick a product, choose a quantity, commit.
 *
 * This is not the terminal. M1-07 builds the real fixed appliance layout with the F-key
 * bar, the always-focused scan field and the tender overlay. What this proves is the one
 * thing M0 exists to prove — that a sale commits to the local database and lands in the
 * outbox, with nothing on the network in the way.
 */
export default function Page() {
  const [products, setProducts] = useState<Product[]>([]);
  const [selected, setSelected] = useState<Product | null>(null);
  const [qty, setQty] = useState(1);
  const [committed, setCommitted] = useState<Committed | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    fetch('/api/products')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`products: HTTP ${r.status}`))))
      .then((list: Product[]) => {
        setProducts(list);
        setSelected(list[0] ?? null);
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  // All money via @lumora/domain. Nothing in this component does arithmetic on cents.
  const total = selected ? lineTotalMinor(selected.priceMinor, qty) : 0;
  const tax = selected ? extractVatMinor(total, selected.taxRateBp) : 0;

  const commit = useCallback(async () => {
    if (!selected) return;
    setBusy(true);
    setError(null);

    // Generated here, before the request goes out. That is what makes a retry safe: the
    // same uuid twice is the same sale, not two of them.
    const clientUuid = crypto.randomUUID();

    try {
      const response = await fetch('/api/sales', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          clientUuid,
          branchCode: 'KND',
          terminalCode: 'T1',
          taxMode: selected.taxMode,
          taxRateBp: selected.taxRateBp,
          subtotalMinor: total,
          discountMinor: 0,
          taxMinor: tax,
          totalMinor: total,
          lines: [
            {
              productClientUuid: selected.clientUuid,
              qty,
              unitPriceMinor: selected.priceMinor,
              discountMinor: 0,
              taxMinor: tax,
              lineTotalMinor: total,
            },
          ],
        }),
      });

      const body = await response.json();
      if (!response.ok) {
        throw new Error(body.detail ?? `HTTP ${response.status}`);
      }
      setCommitted(body as Committed);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [selected, qty, total, tax]);

  return (
    <div className="flex h-full flex-col">
      <SyncStatusStrip />
      <main className="mx-auto flex w-full max-w-2xl flex-col gap-6 overflow-y-auto p-8">
        <header>
          <h1 className="text-2xl font-semibold">Lumora Terminal</h1>
          <p className="text-ink-3 text-xs">
            M0 spike — sale commits locally, outbox carries it later.
          </p>
        </header>

        {error && (
          <p role="alert" className="border-danger text-danger border-l-2 py-2 pl-3 text-sm">
            {error}
          </p>
        )}

        <section className="flex flex-col gap-2">
          <h2 className="text-ink-3 text-xs uppercase tracking-wider">Item</h2>
          <div className="flex flex-col gap-2">
            {products.map((product) => (
              <button
                key={product.clientUuid}
                type="button"
                onClick={() => setSelected(product)}
                aria-pressed={selected?.clientUuid === product.clientUuid}
                className={`border-hair min-h-touch flex items-center justify-between rounded-lg border px-4 text-left ${
                  selected?.clientUuid === product.clientUuid ? 'border-accent' : ''
                }`}
              >
                <span>{product.name}</span>
                <span className="lum-money">{formatMinor(product.priceMinor)}</span>
              </button>
            ))}
            {products.length === 0 && !error && (
              <p className="text-ink-3 text-sm">
                No products. Run <code>pnpm db:seed</code>.
              </p>
            )}
          </div>
        </section>

        <section className="flex items-center gap-3">
          <h2 className="text-ink-3 text-xs uppercase tracking-wider">Qty</h2>
          <button
            type="button"
            onClick={() => setQty((q) => Math.max(1, q - 1))}
            className="border-hair min-h-touch w-touch rounded-lg border text-xl"
            aria-label="Decrease quantity"
          >
            −
          </button>
          <output className="lum-money w-12 text-center text-xl">{qty}</output>
          <button
            type="button"
            onClick={() => setQty((q) => q + 1)}
            className="border-hair min-h-touch w-touch rounded-lg border text-xl"
            aria-label="Increase quantity"
          >
            +
          </button>
        </section>

        <section className="border-hair flex flex-col gap-1 border-t pt-4">
          <div className="text-ink-3 flex justify-between text-sm">
            <span>VAT included</span>
            <span className="lum-money">{formatMinor(tax)}</span>
          </div>
          <div className="flex items-baseline justify-between">
            <span className="text-lg">Total</span>
            {/* Money is the largest thing on screen. */}
            <span className="lum-money text-4xl font-semibold">{formatMinor(total)}</span>
          </div>
        </section>

        <button
          type="button"
          onClick={() => void commit()}
          disabled={!selected || busy}
          className="bg-accent min-h-touch rounded-lg text-lg font-semibold text-white disabled:opacity-40"
        >
          {busy ? 'Committing…' : 'Tender cash'}
        </button>

        {committed && (
          <p className="border-ok text-ok border-l-2 py-2 pl-3 text-sm">
            Sale committed — invoice <strong>{committed.invoiceNumber}</strong> for{' '}
            <span className="lum-money">{formatMinor(committed.totalMinor)}</span>
            {committed.alreadyExisted && ' (already existed)'}
          </p>
        )}
      </main>
    </div>
  );
}
