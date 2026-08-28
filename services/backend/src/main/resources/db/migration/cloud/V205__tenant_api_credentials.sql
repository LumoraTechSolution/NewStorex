-- V205 — the credential that says which tenant a batch belongs to (M4-01).
--
-- Cloud tier only. V200 gave every synced table a `tenant_id`, which is the *schema* half of
-- multi-tenancy. This migration is the other half, and without it the first half is decoration:
-- until now the tenant arrived in the request body as `tenantClientUuid`, self-registering on
-- first sight, so anything that could reach the port could invent a tenant or — far worse — name
-- an existing one and write into it. `tenant_id` isolates nothing if the caller picks it.
--
-- After this, the tenant is derived from the credential and the payload cannot express one at all.
-- That is deliberately stronger than validating the body's claim against the credential: a field
-- that is checked in one place is a field somebody later reads in another, and the safest version
-- of a dangerous input is the one that does not exist.
--
-- ## Why a hash, and why SHA-256 rather than BCrypt
--
-- The stored value is a hash so that a dump of this table is not a set of working keys.
--
-- BCrypt is the right choice for the PINs in V109 and the wrong one here, for two reasons. The
-- token is 256 bits of CSPRNG output rather than something a human chose, so there is no guessing
-- attack for a slow hash to frustrate — the work factor would buy nothing. And BCrypt salts per
-- row, which makes "find the credential matching this token" a scan of every row with a BCrypt
-- verify on each. A deterministic hash makes it one unique-index probe, on the path every batch
-- from every till takes.
--
-- ## Why per credential rather than per tenant
--
-- v1 is a single till, so this could have been one column on `tenants`. It is a table because
-- revocation is the whole point of having a credential: a shop that adds a second till, or
-- replaces a stolen one, must be able to cut off exactly that machine without re-keying the
-- terminals that are still fine. That is impossible if the shop and the credential are the same
-- row, and retrofitting it means re-keying every till in the field at once.

-- ---------------------------------------------------------------------------
-- A tenant the cloud has never heard of is now a rejection rather than a row, so a tenant has to
-- be provisioned before a till can push. `active` is what suspends one without deleting its
-- history — a lapsed licence must stop ingest, not erase a shop's year of sales (M4-08, M4-09).
ALTER TABLE tenants ADD COLUMN active boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN tenants.active IS
    'False suspends ingest for this tenant without touching a row of its history. Set by super-admin (M4-08).';

-- ---------------------------------------------------------------------------
CREATE TABLE tenant_api_credentials (
    id         bigserial PRIMARY KEY,
    tenant_id  bigint    NOT NULL REFERENCES tenants (id),

    -- Which machine holds this token, in words a person can act on. A revocation screen listing
    -- four hashes is a screen nobody dares press a button on.
    label      text      NOT NULL,

    -- The first few characters of the token, stored in clear. It identifies a credential in a log
    -- line or a support call without the log line being a working key. Too short to narrow a
    -- 256-bit search usefully; long enough to tell four tills apart.
    token_prefix text    NOT NULL,

    -- Hex SHA-256 of the whole token. The plaintext is shown once, at provisioning, and is not
    -- recoverable from here — a lost token is reissued, never looked up.
    token_hash text      NOT NULL,

    created_at   timestamptz NOT NULL DEFAULT now(),
    -- Last successful authentication. `sales.received_at` says when data last arrived; this says
    -- when the till last *tried*, which is the difference between "the shop is closed" and "the
    -- shop is open and something is broken".
    last_seen_at timestamptz,
    -- Revoked rather than deleted: the row is the evidence that this key existed and was cut off.
    revoked_at   timestamptz
);

-- The lookup the auth path makes on every batch, and the guarantee that one token means one
-- tenant. A collision here would be a token authenticating as two shops.
CREATE UNIQUE INDEX ux_tenant_api_credentials_token_hash ON tenant_api_credentials (token_hash);
CREATE INDEX ix_tenant_api_credentials_tenant ON tenant_api_credentials (tenant_id);

COMMENT ON TABLE tenant_api_credentials IS
    'Bearer tokens a till presents to /api/sync. The tenant is derived from this row and never from the request body (M4-01).';
