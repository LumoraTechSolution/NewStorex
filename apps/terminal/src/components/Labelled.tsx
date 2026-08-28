'use client';

/**
 * A labelled form field, as the back office draws one (M3-01).
 *
 * <h2>The hint is outside the label, deliberately</h2>
 *
 * A `<label>` wrapping both the caption and the hint makes the whole lot the field's accessible
 * name: the Products screen's filter box announced itself as "Find name, code, or a barcode", which
 * then collided with the product form's "Name" and made `getByLabel('Name')` ambiguous. A screen
 * reader reads the same run-on sentence before every keystroke. So the label carries the caption
 * and nothing else, and the hint sits beside it as ordinary visible text.
 *
 * <p>Shared rather than copied because the label is what Playwright reaches a field by, and two
 * implementations means one of them eventually drifts — at which point the specs using it match
 * nothing, and so does a screen reader.
 */
export function Labelled({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1">
      <label className="flex flex-col gap-1">
        <span className="text-ink-3 text-xs uppercase tracking-wider">{label}</span>
        {children}
      </label>
      {hint && <span className="text-ink-3 text-xs">{hint}</span>}
    </div>
  );
}

/** The input styling every back-office field shares. Touch-sized, because §A says 56px. */
export const FIELD_CLASS = 'border-hair bg-page text-ink min-h-touch rounded border px-3';

/** The same, in the tabular monospace face — for anything that is digits in a column. */
export const NUMERIC_FIELD_CLASS = `${FIELD_CLASS} lum-money`;
