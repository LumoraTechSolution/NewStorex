export {};

/** The bridge `electron/preload.cjs` exposes. Undefined outside the desktop shell — `next dev`
 * in a plain browser tab, for instance — which is why every call site treats it as optional. */
declare global {
  interface Window {
    lumora?: {
      shell: {
        isDesktop: boolean;
        platform: string;
        electron: string;
      };
      printer: {
        print(bytes: Uint8Array): Promise<{ ok: true } | { ok: false; error: string }>;
      };
    };
  }
}
