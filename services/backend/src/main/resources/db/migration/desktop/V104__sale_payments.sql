-- V104 — how a sale was tendered (M1-11).
--
-- Two additions, both driven by @lumora/domain's `summariseTender`:
--
--   1. `sales` gains rounding_adjustment_minor and change_minor. Both are the *payment's*
--      numbers, never the sale's — subtotal/discount/tax/total stay exactly what the
--      customer was invoiced (M1-03's rounding policy). rounding_adjustment_minor may be
--      negative (the shop absorbed a fraction when cash rounded down), so it carries no
--      sign check; change_minor can only ever be handed back, never negative.
--
--   2. sale_payments — one row per tender line. Not folded into `sales` as fixed columns
--      because a split sale can carry more than one: card and cash together is the case
--      M1-11 exists for. No client_uuid: like sale_items, a payment line has no identity
--      of its own and is never delivered separately from the sale that owns it.

ALTER TABLE sales
    ADD COLUMN rounding_adjustment_minor bigint NOT NULL DEFAULT 0,
    ADD COLUMN change_minor              bigint NOT NULL DEFAULT 0 CHECK (change_minor >= 0);

COMMENT ON COLUMN sales.rounding_adjustment_minor IS
    'cashPayable - cashOwed from summariseTender. Positive: the drawer collected more than the exact total because cash rounds to the nearest rupee. May be negative.';
COMMENT ON COLUMN sales.change_minor IS
    'What was handed back to the customer. Never touches subtotal/discount/tax/total.';

CREATE TABLE sale_payments (
    id               bigserial PRIMARY KEY,
    sale_id          bigint    NOT NULL REFERENCES sales (id) ON DELETE CASCADE,
    line_no          integer   NOT NULL CHECK (line_no > 0),
    kind             text      NOT NULL CHECK (kind IN ('CASH', 'CARD', 'WALLET', 'STORE_CREDIT')),
    -- What this line tendered: cash physically handed over (may exceed what cash owed —
    -- the excess is the sale's change_minor), or the exact amount charged for every other
    -- kind. Never negative; a refunded tender is a new movement, not an edited one.
    amount_minor     bigint    NOT NULL CHECK (amount_minor >= 0)
);
CREATE UNIQUE INDEX ux_sale_payments_sale_line ON sale_payments (sale_id, line_no);

COMMENT ON TABLE sale_payments IS
    'One row per tender line. A split sale (e.g. card + cash) has more than one; M2-09 locks a refund to the kinds recorded here.';
