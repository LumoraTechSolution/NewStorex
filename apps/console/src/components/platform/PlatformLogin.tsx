'use client';

import { useCallback, useState } from 'react';

import { LoginFrame } from '@/components/Chrome';
import { platformLogin, storePlatformToken, type PlatformSession } from '@/lib/platform';

/**
 * Staff sign-in (M4-08).
 *
 * <p>Visibly not the owner's login, and that is the point. Somebody arriving here by accident
 * should be able to tell at a glance that this is the wrong door and where the right one is — a
 * form that looks identical to the owner's is one people type owner credentials into, which then
 * fails for reasons the screen cannot explain without leaking which accounts exist.
 */
export function PlatformLogin({ onSignedIn }: { onSignedIn: (session: PlatformSession) => void }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = useCallback(
    async (event: React.FormEvent) => {
      event.preventDefault();
      if (busy) return;

      setBusy(true);
      setError(null);
      try {
        const session = await platformLogin(email, password);
        storePlatformToken(session.token);
        onSignedIn(session);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [busy, email, onSignedIn, password],
  );

  return (
    <LoginFrame>
      <header className="flex flex-col gap-1">
        <p className="text-ink-3 text-xs font-medium uppercase tracking-wider">Staff only</p>
        <h1 className="text-2xl font-semibold">StoreX Estate</h1>
        <p className="text-ink-3 text-sm">
          Shops, plans and licences. Not the owner console — if you run a shop,{' '}
          <a className="underline" href="/">
            sign in here instead
          </a>
          .
        </p>
      </header>

      <form className="flex flex-col gap-4" onSubmit={submit}>
        <label className="flex flex-col gap-1">
          <span className="text-ink-3 text-xs uppercase tracking-wider">Email</span>
          <input
            className="border-hair bg-surface text-ink min-h-[56px] rounded border px-3"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="username"
            autoCapitalize="none"
            required
          />
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-ink-3 text-xs uppercase tracking-wider">Password</span>
          <input
            className="border-hair bg-surface text-ink min-h-[56px] rounded border px-3"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>

        {error && (
          <p className="text-danger flex items-start gap-2 text-sm" role="alert">
            <span aria-hidden="true">⚠</span>
            <span>{error}</span>
          </p>
        )}

        <button
          type="submit"
          className="bg-accent text-accent-ink min-h-[56px] rounded font-semibold disabled:opacity-60"
          disabled={busy}
        >
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </LoginFrame>
  );
}
