-- V115 — offline sessions and the key that signs them (M3-09).
--
-- The till must be able to unlock with the internet unplugged, forever. Everything needed to
-- decide "is this person who they say they are, and may they do this" therefore lives in this
-- database: the BCrypt PIN hashes V109 already stores, and now the key that signs the token
-- proving somebody got past them. There is no issuer to call and nothing to validate against.
--
-- ## Why a token at all, when the PIN already works
--
-- M3-08 shipped an honest placeholder: the back office held the operator's code and PIN in memory
-- and replayed both on every request (`OperatorGate`). That is one credential travelling on every
-- save, sitting in the renderer process for as long as the screen is open, and one careless log
-- line away from being written to disk. This migration is what lets that stop: the PIN is sent
-- exactly once, exchanged for a short-lived token, and never held again.
--
-- ## Why a table, when a JWT is supposed to be stateless
--
-- Statelessness is a property worth having when the verifier cannot reach the issuer. Here they
-- are the same process, talking to a Postgres on the same machine, so the usual argument does not
-- apply — and what it would cost is real:
--
--   * Signing out would not sign anything out. The token stays valid until it expires, so the
--     "sign out" button would be a lie the length of the token's lifetime.
--   * Deactivating a user would not lock them out until their token expired. The moment a shop
--     most wants `active = false` to bite is the moment somebody is being walked off the floor.
--   * A role change would not take effect. Claims are a snapshot; permissions are not.
--
-- So the token proves the bearer got past a PIN, and this table decides whether that is still
-- true. One indexed lookup on loopback Postgres is not a cost worth trading any of the above for.
--
-- ## Why the key is a row and not a config value
--
-- A key in `application.yml` is the same key on every till that ever installs this build, which
-- means a token minted on one shop's PC verifies on another's. A key in an environment variable is
-- a key somebody has to set, and the failure mode of "somebody has to set it" is a default. The
-- key is generated on this machine, by this machine, the first time it is needed, and never
-- leaves — the row is the terminal's identity. M5-03's first-run wizard provisions it at
-- activation; until then it is created lazily, which is the same act at a different moment.

-- ---------------------------------------------------------------------------
-- signing_keys
-- ---------------------------------------------------------------------------
CREATE TABLE signing_keys (
    id         bigserial   PRIMARY KEY,

    -- Named in the token's `kid` header. A rotation inserts a new row and deactivates the old
    -- one; tokens already issued keep verifying against the old key until they expire, which is
    -- minutes. Without a kid, rotation would mean signing everybody out mid-shift.
    kid        text        NOT NULL,

    -- 32 random bytes, base64. HS256 — the same process signs and verifies, so an asymmetric key
    -- would be two things to store and no third party to hand a public half to.
    secret     text        NOT NULL,

    -- Exactly one active key at a time, enforced below. Deactivated keys are kept rather than
    -- deleted so an audit can answer "what signed this token" after a rotation.
    active     boolean     NOT NULL DEFAULT true,

    created_at timestamptz NOT NULL DEFAULT now(),
    retired_at timestamptz
);
CREATE UNIQUE INDEX ux_signing_keys_kid ON signing_keys (kid);
CREATE UNIQUE INDEX ux_signing_keys_one_active ON signing_keys (active) WHERE active;

COMMENT ON TABLE signing_keys IS
    'The HS256 secret this till signs session tokens with. Generated on this machine and never transmitted - it is the terminal identity M5-03 provisions at activation.';

-- ---------------------------------------------------------------------------
-- sessions
-- ---------------------------------------------------------------------------
CREATE TABLE sessions (
    id           bigserial   PRIMARY KEY,

    -- The token's `jti`. A uuid rather than the bigserial so that a token cannot be used to
    -- count how many sign-ins this till has ever had, and so the id is meaningless off-machine.
    jti          uuid        NOT NULL,

    tenant_id    bigint      NOT NULL REFERENCES tenants (id),
    user_id      bigint      NOT NULL REFERENCES users (id),

    -- Where it was opened from, for the audit: 'BACK_OFFICE' today, and the till itself when
    -- M5-03 gives the sales screen a sign-in. Free text on purpose - a CHECK here would have to
    -- be migrated every time a new surface learns to authenticate, and nothing branches on it.
    surface      text        NOT NULL,

    issued_at    timestamptz NOT NULL DEFAULT now(),

    -- Short. The token is the credential, and the window in which a stolen one is useful is
    -- exactly this. Refresh extends the session, so a person working continuously never sees it.
    expires_at   timestamptz NOT NULL,

    -- Set by signing out, by a rotation, or by anything that decides this session is over. Not a
    -- DELETE: "when did that session end and who ended it" is the question an audit asks, and a
    -- deleted row answers nothing.
    revoked_at   timestamptz,
    revoked_why  text,

    -- Bumped on every verified request. What makes idle expiry possible later, and what tells a
    -- shop which sessions are actually in use when it wonders why somebody is still signed in.
    last_seen_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_sessions_jti ON sessions (jti);

-- The verification path: find a live session by jti. Partial, because a revoked session is never
-- looked up by this query and there is no reason for it to be in the index.
CREATE INDEX ix_sessions_live ON sessions (jti) WHERE revoked_at IS NULL;

-- "Sign out every session this person has" - what deactivating a user runs.
CREATE INDEX ix_sessions_user ON sessions (user_id) WHERE revoked_at IS NULL;

COMMENT ON TABLE sessions IS
    'One row per sign-in. The JWT proves the bearer passed a PIN; this row decides whether that is still true - see the V115 header for why a stateless token was refused.';
COMMENT ON COLUMN sessions.jti IS
    'The token identifier. Uuid rather than the serial id so a token leaks nothing about how many sessions this till has had.';
