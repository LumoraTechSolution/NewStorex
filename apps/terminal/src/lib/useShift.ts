'use client';

import { useCallback, useEffect, useState } from 'react';

/**
 * Whether this terminal is trading, and who to ask when it is not (M2-01).
 *
 * ## Why the till polls rather than remembering
 *
 * A shift belongs to the terminal, not to this browser window. The Electron renderer can be
 * reloaded, the backend restarted, or the shift closed from a back-office screen that does not
 * exist yet — and in every one of those cases the cart screen must not go on believing it may
 * sell. So the answer comes from the backend, and the backend's answer is the only one.
 *
 * The poll is slow on purpose. Nothing about a shift changes minute to minute, and the till
 * refetches immediately after any action that could have changed it. This interval exists for the
 * cases nothing local caused.
 *
 * ## What this deliberately cannot tell you
 *
 * There is no expected-cash figure here, because the endpoint has none to give (M2-02). That is
 * the blind count's enforcement and it lives server-side; this hook is simply the client of an
 * API that was designed not to leak.
 */
const POLL_INTERVAL_MS = 30_000;

export interface ShiftStatus {
  readonly open: boolean;
  readonly shiftId: number | null;
  readonly clientUuid: string | null;
  readonly openedAt: string | null;
  readonly openingFloatMinor: number | null;
  readonly saleCount: number | null;
  readonly cashMovementCount: number | null;
}

const CLOSED: ShiftStatus = {
  open: false,
  shiftId: null,
  clientUuid: null,
  openedAt: null,
  openingFloatMinor: null,
  saleCount: null,
  cashMovementCount: null,
};

export function useShift(branchCode: string, terminalCode: string) {
  const [status, setStatus] = useState<ShiftStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const response = await fetch(
        `/api/shifts/current?branchCode=${encodeURIComponent(branchCode)}&terminalCode=${encodeURIComponent(terminalCode)}`,
        { cache: 'no-store' },
      );
      if (!response.ok) throw new Error(`shift status: HTTP ${response.status}`);
      setStatus((await response.json()) as ShiftStatus);
      setError(null);
    } catch (e) {
      // Deliberately does not fall back to CLOSED. "The backend is unreachable" and "no shift is
      // open" are different problems with different fixes, and showing the second when the first
      // is true sends a cashier to count a float they do not need to count.
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [branchCode, terminalCode]);

  useEffect(() => {
    void refresh();
    const timer = setInterval(() => void refresh(), POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [refresh]);

  return {
    /** `null` until the first answer arrives — distinct from a known-closed shift. */
    status,
    error,
    /** True only when the backend has said so. An unknown shift is never a tradeable one. */
    canTrade: status?.open === true,
    refresh,
  };
}

export { CLOSED as CLOSED_SHIFT };
