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
});
