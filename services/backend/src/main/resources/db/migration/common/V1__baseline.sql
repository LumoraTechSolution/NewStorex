-- V1 — baseline conventions.
--
-- Deliberately contains no tables. Its job is to establish the things every later
-- migration leans on, and to prove the migration pipeline runs on both tiers before
-- any real schema depends on it.

-- Keeps updated_at honest without every writer having to remember it.
-- Attach with:
--   CREATE TRIGGER trg_<table>_updated_at BEFORE UPDATE ON <table>
--     FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION set_updated_at() IS
    'Trigger function: stamps updated_at on UPDATE. Attach per table.';

-- Timestamps are always timestamptz. A shop in Colombo, a server in another zone and
-- a Z-report that must close the right day give us no room for naive timestamps.
-- This is a convention, not something the database can enforce; it is recorded here
-- so the decision is discoverable from the schema itself.
