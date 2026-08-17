# Flyway migrations

Three directories, because the desktop database and the cloud database are not the same schema and
never will be. The shop PC owns an outbox and has exactly one tenant; the cloud owns tenant
isolation and has no outbox.

| Directory | Applied to     | Version range | Holds                                             |
| --------- | -------------- | ------------- | ------------------------------------------------- |
| `common/` | both           | `V1`–`V99`    | conventions and anything genuinely identical      |
| `desktop/`| the shop PC    | `V100`+       | outbox, local sequences, single-tenant assumptions|
| `cloud/`  | the cloud      | `V200`+       | multi-tenant ingest, reporting rollups            |

Profiles compose them: `desktop` runs `common + desktop`, `cloud` runs `common + cloud`. The version
ranges are disjoint so a number can never mean two different migrations on one database, and so you
can tell at a glance which tier a migration belongs to.

## Rules

- **Reserve the next number before you write the file.** Two branches picking `V101` is a build
  failure at best and a divergent schema at worst.
- **Never edit an applied migration.** Flyway checksums them; fix forward with a new one.
- **Anything a synced aggregate needs, `common` should not own.** If a table exists on both sides
  but with different columns, it belongs in `desktop/` and `cloud/` separately, not in `common/`.

## Current highest

| Directory  | Highest |
| ---------- | ------- |
| `common/`  | `V1`    |
| `desktop/` | `V105`  |
| `cloud/`   | `V201`  |

This table drifts. Trust the filesystem.
