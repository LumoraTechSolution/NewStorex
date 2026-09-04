'use client';

import { useCallback, useEffect, useState } from 'react';

import { Card, CardGrid, Empty, ErrorNote, Row } from './Chrome';
import { get, type StockLine } from '@/lib/api';

/**
 * The shelves, seen from somewhere else (M6-12).
 *
 * <h2>The half of the console that was designed and never built</h2>
 *
 * `ConsoleReportService` has admitted it in a comment since M4-07: the attention feed was meant to
 * be cash variance <em>and</em> stock variance, and only the cash half existed. An owner away from
 * the shop could see the money and not the goods, which is half a business.
 *
 * <h2>Two questions, and only one of them is urgent</h2>
 *
 * **What am I about to run out of** is the one that makes somebody put the phone down and ring a
 * supplier, so it is first and it is the one with a count in the tab. **What is on the shelf** is a
 * lookup — you arrive at it already knowing what you want to check — so it is a search box rather
 * than a list to scroll.
 *
 * <h2>Blank and zero are different instructions</h2>
 *
 * A product with no reorder point is never listed here however low it goes, and that is the
 * feature. A list nobody curated fills with things legitimately near zero, and then nobody reads
 * it. The footer says so, because a shopkeeper wondering why their empty shelf is not on this
 * screen deserves an answer on the screen.
 */
export function StockScreen({ token }: { token: string }) {
  const [low, setLow] = useState<StockLine[] | null>(null);
  const [found, setFound] = useState<StockLine[] | null>(null);
  const [query, setQuery] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    void get<StockLine[]>('/api/console/stock/low?limit=50', token)
      .then((rows) => {
        if (live) setLow(rows);
      })
      .catch((e: unknown) => {
        if (live) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      live = false;
    };
  }, [token]);

  const search = useCallback(async () => {
    if (query.trim() === '') {
      setFound(null);
      return;
    }
    try {
      setError(null);
      setFound(await get<StockLine[]>(`/api/console/stock?q=${encodeURIComponent(query)}`, token));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [query, token]);

  return (
    <>
      {error && <ErrorNote>{error}</ErrorNote>}

      <CardGrid>
        <Card
          title="Running out"
          footer="Only products with a reorder point set at the till. One without is never listed, however low it goes."
        >
          {low === null ? (
            <Empty>Loading…</Empty>
          ) : low.length === 0 ? (
            <Empty>Nothing is below its reorder point.</Empty>
          ) : (
            low.map((line) => <StockRow key={line.productClientUuid} line={line} />)
          )}
        </Card>

        <Card
          title="Look something up"
          footer="On hand is the sum of every movement, never a stored level."
        >
          <form
            onSubmit={(event) => {
              event.preventDefault();
              void search();
            }}
            className="flex gap-2"
          >
            <input
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Name or SKU"
              aria-label="Search products"
              className="border-hair text-ink min-h-[44px] flex-1 rounded border bg-transparent px-3"
            />
            <button
              type="submit"
              className="border-hair text-ink min-h-[44px] rounded border px-4 font-medium"
            >
              Search
            </button>
          </form>

          {found === null ? (
            <Empty>Type a name or an SKU.</Empty>
          ) : found.length === 0 ? (
            <Empty>Nothing matches “{query.trim()}”.</Empty>
          ) : (
            found.map((line) => <StockRow key={line.productClientUuid} line={line} />)
          )}
        </Card>
      </CardGrid>
    </>
  );
}

/**
 * One product.
 *
 * <p>The number is not coloured on its own. §A: status colour never carries meaning alone, and
 * "low" and "fine" would look identical to a colour-blind reader — so the word is beside it, and a
 * negative gets its own word because it means something different again.
 */
function StockRow({ line }: { line: StockLine }) {
  return (
    <Row>
      <span className="flex flex-col">
        <span className="font-medium">{line.name}</span>
        <span className="text-ink-3 text-xs">
          {line.sku}
          {line.category ? ` · ${line.category}` : ''}
          {line.reorderPoint !== null ? ` · reorder at ${line.reorderPoint}` : ''}
        </span>
      </span>
      <span className="flex flex-col items-end">
        <span className="font-medium tabular-nums">{line.onHand}</span>
        <span className="text-ink-3 text-xs">{describe(line)}</span>
      </span>
    </Row>
  );
}

/**
 * What the number means, in a word.
 *
 * A negative on hand is not "low" — it means a sale was rung up for stock the shop never recorded
 * receiving, which is a different problem with a different fix, and calling it low would send
 * somebody to a supplier instead of to the goods-in book.
 */
function describe(line: StockLine): string {
  if (line.onHand < 0) return 'not received';
  if (line.reorderPoint === null) return 'on hand';
  if (line.onHand <= line.reorderPoint) return 'low';
  return 'on hand';
}
