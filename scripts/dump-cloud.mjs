#!/usr/bin/env node
/* eslint-disable no-console -- a command-line script; stdout is its user interface */
/**
 * A backup of the cloud database, off the cloud provider.
 *
 *   pnpm db:dump:cloud
 *
 * Why this exists: the cloud is a shop's off-site copy of its own history, and
 * until M5-06 automates backups it lives on a free tier with no SLA. Neon's
 * 24-hour restore covers a bad UPDATE; it does not cover losing the project.
 * This is the weekly copy that does.
 *
 * The connection comes from CLOUD_DUMP_URL (a libpq URI, which is what the
 * provider hands you — not the JDBC form the backend wants). The password
 * travels inside it, so it is read from the environment and passed through
 * PGPASSWORD rather than written on the command line, where it would land in
 * shell history and in any process listing.
 *
 * Output is custom format (-Fc): compressed, and restorable selectively with
 * pg_restore. The root .gitignore already excludes *.dump.
 */

import { spawnSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const uri = process.env.CLOUD_DUMP_URL;

if (!uri) {
  console.error(`
CLOUD_DUMP_URL is not set.

Set it to the cloud database's libpq URI — the form the provider gives you,
not the JDBC form the backend runs on:

  postgresql://USER:PASSWORD@HOST/lumora_cloud?sslmode=require

PowerShell, for one command only (does not persist, does not reach the repo):

  $env:CLOUD_DUMP_URL = 'postgresql://...'; pnpm db:dump:cloud
`);
  process.exit(1);
}

let target;
try {
  const parsed = new URL(uri);
  target = `${parsed.hostname}${parsed.pathname}`;
} catch {
  console.error('CLOUD_DUMP_URL is not a valid URI.');
  process.exit(1);
}

// A sortable name, so a directory of these reads as a history.
const stamp = new Date().toISOString().slice(0, 10);
const outDir = resolve(process.cwd(), 'backups');
const outFile = resolve(outDir, `lumora_cloud_${stamp}.dump`);

mkdirSync(outDir, { recursive: true });

console.log(`Dumping ${target} -> ${outFile}`);

// --no-owner / --no-privileges: the roles on a managed provider are not the
// roles anywhere you might restore this, and a dump that insists on them is a
// dump that fails when you most need it.
const result = spawnSync(
  'pg_dump',
  ['--format=custom', '--no-owner', '--no-privileges', '--file', outFile, uri],
  { stdio: 'inherit' },
);

if (result.error?.code === 'ENOENT') {
  console.error(`
pg_dump was not found on PATH.

It ships with the Postgres client tools. On this machine the native Postgres
install usually has it at:

  C:\\Program Files\\PostgreSQL\\16\\bin

Either add that to PATH, or run the dump through the compose container:

  docker compose exec -T db-cloud pg_dump --format=custom --no-owner \\
    --no-privileges "$CLOUD_DUMP_URL" > backups/lumora_cloud_${stamp}.dump
`);
  process.exit(1);
}

if (result.status !== 0) {
  console.error('\npg_dump failed. The dump at the path above is incomplete — delete it.');
  process.exit(result.status ?? 1);
}

console.log(`
Done. Now move it somewhere that is neither the cloud provider nor the shop PC.

A dump you have never restored is not a backup. Before a pilot shop goes live,
restore this into a scratch database and sign in to the console against it.
`);
