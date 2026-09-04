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
 * What makes the trade acceptable is what the token can do. It is read-only by construction with
 * exactly one exception: the server refuses a console session on every write path
 * (`AuthenticatedPrincipal`) except acknowledging a cash variance (M6-10), which touches no money
 * and no ledger. So a stolen one discloses takings, can hide an alert — under its own name, on a
 * shift that stays listed as reviewed — and still cannot alter a figure, issue a refund or touch
 * stock. It also expires, and signing out revokes it server-side rather than merely forgetting it
 * here.
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

/**
 * The console's one write (M6-10).
 *
 * <p>Kept as a separate function rather than a `method` option on `get`, so that every write this
 * app can perform is one grep away. There is exactly one, and the day there is a second, somebody
 * should have to think about it.
 */
export async function post<T>(path: string, token: string, body?: unknown): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (response.status === 401 || response.status === 403) {
    throw new SessionExpiredError();
  }
  if (!response.ok) {
    const failed = await response.json().catch(() => ({}));
    throw new Error(failed.detail ?? `Could not save (HTTP ${response.status})`);
  }
  return (await response.json()) as T;
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
  /** Null until somebody said they had looked at it (M6-10). */
  acknowledgedAt: string | null;
  acknowledgedBy: string | null;
}

export interface CountedDenomination {
  phase: string;
  denominationMinor: number;
  qty: number;
}

/** One variance in full, including the denominations behind the count. */
export interface VarianceDetail {
  shiftClientUuid: string;
  branchCode: string;
  terminalCode: string;
  openedAt: string;
  closedAt: string;
  openingFloatMinor: number;
  countedCashMinor: number;
  expectedCashMinor: number;
  varianceMinor: number;
  varianceReason: string | null;
  varianceNote: string | null;
  counts: CountedDenomination[];
  acknowledgedAt: string | null;
  acknowledgedBy: string | null;
  acknowledgementNote: string | null;
}

/**
 * A product and what is on the shelf (M6-12).
 *
 * `onHand` is Σ movements and can be negative — a sale rung up for stock the shop never recorded
 * receiving. `reorderPoint` is null when the product is not watched, which is a different fact from
 * a threshold of 0 meaning "tell me when it is empty".
 */
export interface StockLine {
  productClientUuid: string;
  sku: string;
  name: string;
  category: string | null;
  onHand: number;
  reorderPoint: number | null;
  priceMinor: number;
}

/**
 * Who was on a till on one day, and what they took (M6-13).
 *
 * `operator` is null for a shift closed before the till started sending it — the cloud was never
 * told, and never will be, because a closed shift is not redelivered. The row is still listed, so a
 * day's takings here always add up to the day's takings on the card above it.
 */
export interface OperatorDay {
  operatorClientUuid: string | null;
  operator: string | null;
  shiftCount: number;
  saleCount: number;
  totalMinor: number;
  varianceMinor: number;
  /**
   * True while one of this person's shifts today is still open (M6-14).
   *
   * <p>The whole of the console's first line rests on this: <em>Open · Nimal since 9:04</em>. It is
   * derived on the cloud from a shift that has not been delivered closed, so a till that stopped
   * syncing mid-shift reads as open — which is why the sentence is shown beside the sync time and
   * never on its own.
   */
  onNow: boolean;
  /** When this person first opened a till today. Null for a shift the cloud was never told about. */
  openedAt: string | null;
}

/**
 * One quarter hour of the trading day, and what a normal one of this weekday does in it (M6-14).
 *
 * <p>`usualSaleCount` and `usualTotalMinor` are averages over the same weekday in the four weeks
 * behind today, counted only over the days the shop actually traded. Both are fractions on purpose:
 * rounding them would flatten the quiet half of a day to zero and make a slow morning look like a
 * closed one.
 */
export interface PulseSlot {
  /** The start of the slot in the shop's own clock, minutes from midnight. */
  minuteOfDay: number;
  saleCount: number;
  totalMinor: number;
  usualSaleCount: number;
  usualTotalMinor: number;
}

export interface RecentSale {
  invoiceNumber: string;
  branchCode: string;
  terminalCode: string;
  totalMinor: number;
  soldAt: string;
}
