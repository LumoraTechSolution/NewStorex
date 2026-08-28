-- V206 — client_uuid is unique per tenant, not globally (M4-01).
--
-- Cloud tier only. V205 made the tenant come from the credential, which stops a caller *naming*
-- another shop. This closes the way it could still reach one by accident of key space.
--
-- ## What was wrong
--
-- Every synced table carried `UNIQUE (client_uuid)` with no tenant in it, and every upsert used
-- that index as its ON CONFLICT target. So the identity of a sale in the cloud was its uuid alone,
-- across all tenants. Two shops that ever produced the same uuid would collide, and the collision
-- resolves in the worst available direction: the INSERT ... ON CONFLICT ... RETURNING id hands back
-- the *existing* row's id regardless of whose it is, so the second shop's sale is either silently
-- discarded — reported to that till as accepted, and then absent from its own reports forever —
-- or written into the first shop's aggregate.
--
-- ## Why fix it when v4 uuids do not collide
--
-- They do not collide by accident, and that is the entire strength of the old design: an
-- invariant that holds because the inputs are usually well-behaved. It is not a boundary. A
-- tenant that sends a uuid it did not generate is not doing anything the schema forbids, and
-- nothing in the ingest path re-checks the tenant of the row it landed on.
--
-- It is also inconsistent with what these tables already do. `sales` has been keyed
-- `(tenant_id, invoice_number)` since V200 and `refunds` `(tenant_id, credit_note_number)` since
-- V203 — every *natural* key here is already tenant-scoped, because invoice numbers obviously
-- repeat across shops. The client uuid is the same kind of key and was the one exception.
--
-- And it is only cheap while the cloud is empty. Re-keying a unique index that live ingest depends
-- on, once real shops are pushing to it, is the sort of migration §A exists to avoid.
--
-- ## What does not change
--
-- `tenants.client_uuid` stays globally unique. It is the one identifier that genuinely spans
-- tenants — it is what a tenant *is* — so there is no tenant to scope it to.
--
-- Idempotency is untouched. Redelivery is still an ON CONFLICT no-op, because a redelivered batch
-- arrives on the same credential and therefore the same tenant_id: the conflict target still
-- matches every row the same till sent before.

-- ---------------------------------------------------------------------------
DROP INDEX ux_sales_client_uuid;
CREATE UNIQUE INDEX ux_sales_tenant_client_uuid ON sales (tenant_id, client_uuid);

DROP INDEX ux_cloud_movements_client_uuid;
CREATE UNIQUE INDEX ux_cloud_movements_tenant_client_uuid ON stock_movements (tenant_id, client_uuid);

DROP INDEX ux_cloud_shifts_client_uuid;
CREATE UNIQUE INDEX ux_cloud_shifts_tenant_client_uuid ON shifts (tenant_id, client_uuid);

DROP INDEX ux_cloud_cash_movements_client_uuid;
CREATE UNIQUE INDEX ux_cloud_cash_movements_tenant_client_uuid ON cash_movements (tenant_id, client_uuid);

DROP INDEX ux_cloud_refunds_client_uuid;
CREATE UNIQUE INDEX ux_cloud_refunds_tenant_client_uuid ON refunds (tenant_id, client_uuid);

DROP INDEX ux_cloud_products_client_uuid;
CREATE UNIQUE INDEX ux_cloud_products_tenant_client_uuid ON products (tenant_id, client_uuid);

DROP INDEX ux_cloud_users_client_uuid;
CREATE UNIQUE INDEX ux_cloud_users_tenant_client_uuid ON users (tenant_id, client_uuid);

DROP INDEX ux_cloud_customers_client_uuid;
CREATE UNIQUE INDEX ux_cloud_customers_tenant_client_uuid ON customers (tenant_id, client_uuid);
