-- V3 — signing request aggregate (create-signing-request CR / CR-3).
--
-- A signing request is the home of the signing-status FSM (PDF_GENERATED -> SIGN_REQUESTED ->
-- SIGNED | FAILED | EXPIRED). It references an agreement; the Agreement aggregate itself stays
-- status-less (CR-2 decision). One agreement may have many signing requests over time.
-- Forward-only: never edits V1/V2; a correction ships as V4+.
--
-- Column types match the JPA mapping so Hibernate `ddl-auto: validate` passes (UUID -> uuid,
-- String/enum -> varchar/text, Instant -> timestamptz, @Version long -> bigint).

CREATE TABLE signing_request (
    id                   UUID                     PRIMARY KEY,
    agreement_id         UUID                     NOT NULL REFERENCES agreement (id),
    -- The vendor document id. NULL until the provider create call returns: the row is persisted
    -- BEFORE that call (so a provider-success / DB-failure split cannot orphan a live document),
    -- then filled in. UNIQUE so webhook/reconciliation lookup by document id is unambiguous.
    provider_document_id VARCHAR(255)             UNIQUE,
    -- The FSM state. Bounded varchar (not unbounded text) so the @Enumerated(STRING) mapping
    -- validates deterministically at boot.
    status               VARCHAR(32)              NOT NULL,
    -- Optimistic-lock guard: concurrent/duplicate webhook deliveries cannot interleave-corrupt
    -- the FSM; the losing writer fails the version check.
    version              BIGINT                   NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Per-signer signing handle. Keyed back to the canonical signer row by FK (not an email string)
-- so the URL's owner is unambiguous; sign_url is a bearer capability (never logged).
CREATE TABLE signing_request_invitee (
    id                 UUID         PRIMARY KEY,
    signing_request_id UUID         NOT NULL REFERENCES signing_request (id),
    signer_id          UUID         NOT NULL REFERENCES signer (id),
    sign_url           TEXT         NOT NULL,
    expiry             VARCHAR(64)
);

-- Loaded by-parent and looked up by document id; index the FK and the lookup column.
CREATE INDEX idx_signing_request_agreement_id ON signing_request (agreement_id);
CREATE INDEX idx_sri_signing_request_id ON signing_request_invitee (signing_request_id);
