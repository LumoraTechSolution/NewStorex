import { existsSync, readFileSync, rmSync } from 'node:fs';

import { psql } from './database';
import { WATERMARK_FILE, type Watermark } from './watermark';

/**
 * Removes what this run created, and nothing else.
 *
 * Only rows above the watermarks the setup recorded, so a developer's own experiments in
 * `lumora_local` survive a test run. The order matters, and it is the reverse of the order the
 * app writes in: refunds and their movements reference sales, sales and cash movements reference
 * shifts, and `sale_items` / `sale_payments` / `refund_items` / `refund_payments` cascade with
 * their parents.
 *
 * `invoice_counters` is deliberately **not** reset — for either sequence. The block a terminal has
 * issued from is not reusable: that is the whole point of M1-12, and a suite that rewound it would
 * be rehearsing the one thing per-terminal numbering exists to make impossible. Both counters
 * simply advance, exactly as they would in a shop.
 */
export default async function globalTeardown() {
  if (!existsSync(WATERMARK_FILE)) return;

  const raw = readFileSync(WATERMARK_FILE, 'utf8').trim();
  rmSync(WATERMARK_FILE, { force: true });

  let mark: Watermark;
  try {
    mark = JSON.parse(raw) as Watermark;
  } catch {
    return;
  }
  if (!Object.values(mark).every((v) => Number.isInteger(v))) return;

  try {
    psql(`
      DELETE FROM stock_movements
       WHERE ref_type = 'refund' AND ref_id IN (SELECT id FROM refunds WHERE id > ${mark.refunds});
      DELETE FROM outbox
       WHERE aggregate = 'refund'
         AND aggregate_id IN (SELECT client_uuid FROM refunds WHERE id > ${mark.refunds});
      DELETE FROM refunds WHERE id > ${mark.refunds};

      DELETE FROM stock_movements
       WHERE ref_type = 'sale' AND ref_id IN (SELECT id FROM sales WHERE id > ${mark.sales});
      DELETE FROM outbox
       WHERE aggregate = 'sale'
         AND aggregate_id IN (SELECT client_uuid FROM sales WHERE id > ${mark.sales});
      DELETE FROM sales WHERE id > ${mark.sales};

      DELETE FROM outbox
       WHERE aggregate = 'cash_movement'
         AND aggregate_id IN (SELECT client_uuid FROM cash_movements WHERE id > ${mark.cashMovements});
      DELETE FROM cash_movements WHERE id > ${mark.cashMovements};

      DELETE FROM outbox
       WHERE aggregate = 'shift'
         AND aggregate_id IN (SELECT client_uuid FROM shifts WHERE id > ${mark.shifts});
      DELETE FROM shifts WHERE id > ${mark.shifts};
    `);
  } catch (e) {
    // Never fail a green run on cleanup. The rows are inert dev data, and a teardown that turns a
    // passing suite red teaches people to distrust the suite.
    console.warn(
      `e2e teardown could not remove its rows (sales > ${mark.sales}): ` +
        (e instanceof Error ? e.message : String(e)),
    );
  }
}
