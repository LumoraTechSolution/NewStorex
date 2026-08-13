# services/backend

Spring Boot 3.3 / Java 17 / Maven. **One jar, two profiles.**

| Profile   | Runs                                       | Binds            | Role                                                     |
| --------- | ------------------------------------------ | ---------------- | -------------------------------------------------------- |
| `desktop` | on the shop PC, inside the Electron bundle | `127.0.0.1:8081` | Source of truth for the sale. Owns the outbox.           |
| `cloud`   | hosted, multi-tenant                       | public           | Idempotent ingest, reporting replica, licence/plan/flags |

Deliberately **not** a pnpm workspace package — it is a Maven module, and `pnpm-workspace.yaml`
excludes it. Turborepo does not orchestrate it.

## Testing

Integration tests run against **real Postgres 16**, never H2 — this system's correctness rests on
Postgres-specific behaviour (`ON CONFLICT` upserts on `client_uuid`, partial indexes on the outbox,
`timestamptz`), and an in-memory stand-in would pass here while a till failed.

The database is the `db-test` compose container, not Testcontainers: Docker Desktop 29 on Windows
answers docker-java's `/info` probe with an empty HTTP 400 on every named pipe, so Testcontainers
cannot connect at all. Start it with `pnpm db:up` (or `docker compose up -d db-test`).

Every run drops the schema and re-migrates from scratch, so the migration path is itself under test.
`CleanDatabaseBeforeTests` refuses to do that to any database not named `lumora_test`.

## Status

**M0-03 done** — the app boots on both profiles, Flyway applies `V1`, `/actuator/health` reports UP,
and the desktop profile is confirmed unreachable from the LAN. Next:

- **M0-04** — minimal schema; every synced aggregate carries a unique `client_uuid`
- **M0-05** — `POST /api/sales`: domain rows _and_ the outbox row in one `@Transactional`
- **M0-07** — `cloud` profile, `POST /api/sync/batch`, idempotent upsert on `client_uuid`
- **M0-08** — `@Scheduled` outbox drain with capped exponential backoff

## The rule this service exists to enforce

A sale commits locally and is final before anything touches the network. The outbox row is written
in the same transaction as the sale, so a sale can never exist without its sync record — and the
cloud upserts on `client_uuid`, so redelivering a batch is a no-op. See ROADMAP §A and §B.
