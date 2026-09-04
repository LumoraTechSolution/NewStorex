// The Spring Boot backend, started by the launcher (M5-01).
//
// ## The desktop profile is not a variant, it is the product
//
// One jar, two profiles. `desktop` binds 127.0.0.1 and composes the common + desktop
// migrations; `cloud` is the other half of the same build. Nothing here overrides the
// profile's own decisions — the port and the database URL are passed because they are
// machine facts the launcher discovers, and everything else stays where a reader would
// look for it, in application-desktop.yml.
//
// ## Configuration travels as environment, never as arguments
//
// The database password would otherwise appear in the Windows process list for anybody
// on the machine to read. Spring's relaxed binding maps SPRING_DATASOURCE_PASSWORD onto
// the same property the YAML names, so nothing about the backend has to know it was
// launched this way.

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const { paths, rememberPid, cloudCredential } = require('./runtimeConfig.cjs');

function javaBinary(resourcesPath) {
  return path.join(resourcesPath, 'runtime', 'jre', 'bin', 'java.exe');
}

function jarPath(resourcesPath) {
  return path.join(resourcesPath, 'runtime', 'backend', 'lumora-backend.jar');
}

/**
 * Polls the health endpoint until it reports UP.
 *
 * Health, not "the port is open": Flyway runs during startup, and on a first launch with a
 * virus scanner watching every file it can take the better part of a minute. A launcher that
 * proceeded on an open socket would show the cashier a window whose every request 503s.
 */
async function waitForHealth(port, timeoutMs, log) {
  const deadline = Date.now() + timeoutMs;
  let announced = false;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/actuator/health`);
      if (response.ok) {
        const body = await response.json();
        if (body.status === 'UP') return;
      }
    } catch {
      // Not up yet. The loop is the retry.
    }
    if (!announced && Date.now() > deadline - timeoutMs + 15_000) {
      log('Still preparing the shop database. First launch takes longer.');
      announced = true;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(
    `The till's service did not start within ${Math.round(timeoutMs / 1000)}s. ` +
      `See ${path.join(paths.logs, 'backend.log')}.`,
  );
}

/**
 * Starts the backend and resolves once it is serving.
 *
 * @returns the child process, so the caller can shut it down.
 */
async function start(resourcesPath, config, log) {
  const jar = jarPath(resourcesPath);
  if (!fs.existsSync(jar)) {
    throw new Error(`The till's service is missing from ${jar}. Run \`pnpm stage:runtime\`.`);
  }

  // The port cannot move (see BACKEND_PORT in runtimeConfig.cjs), so an occupied one is a
  // stop rather than something to work around. Said plainly here because the alternative is
  // a JVM that fails to bind and a splash that reports a 120-second timeout — technically
  // true, and useless to whoever has to fix it.
  const { portFree } = require('./runtimeConfig.cjs');
  if (!(await portFree(config.backendPort))) {
    throw new Error(
      `Port ${config.backendPort} is already in use, and the till's service cannot use another one. ` +
        'Close whatever is using it — on a development machine that is usually this ' +
        'project’s own `mvnw spring-boot:run` — and start StoreX again.',
    );
  }

  const cloud = cloudCredential(config);

  log('Starting the till.');
  const child = spawn(
    javaBinary(resourcesPath),
    [
      // A till is one process on a shop PC that may only have 4 GB. Left unbounded the JVM
      // sizes its heap from total RAM and takes far more than this workload needs.
      '-Xmx512m',
      '-XX:+UseSerialGC',
      // Startup time is what a cashier waits on every morning, and tiered compilation's
      // higher tiers only pay off in a process that runs hot for hours.
      '-XX:TieredStopAtLevel=1',
      '-jar',
      jar,
    ],
    {
      env: {
        ...process.env,
        SPRING_PROFILES_ACTIVE: 'desktop',
        SERVER_PORT: String(config.backendPort),
        LOCAL_DATABASE_URL: `jdbc:postgresql://127.0.0.1:${config.dbPort}/${config.dbName}`,
        LOCAL_DATABASE_USER: config.dbUser,
        LOCAL_DB_PASSWORD: config.dbPassword,
        LOGGING_FILE_NAME: path.join(paths.logs, 'backend.log'),
        // The shop's cloud credential, saved by the first-run wizard into runtime.json (M5-03).
        //
        // **The wizard wins over the environment, and it took a misfiled sale to get here.**
        //
        // The first version of this deferred to a machine-level LUMORA_CLOUD_TOKEN, reasoning that
        // the two pilot tills activated by hand with `setx /M` must not be silently de-activated
        // by an upgrade. That reasoning protected the wrong thing. A stale machine variable —
        // left over from a previous shop, invisible unless you go looking in an elevated prompt —
        // silently overrode a token somebody had just typed into the wizard, and the till spent
        // the afternoon filing its sales under a different shop. The cloud behaved correctly
        // throughout: `TenantAuthFilter` derives the tenant from the token and never from the
        // request, so a wrong token is a wrong shop, reported nowhere and noticed at the till by
        // nobody.
        //
        // So the order is now: whatever somebody most recently and deliberately set. Running the
        // wizard is that act; an environment variable set months ago on a machine that has since
        // been re-provisioned is not. The environment still works when the wizard has saved
        // nothing, which is exactly the pilot-till case the old order was trying to protect —
        // those tills have no stored token, so they are unaffected.
        //
        // Spring reads these at boot (`application-desktop.yml`), and `HttpCloudSyncClient` bakes
        // the token into its RestClient in its constructor — so a token saved by the wizard takes
        // effect on the next launch, not immediately. That is why the wizard says so on screen
        // rather than pretending otherwise.
        //
        // Resolved by cloudCredential() rather than read here, because backup.cjs (M5-06) needs
        // the same answer and a second copy of this precedence rule is a second chance to get it
        // wrong - this time by uploading one shop's whole database into another shop's storage.
        ...(cloud.token ? { LUMORA_CLOUD_TOKEN: cloud.token } : {}),
        ...(cloud.url ? { LUMORA_CLOUD_URL: cloud.url } : {}),
      },
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    },
  );

  // Spring writes its own rolling log via LOGGING_FILE_NAME; this catches what escapes
  // before logging is configured, which is exactly where a misconfiguration shows up.
  const early = fs.createWriteStream(path.join(paths.logs, 'backend-startup.log'), {
    flags: 'a',
  });
  child.stdout.pipe(early);
  child.stderr.pipe(early);

  rememberPid('backend', child.pid);
  await waitForHealth(config.backendPort, 120_000, log);
  return child;
}

/**
 * Stops the backend, giving it a chance to finish what it is doing.
 *
 * A till is allowed to be mid-write when somebody closes the window: a sale is one
 * transaction, so the worst case is a rollback of something not yet committed. The wait is
 * for the JVM to release the database connections cleanly, so Postgres' own shutdown does
 * not have to terminate a backend.
 */
async function stop(child) {
  if (!child || child.exitCode !== null) return;
  child.kill();
  await new Promise((resolve) => {
    const timer = setTimeout(() => {
      // Windows has no SIGKILL that a JVM honours through Node's kill(); taskkill /T also
      // takes any child it spawned.
      try {
        require('node:child_process').spawnSync('taskkill', [
          '/PID',
          String(child.pid),
          '/T',
          '/F',
        ]);
      } catch {
        // Already gone.
      }
      resolve();
    }, 10_000);
    child.once('exit', () => {
      clearTimeout(timer);
      resolve();
    });
  });
}

module.exports = { start, stop };
