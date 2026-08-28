'use client';

import { Card, Empty, Row } from '@/components/Chrome';
import type { TenantState, TenantSummary } from '@/lib/platform';

/**
 * Every shop on the system, and how each one is doing (M4-08).
 *
 * <p>The row's state comes from the server rather than being recomputed here. It is the same
 * discipline §A applies to money: a figure derived in two places eventually disagrees, and the one
 * on screen is the one somebody acts on.
 *
 * @param selectedId which shop the detail pane is showing. Only meaningful at {@code lg}, where
 *     both panes are visible and a list with no selected row leaves the reader unable to tell which
 *     of forty shops the panel beside it is about. Below {@code lg} the list is not on screen at the
 *     same time as the detail, so it is simply never set to anything visible.
 */
export function EstateList({
  tenants,
  onOpen,
  selectedId = null,
}: {
  tenants: TenantSummary[];
  onOpen: (tenantId: number) => void;
  selectedId?: number | null;
}) {
  if (tenants.length === 0) {
    return <Empty>No shops yet. Create the first one.</Empty>;
  }

  const attention = tenants.filter((t) => t.state !== 'LIVE');

  return (
    <>
      {attention.length > 0 && (
        <Card title="Needs attention">
          {attention.map((tenant) => (
            <Row key={tenant.id}>
              <button
                type="button"
                className="flex flex-1 flex-col items-start text-left"
                onClick={() => onOpen(tenant.id)}
              >
                <span className="font-medium">{tenant.name}</span>
                <StateLabel state={tenant.state} />
              </button>
            </Row>
          ))}
        </Card>
      )}

      <Card title={`Shops (${tenants.length})`}>
        {tenants.map((tenant) => (
          <Row key={tenant.id}>
            <button
              type="button"
              className={`-mx-2 flex flex-1 items-center justify-between gap-3 rounded px-2 text-left lg:min-h-[56px] ${
                tenant.id === selectedId ? 'bg-page lg:ring-accent lg:ring-1' : ''
              }`}
              aria-current={tenant.id === selectedId ? 'true' : undefined}
              onClick={() => onOpen(tenant.id)}
            >
              <span className="flex min-w-0 flex-col">
                <span className="truncate font-medium">{tenant.name}</span>
                <span className="text-ink-3 truncate text-xs">
                  {tenant.planCode ?? 'no plan'} · {tenant.terminalCount} till
                  {tenant.terminalCount === 1 ? '' : 's'} · {lastSync(tenant.lastSyncAt)}
                </span>
              </span>
              <StateLabel state={tenant.state} />
            </button>
          </Row>
        ))}
      </Card>
    </>
  );
}

/**
 * Status as icon plus text, never colour alone — §A.
 *
 * <p>Not decoration here: "suspended" and "lapsed" are the two rows somebody is looking for on this
 * screen, and a red dot is invisible to a share of any population and identical to a grey one in
 * bright sun.
 */
function StateLabel({ state }: { state: TenantState }) {
  const shown = {
    LIVE: { icon: '●', text: 'Live', className: 'text-ok' },
    SUSPENDED: { icon: '■', text: 'Suspended', className: 'text-danger' },
    UNLICENSED: { icon: '▲', text: 'Licence lapsed', className: 'text-pending' },
  }[state];

  return (
    <span className={`flex items-center gap-1 text-xs ${shown.className}`}>
      <span aria-hidden="true">{shown.icon}</span>
      <span>{shown.text}</span>
    </span>
  );
}

/**
 * How long ago a shop last reached the cloud.
 *
 * <p>"Never" is a real and important answer — a shop created but never activated — so it is said
 * plainly rather than shown as a blank.
 */
function lastSync(iso: string | null): string {
  if (!iso) return 'never synced';
  const minutes = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
  if (minutes < 2) return 'syncing now';
  if (minutes < 60) return `synced ${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `synced ${hours}h ago`;
  return `synced ${Math.floor(hours / 24)}d ago`;
}

/**
 * The two counts worth seeing before opening any single shop.
 *
 * <p>Stacked rows on a phone, a pair of figures side by side from {@code md} — at that width two
 * numbers in a row is a summary you take in at a glance, where two stacked rows is a list you read.
 */
export function EstateTotals({ tenants }: { tenants: TenantSummary[] }) {
  const live = tenants.filter((t) => t.state === 'LIVE').length;
  return (
    <Card title="Estate">
      <div className="flex flex-col md:flex-row md:gap-8 lg:flex-col lg:gap-0">
        <div className="border-hair flex min-h-[56px] items-center justify-between gap-3 border-b py-2 md:flex-1 md:flex-col md:items-start md:justify-center md:border-b-0 lg:flex-row lg:items-center lg:justify-between lg:border-b">
          <span className="text-ink-3 text-sm">Shops live</span>
          <span className="font-mono text-lg tabular-nums md:text-2xl lg:text-base">
            {live} / {tenants.length}
          </span>
        </div>
        <div className="flex min-h-[56px] items-center justify-between gap-3 py-2 md:flex-1 md:flex-col md:items-start md:justify-center lg:flex-row lg:items-center lg:justify-between">
          <span className="text-ink-3 text-sm">Sales received</span>
          <span className="font-mono text-lg tabular-nums md:text-2xl lg:text-base">
            {tenants.reduce((sum, t) => sum + t.saleCount, 0)}
          </span>
        </div>
      </div>
    </Card>
  );
}
