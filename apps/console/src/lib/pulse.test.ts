import { describe, expect, it } from 'vitest';

import {
  busiest,
  pace,
  shopMinuteOfDay,
  slotLabel,
  SLOT_MINUTES,
  tallest,
  tradingWindow,
  weekdayName,
} from './pulse';
import type { PulseSlot } from './api';

/**
 * The arithmetic behind the pulse (M6-14).
 *
 * <p>Every one of these is a way the graphic could lie confidently rather than fail visibly: a
 * baseline summed past the current hour tells an owner they are having a disastrous day until
 * closing time, and a "now" line read off the viewer's clock puts a Sri Lankan afternoon in the
 * middle of a London morning. None of that throws.
 */

/** Ninety-six empty quarter hours, as the server always sends them. */
function day(overrides: Partial<PulseSlot>[] = []): PulseSlot[] {
  const slots: PulseSlot[] = [];
  for (let minute = 0; minute < 24 * 60; minute += SLOT_MINUTES) {
    slots.push({
      minuteOfDay: minute,
      saleCount: 0,
      totalMinor: 0,
      usualSaleCount: 0,
      usualTotalMinor: 0,
    });
  }
  for (const patch of overrides) {
    const index = slots.findIndex((slot) => slot.minuteOfDay === patch.minuteOfDay);
    slots[index] = { ...slots[index]!, ...patch };
  }
  return slots;
}

const at = (hour: number, minute = 0) => hour * 60 + minute;

describe('shopMinuteOfDay', () => {
  it('reads the shop’s clock, not the viewer’s', () => {
    // 04:30 UTC is 10:00 in Colombo. An owner in London must see the shop's morning, not their own.
    expect(shopMinuteOfDay(new Date('2026-08-31T04:30:00Z'))).toBe(at(10));
  });

  it('places a sale just before midnight in the last slot of the same day', () => {
    expect(shopMinuteOfDay(new Date('2026-08-31T18:20:00Z'))).toBe(at(23, 50));
  });

  it('wraps midnight to zero rather than to the far side of the graphic', () => {
    expect(shopMinuteOfDay(new Date('2026-08-31T18:30:00Z'))).toBe(0);
  });
});

describe('tradingWindow', () => {
  it('draws a plausible day for a shop with no history at all', () => {
    expect(tradingWindow(day(), null)).toEqual({ fromMinute: at(8), toMinute: at(20) });
  });

  it('covers the trade that happened and the trade that usually happens', () => {
    const slots = day([
      { minuteOfDay: at(9), saleCount: 2 },
      { minuteOfDay: at(18), usualSaleCount: 1.5 },
    ]);

    // A quarter hour of air on the left, and the last bar's own width plus one on the right.
    expect(tradingWindow(slots, null)).toEqual({ fromMinute: at(8, 45), toMinute: at(18, 30) });
  });

  it('always stretches to the moment the day has reached', () => {
    // A morning's trade, read at eight in the evening. Without this the "now" line would fall off
    // the right-hand edge and an empty evening would read as missing data rather than a quiet one.
    const slots = day([{ minuteOfDay: at(9), saleCount: 2 }]);

    expect(tradingWindow(slots, at(20, 5)).toMinute).toBe(at(20, 15));
  });

  it('never runs past the ends of the day', () => {
    const slots = day([
      { minuteOfDay: 0, saleCount: 1 },
      { minuteOfDay: at(23, 45), saleCount: 1 },
    ]);

    expect(tradingWindow(slots, null)).toEqual({ fromMinute: 0, toMinute: 24 * 60 });
  });
});

describe('pace', () => {
  const trading = day([
    {
      minuteOfDay: at(9),
      saleCount: 2,
      totalMinor: 40_000,
      usualSaleCount: 1,
      usualTotalMinor: 25_000,
    },
    {
      minuteOfDay: at(11),
      saleCount: 3,
      totalMinor: 60_000,
      usualSaleCount: 2,
      usualTotalMinor: 35_000,
    },
    {
      minuteOfDay: at(17),
      saleCount: 4,
      totalMinor: 90_000,
      usualSaleCount: 4,
      usualTotalMinor: 80_000,
    },
  ]);

  it('compares today so far against a normal day so far, not against a whole one', () => {
    // The single most likely way for this figure to be wrong and believed: at half past eleven the
    // evening has not happened yet, on either day.
    const sofar = pace(trading, at(11, 30));

    expect(sofar.saleCount).toBe(5);
    expect(sofar.takenMinor).toBe(100_000);
    expect(sofar.usualTotalMinor).toBe(60_000);
    expect(sofar.aheadMinor).toBe(40_000);
  });

  it('includes the quarter hour the shop is in right now', () => {
    expect(pace(trading, at(9)).saleCount).toBe(2);
  });

  it('rounds the baseline to whole minor units, because half a cent is not a fact', () => {
    const slots = day([{ minuteOfDay: at(9), usualSaleCount: 1, usualTotalMinor: 333.6 }]);

    expect(pace(slots, at(10)).usualTotalMinor).toBe(334);
  });

  it('says a weekday with no history is not comparable rather than comparing against zero', () => {
    // Otherwise a shop in its first month is told it is having a record day, every day.
    const slots = day([{ minuteOfDay: at(9), saleCount: 2, totalMinor: 40_000 }]);

    expect(pace(slots, at(12)).comparable).toBe(false);
  });

  it('is comparable on the strength of the whole day, not the elapsed part of it', () => {
    // An evening-only baseline read at nine in the morning is still a baseline; "nothing yet today"
    // and "this weekday has never traded" are different sentences.
    const slots = day([{ minuteOfDay: at(18), usualSaleCount: 3, usualTotalMinor: 50_000 }]);

    expect(pace(slots, at(9)).comparable).toBe(true);
  });
});

describe('one scale for both rows', () => {
  it('takes the taller of today and a normal day', () => {
    // Scaling the rows separately would make a quiet day look like a busy one, which is the only
    // thing the graphic exists to tell apart.
    const slots = day([
      { minuteOfDay: at(9), saleCount: 3 },
      { minuteOfDay: at(11), usualSaleCount: 7.5 },
    ]);

    expect(tallest(slots)).toBe(7.5);
  });

  it('never divides by zero on a shop that has not sold anything', () => {
    expect(tallest(day())).toBe(1);
  });
});

describe('busiest', () => {
  it('names the quarter hour with the most sales in it', () => {
    const slots = day([
      { minuteOfDay: at(9), saleCount: 3 },
      { minuteOfDay: at(11), saleCount: 6 },
    ]);

    expect(busiest(slots)?.minuteOfDay).toBe(at(11));
  });

  it('is null before the first sale, so the caption says so instead of naming midnight', () => {
    expect(busiest(day())).toBeNull();
  });
});

describe('labels', () => {
  it('writes the shop clock the way a person says it', () => {
    expect(slotLabel(0)).toBe('12am');
    expect(slotLabel(at(9))).toBe('9am');
    expect(slotLabel(at(12))).toBe('12pm');
    expect(slotLabel(at(14, 15))).toBe('2:15pm');
  });

  it('names the weekday in the shop’s zone', () => {
    // Half past midnight in London on the 1st is still Monday the 31st in Kandy, and the sentence
    // compares against Mondays.
    expect(weekdayName(new Date('2026-08-31T23:30:00Z'))).toBe('Tuesday');
    expect(weekdayName(new Date('2026-08-31T17:00:00Z'))).toBe('Monday');
  });
});
