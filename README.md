# Lumora POS

Offline-first point of sale for Sri Lankan retail. The sale commits to a local Postgres on the shop
PC and is final there; the cloud receives it afterwards, through an outbox, and the network is on
the critical path of nothing.

**Start here:** [`ROADMAP.md`](./ROADMAP.md) — ground rules, milestones and the task list we work
from. Design rationale lives in the
[development guide](https://claude.ai/code/artifact/c2c24386-6677-440a-a989-cf4d83ff8ff8).

## Layout

```
apps/terminal/       Next.js + Electron  → 127.0.0.1:8081   cashier terminal + back office
apps/console/        Next.js PWA         → cloud API        owner console + super-admin (read-only)
packages/domain/     money math, VAT extraction, cart totals — pure TS, no I/O
packages/ui/         design tokens + shared components
packages/api-client/ typed client generated from OpenAPI
services/backend/    Spring Boot — one jar, profiles: desktop | cloud
```

## Prerequisites

| Tool   | Version                                                    |
| ------ | ---------------------------------------------------------- |
| Node   | ≥ 20.11                                                    |
| pnpm   | 9.15.4 (`npm i -g pnpm@9.15.4`, or `corepack enable pnpm`) |
| Java   | 17                                                         |
| Docker | for the development Postgres                               |

## Databases

Two Postgres 16 containers, mirroring the two machines in the real topology. Production has no
Docker on the shop PC — that Postgres is a native binary inside the installer (M5-01).

| Service    | Port   | Database       | Role                                                |
| ---------- | ------ | -------------- | --------------------------------------------------- |
| `db-local` | `5442` | `lumora_local` | The shop PC. Source of truth. Owns the outbox.      |
| `db-cloud` | `5443` | `lumora_cloud` | Multi-tenant ingest and reporting                   |
| `db-test`  | `5444` | `lumora_test`  | Disposable. Integration tests wipe it on every run. |

Ports are 5442/5443 rather than 5432/5433 because the previous POS stack still runs on this
machine and holds 5432. Override with `LOCAL_DB_PORT` / `CLOUD_DB_PORT`.

```bash
cp .env.example .env
pnpm db:up            # both databases
pnpm db:psql          # psql into the local one
pnpm db:offline       # stop the cloud DB — rehearse an outage
pnpm db:online
pnpm db:reset         # destroys volumes and recreates
```

## Commands

```bash
pnpm install

pnpm dev              # every app in parallel — terminal :3000, console :3001
pnpm --filter @lumora/terminal dev

pnpm typecheck        # CI-gated
pnpm lint             # CI-gated
pnpm test             # CI-gated
pnpm build
pnpm format
```

## Backend

```bash
cd services/backend

./mvnw spring-boot:run                    # desktop profile, 127.0.0.1:8081
./mvnw spring-boot:run -Dspring-boot.run.profiles=cloud   # cloud profile, :8082
./mvnw -B test                            # needs `pnpm db:up` first
./mvnw clean verify                       # what CI runs
```

Health: <http://127.0.0.1:8081/actuator/health>. The desktop profile binds **loopback only** — in v1
the till's API is deliberately not reachable from the LAN. Integration tests run against the
`db-test` container and refuse to run against any database not named `lumora_test`.

## Before you write money code

Read ROADMAP §A. The short version: money is integer minor units and never a float; VAT is
_extracted_ from inclusive prices (`vat = total × rate ÷ (1 + rate)`), never multiplied onto them;
balances are always the sum of movements, never a stored level; and all of it lives in
`@lumora/domain` so the receipt and the console can never disagree.
