'use client';

import { useCallback, useState } from 'react';

import { Card, Empty, ErrorNote, Row } from './Chrome';
import { Money } from './Money';
import { get, post, type CashVariance, type VarianceDetail } from '@/lib/api';

/**
 * The attention feed (M4-07), and the thing to do about it (M6-10).
 *
 * <h2>The screen that makes somebody open the app on a Sunday</h2>
 *
 * Takings are pleasant to look at and rarely actionable. This is the one that pays for the product:
 * <em>something does not add up, look here</em>. Cash variance and stock variance are the same
 * shape of problem, which is why M4-07 puts them on one screen; only the cash half exists so far,
 * and the stock half joins it when there is a stock screen to send somebody to.
 *
 * <h2>Over is not good news</h2>
 *
 * A drawer with more in it than expected reads like luck and usually means a sale nobody rang up.
 * Both directions are flagged, and the direction is written in words rather than left to a colour
 * and a minus sign — §A, and also the difference between a glance that informs and one that
 * misleads.
 *
 * <h2>An alert nobody can clear is wallpaper</h2>
 *
 * For its first release this list counted eleven and offered nothing to do about any of them. It
 * would have counted twelve, then twenty, and an owner would have stopped opening the tab — which
 * kills the screen the console exists for. So a variance opens, shows the count behind it, and can
 * be marked reviewed.
 *
 * <b>Reviewed is not gone.</b> The toggle at the bottom lists what was cleared, with the name of
 * whoever cleared it and when. A list you can empty and never look into again would be a way to
 * hide a shortfall from the person it belongs to, which is the opposite of this screen's job.
 */
export function AttentionScreen({
  variances,
  token,
  onChanged,
}: {
  variances: CashVariance[];
  token: string;
  /** Reloads the page's data after an acknowledgement, so the badge count follows the list. */
  onChanged: () => void;
}) {
  const [reviewed, setReviewed] = useState<CashVariance[] | null>(null);
  const [showReviewed, setShowReviewed] = useState(false);
  const [open, setOpen] = useState<VarianceDetail | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const openVariance = useCallback(
    async (variance: CashVariance) => {
      if (open?.shiftClientUuid === variance.shiftClientUuid) {
        setOpen(null);
        return;
      }
      try {
        setError(null);
        setNote('');
        setOpen(
          await get<VarianceDetail>(`/api/console/attention/${variance.shiftClientUuid}`, token),
        );
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [open, token],
  );

  const acknowledge = useCallback(
    async (shiftClientUuid: string) => {
      setBusy(true);
      setError(null);
      try {
        await post<VarianceDetail>(`/api/console/attention/${shiftClientUuid}/acknowledge`, token, {
          note,
        });
        setOpen(null);
        setNote('');
        // The reviewed list, if it is on screen, is now stale too.
        if (showReviewed) setReviewed(await loadReviewed(token));
        onChanged();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [note, onChanged, showReviewed, token],
  );

  const toggleReviewed = useCallback(async () => {
    if (showReviewed) {
      setShowReviewed(false);
      return;
    }
    try {
      setError(null);
      setReviewed(await loadReviewed(token));
      setShowReviewed(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [showReviewed, token]);

  return (
    <>
      <Card
        title="Needs a look"
        footer="Shifts that closed with the drawer out by more than LKR 100.00, in the last 14 days."
      >
        {error && <ErrorNote>{error}</ErrorNote>}

        {variances.length === 0 ? (
          <Empty>Nothing needs attention. Every shift balanced.</Empty>
        ) : (
          variances.map((variance) => (
            <div key={variance.shiftClientUuid} className="flex flex-col">
              <button
                type="button"
                onClick={() => void openVariance(variance)}
                aria-expanded={open?.shiftClientUuid === variance.shiftClientUuid}
                className="min-h-[44px] w-full text-left"
              >
                <Row>
                  <VarianceWhere variance={variance} />
                  <VarianceHowMuch variance={variance} />
                </Row>
              </button>

              {open?.shiftClientUuid === variance.shiftClientUuid && (
                <VariancePanel
                  detail={open}
                  note={note}
                  onNote={setNote}
                  busy={busy}
                  onAcknowledge={() => void acknowledge(variance.shiftClientUuid)}
                />
              )}
            </div>
          ))
        )}
      </Card>

      <button
        type="button"
        onClick={() => void toggleReviewed()}
        className="text-ink-3 min-h-[44px] self-start text-sm underline"
      >
        {showReviewed ? 'Hide reviewed' : 'Show reviewed'}
      </button>

      {showReviewed && (
        <Card title="Reviewed" footer="Cleared, and kept. Nothing here has been deleted.">
          {reviewed === null || reviewed.length === 0 ? (
            <Empty>Nothing has been reviewed yet.</Empty>
          ) : (
            reviewed.map((variance) => (
              <Row key={variance.shiftClientUuid}>
                <span className="flex flex-col">
                  <span className="font-medium">
                    {variance.branchCode} · {variance.terminalCode}
                  </span>
                  <span className="text-ink-3 text-xs">
                    {shortDate(variance.closedAt)}
                    {variance.acknowledgedBy ? ` · reviewed by ${variance.acknowledgedBy}` : ''}
                  </span>
                </span>
                <VarianceHowMuch variance={variance} />
              </Row>
            ))
          )}
        </Card>
      )}
    </>
  );
}

function VarianceWhere({ variance }: { variance: CashVariance }) {
  return (
    <span className="flex flex-col">
      <span className="font-medium">
        {variance.branchCode} · {variance.terminalCode}
      </span>
      <span className="text-ink-3 text-xs">
        {shortDate(variance.closedAt)}
        {variance.varianceReason ? ` · ${variance.varianceReason}` : ''}
      </span>
    </span>
  );
}

function VarianceHowMuch({ variance }: { variance: CashVariance }) {
  return (
    <span className="flex flex-col items-end">
      <Money
        minor={Math.abs(variance.varianceMinor)}
        className={variance.varianceMinor < 0 ? 'text-danger' : 'text-pending'}
      />
      <span className="text-ink-3 text-xs">{variance.varianceMinor < 0 ? 'short' : 'over'}</span>
    </span>
  );
}

/**
 * What was actually in the drawer.
 *
 * "Rs. 5,000 short" is the alarm; whether it was one note or a hundred coins is the next question,
 * and it decides which conversation the owner has. `shift_counts` has carried the denominations
 * since V203 and nothing has ever shown them.
 */
function VariancePanel({
  detail,
  note,
  onNote,
  busy,
  onAcknowledge,
}: {
  detail: VarianceDetail;
  note: string;
  onNote: (value: string) => void;
  busy: boolean;
  onAcknowledge: () => void;
}) {
  const close = detail.counts.filter((c) => c.phase === 'CLOSE');

  return (
    <div className="border-hair flex flex-col gap-3 border-t px-1 py-3 text-sm">
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1">
        <Fact label="Opening float" minor={detail.openingFloatMinor} />
        <Fact label="Expected" minor={detail.expectedCashMinor} />
        <Fact label="Counted" minor={detail.countedCashMinor} />
        <Fact label="Out by" minor={Math.abs(detail.varianceMinor)} />
      </dl>

      {detail.varianceNote && <p className="text-ink-2">“{detail.varianceNote}”</p>}

      {close.length > 0 && (
        <div className="flex flex-col gap-1">
          <p className="text-ink-3 text-xs uppercase tracking-wider">Counted at close</p>
          <ul className="flex flex-col gap-0.5">
            {close.map((count) => (
              <li key={count.denominationMinor} className="flex justify-between">
                <span className="text-ink-2">
                  <Money minor={count.denominationMinor} /> × {count.qty}
                </span>
                <Money minor={count.denominationMinor * count.qty} />
              </li>
            ))}
          </ul>
        </div>
      )}

      <label className="flex flex-col gap-1">
        <span className="text-ink-3 text-xs uppercase tracking-wider">
          What did you find? (optional)
        </span>
        <input
          type="text"
          value={note}
          onChange={(event) => onNote(event.target.value)}
          placeholder="Counted it again, my mistake"
          className="border-hair text-ink min-h-[44px] rounded border bg-transparent px-3"
        />
      </label>

      <button
        type="button"
        onClick={onAcknowledge}
        disabled={busy}
        className="border-hair text-ink min-h-[44px] self-start rounded border px-4 font-medium disabled:opacity-40"
      >
        {busy ? 'Saving…' : 'Mark reviewed'}
      </button>
      <p className="text-ink-3 text-xs">
        It leaves this list and stays under “Show reviewed”, with your name on it.
      </p>
    </div>
  );
}

function Fact({ label, minor }: { label: string; minor: number }) {
  return (
    <>
      <dt className="text-ink-3">{label}</dt>
      <dd className="text-right">
        <Money minor={minor} />
      </dd>
    </>
  );
}

function loadReviewed(token: string): Promise<CashVariance[]> {
  return get<CashVariance[]>('/api/console/attention?days=14&reviewed=true', token);
}

function shortDate(iso: string): string {
  return new Date(iso).toLocaleDateString([], { day: 'numeric', month: 'short' });
}
