-- V121 — which till this is, and the shop's own address (M5-03).
--
-- ## The gap this closes
--
-- After V120 a freshly installed till has a complete schema and no shop. It has a tenants table
-- with nothing in it, so `LocalShop.soleTenantId()` throws, so nothing works — the installer
-- produces an application nobody can sign in to. `dev-seed.sql` fills that gap for a developer
-- and deliberately is not a migration, because a migration runs on every till and a real shop's
-- identity is not something this project gets to invent.
--
-- The first-run wizard fills it instead. This migration adds the one thing the wizard needs
-- somewhere to put that nothing else already holds.
--
-- ## What was already here, and is deliberately not duplicated
--
-- Most of what a shop is already has a home:
--
--   * its name                    → `tenants.name`
--   * its branch code and name    → `branches.code`, `branches.name`
--   * its VAT identity            → `tenant_settings.supplier_tin` and friends (V118)
--   * its invoice blocks          → `invoice_counters` (V101, V108)
--   * its people                  → `users` (V109)
--
-- So this file adds two things and no more. Adding a `shop_profile` table that restated the
-- above would create a second answer to "what is this shop called", and the two would drift the
-- first time somebody renamed a branch.
--
-- ## 1. `terminal_code` — the answer to "which till am I?"
--
-- This is the genuinely new fact. Everything else above describes the *shop*; this describes
-- the *machine*, and until now the machine did not know. `app/page.tsx` carried it as a
-- hardcoded `'T1'` next to a comment naming this task.
--
-- It matters more than it looks. `invoice_counters` is keyed on
-- `(tenant_id, branch_id, terminal_code, doc_type)`, so the terminal code is what keeps two
-- tills in one shop from issuing the same invoice number. Two machines that both believe they
-- are `T1` each count from 1 in their own local database, never collide locally because they
-- never speak to each other, and collide in the cloud against
-- `ux_sales_tenant_invoice (tenant_id, invoice_number)` — where the second one's sales stop
-- ingesting silently while the till goes on selling perfectly. Two paper receipts then carry
-- the same invoice number, which for an IRD document is a compliance problem and not merely a
-- data one.
--
-- So it is `NOT NULL` with no default. A default of `'T1'` would be exactly the value that is
-- correct on the first till and silently wrong on the second, which is the failure above with
-- a friendlier face.
--
-- ## 2. `shop_address` — the last hardcoded string on the receipt
--
-- `receipt.ts` prints an address and `app/page.tsx` supplied a placeholder for it. Note this is
-- *not* `tenant_settings.supplier_address` from V118: that column is the address as the VAT
-- registration certificate records it, appears on a tax invoice, and is a legal statement. This
-- one is what a customer reads at the top of a till receipt. They are usually the same string
-- and they are not the same fact — a shop can trade from a different address than the one it is
-- registered at, and a receipt that quietly printed the registered address instead would be
-- wrong in a way nobody would notice for months.
--
-- Nullable, because a shop with no address on its receipt is untidy and not broken, whereas a
-- till that refuses to open because nobody typed an address is broken.
--
-- ## Why the wizard is gated on an empty `tenants` table, not on a flag here
--
-- There is deliberately no `setup_completed boolean`. A flag is a second source of truth about
-- something the data already answers: the shop exists, or it does not. A flag can also be set
-- while the rows it claims to describe are missing, and then the wizard is unreachable on a
-- till that needs it. `PlatformBootstrap` settled the same question the same way for the
-- cloud's first admin — the guard is the state of the table, not the presence of a setting.

-- ---------------------------------------------------------------------------
--
-- ## Why this has a DEFAULT, having just argued that a default is the dangerous thing
--
-- The paragraph above says a default of 'T1' is the value that is correct on the first till and
-- silently wrong on the second. That is true of the *wizard*, which must make somebody choose —
-- and it does: the field is on the form, and `ShopSetupService` writes it explicitly on every
-- shop it creates.
--
-- The column default is for a different caller. `tenant_settings` is not owned by setup alone:
-- `TenantSettingsService` and the shift path both upsert into it with only the column they care
-- about, creating the row lazily if it is missing. A NOT NULL with no default turns every one of
-- those into a constraint violation on a shop whose settings row does not exist yet — which is a
-- till that has been provisioned by an older build, or by a test.
--
-- So: NOT NULL, because a till without a terminal code cannot issue an invoice number and should
-- not be able to pretend otherwise; DEFAULT 'T1', because the alternative is that an unrelated
-- write to an unrelated column fails. 'T1' is right for the single-till shop v1 is scoped to, and
-- the wizard overrides it for anybody else — which is where the decision belongs.
ALTER TABLE tenant_settings ADD COLUMN terminal_code text NOT NULL DEFAULT 'T1';

-- The existing row, if there is one, has just taken that default. That is correct rather than
-- lucky for the only database this can apply to: a developer's, which has been running against a
-- hardcoded 'T1' since M1-12 and whose `invoice_counters` rows are already keyed on that exact
-- string. Any other value here would orphan a counter and restart the sequence at 1 on a database
-- that has already issued numbers.
--
-- A real shop has no rows at all at this point, so nothing is backfilled there.

-- Same shape as `users.code` in V109: upper case, short, and never blank. The constraint is
-- here rather than only in Java because this string ends up inside an invoice number, and a
-- lower-case or padded value would produce a document number that differs from the one the
-- same till printed yesterday.
ALTER TABLE tenant_settings ADD CONSTRAINT ck_tenant_settings_terminal_code
    CHECK (terminal_code = upper(terminal_code)
           AND length(terminal_code) BETWEEN 1 AND 8
           AND terminal_code !~ '\s');

ALTER TABLE tenant_settings ADD COLUMN shop_address text;

COMMENT ON COLUMN tenant_settings.terminal_code IS
    'Which till this machine is — the T2 in KND-T2-001047. Set once by the first-run wizard (M5-03). Two tills in one shop must never share it: invoice_counters is keyed on it, so a shared code produces duplicate invoice numbers that fail to ingest in the cloud while the till goes on selling.';
COMMENT ON COLUMN tenant_settings.shop_address IS
    'The address printed at the top of a till receipt. Deliberately not supplier_address (V118), which is the registered address on a tax invoice — a shop may trade from one and be registered at another.';
