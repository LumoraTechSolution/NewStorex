import { expect } from '@playwright/test';

import { psql, scalar } from './database';
import { test, type Till } from './till';

/**
 * The back office (M3-01) and the staff it manages (M3-08), through the real Electron window.
 *
 * <h2>What these specs are for</h2>
 *
 * The unit tests prove the rules — that only an owner may create a user, that a cashier cannot
 * authorise a refund, that the last owner cannot stand down. What they cannot prove is that a
 * person can get to any of it: that Ctrl+B leaves the till, that sign-in accepts a code and a PIN
 * typed the way a keypad types them, and that the till is still there afterwards.
 *
 * <h2>Sign-in is asserted keyboard-only; the rest is not</h2>
 *
 * The back office is the one surface where a mouse is allowed — it is forms, used sitting down, a
 * few times a week. Sign-in is the exception because it is the same PIN entry the till uses, and
 * PIN entry that behaves differently in two places is PIN entry somebody gets wrong in one of
 * them. So the pointer assertion is scoped to reaching the shell, not to using it.
 */

/**
 * The app's own alert, not Next's.
 *
 * Next injects a permanently-empty `<div role="alert">` route announcer into every page, so the
 * bare role matches two elements and strict mode refuses to guess. Same reasoning as the cash-up
 * spec's helper.
 */
function alert(till: Till) {
  return till.page.locator('p[role="alert"]');
}

/**
 * Minor units as the screen prints them — grouped, two decimals, no currency symbol.
 *
 * A deliberate second implementation of `formatMinor`, and the only one in this repo: a spec that
 * imported the formatter under test would pass whatever either of them did. It is four lines, and
 * it is checking a different four lines.
 */
function money(minor: number): string {
  const negative = minor < 0;
  const absolute = Math.abs(minor);
  const rupees = Math.floor(absolute / 100).toLocaleString('en-LK');
  return `${negative ? '-' : ''}${rupees}.${String(absolute % 100).padStart(2, '0')}`;
}

/** Everything the shop has taken since midnight, straight from the sales rows. */
function takenToday(): number {
  return Number(
    scalar(
      "SELECT COALESCE(sum(total_minor), 0) FROM sales WHERE sold_at >= date_trunc('day', now())",
    ) ?? '0',
  );
}

/** F12 → tender overlay → Enter (the suggested full amount) → F12 → committed. */
async function sellOne(till: Till, barcode: string) {
  await scan(till, barcode);
  // Wait for the line before tendering: F12 on a cart the lookup has not landed in yet opens
  // nothing, and the failure then points at the tender overlay rather than at the race.
  await expect(till.page.getByRole('row')).toHaveCount(2, { timeout: 10_000 });
  await till.page.keyboard.press('F12');
  await expect(till.page.getByRole('heading', { name: 'Tender' })).toBeVisible({ timeout: 10_000 });
  await till.page.keyboard.press('Enter');
  await expect(till.page.getByText('Fully tendered')).toBeVisible();
  await till.page.keyboard.press('F12');
}

/** Rings a barcode into the till's scan field, the way the gun does. */
async function scan(till: Till, barcode: string) {
  await till.page.keyboard.type(barcode, { delay: 0 });
  await till.page.keyboard.press('Enter');
}

/** Signs in at an operator prompt: user code, Enter, PIN. */
async function signAs(till: Till, code: string, pin: string) {
  await till.page.keyboard.type(code, { delay: 10 });
  await till.page.keyboard.press('Enter');
  await till.page.keyboard.type(pin, { delay: 10 });
  await till.page.keyboard.press('Enter');
}

/**
 * Clicks a back-office section in the left nav.
 *
 * <p>Scoped to the nav because the section names are not unique on the page: "Products" is also
 * the Reports screen's "Top products" tab, so a bare name match is ambiguous once a report is open.
 */
async function goTo(till: Till, section: string) {
  await till.page
    .getByRole('navigation', { name: 'Back office' })
    .getByRole('button', { name: section, exact: true })
    .click();
}

/** Opens one product's form from the list, by the name on its row. */
async function editProduct(till: Till, name: string) {
  await till.page
    .getByRole('listitem')
    .filter({ hasText: name })
    .getByRole('button', { name: 'Edit' })
    .click();
}

async function openBackOffice(till: Till) {
  await till.page.keyboard.press('Control+b');
  await expect(till.page.getByRole('heading', { name: 'Back office' })).toBeVisible({
    timeout: 10_000,
  });
}

test.describe('back office — M3-01', () => {
  test('Ctrl+B reaches the back office and Escape gives the till back', async ({ till }) => {
    await openBackOffice(till);
    await expect(till.page.getByText('Sign in to manage the shop.')).toBeVisible();

    await till.page.keyboard.press('Escape');

    // The till, exactly as it was. Not a reload of it — the scan field is what a cashier
    // looks for to know they can carry on.
    await expect(till.page.getByRole('textbox')).toBeVisible({ timeout: 10_000 });
    expect(await till.handEvents()).toEqual([]);
  });

  test('an owner signs in with a code and a PIN, and never touches the mouse', async ({ till }) => {
    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');

    // The shell, and the name of whoever is holding it. Scoped to the header line rather than
    // the bare name: the Users list underneath has a row for her too, so the loose locator
    // matched one element or two depending on which fetch landed first.
    await expect(till.page.getByText('Kumari Perera · owner')).toBeVisible({ timeout: 10_000 });
    await expect(till.page.getByRole('heading', { name: 'Users' })).toBeVisible();

    expect(await till.handEvents()).toEqual([]);
  });

  test('a cashier cannot open the back office at all', async ({ till }) => {
    await openBackOffice(till);
    // NIMAL's PIN is correct. He sells all day with it and it buys nothing here.
    await signAs(till, 'NIMAL', '1234');

    await expect(alert(till)).toContainText('cannot open the back office', { timeout: 10_000 });
    await expect(till.page.getByRole('heading', { name: 'Users' })).toHaveCount(0);

    await till.page.keyboard.press('Escape');
  });

  test('an owner adds a user, and that user can then be found in the database', async ({
    till,
  }) => {
    // Deleted first rather than after: a spec that cleans up only on success leaves the shop
    // with a stray user the next run collides with on (tenant_id, code).
    psql("DELETE FROM users WHERE code = 'E2E'");

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await expect(till.page.getByRole('heading', { name: 'Users' })).toBeVisible({
      timeout: 10_000,
    });

    await till.page.getByRole('button', { name: 'Add a user' }).click();
    await till.page.getByLabel('User code').fill('E2E');
    await till.page.getByLabel('Name').fill('End To End');
    await till.page.getByLabel('PIN').fill('9182');
    // The radio itself is `sr-only` — it exists for screen readers and for form semantics, and
    // the label is what anybody sighted actually clicks. Driving the label is therefore both what
    // Playwright can reach and what a person does.
    //
    // Scoped to the Role fieldset because the user list below also prints role names, so a bare
    // "supervisor" matches the picker and whoever already is one.
    await till.page
      .getByRole('group', { name: 'Role' })
      .getByText('supervisor', { exact: true })
      .click();
    await till.page.getByRole('button', { name: 'Create' }).click();

    await expect(till.page.getByRole('status')).toContainText('End To End can now sign in.', {
      timeout: 10_000,
    });

    expect(scalar("SELECT role FROM users WHERE code = 'E2E'")).toBe('SUPERVISOR');
    // The PIN is stored as a hash and never as itself.
    const hash = scalar("SELECT pin_hash FROM users WHERE code = 'E2E'") ?? '';
    expect(hash).toMatch(/^\$2[aby]\$/);
    expect(hash).not.toContain('9182');

    // A supervisor may authorise refunds, so the new user is real enough to matter. Deactivate
    // rather than delete, which is the only thing the back office offers — and then clean up,
    // because this shop is the developer's own.
    await till.page
      .getByRole('listitem')
      .filter({ hasText: 'End To End' })
      .getByRole('button', { name: 'Deactivate' })
      .click();
    await expect(till.page.getByRole('status')).toContainText('can no longer sign in.', {
      timeout: 10_000,
    });
    expect(scalar("SELECT active FROM users WHERE code = 'E2E'")).toBe('f');

    psql("DELETE FROM users WHERE code = 'E2E'");
  });
});

test.describe('offline sessions — M3-09', () => {
  /**
   * Signing in opens a session on this machine, and leaving ends it.
   *
   * <h2>Why this is asserted in the database and not on the screen</h2>
   *
   * The screen looked exactly like this before M3-09, when the back office was replaying the
   * operator's PIN on every request. Nothing a person can see distinguishes a real session from a
   * held credential — which is precisely why the change is worth a spec that reads the row.
   *
   * <h2>And why the count matters</h2>
   *
   * One row per sign-in, not one per request. If the terminal ever went back to authenticating
   * each call, this would pass with forty rows instead of one, so the count is the assertion.
   */
  test('signing in opens one session, and leaving revokes it rather than waiting it out', async ({
    till,
  }) => {
    // A shop that has been used has old sessions in it. Only the ones opened from here matter,
    // so the clock is the filter — and it is read before anything is opened.
    const since = psql('SELECT now()::text');
    const opened = () =>
      Number(scalar(`SELECT count(*) FROM sessions WHERE issued_at > '${since}'`) ?? '0');

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await expect(till.page.getByRole('heading', { name: 'Users' })).toBeVisible({
      timeout: 10_000,
    });

    expect(opened()).toBe(1);
    expect(scalar(`SELECT revoked_at FROM sessions WHERE issued_at > '${since}'`)).toBeNull();

    // Doing more work must not open more sessions — the token is carried, not re-earned.
    await till.page.getByRole('button', { name: 'Products' }).click();
    await expect(till.page.getByRole('heading', { name: 'Products' })).toBeVisible({
      timeout: 10_000,
    });
    expect(opened()).toBe(1);

    // "Back to the till", not Escape: once signed in, the shell's way out is the button, and
    // that is the button whose job includes ending the session.
    await till.page.getByRole('button', { name: 'Back to the till' }).click();
    await expect(till.page.getByPlaceholder(/Scan a barcode/)).toBeVisible({ timeout: 10_000 });

    // Revocation is fire-and-forget from the renderer, so the row is polled rather than read
    // once: asserting immediately would be asserting that the network is instant.
    await expect
      .poll(() => scalar(`SELECT revoked_why FROM sessions WHERE issued_at > '${since}'`), {
        timeout: 10_000,
      })
      .toBe('signed out');
  });

  /**
   * A refused sign-in leaves nothing behind.
   *
   * <p>A session row for somebody who never got in would be a lie in the one table an audit
   * reads to answer "who was on this machine".
   */
  test('a refused sign-in opens no session at all', async ({ till }) => {
    const since = psql('SELECT now()::text');

    await openBackOffice(till);
    await signAs(till, 'OWNER', '0000');
    await expect(alert(till)).toContainText('not recognised', { timeout: 10_000 });

    expect(scalar(`SELECT count(*) FROM sessions WHERE issued_at > '${since}'`)).toBe('0');

    await till.page.keyboard.press('Escape');
  });
});

test.describe('local reports — M3-10', () => {
  /**
   * A sale rung up at the till appears in today's report, to the cent.
   *
   * <h2>Why the sale is made through the window and not inserted</h2>
   *
   * Inserting rows and reading them back would test the SQL, which the backend suite already does
   * against real Postgres. What only this can prove is that the two halves agree end to end — that
   * a basket priced by `@lumora/domain`, committed by the till and summed by the report arrives on
   * the screen as the same money. A minor-unit slip or a rounding rule applied on one side only
   * would show up here and nowhere else.
   */
  test("a sale rung up at the till lands in today's report", async ({ till }) => {
    const before = takenToday();
    await sellOne(till, '4791234567890');
    const after = takenToday();
    expect(after).toBeGreaterThan(before);

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Reports' }).click();
    await expect(till.page.getByRole('heading', { name: 'Reports' })).toBeVisible({
      timeout: 10_000,
    });

    // The figure on screen is the database's, to the cent — not merely "a number appeared".
    await expect(till.page.getByText(money(after), { exact: true }).first()).toBeVisible({
      timeout: 10_000,
    });

    // And the sale reached a tender row, which is the half of the report that comes from
    // sale_payments rather than from sales.
    await expect(till.page.getByRole('cell', { name: 'Cash' })).toBeVisible();

    await till.page.getByRole('button', { name: 'Back to the till' }).click();
  });

  /**
   * The gate is the endpoint, not the nav.
   *
   * <p>A cashier cannot reach the back office at all, so the case worth asserting is the one below
   * it: the API refuses a request carrying no session. This asks the backend directly, because a
   * hidden button is not a lock and the network tab is the door somebody would actually try.
   */
  test('the reports API refuses a request carrying no session', async ({ till }) => {
    const refused = await till.page.evaluate(async () => {
      const response = await fetch('/api/reports/day', { cache: 'no-store' });
      return { status: response.status, body: await response.text() };
    });

    expect(refused.status).toBeGreaterThanOrEqual(400);
    expect(refused.body).toContain('Sign in');
  });

  /**
   * Stock on hand is one screen, reached from Reports rather than redrawn inside it.
   *
   * <p>Asserted because a second stock table under Reports is the natural thing for the next
   * person to add, and the day the two disagree the owner has no way to tell which is right.
   */
  /**
   * Low stock (M3-15), end to end: set a threshold on the product form, then see it reported.
   *
   * <h2>Why both halves are in one spec</h2>
   *
   * The backend suite proves the comparison and the null-vs-zero rule against real Postgres. What
   * only this can prove is that the two halves meet: that the number typed into a form on one
   * screen is the number the report on another screen compares against. A field that posted but
   * never reached the query — or a report reading a stale copy — passes every unit test there is.
   *
   * <p>The threshold is set through the form rather than with SQL for exactly that reason: an
   * UPDATE here would skip the half most likely to be wrong.
   */
  test('a product watched from the product form appears on the low stock report', async ({
    till,
  }) => {
    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');

    // Set above whatever is on the shelf now, so the product is low by construction without
    // moving any stock. Clamped at zero because the e2e suite's own sales leave this shelf
    // negative, and a negative threshold is refused by the form — correctly, and it is asserted
    // in the backend suite. Zero still makes an empty-or-negative shelf low.
    const onHand = Number(
      scalar(
        `SELECT COALESCE(sum(m.qty_delta), 0)
           FROM stock_movements m
           JOIN products p ON p.id = m.product_id
          WHERE p.sku = 'TEA-400'`,
      ) ?? '0',
    );
    const threshold = Math.max(onHand + 5, 0);

    await goTo(till, 'Products');
    await editProduct(till, 'Ceylon Tea 400g');

    const reorder = till.page.getByLabel('Reorder at');
    await reorder.fill(String(threshold));
    await till.page.getByRole('button', { name: 'Save' }).click();

    // The form's number reached the row — asserted in the database, because the screen showing it
    // back proves only that React kept it.
    await expect
      .poll(() => scalar("SELECT reorder_point FROM products WHERE sku = 'TEA-400'"), {
        timeout: 10_000,
      })
      .toBe(String(threshold));

    await goTo(till, 'Reports');
    await till.page.getByRole('button', { name: 'Low stock', exact: true }).click();

    await expect(till.page.getByRole('row').filter({ hasText: 'Ceylon Tea 400g' })).toBeVisible({
      timeout: 10_000,
    });

    // Unwatching it takes it off the report again — the property the whole screen rests on, and
    // the one a shopkeeper uses to stop being told about a line they no longer care about.
    await goTo(till, 'Products');
    await editProduct(till, 'Ceylon Tea 400g');
    await till.page.getByLabel('Reorder at').fill('');
    await till.page.getByRole('button', { name: 'Save' }).click();
    await expect
      .poll(() => scalar("SELECT reorder_point FROM products WHERE sku = 'TEA-400'"), {
        timeout: 10_000,
      })
      .toBeNull();

    await goTo(till, 'Reports');
    await till.page.getByRole('button', { name: 'Low stock', exact: true }).click();
    await expect(till.page.getByRole('row').filter({ hasText: 'Ceylon Tea 400g' })).toHaveCount(0);

    await till.page.getByRole('button', { name: 'Back to the till' }).click();
  });

  test('the stock-on-hand tab opens the one stock screen rather than a second copy', async ({
    till,
  }) => {
    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Reports' }).click();
    await till.page.getByRole('button', { name: 'Stock on hand', exact: true }).click();

    await till.page.getByRole('button', { name: 'Open stock on hand' }).click();

    await expect(till.page.getByRole('heading', { name: 'Stock on hand' })).toBeVisible({
      timeout: 10_000,
    });

    await till.page.getByRole('button', { name: 'Back to the till' }).click();
  });
});

test.describe('customers — M3-11', () => {
  /**
   * A customer added at the till, attached to a sale, and found again in the back office.
   *
   * <h2>The whole point is the round trip</h2>
   *
   * The unit tests prove that one number typed three ways is one row and that attaching somebody
   * changes none of the money. What only this can prove is that a cashier can get there: that F6
   * opens on the selling screen, that a number typed on the keypad reaches the search, that "not on
   * file" is a two-key answer rather than a dead end, and that the person then ends up on the sale
   * the till actually commits.
   */
  test('F6 records a new customer mid-sale and the sale is filed under them', async ({ till }) => {
    // A number nothing else has used, and cleaned up front rather than after — a spec that tidies
    // only on success leaves a row the next run collides with on (tenant_id, phone).
    const phone = '0777000111';
    psql(
      `DELETE FROM sales WHERE customer_id IN (SELECT id FROM customers WHERE phone = '${phone}')`,
    );
    psql(`DELETE FROM customers WHERE phone = '${phone}'`);

    await scan(till, '4791234567890');
    await expect(till.page.getByRole('row')).toHaveCount(2, { timeout: 10_000 });

    await till.page.keyboard.press('F6');
    await expect(till.page.getByRole('heading', { name: 'Customer' })).toBeVisible({
      timeout: 10_000,
    });

    await till.page.keyboard.type(phone, { delay: 10 });
    await expect(till.page.getByText('Press Enter to add them')).toBeVisible({ timeout: 10_000 });
    await till.page.keyboard.press('Enter');

    await till.page.keyboard.type('Ruwan Fernando', { delay: 10 });
    await till.page.keyboard.press('Enter');

    // The function bar itself becomes the reminder of who is on the sale — a cashier should not
    // have to reopen the overlay to find out whether they attached anybody.
    await expect(till.page.getByRole('button', { name: /Ruwan Fernando/ })).toBeVisible({
      timeout: 10_000,
    });

    await till.page.keyboard.press('F12');
    await expect(till.page.getByRole('heading', { name: 'Tender' })).toBeVisible();
    await till.page.keyboard.press('Enter');
    await expect(till.page.getByText('Fully tendered')).toBeVisible();
    await till.page.keyboard.press('F12');

    // The sale names them in the database, which is the assertion that matters — the screen
    // claiming a customer is attached is not the same as the row saying so.
    await expect
      .poll(
        () =>
          scalar(
            `SELECT count(*) FROM sales s JOIN customers c ON c.id = s.customer_id
              WHERE c.phone = '${phone}'`,
          ),
        { timeout: 10_000 },
      )
      .toBe('1');

    // And the next sale is anonymous again: a cleared cart that still remembered a customer would
    // file the following sale under whoever happened to be before it.
    await expect(till.page.getByRole('button', { name: 'F6 Customer' })).toBeVisible();

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Customers' }).click();
    await till.page.getByLabel('Find').fill(phone);

    await expect(till.page.getByText('Ruwan Fernando')).toBeVisible({ timeout: 10_000 });
    await till.page.getByRole('button', { name: 'History' }).first().click();
    await expect(till.page.getByRole('columnheader', { name: 'Invoice' })).toBeVisible({
      timeout: 10_000,
    });

    await till.page.getByRole('button', { name: 'Back to the till' }).click();
    psql(
      `DELETE FROM sales WHERE customer_id IN (SELECT id FROM customers WHERE phone = '${phone}')`,
    );
    psql(`DELETE FROM customers WHERE phone = '${phone}'`);
  });

  /**
   * A purchase history needs a session; looking somebody up at the till does not.
   *
   * <p>The asymmetry is the design (see `CustomerController`), so it is asserted rather than
   * assumed — the natural "tidy-up" is to gate both the same way, and gating the till lookup means
   * a cashier types a PIN to write down a phone number, which ends with nobody recording anyone.
   */
  test('the till may look somebody up; only a session may read what they bought', async ({
    till,
  }) => {
    const both = await till.page.evaluate(async () => {
      const search = await fetch('/api/customers?q=077', { cache: 'no-store' });
      const history = await fetch('/api/back-office/customers/1/history', { cache: 'no-store' });
      return { search: search.status, history: history.status, body: await history.text() };
    });

    expect(both.search).toBe(200);
    expect(both.history).toBeGreaterThanOrEqual(400);
    expect(both.body).toContain('Sign in');
  });
});

test.describe('PIN throttle — M3-13', () => {
  /**
   * Guessing gets slower, and the person is told they are not locked out.
   *
   * <h2>Why this is worth an e2e spec at all</h2>
   *
   * The unit tests prove the counting, the escalation and the decay. What they cannot prove is that
   * a shopkeeper standing at the till ever sees the difference between "wrong PIN" and "wait a
   * moment" — and that distinction is the entire user-facing point of the design. A throttle whose
   * message never reaches the screen is a shop phoning support to be unlocked from something that
   * was going to clear itself in five seconds.
   */
  test('a run of wrong PINs starts a wait, and says nobody is locked out', async ({ till }) => {
    // The suite deliberately types wrong PINs elsewhere too, so this starts from a known count
    // rather than inheriting one — and clears up after itself for the specs that follow.
    psql("DELETE FROM pin_attempts WHERE code = 'OWNER'");

    await openBackOffice(till);

    // Four are free: mistyping a PIN twice is an ordinary morning, and being made to wait for it
    // is how a till ends up with its PIN on a sticky note beside it.
    for (let i = 0; i < 4; i++) {
      await signAs(till, 'OWNER', '0000');
      await expect(alert(till)).toContainText('not recognised', { timeout: 10_000 });
    }

    // The fifth is where an honest mistake stops being the likeliest explanation. It says nothing
    // new — announcing "that was your fifth" would tell an attacker exactly where the counter is.
    await signAs(till, 'OWNER', '0000');
    await expect(alert(till)).toContainText('not recognised', { timeout: 10_000 });

    // The wait lands on the next attempt, and applies even to the right PIN — a throttle that let
    // the correct answer through would be no throttle at all.
    await signAs(till, 'OWNER', '1234');
    await expect(alert(till)).toContainText('Too many wrong PINs', { timeout: 10_000 });
    await expect(alert(till)).toContainText('Nobody has been locked out');

    expect(scalar("SELECT locked_until IS NOT NULL FROM pin_attempts WHERE code = 'OWNER'")).toBe(
      't',
    );

    // Ageing the row is what the passage of time does to it, and the till lets them straight in.
    psql("UPDATE pin_attempts SET locked_until = now() - interval '1 minute' WHERE code = 'OWNER'");
    await signAs(till, 'OWNER', '1234');
    await expect(till.page.getByRole('heading', { name: 'Users' })).toBeVisible({
      timeout: 10_000,
    });

    // Signing in successfully clears the count outright: this afternoon starts from zero.
    expect(scalar("SELECT count(*) FROM pin_attempts WHERE code = 'OWNER'")).toBe('0');

    await till.page.getByRole('button', { name: 'Back to the till' }).click();
  });
});

test.describe('products — M3-02', () => {
  /**
   * The full round trip: a product that did not exist is created in the back office, and then
   * scanned at the till.
   *
   * <h2>Why it ends at the till and not at the database</h2>
   *
   * A unit test already proves the row is written and that `ProductLookup` finds it. What only
   * this can prove is that the two halves of the app agree — that a price typed as rupees into a
   * form arrives at the scan path as the right number of cents, through JSON, a controller, and
   * two screens neither of which shares code with the other.
   */
  test('a product added in the back office scans at the till for the price that was typed', async ({
    till,
  }) => {
    // Cleaned up front rather than after: a spec that tidies only on success leaves a product
    // the next run collides with on (tenant_id, sku).
    psql("DELETE FROM product_barcodes WHERE barcode = '9990000000017'");
    psql("DELETE FROM products WHERE sku = 'E2E-JAM'");

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Products' }).click();
    await expect(till.page.getByRole('heading', { name: 'Products' })).toBeVisible({
      timeout: 10_000,
    });

    await till.page.getByRole('button', { name: 'Add a product' }).click();
    await till.page.getByLabel('Product code').fill('E2E-JAM');
    await till.page.getByLabel('Name').fill('Woodapple Jam 350g');
    // 4.29 is chosen for the cents: parseFloat('0.29') * 100 is 28.999999999999996, so a float
    // anywhere on this path arrives as 42899 and this assertion is what would catch it.
    await till.page.getByLabel('Price', { exact: true }).fill('4.29');
    await till.page.getByLabel('Primary barcode').fill('9990000000017');
    await till.page.getByRole('button', { name: 'Create' }).click();

    await expect(till.page.getByRole('status')).toContainText('is now in the catalogue.', {
      timeout: 10_000,
    });
    expect(scalar("SELECT price_minor FROM products WHERE sku = 'E2E-JAM'")).toBe('429');

    // Back to the till, and scan it. This is the assertion the whole spec is for.
    //
    // The button rather than Escape: Escape leaves the *sign-in* screen, and once somebody is
    // signed in it is deliberately not a way out — a stray Escape mid-edit would throw away a
    // half-typed product and the session with it.
    await till.page.getByRole('button', { name: 'Back to the till' }).click();
    await expect(till.page.locator('#scan')).toBeVisible({ timeout: 10_000 });
    await scan(till, '9990000000017');
    await expect(till.page.getByText('Woodapple Jam 350g')).toBeVisible({ timeout: 10_000 });
    await expect(till.page.getByText('4.29').first()).toBeVisible();

    // Leave the cart as it was found, so the next spec starts on an empty till.
    await till.page.keyboard.press('Escape');

    psql("DELETE FROM product_barcodes WHERE barcode = '9990000000017'");
    psql("DELETE FROM products WHERE sku = 'E2E-JAM'");
  });

  /**
   * A barcode belongs to one product, and the refusal says which.
   *
   * <p>The unique index would refuse this on its own. What is being asserted is that the message
   * reaching the person holding the packet names the product already carrying the code, rather
   * than an index.
   */
  test('a barcode already on another product is refused by name', async ({ till }) => {
    psql("DELETE FROM products WHERE sku = 'E2E-DUP'");

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Products' }).click();
    await till.page.getByRole('button', { name: 'Add a product' }).click();

    await till.page.getByLabel('Product code').fill('E2E-DUP');
    await till.page.getByLabel('Name').fill('Not Really Tea');
    await till.page.getByLabel('Price', { exact: true }).fill('100');
    // The seed's Ceylon Tea 400g already carries this one.
    await till.page.getByLabel('Primary barcode').fill('4791234567890');
    await till.page.getByRole('button', { name: 'Create' }).click();

    await expect(alert(till)).toContainText('already on Ceylon Tea 400g', { timeout: 10_000 });
    expect(scalar("SELECT count(*) FROM products WHERE sku = 'E2E-DUP'")).toBe('0');

    await till.page.getByRole('button', { name: 'Back to the till' }).click();
  });

  /**
   * A category is created, a product is filed under it, and the rename moves the product with it.
   *
   * <p>That last step is the whole argument for V110 being a table rather than a column on
   * `products`, so it is worth seeing happen through the screens an owner actually uses.
   */
  test('a category can be created, used, and renamed under the product in it', async ({ till }) => {
    psql("DELETE FROM products WHERE sku = 'E2E-SPICE'");
    psql("DELETE FROM product_categories WHERE name IN ('E2E Aisle', 'E2E Aisle Renamed')");

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Products' }).click();
    await expect(till.page.getByRole('heading', { name: 'Products' })).toBeVisible({
      timeout: 10_000,
    });

    await till.page.getByRole('button', { name: 'Categories' }).click();
    await till.page.getByLabel('New category').fill('E2E Aisle');
    await till.page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(till.page.getByRole('status')).toContainText('E2E Aisle added.', {
      timeout: 10_000,
    });
    await till.page.getByRole('button', { name: 'Done' }).click();

    await till.page.getByRole('button', { name: 'Add a product' }).click();
    await till.page.getByLabel('Product code').fill('E2E-SPICE');
    await till.page.getByLabel('Name').fill('Cardamom 50g');
    await till.page.getByLabel('Price', { exact: true }).fill('880');
    await till.page.getByLabel('Category').selectOption({ label: 'E2E Aisle' });
    await till.page.getByRole('button', { name: 'Create' }).click();
    await expect(till.page.getByRole('status')).toContainText('is now in the catalogue.', {
      timeout: 10_000,
    });

    // One row changes, and the product follows it — no update across the catalogue.
    await till.page.getByRole('button', { name: 'Categories' }).click();
    // Scoped to this category's own row: every category line carries a Rename button, and the
    // seed ships three of them. `has:` rather than `hasText:` because the name lives in an
    // input's value, which is not text content.
    const line = till.page
      .getByRole('listitem')
      .filter({ has: till.page.getByLabel('Rename E2E Aisle') });
    await line.getByLabel('Rename E2E Aisle').fill('E2E Aisle Renamed');
    await line.getByRole('button', { name: 'Rename' }).click();
    await expect(till.page.getByRole('status')).toContainText('E2E Aisle Renamed updated.', {
      timeout: 10_000,
    });

    expect(
      scalar(
        'SELECT c.name FROM products p JOIN product_categories c ON c.id = p.category_id' +
          " WHERE p.sku = 'E2E-SPICE'",
      ),
    ).toBe('E2E Aisle Renamed');

    await till.page.getByRole('button', { name: 'Back to the till' }).click();

    psql("DELETE FROM products WHERE sku = 'E2E-SPICE'");
    psql("DELETE FROM product_categories WHERE name IN ('E2E Aisle', 'E2E Aisle Renamed')");
  });
});

test.describe('CSV import — M3-03', () => {
  /**
   * The whole three-step flow, and the thing worth asserting is the middle step: after the preview
   * the catalogue is untouched.
   *
   * <p>A dry run that quietly writes is worse than no dry run, because the shopkeeper now trusts
   * it. Unit tests assert it against the service; this asserts it against the screen a person
   * actually clicks, with a real HTTP round trip in between.
   */
  test('previews an import without writing, then imports what it showed', async ({ till }) => {
    psql("DELETE FROM product_barcodes WHERE barcode = '9990000000024'");
    psql("DELETE FROM products WHERE sku IN ('E2E-IMP-A', 'E2E-IMP-B')");
    psql("DELETE FROM product_categories WHERE name = 'E2E Imported'");

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Products' }).click();
    await till.page.getByRole('button', { name: 'Import', exact: true }).click();

    await till.page
      .getByLabel('Or paste the rows')
      .fill(
        [
          'sku,name,price,vat,category,barcodes',
          'E2E-IMP-A,Imported Jam 350g,4.29,18,E2E Imported,9990000000024',
          'E2E-IMP-B,Imported Salt 1kg,120.00,0,E2E Imported,',
        ].join('\n'),
      );
    await till.page.getByRole('button', { name: 'Check what this would do' }).click();

    await expect(till.page.getByLabel('Import summary')).toContainText('2 new', {
      timeout: 10_000,
    });
    // Listed, not counted — this is the line that catches "Bevarages" beside "Beverages".
    await expect(till.page.getByText('E2E Imported', { exact: false }).first()).toBeVisible();

    // The assertion the dry run exists for. Nothing has been written.
    expect(scalar("SELECT count(*) FROM products WHERE sku LIKE 'E2E-IMP-%'")).toBe('0');
    expect(scalar("SELECT count(*) FROM product_categories WHERE name = 'E2E Imported'")).toBe('0');

    await till.page.getByRole('button', { name: 'Import 2 products' }).click();
    await expect(till.page.getByRole('status').first()).toContainText('Imported 2 new products', {
      timeout: 10_000,
    });

    // 4.29 is chosen for the cents: parseFloat('4.29') * 100 is 428.99999999999994, so a float
    // anywhere between the textarea and the row would land 428 here.
    expect(scalar("SELECT price_minor FROM products WHERE sku = 'E2E-IMP-A'")).toBe('429');
    expect(scalar("SELECT tax_rate_bp FROM products WHERE sku = 'E2E-IMP-B'")).toBe('0');
    expect(scalar("SELECT count(*) FROM product_categories WHERE name = 'E2E Imported'")).toBe('1');

    await till.page.getByRole('button', { name: 'Back to the till' }).click();

    psql("DELETE FROM product_barcodes WHERE barcode = '9990000000024'");
    psql("DELETE FROM products WHERE sku IN ('E2E-IMP-A', 'E2E-IMP-B')");
    psql("DELETE FROM product_categories WHERE name = 'E2E Imported'");
  });

  /**
   * A file with one bad row imports none of it, and the reason is on screen with a line number.
   *
   * <p>Partial import is the tempting behaviour and the wrong one: 380 of 400 leaves a shop
   * half-updated with no record of which half.
   */
  test('refuses the whole file when one row steals a barcode', async ({ till }) => {
    psql("DELETE FROM products WHERE sku LIKE 'E2E-BAD-%'");

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Products' }).click();
    await till.page.getByRole('button', { name: 'Import', exact: true }).click();

    await till.page.getByLabel('Or paste the rows').fill(
      [
        'sku,name,price,vat,barcodes',
        'E2E-BAD-OK,Perfectly Fine,50.00,18,',
        // The seed's Ceylon Tea 400g already carries this code.
        'E2E-BAD-DUP,Steals A Barcode,60.00,18,4791234567890',
      ].join('\n'),
    );
    await till.page.getByRole('button', { name: 'Check what this would do' }).click();

    await expect(till.page.getByText('already on Ceylon Tea 400g')).toBeVisible({
      timeout: 10_000,
    });
    // The good row is not importable either, and the button says so by being unavailable.
    await expect(till.page.getByRole('button', { name: /^Import \d+ product/ })).toBeDisabled();
    expect(scalar("SELECT count(*) FROM products WHERE sku LIKE 'E2E-BAD-%'")).toBe('0');

    await till.page.getByRole('button', { name: 'Back to the till' }).click();
  });

  /**
   * A file with no VAT column parses cleanly and zero-rates everything in it.
   *
   * <p>Nothing is wrong with any row, so without the warning the preview shows a clean import and
   * the shop finds out at the next VAT return. Asserted in the window because the warning is the
   * entire safeguard.
   */
  test('warns before zero-rating a catalogue that has no VAT column', async ({ till }) => {
    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Products' }).click();
    await till.page.getByRole('button', { name: 'Import', exact: true }).click();

    await till.page
      .getByLabel('Or paste the rows')
      .fill('sku,name,price\nE2E-NOVAT,Something,10.00');
    await till.page.getByRole('button', { name: 'Check what this would do' }).click();

    await expect(till.page.getByText(/zero-rated/)).toBeVisible({ timeout: 10_000 });

    await till.page.getByRole('button', { name: 'Cancel' }).click();
    await till.page.getByRole('button', { name: 'Back to the till' }).click();
  });
});

/** Stock on hand for one SKU, the only way this schema allows: by adding up the movements. */
function onHand(sku: string): number {
  return Number(
    scalar(
      'SELECT COALESCE(sum(m.qty_delta), 0) FROM stock_movements m' +
        ` JOIN products p ON p.id = m.product_id WHERE p.sku = '${sku}'`,
    ) ?? '0',
  );
}

/** Removes a delivery and everything it wrote, in foreign-key order. */
function forgetDelivery(reference: string) {
  psql(
    `DELETE FROM stock_movements WHERE ref_type = 'goods_receipt' AND ref_id IN (SELECT id FROM goods_receipts WHERE reference = '${reference}')`,
  );
  psql(
    `DELETE FROM outbox WHERE aggregate = 'goods_receipt' AND aggregate_id IN (SELECT client_uuid FROM goods_receipts WHERE reference = '${reference}')`,
  );
  psql(`DELETE FROM goods_receipts WHERE reference = '${reference}'`);
}

test.describe('goods received — M3-04', () => {
  /**
   * A delivery booked in through the screen, ending in the number that matters.
   *
   * On hand is computed here the same way the product is: `Σ qty_delta`. There is no level to
   * read, and that is the point — this is the first task in the whole build that could plausibly
   * have incremented one instead.
   */
  test('a delivery puts stock on the shelf as RECEIVE movements', async ({ till }) => {
    forgetDelivery('E2E-DN-1');
    psql("DELETE FROM suppliers WHERE name = 'E2E Wholesale'");
    const before = onHand('TEA-400');

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Stock' }).click();
    await expect(till.page.getByRole('heading', { name: 'Stock' })).toBeVisible({
      timeout: 10_000,
    });

    await till.page.getByRole('button', { name: 'Suppliers' }).click();
    await till.page.getByLabel('New supplier').fill('E2E Wholesale');
    // Exact: every supplier already on file has a "Contact for <name>" field, and a substring
    // match reaches all of them the moment the shop has one supplier.
    await till.page.getByLabel('Contact', { exact: true }).fill('071 000 0000');
    await till.page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(till.page.getByRole('status').first()).toContainText('E2E Wholesale added.', {
      timeout: 10_000,
    });
    await till.page.getByRole('button', { name: 'Done' }).click();

    await till.page.getByRole('button', { name: 'Book in a delivery' }).click();
    await till.page.getByLabel('Supplier').selectOption({ label: 'E2E Wholesale' });
    await till.page.getByLabel('Delivery note №').fill('E2E-DN-1');
    await till.page.getByLabel('Product 1').selectOption({ label: 'TEA-400 — Ceylon Tea 400g' });
    await till.page.getByLabel('Quantity 1').fill('24');
    await till.page.getByLabel('Unit cost 1').fill('300.00');

    // The running total is the shopkeeper's own check against the paper in their hand.
    await expect(till.page.getByText('7,200.00')).toBeVisible();

    await till.page.getByRole('button', { name: 'Book it in' }).click();
    await expect(till.page.getByRole('status').first()).toContainText('Stock is on the shelf.', {
      timeout: 10_000,
    });

    expect(onHand('TEA-400') - before).toBe(24);
    expect(
      scalar(
        'SELECT m.reason FROM stock_movements m JOIN goods_receipts g ON g.id = m.ref_id' +
          " WHERE m.ref_type = 'goods_receipt' AND g.reference = 'E2E-DN-1'",
      ),
    ).toBe('RECEIVE');

    // Cost was recorded and the shelf price was not touched — a delivery must not reprice.
    expect(scalar("SELECT price_minor FROM products WHERE sku = 'TEA-400'")).toBe('45000');

    // And the sync record exists, written in the same transaction as the movements (§A).
    expect(
      scalar(
        'SELECT count(*) FROM outbox o JOIN goods_receipts g ON g.client_uuid = o.aggregate_id' +
          " WHERE o.aggregate = 'goods_receipt' AND g.reference = 'E2E-DN-1'",
      ),
    ).toBe('1');

    await till.page.getByRole('button', { name: 'Back to the till' }).click();

    forgetDelivery('E2E-DN-1');
    psql("DELETE FROM suppliers WHERE name = 'E2E Wholesale'");
  });

  /**
   * The same delivery note twice is refused, and the message says why.
   *
   * The commonest stock error there is: one note keyed in by two people, doubling every quantity
   * on it with nothing on screen to suggest anything happened.
   */
  test('the same delivery note cannot be booked in twice', async ({ till }) => {
    forgetDelivery('E2E-DN-2');
    psql("DELETE FROM suppliers WHERE name = 'E2E Twice'");
    const before = onHand('TEA-400');

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Stock' }).click();

    await till.page.getByRole('button', { name: 'Suppliers' }).click();
    await till.page.getByLabel('New supplier').fill('E2E Twice');
    await till.page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(till.page.getByRole('status').first()).toContainText('E2E Twice added.', {
      timeout: 10_000,
    });
    await till.page.getByRole('button', { name: 'Done' }).click();

    for (const attempt of [1, 2]) {
      await till.page.getByRole('button', { name: 'Book in a delivery' }).click();
      await till.page.getByLabel('Supplier').selectOption({ label: 'E2E Twice' });
      await till.page.getByLabel('Delivery note №').fill('E2E-DN-2');
      await till.page.getByLabel('Product 1').selectOption({ label: 'TEA-400 — Ceylon Tea 400g' });
      await till.page.getByLabel('Quantity 1').fill('10');
      await till.page.getByLabel('Unit cost 1').fill('300');
      await till.page.getByRole('button', { name: 'Book it in' }).click();

      if (attempt === 1) {
        await expect(till.page.getByRole('status').first()).toContainText(
          'Stock is on the shelf.',
          { timeout: 10_000 },
        );
        // And then wait for the form to actually go. The notice is set before the list reloads
        // and the form closes after it, so without this the second attempt fills the *first*
        // attempt's form and then clicks a button that unmounts underneath it.
        await expect(till.page.getByRole('button', { name: /Book it in|Booking in/ })).toHaveCount(
          0,
          { timeout: 10_000 },
        );
      } else {
        await expect(alert(till)).toContainText('already booked in', { timeout: 10_000 });
      }
    }

    // One delivery of ten, not two of twenty. This is the number the constraint protects.
    expect(scalar("SELECT count(*) FROM goods_receipts WHERE reference = 'E2E-DN-2'")).toBe('1');
    expect(onHand('TEA-400') - before).toBe(10);

    await till.page.getByRole('button', { name: 'Cancel' }).click();
    await till.page.getByRole('button', { name: 'Back to the till' }).click();

    forgetDelivery('E2E-DN-2');
    psql("DELETE FROM suppliers WHERE name = 'E2E Twice'");
  });
});

/** Removes an adjustment and its outbox row, so a spec can be re-run. */
function forgetAdjustments(sku: string) {
  psql(
    `DELETE FROM outbox WHERE aggregate = 'stock_adjustment' AND aggregate_id IN (` +
      `SELECT m.client_uuid FROM stock_movements m JOIN products p ON p.id = m.product_id` +
      ` WHERE m.reason = 'ADJUST' AND p.sku = '${sku}')`,
  );
  psql(
    `DELETE FROM stock_movements WHERE reason = 'ADJUST' AND product_id IN (` +
      `SELECT id FROM products WHERE sku = '${sku}')`,
  );
}

test.describe('stock adjustments — M3-05', () => {
  /**
   * An adjustment moves stock, records why, and shows where the shelf landed.
   *
   * The preview is the part worth driving through the real window: the shopkeeper is changing a
   * number they cannot otherwise see, and "on hand → after" is what makes it checkable rather than
   * something they have to trust.
   */
  test('a damaged adjustment removes stock and says who, why and what is left', async ({
    till,
  }) => {
    forgetAdjustments('TEA-400');
    const before = onHand('TEA-400');

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Stock' }).click();
    await till.page.getByRole('button', { name: 'Adjust stock', exact: true }).click();

    await till.page.getByLabel('Product').selectOption({ label: 'TEA-400 — Ceylon Tea 400g' });
    await till.page.getByLabel('Reason').selectOption('DAMAGED');
    await till.page.getByLabel('How many').fill('3');

    // The consequence, shown before it happens.
    await expect(till.page.getByText(String(before - 3), { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    await till.page.getByRole('button', { name: 'Adjust the stock' }).click();
    await expect(till.page.getByRole('status').first()).toContainText('Stock adjusted.', {
      timeout: 10_000,
    });

    expect(onHand('TEA-400')).toBe(before - 3);

    // The reason is on the movement, which is the only thing that makes shrinkage answerable.
    expect(
      scalar(
        'SELECT m.reason_code FROM stock_movements m JOIN products p ON p.id = m.product_id' +
          " WHERE m.reason = 'ADJUST' AND p.sku = 'TEA-400' ORDER BY m.id DESC LIMIT 1",
      ),
    ).toBe('DAMAGED');

    // And the sync record, in the same transaction as the movement (§A).
    expect(
      scalar(
        'SELECT count(*) FROM outbox o JOIN stock_movements m ON m.client_uuid = o.aggregate_id' +
          ' JOIN products p ON p.id = m.product_id' +
          " WHERE o.aggregate = 'stock_adjustment' AND p.sku = 'TEA-400'",
      ),
    ).toBe('1');

    await till.page.getByRole('button', { name: 'Back to the till' }).click();
    forgetAdjustments('TEA-400');
  });

  /**
   * `OTHER` without a note gets no further.
   *
   * Without the rule, OTHER becomes the reason everybody picks and the code stops carrying any
   * information — which would quietly undo the whole point of M3-05.
   */
  test('choosing "something else" requires saying what it was', async ({ till }) => {
    forgetAdjustments('TEA-400');
    const before = onHand('TEA-400');

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Stock' }).click();
    await till.page.getByRole('button', { name: 'Adjust stock', exact: true }).click();

    await till.page.getByLabel('Product').selectOption({ label: 'TEA-400 — Ceylon Tea 400g' });
    await till.page.getByLabel('Reason').selectOption('OTHER');
    await till.page.getByLabel('How many').fill('2');
    // OTHER can go either way, so the form asks — and this is the one reason that does.
    await till.page
      .getByRole('group', { name: 'Which way' })
      .getByText('remove from the shelf')
      .click();

    // The note box is required, so the browser refuses to submit before anything is sent.
    await till.page.getByRole('button', { name: 'Adjust the stock' }).click();
    expect(onHand('TEA-400')).toBe(before);

    await till.page.getByLabel('Note').fill('Used for the window display');
    await till.page.getByRole('button', { name: 'Adjust the stock' }).click();
    await expect(till.page.getByRole('status').first()).toContainText('Stock adjusted.', {
      timeout: 10_000,
    });

    expect(onHand('TEA-400')).toBe(before - 2);
    expect(
      scalar(
        'SELECT m.note FROM stock_movements m JOIN products p ON p.id = m.product_id' +
          " WHERE m.reason = 'ADJUST' AND p.sku = 'TEA-400' ORDER BY m.id DESC LIMIT 1",
      ),
    ).toBe('Used for the window display');

    await till.page.getByRole('button', { name: 'Back to the till' }).click();
    forgetAdjustments('TEA-400');
  });
});

/** Clears any stocktake left behind, so the one-open-per-branch index does not block a re-run. */
function forgetStocktakes() {
  psql(
    "DELETE FROM outbox WHERE aggregate = 'stocktake' AND aggregate_id IN (SELECT client_uuid FROM stocktakes)",
  );
  psql("DELETE FROM stock_movements WHERE ref_type = 'stocktake'");
  psql('DELETE FROM stocktake_items');
  psql('DELETE FROM stocktakes');
}

test.describe('stocktake — M3-06', () => {
  /**
   * The claim the whole design rests on, driven through the real window.
   *
   * A count is entered, a sale then happens, and completing the count applies the *difference* on
   * top of it. Overwriting the level would have silently undone the sale — which is the outcome
   * this spec exists to make impossible to reintroduce quietly.
   *
   * <h2>Absolute counts, not counts derived from the current figure</h2>
   *
   * A shopkeeper counting a shelf types what is on it, so the spec does too. Deriving the number
   * from on-hand is also actively wrong here: this developer database has rung up nineteen sales of
   * TEA-400 and never booked in a delivery, so its on-hand is *negative*, and "current minus three"
   * is not a quantity anybody could count.
   */
  test('counting writes the difference, so a sale during the count survives it', async ({
    till,
  }) => {
    forgetStocktakes();
    const COUNTED = 40;
    const before = onHand('TEA-400');

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Stock' }).click();
    await till.page.getByRole('button', { name: 'Stocktake', exact: true }).click();

    await till.page.getByRole('button', { name: 'Start counting' }).click();
    await expect(till.page.getByText('No stock has moved yet.')).toBeVisible({ timeout: 10_000 });

    await till.page.getByLabel('Product').selectOption({ label: 'TEA-400 — Ceylon Tea 400g' });
    await till.page.getByLabel('Counted').fill(String(COUNTED));
    await till.page.getByRole('button', { name: 'Record the count' }).click();
    await expect(till.page.getByRole('list', { name: 'Counted so far' })).toContainText(
      `counted ${COUNTED}`,
      { timeout: 10_000 },
    );

    // Nothing has moved: an open count is a piece of paper, not an adjustment.
    expect(onHand('TEA-400')).toBe(before);

    // The shop keeps trading while somebody walks round with a clipboard.
    psql(
      'INSERT INTO stock_movements (client_uuid, tenant_id, branch_id, product_id, qty_delta, reason, created_by)' +
        " SELECT gen_random_uuid(), p.tenant_id, b.id, p.id, -2, 'SALE', u.id" +
        ' FROM products p, branches b, users u' +
        " WHERE p.sku = 'TEA-400' AND b.code = 'KND' AND u.code = 'OWNER'",
    );
    expect(onHand('TEA-400')).toBe(before - 2);

    await till.page.getByRole('button', { name: 'Finish and record the differences' }).click();
    await expect(till.page.getByRole('status').first()).toContainText('Stocktake finished.', {
      timeout: 10_000,
    });

    // The assertion the whole milestone turns on. The variance recorded was COUNTED − before, and
    // applying it on top of the sale lands on COUNTED − 2. Overwriting the level would have given
    // COUNTED, silently undoing the two that were sold.
    expect(onHand('TEA-400')).toBe(COUNTED - 2);
    expect(
      scalar(
        "SELECT qty_delta::text FROM stock_movements WHERE reason = 'STOCKTAKE'" +
          ' ORDER BY id DESC LIMIT 1',
      ),
    ).toBe(String(COUNTED - before));

    await till.page.getByRole('button', { name: 'Back to the till' }).click();
    forgetStocktakes();
    // The injected sale carries no ref_type, which is what distinguishes it from the real ones
    // this developer database is full of.
    psql(
      "DELETE FROM stock_movements WHERE reason = 'SALE' AND ref_type IS NULL" +
        " AND product_id IN (SELECT id FROM products WHERE sku = 'TEA-400')",
    );
  });

  /**
   * A product nobody counted is left exactly as it was.
   *
   * Counting one shelf is the normal case, and a design that read an absent line as "zero found"
   * would empty a shop the first time somebody counted the spirits.
   */
  test('a product that was not counted is untouched', async ({ till }) => {
    forgetStocktakes();
    const COUNTED = 12;
    const breadBefore = onHand('BREAD-450');

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Stock' }).click();
    await till.page.getByRole('button', { name: 'Stocktake', exact: true }).click();
    await till.page.getByRole('button', { name: 'Start counting' }).click();
    await expect(till.page.getByText('No stock has moved yet.')).toBeVisible({ timeout: 10_000 });

    await till.page.getByLabel('Product').selectOption({ label: 'TEA-400 — Ceylon Tea 400g' });
    await till.page.getByLabel('Counted').fill(String(COUNTED));
    await till.page.getByRole('button', { name: 'Record the count' }).click();
    await expect(till.page.getByRole('list', { name: 'Counted so far' })).toContainText(
      `counted ${COUNTED}`,
      { timeout: 10_000 },
    );

    await till.page.getByRole('button', { name: 'Finish and record the differences' }).click();
    await expect(till.page.getByRole('status').first()).toContainText('Stocktake finished.', {
      timeout: 10_000,
    });

    expect(onHand('TEA-400')).toBe(COUNTED);
    expect(onHand('BREAD-450')).toBe(breadBefore);

    await till.page.getByRole('button', { name: 'Back to the till' }).click();
    forgetStocktakes();
  });
});

test.describe('stock on hand — M3-07', () => {
  /**
   * The figure on the screen is the sum of the movements, with nothing in between.
   *
   * The assertion that matters is the second half: a movement is inserted straight into the
   * database, the panel is reopened, and the number has already changed. There is no cache to
   * invalidate and no refresh anybody could forget — which is the property a summary table or a
   * materialised view would have cost.
   */
  test('shows the sum of the movements, and follows a new one with no refresh step', async ({
    till,
  }) => {
    const before = onHand('TEA-400');

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Stock' }).click();
    await till.page.getByRole('button', { name: 'On hand', exact: true }).click();

    const teaRow = till.page
      .getByRole('list', { name: 'Stock on hand' })
      .getByRole('listitem')
      .filter({ hasText: 'Ceylon Tea 400g' });
    await expect(teaRow).toContainText(String(before), { timeout: 10_000 });

    // A movement lands from somewhere else entirely — the till, another process, a restore.
    psql(
      'INSERT INTO stock_movements (client_uuid, tenant_id, branch_id, product_id, qty_delta, reason, created_by)' +
        " SELECT gen_random_uuid(), p.tenant_id, b.id, p.id, 6, 'RECEIVE', u.id" +
        ' FROM products p, branches b, users u' +
        " WHERE p.sku = 'TEA-400' AND b.code = 'KND' AND u.code = 'OWNER'",
    );

    // Close and reopen the panel: no refresh button exists, because there is nothing to refresh.
    await till.page.getByRole('button', { name: 'Close' }).click();
    await till.page.getByRole('button', { name: 'On hand', exact: true }).click();
    await expect(teaRow).toContainText(String(before + 6), { timeout: 10_000 });

    psql(
      "DELETE FROM stock_movements WHERE reason = 'RECEIVE' AND ref_type IS NULL" +
        " AND product_id IN (SELECT id FROM products WHERE sku = 'TEA-400')",
    );

    await till.page.getByRole('button', { name: 'Back to the till' }).click();
  });

  /**
   * A shelf below zero is called out, in words as well as colour.
   *
   * This developer database has rung up nineteen sales of TEA-400 and never booked a delivery in,
   * so it is genuinely negative — which is exactly the state a shopkeeper needs to be shown rather
   * than have clamped to a comfortable zero.
   */
  test('a shelf below zero is flagged and sorted to the top', async ({ till }) => {
    expect(onHand('TEA-400')).toBeLessThan(0);

    await openBackOffice(till);
    await signAs(till, 'OWNER', '1234');
    await till.page.getByRole('button', { name: 'Stock' }).click();
    await till.page.getByRole('button', { name: 'On hand', exact: true }).click();

    const list = till.page.getByRole('list', { name: 'Stock on hand' });
    await expect(list.getByRole('listitem').first()).toContainText('below zero', {
      timeout: 10_000,
    });
    await expect(till.page.getByRole('list', { name: 'Stock summary' })).toContainText(
      'below zero',
    );

    await till.page.getByRole('button', { name: 'Back to the till' }).click();
  });
});
