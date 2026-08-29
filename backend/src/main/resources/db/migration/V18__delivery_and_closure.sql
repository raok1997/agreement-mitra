-- V18 -- Signed-document delivery + agreement closure (signed-delivery-and-closure CR).
--
-- Additive and forward-only. Never edits V1..V17.
--
-- THREE CONCERNS, ONE MIGRATION (design "Migration Plan" step 1).
--
-- 1. THE INVITED ADDRESS (design D2). `signing_request_invitee` recorded the signing URL and the
--    per-invitee status but not the address the invitation was ISSUED to. Delivery needs it,
--    because the ONLY address the signed agreement may be sent to is the one at which that party
--    actually completed signing: invited here, then observed SIGNED on the same row. The draft
--    record is deliberately NOT a fallback - a mistyped draft-time address would otherwise email
--    both parties' names, the property address, the financial terms, and a stamp certificate to an
--    uninvolved stranger, irreversibly. Nullable: every pre-existing row predates this column, and
--    a row with no invited address resolves to "unresolvable" (escalated to staff), never to a
--    guess.
--
-- 2. PER-RECIPIENT DELIVERY RECORDS (design D3/D4). One row per (signing request, signer,
--    artifact). Recipients fail independently: one party's mailbox bouncing must not block the
--    other's delivery, must not retry the successful one, and must be individually diagnosable and
--    individually re-sendable. A boolean on the signing request could not express "delivered to the
--    owner, hard-bounced for the tenant", which is exactly the state staff have to act on.
--
--    The UNIQUE index is the exactly-once anchor. The completion path is re-entered by BOTH the
--    webhook and the reconciliation job, so "we only call this once" is wrong on the first
--    redelivery; the row is CLAIMED by a guarded conditional UPDATE before any message is handed to
--    the provider, and a concurrent or repeated attempt finds it already claimed.
--
-- 3. AGREEMENT CLOSURE (design D1). Terminal FULFILMENT state on the agreement, beside payment
--    state - NOT a new signing state. The signing FSM answers "what happened to the signatures" and
--    its terminal states are a legal record; closure answers "is there work outstanding", which
--    continues past signing and also applies to an agreement that never signed at all. Adding
--    DELIVERED/CLOSED to the signing FSM would make SIGNED non-terminal (the spec relies on it) and
--    would leave no way to close a STAMP_FAILED agreement that has no signature to speak of.
--
-- PII: `recipient_email` and `invited_email` ARE party PII. They are stored because delivery cannot
-- happen without them and because a delivery record has to say where the document actually went.
-- They are redacted (local part masked) in every log line, are never returned unredacted by the
-- staff diagnostic view, and no document bytes are stored in either table - only object-storage
-- keys, which live on `signing_request`.

-- 1. The address the signing invitation was issued to, captured at provider-create time.
ALTER TABLE signing_request_invitee
    ADD COLUMN invited_email TEXT;

-- 2. Per-recipient delivery of a signed artifact.
CREATE TABLE signed_document_delivery
(
    id                UUID PRIMARY KEY,
    agreement_id      UUID        NOT NULL REFERENCES agreement (id),
    signing_request_id UUID       NOT NULL REFERENCES signing_request (id),
    signer_id         UUID        NOT NULL REFERENCES signer (id),
    -- Which artifact this row delivers. Only SIGNED_AGREEMENT is ever written: the audit trail is
    -- deliberately never emailed (it carries eKYC-derived detail that does not belong in inboxes).
    -- The column exists so a future artifact is a value, not a migration.
    artifact          VARCHAR(32) NOT NULL,
    -- The signing-verified address, or NULL when none could be resolved (status UNRESOLVABLE).
    recipient_email   TEXT,
    -- PENDING | IN_PROGRESS | SENT | FAILED | UNRESOLVABLE. Ours, not a provider vocabulary.
    -- SENT means "the provider accepted the message" - plain SMTP reports acceptance, never
    -- arrival. Bounce reporting arrives with ZeptoMail's bounce webhook and is out of scope here.
    status            VARCHAR(24) NOT NULL,
    attempts          INTEGER     NOT NULL DEFAULT 0,
    -- A short, fixed failure token (never a provider message, never document content).
    last_error        VARCHAR(128),
    -- Earliest time the next attempt may be claimed; drives the bounded exponential backoff.
    next_attempt_at   TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL,
    sent_at           TIMESTAMPTZ,
    -- TRUE when the signed PDF exceeded the attachment ceiling and the party was sent a
    -- notification pointing at the in-app copy instead (design D6). Recorded rather than inferred,
    -- because "we emailed them" and "we emailed them a link to fetch it themselves" are different
    -- facts and a support conversation turns on which one happened. A truncated or partial
    -- attachment is never an outcome.
    notification_only BOOLEAN     NOT NULL DEFAULT FALSE,
    -- A deliberate staff re-send is a DISTINCT, attributed attempt, not a silent retry.
    resend_count      INTEGER     NOT NULL DEFAULT 0,
    resent_by_identity_id UUID    REFERENCES identity (id),
    resent_at         TIMESTAMPTZ,
    -- Optimistic-locking column, matching the aggregate convention elsewhere in the schema.
    version           BIGINT      NOT NULL DEFAULT 0
);

-- THE EXACTLY-ONCE ANCHOR. At most one delivery record per (request, signer, artifact), so a
-- re-entered completion path cannot create a second record to send from. The application inserts
-- optimistically and treats the constraint violation as "another attempt already created it" -
-- never a read-then-write pre-check, which two concurrent completions would both pass.
CREATE UNIQUE INDEX uq_delivery_request_signer_artifact
    ON signed_document_delivery (signing_request_id, signer_id, artifact);

-- Backs the retry sweep: due, not-yet-terminal rows, oldest-first.
CREATE INDEX ix_delivery_status_next_attempt
    ON signed_document_delivery (status, next_attempt_at);

-- Backs the staff diagnostic view and the closure predicate, both of which read by agreement.
CREATE INDEX ix_delivery_agreement
    ON signed_document_delivery (agreement_id);

-- 3. Agreement fulfilment closure.
--
-- OPEN | CLOSED, NOT NULL with a default so every existing row validates immediately as OPEN and
-- no read has to treat NULL as a third, undefined state - the same shape payment_state took in
-- V16. closed_at and closure_reason are nullable because an OPEN agreement has neither.
--
-- BACKFILL IS DELIBERATELY EMPTY. Pre-existing SIGNED agreements are left OPEN rather than being
-- retro-closed, precisely so this deploy cannot emit a burst of delivery emails for agreements
-- that completed before delivery existed (design, Migration Plan step 3). Confirm that before
-- deploying anywhere holding real signed agreements.
ALTER TABLE agreement
    ADD COLUMN closure_state  VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN closed_at      TIMESTAMPTZ,
    -- COMPLETED | ABANDONED_SIGNING_FAILED | ABANDONED_SIGNING_EXPIRED | ABANDONED_STAMP_FAILED.
    -- Abandoned is a DISTINCT value from completed and must stay so in every read and report: one
    -- produced a signed agreement and the other did not, and collapsing them into a single "closed"
    -- fact would erase the difference.
    ADD COLUMN closure_reason VARCHAR(32);

-- Backs the outstanding-work views, which must exclude closed agreements so dead work leaves the
-- staff queue instead of accumulating there forever.
CREATE INDEX ix_agreement_closure_state
    ON agreement (closure_state);
