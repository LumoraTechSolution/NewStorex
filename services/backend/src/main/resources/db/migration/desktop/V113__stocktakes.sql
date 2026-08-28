-- V113 — counting the shelves (M3-06).
--
-- ## The rule this table exists to enforce
--
-- A stocktake writes the **difference** as a movement. It never sets the level, because there is no
-- level to set — and if there were, setting it would be the single most destructive thing this
-- software could do. On hand is Σ stock_movements, so "the system says 20, I counted 17" becomes a
-- STOCKTAKE movement of −3 and the shop keeps both facts: what was expected and what was there.
-- Overwriting to 17 would leave a number that is correct today and a history that cannot explain
-- it, which is precisely backwards — the 3 that went missing is the entire reason anybody counts.
--
-- ## Why the difference is also the *correct* answer, not merely the honest one
--
-- The subtle argument, and worth spelling out because "just set it to what I counted" sounds
-- obviously right. Counting takes time and the shop keeps trading. Suppose at 09:00 the system says
-- 20, you count 17, and at 09:30 two are sold before you finish the stocktake.
--
--   * Writing the difference: −3 applied to a system that is now 18 gives 15. Real stock is
--     17 − 2 = 15. Correct.
--   * Overwriting the level: setting 17 gives 17. Real stock is 15. Wrong by exactly the sales
--     that happened while somebody was walking round with a clipboard.
--
-- Deltas compose and levels do not. That is the same property that lets two offline tills reconcile
-- by addition with no conflict logic (§A), applied to a person with a pen.
--
-- ## system_qty is a stamp, not a balance
--
-- `stocktake_items.system_qty` records what Σ movements came to at the instant the count was
-- entered. That looks like the stored level §A forbids, and it is not one: nothing ever reads it to
-- answer "what is on hand", nothing updates it, and it exists so the variance stays reproducible
-- after the fact. It is the same kind of thing as `sales.tax_rate_bp` — the world as it was when the
-- row was written, kept so an old document still makes sense.
--
-- The variance itself is deliberately **not** stored. It is `counted_qty - system_qty`, and a third
-- column holding the same fact is a third place for it to disagree.
--
-- ## Two phases, because counting a shop takes longer than one sitting
--
-- A stocktake is OPEN while the counting happens and writes nothing at all; COMPLETED writes every
-- movement in one transaction. A shopkeeper part-way through 400 products must be able to stop for
-- lunch, and a design that wrote a movement per line would leave the shop half-adjusted with no way
-- to tell which half.
--
-- ## Products nobody counted are left alone
--
-- Counting one shelf is normal and must not zero the rest of the shop. Only lines that were
-- actually entered produce a movement, on the same principle as M3-03's "an import is not a sync".

-- ---------------------------------------------------------------------------
CREATE TABLE stocktakes (
    id           bigserial   PRIMARY KEY,
    client_uuid  uuid        NOT NULL,
    tenant_id    bigint      NOT NULL REFERENCES tenants (id),
    branch_id    bigint      NOT NULL REFERENCES branches (id),

    -- ABANDONED exists so a stocktake started by mistake has a way out that is not a DELETE. It
    -- wrote no movements, so nothing is lost by closing it - but the row stays, because the
    -- unique index below needs it gone from OPEN and history is cheap.
    status       text        NOT NULL CHECK (status IN ('OPEN', 'COMPLETED', 'ABANDONED')),
    note         text,

    started_at   timestamptz NOT NULL DEFAULT now(),
    started_by   bigint      NOT NULL REFERENCES users (id),

    -- Both NULL until the count is finished or given up on.
    completed_at timestamptz,
    completed_by bigint      REFERENCES users (id),

    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_stocktakes_client_uuid ON stocktakes (client_uuid);

-- One count at a time per branch, the same shape as ux_shifts_one_open_per_terminal (V107) and for
-- the same reason: two open stocktakes means two people counting the same shelf into different
-- documents, and whichever completes second writes a variance against a system figure the first
-- one already moved.
CREATE UNIQUE INDEX ux_stocktakes_one_open ON stocktakes (tenant_id, branch_id)
    WHERE status = 'OPEN';

CREATE INDEX ix_stocktakes_started ON stocktakes (tenant_id, started_at DESC);

CREATE TRIGGER trg_stocktakes_updated_at BEFORE UPDATE ON stocktakes
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE stocktakes IS
    'A count of the shelves. Completing one writes the DIFFERENCE as STOCKTAKE movements and never sets a level - see the header of V113 for why the difference is also the arithmetically correct answer, not just the honest one.';

-- ---------------------------------------------------------------------------
-- No client_uuid: these are not an aggregate root. They sync inside the stocktake's payload and
-- have no identity of their own, exactly like sale_items in V100.
CREATE TABLE stocktake_items (
    id           bigserial   PRIMARY KEY,
    stocktake_id bigint      NOT NULL REFERENCES stocktakes (id) ON DELETE CASCADE,
    line_no      integer     NOT NULL CHECK (line_no > 0),
    product_id   bigint      NOT NULL REFERENCES products (id),

    -- What the person found. Zero is a real and important answer - the shelf was empty.
    counted_qty  integer     NOT NULL CHECK (counted_qty >= 0),

    -- What Σ movements came to at the instant this line was entered. A stamp, never read to
    -- answer "what is on hand", never updated. See the header.
    system_qty   integer     NOT NULL,

    counted_at   timestamptz NOT NULL DEFAULT now(),
    counted_by   bigint      NOT NULL REFERENCES users (id)
);

CREATE UNIQUE INDEX ux_stocktake_items_line ON stocktake_items (stocktake_id, line_no);

-- One line per product. Counting the same thing twice is a recount, which replaces the line
-- rather than adding a second one - two lines for one product would make the variance ambiguous.
CREATE UNIQUE INDEX ux_stocktake_items_product ON stocktake_items (stocktake_id, product_id);

CREATE INDEX ix_stocktake_items_product ON stocktake_items (product_id);

COMMENT ON COLUMN stocktake_items.system_qty IS
    'Sum of movements at the moment this line was counted. A stamp for reproducing the variance, not a balance - nothing reads it to answer what is on hand.';

COMMENT ON COLUMN stocktake_items.counted_qty IS
    'What was actually on the shelf. The variance is counted_qty - system_qty and is deliberately not stored: a third column holding the same fact is a third place for it to disagree.';
