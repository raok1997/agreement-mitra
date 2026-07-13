-- V8 -- template catalog (template-catalog CR / CR-2).
--
-- The templating stack's FIRST database table: a Postgres registry of published-selectable
-- templates holding METADATA + A POINTER ONLY. Bodies (definition YAML / patch YAML / clause text /
-- HTML) stay classpath resources and are NEVER inlined here; `layer_set_ref` points at the layer
-- set, the resolver loads the bodies from resources via that pointer.
--
-- Forward-only: never edits V1..V7. Column types match the JPA mapping so `ddl-auto: validate`
-- passes (UUID -> uuid, String -> text, int -> integer, enum-as-STRING -> text, Instant ->
-- timestamptz).
--
-- `version` is INTEGER (not TEXT): it is the same monotonic version concept as the definition
-- `meta.version` (an int) and `FormSchema.version` (an int), and "latest published wins" must order
-- it NUMERICALLY -- a TEXT column would rank "9" above "10" under a string DESC and pick the wrong
-- effective template once a second version publishes for a (state, type).

CREATE TABLE template (
  id            UUID        PRIMARY KEY,
  name          TEXT        NOT NULL,
  description   TEXT,
  type          TEXT        NOT NULL,
  state         TEXT        NOT NULL,
  language      TEXT        NOT NULL,
  version       INTEGER     NOT NULL,
  status        TEXT        NOT NULL,
  layer_set_ref TEXT        NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL
);

-- The browse-filter path (`GET /api/templates?state=..&type=..`): only 'published' is ever selected,
-- narrowed by (state, type). Status-leading so the published-only scan is index-covered.
CREATE INDEX idx_template_status_state_type ON template (status, state, type);

-- CATALOG-LANDS-FIRST FALLBACK (design D6 / D5 coordination):
-- `template-document-projection`'s V8 pin migration (which nominally adds template_id +
-- template_content_hash + template_layer_versions to `agreement`) is NOT built/applied yet, so this
-- catalog CR takes the next free on-disk slot V8. Because recording the chosen template needs a
-- template_id on `agreement`, it is added HERE (nullable). WHEN document-projection lands it MUST
-- take V9 and add ONLY the remaining hash/layer-version pin columns (template_content_hash,
-- template_layer_versions) -- it must NOT re-add template_id (added once, here). Populated at
-- select-time by this CR; hash-pinned at generate-as-draft by document projection.
ALTER TABLE agreement ADD COLUMN template_id UUID;
