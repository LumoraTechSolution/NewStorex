'use client';

import { formatMinor } from '@lumora/domain';
import { useCallback, useEffect, useState } from 'react';

import { FIELD_CLASS, Labelled } from '@/components/Labelled';
import type { BackOffice } from '@/lib/useBackOffice';

/**
 * Customers, as the shop keeps them (M3-11).
 *
 * <h2>What this screen is for that the till's F6 is not</h2>
 *
 * The till adds people mid-sale: a number, a name, done. This is where somebody sits down and
 * corrects a misheard number, adds the note that says "office account, invoices monthly", or looks
 * at what a regular has actually bought. The purchase history is the reason it needs a back-office
 * session at all — it is the one genuinely private thing here, and the one thing a till never needs.
 *
 * <h2>Deactivate, never delete</h2>
 *
 * Sales point at these rows. There is no delete button because a delete would either fail against
 * the foreign key or be made to succeed by loosening it — and an invoice that used to name somebody
 * and now names nobody is a worse record than one naming a customer who has left. Erasure under
 * PDPA (M5-10) is a deliberate act that has to decide what happens to the invoices; a delete button
 * would make that decision by accident.
 */
interface CustomerRow {
  id: number;
  clientUuid: string;
  name: string;
  phone: string | null;
  email: string | null;
  note: string | null;
  active: boolean;
  saleCount: number;
  spentMinor: number;
  lastSeenAt: string | null;
}

interface CustomerSale {
  saleId: number;
  invoiceNumber: string;
  soldAt: string;
  totalMinor: number;
  itemCount: number;
}

interface Draft {
  /** The row being edited, or null when this is a new customer. */
  id: number | null;
  name: string;
  phone: string;
  email: string;
  note: string;
}

const BLANK: Draft = { id: null, name: '', phone: '', email: '', note: '' };

export function CustomersScreen({ office }: { office: BackOffice }) {
  const [rows, setRows] = useState<CustomerRow[] | null>(null);
  const [query, setQuery] = useState('');
  const [includeInactive, setIncludeInactive] = useState(false);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [history, setHistory] = useState<{ id: number; sales: CustomerSale[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const found = await office.request<CustomerRow[]>(
        `/api/back-office/customers?q=${encodeURIComponent(query)}&includeInactive=${includeInactive}`,
      );
      setRows(found);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [includeInactive, office, query]);

  useEffect(() => {
    // Debounced, because this fires on every keystroke in the search box and the list is the
    // whole screen — a request per character makes the shop's own machine feel slower than a
    // remote one would.
    const timer = setTimeout(() => void load(), 200);
    return () => clearTimeout(timer);
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

  const save = useCallback(
    async (values: Draft) => {
      const body = {
        name: values.name,
        phone: values.phone,
        email: values.email,
        note: values.note,
      };
      const saved = await act(`${values.name.trim()} saved.`, () =>
        values.id === null
          ? office.request(`/api/back-office/customers`, {
              method: 'POST',
              // Generated here so a double-pressed Save is one customer, not two.
              body: JSON.stringify({ clientUuid: crypto.randomUUID(), ...body }),
            })
          : office.request(`/api/back-office/customers/${values.id}`, {
              method: 'PUT',
              body: JSON.stringify(body),
            }),
      );
      if (saved) setDraft(null);
    },
    [act, office],
  );

  const openHistory = useCallback(
    async (customer: CustomerRow) => {
      if (history?.id === customer.id) {
        setHistory(null);
        return;
      }
      try {
        const sales = await office.request<CustomerSale[]>(
          `/api/back-office/customers/${customer.id}/history`,
        );
        setHistory({ id: customer.id, sales });
        setError(null);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [history, office],
  );

  return (
    <div className="flex flex-col gap-4">
      <header className="flex flex-wrap items-baseline justify-between gap-4">
        <div>
          <h2 className="text-ink text-lg font-semibold">Customers</h2>
          <p className="text-ink-3 text-sm">
            Looked up at the till by phone number. Nothing here changes what anybody is charged.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setDraft(BLANK)}
          className="border-accent text-accent min-h-touch rounded border px-4 font-semibold"
        >
          Add a customer
        </button>
      </header>

      <div className="flex flex-wrap items-end gap-3">
        <Labelled label="Find" hint="a number, or part of a name">
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            className={`${FIELD_CLASS} w-72`}
          />
        </Labelled>
        <label className="text-ink-2 flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={includeInactive}
            onChange={(event) => setIncludeInactive(event.target.checked)}
          />
          Show deactivated
        </label>
      </div>

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

      {draft && (
        <CustomerForm
          draft={draft}
          onChange={setDraft}
          onCancel={() => setDraft(null)}
          onSave={() => void save(draft)}
        />
      )}

      {rows && rows.length === 0 && (
        <p className="text-ink-3 text-sm">
          {query.trim() === ''
            ? 'No customers on file yet. The till adds them as they come in — F6 during a sale.'
            : `Nobody matches “${query.trim()}”.`}
        </p>
      )}

      {rows && rows.length > 0 && (
        <ul className="flex flex-col gap-2" aria-label="Customers">
          {rows.map((customer) => (
            <li
              key={customer.clientUuid}
              className="border-hair flex flex-col gap-2 rounded border p-3"
            >
              <div className="flex flex-wrap items-baseline justify-between gap-3">
                <div className="flex flex-col">
                  <span className="text-ink font-semibold">
                    {customer.name}
                    {!customer.active && (
                      <span className="text-ink-3 ml-2 text-xs uppercase tracking-wider">
                        deactivated
                      </span>
                    )}
                  </span>
                  <span className="text-ink-3 text-sm">
                    <span className="lum-money">{customer.phone ?? 'no number'}</span>
                    {customer.email && <span className="ml-3">{customer.email}</span>}
                  </span>
                  {customer.note && <span className="text-ink-3 text-sm">{customer.note}</span>}
                </div>

                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-ink-3 text-sm">
                    {customer.saleCount === 0
                      ? 'no purchases yet'
                      : `${customer.saleCount} sales · ${formatMinor(customer.spentMinor)}`}
                  </span>
                  <button
                    type="button"
                    onClick={() => void openHistory(customer)}
                    disabled={customer.saleCount === 0}
                    className="border-hair text-ink-2 min-h-touch rounded border px-3 disabled:opacity-40"
                  >
                    History
                  </button>
                  <button
                    type="button"
                    onClick={() =>
                      setDraft({
                        id: customer.id,
                        name: customer.name,
                        phone: customer.phone ?? '',
                        email: customer.email ?? '',
                        note: customer.note ?? '',
                      })
                    }
                    className="border-hair text-ink-2 min-h-touch rounded border px-3"
                  >
                    Edit
                  </button>
                  <button
                    type="button"
                    onClick={() =>
                      void act(
                        customer.active
                          ? `${customer.name} deactivated.`
                          : `${customer.name} reinstated.`,
                        () =>
                          office.request(`/api/back-office/customers/${customer.id}/active`, {
                            method: 'PUT',
                            body: JSON.stringify({ active: !customer.active }),
                          }),
                      )
                    }
                    className="border-hair text-ink-2 min-h-touch rounded border px-3"
                  >
                    {customer.active ? 'Deactivate' : 'Reinstate'}
                  </button>
                </div>
              </div>

              {history?.id === customer.id && (
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-ink-3 text-left text-xs uppercase tracking-wider">
                      <th scope="col" className="py-1">
                        Invoice
                      </th>
                      <th scope="col" className="py-1">
                        When
                      </th>
                      <th scope="col" className="py-1 text-right">
                        Items
                      </th>
                      <th scope="col" className="py-1 text-right">
                        Total
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {history.sales.map((sale) => (
                      <tr key={sale.saleId} className="border-hair border-t">
                        <td className="lum-money text-ink-2 py-1">{sale.invoiceNumber}</td>
                        <td className="text-ink-3 py-1">{when(sale.soldAt)}</td>
                        <td className="lum-money text-ink-2 py-1 text-right">{sale.itemCount}</td>
                        <td className="lum-money text-ink py-1 text-right">
                          {formatMinor(sale.totalMinor)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function CustomerForm({
  draft,
  onChange,
  onCancel,
  onSave,
}: {
  draft: Draft;
  onChange: (draft: Draft) => void;
  onCancel: () => void;
  onSave: () => void;
}) {
  return (
    <section className="border-hair flex flex-col gap-3 rounded border p-4">
      <h3 className="text-ink font-semibold">
        {draft.id === null ? 'New customer' : 'Edit customer'}
      </h3>

      <div className="grid gap-3 md:grid-cols-2">
        <Labelled label="Name">
          <input
            value={draft.name}
            onChange={(event) => onChange({ ...draft, name: event.target.value })}
            className={FIELD_CLASS}
          />
        </Labelled>
        <Labelled label="Phone" hint="how the till finds them — digits, however you type them">
          <input
            value={draft.phone}
            onChange={(event) => onChange({ ...draft, phone: event.target.value })}
            className={FIELD_CLASS}
          />
        </Labelled>
        <Labelled label="Email" hint="optional">
          <input
            value={draft.email}
            onChange={(event) => onChange({ ...draft, email: event.target.value })}
            className={FIELD_CLASS}
          />
        </Labelled>
        <Labelled label="Note" hint="anything worth remembering about them">
          <input
            value={draft.note}
            onChange={(event) => onChange({ ...draft, note: event.target.value })}
            className={FIELD_CLASS}
          />
        </Labelled>
      </div>

      <div className="flex gap-2">
        <button
          type="button"
          onClick={onSave}
          className="border-accent text-accent min-h-touch rounded border px-4 font-semibold"
        >
          Save
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="border-hair text-ink-2 min-h-touch rounded border px-4"
        >
          Cancel
        </button>
      </div>
    </section>
  );
}

/** The shop PC's own clock, to the minute. Nobody reads seconds off a purchase history. */
function when(iso: string): string {
  const at = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())} ${pad(
    at.getHours(),
  )}:${pad(at.getMinutes())}`;
}
