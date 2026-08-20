import { writeFileSync } from 'node:fs';

import { psql, scalar } from './database';
import { WATERMARK_FILE } from './watermark';

/**
 * Fails loudly, early, and with the command that fixes it.
 *
 * Everything checked here is a prerequisite the suite cannot create for itself. The
 * alternative is a spec that fails twelve steps later on a missing product, and a developer
 * reading a timeout where the real message was "you did not seed the database".
 */
export default async function globalSetup() {
  assertDatabaseReachable();
  assertSeeded();
  recordWatermark();
}

function assertDatabaseReachable() {
  try {
    psql('SELECT 1');
  } catch (e) {
    throw new Error(
      'Cannot reach the local Postgres container (db-local).\n' +
        'Start it with:  pnpm db:up\n\n' +
        `Underlying error: ${e instanceof Error ? e.message : String(e)}`,
    );
  }
}

/**
 * The two products the sale specs scan, and the branch every sale is booked against.
 *
 * Checked by barcode rather than by counting rows: the spec types these exact digits into
 * the scan field, so "the seed ran" is not the question — "is *this* code scannable" is.
 */
function assertSeeded() {
  const required = [
    ['4791234567890', 'Ceylon Tea 400g (18%)'],
    ['4791234567951', 'Bread 450g (0%, the zero-rated half of the mixed-rate specs)'],
  ] as const;

  const missing = required.filter(
    ([barcode]) => scalar(`SELECT 1 FROM product_barcodes WHERE barcode = '${barcode}'`) === null,
  );

  if (missing.length > 0) {
    throw new Error(
      'The dev seed is missing barcodes these specs scan:\n' +
        missing.map(([barcode, what]) => `  ${barcode} — ${what}`).join('\n') +
        '\n\nSeed it with:  pnpm db:seed',
    );
  }

  if (scalar("SELECT 1 FROM branches WHERE code = 'KND'") === null) {
    throw new Error("Branch 'KND' is not seeded. Run:  pnpm db:seed");
  }
}

/**
 * The high-water mark the teardown deletes above.
 *
 * These specs ring up real sales into the developer's own `lumora_local` — there is no
 * separate e2e database, and inventing one would mean the suite no longer exercised the
 * database the app actually runs against. So the run records where the table ended before
 * it started, and removes only what it added. Nothing a developer rang up by hand is ever
 * inside that range.
 */
function recordWatermark() {
  const maxSaleId = scalar('SELECT coalesce(max(id), 0) FROM sales') ?? '0';
  writeFileSync(WATERMARK_FILE, maxSaleId, 'utf8');
}
