# Lumora POS — Build Roadmap

> **Current position:** **M0 complete — Gate M0 passed 2026-08-12.** The offline sale and sync loop hold end to end. Next: `M1-01` (`@lumora/domain` `Money` type) and the rest of M1.

**Source of truth for design:** [Building Lumora POS — Development Guide](https://claude.ai/code/artifact/c2c24386-6677-440a-a989-cf4d83ff8ff8). This roadmap is the _execution_ document; the guide is the _design_ document. When they conflict, the guide wins on design and this file wins on sequencing. Revise both when decisions change rather than letting the code drift.

|                    |                                                                                                                           |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------- |
| **Stack**          | Spring Boot 3.3 · Postgres 16 · Next.js 14 · Electron · pnpm + Turborepo                                                  |
| **Target**         | v1 — single-till shop                                                                                                     |
| **Hard date**      | IRD invoice format · April 2026                                                                                           |
| **Starting point** | Greenfield. `D:\Lumora\POS System` and `POS System Desktop` are **reference only** — read before re-solving, do not port. |

### Status legend

| State       | Written as                                              |
| ----------- | ------------------------------------------------------- |
| not started | `- [ ] **M1-04** Task title`                            |
| in progress | `- [~] **M1-04** Task title <sub>in progress</sub>`     |
| finished    | `- [x] **M1-04** Task title <sub>done 2026-08-20</sub>` |
| dropped     | `- [-] **M1-04** Task title <sub>dropped — why</sub>`   |

**Marking convention.** Flip `- [ ]` → `- [x]` and append `<sub>done YYYY-MM-DD</sub>`. Task IDs are stable and are never renumbered — new work appends a new ID. A **gate** may only be ticked after its criterion has actually been executed against running software; never infer a gate from its tasks being complete. Every gate tick appends a row to [§G](#g--progress-log) and updates the _Current position_ line at the top of this file.

---

## §A — Ground rules (never violate)

**The principle.** Today: terminal → cloud API → cloud Postgres; the network is on the critical path of a sale. Target: terminal → local Postgres (**the sale is final here**) → outbox → cloud; the network is on the critical path of _nothing_. The incumbent this product replaces is a native desktop POS whose one durable advantage is that it never stops working. Every decision below is a consequence of that inversion.

### The four things v1 must not compromise

Re-read this list at the start of every milestone. Defer anything else, but these are expensive or impossible to retrofit:

- [ ] **Outbox + idempotency keys** — adding transactional sync to direct writes later means touching every write path. _(enforced by M0-05, M0-08)_
- [ ] **Movements, not balances** — a schema decision; changing it later is a data migration plus a rewrite. _(enforced by M0-04, M1-15)_
- [ ] **Per-terminal invoice number blocks** — cheap now, painful once real invoices exist. _(enforced by M1-12)_
- [ ] **Local-first write path** — if v1 writes to the cloud "just for now", v2 is a rewrite. _(enforced by M0-03, M0-05)_

### Standing rules that apply to every task

- Money math lives in **exactly one place**: `@lumora/domain`. No I/O in that package. Both apps import it. If VAT extraction is implemented twice, the console and the receipt will eventually disagree by a rupee and no test will tell you.
- Money is **integer minor units**. Never a float, never a JS `number` for currency arithmetic.
- VAT is **extracted** from inclusive prices, never multiplied onto them: `vat = total × rate ÷ (1 + rate)`. Mode and rate are stamped per sale so historical receipts stay reproducible when the rate changes.
- **Never sync a level or a balance — sync the movements that produce it.** Balance is always `Σ entries`, never a stored column anyone updates. Addition is commutative, so offline nodes reconcile with no conflict logic.
- Every synced aggregate carries a client-generated `client_uuid`. Keep `bigserial` PKs; add a unique UUID column. The cloud upserts on it, so duplicate delivery is a no-op.
- **Touch targets ≥ 56 px** — fingers, fast, sometimes gloved. Not the 44 px web default.
- **Tabular monospace for all currency.** The register vernacular, and the digits align.
- **Semantic colour only:** green = complete/balanced, red = void/danger, amber = offline/pending, brand accent = the primary action and nothing else.
- **Brand blue `#0FA0F3` is identity, never a white-text button** — it measures 2.9:1 on white and fails AA. Light surfaces use `--lum-accent` `#0973AF` (same hue, 5.2:1); the dark terminal uses the brand blue unmodified at 6.2:1. See **D3**.
- **Status colour never carries meaning alone** — always icon _plus_ text label. On light surfaces amber measures 1.79:1, so the label _is_ the accessibility mechanism.

---

## §B — Target structure

```
NEW POS/
  apps/
    terminal/          Next.js + Electron  → localhost:8081   (terminal + back office)
    console/           Next.js PWA         → cloud API        (owner + super-admin)
  packages/
    domain/            money math, VAT extraction, cart totals — pure TS, no I/O
    ui/                design tokens + components
    api-client/        typed client generated from OpenAPI
  services/
    backend/           Spring Boot — one jar, profiles: desktop | cloud
  pnpm-workspace.yaml
  turbo.json
  ROADMAP.md
```

**Two shipped apps. The seam is which backend the app talks to — not which persona uses it.**

| App                 | Contains                                           | Backend   | Ships as       |
| ------------------- | -------------------------------------------------- | --------- | -------------- |
| **Lumora Terminal** | Terminal (cashier PIN) + Back office (manager PIN) | localhost | NSIS installer |
| **Lumora Console**  | Owner console (responsive PWA) + super-admin       | cloud     | Vercel         |

Terminal and back office belong in **one** app because the owner switches between them constantly — an item isn't in the system, so add it and sell it. They also share the offline engine, session, sync status and printing; splitting them duplicates the hardest code. The console is **separate** because every constraint differs: cloud backend, no offline requirement, no install, and a fast release cadence you do not want coupled to a binary that runs tills.

### v1 topology

Everything runs on one shop PC — Electron shell, Next.js renderer, Spring Boot on `localhost:8081` (profile `desktop`), and local Postgres as the source of truth for the sale. The store-node tier of the full design is present; it just happens to share a machine with the terminal. The outbox drains to the cloud asynchronously, resumably, and **never blocks a sale**.

### The sale write path

1. Cashier tenders → terminal POSTs to `localhost:8081` with a client-generated UUID.
2. **One local transaction** — `sales`, `sale_items`, `stock_movements` (negative), and `outbox` (payload + `client_uuid`) commit together, or not at all.
3. **Sale is final.** Receipt prints, drawer opens, next customer. Nothing waited on the network.
4. Sync worker drains the outbox — background `@Scheduled` job batches, POSTs, retries with backoff.
5. Cloud upserts idempotently keyed on `client_uuid`. Retries are always safe.

Steps 1–3 are synchronous and local. Steps 4–5 can happen seconds or days later, and the shop is unaffected either way.

### Who owns which data

| Data                                             | Authored by         | Direction |
| ------------------------------------------------ | ------------------- | --------- |
| Products, prices, categories, barcodes           | Owner (back office) | push up   |
| Stock receiving, adjustments, stocktake          | Owner (back office) | push up   |
| Users, PINs, roles                               | Owner (back office) | push up   |
| Sales, refunds, shifts, cash movements           | Cashier (terminal)  | push up   |
| Customers, loyalty, store credit                 | Either surface      | push up   |
| Plan tier, feature flags, licence, tenant status | **You (vendor)**    | pull down |

**The v1 constraint accepted on purpose:** the phone console is **read-only**. Allowing catalog edits from a phone while the shop PC is offline requires bidirectional merge — the hardest part of the system, deferred to v3. View from anywhere, edit at the shop.

### Core capability coverage

A POS is not a feature contest. These are the eleven capabilities without which it is not a POS; everything else is deferred until they are solid. Each maps to at least one task below — if a task is ever dropped, check this table still holds.

| #   | Capability                                          | Why it's core                              | Tasks                       |
| --- | --------------------------------------------------- | ------------------------------------------ | --------------------------- |
| 1   | Fast sale entry — scan, search, qty, keyboard-first | Speed at the counter is the entire job     | M1-06 → M1-10               |
| 2   | Checkout — multi-tender, split payment, change      | Money math must be flawless                | M1-01 → M1-04, M1-11        |
| 3   | Receipt printing + cash drawer (ESC/POS)            | A sale isn't done without a receipt        | M1-13, M1-14                |
| 4   | **Offline operation**                               | Survival, not a feature                    | all of M0, M3-09            |
| 5   | VAT handling + compliant invoice                    | Legal requirement — see §D                 | M1-02, M1-05, M1-12, M5-09  |
| 6   | Inventory & stock tracking                          | A POS that doesn't know stock isn't retail | M0-04, M1-15, M3-04 → M3-07 |
| 7   | Cash management — shift open/close, blind count     | How owners catch theft and error           | M2-01 → M2-05, M2-11        |
| 8   | Users, roles, PIN login, permission gates           | Accountability                             | M3-08, M3-09                |
| 9   | Returns / refunds / exchanges                       | Daily need; the main fraud vector          | M2-06 → M2-10               |
| 10  | Reporting + remote owner visibility                 | The reason someone buys modern over legacy | M3-10, M4-05 → M4-07        |
| 11  | Basic customer records                              | Foundation for loyalty and credit          | M3-11                       |

---

## §C — Milestones

### M0 · The spike that derisks everything <sub>~1 week</sub>

Before committing to the redesign, prove the offline sale and sync loop end to end. Nothing else matters if this doesn't hold.

- [x] **M0-01** Monorepo skeleton — `pnpm-workspace.yaml`, `turbo.json`, the `apps/` `packages/` `services/` tree from §B, shared TS config, root lint/format <sub>done 2026-08-12</sub>
- [x] **M0-02** Dev Postgres 16 (docker compose for development; bundled binary comes at M5-01) <sub>done 2026-08-12</sub>
- [x] **M0-03** `services/backend` — Spring Boot 3.3 / Java 17, profile `desktop`, bound to `127.0.0.1:8081`, Flyway `V1` baseline <sub>done 2026-08-12</sub>
- [x] **M0-04** Minimal schema: `tenants`, `products`, `sales`, `sale_items`, `stock_movements`, `outbox` — every synced aggregate carries `client_uuid uuid NOT NULL` with a unique index <sub>done 2026-08-12</sub>
- [x] **M0-05** `POST /api/sales` — accepts the client-generated UUID; **one** `@Transactional` writes domain rows _and_ the outbox row. A sale can never exist without its sync record. <sub>done 2026-08-12</sub>
- [x] **M0-06** Electron shell loading the Next.js renderer; single hardcoded product, tender, commit <sub>done 2026-08-12</sub>
- [x] **M0-07** Cloud profile — `POST /api/sync/batch`, idempotent upsert on `client_uuid`, returns accepted IDs <sub>done 2026-08-12</sub>
- [x] **M0-08** `@Scheduled` sync worker — select N pending rows oldest-first per tenant → POST batch → mark `acked_at` → on failure increment `attempts`, record `last_error`, capped exponential backoff (never a tight retry loop) <sub>done 2026-08-12</sub>
- [x] **M0-09** Sync status strip — `ONLINE` / `OFFLINE — sales saving locally` / `↑ N syncing`. The pending count is a trust feature, not a debug stat. <sub>done 2026-08-12</sub>

> **⛔ Gate M0** — Pull the network cable mid-shift. Ring up ten sales. Plug back in. All ten appear in the cloud **exactly once**. Then replay the same batch and assert row counts unchanged.
> _If this fails, stop and fix the design — not the code._
>
> - [x] **GATE-M0** executed and passed <sub>2026-08-12</sub>

---

### M1 · The sale path <sub>~3 weeks</sub>

The terminal screen, for real, against the local backend.

- [ ] **M1-01** `@lumora/domain` — `Money` type in integer minor units; no floats anywhere in the money path
- [ ] **M1-02** VAT extraction — `vat = total × rate ÷ (1 + rate)`; inclusive and exclusive modes
- [ ] **M1-03** Cart totals — line discounts, order discounts, and an explicit LKR rounding policy (decide and document cash rounding)
- [ ] **M1-04** Property-based tests in `@lumora/domain` — totals must reconcile to the cent across **every** path
- [ ] **M1-05** Stamp tax mode + rate per sale so historical receipts stay reproducible when the rate changes
- [ ] **M1-06** Products / barcodes schema + local product search API
- [ ] **M1-07** Terminal layout — fixed appliance shape, dark, no navigation, **no scrolling during a sale**, F-key bar pinned at the bottom so positions become muscle memory
- [ ] **M1-08** The scan field is **always focused** — a barcode gun works with zero clicks
- [ ] **M1-09** Scanner/keyboard coexistence — never bind plain digits; ignore an `Enter` arriving <60 ms after a character (that's a scanner terminator, not the cashier)
- [ ] **M1-10** Cart interactions fully keyboard-driven — arrow navigation, qty edit, line void
- [ ] **M1-11** Tender overlay — multi-tender, split payment, change calculation; **change due is larger than the total itself**
- [ ] **M1-12** Per-terminal invoice numbering — locally issued blocks, `KND-T2-001047` = branch · terminal · local sequence within this terminal's reserved range
- [ ] **M1-13** ESC/POS receipt renderer
- [ ] **M1-14** Electron main-process serial/USB write + drawer kick via IPC — **no QZ Tray** (this removes the unsigned-certificate problem entirely)
- [ ] **M1-15** `SALE` stock movement written inside the sale transaction
- [ ] **M1-16** Playwright keyboard-only spec — completes a full sale and asserts no `click` event is dispatched
- [x] **M1-17** Resolve open decision **D3** (brand palette beyond the terminal's darkened accent) <sub>done 2026-08-13</sub>

> **⛔ Gate M1** — A cashier completes **20 consecutive sales without touching a mouse**.
>
> - [ ] **GATE-M1** executed and passed

---

### M2 · Cash control & returns <sub>~2 weeks</sub>

The accountability layer — what owners actually buy.

- [ ] **M2-01** `shifts` table + open/close lifecycle
- [ ] **M2-02** **Blind** denomination count — the expected total is never shown to the counter
- [ ] **M2-03** Variance calculation and gating, threshold **per-tenant configurable** (a jeweller and a grocer differ; hardcoding LKR 100 is wrong) — resolves **D1**
- [ ] **M2-04** Reason codes required above the variance threshold
- [ ] **M2-05** `cash_movements` — pay-in, pay-out, drops, all recorded as movements
- [ ] **M2-06** Returns — receipt-linked lookup
- [ ] **M2-07** Manager-PIN gate on refunds
- [ ] **M2-08** Per-line return reasons; partial returns
- [ ] **M2-09** Refund locked to the original tender
- [ ] **M2-10** `RETURN` stock movement on refund-with-restock
- [ ] **M2-11** Z-report, printable, local
- [ ] **M2-12** Outbox aggregates for `shift`, `cash_movement`, `refund`

> **⛔ Gate M2** — A refund **cannot** be issued without an original receipt, and **cannot** be paid to a different tender.
>
> - [ ] **GATE-M2** executed and passed

---

### M3 · Back office <sub>~3 weeks</sub>

Everything the owner needs on the shop PC, all working offline.

- [ ] **M3-01** Back-office shell inside the terminal app, manager-PIN gated
- [ ] **M3-02** Products CRUD — prices, categories, multi-barcode
- [ ] **M3-03** CSV import with validation and a dry-run preview
- [ ] **M3-04** Suppliers + goods received → `RECEIVE` movements
- [ ] **M3-05** Stock adjustments → `ADJUST` movements, reason required
- [ ] **M3-06** Stocktake — counted vs system writes the **difference** as a `STOCKTAKE` movement. It never overwrites the level: shrinkage is precisely what the owner needs to see, and overwriting erases it.
- [ ] **M3-07** Stock on hand as `Σ movements`, with an indexed rollup for query speed (never a stored balance column anyone updates)
- [ ] **M3-08** Users, roles, PINs, permission gates
- [ ] **M3-09** Offline auth — cache argon2/bcrypt hashes locally; the local backend issues short-lived JWTs signed with a key provisioned at activation. **Never a cloud round-trip to unlock a till.**
- [ ] **M3-10** Local reports — day sales, Z-history, stock on hand, top products
- [ ] **M3-11** Basic customer records
- [ ] **M3-12** Outbox aggregates for `product`, `movement`, `user`, `customer`

> **⛔ Gate M3** — A shop with **no internet for a week** can operate completely, including adding new products.
>
> - [ ] **GATE-M3** executed and passed

---

### M4 · Cloud & owner console <sub>~2 weeks</sub>

The differentiator against legacy POS.

- [ ] **M4-01** Cloud profile — multi-tenant schema with tenant isolation
- [ ] **M4-02** Per-aggregate ingest endpoints, idempotent upsert on `client_uuid`
- [ ] **M4-03** Partial-batch failure handling — per-row accept/reject in the response
- [ ] **M4-04** Idempotency test — replay the same outbox batch twice, assert cloud row counts unchanged
- [ ] **M4-05** `apps/console` — Next.js PWA, phone-first single column, light/dark, **read-only**
- [ ] **M4-06** Today's sales, trend, branch view
- [ ] **M4-07** Attention feed — cash variance and stock variance are the same pattern and both belong here
- [ ] **M4-08** Super-admin — tenants, plans, licences, feature flags
- [ ] **M4-09** Downward pull of licence / plan / feature flags on the same sync tick (**the only downward flow in v1**)
- [ ] **M4-10** Deploy console to Vercel; host the cloud backend

> **⛔ Gate M4** — The owner sees today's takings on a phone, from another city.
>
> - [ ] **GATE-M4** executed and passed

---

### M5 · Hardening & pilot <sub>~3 weeks</sub>

- [ ] **M5-01** Bundle JRE + Postgres 16 + backend jar + Next.js standalone into the Electron app
- [ ] **M5-02** NSIS single installer
- [ ] **M5-03** First-run wizard — tenant seed, activation, key provisioning, branch/terminal codes, invoice block allocation
- [ ] **M5-04** Scheduled local backup (`pg_dump` to a second location)
- [ ] **M5-05** Restore path, actually tested — a dead disk must not mean lost history
- [ ] **M5-06** **Automatic cloud backup** — resolves **D5**; treated as a v1 requirement because local-first puts a shop's entire history on one disk
- [ ] **M5-07** Crash recovery — kill the process between writes; assert no partial sale and no orphan movement
- [ ] **M5-08** Mid-sale power cut must not corrupt a shift
- [ ] **M5-09** IRD invoice-format fields on the invoice layout (see §D — hard date April 2026)
- [ ] **M5-10** PDPA — per-customer data export and erasure
- [ ] **M5-11** Desktop auto-update channel
- [ ] **M5-12** Pilot — run **one real shop for a month** before selling a second

> **⛔ Gate M5** — 30 days in a live shop with **no data loss and no manual intervention**.
> _Do not sell a second shop before this passes._
>
> - [ ] **GATE-M5** executed and passed

---

## §D — Compliance track

Sri Lanka is mid-transition from self-kept records to government-linked fiscal invoicing. This is a deadline, and also the single biggest commercial opportunity: POS is normally impossible to displace because switching costs more than staying — a mandate that forces a large share of the market to re-tool anyway removes that inertia. Being compliant _early_ means competing for buyers who are already shopping.

These dates are external, so this track runs in parallel with §C rather than as a milestone.

| Requirement                                                 | Timing         | What it means for the build                                                                                                                                  | Lands in |
| ----------------------------------------------------------- | -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------- |
| Updated IRD tax-invoice format                              | **April 2026** | Invoice layout and required fields must match the IRD specification                                                                                          | M5-09    |
| VAT registration threshold falls to Rs. 36M                 | 2026           | Many more small retailers become VAT-registered and must re-tool — market event, no build                                                                    | —        |
| E-invoicing rollout, ending with B2C via POS                | phased         | Transaction data submitted to RAMIS via a Web API. Cloud-side only: the shop queues, the cloud submits, status flows back. One integration, one certificate. | v4       |
| "Secured POS machines" approved by the Commissioner-General | proposed s.64B | Certification will be required to sell to VAT-registered businesses                                                                                          | v4       |
| PDPA No. 9 of 2022                                          | phasing in     | Customer data export and erasure; breach notification; penalties to Rs. 10M per instance                                                                     | M5-10    |

Two things must land before the mandate bites: **IRD invoice-format compliance** and **per-customer data export and erasure**. Neither is large; both are time-boxed by external dates.

---

## §E — Open decisions

Tracked here so none of them silently becomes a default.

| ID     | Decision                    | Recommendation                                                                                                                                                                              | Resolve by | Status        |
| ------ | --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- | ------------- |
| **D1** | Variance threshold          | Per-tenant config — hardcoding LKR 100 is wrong, a jeweller and a grocer differ                                                                                                             | M2         | - [ ] open    |
| **D2** | Store-credit limits offline | Moot at single till. From v2, either accept a bounded overshoot or restrict credit sales to online-only — decide deliberately rather than discovering it.                                   | before v2  | - [ ] open    |
| **D3** | Brand palette contrast      | **Settled 2026-08-13** — StoreX logo blue `#0FA0F3` is identity only (2.9:1 on white). `--lum-accent` is that hue darkened to `#0973AF` (5.2:1); terminal keeps `#0FA0F3` at 6.2:1 on dark. | M1         | - [x] settled |
| **D4** | Monorepo migration          | **Settled** — greenfield monorepo from M0-01, so the money path is never implemented twice                                                                                                  | —          | - [x] settled |
| **D5** | Backup strategy             | Automatic cloud backup is a v1 requirement, not v5                                                                                                                                          | M5         | - [ ] open    |

**Also settled** (from the guide's §10, recorded so they are not relitigated): modern stack re-architected offline-first with local install as the hero deployment · two apps split by backend · movements and ledgers throughout, never stored balances · outbox with `client_uuid` idempotency, push-only in v1 · per-terminal invoice number blocks from day one · ESC/POS via the Electron main process, replacing QZ Tray.

---

## §F — Deferred, and the standing test discipline

### Explicit non-goals for v1

Restated so they stay non-goals:

- LAN multi-terminal (one store node serving several tills) — **v2**
- Cross-branch stock and credit merging — **v3**
- Bidirectional sync and conflict resolution — **v3**
- IRD e-invoicing submission and secured-POS certification — **v4**
- Native mobile apps — the owner console is a responsive PWA

Each is an _addition_ to this architecture, not a change to it.

### Per-release checklist

Run every item before every release, not once per milestone.

| Layer       | What to test                                                                        | How                                                                                       |
| ----------- | ----------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| Money       | VAT extraction, rounding, split tender, change, discounts                           | Property-based tests in `@lumora/domain` — totals reconcile to the cent across every path |
| Offline     | Sale during outage; sync after reconnect; duplicate delivery; partial batch failure | Integration tests that hard-fail the cloud client                                         |
| Idempotency | Replaying the same outbox batch twice                                               | Assert cloud row counts unchanged                                                         |
| Keyboard    | Full sale with zero mouse events                                                    | Playwright, keyboard-only; assert no `click` is dispatched                                |
| Recovery    | Power cut mid-transaction                                                           | Kill the process between writes; assert no partial sale and no orphan movement            |

- [ ] **The manual cable-pull.** Pull the cable during a busy shift and keep selling. Automate what you can, but **do this by hand before every release** — it is the one failure mode that loses customers permanently.

---

## §G — Progress log

Append-only. One row per gate attempt (pass or fail) and per significant course change.

| Date       | Milestone | Gate result | Note                                                                                                                                                              |
| ---------- | --------- | ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-08-12 | —         | —           | Roadmap created from the development guide. Greenfield, pnpm + Turborepo confirmed.                                                                               |
| 2026-08-12 | M0-01     | —           | Monorepo scaffolded. `typecheck`, `lint`, `test`, `build`, `format:check` all green; terminal emits standalone output. Three toolchain deviations recorded below. |
| 2026-08-12 | M0-02     | —           | Two Postgres 16.6 containers up and healthy (`lumora_local` :5442, `lumora_cloud` :5443), TZ `Asia/Colombo`. Moved off 5432/5433 — see the port map below.        |
| 2026-08-12 | M0-02     | —           | Old POS stack stopped and its restart policy set to `no`, freeing :3000 and :8081. All of its volumes left intact.                                                |
| 2026-08-12 | M0-03     | —           | Backend boots on both profiles; Flyway applied `V1`; health UP; desktop profile verified **refused on the LAN IP**. `mvnw clean verify` green, 3 tests.           |
| 2026-08-12 | M0-04     | —           | `V100__minimal_schema.sql` applied to `lumora_local`. 16 tests green, including structural guards on idempotency, movements-not-balances and integer money.       |
| 2026-08-12 | M0-05     | —           | `POST /api/sales` live. 23 tests green. End-to-end over HTTP: 201 → `KND-T2-000001`, identical retry → 200 with no duplicate, bad totals → 422.                   |
| 2026-08-12 | M0-06     | —           | Electron shell hosting the renderer. A sale rung up by clicking the real button: `KND-T1-000001`, 3 × 450.00, VAT 205.93 extracted, `-3` movement, 1 outbox row.  |
| 2026-08-12 | M0-07     | —           | Cloud profile + `POST /api/sync/batch`, idempotent upsert on `client_uuid`, per-item accept/reject. Own schema (`V200`) and its own test database.                |
| 2026-08-12 | M0-08     | —           | `@Scheduled` outbox drain with capped exponential backoff. 9 worker tests, almost all of them failure-path.                                                       |
| 2026-08-12 | M0-09     | —           | Status strip verified live in Electron in all three states: `ONLINE · All sales synced`, `↑ 1 SYNCING`, `OFFLINE — sales saving locally · 1 waiting`.             |
| 2026-08-12 | **M0**    | **PASSED**  | **Gate M0.** 10 sales offline → cloud restored → 11 in cloud, 11 distinct uuids, 0 duplicates, 0 missing. Batch replayed: row counts unchanged. 38 backend tests. |
| 2026-08-13 | —         | —           | Pushed to `github.com/LumoraTechSolution/NewStorex` — `development` first, `main` only after all gates passed. That branch flow is the standing workflow.         |
| 2026-08-13 | M1-17     | —           | Product named **StoreX**, "Powered by Lumora Tech". **D3 settled** on logo blue `#0FA0F3` — see the brand/accent split below.                                     |

### D3 — the brand blue is not the accent

StoreX's logo blue is `#0FA0F3` — hsl(202 90% 51%), **sampled from the artwork rather than
eyeballed**: it is the modal colour over the mark's area at 1,701 pixels, the neighbouring values
being JPEG ringing rather than real ink.

It is a light azure, and lightness is the whole story: it carries **6.5:1 against dark ink and only
2.9:1 against white**. The wordmark already knows this — "Store" is set in near-black, not reversed
out. Reused naively as a button colour it fails AA on every till in the country, and the failure is
invisible to anyone reviewing it on a good monitor.

So `packages/ui/src/tokens.css` splits the two jobs. `--lum-brand` is the logo blue for marks, rules
and identity, paired with `--lum-brand-ink`. `--lum-accent` is the same hue dragged to 36% lightness
— `#0973AF`, 5.2:1 with white — and remains the primary action and nothing else. On
`[data-surface='terminal']` the accent is the unmodified `#0FA0F3`, which reads 6.2:1 against the
dark appliance: the till is the one surface where the brand colour can be itself.

Semantic green/red/amber are untouched. They report state, not identity, and rebranding them would
be the one change that makes a cash-variance screen harder to read.

Assets live in `packages/ui/assets/`. The supplied JPEG is kept as the source but never shipped —
it is brand blue on **opaque white**, so on the dark terminal it renders as a white rectangle. The
cut-out transparent PNGs beside it are what the apps use. There is still **no vector master**,
which caps the installer icon at 512px; worth chasing before M5-01.

### Gate M0 — executed 2026-08-12, passed

The exact criterion, run against the real stack rather than a simulation.

| Step                                               | Result                                                                             |
| -------------------------------------------------- | ---------------------------------------------------------------------------------- |
| Cloud process killed mid-shift                     | `/actuator/health` on :8082 refused                                                |
| Ten sales rung up with no cloud                    | all `201`, receipts issued, till unaffected                                        |
| Terminal strip during the outage                   | `OFFLINE — sales saving locally · 1 waiting`                                       |
| Outbox state                                       | 10 pending, `attempts` up to 4, every row backed off to a future `next_attempt_at` |
| Cloud restarted                                    | backlog drained without intervention                                               |
| Sales in the cloud                                 | **11 total, 11 distinct `client_uuid`, 0 duplicates**                              |
| Local vs cloud                                     | 11 vs 11, **0 local sales missing**                                                |
| Batch replayed against a cloud that already had it | `200`, 3 accepted, **row counts unchanged**                                        |

The eleventh sale is the one from M0-06; ten were rung up during the outage.

**What this proves.** The network is on the critical path of nothing. A sale commits locally and is
final there, the outbox carries it whenever it can, and redelivery is free — which is what lets the
drain retry blindly instead of having to know whether its last attempt got through.

### M0-07 to M0-09 notes

**The cloud is a different schema, not the same one with a tenant column.** `V200` has no outbox (the
cloud is a destination, never a source), no invoice counters (numbers arrive already issued), and
**no foreign keys between synced aggregates** — a sale must not be rejected because the product it
references has not been pushed yet. Peer references are carried as `client_uuid` and resolved at
query time. This forced a second test database: the two tiers share table names but not shapes, so
migrating both into `lumora_test` left whichever Flyway ran last wiping the other's tests.

**Per-item outcomes, not a batch verdict.** One malformed aggregate must not strand the ninety-nine
behind it, so each item commits in its own transaction and the response carries `accepted` and
`rejected` separately. A batch that wholly succeeds or wholly fails turns one bad row into a
permanently stuck till.

**A rejected row is kept, never dropped.** It stays unacked on the longest backoff with the reason
recorded. A proper dead-letter path is a later concern; silently losing a sale is not acceptable at
any milestone.

**Two bugs worth remembering.** `@Transactional` on a method called from inside the same class does
nothing — Spring works through a proxy the call never crosses — so the per-item transaction is an
explicit `TransactionTemplate`. And `@Scheduled` parses its own duration strings and only understands
ISO-8601, so `15s` threw at startup where `PT15S` works; the `@ConfigurationProperties` bindings
accept both, which is exactly why the inconsistency was easy to miss.

**`online` means the last attempt succeeded**, not that a cable is plugged in — and before the first
attempt it is false, which is the honest answer. It stays stale for up to one tick after an outage
begins; the pending count, which is the number a shopkeeper actually reads, is correct immediately.

### M0-06 notes

**Verified by driving the real window, not by inference.** Electron was launched with
`--remote-debugging-port` and the page evaluated over the DevTools protocol: `window.lumora.shell`
returned `{isDesktop:true, platform:"win32", electron:"33.4.11"}` (the preload bridge is live), the
four seeded products rendered, and clicking _Tender cash_ produced `Sale committed — invoice
KND-T1-000001 for 1,350.00`, matching the database exactly.

**Same-origin instead of CORS.** The renderer calls `/api/*` on its own origin and Next rewrites
forward to `127.0.0.1:8081`. A process that is the source of truth for a shop's money should not also
be growing a cross-origin surface.

**Electron main is CommonJS, not TypeScript.** There is no logic in it yet worth a build step. M1-14
puts real work there (ESC/POS bytes over serial/USB, the drawer kick) and that is the moment to add
compilation. The security defaults are set now though: `contextIsolation` on, `nodeIntegration` off,
`sandbox` on, a single-instance lock (two processes must not issue numbers from one terminal block),
and any window-open request handed to the OS browser so the till cannot navigate away from itself.

**`@lumora/domain` earned its first real code.** The renderer needed a total and a VAT figure, and
putting that arithmetic in the component would have broken the one rule the architecture rests on.
`money.ts` now holds `lineTotalMinor`, `extractVatMinor`, `addVatMinor`, `taxForMinor` and
`formatMinor` — integer-only, 12 tests. **M1-01 to M1-04 remain open**: the `Money` type proper,
cart-wide modes, discounts and the LKR rounding policy, and property-based tests.

### The single-tenant invariant is now enforced

Seeding the dev database produced a second tenant whose branch was also coded `KND`, and
`resolveBranch` — which looked up by code alone — became ambiguous. That was the weakness flagged at
M0-05, surfacing within the hour.

Fixed rather than patched: a desktop database holds **exactly one tenant** by definition, so the
tenant is resolved first and the branch lookup scoped to it. If a second ever appears, commits fail
loudly with instructions instead of silently attributing a shop's takings to the wrong owner.
`aSecondTenantOnAShopPcIsRefusedRatherThanGuessedAt` covers it, and
`SaleService.resolveSoleTenantId` is the seam where M4-01 swaps in the authenticated tenant context.

Dev data now comes from `services/backend/dev-seed.sql` via `pnpm db:seed` — idempotent, and
deliberately **not** a Flyway migration, because migrations run on every till while a real shop's
first tenant is created by the installer's first-run wizard (M5-03).

### The backend records the money, it does not recompute it

`CreateSaleRequest` carries the already-computed totals. That is deliberate: VAT extraction and
rounding live in exactly one place (`@lumora/domain`), and a Java reimplementation here would be
precisely the second implementation the architecture exists to prevent — the one that eventually
disagrees with the printed receipt by a rupee. What the customer was charged is what the receipt
says, and this endpoint records it.

`SaleService.assertTotalsAreSelfConsistent` is a **checksum, not a second opinion**: line totals must
sum to the subtotal, subtotal less discount must equal the total, tax must not exceed the total. It
catches malformed or mis-serialised requests without ever holding an opinion about rounding.

Verified end to end against the dev database — one transaction produced 1 sale, 1 line, 1
`SALE` movement of `-2`, and 1 unacked outbox row whose payload carries everything the cloud needs,
with `taxRateBp` stamped so a reprint after a rate change still reproduces the sale.

### What M0-05 proved, and what it did not

- **Atomicity is tested, not assumed.** `aFailedOutboxWriteTakesTheWholeSaleWithIt` plants a trigger
  that makes the outbox insert raise, then asserts no sale and no orphan movement survive. The test
  is deliberately not `@Transactional` — a test-managed transaction would mask the behaviour.
- **Retry safety works at the local hop too.** Resending the same `client_uuid` returns the original
  sale (HTTP 200, `alreadyExisted: true`) and enqueues nothing further. Idempotency was always going
  to be needed cloud-side; it turns out the till needs it as well, for the response it never saw.
- **Invoice numbers are per-terminal already.** `V101__invoice_counters.sql` allocates from a
  per-`(branch, terminal)` counter in a single atomic `UPDATE … RETURNING`, inside the sale's
  transaction, so a rolled-back sale does not burn a number. M1-12 upgrades this to reserved
  _blocks_; the format and the offline property are already right.
- **Still open:** `SaleService.resolveBranch` looks a branch up by code alone. Correct while a shop
  PC holds one tenant, wrong the moment anything here is multi-tenant. It needs tenant scoping
  before M4-01.

### Two non-negotiables are now enforced by tests, not prose

`MinimalSchemaTest` asserts properties of the schema rather than behaviour of a feature, because
these failure modes get introduced innocently — someone adds a `quantity_on_hand` column to speed up
a query, and offline reconciliation quietly stops being conflict-free.

- Every aggregate root (`tenants`, `branches`, `products`, `sales`, `stock_movements`) has a
  `NOT NULL` `client_uuid` with a unique index, and redelivering one is rejected.
- No table anywhere carries a stored stock level or balance.
- No `*_minor` money column is anything but `bigint`/`integer`.
- `ix_outbox_pending` is partial on `acked_at IS NULL`, so the drain scans the backlog rather than
  the shop's whole history.

Both detectors were verified against planted violations (a table with `quantity_on_hand`,
`credit_balance` and a `double precision` `price_minor`) — they catch all three. A guard that cannot
fail is worth nothing.

`sale_items` deliberately has **no** `client_uuid`: it is not an aggregate root, it syncs inside the
sale's payload, and giving it an idempotency key would imply it can arrive on its own. It cannot.

### Testing runs on a compose database, not Testcontainers

Docker Desktop 29.5 on this machine answers docker-java's `/info` probe with an empty HTTP **400**
on every named pipe (`docker_engine`, `dockerDesktopLinuxEngine`, `docker_cli`) and at every API
version tried, so Testcontainers concludes there is no Docker environment at all. Rather than fight
it, integration tests point at a disposable **`db-test`** compose container (`lumora_test`, :5444,
tmpfs-backed).

The requirement was never Testcontainers — it was **real Postgres, never H2**, because correctness
here rests on `ON CONFLICT` upserts keyed on `client_uuid`, partial indexes on the outbox, and
`timestamptz`. That requirement still holds. Every run cleans and re-migrates, so the migration path
is itself under test, and `CleanDatabaseBeforeTests` refuses to clean any database not named
`lumora_test` — verified by pointing it at `lumora_local` and watching it refuse. Worth revisiting
if Docker Desktop later behaves.

### Flyway migrations are split three ways

`common/` (`V1`–`V99`, both tiers), `desktop/` (`V100`+), `cloud/` (`V200`+); each profile composes
`common` with its own. The desktop database owns an outbox and a single tenant, the cloud owns
tenant isolation and no outbox — they were never going to be the same schema, and disjoint version
ranges stop one number meaning two different migrations. Reserve the next number before writing the
file. See `services/backend/src/main/resources/db/migration/README.md`.

### Port map — this machine

| Port   | Owner                             | Note                                    |
| ------ | --------------------------------- | --------------------------------------- |
| `3000` | new terminal                      | freed from the old stack                |
| `3001` | new console                       |                                         |
| `8081` | new backend, `desktop` profile    | design constant, §B                     |
| `5432` | a native Windows Postgres service | **not Docker, not ours — leave alone**  |
| `5442` | new `db-local`                    | moved off 5432 because of the row above |
| `5443` | new `db-cloud`                    |                                         |

The previous POS stack (`D:\Lumora\POS System`) held 3000/8081/5432 under Docker and auto-started
with Docker Desktop. On 2026-08-12 its three containers were stopped and their restart policy set
to `no`. **All volumes are intact** (`lumora_pos_postgres_data`, `pos-backend_postgres_data`) — it
returns with `docker start lumora-pos-db lumora-pos-backend lumora-pos-frontend`, though doing so
collides with this project again.

### Toolchain deviations worth knowing

- **`node-linker=hoisted`** in `.npmrc`. Next's `output: 'standalone'` mirrors the dependency tree with symlinks, which fails `EPERM` on Windows unless Developer Mode is on — and electron-builder cannot pack a symlinked pnpm store either (M5-01). We give up pnpm's phantom-dependency strictness rather than make an OS setting a build prerequisite.
- **`eslint-config-next` runs a major ahead of `next`** — 15.x against `next@14.2.21`. The 14.x plugin calls `context.getAncestors()`, which ESLint 9 removed. The plugin lints code patterns, not the Next runtime, so the skew is safe. Revisit if the terminal moves to Next 15.
- **pnpm installed globally** (`npm i -g pnpm@9.15.4`) because `corepack enable` needs admin when Node lives in `Program Files`. The version is pinned in `packageManager` regardless, so corepack users get the same one.
