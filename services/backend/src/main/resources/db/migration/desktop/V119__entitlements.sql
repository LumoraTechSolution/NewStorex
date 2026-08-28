-- V119 — what the cloud last told this till it may do (M4-09).
--
-- Desktop tier. The first and only row of downward state in v1. Everything else in this schema is
-- written by the shop and read by the cloud; this is written by the cloud and read by the shop,
-- and the reason that inversion is safe here is that nothing about a sale depends on it.
--
-- ## Why it is stored at all, rather than asked for when needed
--
-- Because the network is on the critical path of nothing (§A), and a capability check that has to
-- reach the cloud is a capability check that fails during an outage. Asking at the moment the
-- cashier presses a key would make the back office stop working exactly when the internet does,
-- which is the failure this whole architecture exists to prevent. So the answer is cached, and the
-- cache is read.
--
-- ## The cache does not expire, and that is deliberate
--
-- There is no `valid_until` here and no sweeper that clears a stale row. A shop offline for a
-- fortnight keeps every capability it had when it was last online. The alternative — expiring the
-- cache and withdrawing features — would put the network back on the critical path of the back
-- office through the side door, and would punish an outage the shop did not cause.
--
-- Staleness is still legible: `checked_at` says when the cloud last answered, and the screen shows
-- it. Legible and enforced are different things, and only one of them is compatible with §A.
--
-- ## No row means everything is allowed
--
-- A till that has never synced — a fresh install, a shop mid-activation, the whole of M0 through
-- M3 — has no row here, and `EntitlementStore` reads that as full capability rather than none.
-- Defaulting the other way would mean a brand-new till boots with its back office switched off
-- until a network call it has not been configured for succeeds, which is a shop that cannot open.
--
-- The commercial lever is not this table. It is ingest: V209 made a lapsed licence stop the till's
-- data reaching the cloud, and that is the consequence that has teeth. The flags here shape a
-- screen; they are not a lock, and a lock is not what they should ever become — see the licensed
-- guard on the flag write in EntitlementStore for the matching rule about a lapse.

CREATE TABLE entitlements (
    -- One row per tenant, and a desktop database holds exactly one tenant, so this table holds at
    -- most one row. Keyed on the tenant anyway rather than on a hardcoded id: LocalShop asserts
    -- the single-tenant invariant in one place and nothing else should re-assume it.
    tenant_id           bigint      PRIMARY KEY REFERENCES tenants (id),

    -- Whether a licence covered now when the cloud last answered. A cached fact about a past
    -- moment, not a live predicate — the live one lives in the cloud, on the append-only rows.
    licensed            boolean     NOT NULL,

    -- The plan of the covering licence, or of the most recent one when it has lapsed. Null only
    -- for a tenant the cloud has no licence row for at all.
    plan_code           text,
    plan_name           text,
    licence_starts_at   timestamptz,
    licence_expires_at  timestamptz,

    -- Recorded and shown, never enforced here. V209 already says they are advisory in v1, and a
    -- limit enforced by the machine being limited is not a limit.
    max_terminals       integer,
    max_users           integer,

    -- When the cloud answered. The whole of what staleness means on this till.
    checked_at          timestamptz NOT NULL DEFAULT now(),

    -- When it last answered *yes*. Kept apart from checked_at so the screen can say "licensed
    -- until the 14th, checked five minutes ago" and "lapsed on the 14th" with the same row.
    licensed_at         timestamptz
);

COMMENT ON TABLE entitlements IS
    'The cloud''s last answer about this shop''s plan and licence. Cached, never expiring — see the V119 header (M4-09).';

-- The capability names that answer came with.
--
-- No foreign key to a registry, and no CHECK on the shape: the registry is `feature_flags` in the
-- cloud, and this till must be able to cache a capability name from a cloud build newer than
-- itself without rejecting the whole answer. Same reasoning as the JsonNode payload on a sync
-- item — an unrecognised name here is simply a flag nothing asks about yet.
CREATE TABLE entitlement_flags (
    tenant_id bigint NOT NULL REFERENCES tenants (id),
    flag_code text   NOT NULL,
    PRIMARY KEY (tenant_id, flag_code)
);

COMMENT ON TABLE entitlement_flags IS
    'Effective capability names from the last licensed answer. Replaced wholesale, and only when licensed (M4-09).';
