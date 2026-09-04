import { readFileSync } from 'node:fs';

import { describe, expect, it } from 'vitest';

import {
  attributeFor,
  beforeFirstPaintScript,
  PAGE_COLOUR,
  parseChoice,
  resolveTheme,
  THEME_STORAGE_KEY,
} from './theme';

/**
 * The theme choice (M4-11).
 *
 * <p>The interesting failures here are not "the wrong colour appears" — that is visible the moment
 * anybody looks. They are the two that are invisible in a screenshot: a stored value that stops
 * being honoured because a name drifted, and a resolution that gets the *machine* right and the
 * *choice* wrong, so the toggle appears to work in one direction only.
 */
describe('resolveTheme', () => {
  it('follows the device only when the viewer has not chosen', () => {
    expect(resolveTheme('system', true)).toBe('dark');
    expect(resolveTheme('system', false)).toBe('light');
  });

  it('overrides the device in both directions', () => {
    // Both halves matter. An early version of the palette guarded the dark rule but not the
    // light one, so choosing light on a dark machine did nothing and the toggle looked broken
    // to exactly the people most likely to press it.
    expect(resolveTheme('dark', false)).toBe('dark');
    expect(resolveTheme('light', true)).toBe('light');
  });
});

describe('attributeFor', () => {
  it('stamps nothing for the default, so the OS media query still applies', () => {
    // Null rather than 'system': `[data-theme]` has no "follow the machine" value, and inventing
    // one would need a CSS rule that does not exist.
    expect(attributeFor('system')).toBeNull();
    expect(attributeFor('light')).toBe('light');
    expect(attributeFor('dark')).toBe('dark');
  });
});

describe('parseChoice', () => {
  it('treats anything it does not recognise as no choice', () => {
    expect(parseChoice('light')).toBe('light');
    expect(parseChoice('dark')).toBe('dark');
    expect(parseChoice(null)).toBe('system');
    // A key hand-edited in devtools, or written by a build that spelled it differently, must
    // leave the console usable rather than wedged on a theme nothing can select.
    expect(parseChoice('System')).toBe('system');
    expect(parseChoice('')).toBe('system');
    expect(parseChoice('midnight')).toBe('system');
  });
});

describe('beforeFirstPaintScript', () => {
  const script = beforeFirstPaintScript();

  it('reads the same key this module writes', () => {
    // The reason the script is generated rather than typed into layout.tsx. Two literals drift,
    // and the symptom is a saved choice that silently stops being honoured on reload — which
    // looks like the flash the script exists to prevent, so nobody suspects the key.
    expect(script).toContain(JSON.stringify(THEME_STORAGE_KEY));
  });

  it('carries the same colours the metadata does', () => {
    expect(script).toContain(JSON.stringify(PAGE_COLOUR.dark));
    expect(script).toContain(JSON.stringify(PAGE_COLOUR.light));
  });

  it('cannot throw where storage is blocked', () => {
    // It runs ahead of the entire application. An exception here is a blank page, not a wrong
    // colour, and private browsing is enough to raise one on getItem alone.
    expect(script).toContain('try {');
    expect(script).toContain('catch (e) {}');
  });

  it('is syntactically valid on its own', () => {
    // It is injected as a string and never sees the TypeScript compiler or the bundler, so a
    // typo in it is caught by nothing else in the build.
    expect(() => new Function(script)).not.toThrow();
  });
});

describe('PAGE_COLOUR', () => {
  /**
   * Read out of the stylesheet rather than typed here twice.
   *
   * <p>Before M4-11 the metadata said #FFFFFF and #04121C while the page rendered something else,
   * which put a seam across the top of an installed PWA in both themes — and a hardcoded copy of
   * the right answer is the same bug waiting for the next repaint. M6-14 was that repaint: the
   * console took its own warm ground, and this now fails if only one of the two files moves.
   */
  const css = readFileSync(new URL('../app/globals.css', import.meta.url), 'utf8');
  const grounds = [...css.matchAll(/--lum-page:\s*(#[0-9a-f]{6})/gi)].map((match) => match[1]);

  it('matches --lum-page in the console’s own tokens', () => {
    expect(grounds[0]).toBe(PAGE_COLOUR.light);
    expect(grounds[1]).toBe(PAGE_COLOUR.dark);
  });

  it('keeps the two dark blocks saying the same thing', () => {
    // The dark palette is stated twice — once for an explicit choice and once for the OS
    // preference — because a media query cannot join a selector list. tokens.css carries the same
    // warning; this is the thing that enforces it.
    expect(grounds).toHaveLength(3);
    expect(grounds[2]).toBe(grounds[1]);
  });
});
