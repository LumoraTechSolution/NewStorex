import type { PulseSlot } from '@/lib/api';

/**
 * The arithmetic behind the pulse (M6-14) — every part of it that can be wrong without looking it.
 *
 * <h2>Why this is a module and not a few lines inside the component</h2>
 *
 * The graphic is the console's signature and it is the easiest thing on the screen to make lie: a
 * window computed off by one slot, a baseline summed past the current hour, a "now" line placed
 * from the wrong clock. None of that throws, none of it looks broken, and all of it produces a
 * confident picture of a day the shop did not have. So the numbers are pure functions with tests
 * and the component only draws what they return.
 *
 * <h2>One array, every number</h2>
 *
 * The sentence above the graphic and the graphic itself are computed from the same slots. Two
 * sources for one fact — a sale count from `/today` beside bars drawn from `/pulse` — is how a
 * dashboard starts disagreeing with itself quietly, at the worst possible moment: the day a till
 * stops syncing halfway through the afternoon.
 */

/**
 * The shop's clock, which is not necessarily the viewer's.
 *
 * <p>This app is for an owner who is somewhere else — that is the entire reason it exists — so
 * "now" has to mean the shop's now. The cloud already defaults every console query to this zone;
 * naming it again here rather than reading a local `Date` keeps the "now" line on the graphic in
 * the same day as the bars underneath it when the owner is in London.
 */
export const SHOP_ZONE = 'Asia/Colombo';

/** Ninety-six quarter hours. The server always sends all of them, and this asserts nothing less. */
export const SLOT_MINUTES = 15;

/** What a shop that has never traded shows: a plausible day, so the axis is not empty. */
const DEFAULT_WINDOW = { fromMinute: 8 * 60, toMinute: 20 * 60 };

/** Minutes since midnight in the shop's own zone. */
export function shopMinuteOfDay(now: Date = new Date(), zone: string = SHOP_ZONE): number {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: zone,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(now);
  const hour = Number(parts.find((p) => p.type === 'hour')?.value ?? '0');
  const minute = Number(parts.find((p) => p.type === 'minute')?.value ?? '0');
  // 24:00 rather than 00:00 is legal output for midnight in some engines, and it would place the
  // marker a whole day to the right of a graphic that stops at 23:45.
  return ((hour % 24) * 60 + minute) % (24 * 60);
}

export interface Window {
  fromMinute: number;
  toMinute: number;
}

/**
 * The hours worth drawing.
 *
 * <p>Ninety-six slots across a phone is two pixels a slot, and sixty of them are the middle of the
 * night. The window is the trade that happened plus the trade that usually happens, padded by a
 * quarter hour so the first and last bars are not flush against the edge — and always stretched to
 * include the moment the day has reached, because the "now" line is the thing that stops an empty
 * afternoon reading as missing data.
 */
export function tradingWindow(slots: PulseSlot[], nowMinute: number | null): Window {
  const active = slots.filter((slot) => slot.saleCount > 0 || slot.usualSaleCount > 0);
  if (active.length === 0) {
    return nowMinute === null
      ? DEFAULT_WINDOW
      : {
          fromMinute: Math.min(DEFAULT_WINDOW.fromMinute, floorToSlot(nowMinute)),
          toMinute: Math.max(DEFAULT_WINDOW.toMinute, floorToSlot(nowMinute) + SLOT_MINUTES),
        };
  }

  let fromMinute = active[0]!.minuteOfDay - SLOT_MINUTES;
  let toMinute = active[active.length - 1]!.minuteOfDay + 2 * SLOT_MINUTES;
  if (nowMinute !== null) {
    fromMinute = Math.min(fromMinute, floorToSlot(nowMinute));
    toMinute = Math.max(toMinute, floorToSlot(nowMinute) + SLOT_MINUTES);
  }
  return {
    fromMinute: Math.max(0, fromMinute),
    toMinute: Math.min(24 * 60, toMinute),
  };
}

function floorToSlot(minute: number): number {
  return Math.floor(minute / SLOT_MINUTES) * SLOT_MINUTES;
}

export interface Pace {
  saleCount: number;
  takenMinor: number;
  /** Rounded to whole minor units: `formatMinor` takes an integer, and half a cent is not a fact. */
  usualTotalMinor: number;
  usualSaleCount: number;
  /** Positive means ahead of a normal one of this weekday by this hour. */
  aheadMinor: number;
  /** False when the shop has no trading history on this weekday yet, so there is nothing to price against. */
  comparable: boolean;
}

/**
 * Today so far, against a normal one of this weekday so far.
 *
 * <p>"So far" is the whole point. Comparing a day at two in the afternoon against a full normal
 * Monday would tell every owner they were having a disastrous day until closing time, which is the
 * single most likely way for this figure to be both wrong and believed.
 */
export function pace(slots: PulseSlot[], nowMinute: number): Pace {
  const elapsed = slots.filter((slot) => slot.minuteOfDay <= nowMinute);
  const takenMinor = elapsed.reduce((sum, slot) => sum + slot.totalMinor, 0);
  const saleCount = elapsed.reduce((sum, slot) => sum + slot.saleCount, 0);
  const usualTotal = elapsed.reduce((sum, slot) => sum + slot.usualTotalMinor, 0);
  const usualSales = elapsed.reduce((sum, slot) => sum + slot.usualSaleCount, 0);
  // The baseline is judged over the whole day, not the elapsed part: a shop that only ever trades
  // in the evening has no history by nine in the morning, and "nothing to compare with" then is a
  // different statement from "this weekday has never traded".
  const comparable = slots.some((slot) => slot.usualSaleCount > 0);

  return {
    saleCount,
    takenMinor,
    usualTotalMinor: Math.round(usualTotal),
    usualSaleCount: usualSales,
    aheadMinor: takenMinor - Math.round(usualTotal),
    comparable,
  };
}

/** The tallest bar in the window, and the one scale both rows are drawn on. */
export function tallest(slots: PulseSlot[]): number {
  return Math.max(1, ...slots.map((slot) => Math.max(slot.saleCount, slot.usualSaleCount)));
}

/** The quarter hour the shop was busiest in, for the sentence a screen reader gets. */
export function busiest(slots: PulseSlot[]): PulseSlot | null {
  let best: PulseSlot | null = null;
  for (const slot of slots) {
    if (slot.saleCount > 0 && (best === null || slot.saleCount > best.saleCount)) best = slot;
  }
  return best;
}

/**
 * A timestamp on the shop's clock — "9:04 am".
 *
 * <p>Composed from parts rather than handed to `toLocaleTimeString`, which is not the pedantry it
 * looks. That returns "9:04 AM" under Node's ICU and "9:04 am" under a browser's, so the screen and
 * the test that checks it disagree about a string neither of them chose. The shop's zone rather
 * than the viewer's, because an owner reading this from another country still wants the time their
 * cashier looked at.
 */
export function shopClock(iso: string, zone: string = SHOP_ZONE): string {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: zone,
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  }).formatToParts(new Date(iso));
  const value = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return `${value('hour')}:${value('minute')} ${value('dayPeriod').toLowerCase()}`.trim();
}

/** "9:15am" — an axis label, which is the same clock with the space squeezed out of it. */
export function slotLabel(minuteOfDay: number): string {
  const hour = Math.floor(minuteOfDay / 60);
  const minute = minuteOfDay % 60;
  const suffix = hour < 12 ? 'am' : 'pm';
  const twelve = hour % 12 === 0 ? 12 : hour % 12;
  return minute === 0
    ? `${twelve}${suffix}`
    : `${twelve}:${String(minute).padStart(2, '0')}${suffix}`;
}

/**
 * The weekday the baseline is made of, named so the sentence says what it compared against.
 *
 * <p>In the shop's zone for the same reason "now" is: an owner reading this at half past midnight
 * in London is looking at a Sri Lankan Monday, and a sentence that called it Sunday would be
 * comparing the right numbers under the wrong name.
 */
export function weekdayName(day: Date = new Date(), zone: string = SHOP_ZONE): string {
  return day.toLocaleDateString([], { weekday: 'long', timeZone: zone });
}
