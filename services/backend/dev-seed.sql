-- Development seed. Run with: pnpm db:seed
--
-- Deliberately NOT a Flyway migration. Migrations run on every till, and a real shop's
-- first tenant is created by the installer's first-run wizard (M5-03), not by us. This
-- file exists so a developer can get a working shop in one command.
--
-- Idempotent: run it as often as you like.

INSERT INTO tenants (client_uuid, name)
VALUES ('00000000-0000-4000-8000-000000000001', 'Kandy Stores')
ON CONFLICT (client_uuid) DO NOTHING;

INSERT INTO branches (client_uuid, tenant_id, code, name)
SELECT '00000000-0000-4000-8000-000000000002', id, 'KND', 'Kandy Main'
FROM tenants WHERE client_uuid = '00000000-0000-4000-8000-000000000001'
ON CONFLICT (client_uuid) DO NOTHING;

INSERT INTO products (client_uuid, tenant_id, sku, name, price_minor, tax_mode, tax_rate_bp)
SELECT v.client_uuid, t.id, v.sku, v.name, v.price_minor, 'INCLUSIVE', v.tax_rate_bp
FROM tenants t,
     (VALUES
        ('00000000-0000-4000-8000-000000000101'::uuid, 'TEA-400',  'Ceylon Tea 400g',      45000::bigint, 1800),
        ('00000000-0000-4000-8000-000000000102'::uuid, 'RICE-5KG', 'Samba Rice 5kg',      285000::bigint, 1800),
        ('00000000-0000-4000-8000-000000000103'::uuid, 'SOAP-1',   'Sandalwood Soap',      18500::bigint, 1800),
        ('00000000-0000-4000-8000-000000000104'::uuid, 'MILK-1L',  'Fresh Milk 1L',        49000::bigint, 1800),
        ('00000000-0000-4000-8000-000000000105'::uuid, 'MILK-400G','Milk Powder 400g',    139000::bigint, 1800),
        ('00000000-0000-4000-8000-000000000106'::uuid, 'SUGAR-1KG','White Sugar 1kg',      32000::bigint, 1800),
        -- Zero-rated, so a dev basket can actually mix rates (M1-18). Without one of these
        -- every cart on this machine is single-rate and the per-line path never runs
        -- outside its tests — which is how it stayed broken long enough to become M1-18.
        ('00000000-0000-4000-8000-000000000107'::uuid, 'BREAD-450','Bread 450g',           25000::bigint, 0)
     ) AS v(client_uuid, sku, name, price_minor, tax_rate_bp)
WHERE t.client_uuid = '00000000-0000-4000-8000-000000000001'
ON CONFLICT (client_uuid) DO NOTHING;

-- Barcodes live in their own table from V103. Milk Powder deliberately carries two, which
-- is the case the single-column schema could not express: same goods, two supplier codes.
-- Bread carries one so a scanned basket can mix tax rates end to end (M1-18, M1-16).
INSERT INTO product_barcodes (client_uuid, tenant_id, product_id, barcode, is_primary)
SELECT v.client_uuid, p.tenant_id, p.id, v.barcode, v.is_primary
FROM products p,
     (VALUES
        ('00000000-0000-4000-8000-000000000101'::uuid, '00000000-0000-4000-8000-000000000201'::uuid, '4791234567890', true),
        ('00000000-0000-4000-8000-000000000102'::uuid, '00000000-0000-4000-8000-000000000202'::uuid, '4791234567906', true),
        ('00000000-0000-4000-8000-000000000103'::uuid, '00000000-0000-4000-8000-000000000203'::uuid, '4791234567913', true),
        ('00000000-0000-4000-8000-000000000104'::uuid, '00000000-0000-4000-8000-000000000204'::uuid, '4791234567920', true),
        ('00000000-0000-4000-8000-000000000105'::uuid, '00000000-0000-4000-8000-000000000205'::uuid, '4791234567937', true),
        ('00000000-0000-4000-8000-000000000105'::uuid, '00000000-0000-4000-8000-000000000206'::uuid, '8901234567895', false),
        ('00000000-0000-4000-8000-000000000106'::uuid, '00000000-0000-4000-8000-000000000207'::uuid, '4791234567944', true),
        ('00000000-0000-4000-8000-000000000107'::uuid, '00000000-0000-4000-8000-000000000208'::uuid, '4791234567951', true)
     ) AS v(product_client_uuid, client_uuid, barcode, is_primary)
WHERE p.client_uuid = v.product_client_uuid
-- Bare DO NOTHING, not ON CONFLICT (client_uuid): on a database that already ran V103, the
-- carried-over rows hold these barcodes under uuids derived from the product, so the
-- collision is on (tenant_id, barcode) and naming one index would abort the statement.
ON CONFLICT DO NOTHING;

-- M3-02. Categories, and the products that sit in them. Three is enough to prove the picker
-- groups and the rename path moves several products at once; a full taxonomy here would just be
-- fixture noise a developer has to scroll past.
INSERT INTO product_categories (client_uuid, tenant_id, name)
SELECT v.client_uuid, t.id, v.name
FROM tenants t,
     (VALUES
        ('00000000-0000-4000-8000-000000000401'::uuid, 'Beverages'),
        ('00000000-0000-4000-8000-000000000402'::uuid, 'Groceries'),
        ('00000000-0000-4000-8000-000000000403'::uuid, 'Household')
     ) AS v(client_uuid, name)
WHERE t.client_uuid = '00000000-0000-4000-8000-000000000001'
ON CONFLICT DO NOTHING;

-- Bread is deliberately left uncategorised: a product with no category is the normal starting
-- state, and a seed where every row has one means that path is never seen while developing.
UPDATE products p
   SET category_id = c.id
  FROM product_categories c,
       (VALUES
          ('00000000-0000-4000-8000-000000000101'::uuid, 'Beverages'),
          ('00000000-0000-4000-8000-000000000102'::uuid, 'Groceries'),
          ('00000000-0000-4000-8000-000000000103'::uuid, 'Household'),
          ('00000000-0000-4000-8000-000000000104'::uuid, 'Beverages'),
          ('00000000-0000-4000-8000-000000000105'::uuid, 'Beverages'),
          ('00000000-0000-4000-8000-000000000106'::uuid, 'Groceries')
       ) AS v(product_client_uuid, category_name)
 WHERE p.client_uuid = v.product_client_uuid
   AND c.tenant_id = p.tenant_id
   AND c.name = v.category_name
   AND p.category_id IS NULL;

-- M2. The variance threshold (D1). The manager PIN that used to live here moved to the users
-- table in V109 (M3-08) — one credential store, not two.
INSERT INTO tenant_settings (tenant_id, cash_variance_threshold_minor)
SELECT id, 10000
FROM tenants WHERE client_uuid = '00000000-0000-4000-8000-000000000001'
ON CONFLICT (tenant_id) DO NOTHING;

-- M3-08. Staff, so that every audit column has a real person to point at and the permission
-- gates have something to gate on.
--
-- All four PINs are BCrypt of "1234". A development seed that never runs on a shop PC — M5-03's
-- first-run wizard provisions the real owner — and one PIN across four users is deliberate: the
-- point of these rows is to exercise the four roles, and four PINs to remember while testing a
-- refusal path is friction with no payoff.
--
-- The roles matter more than the names. NIMAL is a CASHIER precisely so there is a user who
-- cannot authorise a refund: without one, every manual test of the M2-07 gate passes, and the
-- half that should fail is never tried.
INSERT INTO users (client_uuid, tenant_id, code, display_name, role, pin_hash)
SELECT v.client_uuid, t.id, v.code, v.display_name, v.role,
       '$2a$10$uarjBQx1U89pNHyI6orBeOVkYAQbadgeECRV33UtlCj223pkkrgWy'
FROM tenants t,
     (VALUES
        ('00000000-0000-4000-8000-000000000301'::uuid, 'OWNER', 'Kumari Perera',  'OWNER'),
        ('00000000-0000-4000-8000-000000000302'::uuid, 'MGR',   'Ranjith Silva',  'MANAGER'),
        ('00000000-0000-4000-8000-000000000303'::uuid, 'SUP',   'Dilani Fernando','SUPERVISOR'),
        ('00000000-0000-4000-8000-000000000304'::uuid, 'NIMAL', 'Nimal Jayasuriya','CASHIER')
     ) AS v(client_uuid, code, display_name, role)
WHERE t.client_uuid = '00000000-0000-4000-8000-000000000001'
-- Bare DO NOTHING, not ON CONFLICT (client_uuid): on a database that already ran V109, the
-- migrated manager holds the code MGR under a generated uuid, so the collision is on
-- (tenant_id, code) and naming one index would abort the statement.
ON CONFLICT DO NOTHING;

-- M3-04. Two suppliers, so the delivery form has a picker with a choice in it and the
-- retire/bring-back path has something to act on. No goods receipts are seeded: a delivery
-- writes RECEIVE movements, and a seed that quietly put stock on the shelf would make every
-- manual check of "on hand is the sum of movements" start from a number nobody chose.
INSERT INTO suppliers (client_uuid, tenant_id, name, contact)
SELECT v.client_uuid, t.id, v.name, v.contact
FROM tenants t,
     (VALUES
        ('00000000-0000-4000-8000-000000000501'::uuid, 'Ceylon Wholesale',  '011 234 5678'),
        ('00000000-0000-4000-8000-000000000502'::uuid, 'Kandy Fresh Goods', 'Sunil — 077 123 4567')
     ) AS v(client_uuid, name, contact)
WHERE t.client_uuid = '00000000-0000-4000-8000-000000000001'
ON CONFLICT DO NOTHING;

SELECT 'seeded: ' || (SELECT count(*) FROM products) || ' products, '
                  || (SELECT count(*) FROM product_categories) || ' categories, '
                  || (SELECT count(*) FROM suppliers) || ' suppliers, '
                  || (SELECT count(*) FROM product_barcodes) || ' barcodes, '
                  || (SELECT count(*) FROM branches) || ' branch(es), '
                  || (SELECT count(*) FROM tenant_settings) || ' settings row(s), '
                  || (SELECT count(*) FROM users) || ' user(s)' AS result;
