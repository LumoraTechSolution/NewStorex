-- V203 — the cloud side of cash control and returns (M2-12).
--
-- Same three rules as V200. No outbox, no counters, no foreign keys between synced
-- aggregates — a refund may reach the cloud before the sale it reverses, and rejecting it for
-- that would mean a shop's backlog can only ever drain in one order. Peers are referenced by
-- their client_uuid and resolved at query time.
--
-- ## One thing here is genuinely unlike sales: a shift arrives twice
--
-- Every aggregate the cloud has ingested until now was immutable once written — a sale is
-- rung up and never changes, so `ON CONFLICT DO UPDATE SET client_uuid = excluded.client_uuid`
-- (V200's no-op) was enough. A shift is not immutable. It syncs when it opens, and again when
-- it closes carrying the count, the variance and the reason. So this upsert is a real update.
--
-- That makes delivery order matter for the first time, and delivery order is not guaranteed:
-- if the open row fails and gets backed off while the close row goes through, the open row
-- arrives afterwards and would reopen a shift the shop has already reconciled. The upsert in
-- SyncIngestService therefore refuses to move a CLOSED shift backwards. Monotonic, so any
-- arrival order converges on the same state — the same property that makes redelivery free
-- everywhere else, extended to an aggregate that can legitimately change.

-- ---------------------------------------------------------------------------
CREATE TABLE shifts (
    id                  bigserial   PRIMARY KEY,
    client_uuid         uuid        NOT NULL,
    tenant_id           bigint      NOT NULL REFERENCES tenants (id),
    branch_code         text        NOT NULL,
    terminal_code       text        NOT NULL,

    status              text        NOT NULL,
    opened_at           timestamptz NOT NULL,
    opening_float_minor bigint      NOT NULL,

    closed_at           timestamptz,
    counted_cash_minor  bigint,
    expected_cash_minor bigint,
    variance_minor      bigint,
    variance_reason     text,
    variance_note       text,

    received_at         timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_cloud_shifts_client_uuid ON shifts (client_uuid);
CREATE INDEX ix_cloud_shifts_tenant_opened ON shifts (tenant_id, opened_at);
-- The attention feed (M4-07) reads exactly this: closed shifts whose variance is not zero,
-- newest first. Partial, because a shop's history is overwhelmingly shifts that balanced.
CREATE INDEX ix_cloud_shifts_variance ON shifts (tenant_id, closed_at)
    WHERE variance_minor IS NOT NULL AND variance_minor <> 0;

CREATE TRIGGER trg_cloud_shifts_updated_at BEFORE UPDATE ON shifts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE shifts IS
    'Unlike every other aggregate here, a shift is delivered twice — open, then close. The ingest upsert is monotonic so arrival order cannot reopen a reconciled shift.';

-- ---------------------------------------------------------------------------
-- The denominations behind a count. Evidence, not arithmetic: an owner looking at a
-- LKR 5,000 shortfall wants to know whether it was one note or a hundred coins.
CREATE TABLE shift_counts (
    id                 bigserial PRIMARY KEY,
    shift_id           bigint    NOT NULL REFERENCES shifts (id) ON DELETE CASCADE,
    phase              text      NOT NULL,
    denomination_minor bigint    NOT NULL,
    qty                integer   NOT NULL
);
CREATE UNIQUE INDEX ux_cloud_shift_counts ON shift_counts (shift_id, phase, denomination_minor);

-- ---------------------------------------------------------------------------
CREATE TABLE cash_movements (
    id                bigserial   PRIMARY KEY,
    client_uuid       uuid        NOT NULL,
    tenant_id         bigint      NOT NULL REFERENCES tenants (id),
    branch_code       text        NOT NULL,
    -- Not a FK: the shift may not have synced yet.
    shift_client_uuid uuid        NOT NULL,

    kind              text        NOT NULL,
    -- Signed on the till (V107) and signed here. Σ over this column is the drawer's movement,
    -- with no CASE and no reader having to know which kinds subtract.
    amount_minor      bigint      NOT NULL,
    reason_code       text        NOT NULL,
    note              text,

    occurred_at       timestamptz NOT NULL,
    received_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_cloud_cash_movements_client_uuid ON cash_movements (client_uuid);
CREATE INDEX ix_cloud_cash_movements_shift ON cash_movements (shift_client_uuid);
CREATE INDEX ix_cloud_cash_movements_tenant_occurred ON cash_movements (tenant_id, occurred_at);

-- ---------------------------------------------------------------------------
CREATE TABLE refunds (
    id                        bigserial   PRIMARY KEY,
    client_uuid               uuid        NOT NULL,
    tenant_id                 bigint      NOT NULL REFERENCES tenants (id),
    branch_code               text        NOT NULL,
    terminal_code             text        NOT NULL,

    -- Both codes, not FKs, and both NOT NULL: the reference is a fact about the refund even
    -- when the row it names has not arrived. A refund the cloud cannot tie to a sale is the
    -- one thing M2-06 exists to make impossible, so losing the link in transit is not
    -- acceptable merely because the sale is late.
    shift_client_uuid         uuid        NOT NULL,
    sale_client_uuid          uuid        NOT NULL,

    credit_note_number        text        NOT NULL,
    total_minor               bigint      NOT NULL,
    tax_minor                 bigint      NOT NULL,
    rounding_adjustment_minor bigint      NOT NULL DEFAULT 0,

    refunded_at               timestamptz NOT NULL,
    received_at               timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_cloud_refunds_client_uuid ON refunds (client_uuid);
CREATE UNIQUE INDEX ux_cloud_refunds_tenant_credit_note ON refunds (tenant_id, credit_note_number);
CREATE INDEX ix_cloud_refunds_sale ON refunds (sale_client_uuid);
CREATE INDEX ix_cloud_refunds_shift ON refunds (shift_client_uuid);
CREATE INDEX ix_cloud_refunds_tenant_refunded ON refunds (tenant_id, refunded_at);

-- ---------------------------------------------------------------------------
CREATE TABLE refund_items (
    id                  bigserial PRIMARY KEY,
    refund_id           bigint    NOT NULL REFERENCES refunds (id) ON DELETE CASCADE,
    line_no             integer   NOT NULL,
    -- The sale line these units came off, as (sale uuid, line no) — the pair the till knows
    -- and the cloud can resolve once the sale lands. sale_items has no uuid of its own on
    -- either side, because it is not an aggregate root.
    sale_line_no        integer   NOT NULL,
    product_client_uuid uuid      NOT NULL,
    qty                 integer   NOT NULL,
    unit_price_minor    bigint    NOT NULL,
    refund_total_minor  bigint    NOT NULL,
    tax_minor           bigint    NOT NULL,
    tax_mode            text      NOT NULL,
    tax_rate_bp         integer   NOT NULL,
    reason_code         text      NOT NULL,
    note                text,
    restock             boolean   NOT NULL
);
CREATE UNIQUE INDEX ux_cloud_refund_items_refund_line ON refund_items (refund_id, line_no);
CREATE INDEX ix_cloud_refund_items_product ON refund_items (product_client_uuid);
-- "Which products come back, and why" is the report this table exists to answer.
CREATE INDEX ix_cloud_refund_items_reason ON refund_items (reason_code);

-- ---------------------------------------------------------------------------
CREATE TABLE refund_payments (
    id           bigserial PRIMARY KEY,
    refund_id    bigint    NOT NULL REFERENCES refunds (id) ON DELETE CASCADE,
    line_no      integer   NOT NULL,
    kind         text      NOT NULL,
    amount_minor bigint    NOT NULL
);
CREATE UNIQUE INDEX ux_cloud_refund_payments_refund_line ON refund_payments (refund_id, line_no);

-- ---------------------------------------------------------------------------
-- Sales gain their shift here too, so the console can group a day's takings by who was on
-- the till. Nullable for the same reason as on the shop PC: sales that predate M2 have no
-- shift, and inventing one from a timestamp would be inventing a fact.
ALTER TABLE sales ADD COLUMN shift_client_uuid uuid;
CREATE INDEX ix_cloud_sales_shift ON sales (shift_client_uuid);
