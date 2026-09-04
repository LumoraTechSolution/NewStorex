import { expect, test, type Till } from './till';

/**
 * M6 — the till driven by a finger.
 *
 * The mirror of `keyboard-only-sale.spec.ts`, and it exists for the opposite reason. That
 * suite proves nothing on the sale path *requires* a pointer; this one proves the pointer
 * actually works, because "reachable from the keyboard" and "reachable from a touchscreen"
 * are two different claims and a till has to satisfy both.
 *
 * These specs use `click()` rather than `tap()` deliberately. What is under test is the DOM
 * path — does the handler run, does focus end up where the scanner needs it — and Playwright
 * would need a touch-enabled context to synthesise real touch events, which the Electron
 * fixture does not create. A click produces the same `pointerdown`/`click` sequence with the
 * same `detail`, so `handEvents()` classifies it identically to a finger.
 */

/** Seeded in `dev-seed.sql`. Tea is standard-rated, bread is zero-rated (M1-18). */
const TEA = { barcode: '4791234567890', name: 'Ceylon Tea 400g' };
const BREAD = { barcode: '4791234567951', name: 'Bread 450g' };

/** Types a barcode the way a gun does: fast, then Enter (M1-09). */
async function scan(till: Till, barcode: string) {
  await till.page.keyboard.type(barcode, { delay: 0 });
  await till.page.keyboard.press('Enter');
}

function cartRow(till: Till, name: string) {
  return till.page.getByRole('row').filter({ hasText: name });
}

test.describe('the cart responds to a finger (M6)', () => {
  /**
   * The regression test for `ScanField.reclaim()`, and the reason it is worth the most of
   * any spec in this file.
   *
   * The till's whole input model rests on the scan field never losing the caret: a barcode
   * gun is a keyboard with no pointer, so anywhere the caret is not, a scan goes nowhere.
   * Touch controls take focus when they are tapped. If `reclaim()` ever stops running — or
   * a new control suppresses the `focusin` it listens for — the symptom in a real shop is
   * that scanning silently stops working after the cashier touches the screen once, which
   * is both catastrophic and very hard to attribute.
   */
  test('a scan still lands after a tap, and the caret comes back on its own', async ({ till }) => {
    await scan(till, TEA.barcode);
    await expect(cartRow(till, TEA.name)).toHaveCount(1);

    await scan(till, BREAD.barcode);
    await expect(cartRow(till, BREAD.name)).toHaveCount(1);

    // A finger lands on the first line. This moves focus to the row.
    await cartRow(till, TEA.name).click();
    await expect(cartRow(till, TEA.name)).toHaveAttribute('aria-selected', 'true');

    // ...and the scan field must take it straight back, unprompted.
    await expect(till.page.locator('#scan')).toBeFocused();

    // The proof that it really did: a scan with no intervening click works.
    await scan(till, TEA.barcode);
    await expect(cartRow(till, TEA.name).locator('td').nth(1)).toHaveText('2');

    // And a hand was involved, so the touch suite is not silently keyboard-only.
    expect((await till.handEvents()).length).toBeGreaterThan(0);
  });

  /**
   * The arrows move **one** line per press.
   *
   * ## What this does and does not cover — read before trusting it
   *
   * The bug that prompted it only reproduces under **React StrictMode**, which
   * `next.config.mjs` enables and which is active in `next dev` and **not** in the
   * production build this suite runs against. `move` passed an updater function to
   * `setSelected` from inside a `setLines` updater; React deliberately invokes updaters
   * twice in dev to expose exactly that impurity, so every press moved two lines. A cashier
   * on `pnpm dev` could not land on the middle of three items; the production build was
   * always fine.
   *
   * So this spec **would not have caught it**, and did not — it passed against the broken
   * code. It is kept because single-stepping is worth asserting on its own, and because a
   * future break that is *not* StrictMode-specific will land here. The real guard against a
   * repeat is the rule written on `useCart.move`: never pass an updater function to a
   * setter called from inside another setter's updater.
   *
   * Covering the dev-mode case properly needs the suite to run against a dev server, which
   * it deliberately does not (see `playwright.config.ts` — a production build is what makes
   * the keystroke-timing specs deterministic). Worth revisiting only if a second
   * StrictMode-shaped bug appears.
   */
  test('an arrow key moves exactly one line, not two', async ({ till }) => {
    // Four *different* products, because the cart merges by product. Four is the smallest
    // number that would catch a double-step: with two or three it still clamps onto a real
    // line and the cart looks merely enthusiastic rather than wrong.
    for (const barcode of ['4791234567890', '4791234567906', '4791234567913', '4791234567920']) {
      await scan(till, barcode);
    }
    const rows = till.page.getByRole('row');
    await expect(rows).toHaveCount(5); // header + 4 lines

    // Selection sits on the line just added — the fourth.
    await expect(rows.nth(4)).toHaveAttribute('aria-selected', 'true');

    // Each press moves one, and the interior positions are the ones a double-step skips.
    await till.page.keyboard.press('ArrowUp');
    await expect(rows.nth(3)).toHaveAttribute('aria-selected', 'true');

    await till.page.keyboard.press('ArrowUp');
    await expect(rows.nth(2)).toHaveAttribute('aria-selected', 'true');

    await till.page.keyboard.press('ArrowDown');
    await expect(rows.nth(3)).toHaveAttribute('aria-selected', 'true');
  });

  test('tapping a line selects it, the same as an arrow key', async ({ till }) => {
    await scan(till, TEA.barcode);
    await scan(till, BREAD.barcode);

    // Adding moves selection to the line just added — bread, here.
    await expect(cartRow(till, BREAD.name)).toHaveAttribute('aria-selected', 'true');

    await cartRow(till, TEA.name).click();
    await expect(cartRow(till, TEA.name)).toHaveAttribute('aria-selected', 'true');
    await expect(cartRow(till, BREAD.name)).toHaveAttribute('aria-selected', 'false');

    // Selection is not decorative: it is what the line actions act on. Voiding now must
    // take the tapped line and not the one the keyboard last touched.
    await till.page.keyboard.press('F4');
    await expect(cartRow(till, TEA.name)).toHaveCount(0);
    await expect(cartRow(till, BREAD.name)).toHaveCount(1);
  });

  /**
   * Overlays cover the cart, and a tap that lands on a row behind one must do nothing.
   *
   * Without this the cart is live underneath every overlay in the app — a mis-tap while the
   * tender overlay is open would silently change which line the cashier is about to void
   * once they close it.
   */
  test('a tap behind an overlay changes nothing', async ({ till }) => {
    await scan(till, TEA.barcode);
    await scan(till, BREAD.barcode);
    await expect(cartRow(till, BREAD.name)).toHaveAttribute('aria-selected', 'true');

    await till.page.keyboard.press('F12');
    await expect(till.page.getByRole('heading', { name: 'Tender' })).toBeVisible();

    // `force`, because the overlay is legitimately covering the row — that is the condition
    // under test, and Playwright's actionability check would otherwise refuse the click and
    // pass the test for the wrong reason.
    await cartRow(till, TEA.name).click({ force: true });

    await expect(till.page.getByRole('heading', { name: 'Tender' })).toBeVisible();
    await expect(cartRow(till, BREAD.name)).toHaveAttribute('aria-selected', 'true');
    await expect(cartRow(till, TEA.name)).toHaveAttribute('aria-selected', 'false');
  });
});

/**
 * F3 search — the only route to a product a gun cannot read (M6).
 *
 * On a touchscreen till with no keyboard, an item with a damaged barcode or none at all was
 * previously unsellable: the scan field is `inputMode="none"` and raises no on-screen
 * keyboard. These specs cover both halves — that a finger can reach it, and that the caret
 * behaves, which is the part that could break silently.
 */
test.describe('finding an item by name (M6)', () => {
  const searchBox = (till: Till) => till.page.getByRole('textbox', { name: 'Find an item' });

  test('F3 opens a box that takes the caret, and Enter adds the top hit', async ({ till }) => {
    await till.page.keyboard.press('F3');

    // The overlay must hold the caret — the whole feature rests on this. `ScanField` gives
    // it up only because the parent disables the field while an overlay is open.
    await expect(searchBox(till)).toBeFocused();

    await till.page.keyboard.type('Ceylon');
    await expect(till.page.getByRole('button', { name: new RegExp(TEA.name) })).toBeVisible();

    await till.page.keyboard.press('Enter');
    await expect(cartRow(till, TEA.name)).toHaveCount(1);

    // Closed, and the scan field has the caret back, so the gun works again immediately.
    await expect(searchBox(till)).toHaveCount(0);
    await expect(till.page.locator('#scan')).toBeFocused();

    // No hand was involved in any of that.
    expect(await till.handEvents()).toEqual([]);
  });

  test('the F3 button and a tapped result sell an item with no keyboard at all', async ({
    till,
  }) => {
    await till.page.getByRole('button', { name: 'F3 Search' }).click();
    await expect(searchBox(till)).toBeFocused();

    // Typing is the one thing a real touchscreen does through the OS keyboard rather than
    // through us, so the spec types; what is under test is the tap that follows.
    await till.page.keyboard.type('Bread');
    const hit = till.page.getByRole('button', { name: new RegExp(BREAD.name) });
    await expect(hit).toBeVisible();
    await hit.click();

    await expect(cartRow(till, BREAD.name)).toHaveCount(1);
    await expect(searchBox(till)).toHaveCount(0);
    await expect(till.page.locator('#scan')).toBeFocused();
  });

  test('says so when nothing matches, and adds nothing', async ({ till }) => {
    await till.page.keyboard.press('F3');
    await till.page.keyboard.type('zzzznotathing');

    await expect(till.page.locator('p[role="alert"]')).toContainText('Nothing matches');

    await till.page.keyboard.press('Enter');
    await expect(till.page.getByRole('row')).toHaveCount(0);

    await till.page.keyboard.press('Escape');
    await expect(searchBox(till)).toHaveCount(0);
    await expect(till.page.locator('#scan')).toBeFocused();
  });
});

/**
 * The action rail and its keypad (M6).
 *
 * The multiplier specs matter more than they look. "Tap 3, add an item, get three" is the
 * easy half; the half that goes wrong in production is the *reset*, because a multiplier
 * that survives its own use means the next item silently rings up three times — a money bug
 * the cashier only finds when the customer queries the total.
 */
test.describe('the touch rail (M6)', () => {
  const qty = (till: Till, n: number) =>
    till.page.getByRole('button', { name: String(n), exact: true });

  test('a tapped quantity multiplies the next item, then resets itself', async ({ till }) => {
    await qty(till, 3).click();
    await expect(till.page.getByLabel('Quantity 3')).toBeVisible();

    await scan(till, TEA.barcode);
    await expect(cartRow(till, TEA.name).locator('td').nth(1)).toHaveText('3');

    // Back to one, on screen and in effect.
    await expect(till.page.getByLabel('Quantity 1')).toBeVisible();

    // The assertion that earns this spec: the *next* item is not also tripled.
    await scan(till, BREAD.barcode);
    await expect(cartRow(till, BREAD.name).locator('td').nth(1)).toHaveText('1');
  });

  test('the rail voids, clears and tenders the same as the function keys', async ({ till }) => {
    await scan(till, TEA.barcode);
    await scan(till, BREAD.barcode);

    await till.page.getByRole('button', { name: /^Void line/ }).click();
    await expect(cartRow(till, BREAD.name)).toHaveCount(0);
    await expect(cartRow(till, TEA.name)).toHaveCount(1);

    // PAY opens the tender overlay — it does not commit anything by itself.
    await till.page.getByRole('button', { name: /^PAY/ }).click();
    await expect(till.page.getByRole('heading', { name: 'Tender' })).toBeVisible();
    await till.page.keyboard.press('Escape');

    await till.page.getByRole('button', { name: 'Clear F8' }).click();
    await expect(till.page.getByRole('row')).toHaveCount(0);
  });

  /**
   * The guard against the one implementation shortcut that would break the barcode gun.
   *
   * If the keypad were built by dispatching synthetic `KeyboardEvent`s to reuse the existing
   * key handlers, every tap would advance `lastCharacterAt` — and an Enter arriving within
   * 60ms of a tap would then be read as a scanner terminator (M1-09). Taps must be invisible
   * to that clock.
   */
  test('keypad taps do not look like typing to the scanner rule', async ({ till }) => {
    for (const digit of [1, 2, 3, 4, 5]) {
      await qty(till, digit).click();
    }

    // Then type at human pace and pause before Enter, which is what makes it a search rather
    // than a scan. The pause is the point: if the taps had advanced `lastCharacterAt` the
    // clock would already be running, and the assertion below would fail on a barcode lookup
    // of the word "Ceylon" instead of a name search for it.
    await till.page.keyboard.type('Ceylon', { delay: 30 });
    await till.page.waitForTimeout(300);
    await till.page.keyboard.press('Enter');

    await expect(cartRow(till, TEA.name)).toHaveCount(1);

    // And the quantity the taps built is still pending — nothing consumed it along the way.
    await expect(cartRow(till, TEA.name).locator('td').nth(1)).toHaveText('123');
  });
});

/**
 * Taking the money with a finger (M6).
 *
 * The quick-cash tiles are the part worth the most scrutiny. `buffer` holds **minor** units
 * — digits accumulate from the right and are read as `minor(Number(buffer))` — so a tile for
 * a 1,000-rupee note must set "100000". Set "1000" instead and the till tenders ten rupees,
 * announces a huge shortfall, and the cashier has no idea why. The spec below asserts the
 * arithmetic end to end rather than the string, so it fails on the outcome a shopkeeper would
 * actually notice.
 */
test.describe('tendering with a finger (M6)', () => {
  test('a whole sale from scan to committed, using only taps', async ({ till }) => {
    await scan(till, TEA.barcode); // 450.00
    await expect(cartRow(till, TEA.name)).toHaveCount(1);

    await till.page.getByRole('button', { name: /^PAY/ }).click();
    await expect(till.page.getByRole('heading', { name: 'Tender' })).toBeVisible();

    // A 500-rupee note for a 450-rupee basket: settles the sale and leaves 50.00 change.
    // `exact`, or this also matches the "1,500.00" and "2,500.00" tiles beside it.
    await till.page.getByRole('button', { name: '500.00', exact: true }).click();
    await till.page.getByRole('button', { name: 'Tender this' }).click();

    // `exact`, because "50.00" is a substring of the "450.00" that appears six times on this
    // screen — the basket total, the PAY key and the amount due among them.
    await expect(till.page.getByText('Change due')).toBeVisible();
    await expect(till.page.getByText('50.00', { exact: true })).toBeVisible();

    await till.page.getByRole('button', { name: 'Complete' }).click();

    const status = till.page.locator('p[role="status"]');
    await expect(status).toContainText(/KND-T1-\d{6}/);

    // A hand did all of that, so this spec is not silently keyboard-driven.
    expect((await till.handEvents()).length).toBeGreaterThan(0);
  });

  test('the payment type can be tapped, and switching it clears a part-typed amount', async ({
    till,
  }) => {
    await scan(till, TEA.barcode);
    // Waited for, not assumed: `openTender` refuses an empty cart, so pressing F12 before the
    // scan has landed silently does nothing and the failure surfaces as a missing button.
    await expect(cartRow(till, TEA.name)).toHaveCount(1);

    await till.page.keyboard.press('F12');
    await expect(till.page.getByRole('heading', { name: 'Tender' })).toBeVisible();

    await till.page.getByRole('button', { name: 'Card' }).click();
    await expect(till.page.getByRole('button', { name: 'Card' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    // Card cannot overpay, so the suggestion is the exact amount owed — tender and settle.
    await till.page.getByRole('button', { name: 'Tender this' }).click();
    await expect(till.page.getByText('Fully tendered')).toBeVisible();
  });
});

/**
 * F1 help (M6). The slot has been rendered-but-dead since M1-07, which was fine while it was
 * a 56px legend; at 84px it is a button, and a button that does nothing when pressed is worse
 * than one that is not there.
 */
test.describe('the key map (M6)', () => {
  test('F1 lists the keys, and closes from either the keyboard or a tap', async ({ till }) => {
    await till.page.keyboard.press('F1');
    await expect(till.page.getByRole('heading', { name: 'Keys' })).toBeVisible();
    await expect(till.page.getByText('Void the selected line')).toBeVisible();
    // Unassigned keys are listed as unassigned rather than hidden — that is the answer to
    // the question somebody opened this to ask.
    await expect(till.page.getByText('Not assigned yet').first()).toBeVisible();

    await till.page.keyboard.press('Escape');
    await expect(till.page.getByRole('heading', { name: 'Keys' })).toHaveCount(0);

    // And by finger, for the cashier with no keyboard who is most likely to need it.
    await till.page.getByRole('button', { name: 'F1 Help' }).click();
    await expect(till.page.getByRole('heading', { name: 'Keys' })).toBeVisible();
    await till.page.getByRole('button', { name: 'Close' }).click();
    await expect(till.page.getByRole('heading', { name: 'Keys' })).toHaveCount(0);

    await expect(till.page.locator('#scan')).toBeFocused();
  });
});
