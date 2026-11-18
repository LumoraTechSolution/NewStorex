'use client';

import { useEffect, useRef } from 'react';

/**
 * Telling a barcode gun apart from a cashier (M1-09).
 *
 * A USB scanner is a keyboard. It types the code and presses Enter, and nothing in the
 * DOM says which device sent the keystrokes. Two rules fall out of that, and both exist
 * because of a specific way a till goes wrong.
 *
 * **Never bind a plain digit to anything.** A barcode is a burst of digits. If `1` meant
 * "quantity 1" or `7` meant "void", scanning would fire a handful of commands on the way
 * past. Every global binding here is a function key for that reason.
 *
 * **Enter arriving right after a character is the gun's terminator, not the cashier.**
 * Without this guard, scanning an item would type the code *and* trigger whatever Enter is
 * bound to — on this screen, tendering the sale. The cashier scans a second item and the
 * first one has already been paid for. 60ms is comfortably longer than a scanner's
 * inter-character gap (typically under 15ms) and far shorter than a human reaching for
 * Enter after typing.
 */
const SCANNER_TERMINATOR_WINDOW_MS = 60;

/** Printable single characters — what a scanner emits. Not modifiers or navigation. */
function isCharacterKey(event: KeyboardEvent): boolean {
  return event.key.length === 1 && !event.ctrlKey && !event.altKey && !event.metaKey;
}

/**
 * Tracks when the last character key arrived, anywhere on the page.
 *
 * Module-level rather than per-component: the scan field and the global shortcut handler
 * must consult the *same* clock, or each will draw its own conclusion about who typed.
 */
let lastCharacterAt = 0;

export function markCharacterKey(event: KeyboardEvent): void {
  if (isCharacterKey(event)) {
    lastCharacterAt = event.timeStamp || performance.now();
  }
}

/** True when this Enter almost certainly came from a scanner finishing a code. */
export function isScannerTerminator(event: KeyboardEvent): boolean {
  if (event.key !== 'Enter') return false;
  const now = event.timeStamp || performance.now();
  return now - lastCharacterAt < SCANNER_TERMINATOR_WINDOW_MS;
}

/** Shorter than any barcode in use (EAN-8 is the shortest) and longer than a fumbled keypress. */
export const MIN_SCAN_LENGTH = 4;

/**
 * Collects a scan that arrives with no text field focused (M6-18).
 *
 * <p>In a field, the field holds the characters and only the Enter needs judging. With focus on a
 * button or the page itself the characters go nowhere, so they have to be caught on the way past.
 * The rule is the same 60ms one, applied to every gap rather than just the last: a character after
 * a longer pause starts a new code, so a person tapping keys never accumulates one.
 *
 * <p>Pure — timestamps are passed in — so the timing rule is testable without a DOM.
 */
export class ScanBuffer {
  private chars = '';
  private lastAt = Number.NEGATIVE_INFINITY;

  /**
   * Feeds one keypress. Returns the code when this key is the Enter that ends a scan, else null.
   *
   * @param key `KeyboardEvent.key` of a printable character or Enter; anything else (Shift, which
   *     some scanners send before letters) is ignored without breaking the run.
   */
  push(key: string, at: number): string | null {
    const fresh = at - this.lastAt < SCANNER_TERMINATOR_WINDOW_MS;

    if (key === 'Enter') {
      const code = fresh && this.chars.length >= MIN_SCAN_LENGTH ? this.chars : null;
      this.reset();
      return code;
    }
    if (key.length !== 1) return null;

    this.chars = fresh ? this.chars + key : key;
    this.lastAt = at;
    return null;
  }

  reset(): void {
    this.chars = '';
    this.lastAt = Number.NEGATIVE_INFINITY;
  }
}

/** Where a scan already has a home: the element will take the characters itself. */
function isTextEntry(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target.isContentEditable) return true;
  return (
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement
  );
}

/**
 * Calls `onScan` with a code scanned while no text field has focus (M6-18).
 *
 * <p>A scan into a field is left alone — that field already decides what a scan means there (the
 * Find box opens the product, a barcode field in the form adds a row). This only catches the scans
 * that would otherwise be lost, because the owner last clicked a button.
 *
 * <p>The ending Enter is swallowed. Focus is usually still on whatever was clicked last, and an
 * Enter reaching a focused "Discontinue" button presses it.
 */
export function useScanAnywhere(onScan: (code: string) => void): void {
  const latest = useRef(onScan);
  latest.current = onScan;

  useEffect(() => {
    const buffer = new ScanBuffer();

    function onKeyDown(event: KeyboardEvent) {
      if (isTextEntry(event.target)) {
        buffer.reset();
        return;
      }
      if (event.ctrlKey || event.altKey || event.metaKey) {
        buffer.reset();
        return;
      }
      const code = buffer.push(event.key, event.timeStamp || performance.now());
      if (code === null) return;

      event.preventDefault();
      event.stopPropagation();
      latest.current(code);
    }

    document.addEventListener('keydown', onKeyDown, true);
    return () => document.removeEventListener('keydown', onKeyDown, true);
  }, []);
}

export type KeyBinding = {
  /** Function keys, or a plain letter with {@link ctrl}. See the note above on plain digits. */
  key: string;
  /**
   * Require Ctrl, and match the key case-insensitively (M3-01).
   *
   * <p>Exists for the back office and should stay rare. The function bar is the cashier's whole
   * vocabulary, and a shortcut that is not on it is one nobody discovers by looking — which is
   * the point for actions that are not selling, and a bug for any action that is.
   */
  ctrl?: boolean;
  run: () => void;
  /** Skip when a modal has taken over the screen. */
  when?: () => boolean;
};

/**
 * Registers the till's global shortcuts.
 *
 * Attached at the document, in the capture phase, so a binding fires wherever focus
 * happens to be — the scan field holds it almost all the time, and a cashier pressing F12
 * should not have to think about that.
 */
export function useGlobalKeys(bindings: readonly KeyBinding[]): void {
  const latest = useRef(bindings);
  latest.current = bindings;

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      markCharacterKey(event);

      if (event.key === 'Enter' && isScannerTerminator(event)) {
        // The gun's terminator. The scan field is welcome to it; nothing global is.
        return;
      }

      for (const binding of latest.current) {
        if (binding.ctrl) {
          // Ctrl is required, and Alt/Meta must be absent — Ctrl+Alt is AltGr on a European
          // keyboard, and swallowing it would eat characters a cashier is trying to type.
          if (!event.ctrlKey || event.altKey || event.metaKey) continue;
          if (binding.key.toLowerCase() !== event.key.toLowerCase()) continue;
        } else {
          if (event.ctrlKey || event.altKey || event.metaKey) continue;
          if (binding.key !== event.key) continue;
        }
        if (binding.when && !binding.when()) continue;
        // Function keys are browser and OS shortcuts too — F3 opens find, F12 devtools.
        // On an appliance the till's meaning is the only one that should survive.
        event.preventDefault();
        event.stopPropagation();
        binding.run();
        return;
      }
    }

    document.addEventListener('keydown', onKeyDown, true);
    return () => document.removeEventListener('keydown', onKeyDown, true);
  }, []);
}
