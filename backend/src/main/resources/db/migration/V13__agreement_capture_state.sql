-- V13 -- agreement capture state (agreement-capture-persistence CR / M5 attributes store).
--
-- Persists the agreement's full capture state so a saved agreement round-trips its complete content
-- and the stored/signed draft renders exactly what the user saw in the live preview. The blob holds
-- the flat working-set field map (field key -> value) plus the list of added optional-section titles:
--   { "data": { <fieldKey>: <value>, ... }, "activeSections": [ <title>, ... ] }
--
-- Nullable + server-managed: create/edit set it; an API client sending only the fixed fields leaves
-- it NULL and the render falls back to the fixed-column mapping (behaviour unchanged for legacy rows).
-- No backfill -- existing rows stay NULL.
--
-- Forward-only: never edits V1..V12; does NOT touch the `signer`/signing tables. Depends on CR-B's
-- V12 (owner column). Column type matches the JPA @JdbcTypeCode(JSON) mapping (jsonb) so
-- `ddl-auto: validate` passes, mirroring the existing `template_layer_versions` jsonb column.

ALTER TABLE agreement
    ADD COLUMN capture_state JSONB;
