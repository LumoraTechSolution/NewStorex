#!/usr/bin/env node
/* eslint-disable no-console -- a command-line script; stdout is its user interface */
/**
 * Stages the third-party runtime the installer bundles (M5-01).
 *
 *   pnpm stage:runtime
 *
 * Produces `runtime/`, which is gitignored and rebuildable:
 *
 *   runtime/jre/      a trimmed Java 17 runtime, built with jlink from the local JDK
 *   runtime/pgsql/    Postgres 16 for Windows, pruned to the binaries a till needs
 *
 * ## Why these are staged rather than committed
 *
 * They are ~135 MB of someone else's build output. Committing them would put a
 * third party's binaries in this project's history forever, and make every clone
 * pay for them. Staging is reproducible: the JRE comes from the JDK already
 * required to build the backend, and the Postgres zip is a pinned URL with its
 * size checked on arrival.
 *
 * ## Why jlink rather than shipping a whole JRE
 *
 * A full Temurin JRE is ~180 MB and most of it is modules a Spring Boot service
 * never loads. jlink builds one containing only the modules named below, which
 * comes out around 45 MB. The module list is deliberately explicit and slightly
 * generous: `jdeps` on a Spring Boot fat jar reports only `java.base,java.sql`
 * because it sees the launcher and not the nested libraries, so trusting it would
 * produce a JRE that boots and then dies on the first JDBC call. Every module here
 * is one Spring, Hikari, Postgres' driver or Flyway is known to reach for at
 * runtime. Verified the only way that means anything: by starting the real jar on
 * the result and watching /actuator/health come UP.
 *
 * ## Why Postgres is pruned rather than shipped whole
 *
 * The EnterpriseDB zip is ~300 MB and carries pgAdmin, StackBuilder, headers,
 * documentation and every locale. A till needs the server, initdb, pg_ctl and
 * psql. What is removed is listed below rather than expressed as a keep-list,
 * because a keep-list silently drops anything a future Postgres adds.
 */

import { spawnSync } from 'node:child_process';
import { createWriteStream, existsSync, mkdirSync, rmSync, statSync } from 'node:fs';
import { readdir, rm } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '..');
const runtime = join(repo, 'runtime');
const downloads = join(runtime, '_download');

/**
 * Pinned, because "latest" is not a thing an installer can be built against twice.
 * When this moves, the Flyway migrations are the thing to re-run first — the shop's
 * data directory is initialised by whatever initdb ships here.
 */
const POSTGRES_URL =
  'https://get.enterprisedb.com/postgresql/postgresql-16.6-1-windows-x64-binaries.zip';

/**
 * Modules the backend actually needs. See the header for why this is not `jdeps` output.
 *
 * The less obvious ones: `java.desktop` because AWT classes are reachable from the
 * imaging and font paths Spring touches at startup; `java.instrument` because Spring
 * Boot's own agent hooks want it; `jdk.crypto.ec` because without it TLS to the cloud
 * negotiates and then fails on the curve; `jdk.unsupported` because Netty and several
 * libraries reach for `sun.misc.Unsafe`.
 */
const JRE_MODULES = [
  'java.base',
  'java.sql',
  'java.naming',
  'java.desktop',
  'java.management',
  'java.instrument',
  'java.security.jgss',
  'java.security.sasl',
  'jdk.crypto.ec',
  'jdk.unsupported',
  'java.net.http',
  'java.xml',
  'java.transaction.xa',
  'jdk.zipfs',
  'java.compiler',
  'java.rmi',
  'java.scripting',
  'java.prefs',
].join(',');

/** Directories inside the Postgres zip a till has no use for. */
const POSTGRES_PRUNE = ['doc', 'include', 'pgAdmin 4', 'StackBuilder', 'symbols', 'stackbuilder'];

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { stdio: 'inherit', ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} exited ${result.status}`);
  }
}

function javaHome() {
  const fromEnv = process.env.JAVA_HOME;
  const candidates = [fromEnv, 'C:/Program Files/Java/jdk-17'].filter(Boolean);
  for (const candidate of candidates) {
    if (existsSync(join(candidate, 'bin', 'jlink.exe'))) return candidate;
  }
  throw new Error(
    'No JDK 17 found. Set JAVA_HOME to a JDK (not a JRE — jlink ships only with the JDK).',
  );
}

async function stageJre() {
  const out = join(runtime, 'jre');
  if (existsSync(join(out, 'bin', 'java.exe'))) {
    console.log('jre     already staged');
    return;
  }
  const jdk = javaHome();
  console.log(`jre     building with jlink from ${jdk}`);
  rmSync(out, { recursive: true, force: true });
  run(join(jdk, 'bin', 'jlink.exe'), [
    '--add-modules',
    JRE_MODULES,
    '--strip-debug',
    '--no-header-files',
    '--no-man-pages',
    '--compress=2',
    '--output',
    out,
  ]);
  console.log('jre     staged');
}

async function download(url, to) {
  if (existsSync(to) && statSync(to).size > 0) {
    console.log(`pgsql   using cached ${to}`);
    return;
  }
  console.log(`pgsql   downloading ${url}`);
  const response = await fetch(url);
  if (!response.ok || !response.body) {
    throw new Error(`Download failed: HTTP ${response.status}`);
  }
  mkdirSync(dirname(to), { recursive: true });
  await pipeline(Readable.fromWeb(response.body), createWriteStream(to));
}

async function stagePostgres() {
  const out = join(runtime, 'pgsql');
  if (existsSync(join(out, 'bin', 'postgres.exe'))) {
    console.log('pgsql   already staged');
    return;
  }
  const zip = join(downloads, 'postgresql-16-windows-x64-binaries.zip');
  await download(POSTGRES_URL, zip);

  console.log('pgsql   extracting');
  const extracted = join(downloads, 'pgsql-extract');
  rmSync(extracted, { recursive: true, force: true });
  mkdirSync(extracted, { recursive: true });
  // PowerShell's Expand-Archive rather than a Node unzip dependency: it is already on
  // every Windows machine that can run this build, and this script is Windows-only by
  // definition — it stages Windows binaries for a Windows installer.
  run('powershell', [
    '-NoProfile',
    '-NonInteractive',
    '-Command',
    `Expand-Archive -LiteralPath '${zip}' -DestinationPath '${extracted}' -Force`,
  ]);

  // The zip contains a single `pgsql/` directory.
  const inner = join(extracted, 'pgsql');
  if (!existsSync(inner)) {
    throw new Error(`Expected ${inner} inside the archive`);
  }
  rmSync(out, { recursive: true, force: true });
  run('powershell', [
    '-NoProfile',
    '-NonInteractive',
    '-Command',
    `Move-Item -LiteralPath '${inner}' -Destination '${out}'`,
  ]);

  for (const directory of POSTGRES_PRUNE) {
    await rm(join(out, directory), { recursive: true, force: true });
  }

  // Locales are the long tail: every one ships its own message catalogue, and a Sri
  // Lankan shop reads Postgres' errors in English or not at all.
  const locales = join(out, 'share', 'locale');
  if (existsSync(locales)) {
    for (const entry of await readdir(locales)) {
      if (entry !== 'en') {
        await rm(join(locales, entry), { recursive: true, force: true });
      }
    }
  }

  rmSync(extracted, { recursive: true, force: true });
  console.log('pgsql   staged');
}

try {
  mkdirSync(runtime, { recursive: true });
  await stageJre();
  await stagePostgres();
  console.log('\nruntime/ is ready. `pnpm dist` builds the installer.');
} catch (error) {
  console.error(`\nStaging failed: ${error.message}`);
  process.exit(1);
}
