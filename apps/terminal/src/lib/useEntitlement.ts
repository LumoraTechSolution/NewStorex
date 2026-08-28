'use client';

import { useCallback, useEffect, useState } from 'react';

/**
 * What the cloud last said this shop may do (M4-09).
 *
 * The till reads it from its own database — `/api/entitlement` is a local endpoint answered off a
 * cached row — so every screen below can ask this question during an outage and get the same
 * answer it got yesterday. A capability check that had to reach the cloud would be a back office
 * that disappears when the internet does, which is the failure this whole product is built to
 * avoid (ROADMAP §A).
 */
export type Entitlement = {
  /**
   * Whether the cloud has ever answered this till. False on a fresh install and through the whole
   * of M0–M3, and deliberately not a problem state: `allows` says yes to everything.
   */
  known: boolean;
  /** The cached answer, not a live check. False shows a renewal notice and locks nothing. */
  licensed: boolean;
  planCode: string | null;
  planName: string | null;
  licenceExpiresAt: string | null;
  checkedAt: string | null;
  licensedAt: string | null;
  maxTerminals: number | null;
  flags: string[];
};

/** The capability names the cloud's registry knows. Kept as a union so a typo is a type error. */
export type Capability =
  | 'back_office'
  | 'csv_import'
  | 'goods_receipt'
  | 'stocktake'
  | 'customers'
  | 'tax_invoice'
  | 'owner_console';

export type EntitlementState = {
  entitlement: Entitlement | null;
  /**
   * Whether a capability may be shown.
   *
   * Answers **true** while loading and true when nothing has ever been cached. Both are the same
   * decision and it is the important one: a screen that hides itself until an answer arrives is a
   * screen that flickers on every load and vanishes on a till that has never synced. Flags shape
   * what is offered; they are not a lock, and the commercial lever is ingest, not this.
   */
  allows: (capability: Capability) => boolean;
  refresh: () => void;
};

export function useEntitlement(pollMs = 60_000): EntitlementState {
  const [entitlement, setEntitlement] = useState<Entitlement | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      try {
        const response = await fetch('/api/entitlement', { cache: 'no-store' });
        if (!response.ok) throw new Error(String(response.status));
        const body: Entitlement = await response.json();
        if (!cancelled) setEntitlement(body);
      } catch {
        // The local backend is unreachable, which the sync strip already reports far better than
        // this could. Leaving the last answer standing is the same rule the backend cache follows:
        // never withdraw a capability because a read failed.
      }
    };

    void load();
    // A minute, not the five seconds the sync strip polls at. This is a local read of a row the
    // backend refreshes every five minutes at most, so anything faster is work that finds nothing.
    const timer = setInterval(() => void load(), pollMs);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [pollMs, tick]);

  const allows = useCallback(
    (capability: Capability) =>
      entitlement === null || !entitlement.known || entitlement.flags.includes(capability),
    [entitlement],
  );

  const refresh = useCallback(() => setTick((n) => n + 1), []);

  return { entitlement, allows, refresh };
}
