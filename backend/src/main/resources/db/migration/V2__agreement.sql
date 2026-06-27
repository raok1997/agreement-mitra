-- V2 — agreement aggregate (agreement-aggregate CR / CR-2).
--
-- A multi-party rental agreement: one `agreement` row, N `signer` rows (any number of
-- owners and tenants). Status-less — the signing-status FSM is introduced by a later CR.
-- Forward-only: never edits V1; a correction ships as V3+.
--
-- Column types are chosen to match the JPA mapping so Hibernate `ddl-auto: validate`
-- passes (UUID -> uuid, BigDecimal -> numeric(12,2), int -> integer, Instant ->
-- timestamptz, String -> text). Money is fixed-scale numeric, never float.

CREATE TABLE agreement (
    id               UUID                     PRIMARY KEY,
    property_address TEXT                     NOT NULL,
    monthly_rent     NUMERIC(12, 2)           NOT NULL,
    security_deposit NUMERIC(12, 2)           NOT NULL,
    term_months      INTEGER                  NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE signer (
    id           UUID PRIMARY KEY,
    agreement_id UUID NOT NULL REFERENCES agreement (id),
    name         TEXT NOT NULL,
    email        TEXT NOT NULL,
    role         TEXT NOT NULL
);

-- The aggregate is loaded by-parent; index the FK so signer fan-out is not a seq scan.
CREATE INDEX idx_signer_agreement_id ON signer (agreement_id);
