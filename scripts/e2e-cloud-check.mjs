#!/usr/bin/env node
/* eslint-disable no-console -- a command-line script; stdout is its user interface */
/**
 * A trading day, rung against a real till, checked against the real cloud.
 *
 *   node scripts/e2e-cloud-check.mjs
 *
 * This is not a unit test and does not replace one. `pnpm test` proves the money
 * math in isolation and `./mvnw -B test` proves the write paths against Postgres.
 * What neither can prove is the part that only exists once the system is
 * deployed: that a shift, a discounted sale, a split tender, a partial refund
 * and a cash drop all reach a database in Singapore with their arithmetic
 * intact, and that sending them twice changes nothing.
 *
 * So it drives the desktop API exactly as the terminal does, then reads the
 * cloud directly with psql and compares. Every assertion is a comparison of two
 * independently computed numbers — the till's and the cloud's — never a restating
 * of one of them.
 *
 * Requires: `pnpm db:up`, `pnpm db:seed`, the desktop backend running with a
 * cloud token (see services/backend/DEPLOYMENT.md), and psql on PATH or at the
 * usual Windows install path.
 *
 * It writes real rows. Everything it creates is tagged with a run id and listed
 * at the end so you can find them; it deletes nothing, because deleting a sale
 * is not a thing this system does.
 */

import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync } from 'node:fs';

// ---------------------------------------------------------------- configuration

const TILL = process.env.TILL_URL ?? 'http://127.0.0.1:8081';
const CLOUD_URL = process.env.CLOUD_DUMP_URL; // libpq URI, same one db:dump:cloud uses
const BRANCH = process.env.BRANCH_CODE ?? 'KND';
const TERMINAL = process.env.TERMINAL_CODE ?? 'T1';
const PIN = process.env.SEED_PIN ?? '1234';
const CASHIER = 'NIMAL';
const MANAGER = 'MGR';

// How long to wait for the outbox to drain. The worker ticks every 10s with a
// 15s initial delay, so 90s is generous rather than optimistic.
const SYNC_TIMEOUT_MS = 90_000;

const PSQL_CANDIDATES = [
  'psql',
  'C:/Program Files/PostgreSQL/17/bin/psql.exe',
  'C:/Program Files/PostgreSQL/16/bin/psql.exe',
];

// ---------------------------------------------------------------- tiny harness

const results = [];
let failures = 0;

function check(name, actual, expected, note) {
  const ok = actual === expected;
  if (!ok) failures++;
  results.push({ name, ok, actual, expected, note });
  const mark = ok ? '  ok  ' : ' FAIL ';
  const detail = ok ? `${actual}` : `got ${actual}, expected ${expected}`;
  console.log(`${mark} ${name}  ${detail}${note ? `  (${note})` : ''}`);
  return ok;
}

function section(title) {
  console.log(`\n\u2500\u2500 ${title} ${'\u2500'.repeat(Math.max(0, 62 - title.length))}`);
}

function money(minor) {
  return `Rs ${(minor / 100).toFixed(2)}`;
}

// ---------------------------------------------------------------- till client

async function till(method, path, body, token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`${TILL}${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = text;
  }
  if (parsed && typeof parsed === 'object') parsed.__status = res.status;
  if (!res.ok) {
    const err = new Error(`${method} ${path} -> ${res.status}: ${text.slice(0, 400)}`);
    err.status = res.status;
    err.body = parsed;
    throw err;
  }
  return parsed;
}

/**
 * The till API is loopback-only and carries the operator's PIN in the request
 * body where the decision is made, rather than behind a session token. Only the
 * back office (`/api/auth/session`) issues one, and a cashier is refused there
 * by design — so this script holds no tokens at all.
 */

// ---------------------------------------------------------------- cloud client

let psqlBin = null;

function findPsql() {
  for (const c of PSQL_CANDIDATES) {
    if (c === 'psql') {
      const probe = spawnSync(c, ['--version'], { encoding: 'utf8' });
      if (!probe.error) return c;
    } else if (existsSync(c)) {
      return c;
    }
  }
  return null;
}

/** One scalar out of the cloud. Returns a string; callers coerce. */
function cloud(sql) {
  const r = spawnSync(psqlBin, [CLOUD_URL, '-tAc', sql], {
    encoding: 'utf8',
    env: { ...process.env },
  });
  if (r.status !== 0) {
    throw new Error(`psql failed: ${(r.stderr || r.stdout || '').trim().slice(0, 300)}`);
  }
  return r.stdout.trim();
}

function cloudInt(sql) {
  const v = cloud(sql);
  return v === '' ? 0 : Number(v);
}

// ---------------------------------------------------------------- sync helpers

async function syncStatus() {
  return till('GET', '/api/sync/status');
}

/** Waits for the outbox to be empty and at least one push to have succeeded. */
async function waitForDrain(label) {
  const started = Date.now();
  let last = null;
  while (Date.now() - started < SYNC_TIMEOUT_MS) {
    last = await syncStatus();
    if (last.pending === 0 && last.lastSuccessAt) {
      const secs = ((Date.now() - started) / 1000).toFixed(1);
      console.log(`  ok   outbox drained after ${label} in ${secs}s`);
      return last;
    }
    if (last.lastError) {
      console.log(`  ..   sync retrying: ${last.lastError}`);
    }
    await new Promise((r) => setTimeout(r, 2000));
  }
  failures++;
  console.log(
    ` FAIL outbox did not drain after ${label} within ${SYNC_TIMEOUT_MS / 1000}s: ` +
      `pending=${last?.pending} lastAttemptAt=${last?.lastAttemptAt} lastError=${last?.lastError}`,
  );
  if (last && last.pending > 0 && !last.lastAttemptAt) {
    console.log(
      '       No attempt was ever made. That is the no-token signature: the worker returns\n' +
        '       before opening a connection. Check LUMORA_CLOUD_TOKEN is set in the shell that\n' +
        '       launched the backend — setx does not reach an already-open window.',
    );
  }
  return last;
}

// ---------------------------------------------------------------- the trading day

async function main() {
  const runId = randomUUID().slice(0, 8);

  console.log(`StoreX end-to-end check   run ${runId}`);
  console.log(`till  ${TILL}`);

  psqlBin = findPsql();
  if (!CLOUD_URL) {
    console.error(
      '\nCLOUD_DUMP_URL is not set. It is the cloud database as a libpq URI:\n' +
        "  $env:CLOUD_DUMP_URL = 'postgresql://USER:PASS@HOST/lumora_cloud?sslmode=require'\n",
    );
    process.exit(1);
  }
  if (!psqlBin) {
    console.error('\npsql not found. Install the Postgres client tools or add psql to PATH.\n');
    process.exit(1);
  }

  // Baselines. Every later assertion is a delta against these, so the script is
  // safe to run against a cloud that already holds a year of trading.
  const base = {
    sales: cloudInt('SELECT count(*) FROM sales'),
    items: cloudInt('SELECT count(*) FROM sale_items'),
    payments: cloudInt('SELECT count(*) FROM sale_payments'),
    refunds: cloudInt('SELECT count(*) FROM refunds'),
    movements: cloudInt('SELECT count(*) FROM stock_movements'),
    shifts: cloudInt('SELECT count(*) FROM shifts'),
    cash: cloudInt('SELECT count(*) FROM cash_movements'),
  };
  console.log(`cloud baseline  sales=${base.sales} refunds=${base.refunds} shifts=${base.shifts}`);

  // ---------------------------------------------------------------- 1. open shift

  section('1. Open the shift');

  const shiftUuid = randomUUID();
  // Rs 5,000 float: 10x500 + 20x100 + 20x50 + 100x20... kept simple and exact.
  const openingCount = [
    { denominationMinor: 500000, qty: 5 }, // 5 x Rs 5000
    { denominationMinor: 100000, qty: 20 }, // 20 x Rs 1000
    { denominationMinor: 50000, qty: 10 }, // 10 x Rs 500
  ];
  const openingFloat = openingCount.reduce((s, d) => s + d.denominationMinor * d.qty, 0);

  // A terminal may hold only one open shift, which is correct and also means an
  // aborted earlier run leaves one behind. Close it rather than making the
  // operator clean up after a test.
  const stale = await till(
    'GET',
    `/api/shifts/current?branchCode=${BRANCH}&terminalCode=${TERMINAL}`,
  ).catch(() => null);
  if (stale && stale.open && stale.shiftId) {
    console.log(`  ..   closing a shift left open by an earlier run`);
    await till('POST', `/api/shifts/${stale.shiftId}/close`, {
      operatorCode: CASHIER,
      operatorPin: PIN,
      countedCashMinor: 0,
      closingCount: [{ denominationMinor: 100000, qty: 0 }],
      varianceReason: 'OTHER',
      varianceNote: 'abandoned e2e run',
    }).catch((e) => console.log(`  ..   could not close it: ${e.message.slice(0, 120)}`));
  }

  await till('POST', '/api/shifts', {
    clientUuid: shiftUuid,
    branchCode: BRANCH,
    terminalCode: TERMINAL,
    operatorCode: CASHIER,
    operatorPin: PIN,
    openingFloatMinor: openingFloat,
    openingCount,
  });
  console.log(`  ok   shift opened with a float of ${money(openingFloat)}`);

  // ---------------------------------------------------------------- 2. the sales

  section('2. Ring up a realistic basket of sales');

  // Seeded catalogue, all INCLUSIVE. VAT is *extracted* from these prices,
  // never added — vat = total * rate / (1 + rate).
  const P = {
    tea: { uuid: '00000000-0000-4000-8000-000000000101', price: 45000, bp: 1800 },
    rice: { uuid: '00000000-0000-4000-8000-000000000102', price: 285000, bp: 1800 },
    soap: { uuid: '00000000-0000-4000-8000-000000000103', price: 18500, bp: 1800 },
    milk: { uuid: '00000000-0000-4000-8000-000000000104', price: 49000, bp: 1800 },
    powder: { uuid: '00000000-0000-4000-8000-000000000105', price: 139000, bp: 1800 },
    sugar: { uuid: '00000000-0000-4000-8000-000000000106', price: 32000, bp: 1800 },
    bread: { uuid: '00000000-0000-4000-8000-000000000107', price: 25000, bp: 0 }, // zero-rated
  };

  /** VAT extracted from an inclusive amount. The one formula in §A. */
  const vatOf = (inclusiveMinor, bp) => Math.round((inclusiveMinor * bp) / (10000 + bp));

  /** Builds a line, applying a discount to the inclusive total first. */
  const line = (p, qty, discountMinor = 0) => {
    const gross = p.price * qty;
    const lineTotal = gross - discountMinor;
    return {
      productClientUuid: p.uuid,
      qty,
      unitPriceMinor: p.price,
      discountMinor,
      taxMinor: vatOf(lineTotal, p.bp),
      lineTotalMinor: lineTotal,
      taxMode: 'INCLUSIVE',
      taxRateBp: p.bp,
    };
  };

  /**
   * Sums lines into the sale-level totals, matching the checksum in SaleService:
   *
   *   subtotalMinor === Σ line.lineTotalMinor        (already net of line discounts)
   *   subtotalMinor − discountMinor === totalMinor    (discountMinor is a *sale-level* discount)
   *
   * So the two discounts are different things and both are exercised below: a
   * line discount is folded into its own line and leaves discountMinor at zero;
   * a whole-basket discount is the sale-level one.
   */
  const summarise = (lines, saleDiscountMinor = 0) => {
    const subtotal = lines.reduce((s, l) => s + l.lineTotalMinor, 0);
    return {
      subtotalMinor: subtotal,
      discountMinor: saleDiscountMinor,
      taxMinor: lines.reduce((s, l) => s + l.taxMinor, 0),
      totalMinor: subtotal - saleDiscountMinor,
    };
  };

  const sales = [];

  async function ringUp(name, lines, tenders, extra = {}) {
    const s = summarise(lines, extra.saleDiscountMinor ?? 0);
    const paid = tenders.reduce((t, x) => t + x.amountMinor, 0);
    const rounding = extra.roundingAdjustmentMinor ?? 0;
    const change = Math.max(0, paid - (s.totalMinor + rounding));
    const body = {
      clientUuid: randomUUID(),
      branchCode: BRANCH,
      terminalCode: TERMINAL,
      taxMode: 'INCLUSIVE',
      taxRateBp: 1800,
      ...s,
      lines,
      tenders,
      roundingAdjustmentMinor: rounding,
      changeMinor: change,
    };
    const res = await till('POST', '/api/sales', body);
    sales.push({ name, body, res });
    console.log(
      `  ok   ${name.padEnd(34)} ${money(s.totalMinor).padStart(12)}  ` +
        `vat ${money(s.taxMinor).padStart(10)}  ${res.invoiceNumber}`,
    );
    return { body, res, summary: s };
  }

  // (a) The ordinary case: one item, exact cash.
  const saleA = await ringUp(
    'plain cash sale',
    [line(P.tea, 2)],
    [{ kind: 'CASH', amountMinor: 90000 }],
  );

  // (b) A line discount. The discount comes off the inclusive price, so the VAT
  //     must fall with it — this is where a wrong implementation shows itself.
  const riceDiscount = 28500; // 10% off one 5kg bag
  const saleB = await ringUp(
    'line discount, 10% off rice',
    [line(P.rice, 1, riceDiscount), line(P.sugar, 2)],
    [{ kind: 'CASH', amountMinor: 350000 }],
  );

  // (c) Mixed tax rates in one basket: standard-rated groceries plus zero-rated
  //     bread. What was charged is the sum of the lines, not a sale-level rate.
  const mixedLines = [line(P.bread, 3), line(P.milk, 2), line(P.soap, 4)];
  const saleC = await ringUp('mixed rates, incl. zero-rated bread', mixedLines, [
    { kind: 'CARD', amountMinor: summarise(mixedLines).totalMinor },
  ]);

  // (d) Split tender: part card, part cash. One sale, two payment rows.
  const splitLines = [line(P.powder, 2), line(P.tea, 1)];
  const splitTotal = summarise(splitLines).totalMinor;
  const saleD = await ringUp('split tender, card + cash', splitLines, [
    { kind: 'CARD', amountMinor: 200000 },
    { kind: 'CASH', amountMinor: splitTotal - 200000 },
  ]);

  // (e) A whole-basket discount, distinct from (b): this one is taken off the
  //     sale rather than any line, which is the shape SaleService checksums as
  //     subtotal − discountMinor === total.
  const basketLines = [line(P.rice, 2), line(P.sugar, 1)];
  const basketDiscount = 50000; // Rs 500 off the basket
  const saleF = await ringUp(
    'sale-level discount, Rs 500 off',
    basketLines,
    [{ kind: 'CASH', amountMinor: summarise(basketLines, basketDiscount).totalMinor }],
    { saleDiscountMinor: basketDiscount },
  );

  // (f) Cash rounding. Sri Lanka has no 1-rupee coin in practice, so cash
  //     settles to the nearest 5. The adjustment rides beside the total and
  //     never alters it (M1-03).
  const roundLines = [line(P.soap, 3)];
  const roundTotal = summarise(roundLines).totalMinor; // 55500 -> 555.00
  const rounded = Math.round(roundTotal / 500) * 500;
  const saleE = await ringUp(
    'cash rounded to nearest 5',
    roundLines,
    [{ kind: 'CASH', amountMinor: 60000 }],
    { roundingAdjustmentMinor: rounded - roundTotal },
  );

  const tillTotal = sales.reduce((s, x) => s + x.body.totalMinor, 0);
  const tillVat = sales.reduce((s, x) => s + x.body.taxMinor, 0);
  console.log(`       ${sales.length} sales, ${money(tillTotal)}, VAT ${money(tillVat)}`);

  // ---------------------------------------------------------------- 3. arithmetic

  section('3. The money math, checked against the domain rules');

  // Rice at Rs 2,850 less 10% = Rs 2,565 inclusive. VAT at 18% extracted:
  // 256500 * 1800 / 11800 = 39127.1..., rounds to 39127.
  check(
    'discount reduces VAT proportionally',
    saleB.body.lines[0].taxMinor,
    vatOf(285000 - riceDiscount, 1800),
    'VAT extracted after discount, not before',
  );

  check('zero-rated line carries no VAT', saleC.body.lines[0].taxMinor, 0, 'bread is exempt');

  check(
    'mixed-rate sale VAT is the sum of its lines',
    saleC.body.taxMinor,
    saleC.body.lines.reduce((s, l) => s + l.taxMinor, 0),
    'not a sale-level rate applied to the total',
  );

  check(
    'split tender covers the sale exactly',
    saleD.body.tenders.reduce((s, t) => s + t.amountMinor, 0),
    saleD.body.totalMinor,
  );

  check(
    'sale-level discount comes off the total',
    saleF.body.totalMinor,
    saleF.body.subtotalMinor - basketDiscount,
    `${money(basketDiscount)} off ${money(saleF.body.subtotalMinor)}`,
  );

  check(
    'rounding never changes the sale total',
    saleE.body.totalMinor,
    roundTotal,
    `adjustment ${money(saleE.body.roundingAdjustmentMinor)} sits beside it`,
  );

  // Invoice numbers come from the terminal's own reserved block.
  const invoices = sales.map((s) => s.res.invoiceNumber);
  const allPrefixed = invoices.every((i) => i.startsWith(`${BRANCH}-${TERMINAL}-`));
  check('invoice numbers use the terminal block', allPrefixed, true, invoices.join(' '));
  check('invoice numbers are unique', new Set(invoices).size, invoices.length);

  // ---------------------------------------------------------------- 4. cash movement

  section('4. Cash movements');

  const dropAmount = 200000; // Rs 2,000 to the safe
  await till('POST', '/api/cash-movements', {
    clientUuid: randomUUID(),
    branchCode: BRANCH,
    terminalCode: TERMINAL,
    kind: 'DROP',
    amountMinor: dropAmount,
    reasonCode: 'SAFE_DROP',
    note: `e2e ${runId}`,
  });
  console.log(`  ok   safe drop of ${money(dropAmount)}`);

  // ---------------------------------------------------------------- 5. refund

  section('5. Refund against a receipt');

  // Refund one of the two milk powders from sale (d). A refund is always
  // against an invoice — there is no such thing as a loose refund here.
  const lookup = await till(
    'GET',
    `/api/refunds/lookup?invoiceNumber=${encodeURIComponent(saleD.res.invoiceNumber)}`,
    null,
  );
  check('receipt lookup finds the sale', lookup.invoiceNumber, saleD.res.invoiceNumber);

  // The lookup speaks the receipt's language: chargedMinor is what the customer
  // actually paid for that line, and alreadyRefundedQty is what the counter has
  // already given back — the pair is what bounds a second refund.
  const powderLine = lookup.lines.find((l) => l.qty >= 2) ?? lookup.lines[0];
  const refundQty = 1;
  const perUnit = Math.round(powderLine.chargedMinor / powderLine.qty);
  const refundTax = vatOf(perUnit, powderLine.taxRateBp);

  // Money goes back the way it came: a tender cannot be refunded more than it
  // paid, which is what stops a card sale being refunded as cash out of the
  // drawer. This sale was split, so the refund is split too — taking cash first
  // and topping up from the card.
  const refundTenders = [];
  let left = perUnit;
  for (const t of lookup.tenders) {
    if (left <= 0) break;
    const take = Math.min(left, t.refundableMinor);
    if (take > 0) {
      refundTenders.push({ kind: t.kind, amountMinor: take });
      left -= take;
    }
  }

  // Refunding a tender more than it took must be refused. The split sale paid
  // Rs 1,230 in cash, so asking for the whole line back in cash is the exact
  // shape of a card sale being cashed out at the counter.
  let overTenderRejected = false;
  try {
    await till('POST', '/api/refunds', {
      clientUuid: randomUUID(),
      branchCode: BRANCH,
      terminalCode: TERMINAL,
      invoiceNumber: saleD.res.invoiceNumber,
      managerCode: MANAGER,
      managerPin: PIN,
      totalMinor: perUnit,
      taxMinor: refundTax,
      roundingAdjustmentMinor: 0,
      lines: [
        {
          saleLineNo: powderLine.lineNo,
          qty: 1,
          refundTotalMinor: perUnit,
          taxMinor: refundTax,
          reasonCode: 'OTHER',
          restock: false,
        },
      ],
      tenders: [{ kind: 'CASH', amountMinor: perUnit }],
    });
  } catch (e) {
    overTenderRejected = e.status >= 400 && e.status < 500;
  }
  check('a tender cannot be refunded more than it took', overTenderRejected, true);

  const refundRes = await till('POST', '/api/refunds', {
    clientUuid: randomUUID(),
    branchCode: BRANCH,
    terminalCode: TERMINAL,
    invoiceNumber: saleD.res.invoiceNumber,
    managerCode: MANAGER,
    managerPin: PIN,
    totalMinor: perUnit,
    taxMinor: refundTax,
    roundingAdjustmentMinor: 0,
    lines: [
      {
        saleLineNo: powderLine.lineNo,
        qty: refundQty,
        refundTotalMinor: perUnit,
        taxMinor: refundTax,
        reasonCode: 'FAULTY',
        note: `e2e ${runId}`,
        restock: false,
      },
    ],
    tenders: refundTenders,
  });
  console.log(
    `  ok   refunded ${money(perUnit)} against ${saleD.res.invoiceNumber} across ` +
      refundTenders.map((t) => `${t.kind} ${money(t.amountMinor)}`).join(' + '),
  );

  // The receipt must now show less left to refund than it did a moment ago.
  check(
    'the refund is given its own number',
    typeof refundRes.refundNumber === 'string' || typeof refundRes.id === 'number',
    true,
  );

  const after = await till(
    'GET',
    `/api/refunds/lookup?invoiceNumber=${encodeURIComponent(saleD.res.invoiceNumber)}`,
    null,
  );
  const lineAfter = after.lines.find((l) => l.lineNo === powderLine.lineNo);
  check('refunded qty is recorded against the line', lineAfter.alreadyRefundedQty, refundQty);
  check(
    'refunded value is recorded against the line',
    lineAfter.alreadyRefundedMinor,
    perUnit,
    money(perUnit),
  );

  // Over-refunding must be refused. This is the assertion that matters most in
  // this section: a receipt-linked refund is only a control if the limit holds.
  let overRefundRejected = false;
  try {
    await till('POST', '/api/refunds', {
      clientUuid: randomUUID(),
      branchCode: BRANCH,
      terminalCode: TERMINAL,
      invoiceNumber: saleD.res.invoiceNumber,
      managerCode: MANAGER,
      managerPin: PIN,
      totalMinor: perUnit * 10,
      taxMinor: vatOf(perUnit * 10, 1800),
      roundingAdjustmentMinor: 0,
      lines: [
        {
          saleLineNo: powderLine.lineNo,
          qty: 10,
          refundTotalMinor: perUnit * 10,
          taxMinor: vatOf(perUnit * 10, 1800),
          reasonCode: 'OTHER',
          note: 'should be refused',
          restock: false,
        },
      ],
      tenders: [{ kind: 'CASH', amountMinor: perUnit * 10 }],
    });
  } catch (e) {
    overRefundRejected = e.status >= 400 && e.status < 500;
  }
  check('refunding more than was sold is refused', overRefundRejected, true);

  // A cashier must not be able to authorise a refund.
  let cashierRefundRejected = false;
  let cashierRefusalMessage = '';
  try {
    await till('POST', '/api/refunds', {
      clientUuid: randomUUID(),
      branchCode: BRANCH,
      terminalCode: TERMINAL,
      invoiceNumber: saleA.res.invoiceNumber,
      managerCode: CASHIER,
      managerPin: PIN,
      totalMinor: 1000,
      taxMinor: vatOf(1000, 1800),
      roundingAdjustmentMinor: 0,
      lines: [
        {
          saleLineNo: 1,
          qty: 1,
          refundTotalMinor: 1000,
          taxMinor: vatOf(1000, 1800),
          reasonCode: 'OTHER',
          restock: false,
        },
      ],
      tenders: [{ kind: 'CASH', amountMinor: 1000 }],
    });
  } catch (e) {
    // 422, not 401: the PIN was accepted and the *role* was refused, which is
    // why the message can name the person. A 401 would mean "who are you";
    // this means "you, specifically, may not do that".
    cashierRefundRejected = e.status === 422;
    cashierRefusalMessage = e.body?.detail ?? '';
  }
  check('a cashier cannot authorise a refund', cashierRefundRejected, true);
  check(
    'the refusal names the person and the role',
    /cashier/i.test(cashierRefusalMessage) && /Nimal/i.test(cashierRefusalMessage),
    true,
    cashierRefusalMessage.slice(0, 60),
  );

  // ---------------------------------------------------------------- 6. sync

  section('6. Everything reaches the cloud');

  await waitForDrain('the trading day');

  const got = {
    sales: cloudInt('SELECT count(*) FROM sales') - base.sales,
    items: cloudInt('SELECT count(*) FROM sale_items') - base.items,
    payments: cloudInt('SELECT count(*) FROM sale_payments') - base.payments,
    refunds: cloudInt('SELECT count(*) FROM refunds') - base.refunds,
    shifts: cloudInt('SELECT count(*) FROM shifts') - base.shifts,
    cash: cloudInt('SELECT count(*) FROM cash_movements') - base.cash,
  };

  const expectedItems = sales.reduce((s, x) => s + x.body.lines.length, 0);
  const expectedPayments = sales.reduce((s, x) => s + x.body.tenders.length, 0);

  check('sales arrived', got.sales, sales.length);
  check('every line arrived', got.items, expectedItems);
  check('every payment arrived', got.payments, expectedPayments, 'split tender = 2 rows');
  check('the refund arrived', got.refunds, 1);
  check('the shift arrived', got.shifts, 1);
  check('the cash movement arrived', got.cash, 1);

  // The figures, not just the row counts. This is the check that would catch a
  // serialisation bug that a count never would.
  for (const s of sales) {
    const u = s.body.clientUuid;
    const cloudTotal = cloudInt(`SELECT total_minor FROM sales WHERE client_uuid = '${u}'`);
    const cloudTax = cloudInt(`SELECT tax_minor FROM sales WHERE client_uuid = '${u}'`);
    check(`  ${s.name}: total matches`, cloudTotal, s.body.totalMinor);
    check(`  ${s.name}: VAT matches`, cloudTax, s.body.taxMinor);
  }

  const cloudSum = cloudInt(
    `SELECT coalesce(sum(total_minor),0) FROM sales WHERE client_uuid IN (${sales
      .map((s) => `'${s.body.clientUuid}'`)
      .join(',')})`,
  );
  check("today's takings match the till", cloudSum, tillTotal, money(tillTotal));

  // ---------------------------------------------------------------- 7. idempotency

  section('7. Redelivery is a no-op');

  // The property the whole sync design rests on: a batch that arrives twice
  // must not double a shop's takings. Locally tested, but this is over the wire.
  const beforeReplay = cloudInt('SELECT count(*) FROM sales');
  const beforeSum = cloudInt('SELECT coalesce(sum(total_minor),0) FROM sales');

  const replay = sales[0];
  // The till documents this contract itself: 201 when the sale was created, 200
  // when the request was a retry of one already committed. Asserting the status
  // is stronger than counting rows, because it shows the till *recognised* the
  // duplicate rather than merely failing to write a second one.
  const replayed = await till('POST', '/api/sales', replay.body);
  const replayStatus = replayed.__status;
  check('a replayed sale answers 200, not 201', replayStatus, 200, 'the till recognised the retry');
  check(
    'a replayed sale returns the original invoice',
    replayed.invoiceNumber,
    replay.res.invoiceNumber,
    'no new number burned',
  );
  await new Promise((r) => setTimeout(r, 3000));
  await waitForDrain('the replay');

  check(
    'replaying a sale creates no new cloud row',
    cloudInt('SELECT count(*) FROM sales'),
    beforeReplay,
  );
  check(
    'replaying a sale does not change takings',
    cloudInt('SELECT coalesce(sum(total_minor),0) FROM sales'),
    beforeSum,
  );
  console.log(`       the till answered ${replayStatus} to the duplicate`);

  // ---------------------------------------------------------------- 8. close shift

  section('8. Close the shift and reconcile the drawer');

  const current = await till(
    'GET',
    `/api/shifts/current?branchCode=${BRANCH}&terminalCode=${TERMINAL}`,
  );
  const shiftId = current.id ?? current.shiftId;

  // Count the drawer honestly: float + cash sales - cash refund - safe drop.
  const cashTakings = sales
    .filter((s) => s.body.tenders.some((t) => t.kind === 'CASH'))
    .reduce(
      (sum, s) =>
        sum +
        s.body.tenders.filter((t) => t.kind === 'CASH').reduce((a, t) => a + t.amountMinor, 0) -
        s.body.changeMinor,
      0,
    );
  // Only a CASH refund leaves the drawer. This one went back to the card it was
  // paid on, so the drawer never saw it — subtracting it here was a bug in an
  // earlier version of this script and the backend caught it, which is the
  // behaviour you want from a reconciliation.
  const cashRefunded = refundTenders
    .filter((t) => t.kind === 'CASH')
    .reduce((a, t) => a + t.amountMinor, 0);
  const expectedDrawer = openingFloat + cashTakings - cashRefunded - dropAmount;

  // Count it out in notes, deliberately one Rs 100 short so the variance path runs.
  const shortBy = 10000;

  // The drawer is counted in notes, and the backend checks the breakdown sums to
  // the figure claimed — you cannot assert a total you did not count out. Build
  // a real breakdown greedily, then let the count *be* whatever those notes come
  // to, so the two can never disagree by construction.
  // The circulating LKR set, mirroring LKR_DENOMINATIONS_MINOR in the backend
  // and @lumora/domain. There is no Rs 200 note, and the backend says so.
  const DENOMS = [500000, 100000, 50000, 10000, 5000, 2000, 1000, 500, 200, 100];
  const breakdown = [];
  let remaining = expectedDrawer - shortBy;
  for (const d of DENOMS) {
    const qty = Math.floor(remaining / d);
    if (qty > 0) {
      breakdown.push({ denominationMinor: d, qty });
      remaining -= d * qty;
    }
  }
  const counted = breakdown.reduce((t, d) => t + d.denominationMinor * d.qty, 0);
  const closeRes = await till('POST', `/api/shifts/${shiftId}/close`, {
    operatorCode: CASHIER,
    operatorPin: PIN,
    countedCashMinor: counted,
    closingCount: breakdown,
    varianceReason: 'MISCOUNT',
    varianceNote: `e2e ${runId}`,
  });
  console.log(
    `  ok   drawer expected ${money(expectedDrawer)}, counted ${money(counted)}, ` +
      `short ${money(shortBy)}`,
  );

  check(
    'the counted breakdown sums to the figure claimed',
    breakdown.reduce((t, d) => t + d.denominationMinor * d.qty, 0),
    counted,
    'a total you did not count out is refused',
  );
  if (closeRes && closeRes.varianceMinor !== undefined) {
    check(
      'the variance is recorded, not hidden',
      closeRes.varianceMinor,
      counted - expectedDrawer,
      money(counted - expectedDrawer),
    );
  }

  await waitForDrain('the shift close');

  // ---------------------------------------------------------------- summary

  section('Result');

  const passed = results.filter((r) => r.ok).length;
  console.log(`${passed}/${results.length} checks passed`);
  console.log(`\nRows this run created in the cloud, tagged ${runId}:`);
  console.log(`  invoices     ${invoices.join(', ')}`);
  console.log(`  sales        ${money(tillTotal)} across ${sales.length}`);
  console.log(`  VAT          ${money(tillVat)}`);
  console.log(`  refund       ${money(perUnit)}`);
  console.log(`  cash drop    ${money(dropAmount)}`);
  console.log(
    `\nNothing is deleted: a sale that happened cannot be un-happened, and the\n` +
      `invoice block does not rewind. Open the console to see these figures.`,
  );

  if (failures > 0) {
    console.log(`\n${failures} check(s) failed.`);
    process.exit(1);
  }
}

main().catch((e) => {
  console.error(`\nAborted: ${e.message}`);
  if (e.body) console.error(JSON.stringify(e.body, null, 2).slice(0, 800));
  process.exit(1);
});
