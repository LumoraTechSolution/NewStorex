'use client';

import { formatMinor } from '@lumora/domain';

/**
 * Currency, in the tabular monospace face §A requires.
 *
 * <p>The formatting comes from `@lumora/domain` — the same function the receipt and the till use.
 * That is the standing rule and it is not ceremony here: the whole purpose of this screen is that
 * the owner sees the same figure the shop saw, and a second formatter is how the two eventually
 * disagree by a rupee with no test to catch it.
 */
export function Money({ minor, className = '' }: { minor: number; className?: string }) {
  return <span className={`font-mono tabular-nums ${className}`}>{formatMinor(minor)}</span>;
}

/**
 * The headline figure, with its currency (M6-14).
 *
 * <p>"Rs" is set small and raised beside the number rather than in front of it at full size: the
 * amount is the thing being read and the currency is a label on it, which is how a price is set on
 * a shelf and how it is printed on the receipt this figure is the sum of.
 *
 * <p>It shows the cents. The prototype rounded to whole rupees, which is prettier and would have
 * meant the headline disagreeing with the sum of the rows underneath it by up to a rupee — the
 * exact failure §A is about, arrived at through the display layer instead of through the maths.
 */
export function Takings({ minor }: { minor: number }) {
  return (
    <>
      <span className="text-ink-3 mr-1.5 align-[0.28em] text-[0.46em] tracking-wide">Rs</span>
      {formatMinor(minor)}
    </>
  );
}
