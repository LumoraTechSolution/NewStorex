/**
 * Everything the console knows about the cloud API (M4-05).
 *
 * <h2>The session lives in localStorage, and that is a decision</h2>
 *
 * The alternative is an httpOnly cookie, which is strictly safer against XSS. It is not available
 * here: the console is a static PWA on one origin and the API is a Spring Boot service on another,
 * so a cookie would have to be cross-site — `SameSite=None`, which browsers increasingly refuse in
 * third-party contexts and which no amount of configuration makes reliable on iOS.
 *
 * What makes the trade acceptable is what the token can do. It is read-only by construction: the
 * server refuses a console session on every write path (`AuthenticatedPrincipal`), so a stolen one
 * discloses takings and cannot alter a figure, issue a refund or touch stock. It also expires, and
 * signing out revokes it server-side rather than merely forgetting it here.
 *
 * <h2>401 means the session is gone, not that the request was wrong</h2>
 *
 * Sessions are revocable now — deactivating an owner or suspending a tenant kills a live session on
 * its next request. So a 401 anywhere is the app's cue to drop what it holds and show the login,
 * rather than to retry or to leave a stale screen displaying numbers the viewer is no longer
 * entitled to.
 */

const BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://127.0.0.1:8082';

const TOKEN_KEY = 'lumora.console.token';

export interface Session {
  token: string;
  email: string;
  displayName: string;
  expiresAt: string;
}

/** Thrown when the server says the session is no longer good. Callers sign out on sight. */
export class SessionExpiredError extends Error {
  constructor() {
    super('Your session has ended. Please sign in again.');
  }
}

export function storedToken(): string | null {
  // Wrapped because a browser set to block site data throws on access rather than returning null,
  // and a console that white-screens in a private window is a console nobody trusts.
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function storeToken(token: string): void {
  try {
    window.localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // A session that lives only until the tab closes is worse than one that persists, and far
    // better than a login that appears to fail.
  }
}

export function forgetToken(): void {
  try {
    window.localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* nothing to forget */
  }
}

export async function login(email: string, password: string): Promise<Session> {
  const response = await fetch(`${BASE}/api/console/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const body = await response.json();
  if (!response.ok) {
    // The server deliberately does not say which half was wrong, and neither does this.
    throw new Error(body.detail ?? 'Could not sign in.');
  }
  return body as Session;
}

export async function logout(token: string): Promise<void> {
  // Best-effort. If it fails the token is dropped locally anyway — the worst case is a session row
  // that expires on its own, and the alternative is a sign-out button that can refuse to work.
  try {
    await fetch(`${BASE}/api/console/auth/logout`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    });
  } catch {
    /* offline, or the session was already gone */
  }
  forgetToken();
}

export async function get<T>(path: string, token: string): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (response.status === 401 || response.status === 403) {
    throw new SessionExpiredError();
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.detail ?? `Could not load (HTTP ${response.status})`);
  }
  return (await response.json()) as T;
}

// ----------------------------------------------------------------------------- shapes

export interface Today {
  totalMinor: number;
  saleCount: number;
  lastSyncAt: string | null;
}

export interface DailyTotal {
  day: string;
  totalMinor: number;
  saleCount: number;
}

export interface BranchTotal {
  branchCode: string;
  totalMinor: number;
  saleCount: number;
  lastSyncAt: string | null;
}

export interface CashVariance {
  shiftClientUuid: string;
  branchCode: string;
  terminalCode: string;
  closedAt: string;
  varianceMinor: number;
  varianceReason: string | null;
}

export interface RecentSale {
  invoiceNumber: string;
  branchCode: string;
  terminalCode: string;
  totalMinor: number;
  soldAt: string;
}
