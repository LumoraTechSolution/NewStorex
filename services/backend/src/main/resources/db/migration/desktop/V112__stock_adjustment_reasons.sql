-- V112 — an adjustment has to say why (M3-05).
--
-- `stock_movements.reason` has held 'ADJUST' since V100, and on its own it says almost nothing: a
-- shop reading "on hand dropped by 40, reason ADJUST" learns that somebody changed it and not one
-- thing more. This migration adds the column that makes the answer "40 went out as DAMAGED, entered
-- by Nimal, on Tuesday" — which is the only way M3-10 can ever show an owner what shrinkage costs
-- them.
--
-- ## Two columns, not a table
--
-- An adjustment is not a document. A goods receipt is — it has a supplier, a delivery note and
-- several lines that arrived together (V111) — but "three of these were broken" is one fact about
-- one product, and `stock_movements` already carries who, when, which product and how many. Giving
-- it a header table would mean a one-line document for every real adjustment a shop makes.
--
-- ## The CHECK is scoped to ADJUST on purpose
--
-- A SALE's reason is the sale, and a RECEIVE's is the delivery note; both already point at their
-- document through ref_type/ref_id, and demanding a reason code from them would mean inventing a
-- meaningless constant to satisfy a constraint. STOCKTAKE is deliberately left out too: M3-06
-- decides what a stocktake variance records, and pre-empting that here would be a constraint
-- written before the question.
--
-- ## Nothing enforces the sign here
--
-- DAMAGED is always negative and FOUND is always positive, but that rule lives in
-- `@lumora/domain`'s `signedAdjustmentQty` and is re-checked in `StockAdjustmentService`. A CHECK
-- constraint could express it — a long CASE over reason codes — and would then be a second copy of
-- a list that already exists twice. The database's job here is that a reason *exists*; which way it
-- points is policy, and policy that changes should not need a migration.

ALTER TABLE stock_movements
    ADD COLUMN reason_code text,
    ADD COLUMN note        text;

ALTER TABLE stock_movements
    ADD CONSTRAINT ck_stock_movements_adjust_needs_reason
    CHECK (reason <> 'ADJUST' OR (reason_code IS NOT NULL AND btrim(reason_code) <> ''));

-- Shrinkage reporting groups by this and only ever looks at rows that have one, so the index is
-- partial: a shop's history is overwhelmingly SALE rows, and they should not be in it.
CREATE INDEX ix_stock_movements_reason_code ON stock_movements (tenant_id, reason_code)
    WHERE reason_code IS NOT NULL;

COMMENT ON COLUMN stock_movements.reason_code IS
    'Why, for an ADJUST. Required by ck_stock_movements_adjust_needs_reason. The vocabulary lives in @lumora/domain STOCK_ADJUSTMENT_REASONS and is mirrored in AdjustmentReason.java.';

COMMENT ON COLUMN stock_movements.note IS
    'Free text. Required by the service when the reason is OTHER - without that, OTHER becomes the reason everybody picks and the code stops carrying information.';
