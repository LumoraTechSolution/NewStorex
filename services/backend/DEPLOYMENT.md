# Deploying the cloud backend

The shop PC is not deployed — it is installed (M5-01/M5-02). This document is about the **other**
tier: the cloud that the tills push to and the owner console reads from.

The deliverable is a **Docker image plus the environment contract below**. The image names no
provider. Today it runs on a free host; when a client requires AWS or Azure, what changes is the
values in [the contract](#the-environment-contract), not the code — see
[Moving to AWS or Azure](#moving-to-aws-or-azure).

> Cloud values are set in a host's dashboard or task definition, **never in a `.env` file**. The
> repo's `.env.example` is development-only and says so; do not extend it with production values.

---

## Where it runs

| Piece | Where | Why |
| --- | --- | --- |
| Cloud Postgres | **Neon**, free, AWS `ap-southeast-1` (Singapore) | Free tier never expires and includes 24 h point-in-time restore |
| Cloud backend | **Render**, free web service, Singapore, Docker runtime | Builds this Dockerfile from a monorepo subdirectory; free HTTPS hostname |
| Console | **Vercel** | Already the plan of record (ROADMAP §B) |

**Why Neon and not the obvious alternatives.** Render's own free Postgres is **deleted after 30
days**, and Supabase's pauses after 7 idle days — a shop closed for a long weekend comes back to a
paused database. Neither is acceptable once a pilot shop's real sales are in there. At ~100–200 MB
per shop per year, Neon's 0.5 GB is roughly 18 months at two shops.

**Both in the same region, and that matters more than it looks.** `TenantAuthFilter` does a
`touch()` write to `last_seen_at` on *every* authenticated request, so every console request is at
minimum a read plus a write. A cross-region backend↔database pair adds that latency to all of them.
Colombo↔Singapore is ~50–70 ms, which is invisible to the till (the outbox is asynchronous) and
visible on the owner's phone.

---

## The environment contract

This table is the portable artifact. Anything that can run an OCI image and set these variables can
run this backend.

### Required

| Variable | Example | Notes |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `cloud` | **Read the warning below.** |
| `CLOUD_DATABASE_URL` | `jdbc:postgresql://ep-xxxx.c-3.ap-southeast-1.aws.neon.tech:5432/lumora_cloud?sslmode=require` | JDBC form, not the provider's URI. TLS parameters are not optional. |
| `CLOUD_DATABASE_USER` | `lumora_owner` | |
| `CLOUD_DB_PASSWORD` | *(secret)* | Host's secret store. Never the repo, never a file in the image. |
| `LUMORA_CONSOLE_ORIGINS` | `https://storex-console.vercel.app` | Comma-separated, no spaces, no trailing slash, scheme included. |

> ### `SPRING_PROFILES_ACTIVE=cloud` is the one that will cost you an afternoon
>
> `application.yml` sets `spring.profiles.default: desktop`, because that is what a developer
> almost always wants and it binds loopback, so guessing it is harmless *on a laptop*.
>
> On a cloud host, omitting it means the container binds `127.0.0.1` (unreachable), runs the
> **desktop** migrations, mounts none of the `@Profile("cloud")` controllers, and tries to reach a
> `lumora_local` database that does not exist. Verified: the error it produces is
>
> ```
> Unable to obtain connection from database: Connection to 127.0.0.1:5442 refused.
> ```
>
> — which reads as a database problem and says nothing about profiles. If you ever see port
> **5442** in a cloud log, this variable is missing.

### Required on a constrained host

| Variable | Example | Notes |
| --- | --- | --- |
| `SERVER_PORT` | `10000` | Only where the host dictates a port. Render injects `$PORT`; set this to match. Omit elsewhere and it listens on 8082. |
| `CLOUD_DB_POOL_SIZE` | `5` | See [connection pool](#the-connection-pool) below. |
| `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` | `1` | Lets a serverless Postgres suspend when the shop is closed. |
| `JAVA_TOOL_OPTIONS` | *(image default + `-Djava.net.preferIPv4Stack=true`)* | Required on Render. See [IPv4](#ipv4-must-be-forced-on-render) — it **replaces** the image's value, so pass it whole. |

### Required for cloud backups (M5-06)

Without these the service still starts, logs a warning, and writes shop archives to the
container's own filesystem — **which Render recreates on every deploy.** Uploads succeed, rows are
written, the till is told everything is fine, and the archives are gone at the next release. That
is the worst failure mode this service has, so the warning line names it explicitly:

```
Cloud backups have no object storage configured and will be written to ./cloud-backups.
```

| Variable | Example | Notes |
| --- | --- | --- |
| `LUMORA_BACKUP_S3_BUCKET` | `storex-backups` | |
| `LUMORA_BACKUP_S3_ENDPOINT` | `https://<account-id>.r2.cloudflarestorage.com` | R2's account endpoint. Omit on AWS proper, where the region determines it. |
| `LUMORA_BACKUP_S3_REGION` | `auto` | R2 has no regions and still requires the signature to name one. |
| `LUMORA_BACKUP_S3_ACCESS_KEY_ID` | *(secret)* | |
| `LUMORA_BACKUP_S3_SECRET_ACCESS_KEY` | *(secret)* | |
| `LUMORA_BACKUP_KEEP` | `14` | Archives kept per shop. A week of twice-daily uploads. |
| `LUMORA_BACKUP_MAX_BYTES` | `536870912` | Largest archive accepted, in bytes. |

**Why Cloudflare R2.** 10 GB free with no egress charge, and the S3 API — so this is not soldered
to a vendor. Egress matters more than it looks: the day anybody uses these is the day a shop is
downloading its entire database, and providers that meter that charge for exactly the event you
built the feature for.

**Making the bucket and the token:**

1. Cloudflare dashboard → **R2** → *Create bucket*, named `storex-backups`. Keep it **private** —
   an object here is a shop's whole database, staff and customers included.
2. **R2 → Manage API Tokens → Create API Token.** Permission **Object Read & Write**, scoped to
   that one bucket.
3. Copy the **Access Key ID**, the **Secret Access Key**, and the account endpoint shown beside
   them. The secret is displayed once.
4. Set the five variables above in Render's dashboard, never in a file, and redeploy.

The tokens on the shop PCs are unaffected by any of this: a till uploads to *this service*, with
its own tenant credential, and never sees an R2 key. That is deliberate — a shared storage key on
every shop-floor PC in the country would be one stolen laptop away from every shop's history.

### Set once, then deleted

| Variable | Notes |
| --- | --- |
| `LUMORA_PLATFORM_BOOTSTRAP_EMAIL` | See [first platform admin](#first-platform-admin). Delete it afterwards. |
| `LUMORA_PLATFORM_BOOTSTRAP_NAME` | Optional; defaults to `Lumora`. |

### Deliberately absent

`LOCAL_DATABASE_URL`, `LOCAL_DATABASE_USER`, `LOCAL_DB_PASSWORD`, `LUMORA_CLOUD_URL`,
`LUMORA_CLOUD_TOKEN`, `NEXT_PUBLIC_API_BASE_URL`, `TZ`.

The first three belong to the desktop profile; the next two belong on a shop PC; the sixth belongs
to Vercel. **`TZ` is the interesting one:** leave the container on UTC.
`ConsoleReportController` pins `Asia/Colombo` explicitly for its day boundaries, so the JVM's
default zone is not consulted, and setting `TZ` would change nothing except your confidence about
what does.

Listing what is absent is part of the contract — it is what stops someone pasting all of
`.env.example` into a dashboard.

---

## Three decisions the contract encodes

### The connection pool

`application-cloud.yml` defaults to 20, which is right for a dedicated database and wrong for a
free-tier one, where a large idle pool holds a serverless compute awake. Deploy with
`CLOUD_DB_POOL_SIZE=5`.

The knob is an environment variable rather than a lowered default because **the ceiling belongs to
the environment, not the code** — and because `application-desktop.yml` sets its own `8` and never
reads this variable, so the shop PC is unaffected either way.

### Copy the hostname from Neon; never reconstruct it

**Use exactly the hostname Neon's connection string gives you.** Current Neon endpoints look like:

```
ep-dawn-credit-azh6unu8.c-3.ap-southeast-1.aws.neon.tech
```

The `.c-3.` segment is part of the address and **must be kept**. Dropping it, or using a stale
endpoint id after recreating a project or branch, produces:

```
SQL State : 28P01
ERROR: password authentication failed for user 'neondb_owner'
```

**A wrong Neon hostname reports as an authentication failure, not as a connection or DNS error.**
This is the single most misleading failure in this deployment: the message names the user, so every
instinct says to go and reset the password, and the password is fine. Verified by measurement — the
same credentials succeed with `.c-3.` and fail with `28P01` without it.

Two consequences worth internalising:

- **`nslookup` cannot validate a Neon hostname.** Neon serves wildcard DNS, so a lookup returns an
  answer for any name of roughly the right shape, including one no endpoint answers on. It is a test
  that cannot fail and therefore proves nothing.
- **When you recreate a database, branch or project, the endpoint id changes.** The old hostname
  keeps resolving and keeps returning `28P01`.

Before touching credentials, test the connection directly and take Render out of the picture:

```powershell
$env:PGPASSWORD = '...'
psql "host=ep-xxxx.c-3.ap-southeast-1.aws.neon.tech port=5432 dbname=lumora_cloud user=neondb_owner sslmode=require" -c "select current_user, current_database();"
```

Succeeds locally but fails on the host → the problem is how the value is stored in the host. Fails
locally too → the hostname or the credential is wrong, and it is usually the hostname.

Separately, set `JAVA_TOOL_OPTIONS` to the Dockerfile's value plus
`-Djava.net.preferIPv4Stack=true`: Render's free tier has no working outbound IPv6, and Neon
publishes AAAA records. Setting the variable **replaces** the image's default rather than appending
to it, so pass it whole, **on one line** — a newline inside the value truncates the flag list. Confirm
the next boot's `Picked up JAVA_TOOL_OPTIONS:` line ends with the flag, or it did not save.

### Neon's parameter spellings are libpq's, not JDBC's

Neon hands you `?sslmode=require&channel_binding=require`. `sslmode` is spelled the same in both;
`channel_binding` is **libpq's** name and the JDBC driver's is `channelBinding`, so pasting Neon's
string verbatim silently ignores it. Leave it off entirely — see below.

### The direct endpoint, not the pooler

Neon offers a PgBouncer endpoint (`...-pooler...`). **Do not use it here.** PgBouncer's transaction
mode breaks two things this application relies on:

- Hibernate's server-side prepared statements (would need `prepareThreshold=0` in the URL), and
- Flyway's migration lock, which is a session-scoped `pg_advisory_lock` and cannot survive
  transaction pooling at all.

At two tenants with one backend instance, the pooler buys nothing against those hazards. Revisit
only when running more than one instance, and read this paragraph again first.

Neon's connection-details panel has a **Connection pooling** toggle that is often on by default, and
the resulting hostname carries `-pooler` before the region segment. Check for that substring before
pasting the URL anywhere.

### The URI is split by hand, at deploy time

The provider hands you one string:

```
postgresql://neondb_owner:npg_AbC123@ep-xxxx.c-3.ap-southeast-1.aws.neon.tech/lumora_cloud?sslmode=require&channel_binding=require
```

Split it into the three variables yourself, once per environment:

```
CLOUD_DATABASE_URL  = jdbc:postgresql://ep-xxxx.c-3.ap-southeast-1.aws.neon.tech:5432/lumora_cloud?sslmode=require
CLOUD_DATABASE_USER = neondb_owner
CLOUD_DB_PASSWORD   = npg_AbC123
```

There is deliberately **no URI-parsing code**. It would be code that only ever runs in production,
and the three-variable form is exactly what AWS Secrets Manager and Azure Key Vault hand you
natively — parsing a provider's URI would be a Render-shaped wart on the portability story.

**TLS must be in the URL.** The PostgreSQL JDBC driver defaults to `sslmode=prefer`, which tries TLS
and **silently falls back to plaintext** if the server allows it. Neon refuses plaintext, so you
would get TLS anyway — but "encrypted because the server insisted" is luck, not a posture. So
`sslmode=require`, always.

**Do not add `channelBinding=require` on Neon.** It is tempting — it binds the SCRAM handshake to
the TLS channel and buys MITM resistance without shipping a CA bundle — but Neon's connection proxy
does not offer `SCRAM-SHA-256-PLUS`, so the driver aborts the handshake and the server reports:

```
SQL State : 28P01
ERROR: password authentication failed for user 'neondb_owner'
```

A correct password, reported as a bad one. This cost a deploy cycle to find, because every instinct
says to go and reset the password. (Note also that Neon's proxy quotes the username with single
quotes where stock Postgres uses double — that is Neon's phrasing, not a stray character in the
value, and it is not a clue.)

---

## Deploy order

`NEXT_PUBLIC_API_BASE_URL` is **inlined into the console at build time**; `LUMORA_CONSOLE_ORIGINS`
is read by the backend **at boot**. Each needs the other's URL — but both hosts assign a URL when
the *project* is created, before either app runs, so no placeholder deploy is needed.

1. **Neon** — create the project, region AWS `ap-southeast-1`, Postgres 16. Split the URI. No manual
   schema work: Flyway runs `common/V1` and `cloud/V200`–`V210` on first boot, and the schema uses
   **zero Postgres extensions**, so a vanilla database is enough.
2. **Render** — create the Web Service. Root Directory `services/backend`, Runtime **Docker**,
   Region Singapore, Health Check Path `/actuator/health/readiness`. **Note the URL. Do not deploy
   yet.**
3. **Vercel** — create the project ([settings](#vercel-settings)). **Note the URL.** If there will
   be a custom domain, attach it *now* — the origin list must contain the domain the browser
   actually sends.
4. Set the backend's environment, `LUMORA_CONSOLE_ORIGINS` included, using the URL from step 3 →
   **deploy the backend**.
5. Set Vercel's `NEXT_PUBLIC_API_BASE_URL` to the URL from step 2 → **deploy the console**.
6. [Bootstrap the platform admin](#first-platform-admin), then
   [provision the pilot till](#provisioning-a-till).

> **The rule worth remembering.** Changing the console's domain requires a **backend restart**
> (boot-time read). Changing the backend's URL requires a **console rebuild** (build-time inline).
> Neither is a code change, and neither is picked up automatically by the other.

**Vercel preview deployments will be blocked by CORS.** Per-commit preview URLs cannot be
enumerated in an exact-match origin list. That is the correct trade: this endpoint answers with a
shop's takings, and a pattern matcher would weaken a deliberate policy. A read-only console preview
is still useful for layout review. If you truly need a working preview, add one stable alias domain
to the list — an alias, not a pattern.

### Vercel settings

- **Root Directory** `apps/console`, with *Include files outside the Root Directory* **enabled** —
  `transpilePackages` pulls source from `packages/*`.
- **Install and Build commands: leave at defaults.** Vercel reads `packageManager: pnpm@9.15.4`
  from the root `package.json` and installs with `--frozen-lockfile`. The workspace packages are
  source-only (`main: ./src/index.ts`, no `build` script), so there is nothing to pre-build.
- **Node 20.x**, matching `engines`.
- **One variable:** `NEXT_PUBLIC_API_BASE_URL`, for Production and Preview but **not** Development —
  local `pnpm dev` should keep falling through to its `127.0.0.1:8082` default.
- **No `vercel.json`** — it would duplicate the above in a second place that can drift.

`node-linker=hoisted` is not a problem here. The two reasons the repo needs it (Next `standalone`
symlinks, electron-builder) are terminal-build concerns, and the console does not set
`output: 'standalone'`. Local and Vercel are both hoisted, so hoisting cannot produce a
Vercel-only failure.

---

## First platform admin

`PlatformBootstrap` is guarded by an **empty `platform_admins` table**, not by the absence of the
setting. Everything in the platform admin surface is done by an admin, including making admins, so
something outside that loop has to start it.

1. Set `LUMORA_PLATFORM_BOOTSTRAP_EMAIL` to an address you control, before the first deploy.
2. Deploy, and **keep the log stream open.** The generated password is logged at WARN exactly once
   and stored only as a BCrypt hash. It is not recoverable. (Recovery if missed:
   `DELETE FROM platform_admins;` — check `platform_sessions` and `platform_audit` first — and
   restart. Not a routine path.)
3. Immediately: `POST /api/platform/auth/login`, then `POST /api/platform/auth/password`. Put the
   new password in a password manager.
4. **Delete `LUMORA_PLATFORM_BOOTSTRAP_EMAIL` from the host's environment.**

Step 4 is not about a back door — the empty-table guard genuinely makes the variable inert while an
admin exists. Delete it because it becomes an *active hazard* the moment the service starts against
a fresh empty database — a Neon branch, a disaster-recovery restore, a staging clone. Then the guard
is satisfied, and a second admin is created with a password nobody is watching the logs for.

Afterwards, the variable's presence means "we are bootstrapping right now", which is worth
something.

## Provisioning a till

Create the tenant, its licence, the owner's console login and the till token in one call:

```
POST /api/platform/tenants        (Bearer: platform session)
{ "name": "...", "planCode": "...", "licenceDays": 365,
  "ownerEmail": "...", "ownerPassword": "...", "terminalLabel": "Till 1" }
```

The response carries `tillToken`. **It is shown once** — that is why `SecretOnce.tsx` exists. A lost
token is reissued (`POST /api/platform/tenants/{id}/credentials`), never recovered.

On the shop PC, set two **machine-level** environment variables:

```powershell
setx /M LUMORA_CLOUD_URL   "https://<backend>.onrender.com"
setx /M LUMORA_CLOUD_TOKEN "<the once-shown token>"
```

Machine-level, from an elevated prompt, because the backend may run as a service under a different
account. **Not a `.env` file in the install directory** — `.gitignore` protects the repo and says
nothing about a shop's disk, and a plaintext token at a predictable path on a shop-floor PC is a
story you would rather not have to tell later. (M5-03's first-run wizard will do this properly; for
two pilot shops, this is the honest interim.)

**Before provisioning is safe.** With no token, `HttpCloudSyncClient` opens no connection at all.
The till sells, the outbox queues, nothing is lost, and adding the token later drains the backlog on
the next tick. A shop can trade for days before it is connected.

---

## Sleeping hosts

Render's free service sleeps after 15 minutes without an inbound request, and Azure Container Apps
scales to zero the same way. For the till this is **fine by design**:

- Shop closed → outbox empty → `SyncWorker` returns before opening a connection → nothing is sent →
  the service sleeps. Correct, and it conserves instance-hours.
- Shop reopens → first sale hits a cold service → the client's 15 s timeout may trip → the worker
  backs off 5 s and retries → warm within a tick or two. Worst case a sale reaches the cloud ~5
  minutes late (the backoff cap). **Nothing is lost**: the outbox is durable and the upsert is
  idempotent on `(tenant_id, client_uuid)`.

For the console it is **not** fine, and Gate M4 runs straight through it: an owner opening the PWA
against a cold backend waits ~40 s and concludes the app is broken.

**Fix: a free cron (cron-job.org) hitting `GET /actuator/health` every 10 minutes.** This is exactly
why `/actuator/**` sits outside the auth filter's `/api/*` pattern — a keep-warm must not need a
shop's key. 750 free instance-hours a month exceeds a month's 730, so a permanently warm free
service fits. Do not build console-side "waking up…" UI; the keep-warm makes that code path dead.

All of this applies to the free tier only. On Render Starter the service never sleeps, and the
keep-warm should be **deleted** rather than left running — see [Going paid](#going-paid-when-a-client-is).

---

## Going paid, when a client is

The free tier is right for a pilot and wrong for a customer who is billing their
own shop against it. The upgrade is two dashboard clicks and **no change to this
document's contract** — same image, same variables, same region.

| | Free | Paid | What the money actually buys |
| --- | --- | --- | --- |
| Render | sleeps after 15 idle min, 512 MB | **Starter, $7/mo** — always on, 512 MB | No cold start. The keep-warm becomes unnecessary |
| Neon | 500 MB, 24 h restore, no SLA | **Launch, $19/mo** — 10 GB, 7-day PITR, support | The restore window, far more than the storage |

**$26/month for the whole estate, not per client.** One Neon project holds every
tenant — that is what `tenant_id` and the `(tenant_id, client_uuid)` uniqueness in
`V206` are for. Measured against the live database, a shop at 100 sales/day uses
~130–260 MB/year, so 10 GB is roughly 20–40 shop-years. At three clients the
infrastructure is under $9 each.

**Neon Launch is the half not to skip**, and not for the storage — free tier's
500 MB already lasts a small shop years. It is the **7-day point-in-time
restore**. When a shop says "we did something wrong on Tuesday", 24 hours is
already gone.

Render Starter keeps the same 512 MB as free, which is enough because the image
already sets `MaxRAMPercentage=70` and the deployed pool is 5. Only go to the
$25 tier if the JVM is actually short of heap — check before paying for it.

### Turn the keep-warm off at the same time

Once Render is always-on, **delete the cron-job.org job**. It stops doing
anything, adds noise to the logs, and — worse — becomes a monitor that can raise
a false alarm about a service that is perfectly healthy. A keep-warm that
outlives the reason for it is not harmless; it is a thing that pages you at
night for nothing.

### What is deliberately not on this list

- **A cheap VPS** (Hetzner, ~$6, Postgres and the container on one box). Genuinely
  cheaper, and it makes backups, patching, disk monitoring and the 3am restore
  your job. For a small team with paying clients, $26 to keep that someone
  else's problem is the better trade.
- **A second backend instance** (+$7). Only when one client's traffic measurably
  affects another's response times. Note the consequence before doing it: the
  Hikari pool of 5 becomes 10 across instances, which pushes you toward Neon's
  pooler — and back into the Flyway advisory-lock hazard documented above.
- **Neon Scale ($69)**. For longer PITR or read replicas. Nothing here needs
  either yet.

### Storage is not the thing to watch

There is **no purge job anywhere in the schema**, deliberately — `V205` is
explicit that a lapsed licence must stop ingest, not erase a shop's history. So
the database only ever grows. Check it quarterly rather than assuming:

```sql
SELECT pg_size_pretty(pg_database_size('lumora_cloud'));
```

The trigger for upgrading is not a number on that query. It is the day someone's
livelihood depends on the data, which usually arrives well before the disk does.

---

## Backups

The cloud is a shop's off-site copy of its own history, and it is on a free tier with no SLA. Until
M5-06 automates this, three things are the floor:

1. **Neon's 24-hour restore** — on by default. Covers a bad `UPDATE`; does not cover losing the
   project.
2. **A weekly dump, stored somewhere that is neither Neon nor a shop PC:**
   ```powershell
   $env:CLOUD_DUMP_URL = 'postgresql://USER:PASS@HOST/lumora_cloud?sslmode=require'
   pnpm db:dump:cloud
   ```
   A few MB at this scale. `.gitignore` already excludes `*.dump`.
3. **One restore drill, executed before a pilot shop goes live.** Restore into a scratch Neon branch
   and sign in to the console against it. A backup you have never restored is not a backup.

The trigger for the paid tier is a paying customer, not a date — see
[Going paid](#going-paid-when-a-client-is), where the 7-day restore window is the reason rather than
the storage.

---

## Verifying a deployment

Run in order; each is a precondition for the next. Steps 1–5 were executed against this image on
2026-08-28 and are recorded in ROADMAP §G.

**1. The image, before any host is involved.**

```bash
docker build -t lumora-backend:test services/backend
docker run -d --name verify -p 18082:8082 \
  -e SPRING_PROFILES_ACTIVE=cloud \
  -e CLOUD_DATABASE_URL='jdbc:postgresql://host.docker.internal:5443/lumora_cloud' \
  -e CLOUD_DATABASE_USER=lumora -e CLOUD_DB_PASSWORD=lumora -e CLOUD_DB_POOL_SIZE=5 \
  lumora-backend:test
curl -s http://127.0.0.1:18082/actuator/health/readiness      # {"status":"UP"}
```

Then run it **without** `SPRING_PROFILES_ACTIVE` and watch it fail on port 5442. Seeing that once is
what makes the warning above mean something.

**2. Migrated as the cloud tier.**

```sql
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;
```

Expect `1`, then `200`–`210`, all `true`. **Any `V100`+ means the desktop profile ran.**

**3. TLS is actually on** (against the real provider, not the local container):

```sql
SELECT ssl FROM pg_stat_ssl JOIN pg_stat_activity USING (pid) WHERE datname = 'lumora_cloud';
```

Expect `true`. This is the check that catches a silent `sslmode=prefer` fallback.

**4. Deny by default.**

```
GET  /api/console/today   -> 401
POST /api/sync/batch      -> 401
GET  /actuator/health     -> 200   (no credential — the load-balancer carve-out)
```

**5. CORS is exact, not permissive.** Both halves — a passing preflight proves nothing without the
failing one.

```bash
curl -s -D - -o /dev/null -X OPTIONS <base>/api/console/auth/login \
  -H "Origin: https://storex-console.vercel.app" -H "Access-Control-Request-Method: POST"
# 200 + Access-Control-Allow-Origin echoing that exact origin

curl -s -D - -o /dev/null -X OPTIONS <base>/api/console/auth/login \
  -H "Origin: https://evil.example" -H "Access-Control-Request-Method: POST"
# 403, and no Access-Control-Allow-Origin at all
```

**6. Platform admin** — login, change the password, delete the bootstrap variable.

**7. Pilot tenant** — create it, capture `tillToken`.

**8. The till reaches the cloud** — set the two machine variables, restart, ring one sale, and
expect the row on the cloud within ~25 s (15 s initial delay plus one 10 s tick). `GET
/api/sync/status` on the till shows the outbox draining.

**9. Idempotency over a real network** — restart the till backend to force a retry of the same
batch; the cloud row count must not change. This is tested locally, but a real connection is where a
timeout leaves the client genuinely unsure whether the write landed.

**10. Cold start** — stop the keep-warm, wait 20 minutes, ring a sale, and watch it land within 5
minutes with no duplicate. **This validates the scale-to-zero decision, and it is the step most
likely to be skipped.**

**11. Gate M4** — on a **phone, on mobile data**: open the console, sign in, read today's takings,
confirm it matches the till and that the sync time is shown beside it. Per `CLAUDE.md`, the gate
ticks only once this has been *executed* — never inferred from steps 1–10 passing.

---

## Moving to AWS or Azure

Unchanged in every target: **the Dockerfile, every line of Java, all eleven cloud migrations, and
`application-cloud.yml`.** The schema needs no extensions, no partitioning and no materialized
views, so any managed Postgres 16 takes it as-is.

**AWS** — push to ECR; run on **ECS Fargate** or **App Runner** (which takes the image directly and
provides HTTPS and health checks without an ALB); **RDS for PostgreSQL 16**. Environment goes in the
task definition, with `CLOUD_DB_PASSWORD` from Secrets Manager via `secrets[]`, which injects it as
an environment variable — the payoff for having kept the three-variable form. Health check
`/actuator/health/readiness`. Leave `SERVER_PORT` unset and use `containerPort: 8082`.

**Azure** — push to ACR; **Container Apps** with `targetPort: 8082` and the password as an ACA
secret; **Azure Database for PostgreSQL Flexible Server 16**. The Dockerfile's `HEALTHCHECK` maps to
ACA's readiness and liveness probes on `/actuator/health/readiness` and `/actuator/health/liveness`,
both already exposed. ACA scales to zero exactly as Render sleeps, so
[Sleeping hosts](#sleeping-hosts) applies unchanged, keep-warm included.

**A client's own VM** — `docker run` with the five required variables, plus a reverse proxy for TLS.

**The one thing that is not just an environment variable:** neither RDS nor Azure Flexible Server
supports SCRAM channel binding. There, use `sslmode=verify-full` with the provider's CA bundle
(`sslrootcert`, or imported into the JVM truststore in a thin derived image layer) instead of
`channelBinding=require`. Everything else on this page carries over.

**No Terraform or Bicep is written yet, deliberately.** Two tenants on a free tier does not justify
it, and infrastructure code written before a target is chosen is written for the wrong target. It is
about a day's work once a client names their cloud, and it will be better for being written against
a real requirement.
