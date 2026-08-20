import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';

/**
 * Talking to `lumora_local` from the e2e suite.
 *
 * Through `docker compose exec` rather than a Node Postgres driver, deliberately: the
 * terminal app has no database dependency and should not acquire one so a test can read a
 * row. The compose stack is already a hard prerequisite of running anything here, so this
 * borrows it instead of adding a second way to reach the same database.
 *
 * These helpers only ever *read*, apart from the teardown's cleanup — the sales under test
 * are made the way a cashier makes them, through the UI.
 */
/**
 * Found by walking up for the compose file rather than counting `..` segments, so the
 * suite still works whether Playwright was invoked from `apps/terminal` or the repo root.
 */
function repoRoot(): string {
  let dir = resolve(__dirname);
  while (!existsSync(join(dir, 'docker-compose.yml'))) {
    const parent = dirname(dir);
    if (parent === dir) throw new Error('Could not find docker-compose.yml above ' + __dirname);
    dir = parent;
  }
  return dir;
}

const COMPOSE_CWD = repoRoot();

export function psql(sql: string, database = 'lumora_local'): string {
  const service = database === 'lumora_local' ? 'db-local' : 'db-cloud';
  return execFileSync(
    'docker',
    [
      'compose',
      'exec',
      '-T',
      service,
      'psql',
      '-U',
      'lumora',
      '-d',
      database,
      '-t',
      '-A',
      '-c',
      sql,
    ],
    { cwd: COMPOSE_CWD, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  ).trim();
}

/** A single scalar, or null when the query returned no rows. */
export function scalar(sql: string): string | null {
  const out = psql(sql);
  return out === '' ? null : out;
}

/** Rows as arrays of column strings — enough for asserting on a handful of columns. */
export function rows(sql: string): string[][] {
  const out = psql(sql);
  return out === '' ? [] : out.split('\n').map((line) => line.split('|'));
}
