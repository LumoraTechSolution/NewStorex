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

INSERT INTO products (client_uuid, tenant_id, sku, barcode, name, price_minor, tax_mode, tax_rate_bp)
SELECT v.client_uuid, t.id, v.sku, v.barcode, v.name, v.price_minor, 'INCLUSIVE', 1800
FROM tenants t,
     (VALUES
        ('00000000-0000-4000-8000-000000000101'::uuid, 'TEA-400',  '4791234567890', 'Ceylon Tea 400g',      45000::bigint),
        ('00000000-0000-4000-8000-000000000102'::uuid, 'RICE-5KG', '4791234567906', 'Samba Rice 5kg',      285000::bigint),
        ('00000000-0000-4000-8000-000000000103'::uuid, 'SOAP-1',   '4791234567913', 'Sandalwood Soap',      18500::bigint),
        ('00000000-0000-4000-8000-000000000104'::uuid, 'MILK-1L',  '4791234567920', 'Fresh Milk 1L',        49000::bigint)
     ) AS v(client_uuid, sku, barcode, name, price_minor)
WHERE t.client_uuid = '00000000-0000-4000-8000-000000000001'
ON CONFLICT (client_uuid) DO NOTHING;

SELECT 'seeded: ' || (SELECT count(*) FROM products) || ' products, '
                  || (SELECT count(*) FROM branches) || ' branch(es)' AS result;
