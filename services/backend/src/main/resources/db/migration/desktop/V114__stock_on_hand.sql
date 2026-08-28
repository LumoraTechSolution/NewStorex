-- V114 — stock on hand, as one definition (M3-07).
--
-- ## A view, not a table
--
-- The roadmap calls this an "indexed rollup", and the parenthetical is the part that decides its
-- shape: *never a stored balance column anyone updates*. So this is a plain view. It is not cached,
-- it cannot be stale, there is no refresh step to forget, and it cannot drift from the movements
-- because it is not a copy of them — it is the sum, evaluated when asked.
--
-- Two alternatives were considered and both fail that test:
--
--   * A summary table maintained by application code is the exact thing §A forbids. Every write
--     path would have to remember to update it, and the day one forgets, on hand is wrong in a way
--     that looks like theft.
--   * A summary table maintained by a trigger is subtler and still wrong: it drifts the moment
--     anyone loads data with triggers disabled (a restore, a migration, a support fix), and the
--     drift is silent. A view has no state to drift.
--
-- A MATERIALIZED view was also rejected. Postgres cannot refresh one incrementally, so it would
-- rescan everything anyway — paying the full cost *and* being stale between refreshes, which for a
-- shelf figure a shopkeeper is reading is the worst of both.
--
-- ## Where the speed actually comes from
--
-- V100 already indexed (tenant_id, branch_id, product_id). Adding qty_delta as an INCLUDE column
-- turns the aggregate into an **index-only scan**: Postgres sums straight out of the index without
-- touching the heap. That is the whole optimisation, and it is the right one because it makes the
-- honest query fast rather than replacing it with a faster lie.
--
-- The old index is dropped rather than left alongside. It is a strict prefix of the new one, so it
-- can answer nothing the new one cannot — and on a till the write path matters: every line of every
-- sale inserts a movement, and a redundant index is a cost paid on every item sold.

DROP INDEX IF EXISTS ix_stock_movements_product;

CREATE INDEX ix_stock_movements_on_hand
    ON stock_movements (tenant_id, branch_id, product_id) INCLUDE (qty_delta);

COMMENT ON INDEX ix_stock_movements_on_hand IS
    'Covering, so summing qty_delta per product is an index-only scan. Supersedes V100 ix_stock_movements_product, which was a prefix of this.';

-- ---------------------------------------------------------------------------
-- A product that has never moved does not appear here at all. That is deliberate: this view says
-- what the movements say, and "no rows" is the honest representation of "nothing ever happened".
-- Turning absence into a zero is presentation, and belongs in the LEFT JOIN the service does
-- against products - not in a view that would then have to know what a product is.
CREATE VIEW stock_on_hand AS
SELECT m.tenant_id,
       m.branch_id,
       m.product_id,
       sum(m.qty_delta)::integer AS qty_on_hand,
       max(m.created_at)         AS last_moved_at,
       count(*)::integer         AS movement_count
  FROM stock_movements m
 GROUP BY m.tenant_id, m.branch_id, m.product_id;

COMMENT ON VIEW stock_on_hand IS
    'Stock on hand is the sum of stock_movements and is never stored. This view is the single definition of that sum - query it rather than writing another GROUP BY, so there is one place for the calculation to be right.';

COMMENT ON COLUMN stock_on_hand.qty_on_hand IS
    'May be negative. A sale rung up before its delivery was booked in really does leave a shelf below zero, and clamping would hide the discrepancy somebody needs to see.';
