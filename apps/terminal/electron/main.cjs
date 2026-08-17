// Electron main process — the shell that hosts the terminal.
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

const path = require('node:path');
const { app, BrowserWindow, ipcMain, shell } = require('electron');
const { createPrinterTransport, printerConfigFromEnv } = require('./printerTransport.cjs');

// Dev points at `next dev`; a packaged build points at the standalone server the
// installer starts alongside it (M5-01).
const RENDERER_URL = process.env.LUMORA_RENDERER_URL ?? 'http://127.0.0.1:3000';

// Built once from the environment at startup, not per print: the transport itself holds no
// open connection (see printerTransport.cjs), so there is nothing to keep fresh by rebuilding
// it, and a bad config should surface at startup logs rather than on a cashier's first sale.
const printerTransport = createPrinterTransport(printerConfigFromEnv());

/**
 * The sale is already committed by the time this runs (M1-11) — a print failure here is
 * never allowed to look like the sale itself failed. Errors are returned, not thrown, so the
 * renderer never needs a try/catch just to tell a cashier "receipt didn't print, sale is fine."
 */
ipcMain.handle('printer:print', async (_event, bytes) => {
  try {
    await printerTransport.write(bytes);
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
});

/** @type {BrowserWindow | null} */
let mainWindow = null;

function createWindow() {
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

  mainWindow.once('ready-to-show', () => mainWindow?.show());
  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  // A till is not a browser. Anything that wants a new window opens in the customer's
  // actual browser instead, so there is no way to navigate the terminal away from itself.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    void shell.openExternal(url);
    return { action: 'deny' };
  });

  void mainWindow.loadURL(RENDERER_URL);
}

// One till, one window. A second instance would mean two processes issuing invoice
// numbers from the same terminal block.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(createWindow);

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}
