'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

/**
 * The back office's sign-in, and the token every request it makes carries (M3-01, M3-08, M3-09).
 *
 * <h2>The PIN is sent once and never held</h2>
 *
 * Until M3-09 this hook kept the operator's code and PIN in memory and replayed both on every
 * request, because the backend re-authenticated each one. That was named as the interim it was.
 * Now the PIN goes to `POST /api/auth/session` exactly once and what comes back is a short-lived
 * token. Nothing in this process can reproduce the PIN afterwards, so a stray log line, a crash
 * dump or a devtools inspection finds a credential that expires in minutes instead of one that
 * unlocks the shop.
 *
 * <h2>The token lives in a closure, not in storage</h2>
 *
 * Not `localStorage`, not `sessionStorage`, not a cookie. A reload signs you out, which is exactly
 * right for a machine that sits on a counter: the back office should not still be open because
 * somebody refreshed the page an hour ago. It costs one PIN entry and removes every path by which
 * a token outlives the person who typed for it.
 *
 * <h2>Refresh is scheduled, not reactive</h2>
 *
 * The token is extended at two thirds of its life rather than retried after a 401. Retrying means
 * every request has a slow path in which it might be sent twice, and "might be sent twice" is a
 * sentence with no good ending on a write endpoint. Scheduling means the token is simply always
 * fresh while the screen is open, and a refresh that fails signs the person out cleanly with a
 * message, in the one place that knows how to say it.
 *
 * <h2>Permissions come from the server and only hide things</h2>
 *
 * {@link BackOfficeSession.permissions} is what the shell greys out. It is never what decides
 * whether an action is allowed — every endpoint re-checks, because a screen that merely looks
 * locked is unlocked to anybody who opens the network tab.
 */
export type Permission =
  | 'SELL'
  | 'RUN_SHIFT'
  | 'MOVE_CASH'
  | 'AUTHORISE_REFUND'
  | 'BACK_OFFICE'
  | 'MANAGE_PRODUCTS'
  | 'MANAGE_STOCK'
  | 'MANAGE_USERS';

export type Role = 'CASHIER' | 'SUPERVISOR' | 'MANAGER' | 'OWNER';

export interface BackOfficeSession {
  id: number;
  code: string;
  displayName: string;
  role: Role;
  permissions: Permission[];
}

export interface BackOffice {
  session: BackOfficeSession | null;
  error: string | null;
  busy: boolean;
  signIn: (code: string, pin: string) => Promise<boolean>;
  signOut: () => void;
  can: (permission: Permission) => boolean;
  /** `fetch`, with the bearer token attached and errors turned into thrown messages. */
  request: <T>(path: string, init?: RequestInit) => Promise<T>;
}

/** What the backend returns from a sign-in or a refresh. */
interface TokenResponse extends BackOfficeSession {
  token: string;
  expiresAt: string;
}

/**
 * How much of the token's life to use before extending it.
 *
 * <p>Two thirds leaves a third of the window for a refresh to fail and be retried before anything
 * the person is doing is interrupted. Refreshing at 95% would be tidier and would mean a single
 * slow request loses the session mid-save.
 */
const REFRESH_AT = 2 / 3;

/** Never sooner than this, so a clock skew or an absurdly short TTL cannot spin. */
const MIN_REFRESH_MS = 5_000;

export function useBackOffice(): BackOffice {
  const [session, setSession] = useState<BackOfficeSession | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // A ref, not state: `request` must read the token that exists *now*, and a token rotated by a
  // refresh must reach a request already in flight from a callback closed over the old render.
  const token = useRef<string | null>(null);
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearRefresh = useCallback(() => {
    if (refreshTimer.current !== null) {
      clearTimeout(refreshTimer.current);
      refreshTimer.current = null;
    }
  }, []);

  const forget = useCallback(() => {
    clearRefresh();
    token.current = null;
    setSession(null);
  }, [clearRefresh]);

  /** Reads a JSON body whether or not there is one, and turns a failure into a thrown message. */
  const read = useCallback(async (response: Response) => {
    const text = await response.text();
    const body = text ? JSON.parse(text) : null;
    if (!response.ok) throw new Error(body?.detail ?? `HTTP ${response.status}`);
    return body;
  }, []);

  const scheduleRefresh = useCallback(
    (expiresAt: string) => {
      clearRefresh();
      const remaining = new Date(expiresAt).getTime() - Date.now();
      if (!Number.isFinite(remaining)) return;
      const delay = Math.max(MIN_REFRESH_MS, remaining * REFRESH_AT);
      refreshTimer.current = setTimeout(() => {
        void (async () => {
          try {
            const body: TokenResponse = await read(
              await fetch('/api/auth/session/refresh', {
                method: 'POST',
                cache: 'no-store',
                headers: { authorization: `Bearer ${token.current ?? ''}` },
              }),
            );
            token.current = body.token;
            // The role is re-read on every refresh, so a demotion made in another window reaches
            // this one within a token's life rather than at the next sign-in.
            setSession(toSession(body));
            scheduleRefresh(body.expiresAt);
          } catch (e) {
            forget();
            setError(
              e instanceof Error
                ? `${e.message} Sign in again.`
                : 'That sign-in has expired. Sign in again.',
            );
          }
        })();
      }, delay);
    },
    [clearRefresh, forget, read],
  );

  const signIn = useCallback(
    async (code: string, pin: string) => {
      setBusy(true);
      setError(null);
      try {
        const body: TokenResponse = await read(
          await fetch('/api/auth/session', {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ code, pin }),
          }),
        );
        token.current = body.token;
        setSession(toSession(body));
        scheduleRefresh(body.expiresAt);
        return true;
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        return false;
      } finally {
        setBusy(false);
      }
    },
    [read, scheduleRefresh],
  );

  /**
   * Signs out. The local state is cleared first and the server is told afterwards, deliberately:
   * the person pressed a button that means "I am done", and a slow or failed request must not
   * leave them looking at a back office they thought they had left. The revocation is
   * fire-and-forget for the same reason, and it is idempotent on the server.
   */
  const signOut = useCallback(() => {
    const dying = token.current;
    forget();
    setError(null);
    if (dying) {
      void fetch('/api/auth/session', {
        method: 'DELETE',
        keepalive: true,
        headers: { authorization: `Bearer ${dying}` },
      }).catch(() => {
        // Nothing useful to do or say: the token is already gone from this process, and it
        // expires on its own within minutes.
      });
    }
  }, [forget]);

  const request = useCallback(
    async <T>(path: string, init: RequestInit = {}): Promise<T> => {
      if (!token.current) throw new Error('Not signed in');
      const response = await fetch(path, {
        ...init,
        cache: 'no-store',
        headers: {
          ...(init.body ? { 'content-type': 'application/json' } : {}),
          ...init.headers,
          authorization: `Bearer ${token.current}`,
        },
      });
      // 204s and empty bodies are normal here — setting a PIN returns nothing.
      return (await read(response)) as T;
    },
    [read],
  );

  // Leaving the back office by any route — Escape, a crash, a navigation — must not leave a timer
  // firing against a token nobody is using.
  useEffect(() => clearRefresh, [clearRefresh]);

  const permissions = useMemo(() => new Set(session?.permissions ?? []), [session]);
  const can = useCallback((permission: Permission) => permissions.has(permission), [permissions]);

  return { session, error, busy, signIn, signOut, can, request };
}

function toSession(body: TokenResponse): BackOfficeSession {
  return {
    id: body.id,
    code: body.code,
    displayName: body.displayName,
    role: body.role,
    permissions: body.permissions,
  };
}
