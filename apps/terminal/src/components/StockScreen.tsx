'use client';

import {
  ADJUSTMENT_DIRECTION,
  ADJUSTMENT_REASON_LABEL,
  adjustmentNeedsNote,
  formatMinor,
  parseAmountToMinor,
  signedAdjustmentQty,
  STOCK_ADJUSTMENT_REASONS,
  type StockAdjustmentReason,
} from '@lumora/domain';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { FIELD_CLASS, Labelled, NUMERIC_FIELD_CLASS } from '@/components/Labelled';
import { StocktakePanel } from '@/components/StocktakePanel';
import { useEntitlement } from '@/lib/useEntitlement';
import type { BackOffice } from '@/lib/useBackOffice';

/**
 * Suppliers, and goods arriving from them (M3-04).
 *
 * <h2>Booking in a delivery is the whole screen</h2>
 *
 * The shopkeeper is standing at the back door with a delivery note in one hand. What they need is
 * to pick the supplier, type the note's number, and add a line per item — quantity and what it
 * cost. Everything else here is in service of that: the supplier list exists so a delivery has
 * somebody to point at, and the recent-deliveries list exists so they can see the one they just
 * entered and catch a mistake immediately.
 *
 * <h2>Cost, not price</h2>
 *
 * The cost box is what the shop paid. Nothing on this screen changes a shelf price, and the form
 * says so — otherwise the natural assumption is that entering a delivery reprices the goods, and
 * the first time someone believed that they would stop checking.
 *
 * <h2>Nothing here edits or deletes</h2>
 *
 * A receipt is a document. A wrong one is corrected by a stock adjustment (M3-05), which leaves the
 * miscount and the correction both on the record — the same reasoning that makes a refund a credit
 * note rather than an edit to the sale.
 */
interface SupplierRow {
  id: number;
  clientUuid: string;
  name: string;
  contact: string | null;
  active: boolean;
  receiptCount: number;
}

interface ReceiptLineRow {
  lineNo: number;
  productClientUuid: string;
  sku: string;
  productName: string;
  qty: number;
  unitCostMinor: number;
  lineCostMinor: number;
}

interface ReceiptRow {
  id: number;
  clientUuid: string;
  supplierName: string;
  supplierId: number;
  reference: string | null;
  receivedAt: string;
  note: string | null;
  receivedByName: string;
  lineCount: number;
  totalQty: number;
  totalCostMinor: number;
  lines: ReceiptLineRow[];
}

interface ProductRow {
  id: number;
  clientUuid: string;
  sku: string;
  name: string;
  active: boolean;
}

interface OnHandRow {
  productClientUuid: string;
  sku: string;
  productName: string;
  categoryName: string | null;
  qtyOnHand: number;
  lastMovedAt: string | null;
  active: boolean;
}

interface OnHandSummary {
  products: number;
  belowZero: number;
  outOfStock: number;
  totalUnits: number;
}

interface AdjustmentRow {
  clientUuid: string;
  productClientUuid: string;
  sku: string;
  productName: string;
  qtyDelta: number;
  reason: StockAdjustmentReason;
  note: string | null;
  byName: string;
  at: string;
  onHandAfter: number;
}

/** A line being typed. Quantity and cost stay as text until they are parsed on submit. */
interface DraftLine {
  productClientUuid: string;
  qty: string;
  cost: string;
}

const BLANK_LINE: DraftLine = { productClientUuid: '', qty: '1', cost: '' };

export function StockScreen({
  office,
  branchCode,
  openOnHand = false,
}: {
  office: BackOffice;
  branchCode: string;
  /**
   * Open the on-hand panel straight away.
   *
   * <p>Set by the Reports screen's stock tab (M3-10), whose button says "Open stock on hand" and
   * should therefore do exactly that. Landing on the Stock screen with the panel closed would make
   * the button a redirect and leave the reader to hunt for the thing they asked for.
   */
  openOnHand?: boolean;
}) {
  const [suppliers, setSuppliers] = useState<SupplierRow[] | null>(null);
  const [receipts, setReceipts] = useState<ReceiptRow[]>([]);
  const [products, setProducts] = useState<ProductRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [managingSuppliers, setManagingSuppliers] = useState(false);
  const [receiving, setReceiving] = useState(false);
  const [adjusting, setAdjusting] = useState(false);
  const [counting, setCounting] = useState(false);
  const { allows } = useEntitlement();
  const [showingOnHand, setShowingOnHand] = useState(openOnHand);
  const [adjustments, setAdjustments] = useState<AdjustmentRow[]>([]);
  const [expanded, setExpanded] = useState<number | null>(null);

  const load = useCallback(async () => {
    try {
      const [loadedSuppliers, loadedReceipts, loadedProducts, loadedAdjustments] =
        await Promise.all([
          office.request<SupplierRow[]>('/api/back-office/suppliers'),
          office.request<ReceiptRow[]>('/api/back-office/goods-receipts?limit=25'),
          office.request<ProductRow[]>('/api/back-office/products'),
          office.request<AdjustmentRow[]>('/api/back-office/stock-adjustments?limit=25'),
        ]);
      setSuppliers(loadedSuppliers);
      setReceipts(loadedReceipts);
      setProducts(loadedProducts.filter((product) => product.active));
      setAdjustments(loadedAdjustments);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [office]);

  useEffect(() => {
    void load();
  }, [load]);

  const act = useCallback(
    async (what: string, run: () => Promise<unknown>) => {
      setError(null);
      setNotice(null);
      try {
        await run();
        setNotice(what);
        await load();
        return true;
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        return false;
      }
    },
    [load],
  );

  const activeSuppliers = useMemo(
    () => (suppliers ?? []).filter((supplier) => supplier.active),
    [suppliers],
  );

  return (
    <div className="flex flex-col gap-4">
      <header className="flex flex-wrap items-baseline justify-between gap-4">
        <div>
          <h2 className="text-ink text-lg font-semibold">Stock</h2>
          <p className="text-ink-3 text-sm">
            Deliveries in, and who they came from. Stock on hand is always the sum of what has moved
            — nothing here sets a level.
          </p>
        </div>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setShowingOnHand((open) => !open)}
            className="border-hair text-ink-2 min-h-touch rounded border px-4"
          >
            On hand
          </button>
          <button
            type="button"
            onClick={() => setManagingSuppliers((open) => !open)}
            className="border-hair text-ink-2 min-h-touch rounded border px-4"
          >
            Suppliers
          </button>
          {/*
            Stocktake and booking in a delivery are plan capabilities (M4-09). Adjusting stock is
            not, and that split is deliberate: a shop on any plan has to be able to write off a
            broken bottle, or its stock figures start lying and never stop.
          */}
          {allows('stocktake') && (
            <button
              type="button"
              onClick={() => {
                setCounting(true);
                setAdjusting(false);
                setReceiving(false);
                setManagingSuppliers(false);
              }}
              className="border-hair text-ink-2 min-h-touch rounded border px-4"
            >
              Stocktake
            </button>
          )}
          <button
            type="button"
            onClick={() => {
              setAdjusting(true);
              setCounting(false);
              setReceiving(false);
              setManagingSuppliers(false);
            }}
            className="border-hair text-ink-2 min-h-touch rounded border px-4"
          >
            Adjust stock
          </button>
          <button
            type="button"
            onClick={() => {
              setReceiving(true);
              setAdjusting(false);
              setCounting(false);
              setManagingSuppliers(false);
            }}
            disabled={activeSuppliers.length === 0 || !allows('goods_receipt')}
            className="border-accent text-accent min-h-touch rounded border px-4 disabled:opacity-40"
          >
            Book in a delivery
          </button>
        </div>
      </header>

      {error && (
        <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" className="border-ok text-ok border-l-2 px-3 py-2 text-sm">
          {notice}
        </p>
      )}

      {suppliers !== null && activeSuppliers.length === 0 && (
        <p role="status" className="border-pending text-pending border-l-2 px-3 py-2 text-sm">
          Add a supplier first — a delivery has to say who it came from.
        </p>
      )}

      {showingOnHand && (
        <OnHandPanel
          office={office}
          branchCode={branchCode}
          onClose={() => setShowingOnHand(false)}
        />
      )}

      {managingSuppliers && (
        <SuppliersPanel
          suppliers={suppliers ?? []}
          onCreate={(name, contact) =>
            act(`${name} added.`, () =>
              office.request('/api/back-office/suppliers', {
                method: 'POST',
                body: JSON.stringify({ clientUuid: crypto.randomUUID(), name, contact }),
              }),
            )
          }
          onUpdate={(supplier, name, contact, active) =>
            act(`${name} updated.`, () =>
              office.request(`/api/back-office/suppliers/${supplier.id}`, {
                method: 'PUT',
                body: JSON.stringify({ name, contact, active }),
              }),
            )
          }
          onClose={() => setManagingSuppliers(false)}
        />
      )}

      {receiving && (
        <ReceiveForm
          suppliers={activeSuppliers}
          products={products}
          onCancel={() => setReceiving(false)}
          onSubmit={async (supplierId, reference, note, lines) => {
            const ok = await act('Delivery booked in. Stock is on the shelf.', () =>
              office.request('/api/back-office/goods-receipts', {
                method: 'POST',
                body: JSON.stringify({
                  clientUuid: crypto.randomUUID(),
                  branchCode,
                  supplierId,
                  reference,
                  note,
                  lines,
                }),
              }),
            );
            if (ok) setReceiving(false);
          }}
          onError={setError}
        />
      )}

      {counting && allows('stocktake') && (
        <StocktakePanel
          office={office}
          branchCode={branchCode}
          products={products}
          onClose={() => setCounting(false)}
          onChanged={() => void load()}
        />
      )}

      {adjusting && (
        <AdjustForm
          products={products}
          branchCode={branchCode}
          office={office}
          onCancel={() => setAdjusting(false)}
          onError={(message) => {
            setError(message);
            setNotice(null);
          }}
          onDone={(summary) => {
            setAdjusting(false);
            setError(null);
            setNotice(`Stock adjusted. ${summary}`);
            void load();
          }}
        />
      )}

      {adjustments.length > 0 && (
        <>
          <h3 className="text-ink-2 text-sm font-semibold uppercase tracking-wider">
            Recent adjustments
          </h3>
          <ul className="flex flex-col gap-1">
            {adjustments.map((adjustment) => (
              <li
                key={adjustment.clientUuid}
                className="border-hair flex flex-wrap items-baseline gap-x-4 gap-y-1 border-b pb-1 text-sm last:border-b-0"
              >
                <span className="text-ink-3 w-28">
                  {new Date(adjustment.at).toLocaleDateString()}
                </span>
                <span className="lum-money text-ink-3 w-28">{adjustment.sku}</span>
                <span className="text-ink min-w-40 flex-1">{adjustment.productName}</span>
                {/* Signed, and coloured by direction — with the sign itself carrying the meaning
                    so colour is never doing the work alone (§A). */}
                <span
                  className={`lum-money w-16 text-right font-semibold ${
                    adjustment.qtyDelta < 0 ? 'text-danger' : 'text-ok'
                  }`}
                >
                  {adjustment.qtyDelta > 0 ? `+${adjustment.qtyDelta}` : adjustment.qtyDelta}
                </span>
                <span className="text-ink-2 w-56">
                  {ADJUSTMENT_REASON_LABEL[adjustment.reason]}
                  {adjustment.note ? ` — ${adjustment.note}` : ''}
                </span>
                <span className="lum-money text-ink-3 w-20 text-right">
                  {adjustment.onHandAfter} left
                </span>
                <span className="text-ink-3 w-32">{adjustment.byName}</span>
              </li>
            ))}
          </ul>
        </>
      )}

      <h3 className="text-ink-2 text-sm font-semibold uppercase tracking-wider">
        Recent deliveries
      </h3>

      {suppliers === null ? (
        <p className="text-ink-3 text-sm">Loading…</p>
      ) : receipts.length === 0 ? (
        <p className="text-ink-3 text-sm">Nothing has been booked in yet.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {receipts.map((receipt) => (
            <li key={receipt.id} className="border-hair rounded border px-4 py-3">
              <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
                <span className="text-ink min-w-40 flex-1 font-semibold">
                  {receipt.supplierName}
                </span>
                <span className="lum-money text-ink-2 w-32 text-sm">
                  {receipt.reference ?? 'no note №'}
                </span>
                <span className="text-ink-3 w-36 text-sm">
                  {new Date(receipt.receivedAt).toLocaleDateString()}
                </span>
                <span className="text-ink-3 w-32 text-sm">
                  {receipt.lineCount} line{receipt.lineCount === 1 ? '' : 's'} · {receipt.totalQty}{' '}
                  units
                </span>
                <span className="lum-money text-ink w-28 text-right">
                  {formatMinor(receipt.totalCostMinor)}
                </span>
                <button
                  type="button"
                  onClick={() => setExpanded(expanded === receipt.id ? null : receipt.id)}
                  className="border-hair text-ink-2 min-h-touch rounded border px-3"
                >
                  {expanded === receipt.id ? 'Hide' : 'Lines'}
                </button>
              </div>

              {expanded === receipt.id && (
                <div className="border-hair mt-3 flex flex-col gap-1 border-t pt-3">
                  {receipt.lines.map((line) => (
                    <div key={line.lineNo} className="flex flex-wrap gap-x-4 text-sm">
                      <span className="lum-money text-ink-3 w-28">{line.sku}</span>
                      <span className="text-ink-2 min-w-40 flex-1">{line.productName}</span>
                      <span className="lum-money text-ink-2 w-16 text-right">{line.qty}</span>
                      <span className="lum-money text-ink-3 w-28 text-right">
                        @ {formatMinor(line.unitCostMinor)}
                      </span>
                      <span className="lum-money text-ink w-28 text-right">
                        {formatMinor(line.lineCostMinor)}
                      </span>
                    </div>
                  ))}
                  <p className="text-ink-3 mt-2 text-xs">
                    Booked in by {receipt.receivedByName}
                    {receipt.note ? ` — ${receipt.note}` : ''}. A delivery is never edited; correct
                    a mistake with a stock adjustment.
                  </p>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ------------------------------------------------------------------------ the delivery

function ReceiveForm({
  suppliers,
  products,
  onCancel,
  onSubmit,
  onError,
}: {
  suppliers: SupplierRow[];
  products: ProductRow[];
  onCancel: () => void;
  onSubmit: (
    supplierId: number,
    reference: string,
    note: string,
    lines: { productClientUuid: string; qty: number; unitCostMinor: number }[],
  ) => Promise<void>;
  onError: (message: string) => void;
}) {
  const [supplierId, setSupplierId] = useState<number>(suppliers[0]?.id ?? 0);
  const [reference, setReference] = useState('');
  const [note, setNote] = useState('');
  const [lines, setLines] = useState<DraftLine[]>([BLANK_LINE]);
  // Locked while the request is in flight. Without it a second press mints a second client uuid
  // and books the same delivery in twice — which on this screen is a doubled shelf.
  const [busy, setBusy] = useState(false);

  const setLine = (index: number, patch: Partial<DraftLine>) =>
    setLines((current) => current.map((line, i) => (i === index ? { ...line, ...patch } : line)));

  /**
   * The running total, so a delivery note that comes to a different figure is caught here rather
   * than a month later. Computed with the same integer arithmetic the backend will use — a line
   * whose cost has not been typed yet simply does not count towards it.
   */
  const totalMinor = lines.reduce((sum, line) => {
    const cost = parseAmountToMinor(line.cost);
    const qty = Number.parseInt(line.qty, 10);
    if (cost === null || !Number.isFinite(qty)) return sum;
    return sum + cost * qty;
  }, 0);

  return (
    <form
      className="border-accent flex flex-col gap-4 rounded border p-4"
      onSubmit={(event) => {
        event.preventDefault();

        const parsed: { productClientUuid: string; qty: number; unitCostMinor: number }[] = [];
        for (const [index, line] of lines.entries()) {
          if (line.productClientUuid === '') {
            onError(`Line ${index + 1} has no product.`);
            return;
          }
          const qty = Number.parseInt(line.qty, 10);
          if (!Number.isFinite(qty) || qty < 1) {
            onError(`Line ${index + 1} needs a quantity of at least 1.`);
            return;
          }
          const unitCostMinor = parseAmountToMinor(line.cost);
          if (unitCostMinor === null || unitCostMinor < 0) {
            onError(`Line ${index + 1} needs a cost. Type it in rupees, as 285.00 or 285.`);
            return;
          }
          parsed.push({ productClientUuid: line.productClientUuid, qty, unitCostMinor });
        }

        setBusy(true);
        void onSubmit(supplierId, reference, note, parsed).finally(() => setBusy(false));
      }}
    >
      <h3 className="text-ink font-semibold">Book in a delivery</h3>

      <div className="flex flex-wrap gap-3">
        <Labelled label="Supplier">
          <select
            value={supplierId}
            onChange={(event) => setSupplierId(Number(event.target.value))}
            className={FIELD_CLASS}
          >
            {suppliers.map((supplier) => (
              <option key={supplier.id} value={supplier.id}>
                {supplier.name}
              </option>
            ))}
          </select>
        </Labelled>
        <Labelled label="Delivery note №" hint="the supplier's own number — stops double entry">
          <input
            value={reference}
            onChange={(event) => setReference(event.target.value)}
            className={`${NUMERIC_FIELD_CLASS} w-48`}
          />
        </Labelled>
        <Labelled label="Note" hint="optional">
          <input
            value={note}
            onChange={(event) => setNote(event.target.value)}
            className={`${FIELD_CLASS} w-64`}
          />
        </Labelled>
      </div>

      <fieldset className="flex flex-col gap-2">
        <legend className="text-ink-3 text-xs uppercase tracking-wider">What arrived</legend>
        <p className="text-ink-3 text-xs">
          The cost is what you paid, not the shelf price. Nothing here changes what a customer is
          charged.
        </p>

        {lines.map((line, index) => (
          // Keyed by position: the row's identity is its place in the list, and new rows are
          // always appended. Keying by product would remount the row as soon as one was chosen.
          <div key={index} className="flex flex-wrap items-end gap-2">
            <Labelled label={`Product ${index + 1}`}>
              <select
                value={line.productClientUuid}
                onChange={(event) => setLine(index, { productClientUuid: event.target.value })}
                className={`${FIELD_CLASS} w-72`}
              >
                <option value="">— choose —</option>
                {products.map((product) => (
                  <option key={product.clientUuid} value={product.clientUuid}>
                    {product.sku} — {product.name}
                  </option>
                ))}
              </select>
            </Labelled>
            <Labelled label={`Quantity ${index + 1}`}>
              <input
                value={line.qty}
                onChange={(event) => setLine(index, { qty: event.target.value.replace(/\D/g, '') })}
                inputMode="numeric"
                className={`${NUMERIC_FIELD_CLASS} w-24 text-right`}
              />
            </Labelled>
            <Labelled label={`Unit cost ${index + 1}`}>
              <input
                value={line.cost}
                onChange={(event) => setLine(index, { cost: event.target.value })}
                inputMode="decimal"
                className={`${NUMERIC_FIELD_CLASS} w-32 text-right`}
              />
            </Labelled>
            {lines.length > 1 && (
              <button
                type="button"
                onClick={() => setLines((current) => current.filter((_, i) => i !== index))}
                className="border-hair text-ink-2 min-h-touch rounded border px-3"
              >
                Remove
              </button>
            )}
          </div>
        ))}

        <button
          type="button"
          onClick={() => setLines((current) => [...current, BLANK_LINE])}
          className="border-hair text-ink-2 min-h-touch self-start rounded border px-3"
        >
          Add another line
        </button>
      </fieldset>

      <p className="text-ink flex items-baseline gap-3">
        <span className="text-ink-3 text-xs uppercase tracking-wider">Delivery total</span>
        <span className="lum-money text-lg font-semibold">{formatMinor(totalMinor)}</span>
      </p>

      <div className="flex gap-2">
        <button
          type="submit"
          disabled={busy}
          className="border-accent text-accent min-h-touch rounded border px-4 font-semibold disabled:opacity-40"
        >
          {busy ? 'Booking in…' : 'Book it in'}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="border-hair text-ink-2 min-h-touch rounded border px-4"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}

// ------------------------------------------------------------------------- suppliers

function SuppliersPanel({
  suppliers,
  onCreate,
  onUpdate,
  onClose,
}: {
  suppliers: SupplierRow[];
  onCreate: (name: string, contact: string) => Promise<boolean>;
  onUpdate: (
    supplier: SupplierRow,
    name: string,
    contact: string,
    active: boolean,
  ) => Promise<boolean>;
  onClose: () => void;
}) {
  const [name, setName] = useState('');
  const [contact, setContact] = useState('');

  return (
    <section className="border-hair flex flex-col gap-3 rounded border p-4">
      <h3 className="text-ink font-semibold">Suppliers</h3>
      <p className="text-ink-3 text-sm">
        Retiring one takes them off the delivery form and leaves their history intact.
      </p>

      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={async (event) => {
          event.preventDefault();
          if (await onCreate(name.trim(), contact.trim())) {
            setName('');
            setContact('');
          }
        }}
      >
        <Labelled label="New supplier">
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={128}
            required
            className={FIELD_CLASS}
          />
        </Labelled>
        <Labelled label="Contact" hint="a phone number, a rep's name — optional">
          <input
            value={contact}
            onChange={(event) => setContact(event.target.value)}
            className={FIELD_CLASS}
          />
        </Labelled>
        <button
          type="submit"
          className="border-accent text-accent min-h-touch rounded border px-4 font-semibold"
        >
          Add
        </button>
      </form>

      <ul className="flex flex-col gap-2">
        {suppliers.map((supplier) => (
          <SupplierLine key={supplier.id} supplier={supplier} onUpdate={onUpdate} />
        ))}
      </ul>

      <button
        type="button"
        onClick={onClose}
        className="border-hair text-ink-2 min-h-touch self-start rounded border px-4"
      >
        Done
      </button>
    </section>
  );
}

function SupplierLine({
  supplier,
  onUpdate,
}: {
  supplier: SupplierRow;
  onUpdate: (
    supplier: SupplierRow,
    name: string,
    contact: string,
    active: boolean,
  ) => Promise<boolean>;
}) {
  const [name, setName] = useState(supplier.name);
  const [contact, setContact] = useState(supplier.contact ?? '');

  const edited = name.trim() !== supplier.name || contact.trim() !== (supplier.contact ?? '');

  return (
    <li className="border-hair flex flex-wrap items-center gap-3 rounded border px-3 py-2">
      <input
        value={name}
        onChange={(event) => setName(event.target.value)}
        aria-label={`Supplier name for ${supplier.name}`}
        className={`${FIELD_CLASS} w-56`}
      />
      <input
        value={contact}
        onChange={(event) => setContact(event.target.value)}
        aria-label={`Contact for ${supplier.name}`}
        className={`${FIELD_CLASS} w-40`}
      />
      <span className="text-ink-3 w-32 text-sm">
        {supplier.receiptCount} deliver{supplier.receiptCount === 1 ? 'y' : 'ies'}
      </span>
      <span className={`w-24 text-sm ${supplier.active ? 'text-ok' : 'text-ink-3'}`}>
        {supplier.active ? '● in use' : '○ retired'}
      </span>
      <button
        type="button"
        disabled={!edited}
        onClick={() => void onUpdate(supplier, name.trim(), contact.trim(), supplier.active)}
        className="border-hair text-ink-2 min-h-touch rounded border px-3 disabled:opacity-40"
      >
        Save
      </button>
      <button
        type="button"
        onClick={() =>
          void onUpdate(supplier, supplier.name, supplier.contact ?? '', !supplier.active)
        }
        className="border-hair text-ink-2 min-h-touch rounded border px-3"
      >
        {supplier.active ? 'Retire' : 'Bring back'}
      </button>
    </li>
  );
}

// ---------------------------------------------------------------------- adjustments

/**
 * Moving stock with no document behind it (M3-05).
 *
 * The form asks for a quantity as a plain positive number and never for a sign — the reason
 * carries the direction, exactly as it does for a pay-out on the drawer. A shopkeeper who has to
 * remember to type a minus will one day forget, and stock moves by twice the amount the wrong way
 * with nothing to flag it.
 *
 * What the shopkeeper is shown before they commit is where the shelf lands. They are changing a
 * number they cannot see, and "17 → 14" is the difference between an adjustment they can check and
 * one they have to trust.
 */
function AdjustForm({
  products,
  branchCode,
  office,
  onCancel,
  onDone,
  onError,
}: {
  products: ProductRow[];
  branchCode: string;
  office: BackOffice;
  onCancel: () => void;
  onDone: (summary: string) => void;
  onError: (message: string) => void;
}) {
  const [productClientUuid, setProductClientUuid] = useState('');
  const [reason, setReason] = useState<StockAdjustmentReason>('DAMAGED');
  const [qty, setQty] = useState('');
  const [increase, setIncrease] = useState(false);
  const [note, setNote] = useState('');
  const [onHand, setOnHand] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  const direction = ADJUSTMENT_DIRECTION[reason];

  // Fetched rather than derived, because the till may have sold some of it while this form was
  // open. A stale figure here would make the preview below quietly wrong.
  useEffect(() => {
    if (productClientUuid === '') {
      setOnHand(null);
      return;
    }
    let current = true;
    void office
      .request<{ onHand: number }>(
        `/api/back-office/stock-on-hand?branchCode=${encodeURIComponent(branchCode)}` +
          `&productClientUuid=${encodeURIComponent(productClientUuid)}`,
      )
      .then((response) => {
        if (current) setOnHand(response.onHand);
      })
      .catch(() => {
        if (current) setOnHand(null);
      });
    return () => {
      current = false;
    };
  }, [branchCode, office, productClientUuid]);

  /** Null while the form is not yet answerable — a half-typed quantity is normal, not an error. */
  const qtyDelta = (() => {
    const typed = Number.parseInt(qty, 10);
    if (!Number.isFinite(typed) || typed < 1) return null;
    try {
      return signedAdjustmentQty(reason, typed, direction === 'EITHER' ? increase : undefined);
    } catch {
      return null;
    }
  })();

  const after = onHand !== null && qtyDelta !== null ? onHand + qtyDelta : null;

  return (
    <form
      className="border-accent flex flex-col gap-4 rounded border p-4"
      onSubmit={async (event) => {
        event.preventDefault();
        if (productClientUuid === '') {
          onError('Choose a product first.');
          return;
        }
        if (qtyDelta === null) {
          onError('Type how many, as a whole number of at least 1.');
          return;
        }
        if (adjustmentNeedsNote(reason) && note.trim() === '') {
          onError('Choosing "something else" means writing down what it was.');
          return;
        }

        setBusy(true);
        try {
          const saved = await office.request<{ onHandAfter: number; productName: string }>(
            '/api/back-office/stock-adjustments',
            {
              method: 'POST',
              body: JSON.stringify({
                clientUuid: crypto.randomUUID(),
                branchCode,
                productClientUuid,
                qtyDelta,
                reasonCode: reason,
                note,
              }),
            },
          );
          onDone(`${saved.productName} — ${saved.onHandAfter} on hand.`);
        } catch (e) {
          onError(e instanceof Error ? e.message : String(e));
        } finally {
          setBusy(false);
        }
      }}
    >
      <h3 className="text-ink font-semibold">Adjust stock</h3>
      <p className="text-ink-3 text-sm">
        For anything that changes stock without a sale or a delivery. Every adjustment records who
        made it and why, and none of them can be undone — a mistake is corrected by another
        adjustment, so both stay on the record.
      </p>

      <div className="flex flex-wrap gap-3">
        <Labelled label="Product">
          <select
            value={productClientUuid}
            onChange={(event) => setProductClientUuid(event.target.value)}
            className={`${FIELD_CLASS} w-72`}
          >
            <option value="">— choose —</option>
            {products.map((product) => (
              <option key={product.clientUuid} value={product.clientUuid}>
                {product.sku} — {product.name}
              </option>
            ))}
          </select>
        </Labelled>

        <Labelled label="Reason">
          <select
            value={reason}
            onChange={(event) => setReason(event.target.value as StockAdjustmentReason)}
            className={`${FIELD_CLASS} w-64`}
          >
            {STOCK_ADJUSTMENT_REASONS.map((candidate) => (
              <option key={candidate} value={candidate}>
                {ADJUSTMENT_REASON_LABEL[candidate]}
              </option>
            ))}
          </select>
        </Labelled>

        <Labelled label="How many" hint="a plain count — the reason decides the direction">
          <input
            value={qty}
            onChange={(event) => setQty(event.target.value.replace(/\D/g, ''))}
            inputMode="numeric"
            required
            className={`${NUMERIC_FIELD_CLASS} w-24 text-right`}
          />
        </Labelled>

        {direction === 'EITHER' && (
          <fieldset className="flex flex-col gap-2">
            <legend className="text-ink-3 text-xs uppercase tracking-wider">Which way</legend>
            <div className="flex gap-2">
              {[
                { value: false, label: 'remove from the shelf' },
                { value: true, label: 'add to the shelf' },
              ].map((option) => (
                <label
                  key={String(option.value)}
                  className={`min-h-touch flex cursor-pointer items-center gap-2 rounded border px-3 ${
                    increase === option.value
                      ? 'border-accent text-accent'
                      : 'border-hair text-ink-2'
                  }`}
                >
                  <input
                    type="radio"
                    name="direction"
                    checked={increase === option.value}
                    onChange={() => setIncrease(option.value)}
                    className="sr-only"
                  />
                  {option.label}
                </label>
              ))}
            </div>
          </fieldset>
        )}
      </div>

      <Labelled
        label="Note"
        hint={adjustmentNeedsNote(reason) ? 'required for "something else"' : 'optional'}
      >
        <input
          value={note}
          onChange={(event) => setNote(event.target.value)}
          required={adjustmentNeedsNote(reason)}
          className={`${FIELD_CLASS} w-full`}
        />
      </Labelled>

      {/*
        The consequence, before it happens. Negative is called out in the danger colour *and* in
        words, because a shelf that goes below zero is either a missing delivery or a real
        discrepancy, and both need looking at rather than accepting.
      */}
      <p className="flex flex-wrap items-baseline gap-3 text-sm" role="status">
        <span className="text-ink-3 text-xs uppercase tracking-wider">On hand</span>
        {onHand === null ? (
          <span className="text-ink-3">choose a product</span>
        ) : after === null ? (
          <span className="lum-money text-ink">{onHand}</span>
        ) : (
          <>
            <span className="lum-money text-ink-2">{onHand}</span>
            <span className="text-ink-3">→</span>
            <span
              className={`lum-money text-lg font-semibold ${after < 0 ? 'text-danger' : 'text-ink'}`}
            >
              {after}
            </span>
            {after < 0 && (
              <span className="text-danger">
                ▲ that leaves the shelf below zero — check for a delivery that was never booked in
              </span>
            )}
          </>
        )}
      </p>

      <div className="flex gap-2">
        <button
          type="submit"
          disabled={busy}
          className="border-accent text-accent min-h-touch rounded border px-4 font-semibold disabled:opacity-40"
        >
          {busy ? 'Saving…' : 'Adjust the stock'}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="border-hair text-ink-2 min-h-touch rounded border px-4"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}

// ------------------------------------------------------------------------- on hand

/**
 * What is on every shelf (M3-07).
 *
 * Nothing here is cached and there is no refresh button, because there is nothing to refresh: the
 * figure is the sum of the movements, read when the screen asks. A shopkeeper looking at this is
 * looking at the movements, not at a number somebody maintained — which is the whole reason the
 * schema has no level column to be wrong.
 *
 * The ordering is the design: below zero first, then out of stock, then everything else. Somebody
 * opens this screen because they suspect a problem, and an alphabetical list makes them scroll past
 * four hundred correct rows to find it.
 */
function OnHandPanel({
  office,
  branchCode,
  onClose,
}: {
  office: BackOffice;
  branchCode: string;
  onClose: () => void;
}) {
  const [rows, setRows] = useState<OnHandRow[] | null>(null);
  const [summary, setSummary] = useState<OnHandSummary | null>(null);
  const [includeDiscontinued, setIncludeDiscontinued] = useState(false);
  const [filter, setFilter] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    void office
      .request<{ rows: OnHandRow[]; summary: OnHandSummary }>(
        `/api/back-office/stock-on-hand/all?branchCode=${encodeURIComponent(branchCode)}` +
          `&includeDiscontinued=${includeDiscontinued}`,
      )
      .then((response) => {
        if (!current) return;
        setRows(response.rows);
        setSummary(response.summary);
        setError(null);
      })
      .catch((e: unknown) => {
        if (current) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      current = false;
    };
  }, [branchCode, includeDiscontinued, office]);

  const visible = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    return (rows ?? []).filter(
      (row) =>
        needle === '' ||
        row.productName.toLowerCase().includes(needle) ||
        row.sku.toLowerCase().includes(needle),
    );
  }, [rows, filter]);

  return (
    <section className="border-hair flex flex-col gap-3 rounded border p-4">
      <header className="flex flex-wrap items-baseline justify-between gap-3">
        <div>
          <h3 className="text-ink font-semibold">Stock on hand</h3>
          <p className="text-ink-3 text-sm">
            The sum of everything that has moved. Nothing here is a stored figure, so it cannot be
            out of date — and a shelf below zero means a delivery was never booked in.
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="border-hair text-ink-2 min-h-touch rounded border px-4"
        >
          Close
        </button>
      </header>

      {error && (
        <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
          {error}
        </p>
      )}

      {summary && (
        <ul className="flex flex-wrap gap-x-6 gap-y-1 text-sm" aria-label="Stock summary">
          <li className="text-ink-2">{summary.products} products</li>
          <li className={summary.belowZero > 0 ? 'text-danger font-semibold' : 'text-ink-3'}>
            {summary.belowZero > 0 ? '▲' : '○'} {summary.belowZero} below zero
          </li>
          <li className="text-ink-3">○ {summary.outOfStock} out of stock</li>
          <li className="lum-money text-ink-2">{summary.totalUnits} units in total</li>
        </ul>
      )}

      <div className="flex flex-wrap items-end gap-4">
        <Labelled label="Find" hint="name or code">
          <input
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            className={`${FIELD_CLASS} w-64`}
          />
        </Labelled>
        <label className="min-h-touch text-ink-2 flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={includeDiscontinued}
            onChange={(event) => setIncludeDiscontinued(event.target.checked)}
          />
          {/* A discontinued line can still have stock to sell through, so it is worth being able
              to see — just not by default, where it would pad the list nobody wants padded. */}
          Include discontinued
        </label>
      </div>

      {rows === null ? (
        <p className="text-ink-3 text-sm">Loading…</p>
      ) : (
        <ul className="flex flex-col gap-1" aria-label="Stock on hand">
          {visible.map((row) => (
            <li
              key={row.productClientUuid}
              className={`border-hair flex flex-wrap items-baseline gap-x-4 gap-y-1 border-b pb-1 text-sm last:border-b-0 ${
                row.active ? '' : 'opacity-60'
              }`}
            >
              <span className="lum-money text-ink-3 w-28">{row.sku}</span>
              <span className="text-ink min-w-40 flex-1">{row.productName}</span>
              <span className="text-ink-3 w-32">{row.categoryName ?? '—'}</span>
              {/* Sign and words carry the meaning; colour only reinforces it (§A). */}
              <span
                className={`lum-money w-24 text-right font-semibold ${
                  row.qtyOnHand < 0
                    ? 'text-danger'
                    : row.qtyOnHand === 0
                      ? 'text-ink-3'
                      : 'text-ink'
                }`}
              >
                {row.qtyOnHand}
              </span>
              <span
                className={`w-32 text-sm ${
                  row.qtyOnHand < 0 ? 'text-danger' : row.qtyOnHand === 0 ? 'text-ink-3' : 'text-ok'
                }`}
              >
                {row.qtyOnHand < 0
                  ? '▲ below zero'
                  : row.qtyOnHand === 0
                    ? row.lastMovedAt === null
                      ? '○ never stocked'
                      : '○ out of stock'
                    : '● in stock'}
              </span>
            </li>
          ))}
        </ul>
      )}

      {rows !== null && visible.length === 0 && (
        <p className="text-ink-3 text-sm">Nothing matches that.</p>
      )}
    </section>
  );
}
