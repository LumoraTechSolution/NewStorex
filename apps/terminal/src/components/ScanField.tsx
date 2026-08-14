'use client';

import { useEffect, useRef } from 'react';

import { isScannerTerminator, markCharacterKey } from '@/lib/scanner';

/**
 * The scan field (M1-08).
 *
 * **It always has focus.** A barcode gun is a keyboard with no pointer, so anywhere the
 * caret is not, a scan goes nowhere — and the cashier finds out when the customer asks why
 * nothing appeared. Focus is taken on mount, returned on blur, and returned after any
 * click lands elsewhere on the screen. There is no state in which scanning does nothing.
 *
 * That is also why the till has no navigation: every other control on the screen is a
 * function key, so nothing else ever legitimately wants the caret.
 */
export function ScanField({
  onScan,
  onQuery,
  disabled = false,
  hint,
}: {
  /** A code the gun completed — add it and move on, no confirmation. */
  onScan: (code: string) => void;
  /** The cashier typed something and pressed Enter themselves. */
  onQuery: (text: string) => void;
  disabled?: boolean;
  hint?: string;
}) {
  const ref = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (disabled) return;
    const input = ref.current;
    input?.focus();

    // A click on a button still fires its handler; the caret just comes back afterwards.
    // Deferred a tick so the click completes before focus moves.
    function reclaim() {
      if (document.activeElement !== input) {
        setTimeout(() => ref.current?.focus(), 0);
      }
    }

    document.addEventListener('click', reclaim);
    document.addEventListener('focusin', reclaim);
    return () => {
      document.removeEventListener('click', reclaim);
      document.removeEventListener('focusin', reclaim);
    };
  }, [disabled]);

  function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    markCharacterKey(event.nativeEvent);

    if (event.key !== 'Enter') return;
    const input = event.currentTarget;
    const value = input.value.trim();

    // Stop here whatever happens: the global handler must not also see this Enter and
    // tender the sale on top of adding the item.
    event.preventDefault();
    event.stopPropagation();
    if (value === '') return;

    const fromGun = isScannerTerminator(event.nativeEvent);
    input.value = '';
    if (fromGun) {
      onScan(value);
    } else {
      onQuery(value);
    }
  }

  return (
    <div className="flex items-baseline gap-3">
      <label htmlFor="scan" className="text-ink-3 text-xs uppercase tracking-wider">
        Scan
      </label>
      <input
        id="scan"
        ref={ref}
        type="text"
        disabled={disabled}
        // Nothing helpful may interpose itself between the gun and the field.
        autoComplete="off"
        autoCorrect="off"
        autoCapitalize="off"
        spellCheck={false}
        inputMode="none"
        placeholder="Scan a barcode, or type a name and press Enter"
        onKeyDown={onKeyDown}
        className="border-hair bg-surface text-ink min-h-touch placeholder:text-ink-3 focus:border-accent flex-1 rounded-lg border px-4 text-lg outline-none"
      />
      {hint && <span className="text-ink-3 text-xs">{hint}</span>}
    </div>
  );
}
