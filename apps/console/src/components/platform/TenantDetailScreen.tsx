'use client';

import { useState } from 'react';

import { Card, CardGrid, ErrorNote, Row } from '@/components/Chrome';
import { SecretOnce } from '@/components/platform/SecretOnce';
import { platformPost, type FeatureFlag, type Plan, type TenantDetail } from '@/lib/platform';

/**
 * One shop: its licence, its tills, its owners, its flags (M4-08).
 *
 * <p>Every button here is an act with consequences for somebody's business, so each says what it
 * will do rather than only what it is called — "stops the till syncing" beats "revoke". The two
 * genuinely destructive ones ask again before firing, because a mis-tap on a phone should not
 * suspend a shop.
 */
export function TenantDetailScreen({
  token,
  detail,
  plans,
  flags,
  onChanged,
  onBack,
}: {
  token: string;
  detail: TenantDetail;
  plans: Plan[];
  flags: FeatureFlag[];
  onChanged: () => void;
  onBack: () => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [issued, setIssued] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);

  const tenant = detail.tenant;

  async function act(path: string, payload: unknown, after?: (result: unknown) => void) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const result = await platformPost<unknown>(path, token, payload);
      after?.(result);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
      setConfirming(null);
    }
  }

  return (
    <>
      {/* Only below lg. Above it the list is still on screen, so there is nowhere to go back to. */}
      <button
        type="button"
        className="text-ink-3 min-h-[44px] self-start text-sm underline lg:hidden"
        onClick={onBack}
      >
        ← All shops
      </button>

      {error && <ErrorNote>{error}</ErrorNote>}

      <Card title={tenant.name}>
        <Row>
          <span className="text-ink-3 text-sm">Plan</span>
          <span>{tenant.planCode ?? 'none'}</span>
        </Row>
        <Row>
          <span className="text-ink-3 text-sm">Licence</span>
          <span>
            {tenant.licenceExpiresAt
              ? `to ${new Date(tenant.licenceExpiresAt).toLocaleDateString()}`
              : 'lapsed'}
          </span>
        </Row>
        <Row>
          <span className="text-ink-3 text-sm">Sales received</span>
          <span className="font-mono tabular-nums">{tenant.saleCount}</span>
        </Row>
        <Row>
          <span className="text-ink-3 text-sm">Status</span>
          <span>{tenant.active ? 'Active' : 'Suspended'}</span>
        </Row>
      </Card>

      {/* Renewal first, because a lapsed licence is the commonest reason to open this screen. */}
      <CardGrid>
        <RenewCard
          plans={plans}
          busy={busy}
          onRenew={(planCode, days) =>
            act(`/api/platform/tenants/${tenant.id}/licence`, { planCode, days, note: 'renewal' })
          }
        />

        <Card title="Tills">
          {detail.terminals.map((terminal) => (
            <Row key={terminal.id}>
              <span className="flex flex-col">
                <span>{terminal.label}</span>
                <span className="text-ink-3 font-mono text-xs">{terminal.tokenPrefix}…</span>
              </span>
              {terminal.revoked ? (
                <span className="text-ink-3 text-xs">Revoked</span>
              ) : (
                <button
                  type="button"
                  className="text-danger min-h-[44px] text-sm underline"
                  disabled={busy}
                  onClick={() =>
                    confirming === `cred-${terminal.id}`
                      ? act(
                          `/api/platform/tenants/${tenant.id}/credentials/${terminal.id}/revoke`,
                          {},
                        )
                      : setConfirming(`cred-${terminal.id}`)
                  }
                >
                  {confirming === `cred-${terminal.id}` ? 'Really revoke?' : 'Revoke'}
                </button>
              )}
            </Row>
          ))}

          {issued && (
            <SecretOnce
              label="Till token"
              value={issued}
              warning="Put it in the till's LUMORA_CLOUD_TOKEN. It cannot be read back."
            />
          )}

          <button
            type="button"
            className="border-hair text-ink min-h-[44px] rounded border text-sm"
            disabled={busy}
            onClick={() =>
              act(
                `/api/platform/tenants/${tenant.id}/credentials`,
                { label: `Till ${detail.terminals.length + 1}` },
                (result) => setIssued((result as { token: string }).token),
              )
            }
          >
            Issue another till token
          </button>
        </Card>
      </CardGrid>

      <CardGrid>
        <Card title="Owners">
          {detail.owners.map((owner) => (
            <Row key={owner.id}>
              <span className="flex flex-col">
                <span>{owner.displayName}</span>
                <span className="text-ink-3 text-xs">{owner.email}</span>
              </span>
              <button
                type="button"
                className="text-ink-3 min-h-[44px] text-sm underline"
                disabled={busy}
                onClick={() =>
                  act(
                    `/api/platform/tenants/${tenant.id}/owners/${owner.id}/${
                      owner.active ? 'deactivate' : 'activate'
                    }`,
                    {},
                  )
                }
              >
                {owner.active ? 'Deactivate' : 'Reactivate'}
              </button>
            </Row>
          ))}
        </Card>

        <Card title="Licence history" footer="Append-only — a renewal adds a row, never edits one.">
          {detail.licenceHistory.map((licence) => (
            <Row key={licence.id}>
              <span className="text-sm">{licence.planCode}</span>
              <span className="text-ink-3 text-xs">
                {new Date(licence.startsAt).toLocaleDateString()} →{' '}
                {new Date(licence.expiresAt).toLocaleDateString()}
              </span>
            </Row>
          ))}
        </Card>
      </CardGrid>

      <Card
        title="Features"
        footer="A flag set here overrides the plan. Nothing on the till reads these yet — that is M4-09."
      >
        <div className="md:grid md:grid-cols-2 md:gap-x-6">
          {flags.map((flag) => {
            const on = detail.effectiveFlags.includes(flag.code);
            const overridden = detail.overrides.some((o) => o.flagCode === flag.code);
            return (
              <Row key={flag.code}>
                <span className="flex flex-col">
                  <span>{flag.name}</span>
                  <span className="text-ink-3 text-xs">
                    {overridden ? 'overridden for this shop' : 'from the plan'}
                  </span>
                </span>
                <button
                  type="button"
                  className="border-hair min-h-[44px] rounded border px-3 text-sm"
                  disabled={busy}
                  onClick={() =>
                    act(`/api/platform/tenants/${tenant.id}/flags`, {
                      flagCode: flag.code,
                      // Cycles on → off → back to the plan, so an override can always be undone
                      // without needing to remember what the plan said.
                      enabled: overridden ? (on ? false : null) : !on,
                      note: 'set from the estate screen',
                    })
                  }
                >
                  {on ? 'On' : 'Off'}
                </button>
              </Row>
            );
          })}
        </div>
      </Card>

      <Card
        title="Danger"
        footer={
          tenant.active
            ? 'Suspending stops the till syncing and signs the owner out. A lapsed licence stops only the till.'
            : 'Resuming lets the queued outbox drain on the next tick. Nothing was lost.'
        }
      >
        <button
          type="button"
          className={`min-h-[56px] rounded border text-sm ${
            tenant.active ? 'border-danger text-danger' : 'border-hair text-ink'
          }`}
          disabled={busy}
          onClick={() =>
            !tenant.active || confirming === 'suspend'
              ? act(`/api/platform/tenants/${tenant.id}/${tenant.active ? 'suspend' : 'resume'}`, {
                  why: 'set from the estate screen',
                })
              : setConfirming('suspend')
          }
        >
          {!tenant.active
            ? 'Resume this shop'
            : confirming === 'suspend'
              ? 'Really suspend this shop?'
              : 'Suspend this shop'}
        </button>
      </Card>
    </>
  );
}

/** Renewing is its own card because it is the one act with two inputs to get right. */
function RenewCard({
  plans,
  busy,
  onRenew,
}: {
  plans: Plan[];
  busy: boolean;
  onRenew: (planCode: string, days: number) => void;
}) {
  const [planCode, setPlanCode] = useState(plans[0]?.code ?? 'standard');
  const [days, setDays] = useState('30');

  return (
    <Card
      title="Grant or renew"
      footer="Renewing early adds to what is left rather than restarting the clock."
    >
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

      <label className="flex flex-col gap-1">
        <span className="text-ink-3 text-xs uppercase tracking-wider">Days</span>
        <input
          className="border-hair bg-surface text-ink min-h-[56px] rounded border px-3"
          type="number"
          value={days}
          onChange={(e) => setDays(e.target.value)}
        />
      </label>

      <button
        type="button"
        className="bg-accent text-accent-ink min-h-[56px] rounded font-semibold disabled:opacity-60"
        disabled={busy}
        onClick={() => onRenew(planCode, Number(days) || 30)}
      >
        Grant licence
      </button>
    </Card>
  );
}
