/**
 * Reads an ESC/POS buffer back as the text a printer would put on paper.
 *
 * Test support, not production code — it exists so a spec can assert about what a shopkeeper
 * reads rather than about byte offsets. It lives here rather than inside a `.test.ts` because
 * three suites need it (`receipt`, `zreport`, and the credit note), and three copies of a decoder
 * is three chances for one of them to drift and start passing a document the others would fail.
 * `vitest.config.ts` only collects `*.test.ts`, so nothing here runs as a suite of its own.
 *
 * ## Why it parses commands rather than filtering bytes
 *
 * Several ESC/POS commands carry a parameter byte that lands inside printable ASCII: `ESC E 0x01`
 * contains 0x45 (`'E'`), and `ESC a 0x00` contains 0x61 (`'a'`). Filtering by byte value alone
 * therefore splices stray letters into the decoded text — and a width assertion then fails by one
 * character on a line that is actually fine, which is a very slow thing to debug. So each
 * command's exact length is skipped instead, leaving only the bytes that were meant as content.
 */
export function decodeEscPos(bytes: Uint8Array): string {
  const b = Array.from(bytes);
  let out = '';
  let i = 0;

  while (i < b.length) {
    const byte = b[i];

    if (byte === 0x1b) {
      // ESC @ (2) | ESC a n / ESC E n / ESC d n (3) | ESC p m t1 t2 (5)
      const cmd = b[i + 1];
      i += cmd === 0x40 ? 2 : cmd === 0x70 ? 5 : 3;
      continue;
    }
    if (byte === 0x1d) {
      // GS ! n | GS V m (3)
      i += 3;
      continue;
    }
    if (byte === 0x0a) {
      out += '\n';
      i++;
      continue;
    }
    if (byte !== undefined && byte >= 0x20 && byte <= 0x7e) {
      out += String.fromCharCode(byte);
    }
    i++;
  }

  return out;
}

/** The printable lines only — what a cashier, or an assertion, can actually read. */
export function escPosLines(bytes: Uint8Array): string[] {
  return decodeEscPos(bytes).split('\n');
}
