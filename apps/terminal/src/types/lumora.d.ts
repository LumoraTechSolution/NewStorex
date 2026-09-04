export {};

/** The bridge `electron/preload.cjs` exposes. Undefined outside the desktop shell — `next dev`
 * in a plain browser tab, for instance — which is why every call site treats it as optional. */
declare global {
  /** What the update channel has to report. Only ever "ready" today: an update is downloaded and
   *  waiting for the next quit, because that is the only moment one is ever applied (M5-11). */
  interface UpdateState {
    status: 'ready';
    version?: string;
  }

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
      updates: {
        /** Null until the channel has something to say. Read-only by design — see `preload.cjs`. */
        state(): Promise<UpdateState | null>;
        /** Returns an unsubscribe function. */
        onChange(listener: (event: UpdateState) => void): () => void;
      };
      setup: {
        /** Write-only, and there is deliberately no matching read — see `preload.cjs`. */
        saveCloudCredential(credential: {
          token: string;
          url?: string | null;
        }): Promise<{ ok: true } | { ok: false; error: string }>;
      };
    };
  }
}
