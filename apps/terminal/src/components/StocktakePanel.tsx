'use client';

import { useCallback, useEffect, useState } from 'react';

import { FIELD_CLASS, Labelled, NUMERIC_FIELD_CLASS } from '@/components/Labelled';
import type { BackOffice } from '@/lib/useBackOffice';

/**
 * Counting the shelves (M3-06).
 *
 * <h2>What the screen has to make obvious</h2>
 *
 * That nothing has happened yet. A person counting four hundred products needs to know they can
 * stop, come back, recount a line they were unsure about, and that none of it touches stock until
 * they say so. So the open count shows every line with its variance and a plain statement that no
 * stock has moved, and the only button that changes anything says what it will do.
 *
 * <h2>Why the system figure is shown</h2>
 *
 * A count where the expected number is visible is not a blind count, and for cash that would be
 * disqualifying (M2-02). Stock is different: the shopkeeper is standing at the shelf and can see
 * what is on it, so hiding the system figure does not stop them being influenced — it stops them
 * noticing that the shelf and the screen disagree while they are still in front of the shelf, which
 * is the moment a miscount is cheapest to catch.
 */
interface StocktakeLine {
  lineNo: number;
  productClientUuid: string;
  sku: string;
  productName: string;
  countedQty: number;
  systemQty: number;
  countedAt: string;
}

interface StocktakeRow {
  id: number;
  clientUuid: string;
  status: 'OPEN' | 'COMPLETED' | 'ABANDONED';
  note: string | null;
  startedAt: string;
  startedByName: string;
  completedAt: string | null;
  completedByName: string | null;
  lineCount: number;
  countedShort: number;
  countedOver: number;
  netVarianceQty: number;
  lines: StocktakeLine[];
}

interface ProductRow {
  clientUuid: string;
  sku: string;
  name: string;
}

export function StocktakePanel({
  office,
  branchCode,
  products,
  onClose,
  onChanged,
}: {
  office: BackOffice;
  branchCode: string;
  products: ProductRow[];
  onClose: () => void;
  onChanged: () => void;
}) {
  const [current, setCurrent] = useState<StocktakeRow | null>(null);
  const [past, setPast] = useState<StocktakeRow[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [productClientUuid, setProductClientUuid] = useState('');
  const [counted, setCounted] = useState('');

  const load = useCallback(async () => {
    try {
      const [open, recent] = await Promise.all([
        office.request<StocktakeRow | null>(
          `/api/back-office/stocktakes/current?branchCode=${encodeURIComponent(branchCode)}`,
        ),
        office.request<StocktakeRow[]>('/api/back-office/stocktakes?limit=5'),
      ]);
      setCurrent(open);
      setPast(recent.filter((row) => row.status !== 'OPEN'));
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoaded(true);
    }
  }, [branchCode, office]);

  useEffect(() => {
    void load();
  }, [load]);

  const act = useCallback(
    async (what: string | null, run: () => Promise<unknown>) => {
      setBusy(true);
      setError(null);
      try {
        await run();
        if (what) setNotice(what);
        await load();
        onChanged();
        return true;
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        return false;
      } finally {
        setBusy(false);
      }
    },
    [load, onChanged],
  );

  return (
    <section className="border-accent flex flex-col gap-4 rounded border p-4">
      <header className="flex flex-wrap items-baseline justify-between gap-3">
        <div>
          <h3 className="text-ink font-semibold">Stocktake</h3>
          <p className="text-ink-3 text-sm">
            Count the shelves. What gets recorded is the <strong>difference</strong> between what
            you found and what the system expected — never a replacement figure, so a shortfall
            stays visible as its own movement.
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="border-hair text-ink-2 min-h-touch rounded border px-4"
        >
          Close
        </button>
      </header>

      {error && (
        <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" className="border-ok text-ok border-l-2 px-3 py-2 text-sm">
          {notice}
        </p>
      )}

      {!loaded ? (
        <p className="text-ink-3 text-sm">Loading…</p>
      ) : current === null ? (
        <StartCount
          busy={busy}
          onStart={(note) =>
            act('Counting started. Nothing moves until you finish.', () =>
              office.request('/api/back-office/stocktakes', {
                method: 'POST',
                body: JSON.stringify({ clientUuid: crypto.randomUUID(), branchCode, note }),
              }),
            )
          }
        />
      ) : (
        <>
          <p role="status" className="border-pending text-pending border-l-2 px-3 py-2 text-sm">
            {/* Icon plus text, never colour alone (§A). */}◐ Counting since{' '}
            {new Date(current.startedAt).toLocaleString()}, started by {current.startedByName}.{' '}
            <strong>No stock has moved yet.</strong>
          </p>

          <form
            className="flex flex-wrap items-end gap-3"
            onSubmit={async (event) => {
              event.preventDefault();
              if (productClientUuid === '') {
                setError('Choose a product to count.');
                return;
              }
              const qty = Number.parseInt(counted, 10);
              if (!Number.isFinite(qty) || qty < 0) {
                setError('Type how many are on the shelf. Zero is a real answer.');
                return;
              }
              const ok = await act(null, () =>
                office.request(`/api/back-office/stocktakes/${current.id}/counts`, {
                  method: 'PUT',
                  body: JSON.stringify({ productClientUuid, countedQty: qty }),
                }),
              );
              if (ok) {
                setProductClientUuid('');
                setCounted('');
              }
            }}
          >
            <Labelled label="Product">
              <select
                value={productClientUuid}
                onChange={(event) => setProductClientUuid(event.target.value)}
                className={`${FIELD_CLASS} w-72`}
              >
                <option value="">— choose —</option>
                {products.map((product) => (
                  <option key={product.clientUuid} value={product.clientUuid}>
                    {product.sku} — {product.name}
                  </option>
                ))}
              </select>
            </Labelled>
            <Labelled label="Counted" hint="how many are actually on the shelf">
              <input
                value={counted}
                onChange={(event) => setCounted(event.target.value.replace(/\D/g, ''))}
                inputMode="numeric"
                className={`${NUMERIC_FIELD_CLASS} w-24 text-right`}
              />
            </Labelled>
            <button
              type="submit"
              disabled={busy}
              className="border-accent text-accent min-h-touch rounded border px-4 font-semibold disabled:opacity-40"
            >
              Record the count
            </button>
          </form>

          {current.lines.length > 0 && (
            <ul className="flex flex-col gap-1" aria-label="Counted so far">
              {current.lines.map((line) => {
                const variance = line.countedQty - line.systemQty;
                return (
                  <li
                    key={line.productClientUuid}
                    className="border-hair flex flex-wrap items-baseline gap-x-4 gap-y-1 border-b pb-1 text-sm last:border-b-0"
                  >
                    <span className="lum-money text-ink-3 w-28">{line.sku}</span>
                    <span className="text-ink min-w-40 flex-1">{line.productName}</span>
                    <span className="lum-money text-ink-3 w-24 text-right">
                      system {line.systemQty}
                    </span>
                    <span className="lum-money text-ink-2 w-24 text-right">
                      counted {line.countedQty}
                    </span>
                    <span
                      className={`lum-money w-20 text-right font-semibold ${
                        variance === 0 ? 'text-ok' : variance < 0 ? 'text-danger' : 'text-pending'
                      }`}
                    >
                      {variance === 0 ? '● agrees' : variance > 0 ? `+${variance}` : variance}
                    </span>
                    <button
                      type="button"
                      onClick={() =>
                        void act(null, () =>
                          office.request(
                            `/api/back-office/stocktakes/${current.id}/counts/${line.productClientUuid}`,
                            { method: 'DELETE' },
                          ),
                        )
                      }
                      className="border-hair text-ink-2 min-h-touch rounded border px-3"
                    >
                      Remove
                    </button>
                  </li>
                );
              })}
            </ul>
          )}

          <p className="text-ink-2 text-sm">
            {current.lineCount} counted · {current.countedShort} short · {current.countedOver} over
            · net{' '}
            <span className="lum-money font-semibold">
              {current.netVarianceQty > 0 ? `+${current.netVarianceQty}` : current.netVarianceQty}
            </span>
          </p>

          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              disabled={busy || current.lineCount === 0}
              onClick={() =>
                void act(
                  `Stocktake finished. ${current.lineCount} product${
                    current.lineCount === 1 ? '' : 's'
                  } counted, net ${current.netVarianceQty}.`,
                  () =>
                    office.request(`/api/back-office/stocktakes/${current.id}/complete`, {
                      method: 'POST',
                    }),
                )
              }
              className="border-accent text-accent min-h-touch rounded border px-4 font-semibold disabled:opacity-40"
            >
              {/* The button says what it will do, because this is the only click that moves stock. */}
              Finish and record the differences
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() =>
                void act('Stocktake abandoned. No stock was moved.', () =>
                  office.request(`/api/back-office/stocktakes/${current.id}/abandon`, {
                    method: 'POST',
                  }),
                )
              }
              className="border-hair text-ink-2 min-h-touch rounded border px-4"
            >
              Abandon
            </button>
          </div>
        </>
      )}

      {past.length > 0 && (
        <>
          <h4 className="text-ink-2 text-sm font-semibold uppercase tracking-wider">
            Earlier counts
          </h4>
          <ul className="flex flex-col gap-1">
            {past.map((row) => (
              <li
                key={row.clientUuid}
                className="border-hair flex flex-wrap items-baseline gap-x-4 gap-y-1 border-b pb-1 text-sm last:border-b-0"
              >
                <span className="text-ink-3 w-40">
                  {new Date(row.completedAt ?? row.startedAt).toLocaleString()}
                </span>
                <span className={`w-32 ${row.status === 'COMPLETED' ? 'text-ok' : 'text-ink-3'}`}>
                  {row.status === 'COMPLETED' ? '● recorded' : '○ abandoned'}
                </span>
                <span className="text-ink-2 flex-1">
                  {row.lineCount} counted · {row.countedShort} short · {row.countedOver} over
                </span>
                <span
                  className={`lum-money w-20 text-right font-semibold ${
                    row.netVarianceQty < 0 ? 'text-danger' : 'text-ink-2'
                  }`}
                >
                  {row.netVarianceQty > 0 ? `+${row.netVarianceQty}` : row.netVarianceQty}
                </span>
                <span className="text-ink-3 w-32">{row.completedByName ?? row.startedByName}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

function StartCount({
  busy,
  onStart,
}: {
  busy: boolean;
  onStart: (note: string) => Promise<boolean>;
}) {
  const [note, setNote] = useState('');

  return (
    <form
      className="flex flex-wrap items-end gap-3"
      onSubmit={(event) => {
        event.preventDefault();
        void onStart(note);
      }}
    >
      <Labelled label="Note" hint="which shelf, or why — optional">
        <input
          value={note}
          onChange={(event) => setNote(event.target.value)}
          className={`${FIELD_CLASS} w-72`}
        />
      </Labelled>
      <button
        type="submit"
        disabled={busy}
        className="border-accent text-accent min-h-touch rounded border px-4 font-semibold disabled:opacity-40"
      >
        Start counting
      </button>
    </form>
  );
}
