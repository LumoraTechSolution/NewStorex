import { writeFileSync } from 'node:fs';

import { psql, scalar } from './database';
import { WATERMARK_FILE, type Watermark } from './watermark';

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
  clearPinThrottle();
  recordWatermark();
  openShift();
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

  // M2-07 via M3-08. The refund specs authorise as MGR and expect NIMAL to be refused, so both
  // have to exist and hold the roles the specs assume. Checked here because the alternative is a
  // refusal deep inside a refund spec that reads like a broken refund flow rather than an
  // unseeded database.
  const staff: readonly [string, string][] = [
    ['MGR', 'authorises the refund specs'],
    ['NIMAL', 'is the cashier the refusal spec expects'],
  ];
  const absent = staff.filter(
    ([code]) => scalar(`SELECT 1 FROM users WHERE code = '${code}' AND active`) === null,
  );
  if (absent.length > 0) {
    throw new Error(
      'The dev seed is missing users these specs sign in as:\n' +
        absent.map(([code, what]) => `  ${code} — ${what}`).join('\n') +
        '\n\nSeed it with:  pnpm db:seed',
    );
  }
  if (scalar("SELECT 1 FROM users WHERE code = 'NIMAL' AND role = 'CASHIER'") === null) {
    throw new Error(
      "NIMAL is seeded but is not a CASHIER, so the 'a cashier cannot authorise a refund' spec " +
        'would pass for the wrong reason. Reseed with:  pnpm db:seed',
    );
  }
}

/**
 * A shift, because since M2-01 the till refuses to sell without one.
 *
 * Opened here rather than through the UI: this is a precondition of the sale specs, not something
 * they are testing, and a spec that has to cash-up before it can scan is a spec whose failure
 * message is about the wrong thing. The cash-up flow has its own spec that drives the real screens.
 *
 * Idempotent — a developer who already has a shift open on this till keeps it, and the teardown
 * only removes a shift this run created (it is above the watermark, theirs is not).
 *
 * `opened_by` is looked up rather than hardcoded: since M3-08 it is a real foreign key, and every
 * sale these specs ring up is attributed to whoever it names. A literal 1 happened to work on the
 * day V109 ran and would stop working on any database whose first user is somebody else.
 *
 * One consequence worth knowing: a shift opened this way writes no outbox row, because it never
 * went through ShiftService. So an e2e run leaves cash movements, refunds and sales in the cloud
 * but no shift — which looks like a sync bug and is not one. Shift ingest, including the monotonic
 * open-after-close guard, is covered by CloudIngestM2Test.
 */
function openShift() {
  psql(`
    INSERT INTO shifts (client_uuid, tenant_id, branch_id, terminal_code, status,
                        opened_by, opening_float_minor)
    SELECT gen_random_uuid(), b.tenant_id, b.id, 'T1', 'OPEN',
           (SELECT u.id FROM users u
             WHERE u.tenant_id = b.tenant_id AND u.active AND u.code = 'MGR'),
           500000
      FROM branches b
     WHERE b.code = 'KND'
       AND NOT EXISTS (
         SELECT 1 FROM shifts s
          WHERE s.branch_id = b.id AND s.terminal_code = 'T1' AND s.status = 'OPEN');
  `);
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
/**
 * Forgets the PIN failures the last run deliberately caused (M3-13).
 *
 * Several specs type a wrong PIN on purpose — that is the behaviour they exist to assert — and
 * `PinAttemptGuard` counts consecutive failures across a fifteen-minute window. Two runs in quick
 * succession would therefore start the second one part-way into a cooling-off period, and the spec
 * that fails would be a different one each time depending on ordering. That is a flake with a
 * plausible-looking cause, which is the worst kind.
 *
 * Only the throttle is cleared, never a user or a PIN: the thing being reset is a rate limit this
 * suite itself tripped, and nothing about the shop's own state.
 */
function clearPinThrottle() {
  psql('DELETE FROM pin_attempts');
}

function recordWatermark() {
  const mark = (table: string) =>
    Number(scalar(`SELECT coalesce(max(id), 0) FROM ${table}`) ?? '0');
  const watermark: Watermark = {
    sales: mark('sales'),
    refunds: mark('refunds'),
    cashMovements: mark('cash_movements'),
    shifts: mark('shifts'),
  };
  writeFileSync(WATERMARK_FILE, JSON.stringify(watermark), 'utf8');
}
