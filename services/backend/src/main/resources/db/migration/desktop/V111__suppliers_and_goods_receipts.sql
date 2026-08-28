-- V111 — suppliers, and stock arriving from them (M3-04).
--
-- The first thing in this system that puts stock *on* a shelf. Everything until now took it off:
-- V100's SALE movements, V108's RETURN. That asymmetry is why stock on hand has been meaningless
-- so far — every product has only ever gone negative.
--
-- ## Still no quantity_on_hand column, and there never will be
--
-- V100 said it and this migration is the first real chance to break it. A goods receipt is the
-- obvious place to "just increment the level", and the increment would be wrong within a week: two
-- tills receiving offline, a receipt entered twice, a correction applied to the level but not the
-- history. On hand is `Σ stock_movements.qty_delta`, always. A receipt writes RECEIVE rows and
-- nothing else moves.
--
-- ## Cost is not price
--
-- `unit_cost_minor` is what the shop paid. It is deliberately never copied into
-- `products.price_minor`, and no code in this milestone does so. A delivery that silently repriced
-- the shelf would be a supplier setting the shop's margin, and the shopkeeper would find out from
-- a customer. Cost lives here so M3-10 can show margin and so the value of stock on hand is
-- answerable; changing a shelf price stays a decision somebody makes on the products screen.
--
-- ## A receipt is a document, not a draft
--
-- There is no UPDATE and no DELETE on these tables, for the same reason V108 gives about refunds
-- and sales: the receipt records what was delivered and checked in, and editing it destroys the
-- only evidence of what the shop thought at the time. A receipt entered wrongly is corrected by an
-- ADJUST movement with a reason (M3-05), which leaves both facts visible — the miscount and the
-- correction — instead of one plausible number.
--
-- ## Receiving does not need an open shift
--
-- Selling does (M2-01), because a sale that no shift covers is cash nothing reconciles. Stock is
-- not cash. Goods arrive at seven in the morning before anyone has counted a float, and a system
-- that refuses the delivery until somebody opens a till is a system people work around.

-- ---------------------------------------------------------------------------
-- suppliers
-- ---------------------------------------------------------------------------
CREATE TABLE suppliers (
    id          bigserial   PRIMARY KEY,
    client_uuid uuid        NOT NULL,
    tenant_id   bigint      NOT NULL REFERENCES tenants (id),
    name        text        NOT NULL CHECK (length(btrim(name)) > 0),

    -- Whatever the shop writes on the delivery note: a phone number, a rep's name, both.
    -- Deliberately not a normalised contacts table — a v1 shop has a dozen suppliers and a
    -- phone number, and modelling an address book buys nothing anybody asked for.
    contact     text,

    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_suppliers_client_uuid ON suppliers (client_uuid);

-- Case-folded, same as product_categories in V110: "Keells" and "keells" are one supplier, and
-- letting both exist splits a year of purchase history across two rows on the report that was
-- the reason for recording the supplier at all.
CREATE UNIQUE INDEX ux_suppliers_tenant_name ON suppliers (tenant_id, lower(btrim(name)));

CREATE TRIGGER trg_suppliers_updated_at BEFORE UPDATE ON suppliers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE suppliers IS
    'Who goods come from. Deactivated, never deleted - a goods receipt from two years ago has to keep naming somebody.';

-- ---------------------------------------------------------------------------
-- goods_receipts
-- ---------------------------------------------------------------------------
CREATE TABLE goods_receipts (
    id          bigserial   PRIMARY KEY,
    client_uuid uuid        NOT NULL,
    tenant_id   bigint      NOT NULL REFERENCES tenants (id),
    branch_id   bigint      NOT NULL REFERENCES branches (id),

    -- NOT NULL on purpose. A nullable supplier makes every purchase report say "unknown" for the
    -- rows that matter most, and the cost of the alternative is one form: a shop buying at the
    -- market creates a supplier called that once and uses it forever.
    supplier_id bigint      NOT NULL REFERENCES suppliers (id),

    -- The supplier's own number on the invoice or delivery note. Optional, because a market
    -- purchase has none, and the unique index below is partial for exactly that reason.
    reference   text,

    -- When the goods physically arrived, which is not when somebody got round to typing them in.
    received_at timestamptz NOT NULL DEFAULT now(),
    note        text,

    created_by  bigint      NOT NULL REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_goods_receipts_client_uuid ON goods_receipts (client_uuid);

-- The same delivery note from the same supplier cannot be entered twice. This is the constraint
-- that stops the commonest stock error there is: a receipt keyed in by two people, doubling every
-- quantity on it with nothing on screen to suggest anything happened.
CREATE UNIQUE INDEX ux_goods_receipts_supplier_reference
    ON goods_receipts (tenant_id, supplier_id, btrim(reference))
    WHERE reference IS NOT NULL AND btrim(reference) <> '';

CREATE INDEX ix_goods_receipts_received ON goods_receipts (tenant_id, received_at DESC);
CREATE INDEX ix_goods_receipts_supplier ON goods_receipts (supplier_id);

CREATE TRIGGER trg_goods_receipts_updated_at BEFORE UPDATE ON goods_receipts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE goods_receipts IS
    'A delivery, as checked in. Never edited and never deleted - a receipt entered wrongly is corrected by an ADJUST movement (M3-05), which leaves both the miscount and the correction visible.';

-- ---------------------------------------------------------------------------
-- goods_receipt_items
-- ---------------------------------------------------------------------------
-- No client_uuid, on the same grounds as sale_items in V100: these are not an aggregate root.
-- They sync inside the receipt's payload and have no identity of their own, so giving them an
-- idempotency key would imply they can arrive separately. They cannot.
CREATE TABLE goods_receipt_items (
    id               bigserial PRIMARY KEY,
    goods_receipt_id bigint    NOT NULL REFERENCES goods_receipts (id) ON DELETE CASCADE,
    line_no          integer   NOT NULL CHECK (line_no > 0),
    product_id       bigint    NOT NULL REFERENCES products (id),

    qty              integer   NOT NULL CHECK (qty > 0),

    -- What the shop paid per unit, in minor units. Not the shelf price - see the header comment.
    unit_cost_minor  bigint    NOT NULL CHECK (unit_cost_minor >= 0)
);

CREATE UNIQUE INDEX ux_goods_receipt_items_line ON goods_receipt_items (goods_receipt_id, line_no);
CREATE INDEX ix_goods_receipt_items_product ON goods_receipt_items (product_id);

-- One product may not appear twice on one receipt. Two lines of the same goods is either a
-- double entry or a quantity that should have been added up, and both are worth stopping at the
-- point somebody types the second one.
CREATE UNIQUE INDEX ux_goods_receipt_items_product ON goods_receipt_items (goods_receipt_id, product_id);

COMMENT ON COLUMN goods_receipt_items.unit_cost_minor IS
    'Cost, not shelf price. Nothing copies this into products.price_minor - a delivery must not reprice the shelf.';
