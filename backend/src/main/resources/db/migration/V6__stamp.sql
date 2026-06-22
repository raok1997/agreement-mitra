-- V6 — stamp-composition (CR-6): the e-stamp procured for an agreement's instrument.
--
-- Adds server-managed stamp DATA to the agreement aggregate (the stamp LIFECYCLE stays on the
-- signing_request FSM — CR-2's status-less Agreement is preserved). All columns are nullable:
-- empty until a stamp is procured during the first signing request. `stamped_pdf_key` is the
-- object-storage key of the composited stamped PDF — the bytes live in MinIO/S3, never here.
-- Agreement-scoped (key derived from the agreement id): the stamp belongs to the instrument and
-- is reused across signing attempts (a deliberate divergence from signed-artifact request-scoping,
-- safe under CR-5 lock-forever). Forward-only: never edits an applied migration.

ALTER TABLE agreement
    ADD COLUMN stamp_serial       VARCHAR(40),
    ADD COLUMN stamped_pdf_key    TEXT,
    ADD COLUMN stamp_denomination INTEGER,
    ADD COLUMN stamp_jurisdiction VARCHAR(8),
    ADD COLUMN stamp_duty_paid    BOOLEAN,
    ADD COLUMN stamp_procured_at  TIMESTAMP WITH TIME ZONE;
