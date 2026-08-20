import { existsSync, readFileSync, rmSync } from 'node:fs';

import { psql } from './database';
import { WATERMARK_FILE } from './watermark';

/**
 * Removes the sales this run rang up, and nothing else.
 *
 * Only rows above the watermark the setup recorded, so a developer's own experiments in
 * `lumora_local` survive a test run. The order matters: movements and the outbox row
 * reference the sale, and `sale_items` / `sale_payments` cascade with it.
 *
 * `invoice_counters` is deliberately **not** reset. The block a terminal has issued from is
 * not reusable — that is the whole point of M1-12, and a test suite that rewound it would be
 * rehearsing the one thing per-terminal numbering exists to make impossible. The counter
 * simply advances, exactly as it would in a shop.
 */
export default async function globalTeardown() {
  if (!existsSync(WATERMARK_FILE)) return;

  const watermark = Number(readFileSync(WATERMARK_FILE, 'utf8').trim());
  rmSync(WATERMARK_FILE, { force: true });
  if (!Number.isInteger(watermark)) return;

  try {
    psql(`
      DELETE FROM stock_movements
       WHERE ref_type = 'sale' AND ref_id IN (SELECT id FROM sales WHERE id > ${watermark});
      DELETE FROM outbox
       WHERE aggregate = 'sale'
         AND aggregate_id IN (SELECT client_uuid FROM sales WHERE id > ${watermark});
      DELETE FROM sales WHERE id > ${watermark};
    `);
  } catch (e) {
    // Never fail a green run on cleanup. The rows are inert dev data, and a teardown that
    // turns a passing suite red teaches people to distrust the suite.
    console.warn(
      `e2e teardown could not remove its sales (id > ${watermark}): ` +
        (e instanceof Error ? e.message : String(e)),
    );
  }
}
