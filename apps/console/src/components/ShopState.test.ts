import { describe, expect, it } from 'vitest';

import { ago, describeShop } from './ShopState';
import type { OperatorDay, RecentSale } from '@/lib/api';

/**
 * The first line on the console (M6-14).
 *
 * <p>It is the one sentence on the screen that makes a claim about the present tense, and the two
 * ways it can be wrong are both quiet: telling an owner their shop is open when the last till was
 * cashed up an hour ago, or naming a time off the viewer's clock instead of the shop's.
 */

function person(over: Partial<OperatorDay> = {}): OperatorDay {
  return {
    operatorClientUuid: 'u-1',
    operator: 'Nimal Perera',
    shiftCount: 1,
    saleCount: 40,
    totalMinor: 500_000,
    varianceMinor: 0,
    onNow: false,
    openedAt: null,
    ...over,
  };
}

function sale(soldAt: string): RecentSale {
  return {
    invoiceNumber: 'KND-T1-000001',
    branchCode: 'KND',
    terminalCode: 'T1',
    totalMinor: 1_000,
    soldAt,
  };
}

describe('describeShop', () => {
  it('leads with who is behind the counter and since when', () => {
    // 03:34 UTC is 9:04 in Colombo — the shop's clock, which is the only one that means anything
    // to an owner reading this from somewhere else.
    const state = describeShop([person({ onNow: true, openedAt: '2026-08-31T03:34:00Z' })], []);

    expect(state.open).toBe(true);
    expect(state.label).toBe('Open');
    expect(state.details[0]).toBe('Nimal Perera on the till since 9:04 am');
  });

  it('names everybody on, rather than counting them', () => {
    const state = describeShop(
      [
        person({ onNow: true, openedAt: '2026-08-31T04:00:00Z' }),
        person({
          operatorClientUuid: 'u-2',
          operator: 'Kamala Silva',
          onNow: true,
          openedAt: '2026-08-31T03:30:00Z',
        }),
      ],
      [],
    );

    expect(state.details[0]).toBe('Nimal Perera and Kamala Silva on the till since 9:00 am');
  });

  it('still says the shop is open when the cloud was never told who opened it', () => {
    // A shift synced before M6-13 carries no person. The shop is no less open for it.
    const state = describeShop([person({ operator: null, onNow: true, openedAt: null })], []);

    expect(state.open).toBe(true);
    expect(state.details[0]).toBe('Someone on the till');
  });

  it('does not say "closed" about a shop it has heard nothing from', () => {
    // A shop that has not opened yet and a shop shut for the day are not the same sentence, and
    // this app cannot tell them apart from a shift table. So it says what it actually knows.
    expect(describeShop([], []).label).toBe('No till opened today');
    expect(describeShop([person({ onNow: false })], []).label).toBe('No till open');
  });

  it('carries the last sale, because that is what "is anything happening" means', () => {
    const now = new Date('2026-08-31T08:14:00Z');
    const state = describeShop([person({ onNow: true })], [sale('2026-08-31T08:08:00Z')], now);

    expect(state.details[1]).toBe('last sale 6 minutes ago');
  });

  it('keeps the last sale on a shop that has closed for the day', () => {
    const now = new Date('2026-08-31T15:00:00Z');
    const state = describeShop([person({ onNow: false })], [sale('2026-08-31T14:00:00Z')], now);

    expect(state.open).toBe(false);
    expect(state.details).toEqual(['last sale an hour ago']);
  });
});

describe('ago', () => {
  const now = new Date('2026-08-31T12:00:00Z');
  const minutesBefore = (minutes: number) =>
    new Date(now.getTime() - minutes * 60_000).toISOString();

  it('reads as a person would say it', () => {
    expect(ago(minutesBefore(0), now)).toBe('just now');
    expect(ago(minutesBefore(1), now)).toBe('1 minute ago');
    expect(ago(minutesBefore(6), now)).toBe('6 minutes ago');
    expect(ago(minutesBefore(60), now)).toBe('an hour ago');
    expect(ago(minutesBefore(200), now)).toBe('3 hours ago');
    expect(ago(minutesBefore(60 * 30), now)).toBe('yesterday');
    expect(ago(minutesBefore(60 * 24 * 3), now)).toBe('3 days ago');
  });
});
