'use client';

import { cartTotals, emptyCartTotals, taxStamp, type CartTotals } from '@lumora/domain';
import { useCallback, useMemo, useState } from 'react';

export type Product = {
  clientUuid: string;
  sku: string;
  name: string;
  priceMinor: number;
  taxMode: 'INCLUSIVE' | 'EXCLUSIVE';
  taxRateBp: number;
  barcodes: string[];
};

export type CartLine = {
  /** Stable across re-renders and re-orders, so React never reuses a row's DOM for a different product. */
  key: string;
  product: Product;
  qty: number;
};

export type Cart = {
  lines: CartLine[];
  totals: CartTotals;
  /** Index of the line the keyboard is on, or -1 when the cart is empty. */
  selected: number;
};

const DEFAULT_STAMP = taxStamp('INCLUSIVE', 0);

/**
 * The cart, and nothing else.
 *
 * Holds no money arithmetic of its own: every figure comes from `cartTotals` in
 * `@lumora/domain`, so the screen, the receipt and the sale payload are the same numbers
 * rather than three implementations that agree until they do not.
 */
export function useCart() {
  const [lines, setLines] = useState<CartLine[]>([]);
  const [selected, setSelected] = useState(-1);

  const totals = useMemo(() => {
    if (lines.length === 0) return emptyCartTotals(DEFAULT_STAMP);

    // Every line carries the rate off its own product (M1-18). A basket of bread at 0% and
    // arrack at 18% is priced correctly rather than refused, which is what the till did
    // until this landed — `cartTotals` groups the result into `taxBreakdown` for the
    // totals panel and the receipt's VAT summary.
    //
    // The sale-level stamp is the *default* for a line that brought none, and the terminal
    // has no such line: it is the first product's, which on the ordinary single-rate sale
    // is every product's. It is what `sales.tax_mode` / `sales.tax_rate_bp` record.
    const first = lines[0]!.product;
    return cartTotals({
      lines: lines.map((l) => ({
        productClientUuid: l.product.clientUuid,
        qty: l.qty,
        unitPriceMinor: l.product.priceMinor,
        tax: taxStamp(l.product.taxMode, l.product.taxRateBp),
      })),
      tax: taxStamp(first.taxMode, first.taxRateBp),
    });
  }, [lines]);

  /**
   * Adds a scanned product, or bumps the line it is already on.
   *
   * Merging rather than appending is what makes a gun usable: scanning the same tin four
   * times should read "4" on one row, not fill the screen with four rows the cashier then
   * has to check. The merged line moves to selection so the qty keys act on it.
   */
  /*
   * A note on the `setSelected` calls inside the `setLines` updaters below.
   *
   * They are side effects in a function React requires to be pure, and StrictMode invokes
   * these twice on purpose to expose exactly that. They survive only because each passes a
   * *computed constant* — `at`, `current.length`, `Math.min(index, …)` — so running twice
   * sets the same value twice and is indistinguishable from running once.
   *
   * `move` did not have that property: it passed an updater function, so the second
   * invocation moved the selection a second time and the arrow keys skipped every other
   * line. If you add a `setSelected` here, keep it a constant, or lift it out the way
   * `move` now does.
   */
  const addProduct = useCallback((product: Product, qty = 1) => {
    setLines((current) => {
      const at = current.findIndex((l) => l.product.clientUuid === product.clientUuid);
      if (at >= 0) {
        const next = current.slice();
        next[at] = { ...next[at]!, qty: next[at]!.qty + qty };
        setSelected(at);
        return next;
      }
      setSelected(current.length);
      return [...current, { key: `${product.clientUuid}:${Date.now()}`, product, qty }];
    });
  }, []);

  const changeQty = useCallback((index: number, delta: number) => {
    setLines((current) => {
      const line = current[index];
      if (!line) return current;
      const qty = line.qty + delta;
      if (qty < 1) {
        // Decrementing past one removes the line, which is what a cashier means by it.
        const next = current.filter((_, i) => i !== index);
        setSelected(Math.min(index, next.length - 1));
        return next;
      }
      const next = current.slice();
      next[index] = { ...line, qty };
      return next;
    });
  }, []);

  const setQty = useCallback((index: number, qty: number) => {
    if (!Number.isInteger(qty) || qty < 1) return;
    setLines((current) => {
      const line = current[index];
      if (!line) return current;
      const next = current.slice();
      next[index] = { ...line, qty };
      return next;
    });
  }, []);

  const voidLine = useCallback((index: number) => {
    setLines((current) => {
      if (!current[index]) return current;
      const next = current.filter((_, i) => i !== index);
      setSelected(Math.min(index, next.length - 1));
      return next;
    });
  }, []);

  const clear = useCallback(() => {
    setLines([]);
    setSelected(-1);
  }, []);

  /**
   * Moves the selection one line, clamped at both ends.
   *
   * Reads `lines` from the closure rather than nesting `setSelected` inside a `setLines`
   * updater, and that is not a style preference. React invokes updater functions **twice**
   * under StrictMode, which `next.config.mjs` enables — so a `setSelected` called from
   * inside one ran twice per keypress and the selection skipped every other line. An
   * updater has to be pure; the nesting made it a side effect, and the double invocation
   * that is supposed to *expose* impurity silently doubled the movement instead.
   *
   * Clamped, not wrapped: a cashier holding Down should stop at the last line, not silently
   * land back on the first and edit the wrong one.
   */
  const move = useCallback(
    (delta: number) => {
      if (lines.length === 0) {
        setSelected(-1);
        return;
      }
      setSelected((s) => Math.max(0, Math.min(lines.length - 1, s + delta)));
    },
    [lines.length],
  );

  const cart: Cart = { lines, totals, selected };
  return { cart, addProduct, changeQty, setQty, voidLine, clear, move, setSelected };
}
