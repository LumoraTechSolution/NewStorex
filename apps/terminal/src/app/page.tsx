'use client';

import { formatMinor } from '@lumora/domain';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useState } from 'react';

import { ActionRail } from '@/components/ActionRail';
import { CartLines } from '@/components/CartLines';
import { FunctionBar, type FunctionKey } from '@/components/FunctionBar';
import { HelpOverlay } from '@/components/HelpOverlay';
import { CustomerOverlay, type Customer } from '@/components/CustomerOverlay';
import { RefundOverlay, type RefundOutcome } from '@/components/RefundOverlay';
import { ScanField } from '@/components/ScanField';
import { SearchOverlay } from '@/components/SearchOverlay';
import { SetupWizard } from '@/components/SetupWizard';
import { ShiftOverlay, type ClosedShift } from '@/components/ShiftOverlay';
import { TaxInvoiceOverlay, type IssuedTaxInvoice } from '@/components/TaxInvoiceOverlay';
import { SyncStatusStrip } from '@/components/SyncStatusStrip';
import { LicenceNotice } from '@/components/LicenceNotice';
import { UpdateNotice } from '@/components/UpdateNotice';
import { TenderOverlay, type TenderOutcome } from '@/components/TenderOverlay';
import { TillIdentity } from '@/components/TillIdentity';
import { ThemeToggle } from '@/components/ThemeToggle';
import { TotalsPanel } from '@/components/TotalsPanel';
import { buildReceiptWithDrawerKick, type ReceiptData } from '@/lib/receipt';
import { buildTaxInvoice } from '@/lib/taxInvoice';
import { useEntitlement } from '@/lib/useEntitlement';
import { useGlobalKeys } from '@/lib/scanner';
import { useCart, type Cart, type Product } from '@/lib/useCart';
import { useShift } from '@/lib/useShift';
import { useShopIdentity, type ShopIdentity } from '@/lib/useShopIdentity';
import {
  buildCreditNoteWithDrawerKick,
  buildZReport,
  type CreditNoteData,
  type ZReportData,
} from '@/lib/zreport';

type Committed = {
  invoiceNumber: string;
  totalMinor: number;
  changeMinor: number;
  soldAt: string;
  alreadyExisted: boolean;
};

/**
 * The one line of the receipt header that is not the shop's own (M5-03).
 *
 * Everything else here now comes from the database, provisioned by the first-run wizard. This
 * stays a constant because it is this product's mark, not the shop's — a shopkeeper does not get
 * to edit it, and a column for it would invite exactly that.
 */
const TAGLINE = 'Powered by Lumora Tech';

function receiptDataFor(
  shop: ShopIdentity,
  cart: Cart,
  outcome: TenderOutcome,
  committed: Committed,
  customerName: string | null,
): ReceiptData {
  return {
    storeName: shop.shopName,
    tagline: TAGLINE,
    // An unset address prints as a blank line rather than collapsing the header, for the reason
    // `receipt.ts` gives about the customer line: a fixed layout is one a cashier can scan.
    storeAddress: shop.shopAddress ?? '',
    customerName,
    branchName: shop.branchName,
    branchCode: shop.branchCode,
    terminalCode: shop.terminalCode,
    invoiceNumber: committed.invoiceNumber,
    soldAt: committed.soldAt,
    lines: cart.totals.lines.map((line, index) => ({
      name: cart.lines[index]?.product.name ?? 'Item',
      qty: line.qty,
      unitPriceMinor: line.unitPriceMinor,
      lineTotalMinor: line.lineTotalMinor,
    })),
    subtotalMinor: cart.totals.subtotalMinor,
    discountMinor: cart.totals.discountMinor,
    taxMinor: cart.totals.taxMinor,
    taxRateBp: cart.totals.taxRateBp,
    taxMode: cart.totals.taxMode,
    taxBreakdown: cart.totals.taxBreakdown,
    totalMinor: cart.totals.totalMinor,
    tenders: outcome.tenders,
    roundingAdjustmentMinor: outcome.roundingAdjustmentMinor,
    changeMinor: outcome.changeMinor,
  };
}

/**
 * Best-effort printing, for every document the till produces.
 *
 * The thing being printed has already been committed on the backend before this runs — a sale
 * (M1-11), a credit note (M2-06) or a closed shift (M2-11). A print failure must therefore never
 * look like the underlying action failed, so this never throws: it reports what happened and lets
 * the caller decide how to say so.
 *
 * Takes bytes rather than the document, so the same path serves all three and there is exactly
 * one place that knows how a print failure is handled.
 */
async function print(
  bytes: Uint8Array,
): Promise<{ ok: true } | { ok: false; error: string } | null> {
  if (!window.lumora?.printer) {
    // Not running inside the desktop shell — `next dev` in a plain browser tab, most likely.
    return null;
  }
  try {
    return await window.lumora.printer.print(bytes);
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

/** Appends "…and the paper did not come out" to a message, when that is what happened. */
function withPrintOutcome(
  text: string,
  result: Awaited<ReturnType<typeof print>>,
  what: string,
): { text: string; failed: boolean } {
  const failed = result !== null && !result.ok;
  return {
    text: failed ? `${text} — ${what} did not print: ${(result as { error: string }).error}` : text,
    failed,
  };
}

/**
 * The gate in front of the till (M5-03).
 *
 * A freshly installed till has a migrated schema and no shop, so the first thing this process
 * has to answer is not "what is in the cart" but "whose till is this". Three outcomes, and each
 * one is a whole screen rather than a banner:
 *
 *  - not set up   → the wizard, which is the only thing that can be done here
 *  - set up       → the till, handed its identity so nothing downstream has to ask again
 *  - unreachable  → an error, deliberately *not* the wizard. Offering setup to a shop that
 *                   already exists is how a till acquires a second identity, and the server
 *                   would refuse it at the last step, after the shopkeeper had typed everything.
 *
 * The till is a separate component below rather than a branch inside one, so that `Till` can
 * take `shop` as a plain prop and every receipt path reads it from there. The alternative —
 * nullable identity threaded through the existing component — puts a `?? 'StoreX'` at each of
 * thirteen call sites, and one of them eventually prints on a real receipt.
 */
export default function Page() {
  const { state, reload } = useShopIdentity();

  if (state.status === 'loading') {
    return (
      <main className="bg-page text-ink-2 flex min-h-screen items-center justify-center">
        <p className="text-lg">Starting…</p>
      </main>
    );
  }

  if (state.status === 'needs-setup') {
    return <SetupWizard onComplete={reload} />;
  }

  if (state.status === 'error') {
    return (
      <main className="bg-page text-ink flex min-h-screen items-center justify-center p-8">
        <div className="max-w-md text-center">
          <h1 className="text-xl font-semibold">This till cannot reach its own database</h1>
          <p className="text-ink-2 mt-3">{state.message}</p>
          <button
            type="button"
            onClick={() => void reload()}
            className="border-hair text-ink min-h-touch mt-6 rounded-lg border px-6 text-lg"
          >
            Try again
          </button>
        </div>
      </main>
    );
  }

  return <Till shop={state.identity} />;
}

/**
 * The terminal (M1-07 to M1-10).
 *
 * A fixed appliance, not a web page. The shell never scrolls and has no navigation: the
 * status strip, the scan field, the totals and the F-key bar hold their positions for the
 * whole of a shift, and only the cart list moves inside its own region. Everything a
 * cashier does here is reachable from the keyboard — Gate M1 is twenty consecutive sales
 * without touching a mouse.
 *
 * F12 opens the tender overlay (M1-11) rather than committing the sale directly, which is
 * what the M0 spike did. The overlay is a second modal alongside the item picker, and the
 * two never overlap — `interactionsBlocked` is what the scan field, the global F-keys and
 * the picker's own arrow handling all gate on.
 *
 * Since M5-03 it is handed the shop it belongs to rather than reading five constants: the
 * branch and terminal codes here are what every invoice number this till issues is built from.
 */
function Till({ shop }: { shop: ShopIdentity }) {
  const router = useRouter();
  const {
    cart,
    addProduct: addProductToCart,
    changeQty,
    voidLine,
    clear: clearCart,
    move,
    setSelected,
  } = useCart();
  /**
   * The touch keypad's pending quantity (M6), as the digit string the taps built.
   *
   * Empty means one. Kept as a string rather than a number so '0' and '00' behave the way
   * they do on a till keypad — leading zeros are simply absent — and so clearing is one
   * assignment rather than a sentinel.
   */
  const [qtyBuffer, setQtyBuffer] = useState('');
  const multiplier = Math.max(1, Number(qtyBuffer) || 1);

  /**
   * Adds a product, consuming the pending quantity.
   *
   * **Everything that adds goes through here** — the scan field, the search overlay, the
   * item picker — and it always clears the multiplier afterwards. That is deliberate and it
   * is the single most likely bug in the touch layer: a multiplier that survives its own
   * use means the cashier taps 3, adds one thing, and the *next* item silently rings up
   * three times. The reset lives with the read so the two can never drift apart.
   */
  const addProduct = useCallback(
    (product: Product) => {
      addProductToCart(product, multiplier);
      setQtyBuffer('');
    },
    [addProductToCart, multiplier],
  );
  const [message, setMessage] = useState<{ tone: 'ok' | 'danger'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [picker, setPicker] = useState<Product[] | null>(null);
  const [tendering, setTendering] = useState(false);
  const [cashingUp, setCashingUp] = useState(false);
  const [returning, setReturning] = useState(false);
  const [choosingCustomer, setChoosingCustomer] = useState(false);
  /**
   * Who this sale is for (M3-11), or null — which is the normal case and stays that way.
   *
   * Lives here rather than in `useCart` deliberately: the cart is money, and this is not. Nothing
   * about the totals is derived from it, and putting it in the cart's state would make that
   * relationship a matter of discipline instead of a matter of type.
   */
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [issuingTaxInvoice, setIssuingTaxInvoice] = useState(false);
  const [searching, setSearching] = useState(false);
  const [helping, setHelping] = useState(false);
  // What this shop has bought (M4-09). Read from the till's own database, so it answers the same
  // during an outage as it does online — a capability that vanished with the internet would put
  // the network back on the critical path of selling.
  //  as well as , for the shop name the cloud last answered with. The two
  // come from one poll rather than two so the header and the licence notice can never disagree.
  const { allows, entitlement } = useEntitlement();
  // The receipt a tax invoice would most likely be asked for. Survives `clear()` on purpose:
  // the customer asks after the sale is finished and the cart has already been emptied.
  const [lastSaleInvoiceNumber, setLastSaleInvoiceNumber] = useState<string | null>(null);

  /**
   * Empties the cart and forgets who it was for.
   *
   * <p>The two have to happen together and there must be exactly one way to do it. A cleared cart
   * that still remembers a customer is the next sale being quietly attributed to the last one's —
   * on a screen where the only evidence is the F6 label a cashier has no reason to look at.
   */
  const clear = useCallback(() => {
    clearCart();
    setCustomer(null);
  }, [clearCart]);

  // M2-01. Whether this till may trade at all, answered by the backend and never assumed.
  const shift = useShift(shop.branchCode, shop.terminalCode);

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

  /** F12 from the cart screen: opens the tender overlay, does not commit anything yet. */
  const openTender = useCallback(() => {
    if (cart.lines.length === 0 || busy) return;
    // M2-01. The backend refuses a sale with no open shift, and it is a much better experience to
    // say so before the cashier has tendered than after. This is a courtesy, not the enforcement:
    // SaleService is where the rule actually lives.
    if (!shift.canTrade) {
      setMessage({
        tone: 'danger',
        text: 'No shift is open — press F10 to open one before selling',
      });
      return;
    }
    setMessage(null);
    setTendering(true);
  }, [busy, cart, shift.canTrade]);

  /**
   * What the tender overlay calls once it is settled and the cashier pressed F12 there. The
   * cart's own totals are untouched by tendering — {@link TenderOutcome} only adds the
   * payment breakdown, the cash-rounding adjustment and the change on top of them.
   */
  const submitSale = useCallback(
    async (outcome: TenderOutcome) => {
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
            branchCode: shop.branchCode,
            terminalCode: shop.terminalCode,
            taxMode: totals.taxMode,
            taxRateBp: totals.taxRateBp,
            subtotalMinor: totals.subtotalMinor,
            discountMinor: totals.discountMinor,
            taxMinor: totals.taxMinor,
            totalMinor: totals.totalMinor,
            roundingAdjustmentMinor: outcome.roundingAdjustmentMinor,
            changeMinor: outcome.changeMinor,
            lines: totals.lines.map((line) => ({
              productClientUuid: line.productClientUuid,
              qty: line.qty,
              unitPriceMinor: line.unitPriceMinor,
              discountMinor: line.discountMinor,
              taxMinor: line.taxMinor,
              lineTotalMinor: line.lineTotalMinor,
              // The rate this line was actually charged at (M1-18). Sent even on a
              // single-rate sale, where it equals the sale's: the alternative is a backend
              // that has to know which of two shapes it is looking at.
              taxMode: line.taxMode,
              taxRateBp: line.taxRateBp,
            })),
            tenders: outcome.tenders.map((t) => ({ kind: t.kind, amountMinor: t.amountMinor })),
            // M3-11. Null nine times in ten, and the backend refuses one that is not on file or
            // no longer active rather than dropping it silently.
            customerClientUuid: customer?.clientUuid ?? null,
          }),
        });

        const body = await response.json();
        if (!response.ok) throw new Error(body.detail ?? `HTTP ${response.status}`);

        const committed = body as Committed;
        setLastSaleInvoiceNumber(committed.invoiceNumber);
        const text = `${committed.invoiceNumber} — ${formatMinor(committed.totalMinor)}${
          committed.changeMinor > 0 ? ` · change ${formatMinor(committed.changeMinor)}` : ''
        }${committed.alreadyExisted ? ' (already existed)' : ''}`;

        // The receipt and the drawer kick are one print job (M1-14) — best-effort, and never
        // allowed to make a committed sale look like it failed.
        const printed = await print(
          buildReceiptWithDrawerKick(
            receiptDataFor(shop, cart, outcome, committed, customer?.name ?? null),
          ),
        );
        const outcomeText = withPrintOutcome(text, printed, 'receipt');

        setMessage({ tone: outcomeText.failed ? 'danger' : 'ok', text: outcomeText.text });
        setTendering(false);
        clear();
        // A sale changes the shift's counters, which the status strip shows.
        void shift.refresh();
      } catch (e) {
        // The cart survives a failed submit — the cashier presses F12 again to retry
        // tendering rather than re-ringing up every line.
        setTendering(false);
        setMessage({ tone: 'danger', text: e instanceof Error ? e.message : String(e) });
      } finally {
        setBusy(false);
      }
    },
    [cart, clear, customer?.clientUuid, customer?.name, shift, shop],
  );

  /**
   * Prints an issued tax invoice (M5-09).
   *
   * <p>Best-effort like every other document here: the invoice is already committed on the backend
   * and has already taken its serial number by the time this runs, so a printer failure must report
   * itself without suggesting the invoice was not issued. It can be reprinted — issuing again
   * returns the same document rather than creating a second one.
   */
  const printTaxInvoice = useCallback(async (invoice: IssuedTaxInvoice) => {
    const printed = await print(buildTaxInvoice(invoice));
    const outcome = withPrintOutcome(
      `Tax invoice ${invoice.invoiceNumber}`,
      printed,
      'tax invoice',
    );
    setMessage({ tone: outcome.failed ? 'danger' : 'ok', text: outcome.text });
  }, []);

  // ---------------------------------------------------------------------- cash up (M2)

  /**
   * Printed the moment a shift closes, unprompted.
   *
   * A Z-report a cashier has to remember to ask for is a Z-report that does not get filed. The
   * figures come from the backend's frozen record (M2-11), not from anything this screen kept.
   */
  const printZReport = useCallback(
    async (closed: ClosedShift) => {
      try {
        const response = await fetch(`/api/reports/z/${closed.id}`, { cache: 'no-store' });
        const body = await response.json();
        if (!response.ok) throw new Error(body.detail ?? `HTTP ${response.status}`);

        const report = body as Omit<ZReportData, 'storeName' | 'tagline'>;
        const printed = await print(
          buildZReport({ ...report, storeName: shop.shopName, tagline: TAGLINE }),
        );
        const varianceText =
          closed.varianceMinor === 0
            ? 'drawer balanced'
            : `drawer ${closed.varianceMinor > 0 ? 'over' : 'short'} ${formatMinor(Math.abs(closed.varianceMinor))}`;
        const outcome = withPrintOutcome(`Shift closed — ${varianceText}`, printed, 'Z-report');
        setMessage({ tone: outcome.failed ? 'danger' : 'ok', text: outcome.text });
      } catch (e) {
        // The shift is closed either way. Say what happened to the paper, not that the close failed.
        setMessage({
          tone: 'danger',
          text: `Shift closed, but the Z-report could not be built: ${e instanceof Error ? e.message : String(e)}`,
        });
      }
    },
    [shop.shopName],
  );

  // ---------------------------------------------------------------------- returns (M2)

  const onRefunded = useCallback(
    async (outcome: RefundOutcome) => {
      setReturning(false);
      const data: CreditNoteData = {
        storeName: shop.shopName,
        tagline: TAGLINE,
        branchCode: shop.branchCode,
        terminalCode: shop.terminalCode,
        creditNoteNumber: outcome.creditNoteNumber,
        saleInvoiceNumber: outcome.saleInvoiceNumber,
        refundedAt: outcome.refundedAt,
        lines: outcome.lines,
        totalMinor: outcome.totalMinor,
        taxMinor: outcome.taxMinor,
        roundingAdjustmentMinor: outcome.roundingAdjustmentMinor,
        tenders: outcome.tenders,
      };
      const printed = await print(buildCreditNoteWithDrawerKick(data));
      const text = withPrintOutcome(
        `${outcome.creditNoteNumber} — refunded ${formatMinor(outcome.totalMinor)} against ${outcome.saleInvoiceNumber}`,
        printed,
        'credit note',
      );
      setMessage({ tone: text.failed ? 'danger' : 'ok', text: text.text });
      void shift.refresh();
    },
    [shift, shop.branchCode, shop.shopName, shop.terminalCode],
  );

  // -------------------------------------------------------------------- keyboard (M1-10)

  // Only one modal owns the keyboard at a time. Neither the picker nor the overlay nests
  // inside the other — opening one is only possible from the plain cart screen.
  const interactionsBlocked = useCallback(
    () =>
      picker !== null ||
      tendering ||
      cashingUp ||
      returning ||
      choosingCustomer ||
      issuingTaxInvoice ||
      searching ||
      helping,
    [
      cashingUp,
      choosingCustomer,
      helping,
      issuingTaxInvoice,
      picker,
      returning,
      searching,
      tendering,
    ],
  );
  const noPicker = useCallback(() => !interactionsBlocked(), [interactionsBlocked]);

  useGlobalKeys([
    { key: 'ArrowUp', run: () => move(-1), when: noPicker },
    { key: 'ArrowDown', run: () => move(1), when: noPicker },
    { key: '+', run: () => changeQty(cart.selected, 1), when: noPicker },
    { key: '-', run: () => changeQty(cart.selected, -1), when: noPicker },
    { key: 'F2', run: () => changeQty(cart.selected, 1), when: noPicker },
    { key: 'F1', run: () => setHelping(true), when: noPicker },
    { key: 'F3', run: () => setSearching(true), when: noPicker },
    { key: 'F4', run: () => voidLine(cart.selected), when: noPicker },
    { key: 'F6', run: () => setChoosingCustomer(true), when: noPicker },
    { key: 'F8', run: clear, when: noPicker },
    { key: 'F9', run: () => setReturning(true), when: noPicker },
    { key: 'F10', run: () => setCashingUp(true), when: noPicker },
    { key: 'F12', run: openTender, when: noPicker },
    // M3-01. Deliberately not an F-key. Every slot on the function bar is a selling action, and
    // putting "change prices" one keypress away from "void line" is how a busy cashier ends up
    // somewhere they did not mean to be. Ctrl+B is out of the cashier's vocabulary on purpose.
    { key: 'b', ctrl: true, run: () => router.push('/back-office'), when: noPicker },
    // M5-09. Issued on request and after the fact, so it sits with Ctrl+B rather than taking
    // a function key off the selling path.
    {
      key: 'i',
      ctrl: true,
      run: () => setIssuingTaxInvoice(true),
      when: () => noPicker() && allows('tax_invoice'),
    },
    {
      key: 'Escape',
      run: () => setPicker(null),
      when: () =>
        !tendering &&
        !cashingUp &&
        !returning &&
        !choosingCustomer &&
        !issuingTaxInvoice &&
        !searching &&
        !helping,
    },
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
    { key: 'F1', label: 'Help', run: () => setHelping(true) },
    {
      key: 'F2',
      label: 'Qty +',
      run: () => changeQty(cart.selected, 1),
      disabled: cart.selected < 0,
    },
    { key: 'F3', label: 'Search', run: () => setSearching(true), disabled: !shift.canTrade },
    {
      key: 'F4',
      label: 'Void line',
      run: () => voidLine(cart.selected),
      disabled: cart.selected < 0,
    },
    { key: 'F5', label: 'Discount' },
    {
      key: 'F6',
      // The name once somebody is on the sale, so the bar itself is the reminder — a cashier
      // should not have to open the overlay to find out whether they attached anybody.
      label: customer ? customer.name : 'Customer',
      run: () => setChoosingCustomer(true),
      // Disabled rather than removed when the plan does not include customers (M4-09). The bar is
      // muscle memory: every slot keeps its position for the life of the product, and a plan
      // change must never renumber the keys a cashier has learnt.
      disabled: !shift.canTrade || !allows('customers'),
    },
    { key: 'F7', label: 'Hold' },
    { key: 'F8', label: 'Clear', run: clear, disabled: cart.lines.length === 0 },
    { key: 'F9', label: 'Return', run: () => setReturning(true), disabled: !shift.canTrade },
    { key: 'F10', label: shift.canTrade ? 'Cash up' : 'Open shift', run: () => setCashingUp(true) },
    { key: 'F11', label: 'Reprint' },
    {
      key: 'F12',
      label: busy ? 'Working…' : 'Tender',
      run: openTender,
      disabled: cart.lines.length === 0 || busy || !shift.canTrade,
    },
  ];

  return (
    <div className="flex h-full flex-col overflow-hidden">
      <SyncStatusStrip />
      {/* Below the sync strip, because a lapse is the explanation for what that strip is showing. */}
      <LicenceNotice />
      <UpdateNotice />

      <header className="border-hair flex shrink-0 items-center gap-4 border-b px-4 py-3">
        <div className="flex-1">
          <ScanField
            onScan={(c) => void onScan(c)}
            onQuery={(t) => void onQuery(t)}
            disabled={interactionsBlocked()}
          />
        </div>
        {/*
          Whose till this is and who is on it. Beside the scan field rather than in the sync strip,
          because it answers a question a cashier has at the start of a shift rather than one about
          the network — and because a shop-name mismatch needs to be somewhere a person looks
          without being told to.
        */}
        <TillIdentity
          shopName={shop.shopName}
          terminalCode={shop.terminalCode}
          operatorName={shift.status?.operatorName ?? null}
          cloudShopName={entitlement?.tenantName ?? null}
        />
        {/*
          The only hint that the back office exists. Deliberately small and out of the way: it is
          not a cashier's control, and a prominent button labelled "back office" beside the scan
          field is one mis-tap from a price list during a queue.
        */}
        <span className="text-ink-3 hidden text-xs sm:inline">Ctrl+B back office</span>
        <ThemeToggle />
      </header>

      {/*
        Shown whenever the till cannot sell, and only then. Not a toast: this is a state the
        cashier has to act on, so it stays on screen until they do. `status === null` means the
        backend has not answered yet, which is not the same as no shift — see useShift.
      */}
      {shift.status !== null && !shift.canTrade && (
        <p
          role="status"
          className="border-pending text-pending shrink-0 border-l-2 px-4 py-2 text-sm"
        >
          No shift is open on this till — press F10 to count the float and open one.
        </p>
      )}
      {shift.error && (
        <p
          role="status"
          className="border-pending text-pending shrink-0 border-l-2 px-4 py-2 text-sm"
        >
          Cannot reach the till service: {shift.error}
        </p>
      )}

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

      {/*
        Two columns (M6). The receipt takes everything the action rail does not: the till
        deliberately has no product-button grid, because a shop's catalogue does not fit on
        a screen and the barcode gun is faster than any grid for the items that do. Products
        without a barcode are reached through F3 search instead.

        The cart is still the only scrolling region on the screen (M1-07) — it is simply one
        level deeper now, with the totals beneath it rather than beside it.
      */}
      <div className="flex min-h-0 flex-1">
        <section className="border-hair flex min-h-0 flex-1 flex-col border-r">
          <main className="min-h-0 flex-1 overflow-y-auto">
            {/*
              Tapping a line selects it, the same as an arrow key would (M6). Gated on the
              same `interactionsBlocked` as everything else: an overlay covers the cart, and
              a tap that lands on a row behind it must do nothing at all.
            */}
            <CartLines
              cart={cart}
              onSelect={(index) => {
                if (interactionsBlocked()) return;
                setSelected(index);
              }}
            />
          </main>
          <TotalsPanel cart={cart} />
        </section>

        {/*
          The touch half of the till (M6). Every control on it runs the same callback its
          function key runs — see ActionRail. It is disabled wholesale while any overlay is
          open, so a tap that lands behind one does nothing.
        */}
        <ActionRail
          multiplier={multiplier}
          onDigit={(digit) =>
            setQtyBuffer((current) => (current + digit).replace(/^0+/, '').slice(0, 3))
          }
          onClearMultiplier={() => setQtyBuffer('')}
          totalMinor={cart.totals.totalMinor}
          onSearch={() => setSearching(true)}
          onVoidLine={() => voidLine(cart.selected)}
          onClear={clear}
          onTender={openTender}
          canSearch={shift.canTrade}
          canVoid={cart.selected >= 0}
          canClear={cart.lines.length > 0}
          canTender={cart.lines.length > 0 && !busy && shift.canTrade}
          tenderLabel={busy ? 'Working…' : 'Tender'}
          disabled={interactionsBlocked()}
        />
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

      {cashingUp && (
        <ShiftOverlay
          status={shift.status}
          branchCode={shop.branchCode}
          terminalCode={shop.terminalCode}
          onDone={() => void shift.refresh()}
          onClosed={(closed) => void printZReport(closed)}
          onCancel={() => setCashingUp(false)}
        />
      )}

      {returning && (
        <RefundOverlay
          branchCode={shop.branchCode}
          terminalCode={shop.terminalCode}
          onDone={(outcome) => void onRefunded(outcome)}
          onCancel={() => setReturning(false)}
        />
      )}

      {tendering && (
        <TenderOverlay
          totalDueMinor={cart.totals.totalMinor}
          busy={busy}
          onCancel={() => setTendering(false)}
          onConfirm={(outcome) => void submitSale(outcome)}
        />
      )}

      {choosingCustomer && (
        <CustomerOverlay
          attached={customer}
          branchCode={shop.branchCode}
          terminalCode={shop.terminalCode}
          onAttach={(chosen) => {
            setCustomer(chosen);
            setChoosingCustomer(false);
            setMessage(
              chosen === null
                ? { tone: 'ok', text: 'Customer removed from this sale' }
                : { tone: 'ok', text: `This sale is for ${chosen.name}` },
            );
          }}
          onClose={() => setChoosingCustomer(false)}
        />
      )}

      {issuingTaxInvoice && (
        <TaxInvoiceOverlay
          defaultSaleInvoiceNumber={lastSaleInvoiceNumber}
          onIssued={(invoice) => {
            setIssuingTaxInvoice(false);
            void printTaxInvoice(invoice);
          }}
          onClose={() => setIssuingTaxInvoice(false)}
        />
      )}

      {helping && <HelpOverlay onClose={() => setHelping(false)} />}

      {searching && (
        <SearchOverlay
          lookup={lookup}
          onPick={(product) => {
            addProduct(product);
            setSearching(false);
            setMessage(null);
          }}
          onCancel={() => setSearching(false)}
        />
      )}
    </div>
  );
}
