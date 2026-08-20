-- V106 — per-line tax rates (M1-18).
--
-- `sales.tax_mode` / `sales.tax_rate_bp` have carried the tax treatment for a whole sale
-- since V100. That is one rate per basket, and a basket is not one rate: bread is exempt,
-- arrack is 18%, and a shopkeeper puts both on the same counter. The till has been
-- *refusing* such a sale rather than pricing the exempt line at 18% — safe, and useless.
--
-- The stamp moves to where the price is: the line. What each column means afterwards:
--
--   sale_items.tax_mode / tax_rate_bp   what this line was actually charged under.
--                                       Authoritative. A tax invoice's per-rate summary is
--                                       GROUP BY these, and nothing else can produce it.
--
--   sales.tax_mode / tax_rate_bp        unchanged in meaning and still NOT NULL — the
--                                       cart's *default*, the stamp inherited by lines that
--                                       carried none. On a single-rate sale (almost all of
--                                       them) it equals every line's. On a mixed one it is
--                                       the default, not a summary, and reading it as the
--                                       sale's rate would be wrong. Kept because a
--                                       historical receipt must reprint with what it was
--                                       issued under (M1-05), and because widening a
--                                       NOT NULL column to nullable would make every
--                                       existing reader handle a null that never occurs.
--
-- Backfilled from the sale rather than defaulted: before this migration every line in a
-- sale genuinely was at the sale's rate, because a mixed cart could not be committed. So
-- the backfill is exact, not a guess, and there is no window where a row means something
-- other than what it says.

ALTER TABLE sale_items
    ADD COLUMN tax_mode    text,
    ADD COLUMN tax_rate_bp integer;

UPDATE sale_items i
   SET tax_mode    = s.tax_mode,
       tax_rate_bp = s.tax_rate_bp
  FROM sales s
 WHERE s.id = i.sale_id;

ALTER TABLE sale_items
    ALTER COLUMN tax_mode    SET NOT NULL,
    ALTER COLUMN tax_rate_bp SET NOT NULL,
    ADD CONSTRAINT ck_sale_items_tax_mode CHECK (tax_mode IN ('INCLUSIVE', 'EXCLUSIVE')),
    ADD CONSTRAINT ck_sale_items_tax_rate_bp CHECK (tax_rate_bp >= 0);

COMMENT ON COLUMN sale_items.tax_rate_bp IS
    'Basis points, for this line alone. 18% VAT = 1800, 0 = exempt or zero-rated. Authoritative: the sale-level column is only the cart default the line may have overridden.';
COMMENT ON COLUMN sales.tax_rate_bp IS
    'The cart DEFAULT in force when this sale was rung up, not the current rate and not a summary of the lines. Since M1-18 a sale may mix rates: GROUP BY sale_items.tax_rate_bp for what was actually charged.';
