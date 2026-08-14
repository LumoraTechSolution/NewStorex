-- V103 — barcodes become their own table, and the catalogue becomes searchable (M1-06).
--
-- ## Why a table and not a column
--
-- One product genuinely has several barcodes. The manufacturer's EAN, a different EAN
-- after a packaging change, a supplier's own code on the same goods, and a shop-printed
-- label on loose items are all the same product to a shopkeeper. V100's single
-- products.barcode column forces a choice between them, and the one that loses simply
-- does not scan — which the cashier experiences as "the system is broken".
--
-- The column is migrated into the table and then dropped. Keeping both would leave two
-- places recording the same fact, and they would disagree within a month.
--
-- ## The one hard rule
--
-- A barcode resolves to exactly one product. That is what ux_product_barcodes_tenant_code
-- enforces, and it is not a tidiness constraint: M1-08 requires a scan to add an item with
-- zero clicks, and an ambiguous code would mean stopping to ask the cashier which product
-- they meant. Better to refuse the duplicate at the point someone types it in.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
CREATE TABLE product_barcodes (
    id          bigserial   PRIMARY KEY,
    client_uuid uuid        NOT NULL,
    tenant_id   bigint      NOT NULL REFERENCES tenants (id),
    product_id  bigint      NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    barcode     text        NOT NULL CHECK (btrim(barcode) <> ''),
    -- The one printed on a receipt or shown in the back office. Display only; every
    -- barcode on the product scans equally well.
    is_primary  boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_product_barcodes_client_uuid ON product_barcodes (client_uuid);

-- The scanner's index. Unique so a scan can never be ambiguous, and the lookup that
-- happens on every single item sold is one index probe.
CREATE UNIQUE INDEX ux_product_barcodes_tenant_code ON product_barcodes (tenant_id, barcode);

CREATE INDEX ix_product_barcodes_product ON product_barcodes (product_id);

-- At most one primary per product.
CREATE UNIQUE INDEX ux_product_barcodes_primary ON product_barcodes (product_id)
    WHERE is_primary;

CREATE TRIGGER trg_product_barcodes_updated_at BEFORE UPDATE ON product_barcodes
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE product_barcodes IS
    'One product may carry several codes. A code resolves to exactly one product - that is what makes a scan a zero-click action.';

-- ---------------------------------------------------------------------------
-- Carry V100's single barcode across. Deterministic uuids derived from the product so
-- re-running against a restored backup produces the same rows.
INSERT INTO product_barcodes (client_uuid, tenant_id, product_id, barcode, is_primary)
SELECT
    md5('barcode:' || p.client_uuid::text)::uuid,
    p.tenant_id,
    p.id,
    btrim(p.barcode),
    true
FROM products p
WHERE p.barcode IS NOT NULL AND btrim(p.barcode) <> '';

DROP INDEX IF EXISTS ux_products_tenant_barcode;
ALTER TABLE products DROP COLUMN barcode;

-- ---------------------------------------------------------------------------
-- Search indexes.
--
-- Trigram rather than a tsvector: a cashier types "ceyl" or "milk 1", not words in a
-- language the full-text parser knows, and product names are half brand names anyway.
-- Trigram handles the leading-wildcard LIKE that a plain b-tree cannot.
CREATE INDEX ix_products_name_trgm ON products USING gin (lower(name) gin_trgm_ops);
CREATE INDEX ix_products_sku_trgm ON products USING gin (lower(sku) gin_trgm_ops);

-- Most of a shop's catalogue is active; the partial index keeps the discontinued lines
-- out of every search.
CREATE INDEX ix_products_active_name ON products (name) WHERE active;
