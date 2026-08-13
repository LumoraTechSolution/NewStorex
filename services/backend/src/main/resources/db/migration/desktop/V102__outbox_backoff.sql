-- V102 — when the drain may next try a row.
--
-- Without this the worker has no way to back off: it would re-read the same failing row
-- every tick, hammering an unreachable cloud and burying the rows behind it. Storing the
-- next attempt time rather than deriving it from `attempts` keeps the decision in the
-- index, so the query stays a cheap ordered scan of what is actually due.

ALTER TABLE outbox ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();

-- Replaces the created_at variant from V100. Still partial on unacked rows — the point is
-- that the scan grows with the backlog, not with the shop's history — but now ordered by
-- when a row became eligible again.
DROP INDEX ix_outbox_pending;
CREATE INDEX ix_outbox_pending ON outbox (next_attempt_at, created_at) WHERE acked_at IS NULL;

COMMENT ON COLUMN outbox.next_attempt_at IS
    'Earliest time the drain may retry. Pushed out on failure with capped exponential backoff.';
