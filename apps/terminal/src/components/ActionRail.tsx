'use client';

import { formatMinor } from '@lumora/domain';

/**
 * The touch rail (M6).
 *
 * ## Why it duplicates the F-key bar rather than replacing it
 *
 * Every button here runs the *same callback* its function key runs — nothing on this rail is
 * reachable only by finger, and nothing it does is new. That is the rule the whole touch
 * layer rests on: touch is a second door into the same room, so the keyboard path cannot
 * regress because touch does not exist on it.
 *
 * Each button therefore carries its key name. A cashier who taps `Void line` sees `F4` on it
 * and learns the shortcut without being taught; one who already knows `F4` is not being asked
 * to hunt for a button. The two input methods teach each other instead of competing.
 *
 * ## Why the digits are here and not bound to the keyboard
 *
 * The keypad sets a quantity multiplier — tap `3`, then add an item, and three of it go in.
 * The digits are **touch-only on purpose**: `scanner.ts` never binds a plain digit, because a
 * barcode is a burst of digits and binding them would make every scan type a quantity. The
 * keyboard's equivalent is `+` / `-` / `F2`, already bound, and both routes call the same
 * `changeQty`. Do not "unify" them by dispatching synthetic KeyboardEvents from these
 * buttons: that would advance `lastCharacterAt` and break the M1-09 terminator window under
 * a real gun, which is a failure that only shows up in a shop.
 */
export function ActionRail({
  multiplier,
  onDigit,
  onClearMultiplier,
  totalMinor,
  onSearch,
  onVoidLine,
  onClear,
  onTender,
  canVoid,
  canClear,
  canTender,
  canSearch,
  tenderLabel,
  disabled = false,
}: {
  /** The pending quantity, or 1 when nothing has been typed. */
  multiplier: number;
  onDigit: (digit: string) => void;
  onClearMultiplier: () => void;
  totalMinor: number;
  onSearch: () => void;
  onVoidLine: () => void;
  onClear: () => void;
  onTender: () => void;
  canVoid: boolean;
  canClear: boolean;
  canTender: boolean;
  canSearch: boolean;
  tenderLabel: string;
  disabled?: boolean;
}) {
  const pending = multiplier > 1;

  return (
    // `min-h-0` matters more than it looks: without it this column's natural height wins
    // over the flex parent, PAY is pushed below the fold and the F-key bar draws on top of
    // it — which is exactly what happened at 1024x768 the first time. The keypad gives up
    // height instead, so the actions at the bottom are always whole.
    <aside
      aria-label="Touch actions"
      className="border-hair flex min-h-0 w-64 shrink-0 flex-col gap-2 border-l p-3 xl:w-72"
    >
      {/*
        Always rendered, so the keypad never shifts under a finger — it reads "1" when
        nothing is pending. The accent only appears once a quantity is actually waiting,
        because a permanently coloured chip stops being a signal.
      */}
      <div
        className={`min-h-touch flex items-center justify-between rounded-lg border px-4 ${
          pending ? 'border-accent' : 'border-hair'
        }`}
      >
        <span className="text-ink-3 text-xs uppercase tracking-wider">Quantity</span>
        <span
          aria-live="polite"
          aria-label={`Quantity ${multiplier}`}
          className={`lum-money text-xl font-bold ${pending ? 'text-accent' : 'text-ink-3'}`}
        >
          ×{multiplier}
        </span>
      </div>

      {/*
        `min-h-0` + `flex-1` on the grid, and the keys size themselves to it: at 1366x768
        they are comfortably above the 56px minimum, and at 1024x768 with three banners
        showing they shrink rather than shoving PAY off the bottom of the screen.
      */}
      <div className="grid min-h-0 flex-1 grid-cols-3 gap-1.5">
        {['7', '8', '9', '4', '5', '6', '1', '2', '3'].map((digit) => (
          <Key key={digit} label={digit} onClick={() => onDigit(digit)} disabled={disabled} />
        ))}
        <Key label="0" onClick={() => onDigit('0')} disabled={disabled} />
        <Key label="00" onClick={() => onDigit('00')} disabled={disabled} />
        {/*
          Named "Undo quantity" rather than "Clear quantity" so it does not collide with the
          Clear button below it — two controls whose accessible names both begin "Clear" is
          ambiguous to a screen reader for the same reason it is ambiguous to a test.
        */}
        <Key
          label="⌫"
          name="Undo quantity"
          onClick={onClearMultiplier}
          disabled={disabled || !pending}
        />
      </div>

      <Action
        fkey="F3"
        label="Search"
        onClick={onSearch}
        disabled={disabled || !canSearch}
        tone="plain"
      />
      <Action
        fkey="F4"
        label="Void line"
        onClick={onVoidLine}
        disabled={disabled || !canVoid}
        tone="danger"
      />
      <Action
        fkey="F8"
        label="Clear"
        onClick={onClear}
        disabled={disabled || !canClear}
        tone="plain"
      />

      {/*
        The one green control on the screen, and the exception to §A's "accent is the primary
        action and nothing else" — recorded beside --lum-ok. A green pay key is a forty-year
        convention on this hardware and every till a shopkeeper has used has one; breaking it
        for token purity would cost more than it buys. The accent stays free for selection.

        The label is deliberately not "Tender": `F12 Tender` on the function bar is an e2e
        selector, and two buttons with that accessible name would make it ambiguous.
      */}
      <button
        type="button"
        tabIndex={-1}
        disabled={disabled || !canTender}
        onClick={onTender}
        className="bg-ok text-ok-ink flex min-h-[92px] items-center justify-between rounded-lg px-4 disabled:opacity-40"
      >
        <span className="flex flex-col items-start">
          <span className="text-2xl font-bold tracking-wide">PAY</span>
          <span className="lum-money text-xs opacity-75">F12</span>
        </span>
        <span className="lum-money text-xl font-bold">
          {tenderLabel === 'Working…' ? '…' : formatMinor(totalMinor)}
        </span>
      </button>
    </aside>
  );
}

function Key({
  label,
  name,
  onClick,
  disabled,
}: {
  label: string;
  name?: string;
  onClick: () => void;
  disabled: boolean;
}) {
  return (
    <button
      type="button"
      tabIndex={-1}
      aria-label={name ?? label}
      disabled={disabled}
      onClick={onClick}
      className="border-hair text-ink lum-money min-h-[48px] rounded-lg border text-xl font-medium disabled:opacity-30"
    >
      {label}
    </button>
  );
}

function Action({
  fkey,
  label,
  onClick,
  disabled,
  tone,
}: {
  fkey: string;
  label: string;
  onClick: () => void;
  disabled: boolean;
  tone: 'plain' | 'danger';
}) {
  return (
    <button
      type="button"
      tabIndex={-1}
      disabled={disabled}
      onClick={onClick}
      className={`min-h-touch flex items-center justify-between rounded-lg border px-4 text-base font-semibold disabled:opacity-30 ${
        tone === 'danger' ? 'border-danger text-danger' : 'border-hair text-ink'
      }`}
    >
      <span>{label}</span>
      <span className="lum-money text-ink-3 text-xs font-medium">{fkey}</span>
    </button>
  );
}
