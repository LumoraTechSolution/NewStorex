/**
 * Raw ESC/POS command bytes (M1-13).
 *
 * The command set below is the Epson ESC/POS standard, which the overwhelming majority of
 * thermal receipt printers clone regardless of brand — this is deliberately not printer-specific.
 * Pure byte-building, no I/O: what actually writes these bytes to a printer is the transport
 * layer (`printerTransport.cjs`, Electron main process only), which this module knows nothing
 * about. That split is what makes the receipt content testable without hardware.
 *
 * Text is encoded as plain ASCII on purpose. Most thermal printers default to a single-byte code
 * page and mis-render anything outside it without an explicit `ESC t n` code-page switch, which
 * varies by vendor — safer to stay in the ASCII range everywhere a receipt has to print
 * correctly than to guess a code page no printer in the field has been confirmed against.
 */

const ESC = 0x1b;
const GS = 0x1d;

function bytes(...values: readonly number[]): Uint8Array {
  return Uint8Array.from(values);
}

export function concatBytes(chunks: readonly Uint8Array[]): Uint8Array {
  const total = chunks.reduce((sum, c) => sum + c.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

/**
 * Anything outside printable ASCII (0x20-0x7E) becomes `?` rather than being dropped, so a
 * mis-typed product name shows up as a visible gap on the receipt instead of silently shrinking
 * it — the cashier notices a run of `?` immediately; a shortened line she might not.
 */
export function asciiBytes(text: string): Uint8Array {
  const out = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    out[i] = code >= 0x20 && code <= 0x7e ? code : 0x3f; // '?'
  }
  return out;
}

export const LF = bytes(0x0a);

export function init(): Uint8Array {
  return bytes(ESC, 0x40);
}

export type Alignment = 'left' | 'center' | 'right';

export function align(a: Alignment): Uint8Array {
  return bytes(ESC, 0x61, a === 'left' ? 0 : a === 'center' ? 1 : 2);
}

export function bold(on: boolean): Uint8Array {
  return bytes(ESC, 0x45, on ? 1 : 0);
}

/** Double width and height together — a receipt has one size worth reaching for, not four. */
export function doubleSize(on: boolean): Uint8Array {
  return bytes(GS, 0x21, on ? 0x11 : 0x00);
}

export function text(s: string): Uint8Array {
  return asciiBytes(s);
}

/** A line of text terminated with a line feed. Empty by default: a blank line is just LF. */
export function line(s = ''): Uint8Array {
  return concatBytes([asciiBytes(s), LF]);
}

export function feed(lines = 1): Uint8Array {
  return bytes(ESC, 0x64, lines);
}

/** `partial` leaves a tearable tab, which is what a receipt actually needs — not a full cut. */
export function cut(partial = true): Uint8Array {
  return bytes(GS, 0x56, partial ? 1 : 0);
}

function clampPulseUnit(ms: number): number {
  // Units are roughly 2ms each per the Epson spec, and the field is a single byte.
  return Math.max(0, Math.min(255, Math.round(ms / 2)));
}

/**
 * The cash-drawer kick. Almost every till in the field wires the drawer's RJ11 into the
 * *printer*, not the PC, so opening it is the same write as printing the receipt — one more
 * command appended to the same buffer, one more write over the same connection.
 *
 * @param pin which of the printer's two drawer connectors to pulse — 0 unless a shop's printer
 *   is wired to the second one, which is rare enough not to be worth a config option yet.
 */
export function openDrawer(pin: 0 | 1 = 0, onMs = 50, offMs = 500): Uint8Array {
  return bytes(ESC, 0x70, pin, clampPulseUnit(onMs), clampPulseUnit(offMs));
}
