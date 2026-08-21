-- V108 — returns (M2-06 … M2-10).
--
-- ## A refund is a document, not an edit
--
-- Nothing here touches `sales`. The sale that was rung up is what the customer was invoiced
-- and what the revenue authority will be shown; reversing part of it by decrementing a
-- quantity would destroy the only record that the original transaction ever happened, and
-- with it the audit trail that is the entire reason a shop buys this software. So a return
-- writes a new document — a credit note — that points at the sale, and the sale stays exactly
-- as it was issued. V104 already said this about tenders: "a refunded tender is a new
-- movement, not an edited one."
--
-- ## Every column below exists to close one specific hole
--
--   sale_id NOT NULL              M2-06. There is no such thing as a freestanding refund.
--                                 A refund with no receipt behind it is how a cashier turns
--                                 the drawer into an ATM, and it is Gate M2's first half.
--
--   refund_items.sale_item_id     M2-08. A partial return is "these units of that line",
--                                 never a loose amount. The line it came from is what caps
--                                 how much can ever be given back.
--
--   refund_items.reason_code      M2-08. Required per line, not per document: a customer
--                                 returning a damaged item and an unwanted one in the same
--                                 visit is one refund with two different reasons, and a
--                                 single document-level reason would record neither.
--
--   refund_payments.kind          M2-09, Gate M2's second half. Constrained in RefundService
--                                 against what the sale actually took: money goes back the
--                                 way it came, or it does not go back.
--
--   authorised_by                 M2-07. Who supplied the manager PIN. Distinct from
--                                 created_by, because "the cashier who processed it" and
--                                 "the manager who allowed it" are the two facts an
--                                 investigation needs and they are usually different people.
--
-- ## Amounts are magnitudes here, signs live in the movements
--
-- Every money column below is positive: this table says what was given back, and the table
-- itself is the direction. That is the opposite convention from cash_movements (V107), where
-- the sign is in the column — deliberately, because there the sign varies row to row and here
-- it cannot. The one place a refund becomes signed is `stock_movements`, where a restocked
-- unit is a positive qty_delta exactly as the sale was a negative one, and stock on hand goes
-- on being Σ entries with no special case for returns.

-- ---------------------------------------------------------------------------
-- Credit notes get their own number block
-- ---------------------------------------------------------------------------
-- A credit note is a numbered document in its own right, and its number may not come out of
-- the invoice sequence: an auditor reading invoices 1047, 1048, 1050 has to be able to
-- conclude that 1049 is missing, not that it was a refund. So `invoice_counters` becomes a
-- counter per (terminal, document type) rather than per terminal, with two independent
-- blocks that advance at their own rates.
--
-- Backfilled to 'INVOICE' rather than defaulted-and-left: every existing row counts sales,
-- so the value is known, not assumed. The default stays on the column so a caller that
-- predates this migration keeps working.
ALTER TABLE invoice_counters ADD COLUMN doc_type text NOT NULL DEFAULT 'INVOICE';

ALTER TABLE invoice_counters
    ADD CONSTRAINT ck_invoice_counters_doc_type CHECK (doc_type IN ('INVOICE', 'CREDIT_NOTE'));

DROP INDEX ux_invoice_counters;
CREATE UNIQUE INDEX ux_invoice_counters
    ON invoice_counters (tenant_id, branch_id, terminal_code, doc_type);

COMMENT ON COLUMN invoice_counters.doc_type IS
    'Which sequence this row counts. Invoices and credit notes advance independently so a gap in the invoice numbers always means a missing invoice.';

-- ---------------------------------------------------------------------------
-- refunds
-- ---------------------------------------------------------------------------
CREATE TABLE refunds (
    id                        bigserial   PRIMARY KEY,
    client_uuid               uuid        NOT NULL,
    tenant_id                 bigint      NOT NULL REFERENCES tenants (id),
    branch_id                 bigint      NOT NULL REFERENCES branches (id),
    terminal_code             text        NOT NULL,

    -- Never null, for the same reason cash_movements.shift_id is not: money left the drawer
    -- and something has to reconcile it at close.
    shift_id                  bigint      NOT NULL REFERENCES shifts (id),

    -- M2-06. The whole gate.
    sale_id                   bigint      NOT NULL REFERENCES sales (id),

    credit_note_number        text        NOT NULL,

    -- What was handed back, and the VAT inside it. There is deliberately no subtotal or
    -- discount column: on a refund they would be fictions. A returned line gives back the
    -- amount that was actually charged for it — which already has both the line discount and
    -- its share of the order discount taken out — so a "subtotal before discount" here would
    -- be a number that reverses nothing and reconciles to nothing.
    total_minor               bigint      NOT NULL CHECK (total_minor > 0),
    tax_minor                 bigint      NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),

    -- Cash rounding applies to a refund exactly as it does to a sale (M1-03): the shop cannot
    -- hand back 30 cents any more than it can collect them. Signed, like sales.
    rounding_adjustment_minor bigint      NOT NULL DEFAULT 0,

    -- M2-07: who let this happen, and who did it. Both real FKs at M3-08.
    authorised_by             bigint      NOT NULL,
    created_by                bigint      NOT NULL,

    refunded_at               timestamptz NOT NULL DEFAULT now(),
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_refunds_tax_within_total CHECK (tax_minor <= total_minor)
);
CREATE UNIQUE INDEX ux_refunds_client_uuid ON refunds (client_uuid);
CREATE UNIQUE INDEX ux_refunds_tenant_credit_note ON refunds (tenant_id, credit_note_number);
CREATE INDEX ix_refunds_sale ON refunds (sale_id);
CREATE INDEX ix_refunds_shift ON refunds (shift_id);
CREATE INDEX ix_refunds_refunded_at ON refunds (tenant_id, refunded_at);

CREATE TRIGGER trg_refunds_updated_at BEFORE UPDATE ON refunds
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE refunds IS
    'A credit note against one sale. Never freestanding (M2-06), never an edit to the sale it reverses.';

-- ---------------------------------------------------------------------------
-- refund_items
-- ---------------------------------------------------------------------------
-- No client_uuid, same reasoning as sale_items: these have no identity apart from the refund
-- that owns them and are never delivered separately from it.
CREATE TABLE refund_items (
    id                 bigserial PRIMARY KEY,
    refund_id          bigint    NOT NULL REFERENCES refunds (id) ON DELETE CASCADE,
    line_no            integer   NOT NULL CHECK (line_no > 0),

    -- The cap. RefundService will not let Σ qty across every refund of this line exceed the
    -- line's own qty, which is what makes "partial return" safe to repeat.
    sale_item_id       bigint    NOT NULL REFERENCES sale_items (id),
    qty                integer   NOT NULL CHECK (qty > 0),

    -- Copied from the sale line so a credit note prints the same figures the receipt did,
    -- even after the product's price changes tomorrow.
    unit_price_minor   bigint    NOT NULL CHECK (unit_price_minor >= 0),

    -- What is given back for these units: this line's share of what was actually charged,
    -- and the VAT inside that share. Σ over the refund is refunds.total_minor / tax_minor.
    refund_total_minor bigint    NOT NULL CHECK (refund_total_minor >= 0),
    tax_minor          bigint    NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),

    -- The rate this line was charged at, carried across from sale_items (M1-18). A credit
    -- note is a tax document and has to show the same per-rate summary the invoice did — and
    -- the current rate is not necessarily the one this sale was made under (M1-05).
    tax_mode           text      NOT NULL CHECK (tax_mode IN ('INCLUSIVE', 'EXCLUSIVE')),
    tax_rate_bp        integer   NOT NULL CHECK (tax_rate_bp >= 0),

    -- M2-08.
    reason_code        text      NOT NULL CHECK (reason_code IN (
                           'DAMAGED', 'FAULTY', 'WRONG_ITEM', 'EXPIRED',
                           'NOT_AS_DESCRIBED', 'CHANGED_MIND', 'PRICING_ERROR', 'OTHER')),
    note               text,

    -- M2-10. False for a damaged item — it is not going back on the shelf, and writing a
    -- RETURN movement for it would tell the owner they have stock they cannot sell. That
    -- distinction is the whole reason this is a per-line flag and not a refund-level one.
    restock            boolean   NOT NULL,

    CONSTRAINT ck_refund_items_tax_within_total CHECK (tax_minor <= refund_total_minor)
);
CREATE UNIQUE INDEX ux_refund_items_refund_line ON refund_items (refund_id, line_no);
-- The lookup behind "how much of this line has already gone back", run on every refund.
CREATE INDEX ix_refund_items_sale_item ON refund_items (sale_item_id);

COMMENT ON COLUMN refund_items.restock IS
    'Whether these units returned to sellable stock. False writes no RETURN movement — damaged goods are not inventory.';

-- ---------------------------------------------------------------------------
-- refund_payments
-- ---------------------------------------------------------------------------
CREATE TABLE refund_payments (
    id           bigserial PRIMARY KEY,
    refund_id    bigint    NOT NULL REFERENCES refunds (id) ON DELETE CASCADE,
    line_no      integer   NOT NULL CHECK (line_no > 0),
    -- Same kinds as sale_payments. M2-09 additionally requires that each kind here appeared
    -- on the original sale and that the amount does not exceed what that kind paid, less
    -- whatever earlier refunds already sent back to it. That is a cross-row rule over two
    -- tables, so it lives in RefundService with a test, not in a CHECK.
    kind         text      NOT NULL CHECK (kind IN ('CASH', 'CARD', 'WALLET', 'STORE_CREDIT')),
    amount_minor bigint    NOT NULL CHECK (amount_minor > 0)
);
CREATE UNIQUE INDEX ux_refund_payments_refund_line ON refund_payments (refund_id, line_no);
-- Refunds are grouped by kind whenever the next one asks what is left to give back.
CREATE INDEX ix_refund_payments_kind ON refund_payments (refund_id, kind);

COMMENT ON TABLE refund_payments IS
    'How the money went back. Locked to the tenders the original sale actually took (M2-09) — a card sale cannot be refunded as cash.';
