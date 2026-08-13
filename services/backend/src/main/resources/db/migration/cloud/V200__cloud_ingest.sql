-- V200 — the cloud's ingest schema.
--
-- Cloud tier only. This is NOT the desktop schema with a tenant column bolted on; the two
-- tiers own different problems and the tables reflect that:
--
--   * There is no outbox here. The cloud is the destination, never a source.
--   * There are no invoice counters. Numbers are issued on the till and arrive already set.
--   * Foreign keys between synced aggregates are deliberately absent. Aggregates arrive in
--     whatever order the outbox drained them, and a sale must not be rejected because the
--     product it references has not been pushed yet. References are carried as the peer's
--     client_uuid and resolved at query time.
--
-- Every table keeps a unique client_uuid, which is what makes ingest idempotent: redelivering
-- a batch is an ON CONFLICT no-op, so the drain can retry as often as it likes.

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

-- ---------------------------------------------------------------------------
CREATE TABLE sales (
    id             bigserial   PRIMARY KEY,
    client_uuid    uuid        NOT NULL,
    tenant_id      bigint      NOT NULL REFERENCES tenants (id),

    -- Codes, not foreign keys: the branch row may not have synced yet.
    branch_code    text        NOT NULL,
    terminal_code  text        NOT NULL,
    invoice_number text        NOT NULL,

    subtotal_minor bigint      NOT NULL,
    discount_minor bigint      NOT NULL,
    tax_minor      bigint      NOT NULL,
    total_minor    bigint      NOT NULL,

    tax_mode       text        NOT NULL,
    tax_rate_bp    integer     NOT NULL,

    sold_at        timestamptz NOT NULL,
    -- When the cloud received it. The gap between this and sold_at is exactly how long the
    -- shop was offline, which makes it the honest input to any "is this till syncing?" alert.
    received_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_sales_client_uuid ON sales (client_uuid);
CREATE UNIQUE INDEX ux_sales_tenant_invoice ON sales (tenant_id, invoice_number);
CREATE INDEX ix_sales_tenant_sold_at ON sales (tenant_id, sold_at);

COMMENT ON COLUMN sales.received_at IS
    'Arrival time in the cloud. sold_at minus received_at is how long this sale sat in an outbox.';

-- ---------------------------------------------------------------------------
CREATE TABLE sale_items (
    id                  bigserial PRIMARY KEY,
    sale_id             bigint    NOT NULL REFERENCES sales (id) ON DELETE CASCADE,
    line_no             integer   NOT NULL,
    -- Not a FK. The product may arrive after the sale that mentions it.
    product_client_uuid uuid      NOT NULL,
    qty                 integer   NOT NULL,
    unit_price_minor    bigint    NOT NULL,
    discount_minor      bigint    NOT NULL,
    tax_minor           bigint    NOT NULL,
    line_total_minor    bigint    NOT NULL
);
CREATE UNIQUE INDEX ux_cloud_sale_items_sale_line ON sale_items (sale_id, line_no);
CREATE INDEX ix_cloud_sale_items_product ON sale_items (product_client_uuid);

-- ---------------------------------------------------------------------------
CREATE TABLE stock_movements (
    id                  bigserial   PRIMARY KEY,
    client_uuid         uuid        NOT NULL,
    tenant_id           bigint      NOT NULL REFERENCES tenants (id),
    branch_code         text        NOT NULL,
    product_client_uuid uuid        NOT NULL,
    qty_delta           integer     NOT NULL,
    reason              text        NOT NULL,
    occurred_at         timestamptz NOT NULL,
    received_at         timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_cloud_movements_client_uuid ON stock_movements (client_uuid);
CREATE INDEX ix_cloud_movements_product ON stock_movements (tenant_id, product_client_uuid);

COMMENT ON TABLE stock_movements IS
    'Movements, not levels — the same rule as the shop PC. Two branches reconcile by addition, which is why no conflict resolution is needed.';
