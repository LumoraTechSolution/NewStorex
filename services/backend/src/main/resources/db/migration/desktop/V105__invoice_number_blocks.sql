-- V105 — invoice numbers are issued from a reserved block, not an unbounded counter (M1-12).
--
-- V101's counter was correct but unbounded: `next_seq` climbed forever with nothing capping
-- it and nothing recording that a range had ever been decided on purpose. That is fine while
-- no real invoice exists, and exactly the kind of thing that is expensive to retrofit once
-- one does — the IRD format (hard date April 2026) requires the sequence to mean something
-- an auditor can rely on, not just "whatever the counter happened to reach."
--
-- range_start/range_end turn that implicit, infinite counter into an explicit, bounded one.
-- A terminal's first sale still auto-provisions its own block with no setup step — "same
-- offline property" the V101 comment promised — but the block now has an edge: allocation
-- refuses once next_seq passes range_end rather than climbing without limit. The default
-- block is 999,999 wide, which a single till will not exhaust in any realistic lifetime; the
-- columns exist so a future provisioning step (M5-03's first-run wizard, or a deliberate
-- recovery after a till's local database is lost and rebuilt) can hand a terminal a specific,
-- non-default range — e.g. one that starts above the highest number the cloud has already
-- seen from it — without a schema change. That is "fewer assumptions about who allocates":
-- the allocator no longer assumes the block it would pick by default is the only one that
-- could ever be correct.

ALTER TABLE invoice_counters
    ADD COLUMN range_start bigint NOT NULL DEFAULT 1,
    ADD COLUMN range_end   bigint NOT NULL DEFAULT 999999;

ALTER TABLE invoice_counters
    ADD CONSTRAINT ck_invoice_counters_range CHECK (range_end >= range_start),
    -- next_seq may sit one past range_end: that is the "exhausted, nothing left" state the
    -- allocator leaves behind after handing out the last number in the block. Anything
    -- further would mean the cap was not enforced.
    ADD CONSTRAINT ck_invoice_counters_next_seq_in_range
        CHECK (next_seq >= range_start AND next_seq <= range_end + 1);

COMMENT ON COLUMN invoice_counters.range_start IS
    'The first number this terminal may issue. 1 by default; a deliberate provisioning step may set it higher.';
COMMENT ON COLUMN invoice_counters.range_end IS
    'The last number this terminal may issue. Allocation is refused, not wrapped, once next_seq passes it.';
