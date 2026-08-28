-- V116 — customers, and the sale that names one (M3-11).
--
-- "Basic customer records" is the whole task and the word basic is doing work. There is no loyalty
-- balance, no points, no credit limit and no ledger. Those are v2, and every one of them is a
-- *movement* table hanging off this one — which is exactly why this migration has to get the
-- identity right and can afford to get everything else later.
--
-- ## What a shop actually looks somebody up by
--
-- A phone number. Not an email, not a card, not a surname: the shopkeeper asks "number?" and types
-- seven to ten digits on the same keypad they ring up sales on. So the phone is the lookup key and
-- the thing uniqueness is enforced on, and the name is what gets printed and greeted.
--
-- Stored as digits alone. A shop will type 077 123 4567, 0771234567 and +94 77 123 4567 for one
-- person across three visits, and a column that keeps them as typed is a column with three rows for
-- one customer and a unique index that never fires. Normalising on the way in is the only way the
-- uniqueness below means anything. What is lost is the shopkeeper's own spacing, which nobody reads
-- back off a screen — the display formats it again.
--
-- ## Nullable, because a shop has customers it has no number for
--
-- The phone is optional and the unique index is partial. A walk-in who wants their name on an
-- invoice for the office is a real customer with nothing to look them up by, and a NOT NULL here
-- would be answered by staff typing 0000000 — which then collides on the second one, and is a
-- worse record than no record.
--
-- ## The sale points at the customer; the customer never points at the sale
--
-- `sales.customer_id` is nullable and almost always null: the overwhelming majority of sales in a
-- grocery are anonymous and must stay that way, because the alternative is a prompt between the
-- last item and the drawer opening. Nothing about the sale changes when a customer is attached —
-- no price, no tax, no total. That is deliberate for v1: the moment a customer can change what is
-- charged, the receipt and the report need to agree about which customer, and a mis-tap becomes a
-- pricing error rather than a wrong name.
--
-- The FK direction is the one that survives. A customer's purchase history is a query over sales,
-- not a list on the customer — a stored list would be a second copy of the same fact, and §A's
-- objection to stored levels applies to stored anything.
--
-- ## Deactivated, never deleted
--
-- Same as `users` (V109): the FK from `sales` means a DELETE either fails or orphans an invoice,
-- and an invoice that used to name somebody and now names nobody is a worse audit trail than one
-- that names a customer who has left. PDPA erasure (M5-10) is a separate and deliberate act that
-- has to decide what happens to the invoices, which is not a decision a delete button should make
-- by accident.

CREATE TABLE customers (
    id          bigserial   PRIMARY KEY,
    client_uuid uuid        NOT NULL,
    tenant_id   bigint      NOT NULL REFERENCES tenants (id),

    name        text        NOT NULL CHECK (length(trim(name)) > 0),

    -- Digits only, normalised by CustomerService. See the header: a column that keeps the
    -- shopkeeper's spacing is a column with three rows for one person.
    phone       text        CHECK (phone IS NULL OR phone ~ '^[0-9]{6,15}$'),

    email       text,

    -- Anything the shop wants to remember: "prefers the small loaf", "office account, invoices
    -- monthly". Free text on purpose — the alternative is guessing which four fields a grocery
    -- needs and being wrong about all of them.
    note        text,

    active      boolean     NOT NULL DEFAULT true,

    created_by  bigint      NOT NULL REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_customers_client_uuid ON customers (client_uuid);

-- One customer per number. Partial, because the number is optional and several customers may
-- legitimately have none — a plain unique index would allow exactly one of those.
CREATE UNIQUE INDEX ux_customers_tenant_phone
    ON customers (tenant_id, phone) WHERE phone IS NOT NULL;

-- The lookup at the till: a partial digit string, typed on the keypad, matched as a prefix.
-- text_pattern_ops is what makes `phone LIKE '077%'` an index scan under any collation.
CREATE INDEX ix_customers_phone_prefix
    ON customers (tenant_id, phone text_pattern_ops) WHERE active;

-- And the back office's search by name, which is a substring rather than a prefix — the same
-- trigram reasoning as V103's product search: somebody types "pere", not "Perera, K".
CREATE INDEX ix_customers_name_trgm ON customers USING gin (lower(name) gin_trgm_ops);

CREATE TRIGGER trg_customers_updated_at BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE customers IS
    'Named customers. Rows are deactivated, never deleted - sales reference them and PDPA erasure (M5-10) is a deliberate act, not a delete button.';
COMMENT ON COLUMN customers.phone IS
    'Digits only, normalised on write. The lookup key a shop actually uses, and what uniqueness is enforced on.';

-- ---------------------------------------------------------------------------
-- The sale that names one
-- ---------------------------------------------------------------------------
ALTER TABLE sales ADD COLUMN customer_id bigint REFERENCES customers (id);

-- Partial: almost every sale in a grocery is anonymous, and an index over a column that is null
-- nine times in ten should not carry those rows. This is the "what has this person bought" query.
CREATE INDEX ix_sales_customer ON sales (customer_id, sold_at DESC) WHERE customer_id IS NOT NULL;

COMMENT ON COLUMN sales.customer_id IS
    'Who the sale was for, when anybody asked. Null is the normal case and changes nothing about the money - see the V116 header.';
