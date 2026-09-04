// Where the installed till keeps its data, and the secrets it generates on first run (M5-01).
//
// ## Everything writable lives outside the install directory
//
// `C:\Program Files\...` is read-only for a standard user and is replaced wholesale by the
// next installer. The database, the logs and the generated credentials therefore live under
// %LOCALAPPDATA%, which survives a reinstall and belongs to the person running the till.
//
// LOCALAPPDATA rather than APPDATA on purpose: APPDATA roams. A shop PC joined to a domain
// would try to synchronise a Postgres data directory to a file server between logins, which
// corrupts it — slowly, and in a way that looks like a disk fault.
//
// ## The password is generated here and never has a default
//
// A shipped default database password is the same password on every till this build ever
// installs. It is generated once, on the machine, and stored in a file only that machine's
// user can read. The same argument as the sync token in application-desktop.yml, which
// deliberately has no default either.

const crypto = require('node:crypto');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');

/**
 * The backend's port, and the one number here that is **not** negotiable.
 *
 * Next bakes `next.config.mjs`'s rewrite destination into the build — it appears in both
 * `server.js` and `routes-manifest.json` — so `/api/*` proxies to whatever the destination
 * said at `next build` time and no environment variable can move it afterwards. A launcher
 * that picked a free port here would produce a till whose screen loads and whose every
 * request goes to a port nothing is listening on.
 *
 * So 8081 is a design constant (CLAUDE.md says as much) rather than a preference. If it is
 * occupied the honest outcome is to say so and stop: the alternative is a window that opens
 * and cannot sell, which is worse than one that does not open. The usual occupant on a
 * developer machine is this project's own `mvnw spring-boot:run`.
 */
const BACKEND_PORT = 8081;

/** Everything the till writes, under one root that a reinstall does not touch. */
function dataRoot() {
  const base = process.env.LOCALAPPDATA ?? path.join(os.homedir(), 'AppData', 'Local');
  return path.join(base, 'StoreX');
}

const paths = {
  root: dataRoot(),
  config: path.join(dataRoot(), 'config'),
  pgdata: path.join(dataRoot(), 'pgdata'),
  logs: path.join(dataRoot(), 'logs'),
  configFile: path.join(dataRoot(), 'config', 'runtime.json'),
};

/**
 * A startup breadcrumb, written before the window exists.
 *
 * The splash can only show a message once it has rendered, and everything interesting about
 * a first launch happens before that. Electron on Windows also detaches from the console, so
 * stdout goes nowhere a support call can reach. This file is what makes "it just sits there"
 * a diagnosable report rather than a guess.
 */
function trace(message) {
  try {
    fs.mkdirSync(paths.logs, { recursive: true });
    fs.appendFileSync(
      path.join(paths.logs, 'startup.log'),
      `${new Date().toISOString()}  ${message}
`,
      'utf8',
    );
  } catch {
    // Diagnostics must never be the reason a till fails to start.
  }
}

function ensureDirectories() {
  for (const directory of [paths.root, paths.config, paths.pgdata, paths.logs]) {
    fs.mkdirSync(directory, { recursive: true });
  }
}

/**
 * True when nothing is listening on the port.
 *
 * Binds rather than connects. A connect-probe reports "free" for a port held by a socket in
 * TIME_WAIT or bound to a different interface, and the bind that follows then fails — which
 * on a till looks like the app refusing to start for no reason.
 */
function portFree(port) {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(false));
    server.once('listening', () => server.close(() => resolve(true)));
    // 0.0.0.0, not 127.0.0.1 — and this distinction cost a real debugging session.
    //
    // Binding a port on the loopback address succeeds even when another process already
    // holds the *same* port on the wildcard address, because they are different sockets.
    // Docker publishes container ports on 0.0.0.0, so with this project's own db-local
    // container running, a loopback probe of 5442 answers "free", the bundled Postgres
    // starts happily on 127.0.0.1:5442 — and the backend, connecting to 127.0.0.1:5442,
    // reaches whichever of the two Windows routes it to. It reached Docker's, reported
    // healthy, and the bundled cluster sat there with no database in it.
    //
    // Probing the wildcard address answers the question actually being asked: is this port
    // number free on this machine? It is the stricter test, and the till moves to 5443.
    server.listen(port, '0.0.0.0');
  });
}

/**
 * The preferred port if it is free, otherwise the next one that is.
 *
 * A till is not the only thing on a shop PC. 5432 in particular is very often already a
 * Postgres somebody installed years ago, which is exactly why the bundled server defaults
 * to 5442 and never touches it — the same reasoning as the dev compose file.
 */
async function choosePort(preferred, attempts = 40) {
  for (let port = preferred; port < preferred + attempts; port += 1) {
    if (await portFree(port)) return port;
  }
  throw new Error(`No free port near ${preferred}`);
}

function readConfig() {
  try {
    return JSON.parse(fs.readFileSync(paths.configFile, 'utf8'));
  } catch {
    return null;
  }
}

function writeConfig(config) {
  fs.writeFileSync(paths.configFile, `${JSON.stringify(config, null, 2)}\n`, {
    encoding: 'utf8',
    // Owner-only where the platform honours it. On Windows the ACL inherited from
    // LOCALAPPDATA is what actually protects this; the mode is not harmful and is correct
    // if this ever runs anywhere else.
    mode: 0o600,
  });
}

/**
 * The machine's runtime configuration, created on first run and stable afterwards.
 *
 * <h2>Ports are re-checked every launch, secrets are not</h2>
 *
 * A port that was free in January can be taken in March by something else the shop
 * installed, so the stored port is a preference rather than a promise. The database
 * password is the opposite: regenerating it would leave a data directory nobody can open,
 * so once written it is never touched again.
 */
async function loadRuntimeConfig() {
  ensureDirectories();
  const existing = readConfig() ?? {};

  const config = {
    // Generated once. See the header for why there is no default.
    dbPassword: existing.dbPassword ?? crypto.randomBytes(24).toString('hex'),
    dbUser: existing.dbUser ?? 'lumora',
    dbName: existing.dbName ?? 'lumora_local',
    // The database and the renderer may move; the backend may not. See BACKEND_PORT.
    dbPort: await choosePort(existing.dbPort ?? 5442),
    backendPort: BACKEND_PORT,
    frontendPort: await choosePort(existing.frontendPort ?? 3000),
    // Written so a crashed launch can be cleaned up by the next one rather than leaving a
    // Postgres holding the data directory against us.
    pids: existing.pids ?? {},
    // The shop's cloud credential (M5-03), saved by the first-run wizard and carried forward
    // untouched on every launch afterwards — the same treatment as dbPassword above, and for a
    // related reason: this is a secret nobody can look up again. The cloud shows a till token
    // exactly once at provisioning, so regenerating or dropping it here would mean a shop that
    // has to be re-credentialled from the platform screen.
    //
    // This file rather than a machine environment variable, which is what DEPLOYMENT.md has
    // instructed for the two pilot shops. `setx /M` needs an elevated prompt, writes somewhere a
    // shopkeeper cannot see or correct, and gives no feedback when it is mistyped — the till
    // sells perfectly and silently never syncs. Here it sits beside the database password under
    // the same 0600 file in LOCALAPPDATA, and the wizard can verify it before saving.
    //
    // Absent is a legitimate state, not a missing setting: a till with no token queues its
    // outbox and loses nothing (see SyncProperties.token), so a shop can trade for days before
    // it is connected. Hence `?? null` rather than a generated value.
    cloudToken: existing.cloudToken ?? null,
    cloudUrl: existing.cloudUrl ?? null,
  };

  writeConfig(config);
  return config;
}

/**
 * Saves the cloud credential the wizard collected.
 *
 * <p>Re-reads the file rather than mutating the config object the caller holds, because the
 * launch sequence writes pids into the same file and the two must not clobber each other.
 */
function saveCloudCredential({ token, url }) {
  const config = readConfig();
  if (!config) {
    throw new Error('No runtime config to write the cloud credential into');
  }
  config.cloudToken = token ?? null;
  config.cloudUrl = url ?? null;
  writeConfig(config);
}

/**
 * The cloud URL and token this till should present, and which of the two sources wins.
 *
 * Extracted because two callers now need it and they must never disagree. backend.cjs passes it
 * to Spring so sales file under the right shop; backup.cjs (M5-06) uploads a dump of the whole
 * database with it. If those two ever resolved the credential differently, a shop's archive would
 * land in another shop's storage — a quieter version of the misfiled-sale bug that produced this
 * rule in the first place, and one nobody would notice until a restore.
 *
 * **The wizard wins over the environment.** A stale machine-level LUMORA_CLOUD_TOKEN, invisible
 * unless you go looking in an elevated prompt, once silently overrode a token somebody had just
 * typed into the wizard and the till spent an afternoon filing its sales under a previous shop.
 * So the order is whatever somebody most recently and deliberately set. The environment still
 * works when the wizard has saved nothing, which is the hand-activated pilot tills exactly.
 *
 * @returns {{url: string|null, token: string|null}} either may be null; a till with no token is a
 *   legitimate state that queues rather than fails.
 */
function cloudCredential(config) {
  return {
    url: config.cloudUrl || process.env.LUMORA_CLOUD_URL || null,
    token: config.cloudToken || process.env.LUMORA_CLOUD_TOKEN || null,
  };
}

/** Records a child's pid so the next launch can clear it if this one dies badly. */
function rememberPid(name, pid) {
  const config = readConfig();
  if (!config) return;
  config.pids = { ...config.pids, [name]: pid };
  writeConfig(config);
}

function forgetPids() {
  const config = readConfig();
  if (!config) return;
  config.pids = {};
  writeConfig(config);
}

module.exports = {
  paths,
  trace,
  ensureDirectories,
  loadRuntimeConfig,
  saveCloudCredential,
  cloudCredential,
  rememberPid,
  forgetPids,
  choosePort,
  portFree,
};
