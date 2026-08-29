-- V12 -- agreement ownership (agreement-ownership CR / CR-B).
--
-- Attaches an optional owner to the agreement aggregate: a nullable FK to CR-A's `identity(id)`.
-- Create leaves it NULL (anonymous draft); it is set only by the explicit claim (save) action.
-- Once set, the agreement's reads/edits are owner-scoped. No backfill -- existing rows stay NULL.
--
-- Forward-only: never edits V1..V11; does NOT touch the `signer`/signing tables. Hard dependency:
-- CR-A's V11 must have created `identity` first (the FK references it).
--
-- Column type matches the JPA mapping so `ddl-auto: validate` passes (UUID -> uuid). Nullable by
-- design: an unclaimed draft is legitimately owner-less.

ALTER TABLE agreement
    ADD COLUMN owner_identity_id UUID REFERENCES identity (id);

-- The "list mine" query filters by owner; index the FK so it is not a seq scan.
CREATE INDEX idx_agreement_owner_identity_id ON agreement (owner_identity_id);
