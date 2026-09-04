'use client';

import { useEffect, useState } from 'react';

/**
 * "An update is ready" — and nothing to press (M5-11).
 *
 * <h2>Why there is no button</h2>
 *
 * Every desktop app puts a **Restart now** beside this line. On a till that button is a way to stop
 * Postgres under an open shift, lose an unprinted receipt and leave a cashier holding a queue — so
 * it does not exist, in the renderer or in the bridge. The update is applied by the installer after
 * this process exits, which for a shop is the end of the day, and the sentence here says so rather
 * than implying a choice nobody has.
 *
 * <h2>Why it is shown at all, then</h2>
 *
 * Because the alternative is a POS that silently changes overnight. A shopkeeper who opens on
 * Tuesday to a screen that moved deserves to have read on Monday that it was going to. This is the
 * one line that turns an update from something that happens to them into something they were told.
 *
 * <h2>Both a pull and a subscription</h2>
 *
 * The state is asked for on mount and subscribed to afterwards. A message pushed before a reload is
 * gone, and this component remounts on every navigation between the till and the back office — so
 * the pull is what makes it survive, and the subscription is what makes it appear without one.
 */
export function UpdateNotice() {
  const [update, setUpdate] = useState<UpdateState | null>(null);

  useEffect(() => {
    const bridge = window.lumora?.updates;
    // Undefined in a plain browser tab, which is where `next dev` runs. Nothing here is desktop
    // behaviour worth faking there.
    if (!bridge) return;

    let live = true;
    void bridge.state().then((current) => {
      if (live) setUpdate(current);
    });
    const unsubscribe = bridge.onChange((event) => setUpdate(event));

    return () => {
      live = false;
      unsubscribe();
    };
  }, []);

  if (!update) {
    return null;
  }

  return (
    <div
      role="status"
      aria-live="polite"
      className="border-hair text-ink-3 flex flex-wrap items-center gap-x-2 gap-y-1 border-b px-4 py-2 text-xs"
    >
      {/* Decorative: colour and glyphs never carry meaning on their own (§A), the words do. */}
      <span aria-hidden="true">↑</span>
      <span className="text-ink-2 font-semibold uppercase tracking-wider">
        UPDATE READY{update.version ? ` · ${update.version}` : ''}
      </span>
      <span>It will be installed the next time StoreX is closed. Nothing to do now.</span>
    </div>
  );
}
