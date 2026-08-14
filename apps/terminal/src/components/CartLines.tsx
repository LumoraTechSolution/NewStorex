'use client';

import { formatMinor } from '@lumora/domain';
import { useEffect, useRef } from 'react';

import type { Cart } from '@/lib/useCart';

/**
 * The cart (M1-10).
 *
 * The one region that scrolls, and it scrolls **itself** — the page never does. A cashier
 * mid-sale must never lose sight of the total or the F-key bar because the list grew, so
 * the shell is fixed and only this list moves inside it (M1-07).
 *
 * The selected row is kept in view as selection moves, since arrow keys are how the cart
 * is navigated and a selection you cannot see is worse than none.
 */
export function CartLines({ cart }: { cart: Cart }) {
  const selectedRef = useRef<HTMLTableRowElement>(null);

  useEffect(() => {
    selectedRef.current?.scrollIntoView({ block: 'nearest' });
  }, [cart.selected, cart.lines.length]);

  if (cart.lines.length === 0) {
    return (
      <div className="text-ink-3 flex h-full items-center justify-center text-sm">
        Scan an item to begin
      </div>
    );
  }

  return (
    <table className="w-full border-collapse text-left">
      <thead className="bg-page text-ink-3 sticky top-0 text-xs uppercase tracking-wider">
        <tr>
          <th scope="col" className="py-2 pl-4 font-normal">
            Item
          </th>
          <th scope="col" className="py-2 text-right font-normal">
            Qty
          </th>
          <th scope="col" className="py-2 text-right font-normal">
            Price
          </th>
          <th scope="col" className="py-2 pr-4 text-right font-normal">
            Amount
          </th>
        </tr>
      </thead>
      <tbody>
        {cart.lines.map((line, index) => {
          const totals = cart.totals.lines[index];
          const isSelected = index === cart.selected;
          return (
            <tr
              key={line.key}
              ref={isSelected ? selectedRef : undefined}
              aria-selected={isSelected}
              className={`border-hair border-b ${isSelected ? 'bg-surface' : ''}`}
            >
              <td className="py-3 pl-4">
                {/* The selection marker is a character, not just a background tint:
                    on a bright shop floor a subtle fill is the first thing to disappear. */}
                <span aria-hidden="true" className="text-accent mr-2">
                  {isSelected ? '▸' : ' '}
                </span>
                {line.product.name}
                <span className="text-ink-3 ml-2 text-xs">{line.product.sku}</span>
              </td>
              <td className="lum-money py-3 text-right text-lg">{line.qty}</td>
              <td className="lum-money text-ink-2 py-3 text-right">
                {formatMinor(line.product.priceMinor)}
              </td>
              <td className="lum-money py-3 pr-4 text-right text-lg">
                {totals ? formatMinor(totals.lineTotalMinor) : '—'}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
