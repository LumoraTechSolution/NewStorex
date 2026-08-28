-- V110 — categories, and the last column the back office needs to edit a product (M3-02).
--
-- V100 gave products a SKU, a name, a price and a tax treatment; V103 gave them as many barcodes
-- as a shopkeeper actually has. What was missing is the one field nobody sells without and every
-- report groups by: what kind of thing this is.
--
-- ## A table, not a text column on products
--
-- `products.category text` is the smaller change and it is wrong for two reasons that both show up
-- within a month of real use. A shop renames an aisle — "Beverages" becomes "Drinks" — and a text
-- column turns that into an UPDATE across the whole catalogue that nobody remembers to run on the
-- rows added since. And a typo is not a typo, it is a new category: "Bevarages" sits quietly
-- beside "Beverages" in the picker and splits a month's sales in two on the report that was the
-- reason for having categories at all.
--
-- A table costs one join on a screen that is used a few times a week, and makes both of those
-- impossible: the rename is one row, and the picker offers what exists rather than a free-text box.
--
-- ## Nullable, deliberately
--
-- A product may have no category. Requiring one means the first thing a shop must do before adding
-- its first product is invent a taxonomy, and what shops actually do under that pressure is create
-- one category called "General" and put everything in it — which is the uncategorised state, with
-- extra steps and a name that now has to be maintained.
--
-- ## Deactivated, never deleted
--
-- Same rule as `users` in V109 and for the same reason: products point at these rows, and a report
-- run over last quarter has to keep resolving the category a sale was made under. `active = false`
-- takes a category out of the picker and leaves history intact.

-- ---------------------------------------------------------------------------
CREATE TABLE product_categories (
    id          bigserial   PRIMARY KEY,
    client_uuid uuid        NOT NULL,
    tenant_id   bigint      NOT NULL REFERENCES tenants (id),
    name        text        NOT NULL CHECK (length(btrim(name)) > 0),
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_product_categories_client_uuid ON product_categories (client_uuid);

-- Case-folded: "Beverages" and "beverages" are one aisle, and letting both exist recreates
-- exactly the split this table was introduced to prevent.
CREATE UNIQUE INDEX ux_product_categories_tenant_name
    ON product_categories (tenant_id, lower(btrim(name)));

CREATE TRIGGER trg_product_categories_updated_at BEFORE UPDATE ON product_categories
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE product_categories IS
    'What kind of thing a product is. Rows are deactivated, never deleted - a report over last quarter has to keep resolving the category its sales were made under.';

-- ---------------------------------------------------------------------------
-- No ON DELETE clause, which means NO ACTION: a category that still has products cannot be
-- deleted, and the back office offers no delete anyway. The default is the safe one here and
-- spelling out RESTRICT would only imply the other options were considered and rejected per-case.
ALTER TABLE products
    ADD COLUMN category_id bigint REFERENCES product_categories (id);

-- The back office lists by category and M3-10 reports by it. Partial on NOT NULL because most of
-- the interesting rows have one and the uncategorised tail should not be in the index.
CREATE INDEX ix_products_category ON products (tenant_id, category_id)
    WHERE category_id IS NOT NULL;

COMMENT ON COLUMN products.category_id IS
    'Nullable on purpose. A shop must be able to add a product before it has decided on a taxonomy.';
