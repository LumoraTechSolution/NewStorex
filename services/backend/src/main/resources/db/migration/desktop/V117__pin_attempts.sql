-- V117 — what happens after a wrong PIN (M3-13).
--
-- The hole this closes was recorded in M3-08 rather than hidden. BCrypt at cost 10 puts a floor of
-- roughly a tenth of a second on each guess, so a four-digit PIN — ten thousand combinations — is
-- about a quarter of an hour of sustained hammering against an API that is already running on the
-- shop's own PC. That is a real limit and it is not a strong one.
--
-- ## It cools off; it does not lock out
--
-- The task is called "lockout" and this is deliberately not one. A lock that a person has to be
-- released from is a denial of service anybody can trigger: type six wrong PINs at the owner's code
-- and the shop cannot authorise a refund until somebody who is not there unlocks it. On a till,
-- that failure is worse than the one it prevents — brute force needs an attacker standing at the
-- counter for days, whereas a bored teenager can lock the owner out in fifteen seconds.
--
-- So the counter escalates a **cooling-off period** and the period ends by itself. A cashier who
-- mistyped waits a few seconds; somebody working through the keyspace gets roughly two attempts a
-- minute, which turns fifteen minutes into several days of continuous work at a keypad in a shop.
-- Nothing needs releasing, and there is no support call.
--
-- ## Keyed on the code that was typed, not on a user
--
-- `code` here is whatever was entered, including codes no user holds. Two reasons, and the second
-- is the one that settles it:
--
--   1. A user_id cannot be recorded for a code that matches nobody, so throttling on the user would
--      leave wrong-code attempts entirely unthrottled — and enumerating codes is the first half of
--      the attack.
--   2. If a real code slowed down and an unknown one did not, the difference in behaviour is an
--      oracle for which codes exist. V109 went to the trouble of making a wrong code and a wrong
--      PIN indistinguishable, including running the BCrypt comparison against an unsatisfiable hash
--      so the timing matches. A throttle that only applied to real codes would undo all of it.
--
-- ## The counter has to survive the thing that resets it
--
-- Rows, not memory. A process restart is free to an attacker — the backend runs on the machine they
-- are standing at — and an in-memory counter would be cleared by one. It is also why this is a
-- table rather than a column on `users`: there is no user to hang a wrong code off.

CREATE TABLE pin_attempts (
    id              bigserial   PRIMARY KEY,
    tenant_id       bigint      NOT NULL REFERENCES tenants (id),

    -- Upper-cased on the way in, exactly as `users.code` is, so "amila" and "AMILA" are one
    -- counter. A throttle that can be reset by changing case is not a throttle.
    code            text        NOT NULL CHECK (code = upper(code)),

    -- Consecutive failures. Reset to zero by a success, and started over when the last failure is
    -- older than the decay window — a mistyped PIN this morning should not shorten anybody's
    -- patience this afternoon.
    failures        integer     NOT NULL DEFAULT 0 CHECK (failures >= 0),

    first_failed_at timestamptz,
    last_failed_at  timestamptz,

    -- Null means "not cooling off". Compared against now() on every attempt; nothing sweeps it,
    -- because a lapsed timestamp is already the same answer as a null one.
    locked_until    timestamptz,

    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- One counter per code per shop, and the lookup the authentication path runs on every attempt.
CREATE UNIQUE INDEX ux_pin_attempts_tenant_code ON pin_attempts (tenant_id, code);

CREATE TRIGGER trg_pin_attempts_updated_at BEFORE UPDATE ON pin_attempts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE pin_attempts IS
    'Consecutive failed PIN attempts per typed code. Escalates a cooling-off period that ends by itself - never a lock somebody has to be released from. See the V117 header.';
COMMENT ON COLUMN pin_attempts.code IS
    'The code as typed, including ones no user holds - otherwise enumerating codes is unthrottled and the throttle itself becomes an oracle for which codes are real.';
