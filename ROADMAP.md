# Lumora POS — Build Roadmap

> **Current position:** **M4 has one task left, and it is a deployment rather than a decision.** `M4-09` gave the cloud its first and only downward flow: licence, plan and feature flags, pulled on the sync tick. The obvious build — a field on the batch response — is wrong, because `V209` makes a lapsed licence stop ingest, so a lapsed shop's push is a 401 and **the one shop needing the renewal notice is the only one that could never receive it**. It is a `GET` of its own on an exact-path allowlist that authenticates a till whose licence has run out. `V119` caches the answer under three rules that keep it off the critical path: **never asked means everything allowed**, **a lapse withdraws nothing**, **the cache never expires** — so the flags are a product boundary, not the commercial lever; ingest is the lever and always was. `M4-11` then gave the console a **three-state** theme control (Auto is the default and a two-way switch would have removed it), with a generated before-paint script so the storage key cannot drift — and turned up a seam nobody had looked at: the `theme-color` metas and the manifest had never matched `--lum-page` in either theme. Both verified against running software, not only tests. `M4-10` is now half-done in the way that matters: the cloud has a **deployment artifact** — one Docker image plus an environment contract, verified against the running container — and the recommendation is Neon + Render + Vercel, chosen on the unglamorous ground that Render's free Postgres is deleted after 30 days and a pilot shop's sales cannot live somewhere with an expiry date. Because a client may require AWS or Azure, the port is a change of **values, not code**: the image, the Java, all eleven cloud migrations and `application-cloud.yml` are identical on ECS or ACA. The trap it exists to prevent is `spring.profiles.default: desktop` — an unset `SPRING_PROFILES_ACTIVE` runs the _desktop_ migrations in the cloud and reports only `Connection refused` on port 5442, which reads as a database problem and never mentions profiles. **Next: provision the hosting** — Neon, Render, Vercel, the keep-warm, the platform-admin bootstrap and the pilot till's token, none of which is code, after which Gate M4 can be attempted. **415 backend tests**, 170 domain, 84 terminal, **15 console** (was 6), and 38 Playwright specs driving the real Electron window. Gates M1, M2 and M3 all remain outstanding and every one needs a person — **Gate M3 is still the only thing between this and a finished milestone.**

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
- **Status colour never carries meaning alone** — always icon _plus_ text label. The label is a mechanism, not an excuse: semantic tokens are dark enough to be text on their own page in both themes, verified in the running window (see **D6**).
- **The till is dark by default and light by explicit choice** — never by OS preference. See **D6**.

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

- [x] **M1-01** `@lumora/domain` — `Money` type in integer minor units; no floats anywhere in the money path <sub>done 2026-08-13</sub>
- [x] **M1-02** VAT extraction — `vat = total × rate ÷ (1 + rate)`; inclusive and exclusive modes <sub>done 2026-08-13</sub>
- [x] **M1-03** Cart totals — line discounts, order discounts, and an explicit LKR rounding policy (decide and document cash rounding) <sub>done 2026-08-13 — policy signed off</sub>
- [x] **M1-04** Property-based tests in `@lumora/domain` — totals must reconcile to the cent across **every** path <sub>done 2026-08-13</sub>
- [x] **M1-05** Stamp tax mode + rate per sale so historical receipts stay reproducible when the rate changes <sub>done 2026-08-13</sub>
- [x] **M1-06** Products / barcodes schema + local product search API <sub>done 2026-08-13</sub>
- [x] **M1-07** Terminal layout — fixed appliance shape, dark, no navigation, **no scrolling during a sale**, F-key bar pinned at the bottom so positions become muscle memory <sub>done 2026-08-13</sub>
- [x] **M1-08** The scan field is **always focused** — a barcode gun works with zero clicks <sub>done 2026-08-13</sub>
- [x] **M1-09** Scanner/keyboard coexistence — never bind plain digits; ignore an `Enter` arriving <60 ms after a character (that's a scanner terminator, not the cashier) <sub>done 2026-08-13</sub>
- [x] **M1-10** Cart interactions fully keyboard-driven — arrow navigation, qty edit, line void <sub>done 2026-08-13</sub>
- [x] **M1-11** Tender overlay — multi-tender, split payment, change calculation; **change due is larger than the total itself** <sub>done 2026-08-17 — see §G, keyboard-only Electron pass still outstanding</sub>
- [x] **M1-12** Per-terminal invoice numbering — locally issued blocks, `KND-T2-001047` = branch · terminal · local sequence within this terminal's reserved range <sub>done 2026-08-17 — see §G</sub>
- [x] **M1-13** ESC/POS receipt renderer <sub>done 2026-08-17 — see §G</sub>
- [x] **M1-14** Electron main-process serial/USB write + drawer kick via IPC — **no QZ Tray** (this removes the unsigned-certificate problem entirely) <sub>done 2026-08-17 — TCP transport verified live; serial toolchain confirmed (loads under Electron, real COM ports enumerated) but no physical printer to write to, see §G</sub>
- [x] **M1-15** `SALE` stock movement written inside the sale transaction <sub>done 2026-08-18 — the local write already existed; what was missing was the movement ever reaching the cloud, see §G</sub>
- [x] **M1-16** Playwright keyboard-only spec — completes a full sale and asserts no `click` event is dispatched <sub>done 2026-08-20 — 6 specs against the real Electron window, not a Chromium page; `pnpm --filter @lumora/terminal test:e2e`; see §G</sub>
- [x] **M1-17** Resolve open decision **D3** (brand palette beyond the terminal's darkened accent) <sub>done 2026-08-13</sub>
- [x] **M1-18** **Per-line tax rates.** `cartTotals` took one `TaxStamp` for the whole cart, and `sales` stored one `tax_mode`/`tax_rate_bp`. A cart mixing an 18% line with an exempt one would have priced the exempt line at 18%. The till **refused** such a cart rather than selling it quietly wrong; lifting it needed a per-line stamp in the domain and a schema change. Found building M1-07. <sub>done 2026-08-20 — `V106`/`V202`, and the sale-tax checksum tightened to `Σ line.taxMinor`; see §G</sub>

> **⛔ Gate M1** — A cashier completes **20 consecutive sales without touching a mouse**.
>
> - [ ] **GATE-M1** executed and passed

---

### M2 · Cash control & returns <sub>~2 weeks</sub>

The accountability layer — what owners actually buy.

- [x] **M2-01** `shifts` table + open/close lifecycle <sub>done 2026-08-20</sub> — and the till now **refuses to sell without one**; see §G
- [x] **M2-02** **Blind** denomination count — the expected total is never shown to the counter <sub>done 2026-08-20</sub> — enforced by the endpoint having no such field, not by the screen
- [x] **M2-03** Variance calculation and gating, threshold **per-tenant configurable** (a jeweller and a grocer differ; hardcoding LKR 100 is wrong) — resolves **D1** <sub>done 2026-08-20</sub> — `tenant_settings`, LKR 100.00 default
- [x] **M2-04** Reason codes required above the variance threshold <sub>done 2026-08-20</sub> — compared on the _absolute_ variance, so an over drawer is gated too
- [x] **M2-05** `cash_movements` — pay-in, pay-out, drops, all recorded as movements <sub>done 2026-08-20</sub> — signed in the column, with a CHECK tying the sign to the kind
- [x] **M2-06** Returns — receipt-linked lookup <sub>done 2026-08-20</sub> — the only way into a refund; credit notes get their own number block
- [x] **M2-07** Manager-PIN gate on refunds <sub>done 2026-08-20</sub> — BCrypt; an **unset** PIN refuses every refund
- [x] **M2-08** Per-line return reasons; partial returns <sub>done 2026-08-20</sub> — cumulative apportionment, so partials sum back to the whole exactly
- [x] **M2-09** Refund locked to the original tender <sub>done 2026-08-20</sub> — enforced in the domain _and_ re-derived in `RefundService`
- [x] **M2-10** `RETURN` stock movement on refund-with-restock <sub>done 2026-08-20</sub> — per-line flag; damaged goods write no movement
- [x] **M2-11** Z-report, printable, local <sub>done 2026-08-20</sub> — closed shifts only, and it shows the whole derivation
- [x] **M2-12** Outbox aggregates for `shift`, `cash_movement`, `refund` <sub>done 2026-08-20</sub> — `V203`; the shift is the first aggregate that is **not** immutable

> **⛔ Gate M2** — A refund **cannot** be issued without an original receipt, and **cannot** be paid to a different tender.
>
> - [ ] **GATE-M2** executed and passed

---

### M3 · Back office <sub>~3 weeks</sub>

Everything the owner needs on the shop PC, all working offline.

- [x] **M3-01** Back-office shell inside the terminal app, manager-PIN gated <sub>done 2026-08-21</sub> — its own route behind `Ctrl+B`, deliberately not an F-key; see §G
- [x] **M3-02** Products CRUD — prices, categories, multi-barcode <sub>done 2026-08-22</sub> — `V110`; categories are a table, barcodes are set as a list, nothing is ever deleted
- [x] **M3-03** CSV import with validation and a dry-run preview <sub>done 2026-08-22</sub> — the file is read in `@lumora/domain`, the plan is decided on the server, and a plan can only be applied as it was shown
- [x] **M3-04** Suppliers + goods received → `RECEIVE` movements <sub>done 2026-08-22</sub> — `V111`; a receipt is a document, cost is not price, and no level is ever incremented
- [x] **M3-05** Stock adjustments → `ADJUST` movements, reason required <sub>done 2026-08-22</sub> — `V112`; the sign comes from the reason, and the database refuses an ADJUST that names none
- [x] **M3-06** Stocktake — counted vs system writes the **difference** as a `STOCKTAKE` movement. It never overwrites the level: shrinkage is precisely what the owner needs to see, and overwriting erases it. <sub>done 2026-08-22</sub> — `V113`; and the difference turns out to be the arithmetically correct answer too, not only the honest one — see §G
- [x] **M3-07** Stock on hand as `Σ movements`, with an indexed rollup for query speed (never a stored balance column anyone updates) <sub>done 2026-08-22</sub> — `V114`; the rollup is a plain view plus a covering index, because anything with storage is a figure a writer can forget
- [x] **M3-08** Users, roles, PINs, permission gates <sub>done 2026-08-21</sub> — `V109`; roles are an enum, not a table, and the `M2-07` shop-wide PIN is gone rather than deprecated
- [x] **M3-09** Offline auth — cache argon2/bcrypt hashes locally; the local backend issues short-lived JWTs signed with a key provisioned at activation. **Never a cloud round-trip to unlock a till.** <sub>done 2026-08-23</sub> — `V115`; the key is a row this machine generates, and the token deliberately carries no permissions — see §G
- [x] **M3-10** Local reports — day sales, Z-history, stock on hand, top products <sub>done 2026-08-23</sub> — no migration; four tabs, and the stock one points at M3-07's screen rather than drawing the figure twice
- [x] **M3-11** Basic customer records <sub>done 2026-08-23</sub> — `V116`; the phone number is the record, F6 adds one mid-sale, and attaching somebody changes none of the money
- [x] **M3-12** Outbox aggregates for `product`, `movement`, `user`, `customer` <sub>done 2026-08-23</sub> — `V204` (cloud); `movement` turned out already covered and adding it would have doubled a shop's stock — see §G
- [x] **M3-13** PIN attempt lockout. <sub>done 2026-08-23</sub> — `V117`; built as an escalating **cool-off that ends by itself**, never a lock somebody has to be released from — a lock is a denial of service any passer-by can trigger against the owner's own code. Original note: BCrypt at cost 10 is the only thing currently rating a guess, which puts a four-digit PIN about a quarter of an hour from exhausted. Belongs with `M3-09`, where a session gives the count somewhere to live that survives a restart. Raised by `M3-08`, and recorded rather than hidden — see `UserService`.

> **⛔ Gate M3** — A shop with **no internet for a week** can operate completely, including adding new products.
>
> - [ ] **GATE-M3** executed and passed

---

### M4 · Cloud & owner console <sub>~2 weeks</sub>

The differentiator against legacy POS.

- [x] **M4-01** Cloud profile — multi-tenant schema with tenant isolation <sub>done 2026-08-24</sub> — `V205` + `V206`; the schema half was already there and isolating nothing, because the caller picked the tenant — see §G
- [x] **M4-02** Per-aggregate ingest endpoints, idempotent upsert on `client_uuid` <sub>done 2026-08-24</sub> — landed incrementally across M0/M2/M3 as **one** endpoint dispatching per aggregate, not one endpoint each; upsert is now on `(tenant_id, client_uuid)`
- [x] **M4-03** Partial-batch failure handling — per-row accept/reject in the response <sub>done 2026-08-24</sub> — `SyncBatchResult`, shipped with M0-07 and per-item transactional since
- [x] **M4-04** Idempotency test — replay the same outbox batch twice, assert cloud row counts unchanged <sub>done 2026-08-24</sub> — asserted for sales, movements, shifts, refunds and now across two tenants sharing a uuid
- [x] **M4-05** `apps/console` — Next.js PWA, phone-first single column, light/dark, **read-only** <sub>done 2026-08-25</sub> — `V208`; owner login is its own credential type and no till can create or revoke one. Most of the work was the cloud's **read** side, which did not exist at all — see §G
- [x] **M4-06** Today's sales, trend, branch view <sub>done 2026-08-25</sub> — the sync time sits beside the money, because a cloud reading an outbox is only as fresh as the last drain
- [x] **M4-07** Attention feed — cash variance and stock variance are the same pattern and both belong here <sub>done 2026-08-25</sub> — **cash half only**; the stock half joins it when the console has a stock screen to link to
- [x] **M4-08** Super-admin — tenants, plans, licences, feature flags <sub>done 2026-08-25</sub> — `V209`; a **third** credential kind that deliberately carries no tenant, and a lapsed licence that stops the till without locking the owner out — see §G
- [x] **M4-09** Downward pull of licence / plan / feature flags on the same sync tick (**the only downward flow in v1**) <sub>done 2026-08-28</sub> — `V119`; a **GET of its own**, not a field on the batch response, because a lapsed shop's push is a 401 and would never carry the news of its own lapse. The till caches the answer and the cache **never expires** — see §G
- [~] **M4-10** Deploy console to Vercel; host the cloud backend <sub>in progress</sub> — the **artifact** is built and verified (`Dockerfile`, `.dockerignore`, `DEPLOYMENT.md`): one image, an environment contract that ports to AWS/Azure unchanged, and a profile that must be **stated** — unset `SPRING_PROFILES_ACTIVE` boots the cloud host as a shop PC and says only `Connection refused` on port 5442. Neon over Render Postgres because the latter is **deleted after 30 days** and pilot shops' real sales go here. What remains is not code: provision Neon/Render/Vercel, the keep-warm, the admin bootstrap and the till token — see `services/backend/DEPLOYMENT.md` and §G
- [x] **M4-12** Console responsive at three sizes — phone, tablet, desktop <sub>done 2026-08-25</sub> — the nav becomes a sidebar at `lg` and the estate becomes master–detail; CSS breakpoints only, nothing measures the window — see §G
- [x] **M4-11** Console theme **toggle** <sub>done 2026-08-28</sub> — **three** states, not two: "follow this device" is the console's default and reducing it to a switch would have taken it away. The before-paint script is generated from the same module the toggle writes with, so the key cannot drift. Fixed the `theme-color` metas and the manifest, which had never matched `--lum-page` in either theme — see §G. Original note: — the palette already follows the OS and already honours a `data-theme` override (done 2026-08-13); this is the user-facing control. Needs a persisted choice and a blocking inline script that stamps the attribute **before first paint**, or a viewer whose OS is light and choice is dark gets a white flash on every load. Belongs with the real console shell, not the scaffold page.

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
- [x] **M5-09** IRD invoice-format fields on the invoice layout <sub>done 2026-08-24</sub> — `V118` + `V207`; read from **Gazette 2481/22** itself, which overturned three things §D had from secondary reporting. The tax invoice is a separate document issued **on request**, not the till receipt — §4.2 forbids exempt supplies on one and a grocery basket mixes them. See §G
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

| Requirement                                                 | Timing                             | What it means for the build                                                                                                                                                       | Lands in |
| ----------------------------------------------------------- | ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| Updated IRD tax-invoice format                              | **1 July 2026 - already in force** | Gazette **2481/22** (27 Mar 2026) replaced the Nov 2025 spec and moved the date from 1 April to 1 July 2026. Both are now past. Invoice layout and required fields must match it. | M5-09    |
| VAT registration threshold falls to Rs. 36M                 | 2026                               | Many more small retailers become VAT-registered and must re-tool — market event, no build                                                                                         | —        |
| E-invoicing rollout, ending with B2C via POS                | phased                             | Transaction data submitted to RAMIS via a Web API. Cloud-side only: the shop queues, the cloud submits, status flows back. One integration, one certificate.                      | v4       |
| "Secured POS machines" approved by the Commissioner-General | proposed s.64B                     | Certification will be required to sell to VAT-registered businesses                                                                                                               | v4       |
| PDPA No. 9 of 2022                                          | phasing in                         | Customer data export and erasure; breach notification; penalties to Rs. 10M per instance                                                                                          | M5-10    |

Two things must land before the mandate bites: **IRD invoice-format compliance** and **per-customer data export and erasure**. Neither is large; both are time-boxed by external dates.

> **Read at last, on 2026-08-24 — and it changed the plan.** The gazette text and IRD Circular SEC/2026/E/03 are now the basis for `M5-09` rather than reporting about them, and they corrected three things this table had wrong. Purchaser TIN/name/address are required only **where the purchaser is VAT-registered** (Circular §4.3), so a walk-in needs none — the opposite of what a strict reading of Gazette §3.1 suggests, and the difference between a till that can serve a queue and one that cannot. The serial format is `YYMMM-QQQQ-XXXXX`, and `QQQQ` is a free branch/unit identifier, which is what lets §A's per-terminal blocks survive intact. Dates are **MM/DD/YYYY**, month-first, in both clauses. And §4.2 forbids exempt supplies on a tax invoice at all, which is why the document is issued on request instead of being the receipt. The paragraph below is kept for the record of what was believed before.
>
> **The invoice-format date has passed.** Checked 2026-08-20 while shaping M1-18's receipt block: the April 2026 deadline this table carried came from Gazette 2463/05 (17 Nov 2025), which Gazette **2481/22** (27 Mar 2026) rescinded, moving the mandate to **1 July 2026** and changing the specification itself. M5-09 is therefore no longer work against a future deadline — it is late, and it should be re-read against 2481/22 rather than against whatever was known when this row was written. M1-18 put the per-rate net/VAT/gross separation the format requires onto the receipt already (§G), but nobody has checked the rest of the field list — TIN, the serial-number format, the invoice-date/supply-date split — against the actual gazette text. Sources are secondary reporting, not the gazette; **read the gazette before treating any of this as settled.**

---

## §E — Open decisions

Tracked here so none of them silently becomes a default.

| ID     | Decision                    | Recommendation                                                                                                                                                                                                                               | Resolve by | Status        |
| ------ | --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- | ------------- |
| **D1** | Variance threshold          | **Settled 2026-08-20** — per-tenant, in `tenant_settings.cash_variance_threshold_minor`, defaulting to LKR 100.00. Compared against the **absolute** variance: a drawer over is usually a sale nobody rang up.                               | M2         | - [x] settled |
| **D2** | Store-credit limits offline | Moot at single till. From v2, either accept a bounded overshoot or restrict credit sales to online-only — decide deliberately rather than discovering it.                                                                                    | before v2  | - [ ] open    |
| **D3** | Brand palette contrast      | **Settled 2026-08-13** — StoreX logo blue `#0FA0F3` is identity only (2.9:1 on white). `--lum-accent` is that hue darkened to `#0973AF` (5.2:1); terminal keeps `#0FA0F3` at 6.2:1 on dark.                                                  | M1         | - [x] settled |
| **D4** | Monorepo migration          | **Settled** — greenfield monorepo from M0-01, so the money path is never implemented twice                                                                                                                                                   | —          | - [x] settled |
| **D6** | Light mode on the till      | **Settled 2026-08-13 — the till gets light mode.** An explicit, persisted, per-machine choice that defaults to dark; deliberately _not_ an OS preference. Adding it exposed four AA failures in the light palette, all now fixed. See below. | M1         | - [x] settled |
| **D5** | Backup strategy             | Automatic cloud backup is a v1 requirement, not v5                                                                                                                                                                                           | M5         | - [ ] open    |

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

| Date       | Milestone | Gate result | Note                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ---------- | --------- | ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-08-28 | M4-10     | —           | The cloud gets a deployment artifact: `services/backend/Dockerfile`, `.dockerignore` and `DEPLOYMENT.md`. The shape of it is a **Docker image plus an environment contract** rather than an account on a host, because a client may require AWS or Azure and that must be a change of values, not of code — the image, the Java, all eleven cloud migrations and `application-cloud.yml` are identical on ECS, ACA or a client VM, and the only genuine exception is TLS (`verify-full` + provider CA where SCRAM channel binding is unavailable). **`SPRING_PROFILES_ACTIVE=cloud` is the trap**, and it was verified by running the image without it: `spring.profiles.default: desktop` means the container runs the _desktop_ migrations against `127.0.0.1:5442` and reports `Connection refused` — an error that reads as a database problem and never mentions profiles. Seeing port 5442 in a cloud log means this variable is missing. **Neon over Render Postgres and Supabase** on lifetime alone: Render's free database is deleted after 30 days and Supabase's pauses after 7 idle days, and a shop closed for a long weekend must not come back to a paused database holding its sales. Hikari's 20 became `${CLOUD_DB_POOL_SIZE:20}` deployed at 5 — an env knob rather than a lowered default, because the ceiling belongs to the environment and the desktop profile has its own 8 it never reads. Neon's **pooler is refused**: PgBouncer transaction mode breaks Hibernate's server-side prepared statements _and_ Flyway's session-scoped `pg_advisory_lock`. The provider URI is split into three variables by hand at deploy time and there is deliberately no parser — that would be code that only ever runs in production, and the three-variable form is what Secrets Manager and Key Vault hand you anyway. Two seams found while building: **`mvnw` is CRLF in the repo**, so Linux reads the shebang as `/bin/sh\r` and reports `./mvnw: not found` about a file plainly present — stripped in the build stage rather than converting the file, which a Windows developer runs daily; and `-Djarmode=layertools` is deprecated in Boot 3.3 (`-Djarmode=tools extract --layers --launcher`). Scale-to-zero is **safe for the till and not for the console**: an idle outbox opens no connection so the host sleeps, and a cold start costs a sale at most ~5 minutes via the backoff with no loss, but an owner meeting a 40-second login concludes the app is broken — hence a keep-warm on `GET /actuator/health`, which is precisely why that path was left outside the auth filter's `/api/*`. The two URLs reference each other (`NEXT_PUBLIC_API_BASE_URL` is inlined at console **build**, `LUMORA_CONSOLE_ORIGINS` read at backend **boot**), resolved by noting both hosts assign a URL at project creation — so the order is create-both, then deploy-both, with no throwaway deploy. Verified against the running image: profile `cloud`, Flyway `V1`+`V200`–`V210` (no `V100`+), pool 5 not 20, `/api/*` 401 with `/actuator/health` 200, and CORS echoing the exact origin at 200 while `evil.example` gets 403 with no allow-origin header. Preview deployments will be CORS-blocked, accepted deliberately. `TZ` is deliberately unset — `ConsoleReportController` pins `Asia/Colombo`, so UTC is correct. **Hosting is not yet provisioned**: Neon/Render/Vercel accounts, the keep-warm, the platform-admin bootstrap and the till token are the remaining human steps, and Gate M4 is attemptable only after them |
| 2026-08-28 | M4-11     | —           | Console theme toggle. **Three** states — Auto, Light, Dark — because "follow this device" is what the console ships on and a two-way switch would have silently removed it. A blocking script as the first child of `<body>` stamps `data-theme` before first paint; it is **generated** from `lib/theme.ts` rather than typed into the layout, so the storage key has one definition. Found while doing it: the `theme-color` metas said `#FFFFFF`/`#04121C` and the manifest `#04121C`, while the page rendered `#f5f7f9`/`#0a0e12` — the chrome of an installed PWA had never matched the page in either theme. Verified in real Chromium (Electron's), measured in the first animation frame: all four theme/OS combinations correct, and no flash.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| 2026-08-28 | M4-09     | —           | Licence, plan and feature flags now flow **down** on the sync tick — the only downward flow in v1. `V119` caches the cloud's answer on the till. Built as a separate `GET /api/sync/entitlement` rather than a field on the batch response, because a lapsed licence stops ingest, so the shop that most needs the renewal notice is exactly the shop whose push is a 401: the news could only ever have reached a till that had not lapsed. The endpoint therefore authenticates a till whose licence has run out, via an exact-path allowlist in `TenantAuthFilter`. Three rules keep this off the critical path — never asked means everything allowed, a lapse withdraws nothing, and the cache has no expiry. Verified live on :8081 and :8082, not only in tests. **415 backend tests** (was 405).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| 2026-08-25 | M4-12     | —           | Console made responsive at phone/tablet/desktop. Nav becomes a sidebar at `lg`; the estate becomes master–detail. CSS breakpoints only — nothing measures the window. Verified in a real browser at 390/834/1440 px, including a no-horizontal-overflow assertion at every size.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| 2026-08-25 | M4-08     | —           | `V210` backfills licences for tenants predating `V209`. Found by running the estate screen, not by a test — the test database is re-migrated from empty, so it has no pre-`V209` tenants and the bug is invisible there. Without it, the `V209` deploy silently 401s every existing shop's till.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| 2026-08-25 | M4-08     | —           | Super-admin shipped. `V209` adds plans, append-only `tenant_licences`, a feature-flag registry, per-tenant overrides, `platform_admins`/`platform_sessions` and `platform_audit`. A third credential kind with **no tenant**; a lapsed licence stops ingest but not the console. First admin comes from a bootstrap runner, not a seeded row. 405 backend tests. Verified live on :8082.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| 2026-08-12 | —         | —           | Roadmap created from the development guide. Greenfield, pnpm + Turborepo confirmed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| 2026-08-12 | M0-01     | —           | Monorepo scaffolded. `typecheck`, `lint`, `test`, `build`, `format:check` all green; terminal emits standalone output. Three toolchain deviations recorded below.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| 2026-08-12 | M0-02     | —           | Two Postgres 16.6 containers up and healthy (`lumora_local` :5442, `lumora_cloud` :5443), TZ `Asia/Colombo`. Moved off 5432/5433 — see the port map below.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| 2026-08-12 | M0-02     | —           | Old POS stack stopped and its restart policy set to `no`, freeing :3000 and :8081. All of its volumes left intact.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| 2026-08-12 | M0-03     | —           | Backend boots on both profiles; Flyway applied `V1`; health UP; desktop profile verified **refused on the LAN IP**. `mvnw clean verify` green, 3 tests.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| 2026-08-12 | M0-04     | —           | `V100__minimal_schema.sql` applied to `lumora_local`. 16 tests green, including structural guards on idempotency, movements-not-balances and integer money.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| 2026-08-12 | M0-05     | —           | `POST /api/sales` live. 23 tests green. End-to-end over HTTP: 201 → `KND-T2-000001`, identical retry → 200 with no duplicate, bad totals → 422.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| 2026-08-12 | M0-06     | —           | Electron shell hosting the renderer. A sale rung up by clicking the real button: `KND-T1-000001`, 3 × 450.00, VAT 205.93 extracted, `-3` movement, 1 outbox row.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| 2026-08-12 | M0-07     | —           | Cloud profile + `POST /api/sync/batch`, idempotent upsert on `client_uuid`, per-item accept/reject. Own schema (`V200`) and its own test database.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| 2026-08-12 | M0-08     | —           | `@Scheduled` outbox drain with capped exponential backoff. 9 worker tests, almost all of them failure-path.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| 2026-08-12 | M0-09     | —           | Status strip verified live in Electron in all three states: `ONLINE · All sales synced`, `↑ 1 SYNCING`, `OFFLINE — sales saving locally · 1 waiting`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| 2026-08-12 | **M0**    | **PASSED**  | **Gate M0.** 10 sales offline → cloud restored → 11 in cloud, 11 distinct uuids, 0 duplicates, 0 missing. Batch replayed: row counts unchanged. 38 backend tests.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| 2026-08-13 | —         | —           | Pushed to `github.com/LumoraTechSolution/NewStorex` — `development` first, `main` only after all gates passed. That branch flow is the standing workflow.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| 2026-08-13 | M1-17     | —           | Product named **StoreX**, "Powered by Lumora Tech". **D3 settled** on logo blue `#0FA0F3` — see the brand/accent split below.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| 2026-08-13 | M1-17     | —           | Verified in Electron. Caught the tender button at **2.42:1** (`text-white` on brand blue) — Tailwind exposed no ink token. Fixed to **6.61:1**; sale `KND-T1-000015` rung up through the real window.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| 2026-08-13 | M4-05     | —           | Light/dark tokens landed early. The console followed the OS in name only — its layout comment promised it, `tokens.css` had no `prefers-color-scheme` block at all. Terminal stays dark; **D6** opened for whether it ever shouldn't.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| 2026-08-13 | M1-01→05  | —           | Money layer done. 53 domain tests, property-based. Cash rounding decided (**awaiting sign-off**). Four domain-computed carts accepted by the Java checksum, including a 1-cent discount over four lines. Properties caught a negative-zero bug on first run.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| 2026-08-13 | M1-06     | —           | `V103` — barcodes became their own table, `products.barcode` dropped after carrying its values across. Trigram search, ranked. Backend suite 38 → 53. Verified against the live database, not just an empty one; the dev seed turned out not to be idempotent and was fixed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| 2026-08-13 | **D6**    | —           | **Settled: the till gets light mode**, explicit and persisted, defaulting to dark. Exposed four AA failures in the light palette — amber carrying the offline warning was at 3.38:1 — all fixed and re-measured in the running window.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| 2026-08-13 | M1-03     | —           | Cash rounding **signed off**: nearest rupee, halves away from zero, applied to the tender and never to the sale.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| 2026-08-13 | M1-07→10  | —           | The appliance. Fixed shell, F-key bar with every slot held, always-focused scan field, keyboard cart. One full sale driven with **0 clicks** — `KND-T1-000020`, 1,710.00. The gun's Enter correctly did **not** tender. **M1-18** raised: mixed tax rates in one cart are refused rather than sold wrong.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| 2026-08-17 | M1-11     | —           | Tender overlay. `summariseTender` (`@lumora/domain`) added — 14 property-based tests, domain suite 53 → 67. `V104`/`V201` add `sale_payments` + `rounding_adjustment_minor`/`change_minor` to `sales`. Backend suite 46 → 58, including a checksum that rejects tenders which don't reconcile and a guard that refuses change without a `CASH` line. Verified against the **running desktop backend and real `lumora_local`**, not just the test database: a 450.50 cash sale tendered as 1,000.00 came back `changeMinor: 54900`, persisted correctly in `sale_payments` and the outbox payload, and a resend returned `alreadyExisted: true` with no duplicate row. **Not yet done:** a human keyboard-only pass of the overlay itself in the running Electron window (Tab/digits/Enter/F12/Esc) — this session verified the money path end-to-end but did not drive the UI live, unlike M0-06/M1-07→10/M1-17 above.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| 2026-08-17 | M1-12     | —           | Invoice numbers now come from a bounded, reserved block, not an unbounded counter. `V105` adds `range_start`/`range_end` to `invoice_counters`; a terminal's first sale still auto-provisions the default 999,999-wide block with no setup step, but `InvoiceNumberAllocator` now refuses once a block's `next_seq` passes its `range_end`, and never widens a block a future provisioning step reserved with different bounds. Backend suite 58 → 63. Verified against the **running desktop backend and real `lumora_local`**: reserved a 2-number block for a throwaway terminal over `psql`, posted three sales over real HTTP — `KND-TSMOKE-000001`, `-000002`, then the third came back `422` / `Invoice block exhausted for terminal TSMOKE`. Test rows cleaned up afterward.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| 2026-08-17 | M1-13     | —           | ESC/POS receipt renderer. `escpos.ts` (raw command bytes) + `receipt.ts` (header, lines, subtotal/discount/tax/total, tender lines, rounding, change, drawer kick) landed as pure TS, tested with byte-level assertions rather than snapshot text — 22 tests, including a property that no rendered line ever exceeds the configured paper width. `vitest` added to `@lumora/terminal` for this, its first test suite.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| 2026-08-17 | M1-14     | —           | Printer transport. Found before writing hardware code: no MSVC toolchain here to build `serialport` from source, no physical printer either way — asked how to proceed rather than guessing; chose a `PrinterTransport` interface with **TCP (RAW/JetDirect, port 9100) as the default and the one this environment can prove**, `serialport` wired in behind the same interface as an `optionalDependency`, lazily required, left unverified. `main.cjs` gained `ipcMain.handle('printer:print', ...)`, `preload.cjs` a `window.lumora.printer.print(bytes)` bridge — both CommonJS, tested directly instead of adding a TypeScript build step for Electron main (see the write-up below for why the old comment expecting that step is now stale on purpose). 11 new transport tests, one a real `net.createServer` round trip. **Then verified live:** launched the real Electron app (`ELECTRON_RUN_AS_NODE` had to be cleared — it was set in this shell and silently downgrades Electron to a plain Node process with no `ipcMain`, a trap worth knowing about), drove `window.lumora.printer.print()` directly over CDP against a fake TCP printer, and the exact bytes (`ESC @ "PING\n" GS V 1`) arrived. **Not verified:** `SerialPrinterTransport` against real hardware, and a full click-through "sale completes → receipt prints" pass through the cashier UI — this session verified the IPC/transport pipeline directly, not via keystrokes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| 2026-08-17 | M1-14     | —           | Closed the serial-transport ABI gap flagged above. Installed Visual Studio Build Tools 2022 ("Desktop development with C++" workload) via winget, added `@electron/rebuild` and a `pnpm --filter @lumora/terminal rebuild:serial` script. Ran it — clean rebuild — then loaded `serialport` inside a **real Electron 33 main process** (`app.whenReady()`, not `next -e`) and called `SerialPort.list()`: it returned this machine's actual COM ports (`COM1`, plus two Bluetooth serial ports), with no `NODE_MODULE_VERSION` mismatch. That is real confirmation the native binding now matches Electron's ABI. **Still not verified:** writing bytes to an attached receipt printer — there is not one on this machine. `serialport` moved from "wired, unverified toolchain" to "wired, toolchain confirmed, only real hardware missing."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| 2026-08-17 | M1-14     | —           | **Real bug, found by the user running `pnpm --filter @lumora/terminal electron` for the first time**, not by this session's own testing: the app failed to load at all — `TcpPrinterTransport requires a host`. `main.cjs` built the printer transport once, eagerly, at module load, and `TcpPrinterTransport`'s constructor threw when no `LUMORA_PRINTER_HOST` was set (the ordinary case for a fresh machine). The throw was never caught, so it took the whole app down before a window ever opened — directly contradicting the principle this milestone was built around, that printing must never be able to affect anything but printing. Fixed two ways: `printerConfigFromEnv` now defaults `host` to `127.0.0.1` rather than leaving it unset, and, more importantly, transport construction in `main.cjs` is now wrapped in try/catch — any future misconfiguration degrades to "printing disabled, logged once" rather than an app that will not start. Reproduced the exact failure and the fix against a real launch: with no printer env vars set at all, the app now opens normally, and a print attempt returns a clean `{ok:false, error:"...ECONNREFUSED..."}` instead of anything crashing.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| 2026-08-18 | M1-15     | —           | **The task was not the one the roadmap described.** "Largely already true, needs a dedicated test" was right about the shop PC and wrong about everything past it: `SaleService` had always written a `SALE` movement inside the sale transaction, but the outbox payload carried no movements, `SyncIngestService` never looked for any, and the cloud's `stock_movements` table — created back in `V200`, with a unique index on `client_uuid` — had never received a single row. Ground rule #2 was half-built and looked finished. Fixed by hoisting the movement's `client_uuid` out of the insert and into the payload, so the key the cloud upserts on is the key the till wrote, and adding `ingestMovements` with `ON CONFLICT (client_uuid) DO NOTHING`. Backend suite 63 → 69. **Verified against both running backends and the real `lumora_local`/`lumora_cloud`, not just the test database:** a two-line sale over HTTP (`KND-TM115-000001`) wrote two movements whose uuids matched the outbox payload exactly, both crossed to the cloud on the next drain, and then the outbox row was un-acked to force a genuine redelivery — the cloud re-accepted the batch and stock on hand stayed at `-2`/`-2`, with 2 movement rows, not 4. Smoke rows deleted from both databases afterward.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |

| 2026-08-20 | M1-18 | — | **Per-line tax rates.** The stamp moved from the cart to the line: `CartLineInput.tax` (falling back to the cart's), a `taxBreakdown` on `CartTotals` grouping net/VAT/gross by rate, `V106`/`V202` adding `tax_mode`/`tax_rate_bp` to `sale_items` on both tiers, and a VAT summary block on the receipt. Domain 67 → 76, terminal 33 → 39, backend 69 → 77. The till no longer refuses a mixed basket. **Verified against both running backends and the real `lumora_local`/`lumora_cloud`:** a bread-at-0% + tea-at-18% sale over HTTP (`KND-TM118-000001`) stored each line under its own rate, `GROUP BY tax_rate_bp` returned the per-rate summary a tax invoice needs, both rates crossed to the cloud, a forced redelivery left 1 sale and 2 items unchanged, a pre-M1-18 payload with no per-line stamp still inherited the sale's, and a mixed cart taxed as though it were all 18% was rejected `422` with nothing written. Smoke rows deleted from both databases afterwards. **Not verified:** the mixed cart driven through the UI by keystrokes — this session proved the money path and the wire, not the screen. |
| 2026-08-20 | M1-18 | — | **Two things were wrong before this task started, both found by doing it.** (1) The backend never checked that the line taxes summed to the sale's tax, and a test fixture had been quietly exploiting that — `twoLineRequest` extracted VAT from the subtotal (19,220) where the domain sums the lines (19,219). One cent, in a fixture asserting the backend agreed with a terminal it disagreed with. (2) `§D`'s IRD deadline was stale: April 2026 became 1 July 2026 under a different gazette, and both dates are now past. See the note added to §D. |

| 2026-08-20 | M1-16 | — | **The keyboard-only spec, against the real Electron window.** Playwright added to `apps/terminal` (`e2e/`, `playwright.config.ts`, `pnpm test:e2e`), launching the actual app through the Electron API rather than pointing Chromium at the dev server — the same reason this project refused H2 and Testcontainers, and the reason the one M1-14 bug that reached a user (`main.cjs` throwing before a window opened) was invisible to anything but the real shell. Six specs: a keyboard-only sale asserted down to the `sales` row and its outbox row; three consecutive sales proving the cart clears, focus returns and invoice numbers advance; a mixed-rate basket; both halves of the M1-09 scanner rule; and one that clicks the mouse on purpose and requires the pointer detector to notice, so the other five asserting "no pointer events" cannot pass vacuously if the recorder ever stops being installed. The suite starts the backend and a Next production build itself, seeds nothing but checks the seed and fails with the command that fixes it, and deletes only the sales it created (a watermark taken before the run) so a developer's own rows survive. `invoice_counters` is deliberately **not** rewound — a terminal's issued block is not reusable, which is the whole of M1-12. |
| 2026-08-20 | M1-16 | — | **The assertion is not "the test never clicked".** Every pointer-shaped event (`pointerdown`/`mousedown`/`click`/`dblclick`/`contextmenu`, capture phase, on `window`) is recorded and each spec asserts the list is empty — a statement about the app, where "we didn't call `page.click()`" would only be a statement about the test. Worth knowing for whoever changes this: a `<button>` activated by Enter fires a synthetic `click` with `detail === 0`, so if the till ever gains a focused-button control the assertion to keep is `detail === 0`, which is the actual line between "activated from the keyboard" and "a hand left the keyboard". The suite also runs against a fake TCP printer, so the receipt is now asserted from a sale the UI really rang up rather than from a hand-built fixture — which closes M1-18's outstanding "not driven through the UI" gap. |
| 2026-08-20 | M1-16 | — | **Two false starts worth recording, because both cost real time and neither was the app's fault.** (1) A spec that failed cold and passed warm: `next dev` compiles on demand, so the first run raced a webpack build later runs did not — disqualifying for a suite whose subject is keystroke timing. Now a production build. Note `next start` does **not** serve the `output: 'standalone'` artefact the installer will ship; doing that properly needs M5-01's packaging step and is left there rather than reimplemented in a test config. (2) A locator, not a race: `getByText(name).first()` matched several nested elements inside a cart cell that also carries the SKU, and picked whichever the DOM ordered first — failing while the cart was plainly on screen. Scoped to `getByRole('row')`. **A wrong turn mid-way is also recorded here on purpose:** the intermittent failures were first blamed on a focus race (the scan field is disabled during tendering, so focus drops to `<body>` and a passive `useEffect` restores it only after paint) and `ScanField` was changed to a layout effect. A MutationObserver on the `disabled` attribute then showed focus was _already_ back before the attribute change was even observable — no such window exists — so the change was reverted rather than kept as a plausible-sounding fix for a bug that was never demonstrated. |

| 2026-08-20 | M2-01 | — | **The till now refuses to sell without an open shift.** A behaviour change to a working path, made deliberately: a sale rung up outside a shift is cash nothing reconciles at the end of the day, which is the exact hole the milestone exists to close. It costs §A nothing — opening a shift is entirely local, and the principle is that the _network_ is on the critical path of nothing, not that the till may have no workflow. `sales.shift_id` stays nullable because pre-M2 sales genuinely had none and backfilling one from a timestamp would invent a fact; the rule lives in `SaleService`, where policy belongs, not in the column. Consequences: the e2e global setup opens a shift, `SaleCommitTest` seeds one per fixture, and a second terminal needs its own (the partial unique index is scoped to tenant + branch + terminal). |
| 2026-08-20 | M2-02 | — | **The blind count is enforced by the endpoint having no field for it, not by the screen choosing not to render one.** `ShiftStatusResponse` — the only shift endpoint a trading till calls — carries no expected cash and no variance; the figure is computed for the first time _inside_ `close`, after the count has already arrived in the same request and is beyond changing. A UI that merely declines to display a value it was sent is blind until somebody opens devtools. The e2e suite asserts the stronger thing: every `/api/` response the Electron window receives during a shift is inspected and none may contain `expectedCash` or `varianceMinor`. `ZReportService` refuses an open shift for the same reason — a Z-report is exactly the leak a printer would make. The cost is a second round trip: the till submits the count, is told a reason is required, and submits again with one. That is the right price. |
| 2026-08-20 | M2-03 | **D1** | **D1 settled: per-tenant, defaulting to LKR 100.00.** `tenant_settings.cash_variance_threshold_minor`. Compared against the **absolute** variance, which is the part worth stating: a drawer LKR 500 _over_ is usually a sale nobody rang up, and a gate that only looked at shortfalls would wave the worse problem through every time. Falls back to the default when a shop has no settings row — a till that cannot close a shift because nobody ran a configuration step is worse than one reconciled against a sensible number. |
| 2026-08-20 | M2-07 | — | **An unset manager PIN refuses every refund.** `manager_pin_hash` is NULL until a shop sets one, and that is the direction a security default has to fail in: a shop that never configured a manager gets refunds it cannot process — visible, fixable, loudly wrong — rather than refunds anybody can authorise, which looks exactly like working software right up until the money is gone. BCrypt rather than a fast hash because a PIN is four to six digits and the whole keyspace falls to a laptop in seconds otherwise; on a shop PC the database file is readable, so the work factor is the only thing between "readable" and "known". `verifyManagerPin` returns `void` and throws, deliberately: a boolean invites `if (ok) { … }` with no else. Added `spring-security-crypto` alone, **not** `spring-boot-starter-security` — the starter would install a filter chain and authenticate every endpoint on a loopback API that has no auth until M3-09. |
| 2026-08-20 | M2-08 | — | **Partial returns apportion cumulatively, not per unit.** Returning 1 of 3 and later the other 2 must give back exactly what returning all 3 at once would; `round(net/3) × 3` does not, and the shop pockets the difference on a rounding error nobody can explain. So a line of `qty` charged `net` defines `f(j) = floor(net × j ÷ qty)` and a return of `k` units after `r` have gone is `f(r+k) − f(r)`. Two evaluations of one expression, so the intermediate rounding cancels and any sequence of partials telescopes to `f(qty) − f(0)` = `net` exactly. Same construction for the tax. Tested exhaustively: every split of every line up to 12 units, over ten awkward amounts, plus a full reversal of a real mixed-rate discounted cart priced by `cartTotals`. |
| 2026-08-20 | M2-06 M2-09 | — | **Both halves of Gate M2 are enforced twice, by construction and then again server-side.** The receipt half: `refunds.sale_id` is NOT NULL and the only code that writes it starts from the invoice lookup, so there is no API that takes an amount and gives money back. The tender half: `allocateRefundTenders` can only allocate to kinds the sale actually took, and `RefundService` re-derives the same caps from `sale_payments` less earlier refunds — so the till's UI is never the only thing standing between a card sale and a cash refund. Cash is netted against the change the sale handed out, or a customer who paid a 5,000 note for a 3,200 basket would look refundable for 5,000. Two independent caps per line, quantity **and** money: returning the right number of units for the wrong amount is exactly what a quantity check alone waves through. |
| 2026-08-20 | M2-06 | — | **Credit notes count on their own sequence.** `invoice_counters` gained a `doc_type`, so a terminal has two independent blocks and a credit note reads `KND-T1-CN-000004`. The reason is auditability, not cosmetics: someone reading invoices 1047, 1048, 1050 has to be able to conclude that 1049 is _missing_, and they cannot if a refund might have borrowed the number. A refund is also never an edit — nothing in `RefundService` touches `sales` or `sale_items`, because the invoice is what the customer was given and what the revenue authority will be shown. |
| 2026-08-20 | M2-12 | — | **A shift is the first aggregate in the system that is not immutable, and that broke an assumption.** Everything before it was written once, so the cloud's upsert could be a no-op and delivery order never mattered. A shift syncs twice — open, then close with the count and variance — so the upsert is a real update, and order is _not_ guaranteed: if the open row fails and is backed off while the close row succeeds, the open row lands afterwards and would reopen a shift the shop has already counted and filed. Fixed by making the ingest monotonic (`WHERE shifts.status <> 'CLOSED'`), so any arrival order converges. Tested directly in `CloudIngestM2Test`. Also: a refund is accepted with no sale present, by design — aggregates drain in whatever order the outbox manages them, and rejecting a refund for arriving first would mean a shop's backlog could only ever drain one way. |
| 2026-08-20 | M2 | — | **Expected cash is a query; the figure stored at close is a stamp, not a balance.** §A says balances are never stored, and while a shift is open expected cash is always `float + Σ cash tenders + Σ rounding + Σ cash movements − Σ change − Σ cash refunds`, computed on read with no running total anywhere. `shifts.expected_cash_minor` is written exactly once and never updated — the same kind of thing as `sales.tax_rate_bp`. Without the freeze, a refund raised next week against a sale from this shift would silently change a number on a Z-report already printed, signed and filed, and the shop would hold two papers disagreeing about one drawer. Asserted by a test that mutates the shift's rows after close and checks the stored figure does not move. |
| 2026-08-20 | M2 | — | **Found while building M2-01: `SaleService`'s `catch (DuplicateKeyException)` could never have worked.** Postgres aborts the entire transaction on a constraint violation, so the recovery query inside the catch fails with `25P02` and replaces a useful error with a confusing one. It went unnoticed because the top-of-method `findByClientUuid` catches every ordinary retry — the catch block is only reachable on a genuine concurrent race, which a single-cashier till does not produce. Removed from `SaleService`, and not repeated in the three M2 services. The correct recovery is to let it roll back: the terminal retries and finds the winner. `ShiftService.open` instead **pre-checks** for an already-open shift, which is what makes "this till already has a shift open" a sentence a cashier can act on rather than a 500. |
| 2026-08-20 | M2 | — | **The e2e suite's backend command had never actually run.** `playwright.config.ts` spawned `mvnw.cmd`, which cmd.exe cannot find because it does not search the current directory — so `reuseExistingServer: true` was silently carrying the whole suite on a backend left running from a dev session. It only surfaced when M2 needed a _current_ build. Fixed to `.\\mvnw.cmd`. Two stale servers from earlier sessions were also stopped (a pre-M2 desktop backend on 8081 and a pre-M2 cloud backend on 8082); the second was rejecting `cash_movement` batches with "Unsupported aggregate kind", which reads exactly like a code bug and was not one. **If sync starts rejecting a kind the code plainly handles, check what is actually listening on 8082 first.** |
| 2026-08-20 | M2 | — | **Verified end to end against running software.** 122 backend tests (Postgres 16, `lumora_test`), 132 domain, 56 terminal, and 11 Playwright specs driving the real Electron window — 5 of them new: the no-leak assertion, a keyboard-only pay-out, a receipt-linked refund that prints a credit note, an unknown invoice going nowhere, and a wrong PIN refunding nothing. All five register **zero pointer events**. The sync loop was then confirmed in `lumora_cloud`: cash movements, refunds, refund items, `RETURN` movements and sales carrying their shift all arrived. Note the cloud shows **no** shift rows after an e2e run and that is not a bug — the suite opens its shift with SQL rather than through `ShiftService`, so no outbox row is written; shift ingest is covered by `CloudIngestM2Test` instead. |
| 2026-08-25 | M4-05 | — | **The console could not reach the API from a browser, and every test passed.** Verified with curl, which ignores CORS. Two faults: no CORS policy anywhere, and the auth filter 401'd preflights — which carry no credential by design. `CorsFilter` now runs ahead of authentication, origins are an explicit list, and 401s carry CORS headers so the browser can read them. `ConsoleCorsTest` asserts the handshake, because a test that speaks HTTP is not one that speaks browser. 377 backend tests, was 371. |
| 2026-08-25 | M4-05 M4-06 M4-07 | — | **The console — and the cloud read side it turned out to need.** The cloud had one endpoint and it was write-only, so three of the four jobs were server-side. `V208` gives owners their own credential (never the till-synced `users` table, which an outbox can overwrite) with an opaque revocable session. Till and console credentials are kept distinct so neither can do the other's job — a mismatch is 403, not 401. Found and fixed: twelve controllers had no `@Profile` and were mounted on the cloud. Verified live on :8082 — signed in, read two branches' takings, saw a short drawer, was refused 403 pushing a sale, signed out and was refused 401. **No way to create an owner account except from Java — that is M4-08 and it now blocks real use.** 371 backend tests, was 337. |
| 2026-08-24 | M5-09 | — | **Shop address and a customer line on the printed receipt.** Two fields off the gap list, at the owner's request — **not all of M5-09**: TIN, the `TAX INVOICE` title, the serial-number format and the invoice-date/supply-date split are still unchecked against Gazette 2481/22. The address wraps at word boundaries in code rather than at the printer, which breaks mid-word; the customer line prints blank rather than being omitted, so a tax invoice asked for after the sale can be written on instead of reprinted. 64 terminal tests, was 56. |
| 2026-08-24 | M4-01 | — | **The `tenant_id` column was isolating nothing.** The tenant arrived in the request body and self-registered on first sight, with no authentication on the port — a caller picked which shop it was writing into. `V205` moved it to a bearer credential and removed the field from the batch entirely; `V206` re-keyed every `client_uuid` unique index to `(tenant_id, client_uuid)`, closing the quieter half where one shop's row was a valid ON CONFLICT target for another's and the loser was told "accepted". M4-02, M4-03 and M4-04 were found already built across M0–M3 and are ticked as found. 315 backend tests, was 301. |
| 2026-08-24 | M5-09 | — | **Gazette 2481/22 read from the source, and it overturned three assumptions.** Purchaser particulars are required only where the purchaser is VAT-registered (Circular §4.3) — a walk-in needs none, and the strict reading would have made the till unusable. §4.2 forbids exempt supplies on a tax invoice, so the document is issued **on request** and is not the till receipt; the owner chose that model. Dates are MM/DD/YYYY. The `YYMMM-QQQQ-XXXXX` serial keeps §A's per-terminal blocks because `QQQQ` is a free branch identifier. `V118` + `V207`. 337 backend tests, was 315; 84 terminal, was 64. |

### M4-11 — three states, and the seam nobody had looked at

The task described a switch. The console needs a **three-state** control, and the difference is not
pedantry: the console has followed the viewer's OS since the palette was written, and that is the
right default for a phone app read in daylight and in bed. A two-way switch has to land on light or
dark, and whichever it lands on becomes a fixed theme — a viewer who wanted their phone's own
behaviour back could never get it. So: **Auto, Light, Dark**, with Auto the shipped state and the
absence of a stored key.

That is the opposite of the till, and deliberately. `D6` settled that the terminal must _never_
follow the machine — a shop PC's theme is whatever the person who installed Windows left on, and a
till that changed colour after a system update is a support call. Two surfaces, two defaults, and
the tokens already expressed both; this milestone only added the control.

Three buttons rather than one that cycles. A cycling button through three states is the control
people press twice, and it can only ever show one label — so it either says where you are or where
you are going, and either choice is wrong for half the people reading it. A `radiogroup` says all
three and marks the current one, which is also the only version a screen reader can announce as a
state.

#### The script is generated, not typed

The blocking script is the first child of `<body>`, which is what stops the flash — a viewer whose
phone is light and whose choice is dark would otherwise watch the app paint white and correct itself
on every load. It cannot import anything, so the obvious build is a string literal in `layout.tsx`
and a matching constant in the component.

They drift. And the symptom of the drift is not an error: it is a saved choice that quietly stops
being honoured, which looks exactly like the flash the script exists to prevent, so nobody suspects
the key. So `lib/theme.ts` **builds** the script, and a test asserts the emitted string contains the
key the module writes with, plus that the script parses at all — it is injected as a string and
never sees TypeScript or the bundler, so nothing else in the build would catch a typo in it.

#### A seam that had been there since M4-05

Doing this meant reading the browser-chrome colours, and none of them were right. `layout.tsx`
declared `theme-color` as `#FFFFFF` and `#04121C`; the manifest said `#04121C`; `--lum-page` renders
`#f5f7f9` and `#0a0e12`. So an installed PWA had a status bar that matched the page in **neither**
theme — a visible seam across the top of the screen. Both are now taken from one exported constant
that the test pins against the token values.

There is a second half to that. The two metas are scoped to `prefers-color-scheme`, which is exactly
right while the choice is Auto; the moment somebody chooses otherwise those two describe the
_machine_ rather than the page, and an owner on a light phone who picks dark gets a dark app under a
white status bar. An explicit choice therefore inserts an unscoped third meta as the **first** child
of `<head>` — the browser uses the first `theme-color` whose media matches, and one with no media
always matches. Choosing Auto removes it. The manifest can only carry one value and it stays dark:
it paints the launch splash before any page exists, and a white flash in bed is the complaint this
whole feature is about.

#### Where it sits

In the sidebar footer beside Sign out on desktop, in the header on phone and tablet, and in
`LoginFrame` — which puts it on **both** sign-in screens from one place, and is where it matters
most: somebody checking their takings at night meets that screen first, and having to type a
password before they can dim it is precisely when the setting would have been worth having.

#### Verified in a real browser, not only in tests

`@playwright/test` is in the repo but its Chromium is not downloaded — the e2e suite drives Electron,
which ships its own. So the check ran in that: a throwaway Electron harness driving the dev server,
reading the computed background in the **first animation frame** after parse, with
`nativeTheme.themeSource` forced. All four combinations correct — Auto follows the machine both ways,
and an explicit choice overrides it in both directions, including the case the task names: **dark
chosen on a light machine paints dark in the first frame**, with the managed meta first in `<head>`.
Pressing Dark applies, stores and marks itself checked; pressing Auto forgets the key rather than
storing the word "system", and the managed meta goes away.

Console tests `6` → `15`. The harness was deleted; it is not a suite, and a console e2e needs a
browser download that this milestone did not justify.

### M4-09 — the news a lapsed shop could never have received

The task read "downward pull of licence / plan / feature flags on the same sync tick", and the
obvious way to build it is a field on the batch response. The till already makes that call every ten
seconds; adding an `entitlement` object to `SyncBatchResult` costs one round trip of nothing.

It is wrong, and the reason is a circularity rather than a preference. `V209` made a lapsed licence
stop ingest — that is the commercial lever, and `TenantCredentialService.authenticate` enforces it by
requiring a covering licence before a token authenticates at all. So the push of a lapsed shop is a
401 with no body. Carrying the entitlement on the batch response would mean the till could be told
"your licence expired" only while its licence had not expired. **The one shop that needs the renewal
notice is the only shop that could never receive one**, and what the cashier would actually see is a
sync strip stuck on OFFLINE with the cable plainly plugged in — a support call that begins with a
false diagnosis.

There is a second, milder reason that points the same way: a batch response only exists when there is
a batch. A shop that closed at six has an empty outbox, so a licence lapsing overnight would be news
that arrives after the first sale of the morning rather than before it.

So it is `GET /api/sync/entitlement`, pulled on the same scheduled tick as the drain but not in the
same request — **the tick is what "the same sync tick" means here, not the HTTP call**. It sits in an
allowlist of exact paths in `TenantAuthFilter` that resolves a till credential _without_ the licence
predicate. The allowlist mirrors `ANONYMOUS_PATHS` deliberately, prefix-free for the same stated
reason: a prefix exemption also exempts whatever gets added underneath it later. Nothing else is
relaxed — a revoked credential and a suspended tenant are still nobody, and the one exempted endpoint
returns the licence window and no ledger at all.

#### Three rules, and each is a way this could have become a network dependency

`V119` caches the answer on the till, and `EntitlementStore` holds the rules:

1. **Never asked means everything allowed.** A till with no cached row is fully capable. Defaulting
   the other way means a fresh install boots with its back office switched off until a network call
   it has not been configured for succeeds — a shop that cannot open. Every till built before this
   milestone is in exactly that state.
2. **A lapse withdraws nothing.** The licence state is written on every answer; the flags are written
   **only when the answer says licensed**. The cloud sends no flags for a lapsed shop (they resolve
   from a covering licence), so writing that through would clear the set and shut the back office of
   a shop that is merely late paying. This is `V209`'s own argument about the console, applied to the
   shop PC: cutting sync is a lever, locking a shopkeeper out of their own catalogue is taking their
   data hostage — and it removes the screen that would tell them how to fix it.
3. **The cache does not expire.** There is no `valid_until` and no sweeper. A shop offline for a
   fortnight keeps everything. `checked_at` exists to be _shown_, never compared against a threshold;
   an expiring cache would put the network back on the critical path of the back office through the
   side door and would punish an outage the shop did not cause.

The honest consequence, written down rather than discovered later: **the flags are not the commercial
lever and must never become one.** Unplug the cable and the till keeps every capability it had. Ingest
is the lever, `V209` already set it, and that is the one with teeth. Flags shape which screens are
offered to a shop that has been told something; they are a product boundary, not a lock.

#### What reads them

`useEntitlement` answers true while loading and true when nothing is cached, so no screen flickers or
vanishes. Customers is gated in the back-office nav (greyed, not hidden — an owner who cannot find a
section rings up to ask whether it exists, one who sees it greyed knows there is something to buy),
and again on the rendered section, because `section` is state that survives a plan change. CSV import,
stocktake and booking in a delivery are each gated at their button and their panel. On the till, Ctrl+I
for the tax invoice is gated, and F6 for customers is **disabled rather than removed** — the function
bar is muscle memory, and a plan change must never renumber the keys a cashier has learnt. Adjusting
stock is deliberately ungated: a shop on any plan has to be able to write off a broken bottle, or its
stock figures start lying and never stop.

#### Verified with the software running, not only in tests

Both profiles up, a `standard`-plan tenant provisioned in `lumora_cloud`, the till started with its
token. The till pulled the plan, its expiry and its four flags into `lumora_local`. The licence was
then expired in the cloud, and the same token got **401 on `/api/sync/batch` and 200 on
`/api/sync/entitlement`** — the circularity above, closed. The till picked the lapse up on the next
tick, logged the warning naming the plan and the date, flipped `licensed` to false, and **kept all
four flags and its `licensed_at`**. The cloud process was then killed outright: the till went on
answering from its own database with `checked_at` frozen at the last successful pull.

One thing that was not planned and is worth recording: while the verification tenant was licensed, the
dev till's **existing outbox backlog drained into it** — twelve stock movements, four sales, two
shifts and ten products. Those rows are now acked locally against a tenant that has since been
deleted from the dev cloud. Harmless in a scratch database, and a reminder that pointing a till with a
non-empty outbox at a fresh tenant ships it that shop's whole history on the first licensed tick.

`415` backend tests, up from `405`: five on the cloud feed (including the lapsed-token pair and the
403 an owner session gets, since this is a till endpoint) and five on the till's cache rules.

### M4-12 — the console stops being a phone app on a monitor

The console was built phone-first, which was right, and then only phone — `max-w-md` at every width,
so a 1440px monitor showed a 448px column with a row of three tabs marooned across the top of it.
Stretching that column is not the fix; a measure 1600px wide is genuinely harder to read than one
400px wide. What changes with the canvas is the **arrangement**, not the scale.

Three shapes now, on Tailwind's default breakpoints:

- **Phone** (base) — unchanged. One column, `max-w-md`, tab strip under the thumb.
- **Tablet** (`md`, 768px) — wider measure, cards pair into two columns, tabs stay.
- **Desktop** (`lg`, 1024px) — the nav leaves the content and becomes a fixed sidebar, and the
  estate becomes **master–detail**: the shop list and the selected shop's panel side by side.

**The master–detail switch is a grid and two visibility classes, not a second implementation.** A
phone can only do a list-and-panel as a drill-down; a desktop that did the same would discard the
list every time somebody checked a licence, and comparing two shops would take four navigations. But
both use the same `EstateList` and `TenantDetailScreen` — below `lg` one pane is hidden, above it
both show. The back link is `lg:hidden`, because above that width there is nothing to go back to.

**Nothing measures the viewport in JavaScript.** Every breakpoint is CSS. A layout keyed on
`window.innerWidth` renders the wrong shape on the server and corrects itself after hydration, which
is a visible jump on every load. The cost is that both navs are always in the DOM — a few hundred
bytes, and correct in the first paint. It also caught out the verification script, which kept
selecting the hidden nav's twin until every query was scoped to `nav[aria-label="Sections"]:visible`.

**What deliberately does not change is which figures are on screen.** A desktop layout that revealed
numbers the phone hid would let the two disagree about what the shop took today, which is the one
thing this app exists to say. `Row` also keeps its 56px height on desktop, where a mouse would be
happy with less: a list that changes row height between a laptop and the tablet beside it reads as
two different products, and the denser version buys nothing but one more row above the fold.

**Verified by driving a real browser at 390, 834 and 1440 px** — both consoles, login through to a
shop's detail panel. The script asserts the master–detail switch flips at the right width, that the
back link appears only below it, and that **no page scrolls horizontally at any size**, which is the
classic responsive failure and the one a screenshot alone will not show you. No console errors at any
width. The owner console was re-shot against a tenant with 119 real sales in it, because an
empty-state screenshot proves a layout holds and says nothing about how it handles rows.

### V210 — the tenants the licensing rule forgot

Found by **running** the estate screen against the development cloud, not by a test. Two tenants
left over from `M4-05` showed as "Licence lapsed" — correct according to the schema and wrong about
the world.

`V209` made a live licence a condition of authentication, granted one to every tenant created after
it, and granted none to any tenant that already existed. So the deploy that applies `V209` stops
every shop already on the system from syncing — and not with an error anybody would read as a
licensing problem. The till gets a 401, which is deliberately the same answer it gets for a revoked
key, a suspended tenant and a typo (`V205`). The outbox would queue, the shop would keep selling,
and the first symptom would be an owner noticing the console had gone quiet.

**No test could have caught this**, and that is the part worth remembering. `CleanDatabaseBeforeTests`
drops and re-migrates before every run, so at migration time there are never any pre-existing
tenants; the backfill is a no-op there and the bug is invisible. A migration that establishes a rule
has to bring the existing rows into it, and the only place that shows up is a database with history.

`V210` backfills, fixing forward rather than editing `V209` — which is applied here and checksummed.
It grants **standard**, not trial: a tenant that predates licensing has been running as if it had
one, so dropping it onto a trial would be an unagreed downgrade, and a trial expires — the same bug
with a thirty-day delay. Backdated to each tenant's `created_at` so the history reads as an unbroken
period, and the note says it was a backfill. Verified live: the two shops went from "no plan ·
Licence lapsed" to "standard · Live", and the estate's live count went 3/6 to 5/6.

### M4-08 — the estate, and the credential that belongs to no shop

The milestone's own blocker, stated in the previous position line: there was no way to create an
owner account except from Java, so no real shop could sign in. That is closed. What follows is what
building it decided.

**Creating a shop is one transaction, because four separate acts is four ways to half-make one.**
A usable tenant needs the row, a licence period, an owner who can sign in, and a token for the till.
Only the first and last existed, and each missing piece produces something that _looks_ created and
does not work — an owner with no licence syncs nothing, a tenant with no owner is a row nobody can
reach, and a token issued against an unlicensed tenant authenticates as nothing. All four now happen
in `TenantAdminService.createTenant` or none do, and a test asserts it: a password below the console
minimum leaves no tenant row behind.

**The third credential kind carries no tenant, and asking for one throws.** `TILL` and `CONSOLE` both
resolve to exactly one shop. Staff are the opposite — the whole point is spanning shops — and the
tempting shortcuts are all quietly wrong: tenant zero, the first row, or the tenant named in a
parameter. So `AuthenticatedPrincipal.tenantIdOrNull` is genuinely null for `PLATFORM` and
`tenantId()` throws. Every existing caller reaches it through `CloudPrincipals.require`, which asserts
the kind first, so the throw is unreachable from a correct call site; it exists so an incorrect one
fails at the top of the request instead of succeeding against the wrong shop.

The wall that falls out of this is worth stating: **staff can create a shop, licence it, suspend it
and re-key it, and cannot ring up a sale in it or read a figure through the owner's endpoints.**
Administering a business and operating one are different credential kinds, not different branches in
one. Six tests assert each direction, and all six were re-checked against the running service.

**A lapsed licence stops ingest and deliberately does not lock the owner out.** This corrects what
`V205` and `V208` assumed in their headers, both written before licences or a renewal notice existed.
Cutting a shop's sync is the commercial lever; cutting an owner out of their own takings is taking
their data hostage, and it removes the only screen that could tell them what is wrong. That is only
defensible if the screen actually says so, so `/api/console/auth/me` now carries `licensed` and the
plan. `tenants.active = false` — a person deliberately suspending — still stops both, and also
revokes live console sessions: without that, a suspension would take up to seven days to bite.

**Licences are append-only, which is §A's rule one layer up.** "Licensed until March" is derived from
the grants, never stored and mutated. Renewing inserts, and renewing _early_ starts from the end of
the current period rather than throwing away a month somebody has paid for. A billing dispute is
answered by reading the table.

**The feature-flag registry is a table so a typo cannot be silent.** With free text, `stock_take` and
`stocktake` are two flags, one of which is off forever and neither of which reports a problem. The
foreign key makes it a failed write at the moment somebody makes the mistake — asserted by a test that
posts exactly that typo. Overrides carry a boolean rather than meaning "on" by their presence, because
an override has to be able to take a plan feature _away_.

**Nothing on the till reads these flags yet.** They are declared, resolvable and editable, and `M4-09`
is what pulls them down on the sync tick. The screen says so in as many words rather than implying a
switch that does something.

**The first admin comes from a bootstrap runner, not a seeded row.** Everything here is done by an
admin, including making admins. A seeded row means every deployment of this build ships the same
working credential; an unauthenticated signup route is a race with the internet; a SQL script is the
"run Java by hand" this milestone exists to abolish. So: an email from whoever deploys, a password the
process generates and logs once, and both used only when the table is empty. Setting the property
again later does nothing — the guard is the state of the table, not the absence of the config, so
leaving it in an environment file is not a standing back door.

**Two things the tests caught that a reading would not have.** Jackson serialises a record's
_components_ and nothing else, so `TenantSummary.state()` was invisible on the wire and every row on
the estate screen would have shown an undefined state — fixed with an explicit `@JsonProperty`. And
the audit trail named only the display name, which cannot tell two colleagues apart; it now carries
the email, which is the identity that is actually unique.

**Not a bug, checked rather than assumed.** A live response looked like it had mojibake where an
em-dash should be. The raw bytes are `e2 80 94` — correct UTF-8. The mangling was `python -m json.tool`
decoding stdin as cp1252 on this machine. Worth recording so the next person does not "fix" the server.

**Known and deliberate.** `plans.max_terminals` and `max_users` are recorded and shown but not enforced
at credential issue — the column comment says so. Enforcement belongs with `M4-09`, where the till
learns what its plan allows.

### M3-08 / M3-01 — the audit trail finally names somebody

**Eight migrations of promises, collected.** Every `created_by` since `V100` carried the same
comment — "real FK arrives with M3-08" — and the literal `1` that `LocalShop.SEEDED_OPERATOR_ID`
supplied. `V109` creates `users`, repoints those columns and turns six foreign keys on, so from
here an audit trail that names nobody is a write that fails rather than a row that quietly lies.
The placeholder constant is deleted, not deprecated; there is no longer any way to write an
unattributed row.

**The shop-wide manager PIN is gone rather than kept for compatibility.**
`tenant_settings.manager_pin_hash` moved into a real MANAGER user, hash intact, and the column is
dropped. Two credential stores for one gate is a bug waiting for a rotation: one of them gets
forgotten and keeps working. `TenantSettingsService` is now only the variance threshold.

**Code identifies, PIN authenticates.** A PIN alone was rejected twice over. It cannot be looked
up, so verifying one means BCrypt-comparing against every active user — one deliberately-slow
comparison each, and slowness is the entire reason BCrypt is here. Worse, two people may both pick
1234, and then `authorised_by` names whoever the scan reached first. An audit trail that can credit
the wrong person for a refund is worse than none, because it gets believed. So the manager types
three extra characters, and `refunds` now records **who allowed it and who rang it through
separately** — the distinction `V108` described and could not express.

**Roles are an enum, not a table.** No `permissions` table, no join, no per-shop configuration.
What a MANAGER may do is a set in `Role.java`, in version control and covered by tests. Configurable
roles sound more flexible and buy a specific misery: one shop's MANAGER silently differs from
another's, and "why was this refund refused?" has no answer that does not start with reading that
shop's database. The four names are duplicated into a CHECK constraint on purpose — the database is
the last line — and a test asserts the two lists agree, so the duplication cannot become a
divergence.

**SUPERVISOR exists so the gate is a permission and not a rank.** In a small shop the person on the
floor at 8pm is not the manager, and a returns policy that needs the manager's own PIN is a policy
that ends with the manager's PIN written on the till.

**The shift is the session.** This is the decision most worth arguing with later. Sales, cash
movements and stock movements are attributed to the shift's `opened_by` — the person who counted
the float and authenticated to open it. It is true of a v1 shop: one till, one person behind it,
and the till already refuses to trade without a shift. It stops being true the moment two cashiers
share a till on one shift, which is what `M3-09`'s real sessions are for. Until then this
attributes to somebody checkable rather than to nobody, which is what it did before. Closing does
**not** require the person who opened — a shift outlives whoever started it often enough that
forcing a match would leave tills nobody can close, and `opened_by` / `closed_by` being separate
columns is what records the handover instead of hiding it.

**Signing is its own screen.** `F12` on a denomination count no longer submits; it goes to a SIGN
screen. Mechanically the counter owns the digit keys, so a PIN field beside it would type into the
notes column. The better reason is that it puts the name after the count: whoever signs is signing
for a figure already fixed, which is the same argument the blind count rests on. The SIGN screen
shows the counted total and deliberately nothing to compare it against — it is one keypress from
submitting, and showing the expected figure there would undo `M2-02` at the last possible moment.

**The back office is a route, not another overlay, and `Ctrl+B` not an F-key.** The till is a fixed
appliance — one screen, no navigation, nothing scrolls but the cart — and the back office is lists
and forms. Forcing one into the other wrecks the shell or produces a back office nobody can use.
`Ctrl+B` because every slot on the function bar is a selling action, and putting "change prices"
one keypress from "void line" is how a busy cashier ends up somewhere they did not mean to be. It
is also **the one surface where a mouse is allowed**: it is used sitting down, a few times a week,
to type names into forms. Sign-in stays keyboard-only, because it is the same PIN entry the till
uses and PIN entry that differs between two places is PIN entry somebody gets wrong in one of them.

**What is deliberately still missing.** No session and no token: every back-office request replays
`X-Operator-Code` / `X-Operator-Pin`, so the page holds the PIN in memory while it is open. That is
uncomfortable and it is deliberate — `M3-09` issues short-lived locally-signed JWTs, and inventing
a weaker session now means building it twice and shipping the worse one first. `OperatorGate`
should be **deleted** then rather than left alongside; two ways in is how one stops being
maintained. No attempt lockout either: BCrypt's cost is the only thing rating a guess, which is
real and not strong, and is now tracked as **`M3-13`** rather than left unsaid.

**Verification, and one gap in it.** 143 backend tests (122 → 143), 16 Playwright specs (11 → 16)
including a cashier being refused a refund and refused the back office through the real window,
with zero pointer events. `V109` was applied for real to `lumora_local` carrying 23 sales, 36
movements, a shift, a refund and a cash movement — the whole point being that the test database
always migrates from empty and can never exercise a backfill. It applied cleanly. **The gap: the
backfill `UPDATE`s did nothing there**, because a fresh `bigserial` gave the migrated manager id
`1`, the same value the placeholder had been using. On a single-tenant desktop database that will
essentially always be true, so those statements are a safety net that has never caught anything.
They are cheap and correct; do not mistake "V109 applied to real data" for "the backfill branch is
tested".

**An operational gotcha worth knowing.** Running a subset of the e2e suite and then the whole suite
crashed a worker with `0xC0000409`, followed by `electron.launch: Target page … has been closed` on
everything after it. The cause was three Electron processes left alive by the earlier targeted
runs. Kill strays before a full run; the failure looks like a code fault and is not one.

### M3-02 — the catalogue becomes editable

**Categories are a table, and that is the whole decision.** `products.category text` was the
smaller change and it fails twice within a month of real use. A shop renames an aisle — "Beverages"
becomes "Drinks" — and a text column makes that an `UPDATE` across the catalogue that nobody
remembers to re-run on the rows added since. And a typo is not a typo, it is a new category:
"Bevarages" sits quietly beside "Beverages" and splits a month's takings across two lines on the
report that was the reason for having categories at all. `V110` costs one join on a screen used a
few times a week and makes both impossible. Nullable on purpose: requiring a category means a
shop's first act is to invent a taxonomy, and what shops do under that pressure is create one
called "General" — the uncategorised state with extra steps and a name to maintain.

**Barcodes are set as a list, not added and removed one at a time.** `save` takes the complete set
a product should carry and makes the table match. Per-code endpoints were the alternative and they
make the form's Save button a lie: an owner who deletes a row and then abandons the form expects
the code to still scan. Codes that survive an edit keep their `client_uuid` rather than being
deleted and reinserted, so `M3-12` will not see a removal and an addition every time somebody fixes
a spelling. And the duplicate check runs before the unique index does, purely so the refusal can
say _"already on Ceylon Tea 400g"_ — the person typing is holding a packet that will not scan, and
an index name tells them nothing they can act on.

**The write path is a separate controller on a separate base path.** `/api/back-office/products`,
not more verbs on the till's `/api/products`. The till's reads are unauthenticated because a scan
cannot wait for a PIN; every endpoint here demands `MANAGE_PRODUCTS`. One path carrying both, half
of it gated, is where a later GET gets added to the wrong half.

**A tax rate above 100% is refused, and V100's CHECK does not catch it.** The constraint only
refuses negatives. The mistake that actually happens is typing 18% as 18000 basis points, which
prices nothing wrongly on the shelf and then extracts almost the entire total as tax onto a receipt
the customer takes away. The screen parses both the price and the rate with `parseAmountToMinor` —
basis points are a percentage with two decimals, the same arithmetic as rupees to cents, so there
is no second conversion to get wrong and no `parseFloat` anywhere near either.

**Two latent test faults surfaced, and both were real.** Adding a catalogue test that committed
rows broke three others, which is worth recording because neither cause was the new code.
(1) `ProductLookup.active()` and `search()` are **not tenant-scoped** — deliberately, since a
desktop database holds exactly one tenant and making every scan look one up first buys nothing —
so `ProductLookupTest`, which seeds a second tenant and asserts whole-catalogue counts, was quietly
depending on class execution order. (2) `SaleCommitTest`, `RefundTest` and `ShiftLifecycleTest`
still wrote the literal `1` into `opened_by` / `closed_by` / `created_by`: a pre-V109 placeholder
that only kept working because some other class had committed a user with that id first. Both are
fixed at the source rather than worked around, and the new test rolls back so it cannot pollute the
shared tenant.

**The hint text was part of every field's accessible name.** `Labelled` wrapped the caption _and_
the hint in one `<label>`, so the filter box announced itself as "Find name, code, or a barcode" —
which then collided with the product form's "Name" and made `getByLabel('Name')` ambiguous. A
screen reader reads that whole run-on sentence before every keystroke. The hint now sits outside
the label. Worth noting the test found an accessibility bug, not the other way round.

**Escape does not leave the back office once somebody is signed in**, and that is deliberate rather
than missing: a stray Escape mid-edit would throw away a half-typed product along with the session.
The way out is the button. Escape still leaves the sign-in screen, where there is nothing to lose.

**Verified against running software.** 157 backend tests (143 → 157) and **19 Playwright specs**
(16 → 19), the central one being a round trip no unit test can make: a product typed into the back
office at `4.29` — chosen for its cents, since `parseFloat('0.29') * 100` is 28.999999999999996 —
then scanned at the till and priced correctly. `V110` applied cleanly to `lumora_local`.

### M3-03 — the import that shows its working

**The file is read in the domain package, and that is not where it looks like it belongs.** A CSV
holds prices as text, and turning `285.00` into 28500 is money math — §A puts money math in exactly
one place. A Java parser would have been the second implementation of a decimal shift, and the day
the two disagreed the evidence would be a catalogue that priced correctly on screen and wrongly on a
receipt. So `parseProductCsv` lives in `@lumora/domain` beside `parseAmountToMinor`, and the import
endpoint takes **rows, not a file**. The split falls out cleanly: text becomes rows on one side, and
rows are checked against the catalogue on the other, because only the server knows what SKUs and
barcodes already exist. Neither half can do the other's job and only one of them touches money.

**The dry run is the feature, so it is tested as a property rather than a screen.** `plan()` writes
nothing — including categories. Reporting a new category is easiest to implement by creating it and
saying so, and then a preview somebody abandoned has quietly seeded the picker with the typo they
were about to spot. Both the unit test and the Playwright spec assert the catalogue is untouched
after a preview; a dry run that quietly writes is worse than none, because it is now trusted.

**A plan can only be applied as it was shown.** The preview returns a `planHash` over the decided
actions and their field-level changes, and `apply` recomputes the plan and refuses on mismatch.
Two things this catches: a different file picked between the preview and the confirmation, and the
catalogue moving underneath — a product added by hand in between turns the same file from a create
into an update, which really is a different plan. Row order is deliberately _not_ part of the hash,
because reordering rows does not change what the import does and forcing a second look would train
people to click through it.

**One bad row refuses the whole file.** Importing the good 380 of 400 leaves a shop half-updated
with no record of which half, and the only way back is a spreadsheet diff nobody will do. Refusing
everything costs one edit-and-retry and keeps the catalogue in a state somebody understands. The
parser mirrors this by collecting _every_ broken row rather than stopping at the first — forty
faults reported one at a time is forty round trips through a spreadsheet.

**Changes are rendered, not counted.** "412 products updated" is exactly what a file that halves
every price also says. The preview lists `price 120.00 → 135.00` per row, and barcodes compare as
sets so a re-import in a different order is not a change — otherwise the real edits drown in noise
on every supplier resend, which is the commonest use of the whole feature.

**Categories in the file are created, and the preview lists them by name.** Refusing unknown
categories would make importing a fresh catalogue impossible without typing a taxonomy in by hand
first. The safeguard against the exact problem V110's table exists to prevent is that they are
_listed_, not counted: "Bevarages" beside "Beverages" is visible there and nowhere else.

**An absent VAT column is valid, silent, and catastrophic — so it warns.** Every row parses, nothing
is wrong, and the whole catalogue is zero-rated until the next VAT return. Same for an absent
barcode column, where the symptom is goods that will not scan. These are warnings rather than errors
because both files are legitimate; what they must not be is quiet.

**Duplicates within the file are caught by the planner too, not only by the parser.** The endpoint
takes rows, so the parser is not on every caller's path. Left to the database, the preview would
report two clean creates and the apply would fail halfway on a unique index — rolling back
correctly, and still meaning the preview lied.

**An import is not a sync.** A product the file does not mention is left exactly as it is. A
supplier's price list covers that supplier's goods, and treating absence as deletion would retire
half a shop the first time one was loaded.

**Verified against running software.** 172 backend tests (157 → 172), 155 domain (132 → 155), and
**22 Playwright specs** (19 → 22): a preview that leaves the database untouched and then imports
what it showed, a file refused whole because one row stole a barcode, and the zero-rating warning
seen in the real window. The import price is `4.29` again, for the same reason as M3-02 —
`parseFloat('4.29') * 100` is 428.99999999999994, and a float anywhere on the path lands 428.

### M3-04 — the first movement that puts stock on a shelf

**Everything before this took stock off.** SALE in M1, RETURN in M2 — so "stock on hand" has never
had anything true to say, because every product has only ever gone negative. `V111` adds
`suppliers`, `goods_receipts` and `goods_receipt_items`, and a delivery writes `RECEIVE` movements.

**This was the schema's one real chance to grow a `quantity_on_hand` column, and it did not.** A
goods receipt is the obvious place to "just increment the level", and the increment is wrong within
a week: a note keyed in twice, a correction applied to the level but not the history, two tills
receiving offline. On hand stays `Σ qty_delta`. There is now a test that reads
`information_schema.columns` and fails the day such a column appears — the rule was a comment in
V100 for four milestones and is now an assertion.

**Cost is recorded and never becomes a price.** `unit_cost_minor` is what the shop paid. Nothing in
this milestone writes it to `products.price_minor`, the form says so in as many words, and a test
asserts the shelf price is unmoved after a delivery. A delivery that repriced the shelf would be the
supplier setting the shop's margin, and the shopkeeper would find out from a customer.

**The unique index on (supplier, delivery note №) is the most valuable line in the migration.** One
note keyed in by two people doubles every quantity on it, with nothing on screen to suggest anything
happened — no error, no duplicate document a person would notice, just a stock figure that is wrong
in the direction that hides shrinkage. Partial, so the market purchases that have no note do not
collide with each other.

**A goods receipt is immutable, and the correction is an adjustment.** No edit, no delete — the same
argument V108 makes about refunds not editing sales. A receipt entered wrongly is corrected by an
`ADJUST` movement with a reason (`M3-05`), which leaves the miscount _and_ the correction on the
record instead of one plausible number.

**Receiving deliberately does not need an open shift.** Selling does (M2-01) because a sale outside a
shift is cash nothing reconciles. Stock is not cash, goods arrive at seven in the morning before
anyone has counted a float, and a system that refuses the delivery until somebody opens a till is a
system people work around.

**The outbox row goes in with the movements, and the cloud was taught to accept it in the same
change.** §A rule 1 is that the sync record is written in the same transaction as the domain rows,
so deferring it to `M3-12` would have meant editing this write path twice. But writing an outbox row
for an aggregate the ingest does not know is worse than not writing one: it is rejected and backed
off forever, the till looks fine, the shop's stock silently never reaches the cloud, and the symptom
reads like a bug in the sync loop — the exact trap recorded in the 2026-08-20 M2 rows. So
`SyncIngestService` gained a `goods_receipt` case in the same commit. Cloud-side it ingests **only
the movements**, because there is no cloud reader for the document yet (purchase and margin
reporting is `M4-06`) and a table with no reader is a schema decision made without the question that
should shape it. `ingestMovements` was generalised to take the timestamp — a sale says `soldAt`, a
receipt says `receivedAt`, and everything else about the rows is identical; two copies would be two
places for the `ON CONFLICT DO NOTHING` to be forgotten, and the forgotten one doubles a shop's
stock on a retry.

**Reads are gated on `MANAGE_STOCK`, not `BACK_OFFICE`.** A deliberate difference from the products
screen: a goods receipt carries what the shop _paid_, and cost prices are the one thing on a till an
owner may reasonably not want every manager reading.

**Verified against running software.** 190 backend tests (172 → 190) and **24 Playwright specs**
(22 → 24): a delivery booked in through the real window with on hand asserted as `Σ movements`
before and after, the shelf price unmoved, the outbox row present, and the same delivery note
refused the second time with one receipt and ten units left behind rather than two and twenty.
`V111` applied cleanly to `lumora_local`.

**The `0xC0000409` trap caught this session too, and the §G note was right.** A targeted
`--grep` run followed by the full suite crashed a worker with a stack-buffer-overrun code and took
every test after it down with `electron.launch: Target page … has been closed`. Three stray Electron
processes. Killing them fixed it with no code change. A second, new variant is worth adding: a Next
server started as a detached background job can be reaped mid-run, which kills port 3000 and
produces `ERR_CONNECTION_REFUSED` on every later spec plus one baffling click timeout in the middle.
**Let Playwright start both servers.** Both failure modes read exactly like application bugs and
neither is one.

### M3-05 — the correction path, and the hole it opens

**This is the one way to move stock with no sale, no customer and no supplier.** That makes it two
things at once: the reason `M3-04`'s goods receipts and `M2-06`'s sales can be immutable, and the
exact shape of the gap somebody walks goods out through. Both halves of the design follow from that
— a reason is mandatory so the movement stays answerable, and nothing is ever deleted so a
correction leaves the mistake beside the fix.

**The sign belongs to the reason, not to the typist.** `signedAdjustmentQty` in `@lumora/domain`
takes a plain positive count and DAMAGED is what makes it −5, exactly as `signedCashMovementMinor`
does for a pay-out. A shopkeeper who has to remember a minus will one day forget, and stock moves by
twice the amount the wrong way with nothing to flag it. `AdjustmentReason.requireConsistent` then
re-checks the sign server-side: a client sending `DAMAGED, +5` is refused rather than quietly
putting goods on a shelf that is actually empty, which the next stocktake would report as shrinkage
that never happened. Only `COUNT_CORRECTION` and `OTHER` may go either way, and for those the form
asks.

**`V112` adds `reason_code` and `note` to `stock_movements`, not a table.** A goods receipt is a
document — supplier, delivery note, several lines that arrived together — but "three of these were
broken" is one fact about one product, and the movement already carries who, when, which product and
how many. A header table would mean a one-line document for every real adjustment a shop makes. The
CHECK is scoped to `ADJUST` on purpose: SALE and RECEIVE already point at their document through
`ref_type`/`ref_id`, and STOCKTAKE is left out because `M3-06` decides what a variance records and a
constraint written before the question is a constraint written wrong.

**The database refuses a reasonless ADJUST independently of the service**, and there is a test that
inserts one directly to prove it. "Our code always sets it" and "a row without one cannot exist" are
different guarantees, and only the second survives the next person writing a script.

**The vocabulary is duplicated between TypeScript and Java, and a test enforces that they agree.**
The screen cannot import Java and the backend cannot trust the screen, so the list exists twice —
same reasoning as `Role` and its CHECK constraint in V109. `StockAdjustmentTest` reads
`packages/domain/src/stock.ts` off disk and asserts both directions: every Java reason is offered by
the screen, and the screen offers nothing Java would refuse. The second direction is the one that
matters, because it is the one that would let a shopkeeper pick a reason and then be told no.

**On hand may go negative and is not clamped.** A sale rung up before its delivery was booked in
really does leave a shelf at −2, and clamping to zero would hide the exact discrepancy the
shopkeeper needs to see. The form shows it in the danger colour with a sentence saying to look for a
delivery that was never entered — colour never carrying the meaning alone (§A).

**`onHandAfter` is bounded by the movement's own id.** Without `x.id <= m.id` the subquery sums the
product's whole history, and every row in a list of past adjustments shows _today's_ figure under a
column headed "on hand after" — worse than showing nothing. Ordered by `id` rather than `created_at`
because a bigserial is strictly monotonic while two movements can share a timestamp.

**A flake that turned out to be a real defect.** The e2e spec for M3-04 intermittently timed out
clicking "Book it in". The cause was not Electron: `act()` sets the notice, _then_ reloads, and only
then does the caller close the form — so a spec that waits for the notice can fill the already-open
form and click a button that unmounts underneath it. A person can do the same thing, and because
each submit mints a fresh `client_uuid`, two presses would book the delivery in twice. Fixed
properly with a `busy` guard on the submit button, and the spec now waits for the form to actually
go. **Worth remembering: a Playwright click that times out on an element it has already found is
usually a detach, not a slow page.**

**Verified against running software.** 205 backend tests (190 → 205), 170 domain (155 → 170), and
**26 Playwright specs** (24 → 26): a DAMAGED adjustment driven through the real window with the
on-hand preview asserted before it commits, the reason landing on the movement, the outbox row
present, and `OTHER` refused until it says what it was. `V112` applied cleanly to `lumora_local`.

### M3-06 — the stocktake writes the difference, and that is the _correct_ answer

**"Set it to what I counted" is not merely dishonest, it is wrong.** The roadmap has always said a
stocktake must write the difference because shrinkage is what the owner needs to see, and that
argument is about integrity. Building it surfaced a second argument that is about arithmetic, and
it is the stronger one. Counting takes time and the shop keeps trading:

- System says 20, you count 17, and two are sold before you finish. Writing the difference applies
  −3 to a system now at 18 and lands on 15 — which is what is actually on the shelf.
- Overwriting the level sets 17, and is wrong by exactly the sales that happened while somebody
  walked round with a clipboard.

Deltas compose; levels do not. It is the same property that lets two offline tills reconcile by
addition with no conflict logic (§A), applied to a person with a pen. `StocktakeTest` asserts it
directly, and the Playwright spec drives the whole sequence — count, sell, complete — through the
real window, because that is the behaviour somebody would "simplify" away.

**Two phases, because counting a shop takes longer than one sitting.** OPEN writes nothing at all —
`anOpenStocktakeMovesNoStock` is the test, and the screen says _"No stock has moved yet"_ in as many
words. COMPLETED writes every movement in one transaction. A design that wrote a movement per line
would leave a shop half-adjusted with no way to tell which half, and a shopkeeper who stopped for
lunch would have done exactly that.

**Products nobody counted are left alone.** Counting one shelf is the normal case, and an absent
line meaning "zero found" would empty a shop the first time somebody counted the spirits. Same
principle as M3-03's "an import is not a sync".

**`system_qty` is a stamp, not a balance.** It records what Σ movements came to at the instant the
line was entered, so the variance stays reproducible afterwards. That looks like the stored level §A
forbids and is not one: nothing reads it to answer "what is on hand", and nothing updates it. It is
the same kind of thing as `sales.tax_rate_bp`. The variance itself is deliberately **not** stored —
a third column holding `counted − system` is a third place for the three to disagree.

**One count open per branch**, enforced by a partial unique index shaped exactly like
`ux_shifts_one_open_per_terminal`. Two open stocktakes means two people counting one shelf into
different documents, and whichever completes second writes a variance against a system figure the
first already moved. ABANDONED exists so a count started by mistake has a way out that is not a
DELETE — it wrote nothing, but the attempt is still history.

**No `reason_code` on a STOCKTAKE movement, which settles what V112 left open.** V112 scoped its
CHECK to `ADJUST` and said M3-06 would decide. The decision: a stocktake variance's reason _is_ the
stocktake, and it already points at the document through `ref_type`/`ref_id`. Requiring a code as
well would mean inventing a constant to satisfy a constraint.

**Two e2e lessons, both worth keeping.** `getByLabel` matches form controls, so a `<ul aria-label>`
needs `getByRole('list', { name })` — the failure looks like a missing element and is a wrong
locator kind. And the specs now count **absolute** quantities rather than "current minus three":
this developer database has nineteen sales of TEA-400 and no deliveries ever booked in, so its on
hand is **−25**, and a derived count went negative — at which point the numeric input silently ate
the minus sign and the spec typed 28 instead of −28. A shopkeeper types what is on the shelf; so
should the spec.

**Verified against running software.** 221 backend tests (205 → 221) and **28 Playwright specs**
(26 → 28), including the count-sell-complete sequence landing on 38 rather than 40. `V113` applied
cleanly to `lumora_local`.

### Gate M3 rehearsed with the cable out — not the gate, but real evidence

**What was actually run, 2026-08-23.** `pnpm db:offline` stopped `db-cloud`, and the **whole
Playwright suite was run against a genuinely unreachable cloud**: 38 specs, all passing. That covers
selling, tendering, refunds against a receipt, a cash pay-out, opening and closing a shift, creating
a product, a CSV import, a goods receipt, a stock adjustment, a stocktake, stock on hand, the day
report, adding a customer at the till, signing in and out of the back office, and the PIN throttle.
Nothing degraded and nothing waited on anything.

The queue behind it grew to 41 unacked rows across seven aggregates — `sale`, `refund`, `shift`,
`cash_movement`, and M3-12's three new ones. Bringing the cloud back (`pnpm db:online` plus both
profiles running) drained it, and the cloud database then held 12 products with their barcodes,
3 users, 3 customers, and 3 sales carrying a `customer_client_uuid` — end to end through the **real
sync worker**, not through `SyncIngestService` in a test. `information_schema` confirms there is no
column on cloud `users` whose name contains "pin".

**This is not Gate M3 and GATE-M3 stays unticked.** The gate says _a week_, and a week is about the
things a scripted run cannot produce: a queue that has grown for days, a shop that has closed and
reopened, a disk that has filled, an invoice block that has run out, somebody's patience. A person
has to do it. What the rehearsal establishes is that nothing in M3 put the network back on the
critical path — which is the failure the gate would otherwise discover late.

**Two things seen during the rehearsal that look like defects and are not.**

- **Eight rows were still pending after the cloud came back.** They had eight failed attempts each
  and were sitting in the capped five-minute backoff from when the cloud was genuinely down. That is
  `V102` working exactly as designed; a shop that recovers at 3am syncs by 3:05.
- **The cloud holds three `E2E` users, all with the same code.** The e2e spec deletes its local user
  each run and creates a new one with a fresh `client_uuid`, so the cloud correctly treats them as
  three different people who happened to share a code. Identity is the uuid, and cloud `users` has no
  unique index on `(tenant_id, code)` for exactly that reason — uniqueness of a code is a rule the
  shop enforces on its own database. A test artefact, not a sync bug.

### The console could not talk to the API at all, and the tests all passed

Reported immediately after the entry below was written: **"Failed to fetch"** on every request. The
endpoints had been verified with curl, and curl does not implement CORS — so the whole suite,
including tests that spoke real HTTP over a real socket, was green against an API no browser could
reach.

Two faults, either of which alone was enough:

1. **No CORS policy existed anywhere in the codebase.** The console is a separate origin
   (`localhost:3001` in development, a Vercel domain in production) from the API on `:8082`, so the
   browser refused every request before it left the machine.
2. **The auth filter answered 401 to preflights.** A preflight is the browser asking _may I send
   this request, with an Authorization header?_ — so it carries no credential by definition.
   Demanding one answers "unauthorized" to the question "may I authenticate", which no client can
   recover from. Even with CORS configured this alone would have kept the console dark.

Fixed with a `CorsFilter` ordered **ahead** of authentication — load-bearing ordering, since
`CorsFilter` answers preflights itself and never calls the rest of the chain — plus a defensive
`CorsUtils.isPreFlightRequest` skip in the auth filter, because the failure mode if that ordering
ever changes is total and presents as "Failed to fetch" rather than as anything resembling an auth
problem. Origins are an explicit configured list and never a wildcard: this API answers with a
shop's takings.

One detail worth keeping: a **401 needs CORS headers as much as a 200 does**. Without them the
browser hides the response entirely, and the console shows "Failed to fetch" where it should show
"your session ended" — the difference between somebody signing in again and somebody reporting that
the app is broken. Asserted.

**The lesson, recorded because it will recur.** A test that speaks HTTP is not a test that speaks
browser. `TenantIsolationTest` and `ConsoleAuthTest` both drive real sockets and both passed
throughout. `ConsoleCorsTest` exists now to assert the handshake explicitly, because nothing else in
the suite can see it. 377 backend tests, was 371.

**Still unverified:** the terminal is also cross-origin — an Electron renderer on `:3000` calling
`:8081` — and has no CORS configuration either, yet rings up sales through the real window in the
e2e suite. It evidently works and the reason has not been established. Worth knowing before M5-01
changes how the renderer is served.

### M4-05 to M4-07 — the console, and the read side that did not exist

The task list says "build the console". The survey said something else: the cloud had **exactly one
endpoint**, `POST /api/sync/batch`. Everything ever built for the cloud was write-only. Three of the
four jobs behind "build the console" were server-side, and the phone app was the last and smallest.

**Owner login is a different credential from a till's, and had to be.** `V205` gave the cloud a
machine token: baked into a terminal at activation, never expires, nobody types it. Every property
of that is wrong for a person. `V208` adds `console_users` and `console_sessions` — an email, a
BCrypt password, a session that ends. Deliberately **not** the `users` table the tills sync up: that
table is written by an outbox, so reusing it would mean a shop PC could grant, revoke or rename
console access by pushing a row. The direction of trust runs the wrong way.

**The session token is opaque, where the till's is a JWT.** M3-09 needed a self-describing token
because its verifier works with the cable unplugged; its own header admits the result is "little
more than a signed session id". In the cloud the verifier is always beside the database, so a
signature buys nothing and costs revocation — and "sign out on my stolen phone" is the one thing an
owner will genuinely need. Asserted: signing out kills the token on the next request, and so does
deactivating the account.

**The two credential kinds are kept apart on purpose.** Both resolve to a tenant, so collapsing them
into one was the obvious simplification and the wrong one: a token soldered into a till would then
read the owner's whole business, and an owner's phone session could push sales into the shop's
ledger. Neither would error — the request would simply succeed. `AuthenticatedPrincipal` carries the
kind, endpoints require one, and the mismatch is a **403 rather than a 401** so a till told to
re-authenticate does not loop forever on the only token it has.

**A leak found while surveying.** Most controllers carried no `@Profile`, which makes them beans
under every profile — so the cloud instance mounted `SaleController`, `ProductController`,
`UserController` and nine others. They could only ever fail there, because everything they call goes
through `LocalShop`, which asserts a single-tenant database. A route that exists and always fails is
worse than one that does not exist: it is a promise in the URL space that somebody eventually tries
to keep. Twelve controllers are now `@Profile("desktop")`.

**Every read query takes a tenant and none of them finds one.** `ConsoleReportService` has no method
that can run without being told whose data it is — the shape that makes a cross-tenant leak possible
is simply absent. Nine of the sixteen tests in `ConsoleReportTest` exist only to assert that one
shop's figures never appear in another's, because that failure would look, on the screen, exactly
like the product working.

**Two details worth keeping.** The date boundary is computed in the shop's zone, passed in, never
from the JVM default — getting it wrong shows up as takings that reset at 5:30am, which is a bug
reported as "the app is broken" that nobody can reproduce. And the trend series is generated from a
date range and left-joined, so a closed Sunday is a zero rather than an absent row; a chart that
omits it draws a line straight through and says the opposite of what happened.

**On the phone.** One page, three tabs, no router — the whole dataset is a few kilobytes so it loads
at once. Bars are CSS, because fourteen of them is the entire requirement and every charting library
costs more than the screen does. The sync time sits **beside** the money and turns amber, because
this is a cloud reading an outbox and a till that stopped syncing at lunch would otherwise show a
plausible, quietly shrinking total all afternoon. There is a manifest so it installs, and
deliberately **no service worker**: caching takings would mean showing figures with nothing honest
to say about their age.

**Verified against the running cloud, not only in tests.** Signed in over HTTP on :8082, read
LKR 10,200.00 across two branches, saw a KND drawer 250.00 short in the attention feed, was refused
403 pushing a sale on a console session, signed out and was refused 401 reusing the token.

**The gap this leaves.** There is **no way to create an owner account** except from Java — no
endpoint, no seed, nothing. That is `M4-08`'s super-admin work and it is now the thing standing
between this and a shop that can actually use the console. The demo account in `lumora_cloud` was
inserted by hand.

371 backend tests (was 337), and the console has its own vitest suite for the first time.

### M5-09 — the gazette, read at last

The roadmap had said since 2026-08-20 that this field list came from secondary reporting and should
not be trusted. It should not have been: three of the things everybody "knew" about Gazette 2481/22
were wrong, and one of them would have made the till unusable.

**Sources.** The gazette PDF from `ird.gov.lk` and IRD Circular **SEC/2026/E/03** of 20 May 2026,
which is the department's own explanatory note on it. Both are extracted to text and every rule
below cites its clause, so the next person can check this against the document rather than against
this entry.

**What was wrong.**

_Purchaser details are conditional._ Gazette §3.1 lists the purchaser's TIN, name and address among
the required particulars and reads as though they are always needed. Circular §4.3 is the operative
wording: _"Where the purchaser is VAT-registered, the following must be stated"_. A consumer has no
TIN. Had the strict reading stood, every shopper would have had to produce a taxpayer number before
the queue could move, and the honest conclusion would have been that this product cannot be sold in
Sri Lanka.

_A tax invoice may carry only VAT-taxable supplies_ (§4.2, Circular §4.8). Exempt goods "should not
be included". A grocery basket mixes bread and arrack constantly, so a receipt cannot also be a tax
invoice without splitting half the shop's baskets across two documents. **This is what decided the
design**: the till prints its ordinary receipt for every sale, and the tax invoice is a separate
document issued on request, which is also what the Act actually requires and how shops already work.
The decision was put to the owner rather than assumed.

_Dates are MM/DD/YYYY_ — month first, in a country that writes them day first, specified twice in
§4.1(b) and (d) and repeated in the circular. It looks like a bug on sight, so `formatGazetteDate`
carries a test whose name says it is deliberate.

**The serial number, and why §A survived it.** §4.1(a) prescribes `YYMMM_QQQQ_XXXXX` — year, month,
an identifier, a numeric run — no spaces, forty characters. That could have been fatal to §A's third
rule: a nationally-formatted number sounds like a global sequence, and a global sequence needs the
network. It is not. `QQQQ` is explicitly free for "branches, sections, units", so each till carries
its own, its numeric run is independent, and a terminal keeps issuing legal invoice numbers with the
cable out. The separator is the one genuine ambiguity: the format string uses underscores and the
gazette's own worked example is `26JUL-BR03-1`. Read as notation the underscores mark field
boundaries, so the example wins and hyphens are used — a one-character change if that turns out
wrong, and what actually binds is the no-spaces/forty-character rule, which is enforced.

**What was built.** `V118` on the desktop: the supplier's TIN, registered name and address in
`tenant_settings`; TIN and address on `customers`; a third document sequence per terminal; and a
`tax_invoices` table that snapshots supplier and purchaser exactly the way `sales.tax_rate_bp` is
snapshotted, so a reprint reproduces the invoice that was issued rather than today's letterhead.
`V207` in the cloud is deliberately thinner — the ledger, not the document; a reprint must come from
the till that issued it.

**A conflation recorded rather than hidden.** "VAT-taxable" is implemented as `tax_rate_bp > 0`,
which treats zero-rated supplies as exempt. They are not: zero-rated is taxable at 0% and belongs on
a tax invoice. The schema carries a rate and no supply classification, so the distinction is not
available to make. It costs nothing in a retail shop, where zero-rated supply is essentially export,
and it would have to be fixed before this served an exporter.

**Refusals, on purpose.** A shop with no VAT registration details cannot issue one — an invoice
carrying a guessed TIN is filed by the purchaser and claimed against, which is worse than no invoice.
A sale with no taxable lines cannot be invoiced. A purchaser TIN with no name is refused. Issuing
twice returns the same document rather than creating a second one for one supply.

**Still not done.** The credit note in `zreport.ts` has none of this and arguably needs its own
treatment. `SEC/2026/E/03` §4.4 also offers an exemption from the `YYMMM_QQQQ_` prefix for anyone
who got Commissioner-General approval before 1 July 2026 to integrate with RAMIS by web API — the
date has passed, so it is not available, but it is the shape of the v4 e-invoicing work.

337 backend tests (was 315), 84 terminal (was 64).

### M4-01 — the tenant column that was isolating nothing

`M4` opened with a survey rather than code, and the survey was the useful part: `M4-02`, `M4-03`
and `M4-04` were already built. The cloud has been taking batches since `M0-07`, and every
milestone since added its aggregates to the same endpoint — per-item accept/reject has been there
from the start, and redelivery has been asserted for sales, movements, shifts and refunds all
along. They are ticked as found, not as newly written. One deviation from the wording: `M4-02` says
"per-aggregate ingest **endpoints**" and what exists is one endpoint dispatching on an `aggregate`
field. That is better and should stay — a mixed batch is one round trip and one transaction
boundary per item, where one endpoint per kind would mean the drain ordering its own aggregates.

What was **not** built was the thing the milestone is named for.

**The hole.** `V200` gave every synced table a `tenant_id`, which reads like multi-tenancy and was
not. The tenant arrived inside the request body as `tenantClientUuid`, and `upsertTenant` created
it on first sight. There was no authentication on the port at all. So a caller could name any
tenant — including one that already existed — and write into it. A `tenant_id` column that the
caller populates is not an isolation boundary; it is a label.

**Two halves, because closing one leaves the other.**

`V205` is the credential. A tenant is now provisioned, and a till presents a bearer token that
resolves to exactly one. The batch no longer carries a tenant **at all** — deliberately stronger
than validating the body's claim against the token, because a field that is checked in one place is
a field somebody later reads in another. The safest version of a dangerous input is the one that
does not exist. The token is hashed with SHA-256 rather than BCrypt: it is 256 bits of CSPRNG
output, so there is no guessing attack for a slow hash to frustrate, and BCrypt's per-row salt would
turn one index probe into a scan with a key-stretch on every row — on the path every batch takes.
Credentials are a table rather than a column so a stolen till can be cut off without re-keying the
ones that are fine.

`V206` is the one that was easy to miss. Every `client_uuid` unique index was **global**, and every
upsert used it as its ON CONFLICT target. Two tenants sending the same uuid collided, and the
collision resolved in the worst available direction: `INSERT … ON CONFLICT … RETURNING id` hands
back the existing row's id regardless of whose it is, so the second shop's sale was silently
absorbed into the first shop's and the till was told **accepted**. A sale that exists nowhere it can
be reported from, and no error anywhere. Random v4 uuids make that essentially impossible by
accident — which is precisely why it would never have been found except by somebody doing it on
purpose. It was also inconsistent with these tables' own habits: `sales` has been keyed
`(tenant_id, invoice_number)` since `V200` and `refunds` `(tenant_id, credit_note_number)` since
`V203`. Every natural key here was already tenant-scoped; the client uuid was the exception.
Idempotency is untouched — a redelivered batch arrives on the same credential, so the conflict still
fires.

**Not `spring-boot-starter-security`.** The same jar runs the shop PC under the `desktop` profile,
where `M3-09` already settled who may do what on a loopback API; the starter installs a filter chain
across the whole application and a second scheme fighting the first helps nobody. A plain
`OncePerRequestFilter` registered on `/api/*` under the cloud profile only, denying by default so
the next cloud endpoint is protected before it is written. `/actuator/**` stays outside the pattern:
a load balancer has to health-check the process without holding a shop's key (`M4-10`).

**An unactivated till queues rather than pushing as nobody.** The drain checks for a token before
opening a connection. Without that it would collect a 401 every ten seconds and back off, reaching
the same outcome by way of a log nobody could tell from a real outage.

**A test-only snag worth writing down.** The isolation suite drives real HTTP, because a filter is
exactly what a test calling the service directly cannot see. Five of its twelve tests failed on the
client side with `HttpRetryException: cannot retry due to server authentication, in streaming mode`
— the legacy `HttpURLConnection` meets a 401 on a request that had a body, tries to re-send it with
credentials, and cannot. Buffering the body does not help. `JdkClientHttpRequestFactory`
(`java.net.http.HttpClient`) has no such instinct and needs no new dependency. The 401 also gained
the `WWW-Authenticate: Bearer` header RFC 9110 requires, with no realm — a realm here would name the
tenant, which is the one thing an unauthenticated caller must not learn. Every rejection is
byte-identical: unknown token, revoked credential and suspended tenant are one response, asserted,
so the 401 cannot become an oracle for which shops exist.

315 backend tests, up from 301.

### M5-09 — the shop address and the customer line

Not the whole task, and it should not be read as one. Two fields off the gap list — the supplier's
address and a customer name — added to the printed receipt at the owner's request; the rest (TIN,
the `TAX INVOICE` title, the serial-number format, the invoice-date/supply-date split) is explicitly
still unchecked against Gazette **2481/22**.

The address wraps at word boundaries in `receipt.ts` rather than being left to the printer, which
breaks mid-word wherever the paper ends, and an explicit `
` gives a second line. On 58mm paper it
re-wraps to three lines by itself. The customer line prints **blank** when nobody is attached rather
than being omitted: a customer who asks for a tax invoice after the sale can write on the line,
where omitting it means reprinting the receipt. Both fields are required on `ReceiptData` and the
name is required-but-nullable — the same reasoning `M1-18` applied to `taxBreakdown`, that a caller
who has not thought about a compliance field should fail to compile. The shop address is still the
hardcoded placeholder sitting beside `STORE_NAME` and `BRANCH_CODE`, waiting on `M5-03`. The credit
note in `zreport.ts` did **not** get either field, and arguably should — a decision to make with the
rest of the gazette read.

### M3-13 — a cool-off, not a lockout

**The task is named "lockout" and this deliberately is not one.** A lock that a person has to be
released from is a denial of service anybody can trigger: type six wrong PINs at the owner's code
and the shop cannot authorise a refund until somebody who is not on the premises does something
about it. On a till that failure is worse than the one it prevents — brute force needs an attacker
standing at the counter for days, whereas a bored teenager can lock the owner out in fifteen seconds
and no skill at all. So the period escalates and then **ends by itself**: four free attempts, then a
wait doubling from five seconds and capped at two minutes, with the counter starting over after
fifteen quiet minutes.

The arithmetic is the point. M3-08 measured a four-digit PIN at about a quarter of an hour of
sustained hammering; at two attempts a minute, ten thousand combinations is several days of
continuous work at a keypad in somebody's shop. The cap matters too — an unbounded backoff
eventually _becomes_ the lockout this exists not to be.

**Keyed on the code that was typed, not on a user.** Two reasons, and the second settles it. A
user_id cannot be recorded for a code matching nobody, so throttling on the user leaves code
enumeration entirely unthrottled. And if a real code slowed down while an invented one did not, that
difference is an oracle for which codes exist — undoing all of V109's work to make a wrong code and
a wrong PIN indistinguishable, right down to running the BCrypt comparison against an unsatisfiable
hash so even the timings match. A test asserts an invented code is throttled identically.

**The message does not change on the attempt that trips it.** Announcing "that was your fifth" tells
an attacker exactly where the counter sits. The wait lands on the _next_ attempt, where it says
nothing they did not already know — and it applies to the correct PIN too, because somebody working
the keyspace is by definition going to type the right answer eventually.

**The counter had to survive the exception that carries it, and that is why `PinAttemptGuard` is its
own bean.** The failure path throws. An increment inside the authentication transaction would be
rolled back by that throw, and the throttle would count to one and stay there — a bug that passes
every test written against a single attempt and fails only the thing it exists to stop.
`Propagation.REQUIRES_NEW` commits it independently. It is a separate class rather than a private
method because Spring's proxy does not intercept self-invocation, so `REQUIRES_NEW` on a helper
inside `UserService` would silently do nothing whatsoever — a second way to get the same bug, with
no compiler or test to say so.

**Rows, not memory.** A process restart is free to an attacker standing at the machine the backend
runs on, so an in-memory counter is cleared by turning it off and on again. It is a table rather
than a column on `users` for the same reason the key is the typed code: there is no user row to hang
a wrong code off.

**The e2e suite had to stop poisoning itself.** Several specs type a wrong PIN on purpose, and the
counter spans fifteen minutes — so two runs in quick succession would start the second part-way into
a cooling-off period, and whichever spec then failed would vary with ordering. `global-setup` now
clears `pin_attempts` at the start of a run. Only the rate limit is reset, never a user or a PIN:
what is being forgotten is a limit this suite itself tripped.

**Verified against running software.** 301 backend tests (290 → 301) and **38 Playwright specs**
(37 → 38), including one that types five wrong PINs into the real Electron window and asserts the
shopkeeper is told to wait _and_ told that nobody has been locked out — because a shopkeeper who
believes they are locked out phones somebody about something that was going to clear itself in five
seconds. `V117` applied cleanly to `lumora_local`.

### M3-12 — the whole row, and the aggregate that was already there

**`movement` was already covered, and adding it would have been a data-corruption bug.** The task
lists four aggregates; three needed building. Every stock movement already travels inside the
document that caused it — a sale, a refund, a goods receipt, an adjustment, a stocktake — because
M1-15 and M3-04 put it there. A separate `movement` aggregate would deliver each movement twice, and
on a table whose entire meaning is `Σ qty_delta` that does not produce a duplicate row to clean up
later; it doubles the shop's stock. So `OutboxCoverageTest` **audits** the coverage instead: it
exercises the write paths and then asserts that no movement written during the test is absent from
the outbox. That catches the failure that actually happens — a new movement writer added later with
no sync — without pretending to enumerate paths a future change might add.

**The whole row every time, not a diff.** Sales are immutable, so V200's upsert is a deliberate
no-op. A shift changes exactly once and only forwards, so V203's is monotonic. Products, users and
customers change whenever the shop says so, and they are the first aggregates with **no state
machine at all**. Shipping the entire state rather than the change is what keeps the two properties
this architecture rests on: redelivery is still free, and arrival order still does not matter. A diff
stream would make delivery order load-bearing, and an offline shop's backlog has no order worth
relying on. Two outbox rows for one product is therefore correct rather than a duplicate — each is
the state at the moment it was written, and whichever lands second stands.

**Barcodes replace rather than merge.** The list that arrives is authoritative and the ingest deletes
what is not in it. A merge is the obvious implementation and cannot express a removal, which leaves
the cloud answering to a barcode the shop has since reassigned to something else.

**The cloud has nowhere to put a PIN hash, and that is asserted against the schema.** `users` on the
cloud carries code, name, role and active — no credential column exists, so a build that started
shipping one would fail loudly instead of quietly storing it. The test checks
`information_schema.columns` for `pin_hash`, `pin`, `password` and `password_hash`, and a second
test checks the raw outbox JSON contains no `$2a$` and no PIN. This is the same principle M3-09 rests
on: the till authenticates entirely locally precisely so the cloud never holds anything worth
stealing.

**Resetting a PIN enqueues nothing, deliberately.** Nothing the cloud stores about that person has
changed, so a row would ship state whose only difference is invisible to the receiver — and it would
put a PIN change on a queue, which is one careless payload edit away from putting the PIN on it.

**Email and note stay on the till.** Neither has a reader in the console, PDPA (M5-10) is coming, and
a column shipped only because something might one day read it is personal data copied into a second
jurisdiction for a reason nobody wrote down. Adding either later is one line; un-copying it is not.

**The sale names its customer by uuid, and does not wait for them.** `sales.customer_client_uuid` on
the cloud is not a foreign key, for the same reason `shift_client_uuid` is not: a backlog drains in
whatever order the retries allow, and an FK would reject the sale and back it off until the customer
happened to succeed. A test ingests the sale **first** and then the customer, and asserts the join
resolves afterwards.

**M3-11's debt is paid.** `CustomerService`'s class comment said an outbox row without an ingest case
is worse than none — rejected, backed off forever, symptom reading like a sync bug — and that M3-12
was the very next task. It was, and that comment now describes what exists rather than what is owed.

**Verified against running software.** 290 backend tests (274 → 290) and **37 Playwright specs**
(unchanged — this milestone is entirely below the UI). `V204` applied cleanly to `lumora_cloud`.

### M3-11 — the phone number is the record

**A shop asks "number?", so the number is the key.** Not an email, not a card, not a surname:
`customers.phone` is unique per tenant, it is what the till searches as a prefix, and it is
normalised to digits before anything is done with it. That last part is the whole migration: one
person will be typed as `077 123 4567`, `0771234567` and `077-123-4567` across three visits, and a
column that keeps them as typed is three rows for one customer with a unique index that never fires
— which looks like working software right up until somebody asks what a regular has spent.

**And the uniqueness is partial, because a shop has customers it has no number for.** A walk-in who
wants their name on an invoice for the office is a real customer with nothing to look them up by. A
plain unique index would allow exactly one of those per shop, and the workaround staff would find is
typing `0000000` — which collides on the second one, and is a worse record than no record.

**Normalisation stops at digits, on purpose.** `+94 77 123 4567` becomes `94771234567`, not
`0771234567`. Turning a country code into a leading zero is a rule about Sri Lankan numbering that
would be wrong for the first shop with an overseas customer, and a lookup that quietly rewrites what
was typed is worse than one that finds nothing and lets the shopkeeper try again.

**Attaching a customer changes none of the money, and a test says so.** No price moves, no discount
appears, no total changes — the assertion compares the same basket rung up anonymously and rung up
for somebody. This is a v1 decision rather than an oversight: the moment a customer can change what
is charged, a mis-tap at the till stops being a wrong name on a receipt and becomes a pricing error,
and the receipt and the report have to agree about _which_ customer before either can be trusted.
Loyalty and credit are movement tables hanging off this one, and they arrive with the question of
what they are allowed to move.

**"Not on file" is a two-key answer.** The commonest moment F6 exists for is a customer nobody has
recorded. If the search finds nobody, the same overlay offers to save the number just typed with a
name — because the alternative is a cashier who learns that F6 usually fails, stops pressing it, and
a customer list that never fills up. The `created_by` for a till-created customer is whoever opened
the shift, which is exactly who is stood at the keypad and the same attribution every sale on that
shift already carries.

**The two doors are gated differently, and that asymmetry is asserted.** `/api/customers` — search
and add — is ungated, exactly like the product lookup and the sale commit beside it: the sales screen
has no session in v1, and inventing one only for this would mean a cashier types a PIN to write down
a phone number. `/api/back-office/customers/**` needs a `BACK_OFFICE` session, because a purchase
history is the one genuinely private thing here and the one thing a till never needs. An e2e spec
asserts both halves, because the natural tidy-up is to gate them the same way.

**Deliberately no outbox row, and the comment in `CustomerService` is the debt.** §A rule 1 wants the
sync record in the same transaction as the domain rows, and M3-04 honoured that by teaching the cloud
a `goods_receipt` case in the same commit. This does not, because there is no `customer` case in
`SyncIngestService` and no cloud table behind it — and an outbox row for an aggregate the ingest does
not know is _worse_ than none: rejected, backed off forever, till looks fine, and the symptom reads
like a bug in the sync loop. `M3-12` is the very next task and adds the aggregate, the ingest case
and the cloud table together.

**Seventeen positional call sites, and no compatibility constructor.** `CreateSaleRequest` gained a
nullable `customerClientUuid` as its last component, which broke every `new CreateSaleRequest(...)`
in the test suite. The tempting fix is an overloaded constructor that fills in `null`; it was
refused for the same reason `OperatorGate` was deleted rather than deprecated — two ways to
construct one thing is how one of them stops being maintained. The call sites now pass `null`
explicitly, which is also what they mean.

**A Maven trap worth knowing.** `./mvnw test-compile` after changing only main sources reported
BUILD SUCCESS with seventeen call sites that could not possibly compile: the incremental check saw no
changed test sources and skipped them, leaving stale `.class` files. `./mvnw clean test-compile`
showed the real errors immediately. If a signature change looks like it compiled, it did not.

**Verified against running software.** 274 backend tests (261 → 274) and **37 Playwright specs**
(35 → 37), including one that presses F6 mid-sale in the real Electron window, records a customer
who was not on file, tenders, and then asserts the _sale row_ names them — and that the next sale is
anonymous again, because a cleared cart that still remembered a customer would quietly file the
following sale under whoever came before it. `V116` applied cleanly to `lumora_local`.

### M3-10 — the reports, and the one that was already built

**No migration.** Every figure is a read over rows that already exist, and the two indexes the day
window needs — `ix_sales_sold_at` and `ix_refunds_refunded_at` — have been there since V100 and V108.
Worth stating because the reflex on a reporting task is a rollup table, and a rollup is a stored
figure a write path can forget to update, which §A rules out for exactly the reason M3-07 rediscovered.

**A "day" is the shop PC's day.** There is no timezone column and there should not be one: the
desktop backend runs on the counter, so the machine's clock _is_ the shop's clock, and asking a
shopkeeper to configure a timezone for the room they are standing in is a setting with one correct
value and one way to get it wrong. The boundary is computed once in Java and passed as absolute
instants, so the SQL never has to agree about time with anything. The seam is named where it moves:
M4-05 reads the same rows for a shop the cloud is not standing in, and that is when a branch needs a
stored zone.

**Half-open, and a test that would have caught the obvious alternative.** `>= from AND < to`, so a
sale at 23:59:59 belongs to one day and one at midnight to the next. `BETWEEN` is inclusive at both
ends and double-counts midnight — invisible on any day that happens not to have a midnight sale, and
found in April as a total that is off by one sale with no way to work out which.

**Refunds are subtracted, never netted into the sale.** Gross, returns and net are three figures on
every screen. Folding a return into the sale it came from makes a day with ten sales and ten returns
look like a quiet day, and the difference between those two days is the whole reason anybody reads a
report. Top products is ranked on units that _stayed_ sold for the same reason: a line sold ten times
and returned nine is not a line worth reordering, and a ranking that says otherwise gets acted on.

**Stock on hand was already built, so it is linked and not redrawn.** The task names it as a report;
M3-07 shipped it as a view, a service and a screen. A second presentation would be two renderings of
one number, and the day they disagree the owner has no way to tell which is right — the same argument
that made the rollup a view. The Reports tab therefore carries a button that opens the real screen,
and `StockScreen` gained an `openOnHand` prop so the button does what its label says instead of
dropping the reader on the Stock screen to hunt for it. An e2e spec asserts this, because a second
stock table under Reports is exactly what the next person would helpfully add.

**Gated, where the Z-report deliberately is not.** These endpoints need a `BACK_OFFICE` session
(M3-09). `ZReportController` still needs none, and that asymmetry is on purpose: the till prints a
Z-report at the moment the drawer is counted, from a screen a cashier is standing at, and a
back-office session in front of that would mean a cashier cannot close their own till. The shift
history is the opposite case — somebody sitting down to read the shop's takings. Open shifts are
excluded from it for a third reason again: every row carries `expectedCashMinor`, and listing an open
shift would hand the person about to count the drawer the exact figure M2-02's blind count exists to
keep from them, through a door the cash-up flow cannot see.

**Two mistakes worth the next person's time.** `both` is a reserved word in Postgres — it appears in
`trim(both …)` — so a subquery aliased `both` is a syntax error and the message points at the line
after it. And the test that gives each case its own historic day originally handed them out
consecutively, which made one test's sales the next test's midnight; it failed as a broken half-open
interval, which is the wrong thing to go and fix. Days are now handed out five at a time.

**Verified against running software.** 261 backend tests (251 → 261) and **35 Playwright specs**
(32 → 35), including one that rings a real sale through the Electron window and then asserts the
report shows today's takings to the cent — the one place a minor-unit slip between the domain, the
commit and the report would show up, and where no unit test on either side would see it.

### M3-09 — the PIN stops travelling

**What was actually wrong.** M3-08 shipped `OperatorGate` and said so in its own class comment: the
back office held the operator's code and PIN in memory for as long as the screen was open and
replayed both on every save, because the backend re-authenticated each request. Every request
carried a credential that unlocks the shop, and the mitigations — loopback binding, the till's own
process — are all properties of the deployment rather than of the design. This deletes it. The PIN
now goes to `POST /api/auth/session` exactly once and nothing in either process can reproduce it
afterwards.

**The token carries who, and nothing else.** `sub` and `jti`, no role and no permission list. The
temptation is obvious — put the claims in and skip a query — and it is the wrong trade here: a claim
is a snapshot, and a snapshot of a permission goes on working after the permission is taken away.
Deactivate somebody mid-shift, or demote them, and a self-contained token would keep letting them
through until it expired. So every verification re-reads the user, and `SessionTest` asserts the
_absence_ of `MANAGER` and `BACK_OFFICE` from the decoded token, because the optimisation that
breaks this looks harmless in review.

**Which makes it a signed session id, and that is the honest description.** Statelessness earns its
keep when the verifier cannot reach the issuer. Here they are the same process talking to a Postgres
on the same machine, so it buys nothing and costs revocation: signing out would not sign anything
out, and `active = false` would not bite until the token expired — at exactly the moment a shop most
wants it to. `sessions` is therefore a real table, and "sign out" revokes the row rather than hoping.

**The key is a row, not a config value.** A key in `application.yml` is the same key in every copy of
this build, so a token minted on one shop's PC would verify on another's; a key in an environment
variable is a key somebody has to set, and the failure mode of that is a default nobody changed. 32
bytes from `SecureRandom`, generated by this machine the first time it is needed, never transmitted.
M5-03's first-run wizard will provision it at activation — the same act at a tidier moment — and
creating it lazily now means a till that upgrades into M3-09 works on its next boot rather than
needing re-activation. Tokens name their key in `kid`, so a rotation does not sign the shop out
mid-shift; without that, rotation empties every screen at once, which is how a shop learns not to
rotate.

**JJWT rather than sixty lines of `Mac` and Base64url.** Hand-rolling looked tempting — the format is
simple and it avoids a dependency on an offline machine. It was refused because hand-rolled JWT
verification is precisely where alg-confusion bugs live, and "we only ever accept HS256" is a
sentence every vulnerable implementation also contained. The alg is still pinned twice over and both
paths have a test: a token signed with another key, and an `alg: none` token naming a real session.

**Two e2e failures, and only one of them was this change.** Both were latent locator fragility that a
few milliseconds of changed timing exposed. `getByText('Kumari Perera')` matched the header line
_and_ her row in the users list — one element or two depending on which fetch landed first.
`getByLabel('Contact')` matched the new-supplier field plus every existing supplier's "Contact for
&lt;name&gt;". Neither was an auth defect; both are now exact. Worth recording because the first
instinct on a red suite after an auth change is to look at the auth.

**Escape does not leave the back office once signed in.** Found while writing the sign-out spec: the
keyboard handler is registered only while signed _out_, so the advertised "Esc back to the till"
applies to the sign-in screen alone and the shell's only way out is the button. Left as it is —
M3-09 is not the task that redesigns the shell's keyboard map — but recorded rather than quietly
worked around, because the spec had to use the button and the next person will wonder why.

**Verified against running software.** 251 backend tests (234 → 251) and **32 Playwright specs**
(30 → 32), including one that signs in through the real window and then reads `sessions` to assert
that exactly one row was opened, that browsing more screens opens no more, and that leaving revokes
it — because nothing on screen distinguishes a real session from a held credential, which is the
whole reason the change was worth making. `V115` applied cleanly to `lumora_local`.

### M3-07 — the rollup that turned out to be a view and an index

**The task said "indexed rollup" and the parenthetical decided its shape.** _Never a stored balance
column anyone updates._ So `stock_on_hand` is a **plain view**: `sum(qty_delta) GROUP BY tenant,
branch, product`. It is not cached, cannot be stale, has no refresh step to forget, and cannot drift
from the movements because it is not a copy of them. Two alternatives were considered and both fail
that test — a summary table maintained by application code is the exact thing §A forbids, and one
maintained by a trigger drifts silently the moment anyone loads data with triggers disabled (a
restore, a migration, a support fix). A materialised view was rejected too: Postgres cannot refresh
one incrementally, so it pays the full scan cost _and_ is stale in between, which for a shelf figure
somebody is reading is the worst of both.

**The speed comes from a covering index, which makes the honest query fast rather than replacing it
with a faster lie.** V100 already indexed `(tenant_id, branch_id, product_id)`; V114 drops that and
recreates it with `INCLUDE (qty_delta)` so summing never touches the heap. The old one is dropped
rather than left alongside because it is a strict prefix of the new one and can answer nothing the
new one cannot — and on a till the write path is what matters: every line of every sale inserts a
movement, so a redundant index is a cost paid on every item sold.

**Asserting the plan took three attempts, and the first two failures were the planner being right.**
Worth recording, because the instinct is to assume the index is broken.

1. 500 movements → `Seq Scan`. Correct: a table that fits in two pages is cheaper to read whole.
2. 20,000 movements, all one product → `Seq Scan`. Also correct: every row matched the filter, so
   an index only adds work.
3. 20,000 movements across 400 products — roughly a year of a small shop, and the shape the index
   actually exists for — → the index wins.

The test also deliberately does **not** assert `Index Only Scan`. That needs the visibility map,
which is set by VACUUM, and these rows live in a transaction that is rolled back — they have never
been vacuumed and never will be. On a real till, with committed movements and autovacuum done, the
same query is index-only. What is asserted instead is the thing that would actually regress: the
index is reached rather than the table read, plus a separate check that `indexdef` still carries
`INCLUDE (qty_delta)`, which is what a careless merge would drop.

**The M0 guard caught the new view, which is the guard working.** `MinimalSchemaTest.
noTableStoresAStockLevel` scans `information_schema.columns`, which includes a view's columns, so
`stock_on_hand.qty_on_hand` — the literal sum the rule asks for — failed it. Narrowed to
`table_type = 'BASE TABLE'`, and the narrowing does not weaken it: the thing being guarded against is
a stored figure a write path must remember to update, and a view has no storage to forget. A
materialised view would still be caught, deliberately. A second test now also asserts the view
_exists_, so deleting it cannot make the guard pass more easily — a rule should not fail open.

**Three copies of the sum became one.** `StockAdjustmentService.onHand` held its own
`SELECT sum(qty_delta)` with a comment saying it was waiting for this task; it now delegates. The
service is the only Java that reads the view, and the view is the only definition of the
calculation.

**A product that never moved is zero, not missing.** The view says nothing about it, which is
correct — nothing happened — and turning that into a zero is presentation, done in the service's
LEFT JOIN rather than in a view that would then have to know what a product is. The screen keeps the
distinction: _never stocked_ and _out of stock_ are different sentences, and only one of them is a
problem.

**Verified against running software.** 234 backend tests (221 → 234) and **30 Playwright specs**
(28 → 30), including one that inserts a movement straight into the database, reopens the panel, and
finds the figure already changed — there being no refresh button because there is nothing to
refresh. `V114` applied cleanly to `lumora_local`.

### M2 — the accountability layer

**Where the money math lives, and where the rules do.** M2 kept the split M1 established and it
paid off twice. `@lumora/domain` gained `cashup.ts` (denomination counts, expected cash, variance)
and `refund.ts` (partial-return apportionment, refund tender rules); the backend recomputes none of
it. What the backend does instead is enforce the things arithmetic cannot — that the sale exists,
that a manager allowed it, that nothing goes back twice, that money goes back the way it came.
Those are all **caps**: comparisons against figures the sale already stores. That is precisely why
they are enforceable server-side without a second money implementation, and it is what stops the
till's UI being the only gate.

The one place the two tiers compute the same thing is the addition in `expectedCashMinor`. The
terms are SQL aggregates only a database can produce, and shipping them to the terminal to be
summed would put the network on the critical path of closing a till. A fold over figures the domain
already produced is not the duplication §A warns about; VAT, rounding and apportionment still exist
once.

**Blindness is a shape, not a rule.** The whole of M2-02 comes down to `ShiftStatusResponse` having
no expected-cash field. A rule that says "do not show this" survives exactly until the next
refactor passes one more prop; a response type with nothing to show survives because there is
nothing to pass. `ZReportService` refuses an open shift for the same reason, and the e2e suite
watches every API response the Electron window receives rather than what the screen renders. If
somebody ever adds the figure "just for a manager view", three tests fail and one of them explains
why.

**Signs live on the kind, not on the typist.** `cash_movements.amount_minor` is signed, with a
CHECK tying the sign to `kind`, so expected cash is a plain `SUM` with no `CASE` and a pay-out that
adds to the drawer is unrepresentable. The cashier types a magnitude; the screen shows them the
effect before they commit. A cashier who has to remember a minus sign will one day forget, and the
drawer then reconciles to a figure wrong by twice the amount.

**Amounts on a refund are magnitudes; the sign appears once, in the movements.** The `refunds`
tables store what was given back and the table itself is the direction — the opposite convention
from `cash_movements`, deliberately, because there the sign varies row to row and here it cannot.
The single place a refund becomes signed is `stock_movements`, where a restocked unit is a positive
`qty_delta` exactly as the sale wrote a negative one, so stock on hand goes on being Σ entries with
no special case for returns. `restock` is per line and not per document, because a customer
returning one damaged item and one unwanted one in the same visit is one refund with two different
outcomes: the damaged one gets the money back and writes no movement, since telling an owner they
hold stock they cannot sell is worse than not counting it.

**What is deliberately still missing.** There is no free-text variance note on the till — the
reason picker is the whole note in v1, because a text field on a till keyboard is a field nobody
fills in, and `OTHER` is not offered at close for exactly that reason. There is no HTTP endpoint
that sets the manager PIN: an unauthenticated loopback API able to set the credential guarding
refunds would be a gate with a handle on the outside. Both arrive with M3-08's real users.

**Gate M2 has not been attempted.** Every rule it names is asserted by tests, and the e2e suite
makes the attempt cheap, but the gate is a statement about what the software _refuses_ and a person
has to try to break it — with a card receipt in one hand and a drawer of cash in the other. The
same is true of Gate M1, which is still outstanding.

### M1-16 — proving the mouse is optional

**Electron, not Chromium.** The spec launches the real application. The alternative — a browser
page against the dev server — tests the renderer and nothing else, and the till is not a
renderer: it is a main process that owns the printer, a preload bridge, a single-instance lock
and a window that has already failed to open once in this project's history. Gate M1 is
executed against that window, and a spec proving the keyboard path somewhere else proves it
about somewhere else. This is the same judgement as Postgres-over-H2 and compose-over-
Testcontainers, applied one layer up.

**What the suite is for.** It cannot tick Gate M1 and does not try to. The gate is twenty
consecutive sales _by a person_, and a person is what finds the things a script structurally
cannot — that a key is under the wrong finger, that the eye has to hunt for the total, that
the overlay feels like it stole focus even where the DOM says it did not. What the suite holds
is the mechanical half: that the whole sale path is reachable with keystrokes alone, and that
none of it quietly starts requiring a pointer. That makes the gate cheap to attempt and hard
to regress, which is the useful division of labour between a test and a human.

**Recording pointer events rather than avoiding clicks.** The obvious implementation — never
call `page.click()` and declare victory — asserts something about the test, not the product.
Instead every pointer-shaped event is captured on `window` in the capture phase (so nothing
the app does with `stopPropagation` can hide it) and each spec ends by asserting the list is
empty. The regression this actually catches is someone adding a control that only a mouse can
reach, and the keyboard path silently ceasing to cover the whole sale.

One subtlety for whoever touches this next: a focused `<button>` activated with Enter _does_
fire a `click`, with `detail === 0`. Zero-of-any-kind is currently true because the till drives
everything through document-level key handlers, and it is the stricter assertion, so it stays.
If a focused-button control ever arrives deliberately, the assertion worth keeping is
`detail === 0` — that is the real boundary between a keyboard activation and a hand leaving the
keyboard.

**One spec exists to keep the other five honest.** Every keyboard spec ends by asserting the
recorded pointer events are empty — an assertion whose failure mode is silence. If the recorder
ever stopped being installed, they would all keep passing while proving nothing, and a
mouse-only control could walk straight in. So one spec clicks a button with the mouse and
requires the detector to notice. (It clicks the theme toggle, the only button on the screen
that is never disabled: a disabled button dispatches no click, which would have made the guard
exactly as vacuous as the thing it guards against — found by writing it against F12 first.)

**The receipt is now asserted from a sale that was rung up.** The suite runs a fake TCP printer
on an ephemeral port and points the app at it, so `buildReceipt` is exercised from real cart
totals rather than a fixture. That is what closes the gap M1-18 left open: the mixed-rate
basket now goes in through the scan field, out through the VAT summary block on the paper, and
is checked per line in the database on the way past.

**It writes to `lumora_local`, and cleans up after itself.** There is no separate e2e database,
because inventing one would mean the suite no longer exercised the database the app runs
against. So the run records `max(sales.id)` before it starts and deletes only above that line;
anything a developer rang up by hand is outside the range and survives. `invoice_counters` is
left advanced on purpose — a terminal's issued block is not reusable, and a suite that rewound
it would be rehearsing the exact thing per-terminal numbering exists to prevent.

**Two false starts, and one wrong turn that is worth more than the fix.** The suite was
intermittently red, and the first hypothesis was a real-sounding focus race: the scan field is
disabled while the tender overlay is up, disabling an input drops focus to `<body>`, and a
passive `useEffect` restores it only after paint — so a gun firing a character every 10ms into
that window would clip its leading digits, and the till would report "No product for barcode
791234567890", which reads as a bad barcode rather than a lost keystroke. Plausible, matched
the symptom, and `ScanField` was duly changed to a layout effect.

It was wrong. A MutationObserver on the input's `disabled` attribute — which fires as a
microtask, strictly before a passive effect would run — showed focus was already back on the
field at that point. There is no enabled-but-unfocused window. The change was reverted rather
than kept: a fix that cannot be shown to fix anything, carrying a comment that says it does, is
worse than no change at all, because the next person reads the comment as evidence.

The actual causes were both in the test. `next dev` compiles routes on demand, so the first run
of a suite raced a webpack build that later runs did not — a suite about keystroke timing
cannot be built on that, so it now runs a production build. And `getByText(name).first()`
matched several nested elements inside a cart cell that also carries the SKU, taking whichever
the DOM ordered first; it failed while the cart was plainly on screen in the failure snapshot.
Scoped to `getByRole('row')`, six consecutive full-suite runs are green.

### M1-18 — one rate per basket was never true

**The bug was visible from M1-07 and shipped anyway, correctly.** The till refused a cart
mixing an exempt line with a standard-rated one rather than pricing the exempt line at 18%.
That was the right call at the time and it is worth being explicit about why: refusing a
sale is a bad day for one shopkeeper, and quietly overcharging VAT on bread is a bad year
for the business. But "refuses" is not "works", and a grocer cannot sell bread and arrack on
one docket — which is most of what a Sri Lankan corner shop does all day.

**What made it small was where the tax lives.** `cartTotals` normalises every line to gross
in its first pass and never branches on tax mode again; after that step a cart is a list of
gross amounts and rate matters only at the moment of extraction. So the change was to gross
each line up under _its own_ stamp rather than the cart's — one line of the existing pass —
and everything downstream, apportionment included, was already rate-agnostic. The
alternative shape, threading a rate through the discount arithmetic, would have put a tax
branch inside the part of that file that is hardest to get right.

**The sale-level stamp did not go away, and should not.** `sales.tax_mode` / `tax_rate_bp`
now mean the cart's **default** — what a line inherits when it carries none. That is not a
summary of the lines and must not be read as one: on a mixed basket it is simply the first
product's rate. The authority is `sale_items`, and the per-rate figures a tax invoice needs
are a `GROUP BY` over them. Keeping the column meant no nullable widening, no reader
handling a null that never occurs, and M1-05's guarantee that a historical receipt reprints
under the rate it was issued with is untouched.

**`Σ line.taxMinor == taxMinor` went from true to load-bearing.** With one rate, a sale's
tax could be recomputed from the total; with several there is no single rate to recompute it
from, and the lines are the only thing that can say what the tax was. The backend now checks
it. That check immediately caught a test fixture that had been extracting VAT from the
subtotal instead of summing the lines — 19,220 against 19,219 — meaning a fixture asserting
the backend agreed with the terminal had disagreed with it by a cent since M1-15, and
nothing failed. Extraction truncates; extracting once from a group is not extracting from
each of its parts. That is also why `taxBreakdown` sums the lines rather than re-extracting
from each group's gross.

**The receipt gained a net line it should have had all along.** A tax invoice has to state
the amount excluding tax, the tax, and the total as three separate figures. The receipt
printed the VAT and the total and left the net to be inferred by subtraction — and under an
inclusive regime the net appears on no other document, so if the receipt does not say it,
nothing does. Single-rate sales now print `Net (excl. VAT)`; mixed sales print a `VAT
SUMMARY` table, one row per rate, with a totals row. On a 58mm roll the **Gross** column is
dropped rather than wrapped: it is Net + VAT, both already in the row, so it is the only
column a reader can reconstruct. The rate is printed even when it is zero — "0%" says the
line was considered and found exempt, where a blank is an omission and the two look
identical afterwards.

**Backward compatibility is deliberate, and is not only about history.** A line may send no
stamp at all, and inherits the sale's. Before M1-18 a cart could hold only one rate, so the
sale's stamp is not a fallback for such a payload — it is precisely what that till meant.
The same reasoning as M1-15's movements: a shop's backlog must never need a terminal upgrade
before it can reach the cloud. Sending half a stamp is rejected instead, because inheriting
the missing half would silently pair one sale's mode with another line's rate.

**The dev seed had no zero-rated product.** Every seeded item was 18%, so no cart on this
machine could mix rates and the refusal path never ran outside its tests — which is part of
how the gap survived from M1-07 to now. `BREAD-450` at 0% is now seeded.

### M1-15 — the movement that never left the building

**The local half was never the risk.** A `SALE` movement has been written in the sale's own
transaction since M0-04, and three tests already covered it: the negative delta, the rollback
leaving no orphan, and a retry not moving stock twice. Reading only that, the task looks done.

**The gap was one table further on.** The cloud schema has had a `stock_movements` table since
`V200`, complete with the unique index on `client_uuid` that the whole sync design turns on. Nothing
ever inserted into it. `SyncIngestService` handled tenants, sales, sale items and payments, and the
sale payload it read from had no `movements` key to handle. So the shop PC knew its stock and the
cloud did not, and every test passed — because each half was consistent with itself.

**The uuid has to be minted at the till, not at the far end.** This is the part worth remembering.
The obvious cloud-side implementation derives a movement from each sale line, which works perfectly
until a batch is redelivered: a movement invented at the far end gets a new uuid every time, so the
`ON CONFLICT` guard never fires, and on-hand — being `Σ qty_delta` — walks down by one sale's worth
per retry. Nothing errors. Nothing looks duplicated in the UI. The number is just quietly wrong, and
"movements, not balances" is precisely the rule that stops anyone noticing, because there is no
level column to disagree with. So `SaleService` now generates the uuid once, writes it locally, and
puts that same value in the payload. The cloud upserts on the till's key or it is not idempotent.

**Movements are ingested before the sale's immutability check, not after.** `upsertSale` returns
early when the sale already has lines, on the reasoning that a rung-up sale cannot change. Movements
sit above that return: they carry their own idempotency key so they do not need the guard, and
putting them first means a sale ingested by a build older than this one can still have its movements
backfilled the next time the shop resends. They also carry no `sale_id` in the cloud — a movement
there is a fact about a product at a branch at a time, and returns (`M2-10`) and goods receipts
(`M3-04`) will write the same table with no sale to hang from.

**A till older than M1-15 still ingests.** A payload with no `movements` key is accepted and simply
moves no stock, the same tolerance the M1-11 tender fields got. Rejecting it would strand every sale
queued behind it in that shop's outbox — a compatibility break that costs the shop its whole day's
sync, to protect a number the old till was never sending anyway.

### M1-13 / M1-14 — the receipt leaves the process

**The split that made both halves testable: content vs. transport.** `receipt.ts` knows a sale
and produces bytes; it has never heard of a socket. `printerTransport.cjs` knows how to move
bytes to a printer; it has never heard of VAT or a tender line. Neither depends on hardware to
test — `receipt.ts` is asserted byte-by-byte, and `TcpPrinterTransport` is proven against a real
`net.createServer` standing in for the printer. The only thing that could not be exercised without
real hardware was the last few centimetres: a physical device actually consuming the bytes.

**TCP won the transport choice by being the one thing in this environment that could be proven.**
The roadmap says "serial/USB," and that is still where `SerialPrinterTransport` points — but this
machine has no MSVC toolchain to build `serialport` from source, no Electron-native-rebuild
tooling set up yet, and no physical printer to confirm a build would even matter. Rather than
guess at an architecture decision with real hardware implications, the choice was surfaced and a
`PrinterTransport` interface built so the answer is a config value
(`LUMORA_PRINTER_TRANSPORT=tcp|serial`), not a rewrite: TCP (RAW/JetDirect, port 9100 — the de
facto standard for network-capable thermal printers) is the default and the one this session could
verify start to finish; `serialport` is an `optionalDependency`, lazily required only if serial is
selected, so a machine where its native binding cannot install still gets a working app.

**`ELECTRON_RUN_AS_NODE` was set in this shell, and it silently changes what `require('electron')`
returns.** With it set, `require('electron')` resolves to the path of the Electron binary — a
plain string — instead of the API object, so `ipcMain`, `app`, and everything else destructured
from it become `undefined` with no warning at import time; the failure only surfaces later, at
first use, as `Cannot read properties of undefined`. Nothing about the failure says "wrong
environment variable." Clearing it (`env -u ELECTRON_RUN_AS_NODE`) for the launch was what got a
real main process — and a real `ipcMain` — for the first time this session.

**Verified by driving the real bridge, not by inference.** Electron was launched with
`--remote-debugging-port`, and `window.lumora.printer.print(...)` was called directly over CDP —
the same function `page.tsx` calls after a sale, just invoked from the DevTools protocol instead
of a keystroke. The bytes sent were `ESC @ "PING\n" GS V 1`; the fake TCP printer received exactly
`1b 40 50 49 4e 47 0a 1d 56 01`. That proves the preload bridge, the IPC round trip, the main
process handler and the transport all agree on the same bytes — everything this milestone added,
exercised together, for real. What it does not prove: `SerialPrinterTransport` against an actual
device (still unverified, on purpose — see above), and a full cashier-keystrokes-to-printed-paper
pass through the UI, which needs either a human at a real till or the Playwright spec M1-16 has not
landed yet.

**The Electron-main-build-step comment is now deliberately stale.** An earlier note said M1-14 was
"the moment to add compilation" to `main.cjs`, expecting TypeScript would be the way to get real,
tested logic into the main process. `printerTransport.cjs` gets there a different way: plain
CommonJS, tested directly with vitest (Node needs no transpilation to run CommonJS). That was a
smaller, more contained piece of work than standing up a TypeScript-for-Electron-main build
pipeline as a second project alongside the printer transport, and it fully satisfies what the
original comment actually wanted — real logic, genuinely tested — without expanding this
milestone's scope into build tooling nobody asked for.

### M1-12 — invoice numbers get an edge

**Still zero-setup — a terminal's first sale provisions its own block, same as before.** The V101
counter auto-created its row on first use with no admin step, and that stays true: what changed is
that the row it creates now carries `range_start`/`range_end` (1..999,999 by default) instead of
counting up with nothing capping it. A single till will not realistically exhaust that in its
lifetime; the edge exists for the failure mode that matters, not for day-to-day allocation.

**The point is the boundary, not the width.** Before M1-12, `next_seq` could climb forever with
nothing recording that a range had ever been a deliberate decision — fine while no real invoice
exists, expensive to retrofit once the IRD format (hard date April 2026) makes the sequence
something an auditor relies on. Now allocation is a single atomic `UPDATE ... WHERE next_seq <=
range_end`: once a block's `next_seq` passes its own `range_end`, the row simply does not update,
`RETURNING` yields nothing, and that empty result becomes a clear `SaleRejectedException` (422,
"Invoice block exhausted for terminal X") instead of the counter climbing past whatever the block
was ever meant to hold.

**An already-provisioned block is never widened by an ordinary sale.** `allocate()`'s `INSERT ...
ON CONFLICT DO UPDATE` only ever supplies the _default_ range as the values for a brand-new row;
the conflict path touches `next_seq` alone. That is what leaves room for a future provisioning step
— M5-03's first-run wizard, or a deliberate recovery after a till's local database is lost and
rebuilt — to reserve a specific, non-default range (e.g. one starting above the highest number the
cloud already has on record for that terminal) without a schema change and without that reservation
being silently overwritten the next time the terminal rings up a sale.

### M1-11 — the tender overlay

**Enter tenders, F12 completes.** F12 already means "commit" everywhere else on the till, so the
overlay reuses it rather than inventing a second meaning for the big key: pressing F12 from the
cart opens the overlay, `Enter` inside it adds the amount on screen as a tender line, and F12
again — enabled only once the sale is fully settled — submits. Tab cycles the tender kind; digits
build the amount from the right like a calculator (typing `4`,`5`,`0`,`0`,`0` reads 450.00), which
needs no decimal key and matches how a cash register actually gets typed at speed.

**Only cash makes change, so only cash rounds.** `summariseTender` (`@lumora/domain`) keeps M1-03's
rule intact once a sale can split across tenders: a card or wallet line is charged exactly and can
never exceed what's left owed — there is no receipt either side holds for "the terminal
overcharged you by 40 cents, here is your change on the card" — so only the _cash-covered
remainder_ ever gets rounded to the nearest rupee, never the sale total and never a non-cash line.
The function throws rather than clamping when a non-cash line would overshoot, and the overlay
surfaces that as an on-screen error rather than silently capping the amount.

**The backend checksum extends to how the sale was paid, not just its totals.**
`assertTotalsAreSelfConsistent` now also asserts `Σtenders − change == total + roundingAdjustment`
— the identity `summariseTender` guarantees for any settled tender — plus one narrow guard beyond
pure arithmetic: `changeMinor > 0` is rejected unless at least one tender line is `CASH`, since the
sum-identity alone cannot see _which_ kind produced the change, only that some total of it was
claimed. Both are checksums on numbers the terminal already computed, not a second
implementation of the money math — the same posture as the rest of `SaleService`.

**Persistence is a new table, not new columns bolted onto `sales`.** A split sale (card + cash) is
the case M1-11 exists for, so tender lines live in `sale_payments` — one row per line, synced
inside the sale's own outbox payload exactly like `sale_items`, with no `client_uuid` of their own
since neither has independent identity. `rounding_adjustment_minor` and `change_minor` do live on
`sales` directly: there is exactly one of each per sale regardless of how many tender lines paid
it. `V201` mirrors `V104` on the cloud side; `SyncIngestService` treats both new sale fields as
optional so a batch from a pre-M1-11 till still ingests rather than being rejected wholesale.

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

**Verified in the running window, and it caught the exact bug the split exists to prevent.** The
tender button was `bg-accent … text-white`, because the Tailwind configs exposed no ink token at
all — white was the only thing to reach for. Against the brand blue that measured **2.42:1**. Both
configs now expose `brand`/`brand-ink` and `accent`/`accent-ink`, and the button reads **6.61:1**.
Nothing in the token file was wrong; the bug was that a component could not name the correct
colour. A token nobody can reference does not exist.

### M1-07 to M1-10 — the appliance

**The shell never scrolls; one region inside it does.** The status strip, scan field, totals and
F-key bar hold their positions for a whole shift, and only the cart list moves. "No scrolling during
a sale" does not mean a long cart is impossible — it means the cashier never loses sight of the
total or the keys because the list grew.

**Every F-key slot is rendered whether or not it does anything yet.** F5 Discount, F7 Hold, F9
Return and the rest sit greyed in their places until the milestone that fills them. The point of the
bar is that a cashier learns "void is fourth from the left" with their hand rather than their eyes,
and a bar that reflowed as features landed would retrain them every release.

**Scanning merges rather than appends.** Four passes of the same tin reads "4" on one row. Appending
would fill the screen with rows the cashier then has to verify against the basket.

#### The two scanner rules, and the bug each prevents

A USB gun is a keyboard; nothing in the DOM says which device sent a keystroke.

**Never bind a plain digit.** A barcode is a burst of digits. If `1` meant "quantity 1" or `7` meant
"void", every scan would fire a handful of commands on its way past. Every global binding is a
function key or an arrow for exactly this reason.

**An `Enter` within 60ms of a character is the gun's terminator, not the cashier.** Without the
guard, scanning would add the item _and_ trigger whatever Enter is bound to — on this screen,
tendering. The cashier scans a second item and the first one has already been paid for and printed.
60ms sits comfortably above a scanner's inter-character gap (typically under 15ms) and well below a
human reaching for Enter. Both the scan field and the global handler consult one shared clock; two
clocks would mean two answers about who typed.

#### Verified with the keyboard only

Driven through CDP `Input.dispatchKeyEvent`, so the keys traverse the real browser input pipeline
rather than synthesised JS events. A click counter was armed at the document in capture phase first.

| Step                                | Result                                      |
| ----------------------------------- | ------------------------------------------- |
| Scan `4791234567890` as a gun would | Ceylon Tea added — **and no sale tendered** |
| Scan it again                       | merged to qty 2, one row                    |
| Scan Milk Powder's **second** code  | resolved to the same product                |
| Type "sugar", pause, Enter          | treated as a search, not a scan             |
| ArrowUp, `+`, `-`, F4               | selection moved, qty edited, line voided    |
| F12                                 | `KND-T1-000020` — 1,710.00                  |
| **Clicks dispatched**               | **0**                                       |

That is Gate M1's criterion for **one** sale. The gate itself is twenty consecutive, by a person, and
stays unticked until that has actually been done.

### D6 — light mode on the till, and what it exposed

The till now has light mode. It is an **explicit, persisted, per-machine choice defaulting to dark**,
not a `prefers-color-scheme` query: a shop PC's system theme is whatever the person who installed
Windows left it on, and a till that changed colour after an update would be a support call. One
screen, one place, one set of lights — every cashier on every shift sees the same thing, which is
the property the fixed palette was protecting in the first place.

The choice is applied by an inline script **before first paint**. Setting it in an effect after
hydration means a shop that chose light sees a black flash on every launch and reload, which on an
appliance reads as broken.

Two things deliberately do not change with the theme: 56px touch targets and tabular monospace
money. Those are ergonomics, not decoration, and a cashier's hands should not have to relearn the
screen because the owner changed the colours.

**Adding it exposed four AA failures that the dark-only till had been hiding.** The light palette's
semantic colours had only ever been used as dots and borders; the status strip renders its whole
message in them, and as _text_ on a light page they measured:

| Token           | Was      | Now                  | Used for                         |
| --------------- | -------- | -------------------- | -------------------------------- |
| `--lum-pending` | 3.38:1 ✗ | **4.96:1** `#8F5D00` | "OFFLINE — sales saving locally" |
| `--lum-ok`      | 2.96:1 ✗ | **4.69:1** `#0A7D0A` | sale committed                   |
| `--lum-danger`  | 4.24:1 ✗ | **5.11:1** `#BF2D2D` | errors, voids                    |
| `--lum-ink-3`   | 3.22:1 ✗ | **4.84:1** `#5D6B76` | section labels, hints            |

Amber is the one that mattered. It carries the offline warning — the single message on a till that
must never be easy to miss — and at 12px on a light page it was failing by a wide margin. "Muted" is
not a WCAG exemption either, which is why `--lum-ink-3` moved too. Every text token now clears AA in
both till themes, measured in the running window rather than calculated.

### M1-06 — barcodes became a table

`V103` moves barcodes out of `products.barcode` into `product_barcodes`, carries the existing values
across, and drops the column.

One product genuinely has several codes: the manufacturer's EAN, a different EAN after a packaging
change, a supplier's own code on the same goods, a shop-printed label on loose items. A single
column forces a choice, and the code that loses simply does not scan — which a cashier experiences
as "the system is broken". Keeping both column and table would leave two records of one fact, and
they would disagree within a month.

**A barcode resolves to exactly one product**, enforced by a unique index rather than by application
code. That is not tidiness: M1-08 requires a scan to add an item with zero clicks, and an ambiguous
code means stopping to ask the cashier which product was meant. Refusing the duplicate when someone
types it in is the only point where the question can be answered properly.

**Two lookup paths, deliberately not one.** A scan is most of the work a till does, so it gets its
own query — one probe of a unique index, no trigram, no ranking. Search is the fallback for loose
goods and missing labels. `/api/products/search` returns `exactMatch`, so the terminal knows to add
the item silently rather than inferring it from a result count — a name search that happened to
return one row would otherwise behave like a scan.

Ranking is barcode → exact SKU → name prefix → contains. Sorting by trigram similarity instead reads
as arbitrary at the counter: "milk" should put _Milk Powder 400g_ above _Fresh Milk 1L_ because it
starts with the word, not because of shared three-letter runs. Trigram indexes still back the
contains-search, since a cashier types "owder" and a b-tree cannot serve a leading wildcard.

**The seed was not as idempotent as it claimed.** Its header says "run it as often as you like", but
against an already-migrated database the barcode insert collided on `(tenant_id, barcode)` — the
carried-over rows hold those codes under uuids derived from the product, so `ON CONFLICT
(client_uuid)` did not match and the statement aborted. Two products silently ended up with no
barcode. Now a bare `ON CONFLICT DO NOTHING`, verified by running it twice.

### M1-03 — the LKR cash rounding policy <sub>signed off 2026-08-13</sub>

**Cash tenders round to the nearest LKR 1.00, halves away from zero. Everything else takes the
exact amount.** Sri Lanka's circulating coinage is Rs 1, 2, 5 and 10, so a cash total of 450.50
cannot actually be settled — the cashier already rounds it by hand, off the books and
inconsistently. Card and wallet have no such problem and must not be rounded.

**The rounding belongs to the payment, never to the sale.** This is the part that is expensive to
undo, and it is why the policy is not simply "round the total":

1. **Tax integrity.** Declared VAT derives from the sale total. Moving that total would declare VAT
   on an amount the customer was never invoiced for, and the IRD invoice format (hard date April 2026) would report a figure that disagrees with the sale record.
2. **The backend checksum.** `assertTotalsAreSelfConsistent` requires `subtotal − discount == total`
   exactly. Rounding the sale total breaks it on any sale not ending in a round rupee — which is
   most of them — and the till would start rejecting its own sales.

So a 450.50 cash sale records total 450.50, cash received 451.00, rounding adjustment 0.50. The
drawer balances, the receipt shows the adjustment, the tax figure is untouched. The residual across
a day is a real number the M2 cash-up screen accounts for rather than hides.

The increment is a constant, not per-tenant configuration. A different step later is a deliberate
change with a migration for historical sales, not a setting someone can get wrong.

### M1-01 to M1-05 — what the money layer decided

**`Minor` is a branded number, not a class.** A raw `number` will not typecheck where money is
expected, so a price arriving from JSON must pass through `minor()` — which is exactly where a float
would otherwise slip in. Not a class, because these values serialise straight into the sale payload
and the outbox row; a class would need marshalling on both sides, and the first time someone forgot,
the bug would be a wrong number rather than a crash.

**Carts normalise to gross before anything else.** After that single step there is no
`INCLUSIVE`/`EXCLUSIVE` branch anywhere: tax is always extracted, discounts always apply to gross.
Two paths through money code means the rarely-used one is the one nobody notices is wrong, and
`EXCLUSIVE` is the trade-counter case exercised once a month. Grossing is done **per unit** so
`qty × unitPrice = lineTotal` still holds on the receipt.

**Order discounts are apportioned by largest remainder.** Rounding each share independently loses or
invents up to a cent per line — a 10.00 discount over three lines discounts 9.99 — and that cent then
fails the backend checksum with a customer waiting. Ties go to the earlier line so a reprinted
receipt matches the original.

**The property tests found a real bug on their first run:** `roundCashMinor` produced **negative
zero** for small negative amounts. `-0` compares unequal to `0` under `Object.is`, so a zero rounding
delta would have looked like a different figure from no rounding delta. `minor()` now normalises it.
That is the case for properties over examples in one line — nobody would have typed it.

**Proven against the Java checksum, not assumed.** Four carts the domain computed were posted to the
running backend: the M0 single line, 10.00 over three lines, four lines with mixed line and order
discounts, and a 1-cent discount spread across four lines. All `201`.

### Light and dark — how the two surfaces differ

There is **one** dark palette, entered two different ways, because the two apps have opposite
requirements and a shared ramp is what stops them drifting apart.

The **terminal** opts in permanently through `data-surface='terminal'`. A till that changed colour
at sunset would be a support call, and a cashier's muscle memory for where things sit should never
fight a changed contrast. It was verified to ignore every theme signal there is — OS light, OS
dark, and an explicit `data-theme` of either — six combinations, all still `#0a0e12`.

The **console** follows the viewer: it is a phone app read in daylight and in bed. Each console
selector carries `:not([data-surface='terminal'])`, so no preference or toggle can ever drag the
appliance out of dark.

The `prefers-color-scheme` block is guarded with `:not([data-theme='light'])`. Without that guard an
explicit light choice loses to a dark OS and the toggle only works in one direction — the failure
looks like "the button does nothing" for exactly half the users. All four combinations were measured
in a real window: OS light 17.26:1, OS dark 16.39:1, and both forced overrides winning against the
opposite OS setting.

`color-scheme` is set alongside the tokens so native chrome follows. Omitting it leaves a dark
console with a white scrollbar and light date pickers, which reads as a half-finished theme.

### Driving the Electron window on this machine

Two mechanics that will otherwise cost an hour each time:

- **`ELECTRON_RUN_AS_NODE=1` is set in the agent's shell environment.** Electron then runs the main
  script as plain Node, `require('electron')` yields a path string, and `app` is `undefined` —
  surfacing as `Cannot read properties of undefined (reading 'requestSingleInstanceLock')`, which
  reads like a broken entrypoint rather than an env var. Launch with `env -u ELECTRON_RUN_AS_NODE`.
- **Tailwind config changes need a dev-server restart**, not a hot reload. The class lands in the
  DOM and silently resolves to nothing, so the element inherits body ink and looks _almost_ right.
  Measure `getComputedStyle` rather than trusting the screenshot. Killing the dev server also needs
  the port owner killed directly; stopping the wrapping shell leaves the Node process holding 3000.

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
