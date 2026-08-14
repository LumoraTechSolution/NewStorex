'use client';

import { useEffect, useState } from 'react';

/**
 * Switches the till between the dark appliance and light mode (D6).
 *
 * A shop with a bright window is the case this exists for: behind glass, a dark screen is
 * a mirror. It is a per-machine setting rather than a per-user one — a till has one
 * screen in one place under one set of lights, and every cashier on every shift sees the
 * same thing, which is the property the fixed palette was protecting in the first place.
 *
 * The value is read by the inline script in `layout.tsx` before first paint. This
 * component only ever writes it and reflects the current state; it must not be the thing
 * that applies it, or the screen flashes on every load.
 */
const STORAGE_KEY = 'storex.terminal.theme';

type Theme = 'dark' | 'light';

export function ThemeToggle() {
  // Starts as null rather than 'dark' so the label does not render the wrong state for a
  // frame on a till that is set to light.
  const [theme, setTheme] = useState<Theme | null>(null);

  useEffect(() => {
    setTheme(document.documentElement.dataset.theme === 'light' ? 'light' : 'dark');
  }, []);

  function choose(next: Theme) {
    if (next === 'light') {
      document.documentElement.dataset.theme = 'light';
    } else {
      delete document.documentElement.dataset.theme;
    }
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // A till with storage blocked still switches for this session; it just forgets.
    }
    setTheme(next);
  }

  const next: Theme = theme === 'light' ? 'dark' : 'light';

  return (
    <button
      type="button"
      onClick={() => choose(next)}
      // Named rather than an icon alone: the F-key bar (M1-07) is where a cashier learns
      // positions, and an unlabelled sun/moon is the kind of control people press twice.
      aria-label={`Switch to ${next} mode`}
      className="border-hair text-ink-2 rounded-lg border px-3 py-1 text-xs uppercase tracking-wider"
    >
      {theme === null ? ' ' : theme === 'light' ? 'Light' : 'Dark'}
    </button>
  );
}
