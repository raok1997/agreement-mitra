## Why

`template-definition-model` gave us the shape of a single template; `template-resolution-engine`
composes `(state, type)` into one immutable, hash-pinned **effective template** and left a
`LayerSource` seam that today reads layers from **classpath resources**; `template-form-projection`
projects that effective template into the capture form and introduced the module's first public
surface (`documents.api`); `template-document-projection` draws the filled document and **pins** the
effective template's identity onto the agreement (its `V8__agreement_template_pin.sql` added the
nullable `template_id`, `template_content_hash`, `template_layer_versions` columns on `agreement`).

Everything so far resolves **one** template -- the single reference definition, for a default
`(state, type)`. But the `document-templating-platform` exploration's target is a **library**:
residential / commercial / PG / leave-and-licence agreements across many states. The moment there is
more than one, a user must **browse and pick one before capture**, and the system needs a **registry**
of what is published to browse over. The exploration calls this **CR-2 (template catalog)** and marks
it as where the templating stack **first touches a database**:

> **Template registry** (Postgres): `{id, name, description, category, state, language, version,
> status}`. Bodies live as resources / object storage, **never inline in Postgres**. The
> domain-agnostic `DocumentRenderer` stays as-is; a template-resolution layer selects the body.

This change lands exactly that: a **Postgres catalog of published templates (metadata only)**,
**selection endpoints** to browse and pick one, a **registry-backed `LayerSource`** so a picked
template resolves to its layers **by id** (bodies staying as resources), the recording of the chosen
`template_id` onto the agreement, and a **template picker** in the SPA. It is the seam that turns the
one-template render path into a many-template product without moving any template body into the
database and without rewriting the resolver.

**What this CR deliberately is not.** It does **not** author or edit templates (the admin structured
builder is a separate, admin-only CR), does **not** re-implement the resolver (already its own CR),
does **not** render or preview (document projection), and does **not** move layer bodies into object
storage (bodies stay as classpath resources here; the DB stores metadata plus a pointer). It adds the
**browse-and-select** layer, and the registry that browse reads from, and nothing more.

## What Changes

- Introduce a new **`template-catalog`** capability inside the `documents` module: a **Postgres
  registry** of published templates. The catalog stores **metadata only** -- `{ id, name, description,
  category/type, state, language (reserved), version, status }` plus a **pointer** to the template's
  layer set / resource location. It **never** stores the definition YAML, patch YAML, clause text, or
  HTML body; **bodies stay as classpath resources** (object-storage promotion is a named follow-on).
- Add a **catalog aggregate + repository** (`TemplateCatalogEntry`, package-private JPA entity in
  `in.agreementmitra.documents.template`, mirroring the `Agreement` aggregate style: app-assigned
  UUID via a factory, `Persistable<UUID>` with a transient `isNew`, id-based equals/hashCode). A
  **forward-only Flyway migration** creates the `template` table; JPA stays `ddl-auto: validate`.
- Add **selection endpoints** on the existing public `documents.api` `@NamedInterface`:
  - `GET /api/templates?state=..&type=..&q=..` -- lists **published** catalog entries, filtered by
    `state`, `type`, and a free-text `q` over name/description. Draft and deprecated entries are
    **hidden** (never returned by this endpoint).
  - `GET /api/templates/{id}` -- returns one **published** entry including its dimensions
    (`state`, `type`, reserved `language`) and version. An unknown id **or** a non-published entry
    both return **404** (no draft/deprecated existence oracle).
- **Back the resolver's `LayerSource` from the registry** without rewriting the resolver: add a
  registry-backed `LayerSource` so a **selected** template resolves to its ordered layers **by id**
  via the catalog row's pointer, while the **layer bodies stay classpath resources** -- the DB stores
  metadata + a pointer, the resolver still loads bodies from resources. This reuses the existing
  `LayerSource.layersFor(...)` seam (the resolution engine's D8), not a new resolver.
- **Record the choice.** Selecting a catalog template sets the agreement's **`template_id`** -- the
  column **already added** by `template-document-projection`'s `V8__agreement_template_pin.sql`
  (**coordinated with, not re-added**). The effective-template **hash + version pin** still happens at
  generate-as-draft (document projection); the catalog records **which** template was chosen. The id
  is server-sourced from the selection, never a free client field.
- **Seed** the catalog with the existing reference template(s) as **published**, dummy,
  system-authored rows, so the browse screen is non-empty on a fresh database.
- **Frontend**: a **template picker / browse** screen in the Vue SPA (search box + filter by type and
  state; the language filter is **reserved/disabled**, English only) that lists published templates
  and leads the chosen one into the existing capture flow. API calls live in `src/api/`.

**Explicitly not in this change** (each a named follow-on / separate CR): **authoring or editing**
templates -- the admin structured builder (admin-only, later); the **resolution engine** itself
(already `template-resolution-engine`); **rendering / preview / PDF** (`template-document-projection`);
the **capture form structure** (`template-form-projection`); **moving layer bodies into object
storage** (bodies stay classpath resources here); and **full version-lifecycle workflow enforcement**
beyond "only published is selectable" (the `draft -> legal_approved -> published -> deprecated`
authoring workflow is admin-builder territory). See Non-Goals in `design.md`.

## Capabilities

### New Capabilities

- `template-catalog`: a Postgres **registry of published templates** holding **metadata only**
  (`id, name, description, category/type, state, language reserved, version, status`) plus a pointer to
  the template's classpath layer set -- template/layer **bodies are never inlined in Postgres**;
  published versions are **immutable** and only **published** entries are selectable. **Selection
  endpoints** on `documents.api` (`GET /api/templates` filtered list of published entries;
  `GET /api/templates/{id}` one published entry, 404 for unknown or non-published -- no draft oracle);
  a **registry-backed `LayerSource`** that resolves a selected template to its ordered layers **by id**
  while bodies stay resources; and a Vue **template picker** that leads a chosen template into capture.

### Modified Capabilities

- `agreement-management`: selecting a catalog template **records the chosen `template_id`** on the
  agreement (server-sourced from the selection, never a client-settable field). This populates the
  `template_id` column introduced by `template-document-projection`'s `V8` pin migration; the effective
  template **hash + layer-version pin** is still set at generate-as-draft (document projection). No
  existing field, transition, or the anti-mass-assignment guarantee changes.
- `template-resolution`: unchanged in behavior, but its `LayerSource` seam gains a **registry-backed
  implementation** so a selected template's layers are located **by id** from the catalog row's pointer
  (bodies still loaded from classpath resources). The resolver, its precedence, and effective-template
  identity are untouched -- only the source of "which layers for this template" can now be the registry
  instead of a hardcoded classpath lookup.

## Impact

- **`documents` module**: a new package-private `TemplateCatalogEntry` JPA aggregate + repository and a
  package-private `TemplateCatalogService` in `in.agreementmitra.documents.template` (co-located with
  the definition/resolution records so **no record visibility widens**); a registry-backed
  `LayerSource` implementation alongside the existing classpath one. The public `documents.api`
  `@NamedInterface` (introduced by `template-form-projection`) gains a `TemplateCatalogApi` port +
  catalog DTOs and a `TemplateCatalogController` (`GET /api/templates`, `GET /api/templates/{id}`). The
  service and repository stay module-internal. `ModularityTests` stays green (the same one named
  interface, extended; no new module, no widened record).
- **Reuse, not fork**: the catalog references the layer set / dimensions established by
  `template-definition-model` and `template-resolution-engine`; it backs their `LayerSource` seam
  rather than re-implementing resolution. **Both must be applied first.**
- **`signing` module (agreement-management)**: the `agreement` aggregate gains a server-managed way to
  record the selected **`template_id`** (a plain value -- the column already exists from `V8`); it holds
  only an id, **never** a `documents` catalog type, so Modulith boundaries stay clean (no
  `signing -> documents.template` reach-in; the id crosses as a value at the `api` layer).
- **Security wiring**: `GET /api/templates` and `GET /api/templates/{id}` are **public reads of
  system-owned metadata** (no PII, no auth needed); they are added to the anonymous permit set
  alongside the other templating read endpoints. No change to any authenticated matcher, the signing
  permit, or the webhook permit.
- **Dependencies**: **none added.** Persistence uses Spring Data JPA + Flyway already present; JSON
  serialization uses the Jackson already provided by Spring Boot. **No `gradle.lockfile` change** and
  nothing new enters the OSV `securityScan` surface.
- **Data / schema**: **the templating stack's first table.** One forward-only Flyway migration
  (nominally `V9__template_catalog.sql`, but the real version is assigned at apply time in apply order
  -- see design D6 and the tasks note) creates the `template` catalog table (metadata + pointer). It
  **does not** add columns to `agreement` -- the `template_id` column is document projection's `V8`.
  JPA stays `ddl-auto: validate`; `flyway.clean` stays disabled; template bodies stay classpath
  resources, never Postgres.
- **Frontend**: a template picker / browse view + a `src/api/` client for the catalog endpoints,
  leading a chosen template into the existing capture flow.
- **No** change to: the signing-status FSM, `EsignProvider` / webhook flow, stamping, object storage,
  the reconciliation job, the resolver, or the render path.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **None.** A catalog entry is **system-owned
  template metadata** -- a template's name, description, category/type, state, reserved language,
  version, status, and a pointer to its classpath layer set. It carries **no signer data** at all: no
  government identity number, one-time code, virtual id, name, address, or party PII, and **no secret
  material**. The registry describes *which templates exist*, never an instance of anyone's data.
- **Bodies never enter Postgres.** The catalog stores **metadata plus a pointer** only; the definition
  YAML, patch YAML, clause text, and HTML body **stay as classpath resources**, consistent with the
  platform rule that template/layer bodies live as resources / object storage and never inline in
  Postgres. A registry row maps to its layer set by pointer; the resolver still loads bodies from
  resources.
- **The two new surfaces are HTTP reads of published metadata.** `GET /api/templates` returns **only
  published** entries (draft and deprecated are hidden); `GET /api/templates/{id}` returns a published
  entry or **404** for both an unknown id and a non-published one -- the same response for both, so a
  draft or deprecated template's existence **cannot be probed** (no oracle). The response body carries
  no PII and is deterministic per catalog state.
- **First new DB table + Flyway migration in this stack.** The migration is **forward-only** and never
  edits an applied migration; JPA stays `ddl-auto: validate` (Flyway is the single schema source);
  `flyway.clean` stays disabled. A boot-and-validate integration test catches schema/mapping drift. The
  migration creates the `template` table only; it **does not** touch `agreement` (that column is
  document projection's `V8`).
- **Immutability of published versions.** A published catalog version is **immutable** -- the catalog
  exposes selection/read only; it has **no authoring or edit surface** in this CR (authoring is the
  admin builder). Only published entries are selectable.
- **Markup/data boundary held.** The catalog manipulates **structured metadata**, never markup; no
  user-authored markup and no expression/DSL surface is introduced. Selecting a template records an
  **id** (server-sourced from the selection), never a client-supplied body or path.
- **Sandbox + dummy data only?** Preserved -- the seed rows are **dummy, system-authored** published
  templates; nothing connects to a live provider or real data; no credential or env var is added.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** -- no signing sequence diagram required.
