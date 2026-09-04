-- V212 — the cloud's copy of a shop's whole database (M5-06, resolves D5).
--
-- ## Why this exists when eleven aggregates already sync
--
-- The outbox carries what the cloud needs in order to *report*: sales, shifts, refunds, movements,
-- products, people. It is not, and was never meant to be, a second copy of the shop. `restore.md`
-- names the gap precisely — `invoice_counters` never leaves the till, so a shop rebuilt from the
-- cloud alone would re-issue invoice numbers that are already on paper in a customer's hand.
--
-- §A puts a shop's entire trading history on one disk under one counter. M5-04 answers the
-- accident (a bad migration, a deleted table, a corrupted index) with a local `pg_dump` twice a
-- day. It does not answer the disk, and a folder beside the data is on the same disk that died.
-- This table is the record of the copy that is somewhere else.
--
-- ## The bytes are not in here, and that is deliberate
--
-- Only the metadata. The archive itself goes to object storage, because the cloud database is a
-- 0.5 GB Neon free tier holding every shop's ledger and a single shop's dumps would evict it.
-- `object_key` is where the store put it, opaque to this table on purpose: a filesystem store and
-- an S3 store write different keys, and the row must stay true across a change of either.
--
-- ## The uniqueness is idempotency, not tidiness
--
-- An upload that times out after the server wrote the object is indistinguishable, from the till,
-- from one that never arrived — so the till retries, and it must be safe. `(tenant_id,
-- terminal_code, name)` is the same thinking as `client_uuid` everywhere else: the till names the
-- archive once, and re-presenting that name is the same backup and not a new one.
--
-- `terminal_code` is in the key rather than assumed: v2 is multi-terminal, two tills dump their
-- shared database at the same minute, and the names would collide on the wall clock alone.

CREATE TABLE tenant_backups (
    id            bigserial   PRIMARY KEY,
    tenant_id     bigint      NOT NULL REFERENCES tenants (id),
    terminal_code text        NOT NULL,

    -- The archive's own file name on the shop PC, e.g. `storex-2026-09-01T0314.dump`. Kept so a
    -- support call can name the same file on both ends of the wire. Never used as a path — see
    -- CloudBackupService, which builds the key itself and refuses a name that is not this shape.
    name          text        NOT NULL,

    -- When the shop took it, from the till's clock, against when we received it, from ours. Both,
    -- because they answer different questions: the first is how old the data is, the second is how
    -- long a till has been failing to reach us. A till with a wrong clock makes the first useless
    -- and the second still true.
    taken_at      timestamptz NOT NULL,
    received_at   timestamptz NOT NULL DEFAULT now(),

    bytes         bigint      NOT NULL,

    -- Computed by the till before sending and again by the cloud while streaming. Stored so a
    -- restore can prove the archive it just downloaded is the archive the shop made, rather than
    -- one that a truncated upload or a bad disk quietly altered.
    sha256        text        NOT NULL,

    object_key    text        NOT NULL
);

CREATE UNIQUE INDEX ux_cloud_backups_tenant_terminal_name
    ON tenant_backups (tenant_id, terminal_code, name);

-- Both reads this table serves: "what is the newest copy of this shop" and "what should retention
-- delete". Descending, because nobody has ever wanted the oldest one first.
CREATE INDEX ix_cloud_backups_tenant_taken
    ON tenant_backups (tenant_id, taken_at DESC);

COMMENT ON TABLE tenant_backups IS
    'Metadata for a shop database archive held in object storage (M5-06). The bytes are never in Postgres. A row here without its object is a lie, so the object is written first and the row second.';
