import { tmpdir } from 'node:os';
import { join } from 'node:path';

/**
 * Where the setup leaves the sale id the run started from, for the teardown to read.
 *
 * A file rather than a module-level variable because Playwright runs global setup and
 * teardown in separate processes from each other and from the workers — nothing in memory
 * survives between them.
 */
export const WATERMARK_FILE = join(tmpdir(), 'lumora-e2e-sale-watermark');
