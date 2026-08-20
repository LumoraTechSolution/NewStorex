import { rows, scalar } from './database';
import { expect, test, type Till } from './till';

/**
 * M1-16 — the keyboard-only sale, driven through the real Electron window.
 *
 * ## What this proves, and what it does not
 *
 * Gate M1 is "a cashier completes 20 consecutive sales without touching a mouse". This
 * suite is **not** that gate and cannot tick it: the gate is about a person, and a person
 * finds the things a script cannot — that a key is in the wrong place, that the eye has to
 * hunt for the total, that the overlay steals focus in a way the DOM says it does not.
 * What this suite does is make the gate cheap to attempt and impossible to regress, by
 * holding the mechanical half: that the sale path is reachable with keystrokes alone and
 * that nothing on it requires a pointer.
 *
 * ## The assertion that matters
 *
 * Not "we never called `page.click()`" — that would only be a statement about the test.
 * Every pointer-shaped event the window receives is recorded in the capture phase, and the
 * sale must complete having produced **none of them**. That catches the real regression:
 * someone adds a control reachable only by mouse, and the keyboard path quietly stops
 * covering the whole sale.
 *
 * Note that a keyboard-activated `<button>` fires a synthetic `click` with `detail === 0`.
 * The specs below assert zero pointer events of any kind, which is stricter — and currently
 * true, because the till drives everything through document-level key handlers rather than
 * focused buttons. If that ever changes deliberately, the assertion to keep is
 * `detail === 0`: that is the line between "activated from the keyboard" and "a hand left
 * the keyboard".
 */

/** Seeded in `dev-seed.sql`. Tea is standard-rated, bread is zero-rated (M1-18). */
const TEA = { barcode: '4791234567890', name: 'Ceylon Tea 400g', unitMinor: 45000 };
const BREAD = { barcode: '4791234567951', name: 'Bread 450g', unitMinor: 25000 };

/**
 * Types a barcode the way a gun does: fast, then Enter.
 *
 * The zero delay is not a shortcut, it is the point. `isScannerTerminator` treats an Enter
 * arriving within 60ms of a character as the gun's terminator and lets the scan field have
 * it, so a burst-typed code followed immediately by Enter is what exercises the M1-09 path.
 */
async function scan(till: Till, barcode: string) {
  await till.page.keyboard.type(barcode, { delay: 0 });
  await till.page.keyboard.press('Enter');
}

/**
 * Waits for a cart row for this product.
 *
 * By **row**, not by text. `getByText(name).first()` looks equivalent and is not: the name
 * appears inside a cell that also carries the SKU, so the match set includes several nested
 * elements and `.first()` picks whichever the DOM happens to order first. That was flaky here
 * for exactly as long as it took to notice the cart was on screen while the assertion insisted
 * it was not. A row is the thing being asserted about anyway — one line in the cart.
 *
 * The failure path reports what the till said and every key the window received, because the
 * interesting failure is a scan that arrived wrong ("No product for barcode 791234567890" is a
 * lost leading digit, not a missing product) and Playwright's own message cannot show that.
 */
async function expectInCart(till: Till, name: string) {
  const row = till.page.getByRole('row').filter({ hasText: name });
  try {
    await expect(row).toHaveCount(1, { timeout: 10_000 });
  } catch (cause) {
    // Playwright's own message here is just "locator not found", and the interesting
    // evidence is elsewhere: what the till said, and what the window actually received.
    const status = till.page.locator('p[role="status"]');
    const said = (await status.count()) > 0 ? await status.textContent() : '(nothing)';
    const keys = (await till.keyLog()).join('\n  ');
    throw new Error(
      [
        `"${name}" never reached the cart.`,
        `The till says: ${said}`,
        '',
        'Keys the window saw:',
        `  ${keys}`,
      ].join('\n'),
      { cause },
    );
  }
}

/**
 * The tender overlay, by its heading.
 *
 * Not `getByText('Tender')` — the F-key bar carries a permanent "Tender" label for F12, so
 * a text match finds the affordance whether or not the overlay is open, which is precisely
 * backwards. The heading exists only while the overlay is mounted.
 */
function tenderOverlay(till: Till) {
  return till.page.getByRole('heading', { name: 'Tender' });
}

/** F12 → tender overlay → Enter (accept the suggested full amount) → F12 → committed. */
async function tenderAndComplete(till: Till) {
  await till.page.keyboard.press('F12');
  await expect(tenderOverlay(till)).toBeVisible();

  // Enter with an empty buffer tenders the suggested amount, which for a single cash line
  // is the whole sale. Nothing is typed, so nothing rounds.
  await till.page.keyboard.press('Enter');
  await expect(till.page.getByText('Fully tendered')).toBeVisible();

  await till.page.keyboard.press('F12');
}

/**
 * The status line the page shows after a commit, e.g. `KND-T1-000042 — 950.00`.
 *
 * Narrowed to the paragraph: the sync strip at the top of the window is also a live region
 * with `role="status"`, and it is present on every screen.
 */
function statusLine(till: Till) {
  return till.page.locator('p[role="status"]');
}

async function invoiceNumberFrom(till: Till): Promise<string> {
  const text = (await statusLine(till).textContent()) ?? '';
  const match = /\b([A-Z]+-[A-Z0-9]+-\d+)\b/.exec(text);
  expect(match, `no invoice number in status line: ${text}`).not.toBeNull();
  return match![1]!;
}

test.describe('a sale completed without touching a mouse (M1-16)', () => {
  test('rings up, tenders and commits from the keyboard alone', async ({ till }) => {
    await scan(till, TEA.barcode);
    await expectInCart(till, TEA.name);

    await tenderAndComplete(till);

    const invoiceNumber = await invoiceNumberFrom(till);
    expect(invoiceNumber).toMatch(/^KND-T1-\d{6}$/);

    // The sale is final in the local database — that is the whole architecture, so the
    // assertion is against the row, not against the screen that claims the row exists.
    const total = scalar(`SELECT total_minor FROM sales WHERE invoice_number = '${invoiceNumber}'`);
    expect(total).toBe(String(TEA.unitMinor));

    // And the outbox row that will carry it, written in the same transaction.
    const outbox = scalar(
      `SELECT count(*) FROM outbox WHERE aggregate = 'sale'
         AND aggregate_id = (SELECT client_uuid FROM sales WHERE invoice_number = '${invoiceNumber}')`,
    );
    expect(outbox).toBe('1');

    expect(await till.pointerEvents()).toEqual([]);
  });

  test('clears the cart and advances the invoice number across consecutive sales', async ({
    till,
  }) => {
    // Three rather than the gate's twenty: what a script can prove here is that the loop
    // closes — the cart empties, focus returns to the scan field, and the next number is
    // the next number. The twentieth sale tells a machine nothing the third did not.
    const invoiceNumbers: string[] = [];

    for (let i = 0; i < 3; i++) {
      await scan(till, TEA.barcode);
      await expectInCart(till, TEA.name);
      await tenderAndComplete(till);
      invoiceNumbers.push(await invoiceNumberFrom(till));

      // The cart is empty again and the caret is back where a gun can reach it, with no
      // intervention — if either were untrue the next scan would land nowhere.
      await expect(till.page.getByRole('row').filter({ hasText: TEA.name })).toHaveCount(0);
      await expect(till.page.locator('#scan')).toBeFocused();
    }

    const sequences = invoiceNumbers.map((n) => Number(n.split('-')[2]));
    expect(sequences[1]).toBe(sequences[0]! + 1);
    expect(sequences[2]).toBe(sequences[1]! + 1);

    expect(await till.pointerEvents()).toEqual([]);
  });
});

test.describe('a mixed-rate basket, end to end (M1-18)', () => {
  /**
   * The gap M1-18 left open: the money path and the wire were proven, the screen was not.
   * Bread at 0% and tea at 18% in one cart is the basket the till refused outright until
   * M1-18, so it is worth ringing up through the UI at least once.
   */
  test('prices each line at its own rate and prints a per-rate VAT summary', async ({ till }) => {
    await scan(till, BREAD.barcode);
    await expectInCart(till, BREAD.name);
    await scan(till, TEA.barcode);
    await expectInCart(till, TEA.name);

    // The refusal this task removed. Its absence is the feature.
    await expect(till.page.getByText(/taxed differently|not supported yet/i)).toHaveCount(0);

    // Both rates are on screen, each with its own figure — not one blended VAT number the
    // cashier could not check against anything.
    await expect(till.page.getByText('VAT 0%')).toBeVisible();
    await expect(till.page.getByText('VAT 18%')).toBeVisible();

    await tenderAndComplete(till);
    const invoiceNumber = await invoiceNumberFrom(till);

    // Per line, in the database: the bread untaxed, the tea taxed on its own gross.
    const items = rows(`
      SELECT p.name, i.tax_rate_bp, i.tax_minor
        FROM sale_items i
        JOIN products p ON p.id = i.product_id
       WHERE i.sale_id = (SELECT id FROM sales WHERE invoice_number = '${invoiceNumber}')
       ORDER BY i.line_no
    `);
    expect(items).toEqual([
      [BREAD.name, '0', '0'],
      [TEA.name, '1800', String(Math.floor((TEA.unitMinor * 1800) / 11800))],
    ]);

    // And on the paper. This is the first time the receipt has been asserted from a sale
    // the UI actually rang up rather than from a hand-built fixture.
    const receipt = till.printedText();
    expect(receipt).toContain(invoiceNumber);
    expect(receipt).toContain('VAT SUMMARY');
    expect(receipt).toMatch(/\n\s+0%/);
    expect(receipt).toMatch(/\n\s+18%/);
    expect(receipt).toContain('700.00'); // total: 250.00 bread + 450.00 tea

    expect(await till.pointerEvents()).toEqual([]);
  });
});

test.describe('the scanner and the cashier are not the same device (M1-09)', () => {
  /**
   * The bug this guards: without the terminator rule, a scan types the code *and* triggers
   * whatever Enter is bound to — on this screen, tendering. The cashier scans a second item
   * and the first has already been paid for. It is the single worst failure mode on the
   * till, and it is invisible to any test that presses keys slowly.
   */
  test('a gun-speed Enter adds the item and does not tender the sale', async ({ till }) => {
    await scan(till, TEA.barcode);
    await expectInCart(till, TEA.name);

    // No overlay, no commit — the Enter was consumed by the scan field and went no further.
    await expect(tenderOverlay(till)).toHaveCount(0);
    await expect(statusLine(till)).toHaveCount(0);

    expect(await till.pointerEvents()).toEqual([]);
  });

  /**
   * The other half of the same rule, and the reason it is a time window rather than a flag:
   * an Enter the cashier presses themselves, well after typing, is a *query*, not a scan.
   * Typing a product name and pressing Enter has to reach `onQuery` and add the item by
   * name — proving the window discriminates rather than simply swallowing every Enter.
   */
  test('a human-paced Enter after typing a name searches instead of scanning', async ({ till }) => {
    await till.page.keyboard.type('Ceylon', { delay: 30 });
    // Comfortably outside the 60ms terminator window — this is a person, not a gun.
    await till.page.waitForTimeout(300);
    await till.page.keyboard.press('Enter');

    await expectInCart(till, TEA.name);
    expect(await till.pointerEvents()).toEqual([]);
  });
});

test.describe('the pointer detector itself', () => {
  /**
   * Guards against the whole suite going quietly vacuous.
   *
   * Every spec above ends by asserting no pointer events were recorded. If the recorder ever
   * stopped being installed — a reload that drops the init script, a rename, a Playwright
   * change — those assertions would keep passing while proving nothing at all, and a
   * mouse-only control could walk straight in. So: use the mouse on purpose, and require the
   * detector to notice. A test whose failure mode is silence needs one of these.
   */
  test('records a real mouse click, so an empty list means something', async ({ till }) => {
    // The theme toggle, because it is the one button on the screen that is never disabled —
    // and a disabled button dispatches no click at all, which would make this guard as
    // vacuous as the thing it is guarding against.
    await till.page.getByRole('button', { name: /Switch to (light|dark) mode/ }).click();

    const events = await till.pointerEvents();
    expect(events.length).toBeGreaterThan(0);
    expect(events.map((e) => e.type)).toContain('click');

    // `detail > 0` is what separates a pointing device from a button activated by Enter —
    // the discriminator the spec header points future readers at.
    expect(events.some((e) => e.type === 'click' && e.detail > 0)).toBe(true);
  });
});
