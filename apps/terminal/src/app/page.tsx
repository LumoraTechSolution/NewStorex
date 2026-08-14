'use client';

import { formatMinor } from '@lumora/domain';
import { useCallback, useEffect, useState } from 'react';

import { CartLines } from '@/components/CartLines';
import { FunctionBar, type FunctionKey } from '@/components/FunctionBar';
import { ScanField } from '@/components/ScanField';
import { SyncStatusStrip } from '@/components/SyncStatusStrip';
import { ThemeToggle } from '@/components/ThemeToggle';
import { TotalsPanel } from '@/components/TotalsPanel';
import { useGlobalKeys } from '@/lib/scanner';
import { useCart, type Product } from '@/lib/useCart';

type Committed = { invoiceNumber: string; totalMinor: number; alreadyExisted: boolean };

/**
 * The terminal (M1-07 to M1-10).
 *
 * A fixed appliance, not a web page. The shell never scrolls and has no navigation: the
 * status strip, the scan field, the totals and the F-key bar hold their positions for the
 * whole of a shift, and only the cart list moves inside its own region. Everything a
 * cashier does here is reachable from the keyboard — Gate M1 is twenty consecutive sales
 * without touching a mouse.
 *
 * The tender overlay with split payment and change is M1-11; F12 currently commits the
 * sale as exact cash, which is what the M0 spike did.
 */
export default function Page() {
  const { cart, addProduct, changeQty, voidLine, clear, move } = useCart();
  const [message, setMessage] = useState<{ tone: 'ok' | 'danger'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [picker, setPicker] = useState<Product[] | null>(null);

  // ------------------------------------------------------------------ catalogue lookup

  const lookup = useCallback(async (query: string) => {
    const response = await fetch(`/api/products/search?q=${encodeURIComponent(query)}`, {
      cache: 'no-store',
    });
    if (!response.ok) throw new Error(`search: HTTP ${response.status}`);
    return (await response.json()) as { exactMatch: boolean; products: Product[] };
  }, []);

  const onScan = useCallback(
    async (code: string) => {
      try {
        const result = await lookup(code);
        // exactMatch is the backend saying "this was a barcode". Trusting a result count
        // instead would make a one-hit name search behave like a scan.
        if (result.exactMatch && result.products[0]) {
          addProduct(result.products[0]);
          setMessage(null);
          return;
        }
        setMessage({ tone: 'danger', text: `No product for barcode ${code}` });
      } catch (e) {
        setMessage({ tone: 'danger', text: e instanceof Error ? e.message : String(e) });
      }
    },
    [addProduct, lookup],
  );

  const onQuery = useCallback(
    async (text: string) => {
      try {
        const result = await lookup(text);
        if (result.exactMatch && result.products[0]) {
          addProduct(result.products[0]);
          setMessage(null);
          return;
        }
        if (result.products.length === 0) {
          setMessage({ tone: 'danger', text: `Nothing matches "${text}"` });
          return;
        }
        if (result.products.length === 1) {
          addProduct(result.products[0]!);
          setMessage(null);
          return;
        }
        setPicker(result.products);
      } catch (e) {
        setMessage({ tone: 'danger', text: e instanceof Error ? e.message : String(e) });
      }
    },
    [addProduct, lookup],
  );

  // ------------------------------------------------------------------------- tendering

  const tender = useCallback(async () => {
    if (cart.lines.length === 0 || busy) return;
    if (cart.blocked) {
      setMessage({ tone: 'danger', text: cart.blocked });
      return;
    }
    setBusy(true);
    setMessage(null);

    // Generated before the request goes out: the same uuid twice is the same sale, which
    // is what makes the terminal's retry safe.
    const clientUuid = crypto.randomUUID();
    const totals = cart.totals;

    try {
      const response = await fetch('/api/sales', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          clientUuid,
          branchCode: 'KND',
          terminalCode: 'T1',
          taxMode: totals.taxMode,
          taxRateBp: totals.taxRateBp,
          subtotalMinor: totals.subtotalMinor,
          discountMinor: totals.discountMinor,
          taxMinor: totals.taxMinor,
          totalMinor: totals.totalMinor,
          lines: totals.lines.map((line) => ({
            productClientUuid: line.productClientUuid,
            qty: line.qty,
            unitPriceMinor: line.unitPriceMinor,
            discountMinor: line.discountMinor,
            taxMinor: line.taxMinor,
            lineTotalMinor: line.lineTotalMinor,
          })),
        }),
      });

      const body = await response.json();
      if (!response.ok) throw new Error(body.detail ?? `HTTP ${response.status}`);

      const committed = body as Committed;
      setMessage({
        tone: 'ok',
        text: `${committed.invoiceNumber} — ${formatMinor(committed.totalMinor)}${
          committed.alreadyExisted ? ' (already existed)' : ''
        }`,
      });
      clear();
    } catch (e) {
      setMessage({ tone: 'danger', text: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  }, [busy, cart, clear]);

  // -------------------------------------------------------------------- keyboard (M1-10)

  const noPicker = useCallback(() => picker === null, [picker]);

  useGlobalKeys([
    { key: 'ArrowUp', run: () => move(-1), when: noPicker },
    { key: 'ArrowDown', run: () => move(1), when: noPicker },
    { key: '+', run: () => changeQty(cart.selected, 1), when: noPicker },
    { key: '-', run: () => changeQty(cart.selected, -1), when: noPicker },
    { key: 'F2', run: () => changeQty(cart.selected, 1), when: noPicker },
    { key: 'F4', run: () => voidLine(cart.selected), when: noPicker },
    { key: 'F8', run: clear, when: noPicker },
    { key: 'F12', run: () => void tender(), when: noPicker },
    { key: 'Escape', run: () => setPicker(null) },
  ]);

  // The picker is the one place selection leaves the cart, so it owns the arrows while open.
  useEffect(() => {
    if (!picker) return;
    function onKey(event: KeyboardEvent) {
      const index = Number(event.key) - 1;
      if (Number.isInteger(index) && index >= 0 && index < picker!.length) {
        event.preventDefault();
        addProduct(picker![index]!);
        setPicker(null);
      }
    }
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [picker, addProduct]);

  const functionKeys: FunctionKey[] = [
    { key: 'F1', label: 'Help' },
    {
      key: 'F2',
      label: 'Qty +',
      run: () => changeQty(cart.selected, 1),
      disabled: cart.selected < 0,
    },
    { key: 'F3', label: 'Search' },
    {
      key: 'F4',
      label: 'Void line',
      run: () => voidLine(cart.selected),
      disabled: cart.selected < 0,
    },
    { key: 'F5', label: 'Discount' },
    { key: 'F6', label: 'Customer' },
    { key: 'F7', label: 'Hold' },
    { key: 'F8', label: 'Clear', run: clear, disabled: cart.lines.length === 0 },
    { key: 'F9', label: 'Return' },
    { key: 'F10', label: 'Cash up' },
    { key: 'F11', label: 'Reprint' },
    {
      key: 'F12',
      label: busy ? 'Working…' : 'Tender',
      run: () => void tender(),
      disabled: cart.lines.length === 0 || busy,
    },
  ];

  return (
    <div className="flex h-full flex-col overflow-hidden">
      <SyncStatusStrip />

      <header className="border-hair flex shrink-0 items-center gap-4 border-b px-4 py-3">
        <div className="flex-1">
          <ScanField
            onScan={(c) => void onScan(c)}
            onQuery={(t) => void onQuery(t)}
            disabled={picker !== null}
          />
        </div>
        <ThemeToggle />
      </header>

      {message && (
        <p
          role="status"
          aria-live="polite"
          className={`shrink-0 border-l-2 px-4 py-2 text-sm ${
            message.tone === 'ok' ? 'border-ok text-ok' : 'border-danger text-danger'
          }`}
        >
          {message.text}
        </p>
      )}

      {/* The only scrolling region on the screen. */}
      <div className="flex min-h-0 flex-1">
        <main className="min-h-0 flex-1 overflow-y-auto">
          <CartLines cart={cart} />
        </main>
        <TotalsPanel cart={cart} />
      </div>

      <FunctionBar keys={functionKeys} />

      {picker && (
        <div className="bg-page/90 absolute inset-0 flex items-center justify-center p-8">
          <div className="border-hair bg-surface w-full max-w-xl rounded-lg border p-4">
            <h2 className="text-ink-3 mb-3 text-xs uppercase tracking-wider">
              Choose an item — press its number, or Esc
            </h2>
            <ul className="flex flex-col gap-1">
              {picker.slice(0, 9).map((product, index) => (
                <li key={product.clientUuid}>
                  <button
                    type="button"
                    tabIndex={-1}
                    onClick={() => {
                      addProduct(product);
                      setPicker(null);
                    }}
                    className="border-hair min-h-touch flex w-full items-center justify-between rounded border px-4 text-left"
                  >
                    <span>
                      <span className="text-accent mr-3 font-semibold">{index + 1}</span>
                      {product.name}
                    </span>
                    <span className="lum-money">{formatMinor(product.priceMinor)}</span>
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
    </div>
  );
}
