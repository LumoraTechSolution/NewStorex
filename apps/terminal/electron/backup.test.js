import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import crypto from 'node:crypto';
import http from 'node:http';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

/**
 * Where the till thinks it lives, for the duration of this file.
 *
 * Set before anything imports `runtimeConfig.cjs`, because that module resolves its paths once
 * at load from LOCALAPPDATA and never looks again. Doing this inside a test does not work and
 * does not fail either: the state file simply lands in the real installed till's config folder,
 * where it silently suppresses that shop's next cloud upload.
 */
const APP_DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'storex-appdata-'));
const REAL_APP_DATA = process.env.LOCALAPPDATA;
process.env.LOCALAPPDATA = APP_DATA;

/**
 * Backups, against a real Postgres (M5-04, M5-05).
 *
 * <h2>Why this does not mock pg_dump</h2>
 *
 * A backup test that asserts "we called pg_dump with the right arguments" verifies the arguments
 * and nothing else — and the failures that matter here are all on the other side of that call: a
 * password that never reaches the tool, a format `pg_restore` cannot read, a partial file left
 * where a good one should be. Every one of those passes a mocked test and loses a shop's history.
 *
 * So this runs the actual bundled `pg_dump.exe` against the actual `db-test` container, dumps a
 * table it created, and — for M5-05 — restores that dump into a fresh database and reads the rows
 * back out. If Postgres is not up, the tests skip rather than fail: `pnpm test` is a CI gate that
 * must not need Docker (see CLAUDE.md), and a red suite on a developer's laptop teaches people to
 * ignore it.
 *
 * <h2>What it deliberately does not cover</h2>
 *
 * The schedule itself. Twelve-hour timers are not worth faking, and the thing that would actually
 * break — a shop PC switched off at night — is a question about `setTimeout` in Electron rather
 * than about this code. `docs/restore.md` carries the part a person has to do.
 */

const exec = promisify(execFile);

// The db-test container from `pnpm db:up`. Its credentials are the compose file's, and it is
// disposable by design — wiped on every backend test run, which is why it is safe to create and
// drop databases inside it here.
const PG = {
  host: '127.0.0.1',
  port: '5444',
  user: 'lumora',
  password: 'lumora',
  database: 'lumora_test',
};

/** The staged runtime, which is where the packaged app finds its Postgres tools. */
const RESOURCES = path.resolve(__dirname, '..', '..', '..');

function tool(name) {
  return path.join(RESOURCES, 'runtime', 'pgsql', 'bin', `${name}.exe`);
}

function env() {
  return { ...process.env, PGPASSWORD: PG.password };
}

async function psql(database, sql) {
  return exec(
    tool('psql'),
    [
      '-h',
      PG.host,
      '-p',
      PG.port,
      '-U',
      PG.user,
      '-d',
      database,
      '-v',
      'ON_ERROR_STOP=1',
      '-c',
      sql,
    ],
    { env: env(), windowsHide: true },
  );
}

/**
 * Whether the staged Postgres is on disk, decided at collection time because `it.runIf` needs a
 * boolean now rather than a promise later. The container is checked separately in `beforeAll`,
 * where it can be awaited — a test that is collected and then finds no database skips itself
 * through `available` instead.
 */
const staged = fs.existsSync(tool('pg_dump'));

let workDir;

beforeAll(() => {
  // The other precondition — a running `db:up` container — is not probed here. A test that
  // reached this point and cannot connect should fail loudly rather than skip: `staged` already
  // covers the fresh-clone case, and silently passing because Postgres was down is exactly how a
  // backup suite comes to prove nothing.
  if (!staged) return;
  workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'storex-backup-'));
});

afterAll(() => {
  if (workDir) fs.rmSync(workDir, { recursive: true, force: true });
});

describe('runBackup', () => {
  it.runIf(staged)(
    'writes an archive pg_restore can read, containing the rows that were there',
    async () => {
      const table = `backup_probe_${Date.now()}`;
      await psql(PG.database, `CREATE TABLE ${table} (id int primary key, note text)`);
      try {
        await psql(PG.database, `INSERT INTO ${table} VALUES (1, 'a sale that must survive')`);

        const target = path.join(workDir, 'probe.dump');
        await exec(
          tool('pg_dump'),
          [
            '-h',
            PG.host,
            '-p',
            PG.port,
            '-U',
            PG.user,
            '-d',
            PG.database,
            '-Fc',
            '--no-owner',
            '--no-privileges',
            '-f',
            target,
          ],
          { env: env(), windowsHide: true },
        );

        expect(fs.existsSync(target)).toBe(true);
        expect(fs.statSync(target).size).toBeGreaterThan(0);

        // The verify() check in backup.cjs, run for real: a table of contents that parses and
        // names the table proves the archive is readable, which "the file exists" does not.
        const { stdout } = await exec(tool('pg_restore'), ['--list', target], {
          env: env(),
          windowsHide: true,
        });
        expect(stdout).toContain(table);
      } finally {
        await psql(PG.database, `DROP TABLE IF EXISTS ${table}`);
      }
    },
    60_000,
  );
});

describe('restore (M5-05)', () => {
  /**
   * The half that makes the other half worth having.
   *
   * A backup nobody has restored from is a belief, not a backup — so this dumps a table, drops it,
   * restores into a **different** database, and reads the row back. Restoring into a fresh
   * database rather than over the original is what a real recovery looks like: the disk that died
   * is not the disk you restore onto, and `restore.md` documents the same shape.
   */
  it.runIf(staged)(
    'a dropped table comes back with its rows, in a database that never had it',
    async () => {
      const table = `restore_probe_${Date.now()}`;
      const scratch = `lumora_restore_probe_${Date.now()}`;
      const target = path.join(workDir, 'restore.dump');

      await psql(PG.database, `CREATE TABLE ${table} (id int primary key, total_minor bigint)`);
      try {
        // Integer minor units, as everything about money in this codebase is (§A).
        await psql(PG.database, `INSERT INTO ${table} VALUES (1, 128500), (2, 4990)`);

        await exec(
          tool('pg_dump'),
          [
            '-h',
            PG.host,
            '-p',
            PG.port,
            '-U',
            PG.user,
            '-d',
            PG.database,
            '-Fc',
            '--no-owner',
            '--no-privileges',
            '-t',
            table,
            '-f',
            target,
          ],
          { env: env(), windowsHide: true },
        );

        // The disaster: the table is gone from the live database.
        await psql(PG.database, `DROP TABLE ${table}`);

        // The recovery, into somewhere that has never seen this data.
        await psql('postgres', `CREATE DATABASE ${scratch}`);
        await exec(
          tool('pg_restore'),
          ['-h', PG.host, '-p', PG.port, '-U', PG.user, '-d', scratch, '--no-owner', target],
          { env: env(), windowsHide: true },
        );

        const { stdout } = await exec(
          tool('psql'),
          [
            '-h',
            PG.host,
            '-p',
            PG.port,
            '-U',
            PG.user,
            '-d',
            scratch,
            '-tAc',
            `SELECT sum(total_minor) FROM ${table}`,
          ],
          { env: env(), windowsHide: true },
        );

        // 128500 + 4990. Asserting the total rather than the row count, because a restore that
        // brings back two empty rows is a restore that lost the money.
        expect(stdout.trim()).toBe('133490');
      } finally {
        await psql(PG.database, `DROP TABLE IF EXISTS ${table}`).catch(() => {});
        await psql('postgres', `DROP DATABASE IF EXISTS ${scratch}`).catch(() => {});
      }
    },
    90_000,
  );
});

describe('backupName', () => {
  it('sorts chronologically as text, which is what the pruning relies on', async () => {
    const { backupName } = await import('./services/backup.cjs');
    const earlier = backupName(new Date('2026-09-01T03:14:00Z'));
    const later = backupName(new Date('2026-09-01T15:14:00Z'));

    expect(earlier).toBe('storex-2026-09-01T0314.dump');
    expect([later, earlier].sort()).toEqual([earlier, later]);
  });
});

/**
 * The cloud copy (M5-06).
 *
 * <h2>Why there is a real HTTP server in here</h2>
 *
 * The same argument as `pg_dump` above. What can go wrong is not the shape of the call — it is a
 * header the cloud rejects, a body that arrives short, a credential resolved from the wrong place,
 * or a failed upload that records itself as a success and so is never retried. All four pass a
 * mocked `fetch` and lose a shop's only off-site copy. So this stands up a server that behaves
 * like the cloud, uploads a real file to it, and reads what actually arrived.
 *
 * The same server also answers `/api/setup/identity`, because that is where the till learns which
 * terminal it is, and a wrong answer there files the archive under the wrong till.
 */
describe('uploadToCloud', () => {
  /**
   * The module under test, with any record of a previous upload cleared.
   *
   * Not a reload. `vi.resetModules()` does not reach a `require`d CommonJS module's captured
   * constants, so redirecting LOCALAPPDATA here would appear to work and would not — see the note
   * at the top of this file. What actually needs resetting between tests is one JSON file.
   */
  async function loadBackupModule() {
    const backup = await import('./services/backup.cjs');
    fs.mkdirSync(path.dirname(backup.cloudStateFile()), { recursive: true });
    fs.rmSync(backup.cloudStateFile(), { force: true });
    return backup;
  }

  function cloudServer(handler) {
    return new Promise((resolve) => {
      const received = { uploads: [] };
      const server = http.createServer((request, response) => {
        if (request.url === '/api/setup/identity') {
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end(
            JSON.stringify({ terminalCode: 'T1', branchCode: 'KND', shopName: 'Kandy' }),
          );
          return;
        }
        const chunks = [];
        request.on('data', (chunk) => chunks.push(chunk));
        request.on('end', () => {
          received.uploads.push({ headers: request.headers, body: Buffer.concat(chunks) });
          handler(request, response);
        });
      });
      server.listen(0, '127.0.0.1', () =>
        resolve({ server, received, port: server.address().port }),
      );
    });
  }

  const ok = (_request, response) => {
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ name: 'x', bytes: 1, sha256: 'x', alreadyHeld: false }));
  };

  let root;
  let previousToken;
  let previousUrl;

  beforeEach(() => {
    previousToken = process.env.LUMORA_CLOUD_TOKEN;
    previousUrl = process.env.LUMORA_CLOUD_URL;
    // A machine-level token on the developer's own PC would otherwise decide these tests, which
    // is the same class of bug the precedence rule in cloudCredential() exists to prevent.
    delete process.env.LUMORA_CLOUD_TOKEN;
    delete process.env.LUMORA_CLOUD_URL;
    root = fs.mkdtempSync(path.join(os.tmpdir(), 'storex-cloud-'));
  });

  afterEach(() => {
    if (previousToken === undefined) delete process.env.LUMORA_CLOUD_TOKEN;
    else process.env.LUMORA_CLOUD_TOKEN = previousToken;
    if (previousUrl === undefined) delete process.env.LUMORA_CLOUD_URL;
    else process.env.LUMORA_CLOUD_URL = previousUrl;
    fs.rmSync(root, { recursive: true, force: true });
  });

  afterAll(() => {
    process.env.LOCALAPPDATA = REAL_APP_DATA;
    fs.rmSync(APP_DATA, { recursive: true, force: true });
  });

  it('sends the archive, its name, its terminal and its digest', async () => {
    const { server, received, port } = await cloudServer(ok);
    try {
      const backup = await loadBackupModule();
      const file = path.join(root, 'storex-2026-09-01T0314.dump');
      const archive = Buffer.from('a shop, compressed');
      fs.writeFileSync(file, archive);

      const outcome = await backup.uploadToCloud(
        { backendPort: port, cloudUrl: `http://127.0.0.1:${port}`, cloudToken: 'till-token' },
        file,
        'storex-2026-09-01T0314.dump',
      );

      expect(outcome).toBe('sent');
      expect(received.uploads).toHaveLength(1);
      const [upload] = received.uploads;
      // The bytes, not a length: a body that arrives truncated is the failure this whole feature
      // exists to catch, and the cloud rejects on exactly this digest.
      expect(upload.body.equals(archive)).toBe(true);
      expect(upload.headers['x-backup-name']).toBe('storex-2026-09-01T0314.dump');
      expect(upload.headers['x-backup-terminal']).toBe('T1');
      expect(upload.headers.authorization).toBe('Bearer till-token');
      expect(upload.headers['x-backup-sha256']).toBe(
        crypto.createHash('sha256').update(archive).digest('hex'),
      );
      expect(Number(upload.headers['content-length'])).toBe(archive.length);
    } finally {
      server.close();
    }
  });

  it('does not send the same day twice', async () => {
    const { server, received, port } = await cloudServer(ok);
    try {
      const backup = await loadBackupModule();
      const file = path.join(root, 'storex-2026-09-01T0314.dump');
      fs.writeFileSync(file, 'once');
      const config = {
        backendPort: port,
        cloudUrl: `http://127.0.0.1:${port}`,
        cloudToken: 'till-token',
      };

      expect(await backup.uploadToCloud(config, file, 'storex-2026-09-01T0314.dump')).toBe('sent');
      expect(await backup.uploadToCloud(config, file, 'storex-2026-09-01T0314.dump')).toBe(
        'not-yet',
      );
      expect(received.uploads).toHaveLength(1);

      // Twenty hours later it goes again — a shop gets one copy a day, not one ever.
      const tomorrow = Date.now() + 21 * 60 * 60 * 1000;
      expect(
        await backup.uploadToCloud(config, file, 'storex-2026-09-01T0314.dump', tomorrow),
      ).toBe('sent');
      expect(received.uploads).toHaveLength(2);
    } finally {
      server.close();
    }
  });

  /**
   * The one that decides whether a shop with a rejected credential ever recovers.
   *
   * A failure must not be recorded as an upload. If it were, the twenty-hour gate would skip every
   * retry for the rest of the day, and a till that came back online five minutes later would wait
   * until tomorrow to try again.
   */
  it('a refused upload is not remembered as a success', async () => {
    const { server, port } = await cloudServer((_request, response) => {
      response.writeHead(401);
      response.end('{"detail":"no"}');
    });
    try {
      const backup = await loadBackupModule();
      const file = path.join(root, 'storex-2026-09-01T0314.dump');
      fs.writeFileSync(file, 'refused');
      const config = {
        backendPort: port,
        cloudUrl: `http://127.0.0.1:${port}`,
        cloudToken: 'stale-token',
      };

      expect(await backup.uploadToCloud(config, file, 'storex-2026-09-01T0314.dump')).toBe(
        'failed',
      );
      expect(fs.existsSync(backup.cloudStateFile())).toBe(false);
      // And the next tick tries again rather than reporting a quiet 'not-yet'.
      expect(await backup.uploadToCloud(config, file, 'storex-2026-09-01T0314.dump')).toBe(
        'failed',
      );
    } finally {
      server.close();
    }
  });

  it('a till with no credential is not a failure', async () => {
    const backup = await loadBackupModule();
    const file = path.join(root, 'storex-2026-09-01T0314.dump');
    fs.writeFileSync(file, 'unconnected');

    expect(
      await backup.uploadToCloud(
        { backendPort: 1, cloudUrl: null, cloudToken: null },
        file,
        'storex-2026-09-01T0314.dump',
      ),
    ).toBe('no-credential');
  });

  it('will not guess a terminal code when the till cannot say what it is', async () => {
    const backup = await loadBackupModule();
    const file = path.join(root, 'storex-2026-09-01T0314.dump');
    fs.writeFileSync(file, 'no backend');

    // Port 1 is not listening, so the identity call fails and the archive stays home rather than
    // being filed under a terminal nobody chose.
    expect(
      await backup.uploadToCloud(
        { backendPort: 1, cloudUrl: 'http://127.0.0.1:1', cloudToken: 'till-token' },
        file,
        'storex-2026-09-01T0314.dump',
      ),
    ).toBe('unknown-terminal');
  });
});
