'use client';

import { useCallback, useState } from 'react';

/**
 * The two things every gated action on the till now asks for (M3-08): who you are, and your PIN.
 *
 * <h2>Why a code and not just a PIN</h2>
 *
 * A PIN alone would be one field instead of two, and it cannot identify anybody. Two people are
 * allowed to choose 1234, and the backend would have to pick one of them to write into
 * `authorised_by` — an audit trail that can name the wrong person is worse than none, because it
 * gets believed. So the code identifies and the PIN authenticates, and this hook is the one place
 * that knows how the pair is typed.
 *
 * <h2>Why a hook and not a controlled input</h2>
 *
 * The till has no focus ring to chase and no `<input>` a scanner can steal. Every overlay already
 * owns a document-level `keydown` handler, so this plugs into that: call {@link Operator.onKey}
 * first and bail out if it returns true. Three overlays sharing one implementation is also the
 * only way the code field behaves the same in all of them — a second implementation would diverge
 * on exactly the fiddly parts, like what Backspace does when the PIN is already empty.
 */
export type OperatorField = 'CODE' | 'PIN';

export interface Operator {
  code: string;
  pin: string;
  /** Which of the two the next keypress goes into. */
  field: OperatorField;
  /** Both filled in enough to be worth sending. */
  ready: boolean;
  /**
   * Feed it a keydown. Returns true when it consumed the event, in which case the caller must
   * stop — the key was for these fields, not for whatever else the overlay does with it.
   *
   * <p>Deliberately does not handle Enter. Enter means "submit" to the overlay, and what to submit
   * differs between opening a shift, closing one and authorising a refund; swallowing it here
   * would put that decision in the wrong place.
   */
  onKey: (event: KeyboardEvent) => boolean;
  /** Wipes both fields and returns to the code. Used after a refusal. */
  reset: () => void;
  /** Moves to the PIN, as Enter or Tab on the code field does. */
  advance: () => void;
}

const MAX_CODE = 16;
const MAX_PIN = 12;

export function useOperator(): Operator {
  const [code, setCode] = useState('');
  const [pin, setPin] = useState('');
  const [field, setField] = useState<OperatorField>('CODE');

  const reset = useCallback(() => {
    setCode('');
    setPin('');
    setField('CODE');
  }, []);

  const advance = useCallback(() => {
    setCode((current) => {
      if (current.trim().length > 0) setField('PIN');
      return current;
    });
  }, []);

  const onKey = useCallback(
    (event: KeyboardEvent) => {
      if (event.ctrlKey || event.altKey || event.metaKey) return false;

      if (event.key === 'Tab') {
        event.preventDefault();
        // Tab only ever goes forward. There is nothing behind the code field, and a till that
        // moves focus somewhere invisible on Shift+Tab is a till the cashier has to look at.
        if (field === 'CODE') advance();
        else setField('CODE');
        return true;
      }

      if (event.key === 'Backspace') {
        event.preventDefault();
        if (field === 'PIN') {
          // An empty PIN plus Backspace means "I mistyped my code", which is the only reason
          // anybody presses it there. Stepping back beats making them Escape out of the whole
          // refund to fix one character.
          setPin((current) => {
            if (current.length === 0) setField('CODE');
            return current.slice(0, -1);
          });
        } else {
          setCode((current) => current.slice(0, -1));
        }
        return true;
      }

      if (event.key.length !== 1) return false;

      if (field === 'CODE') {
        event.preventDefault();
        // Upper-cased on the way in because the backend stores codes upper-case, and a cashier
        // seeing what they typed change case later would reasonably wonder what else did.
        setCode((current) =>
          current.length >= MAX_CODE ? current : current + event.key.toUpperCase(),
        );
        return true;
      }

      // Digits only in the PIN: it is entered on a numeric keypad, and quietly accepting a letter
      // produces a PIN that cannot be retyped on the hardware it was invented on.
      if (event.key >= '0' && event.key <= '9') {
        event.preventDefault();
        setPin((current) => (current.length >= MAX_PIN ? current : current + event.key));
        return true;
      }
      return false;
    },
    [advance, field],
  );

  return {
    code,
    pin,
    field,
    ready: code.trim().length > 0 && pin.length >= 4,
    onKey,
    reset,
    advance,
  };
}
