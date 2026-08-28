'use client';

import { parseProductCsv, type CsvProblem, type ImportRow } from '@lumora/domain';
import { useCallback, useState } from 'react';

import { FIELD_CLASS, Labelled } from '@/components/Labelled';
import type { BackOffice } from '@/lib/useBackOffice';

/**
 * Loading a product list from a spreadsheet (M3-03).
 *
 * <h2>Three steps, and the middle one is the point</h2>
 *
 * Choose a file, **see what it would do**, then confirm. The preview is not a courtesy screen: a
 * shopkeeper importing a supplier's list is making four hundred decisions at once, and the ones
 * that hurt are invisible — a price column read as VAT, an update where they expected a create, a
 * category spelled two ways. Nothing is written until the third step, and the server will only
 * apply the plan it already showed (see `planHash`).
 *
 * <h2>Reading the file happens here; deciding what it means happens on the server</h2>
 *
 * `parseProductCsv` is in `@lumora/domain` because a CSV holds prices as text and turning text into
 * minor units is money math, which §A puts in exactly one package. Everything that needs the
 * catalogue — does this code exist, who holds that barcode, is this category new — is the server's,
 * because only it knows. A row that reads cleanly here can still be refused there, and that is the
 * whole reason the preview round-trips instead of being computed in the browser.
 */
type Action = 'CREATE' | 'UPDATE' | 'UNCHANGED' | 'ERROR';

interface FieldChange {
  field: string;
  before: string;
  after: string;
}

interface PlannedRow {
  line: number;
  sku: string;
  name: string;
  action: Action;
  changes: FieldChange[];
  problem: string | null;
}

interface ImportPlan {
  rows: PlannedRow[];
  creates: number;
  updates: number;
  unchanged: number;
  errors: number;
  newCategories: string[];
  planHash: string;
}

const TEMPLATE = [
  'sku,name,price,vat,category,barcodes',
  'TEA-400,Ceylon Tea 400g,450.00,18,Beverages,4791234567890',
  'BREAD-450,Bread 450g,250.00,0,Bakery,4791234567951',
  'MILK-400G,Milk Powder 400g,1390.00,18,Beverages,4791234567937|8901234567895',
].join('\n');

export function ProductImportPanel({
  office,
  onImported,
  onClose,
}: {
  office: BackOffice;
  onImported: (summary: string) => void;
  onClose: () => void;
}) {
  const [text, setText] = useState('');
  const [source, setSource] = useState<string | null>(null);
  const [rows, setRows] = useState<readonly ImportRow[]>([]);
  const [fileProblems, setFileProblems] = useState<readonly CsvProblem[]>([]);
  const [warnings, setWarnings] = useState<readonly string[]>([]);
  const [plan, setPlan] = useState<ImportPlan | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  /**
   * Reads the file and asks the server what it would do — in that order, and the second only if
   * the first found nothing wrong. A file with unreadable rows is not worth a round trip, and the
   * shopkeeper gets the line numbers instantly rather than after a request.
   */
  const examine = useCallback(
    async (csv: string, describedAs: string) => {
      setError(null);
      setPlan(null);
      setSource(describedAs);

      const parsed = parseProductCsv(csv);
      setRows(parsed.rows);
      setFileProblems(parsed.problems);
      setWarnings(parsed.warnings);
      if (parsed.problems.length > 0 || parsed.rows.length === 0) return;

      setBusy(true);
      try {
        setPlan(
          await office.request<ImportPlan>('/api/back-office/products/import/preview', {
            method: 'POST',
            body: JSON.stringify({ rows: parsed.rows }),
          }),
        );
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [office],
  );

  const confirm = useCallback(async () => {
    if (!plan) return;
    setBusy(true);
    setError(null);
    try {
      const applied = await office.request<ImportPlan>('/api/back-office/products/import', {
        method: 'POST',
        body: JSON.stringify({ rows, planHash: plan.planHash }),
      });
      onImported(
        `Imported ${applied.creates} new product${applied.creates === 1 ? '' : 's'} and ` +
          `updated ${applied.updates}.`,
      );
      onClose();
    } catch (e) {
      // The plan is cleared on failure, so the only way forward is to look again. The commonest
      // cause is that the catalogue moved underneath, and re-confirming the stale plan is exactly
      // what must not happen.
      setError(e instanceof Error ? e.message : String(e));
      setPlan(null);
    } finally {
      setBusy(false);
    }
  }, [office, onClose, onImported, plan, rows]);

  return (
    <section className="border-accent flex flex-col gap-4 rounded border p-4">
      <header>
        <h3 className="text-ink font-semibold">Import products from a spreadsheet</h3>
        <p className="text-ink-3 text-sm">
          Save the sheet as CSV. You will see exactly what it would change before anything is
          written, and an import never removes a product that is missing from the file.
        </p>
      </header>

      <div className="flex flex-wrap items-end gap-4">
        <Labelled label="CSV file">
          <input
            type="file"
            accept=".csv,text/csv"
            onChange={async (event) => {
              const file = event.target.files?.[0];
              if (file) {
                const content = await file.text();
                setText(content);
                await examine(content, file.name);
              }
            }}
            className={`${FIELD_CLASS} py-3`}
          />
        </Labelled>
        <button
          type="button"
          onClick={() => {
            setText(TEMPLATE);
            setSource(null);
            setPlan(null);
            setFileProblems([]);
            setWarnings([]);
          }}
          className="border-hair text-ink-2 min-h-touch rounded border px-4"
        >
          Show me the format
        </button>
      </div>

      {/*
        A paste box as well as a file picker, because both are real. A supplier's list arrives as a
        file; ten new lines a shopkeeper typed into a sheet get pasted. Neither is a fallback for
        the other and each is four lines of code.
      */}
      <Labelled label="Or paste the rows" hint="the first line is the column headings">
        <textarea
          value={text}
          onChange={(event) => {
            setText(event.target.value);
            setPlan(null);
          }}
          rows={6}
          spellCheck={false}
          className={`${FIELD_CLASS} lum-money w-full py-2`}
        />
      </Labelled>

      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={busy || text.trim() === ''}
          onClick={() => void examine(text, 'the pasted rows')}
          className="border-accent text-accent min-h-touch rounded border px-4 font-semibold disabled:opacity-40"
        >
          {busy ? 'Checking…' : 'Check what this would do'}
        </button>
        <button
          type="button"
          onClick={onClose}
          className="border-hair text-ink-2 min-h-touch rounded border px-4"
        >
          Cancel
        </button>
      </div>

      {error && (
        <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
          {error}
        </p>
      )}

      {fileProblems.length > 0 && (
        <div role="alert" className="border-danger flex flex-col gap-1 border-l-2 px-3 py-2">
          <p className="text-danger text-sm font-semibold">
            {source ? `${source} cannot be read yet.` : 'This cannot be read yet.'}
          </p>
          <ul className="text-danger flex flex-col gap-1 text-sm">
            {fileProblems.map((problem, index) => (
              // Keyed by position: one line can carry two faults, so the line number is not
              // unique and the list is rendered once and never reordered.
              <li key={index}>
                <span className="lum-money">line {problem.line}</span> — {problem.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      {warnings.map((warning) => (
        <p
          key={warning}
          role="status"
          className="border-pending text-pending border-l-2 px-3 py-2 text-sm"
        >
          {warning}
        </p>
      ))}

      {plan && <PlanReview plan={plan} busy={busy} onConfirm={confirm} />}
    </section>
  );
}

// ---------------------------------------------------------------------------- the plan

function PlanReview({
  plan,
  busy,
  onConfirm,
}: {
  plan: ImportPlan;
  busy: boolean;
  onConfirm: () => void;
}) {
  // Errors first, then the rows that actually change something. An unchanged row is the majority
  // of a re-import and the least interesting thing on the screen.
  const shown = [...plan.rows]
    .filter((row) => row.action !== 'UNCHANGED')
    .sort((a, b) => (a.action === 'ERROR' ? -1 : b.action === 'ERROR' ? 1 : a.line - b.line));

  return (
    <div className="border-hair flex flex-col gap-3 rounded border p-4">
      <h4 className="text-ink font-semibold">What this would do</h4>

      <ul className="flex flex-wrap gap-x-6 gap-y-1 text-sm" aria-label="Import summary">
        <li className="text-ok">● {plan.creates} new</li>
        <li className="text-ink-2">◐ {plan.updates} changed</li>
        <li className="text-ink-3">○ {plan.unchanged} already correct</li>
        <li className={plan.errors > 0 ? 'text-danger font-semibold' : 'text-ink-3'}>
          {plan.errors > 0 ? '▲' : '○'} {plan.errors} cannot be imported
        </li>
      </ul>

      {plan.newCategories.length > 0 && (
        <div role="status" className="border-pending text-pending border-l-2 px-3 py-2 text-sm">
          {/*
            Listed rather than counted, and this is the safeguard against the exact problem V110's
            category table exists to prevent: "Bevarages" sitting beside "Beverages" is visible
            here and nowhere else, and once imported it splits a month's takings across two lines.
          */}
          <p className="font-semibold">
            {plan.newCategories.length} new categor{plan.newCategories.length === 1 ? 'y' : 'ies'}{' '}
            will be created — check the spelling:
          </p>
          <p>{plan.newCategories.join(' · ')}</p>
        </div>
      )}

      {plan.errors > 0 && (
        <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
          Nothing will be imported while any row is wrong. Fix these {plan.errors} and try again —
          importing only the good rows would leave the catalogue half-updated with no record of
          which half.
        </p>
      )}

      {shown.length > 0 && (
        <ul className="flex flex-col gap-1">
          {shown.map((row) => (
            <li
              key={row.line}
              className="border-hair flex flex-wrap items-baseline gap-x-3 gap-y-1 border-b pb-1 text-sm last:border-b-0"
            >
              <span className="lum-money text-ink-3 w-16">line {row.line}</span>
              <span className={`w-20 ${row.action === 'ERROR' ? 'text-danger' : 'text-ink-3'}`}>
                {row.action === 'CREATE'
                  ? '● new'
                  : row.action === 'UPDATE'
                    ? '◐ change'
                    : '▲ error'}
              </span>
              <span className="lum-money text-ink-2 w-28">{row.sku}</span>
              <span className="text-ink min-w-40 flex-1">{row.name}</span>
              {row.problem ? (
                <span className="text-danger flex-1 basis-full sm:basis-auto">{row.problem}</span>
              ) : (
                <span className="text-ink-3 flex-1 basis-full sm:basis-auto">
                  {row.changes
                    .map((change) => `${change.field} ${change.before} → ${change.after}`)
                    .join(' · ')}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}

      {plan.errors === 0 && plan.creates + plan.updates === 0 && (
        <p className="text-ink-3 text-sm">
          Everything in this file already matches the catalogue. There is nothing to import.
        </p>
      )}

      <button
        type="button"
        disabled={busy || plan.errors > 0 || plan.creates + plan.updates === 0}
        onClick={onConfirm}
        className="border-accent text-accent min-h-touch self-start rounded border px-4 font-semibold disabled:opacity-40"
      >
        {busy
          ? 'Importing…'
          : `Import ${plan.creates + plan.updates} product${
              plan.creates + plan.updates === 1 ? '' : 's'
            }`}
      </button>
    </div>
  );
}
