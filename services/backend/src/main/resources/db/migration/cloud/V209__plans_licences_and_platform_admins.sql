-- V209 — plans, licences, feature flags, and somebody empowered to set them (M4-08).
--
-- Cloud tier. This closes the hole V205 and V208 both left open on purpose and both named in
-- their headers: they stopped tenants self-registering and gave owners a login, but nothing can
-- *create* either. Until now a shop existed only if somebody ran Java, which meant no real shop
-- could be signed up at all. Everything here exists so that a person can do it from a screen.
--
-- ## Four kinds of thing, and why they are four tables and not one
--
-- It is tempting to put `plan`, `expires_at` and a few booleans on `tenants` and be done. That
-- collapses four different questions into one row and loses the answer to three of them:
--
--   * A **plan** is a product — what is sold, at what price, with which features. It is shared by
--     every tenant on it, so a price change is one row rather than a migration over the estate.
--   * A **licence** is a period. It is append-only, so "when did this shop start paying, and has
--     it lapsed before?" is a query rather than a lost fact. A column that gets overwritten on
--     every renewal is a column that has never known a shop's history.
--   * A **feature flag** is a capability name. It is a registry table so that a typo is a foreign
--     key violation rather than a flag that is silently off forever.
--   * An **override** is one tenant differing from its plan — a trial extension, a feature turned
--     on for a pilot. Kept apart from the plan so that granting one shop something does not mean
--     inventing a private plan for it, which is how a plan list reaches forty rows.
--
-- ## Licences follow §A's shape, not a level
--
-- §A says never sync a level, sync the movements that produce it. A licence period is the same
-- idea one layer up: the *state* "licensed until March" is derived from the grants, never stored
-- and mutated. Renewing appends. Nothing is ever updated, so nothing can be lost by a bad write,
-- and a billing dispute is answered by reading the table.
--
-- ## What a lapsed licence does, and what it deliberately does not do
--
-- A lapsed licence **stops ingest** — the till's token stops authenticating, so the shop keeps
-- selling locally (it always could; that is the whole architecture) and its data stops reaching
-- the cloud. It does **not** stop the owner reading the console.
--
-- That asymmetry is deliberate, and it is a correction to what V205's and V208's headers assumed
-- when neither licences nor a renewal notice existed. Cutting a shop's sync is the commercial
-- lever. Cutting an owner out of their own takings is taking their data hostage, and it also
-- removes the one screen that could tell them why and how to fix it. `tenants.active = false` —
-- a deliberate suspension by a person — still stops both, and remains the blunt instrument it was.

-- ---------------------------------------------------------------------------
-- Somebody to do all of this. A third credential kind, and the first one that is not confined to
-- a single tenant — see AuthenticatedPrincipal on why the kinds are never collapsed into one.
--
-- Deliberately not a row in `console_users` with an `is_admin` column. That column would make
-- every tenant-scoped query one forgotten predicate away from crossing shops, and a mistake in
-- this direction is not visible in a response — it simply succeeds.
CREATE TABLE platform_admins (
    id            bigserial   PRIMARY KEY,

    -- Same normalisation rule as V208's console_users, enforced the same way, so there is one
    -- rule about email in this schema rather than two that drift.
    email         text        NOT NULL CHECK (email = lower(trim(email)) AND position('@' in email) > 1),

    -- BCrypt, like V208. A human chose it, so the work factor is doing real work.
    password_hash text        NOT NULL,

    display_name  text        NOT NULL,

    -- Staff leave. False keeps every audit row they wrote pointing at a real person.
    active        boolean     NOT NULL DEFAULT true,

    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz
);

CREATE UNIQUE INDEX ux_platform_admins_email ON platform_admins (email);

CREATE TRIGGER trg_platform_admins_updated_at BEFORE UPDATE ON platform_admins
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE platform_admins IS
    'Lumora staff who can create and licence tenants. Not tenant-scoped, and never a console_user (M4-08).';

-- ---------------------------------------------------------------------------
-- Opaque bearer sessions, exactly like V208's and for the same reason: the verifier is beside the
-- database, so a signature buys nothing and revocability is worth everything.
--
-- The TTL is shorter than the owner's seven days, and that is part of why this is its own table.
-- A stolen console session leaks one shop's takings; a stolen platform session can licence,
-- suspend and re-key every shop on the system. The blast radius differs by orders of magnitude,
-- so the window does too.
CREATE TABLE platform_sessions (
    id                bigserial   PRIMARY KEY,
    token_hash        text        NOT NULL,
    platform_admin_id bigint      NOT NULL REFERENCES platform_admins (id),
    issued_at         timestamptz NOT NULL DEFAULT now(),
    expires_at        timestamptz NOT NULL,
    revoked_at        timestamptz,
    revoked_why       text,
    last_seen_at      timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_platform_sessions_token_hash ON platform_sessions (token_hash);
CREATE INDEX ix_platform_sessions_admin ON platform_sessions (platform_admin_id, issued_at DESC);
CREATE INDEX ix_platform_sessions_live ON platform_sessions (expires_at) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------
-- What is sold.
CREATE TABLE plans (
    id            bigserial   PRIMARY KEY,

    -- The stable handle. Code refers to a plan by this, never by id, so a plan can be renamed for
    -- marketing without a deploy.
    code          text        NOT NULL CHECK (code = lower(trim(code)) AND code <> ''),

    name          text        NOT NULL,
    description   text        NOT NULL DEFAULT '',

    -- Integer minor units, per §A. A monthly price in LKR cents. Zero is legitimate: the trial
    -- plan below is free, and free is a price.
    price_minor   bigint      NOT NULL DEFAULT 0 CHECK (price_minor >= 0),

    -- NULL means no limit. A limit of zero would mean a plan that cannot run a till, which is not
    -- a thing anyone should be able to create by leaving a field blank.
    max_terminals integer     CHECK (max_terminals IS NULL OR max_terminals > 0),
    max_users     integer     CHECK (max_users IS NULL OR max_users > 0),

    -- False retires a plan from the list new tenants can be put on, without disturbing the
    -- tenants already licensed under it. Withdrawing a product must never re-price its customers.
    active        boolean     NOT NULL DEFAULT true,

    sort_order    integer     NOT NULL DEFAULT 100,

    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_plans_code ON plans (code);

CREATE TRIGGER trg_plans_updated_at BEFORE UPDATE ON plans
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN plans.max_terminals IS
    'Advisory in v1: recorded and shown, not enforced at credential issue. See the V209 header.';

-- ---------------------------------------------------------------------------
-- The registry of capability names.
--
-- A table rather than free text on the override row. A flag is referenced from two places and
-- typed by a human in one; with free text, `stock_take` and `stocktake` are two flags, one of
-- which is off forever and neither of which reports a problem. A foreign key makes that a failed
-- write at the moment somebody makes the mistake.
CREATE TABLE feature_flags (
    code        text        PRIMARY KEY
                CHECK (code = lower(trim(code)) AND code ~ '^[a-z][a-z0-9_]*$'),
    name        text        NOT NULL,
    description text        NOT NULL DEFAULT '',
    created_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE feature_flags IS
    'Known capability names. Declared in M4-08; nothing on the till reads them until M4-09 pulls them down.';

-- Which flags a plan includes.
CREATE TABLE plan_features (
    plan_id   bigint NOT NULL REFERENCES plans (id) ON DELETE CASCADE,
    flag_code text   NOT NULL REFERENCES feature_flags (code),
    PRIMARY KEY (plan_id, flag_code)
);

-- ---------------------------------------------------------------------------
-- One tenant differing from its plan, in either direction.
--
-- `enabled` is a boolean rather than the row's presence meaning "on", because an override has to
-- be able to take something *away* as well — a plan feature withdrawn from one shop that abused
-- it. Presence-means-on can only ever add.
CREATE TABLE tenant_feature_overrides (
    tenant_id  bigint      NOT NULL REFERENCES tenants (id),
    flag_code  text        NOT NULL REFERENCES feature_flags (code),
    enabled    boolean     NOT NULL,

    -- Why somebody did this. Six months later this column is the difference between a deliberate
    -- decision and a row nobody dares touch.
    note       text        NOT NULL DEFAULT '',

    set_by     bigint      REFERENCES platform_admins (id),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, flag_code)
);

CREATE TRIGGER trg_tenant_feature_overrides_updated_at BEFORE UPDATE ON tenant_feature_overrides
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- The periods a shop is paid up for. Append-only: renewing inserts, and nothing here is ever
-- updated. See the header on why this is not two columns on `tenants`.
CREATE TABLE tenant_licences (
    id         bigserial   PRIMARY KEY,
    tenant_id  bigint      NOT NULL REFERENCES tenants (id),
    plan_id    bigint      NOT NULL REFERENCES plans (id),

    starts_at  timestamptz NOT NULL DEFAULT now(),

    -- NOT NULL on purpose. A nullable end date means every query about licensing carries an
    -- `OR expires_at IS NULL` that somebody eventually forgets, and it means a perpetual licence
    -- is one missed keystroke away. A licence that should not end is granted for a hundred years,
    -- which is the same thing said out loud.
    expires_at timestamptz NOT NULL,

    note       text        NOT NULL DEFAULT '',
    granted_by bigint      REFERENCES platform_admins (id),
    created_at timestamptz NOT NULL DEFAULT now(),

    CHECK (expires_at > starts_at)
);

-- The predicate the till's auth path runs on every batch: is there a row covering now?
CREATE INDEX ix_tenant_licences_tenant_window
    ON tenant_licences (tenant_id, starts_at DESC, expires_at DESC);

COMMENT ON TABLE tenant_licences IS
    'Append-only licence periods. A tenant is licensed iff a row covers now(); renewal inserts, never updates (M4-08).';

COMMENT ON COLUMN tenant_licences.granted_by IS
    'NULL for grants made by the system rather than a person — the bootstrap admin, and provisioning inside tests.';

-- ---------------------------------------------------------------------------
-- What staff did, across shops.
--
-- M3-08 gave the shop floor an audit trail on the principle that an action nobody is named for is
-- an action nobody can be asked about. This is the same principle applied to the far more
-- dangerous set of actions: a platform admin can suspend a business, re-key its till, and create
-- an owner login for a shop that is not theirs. Those are exactly the acts that must leave a
-- record, and the record has to be written by the same transaction that does them.
--
-- `detail` is jsonb rather than columns because the shape differs per action, and this table is
-- written on every admin write and read by a person rather than by a query planner.
CREATE TABLE platform_audit (
    id                bigserial   PRIMARY KEY,
    platform_admin_id bigint      REFERENCES platform_admins (id),

    -- Dotted, e.g. 'tenant.create', 'licence.grant', 'credential.revoke'. Not an enum: an action
    -- added later must not need a migration, and an unrecognised value here is still a legible
    -- record of something having happened.
    action            text        NOT NULL,

    -- Which shop it was about, where that is meaningful. NULL for plan-level acts.
    tenant_id         bigint      REFERENCES tenants (id),

    detail            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    at                timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_platform_audit_at ON platform_audit (at DESC);
CREATE INDEX ix_platform_audit_tenant ON platform_audit (tenant_id, at DESC);

-- ---------------------------------------------------------------------------
-- Seed: the flags that name capabilities this codebase actually has.
--
-- Every one of these corresponds to something already built, and none is aspirational. A registry
-- seeded with features that do not exist is a screen that lies about what can be sold.
INSERT INTO feature_flags (code, name, description) VALUES
    ('back_office',   'Back office',        'The manager-gated back office inside the terminal (M3-01).'),
    ('csv_import',    'CSV product import', 'Bulk catalogue import with a dry-run preview (M3-03).'),
    ('goods_receipt', 'Goods received',     'Suppliers and goods-received documents (M3-04).'),
    ('stocktake',     'Stocktake',          'Counted-versus-system stocktakes writing the difference (M3-06).'),
    ('customers',     'Customer records',   'Customer records attachable to a sale (M3-11).'),
    ('tax_invoice',   'IRD tax invoice',    'The Gazette 2481/22 tax invoice, issued on request (M5-09).'),
    ('owner_console', 'Owner console',      'The cloud console an owner reads from a phone (M4-05).');

-- Seed: the plans. Three, because two is not a ladder and five is a decision nobody has made yet.
INSERT INTO plans (code, name, description, price_minor, max_terminals, max_users, sort_order) VALUES
    ('trial',    'Trial',    'Everything, for a month. What a new shop is provisioned on.',
          0,  1,  3, 10),
    ('standard', 'Standard', 'A single till with the back office and the owner console.',
     450000,  1,  5, 20),
    ('plus',     'Plus',     'Adds stock documents, stocktakes and the IRD tax invoice.',
     950000,  4, 20, 30);

-- Trial gets everything, deliberately: a shop evaluating the product should see the product.
INSERT INTO plan_features (plan_id, flag_code)
SELECT p.id, f.code FROM plans p CROSS JOIN feature_flags f WHERE p.code = 'trial';

INSERT INTO plan_features (plan_id, flag_code)
SELECT p.id, f.code FROM plans p CROSS JOIN feature_flags f
 WHERE p.code = 'standard'
   AND f.code IN ('back_office', 'csv_import', 'customers', 'owner_console');

INSERT INTO plan_features (plan_id, flag_code)
SELECT p.id, f.code FROM plans p CROSS JOIN feature_flags f WHERE p.code = 'plus';
