'use client';

import { useCallback, useEffect, useState } from 'react';

import { AppShell } from '@/components/AppShell';
import { Card, Empty, ErrorNote, Screen } from '@/components/Chrome';
import { EstateList, EstateTotals } from '@/components/platform/EstateList';
import { NewTenantForm } from '@/components/platform/NewTenantForm';
import { PlatformLogin } from '@/components/platform/PlatformLogin';
import { TenantDetailScreen } from '@/components/platform/TenantDetailScreen';
import {
  forgetPlatformToken,
  platformGet,
  platformLogout,
  PlatformSessionExpiredError,
  storedPlatformToken,
  type AuditEntry,
  type FeatureFlag,
  type Plan,
  type TenantDetail,
  type TenantSummary,
} from '@/lib/platform';

/**
 * The super-admin console (M4-08).
 *
 * <h2>Its own route, not a tab on the owner's screen</h2>
 *
 * The owner console is one page with three tabs and no router. This is deliberately a separate
 * route with a separate login and a separate stored token, because the two are different
 * credentials with wildly different blast radius — see `lib/platform.ts`. The wall is enforced on
 * the server regardless of what this page does: a console session here is a 403, and a staff
 * session on the owner's endpoints is a 403.
 *
 * <h2>This one writes, and the owner's still does not</h2>
 *
 * M4-05's read-only rule is a property of the console session, not of the app, and it is intact.
 * Staff sessions administer businesses — create, licence, suspend. They still cannot operate one:
 * no sale, no stock movement, no refund reaches this credential kind.
 *
 * <h2>Drill-down on a phone, master–detail on a desktop</h2>
 *
 * The estate is a list of shops and a panel about one of them, and the right way to show that
 * depends entirely on how much glass there is. A phone can only do it as a drill-down: tap a shop,
 * the list is replaced, a back link returns. A desktop that did the same would throw away the list
 * every time somebody checked a licence, and comparing two shops would mean navigating four times.
 *
 * <p>So at {@code lg} both panes are on screen at once and selecting a shop swaps only the right
 * one. It is the same two components either way — the switch is a grid and two visibility classes,
 * not a second implementation. The back link exists only below {@code lg}, because above it there
 * is nothing to go back to.
 */
type Tab = 'ESTATE' | 'NEW' | 'AUDIT';

interface Data {
  tenants: TenantSummary[];
  plans: Plan[];
  flags: FeatureFlag[];
  audit: AuditEntry[];
}

export default function AdminPage() {
  const [token, setToken] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const [tab, setTab] = useState<Tab>('ESTATE');
  const [data, setData] = useState<Data | null>(null);
  const [openTenant, setOpenTenant] = useState<TenantDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // localStorage does not exist during the server render, so the first paint cannot know whether
  // anybody is signed in. `ready` stops the login flashing in front of somebody who already is.
  useEffect(() => {
    setToken(storedPlatformToken());
    setReady(true);
  }, []);

  const signOut = useCallback(async () => {
    const current = token;
    setToken(null);
    setData(null);
    setOpenTenant(null);
    if (current) await platformLogout(current);
    else forgetPlatformToken();
  }, [token]);

  const load = useCallback(async (currentToken: string) => {
    setLoading(true);
    setError(null);
    try {
      const [tenants, plans, flags, audit] = await Promise.all([
        platformGet<TenantSummary[]>('/api/platform/tenants', currentToken),
        platformGet<Plan[]>('/api/platform/plans', currentToken),
        platformGet<FeatureFlag[]>('/api/platform/flags', currentToken),
        platformGet<AuditEntry[]>('/api/platform/audit?limit=50', currentToken),
      ]);
      setData({ tenants, plans, flags, audit });
    } catch (e) {
      if (e instanceof PlatformSessionExpiredError) {
        // Revoked, expired, or the account was deactivated. Drop everything rather than leaving an
        // estate on screen that the viewer is no longer entitled to.
        setToken(null);
        setData(null);
        setOpenTenant(null);
        forgetPlatformToken();
        return;
      }
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  const openDetail = useCallback(
    async (tenantId: number) => {
      if (!token) return;
      setError(null);
      try {
        setOpenTenant(await platformGet<TenantDetail>(`/api/platform/tenants/${tenantId}`, token));
      } catch (e) {
        if (e instanceof PlatformSessionExpiredError) {
          setToken(null);
          forgetPlatformToken();
          return;
        }
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [token],
  );

  useEffect(() => {
    if (token) void load(token);
  }, [load, token]);

  if (!ready) return null;

  if (!token) {
    return <PlatformLogin onSignedIn={(session) => setToken(session.token)} />;
  }

  return (
    <AppShell
      brand="StoreX"
      brandSuffix="Estate"
      items={[
        { value: 'ESTATE', label: 'Shops' },
        { value: 'NEW', label: 'New shop' },
        { value: 'AUDIT', label: 'Activity' },
      ]}
      active={tab}
      onSelect={(value) => {
        setTab(value);
        setOpenTenant(null);
      }}
      onSignOut={() => void signOut()}
    >
      <Screen>
        {error && <ErrorNote>{error}</ErrorNote>}
        {loading && data === null && (
          <p className="text-ink-3 py-8 text-center text-sm">Loading…</p>
        )}

        {data && tab === 'ESTATE' && (
          <div className="lg:grid lg:grid-cols-[minmax(0,24rem)_minmax(0,1fr)] lg:items-start lg:gap-6">
            {/* The list. Hidden on a phone once a shop is open; always present at lg. */}
            <div
              className={`flex flex-col gap-4 md:gap-5 ${openTenant ? 'hidden lg:flex' : 'flex'}`}
            >
              <EstateTotals tenants={data.tenants} />
              <EstateList
                tenants={data.tenants}
                selectedId={openTenant?.tenant.id ?? null}
                onOpen={(id) => void openDetail(id)}
              />
            </div>

            {/* The detail. Hidden on a phone until something is chosen. */}
            <div
              className={`mt-4 flex flex-col gap-4 md:gap-5 lg:mt-0 ${
                openTenant ? 'flex' : 'hidden lg:flex'
              }`}
            >
              {openTenant ? (
                <TenantDetailScreen
                  token={token}
                  detail={openTenant}
                  plans={data.plans}
                  flags={data.flags}
                  onChanged={() => {
                    void load(token);
                    void openDetail(openTenant.tenant.id);
                  }}
                  onBack={() => setOpenTenant(null)}
                />
              ) : (
                <Card>
                  <Empty>Choose a shop to see its licence, tills and owners.</Empty>
                </Card>
              )}
            </div>
          </div>
        )}

        {data && tab === 'NEW' && (
          <div className="lg:max-w-2xl">
            <NewTenantForm token={token} plans={data.plans} onCreated={() => void load(token)} />
          </div>
        )}

        {data && tab === 'AUDIT' && <AuditFeed entries={data.audit} />}
      </Screen>
    </AppShell>
  );
}

/**
 * What staff have done, most recent first.
 *
 * <p>Shows the email rather than the display name: two colleagues can share "Estate Staff", and a
 * trail that cannot tell them apart does not answer the question an audit is for.
 */
function AuditFeed({ entries }: { entries: AuditEntry[] }) {
  if (entries.length === 0) {
    return <p className="text-ink-3 py-6 text-center text-sm">Nothing recorded yet.</p>;
  }
  return (
    <section className="bg-surface border-hair flex flex-col gap-3 rounded-lg border p-4">
      <h2 className="text-ink-3 text-xs font-medium uppercase tracking-wider">Activity</h2>
      {entries.map((entry) => (
        <div
          key={entry.id}
          className="border-hair flex flex-col gap-1 border-b pb-2 last:border-b-0"
        >
          <span className="text-sm">
            <span className="font-mono text-xs">{entry.action}</span>
            {entry.tenantName && <span className="text-ink-3"> · {entry.tenantName}</span>}
          </span>
          <span className="text-ink-3 text-xs">
            {entry.actorEmail ?? entry.actor} · {new Date(entry.at).toLocaleString()}
          </span>
        </div>
      ))}
    </section>
  );
}
