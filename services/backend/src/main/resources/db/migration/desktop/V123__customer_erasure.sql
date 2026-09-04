-- V123 — the right to be forgotten, and the parts of a shop that cannot forget (M5-10).
--
-- ## What PDPA No. 9 of 2022 actually asks for
--
-- Two rights that touch this system: a person may ask for a copy of what is held about them, and
-- may ask for it to be erased. The first is a read and needs no schema. This column is the second.
--
-- ## Erasure here is anonymisation, and that is the correct answer rather than a compromise
--
-- A shop is required to keep its financial records. A sale that once named somebody and now names
-- nobody is still a sale that must reconcile, must appear in a Z-report, and must survive an audit
-- — and an issued tax invoice is a statutory document the *purchaser* filed and claimed input
-- credit against, which the shop does not get to unilaterally revoke. Deleting the customer row
-- would either fail against the foreign key from `sales` or, if somebody loosened it, silently
-- rewrite the shop's trading history to make a person go away.
--
-- So the personal data is destroyed and the record of the transaction is not. `CustomerService`
-- overwrites name, phone, email, note, TIN and address; the row keeps its id and its client_uuid,
-- so every sale still points at the same anonymous party it always pointed at, and the totals a
-- shopkeeper reconciles do not move.
--
-- ## Why this is a column and not a deleted row
--
-- Somebody has to be able to answer "did you action my request", and "the row is gone" cannot
-- answer it. `erased_at` is the evidence that it was done and `erased_by` is who did it — the same
-- reasoning as the stocktake's ABANDONED state and the shift's closed_by: the fact that a person
-- acted is a new fact, not the absence of an old one.
--
-- ## What this deliberately does not touch
--
-- `tax_invoices` snapshots the purchaser's name, TIN and address at issue, because a tax invoice
-- must show what it showed when it was printed (Gazette 2481/22 §4). Those stay. There will be very
-- few — a tax invoice is issued on request to a VAT-registered purchaser, not to a shopper buying
-- a loaf — and `docs/pdpa.md` says so to whoever has to answer for it.

ALTER TABLE customers
    ADD COLUMN erased_at timestamptz,
    ADD COLUMN erased_by bigint REFERENCES users (id);

-- Both or neither. An erasure with no author is an audit trail that cannot answer the only
-- question anybody asks of it.
ALTER TABLE customers
    ADD CONSTRAINT ck_customers_erasure_has_an_author CHECK (
        (erased_at IS NULL AND erased_by IS NULL)
        OR (erased_at IS NOT NULL AND erased_by IS NOT NULL));

COMMENT ON COLUMN customers.erased_at IS
    'Set when the person asked to be forgotten (M5-10, PDPA No. 9 of 2022). The row survives because sales reference it; every personal field on it does not. Never cleared - an erasure cannot be undone, and a customer who comes back is a new customer.';

COMMENT ON COLUMN customers.erased_by IS
    'The user who actioned the request. Somebody has to be able to say who, and when, if it is ever questioned.';
