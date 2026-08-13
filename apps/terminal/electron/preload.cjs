// The entire surface the renderer gets from Node. Keep it small and explicit — every
// function added here is one the renderer can be tricked into calling.

const { contextBridge } = require('electron');

contextBridge.exposeInMainWorld('lumora', {
  shell: {
    // Lets the renderer tell it is running inside the desktop app rather than a browser
    // tab. Printing and the drawer only exist in the former.
    isDesktop: true,
    platform: process.platform,
    electron: process.versions.electron,
  },

  // Printing and drawer control land here in M1-14, as ipcRenderer.invoke calls that
  // return once main has written the bytes.
});
