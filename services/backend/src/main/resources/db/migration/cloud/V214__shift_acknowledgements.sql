-- V214 — somebody looked at it (M6-10).
--
-- ## The screen this fixes
--
-- M4-07's attention feed lists closed shifts whose drawer was out by more than the threshold. It
-- counts eleven, and offers nothing to do about any of them. There is no way to open one, no way to
-- say "I checked, it was my mistake", and no way to make it go away — so it counts to eleven, then
-- twelve, then twenty, and the owner stops looking.
--
-- An alert nobody can clear is not an alert. It is wallpaper, and it kills the one screen the
-- console exists for.
--
-- ## An acknowledgement is a new fact, never an edit
--
-- The obvious implementation is a boolean on `shifts`. It is wrong for the same reason every other
-- state in this system is a row rather than a flag: the shift is a **synced aggregate**, delivered
-- by the till and upserted here, and the next delivery of that shift would overwrite anything the
-- console had written on it. A reviewed variance would silently un-review itself the next time the
-- shop's outbox drained.
--
-- More than that, it is a different fact with a different author. The shift is what the till says
-- happened. This is what a person in the shop's office did about it, days later, from a phone. Those
-- do not belong in one row, and the stocktake's ABANDONED state and the shift's own closed_by are
-- the same instinct: the fact that somebody acted is new information, not the absence of old
-- information.
--
-- ## Which means the console can now write, and that is a deliberate widening
--
-- `AuthenticatedPrincipal` has said since M4-05 that a console session reads and never writes, and
-- that a stolen one discloses takings and cannot alter a figure. That stays true: this table touches
-- no money and no ledger, cannot change what a shift says, and records who did it. A stolen session
-- can hide an alert; it cannot hide the shift, which is still there under "show reviewed" with the
-- name of whoever dismissed it and when.
--
-- The alternative was making an owner walk to the till to clear a variance they are looking at on
-- their phone, which is the whole reason the console exists.

CREATE TABLE shift_acknowledgements (
    id                bigserial   PRIMARY KEY,
    tenant_id         bigint      NOT NULL REFERENCES tenants (id),

    -- The shift's client_uuid rather than its id. The console can be looking at a variance whose
    -- shift row is being re-upserted by an arriving batch at that moment, and the uuid is the
    -- identity that survives every delivery — the same key the outbox and the ingest use.
    -- Deliberately not a foreign key: the till's identity for the shift is authoritative, and a
    -- constraint here would make an acknowledgement's validity depend on ingest ordering.
    shift_client_uuid uuid        NOT NULL,

    acknowledged_at   timestamptz NOT NULL DEFAULT now(),
    acknowledged_by   bigint      NOT NULL REFERENCES console_users (id),

    -- What the owner concluded. Optional, because most of the time the honest answer is "I checked
    -- and it was fine" and forcing a sentence out of somebody produces "ok" eleven times.
    note              text
);

-- One acknowledgement per shift, per shop. A second press of the button, or a retried request, is
-- the same act — and two rows would make "who reviewed this" a question with two answers.
CREATE UNIQUE INDEX ux_shift_acknowledgements_tenant_shift
    ON shift_acknowledgements (tenant_id, shift_client_uuid);

COMMENT ON TABLE shift_acknowledgements IS
    'Somebody in the shop''s office looked at a cash variance and said so (M6-10). A new fact about a person, never an edit to the shift - which is a synced aggregate and would overwrite it on the next delivery.';
