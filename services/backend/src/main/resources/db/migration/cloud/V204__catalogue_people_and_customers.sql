-- V204 — the cloud side of the catalogue, the staff and the customers (M3-12).
--
-- Same three rules as V200 and V203. No outbox, no counters, and no foreign keys between synced
-- aggregates: a sale may reach the cloud before the product it sold or the customer it was for, and
-- rejecting it for that would mean a shop's backlog can only ever drain in one order. Peers are
-- referenced by client_uuid and resolved at query time.
--
-- ## These three are mutable, like a shift and unlike a sale
--
-- A sale is rung up and never changes, so V200's upsert is a deliberate no-op. A product's price
-- changes, a user is promoted, a customer corrects a mistyped number — so every upsert below is a
-- real UPDATE, and every one of these aggregates is delivered again each time it is edited.
--
-- Delivery order therefore matters, and it is not guaranteed. The resolution is different from
-- V203's, and simpler: a shift is monotonic because CLOSED must not be reopened, whereas these
-- carry no state machine at all. Last writer wins, and `updated_at` on the row records when the
-- cloud last heard. A shop editing a price twice while offline delivers both rows; whichever lands
-- second is the one that stands, and both are the shop's own edits made minutes apart.
--
-- ## What is deliberately not synced
--
-- **`users.pin_hash`.** The cloud never receives a credential. Its reason for holding users at all
-- is so the console can print "authorised by Kumari" instead of an id — and a hash it does not need
-- is a hash that can leak from a place the shop does not control. This is also why offline auth
-- (M3-09) is entirely local: a till that could authenticate against the cloud would need the cloud
-- to hold something worth stealing.
--
-- **`customers.email` and `customers.note`.** Held locally, not shipped. PDPA (M5-10) is coming, the
-- console has no reader for either, and a column that exists only so that something might one day
-- read it is personal data being copied to a second jurisdiction for no reason anybody wrote down.
-- The name and number are here because the console's sales views name the customer.

-- ---------------------------------------------------------------------------
-- products
-- ---------------------------------------------------------------------------
CREATE TABLE products (
    id           bigserial   PRIMARY KEY,
    client_uuid  uuid        NOT NULL,
    tenant_id    bigint      NOT NULL REFERENCES tenants (id),

    sku          text        NOT NULL,
    name         text        NOT NULL,

    -- The price as it stands now. The price a given sale charged is on the sale line, stamped at
    -- the time (M1-05) — this column must never be used to re-derive what an old sale was worth.
    price_minor  bigint      NOT NULL,
    tax_mode     text        NOT NULL,
    tax_rate_bp  integer     NOT NULL,

    -- Category as a name, not an id. The cloud has no categories table and needs none: the console
    -- groups by the string, and a join table for a label nobody edits from here is a second thing
    -- to keep in step for no reader's benefit.
    category     text,

    active       boolean     NOT NULL DEFAULT true,

    received_at  timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_cloud_products_client_uuid ON products (client_uuid);
CREATE INDEX ix_cloud_products_tenant_sku ON products (tenant_id, sku);

CREATE TRIGGER trg_cloud_products_updated_at BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN products.price_minor IS
    'The price now. Never what an old sale charged - that is stamped on the sale line and this column moves.';

-- The barcodes a product answers to. Replaced wholesale on every delivery rather than merged: a
-- barcode removed at the shop has to disappear here too, and a merge cannot express a removal.
CREATE TABLE product_barcodes (
    id         bigserial PRIMARY KEY,
    product_id bigint    NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    barcode    text      NOT NULL,
    is_primary boolean   NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX ux_cloud_product_barcodes ON product_barcodes (product_id, barcode);

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
-- No pin_hash. See the header: the cloud holds these so the console can name whoever authorised
-- something, and nothing more.
CREATE TABLE users (
    id           bigserial   PRIMARY KEY,
    client_uuid  uuid        NOT NULL,
    tenant_id    bigint      NOT NULL REFERENCES tenants (id),

    code         text        NOT NULL,
    display_name text        NOT NULL,
    role         text        NOT NULL,
    active       boolean     NOT NULL DEFAULT true,

    received_at  timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_cloud_users_client_uuid ON users (client_uuid);
CREATE INDEX ix_cloud_users_tenant_code ON users (tenant_id, code);

CREATE TRIGGER trg_cloud_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE users IS
    'Staff, so the console can name who did something. Deliberately carries no credential - the till authenticates entirely locally (M3-09).';

-- ---------------------------------------------------------------------------
-- customers
-- ---------------------------------------------------------------------------
CREATE TABLE customers (
    id          bigserial   PRIMARY KEY,
    client_uuid uuid        NOT NULL,
    tenant_id   bigint      NOT NULL REFERENCES tenants (id),

    name        text        NOT NULL,
    -- Digits, normalised on the till before it ever left. Not unique here: uniqueness is a rule
    -- the shop enforces on its own database, and the cloud rejecting a row for breaking it would
    -- stall a backlog over a conflict only the shop can resolve.
    phone       text,
    active      boolean     NOT NULL DEFAULT true,

    received_at timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_cloud_customers_client_uuid ON customers (client_uuid);
CREATE INDEX ix_cloud_customers_tenant_phone ON customers (tenant_id, phone);

CREATE TRIGGER trg_cloud_customers_updated_at BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- The sale that names one
-- ---------------------------------------------------------------------------
-- A uuid and not a foreign key, for the reason at the top: the sale may arrive before the customer.
ALTER TABLE sales ADD COLUMN customer_client_uuid uuid;
CREATE INDEX ix_cloud_sales_customer ON sales (customer_client_uuid)
    WHERE customer_client_uuid IS NOT NULL;
