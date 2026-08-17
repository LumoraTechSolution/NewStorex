import net from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';

import {
  TcpPrinterTransport,
  SerialPrinterTransport,
  createPrinterTransport,
  printerConfigFromEnv,
} from './printerTransport.cjs';

/**
 * `SerialPrinterTransport` is deliberately not exercised here beyond construction — doing so
 * for real needs a physical or virtual serial device this environment does not have. See the
 * module comment: it is wired in and unverified, on purpose, until real hardware or proper
 * native-build tooling exists.
 */

describe('TcpPrinterTransport', () => {
  let server;

  afterEach(async () => {
    if (server) {
      await new Promise((resolve) => server.close(resolve));
      server = undefined;
    }
  });

  it('delivers the exact bytes to a real TCP listener', async () => {
    const received = [];
    server = net.createServer((socket) => {
      socket.on('data', (chunk) => received.push(chunk));
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    const port = server.address().port;

    const transport = new TcpPrinterTransport({ host: '127.0.0.1', port });
    const payload = Uint8Array.from([0x1b, 0x40, 0x48, 0x69, 0x0a]); // ESC @ "Hi\n"
    await transport.write(payload);

    // The write resolves once *our* side has flushed — give the server's data event a tick.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(Buffer.concat(received)).toEqual(Buffer.from(payload));
  });

  it('rejects clearly when nothing is listening', async () => {
    // Nothing bound here: connecting to it must fail fast with ECONNREFUSED.
    const probe = net.createServer();
    await new Promise((resolve) => probe.listen(0, '127.0.0.1', resolve));
    const port = probe.address().port;
    await new Promise((resolve) => probe.close(resolve));

    const transport = new TcpPrinterTransport({ host: '127.0.0.1', port });
    await expect(transport.write(Uint8Array.of(1))).rejects.toThrow(/unreachable/);
  });

  it('requires a host', () => {
    expect(() => new TcpPrinterTransport({})).toThrow(/requires a host/);
  });

  it('defaults to port 9100, the RAW/JetDirect standard', () => {
    const transport = new TcpPrinterTransport({ host: '127.0.0.1' });
    expect(transport.port).toBe(9100);
  });
});

describe('SerialPrinterTransport', () => {
  it('requires a serial path', () => {
    expect(() => new SerialPrinterTransport({})).toThrow(/requires a serial path/);
  });

  it('defaults to 19200 baud', () => {
    const transport = new SerialPrinterTransport({ path: 'COM3' });
    expect(transport.baudRate).toBe(19200);
  });
});

describe('createPrinterTransport', () => {
  it('builds a TCP transport by default', () => {
    const transport = createPrinterTransport({ host: '127.0.0.1' });
    expect(transport).toBeInstanceOf(TcpPrinterTransport);
  });

  it('builds a serial transport when asked', () => {
    const transport = createPrinterTransport({ transport: 'serial', path: 'COM3' });
    expect(transport).toBeInstanceOf(SerialPrinterTransport);
  });
});

describe('printerConfigFromEnv', () => {
  it('defaults to tcp against loopback — the app must still start with no printer configured', () => {
    const config = printerConfigFromEnv({});
    expect(config).toEqual({
      transport: 'tcp',
      host: '127.0.0.1',
      port: undefined,
      path: undefined,
      baudRate: undefined,
    });
    // The point of the default: constructing a transport from it must never throw.
    expect(() => createPrinterTransport(config)).not.toThrow();
  });

  it('reads every setting, parsing numbers', () => {
    const config = printerConfigFromEnv({
      LUMORA_PRINTER_TRANSPORT: 'serial',
      LUMORA_PRINTER_HOST: '192.168.1.50',
      LUMORA_PRINTER_PORT: '9100',
      LUMORA_PRINTER_SERIAL_PATH: 'COM3',
      LUMORA_PRINTER_BAUD_RATE: '9600',
    });
    expect(config).toEqual({
      transport: 'serial',
      host: '192.168.1.50',
      port: 9100,
      path: 'COM3',
      baudRate: 9600,
    });
  });

  it('anything other than the literal "serial" means tcp', () => {
    expect(printerConfigFromEnv({ LUMORA_PRINTER_TRANSPORT: 'usb' }).transport).toBe('tcp');
  });
});
