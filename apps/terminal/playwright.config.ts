import { defineConfig } from '@playwright/test';

/**
 * End-to-end config for the terminal (M1-16).
 *
 * ## There are no browser projects here
 *
 * Every spec launches the **real Electron app** through Playwright's Electron API rather
 * than a Chromium page pointed at the dev server. A till is not a browser tab, and this
 * project has twice refused a stand-in that would pass while the real thing failed — H2
 * instead of Postgres, and Testcontainers instead of the compose database. Electron is the
 * same call: the one M1-14 bug that actually reached a user (`main.cjs` throwing before a
 * window opened, so the app would not start at all) lived entirely in the main process, and
 * no amount of Chromium would have caught it.
 *
 * It also matters that Gate M1 is executed against this window. A spec that proves the
 * keyboard path somewhere else proves it about somewhere else.
 *
 * ## What has to be running
 *
 * Three things, and two of them are started for you:
 *
 *   1. **Postgres** — `pnpm db:up`, seeded with `pnpm db:seed`. Not started here; the
 *      global setup fails with that instruction rather than guessing.
 *   2. **The backend**, desktop profile on :8081. Started below, reused if already up.
 *   3. **Next**, on :3000. Started below, reused if already up.
 *
 * `reuseExistingServer` is on for both so a developer already running `pnpm dev` pays
 * nothing, and CI (which has neither) gets them started.
 */
export default defineConfig({
  testDir: './e2e',

  // A sale writes to a real database and issues a real invoice number from a real block.
  // Two specs doing that at once would interleave their invoice sequences and race on the
  // watermark the teardown uses to clean up. This suite is deliberately serial.
  workers: 1,
  fullyParallel: false,

  // Nothing here is retried. A keyboard-only sale that only works on the second attempt is
  // a failing till, and a retry would hide exactly the flakiness worth knowing about.
  retries: 0,

  timeout: 60_000,
  expect: { timeout: 10_000 },

  reporter: process.env.CI ? [['github'], ['list']] : [['list']],

  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',

  webServer: [
    {
      // The Maven wrapper, not `mvn` — there is no `mvn` on PATH on this machine.
      command: 'mvnw.cmd -q spring-boot:run',
      cwd: '../../services/backend',
      url: 'http://127.0.0.1:8081/actuator/health',
      reuseExistingServer: true,
      // A cold Spring Boot start with a Flyway migration pass is not quick.
      timeout: 180_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      // A production build, not `next dev`: the dev server compiles routes on demand, so the
      // first run of a suite races a webpack build that later runs do not. For a spec whose
      // subject is keystroke timing that is disqualifying — it failed cold and passed warm,
      // which is the worst way for a test to behave.
      //
      // `next start` serves the ordinary optimised build. It is **not** the standalone output
      // the installer will ship (M5-01) — Next warns as much, because `output: 'standalone'`
      // wants `node .next/standalone/server.js` plus a static-asset copy step. Serving it
      // properly here would mean reimplementing that packaging in a test config, so this
      // deliberately stops at "production build" and leaves "the artefact that ships" to
      // M5-01, where it belongs and can be verified once.
      //
      // `reuseExistingServer` still lets a developer point the suite at their own `pnpm dev`
      // while iterating; it just is not what an unattended run gets.
      command: 'pnpm exec next build && pnpm exec next start -p 3000',
      url: 'http://127.0.0.1:3000',
      reuseExistingServer: true,
      timeout: 300_000,
    },
  ],
});
