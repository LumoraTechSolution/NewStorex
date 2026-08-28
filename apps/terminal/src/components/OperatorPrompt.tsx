'use client';

import type { Operator } from '@/lib/useOperator';

/**
 * The user code and PIN, as the till draws them (M3-08).
 *
 * <p>There is no `<input>` here and no focus ring. The active field is shown by an underline and
 * a caret block, because the till's keyboard handling is document-level: a real input would let a
 * barcode gun type into it, and would put a browser's own focus model in charge of something the
 * appliance needs to be certain about.
 *
 * <p>The PIN is masked and the code is not. The code is somebody's employee number and it is
 * printed on their name badge; masking it would only make it harder to spot a typo in the one
 * field where a typo is indistinguishable from a wrong PIN.
 */
export function OperatorPrompt({ operator, label }: { operator: Operator; label: string }) {
  return (
    <div className="flex flex-col gap-3">
      <p className="text-ink-3 text-sm">{label}</p>

      <div className="flex gap-3">
        <Field
          name="user"
          active={operator.field === 'CODE'}
          value={operator.code || ''}
          placeholder="––––"
        />
        <Field
          name="PIN"
          active={operator.field === 'PIN'}
          value={'•'.repeat(operator.pin.length)}
          placeholder="––––"
        />
      </div>
    </div>
  );
}

function Field({
  name,
  active,
  value,
  placeholder,
}: {
  name: string;
  active: boolean;
  value: string;
  placeholder: string;
}) {
  return (
    <div
      className={`min-h-touch flex-1 rounded border px-4 py-2 ${
        active ? 'border-accent' : 'border-hair'
      }`}
    >
      <div className="text-ink-3 text-xs uppercase tracking-wider">{name}</div>
      <div className="lum-money text-ink text-2xl">
        {value || <span className="text-ink-3">{placeholder}</span>}
        {/*
          A caret only on the active field. `aria-hidden` because it is punctuation for the eye:
          a screen reader announcing a bar character on every keystroke would bury the value.
        */}
        {active && (
          <span aria-hidden className="text-accent lum-caret">
            ▍
          </span>
        )}
      </div>
    </div>
  );
}
