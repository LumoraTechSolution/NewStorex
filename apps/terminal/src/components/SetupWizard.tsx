'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * The first-run wizard (M5-03).
 *
 * <h2>What it replaces</h2>
 *
 * Until this existed, activating a till meant an elevated command prompt and two `setx /M`
 * commands with a 47-character token pasted into them — the procedure `DEPLOYMENT.md` describes
 * and calls "the honest interim". Its failure mode is the bad kind: mistype the token and the
 * till sells perfectly, prints perfectly, and silently never syncs, which is discovered weeks
 * later when the owner's console shows less money than the drawer did.
 *
 * <h2>Four steps, and the order is the argument</h2>
 *
 * Shop, then till, then owner, then cloud. Each step is answerable by the person standing at the
 * counter without leaving the screen to find something out, and the one step that needs a thing
 * from elsewhere — the token — is last, so a shop that has not got one yet still finishes setup
 * with a working till. That ordering is the whole reason the cloud step can be skipped: a till
 * with no token queues its outbox and loses nothing, so "not connected yet" is a state, not a
 * failure.
 *
 * <h2>Keyboard first, like everything else here</h2>
 *
 * Gate M1 says a cashier completes twenty sales without touching a mouse, and a wizard that
 * demands one on the first screen would be a strange exception to that. Enter advances, Escape
 * goes back, and the first field of each step takes focus when it appears.
 */

type Step = 'shop' | 'till' | 'owner' | 'cloud' | 'done';

const STEPS: readonly Step[] = ['shop', 'till', 'owner', 'cloud'];

interface Draft {
  shopName: string;
  shopAddress: string;
  branchCode: string;
  branchName: string;
  terminalCode: string;
  supplierTin: string;
  supplierRegisteredName: string;
  supplierAddress: string;
  ownerCode: string;
  ownerName: string;
  ownerPin: string;
  ownerPinAgain: string;
  cloudUrl: string;
  cloudToken: string;
}

const EMPTY: Draft = {
  shopName: '',
  shopAddress: '',
  branchCode: '',
  branchName: '',
  // 'T1' is the only prefilled field on the form, and it is prefilled because it is right for
  // every first till and this is by definition the first. The second till in a shop is set up by
  // somebody who has already been told it must differ — the field is editable and the step says
  // why in the text beneath it.
  terminalCode: 'T1',
  supplierTin: '',
  supplierRegisteredName: '',
  supplierAddress: '',
  ownerCode: 'OWNER',
  ownerName: '',
  ownerPin: '',
  ownerPinAgain: '',
  cloudUrl: '',
  cloudToken: '',
};

export function SetupWizard({ onComplete }: { onComplete: () => void }) {
  const [step, setStep] = useState<Step>('shop');
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [cloudNote, setCloudNote] = useState<string | null>(null);
  const firstFieldRef = useRef<HTMLInputElement>(null);

  const set = useCallback((field: keyof Draft, value: string) => {
    setDraft((current) => ({ ...current, [field]: value }));
    setError(null);
  }, []);

  // The first field of each step takes focus as the step appears, so the whole wizard can be
  // typed without reaching for anything. Runs on `step` alone: re-focusing on every keystroke
  // would fight the person using it.
  useEffect(() => {
    firstFieldRef.current?.focus();
  }, [step]);

  /** What is wrong with the current step, or null. Checked here so Next is honest about being
   * disabled, and again on the server, which is the one that decides. */
  function problemWith(current: Step): string | null {
    if (current === 'shop') {
      if (!draft.shopName.trim())
        return 'The shop needs a name — it prints at the top of every receipt.';
      return null;
    }
    if (current === 'till') {
      if (!draft.branchCode.trim()) return 'The branch needs a code.';
      if (!draft.branchName.trim()) return 'The branch needs a name.';
      if (!draft.terminalCode.trim()) return 'This till needs a code.';
      if (!/^[A-Za-z0-9]+$/.test(draft.branchCode.trim()))
        return 'The branch code can only use letters and digits — it forms part of every invoice number.';
      if (!/^[A-Za-z0-9]+$/.test(draft.terminalCode.trim()))
        return 'The till code can only use letters and digits — it forms part of every invoice number.';
      return null;
    }
    if (current === 'cloud') {
      // The pair, not the fields. Either both or neither: a token with no address is the failure
      // this whole wizard exists to prevent — the till falls back to its own loopback, sells
      // perfectly, and queues sales that are never going anywhere. An address with no token is
      // merely useless, and is refused for symmetry rather than danger.
      const hasUrl = draft.cloudUrl.trim().length > 0;
      const hasToken = draft.cloudToken.trim().length > 0;
      if (hasToken && !hasUrl) {
        return 'A token needs a cloud address to send to — otherwise the till keeps every sale and sends none of them.';
      }
      if (hasUrl && !hasToken) {
        return 'Add the till token as well, or clear the address and connect later.';
      }
      if (hasUrl && !/^https?:\/\//i.test(draft.cloudUrl.trim())) {
        return 'The cloud address must start with https://.';
      }
      return null;
    }
    if (current === 'owner') {
      if (!draft.ownerCode.trim()) return 'The owner needs a sign-in code.';
      if (!draft.ownerName.trim()) return 'The owner needs a name.';
      if (draft.ownerPin.length < 4) return 'The PIN must be at least 4 digits.';
      if (draft.ownerPin !== draft.ownerPinAgain) return 'The two PINs do not match.';
      return null;
    }
    return null;
  }

  const currentProblem = problemWith(step);

  /**
   * Asks the backend whether the cloud recognises this token.
   *
   * Run before the shop is created, not after, and that ordering is the whole point: a token the
   * cloud rejects is fixable while the person is still on this screen with the platform page open
   * in front of them. Told afterwards, they have a finished shop and a message about a field they
   * have already moved past.
   *
   * A cloud that cannot be reached is **not** a failure here. The till is designed to sell with no
   * network at all, and a shop being set up in a back room with no signal is being set up
   * correctly — so an unreachable cloud saves the token and says so plainly.
   */
  async function verifyCloud(): Promise<{ blocked: boolean; note: string | null }> {
    if (!draft.cloudToken.trim()) {
      return { blocked: false, note: null };
    }
    try {
      const response = await fetch('/api/setup/cloud-check', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          cloudUrl: draft.cloudUrl.trim(),
          token: draft.cloudToken.trim(),
        }),
      });
      const result = (await response.json()) as {
        ok: boolean;
        reachable: boolean;
        shopName: string | null;
        detail: string | null;
      };

      if (result.ok) {
        // The shop name the cloud answered with, shown back rather than a green tick. Seeing
        // "jeewa stores" is what confirms the token; seeing another shop's name is the mistake
        // caught before a day of sales lands in somebody else's ledger.
        return {
          blocked: false,
          note: result.shopName
            ? 'The cloud recognises this token as "' + result.shopName + '".'
            : 'The cloud recognises this token.',
        };
      }
      if (!result.reachable) {
        return {
          blocked: false,
          note:
            (result.detail ?? 'The cloud could not be reached.') +
            ' The token is saved and will be used once the connection is back — nothing is lost in the meantime.',
        };
      }
      // The cloud answered and said no. This is the one case worth stopping for.
      return { blocked: true, note: result.detail ?? 'The cloud does not recognise that token.' };
    } catch {
      return {
        blocked: false,
        note: 'The token could not be checked from here. It is saved and will be used once the till can reach the cloud.',
      };
    }
  }

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const checked = await verifyCloud();
      if (checked.blocked) {
        // Nothing has been created yet, so this is a field to correct rather than a state to
        // recover from.
        setError(checked.note);
        setBusy(false);
        return;
      }

      const response = await fetch('/api/setup/shop', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          shopName: draft.shopName,
          branchCode: draft.branchCode,
          branchName: draft.branchName,
          terminalCode: draft.terminalCode,
          shopAddress: draft.shopAddress,
          supplierTin: draft.supplierTin,
          supplierRegisteredName: draft.supplierRegisteredName,
          supplierAddress: draft.supplierAddress,
          ownerCode: draft.ownerCode,
          ownerName: draft.ownerName,
          ownerPin: draft.ownerPin,
        }),
      });
      if (!response.ok) {
        const body = (await response.json().catch(() => ({}))) as { detail?: string };
        throw new Error(body.detail ?? `Setup failed (HTTP ${response.status})`);
      }

      // The shop exists from here on. Everything below is best-effort by design: the token is a
      // convenience the till can be given later, and nothing about it may undo a shop that was
      // just created successfully.
      if (draft.cloudToken.trim()) {
        const saved = await window.lumora?.setup.saveCloudCredential({
          token: draft.cloudToken.trim(),
          url: draft.cloudUrl.trim() || null,
        });
        if (!saved) {
          setCloudNote(
            'The shop is set up. The cloud token could not be saved because this is running ' +
              'outside the desktop app — set it from the installed till.',
          );
        } else if (!saved.ok) {
          setCloudNote(`The shop is set up. The cloud token could not be saved: ${saved.error}`);
        } else {
          // The verification's own words first — naming the shop the cloud recognised is the part
          // worth reading — then the restart, which is a real constraint rather than a formality:
          // HttpCloudSyncClient bakes the token into its RestClient at construction.
          setCloudNote(
            (checked.note ? checked.note + ' ' : '') +
              'Saved. This till connects to the cloud the next time StoreX starts — until then ' +
              'sales are recorded locally and queued, and nothing is lost.',
          );
        }
      } else {
        setCloudNote(
          'No token yet, which is fine. The till sells and keeps every sale queued; add the ' +
            'token later and the backlog goes up on its own.',
        );
      }
      setStep('done');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  function next() {
    const problem = problemWith(step);
    if (problem) {
      setError(problem);
      return;
    }
    // `noUncheckedIndexedAccess` makes the lookup `Step | undefined`, and the guard is the honest
    // answer rather than a cast: past the last step there is no next one, there is a submit.
    const following = STEPS[STEPS.indexOf(step) + 1];
    if (!following) {
      void submit();
      return;
    }
    setStep(following);
  }

  function back() {
    const preceding = STEPS[STEPS.indexOf(step) - 1];
    if (preceding) setStep(preceding);
  }

  function onKeyDown(event: React.KeyboardEvent) {
    if (busy || step === 'done') return;
    if (event.key === 'Enter') {
      event.preventDefault();
      next();
    } else if (event.key === 'Escape') {
      event.preventDefault();
      back();
    }
  }

  if (step === 'done') {
    return (
      <main className="bg-page text-ink flex min-h-screen items-center justify-center p-8">
        <div className="w-full max-w-xl">
          <h1 className="text-ink text-3xl font-semibold">{draft.shopName} is ready</h1>
          <p className="text-ink-2 mt-3 text-lg">
            This till is{' '}
            <strong className="text-ink">
              {draft.branchCode.toUpperCase()}-{draft.terminalCode.toUpperCase()}
            </strong>
            . Its invoice numbers start at 1 and belong to it alone.
          </p>
          {cloudNote ? <p className="text-ink-2 mt-4 text-base">{cloudNote}</p> : null}
          <button
            type="button"
            onClick={onComplete}
            className="bg-accent text-on-accent min-h-touch mt-8 w-full rounded-lg px-6 text-lg font-semibold"
          >
            Sign in
          </button>
        </div>
      </main>
    );
  }

  return (
    <main
      className="bg-page text-ink flex min-h-screen items-center justify-center p-8"
      onKeyDown={onKeyDown}
    >
      <div className="w-full max-w-xl">
        <p className="text-ink-3 text-sm uppercase tracking-wide">
          Step {STEPS.indexOf(step) + 1} of {STEPS.length}
        </p>

        {step === 'shop' ? (
          <>
            <h1 className="mt-2 text-3xl font-semibold">What is this shop called?</h1>
            <p className="text-ink-2 mt-2">It prints at the top of every receipt.</p>
            <Field
              label="Shop name"
              value={draft.shopName}
              onChange={(v) => set('shopName', v)}
              inputRef={firstFieldRef}
            />
            <Field
              label="Address (optional)"
              value={draft.shopAddress}
              onChange={(v) => set('shopAddress', v)}
              hint="Printed under the name. You can add this later."
            />
          </>
        ) : null}

        {step === 'till' ? (
          <>
            <h1 className="mt-2 text-3xl font-semibold">Which branch, and which till?</h1>
            <p className="text-ink-2 mt-2">
              These two codes make up every invoice number this till issues — like{' '}
              <span className="text-ink font-mono">
                {(draft.branchCode || 'KND').toUpperCase()}-
                {(draft.terminalCode || 'T1').toUpperCase()}-000001
              </span>
              .
            </p>
            <Field
              label="Branch code"
              value={draft.branchCode}
              onChange={(v) => set('branchCode', v)}
              inputRef={firstFieldRef}
              hint="Short, letters and digits — e.g. KND."
            />
            <Field
              label="Branch name"
              value={draft.branchName}
              onChange={(v) => set('branchName', v)}
              hint="What people call it — e.g. Kandy Main."
            />
            <Field
              label="Till code"
              value={draft.terminalCode}
              onChange={(v) => set('terminalCode', v)}
              hint="If this shop has a second till, it must have a different code. Two tills sharing one code issue the same invoice numbers."
            />
          </>
        ) : null}

        {step === 'owner' ? (
          <>
            <h1 className="mt-2 text-3xl font-semibold">Who runs this shop?</h1>
            <p className="text-ink-2 mt-2">
              This account can do everything, including adding everyone else. You can add cashiers
              from the back office afterwards.
            </p>
            <Field
              label="Sign-in code"
              value={draft.ownerCode}
              onChange={(v) => set('ownerCode', v)}
              inputRef={firstFieldRef}
              hint="Typed at the sign-in screen — short and memorable."
            />
            <Field label="Name" value={draft.ownerName} onChange={(v) => set('ownerName', v)} />
            <Field
              label="PIN"
              value={draft.ownerPin}
              onChange={(v) => set('ownerPin', v)}
              type="password"
              inputMode="numeric"
              hint="At least 4 digits."
            />
            <Field
              label="PIN again"
              value={draft.ownerPinAgain}
              onChange={(v) => set('ownerPinAgain', v)}
              type="password"
              inputMode="numeric"
            />
          </>
        ) : null}

        {step === 'cloud' ? (
          <>
            <h1 className="mt-2 text-3xl font-semibold">Connect to the cloud?</h1>
            <p className="text-ink-2 mt-2">
              This is what lets the owner see takings from a phone. You can skip it — the till sells
              either way, and every sale is kept and sent when a token is added.
            </p>
            <Field
              label="Cloud address"
              value={draft.cloudUrl}
              onChange={(v) => set('cloudUrl', v)}
              inputRef={firstFieldRef}
              hint="The https:// address you were given with the token. Both go together — a token with no address sends nothing."
            />
            <Field
              label="Till token"
              value={draft.cloudToken}
              onChange={(v) => set('cloudToken', v)}
              hint="Starts with lum_. Shown once when the shop was created — if it has been lost, a new one can be issued."
            />
            <p className="text-ink-3 mt-4 text-sm">
              These are checked against the cloud before anything is saved, and it will tell you
              which shop the token belongs to.
            </p>
          </>
        ) : null}

        {error ? (
          <p role="alert" className="text-danger mt-6 text-base">
            {error}
          </p>
        ) : null}

        <div className="mt-8 flex gap-3">
          {STEPS.indexOf(step) > 0 ? (
            <button
              type="button"
              onClick={back}
              disabled={busy}
              className="border-hair text-ink min-h-touch flex-1 rounded-lg border px-6 text-lg"
            >
              Back
            </button>
          ) : null}
          <button
            type="button"
            onClick={next}
            disabled={busy || currentProblem !== null}
            className="bg-accent text-on-accent min-h-touch flex-[2] rounded-lg px-6 text-lg font-semibold disabled:opacity-50"
          >
            {busy ? 'Setting up…' : STEPS.indexOf(step) === STEPS.length - 1 ? 'Finish' : 'Next'}
          </button>
        </div>
      </div>
    </main>
  );
}

function Field({
  label,
  value,
  onChange,
  hint,
  type = 'text',
  inputMode,
  inputRef,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  hint?: string;
  type?: 'text' | 'password';
  inputMode?: 'numeric';
  inputRef?: React.RefObject<HTMLInputElement>;
}) {
  return (
    <label className="mt-6 block">
      <span className="text-ink-2 block text-sm">{label}</span>
      <input
        ref={inputRef}
        type={type}
        inputMode={inputMode}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="border-hair bg-surface text-ink min-h-touch focus:border-accent mt-1 w-full rounded-lg border px-4 text-lg outline-none"
      />
      {hint ? <span className="text-ink-3 mt-1 block text-sm">{hint}</span> : null}
    </label>
  );
}
