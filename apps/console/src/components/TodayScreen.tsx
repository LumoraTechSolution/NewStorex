'use client';

import { Card, CardGrid, Empty, Headline, Row } from './Chrome';
import { Money } from './Money';
import type { BranchTotal, RecentSale, Today } from '@/lib/api';

/**
 * The headline (M4-06) — what the shop has taken today.
 *
 * <h2>The sync time sits beside the money, not in a settings screen</h2>
 *
 * This is a cloud reading the output of an outbox, so the figure is only as fresh as the last
 * drain. A till that stopped syncing at lunchtime would otherwise show a plausible, wrong, quietly
 * shrinking total all afternoon — and the owner would believe it, because there is nothing on the
 * screen to suggest otherwise. Amber and a plain sentence when it goes stale, per §A: colour never
 * carries the meaning on its own.
 */
const STALE_AFTER_MINUTES = 30;

export function TodayScreen({
  today,
  branches,
  recent,
}: {
  today: Today;
  branches: BranchTotal[];
  recent: RecentSale[];
}) {
  const staleness = describeSync(today.lastSyncAt);

  return (
    <>
      {/* Full width at every size: the day's takings are the answer, not one card among three. */}
      <Card title="Today">
        <Headline>
          <Money minor={today.totalMinor} />
        </Headline>
        <p className="text-ink-2 text-sm">
          {today.saleCount === 0
            ? 'No sales yet today'
            : `${today.saleCount} ${today.saleCount === 1 ? 'sale' : 'sales'}`}
        </p>
        <p
          className={`flex items-center gap-2 text-xs ${
            staleness.stale ? 'text-pending' : 'text-ink-3'
          }`}
        >
          <span aria-hidden="true">{staleness.stale ? '!' : 'OK'}</span>
          <span>{staleness.text}</span>
        </p>
      </Card>

      <CardGrid>
        <Card title="Branches">
          {branches.length === 0 ? (
            <Empty>Nothing has come in from any branch today.</Empty>
          ) : (
            branches.map((branch) => (
              <Row key={branch.branchCode}>
                <span className="font-medium">{branch.branchCode}</span>
                <span className="flex flex-col items-end">
                  <Money minor={branch.totalMinor} />
                  <span className="text-ink-3 text-xs">{branch.saleCount} sales</span>
                </span>
              </Row>
            ))
          )}
        </Card>

        <Card title="Recent sales">
          {recent.length === 0 ? (
            <Empty>No sales recorded yet.</Empty>
          ) : (
            recent.slice(0, 10).map((sale) => (
              <Row key={sale.invoiceNumber}>
                <span className="flex flex-col">
                  <span className="font-mono text-sm">{sale.invoiceNumber}</span>
                  <span className="text-ink-3 text-xs">{timeOf(sale.soldAt)}</span>
                </span>
                <Money minor={sale.totalMinor} />
              </Row>
            ))
          )}
        </Card>
      </CardGrid>
    </>
  );
}

export function describeSync(lastSyncAt: string | null): { text: string; stale: boolean } {
  if (lastSyncAt === null) {
    // Not an error and not necessarily a problem — a shop that has not opened yet looks exactly
    // like this. Saying so plainly beats an alarming empty state.
    return { text: 'Nothing has arrived from the shop today', stale: false };
  }
  const minutes = Math.floor((Date.now() - new Date(lastSyncAt).getTime()) / 60_000);
  if (minutes < 1) return { text: 'Up to date, just now', stale: false };
  if (minutes < STALE_AFTER_MINUTES)
    return { text: `Up to date, ${minutes} min ago`, stale: false };
  if (minutes < 120) return { text: `Last update ${minutes} min ago`, stale: true };
  return { text: `Last update ${Math.floor(minutes / 60)} hours ago`, stale: true };
}

function timeOf(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}
