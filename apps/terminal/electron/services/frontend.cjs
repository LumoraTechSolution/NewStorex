// The Next.js server that renders the till (M5-01).
//
// ## Why a server at all, rather than static files
//
// `output: 'export'` would let the window load files from disk with no process to manage.
// It is not available here: next.config.mjs rewrites `/api/*` onto the local backend, which
// is what keeps the renderer same-origin with its API and means there is no CORS surface on
// the process that owns the shop's money. A static export has no rewrite layer, so the
// renderer would have to call a different origin and the whole question would come back.
//
// ## It runs inside Electron's own Node
//
// `process.execPath` with ELECTRON_RUN_AS_NODE=1 is the Electron binary behaving as Node.
// The alternative is bundling a second Node runtime, which would be ~50 MB of a thing the
// installer already contains, and a second version to keep patched.
//
// ## The standalone output nests one level deeper than the docs suggest
//
// In a monorepo, `next build` writes `.next/standalone/apps/terminal/server.js` rather than
// `.next/standalone/server.js`, because it mirrors the workspace layout so that relative
// requires into hoisted node_modules still resolve. The packaging config mirrors that shape
// exactly; flattening it would break every workspace import at runtime.

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const { paths, rememberPid } = require('./runtimeConfig.cjs');

/**
 * The standalone entry point, at the depth Next actually writes it.
 *
 * `runtime/web` mirrors `.next/standalone` exactly, nesting and all, because `server.js`
 * carries no node_modules of its own and depends on Node resolving *upward* to the one
 * beside `apps/`. Flattening the tree is the obvious tidy-up and breaks every require.
 */
function serverPath(resourcesPath) {
  return path.join(resourcesPath, 'runtime', 'web', 'apps', 'terminal', 'server.js');
}

/** Polls the root page until it answers. */
async function waitForServer(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/`);
      if (response.ok) return;
    } catch {
      // Not listening yet.
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`The till's screen did not start within ${Math.round(timeoutMs / 1000)}s.`);
}

async function start(resourcesPath, config, log) {
  const server = serverPath(resourcesPath);
  if (!fs.existsSync(server)) {
    throw new Error(`The till's screen is missing from ${server}.`);
  }

  log('Opening the till.');
  const child = spawn(process.execPath, [server], {
    cwd: path.dirname(server),
    env: {
      ...process.env,
      ELECTRON_RUN_AS_NODE: '1',
      PORT: String(config.frontendPort),
      HOSTNAME: '127.0.0.1',
      NODE_ENV: 'production',
      // Deliberately NOT passing LUMORA_BACKEND_URL: next.config.mjs reads it at *build*
      // time and Next freezes the resolved destination into server.js and
      // routes-manifest.json. Setting it here would look like configuration and do nothing.
      // That is why the backend's port is a constant rather than something we choose —
      // see BACKEND_PORT in runtimeConfig.cjs.
    },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });

  const log_ = fs.createWriteStream(path.join(paths.logs, 'frontend.log'), { flags: 'a' });
  child.stdout.pipe(log_);
  child.stderr.pipe(log_);

  rememberPid('frontend', child.pid);
  await waitForServer(config.frontendPort, 60_000);
  return child;
}

function stop(child) {
  if (!child || child.exitCode !== null) return;
  child.kill();
}

module.exports = { start, stop };
