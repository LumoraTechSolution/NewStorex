'use client';

import { useEffect, useState } from 'react';

type SyncStatus = {
  online: boolean;
  pending: number;
  oldestPendingAt: string | null;
  lastSuccessAt: string | null;
  lastError: string | null;
};

/**
 * The one component that answers "what happens when the internet goes?"
 *
 * <p>The pending count is a trust feature, not a debug stat. A shopkeeper who can see
 * "12 sales saved locally" understands the offline guarantee better than any amount of
 * reassurance — and a count that stops falling is how they find out something is wrong
 * before their accountant does.
 *
 * Status colour never carries meaning on its own (ROADMAP §A) — every state below pairs a
 * mark with a word. The colours themselves clear AA as text in both till themes since D6;
 * the label is a second mechanism, not a licence for an unreadable one.
 */
export function SyncStatusStrip({ pollMs = 5000 }: { pollMs?: number }) {
  const [status, setStatus] = useState<SyncStatus | null>(null);
  const [reachable, setReachable] = useState(true);

  useEffect(() => {
    let cancelled = false;

    const poll = async () => {
      try {
        const response = await fetch('/api/sync/status', { cache: 'no-store' });
        if (!response.ok) throw new Error(String(response.status));
        const body: SyncStatus = await response.json();
        if (!cancelled) {
          setStatus(body);
          setReachable(true);
        }
      } catch {
        // The local backend itself is unreachable — a different and more serious thing
        // than the cloud being away, and it must not be reported as "offline", which
        // would imply sales are still being saved.
        if (!cancelled) setReachable(false);
      }
    };

    void poll();
    const timer = setInterval(() => void poll(), pollMs);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [pollMs]);

  if (!reachable) {
    return (
      <Strip
        tone="danger"
        icon="!"
        label="TERMINAL OFFLINE"
        detail="Cannot reach the local service"
      />
    );
  }

  if (!status) {
    return <Strip tone="idle" icon="·" label="CHECKING…" />;
  }

  if (status.pending > 0 && !status.online) {
    return (
      <Strip
        tone="pending"
        icon="●"
        label="OFFLINE — sales saving locally"
        detail={`${status.pending} waiting${status.oldestPendingAt ? ` · oldest ${sinceLabel(status.oldestPendingAt)}` : ''}`}
      />
    );
  }

  if (status.pending > 0) {
    return <Strip tone="pending" icon="↑" label={`${status.pending} syncing`} />;
  }

  if (!status.online) {
    return <Strip tone="pending" icon="●" label="OFFLINE" detail="Nothing waiting to sync" />;
  }

  return <Strip tone="ok" icon="●" label="ONLINE" detail="All sales synced" />;
}

function Strip({
  tone,
  icon,
  label,
  detail,
}: {
  tone: 'ok' | 'pending' | 'danger' | 'idle';
  icon: string;
  label: string;
  detail?: string;
}) {
  const colour = {
    ok: 'text-ok',
    pending: 'text-pending',
    danger: 'text-danger',
    idle: 'text-ink-3',
  }[tone];

  return (
    <div
      role="status"
      aria-live="polite"
      className="border-hair flex items-center gap-2 border-b px-4 py-2 text-xs"
    >
      <span aria-hidden="true" className={colour}>
        {icon}
      </span>
      <span className={`font-semibold uppercase tracking-wider ${colour}`}>{label}</span>
      {detail && <span className="text-ink-3">{detail}</span>}
    </div>
  );
}

function sinceLabel(iso: string): string {
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}
