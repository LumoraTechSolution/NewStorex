'use client';

import { formatMinor } from '@lumora/domain';

/**
 * Currency, in the tabular monospace face §A requires.
 *
 * <p>The formatting comes from `@lumora/domain` — the same function the receipt and the till use.
 * That is the standing rule and it is not ceremony here: the whole purpose of this screen is that
 * the owner sees the same figure the shop saw, and a second formatter is how the two eventually
 * disagree by a rupee with no test to catch it.
 */
export function Money({ minor, className = '' }: { minor: number; className?: string }) {
  return <span className={`font-mono tabular-nums ${className}`}>{formatMinor(minor)}</span>;
}
