import { createServer, type Server, type Socket } from 'node:net';

import {
  _electron as electron,
  test as base,
  type ElectronApplication,
  type Page,
} from '@playwright/test';

/**
 * One record of something a pointing device would have caused.
 *
 * `detail` is the discriminator that matters. A real mouse click carries a click count of
 * at least 1; the synthetic click the browser fires when a focused `<button>` is activated
 * by Enter or Space carries `0`. Both are `isTrusted`, so trust alone cannot tell them
 * apart — and the difference is exactly "did a hand leave the keyboard".
 */
export interface PointerEvent {
  readonly type: string;
  readonly detail: number;
  readonly isTrusted: boolean;
  readonly target: string;
}

export interface Till {
  readonly page: Page;
  readonly app: ElectronApplication;
  /** Everything pointer-shaped the window has seen since it loaded. */
  pointerEvents(): Promise<PointerEvent[]>;
  /**
   * Every key the window has seen, with where it landed.
   *
   * Kept because a keystroke test that fails without this is nearly undebuggable: the
   * symptom is a wrong barcode in a status message, and the question is always "what did
   * the field actually receive, and did it have focus at the time".
   */
  keyLog(): Promise<string[]>;
  /** Bytes the app has sent to the (fake) receipt printer, decoded to readable text. */
  printedText(): string;
  /** Raw printer bytes, for asserting on ESC/POS commands rather than content. */
  printedBytes(): Buffer;
  forgetPrinted(): void;
}

/**
 * Installed before the first paint, in the capture phase, on `window`.
 *
 * Capture phase so nothing can stop propagation before this sees it — `useGlobalKeys` and
 * the tender overlay both call `stopPropagation`, and a listener that could be silenced by
 * the code under test is not evidence of anything.
 */
function recorderSource() {
  const TYPES = [
    'pointerdown',
    'pointerup',
    'mousedown',
    'mouseup',
    'click',
    'dblclick',
    'contextmenu',
  ];
  const recorded: PointerEvent[] = [];
  (window as unknown as { __lumoraPointerEvents: PointerEvent[] }).__lumoraPointerEvents = recorded;

  const keys: string[] = [];
  (window as unknown as { __lumoraKeys: string[] }).__lumoraKeys = keys;
  document.addEventListener(
    'keydown',
    (event) => {
      const active = document.activeElement as HTMLInputElement | null;
      keys.push(
        `${JSON.stringify((event as KeyboardEvent).key)} -> ` +
          `${active?.id || active?.tagName} value=${JSON.stringify(active?.value ?? '')}`,
      );
    },
    true,
  );

  for (const type of TYPES) {
    window.addEventListener(
      type,
      (event) => {
        const target = event.target as Element | null;
        recorded.push({
          type,
          detail: (event as MouseEvent).detail ?? 0,
          isTrusted: event.isTrusted,
          target: target?.tagName
            ? `${target.tagName.toLowerCase()}${target.id ? `#${target.id}` : ''}`
            : String(target),
        });
      },
      true,
    );
  }
}

/** A receipt printer that exists only to be written to (M1-14's TCP transport, port 9100). */
async function startFakePrinter(): Promise<{ server: Server; port: number; received: Buffer[] }> {
  const received: Buffer[] = [];
  const sockets = new Set<Socket>();

  const server = createServer((socket) => {
    sockets.add(socket);
    socket.on('data', (chunk) => received.push(chunk));
    socket.on('close', () => sockets.delete(socket));
    socket.on('error', () => sockets.delete(socket));
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (address === null || typeof address === 'string') {
    throw new Error('Fake printer did not bind to a TCP port');
  }
  return { server, port: address.port, received };
}

/**
 * Strips ESC/POS command bytes so an assertion can read the receipt as text.
 *
 * The same routine `receipt.test.ts` uses, and for the same reason: several commands take a
 * parameter byte inside printable ASCII, so filtering by byte value alone splices stray
 * letters into the output.
 */
function decodeEscPos(bytes: Buffer): string {
  let out = '';
  let i = 0;
  while (i < bytes.length) {
    const byte = bytes[i];
    if (byte === 0x1b) {
      const cmd = bytes[i + 1];
      i += cmd === 0x40 ? 2 : cmd === 0x70 ? 5 : 3;
      continue;
    }
    if (byte === 0x1d) {
      i += 3;
      continue;
    }
    if (byte === 0x0a) {
      out += '\n';
      i++;
      continue;
    }
    if (byte !== undefined && byte >= 0x20 && byte <= 0x7e) out += String.fromCharCode(byte);
    i++;
  }
  return out;
}

export const test = base.extend<{ till: Till }>({
  till: async ({}, use) => {
    const printer = await startFakePrinter();

    // `ELECTRON_RUN_AS_NODE` silently downgrades Electron to a plain Node process with no
    // `ipcMain` and no window — M1-14 lost time to exactly this, set in an ambient shell.
    // Deleted rather than trusted to be absent.
    const env = { ...process.env } as Record<string, string>;
    delete env.ELECTRON_RUN_AS_NODE;
    env.LUMORA_PRINTER_HOST = '127.0.0.1';
    env.LUMORA_PRINTER_PORT = String(printer.port);

    const app = await electron.launch({ args: ['electron/main.cjs'], env });

    let page: Page;
    try {
      page = await app.firstWindow({ timeout: 30_000 });
    } catch (e) {
      await app.close().catch(() => {});
      throw new Error(
        'The Electron window never appeared.\n' +
          'The usual cause is another instance already running — main.cjs takes a single-instance ' +
          'lock, so a second one quits immediately without opening a window. Close any running ' +
          '`pnpm --filter @lumora/terminal electron` first.\n\n' +
          `Underlying error: ${e instanceof Error ? e.message : String(e)}`,
      );
    }

    // Installed as an init script and then reloaded, so the listeners are in place before
    // the app's own code runs rather than racing it.
    await page.addInitScript(recorderSource);
    await page.reload();
    await page.waitForLoadState('domcontentloaded');
    await page.locator('#scan').waitFor({ state: 'visible' });

    let consumed = 0;
    await use({
      page,
      app,
      pointerEvents: () =>
        page.evaluate(
          () =>
            (window as unknown as { __lumoraPointerEvents: PointerEvent[] }).__lumoraPointerEvents,
        ),
      keyLog: () =>
        page.evaluate(() => (window as unknown as { __lumoraKeys: string[] }).__lumoraKeys),
      printedBytes: () => Buffer.concat(printer.received.slice(consumed)),
      printedText: () => decodeEscPos(Buffer.concat(printer.received.slice(consumed))),
      forgetPrinted: () => {
        consumed = printer.received.length;
      },
    });

    await app.close().catch(() => {});
    await new Promise<void>((resolve) => printer.server.close(() => resolve()));
  },
});

export { expect } from '@playwright/test';
