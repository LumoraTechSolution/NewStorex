'use client';

export type FunctionKey = {
  key: string;
  label: string;
  run?: () => void;
  disabled?: boolean;
};

/**
 * The F-key bar (M1-07).
 *
 * Pinned to the bottom, and **every slot is always rendered, in the same order, whether or
 * not it does anything yet**. That is the whole point: a cashier learns "void is fourth
 * from the left" with their hand, not their eyes, and a bar that reflows as features
 * arrive would retrain them every release. Unassigned keys sit greyed in their place until
 * the milestone that fills them.
 *
 * Labels are short enough not to wrap at 1024px, the narrowest screen the till supports.
 *
 * **Sized for a finger as well as a key (M6).** At 56px this was a legend that happened to
 * be clickable; at 84px it is a control a cashier can hit without looking, which is what a
 * touchscreen till needs from the row that carries void, tender and cash up. The two-span
 * structure is deliberately untouched — the accessible name of each button is `key` plus
 * `label` ("F6 Customer"), and the e2e suite selects on exactly that string.
 */
export function FunctionBar({ keys }: { keys: readonly FunctionKey[] }) {
  return (
    <nav
      aria-label="Function keys"
      className="border-hair bg-surface flex shrink-0 gap-px border-t"
    >
      {keys.map((fk) => {
        const active = Boolean(fk.run) && !fk.disabled;
        return (
          <button
            key={fk.key}
            type="button"
            tabIndex={-1}
            disabled={!active}
            onClick={fk.run}
            className={`flex min-h-[84px] flex-1 flex-col items-center justify-center gap-1 px-1 py-2 ${
              active ? 'text-ink' : 'text-ink-3'
            }`}
          >
            <span
              className={`lum-money text-lg font-bold leading-none ${active ? 'text-accent' : ''}`}
            >
              {fk.key}
            </span>
            <span className="truncate text-sm">{fk.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
