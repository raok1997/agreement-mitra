## Context

The templating stack has, in order: `template-definition-model` (the single-template shape --
typed `Field`s, plain-text-slot `Clause`s, ordered `Section`s, `CanonicalJson` + SHA-256 identity,
immutable records in `in.agreementmitra.documents.template`); `template-resolution-engine` (composes
`(state, type)` into one materialized, hash-pinned `EffectiveTemplate` behind a `LayerSource` seam
that, in its D8, reads layers from **classpath resources** and is explicitly "registry-ready later");
`template-form-projection` (projects the effective template into the capture form and added the
module's first public surface, the `documents.api` `@NamedInterface`); and
`template-document-projection` (draws the filled document and **pins** the effective-template identity
onto the agreement via `V8__agreement_template_pin.sql`, which added the nullable `template_id`,
`template_content_hash`, `template_layer_versions` columns).

Every one of those resolves **one** template for a **default** `(state, type)` -- document
projection's own Open Questions flag "the exact default token and how the agreement will later carry
chosen dimensions are the catalog CR's concern." This is that CR. It is the `document-templating-
platform` exploration's **CR-2**, and the **first templating CR to introduce a database table**: a
Postgres **registry** of published templates that the user browses to pick one before capture.

The exploration's registry decision is precise and load-bearing here: `{id, name, description,
category, state, language, version, status}` **metadata only**; **bodies live as resources / object
storage, never inline in Postgres**; published versions are **immutable**; and an agreement **records
the template id + version it used**. This CR builds to exactly those points.

Constraints unchanged: Java 21 + Spring Boot 3.5.x + Spring Modulith modular monolith; records for
DTOs; constructor injection; package-private by default, `public` only on the module `api`; Flyway is
the single schema source (`ddl-auto: validate`, `flyway.clean` disabled); keep `ModularityTests`
green; sandbox + dummy data only; the markup/data boundary (template bodies are system-owned; users
pick and fill, never author markup).

## Goals / Non-Goals

**Goals**

- A **Postgres catalog** (`template` table) of published templates holding **metadata only** plus a
  **pointer** to the template's classpath layer set -- never the definition/patch/HTML bodies.
- **Selection endpoints** on the existing `documents.api`: list published templates filtered by
  `state` / `type` / free-text `q`; fetch one published template (with its dimensions); hide draft and
  deprecated from both (no existence oracle).
- A **registry-backed `LayerSource`** so a **selected** template resolves to its ordered layers **by
  id** via the catalog row's pointer, reusing the resolution engine's existing seam -- bodies stay
  classpath resources; the resolver is untouched.
- **Record the chosen `template_id`** on the agreement (server-sourced), populating the column
  `template-document-projection`'s `V8` already added -- coordinated, not re-added.
- **Seed** the catalog with the existing reference template(s) as published, dummy rows.
- A Vue **template picker** (search + filter by type/state; language reserved/disabled) that leads a
  chosen template into the existing capture flow.
- Unit-testable core (metadata mapping, published-only filtering, id-based selection) without a Spring
  context; integration-tested endpoints + migration on Testcontainers Postgres; `ModularityTests`
  green.

**Non-Goals (each a separate concern / named follow-on CR)**

- **Authoring or editing templates** -- the admin **structured builder** (sections + clauses +
  variables compiled to a system-owned definition). Admin-only, gated behind `mobile-otp-auth` + an
  admin role; later. The catalog here is **read/select only** -- it has no create/update surface.
- **The resolution engine** -- already `template-resolution-engine`. This CR **backs its `LayerSource`
  seam from the registry**; it does not re-implement composition, precedence, or effective-template
  identity.
- **Rendering / preview / PDF** -- `template-document-projection`. The catalog selects; it does not
  draw.
- **The capture form structure** -- `template-form-projection`. The picked template's dimensions
  **feed** that projection; this CR does not build the form.
- **Moving layer bodies into object storage** -- bodies **stay classpath resources** here; the DB
  stores metadata + a pointer. Object-storage promotion is a bounded follow-on behind the same
  pointer.
- **Full version-lifecycle workflow enforcement** beyond "only published is selectable" -- the
  `draft -> legal_approved -> published -> deprecated` transitions belong to the admin builder. This CR
  reads `status` and exposes **only** `published`; it does not run the workflow.

## Decisions

### D1: The registry holds metadata + a pointer -- never a template body

The `template` table stores exactly the exploration's metadata set -- `id, name, description,
category/type, state, language (reserved), version, status` -- plus a **`layer_set_ref`** pointer that
names **where the template's layer set lives** (today: a classpath location key). It stores **no**
definition YAML, patch YAML, clause text, or HTML. Rationale: the platform's non-negotiable is
"bodies live as resources / object storage, never inline in Postgres"; Postgres holds the **index of
what exists**, resources hold the **content**. A registry row therefore *maps to* its layer set by
pointer; resolution still loads the bodies from resources (D3). **Alternatives rejected:** inlining
the definition/patch YAML as a `text`/`jsonb` column (violates the boundary, bloats the row, and
duplicates the canonical resource -- the resource stays the source of truth); storing a rendered HTML
body (that is document projection's ephemeral output, never persisted metadata).

### D2: A `TemplateCatalogEntry` aggregate mirroring `Agreement`; package-private in `documents.template`

The catalog row maps to a package-private `@Entity TemplateCatalogEntry` in the **one** templating
package `in.agreementmitra.documents.template` (the locked packaging from `template-resolution-engine`
D10 and `template-document-projection` D8), so it can sit beside the definition/resolution records
without widening any record visibility. It mirrors the `Agreement` aggregate style: **app-assigned
UUID via a factory** (identity stable from birth), `Persistable<UUID>` with a transient `isNew` flag
(`@PostPersist`/`@PostLoad`) to skip the phantom `SELECT` on `save()`, **id-based equals/hashCode**,
and an **id-only `toString()`**. Fields: `id`, `name`, `description`, `type` (category), `state`,
`language` (reserved, defaulted to English), `version`, `status`, `layerSetRef`, `createdAt`. The
repository is a package-private Spring Data interface with published-only finders (D4).
**Alternative rejected:** a generic key/value config table -- loses type-safety, validation, and the
clean aggregate the rest of the module already uses.

### D3: Registry-backed `LayerSource` -- select by id, load bodies from resources (reuse the seam, do not rewrite the resolver)

The resolution engine (D8) resolves via `LayerSource.layersFor(state, type) -> ordered { base,
patches[] }`, today a classpath implementation. This CR adds a **registry-backed `LayerSource`** that,
given a **selected template id** (or its `(state, type)` dimensions), reads the catalog row, follows
its `layer_set_ref` **pointer**, and returns the **same ordered layer set** -- **still loading the
layer bodies from classpath resources**. The DB decides *which* layer set (by id / published status);
the resources still provide the *bodies*. The `TemplateResolver`, its fixed precedence
(`base <- type <- state <- state+type`), and effective-template identity are **untouched** -- this is
a new `LayerSource` behind the existing seam, selected by configuration/profile, not a resolver
rewrite. **Alternatives rejected:** having the catalog return layer bodies from Postgres (violates
D1); forking a second resolver keyed by template id (the seam exists precisely so we do not).

### D4: Only published is selectable; the endpoints never leak draft/deprecated (no oracle)

`status` is one of the exploration's lifecycle values (`draft | legal_approved | published |
deprecated`). Both endpoints expose **only `published`**: `GET /api/templates` filters to published
before applying `state` / `type` / `q`; `GET /api/templates/{id}` returns the entry **only if
published**, else **404** -- the **same 404** for an unknown id and a non-published (draft/deprecated)
id, so their existence cannot be probed (consistent with the stack's no-oracle discipline, e.g.
form projection's 404-without-echo and mobile-otp-auth's uniform 404). The repository's finders are
**published-scoped by construction** (e.g. `findByIdAndStatus(id, PUBLISHED)`,
`findByStatusAndState...`) so a non-published row cannot escape through a query path. **Alternative
rejected:** a `403` for a known-but-unpublished id -- that is an existence oracle; `404` is uniform.

### D5: Recording the chosen template_id -- a value across the Modulith boundary, no type leak

Selection records **`template_id`** on the agreement. That column **already exists** -- it was added
by `template-document-projection`'s `V8__agreement_template_pin.sql` (three nullable columns:
`template_id`, `template_content_hash`, `template_layer_versions`). This CR **coordinates with** that
migration and **does not re-add** the column. The `documents` module provides catalog **ids** (via
the `documents.api` `TemplateCatalogApi`); the `signing`/`agreement` side **stores the id** via a
server-managed setter, exactly as it stores the effective-template pin -- it holds only a **UUID/text
id**, never a `documents.template` catalog type, so no `signing -> documents.template` reach-in exists
and `ModularityTests` stays green. The **hash + layer-version pin** still happens at generate-as-draft
(document projection): the catalog records **which** template was chosen; document projection records
**exactly which resolved bytes** were rendered. The id is **server-sourced from the selection**, never
a free client field (anti-mass-assignment, consistent with how owner and pin are handled).
**Alternative rejected:** a second migration adding `template_id` here -- it would collide with `V8`;
the column is shared, populated at select-time by this CR and hash-pinned at generate-time by document
projection.

### D6: Migration numbering -- nominal `V9`, real number assigned at apply time (coordination caveat)

The last migration **on disk** is `V7__rich_agreement_capture.sql`. But two **active-but-unapplied**
changes also add migrations: `mobile-otp-auth` proposes `V7__mobile_identity_auth.sql` (which already
**collides** with the on-disk `V7`) and `template-document-projection` proposes
`V8__agreement_template_pin.sql`. **No number is final until applied.** This CR nominally names its
migration **`V9__template_catalog.sql`** (assuming document projection's `V8` lands first), but the
**real version must be assigned at apply time in apply order** -- forward-only, never editing an
applied migration. If the numbers shift (because `mobile-otp-auth`'s `V7` is renumbered, or `V8` is
still unapplied when this lands), renumber this migration to the next free ascending slot **before
applying**. This CR's migration **creates the `template` catalog table** (metadata + pointer); it
does **not** add columns to `agreement` (that is document projection's `V8`). Recorded again in
`tasks.md` so it is not missed at apply time.

### D7: Packaging and boundary -- service/repo/entity package-private; only the api is public

Everything that touches the definition/resolution records or the JPA entity is **package-private in
`documents.template`**: `TemplateCatalogEntry`, its repository, `TemplateCatalogService`, and the
registry-backed `LayerSource`. The **only** public surface is on the existing `documents.api`
`@NamedInterface` (introduced by form projection, extended by document projection): a
`TemplateCatalogApi` port + immutable catalog DTOs (a list item and a detail record) + a
`TemplateCatalogController`. The port implementation stays package-private, exactly as form
projection kept its `FormProjector` and document projection kept its `DocumentProjectionService`
internal. `ModularityTests` therefore sees the **same one** named interface, extended -- no new module
and no widened record.

## Catalog table + selection flow (ASCII sketch)

```
  template  (metadata + pointer only -- NO body ever)
  +----------------------+---------------------------+---------------------------------------------+
  | column               | type                      | notes                                       |
  +----------------------+---------------------------+---------------------------------------------+
  | id                   | uuid  PRIMARY KEY         | app-assigned in factory                     |
  | name                 | text  NOT NULL            | e.g. "Residential Rental Agreement"         |
  | description          | text                      | short human blurb (browse card)             |
  | type                 | text  NOT NULL            | category: residential|commercial|pg|licence |
  | state                | text  NOT NULL            | jurisdiction dimension (e.g. TG)            |
  | language             | text  NOT NULL            | RESERVED -- English only for now            |
  | version              | text  NOT NULL            | published version identity                  |
  | status               | text  NOT NULL            | draft|legal_approved|published|deprecated   |
  | layer_set_ref        | text  NOT NULL            | POINTER to classpath layer set (NOT a body) |
  | created_at           | timestamptz NOT NULL      |                                             |
  +----------------------+---------------------------+---------------------------------------------+
  INDEX on (status, state, type)  -- the browse filter path; only 'published' is ever selected.
  ( bodies -- definition YAML / patch YAML / clause text / HTML -- stay CLASSPATH RESOURCES )

  Selection flow (browse -> pick -> capture):

   SPA                     documents.api                documents.template (package-private)
    | GET /api/templates       |                              |
    |  ?state&type&q           |  list(published, filters)    |
    |------------------------->|----------------------------->| repo.findPublished(state,type,q)
    |   200 [ {id,name,...} ]  |<-----------------------------|   (published-only finder, D4)
    |<-------------------------|                              |
    | GET /api/templates/{id}  |                              |
    |------------------------->|  detail(id)  --------------->| repo.findByIdAndStatus(id, PUBLISHED)
    |   200 {id,dimensions,..} |<-----------------------------|   else -> 404 (no oracle, D4)
    |<---(404 if not pub)------|                              |
    | user picks template id   |                              |
    | create agreement with    |  record template_id (server-sourced, D5) --> agreement.template_id
    |  selected template_id -->|  (V8 column; NOT re-added)                    (hash pin later, at
    |------------------------->|                                                generate-as-draft)
    v                          v
   capture flow ( form-projection uses the picked template's dimensions )

   Resolution when the picked template renders (document projection, later CR):
     TemplateResolver.resolve(dimensions)
        -> LayerSource.layersFor(...)   [ registry-backed, D3 ]
             reads catalog row by id/dimensions -> follows layer_set_ref POINTER
             -> loads ordered layer BODIES from CLASSPATH RESOURCES (never Postgres)
        -> EffectiveTemplate (unchanged resolver, unchanged precedence + identity)
```

## Risks / Trade-offs

- **Shared `template_id` column across two CRs.** The column is added by document projection's `V8`
  and populated by this CR at select-time (hash-pinned by document projection at generate-time). If the
  two land out of order the column may not exist yet. Mitigation: this CR **depends on** document
  projection for the column (recorded as a prerequisite); if this CR must land first, the `template_id`
  column moves into this CR's migration and document projection's `V8` drops it -- a coordination the
  apply-order note (D6) makes explicit. Either way the column is added **once**, never twice.
- **Registry vs classpath drift.** With the registry deciding *which* layer set and resources holding
  the *bodies*, a catalog row's `layer_set_ref` could point at a missing/renamed resource. Mitigation:
  a startup/integration check that every **published** row's pointer resolves to a present layer set;
  the seed rows point at the real reference layers; a dangling pointer fails the check, not a render.
- **Metadata duplication with the definition `meta`.** A definition already carries `dimensions` and
  `version` in its `meta`; the catalog row repeats `state/type/version`. Mitigation: the catalog is the
  **browse index** (query-shaped, published-scoped); the definition `meta` stays the **content** source
  of truth. The seed derives the catalog metadata **from** the reference definition so they agree; a
  test asserts the seed row's dimensions/version match the definition it points at.
- **Object storage deferred.** Bodies stay classpath resources; a real multi-template library will want
  object-storage bodies (authoring writes them). Accepted for this CR (bounded, behind the pointer);
  object-storage promotion is a named follow-on that changes only how the pointer is dereferenced, not
  the registry shape.
- **Only-published enforcement lives in the query layer.** If a future code path queries the repository
  without the published filter it could leak a draft. Mitigation: the repository exposes **only**
  published-scoped finders (D4); an integration test asserts a draft/deprecated row is invisible to
  both endpoints.
- **No authoring surface yet.** Rows are seeded, not authored; the team cannot add a template through
  the product until the admin builder lands. Accepted -- this CR is browse/select; authoring is a
  separate admin-only CR with its own auth/role work.

## Migration Plan

Additive and ordered **after** `template-definition-model`, `template-resolution-engine`, and
(for the shared `template_id` column) `template-document-projection`. Steps:

1. Add the `TemplateCatalogEntry` aggregate + repository (published-only finders) and the
   `TemplateCatalogService`, package-private in `documents.template`.
2. Add the registry-backed `LayerSource` (select layer set by id / dimensions from the catalog;
   load bodies from classpath resources) behind the existing seam; select it by profile/config so the
   classpath source stays the fallback.
3. Extend the `documents.api` `@NamedInterface` with the `TemplateCatalogApi` port + catalog DTOs and
   the `TemplateCatalogController` (`GET /api/templates`, `GET /api/templates/{id}`).
4. Record the chosen `template_id` on the agreement at selection (server-managed setter on the
   aggregate; the `V8` column, **not** re-added). Permit the two read endpoints in `SecurityConfig`
   alongside the other anonymous templating reads.
5. Ship the forward-only migration **`V9__template_catalog.sql`** (nominal -- **assign the real number
   at apply time**, D6): create the `template` table (metadata + pointer) and its
   `(status, state, type)` index; **do not** touch `agreement`. `ddl-auto: validate` must pass against
   the new table.
6. Seed the catalog with the reference template(s) as **published**, dummy rows (via a seeding
   migration/loader consistent with the reference layer set), so browse is non-empty.
7. Frontend: a template picker view + a `src/api/` catalog client; a picked template flows into the
   existing capture flow carrying its dimensions and id.

Rollback in this pre-production sandbox phase: drop the `template` table and remove the migration
manually (`flyway.clean` stays disabled). No deployed consumers. **No new dependency, no lockfile
change.**

## Open Questions

- **`layer_set_ref` shape.** A classpath location key today; when object storage lands it becomes a
  storage key. Proposing an opaque string the active `LayerSource` interprets (classpath now,
  object-storage later) so the column shape does not change. Confirm the key format.
- **Where the chosen dimensions live on the agreement.** This CR records `template_id`; whether the
  agreement also stores the resolved `(state, type)` (versus deriving them from the pinned template) is
  a small follow-up. Document projection's Open Questions raised the same point; proposing the
  `template_id` is sufficient for select-time and the hash pin carries the resolved identity.
- **Version selection granularity.** The catalog exposes published entries; if two published versions
  of the same `(state, type)` template coexist (effective-dating for law changes), which the browse
  lists and which selection binds is a lifecycle concern. Proposing "latest published per
  `(state, type, name)`" for now; the full effective-dating story rides the admin lifecycle CR.
- **Seed mechanism.** A Flyway data insert vs an application seeding loader keyed to the reference
  layer set. Proposing the loader (so the seed metadata derives from the reference definition and
  cannot drift), env/profile-gated to sandbox; confirm.
- **Migration number at apply time.** Nominal `V9`; the actual number depends on whether
  `mobile-otp-auth`'s colliding `V7` and document projection's `V8` are applied first (D6). Assign at
  apply time.

## Addendum -- picker view unblocked (2026-07-12, M6)

Task 6.2 (the picker view) was deferred because its host shell did not exist. It now does: the
schema-fed `CaptureForm.vue` landed with `preview-centric-capture` + `template-form-projection`, so the
picker is built under M6. Decision (recorded in flow-journal section 8.3):

- **Entry step via a lightweight view-switch, not vue-router.** No router/pinia is installed; adding
  one widens the frontend OSV surface (`package-lock.json`) that the frontend `securityScan` gate
  locks. `App.vue` holds a `selection` ref -- render `TemplatePicker.vue` first; on selection, mount
  `CaptureForm` with `state`/`type` props that replace its hardcoded `DEFAULT_STATE`/`DEFAULT_TYPE`. A
  "change template" affordance returns to the picker and clears the localStorage draft.
- **Carry dimensions without a detail round-trip.** `TemplateSummary` (the list item) already carries
  `state` + `type`, so the chosen card's dimensions flow straight into capture; `GET /templates/{id}`
  is not required on the select path (it stays available for a detail view).
- **Parity caveat (not this CR's to fix).** For any non-default `(state, type)`, the live preview
  (schema-driven `documents` compiler) and the stored draft (fixed-getter mapper) can diverge until
  `agreement-attributes-and-pinning` (M5) single-sources the mapper -- see flow-journal 8.5.
  The picker therefore should not imply full preview/draft parity for non-default templates yet.
