'use client';

import { formatMinor, parseAmountToMinor } from '@lumora/domain';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { FIELD_CLASS, Labelled, NUMERIC_FIELD_CLASS } from '@/components/Labelled';
import { ProductImportPanel } from '@/components/ProductImportPanel';
import type { BackOffice } from '@/lib/useBackOffice';
import { useEntitlement } from '@/lib/useEntitlement';

/**
 * The catalogue, as an owner edits it (M3-02).
 *
 * <h2>Everything is on one screen and the list is filtered here</h2>
 *
 * A shop's catalogue is a few hundred to a few thousand rows, all of it already on this machine.
 * Fetching it once and filtering in the browser is instant, works with the network unplugged, and
 * spares a paginated list that an owner looking for "the tea one" has to page through. If a
 * catalogue ever arrives that this cannot hold, the fix is a server-side filter on this same
 * screen — not pagination.
 *
 * <h2>Prices are typed as rupees and never become a float</h2>
 *
 * `parseAmountToMinor` does the conversion by string surgery, because `parseFloat('0.29') * 100` is
 * 28.999999999999996 and that is a cent lost on a value somebody types every day. The tax rate goes
 * through the same function: basis points are a percentage with two decimals, which is exactly the
 * arithmetic rupees-to-cents already does, so 18 becomes 1800 and 2.5 becomes 250 with no second
 * implementation to get wrong.
 *
 * <h2>Nothing is deleted</h2>
 *
 * A product that has been sold is referenced by `sale_items`, so it is discontinued and stays
 * listed. Same for categories. The buttons say so.
 */
interface ProductRow {
  id: number;
  clientUuid: string;
  sku: string;
  name: string;
  priceMinor: number;
  taxMode: 'INCLUSIVE' | 'EXCLUSIVE';
  taxRateBp: number;
  categoryId: number | null;
  categoryName: string | null;
  barcodes: string[];
  active: boolean;
}

interface CategoryRow {
  id: number;
  clientUuid: string;
  name: string;
  active: boolean;
  productCount: number;
}

interface Draft {
  sku: string;
  name: string;
  price: string;
  taxMode: 'INCLUSIVE' | 'EXCLUSIVE';
  taxRate: string;
  categoryId: number | null;
  barcodes: string[];
}

const BLANK: Draft = {
  sku: '',
  name: '',
  price: '',
  // The overwhelming majority of Sri Lankan shelf prices are VAT-inclusive, and a default that
  // is wrong for most shops is a default that gets left wrong on the products nobody re-checks.
  taxMode: 'INCLUSIVE',
  taxRate: '18',
  categoryId: null,
  barcodes: [''],
};

function toDraft(product: ProductRow): Draft {
  return {
    sku: product.sku,
    name: product.name,
    price: formatMinor(product.priceMinor),
    taxMode: product.taxMode,
    taxRate: formatMinor(product.taxRateBp),
    categoryId: product.categoryId,
    barcodes: product.barcodes.length > 0 ? product.barcodes : [''],
  };
}

export function ProductsScreen({ office }: { office: BackOffice }) {
  const [products, setProducts] = useState<ProductRow[] | null>(null);
  const [categories, setCategories] = useState<CategoryRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [showRetired, setShowRetired] = useState(false);
  const [editing, setEditing] = useState<ProductRow | null>(null);
  const [adding, setAdding] = useState(false);
  const [managingCategories, setManagingCategories] = useState(false);
  const [importing, setImporting] = useState(false);
  const { allows } = useEntitlement();

  const mayManage = office.can('MANAGE_PRODUCTS');

  const load = useCallback(async () => {
    try {
      const [loadedProducts, loadedCategories] = await Promise.all([
        office.request<ProductRow[]>('/api/back-office/products'),
        office.request<CategoryRow[]>('/api/back-office/categories'),
      ]);
      setProducts(loadedProducts);
      setCategories(loadedCategories);
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

  /**
   * Matched against name, code and every barcode.
   *
   * The barcode is in there for the case this screen exists to serve: an owner holding a packet
   * that will not scan, who has the code in front of them and nothing else to search by.
   */
  const visible = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    return (products ?? [])
      .filter((product) => showRetired || product.active)
      .filter(
        (product) =>
          needle === '' ||
          product.name.toLowerCase().includes(needle) ||
          product.sku.toLowerCase().includes(needle) ||
          product.barcodes.some((barcode) => barcode.includes(needle)),
      );
  }, [products, filter, showRetired]);

  const submit = useCallback(
    async (draft: Draft, product: ProductRow | null) => {
      const priceMinor = parseAmountToMinor(draft.price);
      if (priceMinor === null || priceMinor < 0) {
        setError('That price could not be read. Type it in rupees, as 285.00 or 285.');
        return;
      }
      const taxRateBp = parseAmountToMinor(draft.taxRate);
      if (taxRateBp === null || taxRateBp < 0) {
        setError('That tax rate could not be read. Type it as a percentage, as 18 or 2.5.');
        return;
      }

      const body = JSON.stringify({
        clientUuid: product ? product.clientUuid : crypto.randomUUID(),
        sku: draft.sku,
        name: draft.name,
        priceMinor,
        taxMode: draft.taxMode,
        taxRateBp,
        categoryId: draft.categoryId,
        barcodes: draft.barcodes.map((barcode) => barcode.trim()).filter(Boolean),
      });

      const ok = await act(
        product ? `${draft.name} saved.` : `${draft.name} is now in the catalogue.`,
        () =>
          office.request(
            product ? `/api/back-office/products/${product.id}` : '/api/back-office/products',
            { method: product ? 'PUT' : 'POST', body },
          ),
      );
      if (ok) {
        setAdding(false);
        setEditing(null);
      }
    },
    [act, office],
  );

  return (
    <div className="flex flex-col gap-4">
      <header className="flex flex-wrap items-baseline justify-between gap-4">
        <div>
          <h2 className="text-ink text-lg font-semibold">Products</h2>
          <p className="text-ink-3 text-sm">
            What this shop sells, what it costs, and which codes are on it.
          </p>
        </div>
        {mayManage && (
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setManagingCategories((open) => !open)}
              className="border-hair text-ink-2 min-h-touch rounded border px-4"
            >
              Categories
            </button>
            {/* Bulk import is a plan capability (M4-09); adding products one at a time is not. */}
            {allows('csv_import') && (
              <button
                type="button"
                onClick={() => {
                  setImporting(true);
                  setAdding(false);
                  setEditing(null);
                }}
                className="border-hair text-ink-2 min-h-touch rounded border px-4"
              >
                Import
              </button>
            )}
            <button
              type="button"
              onClick={() => {
                setAdding(true);
                setEditing(null);
              }}
              className="border-accent text-accent min-h-touch rounded border px-4"
            >
              Add a product
            </button>
          </div>
        )}
      </header>

      {/*
        No role reaches this today — MANAGER and OWNER are the only two holding BACK_OFFICE and
        both also hold MANAGE_PRODUCTS. It is here because the endpoints genuinely split the two
        permissions, and the first role that reads without writing (a stock clerk, most likely)
        should find a screen that already tells them so rather than one full of buttons that 422.
      */}
      {!mayManage && (
        <p role="status" className="border-pending text-pending border-l-2 px-3 py-2 text-sm">
          You can see the catalogue but not change it — that needs a manager or the owner.
        </p>
      )}
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

      {managingCategories && mayManage && (
        <CategoriesPanel
          categories={categories}
          onCreate={(name) =>
            act(`${name} added.`, () =>
              office.request('/api/back-office/categories', {
                method: 'POST',
                body: JSON.stringify({ clientUuid: crypto.randomUUID(), name }),
              }),
            )
          }
          onUpdate={(category, name, active) =>
            act(`${name} updated.`, () =>
              office.request(`/api/back-office/categories/${category.id}`, {
                method: 'PUT',
                body: JSON.stringify({ name, active }),
              }),
            )
          }
          onClose={() => setManagingCategories(false)}
        />
      )}

      {importing && mayManage && allows('csv_import') && (
        <ProductImportPanel
          office={office}
          onClose={() => setImporting(false)}
          onImported={(summary) => {
            setNotice(summary);
            setError(null);
            void load();
          }}
        />
      )}

      {adding && mayManage && (
        <ProductForm
          title="Add a product"
          initial={BLANK}
          categories={categories}
          submitLabel="Create"
          onCancel={() => setAdding(false)}
          onSubmit={(draft) => submit(draft, null)}
        />
      )}

      {editing && mayManage && (
        <ProductForm
          // Keyed by the product, so opening a second one mounts a fresh form rather than
          // leaving the first one's half-typed price sitting in the box. An effect that copied
          // `initial` into state on every change would do it too — and would also wipe whatever
          // was being typed each time the parent re-rendered.
          key={editing.id}
          title={`${editing.sku} — ${editing.name}`}
          initial={toDraft(editing)}
          categories={categories}
          submitLabel="Save"
          onCancel={() => setEditing(null)}
          onSubmit={(draft) => submit(draft, editing)}
        />
      )}

      <div className="flex flex-wrap items-end gap-4">
        <Labelled label="Find" hint="name, code, or a barcode">
          <input
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            className={`${FIELD_CLASS} w-72`}
          />
        </Labelled>
        <label className="min-h-touch text-ink-2 flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={showRetired}
            onChange={(event) => setShowRetired(event.target.checked)}
          />
          Show discontinued
        </label>
        <p className="text-ink-3 min-h-touch flex items-center text-sm">
          {products === null ? 'Loading…' : `${visible.length} shown`}
        </p>
      </div>

      {products !== null && visible.length === 0 && (
        <p className="text-ink-3 text-sm">Nothing matches that.</p>
      )}

      <ul className="flex flex-col gap-2">
        {visible.map((product) => (
          <li
            key={product.id}
            className={`border-hair flex flex-wrap items-center gap-x-4 gap-y-1 rounded border px-4 py-3 ${
              product.active ? '' : 'opacity-60'
            }`}
          >
            <span className="lum-money text-ink-2 w-28 text-sm">{product.sku}</span>
            <span className="text-ink min-w-48 flex-1 font-semibold">{product.name}</span>
            <span className="text-ink-3 w-32 text-sm">{product.categoryName ?? '—'}</span>
            <span className="lum-money text-ink w-28 text-right">
              {formatMinor(product.priceMinor)}
            </span>
            <span className="text-ink-3 w-20 text-right text-sm">
              {product.taxRateBp === 0 ? 'no VAT' : `${formatMinor(product.taxRateBp)}%`}
            </span>
            <span className="lum-money text-ink-3 w-40 text-sm">
              {/* The primary code, and how many others there are. The full list is in the form. */}
              {product.barcodes[0] ?? 'no barcode'}
              {product.barcodes.length > 1 ? ` +${product.barcodes.length - 1}` : ''}
            </span>
            {/* Icon plus text, never colour alone (ROADMAP §A). */}
            <span className={`w-32 text-sm ${product.active ? 'text-ok' : 'text-ink-3'}`}>
              {product.active ? '● selling' : '○ discontinued'}
            </span>
            {mayManage && (
              <span className="flex gap-2">
                <button
                  type="button"
                  onClick={() => {
                    setEditing(product);
                    setAdding(false);
                  }}
                  className="border-hair text-ink-2 min-h-touch rounded border px-3"
                >
                  Edit
                </button>
                <button
                  type="button"
                  onClick={() =>
                    void act(
                      product.active
                        ? `${product.name} will no longer come up at the till.`
                        : `${product.name} is on sale again.`,
                      () =>
                        office.request(`/api/back-office/products/${product.id}/active`, {
                          method: 'PUT',
                          body: JSON.stringify({ active: !product.active }),
                        }),
                    )
                  }
                  className="border-hair text-ink-2 min-h-touch rounded border px-3"
                >
                  {product.active ? 'Discontinue' : 'Bring back'}
                </button>
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

// ---------------------------------------------------------------------------- the form

function ProductForm({
  title,
  initial,
  categories,
  submitLabel,
  onCancel,
  onSubmit,
}: {
  title: string;
  initial: Draft;
  categories: CategoryRow[];
  submitLabel: string;
  onCancel: () => void;
  onSubmit: (draft: Draft) => Promise<void>;
}) {
  const [draft, setDraft] = useState<Draft>(initial);

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) =>
    setDraft((current) => ({ ...current, [key]: value }));

  const setBarcode = (index: number, value: string) =>
    setDraft((current) => ({
      ...current,
      barcodes: current.barcodes.map((barcode, i) => (i === index ? value : barcode)),
    }));

  return (
    <form
      className="border-accent flex flex-col gap-4 rounded border p-4"
      onSubmit={(event) => {
        event.preventDefault();
        void onSubmit(draft);
      }}
    >
      <h3 className="text-ink font-semibold">{title}</h3>

      <div className="flex flex-wrap gap-3">
        <Labelled label="Product code" hint="the shop's own code — a SKU">
          <input
            value={draft.sku}
            onChange={(event) => set('sku', event.target.value)}
            maxLength={64}
            required
            className={NUMERIC_FIELD_CLASS}
          />
        </Labelled>
        <Labelled label="Name" hint="what prints on the receipt">
          <input
            value={draft.name}
            onChange={(event) => set('name', event.target.value)}
            required
            className={`${FIELD_CLASS} w-64`}
          />
        </Labelled>
        <Labelled label="Price" hint="rupees, as 285.00">
          <input
            value={draft.price}
            onChange={(event) => set('price', event.target.value)}
            inputMode="decimal"
            required
            className={`${NUMERIC_FIELD_CLASS} w-32 text-right`}
          />
        </Labelled>
        <Labelled label="VAT %" hint="0 for zero-rated">
          <input
            value={draft.taxRate}
            onChange={(event) => set('taxRate', event.target.value)}
            inputMode="decimal"
            required
            className={`${NUMERIC_FIELD_CLASS} w-24 text-right`}
          />
        </Labelled>
        <Labelled label="Category" hint="optional">
          <select
            value={draft.categoryId ?? ''}
            onChange={(event) =>
              set('categoryId', event.target.value === '' ? null : Number(event.target.value))
            }
            className={FIELD_CLASS}
          >
            <option value="">— none —</option>
            {categories
              // A retired category stays selected on the products already in it, but is not
              // offered to anything new.
              .filter((category) => category.active || category.id === draft.categoryId)
              .map((category) => (
                <option key={category.id} value={category.id}>
                  {category.name}
                  {category.active ? '' : ' (retired)'}
                </option>
              ))}
          </select>
        </Labelled>
      </div>

      <fieldset className="flex flex-col gap-2">
        <legend className="text-ink-3 text-xs uppercase tracking-wider">Price includes VAT</legend>
        <div className="flex gap-2">
          {(['INCLUSIVE', 'EXCLUSIVE'] as const).map((mode) => (
            <label
              key={mode}
              className={`min-h-touch flex cursor-pointer items-center gap-2 rounded border px-3 ${
                draft.taxMode === mode ? 'border-accent text-accent' : 'border-hair text-ink-2'
              }`}
            >
              <input
                type="radio"
                name="taxMode"
                checked={draft.taxMode === mode}
                onChange={() => set('taxMode', mode)}
                className="sr-only"
              />
              {mode === 'INCLUSIVE' ? 'yes — shelf price' : 'no — VAT added at the till'}
            </label>
          ))}
        </div>
        <p className="text-ink-3 text-xs">
          {draft.taxMode === 'INCLUSIVE'
            ? 'VAT is extracted out of the price above. What the customer pays is what is on the shelf.'
            : 'VAT is added on top of the price above. Rare in retail — check the shelf label.'}
        </p>
      </fieldset>

      <fieldset className="flex flex-col gap-2">
        <legend className="text-ink-3 text-xs uppercase tracking-wider">Barcodes</legend>
        <p className="text-ink-3 text-xs">
          The first one is the primary. A product may carry several — the manufacturer&apos;s code
          and a supplier&apos;s own are the same goods, and both have to scan.
        </p>
        {draft.barcodes.map((barcode, index) => (
          // Keyed by position, which is right here: the row's identity *is* its position in the
          // list, the value in it is the thing being edited, and a new blank row is always
          // appended at the end. Keying by the barcode text would remount the input on every
          // keystroke and lose the caret.
          <div key={index} className="flex items-center gap-2">
            <input
              value={barcode}
              onChange={(event) => setBarcode(index, event.target.value)}
              aria-label={index === 0 ? 'Primary barcode' : `Barcode ${index + 1}`}
              className={`${NUMERIC_FIELD_CLASS} w-64`}
            />
            <button
              type="button"
              onClick={() =>
                setDraft((current) => ({
                  ...current,
                  barcodes: current.barcodes.filter((_, i) => i !== index),
                }))
              }
              className="border-hair text-ink-2 min-h-touch rounded border px-3"
            >
              Remove
            </button>
          </div>
        ))}
        <button
          type="button"
          onClick={() =>
            setDraft((current) => ({ ...current, barcodes: [...current.barcodes, ''] }))
          }
          className="border-hair text-ink-2 min-h-touch self-start rounded border px-3"
        >
          Add another barcode
        </button>
      </fieldset>

      <div className="flex gap-2">
        <button
          type="submit"
          className="border-accent text-accent min-h-touch rounded border px-4 font-semibold"
        >
          {submitLabel}
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

// ----------------------------------------------------------------------- categories

/**
 * Categories, managed in place rather than on their own screen.
 *
 * <p>They are only ever touched while editing a product — that is the moment somebody notices the
 * aisle they want is missing — and a separate screen would mean abandoning a half-typed product to
 * go and create one.
 */
function CategoriesPanel({
  categories,
  onCreate,
  onUpdate,
  onClose,
}: {
  categories: CategoryRow[];
  onCreate: (name: string) => Promise<boolean>;
  onUpdate: (category: CategoryRow, name: string, active: boolean) => Promise<boolean>;
  onClose: () => void;
}) {
  const [name, setName] = useState('');

  return (
    <section className="border-hair flex flex-col gap-3 rounded border p-4">
      <h3 className="text-ink font-semibold">Categories</h3>
      <p className="text-ink-3 text-sm">
        Renaming one moves every product in it at once. Retiring one takes it off the picker and
        leaves the products where they are, so old reports still read correctly.
      </p>

      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={async (event) => {
          event.preventDefault();
          if (await onCreate(name.trim())) setName('');
        }}
      >
        <Labelled label="New category">
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={64}
            required
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
        {categories.map((category) => (
          <CategoryLine key={category.id} category={category} onUpdate={onUpdate} />
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

function CategoryLine({
  category,
  onUpdate,
}: {
  category: CategoryRow;
  onUpdate: (category: CategoryRow, name: string, active: boolean) => Promise<boolean>;
}) {
  const [name, setName] = useState(category.name);

  return (
    <li className="border-hair flex flex-wrap items-center gap-3 rounded border px-3 py-2">
      <input
        value={name}
        onChange={(event) => setName(event.target.value)}
        aria-label={`Rename ${category.name}`}
        className={`${FIELD_CLASS} w-56`}
      />
      <span className="text-ink-3 w-32 text-sm">
        {category.productCount} product{category.productCount === 1 ? '' : 's'}
      </span>
      <span className={`w-24 text-sm ${category.active ? 'text-ok' : 'text-ink-3'}`}>
        {category.active ? '● in use' : '○ retired'}
      </span>
      <button
        type="button"
        disabled={name.trim() === category.name}
        onClick={() => void onUpdate(category, name.trim(), category.active)}
        className="border-hair text-ink-2 min-h-touch rounded border px-3 disabled:opacity-40"
      >
        Rename
      </button>
      <button
        type="button"
        onClick={() => void onUpdate(category, category.name, !category.active)}
        className="border-hair text-ink-2 min-h-touch rounded border px-3"
      >
        {category.active ? 'Retire' : 'Bring back'}
      </button>
    </li>
  );
}
