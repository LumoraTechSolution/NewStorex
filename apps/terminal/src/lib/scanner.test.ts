import { describe, expect, it } from 'vitest';

import { MIN_SCAN_LENGTH, ScanBuffer } from './scanner';

/** Feeds `text` then Enter, one key every `gapMs`. Returns what the Enter produced. */
function type(buffer: ScanBuffer, text: string, gapMs: number, startAt = 1000): string | null {
  let at = startAt;
  for (const key of text) {
    expect(buffer.push(key, at)).toBeNull();
    at += gapMs;
  }
  return buffer.push('Enter', at);
}

describe('ScanBuffer', () => {
  it('returns the code for a fast burst ending in Enter', () => {
    expect(type(new ScanBuffer(), '4791234567890', 5)).toBe('4791234567890');
  });

  it('returns nothing for a person typing', () => {
    expect(type(new ScanBuffer(), '4791234567890', 150)).toBeNull();
  });

  it(`ignores bursts shorter than ${MIN_SCAN_LENGTH}`, () => {
    expect(type(new ScanBuffer(), '479', 5)).toBeNull();
    expect(type(new ScanBuffer(), '4791', 5)).toBe('4791');
  });

  it('returns nothing for an Enter pressed long after the burst', () => {
    const buffer = new ScanBuffer();
    for (const [i, key] of [...'47912345'].entries()) buffer.push(key, 1000 + i * 5);
    expect(buffer.push('Enter', 2000)).toBeNull();
  });

  it('starts a new code after a pause, dropping stray keys typed before it', () => {
    const buffer = new ScanBuffer();
    buffer.push('a', 0);
    buffer.push('b', 300);
    expect(type(buffer, '12345678', 5, 1000)).toBe('12345678');
  });

  it('does not let Shift break a run of letters', () => {
    const buffer = new ScanBuffer();
    let at = 0;
    for (const key of ['Shift', 'A', 'Shift', 'B', 'C', 'D']) buffer.push(key, (at += 5));
    expect(buffer.push('Enter', at + 5)).toBe('ABCD');
  });

  it('is empty again after a scan', () => {
    const buffer = new ScanBuffer();
    expect(type(buffer, '12345678', 5, 0)).toBe('12345678');
    expect(buffer.push('Enter', 50)).toBeNull();
  });
});
