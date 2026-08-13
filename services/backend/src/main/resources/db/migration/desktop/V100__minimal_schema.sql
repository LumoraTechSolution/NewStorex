-- V100 — the minimal schema the M0 spike needs to commit a sale locally.
--
-- Desktop tier only. The cloud gets its own shape at V200: it has no outbox, and it
-- carries tenant isolation this side does not need.
--
-- Two of the four non-negotiables become real here:
--
--   1. Idempotency. Every synced *aggregate root* carries a client-generated
--      client_uuid with a unique index. bigserial primary keys stay - adding a unique
--      column is far less invasive than moving the schema to UUIDv7, and the cloud
--      upserts on client_uuid so a redelivered batch is a no-op.
--
--   2. Movements, not balances. There is deliberately no stock level column anywhere
--      in this file. Stock on hand is the sum of stock_movements, always. Addition is
--      commutative, so two offline nodes reconcile with no conflict logic at all -
--      and shrinkage stays visible instead of being overwritten.
--
-- Money is stored as integer minor units (Sri Lankan cents) in bigint columns named
-- *_minor, so the unit is impossible to misread at a call site. No floats, no numeric
-- for money. Tax rates are basis points (18% = 1800), which keeps VAT extraction
-- exact integer arithmetic: vat = total * bp / (10000 + bp).

-- ---------------------------------------------------------------------------
-- tenants
-- ---------------------------------------------------------------------------
CREATE TABLE tenants (
    id          bigserial   PRIMARY KEY,
    client_uuid uuid        NOT NULL,
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_tenants_client_uuid ON tenants (client_uuid);

CREATE TRIGGER trg_tenants_updated_at BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE tenants IS
    'One row on a shop PC in v1. The column exists everywhere so the cloud can be multi-tenant without a migration.';

-- ---------------------------------------------------------------------------
-- branches
-- ---------------------------------------------------------------------------
CREATE TABLE branches (
    id          bigserial   PRIMARY KEY,
    client_uuid uuid        NOT NULL,
    tenant_id   bigint      NOT NULL REFERENCES tenants (id),
    -- The leading segment of an invoice number: KND-T2-001047.
    code        text        NOT NULL,
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_branches_client_uuid ON branches (client_uuid);
CREATE UNIQUE INDEX ux_branches_tenant_code ON branches (tenant_id, code);

CREATE TRIGGER trg_branches_updated_at BEFORE UPDATE ON branches
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- products
-- ---------------------------------------------------------------------------
CREATE TABLE products (
    id           bigserial   PRIMARY KEY,
    client_uuid  uuid        NOT NULL,
    tenant_id    bigint      NOT NULL REFERENCES tenants (id),
    sku          text        NOT NULL,
    barcode      text,
    name         text        NOT NULL,
    -- Shelf price in cents. Inclusive or exclusive of VAT per tax_mode below.
    price_minor  bigint      NOT NULL CHECK (price_minor >= 0),
    tax_mode     text        NOT NULL CHECK (tax_mode IN ('INCLUSIVE', 'EXCLUSIVE')),
    tax_rate_bp  integer     NOT NULL CHECK (tax_rate_bp >= 0) DEFAULT 0,
    active       boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_products_client_uuid ON products (client_uuid);
CREATE UNIQUE INDEX ux_products_tenant_sku ON products (tenant_id, sku);
-- Partial: many products legitimately have no barcode, and NULLs should not collide.
CREATE UNIQUE INDEX ux_products_tenant_barcode ON products (tenant_id, barcode)
    WHERE barcode IS NOT NULL;

CREATE TRIGGER trg_products_updated_at BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN products.price_minor IS 'Integer minor units (cents). Never a float.';
COMMENT ON COLUMN products.tax_rate_bp IS 'Basis points. 18% VAT = 1800.';

-- ---------------------------------------------------------------------------
-- sales
-- ---------------------------------------------------------------------------
CREATE TABLE sales (
    id             bigserial   PRIMARY KEY,
    client_uuid    uuid        NOT NULL,
    tenant_id      bigint      NOT NULL REFERENCES tenants (id),
    branch_id      bigint      NOT NULL REFERENCES branches (id),
    -- Second segment of the invoice number. Each terminal issues from its own
    -- reserved block, offline, with no global sequence to coordinate.
    terminal_code  text        NOT NULL,
    invoice_number text        NOT NULL,

    subtotal_minor bigint      NOT NULL CHECK (subtotal_minor >= 0),
    discount_minor bigint      NOT NULL DEFAULT 0 CHECK (discount_minor >= 0),
    tax_minor      bigint      NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),
    total_minor    bigint      NOT NULL CHECK (total_minor >= 0),

    -- Stamped per sale so an old receipt stays reproducible after the rate changes.
    tax_mode       text        NOT NULL CHECK (tax_mode IN ('INCLUSIVE', 'EXCLUSIVE')),
    tax_rate_bp    integer     NOT NULL CHECK (tax_rate_bp >= 0),

    sold_at        timestamptz NOT NULL DEFAULT now(),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_sales_client_uuid ON sales (client_uuid);
CREATE UNIQUE INDEX ux_sales_tenant_invoice ON sales (tenant_id, invoice_number);
CREATE INDEX ix_sales_sold_at ON sales (tenant_id, sold_at);

CREATE TRIGGER trg_sales_updated_at BEFORE UPDATE ON sales
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN sales.tax_rate_bp IS
    'The rate in force when this sale was rung up, not the current one. Historical receipts must reproduce.';

-- ---------------------------------------------------------------------------
-- sale_items
-- ---------------------------------------------------------------------------
-- No client_uuid: sale_items are not an aggregate root. They sync inside the sale's
-- payload and have no independent identity, so giving them their own idempotency key
-- would imply they can arrive separately. They cannot.
CREATE TABLE sale_items (
    id               bigserial PRIMARY KEY,
    sale_id          bigint    NOT NULL REFERENCES sales (id) ON DELETE CASCADE,
    product_id       bigint    NOT NULL REFERENCES products (id),
    line_no          integer   NOT NULL CHECK (line_no > 0),

    -- Whole units. The guide's stock movements are integers too. Selling by weight
    -- would need a scaled integer (e.g. milligrams) rather than a numeric - decide
    -- that deliberately if it ever arrives, do not quietly widen this to numeric.
    qty              integer   NOT NULL CHECK (qty > 0),

    unit_price_minor bigint    NOT NULL CHECK (unit_price_minor >= 0),
    discount_minor   bigint    NOT NULL DEFAULT 0 CHECK (discount_minor >= 0),
    tax_minor        bigint    NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),
    line_total_minor bigint    NOT NULL CHECK (line_total_minor >= 0)
);
CREATE UNIQUE INDEX ux_sale_items_sale_line ON sale_items (sale_id, line_no);
CREATE INDEX ix_sale_items_product ON sale_items (product_id);

-- ---------------------------------------------------------------------------
-- stock_movements
-- ---------------------------------------------------------------------------
CREATE TABLE stock_movements (
    id          bigserial   PRIMARY KEY,
    client_uuid uuid        NOT NULL,
    tenant_id   bigint      NOT NULL REFERENCES tenants (id),
    branch_id   bigint      NOT NULL REFERENCES branches (id),
    product_id  bigint      NOT NULL REFERENCES products (id),

    -- Signed. Negative on a sale, positive on a return or a goods receipt.
    qty_delta   integer     NOT NULL CHECK (qty_delta <> 0),
    reason      text        NOT NULL CHECK (reason IN ('SALE', 'RETURN', 'RECEIVE', 'ADJUST', 'STOCKTAKE')),

    -- Link back to whatever caused it: the sale, the refund, the GRN.
    ref_type    text,
    ref_id      bigint,

    -- Real FK arrives with the users table in M3-08. Until then this is the seeded
    -- operator id, not a dangling reference to something that should exist.
    created_by  bigint      NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_stock_movements_client_uuid ON stock_movements (client_uuid);
CREATE INDEX ix_stock_movements_product ON stock_movements (tenant_id, branch_id, product_id);
CREATE INDEX ix_stock_movements_ref ON stock_movements (ref_type, ref_id);

COMMENT ON TABLE stock_movements IS
    'Stock on hand is the SUM of these rows and is never stored as a level. A stocktake writes the difference as a STOCKTAKE movement; it does not overwrite - shrinkage is exactly what the owner needs to see. Do not add a quantity_on_hand column to products.';

-- ---------------------------------------------------------------------------
-- outbox
-- ---------------------------------------------------------------------------
-- Written in the same transaction as the domain rows it describes, so a sale can
-- never exist without its sync record. Nothing in the shop ever waits on it.
CREATE TABLE outbox (
    id           bigserial   PRIMARY KEY,
    tenant_id    bigint      NOT NULL REFERENCES tenants (id),
    aggregate    text        NOT NULL,
    -- The aggregate's client_uuid. The cloud upserts on it, so redelivery is a no-op.
    aggregate_id uuid        NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    attempts     integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error   text,
    acked_at     timestamptz
);

-- The drain reads only unacked rows, oldest first. A partial index keeps that scan
-- proportional to the backlog rather than to the shop's entire history.
CREATE INDEX ix_outbox_pending ON outbox (created_at) WHERE acked_at IS NULL;
CREATE INDEX ix_outbox_aggregate ON outbox (aggregate, aggregate_id);

COMMENT ON TABLE outbox IS
    'Transactional outbox. Rows are inserted alongside the domain rows they describe and drained asynchronously by a scheduled worker.';
