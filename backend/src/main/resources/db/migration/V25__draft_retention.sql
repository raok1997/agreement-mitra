-- V25: stale unpaid drafts are purged after 90 days without an edit (change stale-draft-purge, D4).
--
-- agreement_deletion now records two kinds of deletion, told apart by `reason`:
--   OWNER_DELETE    - the owner deleted an unpaid draft (DELETE /api/agreements/{id}, V23).
--   RETENTION_PURGE - the daily retention run deleted an unpaid draft whose content had not been
--                     edited for 90 days (terms of service section 10), claimed or not.
-- A purged unclaimed draft has no owner, so owner_identity_id becomes nullable. No CHECK ties an
-- owner to OWNER_DELETE: V23 promises a future account erasure may clear owner_identity_id, and
-- such a CHECK would forbid it. The entity factory supplies the owner for an owner delete.
--
-- Purpose, readers and retention are unchanged from V23: support tells "deleted" from "never
-- existed"; staff with database access only; the three-year horizon of ToS section 12 until counsel
-- answers the DPDP retention question (docs/COUNSEL-BRIEF.md (b)). The deletion record is also the
-- evidence the orphaned-object sweep requires before it removes a drafts/ object (design D6).
ALTER TABLE agreement_deletion ALTER COLUMN owner_identity_id DROP NOT NULL;

ALTER TABLE agreement_deletion ADD COLUMN reason VARCHAR(16) NOT NULL DEFAULT 'OWNER_DELETE';
ALTER TABLE agreement_deletion ALTER COLUMN reason DROP DEFAULT;
ALTER TABLE agreement_deletion
    ADD CONSTRAINT agreement_deletion_reason_check
    CHECK (reason IN ('OWNER_DELETE', 'RETENTION_PURGE'));

COMMENT ON TABLE agreement_deletion IS
    'One PII-free row per deleted unpaid draft: reason OWNER_DELETE (its owner deleted it) or '
    'RETENTION_PURGE (90 days without an edit; owner_identity_id is NULL for an unclaimed draft). '
    'Pseudonymous personal data - see the V23 and V25 headers for purpose, readers and retention.';

COMMENT ON COLUMN stamp_intake_audit.agreement_id IS
    'NULL means the submitted reference resolved to nothing, or the agreement was later deleted - '
    'by its owner or by the retention purge (an unpaid draft either way). For the latter, join the '
    'trimmed, upper-cased submitted_reference to agreement_deletion.tracking_reference.';

-- The purge's candidate query is a keyset range over (last_edited_at, id) among unpaid open
-- agreements; the state literals here must match the query's for the planner to use this index.
CREATE INDEX idx_agreement_unpaid_open_last_edited
    ON agreement (last_edited_at, id)
    WHERE payment_state = 'UNPAID' AND closure_state = 'OPEN';
