// Backups, and the restore that proves they are worth having (M5-04, M5-05).
//
// ## Why a POS needs this more than most software does
//
// The whole architecture puts a shop's entire trading history on one disk in one shop: §A's
// local-first rule is what makes the till work with the cable out, and the price of it is that
// the disk under the counter is the only copy that matters. The cloud has a copy of what synced
// — which is not the same thing, and `restore.md` says exactly where the two differ.
//
// A grocer does not have a backup strategy. They have a PC that either still has last year's
// sales on it or does not, and nobody finds out which until the morning it does not boot.
//
// ## pg_dump, not a file copy
//
// Copying `pgdata` while Postgres is running produces a corrupt archive that restores cleanly
// right up until it does not, which is the worst possible failure for a backup. `pg_dump` takes a
// consistent snapshot of a live database and is already in the bundled runtime — M5-01 pruned
// Postgres to 102 MB and kept `pg_dump.exe`, `pg_restore.exe` and `psql.exe`, so this costs
// nothing to ship.
//
// The custom format (`-Fc`) rather than plain SQL: it is compressed, and `pg_restore` can read a
// table list out of it without restoring anything — which is what makes `verify()` below able to
// check an archive is readable rather than merely present.
//
// ## What "a second location" means on a shop PC
//
// M5-04 says a second location and a single hard disk cannot give one. What this does is write
// somewhere a person can point at a USB stick or a network drive — `LUMORA_BACKUP_DIR` — and
// default to a folder beside the data when nothing is set. That default is honest rather than
// safe: it survives an uninstall and a corrupted database, and it does not survive the disk
// dying. The wizard says so, and M5-06's cloud copy is the answer to the disk.

const fs = require('node:fs');
const path = require('node:path');
const { execFile } = require('node:child_process');
const crypto = require('node:crypto');
const http = require('node:http');
const https = require('node:https');

const { paths, trace, cloudCredential } = require('./runtimeConfig.cjs');

/** Keep a month. Long enough to notice a corruption that happened while nobody was looking. */
const KEEP_DAYS = 30;

/**
 * Twelve hours, not twenty-four.
 *
 * A daily backup taken at 3am is a backup a shop that closes at 8pm and switches the PC off never
 * takes. Twice a day, counted from launch, means an ordinary trading day produces at least one
 * whether or not the machine is left on overnight — and the first one lands shortly after opening,
 * when yesterday is the thing worth not losing.
 */
const INTERVAL_MS = 12 * 60 * 60 * 1000;

/**
 * A few minutes after launch, not at it.
 *
 * The first minutes of a shop's morning are the busiest thing this process does — Postgres
 * starting, Flyway migrating, the renderer building its first screen — and a `pg_dump` competing
 * with that is felt by the cashier who is already waiting. Nothing about a backup is urgent enough
 * to be part of a startup.
 */
const FIRST_RUN_DELAY_MS = 5 * 60 * 1000;

function backupDir() {
  // An operator can point this at a USB stick, a NAS, or a synced folder. Read from the
  // environment rather than from runtime.json because it is a deployment choice, not a shop
  // setting — the person who sets it is the one installing the till.
  return process.env.LUMORA_BACKUP_DIR || path.join(paths.root, 'backups');
}

/** `storex-2026-09-01T0314.dump` — sorts chronologically as text, which the pruning relies on. */
function backupName(now = new Date()) {
  const iso = now.toISOString();
  return `storex-${iso.slice(0, 10)}T${iso.slice(11, 13)}${iso.slice(14, 16)}.dump`;
}

function pgTool(resourcesPath, name, args, env) {
  return new Promise((resolve) => {
    execFile(
      path.join(resourcesPath, 'runtime', 'pgsql', 'bin', `${name}.exe`),
      args,
      { encoding: 'utf8', windowsHide: true, env: { ...process.env, ...env } },
      (error, stdout, stderr) => {
        resolve({
          status: error ? (error.code ?? 1) : 0,
          stdout: stdout ?? '',
          stderr: stderr ?? '',
        });
      },
    );
  });
}

/**
 * Takes one backup.
 *
 * Writes to a `.part` file and renames on success, so a dump interrupted by a power cut or a quit
 * cannot leave a truncated archive sitting in the folder looking like a good one. The rename is
 * atomic on NTFS. This matters more than it sounds: the whole value of a backup folder is that
 * everything in it can be trusted, and a half-written file is worse than a missing one because it
 * is the one somebody restores from.
 *
 * @returns {Promise<{ok: true, file: string, bytes: number} | {ok: false, error: string}>}
 */
async function runBackup(resourcesPath, config) {
  const directory = backupDir();
  try {
    fs.mkdirSync(directory, { recursive: true });
  } catch (e) {
    return { ok: false, error: `Cannot create ${directory}: ${e.message}` };
  }

  const target = path.join(directory, backupName());
  const partial = `${target}.part`;

  trace(`backup: starting → ${target}`);
  const result = await pgTool(
    resourcesPath,
    'pg_dump',
    [
      '-h',
      '127.0.0.1',
      '-p',
      String(config.dbPort),
      '-U',
      config.dbUser,
      '-d',
      config.dbName,
      // Custom format: compressed, and readable by `pg_restore --list` without restoring.
      '-Fc',
      // The role that owns the objects is created by our own initdb, so ownership and grants are
      // noise that only makes a restore into a fresh database harder.
      '--no-owner',
      '--no-privileges',
      '-f',
      partial,
    ],
    // Never on the command line: an argument is visible in the process list to every other user
    // on the machine. postgres.cjs makes the same choice for initdb, for the same reason.
    { PGPASSWORD: config.dbPassword },
  );

  if (result.status !== 0) {
    try {
      fs.rmSync(partial, { force: true });
    } catch {
      /* nothing to clean up */
    }
    return { ok: false, error: result.stderr.trim() || `pg_dump exited ${result.status}` };
  }

  fs.renameSync(partial, target);
  const bytes = fs.statSync(target).size;
  trace(`backup: done ${target} (${bytes} bytes)`);
  return { ok: true, file: target, bytes };
}

/**
 * Checks an archive is actually readable, rather than merely present.
 *
 * `pg_restore --list` parses the archive's table of contents without writing anything. A backup
 * that exists and cannot be read is the failure this catches, and it is the common one — a full
 * disk, a USB stick pulled mid-write, a folder synced by something that truncated it.
 *
 * This is not the same as M5-05's restore test, and does not pretend to be: a readable table of
 * contents does not prove the data restores. `restore.md` is the procedure that proves that, and
 * it has to be run by a person against a real database.
 */
async function verify(resourcesPath, file) {
  const result = await pgTool(resourcesPath, 'pg_restore', ['--list', file], {});
  if (result.status !== 0) {
    return { ok: false, error: result.stderr.trim() || `pg_restore exited ${result.status}` };
  }
  // A dump of an empty database still lists a handful of header entries, so "parses" is not
  // enough on its own — a shop's archive names its tables.
  const tables = (result.stdout.match(/TABLE DATA/g) ?? []).length;
  return { ok: true, tables };
}

/**
 * Deletes backups older than {@link KEEP_DAYS}.
 *
 * Deliberately by age and not by count. "Keep the last 10" on a machine that was switched on
 * twice last month keeps two months; on one left running it keeps five days. Age is what a
 * shopkeeper means when they ask how far back they can go.
 *
 * Failures here are logged and swallowed: a folder that cannot be pruned is untidy, and treating
 * it as a backup failure would report a problem the shop does not have.
 */
function prune(now = Date.now()) {
  const directory = backupDir();
  const cutoff = now - KEEP_DAYS * 24 * 60 * 60 * 1000;
  let removed = 0;
  try {
    for (const entry of fs.readdirSync(directory)) {
      if (!entry.startsWith('storex-') || !entry.endsWith('.dump')) continue;
      const full = path.join(directory, entry);
      try {
        if (fs.statSync(full).mtimeMs < cutoff) {
          fs.rmSync(full);
          removed += 1;
        }
      } catch {
        /* a file that vanished under us is one less to delete */
      }
    }
  } catch (e) {
    trace(`backup: could not prune ${directory}: ${e.message}`);
  }
  return removed;
}

// ---------------------------------------------------------------------------- M5-06
//
// ## The copy that is somewhere else
//
// Everything above answers the accident: a bad migration, a deleted table, an index that rotted.
// None of it answers the disk. `LUMORA_BACKUP_DIR` can be pointed at a USB stick and mostly is
// not, and the default folder beside the data is on the same disk that died.
//
// So a verified archive goes to the cloud, using the shop's own till credential and nothing else.
// The cloud stores the bytes and keeps two weeks of them; `restore.md` is what somebody follows
// with one in their hand.
//
// ## Once a day, not twice
//
// The local backup runs every twelve hours because it is free. This one crosses a shop's
// connection, which in Sri Lanka is often a mobile one somebody pays for by the gigabyte, and a
// second copy of the same day's trading is worth very little. Twenty hours rather than
// twenty-four so a shop that opens at eight today and nine tomorrow still gets one every day
// rather than skipping every third.
//
// ## Nothing here can stop a backup, let alone a sale
//
// A failed upload leaves the local archive exactly where it was and returns. The next verified
// backup offers the newest one again. There is no queue, no retry timer and no state to corrupt,
// because the thing being retried is "send the most recent archive" and that is idempotent by
// construction — the cloud keys on the archive's own name.

const CLOUD_MIN_INTERVAL_MS = 20 * 60 * 60 * 1000;

/** Where "when did we last manage this" lives. Beside runtime.json, never in the backup folder:
 *  that folder may be a USB stick nobody plugged in today, and losing the state there would mean
 *  re-uploading the whole database every twelve hours over somebody's mobile data. */
function cloudStateFile() {
  return path.join(paths.config, 'backup-cloud.json');
}

function readCloudState() {
  try {
    return JSON.parse(fs.readFileSync(cloudStateFile(), 'utf8'));
  } catch {
    // Absent on a till that has never uploaded, and unreadable on one where something went wrong.
    // Both mean the same thing to the only caller: upload now.
    return {};
  }
}

function writeCloudState(state) {
  try {
    fs.writeFileSync(cloudStateFile(), JSON.stringify(state, null, 2), { mode: 0o600 });
  } catch (e) {
    // A state file we cannot write means the next tick uploads again a few hours early. That is
    // wasteful and harmless, and it is not worth failing an upload that already succeeded.
    trace(`backup: could not record the cloud upload: ${e.message}`);
  }
}

function sha256OfFile(file) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    const stream = fs.createReadStream(file);
    stream.on('error', reject);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('end', () => resolve(hash.digest('hex')));
  });
}

/**
 * Asks the till's own backend which terminal it is.
 *
 * Not read from runtime.json, because it is not there and should not be: the terminal code is a
 * shop fact that lives in the database the wizard wrote, and a second copy of it in a config file
 * is a second thing to be wrong when somebody renames a till. `/api/setup/identity` is the same
 * endpoint the renderer uses for the receipt header.
 */
function terminalCode(config) {
  return new Promise((resolve) => {
    const request = http.get(
      { host: '127.0.0.1', port: config.backendPort, path: '/api/setup/identity', timeout: 5000 },
      (response) => {
        let body = '';
        response.setEncoding('utf8');
        response.on('data', (chunk) => (body += chunk));
        response.on('end', () => {
          try {
            resolve(response.statusCode === 200 ? (JSON.parse(body).terminalCode ?? null) : null);
          } catch {
            resolve(null);
          }
        });
      },
    );
    request.on('error', () => resolve(null));
    request.on('timeout', () => {
      request.destroy();
      resolve(null);
    });
  });
}

/**
 * Sends one archive to the cloud.
 *
 * The file is streamed rather than read into memory: a shop with a few years of history has a
 * dump larger than the renderer, and this runs in the same process as the window.
 *
 * @returns {Promise<{ok: true, alreadyHeld: boolean} | {ok: false, error: string, permanent: boolean}>}
 *   `permanent` separates "this will never work" — a rejected name, a bad credential — from
 *   "the connection dropped". Only the first is worth saying loudly; the second is a shop with
 *   bad internet, which is the normal condition this whole product is built around.
 */
function putArchive({ url, token }, file, name, terminal, sha256, bytes) {
  return new Promise((resolve) => {
    let target;
    try {
      target = new URL('/api/sync/backup', url);
    } catch {
      resolve({ ok: false, error: `Not a usable cloud URL: ${url}`, permanent: true });
      return;
    }
    const transport = target.protocol === 'https:' ? https : http;

    const request = transport.request(
      target,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/octet-stream',
          'Content-Length': bytes,
          'X-Backup-Terminal': terminal,
          'X-Backup-Name': name,
          'X-Backup-Taken-At': new Date(fs.statSync(file).mtimeMs).toISOString(),
          'X-Backup-Sha256': sha256,
        },
        // Generous. A first upload on a slow shop connection is minutes, and giving up early
        // would mean a till that never manages one at all.
        timeout: 15 * 60 * 1000,
      },
      (response) => {
        let body = '';
        response.setEncoding('utf8');
        response.on('data', (chunk) => (body += chunk));
        response.on('end', () => {
          const status = response.statusCode ?? 0;
          if (status >= 200 && status < 300) {
            let alreadyHeld = false;
            try {
              alreadyHeld = JSON.parse(body).alreadyHeld === true;
            } catch {
              // A 2xx we could not parse still means the cloud has it.
            }
            resolve({ ok: true, alreadyHeld });
            return;
          }
          resolve({
            ok: false,
            error: `cloud answered ${status}: ${body.slice(0, 300)}`,
            // 401 and 403 are in here deliberately: an unactivated or revoked till will not
            // become activated by trying again in twenty hours, and saying so plainly in the log
            // is how somebody finds out before a disk dies rather than after.
            permanent: status === 400 || status === 401 || status === 403 || status === 422,
          });
        });
      },
    );

    request.on('error', (e) => resolve({ ok: false, error: e.message, permanent: false }));
    request.on('timeout', () => {
      request.destroy();
      resolve({ ok: false, error: 'the upload timed out', permanent: false });
    });

    const body = fs.createReadStream(file);
    body.on('error', (e) => {
      request.destroy();
      resolve({ ok: false, error: `could not read ${file}: ${e.message}`, permanent: true });
    });
    body.pipe(request);
  });
}

/**
 * Uploads the archive just taken, if it is time and the till is credentialled.
 *
 * @returns {Promise<'sent' | 'held' | 'not-yet' | 'no-credential' | 'unknown-terminal' | 'failed'>}
 *   named rather than boolean because every one of these is a different thing to read in a log at
 *   the point somebody is trying to find out why there is no copy in the cloud.
 */
async function uploadToCloud(config, file, name, now = Date.now()) {
  const credential = cloudCredential(config);
  if (!credential.token || !credential.url) {
    // Legitimate: a shop can trade for days before somebody connects it. The outbox behaves the
    // same way and neither is a failure.
    return 'no-credential';
  }

  const state = readCloudState();
  if (state.lastUploadedAtMs && now - state.lastUploadedAtMs < CLOUD_MIN_INTERVAL_MS) {
    return 'not-yet';
  }

  const terminal = await terminalCode(config);
  if (!terminal) {
    // The backend is down, or this till has no shop yet. Either way there is nothing to file the
    // archive under, and guessing a terminal code would file it under the wrong one.
    trace('backup: cloud upload skipped, the till could not say which terminal it is');
    return 'unknown-terminal';
  }

  const bytes = fs.statSync(file).size;
  const sha256 = await sha256OfFile(file);
  trace(`backup: uploading ${name} (${bytes} bytes) to ${credential.url}`);
  const result = await putArchive(credential, file, name, terminal, sha256, bytes);

  if (!result.ok) {
    const message = `backup: cloud upload failed - ${result.error}`;
    trace(message);
    if (result.permanent) console.error(message);
    return 'failed';
  }

  writeCloudState({ lastUploadedAtMs: now, lastUploadedName: name, lastUploadedSha256: sha256 });
  trace(`backup: cloud holds ${name}${result.alreadyHeld ? ' (already had it)' : ''}`);
  return result.alreadyHeld ? 'held' : 'sent';
}

/**
 * Starts the schedule. Returns a stop function for shutdown.
 *
 * Everything here is best-effort by design: a failed backup writes a line to the log and the shop
 * carries on selling. That is not laziness about backups — it is the same rule as the printer and
 * the outbox. Nothing that is not the sale itself may ever stop the sale, and a till that refused
 * to trade because a USB stick was full would be a worse product than one with no backups at all.
 */
function startSchedule(resourcesPath, config) {
  let timer = null;
  let stopped = false;

  const tick = async () => {
    if (stopped) return;
    const result = await runBackup(resourcesPath, config);
    if (!result.ok) {
      console.error('Backup failed:', result.error);
      trace(`backup: FAILED ${result.error}`);
      return;
    }
    const checked = await verify(resourcesPath, result.file);
    if (!checked.ok) {
      // Kept rather than deleted. An unreadable archive is evidence about what went wrong, and
      // deleting it would leave a folder that looks fine and a problem nobody can diagnose.
      console.error('Backup written but not readable:', checked.error);
      trace(`backup: UNVERIFIED ${result.file}: ${checked.error}`);
      return;
    }
    trace(`backup: verified ${result.file} (${checked.tables} tables)`);
    prune();
    // After pruning, not before: the local copy is the one a shop can restore from this
    // afternoon, and it must not wait on somebody's mobile connection to be tidied.
    await uploadToCloud(config, result.file, path.basename(result.file));
  };

  timer = setTimeout(function repeat() {
    void tick().finally(() => {
      if (!stopped) timer = setTimeout(repeat, INTERVAL_MS);
    });
  }, FIRST_RUN_DELAY_MS);

  return () => {
    stopped = true;
    if (timer) clearTimeout(timer);
  };
}

module.exports = {
  startSchedule,
  uploadToCloud,
  cloudStateFile,
  CLOUD_MIN_INTERVAL_MS,
  runBackup,
  verify,
  prune,
  backupDir,
  backupName,
  KEEP_DAYS,
  INTERVAL_MS,
};
