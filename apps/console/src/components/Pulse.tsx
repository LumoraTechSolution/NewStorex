'use client';

import type { PulseSlot } from '@/lib/api';
import { busiest, slotLabel, SLOT_MINUTES, tallest, tradingWindow, weekdayName } from '@/lib/pulse';

/**
 * The pulse (M6-14) — today's trade and a normal one of this weekday, facing each other.
 *
 * <h2>Why two profiles and not a line chart</h2>
 *
 * "Rs 84,300 by two o'clock" answers nothing on its own: an owner cannot tell a good Monday from a
 * bad one without knowing what a Monday does. Two shapes across the day answer that at a glance,
 * and answer a second question nobody asked but everybody wants — <em>when</em> the shop is busy,
 * which is the only real input to deciding whether a second person is worth paying for.
 *
 * <p>An owner on a phone does not read an axis. They read density. So today rises from the line and
 * a normal day hangs beneath it, and the comparison is made by the eye rather than by arithmetic.
 *
 * <h2>Two things here are load-bearing</h2>
 *
 * <b>One scale for both rows.</b> Two scales would normalise each profile to its own height and
 * make a quiet day look like a busy one — which is the only thing this graphic exists to tell
 * apart, so scaling them separately would leave it worse than nothing.
 *
 * <b>The quarter hour, not the sale.</b> The first version of this drew one tick per sale, which
 * was truer and unreadable: a hundred and fifty sales across a phone-width morning is a tick every
 * 1.3 pixels and the graphic collapses into a solid slab. Binning keeps what somebody actually
 * reads off it at any width.
 *
 * <h2>It is a picture, and a picture is not a reading</h2>
 *
 * The plot is hidden from assistive technology and a sentence carries the same facts, rather than
 * ninety-six unlabelled bars being announced one after another. The sentence above it — the
 * takings, and how they compare — is the accessible version of the whole card.
 */
export function Pulse({ slots, nowMinute }: { slots: PulseSlot[]; nowMinute: number | null }) {
  const window = tradingWindow(slots, nowMinute);
  const span = window.toMinute - window.fromMinute;
  const shown = slots.filter(
    (slot) => slot.minuteOfDay >= window.fromMinute && slot.minuteOfDay < window.toMinute,
  );
  const scale = tallest(shown);
  const width = (SLOT_MINUTES / span) * 100;
  const currentSlot =
    nowMinute === null ? null : Math.floor(nowMinute / SLOT_MINUTES) * SLOT_MINUTES;

  const left = (minute: number) => ((minute - window.fromMinute) / span) * 100;
  const peak = busiest(shown);

  return (
    <figure className="m-0 mt-4">
      <div className="relative h-[74px] md:h-[92px]" aria-hidden="true">
        <div className="bg-hair absolute inset-x-0 top-1/2 h-px" />

        {shown.map((slot, index) => (
          <div key={slot.minuteOfDay}>
            {/* A normal day, below the line. Never after "now": the baseline is the whole day and
                showing all of it is the point — it is what the afternoon is measured against. */}
            {slot.usualSaleCount > 0 && (
              <span
                className="lum-pulse-bar absolute top-1/2 origin-top rounded-b-sm opacity-45"
                style={{
                  left: `${left(slot.minuteOfDay)}%`,
                  width: `calc(${width}% - 1.5px)`,
                  height: `${Math.max(2, (slot.usualSaleCount / scale) * 26)}px`,
                  background: 'var(--lum-ink-3)',
                  animationDelay: `${index * 12}ms`,
                }}
              />
            )}
            {/* Today, above it. The quarter hour the shop is in right now takes the action colour,
                so "this is where the day has got to" is legible without reading the marker. */}
            {slot.saleCount > 0 && (
              <span
                className="lum-pulse-bar absolute bottom-1/2 origin-bottom rounded-t-sm"
                style={{
                  left: `${left(slot.minuteOfDay)}%`,
                  width: `calc(${width}% - 1.5px)`,
                  height: `${Math.max(2, (slot.saleCount / scale) * 32)}px`,
                  background:
                    slot.minuteOfDay === currentSlot ? 'var(--lum-accent)' : 'var(--lum-brand)',
                  animationDelay: `${140 + index * 12}ms`,
                }}
              />
            )}
          </div>
        ))}

        {/* Without this the empty right half of today's row reads as missing data rather than as an
            afternoon that has not happened yet. */}
        {currentSlot !== null &&
          currentSlot >= window.fromMinute &&
          currentSlot < window.toMinute && (
            <span
              className="bg-ink-3 absolute inset-y-0 w-px opacity-45"
              style={{ left: `${left(currentSlot)}%` }}
            >
              <span className="text-ink-3 absolute -top-0.5 left-1.5 font-mono text-[9px] uppercase tracking-wider">
                now
              </span>
            </span>
          )}
      </div>

      <div
        className="text-ink-3 mt-1.5 flex justify-between font-mono text-[10px]"
        aria-hidden="true"
      >
        {/* Keyed by position, not by value: a short window rounds two ticks to the same hour. */}
        {axisTicks(window.fromMinute, window.toMinute).map((minute, index) => (
          <span key={index}>{slotLabel(minute)}</span>
        ))}
      </div>

      <figcaption className="text-ink-3 mt-2.5 flex flex-wrap gap-x-4 gap-y-1 text-xs">
        <span className="flex items-center gap-1.5">
          <i
            className="h-2 w-2.5 rounded-sm"
            style={{ background: 'var(--lum-brand)' }}
            aria-hidden="true"
          />
          Today, by the quarter hour
        </span>
        <span className="flex items-center gap-1.5">
          <i
            className="h-2 w-2.5 rounded-sm opacity-45"
            style={{ background: 'var(--lum-ink-3)' }}
            aria-hidden="true"
          />
          A normal {weekdayName()}
        </span>
        <span className="sr-only">
          {peak === null
            ? 'No sales to draw yet today.'
            : `Busiest so far between ${slotLabel(peak.minuteOfDay)} and ${slotLabel(
                peak.minuteOfDay + SLOT_MINUTES,
              )}, with ${peak.saleCount} ${peak.saleCount === 1 ? 'sale' : 'sales'}.`}
        </span>
      </figcaption>
    </figure>
  );
}

/**
 * Four labels across whatever window the day turned out to need.
 *
 * <p>Rounded to the hour, because a shop that opened at 7:20 does not want its axis labelled 7:20 —
 * the labels are there to locate the shape in the day, not to be read off precisely.
 *
 * <p>The ends round <em>inwards</em>. They sit hard against the edges of the plot, so rounding the
 * last one up labels the right-hand edge as an hour the graphic does not reach — a window ending at
 * 8:30pm captioned "9pm", with the evening's bars apparently drawn after closing time.
 */
function axisTicks(fromMinute: number, toMinute: number): number[] {
  const step = (toMinute - fromMinute) / 3;
  const hour = 60;
  return [
    Math.ceil(fromMinute / hour) * hour,
    Math.round((fromMinute + step) / hour) * hour,
    Math.round((fromMinute + 2 * step) / hour) * hour,
    Math.floor(toMinute / hour) * hour,
  ];
}
