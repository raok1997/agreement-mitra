-- V15 -- one tracking reference, not two (manual-estamp-upload, gap-closure review).
--
-- V14 introduced a persisted `staff_reference` ALONGSIDE the derived, display-only
-- `AM-<LAST6>-<DDMMYY>` tracking number the customer and the document showed. That was wrong:
-- the customer receives a number, staff type that same number in to attach the e-stamp, and the
-- document must show it. Two references for one agreement is exactly the transcription hazard the
-- check character exists to catch.
--
-- So the persisted, checksummed value becomes THE tracking reference - customer-facing, staff-facing,
-- and rendered on the document - and the derived veneer is deleted from the application. Nothing is
-- dropped here: the derived value was never a column (it was computed at render time from the id and
-- the start date), so there is no data to migrate away from.
--
-- Forward-only: never edits V1..V14.

ALTER TABLE agreement RENAME COLUMN staff_reference TO tracking_reference;

ALTER TABLE agreement
    RENAME CONSTRAINT uq_agreement_staff_reference TO uq_agreement_tracking_reference;

-- Re-prefix `AMS` (AgreementMitra Staff) to `AM`, now that the value is what the CUSTOMER is given.
-- Only the prefix changes: the eight body characters and the trailing check character are untouched,
-- and the checksum covers the body alone, so every existing reference stays valid under the new
-- format. The mapping is one-to-one, so the unique constraint is preserved.
UPDATE agreement
   SET tracking_reference = 'AM' || substr(tracking_reference, 4)
 WHERE tracking_reference LIKE 'AMS%';
