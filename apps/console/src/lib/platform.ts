/**
 * Everything the console knows about the super-admin API (M4-08).
 *
 * <h2>A separate module, and a separate stored token</h2>
 *
 * This deliberately does not reuse `lib/api.ts`'s token. The two credentials are not the same
 * thing and must not be able to stand in for each other: an owner's session reads one shop, a staff
 * session can licence, suspend and re-key every shop on the system. Sharing one key in
 * `localStorage` would mean signing in as staff on a shared laptop silently replaced whatever
 * session was there, and a bug in either screen could send the wrong token to the wrong API.
 *
 * <p>So: its own key, its own login, its own route. The server enforces the same wall from the
 * other side — a console session on `/api/platform` is a 403 and a platform session on
 * `/api/console` is a 403 — so this separation is the convenient half of a rule that does not
 * depend on the browser honouring it.
 *
 * <h2>This one writes, and that is the exception to the console's read-only rule</h2>
 *
 * M4-05 made the owner console read-only in the wire, not just the UI. That rule is intact: it is a
 * property of the *console session*, which the server refuses on every write path. Staff sessions
 * are a different credential kind and administering a business — creating it, licensing it — is
 * inherently a write. What staff still cannot do is operate a shop: no sale, no stock movement, no
 * refund. See `PlatformController`.
 */

const BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://127.0.0.1:8082';

const TOKEN_KEY = 'lumora.platform.token';

export class PlatformSessionExpiredError extends Error {
  constructor() {
    super('Your staff session has ended. Please sign in again.');
  }
}

export function storedPlatformToken(): string | null {
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function storePlatformToken(token: string): void {
  try {
    window.localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // A session lasting only until the tab closes beats a login that appears to fail.
  }
}

export function forgetPlatformToken(): void {
  try {
    window.localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* nothing to forget */
  }
}

async function parse(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    return { detail: text };
  }
}

function detailOf(body: unknown, fallback: string): string {
  if (body && typeof body === 'object' && 'detail' in body) {
    const detail = (body as { detail?: unknown }).detail;
    if (typeof detail === 'string' && detail.length > 0) return detail;
  }
  return fallback;
}

export async function platformLogin(email: string, password: string): Promise<PlatformSession> {
  const response = await fetch(`${BASE}/api/platform/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const body = await parse(response);
  if (!response.ok) {
    // The server does not say which half was wrong, and neither does this.
    throw new Error(detailOf(body, 'Could not sign in.'));
  }
  return body as PlatformSession;
}

export async function platformLogout(token: string): Promise<void> {
  try {
    await fetch(`${BASE}/api/platform/auth/logout`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    });
  } catch {
    /* offline, or already gone */
  }
  forgetPlatformToken();
}

export async function platformGet<T>(path: string, token: string): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (response.status === 401 || response.status === 403) {
    throw new PlatformSessionExpiredError();
  }
  const body = await parse(response);
  if (!response.ok) {
    throw new Error(detailOf(body, `Could not load (HTTP ${response.status})`));
  }
  return body as T;
}

export async function platformPost<T>(path: string, token: string, payload: unknown): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(payload ?? {}),
  });
  if (response.status === 401) {
    throw new PlatformSessionExpiredError();
  }
  const body = await parse(response);
  if (!response.ok) {
    // 422 carries a message written for the person at the screen — show it rather than a status.
    throw new Error(detailOf(body, `That did not work (HTTP ${response.status})`));
  }
  return body as T;
}

// ----------------------------------------------------------------------------- shapes

export interface PlatformSession {
  token: string;
  email: string;
  displayName: string;
  expiresAt: string;
}

export interface Plan {
  id: number;
  code: string;
  name: string;
  description: string;
  priceMinor: number;
  maxTerminals: number | null;
  maxUsers: number | null;
  active: boolean;
  flags: string[];
}

export interface FeatureFlag {
  code: string;
  name: string;
  description: string;
}

/** `state` is derived on the server so the estate list and this screen cannot disagree about it. */
export type TenantState = 'LIVE' | 'SUSPENDED' | 'UNLICENSED';

export interface TenantSummary {
  id: number;
  clientUuid: string;
  name: string;
  active: boolean;
  planCode: string | null;
  licenceExpiresAt: string | null;
  lastSyncAt: string | null;
  saleCount: number;
  ownerCount: number;
  terminalCount: number;
  createdAt: string;
  state: TenantState;
}

export interface Terminal {
  id: number;
  label: string;
  tokenPrefix: string;
  lastSeenAt: string | null;
  revoked: boolean;
}

export interface Owner {
  id: number;
  email: string;
  displayName: string;
  active: boolean;
  lastLoginAt: string | null;
}

export interface Licence {
  id: number;
  planCode: string;
  startsAt: string;
  expiresAt: string;
  note: string;
}

export interface FlagOverride {
  flagCode: string;
  enabled: boolean;
  note: string;
  setBy: string | null;
}

export interface TenantDetail {
  tenant: TenantSummary;
  terminals: Terminal[];
  owners: Owner[];
  licenceHistory: Licence[];
  overrides: FlagOverride[];
  effectiveFlags: string[];
}

export interface AuditEntry {
  id: number;
  action: string;
  tenantId: number | null;
  tenantName: string | null;
  detail: string;
  actor: string;
  actorEmail: string | null;
  at: string;
}

export interface NewTenantResult {
  tenantId: number;
  clientUuid: string;
  name: string;
  ownerEmail: string;
  tillToken: string;
  licenceExpiresAt: string;
  warning: string;
}
