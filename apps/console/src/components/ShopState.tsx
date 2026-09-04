'use client';

import type { OperatorDay, RecentSale } from '@/lib/api';
import { shopClock } from '@/lib/pulse';

/**
 * The first line on the screen (M6-14), and the argument the whole redesign rests on.
 *
 * <h2>An owner does not open this to read a number</h2>
 *
 * They open it to find out whether the shop is alright. <em>Open · Nimal since 9:04 · last sale 6
 * minutes ago</em> is that question answered in one line, and it is only answerable now that M6-13
 * put a person on a cloud shift. The takings follow it rather than lead it — a figure is what you
 * check once you already know the shop is running.
 *
 * <h2>What it must not do is guess</h2>
 *
 * "Open" here means a shift the cloud has not been told is closed, which is also what a till that
 * stopped syncing mid-afternoon looks like. So this line never appears without the sync time beside
 * the takings under it, and it says nothing at all about a shop it has heard nothing from — a
 * confident "Closed" over a till that has been offline since lunchtime is worse than a blank.
 */
export function ShopState({
  operators,
  recent,
}: {
  operators: OperatorDay[];
  recent: RecentSale[];
}) {
  const state = describeShop(operators, recent);

  return (
    <p className="bg-surface border-hair text-ink-2 flex items-center gap-2.5 rounded-xl border px-3.5 py-2.5 text-sm shadow-[0_1px_2px_rgba(20,27,24,0.05)]">
      <span
        className={`relative h-2 w-2 flex-none rounded-full ${state.open ? 'bg-ok' : 'bg-ink-3'}`}
        aria-hidden="true"
      >
        {/* The one live thing on the page. It says "this is now" about a screen whose every other
            number is as old as the last sync — and it is decoration, so it goes under reduced
            motion and the word beside it carries the meaning either way. */}
        {state.open && (
          <span className="bg-ok absolute inset-0 animate-ping rounded-full opacity-40 motion-reduce:animate-none" />
        )}
      </span>
      <span>
        <strong className="text-ink font-semibold">{state.label}</strong>
        {state.details.map((detail) => (
          <span key={detail}> · {detail}</span>
        ))}
      </span>
    </p>
  );
}

export interface ShopStateReading {
  open: boolean;
  label: string;
  details: string[];
}

/**
 * The sentence, as data.
 *
 * <p>Separated from the markup so it can be tested without a DOM — every branch of it is a claim
 * about a real shop, and the wrong branch is a screen that says a closed shop is trading.
 */
export function describeShop(
  operators: OperatorDay[],
  recent: RecentSale[],
  now: Date = new Date(),
): ShopStateReading {
  const onNow = operators.filter((person) => person.onNow);
  const details: string[] = [];

  if (onNow.length > 0) {
    // Names, not a count: "2 people on" is a statistic, and the point of this line is that the
    // owner knows who is in the shop.
    const names = onNow.map((person) => person.operator ?? 'Someone').join(' and ');
    const since = earliest(onNow);
    details.push(since === null ? `${names} on the till` : `${names} on the till since ${since}`);
  }

  const lastSale = recent[0];
  if (lastSale) details.push(`last sale ${ago(lastSale.soldAt, now)}`);

  if (onNow.length === 0) {
    return {
      open: false,
      // A shop that has not opened yet and a shop that shut an hour ago are both "no till open",
      // and this app cannot tell them apart from a shift table. So it says the thing it knows.
      label: operators.length === 0 ? 'No till opened today' : 'No till open',
      details,
    };
  }

  return { open: true, label: 'Open', details };
}

function earliest(operators: OperatorDay[]): string | null {
  const times = operators
    .map((person) => person.openedAt)
    .filter((at): at is string => at !== null)
    .sort();
  const first = times[0];
  return first === undefined ? null : shopClock(first);
}

/**
 * "6 minutes ago".
 *
 * <p>Relative rather than a clock time, because the question this answers is "is anything
 * happening", and "2:08 pm" makes the reader do the subtraction to find out.
 */
export function ago(iso: string, now: Date = new Date()): string {
  const minutes = Math.floor((now.getTime() - new Date(iso).getTime()) / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes === 1) return '1 minute ago';
  if (minutes < 60) return `${minutes} minutes ago`;
  const hours = Math.floor(minutes / 60);
  if (hours === 1) return 'an hour ago';
  if (hours < 24) return `${hours} hours ago`;
  const days = Math.floor(hours / 24);
  return days === 1 ? 'yesterday' : `${days} days ago`;
}
