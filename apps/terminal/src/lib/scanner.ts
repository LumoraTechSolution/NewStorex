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
