'use client';

import { useEffect, useState } from 'react';

import { applyChoice, readChoice, type ThemeChoice } from '@/lib/theme';

/**
 * The console's theme control (M4-11).
 *
 * <h2>Three buttons rather than one that cycles</h2>
 *
 * There are three states — follow the phone, light, dark — and a single button that steps through
 * them is the control people press twice and end up somewhere they did not want. Worse, a cycling
 * button can only ever show one label, so it either says where you are or where you are going, and
 * whichever it picks is wrong for half the people reading it.
 *
 * <p>A radio group says all three and marks the current one, which is also what a screen reader
 * needs: `aria-checked` on three radios is a state, whereas a button whose text changes is an event
 * nobody is told about.
 *
 * <h2>It does not apply the theme on load</h2>
 *
 * The inline script in `layout.tsx` does that, before the first paint. This component reads the
 * saved choice after mount only so its own buttons show the right one — and it starts as `null`
 * rather than `'system'`, because rendering "Auto" as selected for one frame on a phone set to dark
 * is the same class of lie as the flash the script exists to prevent.
 */
const OPTIONS: readonly { value: ThemeChoice; label: string; description: string }[] = [
  // "Auto" rather than "System": on a phone this follows a setting the viewer thinks of as
  // belonging to the phone, and "system" is a desktop word.
  { value: 'system', label: 'Auto', description: 'Follow this device' },
  { value: 'light', label: 'Light', description: 'Always light' },
  { value: 'dark', label: 'Dark', description: 'Always dark' },
];

export function ThemeToggle({ className = '' }: { className?: string }) {
  const [choice, setChoice] = useState<ThemeChoice | null>(null);

  useEffect(() => {
    setChoice(readChoice());
  }, []);

  function choose(next: ThemeChoice) {
    applyChoice(next);
    setChoice(next);
  }

  return (
    <div
      role="radiogroup"
      aria-label="Colour theme"
      className={`border-hair inline-flex shrink-0 rounded-lg border p-0.5 ${className}`}
    >
      {OPTIONS.map((option) => {
        // Null while the saved choice is still unknown, so nothing is marked selected rather
        // than the wrong thing being marked selected.
        const selected = choice === option.value;
        return (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={selected}
            // The label alone reads as three unrelated words out of context; this says what
            // pressing it does, which is what a screen reader announces.
            aria-label={option.description}
            onClick={() => choose(option.value)}
            className={`min-h-[32px] rounded-md px-2 text-xs font-medium ${
              selected ? 'bg-accent text-accent-ink' : 'text-ink-3 hover:text-ink'
            }`}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
