-- V202 — the cloud side of per-line tax rates (M1-18). Mirrors desktop's V106.
--
-- The cloud's sale_items carries no CHECK constraints, the same as the rest of this tier:
-- ingest validates on the way in and a rejected batch must never wedge the queue behind a
-- constraint violation the till cannot fix. The columns are still NOT NULL, because a line
-- with no rate is a line nobody can report on.
--
-- Backfill note: an old till still sends line payloads with no tax on them (see
-- SyncIngestService — the per-line stamp falls back to the sale's). So this is not merely a
-- migration of history, it is the standing behaviour for any terminal not yet upgraded.

ALTER TABLE sale_items
    ADD COLUMN tax_mode    text,
    ADD COLUMN tax_rate_bp integer;

UPDATE sale_items i
   SET tax_mode    = s.tax_mode,
       tax_rate_bp = s.tax_rate_bp
  FROM sales s
 WHERE s.id = i.sale_id;

ALTER TABLE sale_items
    ALTER COLUMN tax_mode    SET NOT NULL,
    ALTER COLUMN tax_rate_bp SET NOT NULL;

-- The per-rate summary a tax invoice needs, and the reporting rollup M4 will want.
CREATE INDEX ix_cloud_sale_items_tax_rate ON sale_items (tax_rate_bp);
