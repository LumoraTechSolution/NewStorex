-- V215 — who was on the till (M6-13).
--
-- ## The gap this closes
--
-- `V203` gave the cloud a shift with a branch, a terminal, a float and a variance, and no person.
-- The till has had `opened_by` and `closed_by` since `V107` and they were dropped at the outbox,
-- so the console could say a drawer was Rs. 250 short and could not say whose drawer it was. An
-- owner asking "who was on the till and what did they take" — the question this whole milestone was
-- extended for — had no answer up here at all.
--
-- ## Nullable, and permanently so for the shifts already here
--
-- Every shift the cloud already holds was delivered by a payload that did not carry this, and
-- redelivering them is not something the outbox does: a synced aggregate is sent when it changes,
-- and a closed shift never changes again. So the backfill for existing rows is that there is none,
-- and a console that renders a missing operator as "not recorded" is telling the truth rather than
-- covering a bug. NOT NULL with a placeholder default would have invented a person.
--
-- ## Both the uuid and the name, which looks like duplication and is not
--
-- The uuid joins to the synced `users` row and survives a rename. The name is a snapshot, and it is
-- what the console shows when the user has *not arrived yet* — shifts and users are separate
-- aggregates draining through the same outbox with no ordering between them, so a shift can land
-- minutes before the person who opened it. A console that showed a blank until some later batch
-- happened to drain would look broken rather than pending.
--
-- The same reasoning as the tax invoice's purchaser snapshot in `V207`, for a different reason: not
-- because the name must be frozen, but because the join may not be satisfiable yet.

ALTER TABLE shifts
    ADD COLUMN opened_by_client_uuid uuid,
    ADD COLUMN opened_by_name        text,
    ADD COLUMN closed_by_client_uuid uuid,
    ADD COLUMN closed_by_name        text;

-- The end-of-day question: for this shop, on this day, who was on a till. The sales themselves are
-- reached through `sales.shift_client_uuid`, which V200 already indexes.
CREATE INDEX ix_cloud_shifts_tenant_operator
    ON shifts (tenant_id, opened_by_client_uuid)
    WHERE opened_by_client_uuid IS NOT NULL;

COMMENT ON COLUMN shifts.opened_by_name IS
    'A snapshot, kept because the shift can arrive before the user does - two aggregates, one outbox, no ordering between them. Prefer joining opened_by_client_uuid to users when it resolves.';
