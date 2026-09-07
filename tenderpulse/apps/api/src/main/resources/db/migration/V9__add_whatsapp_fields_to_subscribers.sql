-- TP-093: adds WhatsApp opt-in fields to the existing (potentially populated) subscribers table.
--
-- whatsapp_number is nullable (no default needed -- absent for every subscriber until they
-- explicitly submit one).
--
-- whatsapp_opt_in is NOT NULL with an in-statement DEFAULT false, per CONTRIBUTING.md's Flyway
-- guidance: a NOT NULL column added to an already-populated table needs either a DEFAULT in the
-- same statement or an explicit backfill. `false` is an unambiguous, universal default here (no
-- placeholder-value ambiguity like TP-058's `InterestProfile.name` column had) -- every existing
-- subscriber row genuinely has never opted in to WhatsApp delivery, so `false` is not just a
-- filler value, it is the correct historical value. Empirically verified against a real,
-- populated Postgres container (see PR evidence for TP-093/#104): existing rows survive with
-- whatsapp_opt_in = false and no DDL failure.
-- Two separate ALTER TABLE statements rather than one comma-separated multi-ADD-COLUMN statement:
-- H2 (used by the test suite in PostgreSQL-compatibility mode -- see
-- src/test/resources/application.yml) does not accept Postgres's comma-separated
-- "ADD COLUMN a, ADD COLUMN b" form in a single ALTER TABLE, even though real Postgres does.
ALTER TABLE subscribers ADD COLUMN whatsapp_number varchar(255);
ALTER TABLE subscribers ADD COLUMN whatsapp_opt_in boolean NOT NULL DEFAULT false;
