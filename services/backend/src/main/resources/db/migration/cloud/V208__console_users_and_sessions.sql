-- V208 — the owner signs in (M4-05).
--
-- Cloud tier. Until now the cloud had exactly one credential type, and it belonged to a machine:
-- V205's `tenant_api_credentials` is a token baked into a till at activation that never expires and
-- nobody types. That is right for a till and wrong for a person, in every particular. An owner has
-- an email, chooses a password, signs in from a phone they might lose, and expects signing out to
-- mean something.
--
-- ## Why this is not the `users` table the tills sync up
--
-- V204 already has `users`, and they are shop staff: a code, a role, a four-digit PIN, synced up
-- from the till that owns them. Reusing that table would mean the cloud's login credential was a
-- row a shop PC can overwrite on its next drain — a till would be able to grant, revoke or rename
-- console access by pushing an outbox row. The direction of trust runs the wrong way. Console
-- accounts are created in the cloud, live only in the cloud, and no till can touch them.
--
-- ## Sessions are rows, and the token is opaque
--
-- The till's M3-09 sessions are JWTs, for a reason that does not apply here: there the verifier had
-- to work with the network unplugged, so the token had to be self-describing. Its own header admits
-- the result is "little more than a signed session id". In the cloud the verifier is always next to
-- the database, so there is nothing to buy with a signature and something real to lose — a
-- self-contained token cannot be revoked before it expires, and "sign out on my stolen phone" is
-- the one thing an owner will actually need this to do.
--
-- So the token is 256 random bits, stored as a SHA-256 hash exactly like V205's, and every request
-- is one index probe that also gets to check `revoked_at`.

-- ---------------------------------------------------------------------------
CREATE TABLE console_users (
    id           bigserial   PRIMARY KEY,
    tenant_id    bigint      NOT NULL REFERENCES tenants (id),

    -- Stored lowercase and trimmed by ConsoleUserService. Case-folding at the boundary rather than
    -- with citext keeps the column a plain text one that any tool can read, and means the
    -- uniqueness rule and the lookup rule are visibly the same rule.
    email        text        NOT NULL CHECK (email = lower(trim(email)) AND position('@' in email) > 1),

    -- BCrypt, like the PINs in V109 — and here the work factor genuinely earns its cost, because
    -- unlike V205's random token this hashes something a human chose.
    password_hash text       NOT NULL,

    display_name text        NOT NULL,

    -- False locks the account out without deleting who did what. The owner of a shop that stops
    -- paying loses access, not history (M4-08, M4-09).
    active       boolean     NOT NULL DEFAULT true,

    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz
);

-- Globally unique, not per tenant. An email identifies a person and a person signs in before
-- anything knows which shop they belong to — a per-tenant index would make the login form need a
-- tenant field, which is a thing no owner knows the answer to.
CREATE UNIQUE INDEX ux_console_users_email ON console_users (email);
CREATE INDEX ix_console_users_tenant ON console_users (tenant_id);

CREATE TRIGGER trg_console_users_updated_at BEFORE UPDATE ON console_users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE console_users IS
    'Owner logins for the console. Created in the cloud and never synced from a till — see the V208 header (M4-05).';

-- ---------------------------------------------------------------------------
CREATE TABLE console_sessions (
    id           bigserial   PRIMARY KEY,

    -- Hex SHA-256 of the bearer token. The plaintext is returned once, at sign-in.
    token_hash   text        NOT NULL,

    console_user_id bigint   NOT NULL REFERENCES console_users (id),

    -- Denormalised from the user so authentication is one query rather than two. It is the tenant
    -- every request on this session is confined to, so it is worth having on the row that is read
    -- on every request.
    tenant_id    bigint      NOT NULL REFERENCES tenants (id),

    issued_at    timestamptz NOT NULL DEFAULT now(),

    -- Longer than the till's fifteen minutes, deliberately. That window is short because a till is
    -- a shared screen on a shop floor that people walk away from; a phone in somebody's pocket is
    -- the opposite situation, and a console that logged an owner out over lunch would simply be
    -- turned off. Read-only access also puts a lower ceiling on what a stolen session can do.
    expires_at   timestamptz NOT NULL,

    -- Signing out, not deleting. "When did that session end" is a question an audit asks, and a
    -- deleted row answers nothing.
    revoked_at   timestamptz,
    revoked_why  text,

    last_seen_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_console_sessions_token_hash ON console_sessions (token_hash);
CREATE INDEX ix_console_sessions_user ON console_sessions (console_user_id, issued_at DESC);

-- Finds the sessions still worth checking. Partial, because a table of expired sessions is a table
-- nobody queries and this keeps the index the size of what is live.
CREATE INDEX ix_console_sessions_live ON console_sessions (expires_at)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE console_sessions IS
    'Opaque bearer sessions for console_users. Revocable, unlike the till JWTs — see the V208 header.';
