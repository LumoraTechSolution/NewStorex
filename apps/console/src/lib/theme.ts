/**
 * The console's theme choice (M4-11).
 *
 * <h2>Three states, not two</h2>
 *
 * The till's toggle is a switch because the till defaults to dark and must never follow the
 * machine — a shop PC's theme is whatever the person who installed Windows left on, and a till
 * that changed colour after a system update is a support call (D6).
 *
 * The console is the opposite surface: a phone app read in daylight and in bed, and it has followed
 * the viewer since the palette was written. So "follow my phone" is not the absence of a choice
 * here, it is the **best** choice for most people and the one the app ships on. Reducing this to a
 * light/dark switch would quietly take it away — whichever side the switch landed on would become a
 * fixed theme, and a viewer who wanted their phone's own behaviour back could never get it.
 *
 * <h2>Why this module is not a component</h2>
 *
 * The stored key and the colours are needed in two places that cannot share a runtime: this module,
 * and the string of JavaScript that `layout.tsx` inlines to run before first paint. That script
 * cannot import anything, so the constants are interpolated into it from here. The alternative — a
 * literal in the script and a copy in the component — is two names that drift, and the symptom of
 * the drift is a saved choice that silently stops being honoured.
 */

/** Namespaced per app: the till and the console are different surfaces with different defaults. */
export const THEME_STORAGE_KEY = 'storex.console.theme';

export type ThemeChoice = 'system' | 'light' | 'dark';

/** What the viewer actually sees, once the choice and the machine have both been consulted. */
export type ResolvedTheme = 'light' | 'dark';

/**
 * The page colour in each theme, for the browser chrome around it.
 *
 * <p>These are `--lum-page` from the design tokens, and they have to stay equal to it. An installed
 * PWA paints the status bar in this colour, so a value that is merely close reads as a seam across
 * the top of the screen — which is what was there before M4-11: the manifest carried `#FFFFFF` and
 * `#04121C` while the page rendered `#f5f7f9` and `#0a0e12`.
 */
export const PAGE_COLOUR: Record<ResolvedTheme, string> = {
  // The console's own ground, not the shared token's — see the override block in globals.css.
  // These are what `--lum-page` actually renders here, and a status bar half a shade off the page
  // under it is a visible seam across the top of an installed PWA.
  light: '#f1f3f0',
  dark: '#0e120f',
};

/** The attribute value to stamp on `<html>`, or null to stamp nothing and let the OS decide. */
export function attributeFor(choice: ThemeChoice): ResolvedTheme | null {
  return choice === 'system' ? null : choice;
}

/**
 * What the viewer ends up looking at.
 *
 * <p>Pure, and separated from everything that touches the document so it can be tested without one.
 * The whole of the theme's logic is this one line; every other function here is plumbing around it.
 */
export function resolveTheme(choice: ThemeChoice, prefersDark: boolean): ResolvedTheme {
  if (choice === 'system') return prefersDark ? 'dark' : 'light';
  return choice;
}

/** Anything unrecognised is treated as no choice, so a stale or hand-edited value cannot wedge. */
export function parseChoice(raw: string | null): ThemeChoice {
  return raw === 'light' || raw === 'dark' ? raw : 'system';
}

export function readChoice(): ThemeChoice {
  try {
    return parseChoice(localStorage.getItem(THEME_STORAGE_KEY));
  } catch {
    // Private browsing, or storage blocked. The console still works and still switches; it
    // just forgets, which is a far better failure than refusing to render.
    return 'system';
  }
}

/**
 * Applies a choice to the live document and remembers it.
 *
 * <p>Deliberately not what puts the theme on screen at load: that is the inline script in
 * `layout.tsx`, which runs before the first paint. If this were the only mechanism, a viewer whose
 * phone is light and whose choice is dark would get a white flash on every single load — the page
 * would paint light, hydrate, and then correct itself.
 */
export function applyChoice(choice: ThemeChoice): void {
  const attribute = attributeFor(choice);
  if (attribute) {
    document.documentElement.dataset.theme = attribute;
  } else {
    delete document.documentElement.dataset.theme;
  }

  syncBrowserChrome(choice);

  try {
    if (choice === 'system') {
      // Removed rather than stored as the string "system". An absent key and a key saying
      // "follow the machine" mean the same thing, and keeping both invents a difference that
      // some later reader will try to honour.
      localStorage.removeItem(THEME_STORAGE_KEY);
    } else {
      localStorage.setItem(THEME_STORAGE_KEY, choice);
    }
  } catch {
    // As above: switching without remembering beats not switching.
  }
}

/**
 * Keeps the status bar of an installed PWA the same colour as the page under it.
 *
 * <p>`layout.tsx` declares two `theme-color` metas scoped to `prefers-color-scheme`, which is
 * exactly right while the choice is "follow my phone". The moment somebody chooses otherwise those
 * two describe the machine rather than the page, and an owner on a light phone who picks dark gets
 * a dark app under a white status bar.
 *
 * <p>So an explicit choice adds a third meta with no `media`, inserted as the **first** child of
 * `<head>`: the browser uses the first `theme-color` whose media matches, and one with no media
 * always matches. Choosing "system" removes it and the pair underneath take over again.
 */
function syncBrowserChrome(choice: ThemeChoice): void {
  const existing = document.querySelector<HTMLMetaElement>('meta[name="theme-color"][data-lum]');

  if (choice === 'system') {
    existing?.remove();
    return;
  }

  const meta = existing ?? document.createElement('meta');
  meta.name = 'theme-color';
  meta.dataset.lum = 'choice';
  meta.content = PAGE_COLOUR[choice];
  if (!existing) {
    document.head.insertBefore(meta, document.head.firstChild);
  }
}

/**
 * The script `layout.tsx` inlines, built here so the key and the colours have one definition.
 *
 * <p>It must stay small, synchronous and total: it runs ahead of the whole application on every
 * load, so anything that can throw has to be caught, and anything slow is time the viewer spends
 * looking at nothing.
 */
export function beforeFirstPaintScript(): string {
  return `
try {
  var c = localStorage.getItem(${JSON.stringify(THEME_STORAGE_KEY)});
  if (c === 'light' || c === 'dark') {
    document.documentElement.dataset.theme = c;
    var m = document.createElement('meta');
    m.name = 'theme-color';
    m.setAttribute('data-lum', 'choice');
    m.content = c === 'dark' ? ${JSON.stringify(PAGE_COLOUR.dark)} : ${JSON.stringify(PAGE_COLOUR.light)};
    document.head.insertBefore(m, document.head.firstChild);
  }
} catch (e) {}
`.trim();
}
