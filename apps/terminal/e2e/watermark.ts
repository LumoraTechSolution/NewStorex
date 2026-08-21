import { tmpdir } from 'node:os';
import { join } from 'node:path';

/**
 * Where the setup leaves the row ids the run started from, for the teardown to read.
 *
 * A file rather than a module-level variable because Playwright runs global setup and teardown in
 * separate processes from each other and from the workers — nothing in memory survives between
 * them.
 *
 * One mark per table the suite writes to. M2 made this more than a single number: a run now also
 * opens a shift and may raise a credit note, and the teardown has to remove exactly those and
 * nothing a developer created by hand in the same database.
 */
export const WATERMARK_FILE = join(tmpdir(), 'lumora-e2e-watermark.json');

export interface Watermark {
  readonly sales: number;
  readonly refunds: number;
  readonly cashMovements: number;
  readonly shifts: number;
}
