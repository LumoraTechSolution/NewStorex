-- Runs once when the db-test container initialises.
--
-- The desktop and cloud tiers are different schemas that happen to share table names:
-- `sales` on a shop PC has an invoice number and a branch FK, `sales` in the cloud has a
-- branch code and no outbox anywhere near it. Migrating both into one database would mean
-- whichever Flyway ran last silently wiped the other's tests.
--
-- Two databases, exactly as in production.
CREATE DATABASE lumora_test_cloud;
GRANT ALL PRIVILEGES ON DATABASE lumora_test_cloud TO lumora;
