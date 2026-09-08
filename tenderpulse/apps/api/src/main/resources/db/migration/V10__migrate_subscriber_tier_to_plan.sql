-- TP-121 (issue #121): migrates the two-value SubscriptionTier (FREE/PAID) column to the spec's
-- three-plan SubscriptionPlan model (FREE/PRO/MAX) -- see
-- tenderpulse/docs/specs/subscription-lifecycle-paypal.md §6.1/§7.1.
--
-- This is a genuine DATA migration, not just a schema rename: existing PAID rows must become PRO,
-- never NULL or an invalid/lost value. Verified empirically against a real, populated Postgres 16
-- container (docker-compose.yml): inserted one FREE row and one PAID row on the pre-migration
-- schema, ran this exact migration, and confirmed via direct SQL query that the FREE row stayed
-- FREE and the PAID row became PRO -- see PR evidence.
--
-- Step ordering matters and was itself verified empirically (an earlier draft got this wrong):
-- renaming a column does NOT rewrite an existing CHECK constraint's allowed literal values, only
-- the column name inside its expression -- so the old subscribers_tier_check constraint (still
-- checking `plan IN ('FREE', 'PAID')` at that point) would reject the very UPDATE that converts
-- PAID to PRO unless it is dropped first. Confirmed both in H2 (PostgreSQL-compatibility mode,
-- used by the test suite) and in real Postgres: doing the UPDATE before dropping the old
-- constraint fails with a check-constraint violation; dropping the old constraint first, then
-- updating, then adding the new constraint succeeds cleanly in both.

-- 1. Rename the column. Existing values (FREE/PAID) travel with it unchanged at this point.
ALTER TABLE subscribers RENAME COLUMN tier TO plan;

-- 2. Drop the old FREE/PAID check constraint *before* touching the data (see ordering note above).
ALTER TABLE subscribers DROP CONSTRAINT subscribers_tier_check;

-- 3. Convert existing PAID rows to PRO. FREE rows are untouched.
UPDATE subscribers SET plan = 'PRO' WHERE plan = 'PAID';

-- 4. Add the new FREE/PRO/MAX check constraint now that every row already satisfies it.
ALTER TABLE subscribers ADD CONSTRAINT subscribers_plan_check CHECK (plan IN ('FREE', 'PRO', 'MAX'));

-- 5. New subscriber columns (spec §7.1). None of these are wired into any behavior yet beyond
-- what already exists -- alert eligibility still uses Subscriber.active / Subscriber.emailOptOut
-- (see com.tenderpulse.domain.Models.kt's AlertStatus/Subscriber kdoc) -- they exist so later
-- phases in this milestone have the schema to build on.
--
-- Separate ALTER TABLE statements per column (not one comma-separated multi-ADD-COLUMN
-- statement): H2 (PostgreSQL-compatibility mode, used by the test suite -- see
-- src/test/resources/application.yml) does not accept Postgres's "ADD COLUMN a, ADD COLUMN b"
-- form in a single ALTER TABLE, even though real Postgres does (same note as V9).
ALTER TABLE subscribers ADD COLUMN alert_status varchar(255) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE subscribers ADD CONSTRAINT subscribers_alert_status_check CHECK (alert_status IN ('ACTIVE', 'PAUSED', 'UNSUBSCRIBED'));
ALTER TABLE subscribers ADD COLUMN email_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE subscribers ADD COLUMN email_consent_at timestamp(6) with time zone;
ALTER TABLE subscribers ADD COLUMN alerts_paused_until timestamp(6) with time zone;
ALTER TABLE subscribers ADD COLUMN updated_at timestamp(6) with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP;
