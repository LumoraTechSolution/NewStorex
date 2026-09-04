// The entire surface the renderer gets from Node. Keep it small and explicit — every
// function added here is one the renderer can be tricked into calling.

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('lumora', {
  shell: {
    // Lets the renderer tell it is running inside the desktop app rather than a browser
    // tab. Printing and the drawer only exist in the former.
    isDesktop: true,
    platform: process.platform,
    electron: process.versions.electron,
  },

  printer: {
    /**
     * @param {Uint8Array} bytes a full ESC/POS buffer — a receipt, or a receipt with the
     *   drawer kick appended (see `buildReceiptWithDrawerKick`). One IPC round trip either
     *   way; the drawer is opened by the printer the bytes reach, not by a second command.
     * @returns {Promise<{ok: true} | {ok: false, error: string}>}
     */
    print: (bytes) => ipcRenderer.invoke('printer:print', bytes),
  },

  updates: {
    /**
     * What the update channel is doing (M5-11). Read-only, and there is deliberately no
     * `installNow`: an update is applied when the shop closes StoreX and at no other moment, so
     * a button that could trigger one would be a button that can stop a till mid-shift.
     *
     * @returns {Promise<{status: string, version?: string} | null>}
     */
    state: () => ipcRenderer.invoke('update:state'),

    /** @param {(event: {status: string, version?: string}) => void} listener */
    onChange: (listener) => {
      const wrapped = (_event, payload) => listener(payload);
      ipcRenderer.on('update:state', wrapped);
      return () => ipcRenderer.removeListener('update:state', wrapped);
    },
  },

  setup: {
    /**
     * Writes the shop's cloud token to `runtime.json` (M5-03). Called once, by the last step
     * of the first-run wizard, and by nothing else.
     *
     * Write-only on purpose: there is no matching `read`. The renderer never needs the token
     * back — the backend is what presents it to the cloud — and a getter here would put a
     * credential one XSS away from a page that has no other use for it.
     *
     * @param {{token: string, url?: string | null}} credential
     * @returns {Promise<{ok: true} | {ok: false, error: string}>}
     */
    saveCloudCredential: (credential) =>
      ipcRenderer.invoke('setup:saveCloudCredential', credential),
  },
});
