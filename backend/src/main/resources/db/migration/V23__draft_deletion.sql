-- V23: an owner may delete an unpaid draft (change delete-draft-agreement, D7 and D10).
--
-- agreement_deletion. One row per agreement its owner deleted, written in the delete transaction,
-- so support can tell "the owner deleted it" from "it never existed" and abuse can be investigated.
-- It holds NO PARTY DATA (no name, contact, address or money value), but it is PSEUDONYMOUS
-- PERSONAL DATA: owner_identity_id joins to the account's name and email, and the tracking
-- reference was emailed to every party. Readers: staff with database access only; nothing in the
-- product reads it. Retention: the three-year horizon of ToS section 12 until counsel answers the
-- DPDP retention question (docs/COUNSEL-BRIEF.md (b)).
--
-- No foreign keys, deliberately: agreement_id names a row that no longer exists, and an FK to
-- identity would block a future account erasure. That erasure must therefore clear (or delete)
-- owner_identity_id here itself.
--
-- A tracking reference can in principle be reissued after its agreement is deleted, so a lookup by
-- reference is "deleted" only when no live agreement holds that reference.
CREATE TABLE agreement_deletion (
    agreement_id       UUID                     PRIMARY KEY,
    tracking_reference VARCHAR(16)              NOT NULL,
    owner_identity_id  UUID                     NOT NULL,
    deleted_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_agreement_deletion_tracking_reference ON agreement_deletion (tracking_reference);

-- stamp_intake_audit.agreement_id -> ON DELETE SET NULL. Staff intake refused against a not-yet-
-- finalised agreement records its id, so a deletable draft can carry these rows. The audit row is
-- kept with its submitted reference, outcome, staff identity and time; only the link is cleared.
--
-- recovery_audit is untouched: its agreement_id is set only for unowned PAID/WAIVED agreements,
-- and payment state never returns to UNPAID, so a deletable draft never has one.
ALTER TABLE stamp_intake_audit DROP CONSTRAINT stamp_intake_audit_agreement_id_fkey;
ALTER TABLE stamp_intake_audit
    ADD CONSTRAINT stamp_intake_audit_agreement_id_fkey
    FOREIGN KEY (agreement_id) REFERENCES agreement (id) ON DELETE SET NULL;

COMMENT ON COLUMN stamp_intake_audit.agreement_id IS
    'NULL means the submitted reference resolved to nothing, or the agreement was later deleted by '
    'its owner (an unpaid draft). For the latter, join the trimmed, upper-cased submitted_reference '
    'to agreement_deletion.tracking_reference.';
