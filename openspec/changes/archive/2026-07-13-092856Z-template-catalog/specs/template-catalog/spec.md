## ADDED Requirements

### Requirement: A registry persists published catalog metadata, never template bodies

The system SHALL persist a **template catalog** in Postgres: one row per catalog entry carrying
**metadata only** -- `id`, `name`, `description`, `type` (category), `state`, `language` (reserved,
English only for now), `version`, `status`, a **pointer** to the entry's layer set
(`layer_set_ref`), and `created_at`. The catalog SHALL NOT store any template body -- no definition
YAML, no layer/patch YAML, no clause text, and no HTML -- those SHALL remain classpath resources
(object storage later); the row SHALL reference its layer set by pointer only. The schema SHALL be
created by a **forward-only** Flyway migration, and JPA SHALL stay `ddl-auto: validate` so Flyway
remains the single source of schema truth.

#### Scenario: A catalog entry persists metadata and a pointer, not a body

- **WHEN** a catalog entry is stored
- **THEN** its row holds the entry's name, description, type, state, reserved language, version,
  status, and a pointer to its layer set, and the row contains no definition YAML, patch YAML, clause
  text, or HTML body

#### Scenario: The catalog table is created by a forward-only migration validated by JPA

- **WHEN** the application starts against the migrated schema
- **THEN** the Flyway migration that creates the `template` table has applied in version order without
  editing any earlier migration, and Hibernate `ddl-auto: validate` passes against the mapped entity

#### Scenario: Template bodies remain resources, not database rows

- **WHEN** a catalog entry's template is resolved for rendering
- **THEN** its layer bodies are loaded from classpath resources via the entry's pointer, and no
  template body is read from Postgres

### Requirement: List only published catalog entries, filtered by state, type, and query

The system SHALL expose `GET /api/templates?state=..&type=..&q=..` that returns catalog entries whose
`status` is **published**, filtered by the optional `state` and `type` dimensions and an optional
free-text `q` matched over name and description. Draft and deprecated entries SHALL NOT appear in the
result under any filter. Each returned item SHALL carry the entry's system-owned metadata (id, name,
description, type, state, reserved language, version) and SHALL contain no signer PII and no secret.

#### Scenario: The list returns only published entries

- **WHEN** the catalog holds published, draft, and deprecated entries and a client requests
  `GET /api/templates`
- **THEN** the response lists only the published entries, and no draft or deprecated entry appears

#### Scenario: Filtering by state and type narrows the list

- **WHEN** a client requests `GET /api/templates?state=TG&type=residential`
- **THEN** the response contains only published entries whose state is `TG` and whose type is
  `residential`

#### Scenario: A free-text query filters by name or description

- **WHEN** a client requests `GET /api/templates?q=residential`
- **THEN** the response contains only published entries whose name or description matches the query,
  and still excludes any draft or deprecated entry

### Requirement: Fetch one published catalog entry, with 404 for unknown or non-published

The system SHALL expose `GET /api/templates/{id}` that returns a single **published** catalog entry
including its dimensions (`state`, `type`, reserved `language`) and `version`. For an **unknown id**
or an id that exists but is **not published** (draft or deprecated), the endpoint SHALL return
**404** using the application's RFC 9457 error contract -- the **same** response for both, so the
existence of a draft or deprecated entry cannot be probed (no existence oracle). The response body
SHALL contain no signer PII and no secret.

#### Scenario: A published id returns its entry with dimensions

- **WHEN** a client requests `GET /api/templates/{id}` for a published entry
- **THEN** the endpoint returns `200` with the entry's metadata including its `state`, `type`,
  reserved `language`, and `version`

#### Scenario: An unknown id returns 404

- **WHEN** a client requests `GET /api/templates/{id}` for an id that does not exist
- **THEN** the endpoint returns `404` via the RFC 9457 contract

#### Scenario: A non-published id returns the same 404 as an unknown id

- **WHEN** a client requests `GET /api/templates/{id}` for an entry whose status is draft or
  deprecated
- **THEN** the endpoint returns `404` indistinguishable from the unknown-id response, so the entry's
  existence is not revealed

### Requirement: Selecting a catalog template records its id on the agreement

The system SHALL, when a catalog template is selected for an agreement, record the selected template's
**id** on that agreement as a server-managed `template_id`. The id SHALL be sourced from the selection
on the server, never accepted as a free client-settable field. This SHALL populate the `template_id`
column introduced by `template-document-projection`'s pin migration (this change SHALL NOT re-add that
column); the effective-template **content hash and layer versions** SHALL still be pinned later, at
generate-as-draft. The agreement SHALL hold only the id value and SHALL NOT depend on any
`documents`-module catalog type, so the module boundary stays clean.

#### Scenario: Selection sets template_id server-side

- **WHEN** an agreement is created or updated with a selected published catalog template
- **THEN** the agreement's `template_id` is set to that template's id by the server, not from a
  client-supplied body field

#### Scenario: The hash pin is still deferred to generate-as-draft

- **WHEN** a template is selected but the agreement has not yet been generated as a draft
- **THEN** the agreement records the selected `template_id`, and the effective-template content hash
  and layer versions remain unset until generate-as-draft pins them

#### Scenario: The agreement carries only an id, not a documents type

- **WHEN** `ModularityTests` runs after this change
- **THEN** it passes: the `signing`/`agreement` side stores the selected template id as a value and
  introduces no dependency on any `documents.template` internal type

### Requirement: A selected template resolves its layers by id through the LayerSource, bodies staying resources

The system SHALL resolve a **selected** catalog template to its ordered layer set by locating the
layers **by the catalog entry's id / dimensions** through the resolution engine's existing
`LayerSource` seam, while the layer **bodies remain classpath resources**. A registry-backed
`LayerSource` SHALL read the catalog row and follow its `layer_set_ref` pointer to return the ordered
layers; it SHALL load the layer bodies from resources, not from Postgres. The `TemplateResolver`, its
fixed layer precedence, and the effective-template identity SHALL be unchanged -- only the source of
"which layers for this template" MAY now be the registry.

#### Scenario: Resolution of a selected template locates its layers via the registry

- **WHEN** a selected published template is resolved
- **THEN** the registry-backed `LayerSource` returns the ordered layer set referenced by the catalog
  entry, and the resolver composes the effective template from those layers

#### Scenario: Layer bodies are loaded from resources, not the database

- **WHEN** the registry-backed `LayerSource` supplies a selected template's layers
- **THEN** the layer bodies are read from classpath resources via the entry's pointer, and no layer
  body is read from Postgres

#### Scenario: The resolver and effective-template identity are unchanged

- **WHEN** the same template is resolved via the classpath `LayerSource` and via the registry-backed
  `LayerSource` for the same layer set
- **THEN** the composed effective template and its content hash are identical, because only the layer
  lookup source changed, not the resolver, its precedence, or the identity computation

### Requirement: Only published versions are selectable and published versions are immutable

The system SHALL treat a **published** catalog version as **immutable** and SHALL make **only
published** entries selectable. The catalog SHALL expose read/selection surfaces only; it SHALL NOT
expose any create, edit, or version-transition surface in this capability (authoring is the admin
builder). A non-published entry SHALL be neither listed, fetchable, nor selectable.

#### Scenario: Only published entries can be selected

- **WHEN** a selection is attempted against a draft or deprecated entry
- **THEN** the entry is not selectable (it is not returned by the list or detail endpoints), so no
  agreement can bind a non-published template

#### Scenario: The catalog exposes no authoring surface

- **WHEN** the catalog capability is inspected
- **THEN** it exposes only list, fetch, and selection reads, and provides no endpoint to create, edit,
  or transition a template version

#### Scenario: A published version is immutable

- **WHEN** a published catalog entry exists
- **THEN** its published metadata is not mutated by this capability, so an agreement that selected it
  binds a stable, reproducible template identity

### Requirement: The catalog exposes only system-owned metadata and keeps the module boundary clean

The catalog and its endpoints SHALL expose **only system-owned template metadata** -- ids, names,
descriptions, types, states, reserved language, versions, statuses, and layer-set pointers -- and
SHALL contain **no signer PII, no secret, and no template body**. The `documents` module SHALL expose
this capability through the existing public `documents.api` named interface (a `TemplateCatalogApi`
port + catalog DTOs + the HTTP controller); the catalog entity, repository, service, and the
registry-backed `LayerSource` SHALL remain module-internal, and no cross-module reach-in SHALL be
introduced.

#### Scenario: A catalog response contains no user data or secret

- **WHEN** any catalog list item or detail is produced
- **THEN** it contains only template metadata and no signer name, address, identity number, one-time
  code, secret, or template body

#### Scenario: The public surface is only the catalog DTOs and port

- **WHEN** another module or the SPA consumes this capability
- **THEN** it depends only on the public catalog DTOs and `TemplateCatalogApi` port (over the named
  interface / HTTP), never on the catalog entity, repository, service, or `LayerSource` internal type

#### Scenario: The module boundary stays clean

- **WHEN** `ModularityTests` runs after this change
- **THEN** it passes: the catalog is exposed only through the existing `documents.api` named interface
  (extended with the catalog port and controller), the entity/repository/service/`LayerSource` stay
  package-private in `documents.template`, and no disallowed cross-module dependency is introduced

## MODIFIED Requirements

### Requirement: Anonymous agreement drafting

The system SHALL continue to let a rental agreement be drafted and persisted, and SHALL additionally
record the **selected catalog template's id** on the agreement when a template is chosen. The
`template_id` SHALL be server-sourced from the selection and SHALL NOT be a client-settable body
field (anti-mass-assignment preserved). It SHALL populate the `template_id` column already introduced
by `template-document-projection`'s pin migration -- this change SHALL NOT re-add that column -- and
the effective-template content-hash and layer-version pin SHALL still be recorded at generate-as-draft
(document projection). No existing agreement field or transition SHALL change.

#### Scenario: A draft records the chosen template id

- **WHEN** an agreement is drafted with a selected published catalog template
- **THEN** the agreement persists with its `template_id` set to the selected template's id, sourced by
  the server and not from a client-settable field

#### Scenario: The template id is coordinated with the existing pin column

- **WHEN** this change and `template-document-projection` are both applied
- **THEN** the `template_id` column exists exactly once (added by the pin migration), this change adds
  no duplicate column, and selection populates it while generate-as-draft pins the content hash and
  layer versions

#### Scenario: Existing agreement behavior is unchanged

- **WHEN** an agreement is drafted without selecting a catalog template
- **THEN** it persists as before with a null `template_id`, and no existing field or transition
  behaves differently
