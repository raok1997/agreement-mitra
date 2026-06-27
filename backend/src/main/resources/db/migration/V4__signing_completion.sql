-- V4 — signing completion (signing-completion CR / CR-4).
--
-- Closes the completion path on top of CR-3's create + webhook + FSM:
--   1. per-signer aggregation — each invitee carries its own status; the aggregate FSM
--      reaches SIGNED only when ALL invitees signed (FAILED on any reject, EXPIRED on expiry).
--   2. signed-artifact storage — the signed PDF + audit trail land in object storage; only
--      their object KEYS live here (bytes never in Postgres).
-- Forward-only: never edits V1/V2/V3; a correction ships as V5+.
--
-- Column types match the JPA mapping so Hibernate `ddl-auto: validate` passes. All columns
-- are nullable/additive so the existing CR-3 rows validate without backfill.

-- Per-invitee completion sub-state. `status` is the vendor-neutral InviteeStatus
-- (PENDING/SIGNED/REJECTED/EXPIRED), a sub-state of the request's SIGN_REQUESTED. `signing_order`
-- is the create-time ordinal (provider-response order) used as the correlation fallback;
-- `provider_invitee_id` is the provider's per-invitee identifier (preferred correlation key).
ALTER TABLE signing_request_invitee
    ADD COLUMN status              VARCHAR(16),
    ADD COLUMN signing_order       SMALLINT,
    ADD COLUMN provider_invitee_id VARCHAR(255);

-- Ordinals are unambiguous within a request (the correlation fallback must not collide).
ALTER TABLE signing_request_invitee
    ADD CONSTRAINT uq_sri_request_order UNIQUE (signing_request_id, signing_order);

-- Object-storage keys for the completed artifacts (NOT the bytes). NULL until the SIGNED
-- transition downloads + stores them; a failed download leaves them NULL for reconciliation.
ALTER TABLE signing_request
    ADD COLUMN signed_pdf_key   TEXT,
    ADD COLUMN audit_trail_key  TEXT;

-- The reconciliation scan selects by (status, created_at): stale SIGN_REQUESTED rows and
-- SIGNED rows awaiting artifact download. Index both predicate columns, oldest-first.
CREATE INDEX idx_signing_request_status_created_at ON signing_request (status, created_at);
