-- V207 — tax invoices reach the cloud (M5-09).
--
-- Cloud tier. The desktop counterpart is V118, which carries the reasoning about why a tax invoice
-- is a document of its own rather than a flag on a sale.
--
-- Deliberately thinner than the desktop table. The cloud does not reprint invoices — the till that
-- issued one holds the copy, and a reprint has to come from there so that what is reprinted is what
-- was issued. What the owner's console needs is the ledger: which invoices exist, against which
-- supplies, for how much, and to whom. So the totals and the identifying particulars are here and
-- the line items are not; the sale they were taken from already synced with its own lines.
--
-- Same rule as every other table in this tier: no foreign key to `sales`. The invoice and the sale
-- may arrive in either order, and an invoice must not be rejected because the outbox happened to
-- drain it first. The link is carried as the sale's invoice number and resolved at query time.

CREATE TABLE tax_invoices (
    id             bigserial   PRIMARY KEY,
    client_uuid    uuid        NOT NULL,
    tenant_id      bigint      NOT NULL REFERENCES tenants (id),

    branch_code    text        NOT NULL,
    terminal_code  text        NOT NULL,

    -- Gazette 2481/22 §4.1(a): YYMMM-QQQQ-XXXXX, no spaces, at most forty characters.
    invoice_number text        NOT NULL,

    -- Not a foreign key — see the header. The receipt this was raised against.
    sale_invoice_number text   NOT NULL,

    issued_at      timestamptz NOT NULL,
    supplied_at    timestamptz NOT NULL,
    received_at    timestamptz NOT NULL DEFAULT now(),

    -- Stamped on the till at issue, not looked up here. A shop that re-registers or moves must not
    -- retrospectively change what an issued invoice says.
    supplier_tin   text        NOT NULL,

    -- Null for a walk-in consumer, which is most of them: purchaser particulars are required only
    -- where the purchaser is VAT-registered (Circular SEC/2026/E/03 §4.3).
    purchaser_tin  text,

    total_excl_vat_minor bigint NOT NULL,
    vat_minor            bigint NOT NULL,
    total_incl_vat_minor bigint NOT NULL
);

CREATE UNIQUE INDEX ux_cloud_tax_invoices_tenant_client_uuid
    ON tax_invoices (tenant_id, client_uuid);

-- The gazette's own uniqueness rule, enforced per tenant like every other natural key in this
-- tier: two supplies sharing one invoice number is the error the numbering format exists to expose.
CREATE UNIQUE INDEX ux_cloud_tax_invoices_tenant_number
    ON tax_invoices (tenant_id, invoice_number);

CREATE INDEX ix_cloud_tax_invoices_tenant_issued ON tax_invoices (tenant_id, issued_at);
CREATE INDEX ix_cloud_tax_invoices_sale ON tax_invoices (tenant_id, sale_invoice_number);

COMMENT ON TABLE tax_invoices IS
    'IRD tax invoices issued by a till. Ledger only — the printable copy stays on the terminal that issued it (M5-09).';
