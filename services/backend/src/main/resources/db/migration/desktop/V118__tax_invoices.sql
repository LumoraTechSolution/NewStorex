-- V118 — the IRD tax invoice (M5-09).
--
-- Gazette Extraordinary **2481/22** of 27 March 2026, in force since 1 July 2026, and IRD
-- Circular SEC/2026/E/03 of 20 May 2026. Read from the gazette text itself, not from reporting
-- about it — the roadmap's §D field list was secondary sources and got two things wrong.
--
-- ## Why this is a document and not a flag on `sales`
--
-- A tax invoice is issued **on request**, not on every sale. The Act requires one when the
-- purchaser is a registered person who asks; a shopper buying a loaf does not get one and does not
-- want one. Two consequences decide the shape of this table:
--
--   * It is issued at a different moment from the sale, so it has its own date. Circular §4.5
--     requires both the Invoice Date (when the invoice is issued) and the Date of Supply (when the
--     goods changed hands). On a walk-in sale they are the same instant; on an invoice raised the
--     following week against last Tuesday's delivery they are not, and the pair is what decides
--     which VAT period the supply falls in.
--   * It has its own serial sequence in a format the shop's ordinary receipt numbers do not
--     satisfy, and issuing one must not consume a receipt number. Same reasoning as V108's credit
--     notes: an auditor reading a gap in a sequence must be able to tell what is missing.
--
-- ## Why the supplier's own details are copied onto every row
--
-- They are a snapshot, exactly like `sales.tax_rate_bp`. The TIN, registered name and address come
-- from the VAT registration certificate (Gazette §2.1) and a shop that moves premises changes
-- them. A reprint of a two-year-old invoice must reproduce the invoice that was issued, not
-- today's letterhead — the printed copy is what the purchaser filed and what an auditor will hold
-- next to this row.
--
-- ## Purchaser details are nullable, and that is the gazette's own rule
--
-- Gazette §3.1 reads as though purchaser TIN, name and address are always required. Circular §4.3
-- is the one that governs: *"Where the purchaser is VAT-registered, the following must be
-- stated"*. A walk-in consumer has no TIN and none of this is collected. This is the single most
-- important thing the secondary sources had wrong, and it is the difference between a till that
-- can serve a queue and one that cannot.

-- ---------------------------------------------------------------------------
-- The supplier's identity, from the VAT registration certificate.
--
-- Nullable with no default, and TaxInvoiceService refuses to issue until they are set. An
-- unconfigured TIN must never become a printed zero: a tax invoice carrying the wrong TIN is worse
-- than no tax invoice, because the purchaser files it and claims input credit against it.
ALTER TABLE tenant_settings ADD COLUMN supplier_tin text
    CHECK (supplier_tin IS NULL OR supplier_tin ~ '^[0-9]{9}$');
ALTER TABLE tenant_settings ADD COLUMN supplier_registered_name text;
ALTER TABLE tenant_settings ADD COLUMN supplier_address text;

COMMENT ON COLUMN tenant_settings.supplier_tin IS
    'Nine-digit TIN from the VAT registration certificate (Gazette 2481/22 §2.1). NULL means no tax invoice can be issued.';
COMMENT ON COLUMN tenant_settings.supplier_registered_name IS
    'The registered business name, which is not necessarily the trading name on the receipt.';

-- ---------------------------------------------------------------------------
-- A customer may be a VAT-registered business, in which case a tax invoice needs its TIN and
-- address. Both nullable: M3-11 built customers around a phone number for a grocery, and the
-- overwhelming majority will never have either of these.
ALTER TABLE customers ADD COLUMN tin text
    CHECK (tin IS NULL OR tin ~ '^[0-9]{9}$');
ALTER TABLE customers ADD COLUMN address text;

COMMENT ON COLUMN customers.tin IS
    'Nine-digit TIN. Present only for a VAT-registered purchaser, who is the only kind that needs one (Circular SEC/2026/E/03 §4.3).';

-- ---------------------------------------------------------------------------
-- A third independent sequence per terminal. V108 explains why credit notes count separately and
-- the same argument applies here with more force: these numbers are read by the revenue authority.
ALTER TABLE invoice_counters DROP CONSTRAINT ck_invoice_counters_doc_type;
ALTER TABLE invoice_counters
    ADD CONSTRAINT ck_invoice_counters_doc_type
    CHECK (doc_type IN ('INVOICE', 'CREDIT_NOTE', 'TAX_INVOICE'));

-- ---------------------------------------------------------------------------
CREATE TABLE tax_invoices (
    id             bigserial   PRIMARY KEY,
    client_uuid    uuid        NOT NULL,
    tenant_id      bigint      NOT NULL REFERENCES tenants (id),
    branch_id      bigint      NOT NULL REFERENCES branches (id),
    branch_code    text        NOT NULL,
    terminal_code  text        NOT NULL,

    -- NOT NULL, and the only code that writes it starts from a committed sale. The same
    -- construction M2-06 used to make a refund impossible without an original receipt: there is no
    -- API here that takes an amount and prints a tax invoice for it.
    sale_id        bigint      NOT NULL REFERENCES sales (id),

    -- Gazette §4.1(a): YYMMM-QQQQ-XXXXX, no spaces, at most forty characters.
    invoice_number text        NOT NULL CHECK (
        length(invoice_number) <= 40 AND invoice_number !~ '\s'),

    -- Gazette §4.1(b) and (d). Both stored, because they are genuinely two facts: an invoice
    -- raised later against an earlier supply has to say so.
    issued_at      timestamptz NOT NULL DEFAULT now(),
    supplied_at    timestamptz NOT NULL,

    supplier_tin             text NOT NULL CHECK (supplier_tin ~ '^[0-9]{9}$'),
    supplier_registered_name text NOT NULL,
    supplier_address         text NOT NULL,

    -- All three null for a consumer, all three set for a VAT-registered purchaser. Enforced
    -- together below rather than individually: a TIN with no name is a half-filled invoice.
    purchaser_tin     text CHECK (purchaser_tin IS NULL OR purchaser_tin ~ '^[0-9]{9}$'),
    purchaser_name    text,
    purchaser_address text,

    -- The VAT-taxable subset of the sale, not the whole basket (Gazette §4.2, Circular §4.8:
    -- a tax invoice carries only supplies subject to VAT). For a mixed basket these are smaller
    -- than the sale's own totals, which is the point — and why they are stored rather than read
    -- off `sales`.
    total_excl_vat_minor bigint NOT NULL CHECK (total_excl_vat_minor >= 0),
    vat_minor            bigint NOT NULL CHECK (vat_minor >= 0),
    total_incl_vat_minor bigint NOT NULL CHECK (total_incl_vat_minor >= 0),

    created_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_tax_invoices_purchaser_all_or_nothing CHECK (
        (purchaser_tin IS NULL AND purchaser_name IS NULL AND purchaser_address IS NULL)
        OR (purchaser_tin IS NOT NULL AND purchaser_name IS NOT NULL AND purchaser_address IS NOT NULL)),

    -- Net + VAT = gross, checked by the database. The receipt and the console derive these from
    -- the same domain code, so this catches the case that code being wrong.
    CONSTRAINT ck_tax_invoices_totals_reconcile CHECK (
        total_excl_vat_minor + vat_minor = total_incl_vat_minor)
);

CREATE UNIQUE INDEX ux_tax_invoices_client_uuid ON tax_invoices (client_uuid);
CREATE UNIQUE INDEX ux_tax_invoices_tenant_number ON tax_invoices (tenant_id, invoice_number);
CREATE INDEX ix_tax_invoices_sale ON tax_invoices (sale_id);
CREATE INDEX ix_tax_invoices_tenant_issued ON tax_invoices (tenant_id, issued_at);

COMMENT ON TABLE tax_invoices IS
    'IRD tax invoices issued on request against a committed sale. Gazette 2481/22, in force 2026-07-01 (M5-09).';
COMMENT ON COLUMN tax_invoices.supplied_at IS
    'Date of Supply — when the goods changed hands. Equals the sale time; stored separately because the invoice date need not.';
