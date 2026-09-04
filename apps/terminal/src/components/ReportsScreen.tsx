'use client';

import { formatMinor } from '@lumora/domain';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { FIELD_CLASS, Labelled } from '@/components/Labelled';
import type { BackOffice } from '@/lib/useBackOffice';

/**
 * What the shop took, what sells, and what happened on each shift (M3-10).
 *
 * <h2>Three questions, three tabs, one screen</h2>
 *
 * A shopkeeper reading their own till asks a small number of things. Day sales answers "is the
 * drawer right"; top products answers "what sells"; low stock answers "what do I reorder"; shifts
 * answers "what happened while I was out". They are tabs rather than separate nav entries because
 * they are read one after another in the same sitting, and because another report is another tab
 * rather than another thing to find.
 *
 * <h2>Low stock is the only one about tomorrow</h2>
 *
 * Every other tab here reports the past. Low stock is the one a shopkeeper can still act on, which
 * is why it sits beside top products rather than under Stock: "what sells" and "what am I about to
 * run out of" are the same decision read in one sitting.
 *
 * <h2>Stock on hand is not repeated here</h2>
 *
 * M3-10 names it as a report and it already exists, under Stock, built on the view M3-07 added.
 * Drawing it a second time would be a second presentation of one figure — and the moment the two
 * disagree, which of them the owner believes is a coin toss. The tab points at the real one.
 *
 * <h2>Nothing here is computed in the browser</h2>
 *
 * Every figure arrives from the backend already summed. Not for speed — a shop's day is a few
 * hundred rows — but because a total added up here is a second implementation of the shop's
 * arithmetic, and §A exists to stop the receipt and the report disagreeing by a rupee.
 */
interface TenderTotal {
  kind: string;
  takenMinor: number;
  givenBackMinor: number;
  netMinor: number;
}

interface HourTotal {
  hour: number;
  saleCount: number;
  grossMinor: number;
}

interface DaySales {
  date: string;
  saleCount: number;
  grossMinor: number;
  discountMinor: number;
  taxMinor: number;
  refundCount: number;
  refundTotalMinor: number;
  refundTaxMinor: number;
  netTakingsMinor: number;
  tenders: TenderTotal[];
  hours: HourTotal[];
}

interface TopProduct {
  clientUuid: string;
  sku: string;
  name: string;
  qtySold: number;
  qtyReturned: number;
  qtyNet: number;
  revenueNetMinor: number;
}

interface LowStockRow {
  productClientUuid: string;
  sku: string;
  productName: string;
  categoryName: string | null;
  qtyOnHand: number;
  reorderPoint: number;
  soldLast30Days: number;
  lastMovedAt: string | null;
}

interface ClosedShift {
  id: number;
  branchCode: string;
  terminalCode: string;
  openedAt: string;
  closedAt: string;
  openedByName: string;
  closedByName: string | null;
  openingFloatMinor: number;
  countedCashMinor: number;
  expectedCashMinor: number;
  varianceMinor: number;
  varianceReason: string | null;
  varianceNote: string | null;
  saleCount: number;
  salesTotalMinor: number;
  refundCount: number;
  refundsTotalMinor: number;
}

type Tab = 'DAY' | 'TOP' | 'LOW' | 'SHIFTS' | 'STOCK';

const TABS: readonly { id: Tab; label: string }[] = [
  { id: 'DAY', label: 'Day sales' },
  { id: 'TOP', label: 'Top products' },
  { id: 'LOW', label: 'Low stock' },
  { id: 'SHIFTS', label: 'Shifts' },
  { id: 'STOCK', label: 'Stock on hand' },
];

const TENDER_LABEL: Record<string, string> = {
  CASH: 'Cash',
  CARD: 'Card',
  WALLET: 'Wallet',
  STORE_CREDIT: 'Store credit',
};

const VARIANCE_REASON_LABEL: Record<string, string> = {
  MISCOUNT: 'miscount',
  FLOAT_ERROR: 'float error',
  UNRECORDED_PAYOUT: 'unrecorded payout',
  CHANGE_GIVEN_WRONG: 'change given wrong',
  THEFT_SUSPECTED: 'theft suspected',
  OTHER: 'other',
};

export function ReportsScreen({
  office,
  branchCode,
  onOpenStock,
}: {
  office: BackOffice;
  branchCode: string;
  onOpenStock: () => void;
}) {
  const [tab, setTab] = useState<Tab>('DAY');

  return (
    <div className="flex flex-col gap-4">
      <header>
        <h2 className="text-ink text-lg font-semibold">Reports</h2>
        <p className="text-ink-3 text-sm">
          Read from this shop&rsquo;s own database. Everything here works with the internet
          unplugged, which is when a shop most wants to know whether the drawer is right.
        </p>
      </header>

      <nav aria-label="Report" className="border-hair flex flex-wrap gap-2 border-b pb-2">
        {TABS.map((entry) => (
          <button
            key={entry.id}
            type="button"
            aria-current={entry.id === tab ? 'page' : undefined}
            onClick={() => setTab(entry.id)}
            className={`min-h-touch rounded px-4 ${
              entry.id === tab ? 'text-accent font-semibold' : 'text-ink-2'
            }`}
          >
            {entry.label}
          </button>
        ))}
      </nav>

      {tab === 'DAY' && <DayPanel office={office} />}
      {tab === 'TOP' && <TopProductsPanel office={office} />}
      {tab === 'LOW' && <LowStockPanel office={office} branchCode={branchCode} />}
      {tab === 'SHIFTS' && <ShiftsPanel office={office} />}
      {tab === 'STOCK' && <StockPointer onOpenStock={onOpenStock} />}
    </div>
  );
}

// ------------------------------------------------------------------------------ day sales

function DayPanel({ office }: { office: BackOffice }) {
  const [date, setDate] = useState(() => today());
  const [report, setReport] = useState<DaySales | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    void office
      .request<DaySales>(`/api/reports/day?date=${encodeURIComponent(date)}`)
      .then((body) => {
        if (!current) return;
        setReport(body);
        setError(null);
      })
      .catch((e: unknown) => {
        if (current) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      current = false;
    };
  }, [date, office]);

  const busiest = useMemo(() => {
    if (!report || report.hours.length === 0) return 0;
    return Math.max(...report.hours.map((h) => h.grossMinor));
  }, [report]);

  return (
    <section className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-3">
        <Labelled label="Day">
          <input
            type="date"
            value={date}
            max={today()}
            onChange={(event) => setDate(event.target.value)}
            className={FIELD_CLASS}
          />
        </Labelled>
        <button
          type="button"
          onClick={() => setDate(today())}
          className="border-hair text-ink-2 min-h-touch rounded border px-4"
        >
          Today
        </button>
        <button
          type="button"
          onClick={() => setDate(shiftDate(date, -1))}
          className="border-hair text-ink-2 min-h-touch rounded border px-4"
        >
          Previous day
        </button>
      </div>

      {error && <Problem message={error} />}

      {report && (
        <>
          <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Figure label="Net takings" value={formatMinor(report.netTakingsMinor)} strong />
            <Figure
              label="Sales"
              value={formatMinor(report.grossMinor)}
              note={`${report.saleCount} ${report.saleCount === 1 ? 'sale' : 'sales'}`}
            />
            <Figure
              label="Returns"
              value={formatMinor(report.refundTotalMinor)}
              note={`${report.refundCount} ${report.refundCount === 1 ? 'return' : 'returns'}`}
            />
            <Figure label="VAT in sales" value={formatMinor(report.taxMinor)} />
          </dl>

          <div className="grid gap-4 lg:grid-cols-2">
            <section className="border-hair flex flex-col gap-2 rounded border p-4">
              <h3 className="text-ink font-semibold">By tender</h3>
              {report.tenders.length === 0 ? (
                <p className="text-ink-3 text-sm">Nothing was taken on this day.</p>
              ) : (
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-ink-3 text-left text-xs uppercase tracking-wider">
                      <th scope="col" className="py-1">
                        Tender
                      </th>
                      <th scope="col" className="py-1 text-right">
                        Taken
                      </th>
                      <th scope="col" className="py-1 text-right">
                        Given back
                      </th>
                      <th scope="col" className="py-1 text-right">
                        Net
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.tenders.map((tender) => (
                      <tr key={tender.kind} className="border-hair border-t">
                        <td className="text-ink-2 py-1">
                          {TENDER_LABEL[tender.kind] ?? tender.kind}
                        </td>
                        <td className="lum-money text-ink py-1 text-right">
                          {formatMinor(tender.takenMinor)}
                        </td>
                        <td className="lum-money text-ink-3 py-1 text-right">
                          {tender.givenBackMinor === 0
                            ? '—'
                            : `(${formatMinor(tender.givenBackMinor)})`}
                        </td>
                        <td className="lum-money text-ink py-1 text-right font-semibold">
                          {formatMinor(tender.netMinor)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>

            <section className="border-hair flex flex-col gap-2 rounded border p-4">
              <h3 className="text-ink font-semibold">By hour</h3>
              {report.hours.length === 0 ? (
                <p className="text-ink-3 text-sm">No sales were rung up on this day.</p>
              ) : (
                <ul className="flex flex-col gap-1">
                  {report.hours.map((hour) => (
                    <li key={hour.hour} className="flex items-center gap-3 text-sm">
                      <span className="lum-money text-ink-3 w-14 shrink-0">
                        {hourLabel(hour.hour)}
                      </span>
                      {/*
                        A bar, not a chart. The shape of a trading day is the only thing anybody
                        reads this for, and a charting library on a machine that must run offline
                        forever is a dependency bought for one screen.
                      */}
                      <span className="bg-page h-3 flex-1 overflow-hidden rounded">
                        <span
                          className="bg-accent block h-full"
                          style={{
                            width: `${busiest === 0 ? 0 : (hour.grossMinor / busiest) * 100}%`,
                          }}
                        />
                      </span>
                      <span className="lum-money text-ink w-28 shrink-0 text-right">
                        {formatMinor(hour.grossMinor)}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>
        </>
      )}
    </section>
  );
}

// -------------------------------------------------------------------------- top products

function TopProductsPanel({ office }: { office: BackOffice }) {
  const [days, setDays] = useState(28);
  const [rows, setRows] = useState<TopProduct[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    const to = today();
    const from = shiftDate(to, -(days - 1));
    void office
      .request<TopProduct[]>(`/api/reports/top-products?from=${from}&to=${to}&limit=20`)
      .then((body) => {
        if (!current) return;
        setRows(body);
        setError(null);
      })
      .catch((e: unknown) => {
        if (current) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      current = false;
    };
  }, [days, office]);

  return (
    <section className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        {[7, 28, 90].map((option) => (
          <button
            key={option}
            type="button"
            aria-pressed={days === option}
            onClick={() => setDays(option)}
            className={`min-h-touch rounded border px-4 ${
              days === option ? 'border-accent text-accent font-semibold' : 'border-hair text-ink-2'
            }`}
          >
            Last {option} days
          </button>
        ))}
      </div>

      <p className="text-ink-3 text-sm">
        Ranked by units that stayed sold. Returns are subtracted, because a line sold ten times and
        returned nine is not a line worth reordering.
      </p>

      {error && <Problem message={error} />}

      {rows && rows.length === 0 && (
        <p className="text-ink-3 text-sm">Nothing was sold in this period.</p>
      )}

      {rows && rows.length > 0 && (
        <table className="w-full text-sm">
          <thead>
            <tr className="text-ink-3 text-left text-xs uppercase tracking-wider">
              <th scope="col" className="py-1">
                Product
              </th>
              <th scope="col" className="py-1 text-right">
                Sold
              </th>
              <th scope="col" className="py-1 text-right">
                Returned
              </th>
              <th scope="col" className="py-1 text-right">
                Net units
              </th>
              <th scope="col" className="py-1 text-right">
                Net takings
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.clientUuid} className="border-hair border-t">
                <td className="text-ink py-1">
                  {row.name}
                  <span className="text-ink-3 lum-money ml-2 text-xs">{row.sku}</span>
                </td>
                <td className="lum-money text-ink-2 py-1 text-right">{row.qtySold}</td>
                <td className="lum-money text-ink-3 py-1 text-right">
                  {row.qtyReturned === 0 ? '—' : row.qtyReturned}
                </td>
                <td className="lum-money text-ink py-1 text-right font-semibold">{row.qtyNet}</td>
                <td className="lum-money text-ink py-1 text-right">
                  {formatMinor(row.revenueNetMinor)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------- low stock

/**
 * What the shop is about to run out of (M3-15).
 *
 * <h2>Why an empty list is the good outcome, and has to look like one</h2>
 *
 * Every other tab shows something whatever happens. This one is usually empty, and an empty screen
 * that says nothing reads like a screen that is broken. So the empty state says which products are
 * being watched and how many — "nothing is low" and "nothing is watched" are completely different
 * facts and the second one needs fixing, not celebrating.
 *
 * <h2>Sold in 30 days is the column that makes it an order</h2>
 *
 * Two products both three short are not the same problem if one sells forty a month and the other
 * four. Shortfall decides the order of the rows; how fast it sells is what tells the shopkeeper
 * whether to act today.
 */
function LowStockPanel({ office, branchCode }: { office: BackOffice; branchCode: string }) {
  const [rows, setRows] = useState<LowStockRow[] | null>(null);
  const [watched, setWatched] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    void office
      .request<LowStockRow[]>(
        `/api/back-office/stock-on-hand/low?branchCode=${encodeURIComponent(branchCode)}`,
      )
      .then((body) => {
        if (!current) return;
        setRows(body);
        setError(null);
      })
      .catch((e: unknown) => {
        if (!current) return;
        setError(e instanceof Error ? e.message : 'Could not load low stock.');
      });
    return () => {
      current = false;
    };
  }, [branchCode, office]);

  // Only to tell "nothing is low" apart from "nothing is watched" in the empty state. The list
  // itself is never derived here — that comparison belongs to the one query that owns it.
  useEffect(() => {
    let current = true;
    void office
      .request<{ reorderPoint: number | null }[]>('/api/back-office/products')
      .then((body) => {
        if (!current) return;
        setWatched(body.filter((row) => row.reorderPoint !== null).length);
      })
      .catch(() => {
        if (current) setWatched(null);
      });
    return () => {
      current = false;
    };
  }, [office]);

  return (
    <section className="flex flex-col gap-3">
      <p className="text-ink-3 text-sm">
        Products at or below the reorder point set for them. A product with no reorder point is
        never listed here, however low it goes — set one on the product to start watching it.
      </p>

      {error && <Problem message={error} />}

      {rows && rows.length === 0 && (
        <p className="text-ink-3 text-sm">
          {watched === 0
            ? 'No product has a reorder point yet, so nothing can be low. Set one on a product to start watching it.'
            : `Nothing is low. ${watched === null ? 'Every watched product' : `All ${watched} watched products`} are above their reorder point.`}
        </p>
      )}

      {rows && rows.length > 0 && (
        <table className="w-full text-sm">
          <thead>
            <tr className="text-ink-3 text-left text-xs uppercase tracking-wider">
              <th scope="col" className="py-1">
                Product
              </th>
              <th scope="col" className="py-1 text-right">
                On hand
              </th>
              <th scope="col" className="py-1 text-right">
                Reorder at
              </th>
              <th scope="col" className="py-1 text-right">
                Short by
              </th>
              <th scope="col" className="py-1 text-right">
                Sold in 30 days
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.productClientUuid} className="border-hair border-t">
                <td className="text-ink py-1">
                  {row.productName}
                  <span className="text-ink-3 lum-money ml-2 text-xs">{row.sku}</span>
                </td>
                <td
                  className={`lum-money py-1 text-right font-semibold ${
                    row.qtyOnHand <= 0 ? 'text-danger' : 'text-ink'
                  }`}
                >
                  {row.qtyOnHand}
                </td>
                <td className="lum-money text-ink-3 py-1 text-right">{row.reorderPoint}</td>
                <td className="lum-money text-ink-2 py-1 text-right">
                  {row.reorderPoint - row.qtyOnHand}
                </td>
                <td className="lum-money text-ink-2 py-1 text-right">{row.soldLast30Days}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

// ------------------------------------------------------------------------------- shifts

/** How many rows a request asks for, and therefore how many "Load more" adds. */
const SHIFT_PAGE = 30;

/**
 * The shift history, with a way to reach the thirty-first (M6-11).
 *
 * <h2>A date range and a button, not page numbers</h2>
 *
 * A shopkeeper does not think in pages. They think "show me last week" and "show me more", and
 * those are two different controls: the range narrows the question, the button extends the answer.
 * Page numbers would be a third concept that answers neither — and on a list that only ever grows
 * at one end, page three means something different tomorrow.
 *
 * <h2>The cursor is the last row on screen</h2>
 *
 * "Load more" sends the closing time and id of the last row it is showing, not an offset. A shift
 * closing on the other till while somebody reads this would push every row down one, and an offset
 * would then repeat the row at the boundary — a shift listed twice in a list being reconciled
 * against a drawer. Asking for "what comes after this exact row" cannot.
 */
function ShiftsPanel({ office }: { office: BackOffice }) {
  const [rows, setRows] = useState<ClosedShift[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  /** False once a request comes back short, which is the only honest signal that there is no more. */
  const [more, setMore] = useState(false);
  const [busy, setBusy] = useState(false);

  const query = useCallback(
    (after?: ClosedShift) => {
      const params = new URLSearchParams({ limit: String(SHIFT_PAGE) });
      if (from) params.set('from', from);
      if (to) params.set('to', to);
      if (after) {
        params.set('beforeClosedAt', after.closedAt);
        params.set('beforeId', String(after.id));
      }
      return office.request<ClosedShift[]>(`/api/reports/shifts?${params.toString()}`);
    },
    [from, office, to],
  );

  const load = useCallback(async () => {
    setBusy(true);
    try {
      const body = await query();
      setRows(body);
      setMore(body.length === SHIFT_PAGE);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [query]);

  const loadMore = useCallback(async () => {
    if (!rows || rows.length === 0) return;
    setBusy(true);
    try {
      const body = await query(rows[rows.length - 1]);
      setRows([...rows, ...body]);
      setMore(body.length === SHIFT_PAGE);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [query, rows]);

  // The first load, and every change of range. Deliberately not debounced on typing: these are date
  // inputs, so a change is a completed date rather than a keystroke.
  useEffect(() => {
    void load();
  }, [load]);

  return (
    <section className="flex flex-col gap-3">
      <p className="text-ink-3 text-sm">
        Closed shifts, newest first. The variance is the figure that was frozen when the drawer was
        counted — it is read back, never recalculated, so this list and the Z-report cannot
        disagree. A shift still open is not here: it has an expected-cash figure, and the person
        about to count the drawer must not see it.
      </p>

      <div className="flex flex-wrap items-end gap-3">
        <Labelled label="From">
          <input
            type="date"
            value={from}
            max={to || undefined}
            onChange={(event) => setFrom(event.target.value)}
            className={FIELD_CLASS}
          />
        </Labelled>
        <Labelled label="To">
          <input
            type="date"
            value={to}
            min={from || undefined}
            onChange={(event) => setTo(event.target.value)}
            className={FIELD_CLASS}
          />
        </Labelled>
        {(from || to) && (
          <button
            type="button"
            onClick={() => {
              setFrom('');
              setTo('');
            }}
            className="border-hair text-ink-2 min-h-touch rounded border px-3"
          >
            Clear dates
          </button>
        )}
      </div>

      {error && <Problem message={error} />}

      {rows && rows.length === 0 && (
        <p className="text-ink-3 text-sm">
          {from || to
            ? 'No shift was closed in those dates.'
            : 'No shift has been closed on this till yet.'}
        </p>
      )}

      {rows && rows.length > 0 && (
        <ul className="flex flex-col gap-2">
          {rows.map((shift) => (
            <li key={shift.id} className="border-hair flex flex-col gap-1 rounded border p-3">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <span className="text-ink font-semibold">
                  {formatDateTime(shift.closedAt)}
                  <span className="text-ink-3 ml-2 text-sm font-normal">
                    {shift.branchCode} · {shift.terminalCode}
                  </span>
                </span>
                <Variance minor={shift.varianceMinor} reason={shift.varianceReason} />
              </div>
              <div className="text-ink-3 flex flex-wrap gap-x-6 gap-y-1 text-sm">
                <span>
                  Opened {formatDateTime(shift.openedAt)} by {shift.openedByName}
                </span>
                <span>Closed by {shift.closedByName ?? '—'}</span>
              </div>
              <dl className="mt-1 grid grid-cols-2 gap-2 text-sm md:grid-cols-4">
                <Cell
                  label="Sales"
                  value={formatMinor(shift.salesTotalMinor)}
                  note={`${shift.saleCount}`}
                />
                <Cell
                  label="Returns"
                  value={formatMinor(shift.refundsTotalMinor)}
                  note={`${shift.refundCount}`}
                />
                <Cell label="Counted" value={formatMinor(shift.countedCashMinor)} />
                <Cell label="Expected" value={formatMinor(shift.expectedCashMinor)} />
              </dl>
              {shift.varianceNote && (
                <p className="text-ink-3 text-sm">&ldquo;{shift.varianceNote}&rdquo;</p>
              )}
            </li>
          ))}
        </ul>
      )}

      {rows && more && (
        <button
          type="button"
          onClick={() => void loadMore()}
          disabled={busy}
          className="border-hair text-ink-2 min-h-touch self-start rounded border px-4 disabled:opacity-40"
        >
          {busy ? 'Loading…' : 'Load more'}
        </button>
      )}
    </section>
  );
}

/**
 * The variance, with an icon and a word beside the colour.
 *
 * <p>§A: status colour never carries meaning alone. Green and red on a number is exactly the case
 * where it would — the two states look identical to a colour-blind reader and to a printed page.
 */
function Variance({ minor, reason }: { minor: number; reason: string | null }) {
  if (minor === 0) {
    return (
      <span className="text-ok flex items-center gap-1 text-sm font-semibold">
        <span aria-hidden="true">✓</span> Balanced
      </span>
    );
  }
  const over = minor > 0;
  return (
    <span
      className={`flex items-center gap-1 text-sm font-semibold ${over ? 'text-pending' : 'text-danger'}`}
    >
      <span aria-hidden="true">{over ? '▲' : '▼'}</span>
      <span className="lum-money">{formatMinor(Math.abs(minor))}</span>
      <span>{over ? 'over' : 'short'}</span>
      {reason && (
        <span className="text-ink-3 font-normal">· {VARIANCE_REASON_LABEL[reason] ?? reason}</span>
      )}
    </span>
  );
}

// -------------------------------------------------------------------------- stock pointer

function StockPointer({ onOpenStock }: { onOpenStock: () => void }) {
  return (
    <section className="border-hair flex flex-col items-start gap-3 rounded border p-4">
      <h3 className="text-ink font-semibold">Stock on hand lives under Stock</h3>
      <p className="text-ink-3 max-w-prose text-sm">
        It is the sum of every movement, evaluated as it is read, and it is already a screen.
        Drawing it here as well would be a second presentation of one figure — and the day the two
        disagree, which one the owner believes is a coin toss.
      </p>
      <button
        type="button"
        onClick={onOpenStock}
        className="border-accent text-accent min-h-touch rounded border px-4 font-semibold"
      >
        Open stock on hand
      </button>
    </section>
  );
}

// -------------------------------------------------------------------------------- shared

function Figure({
  label,
  value,
  note,
  strong,
}: {
  label: string;
  value: string;
  note?: string;
  strong?: boolean;
}) {
  return (
    <div className="border-hair flex flex-col gap-1 rounded border p-3">
      <dt className="text-ink-3 text-xs uppercase tracking-wider">{label}</dt>
      <dd className={`lum-money text-ink ${strong ? 'text-2xl font-semibold' : 'text-lg'}`}>
        {value}
      </dd>
      {note && <span className="text-ink-3 text-xs">{note}</span>}
    </div>
  );
}

function Cell({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className="flex flex-col">
      <dt className="text-ink-3 text-xs uppercase tracking-wider">{label}</dt>
      <dd className="lum-money text-ink">
        {value}
        {note && <span className="text-ink-3 ml-1 text-xs">×{note}</span>}
      </dd>
    </div>
  );
}

function Problem({ message }: { message: string }) {
  return (
    <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
      {message}
    </p>
  );
}

/** The shop PC's own calendar day, as `YYYY-MM-DD`. The backend uses the same clock. */
function today(): string {
  return localDate(new Date());
}

function shiftDate(iso: string, days: number): string {
  // Built from the parts rather than by adding milliseconds to a timestamp: a day is not always
  // 86,400 seconds long, and `Date` handles the month and year rollover correctly on its own.
  const [year = 1970, month = 1, day = 1] = iso.split('-').map(Number);
  return localDate(new Date(year, month - 1, day + days));
}

/**
 * Formats a date in the machine's own timezone.
 *
 * <p>Not `toISOString().slice(0, 10)`, which is UTC: in Colombo that names yesterday for the first
 * five and a half hours of every day, so "Today" would open on the wrong report before mid-morning.
 */
function localDate(at: Date): string {
  return [
    at.getFullYear(),
    String(at.getMonth() + 1).padStart(2, '0'),
    String(at.getDate()).padStart(2, '0'),
  ].join('-');
}

function hourLabel(hour: number): string {
  return `${String(hour).padStart(2, '0')}:00`;
}

function formatDateTime(iso: string): string {
  const at = new Date(iso);
  return `${localDate(at)} ${String(at.getHours()).padStart(2, '0')}:${String(
    at.getMinutes(),
  ).padStart(2, '0')}`;
}
