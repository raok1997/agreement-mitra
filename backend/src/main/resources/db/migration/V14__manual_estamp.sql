-- V14 -- manual e-stamp upload (manual-estamp-upload CR).
--
-- Replaces the synthetic auto-stamp with a staff-driven intake of a REAL SHCIL e-stamp
-- certificate that AgreementMitra staff purchase out-of-band, print, scan, and upload.
-- Forward-only: never edits V1..V13.
--
-- DECISION (tasks 1.2): the CR-6 `stamp_serial` column is RENAMED to `stamp_certificate_number`
-- rather than kept beside a new column. The synthetic BW-series serial it held is dummy dev data
-- and the SHCIL certificate number is its direct, authoritative replacement; keeping both would
-- leave a second, misleading identifier on a document evidencing real duty. For the same reason
-- `stamp_denomination` (an INTEGER rupee denomination of a generated stamp) is replaced by
-- `stamp_duty_amount` (NUMERIC) -- a real certificate carries an exact duty amount -- and
-- `stamp_procured_at` becomes `stamp_attached_at` (nothing is procured by the system any more).
--
-- PII: the certificate scan bytes NEVER land here, only its object-storage key. The values
-- `stamp_purchased_by` and `stamp_document_description` come off the scanned certificate and name
-- the parties / describe the property, so they are treated as PII and are never logged.

ALTER TABLE agreement RENAME COLUMN stamp_serial TO stamp_certificate_number;
ALTER TABLE agreement RENAME COLUMN stamp_procured_at TO stamp_attached_at;
ALTER TABLE agreement ALTER COLUMN stamp_certificate_number TYPE VARCHAR(64);

ALTER TABLE agreement
    DROP COLUMN stamp_denomination,
    ADD COLUMN stamp_duty_amount            NUMERIC(12, 2),
    ADD COLUMN stamp_scan_key               TEXT,
    ADD COLUMN stamp_certificate_issue_date DATE,
    ADD COLUMN stamp_document_description   TEXT,
    ADD COLUMN stamp_purchased_by           TEXT;

-- Single-use ledger (design D4): one SHCIL certificate may be spent on at most one agreement.
-- Indexed on the NORMALISED value so `in-ka123 ` and `IN-KA123` collide as they must. This is the
-- race backstop -- the application never pre-checks, it catches the constraint violation and maps
-- it to 409, so two concurrent uploads of the same certificate cannot both succeed.
CREATE UNIQUE INDEX uq_agreement_stamp_certificate_number
    ON agreement (UPPER(BTRIM(stamp_certificate_number)))
    WHERE stamp_certificate_number IS NOT NULL;

-- Staff-facing agreement reference (design D3): a short, human-safe, checksummed code that staff
-- read off an order, carry to the SHCIL portal, and type back hours later. Collision-free by a
-- unique constraint -- unlike the display-only AM-<LAST6>-<DDMMYY> tracking number, which is
-- derived at render time and is explicitly NOT a lookup key.
ALTER TABLE agreement ADD COLUMN staff_reference VARCHAR(16);

-- Backfill (migration plan step 2). Existing rows are dummy/dev data, so a simple ordinal-derived
-- code is sufficient; it is emitted in the SAME format the application generates (prefix `AMS`,
-- eight body characters over the unambiguous 31-character alphabet, one trailing check character),
-- so a backfilled reference validates exactly like a freshly-minted one. Ordinal-derived means
-- backfilled values are guessable; freshly-minted ones are random. There is no production data.
DO $$
DECLARE
    alphabet CONSTANT TEXT := '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
    r        RECORD;
    body     TEXT;
    n        BIGINT;
    i        INT;
    acc      INT;
BEGIN
    FOR r IN SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn FROM agreement LOOP
        n := r.rn;
        body := '';
        FOR i IN 1..8 LOOP
            body := body || substr(alphabet, (n % 31)::int + 1, 1);
            n := n / 31;
        END LOOP;
        acc := 0;
        FOR i IN 1..8 LOOP
            acc := acc + (strpos(alphabet, substr(body, i, 1)) - 1) * (i + 1);
        END LOOP;
        UPDATE agreement
           SET staff_reference = 'AMS' || body || substr(alphabet, (acc % 31) + 1, 1)
         WHERE id = r.id;
    END LOOP;
END $$;

ALTER TABLE agreement ALTER COLUMN staff_reference SET NOT NULL;
ALTER TABLE agreement ADD CONSTRAINT uq_agreement_staff_reference UNIQUE (staff_reference);

-- Role on the account, not on a claim (design D7). Defaults to CUSTOMER for every account,
-- including every existing row; granting STAFF is a deliberate out-of-band database action and is
-- never derived from an OAuth claim or any client-supplied value.
ALTER TABLE identity ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER';

-- Intake audit: every attempt, accepted or rejected, with the acting staff identity, the target
-- agreement (NULL when the submitted reference resolved to nothing), the outcome, and the time.
-- Deliberately carries NO certificate number, NO scan bytes, and NO certificate metadata -- the
-- certificate number is the single-use token evidencing duty payment and the scan carries party
-- names, so neither belongs in an audit row that is read far more widely than the agreement itself.
CREATE TABLE stamp_intake_audit (
    id                  UUID        PRIMARY KEY,
    staff_identity_id   UUID        NOT NULL REFERENCES identity (id),
    agreement_id        UUID        REFERENCES agreement (id),
    -- The submitted reference, normalised and truncated by the application. Recorded so a rejected
    -- attempt against an unknown reference is still traceable.
    submitted_reference VARCHAR(32),
    outcome             VARCHAR(48) NOT NULL,
    occurred_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_stamp_intake_audit_agreement_id ON stamp_intake_audit (agreement_id);
CREATE INDEX idx_stamp_intake_audit_occurred_at ON stamp_intake_audit (occurred_at);
