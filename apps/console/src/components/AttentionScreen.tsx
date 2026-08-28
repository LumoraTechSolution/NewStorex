'use client';

import { Card, Empty, Row } from './Chrome';
import { Money } from './Money';
import type { CashVariance } from '@/lib/api';

/**
 * The attention feed (M4-07).
 *
 * <h2>The screen that makes somebody open the app on a Sunday</h2>
 *
 * Takings are pleasant to look at and rarely actionable. This is the one that pays for the product:
 * <em>something does not add up, look here</em>. Cash variance and stock variance are the same
 * shape of problem, which is why M4-07 puts them on one screen; only the cash half exists so far,
 * and the stock half joins it when there is a stock screen to send somebody to.
 *
 * <h2>Over is not good news</h2>
 *
 * A drawer with more in it than expected reads like luck and usually means a sale nobody rang up.
 * Both directions are flagged, and the direction is written in words rather than left to a colour
 * and a minus sign — §A, and also the difference between a glance that informs and one that
 * misleads.
 */
export function AttentionScreen({ variances }: { variances: CashVariance[] }) {
  return (
    <Card
      title="Needs a look"
      footer="Shifts that closed with the drawer out by more than LKR 100.00, in the last 14 days."
    >
      {variances.length === 0 ? (
        <Empty>Nothing needs attention. Every shift balanced.</Empty>
      ) : (
        variances.map((variance) => (
          <Row key={variance.shiftClientUuid}>
            <span className="flex flex-col">
              <span className="font-medium">
                {variance.branchCode} · {variance.terminalCode}
              </span>
              <span className="text-ink-3 text-xs">
                {new Date(variance.closedAt).toLocaleDateString([], {
                  day: 'numeric',
                  month: 'short',
                })}
                {variance.varianceReason ? ` · ${variance.varianceReason}` : ''}
              </span>
            </span>
            <span className="flex flex-col items-end">
              <Money
                minor={Math.abs(variance.varianceMinor)}
                className={variance.varianceMinor < 0 ? 'text-danger' : 'text-pending'}
              />
              <span className="text-ink-3 text-xs">
                {variance.varianceMinor < 0 ? 'short' : 'over'}
              </span>
            </span>
          </Row>
        ))
      )}
    </Card>
  );
}
