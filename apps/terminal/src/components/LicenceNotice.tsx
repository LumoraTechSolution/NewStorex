'use client';

import { useEntitlement } from '@/lib/useEntitlement';

/**
 * The renewal notice (M4-09) — and the reason the entitlement pull is a call of its own.
 *
 * A lapsed licence stops the shop's data reaching the cloud (V209). Before this strip, the only
 * symptom on the till was a sync status that never went green: the cashier saw "offline" while the
 * cable was plainly plugged in, and nothing on the machine could say why. That is a support call
 * that starts with a false diagnosis.
 *
 * It says what has stopped, what has not, and what to do. The middle clause is the one that
 * matters to whoever is standing at the counter: **selling is unaffected**, because the sale was
 * never on the network's critical path. Nothing here is a lock, and nothing here is dismissable —
 * a notice the cashier can close is a notice the owner never sees.
 *
 * Rendered nowhere when the licence is fine, and — just as deliberately — nowhere when the cloud
 * has never answered. A till that has not been activated is not a till in arrears, and telling a
 * shopkeeper their licence is a problem on their first morning would be a lie.
 */
export function LicenceNotice() {
  const { entitlement } = useEntitlement();

  if (!entitlement || !entitlement.known || entitlement.licensed) {
    return null;
  }

  const ended = entitlement.licenceExpiresAt ? formatDate(entitlement.licenceExpiresAt) : null;
  const plan = entitlement.planName ?? entitlement.planCode;

  return (
    <div
      role="status"
      aria-live="polite"
      className="border-hair text-danger flex flex-wrap items-center gap-x-2 gap-y-1 border-b px-4 py-2 text-xs"
    >
      {/* Colour never carries meaning on its own (§A): the mark is decorative and the words say it. */}
      <span aria-hidden="true">!</span>
      <span className="font-semibold uppercase tracking-wider">
        {ended ? `LICENCE ENDED ${ended}` : 'LICENCE NOT CURRENT'}
      </span>
      <span className="text-ink-3">
        {plan ? `${plan} · ` : ''}
        Selling is unaffected — sales are saved here and will sync once it is renewed.
      </span>
    </div>
  );
}

/**
 * Day, month, year, in that order. Deliberately not the MM/DD/YYYY the IRD mandates on a tax
 * invoice (M5-09): that format is a legal requirement about a document, not a habit to spread
 * across the interface, and a Sri Lankan shopkeeper reads 03/04 as the third of April.
 */
function formatDate(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}
