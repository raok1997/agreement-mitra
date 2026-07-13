-- V7 — rich agreement capture (rich-agreement-capture CR / CR-1).
--
-- ADDITIVE: adds structured party fields (first/last/father name + current address + mobile)
-- to `signer`, and tenancy start/end dates to `agreement`. Nothing is dropped:
--  - `signer.name` is KEPT (the full name as per Aadhaar, server-derived + user-editable).
--  - `agreement.term_months` is KEPT (now server-derived from the dates).
-- Forward-only: never edits V1..V6. Column types match the JPA mapping so `ddl-auto: validate`
-- passes (LocalDate -> date, String -> text).

-- signer: structured name parts + current address + optional mobile.
ALTER TABLE signer ADD COLUMN first_name      TEXT;
ALTER TABLE signer ADD COLUMN last_name       TEXT;
ALTER TABLE signer ADD COLUMN father_name     TEXT;
ALTER TABLE signer ADD COLUMN current_address TEXT;
ALTER TABLE signer ADD COLUMN mobile          TEXT;

-- Backfill the new NOT NULL columns for any pre-existing rows (best-effort; sandbox dummy data).
UPDATE signer SET first_name      = name WHERE first_name IS NULL;
UPDATE signer SET last_name       = ''   WHERE last_name IS NULL;
UPDATE signer SET father_name     = ''   WHERE father_name IS NULL;
UPDATE signer SET current_address = ''   WHERE current_address IS NULL;

ALTER TABLE signer ALTER COLUMN first_name      SET NOT NULL;
ALTER TABLE signer ALTER COLUMN last_name       SET NOT NULL;
ALTER TABLE signer ALTER COLUMN father_name     SET NOT NULL;
ALTER TABLE signer ALTER COLUMN current_address SET NOT NULL;

-- Contact (email or mobile) is required only before signing, so email becomes optional here.
ALTER TABLE signer ALTER COLUMN email DROP NOT NULL;

-- agreement: tenancy start/end dates are the source of truth; term_months is kept (derived).
ALTER TABLE agreement ADD COLUMN start_date DATE;
ALTER TABLE agreement ADD COLUMN end_date   DATE;

UPDATE agreement SET start_date = created_at::date WHERE start_date IS NULL;
UPDATE agreement
   SET end_date = (created_at + (term_months * INTERVAL '1 month'))::date
 WHERE end_date IS NULL;

ALTER TABLE agreement ALTER COLUMN start_date SET NOT NULL;
ALTER TABLE agreement ALTER COLUMN end_date   SET NOT NULL;
