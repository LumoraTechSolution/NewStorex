-- V124 — running numbers for auto-generated product codes.
--
-- Adding a product used to force the shopkeeper to invent a code. Left blank now, the code is
-- allocated from here as PREFIX-NNN, where the prefix is derived from the product's category:
-- BEV-001 for the first beverage, P-0001 for something not in a category yet.
--
-- ## Why a table and not a query over products
--
-- The cheaper-looking thing is `SELECT max(sku) FROM products WHERE sku LIKE 'BEV-%'`, and it is
-- wrong twice.
--
-- ProductAdminService.save writes `SET sku = ?`, so a product's code is mutable. There is no
-- product delete, but a rename removes the highest number in a prefix just as effectively — after
-- which the next create silently reissues a code the shop has already printed on a shelf label
-- and re-imports its CSV by. That is not a collision the unique index can catch, because the
-- first holder is genuinely gone.
--
-- And read-then-write is a race: two tills both read 2, both write BEV-003, and the loser takes a
-- unique-index violation. ProductAdminService.create is @Transactional, so it cannot catch that
-- and retry — a constraint violation aborts the transaction and leaves nothing to read the winner
-- back with. See the note in CashMovementService, and the same one in RefundService.
--
-- One atomic statement against this table has neither problem.
--
-- ## Gaps are expected, and are not a defect
--
-- Unlike invoice_counters (V101, V105), nothing audits this sequence for missing entries. A
-- number is burnt by a retried create that loses on client_uuid, and by any rollback after
-- allocation. BEV-001 followed by BEV-003 is a correct catalogue. Do not add a lock to close it.
--
-- ## No range_end
--
-- invoice_counters bounds its blocks because an invoice number outside a reserved range is a
-- compliance problem. A product code has no such reader, so this counter simply climbs, and
-- BEV-1000 is a fine code even though the format pads to three.

CREATE TABLE product_sku_counters (
    id         bigserial   PRIMARY KEY,
    tenant_id  bigint      NOT NULL REFERENCES tenants (id),
    -- The derived category prefix, already uppercased and stripped to A-Z0-9 by
    -- ProductSkuAllocator. Categories whose names share a prefix — "Beverages" and "Beverage",
    -- or every Sinhala name, which strips to nothing and falls back to P — deliberately share one
    -- counter. That keeps the codes unique; it only means the prefix is a hint, not a key.
    prefix     text        NOT NULL CHECK (prefix ~ '^[A-Z0-9]{1,8}$'),
    -- The next number to hand out. Allocation is a single atomic INSERT ... ON CONFLICT DO UPDATE
    -- ... RETURNING, so two concurrent creates can never receive the same one.
    next_seq   bigint      NOT NULL DEFAULT 1 CHECK (next_seq > 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_product_sku_counters ON product_sku_counters (tenant_id, prefix);

CREATE TRIGGER trg_product_sku_counters_updated_at BEFORE UPDATE ON product_sku_counters
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE product_sku_counters IS
    'Per-prefix running number for generated product codes. Format: PREFIX-NNN, e.g. BEV-001. Gaps are expected.';
