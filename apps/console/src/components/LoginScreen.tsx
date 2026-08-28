'use client';

import { useCallback, useState } from 'react';

import { LoginFrame } from '@/components/Chrome';
import { login, storeToken, type Session } from '@/lib/api';

/**
 * Signing in (M4-05).
 *
 * <p>One message for every kind of failure, matching what the server does. "No account with that
 * email" would be helpful to the owner exactly once and useful to anyone holding a list of
 * addresses every day after that.
 */
export function LoginScreen({ onSignedIn }: { onSignedIn: (session: Session) => void }) {
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
        const session = await login(email, password);
        storeToken(session.token);
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
        <h1 className="text-2xl font-semibold">StoreX Console</h1>
        <p className="text-ink-3 text-sm">Your shop, from anywhere.</p>
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

      <p className="text-ink-3 text-center text-xs">
        This console is read-only. Nothing here can change what the shop recorded.
      </p>
    </LoginFrame>
  );
}
