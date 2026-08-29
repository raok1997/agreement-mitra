-- V19 -- Recovery request audit (post-payment-continuity CR).
--
-- Additive and forward-only. Never edits V1..V18.
--
-- WHAT THIS IS. One row per recovery request: someone entered a tracking reference and asked for
-- the link to their agreement to be re-sent. This is the ONLY storage the recovery feature adds.
--
-- THERE IS NO TOKEN TABLE, AND THAT IS DELIBERATE (design D2/D4). The emailed link carries the
-- agreement identifier and IS the access - there is nothing to redeem, nothing to expire, and no
-- credential to store. Access ends when the agreement is claimed into an account, because anonymous
-- access to a claimed agreement is already refused. A future change that adds a token table here
-- should re-read D2 first: the permanence is a decision, not an oversight.
--
-- WHY AUDIT AT ALL. The request endpoint answers identically whatever happens (design D1), so its
-- responses tell an operator nothing. This table is where "who asked for what, and what did we do"
-- actually lives - for abuse investigation, and so a support conversation has evidence.
--
-- PII: `recipient_redacted` is a REDACTED contact, never a full address - the same redaction the
-- delivery logs use. `reference` is the agreement's own tracking reference, which the requester
-- supplied and which authorises nothing by itself. `requester_fingerprint` identifies a source for
-- rate-limit forensics, not a person. No party name, property address, or money value belongs here,
-- and there is deliberately no column that could carry one.

CREATE TABLE recovery_audit (
    id                    UUID PRIMARY KEY,

    -- The reference as submitted, normalised. Not a foreign key: most rows are for references that
    -- matched nothing, and those are exactly the rows an abuse investigation cares about.
    reference             VARCHAR(16)  NOT NULL,

    -- The agreement this resolved to, when it resolved to one. NULL for every outcome where it did
    -- not - unknown reference, unpaid, already claimed.
    agreement_id          UUID         NULL REFERENCES agreement (id),

    -- What happened, in our vocabulary: SENT, NOT_ELIGIBLE, NO_CONTACT, THROTTLED, NOT_CONFIGURED.
    -- Deliberately more granular than the response, which is identical in every case.
    outcome               VARCHAR(32)  NOT NULL,

    -- How many recipients were dispatched to. Zero is a normal outcome, not an error.
    recipients_sent       INTEGER      NOT NULL DEFAULT 0,

    -- Redacted contact, or NULL when nothing was sent. Never a full address.
    recipient_redacted    VARCHAR(128) NULL,

    -- A coarse identifier for the request source, for rate-limit forensics.
    requester_fingerprint VARCHAR(128) NULL,

    requested_at          TIMESTAMPTZ  NOT NULL
);

-- Abuse investigation reads by reference ("who has been probing this one?") and by time window
-- ("what happened in the last hour?"). Both are the access patterns this table exists to serve.
CREATE INDEX idx_recovery_audit_reference ON recovery_audit (reference, requested_at DESC);
CREATE INDEX idx_recovery_audit_requested_at ON recovery_audit (requested_at DESC);
