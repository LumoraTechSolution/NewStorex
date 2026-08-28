'use client';

import { formatMinor } from '@lumora/domain';

import { Card, Empty, Headline } from './Chrome';
import { Money } from './Money';
import type { DailyTotal } from '@/lib/api';

/**
 * The trend (M4-06).
 *
 * <h2>Bars drawn in CSS, not a charting library</h2>
 *
 * Fourteen horizontal bars is the whole requirement, and every charting library costs more in
 * bundle size than this screen costs in total. It also keeps the figure printed beside each bar,
 * which is what an owner actually reads — the bar is for the shape of the fortnight, the number is
 * for the answer.
 *
 * <h2>The bars get longer, not narrower</h2>
 *
 * On a phone the fourteen days stack. On a wider screen they stay stacked rather than splitting
 * into two columns of seven: a fortnight read top-to-bottom is one trend, and the same fortnight in
 * two columns is two shorter ones the eye has to join back up. What the extra width buys is a
 * longer bar, which is exactly what makes the shape easier to read.
 *
 * <p>Every day appears, including the ones with no sales. The server generates the date series for
 * exactly that reason: a chart that omits a closed Sunday draws straight through it and says the
 * opposite of what happened.
 */
export function TrendScreen({ trend }: { trend: DailyTotal[] }) {
  if (trend.length === 0) {
    return (
      <Card title="Last 14 days">
        <Empty>No history yet.</Empty>
      </Card>
    );
  }

  const peak = Math.max(...trend.map((d) => d.totalMinor), 1);
  const total = trend.reduce((sum, d) => sum + d.totalMinor, 0);
  const tradingDays = trend.filter((d) => d.saleCount > 0).length;

  return (
    <>
      <Card title="Last 14 days">
        <Headline>
          <Money minor={total} />
        </Headline>
        <p className="text-ink-2 text-sm">
          {tradingDays} trading {tradingDays === 1 ? 'day' : 'days'}
          {tradingDays > 0 && <> · {formatMinor(Math.round(total / tradingDays))} a day</>}
        </p>
      </Card>

      <Card title="By day">
        <ol className="flex flex-col gap-2 md:gap-3">
          {trend.map((day) => (
            <li key={day.day} className="flex flex-col gap-1">
              <div className="flex items-baseline justify-between text-xs md:text-sm">
                <span className="text-ink-2">{dayLabel(day.day)}</span>
                <Money minor={day.totalMinor} className="text-ink" />
              </div>
              {/* Presentational only — the figure above it is the accessible value. */}
              <div className="bg-page h-2 w-full overflow-hidden rounded md:h-3" aria-hidden="true">
                <div
                  className="bg-accent h-full rounded"
                  style={{ width: `${barWidth(day.totalMinor, peak)}%` }}
                />
              </div>
            </li>
          ))}
        </ol>
      </Card>
    </>
  );
}

/** A day with sales always draws something, so a thin day is visibly different from a shut one. */
function barWidth(totalMinor: number, peak: number): number {
  if (totalMinor === 0) return 0;
  return Math.max((totalMinor / peak) * 100, 2);
}

function dayLabel(day: string): string {
  // Parsed as a local date rather than a UTC instant: `new Date('2026-08-25')` is midnight UTC,
  // which in Colombo is still the 25th but in the Americas is the 24th — the label would be off by
  // one for anybody travelling, which is precisely who this app is for.
  const [year, month, date] = day.split('-').map(Number);
  const local = new Date(year!, month! - 1, date!);
  return local.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' });
}
