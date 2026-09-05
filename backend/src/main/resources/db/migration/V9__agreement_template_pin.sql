-- V9 -- agreement template pin (agreement-template-pin CR / CR-3).
--
-- The reproducibility pin: at generate-as-draft the effective template's identity is recorded on
-- the `agreement` so the stored/signed draft is reproducible and can NEVER be silently re-rendered
-- against newer layers. System-owned integrity metadata ONLY -- a content hash + a
-- layerId->version map. NO party PII, Aadhaar, OTP, VID, or secret is stored here.
--
-- Forward-only: never edits V1..V8. Does NOT re-add `template_id` -- V8 (template_catalog) already
-- added it (nullable) and reserved these two remaining pin columns for THIS CR at V9. Both columns
-- are nullable (a fresh/ungenerated agreement carries no pin). Column types match the JPA mapping
-- so `ddl-auto: validate` passes (String -> text, Map<String,Integer> as @JdbcTypeCode(JSON) ->
-- jsonb).
ALTER TABLE agreement ADD COLUMN template_content_hash   TEXT;
ALTER TABLE agreement ADD COLUMN template_layer_versions JSONB;
