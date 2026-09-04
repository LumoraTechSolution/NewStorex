// Electron main process — the shell that hosts the terminal, and in a packaged build the
// thing that starts the shop's entire stack (M5-01).
//
// Plain CommonJS rather than TypeScript. M1-14 is the "real work" the old version of this
// comment expected to be the trigger for a build step (ESC/POS bytes over serial/USB, the
// drawer kick) — it landed as `printerTransport.cjs`, tested directly as CommonJS with
// vitest instead. That gets the actual goal (real logic, genuinely tested) without taking
// on a TypeScript-for-Electron-main build pipeline as a second, separate piece of work.
//
// In v1 this process is also what removes QZ Tray from the picture: the renderer sends
// IPC, main writes to the printer directly, and the unsigned-certificate problem that
// plagued the browser-based approach simply does not exist.
//
// ## Two modes, and only one of them starts anything
//
// In development (`pnpm dev` plus `pnpm --filter @lumora/terminal electron`) the database is
// the compose container, the backend is `mvnw spring-boot:run`, and the renderer is
// `next dev` — all started by hand, all restartable independently, which is what makes them
// pleasant to work on. This process then only opens a window.
//
// In a packaged build there is no Docker, no Maven and no dev server on the shop PC, so this
// process starts Postgres, the backend jar and the standalone Next server itself, in that
// order, and shuts them down in reverse. `app.isPackaged` is the switch. It is deliberately
// not an environment variable: a shop PC must never be one stray variable away from
// expecting a developer's toolchain.

const path = require('node:path');
const { app, BrowserWindow, dialog, ipcMain, shell } = require('electron');
const { createPrinterTransport, printerConfigFromEnv } = require('./printerTransport.cjs');

// Dev points at `next dev`; a packaged build points at the standalone server this process
// starts below, on whichever port was actually free.
const DEV_RENDERER_URL = process.env.LUMORA_RENDERER_URL ?? 'http://127.0.0.1:3000';

/**
 * The last thing the updater said, or null (M5-11).
 *
 * Held here rather than only pushed, because the renderer can reload — a back-office navigation,
 * a crash, a developer refreshing — and a message pushed before that reload is gone. A till that
 * silently stops mentioning an update it has already downloaded is worse than one that never
 * mentioned it: the update is applied on the next quit either way, and nobody was told why.
 */
let updateState = null;

// Built once from the environment at startup, not per print: the transport itself holds no
// open connection (see printerTransport.cjs), so there is nothing to keep fresh by rebuilding
// it. Construction can still throw on a malformed config (e.g. LUMORA_PRINTER_TRANSPORT=serial
// with no path) — caught here rather than left to propagate, because a config mistake must
// degrade to "printing is broken" and nothing more. It must never be able to crash the whole
// app before a single window has opened, which is what an uncaught throw at this point did.
let printerTransport;
let printerTransportError;
try {
  printerTransport = createPrinterTransport(printerConfigFromEnv());
} catch (e) {
  printerTransportError = e instanceof Error ? e.message : String(e);
  console.error('Printer transport misconfigured, printing disabled:', printerTransportError);
}

/**
 * The sale is already committed by the time this runs (M1-11) — a print failure here is
 * never allowed to look like the sale itself failed. Errors are returned, not thrown, so the
 * renderer never needs a try/catch just to tell a cashier "receipt didn't print, sale is fine."
 */
ipcMain.handle('printer:print', async (_event, bytes) => {
  if (!printerTransport) {
    return { ok: false, error: `Printer not configured: ${printerTransportError}` };
  }
  try {
    await printerTransport.write(bytes);
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
});

/**
 * What the update channel has to say, if anything (M5-11).
 *
 * A pull as well as a push. The renderer reloads - a back-office navigation, a crash, a developer
 * refreshing - and a message pushed before that reload is simply gone, which would leave the till
 * silently no longer mentioning an update it has already downloaded and will apply on the next
 * quit. Asking on mount closes that hole.
 */
ipcMain.handle('update:state', () => updateState);

/**
 * Saves the cloud credential the first-run wizard collected (M5-03).
 *
 * The renderer cannot write this itself — it has no filesystem, which is the point of
 * `sandbox: true` — and it must not be asked to hold the token in localStorage, where it would
 * outlive the wizard in a place a page can read. So the token crosses this bridge once, on the
 * last step of setup, and lives in `runtime.json` beside the database password.
 *
 * Errors are returned rather than thrown, like the printer above: the shop is already created by
 * the time this runs, and a failure to save the token must read as "not connected yet" rather
 * than as "setup failed" — the two have very different recoveries, and only one of them is
 * "type your shop's name again".
 */
ipcMain.handle('setup:saveCloudCredential', async (_event, credential) => {
  try {
    const { saveCloudCredential } = require('./services/runtimeConfig.cjs');
    const token = typeof credential?.token === 'string' ? credential.token.trim() : '';
    const url = typeof credential?.url === 'string' ? credential.url.trim() : '';
    if (!token) {
      return { ok: false, error: 'No token given' };
    }
    saveCloudCredential({ token, url: url || null });
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
});

/** @type {BrowserWindow | null} */
let mainWindow = null;
/** @type {BrowserWindow | null} */
let splashWindow = null;

/** The stack this process owns in a packaged build. Empty in development. */
const started = { backend: null, frontend: null, resourcesPath: null, stopBackups: null };

function createSplash() {
  splashWindow = new BrowserWindow({
    width: 520,
    height: 360,
    frame: false,
    resizable: false,
    backgroundColor: '#0A0E12',
    show: true,
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  void splashWindow.loadFile(path.join(__dirname, 'splash.html'));
  return splashWindow;
}

/** Pushes a line of progress onto the splash, if it is still open. */
function say(message) {
  console.warn(`[startup] ${message}`);
  // Also to disk. Electron detaches from the console on Windows, so this is the only record
  // a support call can ask for — see trace() in runtimeConfig.cjs.
  try {
    require('./services/runtimeConfig.cjs').trace(message);
  } catch {
    // Never let logging be the reason startup fails.
  }
  if (splashWindow && !splashWindow.isDestroyed()) {
    const literal = JSON.stringify(message);
    void splashWindow.webContents.executeJavaScript(`window.setStatus(${literal})`).catch(() => {});
  }
}

function createWindow(url) {
  mainWindow = new BrowserWindow({
    width: 1366,
    height: 850,
    minWidth: 1024,
    minHeight: 700,
    // Matches the terminal's dark appliance page colour, so startup does not flash white
    // at a cashier in a dim shop.
    backgroundColor: '#0A0E12',
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      // The renderer is untrusted by construction. Nothing it can do should reach Node
      // except through the narrow surface preload.cjs exposes.
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  mainWindow.once('ready-to-show', () => {
    mainWindow?.show();
    if (splashWindow && !splashWindow.isDestroyed()) splashWindow.close();
    splashWindow = null;
  });
  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  // A till is not a browser. Anything that wants a new window opens in the customer's
  // actual browser instead, so there is no way to navigate the terminal away from itself.
  //
  // The scheme is checked before the URL leaves this process, and that check is the whole
  // point of the handler rather than a formality. `shell.openExternal` hands what it is
  // given to the Windows shell, which honours far more than http — `file:` runs a local
  // executable, and the registered protocol handlers (`ms-msdt:` and its relatives) have
  // their own history of doing worse. Nothing in the renderer opens a window today, so
  // this is shut before it is reachable: the alternative is remembering to add it on the
  // day somebody puts a supplier's link in the back office.
  mainWindow.webContents.setWindowOpenHandler(({ url: target }) => {
    let scheme;
    try {
      scheme = new URL(target).protocol;
    } catch {
      // Not a URL at all. Nothing to hand to the shell.
      return { action: 'deny' };
    }
    if (scheme === 'https:' || scheme === 'http:') {
      void shell.openExternal(target);
    } else {
      console.warn('Refused to open a non-web URL from the renderer:', scheme);
    }
    return { action: 'deny' };
  });

  void mainWindow.loadURL(url);
}

/**
 * Starts Postgres, the backend and the renderer, in that order, and returns the URL to load.
 *
 * The order is the dependency order and is not negotiable: the backend runs Flyway against
 * the database on startup, and the renderer proxies /api to the backend. Each step waits for
 * the previous one to be genuinely serving rather than merely spawned — see the health check
 * in backend.cjs for why "the port is open" is not the same question.
 */
async function startStack() {
  const postgres = require('./services/postgres.cjs');
  const backend = require('./services/backend.cjs');
  const frontend = require('./services/frontend.cjs');
  const { loadRuntimeConfig, forgetPids } = require('./services/runtimeConfig.cjs');

  const resourcesPath = process.resourcesPath;
  started.resourcesPath = resourcesPath;

  const config = await loadRuntimeConfig();
  forgetPids();

  say('database: starting');
  await postgres.start(resourcesPath, config, say);
  say('database: ready');
  started.backend = await backend.start(resourcesPath, config, say);
  say('backend: ready');
  started.frontend = await frontend.start(resourcesPath, config, say);
  say('renderer: ready');

  // Backups, started last and never waited on (M5-04). Nothing about the schedule is part of
  // getting a till trading, and its first run is minutes away by design — see backup.cjs on why
  // a pg_dump must not compete with the busiest moment this process has.
  const backup = require('./services/backup.cjs');
  started.stopBackups = backup.startSchedule(resourcesPath, config);

  // The update channel (M5-11), started last and never waited on either. It refuses itself in
  // development and on any build that is not code-signed — see updater.cjs, which is where the
  // decision lives and where the reason is logged.
  const updater = require('./services/updater.cjs');
  started.stopUpdates = updater.startSchedule(
    { isPackaged: app.isPackaged, resourcesPath },
    (event) => {
      // Held rather than pushed at a window that may not exist yet: this fires ten minutes into
      // a launch at the earliest, but a renderer reload would lose a pushed message and the till
      // would stop mentioning an update that is genuinely waiting.
      updateState = event;
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('update:state', event);
      }
    },
  );

  return `http://127.0.0.1:${config.frontendPort}/`;
}

/**
 * Shuts the stack down in reverse.
 *
 * Postgres last and cleanly, because it is the only one of the three holding anything that
 * cannot be recreated. A `fast` shutdown rolls back whatever was open and checkpoints, which
 * is what makes the next launch fast rather than a recovery.
 */
async function stopStack() {
  const backend = require('./services/backend.cjs');
  const frontend = require('./services/frontend.cjs');
  const postgres = require('./services/postgres.cjs');
  const { forgetPids } = require('./services/runtimeConfig.cjs');

  // First, so a dump in flight is not left half-written by the database going away underneath
  // it. The partial file is discarded on the next launch either way — backup.cjs writes to
  //  and renames — but stopping the timer is what keeps a quit from starting a new one.
  if (started.stopBackups) started.stopBackups();
  // Before the stack, so a check cannot start a download into a process that is on its way out.
  // Anything already downloaded is installed by the NSIS updater after this process exits, which
  // is the only moment it is ever allowed to happen.
  if (started.stopUpdates) started.stopUpdates();

  frontend.stop(started.frontend);
  await backend.stop(started.backend);
  if (started.resourcesPath) postgres.stop(started.resourcesPath);
  forgetPids();
}

// One till, one window. A second instance would mean two processes issuing invoice
// numbers from the same terminal block — and, in a packaged build, two processes trying to
// open the same Postgres data directory.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(async () => {
    if (!app.isPackaged) {
      createWindow(DEV_RENDERER_URL);
      return;
    }

    createSplash();
    try {
      createWindow(await startStack());
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      console.error('Startup failed:', message);
      if (splashWindow && !splashWindow.isDestroyed()) {
        const literal = JSON.stringify(message);
        await splashWindow.webContents
          .executeJavaScript(`window.setFailed(${literal})`)
          .catch(() => {});
      }
      // A dialog as well as the splash text, because the splash is frameless and a
      // shopkeeper needs a button to press rather than a window they have to guess how to
      // close. The message names the log file: this is the moment support gets called.
      dialog.showErrorBox(
        'StoreX could not start',
        `${message}\n\nThe logs are in %LOCALAPPDATA%\\StoreX\\logs.`,
      );
      await stopStack();
      app.exit(1);
    }
  });

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0 && !app.isPackaged) {
      createWindow(DEV_RENDERER_URL);
    }
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });

  // Shutdown is async and `before-quit` is not, so the quit is cancelled once, the stack is
  // stopped, and then quit is called again. Without this Electron tears the process down
  // while Postgres is still checkpointing, which forces recovery on the next launch.
  let shuttingDown = false;
  app.on('before-quit', (event) => {
    if (!app.isPackaged || shuttingDown) return;
    event.preventDefault();
    shuttingDown = true;
    stopStack()
      .catch((error) => console.error('Shutdown problem:', error))
      .finally(() => app.quit());
  });
}
