-- V101 — locally issued invoice numbers.
--
-- A global sequence would need the network, so it cannot be used: the till must be able
-- to issue a legal invoice number with the cable unplugged. Each (branch, terminal) pair
-- counts on its own, which is what makes the numbers collision-free across terminals
-- without any of them talking to each other.
--
-- This is the simple form: one counter per terminal, incremented in the sale's own
-- transaction. M1-12 replaces it with reserved *blocks* so a terminal can be handed a
-- range up front — same number format, same offline property, fewer assumptions about
-- who allocates.

CREATE TABLE invoice_counters (
    id            bigserial   PRIMARY KEY,
    tenant_id     bigint      NOT NULL REFERENCES tenants (id),
    branch_id     bigint      NOT NULL REFERENCES branches (id),
    terminal_code text        NOT NULL,
    -- The next number to hand out. Allocation is a single atomic UPDATE ... RETURNING,
    -- so two concurrent sales can never receive the same one.
    next_seq      bigint      NOT NULL DEFAULT 1 CHECK (next_seq > 0),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_invoice_counters ON invoice_counters (tenant_id, branch_id, terminal_code);

CREATE TRIGGER trg_invoice_counters_updated_at BEFORE UPDATE ON invoice_counters
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE invoice_counters IS
    'Per-terminal invoice sequence, issued locally with no network. Format: BRANCH-TERMINAL-NNNNNN, e.g. KND-T2-001047.';
