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
            className={`min-h-touch flex flex-1 flex-col items-center justify-center gap-0.5 px-1 py-2 ${
              active ? 'text-ink' : 'text-ink-3'
            }`}
          >
            <span className={`text-xs font-semibold ${active ? 'text-accent' : ''}`}>{fk.key}</span>
            <span className="truncate text-xs">{fk.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
