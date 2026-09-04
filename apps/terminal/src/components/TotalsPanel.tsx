'use client';

import { formatMinor } from '@lumora/domain';

import type { Cart } from '@/lib/useCart';

/**
 * The totals block (M1-07, re-placed in M6).
 *
 * Fixed in the shell, never scrolled away. The total is the largest thing on the screen by
 * a wide margin — it is the number the cashier reads aloud and the customer checks, and at
 * a glance from a metre away nothing else on this screen matters.
 *
 * It sits **beneath** the cart rather than beside it, which is where every commercial till
 * puts it: the eye reads the lines down the receipt and lands on the total at the bottom,
 * in the same direction the paper one prints. The breakdown and the total then sit side by
 * side on that row — the small figures left, the one that matters right — so the total gets
 * the full type size the old 320px column could not give it.
 */
export function TotalsPanel({ cart }: { cart: Cart }) {
  const { totals } = cart;
  // More than one entry means the basket mixes rates (M1-18) — bread at 0% and arrack at
  // 18%, say. One "VAT" figure spanning both would be a number the cashier cannot check
  // against anything, so each rate gets its own row, exactly as the receipt prints them.
  const mixed = totals.taxBreakdown.length > 1;
  const taxLabel = totals.taxMode === 'EXCLUSIVE' ? 'VAT added' : 'VAT included';

  return (
    <aside className="border-hair flex shrink-0 items-end justify-between gap-10 border-t px-6 py-5">
      <dl className="flex w-full max-w-xs flex-col gap-2">
        <Row label="Items" value={String(cart.lines.reduce((n, l) => n + l.qty, 0))} />
        <Row label="Subtotal" value={formatMinor(totals.subtotalMinor)} />
        {totals.discountMinor > 0 && (
          <Row label="Discount" value={`-${formatMinor(totals.discountMinor)}`} />
        )}
        {mixed ? (
          totals.taxBreakdown.map((entry) => (
            <Row
              key={`${entry.mode}:${entry.rateBp}`}
              // The rate is shown even at zero: "VAT 0%" says the line was considered and
              // found exempt, where omitting the row says nothing at all.
              label={`VAT ${entry.rateBp / 100}%`}
              value={formatMinor(entry.taxMinor)}
              muted
            />
          ))
        ) : (
          <Row label={taxLabel} value={formatMinor(totals.taxMinor)} muted />
        )}
      </dl>

      <div className="text-right">
        <dt className="text-ink-3 text-xs uppercase tracking-wider">Total</dt>
        <dd className="lum-money mt-1 text-6xl font-semibold leading-none">
          {formatMinor(totals.totalMinor)}
        </dd>
      </div>
    </aside>
  );
}

function Row({ label, value, muted }: { label: string; value: string; muted?: boolean }) {
  return (
    <div className="flex items-baseline justify-between">
      <dt className={`text-sm ${muted ? 'text-ink-3' : 'text-ink-2'}`}>{label}</dt>
      <dd className={`lum-money text-sm ${muted ? 'text-ink-3' : 'text-ink-2'}`}>{value}</dd>
    </div>
  );
}
