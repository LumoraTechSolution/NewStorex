import { describe, expect, it } from 'vitest';

import * as esc from './escpos';

describe('init/align/bold/doubleSize', () => {
  it('emits the documented ESC/POS bytes', () => {
    expect(Array.from(esc.init())).toEqual([0x1b, 0x40]);
    expect(Array.from(esc.align('left'))).toEqual([0x1b, 0x61, 0]);
    expect(Array.from(esc.align('center'))).toEqual([0x1b, 0x61, 1]);
    expect(Array.from(esc.align('right'))).toEqual([0x1b, 0x61, 2]);
    expect(Array.from(esc.bold(true))).toEqual([0x1b, 0x45, 1]);
    expect(Array.from(esc.bold(false))).toEqual([0x1b, 0x45, 0]);
    expect(Array.from(esc.doubleSize(true))).toEqual([0x1d, 0x21, 0x11]);
    expect(Array.from(esc.doubleSize(false))).toEqual([0x1d, 0x21, 0x00]);
  });
});

describe('text/line/feed/cut', () => {
  it('encodes ASCII text as-is', () => {
    expect(Array.from(esc.text('AB'))).toEqual([0x41, 0x42]);
  });

  it('replaces non-ASCII with a visible ? rather than dropping it', () => {
    // 'é' is outside the printable ASCII range this module commits to.
    expect(Array.from(esc.asciiBytes('é'))).toEqual([0x3f]);
  });

  it('a line is its text plus one line feed', () => {
    expect(Array.from(esc.line('hi'))).toEqual([0x68, 0x69, 0x0a]);
    expect(Array.from(esc.line())).toEqual([0x0a]);
  });

  it('feed and cut', () => {
    expect(Array.from(esc.feed(3))).toEqual([0x1b, 0x64, 3]);
    expect(Array.from(esc.cut(true))).toEqual([0x1d, 0x56, 1]);
    expect(Array.from(esc.cut(false))).toEqual([0x1d, 0x56, 0]);
  });
});

describe('openDrawer', () => {
  it('defaults to drawer pin 0 with a 50ms/500ms pulse, in ~2ms units', () => {
    // 50ms / 2ms ≈ 25, 500ms / 2ms = 250.
    expect(Array.from(esc.openDrawer())).toEqual([0x1b, 0x70, 0, 25, 250]);
  });

  it('selects the requested pin', () => {
    expect(Array.from(esc.openDrawer(1))).toEqual([0x1b, 0x70, 1, 25, 250]);
  });

  it('clamps pulse widths to a single byte rather than overflowing it', () => {
    const [, , , t1, t2] = esc.openDrawer(0, 100_000, 100_000);
    expect(t1).toBe(255);
    expect(t2).toBe(255);
  });
});

describe('concatBytes', () => {
  it('joins chunks in order with the combined length', () => {
    const joined = esc.concatBytes([Uint8Array.of(1, 2), Uint8Array.of(), Uint8Array.of(3)]);
    expect(Array.from(joined)).toEqual([1, 2, 3]);
  });
});
