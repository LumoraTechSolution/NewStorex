import { rows, scalar } from './database';
import { expect, test, type Till } from './till';

/**
 * The till's own error banner.
 *
 * Not `getByRole('alert')`: Next injects a permanently-empty `<div role="alert">` route announcer
 * into every page, so the bare role matches two elements and Playwright's strict mode refuses to
 * guess. Scoping to the `<p>` the app actually renders is both unambiguous and closer to what is
 * being asserted.
 */
function alert(till: Till) {
  return till.page.locator('p[role="alert"]');
}

/**
 * M2 through the real Electron window: the blind count, and Gate M2.
 *
 * ## What this proves that the backend suite cannot
 *
 * `ShiftLifecycleTest` and `RefundTest` already assert the rules against the database. What they
 * cannot assert is that a cashier can reach them — that F10 opens a count, that the count is
 * keyboard-only, that a refund is reachable from a receipt number and nothing else, and above all
 * that **the expected drawer total never crosses the wire while a shift is open**. That last one
 * is M2-02's whole point and it is a property of the running app, not of a service method.
 *
 * ## What it deliberately does not do
 *
 * It does not tick Gate M2. The gate is a statement about what the software refuses, and a person
 * has to try to break it — with a card receipt in one hand and a drawer of cash in the other. This
 * makes the attempt cheap and stops the rules regressing silently in between.
 */
const TEA = { barcode: '4791234567890', name: 'Ceylon Tea 400g', unitMinor: 45000 };

async function scan(till: Till, barcode: string) {
  await till.page.keyboard.type(barcode, { delay: 0 });
  await till.page.keyboard.press('Enter');
}

/** Types a number into whichever field currently owns the digits. */
async function type(till: Till, digits: string) {
  await till.page.keyboard.type(digits, { delay: 10 });
}

/**
 * Signs in at an operator prompt: user code, Enter, PIN (M3-08).
 *
 * <p>Enter rather than Tab on purpose — both move to the PIN, and Enter is the one a cashier will
 * reach for, so it is the one worth having a test fail over.
 */
async function signAs(till: Till, code: string, pin: string) {
  await till.page.keyboard.type(code, { delay: 10 });
  await till.page.keyboard.press('Enter');
  await type(till, pin);
}

/** Rings up one Tea, settled in exact cash. Returns the invoice number it was given. */
async function ringUpOneTea(till: Till): Promise<string> {
  await scan(till, TEA.barcode);
  await expect(till.page.getByRole('row').filter({ hasText: TEA.name })).toBeVisible();

  await till.page.keyboard.press('F12'); // tender
  await till.page.keyboard.press('Enter'); // the suggested exact cash
  await till.page.keyboard.press('F12'); // complete

  const status = till.page.getByRole('status').filter({ hasText: /-T1-\d{6}/ });
  await expect(status).toBeVisible({ timeout: 15_000 });
  const text = (await status.textContent()) ?? '';
  const match = /([A-Z]+-T1-\d{6})/.exec(text);
  if (!match) throw new Error(`No invoice number in the status message: ${text}`);
  return match[1]!;
}

test.describe('cash up', () => {
  /**
   * The property M2-02 exists for, asserted where it can actually be broken.
   *
   * Not "the screen does not show the figure" — every response the window receives while a shift
   * is open is inspected, and none of them may carry it. That catches the regression a screen test
   * would miss: someone adds `expectedCashMinor` to the status endpoint for a manager view, the UI
   * dutifully ignores it, and the count is no longer blind to anyone who opens devtools.
   */
  test('the expected drawer total never reaches the window while a shift is open', async ({
    till,
  }) => {
    const leaked: string[] = [];

    till.page.on('response', (response) => {
      if (!response.url().includes('/api/')) return;
      void response
        .text()
        .then((body) => {
          if (/expectedCash|varianceMinor/i.test(body)) leaked.push(`${response.url()} → ${body}`);
        })
        .catch(() => {
          /* a body that cannot be read cannot leak */
        });
    });

    await ringUpOneTea(till);

    // Everything a trading till does: sell, check its own status, look at the shift.
    await till.page.keyboard.press('F10');
    await expect(till.page.getByRole('heading', { name: 'Cash up' })).toBeVisible();
    await till.page.keyboard.press('Escape');

    await till.page.waitForTimeout(500);
    expect(leaked, `expected cash leaked to the renderer:\n${leaked.join('\n')}`).toEqual([]);
  });

  test('a pay-out is recorded with the sign the kind implies, without a mouse', async ({
    till,
  }) => {
    const before = Number(scalar('SELECT coalesce(max(id), 0) FROM cash_movements') ?? '0');

    await till.page.keyboard.press('F10'); // cash up
    await till.page.keyboard.press('1'); // cash in / out
    await expect(till.page.getByRole('heading', { name: 'Cash in / out' })).toBeVisible();

    await till.page.keyboard.press('Tab'); // DROP → PAY_OUT
    await type(till, '25000'); // LKR 250.00
    await till.page.keyboard.press('F12');

    await expect(till.page.getByRole('heading', { name: 'Cash up' })).toBeVisible({
      timeout: 10_000,
    });
    await till.page.keyboard.press('Escape');

    const recorded = rows(
      `SELECT kind, amount_minor FROM cash_movements WHERE id > ${before} ORDER BY id`,
    );
    expect(recorded).toHaveLength(1);
    expect(recorded[0]![0]).toBe('PAY_OUT');
    // The cashier typed a positive 250.00; the drawer lost it. Nobody typed a minus sign.
    expect(recorded[0]![1]).toBe('-25000');

    expect(await till.pointerEvents()).toEqual([]);
  });
});

test.describe('returns — Gate M2', () => {
  test('a refund starts from a receipt and reverses the sale that receipt names', async ({
    till,
  }) => {
    const invoiceNumber = await ringUpOneTea(till);
    till.forgetPrinted();

    await till.page.keyboard.press('F9');
    await expect(till.page.getByText(/Type or scan the invoice number/)).toBeVisible();

    // Typed the way a cashier types it, or a gun scans it.
    await till.page.keyboard.type(invoiceNumber, { delay: 0 });
    await till.page.keyboard.press('Enter');

    await expect(till.page.getByRole('list', { name: 'Sale lines' })).toBeVisible({
      timeout: 10_000,
    });
    await till.page.keyboard.press('Enter'); // return the whole selected line
    // The total, not the F12 hint that also says "refund".
    await expect(till.page.getByText('Refund', { exact: true })).toBeVisible();

    await till.page.keyboard.press('F12'); // to the authorisation prompt
    await expect(
      till.page.getByText('A supervisor or manager must authorise this refund.'),
    ).toBeVisible();
    // A named user holding AUTHORISE_REFUND, not a shop-wide PIN (M3-08).
    await signAs(till, 'MGR', '1234');
    await till.page.keyboard.press('Enter');

    const status = till.page.getByRole('status').filter({ hasText: /-CN-\d{6}/ });
    await expect(status).toBeVisible({ timeout: 15_000 });

    // The credit note names the invoice it reverses, and the sale is untouched.
    const creditNote = scalar(
      `SELECT credit_note_number FROM refunds
        WHERE sale_id = (SELECT id FROM sales WHERE invoice_number = '${invoiceNumber}')`,
    );
    expect(creditNote).toMatch(/-CN-\d{6}$/);
    expect(scalar(`SELECT total_minor FROM sales WHERE invoice_number = '${invoiceNumber}'`)).toBe(
      String(TEA.unitMinor),
    );

    // And it names who allowed it (M3-08). Before users, this column could only hold a
    // placeholder that pointed at nobody.
    expect(
      scalar(
        `SELECT u.code FROM refunds r JOIN users u ON u.id = r.authorised_by
          WHERE r.credit_note_number = '${creditNote}'`,
      ),
    ).toBe('MGR');

    // And it printed as its own document, naming the invoice (M2-06).
    expect(till.printedText()).toContain('CREDIT NOTE');
    expect(till.printedText()).toContain(invoiceNumber);

    expect(await till.pointerEvents()).toEqual([]);
  });

  test('an unknown receipt number gets no further', async ({ till }) => {
    await till.page.keyboard.press('F9');
    await till.page.keyboard.type('KND-T1-999999', { delay: 0 });
    await till.page.keyboard.press('Enter');

    // There is no path onward. The lines screen is what a found sale looks like.
    await expect(alert(till)).toContainText('No sale found for invoice', { timeout: 10_000 });
    await expect(till.page.getByRole('list', { name: 'Sale lines' })).toHaveCount(0);

    await till.page.keyboard.press('Escape');
  });

  /**
   * Gate M2's other half, and M3-08's reason for existing.
   *
   * <p>NIMAL's PIN is correct. He can open a shift with it and sell all day. It buys nothing here,
   * and the refusal says so by name rather than pretending the credential was wrong — at that
   * point the person has already proved who they are.
   */
  test('a cashier cannot authorise a refund, even with the right PIN', async ({ till }) => {
    const invoiceNumber = await ringUpOneTea(till);

    await till.page.keyboard.press('F9');
    await till.page.keyboard.type(invoiceNumber, { delay: 0 });
    await till.page.keyboard.press('Enter');
    await expect(till.page.getByRole('list', { name: 'Sale lines' })).toBeVisible({
      timeout: 10_000,
    });
    await till.page.keyboard.press('Enter');
    await till.page.keyboard.press('F12');
    await signAs(till, 'NIMAL', '1234');
    await till.page.keyboard.press('Enter');

    await expect(alert(till)).toContainText('cannot authorise refunds', { timeout: 10_000 });
    expect(
      scalar(
        `SELECT count(*) FROM refunds
          WHERE sale_id = (SELECT id FROM sales WHERE invoice_number = '${invoiceNumber}')`,
      ),
    ).toBe('0');

    expect(await till.pointerEvents()).toEqual([]);

    await till.page.keyboard.press('Escape');
    await till.page.keyboard.press('Escape');
    await till.page.keyboard.press('Escape');
  });

  test('a wrong PIN refunds nothing', async ({ till }) => {
    const invoiceNumber = await ringUpOneTea(till);

    await till.page.keyboard.press('F9');
    await till.page.keyboard.type(invoiceNumber, { delay: 0 });
    await till.page.keyboard.press('Enter');
    await expect(till.page.getByRole('list', { name: 'Sale lines' })).toBeVisible({
      timeout: 10_000,
    });
    await till.page.keyboard.press('Enter');
    await till.page.keyboard.press('F12');
    await signAs(till, 'MGR', '0000');
    await till.page.keyboard.press('Enter');

    await expect(alert(till)).toContainText('not recognised', { timeout: 10_000 });
    expect(
      scalar(
        `SELECT count(*) FROM refunds
          WHERE sale_id = (SELECT id FROM sales WHERE invoice_number = '${invoiceNumber}')`,
      ),
    ).toBe('0');

    await till.page.keyboard.press('Escape');
    await till.page.keyboard.press('Escape');
    await till.page.keyboard.press('Escape');
  });
});
