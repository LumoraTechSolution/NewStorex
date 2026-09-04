# Restoring a shop (M5-05)

The procedure for the morning a till does not boot, or boots with its data gone.

Read the first section before touching anything. The commonest way to lose a shop's history for
good is a hurried restore over a database that was recoverable.

---

## 0. Before you restore anything

**Do not run a restore against the shop's live data directory.** Restore into a new database and
switch to it once you have looked at what came back. A restore that overwrites is a restore you
cannot undo, and the archive you are trusting has not been read yet.

**Take a copy of the broken thing first.** Even a corrupt `pgdata` folder is evidence, and it is
sometimes recoverable by someone who has seen the failure before. Copy
`%LOCALAPPDATA%\StoreX\pgdata` somewhere else before anything writes to it.

**Check whether you actually need a restore.** A till that will not start is usually not a till
that has lost data — see [Is the data really gone?](#is-the-data-really-gone) at the bottom.

---

## 1. What exists to restore from

| Source                     | Where                                                   | Holds                                       |
| -------------------------- | ------------------------------------------------------- | ------------------------------------------- |
| **Local backups (M5-04)**  | `%LOCALAPPDATA%\StoreX\backups`, or `LUMORA_BACKUP_DIR` | Everything, up to 12 hours old              |
| **Cloud archives (M5-06)** | Object storage, uploaded by the till once a day         | Everything, up to 24 hours old              |
| **The synced aggregates**  | Neon, via the sync path                                 | Everything that had synced — see below      |
| **`pgdata`**               | `%LOCALAPPDATA%\StoreX\pgdata`                          | The live database, if it is readable at all |

Backups are taken twice a day and kept for 30 days. They are written as `pg_dump` custom-format
archives named `storex-2026-09-01T0314.dump`, which sort chronologically — **the last file in the
folder is the newest.**

### The cloud holds two different things, and only one of them is a whole shop

This distinction has cost people their invoice numbering elsewhere, so it is worth being slow
about.

**A cloud archive (M5-06) is a whole shop.** It is the till's own `pg_dump` output, uploaded once
a day with the shop's till credential and kept for fourteen days. Restoring one is the _same_
procedure as [restoring from a local backup](#2-restore-from-a-local-backup) — same file format,
same commands, same result — the only difference being that you download it first. If a local
backup exists, prefer it: it is at most twelve hours old against the archive's twenty-four. If the
disk is gone, this is the route, and it is a complete one.

**The synced aggregates are not.** They are what the console reports from, and they are described
below. Rebuilding a shop out of them is a last resort with a real hazard in it.

### What the synced aggregates do and do not hold

The cloud is a real second copy and it is **not** a complete one. The outbox replicates these
aggregates:

`sale` · `refund` · `shift` · `cash_movement` · `stock_adjustment` · `goods_receipt` ·
`stocktake` · `product` · `customer` · `user` · `tax_invoice`

It does **not** carry: the shop's settings row (VAT identity, variance threshold, terminal code),
its branches, its invoice counters, its sessions, or its PIN attempt history.

So a shop rebuilt **from the aggregates** alone has its trading history and no identity — and
critically, **no invoice counters**, which means it would re-issue numbers it has already printed.
That hazard is the reason M5-06 exists: a cloud archive has the counters in it, because it is a
dump of the whole database and not a selection from it.

The order of preference is therefore:

1. **A local backup** — newest, and needs no network.
2. **A cloud archive** — complete, at most a day old, and survives the disk.
3. **The synced aggregates** — history without identity. Read
   [Rebuilding from the cloud](#4-rebuilding-from-the-cloud) rather than improvising, and expect to
   set the invoice counters by hand.

### Fetching a cloud archive

Ask whoever administers the cloud; there is deliberately no button on the till for this. A till can
_write_ an archive and cannot list or read one back, because the credential that does the uploading
sits on a shop-floor PC and a stolen one must not be able to download the shop's entire database.

The archives are stored under `tenant-<id>/<terminal>/storex-<date>T<time>.dump`, and the
`tenant_backups` table in the cloud database records every one with its size and its SHA-256:

```sql
SELECT terminal_code, name, taken_at, bytes, sha256
  FROM tenant_backups
 WHERE tenant_id = <the shop>
 ORDER BY taken_at DESC;
```

**Check the digest after downloading**, before you restore from it:

```powershell
Get-FileHash -Algorithm SHA256 .\storex-2026-09-01T0314.dump
```

If it does not match the `sha256` column, do not restore from that file — take the next one down
the list and say so to whoever maintains the cloud.

---

## 2. Restore from a local backup

Everything below uses the Postgres tools that ship inside the app, so nothing needs installing.

StoreX installs per-machine, so the tools are under Program Files while the shop's data is under
the user's `LOCALAPPDATA` — the two are deliberately separate, which is what lets an uninstall keep
a shop's history.

```powershell
$config  = Get-Content "$env:LOCALAPPDATA\StoreX\config\runtime.json" | ConvertFrom-Json
$pg      = "${env:ProgramFiles}\StoreX\resources\runtime\pgsql\bin"
$backups = if ($env:LUMORA_BACKUP_DIR) { $env:LUMORA_BACKUP_DIR } else { "$env:LOCALAPPDATA\StoreX\backups" }
$port    = $config.dbPort
$env:PGPASSWORD = $config.dbPassword
```

If `$pg` does not exist, find it — a 32-bit install lands in `${env:ProgramFiles(x86)}`:

```powershell
Get-ChildItem "$env:ProgramFiles","${env:ProgramFiles(x86)}" -Filter 'pg_restore.exe' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 FullName
```

**Pick the archive.** Newest last:

```powershell
Get-ChildItem $backups -Filter 'storex-*.dump' | Sort-Object Name | Select-Object -Last 5
```

**Read it before trusting it.** This lists the archive's contents without restoring anything. If
it errors, the file is damaged — try the one before it:

```powershell
& "$pg\pg_restore.exe" --list "$backups\storex-2026-09-01T0314.dump" | Select-String 'TABLE DATA' | Measure-Object
```

A real shop's archive lists dozens of `TABLE DATA` entries. Two or three means you are looking at
an almost-empty database.

**Restore into a new database**, not over the live one:

```powershell
& "$pg\psql.exe" -h 127.0.0.1 -p $port -U lumora -d postgres -c "CREATE DATABASE lumora_restored"
& "$pg\pg_restore.exe" -h 127.0.0.1 -p $port -U lumora -d lumora_restored --no-owner "$backups\storex-2026-09-01T0314.dump"
```

**Look at what came back** before switching anything over:

```powershell
& "$pg\psql.exe" -h 127.0.0.1 -p $port -U lumora -d lumora_restored -c "
  SELECT (SELECT count(*) FROM sales)  AS sales,
         (SELECT max(sold_at) FROM sales) AS last_sale,
         (SELECT count(*) FROM products) AS products,
         (SELECT name FROM tenants LIMIT 1) AS shop"
```

Check the shop name is the right shop and the last sale is roughly when you expect. **If the shop
name is wrong, stop** — you are holding another shop's backup.

**Switch to it.** Close StoreX first, then:

```powershell
& "$pg\psql.exe" -h 127.0.0.1 -p $port -U lumora -d postgres -c "ALTER DATABASE lumora_local RENAME TO lumora_broken"
& "$pg\psql.exe" -h 127.0.0.1 -p $port -U lumora -d postgres -c "ALTER DATABASE lumora_restored RENAME TO lumora_local"
```

Start StoreX. Keep `lumora_broken` until the shop has traded normally for a few days.

---

## 3. After any restore — the part people forget

**Check the invoice counter.** This is the one that causes real damage if it is wrong. A restore
from a 12-hour-old backup puts the counter back 12 hours, and the till will re-issue numbers that
are already on printed receipts — which for an IRD document is a compliance problem, not a
cosmetic one.

```powershell
& "$pg\psql.exe" -h 127.0.0.1 -p $port -U lumora -d lumora_local -c "
  SELECT terminal_code, doc_type, next_seq FROM invoice_counters ORDER BY doc_type"
```

Compare against the highest number actually issued — the cloud knows, if the till was syncing:

```sql
-- against the cloud database
SELECT max(invoice_number) FROM sales WHERE tenant_id = <id>;
```

If the cloud is ahead, move the counter past it. `next_seq` is the next number to hand out:

```sql
UPDATE invoice_counters SET next_seq = <highest issued + 1>
 WHERE tenant_id = <id> AND terminal_code = 'T1' AND doc_type = 'INVOICE';
```

This is deliberately manual. `InvoiceNumberAllocator` never widens or moves a block on its own —
M1-12 left that seam open precisely so a recovery can reserve a range explicitly, and a machine
guessing at it is how two receipts end up with one number.

**Then check, in order:**

- The shop name and till code in the header are right (M5-03 shows both).
- The cloud shop name in the header matches — a mismatch means the wrong token, not a bad restore.
- The outbox: `SELECT count(*) FROM outbox WHERE acked_at IS NULL`. A large number is normal after
  a restore and should fall on its own. It is `acked_at` rather than `sent_at` because a row is
  cleared when the cloud confirms it, not when the till posts it.
- Open a shift and ring up a one-item sale. The invoice number should be past everything printed.

---

## 4. Rebuilding from the cloud

Only when there is no usable local backup. The cloud holds trading history and not the shop's
identity, so this is a rebuild rather than a restore.

1. **Install StoreX and run the first-run wizard** (M5-03) with the shop's real details — the same
   branch code and **the same till code**. Getting the till code wrong here starts a second
   invoice sequence.
2. **Do not enter the cloud token yet.** A till that syncs before its counters are set will push
   sales that collide with what the cloud already has.
3. **Set the invoice counters past what the cloud holds**, using the query in section 3.
4. Enter the token, restart, and let the outbox drain.

Sales that were already in the cloud are not pulled back down — v1 syncs one way. The cloud
remains the record of them, and the console still reports them correctly. The till starts from the
day it was rebuilt.

---

## Is the data really gone?

Most "the till is broken" mornings are not data loss. Check these first:

| Symptom                             | Likely cause                      | Where to look                                  |
| ----------------------------------- | --------------------------------- | ---------------------------------------------- |
| Splash sticks on "database"         | Postgres did not start            | `%LOCALAPPDATA%\StoreX\logs\startup.log`       |
| "could not reach its own database"  | Backend up, database not          | `logs\backend.log`                             |
| Setup wizard appears on a live shop | Wrong database, **not** lost data | Stop. Do not complete it — see below.          |
| Sales missing from the console      | Sync, not the till                | The header's cloud shop name; the outbox count |

**The setup wizard appearing on a shop that already exists is the dangerous one.** It means the
backend is talking to an empty database — usually the wrong one, or a `pgdata` that did not mount.
Completing the wizard creates a second shop and a second invoice sequence. Close the app and find
the real database first.

---

## What is verified, and what is not

The dump-and-restore path in this document is exercised on every `pnpm test` run by
`apps/terminal/electron/backup.test.js`, against the real bundled `pg_dump.exe` and a real
Postgres: it dumps a table, drops it, restores into a database that never had it, and asserts the
money adds up to what went in.

**What no test covers is this document.** M5-05 says "actually tested", and that means a person
following these steps on a real installed till, with a real backup, and getting a working shop at
the end. Until somebody has done that, treat the procedure as unproven and read each command
before running it.
