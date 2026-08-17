'use strict';

/**
 * Printer transport (M1-14).
 *
 * Two implementations behind one small interface — `write(bytes): Promise<void>` — so the IPC
 * handler in `main.cjs` never needs to know which one is active, only that a config object picked
 * one:
 *
 *  - `TcpPrinterTransport`: a raw socket to the printer's RAW/JetDirect port (9100 by default,
 *    the de facto standard for network-capable thermal printers), using only Node's built-in
 *    `net` — no native module, no build step, identical in dev and prod. This is the default,
 *    because it is the one transport this repo can actually verify end to end without a physical
 *    printer: point it at a plain TCP listener in a test and the exact bytes arrive.
 *  - `SerialPrinterTransport`: a COM/USB-serial connection via `serialport`, matching what the
 *    roadmap calls for directly ("Electron main-process serial/USB write"). Wired in behind the
 *    same interface so switching to it later is a config change, not a rewrite.
 *
 *    `serialport`'s native binding has to be rebuilt against *Electron's* Node ABI, not the
 *    system Node one `pnpm install` targets by default — they are not the same, and a binary
 *    built for one throws a `NODE_MODULE_VERSION` mismatch under the other. Run `pnpm --filter
 *    @lumora/terminal rebuild:serial` (needs a C++ toolchain — MSVC on Windows, via Visual
 *    Studio Build Tools' "Desktop development with C++" workload — if no prebuilt binary
 *    matches Electron's ABI) after install, before selecting this transport. That has been done
 *    and confirmed on this machine: `serialport` loads inside a real Electron 33 main process
 *    and correctly enumerates real COM ports. What remains unverified is the last step —
 *    writing bytes to an actual attached receipt printer — because none is attached here.
 *    `serialport` is an `optionalDependency` regardless, so a machine where its native binding
 *    cannot install still gets a working app, just without this path available.
 *
 * Neither implementation keeps a connection open between prints. A receipt is small and
 * infrequent; nothing here benefits from a kept-open socket or port, and a stale one is exactly
 * what would silently swallow a print after the printer power-cycles.
 */

const net = require('node:net');

class TcpPrinterTransport {
  constructor({ host, port = 9100, connectTimeoutMs = 3000 }) {
    if (!host) throw new Error('TcpPrinterTransport requires a host');
    this.host = host;
    this.port = port;
    this.connectTimeoutMs = connectTimeoutMs;
  }

  write(bytes) {
    return new Promise((resolve, reject) => {
      const socket = net.createConnection({ host: this.host, port: this.port });
      let settled = false;

      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        socket.destroy();
        reject(new Error(`Timed out connecting to printer at ${this.host}:${this.port}`));
      }, this.connectTimeoutMs);

      socket.once('connect', () => {
        clearTimeout(timer);
        // Resolves once *our* side has flushed the write, not once the printer closes its
        // end — a raw print job is fire-and-forget, and waiting on the remote FIN would hang
        // forever against printers that never send one.
        socket.end(Buffer.from(bytes), () => {
          if (settled) return;
          settled = true;
          resolve();
        });
      });

      socket.once('error', (err) => {
        clearTimeout(timer);
        if (settled) return;
        settled = true;
        reject(new Error(`Printer at ${this.host}:${this.port} unreachable: ${err.message}`));
      });
    });
  }

  async close() {}
}

class SerialPrinterTransport {
  constructor({ path, baudRate = 19200 }) {
    if (!path) throw new Error('SerialPrinterTransport requires a serial path (e.g. COM3)');
    this.path = path;
    this.baudRate = baudRate;
  }

  write(bytes) {
    // Lazy-required: a machine that never configures the serial transport should never need
    // the native binding to exist at all, let alone be loaded.
    let SerialPort;
    try {
      ({ SerialPort } = require('serialport'));
    } catch (e) {
      throw new Error(
        `serialport is not available (${e.message}) — install it, or set LUMORA_PRINTER_TRANSPORT=tcp`,
      );
    }

    return new Promise((resolve, reject) => {
      const port = new SerialPort({ path: this.path, baudRate: this.baudRate }, (openErr) => {
        if (openErr) {
          reject(new Error(`Could not open ${this.path}: ${openErr.message}`));
          return;
        }
        port.write(Buffer.from(bytes), (writeErr) => {
          if (writeErr) {
            port.close();
            reject(writeErr);
            return;
          }
          port.drain(() => port.close((closeErr) => (closeErr ? reject(closeErr) : resolve())));
        });
      });
    });
  }

  async close() {}
}

/**
 * @param {{transport?: 'tcp'|'serial', host?: string, port?: number, path?: string, baudRate?: number}} config
 */
function createPrinterTransport(config) {
  if (config.transport === 'serial') {
    return new SerialPrinterTransport(config);
  }
  return new TcpPrinterTransport(config);
}

/**
 * Reads the transport config from the environment, so it is a deployment setting, not code.
 *
 * `host` defaults to loopback rather than being left unset: a till with no printer configured
 * yet is the common case (a fresh dev machine, a shop mid-setup), and it must still be able to
 * *start* — printing should fail on the first print attempt with a plain "unreachable" error,
 * never at app launch by way of a transport that refused to even construct.
 */
function printerConfigFromEnv(env = process.env) {
  return {
    transport: env.LUMORA_PRINTER_TRANSPORT === 'serial' ? 'serial' : 'tcp',
    host: env.LUMORA_PRINTER_HOST || '127.0.0.1',
    port: env.LUMORA_PRINTER_PORT ? Number(env.LUMORA_PRINTER_PORT) : undefined,
    path: env.LUMORA_PRINTER_SERIAL_PATH,
    baudRate: env.LUMORA_PRINTER_BAUD_RATE ? Number(env.LUMORA_PRINTER_BAUD_RATE) : undefined,
  };
}

module.exports = {
  TcpPrinterTransport,
  SerialPrinterTransport,
  createPrinterTransport,
  printerConfigFromEnv,
};
