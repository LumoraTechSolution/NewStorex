-- V213 — the cloud's half of an erasure (M5-10).
--
-- The customer payload is the whole row every time (see `CustomerService.enqueue`), so the moment
-- a till erases somebody the blanked name and the absent phone arrive here and overwrite what was
-- held. That much needs no schema: the cloud only ever carried name, phone and active.
--
-- This column is for the question that follows. An owner looking at the console, or anybody asked
-- to demonstrate that a request was actioned, needs to see the difference between a customer named
-- "Erased customer" and one whose data was destroyed on a date by a person. Without it the console
-- shows a placeholder name and no explanation, which reads like a bug and is the only evidence
-- that the shop complied.
--
-- Nullable and never cleared, exactly as on the till: an erasure does not come back.

ALTER TABLE customers
    ADD COLUMN erased_at timestamptz;

COMMENT ON COLUMN customers.erased_at IS
    'Mirror of the shop''s customers.erased_at (V123). Present means the personal data on this row was destroyed under PDPA; the row survives because sales reference it.';
