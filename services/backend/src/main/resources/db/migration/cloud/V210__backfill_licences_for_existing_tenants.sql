-- V210 — the tenants V209 forgot (M4-08).
--
-- Cloud tier. V209 made a live licence a *condition of authentication*: `TenantCredentialService`
-- now requires a `tenant_licences` row covering now() before a till's token opens the door. It
-- granted one to every tenant created after it, and none to any tenant that already existed.
--
-- So on the deploy that applies V209, every shop already on the system stops syncing. Not with an
-- error anybody would read as a licensing problem — the till gets a 401, which is the same answer
-- it gets for a revoked key, a suspended tenant and a typo, deliberately (V205). The outbox would
-- queue, the shop would keep selling, and the first symptom would be an owner noticing the console
-- had gone quiet. That is precisely the failure the one-answer-for-every-reason rule makes hard to
-- diagnose.
--
-- Found by running the estate screen against the development cloud, where two tenants from the
-- M4-05 work showed as "Licence lapsed" — correct according to the schema and wrong about the
-- world. There are no paying tenants yet, so nothing was actually lost; the point is that the
-- migration establishing a rule has to bring the existing rows into it, not just the new ones.
--
-- ## Why a separate migration and not an edit to V209
--
-- V209 is applied on this machine's cloud and test databases, and Flyway checksums applied
-- migrations. Editing it fails validation on every database that has it. Fixing forward is the
-- project's standing rule and the right one here anyway: this way the repair is a dated, readable
-- row in the history rather than a silent change to a file somebody already ran.
--
-- ## Why the standard plan and not trial
--
-- A tenant that predates licensing has been running as if it had one. Dropping it onto a trial
-- would be a downgrade nobody agreed to, and a trial expires — this would then re-break in thirty
-- days, which is the same bug with a delay. The grant runs for ten years and says in its note that
-- it was a backfill, so it is obvious in the licence history what happened and why.

INSERT INTO tenant_licences (tenant_id, plan_id, starts_at, expires_at, note, granted_by)
SELECT t.id,
       p.id,
       -- Backdated to the tenant's own creation, so the licence history reads as an unbroken
       -- period rather than implying the shop was unlicensed until the day of this deploy.
       t.created_at,
       now() + interval '10 years',
       'Backfilled by V210 — predates the licensing introduced in V209.',
       NULL
  FROM tenants t
  CROSS JOIN plans p
 WHERE p.code = 'standard'
   -- Only tenants with no licence at all. A tenant created between V209 and this migration
   -- already has one, and a second row would be a silent upgrade to a longer term.
   AND NOT EXISTS (SELECT 1 FROM tenant_licences l WHERE l.tenant_id = t.id);
