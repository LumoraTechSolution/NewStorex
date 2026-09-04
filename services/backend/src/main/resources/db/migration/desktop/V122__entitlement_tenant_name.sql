-- V122 — the shop the cloud thinks it is talking to.
--
-- ## Written because of a misfiled sale
--
-- A till was activated for one shop while a stale machine-level `LUMORA_CLOUD_TOKEN` from another
-- shop was still set on the PC. The environment won, the till presented the old key, and an
-- afternoon's sales were ingested under the wrong tenant.
--
-- Nothing malfunctioned. The token was valid, `TenantAuthFilter` derived the tenant from it and
-- never from the request body — exactly as M4-01 requires — and each sale was recorded precisely
-- where the credential said it belonged. **That is why nothing could report it.** The till knew
-- its own name from `tenants.name`; the cloud knew the token's; and until now the two facts were
-- never in the same place at the same time, so no code could compare them and no screen could
-- show them side by side.
--
-- ## One column, and what it is not
--
-- `entitlements` already caches what the cloud last said about this shop's licence. This adds the
-- one field that identifies *whose* licence it is. The till can then print the name it believes
-- it has next to the name the cloud used, and a mismatch becomes two different words on a screen
-- instead of a discrepancy somebody finds in a report next month.
--
-- It is **not** a check the till enforces. Refusing to sell because two strings differ would put
-- the network back on the critical path of a sale, which §A forbids in the strongest terms — and
-- would do it for a condition whose commonest cause is a shop that renamed itself in the console.
-- The till shows the difference and keeps trading. A person decides what it means.
--
-- Nullable, and it will legitimately be null: on a till that has not synced since installing, and
-- on one talking to a cloud built before this field existed. `LicenceNotice` and the sync strip
-- both already treat an absent entitlement as "not known yet" rather than as an error, which is
-- the same handling this needs.

ALTER TABLE entitlements ADD COLUMN tenant_name text;

COMMENT ON COLUMN entitlements.tenant_name IS
    'The shop name the cloud returned for the token this till presents. Shown beside the till''s own tenants.name so a credential pointing at the wrong shop is visible before a day of sales lands there. Never enforced — a mismatch is displayed, not blocked.';
