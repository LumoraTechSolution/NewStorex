-- V201 — the cloud side of how a sale was tendered (M1-11).
--
-- Mirrors desktop's V104, with the same shape difference the rest of the cloud schema
-- has: no client_uuid on sale_payments (it is not an aggregate root, it arrives inside
-- the sale's payload), and a reference to the sale by its cloud-side bigint id rather
-- than a synced uuid, exactly like sale_items.

ALTER TABLE sales
    ADD COLUMN rounding_adjustment_minor bigint NOT NULL DEFAULT 0,
    ADD COLUMN change_minor              bigint NOT NULL DEFAULT 0 CHECK (change_minor >= 0);

CREATE TABLE sale_payments (
    id           bigserial PRIMARY KEY,
    sale_id      bigint    NOT NULL REFERENCES sales (id) ON DELETE CASCADE,
    line_no      integer   NOT NULL,
    kind         text      NOT NULL,
    amount_minor bigint    NOT NULL
);
CREATE UNIQUE INDEX ux_cloud_sale_payments_sale_line ON sale_payments (sale_id, line_no);
