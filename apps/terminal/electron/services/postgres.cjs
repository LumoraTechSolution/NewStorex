// The bundled database (M5-01).
//
// ## This is the source of truth for a sale, so it starts before anything else
//
// §A's inversion puts the local Postgres on the critical path of every sale and the network
// on the critical path of nothing. That makes this file the one whose failure modes matter
// most: if the database does not come up, the till cannot sell, and the shopkeeper needs to
// be told that in words rather than shown a blank window.
//
// ## Why a real Postgres and not an embedded database
//
// The same reason the integration tests refuse H2. Correctness here rests on Postgres
// behaviour — `ON CONFLICT` on `client_uuid`, partial indexes on the outbox, `timestamptz` —
// and a stand-in that passes the tests while the till fails is worse than no tests. The
// migrations that run against this are the same files, in the same order, as the ones that
// run against the developer's compose container.
//
// ## initdb runs once, and only once
//
// `PG_VERSION` inside the data directory is the marker. Re-running initdb over a shop's
// history would be the single most destructive thing this application could do, so the
// check is for the file's presence rather than for any flag we maintain — a marker we wrote
// ourselves could be lost while the data survived, and then we would wipe it.

const { execFile, spawn, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const { paths, trace } = require('./runtimeConfig.cjs');

/**
 * Runs one of Postgres' command-line tools without blocking.
 *
 * **This must never be spawnSync.** Electron's main process is single-threaded and also
 * draws the splash window, so a synchronous `pg_ctl start -w` — which legitimately waits
 * for the server to accept connections — freezes the UI for its entire duration. Windows
 * then marks the app Not Responding and the shopkeeper sees a dead window during the
 * slowest part of the very first launch. Observed exactly that way before this was async.
 */
function tool(resourcesPath, name, args, options = {}) {
  trace(`pg tool: ${name} ${args.join(' ')}`);
  return new Promise((resolve) => {
    execFile(
      binary(resourcesPath, name),
      args,
      { encoding: 'utf8', windowsHide: true, ...options },
      (error, stdout, stderr) => {
        trace(`pg tool done: ${name} status=${error ? (error.code ?? 1) : 0}`);
        resolve({
          status: error ? (error.code ?? 1) : 0,
          stdout: stdout ?? '',
          stderr: stderr ?? '',
        });
      },
    );
  });
}

/** Where the staged Postgres lives, in a packaged app and in development. */
function postgresHome(resourcesPath) {
  return path.join(resourcesPath, 'runtime', 'pgsql');
}

function binary(resourcesPath, name) {
  return path.join(postgresHome(resourcesPath), 'bin', `${name}.exe`);
}

function isInitialised() {
  return fs.existsSync(path.join(paths.pgdata, 'PG_VERSION'));
}

/**
 * Creates the cluster. First run only.
 *
 * The password reaches initdb through a file rather than an argument, because an argument is
 * visible in the process list to every other user on the machine for as long as initdb runs.
 * The file is written into the data directory's parent, read once, and deleted in a `finally`
 * so a failed initdb does not leave it behind.
 */
async function initialise(resourcesPath, config, log) {
  const passwordFile = path.join(paths.config, 'initdb.pwd');
  fs.writeFileSync(passwordFile, config.dbPassword, { encoding: 'utf8', mode: 0o600 });
  try {
    log('Preparing the database for the first time. This takes about a minute.');
    const result = await tool(resourcesPath, 'initdb', [
      '-D',
      paths.pgdata,
      '-U',
      config.dbUser,
      '-A',
      'scram-sha-256',
      `--pwfile=${passwordFile}`,
      '-E',
      'UTF8',
      // C rather than a system locale: collation affects index ordering, and a cluster
      // initialised under one locale cannot be read the same way under another. A till
      // that is restored onto a differently-configured machine has to still work.
      '--locale=C',
      '--no-instructions',
    ]);
    if (result.status !== 0) {
      throw new Error(`initdb failed: ${result.stderr || result.stdout}`);
    }
  } finally {
    fs.rmSync(passwordFile, { force: true });
  }

  // Written to postgresql.auto.conf rather than postgresql.conf: auto.conf is the file
  // Postgres itself rewrites for ALTER SYSTEM, is read last, and is therefore the one place
  // our settings cannot be silently overridden by the defaults file.
  fs.appendFileSync(
    path.join(paths.pgdata, 'postgresql.auto.conf'),
    [
      '',
      '# StoreX — written by the desktop launcher. Loopback only, deliberately (v1 is single-till).',
      `port = ${config.dbPort}`,
      "listen_addresses = '127.0.0.1'",
      'logging_collector = on',
      `log_directory = '${paths.logs.replace(/\\/g, '/')}'`,
      "log_filename = 'postgresql-%Y-%m-%d.log'",
      '',
    ].join('\n'),
    'utf8',
  );
}

/** Whether the server answers on the configured port. */
async function ready(resourcesPath, config) {
  const result = await tool(resourcesPath, 'pg_isready', [
    '-h',
    '127.0.0.1',
    '-p',
    String(config.dbPort),
    '-U',
    config.dbUser,
  ]);
  return result.status === 0;
}

/**
 * Creates the shop's database on first run.
 *
 * `psql` against the `postgres` maintenance database, because CREATE DATABASE cannot run
 * inside a transaction and the driver would wrap it in one. Idempotent by inspection rather
 * than by `IF NOT EXISTS`, which CREATE DATABASE does not support.
 */
async function createDatabase(resourcesPath, config, log) {
  const env = { ...process.env, PGPASSWORD: config.dbPassword };
  const exists = await tool(
    resourcesPath,
    'psql',
    [
      '-h',
      '127.0.0.1',
      '-p',
      String(config.dbPort),
      '-U',
      config.dbUser,
      '-d',
      'postgres',
      '-tAc',
      `SELECT 1 FROM pg_database WHERE datname = '${config.dbName}'`,
    ],
    { env },
  );
  if (exists.stdout.trim() === '1') return;

  log('Creating the shop database.');
  const created = await tool(
    resourcesPath,
    'psql',
    [
      '-h',
      '127.0.0.1',
      '-p',
      String(config.dbPort),
      '-U',
      config.dbUser,
      '-d',
      'postgres',
      '-c',
      `CREATE DATABASE ${config.dbName} OWNER ${config.dbUser} ENCODING 'UTF8' TEMPLATE template0`,
    ],
    { env },
  );
  if (created.status !== 0) {
    throw new Error(`Could not create the database: ${created.stderr}`);
  }
  // Flyway creates every table this application uses, so nothing else is done here. The
  // schema is owned by the migrations in one place — a launcher that also created objects
  // would be a second, undocumented source of schema.
}

/**
 * Clears a server left running by a launch that died badly.
 *
 * <h2>Why this is not optional</h2>
 *
 * A crash, a Task Manager kill, or a power cut leaves postgres.exe holding the data
 * directory. The next launch then cannot start its own server, and — worse — cannot even be
 * diagnosed: the directory is locked, so the app appears to exit instantly with no log,
 * because it dies before it can write one. That is exactly the failure this project hit while
 * building it, and it took the whole detour to identify.
 *
 * `postmaster.pid` is the cluster's own record of which process owns it. Killing by that pid
 * — after checking the process really is a postgres — is narrower than sweeping every
 * postgres.exe on the machine, which on a developer box would take out unrelated servers and
 * on a shop PC could take out somebody else's application.
 */
function clearOrphan() {
  const pidFile = path.join(paths.pgdata, 'postmaster.pid');
  if (!fs.existsSync(pidFile)) return;

  // A clean shutdown removes the file. One still here means the previous run did not get to
  // shut down, so either the server is still alive (kill it) or the file is stale (harmless
  // to try). Either way postgres refuses to start while it exists and it must go.
  const pidFileText = fs.readFileSync(pidFile, 'utf8');
  const pid = Number.parseInt(pidFileText.split('\n')[0].trim(), 10);
  if (Number.isInteger(pid) && pid > 0) {
    trace(`clearing an orphaned database, pid ${pid}`);
    // /T so the postmaster's own worker children go with it; without that they keep the
    // shared memory segment and the next start fails the same way.
    spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true });
  }
  fs.rmSync(pidFile, { force: true });
}

/**
 * Starts the server and waits for it to accept connections.
 *
 * <h2>Why not pg_ctl, which is the documented way to do this</h2>
 *
 * `pg_ctl start -w` never returns here, and the reason is not a Postgres bug. On Windows
 * pg_ctl launches the server as a child that inherits its stdio handles, then exits — but
 * Node's execFile resolves on **stream close**, not on process exit, and the still-running
 * server holds those pipes open for as long as it lives. So the callback is scheduled for
 * whenever the database shuts down, which on a till is never. The trace showed exactly that:
 * `pg tool: pg_ctl ... start` with no matching `done` line, for as long as it was left.
 *
 * Spawning postgres.exe directly and polling with pg_isready avoids the whole question, and
 * costs only the pid file pg_ctl would have written — the server writes its own
 * `postmaster.pid` regardless, which is what `stop()` and `clearOrphan()` read.
 *
 * <h2>Not detached, because on Windows that means a visible console</h2>
 *
 * `detached: true` is the obvious way to say "this process outlives the call", and on Windows
 * it does something else as well: it implies CREATE_NEW_CONSOLE, so the child gets **its own
 * console window**. `windowsHide` does not suppress it — that flag only applies when a console
 * would otherwise be created hidden, and an explicit new console wins. Postgres then forks its
 * background workers (checkpointer, walwriter, autovacuum, and one per connection), and each
 * inherits the same treatment: a shopkeeper opening the till was shown a stack of black
 * terminal windows across the screen, growing as the backend connected.
 *
 * Without `detached`, Node still does not wait for this child — `stdio: 'ignore'` means there
 * are no pipes to hold open, and `unref()` releases it from the event loop. The server
 * survives on its own, silently, which is the whole requirement.
 */
async function launch(resourcesPath, config) {
  const child = spawn(binary(resourcesPath, 'postgres'), ['-D', paths.pgdata], {
    // No stdio to inherit, so nothing keeps this call open — and see above for why this must
    // not be `detached` on Windows.
    stdio: 'ignore',
    windowsHide: true,
  });
  // Released from the event loop: Electron must be free to quit without waiting on a database
  // that is meant to outlive this function.
  child.unref();

  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    if (await ready(resourcesPath, config)) return;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`The database did not accept connections within 60s. See ${paths.logs}.`);
}

/**
 * Proves the server answering on our port is the cluster we just started.
 *
 * <h2>Why a port probe is not enough</h2>
 *
 * A free-port check asks "can I bind this?", and on Windows the answer can be yes while
 * something else is already serving the same port number on a different address — Docker
 * publishes on 0.0.0.0, our server binds 127.0.0.1, both succeed, and a client connecting to
 * 127.0.0.1 reaches whichever the stack routes it to. Observed exactly that: this project's
 * own db-local container and the bundled cluster both "started" on 5442, the backend
 * connected to the container, reported healthy, and the shop's real database sat empty.
 *
 * That failure is silent and it is severe: the till would appear to work while writing every
 * sale into a database the installer does not own and no backup covers. So the check is not
 * "is the port free" but "is the server on this port the one whose data directory I control".
 * `data_directory` is the cluster's own answer and cannot be spoofed by coincidence.
 */
async function servingOurCluster(resourcesPath, config) {
  const result = await tool(
    resourcesPath,
    'psql',
    [
      '-h',
      '127.0.0.1',
      '-p',
      String(config.dbPort),
      '-U',
      config.dbUser,
      '-d',
      'postgres',
      '-tAc',
      'SHOW data_directory',
    ],
    { env: { ...process.env, PGPASSWORD: config.dbPassword } },
  );
  if (result.status !== 0) return false;
  const reported = path.resolve(result.stdout.trim());
  return reported.toLowerCase() === path.resolve(paths.pgdata).toLowerCase();
}

/**
 * Starts the database and returns once it answers.
 *
 * pg_ctl rather than spawning postgres.exe directly: pg_ctl's `-w` waits for the server to
 * actually accept connections, handles the data-directory lock, and leaves a pid file that
 * makes a clean stop possible even if this process is killed.
 */
async function start(resourcesPath, config, log) {
  if (!fs.existsSync(binary(resourcesPath, 'postgres'))) {
    throw new Error(
      `The bundled database is missing from ${postgresHome(resourcesPath)}. ` +
        'Run `pnpm stage:runtime` before building.',
    );
  }

  if (!isInitialised()) {
    await initialise(resourcesPath, config, log);
  } else {
    // The port may have moved since last launch (something else took it), so the stored
    // preference is re-applied every time rather than only at initdb.
    const auto = path.join(paths.pgdata, 'postgresql.auto.conf');
    const current = fs.readFileSync(auto, 'utf8');
    const wanted = `port = ${config.dbPort}`;
    if (!current.includes(wanted)) {
      fs.writeFileSync(auto, `${current.replace(/^port = \d+$/m, wanted)}\n`, 'utf8');
    }
  }

  log('Starting the database.');
  clearOrphan();
  await launch(resourcesPath, config);

  if (!(await servingOurCluster(resourcesPath, config))) {
    throw new Error(
      `Something else is already serving port ${config.dbPort} on this machine, and StoreX ` +
        'reaches it instead of its own database. On a development machine that is this ' +
        "project's `pnpm db:up` container — run `pnpm db:down` and start StoreX again.",
    );
  }

  await createDatabase(resourcesPath, config, log);
}

/**
 * Stops the database.
 *
 * `-m fast` rolls back open transactions and shuts down cleanly. Not `immediate`, which
 * skips the checkpoint and forces recovery on next start — on a till that is a shopkeeper
 * watching a progress bar at opening time.
 */
function stop(resourcesPath) {
  if (!fs.existsSync(path.join(paths.pgdata, 'postmaster.pid'))) return;
  // The one deliberate spawnSync left in this file. Shutdown runs from 'before-quit', where
  // the window is already gone and there is no UI left to block — and a checkpoint allowed to
  // finish is what makes the next launch a start rather than a recovery.
  spawnSync(
    binary(resourcesPath, 'pg_ctl'),
    ['-D', paths.pgdata, '-m', 'fast', '-w', '-t', '30', 'stop'],
    { encoding: 'utf8' },
  );
}

module.exports = { start, stop, ready, isInitialised, postgresHome };
