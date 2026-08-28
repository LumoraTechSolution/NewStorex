-- V109 — users, roles and PINs (M3-08).
--
-- Every `created_by` column written since V100 has carried the same comment: "real FK arrives
-- with M3-08". This is that migration. It creates the table those columns have been pointing at,
-- repoints the existing rows at a real person, and turns the constraint on — so from here an
-- audit trail that names nobody is a write that fails rather than a row that lies.
--
-- ## Identification is code + PIN, not PIN alone
--
-- A PIN alone would be friendlier: the manager walks up, types four digits, the refund goes
-- through. It was rejected for two reasons, and the second is the one that settles it.
--
--   1. Verifying a PIN alone means BCrypt-comparing it against every active user's hash, because
--      a hash cannot be looked up. That is one deliberately-slow comparison per user per attempt.
--      BCrypt is slow on purpose and that is the whole reason it is here.
--   2. Two people are allowed to choose 1234. If a PIN alone identified a user, the first
--      matching row wins and `authorised_by` then names whoever the scan happened to reach
--      first. An audit trail that can credit the wrong person for authorising a refund is worse
--      than no audit trail, because it will be believed.
--
-- So `users.code` identifies and the PIN authenticates. The code is short and typed on the same
-- numeric keypad — an employee number, not a username.
--
-- ## Roles are an enum, not a permission table
--
-- There is no `permissions` table and no `role_permissions` join. What a role may do is a `switch`
-- in `Role.java`, in version control, reviewed and tested. A shop with one till and four staff
-- does not need per-permission configuration; what it needs is for the four roles to mean the
-- same thing on every machine. A permission table would let one shop's MANAGER quietly differ
-- from another's, which is unanswerable when a support call asks why a refund was refused.
--
-- The CHECK below is the same list as the enum. It is duplicated deliberately: the database is
-- the last line, and a role string no code can produce should not be storable.
--
-- ## The shop-wide manager PIN is gone, not deprecated
--
-- `tenant_settings.manager_pin_hash` (M2-07) is migrated into a real MANAGER user and the column
-- is dropped. Leaving it would mean two credential stores for the same gate, and the failure mode
-- of two credential stores is that one of them is forgotten at rotation time and keeps working.
-- The hash moves across intact, so the PIN a shop already uses is unchanged.

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id           bigserial   PRIMARY KEY,
    client_uuid  uuid        NOT NULL,
    tenant_id    bigint      NOT NULL REFERENCES tenants (id),

    -- Typed on a numeric keypad to identify who is acting. Unique per tenant, case-folded on
    -- the way in so "amila" and "AMILA" cannot be two people.
    code         text        NOT NULL CHECK (code = upper(code) AND length(code) BETWEEN 1 AND 16),
    display_name text        NOT NULL CHECK (length(trim(display_name)) > 0),

    role         text        NOT NULL CHECK (role IN ('CASHIER', 'SUPERVISOR', 'MANAGER', 'OWNER')),

    -- BCrypt. NOT NULL: a user with no PIN is a user who cannot be authenticated, and the way to
    -- express "this person may not act" is `active = false`, which says so.
    pin_hash     text        NOT NULL,

    -- Deactivated, never deleted. Every audit column in this schema is an FK to this table, so a
    -- DELETE would either fail or orphan history; and "who authorised that refund last March" has
    -- to keep answering after somebody leaves.
    active       boolean     NOT NULL DEFAULT true,

    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_users_client_uuid ON users (client_uuid);
CREATE UNIQUE INDEX ux_users_tenant_code ON users (tenant_id, code);

-- The authorisation path filters to active users of a tenant on every refund.
CREATE INDEX ix_users_tenant_active ON users (tenant_id) WHERE active;

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE users IS
    'Shop staff. Rows are deactivated, never deleted - every created_by/authorised_by column in this schema references this table and history must keep resolving after somebody leaves.';
COMMENT ON COLUMN users.code IS
    'Identifies; the PIN authenticates. Unique per tenant and stored upper-case. See the V109 header for why a PIN alone does not identify.';
COMMENT ON COLUMN users.role IS
    'CASHIER < SUPERVISOR < MANAGER < OWNER. What each may do lives in Role.java, not in a permission table.';

-- ---------------------------------------------------------------------------
-- Carry the M2 manager PIN across
-- ---------------------------------------------------------------------------
-- One MANAGER per tenant that had a PIN set, holding the same hash. `MGR` is a code, not a name:
-- the shop renames it in the back office once real staff exist.
INSERT INTO users (client_uuid, tenant_id, code, display_name, role, pin_hash)
SELECT gen_random_uuid(), s.tenant_id, 'MGR', 'Manager', 'MANAGER', s.manager_pin_hash
FROM tenant_settings s
WHERE s.manager_pin_hash IS NOT NULL;

-- Every tenant needs at least one user that can reach the back office, including a tenant that
-- never set a manager PIN. That user gets a hash no PIN can satisfy: BCrypt-shaped, right down to
-- the 53-character salt-and-digest tail, but not the hash of anything. The shape matters - a
-- malformed hash makes Spring's encoder log "does not look like BCrypt" on every attempt, and a
-- gate that fails closed should do it quietly. The row is also inactive, so authentication
-- refuses it before the comparison is ever reached.
--
-- The shop therefore cannot get in until the first-run wizard (M5-03) or a reseed sets a real
-- PIN. That is the correct direction to fail in: a lockout is visible and fixable, an account
-- with a guessable default PIN is neither. The row exists at all because the backfill below
-- needs one user per tenant to point at.
INSERT INTO users (client_uuid, tenant_id, code, display_name, role, pin_hash, active)
SELECT gen_random_uuid(), t.id, 'MGR', 'Manager', 'MANAGER',
       '$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', false
FROM tenants t
WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.tenant_id = t.id);

ALTER TABLE tenant_settings DROP COLUMN manager_pin_hash;

-- ---------------------------------------------------------------------------
-- Repoint the audit columns, then constrain them
-- ---------------------------------------------------------------------------
-- Rows written before this migration carry LocalShop.SEEDED_OPERATOR_ID (the literal 1), which
-- was never a real id - only a promise that one would arrive. Point them all at the tenant's
-- manager before adding the FK, or the constraint fails on the first shop that has ever sold
-- anything.
--
-- This is a backfill of a placeholder, not a rewrite of history: the rows never named anybody, so
-- nothing true is being overwritten. It is the last moment this is possible - after the FK exists,
-- every row names a real person.

UPDATE stock_movements m
   SET created_by = (SELECT u.id FROM users u WHERE u.tenant_id = m.tenant_id ORDER BY u.id LIMIT 1)
 WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = m.created_by);

UPDATE shifts s
   SET opened_by = (SELECT u.id FROM users u WHERE u.tenant_id = s.tenant_id ORDER BY u.id LIMIT 1)
 WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = s.opened_by);

UPDATE shifts s
   SET closed_by = (SELECT u.id FROM users u WHERE u.tenant_id = s.tenant_id ORDER BY u.id LIMIT 1)
 WHERE s.closed_by IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = s.closed_by);

UPDATE cash_movements c
   SET created_by = (SELECT u.id FROM users u WHERE u.tenant_id = c.tenant_id ORDER BY u.id LIMIT 1)
 WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = c.created_by);

UPDATE refunds r
   SET authorised_by = (SELECT u.id FROM users u WHERE u.tenant_id = r.tenant_id ORDER BY u.id LIMIT 1)
 WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = r.authorised_by);

UPDATE refunds r
   SET created_by = (SELECT u.id FROM users u WHERE u.tenant_id = r.tenant_id ORDER BY u.id LIMIT 1)
 WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = r.created_by);

ALTER TABLE stock_movements ADD CONSTRAINT fk_stock_movements_created_by
    FOREIGN KEY (created_by) REFERENCES users (id);
ALTER TABLE shifts ADD CONSTRAINT fk_shifts_opened_by
    FOREIGN KEY (opened_by) REFERENCES users (id);
ALTER TABLE shifts ADD CONSTRAINT fk_shifts_closed_by
    FOREIGN KEY (closed_by) REFERENCES users (id);
ALTER TABLE cash_movements ADD CONSTRAINT fk_cash_movements_created_by
    FOREIGN KEY (created_by) REFERENCES users (id);
ALTER TABLE refunds ADD CONSTRAINT fk_refunds_authorised_by
    FOREIGN KEY (authorised_by) REFERENCES users (id);
ALTER TABLE refunds ADD CONSTRAINT fk_refunds_created_by
    FOREIGN KEY (created_by) REFERENCES users (id);
