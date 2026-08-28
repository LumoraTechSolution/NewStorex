import { afterEach, describe, expect, it, vi } from 'vitest';

import { describeSync } from './TodayScreen';

/**
 * The staleness line, which is the only real logic on the Today screen (M4-06).
 *
 * <p>It matters more than it looks. The console reads the output of an outbox, so its figure is
 * only as fresh as the last drain — and a till that stopped syncing at lunchtime shows a plausible,
 * wrong, quietly shrinking total all afternoon. This sentence is the only thing on the screen that
 * tells the owner not to trust the number above it.
 */
describe('describeSync', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  function at(minutesAgo: number): string {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-25T12:00:00Z'));
    return new Date(Date.now() - minutesAgo * 60_000).toISOString();
  }

  it('says nothing has arrived, rather than showing an alarming blank', () => {
    // A shop that has not opened yet looks exactly like a shop that has stopped syncing. Saying
    // so plainly is more honest than either a warning or an empty space.
    expect(describeSync(null)).toEqual({
      text: 'Nothing has arrived from the shop today',
      stale: false,
    });
  });

  it('is not stale a few minutes after a sync', () => {
    const result = describeSync(at(5));
    expect(result.stale).toBe(false);
    expect(result.text).toContain('Up to date');
  });

  it('reads "just now" under a minute', () => {
    expect(describeSync(at(0)).text).toBe('Up to date, just now');
  });

  it('turns stale at half an hour', () => {
    expect(describeSync(at(29)).stale).toBe(false);
    expect(describeSync(at(30)).stale).toBe(true);
  });

  it('switches to hours once minutes stop being readable', () => {
    expect(describeSync(at(45)).text).toBe('Last update 45 min ago');
    expect(describeSync(at(180)).text).toBe('Last update 3 hours ago');
  });

  it('stays stale for a till that has been silent for days', () => {
    const result = describeSync(at(60 * 26));
    expect(result.stale).toBe(true);
    expect(result.text).toContain('26 hours ago');
  });
});
