'use client';

import { useCallback, useEffect, useState } from 'react';

import { AppShell } from '@/components/AppShell';
import { AttentionScreen } from '@/components/AttentionScreen';
import { ErrorNote, Screen } from '@/components/Chrome';
import { LoginScreen } from '@/components/LoginScreen';
import { TodayScreen } from '@/components/TodayScreen';
import { TrendScreen } from '@/components/TrendScreen';
import {
  forgetToken,
  get,
  logout,
  SessionExpiredError,
  storedToken,
  type BranchTotal,
  type CashVariance,
  type DailyTotal,
  type RecentSale,
  type Today,
} from '@/lib/api';

/**
 * The owner console (M4-05 … M4-07).
 *
 * <h2>One page, three sections, no router</h2>
 *
 * All four requests are cheap and the whole dataset is a few kilobytes, so everything loads at once
 * and the section is local state. Routing would buy back-button behaviour and cost a loading spinner
 * on every press — the wrong trade for a screen somebody checks for fifteen seconds between other
 * things.
 *
 * <h2>Three shapes: phone, tablet, desktop</h2>
 *
 * {@code AppShell} moves the navigation from a tab strip to a sidebar at {@code lg}, and the screens
 * themselves re-flow into columns — see {@code CardGrid}. What does <em>not</em> change is which
 * figures are on screen: a desktop layout that reveals numbers a phone hides would make the two
 * disagree about what the shop took today, which is the one thing this app exists to say.
 *
 * <h2>The read-only rule is in the wire, not just the UI</h2>
 *
 * There is no form here because the server would refuse one anyway: a console session is rejected
 * on every write path. That is deliberate belt and braces — a read-only UI is a promise, and a
 * credential that cannot write is a fact.
 */
type Tab = 'TODAY' | 'TREND' | 'ATTENTION';

interface Data {
  today: Today;
  trend: DailyTotal[];
  branches: BranchTotal[];
  attention: CashVariance[];
  recent: RecentSale[];
}

export default function Page() {
  const [token, setToken] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const [tab, setTab] = useState<Tab>('TODAY');
  const [data, setData] = useState<Data | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // localStorage is not available during the server render, so the first paint cannot know whether
  // anybody is signed in. `ready` keeps the login form from flashing up in front of somebody who
  // already is.
  useEffect(() => {
    setToken(storedToken());
    setReady(true);
  }, []);

  const signOut = useCallback(async () => {
    const current = token;
    setToken(null);
    setData(null);
    if (current) await logout(current);
    else forgetToken();
  }, [token]);

  const load = useCallback(async (currentToken: string) => {
    setLoading(true);
    setError(null);
    try {
      const [today, trend, branches, attention, recent] = await Promise.all([
        get<Today>('/api/console/today', currentToken),
        get<DailyTotal[]>('/api/console/trend?days=14', currentToken),
        get<BranchTotal[]>('/api/console/branches', currentToken),
        get<CashVariance[]>('/api/console/attention?days=14', currentToken),
        get<RecentSale[]>('/api/console/recent-sales?limit=20', currentToken),
      ]);
      setData({ today, trend, branches, attention, recent });
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        // The session was revoked or expired server-side. Drop everything rather than leaving
        // figures on screen that the viewer is no longer entitled to see.
        setToken(null);
        setData(null);
        forgetToken();
        return;
      }
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (token) void load(token);
  }, [load, token]);

  if (!ready) return null;

  if (!token) {
    return <LoginScreen onSignedIn={(session) => setToken(session.token)} />;
  }

  return (
    <AppShell
      brand="StoreX"
      items={[
        { value: 'TODAY', label: 'Today' },
        { value: 'TREND', label: 'Trend' },
        { value: 'ATTENTION', label: 'Attention', badge: data?.attention.length },
      ]}
      active={tab}
      onSelect={setTab}
      onSignOut={() => void signOut()}
    >
      <Screen>
        {error && <ErrorNote>{error}</ErrorNote>}
        {loading && data === null && (
          <p className="text-ink-3 py-8 text-center text-sm">Loading…</p>
        )}

        {data && tab === 'TODAY' && (
          <TodayScreen today={data.today} branches={data.branches} recent={data.recent} />
        )}
        {data && tab === 'TREND' && <TrendScreen trend={data.trend} />}
        {data && tab === 'ATTENTION' && <AttentionScreen variances={data.attention} />}

        {data && (
          <button
            type="button"
            className="text-ink-3 min-h-[44px] self-start text-sm underline"
            onClick={() => token && void load(token)}
            disabled={loading}
          >
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        )}
      </Screen>
    </AppShell>
  );
}
