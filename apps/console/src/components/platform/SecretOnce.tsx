'use client';

import { useState } from 'react';

/**
 * A secret the server will never show again (M4-08).
 *
 * <p>Till tokens and generated passwords exist for exactly one render. The screen has to say so
 * loudly, because the failure is silent and expensive: somebody closes the panel, and the only way
 * back is to issue a new key and re-key the machine.
 *
 * <p>Copying is offered but never assumed to have worked — `navigator.clipboard` is unavailable on
 * an insecure origin and can be refused outright, so the value stays selectable on screen and the
 * button reports what actually happened rather than always claiming success.
 */
export function SecretOnce({
  label,
  value,
  warning,
}: {
  label: string;
  value: string;
  warning: string;
}) {
  const [copied, setCopied] = useState<'idle' | 'done' | 'failed'>('idle');

  return (
    <div className="border-pending bg-surface flex flex-col gap-2 rounded-lg border-2 p-4">
      <p className="text-pending flex items-center gap-2 text-xs font-medium uppercase tracking-wider">
        {/* Icon plus text, never colour alone — §A. */}
        <span aria-hidden="true">⚠</span>
        <span>{label} — shown once</span>
      </p>

      <code className="bg-page text-ink block break-all rounded p-3 font-mono text-sm">
        {value}
      </code>

      <p className="text-ink-3 text-xs">{warning}</p>

      <button
        type="button"
        className="border-hair text-ink min-h-[44px] rounded border text-sm"
        onClick={async () => {
          try {
            await navigator.clipboard.writeText(value);
            setCopied('done');
          } catch {
            setCopied('failed');
          }
        }}
      >
        {copied === 'done' && 'Copied'}
        {copied === 'failed' && 'Could not copy — select it above'}
        {copied === 'idle' && 'Copy'}
      </button>
    </div>
  );
}
