#!/usr/bin/env node
/* eslint-disable no-console -- a command-line script; stdout is its user interface */
/**
 * Assembles everything the installer ships, then builds it (M5-01, M5-02).
 *
 *   pnpm dist
 *
 * Runs, in order:
 *
 *   1. the backend jar            (mvnw package)
 *   2. the terminal's web build   (next build → .next/standalone)
 *   3. staging into runtime/      (jre and pgsql come from `pnpm stage:runtime`)
 *   4. electron-builder           → apps/terminal/dist/StoreX-Setup-<version>.exe
 *
 * ## Why the web output is re-assembled here rather than pointed at
 *
 * `output: 'standalone'` produces a tree that is *almost* runnable and deliberately
 * incomplete: Next omits `.next/static` and `public` from it, because in its intended
 * deployment those are served by a CDN. Nothing serves them on a shop PC, so they are copied
 * in. This is documented Next behaviour, not a workaround.
 *
 * The second surprise is the shape. In a monorepo the entry point is
 * `.next/standalone/apps/terminal/server.js`, not `.next/standalone/server.js`, and the only
 * node_modules in the tree sits beside `apps/` — `server.js` has none of its own and relies
 * on Node resolving upward to it. So `runtime/web` is a copy of the whole standalone tree,
 * nesting intact. Flattening it to put server.js at the top is the obvious tidy-up and
 * breaks every require at runtime.
 */

import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '..');
const terminal = join(repo, 'apps', 'terminal');
const runtime = join(repo, 'runtime');

/**
 * Runs one build step, inheriting stdio so its output is this script’s output.
 *
 * `shell` is opt-in and used only where it is genuinely required — `pnpm` and `mvnw.cmd`
 * are batch scripts Windows cannot execute directly. Every argument passed through here is
 * a literal in this file, never caller input, which is the hazard Node’s DEP0190 warns
 * about when a shell is combined with an argument array.
 */
function run(command, args, cwd, { shell = false } = {}) {
  console.log(`\n> ${command} ${args.join(' ')}`);
  const result = spawnSync(command, args, { cwd, stdio: 'inherit', shell });
  if (result.status !== 0) {
    throw new Error(`${command} exited ${result.status}`);
  }
}

function requireStaged() {
  const missing = ['jre/bin/java.exe', 'pgsql/bin/postgres.exe'].filter(
    (relative) => !existsSync(join(runtime, relative)),
  );
  if (missing.length > 0) {
    throw new Error(
      `runtime/ is not staged (missing ${missing.join(', ')}). Run \`pnpm stage:runtime\` first.`,
    );
  }
}

function buildBackend() {
  const backend = join(repo, 'services', 'backend');
  // An absolute path, not the bare name: the wrapper lives in the module directory and is
  // not on PATH (CLAUDE.md — there is no `mvn` on this machine either, which is the point
  // of the wrapper). `shell: true` on Windows would otherwise resolve it against PATH only.
  const wrapper = join(backend, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw');
  run(`"${wrapper}"`, ['-B', '-DskipTests', 'package'], backend, { shell: true });

  const target = join(backend, 'target');
  const jar = join(target, 'lumora-backend-0.0.1-SNAPSHOT.jar');
  if (!existsSync(jar)) {
    throw new Error(`Expected the backend jar at ${jar}`);
  }
  const into = join(runtime, 'backend');
  mkdirSync(into, { recursive: true });
  // Renamed on the way in: the launcher looks for a stable name, so a version bump in the
  // pom does not silently produce an installer whose launcher cannot find its own backend.
  cpSync(jar, join(into, 'lumora-backend.jar'));
  console.log('backend jar staged');
}

function buildWeb() {
  run('pnpm', ['--filter', '@lumora/terminal', 'build'], repo, { shell: true });

  const next = join(terminal, '.next');
  const standalone = join(next, 'standalone');
  const inner = join(standalone, 'apps', 'terminal');
  if (!existsSync(join(inner, 'server.js'))) {
    throw new Error(`Expected the standalone server at ${join(inner, 'server.js')}`);
  }

  // Next leaves these out of the standalone tree on purpose — see the header.
  cpSync(join(next, 'static'), join(inner, '.next', 'static'), { recursive: true });
  const publicDir = join(terminal, 'public');
  if (existsSync(publicDir)) {
    cpSync(publicDir, join(inner, 'public'), { recursive: true });
  }

  // Copied whole, with the `apps/terminal/` nesting intact. `server.js` has no bundled
  // node_modules of its own and relies on Node walking *up* the tree to the one beside
  // `apps/` — so flattening the directory, which is the obvious tidy-up, breaks every
  // require at runtime. The launcher therefore points at
  // `runtime/web/apps/terminal/server.js` rather than at `runtime/web/server.js`.
  const into = join(runtime, 'web');
  rmSync(into, { recursive: true, force: true });
  cpSync(standalone, into, { recursive: true });
  console.log('web build staged');
}

try {
  requireStaged();
  buildBackend();
  buildWeb();
  run('pnpm', ['exec', 'electron-builder', '--win', '--x64'], terminal, { shell: true });
  console.log('\nInstaller written to apps/terminal/dist/');
} catch (error) {
  console.error(`\nBuild failed: ${error.message}`);
  process.exit(1);
}
