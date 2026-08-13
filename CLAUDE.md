# CLAUDE.md

Guidance for Claude Code working in `D:\Lumora\NEW POS`.

## What this is

A **greenfield rebuild** of the Lumora POS as an offline-first desktop application. It is not a fork
or a port of `D:\Lumora\POS System`, and it does not share a repo, a database or a dependency with
it.

> **The parent `D:\Lumora\CLAUDE.md` describes a different codebase.** It says "the real project is
> `POS System/`" and documents that project's three-sibling layout, ports and Flyway numbering. None
> of it applies here. When working in `NEW POS`, this file wins on everything.

Design rationale lives in the
[development guide](https://claude.ai/code/artifact/c2c24386-6677-440a-a989-cf4d83ff8ff8).
**`ROADMAP.md` is the execution document** — the task list, the ground rules, the progress log. Read
it before starting work; update it when you finish.

## The one principle

Everything else is a consequence of this inversion:

- **Old:** terminal → cloud API → cloud Postgres. The network is on the critical path of a sale.
- **New:** terminal → local Postgres (**the sale is final here**) → outbox → cloud. The network is on
  the critical path of nothing.

The product this replaces is a native desktop POS whose one durable advantage is that it never stops
working. A cloud-first POS that stalls mid-transaction loses on the only axis a shopkeeper cares
about.

### Four things that must never be compromised

Expensive or impossible to retrofit. Re-read `ROADMAP.md` §A at the start of every milestone.

1. **Outbox + idempotency keys.** Every synced aggregate carries a client-generated `client_uuid`
   with a unique index. The outbox row is written in the **same transaction** as the domain rows — a
   sale can never exist without its sync record.
2. **Movements, not balances.** Never sync a level; sync the movements that produce it. Balance is
   always `Σ entries`, never a stored column anyone updates.
3. **Per-terminal invoice number blocks.** Issued locally from a reserved range. A global sequence
   needs the network, so it cannot be used.
4. **Local-first write path.** If anything writes to the cloud "just for now", v2 is a rewrite.

### Money

- Integer **minor units**. Never a float, never a JS `number` for currency arithmetic. ESLint blocks
  `parseFloat` in `packages/domain/`.
- VAT is **extracted** from inclusive prices — `vat = total × rate ÷ (1 + rate)` — never multiplied
  onto them. Mode and rate are stamped per sale.
- All of it lives in `@lumora/domain`, which has **no I/O**. Implemented twice means the receipt and
  the console eventually disagree by a rupee, and no test tells you.

## Layout

```
apps/terminal/        Next.js + Electron  → 127.0.0.1:8081   cashier terminal + back office
apps/console/         Next.js PWA         → cloud API        owner console + super-admin (read-only)
packages/domain/      money math, VAT, cart totals — pure TS
packages/ui/          design tokens + components
packages/api-client/  typed client from OpenAPI
services/backend/     Spring Boot — one jar, profiles: desktop | cloud
```

`services/backend` is a **Maven** module and deliberately not a pnpm workspace package —
`pnpm-workspace.yaml` excludes it and Turborepo does not orchestrate it.

Workspace packages are consumed as **TypeScript source** (`transpilePackages`), so there is no build
step and no stale `dist` to chase.

## Commands

```powershell
# JS/TS — from the repo root
pnpm install
pnpm dev            # terminal :3000, console :3001
pnpm typecheck      # CI-gated
pnpm lint           # CI-gated
pnpm test           # CI-gated
pnpm build
pnpm format

# Databases
pnpm db:up          # db-local :5442, db-cloud :5443, db-test :5444
pnpm db:psql        # psql into the local one
pnpm db:offline     # stop db-cloud — rehearse an outage
pnpm db:online
pnpm db:reset       # destroys volumes

# Backend — from services/backend
./mvnw spring-boot:run                                     # desktop profile, 127.0.0.1:8081
./mvnw spring-boot:run "-Dspring-boot.run.profiles=cloud"  # cloud profile, :8082
./mvnw -B test                                             # needs `pnpm db:up` first
./mvnw clean verify                                        # what CI runs
```

`pnpm` is installed globally at `%APPDATA%\npm` and pinned via `packageManager`. There is no `mvn` on
PATH — always use the `mvnw` wrapper. Java is 17 at `C:\Program Files\Java\jdk-17`.

## Ports on this machine

| Port   | Owner                             | Note                                          |
| ------ | --------------------------------- | --------------------------------------------- |
| `3000` | terminal dev server               |                                               |
| `3001` | console dev server                |                                               |
| `8081` | backend, `desktop` profile        | design constant — loopback only, deliberately |
| `8082` | backend, `cloud` profile          |                                               |
| `5432` | a native Windows Postgres service | **not Docker, not ours — leave it alone**     |
| `5442` | `db-local` (`lumora_local`)       | moved off 5432 because of the row above       |
| `5443` | `db-cloud` (`lumora_cloud`)       |                                               |
| `5444` | `db-test` (`lumora_test`)         | disposable, tmpfs — wiped on every test run   |

The old POS stack's containers (`lumora-pos-db`, `lumora-pos-backend`, `lumora-pos-frontend`) were
stopped on 2026-08-12 and their restart policy set to `no`, freeing 3000 and 8081. **All of its
volumes are intact.** Starting it again collides with this project.

The `desktop` profile binds `127.0.0.1` on purpose: in v1 the till's API is not reachable from the
LAN. LAN multi-terminal is v2 and should arrive as a decision, not as a default bind address.

## Flyway — reserve your version number first

Migrations are split three ways, and each profile composes `common` with its own tier:

| Directory  | Applied to  | Version range | Holds                                              |
| ---------- | ----------- | ------------- | -------------------------------------------------- |
| `common/`  | both        | `V1`–`V99`    | conventions, anything genuinely identical          |
| `desktop/` | the shop PC | `V100`+       | outbox, local sequences, single-tenant assumptions |
| `cloud/`   | the cloud   | `V200`+       | multi-tenant ingest, reporting rollups             |

Trust the filesystem for the current highest number, not any document. Never edit an applied
migration — Flyway checksums them; fix forward. Full conventions in
`services/backend/src/main/resources/db/migration/README.md`.

## Testing

Integration tests run against **real Postgres 16, never H2**. Correctness here rests on
Postgres-specific behaviour — `ON CONFLICT` upserts on `client_uuid`, partial indexes on the outbox,
`timestamptz` — and an in-memory stand-in would pass while a till failed.

The database is the `db-test` compose container, **not Testcontainers**: Docker Desktop 29 on this
machine answers docker-java's `/info` probe with an empty HTTP 400 on every named pipe, so
Testcontainers cannot connect at all. Don't re-attempt it without checking that first.

Every run drops the schema and re-migrates, so the migration path is itself under test.
`CleanDatabaseBeforeTests` refuses to do that to any database not named `lumora_test`.

## Toolchain deviations that will look like mistakes

- **`node-linker=hoisted`** in `.npmrc`. Next's `output: 'standalone'` mirrors the dependency tree
  with symlinks, which fails `EPERM` on Windows without Developer Mode; electron-builder also cannot
  pack a symlinked pnpm store (M5-01). We trade pnpm's phantom-dependency strictness for that.
- **`eslint-config-next` runs a major ahead of `next`** (15.x against `next@14.2`). The 14.x plugin
  calls `context.getAncestors()`, removed in ESLint 9. The plugin lints code patterns, not the Next
  runtime, so the skew is safe.
- **Prettier owns the Markdown**, including `ROADMAP.md`. Don't hand-align table pipes or rely on
  column alignment inside fenced blocks — `pnpm format` will collapse it.

## Working on ROADMAP.md

It is the task tracker and the memory between sessions. Task IDs (`M2-07`) are stable and never
renumbered; new work appends. Flip `- [ ]` → `- [x]` and append `<sub>done YYYY-MM-DD</sub>`.

A **gate** may only be ticked after its criterion has actually been executed against running
software — never inferred from its tasks being complete. Every gate tick appends a row to §G and
updates the _Current position_ line at the top of the file. Record surprises and deviations in §G
too; that is where the next session looks first.

## Don't touch

- `D:\Lumora\POS System`, `POS System - Copy`, `POS System Desktop` — the previous product.
  Reference material: read them before re-solving a problem, but nothing here should import from
  them or edit them. Build boilerplate (the Maven wrapper) was copied across; domain code was not.
- The stopped `lumora-pos-*` containers and their volumes.
- `Lumora technologies/` — brand assets.
