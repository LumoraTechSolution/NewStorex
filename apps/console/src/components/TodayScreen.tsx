'use client';

import { formatMinor } from '@lumora/domain';

import { Card, CardGrid, Chip, Empty, Headline, Row } from './Chrome';
import { Money, Takings } from './Money';
import { Pulse } from './Pulse';
import { ShopState } from './ShopState';
import { pace, shopClock, shopMinuteOfDay, weekdayName } from '@/lib/pulse';
import type { BranchTotal, OperatorDay, PulseSlot, RecentSale, Today } from '@/lib/api';

/**
 * The screen an owner opens (M4-06, redesigned in M6-14).
 *
 * <h2>The hero is not a number</h2>
 *
 * It was, and it answered nothing. "Rs 84,300" tells a shopkeeper roughly what they already
 * expected and nothing they can act on. The order here is the order of the questions actually being
 * asked: <em>is the shop alright</em> (the state line), <em>how is it going</em> (the takings,
 * priced against a normal one of this weekday), <em>when was it busy</em> (the pulse), <em>who is
 * doing it</em> (the till), <em>is it still ticking</em> (recent sales).
 *
 * <h2>The sync time sits beside the money, not in a settings screen</h2>
 *
 * This is a cloud reading the output of an outbox, so the figure is only as fresh as the last
 * drain. A till that stopped syncing at lunchtime would otherwise show a plausible, wrong, quietly
 * shrinking total all afternoon — and the owner would believe it, because there is nothing on the
 * screen to suggest otherwise. Amber and a plain sentence when it goes stale, per §A: colour never
 * carries the meaning on its own.
 *
 * <h2>One number per fact</h2>
 *
 * The takings and the sale count come from `/today`. The pulse supplies only the <em>baseline</em>
 * — what a normal one of this weekday had taken by this hour — and never a second total of its own.
 * Two endpoints each rendering "what the shop took" is how a dashboard starts disagreeing with
 * itself, and the day it happens is the day a till syncs halfway through the afternoon.
 */
const STALE_AFTER_MINUTES = 30;

export function TodayScreen({
  today,
  branches,
  recent,
  operators,
  pulse,
}: {
  today: Today;
  branches: BranchTotal[];
  recent: RecentSale[];
  operators: OperatorDay[];
  pulse: PulseSlot[];
}) {
  const staleness = describeSync(today.lastSyncAt);
  const nowMinute = shopMinuteOfDay();
  const normal = pace(pulse, nowMinute);

  return (
    <>
      <ShopState operators={operators} recent={recent} />

      {/* Full width at every size: the day's takings are the answer, not one card among three. */}
      <Card
        title="Taken today"
        aside={
          <span className={staleness.stale ? 'text-pending' : undefined}>
            {staleness.stale ? '! ' : ''}
            {staleness.text}
          </span>
        }
      >
        <Headline>
          <Takings minor={today.totalMinor} />
        </Headline>
        <p className="text-ink-2 text-sm">
          {today.saleCount === 0
            ? 'No sales yet today'
            : `${today.saleCount} ${today.saleCount === 1 ? 'sale' : 'sales'}`}
          {' · '}
          {describePaceAgainstNormal(today.totalMinor, normal.usualTotalMinor, normal.comparable)}
        </p>
        <Pulse slots={pulse} nowMinute={nowMinute} />
      </Card>

      <CardGrid>
        <Card
          title="On the till today"
          footer="Takings are counted against whoever opened the shift. The drawer figure is the count at close."
        >
          {operators.length === 0 ? (
            <Empty>Nobody has opened a till today.</Empty>
          ) : (
            operators.map((person) => (
              <Row key={person.operatorClientUuid ?? 'unrecorded'}>
                <span className="flex min-w-0 flex-col gap-0.5">
                  {/* Never hidden when the name is missing: dropping the row would make this card
                      disagree with the day's total above it, which is a worse lie than a blank. */}
                  <span className="font-medium">{person.operator ?? 'Not recorded'}</span>
                  <span className="text-ink-3 text-xs">{describeOperator(person)}</span>
                </span>
                <span className="flex flex-none items-center gap-2 whitespace-nowrap">
                  <Money minor={person.totalMinor} />
                  {person.varianceMinor !== 0 && (
                    <Chip tone={person.varianceMinor < 0 ? 'danger' : 'pending'}>
                      {person.varianceMinor < 0 ? 'Short' : 'Over'}
                    </Chip>
                  )}
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
                <span className="flex min-w-0 flex-col gap-0.5">
                  <span className="font-mono text-sm">{sale.invoiceNumber}</span>
                  <span className="text-ink-3 text-xs">{shopClock(sale.soldAt)}</span>
                </span>
                <Money minor={sale.totalMinor} />
              </Row>
            ))
          )}
        </Card>

        {/*
          Only when there is more than one. A single-branch shop — which is every v1 shop — got a
          card that repeated the figure above it under a different heading, and a screen that says
          the same thing twice teaches people to skim past the half that will one day differ.
        */}
        {branches.length > 1 && (
          <Card title="Branches">
            {branches.map((branch) => (
              <Row key={branch.branchCode}>
                <span className="font-medium">{branch.branchCode}</span>
                <span className="flex flex-col items-end">
                  <Money minor={branch.totalMinor} />
                  <span className="text-ink-3 text-xs">{branch.saleCount} sales</span>
                </span>
              </Row>
            ))}
          </Card>
        )}
      </CardGrid>
    </>
  );
}

/**
 * The takings, priced (M6-14).
 *
 * <p>A figure on its own is unreadable: nobody knows whether Rs 84,300 by two o'clock is a good
 * Monday. Against what the shop itself usually does by this hour, it is an answer.
 *
 * <p>Ahead is green because it is genuinely good news. Behind is <em>not</em> red — a quiet Tuesday
 * is not an error, and colouring it like one would make the screen shout on half the days of a
 * normal year, which is how people learn to ignore a colour.
 */
export function describePaceAgainstNormal(
  takenMinor: number,
  usualMinor: number,
  comparable: boolean,
): React.ReactNode {
  const weekday = weekdayName();
  if (!comparable) {
    // A shop in its first month of trading on this weekday. Saying so is better than a zero
    // baseline, which would report every new shop as having a record day.
    return `no other ${weekday} to compare with yet`;
  }

  // To the rupee, and deliberately not to the cent. The baseline is an average of four Mondays;
  // "Rs 26,941.33 ahead" claims a precision the comparison does not have, and the cents are the
  // half of it a reader has to skip past to get to the number that means something. Still through
  // formatMinor — one money formatter in this product, which is what §A is actually about.
  const difference = Math.round((takenMinor - usualMinor) / 100) * 100;
  // Under a rupee either way is the same day twice. "Rs 0.40 ahead" is noise presented as insight.
  if (difference === 0) return `level with a normal ${weekday} by this hour`;

  return difference > 0 ? (
    <>
      <b className="text-ok font-semibold">Rs {formatMinor(difference)} ahead</b> of a normal{' '}
      {weekday} by this hour
    </>
  ) : (
    <>
      Rs {formatMinor(-difference)} behind a normal {weekday} by this hour
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

/**
 * The line under somebody's name.
 *
 * <p>§A: colour never carries meaning on its own, and here there is not even a colour — a variance
 * beside somebody's name is a sentence a person may have to answer for, so it says which direction
 * it went rather than relying on a minus sign nobody reads on a phone.
 */
export function describeOperator(person: OperatorDay): string {
  const parts = [`${person.saleCount} ${person.saleCount === 1 ? 'sale' : 'sales'}`];
  if (person.onNow) {
    parts.push(person.openedAt === null ? 'on now' : `on now since ${shopClock(person.openedAt)}`);
  }
  if (person.shiftCount > 1) parts.push(`${person.shiftCount} shifts`);
  if (person.varianceMinor !== 0) {
    const amount = formatMinor(Math.abs(person.varianceMinor));
    parts.push(`drawer ${amount} ${person.varianceMinor < 0 ? 'short' : 'over'}`);
  }
  return parts.join(' · ');
}
