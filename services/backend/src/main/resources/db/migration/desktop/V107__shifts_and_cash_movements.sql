-- V107 — the cash accountability layer (M2-01 … M2-05).
--
-- A sale tells you what was owed. Only a shift tells you whether it arrived. Everything in
-- this file exists to answer one question at the end of a day: the drawer holds X, it should
-- hold Y, and here is every entry that produced Y.
--
-- ## Expected cash is a sum, not a column
--
-- §A says balances are never stored. Expected cash obeys that while a shift is open: it is
-- always
--
--     opening_float + Σ cash tenders + Σ cash rounding + Σ cash_movements − Σ cash refunds
--
-- computed on read, with nothing anywhere holding a running level anyone updates.
--
-- `shifts.expected_cash_minor` is the one apparent exception and is not one. It is written
-- exactly once, at close, and never updated — the same kind of thing as `sales.tax_rate_bp`:
-- a stamp of what a figure *was* when a document was issued. The Z-report is that document.
-- Without the freeze, a refund raised next week against a sale from this shift would silently
-- change the number on a Z-report already printed, signed and filed, and the shop would hold
-- two papers disagreeing about the same drawer. Recomputing is right up to the moment the
-- shift closes and wrong forever after.
--
-- ## Movements carry their own sign
--
-- cash_movements.amount_minor is signed and constrained to agree with its kind, so a pay-out
-- cannot be recorded as cash arriving. Expected cash is then a plain SUM over the column with
-- no CASE, which is the whole reason for signing it rather than storing a magnitude plus a
-- direction every reader has to interpret the same way.

-- ---------------------------------------------------------------------------
-- tenant_settings — one row per tenant; on a till, one row full stop.
-- ---------------------------------------------------------------------------
-- D1 resolved: the variance threshold is per-tenant, not a constant. A jeweller counting
-- LKR 400,000 of takings and a grocer counting LKR 12,000 do not mean the same thing by
-- "the drawer is out", and a hardcoded LKR 100 would make the gate either theatre for one
-- of them or an obstruction for the other.
CREATE TABLE tenant_settings (
    id                            bigserial   PRIMARY KEY,
    tenant_id                     bigint      NOT NULL REFERENCES tenants (id),

    -- Above this, closing a shift requires a reason code (M2-04). Compared against the
    -- absolute variance: a drawer LKR 500 over is as much a problem as one LKR 500 short —
    -- over usually means a sale that was never rung up.
    cash_variance_threshold_minor bigint      NOT NULL DEFAULT 10000
        CHECK (cash_variance_threshold_minor >= 0),

    -- M2-07. A single shop-wide manager PIN, BCrypt-hashed, until M3-08 brings real users
    -- with their own PINs and roles. Deliberately NULL by default: a NULL means "no manager
    -- PIN has been set", and RefundService refuses every refund in that state rather than
    -- treating an unconfigured gate as an open one.
    manager_pin_hash              text,

    created_at                    timestamptz NOT NULL DEFAULT now(),
    updated_at                    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_tenant_settings_tenant ON tenant_settings (tenant_id);

CREATE TRIGGER trg_tenant_settings_updated_at BEFORE UPDATE ON tenant_settings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN tenant_settings.cash_variance_threshold_minor IS
    'Absolute variance above which a reason code is required to close a shift. LKR 100.00 by default (D1).';
COMMENT ON COLUMN tenant_settings.manager_pin_hash IS
    'BCrypt. NULL means no manager PIN is set, and a NULL refuses refunds — an unconfigured gate is a closed gate, never an open one.';

-- ---------------------------------------------------------------------------
-- shifts
-- ---------------------------------------------------------------------------
CREATE TABLE shifts (
    id                  bigserial   PRIMARY KEY,
    client_uuid         uuid        NOT NULL,
    tenant_id           bigint      NOT NULL REFERENCES tenants (id),
    branch_id           bigint      NOT NULL REFERENCES branches (id),
    terminal_code       text        NOT NULL,

    status              text        NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),

    opened_at           timestamptz NOT NULL DEFAULT now(),
    -- Real FK with M3-08, like stock_movements.created_by. The seeded operator until then.
    opened_by           bigint      NOT NULL,
    opening_float_minor bigint      NOT NULL CHECK (opening_float_minor >= 0),

    closed_at           timestamptz,
    closed_by           bigint,

    -- All three are written once, at close, and never updated. See the header: this is a
    -- stamp of what the Z-report said, not a running balance.
    counted_cash_minor  bigint,
    expected_cash_minor bigint,
    variance_minor      bigint,

    -- M2-04. Required above the tenant's threshold, enforced in ShiftService rather than
    -- here: the threshold lives in another table and a CHECK cannot read one.
    variance_reason     text CHECK (variance_reason IN (
                            'MISCOUNT', 'FLOAT_ERROR', 'UNRECORDED_PAYOUT',
                            'CHANGE_GIVEN_WRONG', 'THEFT_SUSPECTED', 'OTHER')),
    variance_note       text,

    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),

    -- A closed shift carries its whole reconciliation or none of it. Half-closed is not a
    -- state the Z-report can render, so it is not a state the table can hold.
    CONSTRAINT ck_shifts_closed_is_complete CHECK (
        (status = 'OPEN'
            AND closed_at IS NULL AND closed_by IS NULL
            AND counted_cash_minor IS NULL AND expected_cash_minor IS NULL
            AND variance_minor IS NULL)
        OR
        (status = 'CLOSED'
            AND closed_at IS NOT NULL AND closed_by IS NOT NULL
            AND counted_cash_minor IS NOT NULL AND expected_cash_minor IS NOT NULL
            AND variance_minor IS NOT NULL)
    ),
    -- Stated rather than assumed. The service computes it; this makes a service that ever
    -- stops computing it fail loudly instead of filing a Z-report that does not subtract.
    CONSTRAINT ck_shifts_variance_is_the_difference CHECK (
        variance_minor IS NULL
        OR variance_minor = counted_cash_minor - expected_cash_minor
    )
);
CREATE UNIQUE INDEX ux_shifts_client_uuid ON shifts (client_uuid);

-- The invariant the whole lifecycle rests on: a terminal has at most one shift open. Partial,
-- so the closed ones — of which there will eventually be thousands — do not participate.
CREATE UNIQUE INDEX ux_shifts_one_open_per_terminal
    ON shifts (tenant_id, branch_id, terminal_code)
    WHERE status = 'OPEN';

CREATE INDEX ix_shifts_opened_at ON shifts (tenant_id, opened_at);

CREATE TRIGGER trg_shifts_updated_at BEFORE UPDATE ON shifts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE shifts IS
    'One cashier session at one terminal. At most one OPEN per terminal, enforced by a partial unique index rather than by application care.';
COMMENT ON COLUMN shifts.expected_cash_minor IS
    'Frozen at close. While the shift is open this figure is always recomputed from the entries; once a Z-report exists it must never move again.';

-- ---------------------------------------------------------------------------
-- shift_counts — the blind denomination count (M2-02)
-- ---------------------------------------------------------------------------
-- Blindness is not a property this table can hold; it is a property of what the counter is
-- shown. It is enforced where it can be: the shift status endpoint never returns expected
-- cash while the shift is open, so the screen taking the count has nothing to leak. These
-- rows are the evidence that a count happened and what it was made of — an owner reading a
-- variance wants to know whether it was one missing 5000 note or forty missing coins.
CREATE TABLE shift_counts (
    id                 bigserial PRIMARY KEY,
    shift_id           bigint    NOT NULL REFERENCES shifts (id) ON DELETE CASCADE,
    phase              text      NOT NULL CHECK (phase IN ('OPEN', 'CLOSE')),
    -- The face value in minor units: 500000 is the LKR 5000 note, 100 the LKR 1 coin.
    denomination_minor bigint    NOT NULL CHECK (denomination_minor > 0),
    qty                integer   NOT NULL CHECK (qty >= 0)
);
CREATE UNIQUE INDEX ux_shift_counts ON shift_counts (shift_id, phase, denomination_minor);

COMMENT ON TABLE shift_counts IS
    'What the drawer physically held, note by note. Σ (denomination_minor × qty) is the counted total; it is never stored separately at this grain.';

-- ---------------------------------------------------------------------------
-- cash_movements — pay-in, pay-out, drop (M2-05)
-- ---------------------------------------------------------------------------
CREATE TABLE cash_movements (
    id            bigserial   PRIMARY KEY,
    client_uuid   uuid        NOT NULL,
    tenant_id     bigint      NOT NULL REFERENCES tenants (id),
    branch_id     bigint      NOT NULL REFERENCES branches (id),
    -- Never null: cash that moved outside a shift is cash nothing reconciles, which is the
    -- hole this milestone exists to close.
    shift_id      bigint      NOT NULL REFERENCES shifts (id),

    kind          text        NOT NULL CHECK (kind IN ('PAY_IN', 'PAY_OUT', 'DROP')),

    -- Signed, and the sign is constrained to agree with the kind. Expected cash is then a
    -- plain SUM with no CASE, and a pay-out that adds to the drawer is unrepresentable.
    amount_minor  bigint      NOT NULL CHECK (amount_minor <> 0),

    reason_code   text        NOT NULL CHECK (reason_code IN (
                      'BANK_DROP', 'SAFE_DROP', 'SUPPLIER_PAYMENT', 'PETTY_CASH',
                      'CHANGE_FLOAT', 'OWNER_DRAW', 'OTHER')),
    note          text,

    created_by    bigint      NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_cash_movements_sign_matches_kind CHECK (
        (kind = 'PAY_IN' AND amount_minor > 0)
        OR (kind IN ('PAY_OUT', 'DROP') AND amount_minor < 0)
    )
);
CREATE UNIQUE INDEX ux_cash_movements_client_uuid ON cash_movements (client_uuid);
CREATE INDEX ix_cash_movements_shift ON cash_movements (shift_id);

COMMENT ON COLUMN cash_movements.amount_minor IS
    'Signed: positive for PAY_IN, negative for PAY_OUT and DROP. A CHECK ties the sign to the kind so the two can never disagree.';

-- ---------------------------------------------------------------------------
-- sales gain their shift
-- ---------------------------------------------------------------------------
-- Nullable, and it will stay nullable: every sale rung up before this migration genuinely
-- had no shift, and backfilling one by guessing from timestamps would invent a fact. New
-- sales always carry one — SaleService refuses to commit without an open shift, which is
-- where that rule belongs, since "the till may not sell unreconciled" is policy and not a
-- shape. See §G.
ALTER TABLE sales ADD COLUMN shift_id bigint REFERENCES shifts (id);
CREATE INDEX ix_sales_shift ON sales (shift_id);

COMMENT ON COLUMN sales.shift_id IS
    'The shift open at this terminal when the sale was rung up. NULL only for sales that predate M2-01; every new sale has one.';
