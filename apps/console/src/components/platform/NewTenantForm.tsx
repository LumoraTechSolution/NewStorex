'use client';

import { useState } from 'react';

import { Card, ErrorNote } from '@/components/Chrome';
import { SecretOnce } from '@/components/platform/SecretOnce';
import { platformPost, type NewTenantResult, type Plan } from '@/lib/platform';

/**
 * Signing a shop up (M4-08).
 *
 * <p>This form is the milestone's actual blocker: before it, an owner account could be created only
 * by running Java, so no real shop could sign in.
 *
 * <p>It asks for the owner's password rather than generating one, and that is deliberate. A
 * generated password has to be read out over a phone to somebody who then cannot change it — there
 * is no self-service reset in v1 — so staff and owner would end up sharing a credential neither can
 * rotate. Typed in together during onboarding, it belongs to the owner from the first minute.
 */
export function NewTenantForm({
  token,
  plans,
  onCreated,
}: {
  token: string;
  plans: Plan[];
  onCreated: () => void;
}) {
  const [name, setName] = useState('');
  const [planCode, setPlanCode] = useState('trial');
  const [licenceDays, setLicenceDays] = useState('30');
  const [ownerEmail, setOwnerEmail] = useState('');
  const [ownerName, setOwnerName] = useState('');
  const [ownerPassword, setOwnerPassword] = useState('');
  const [terminalLabel, setTerminalLabel] = useState('Till 1');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<NewTenantResult | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const result = await platformPost<NewTenantResult>('/api/platform/tenants', token, {
        name,
        planCode,
        licenceDays: Number(licenceDays) || 30,
        ownerEmail,
        ownerPassword,
        ownerName,
        terminalLabel,
      });
      setCreated(result);
      onCreated();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  // The token is unrecoverable, so the form is replaced rather than reset — re-rendering the
  // fields underneath would invite closing the panel before the key had been copied anywhere.
  if (created) {
    return (
      <Card title={`${created.name} is ready`}>
        <SecretOnce label="Till token" value={created.tillToken} warning={created.warning} />
        <p className="text-ink-3 text-sm">
          {created.ownerEmail} can sign in to the console now. Licence runs to{' '}
          {new Date(created.licenceExpiresAt).toLocaleDateString()}.
        </p>
        <button
          type="button"
          className="border-hair text-ink min-h-[44px] rounded border text-sm"
          onClick={() => setCreated(null)}
        >
          Add another shop
        </button>
      </Card>
    );
  }

  return (
    <Card title="New shop">
      <form className="flex flex-col gap-3" onSubmit={submit}>
        <Field label="Shop name" value={name} onChange={setName} required />

        <label className="flex flex-col gap-1">
          <span className="text-ink-3 text-xs uppercase tracking-wider">Plan</span>
          <select
            className="border-hair bg-surface text-ink min-h-[56px] rounded border px-3"
            value={planCode}
            onChange={(e) => setPlanCode(e.target.value)}
          >
            {plans.map((plan) => (
              <option key={plan.code} value={plan.code}>
                {plan.name}
              </option>
            ))}
          </select>
        </label>

        <Field
          label="Licence days"
          value={licenceDays}
          onChange={setLicenceDays}
          type="number"
          required
        />

        <Field label="Owner name" value={ownerName} onChange={setOwnerName} required />
        <Field
          label="Owner email"
          value={ownerEmail}
          onChange={setOwnerEmail}
          type="email"
          required
        />
        <Field
          label="Owner password"
          value={ownerPassword}
          onChange={setOwnerPassword}
          type="password"
          hint="At least 12 characters. The owner types this, not you."
          required
        />
        <Field label="Terminal label" value={terminalLabel} onChange={setTerminalLabel} />

        {error && <ErrorNote>{error}</ErrorNote>}

        <button
          type="submit"
          className="bg-accent text-accent-ink min-h-[56px] rounded font-semibold disabled:opacity-60"
          disabled={busy}
        >
          {busy ? 'Creating…' : 'Create shop'}
        </button>
      </form>
    </Card>
  );
}

function Field({
  label,
  value,
  onChange,
  type = 'text',
  hint,
  required,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  hint?: string;
  required?: boolean;
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-ink-3 text-xs uppercase tracking-wider">{label}</span>
      <input
        className="border-hair bg-surface text-ink min-h-[56px] rounded border px-3"
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        autoCapitalize={type === 'email' ? 'none' : undefined}
        required={required}
      />
      {hint && <span className="text-ink-3 text-xs">{hint}</span>}
    </label>
  );
}
