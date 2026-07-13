> **Prerequisite:** this change depends on `template-definition-model` and
> `template-resolution-engine` (to know a template's dimensions / layer set and to reuse the
> `LayerSource` seam) and coordinates with `template-document-projection` for the shared
> `template_id` column (added by that CR's `V8__agreement_template_pin.sql` -- this CR populates it at
> selection time and MUST NOT re-add it). It FEEDS `template-form-projection` (the picked template's
> dimensions drive the form). Apply **`template-definition-model`**, **`template-resolution-engine`**,
> and **`template-document-projection`** first; reuse their package-private records and the existing
> `documents.api` named interface (do not fork them).
>
> **Migration-number caveat:** the last migration on disk is `V7__rich_agreement_capture.sql`, but two
> active-but-unapplied changes also add migrations (`mobile-otp-auth`'s `V7__mobile_identity_auth.sql`
> already collides with the on-disk `V7`, and `template-document-projection`'s
> `V8__agreement_template_pin.sql`). This CR's migration is nominally `V9__template_catalog.sql`, but
> the **real version must be assigned at apply time in apply order** (forward-only; never edit an
> applied migration). Renumber to the next free ascending slot before applying if the numbers have
> shifted.

> **APPLY-TIME RESOLUTION (implemented):** the last migration on disk was `V7`, and neither
> `mobile-otp-auth`'s `V7` nor `template-document-projection`'s `V8` had actually landed. So this CR
> took the **next free on-disk slot `V8__template_catalog.sql`** (not the nominal `V9`). Because
> document-projection's pin migration does not exist yet, the shared **`agreement.template_id`** column
> is added **here** (nullable, catalog-lands-first fallback). WHEN document-projection lands it MUST
> renumber to **`V9`** and add **only** the remaining `template_content_hash` + `template_layer_versions`
> pin columns -- it must **not** re-add `template_id`. This is recorded in the migration's SQL comment.

## 1. Catalog aggregate + repository (package `in.agreementmitra.documents.template`, package-private)

- [x] 1.1 Add a package-private `TemplateCatalogEntry` `@Entity` (table `template`) co-located with the
  definition/resolution records so **no record visibility widens**: app-assigned UUID via a factory,
  `Persistable<UUID>` with a `@Transient isNew` flag (`@PostPersist`/`@PostLoad`), id-based
  equals/hashCode, **id-only `toString()`**. Fields: `id`, `name`, `description`, `type` (category),
  `state`, `language` (reserved, defaulted English), `version`, `status`, `layerSetRef` (pointer to the
  classpath layer set -- **never a body**), `createdAt`. Column names use Boot snake_case so
  `ddl-auto: validate` lines up.
- [x] 1.2 Add a package-private Spring Data repository with **published-only** finders (so a
  non-published row cannot escape a query path): e.g. `findByStatusAndStateAndType(...)` /
  a published-scoped free-text search over name/description, and `findByIdAndStatus(id, PUBLISHED)`.

## 2. Catalog service + registry-backed LayerSource (package `documents.template`, package-private)

- [x] 2.1 Add a package-private `TemplateCatalogService` (`@Service`, constructor injection): `list(state,
  type, q)` returns published entries filtered by dimensions + free-text; `detail(id)` returns a
  published entry or signals not-found for unknown/non-published (no oracle). Map entities to DTOs
  inside the transaction (no entity crosses the boundary).
- [x] 2.2 Add a **registry-backed** `LayerSource` implementation behind the resolution engine's existing
  seam: given a selected template id / dimensions, read the catalog row and follow its `layerSetRef`
  **pointer** to return the ordered layer set, **loading the layer bodies from classpath resources**
  (not Postgres). Select it by profile/config so the classpath `LayerSource` stays the fallback. Do
  **not** modify the `TemplateResolver`, its precedence, or the effective-template identity.

## 3. Public catalog API + HTTP surface (package `in.agreementmitra.documents.api`)

- [x] 3.1 Extend the existing `documents.api` `@NamedInterface("api")` with a **public**
  `TemplateCatalogApi` port and immutable catalog DTOs (a list item `{ id, name, description, type,
  state, language, version }` and a detail record incl. dimensions). **No new named interface** --
  extend the one form projection added and document projection extended.
- [x] 3.2 Add `TemplateCatalogController`: `GET /api/templates?state=..&type=..&q=..` -> `200` with the
  filtered published list (possibly empty); `GET /api/templates/{id}` -> `200` for a published entry,
  `404` (RFC 9457, no echoed input) for an unknown **or** non-published id. Records only; no
  client-settable id/status fields.
- [x] 3.3 Permit `GET /api/templates` and `GET /api/templates/{id}` in `SecurityConfig` alongside the
  other anonymous templating read endpoints (public reads of system-owned metadata). Do not touch any
  authenticated matcher, the signing permit, or the webhook permit.

## 4. Record the chosen template on the agreement (module `signing`, shared V8 column)

- [x] 4.1 Add a server-managed way to record the selected `template_id` on the `Agreement` aggregate
  (never client-settable), populating the **existing** `template_id` column added by
  `template-document-projection`'s `V8` -- **do not re-add the column**. The id is sourced from the
  selection at the `api` layer and passed inward as a **value** (UUID/text), so `signing` holds no
  `documents.template` type (Modulith-clean). The effective-template **hash + layer-version pin** stays
  at generate-as-draft (document projection) -- unchanged.

## 5. Flyway migration + seed (create the `template` table)

- [x] 5.1 Add the forward-only migration **`V9__template_catalog.sql`** (nominal -- **assign the real
  number at apply time in apply order**, see the caveat above): `CREATE TABLE template (id uuid primary
  key, name text not null, description text, type text not null, state text not null, language text not
  null, version text not null, status text not null, layer_set_ref text not null, created_at timestamptz
  not null)` with an index on `(status, state, type)` for the browse-filter path. This migration
  **creates the `template` table only**; it **does not** add columns to `agreement`. Never edit an
  applied migration. Map column types to the JPA mapping so `ddl-auto: validate` passes.
- [x] 5.2 Seed the catalog with the existing reference template(s) as **published**, dummy,
  system-authored rows whose `layerSetRef` points at the real reference layer set and whose
  dimensions/version derive from the reference definition's `meta` (so metadata does not drift). Gate
  the seed to the sandbox (profile/config). Browse is non-empty on a fresh database.

## 6. Frontend -- template picker / browse (`frontend/src`)

- [x] 6.1 Add a catalog API client in `src/api/` (`listTemplates(state, type, q)`,
  `getTemplate(id)`) calling the two endpoints; keep all API calls in `src/api/`.
- [x] 6.2 DONE (2026-07-12, under M6): `src/components/TemplatePicker.vue` -- the entry-step picker.
  Lists published templates via `listTemplates()` (search box + state/type filters; language filter
  disabled), cards with state/type/version badges. A **lightweight view-switch in `App.vue`** (NOT
  vue-router -- flow-journal 8.3) renders the picker first and, on selection, mounts `CaptureForm` with
  the chosen `(state, type)` as props (replacing its hardcoded defaults); a "Change template" control
  returns to the picker. Dimensions ride `TemplateSummary` (no detail round-trip). **Selection is
  CONSTRAINED to the seeded/default `(TG, residential)`**; every other pair renders disabled with a
  "Coming soon" badge, because generate-as-draft is not yet dimension-aware (flow-journal 8.5). Lift
  the constraint once the draft path is dimension-aware + single-sourced with preview.

## 7. Tests -- unit (many, fast; no Spring context, no I/O)

- [x] 7.1 `TemplateCatalogEntry` mapping/identity: the factory assigns a stable UUID; `isNew` flips
  after persist/load semantics (pure-object level where possible); `toString()` is id-only (no
  metadata leak); id-based equals/hashCode.
- [x] 7.2 Catalog DTO mapping: an entity maps to a list item / detail DTO carrying exactly the
  system-owned metadata (id, name, description, type, state, reserved language, version) and **no**
  body, pointer-only internal, or secret.
- [x] 7.3 Published-only filtering logic: given a mix of published/draft/deprecated entries, `list(...)`
  yields only published; `state`/`type`/`q` narrow correctly; `detail(id)` returns a published entry
  and signals not-found for an unknown **and** for a non-published id (same outcome -- no oracle).
- [x] 7.4 Registry-backed `LayerSource` (mocked repo): a selected published id resolves to the ordered
  layer set via the row's `layerSetRef` pointer; bodies are loaded from the resource seam, not the repo;
  a non-published id yields no layer set.

## 8. Tests -- integration (fewer; Testcontainers Postgres, real wiring + module boundary)

> Mirror the existing persistence integration tests under
> `backend/src/test/java/in/agreementmitra/signing/` (Testcontainers Postgres,
> `@Testcontainers(disabledWithoutDocker = true)`; on Windows run gradle directly with
> `TESTCONTAINERS_RYUK_DISABLED=true` per the project memory).

- [x] 8.1 **List endpoint over seeded rows**: seed published + draft + deprecated catalog rows;
  `GET /api/templates` returns only the published ones; `GET /api/templates?state=..&type=..` and
  `?q=..` filter correctly; a draft/deprecated row never appears under any filter.
- [x] 8.2 **Detail endpoint + no oracle**: `GET /api/templates/{id}` returns `200` with dimensions for a
  published id; returns `404` for an unknown id **and** for a draft/deprecated id, indistinguishably
  (RFC 9457, no echoed input).
- [x] 8.3 **Registry-backed resolution**: a picked published template resolves via the registry-backed
  `LayerSource` to the reference layer set, the resolver composes the effective template, and its
  content hash equals the hash produced via the classpath `LayerSource` for the same layer set (only the
  lookup source changed). Bodies are loaded from resources, not Postgres.
- [x] 8.4 **Record template_id on the agreement**: selecting a published template records its
  `template_id` on the agreement (server-sourced, the shared `V8` column, not re-added); a draft without
  a selection keeps `template_id` null; the effective-template hash pin remains unset until
  generate-as-draft.
- [x] 8.5 **Pointer integrity**: every **published** seed row's `layerSetRef` resolves to a present
  classpath layer set (a dangling pointer fails the check, not a later render).
- [x] 8.6 **Flyway migrate + `ddl-auto: validate`** with the catalog migration present (Testcontainers
  Postgres): the migration applies forward-only, `flyway_schema_history` records it, the `template`
  table exists with the expected columns/index, and the `TemplateCatalogEntry` mapping validates against
  it under `ddl-auto: validate`.
- [x] 8.7 Keep **`ModularityTests`** green: the catalog is exposed only through the existing
  `documents.api` named interface (extended with the catalog port + controller); the entity, repository,
  service, and registry-backed `LayerSource` stay package-private in `documents.template`; `signing`
  stores only the template **id** value and gains no dependency on any `documents.template` type.

## 9. Frontend tests (Vitest)

- [x] 9.1 Unit-test the catalog API client (list/detail call the right paths and pass state/type/q) with
  a mocked fetch (`src/api/templateCatalog.test.ts`, 4 tests green). Picker **component test** DONE
  (2026-07-12, under M6): `src/components/TemplatePicker.test.ts` (7 tests) -- lists the seeded rows,
  only the default `(TG, residential)` is selectable (others disabled + "Coming soon"), emits the chosen
  `(state, type)`, no emit on a Coming-soon card, filters by search/state, surfaces a load failure. A
  thin App e2e (`src/App.test.ts`, 2 tests) covers pick -> fill -> preview -> save + Change template.

## 10. Wrap-up

- [x] 10.1 DONE (2026-07-12, review-fix pass): `./gradlew spotlessApply` green. Backend targeted suite
  green with Docker up -- `./gradlew test --tests "in.agreementmitra.documents.*" --tests
  "in.agreementmitra.ModularityTests"` (`TESTCONTAINERS_RYUK_DISABLED=true`,
  `-Duser.timezone=Asia/Kolkata`) BUILD SUCCESSFUL, so the catalog unit tests, the Testcontainers
  integration tests (`TemplateCatalogApiIntegrationTest`, `TemplateCatalogRegistryIntegrationTest`), the
  full `documents.*` suite, and `ModularityTests` all passed. Frontend: `vitest run` (catalog client 4 +
  `TemplatePicker` 7 + `App` 2 = 13 green), `vue-tsc -b` clean (validates the `version: string ->
  number` contract change), `eslint` clean on the changed files. A full multi-module `./gradlew check`
  was intentionally NOT run (avoids unrelated Docker-dependent module tests during concurrent work) --
  run it before archive. Confirmed **no new dependency and no `gradle.lockfile` change** from this CR.
- [x] 10.2 Re-confirm at apply time: assign the migration's **real version number** in apply order
  (nominal `V9`; forward-only, never edit an applied migration); the migration creates the `template`
  table only and does **not** re-add the `agreement.template_id` column (that is
  `template-document-projection`'s `V8`).
- [x] 10.3 Note the standing follow-on CRs per the `document-templating-platform` exploration:
  **authoring / the admin structured builder** (admin-only, with an admin role on `mobile-otp-auth`),
  **moving layer bodies into object storage** (bodies stay classpath resources here, behind the
  `layerSetRef` pointer), and **full version-lifecycle workflow** (`draft -> legal_approved ->
  published -> deprecated`) beyond "only published is selectable".

## 11. Review findings (2026-07-12 — from M4 review; address before archive)

- [x] 11.1 **[Medium] FIXED -- `version` is now numeric end-to-end.** Was a `String`/`TEXT` ordered
  lexicographically, so `findFirstByStatusAndStateAndTypeOrderByVersionDesc` would rank `"9"` above
  `"10"` once a 2nd version published for a `(state,type)`. Fix (2026-07-12): `version` is `INTEGER`
  everywhere -- V8 migration `version INTEGER NOT NULL`; `TemplateCatalogEntry` field/accessor/factory
  `int`; `TemplateSummary`/`TemplateDetail` `int` (reconciles the contract mismatch with the already-int
  `FormSchema.version`); mapper passes the int through; seeder passes `meta().version()` (an `int`)
  directly (dropped `String.valueOf`); repository `ORDER BY ... version DESC` is now genuine numeric
  order. Frontend `templateCatalog.ts` `version: number` (+ its test); the M6 picker/App test mocks
  updated to numeric. Catalog unit + integration tests updated (string literals -> ints). **V8 is
  pre-release (uncommitted), so it was edited in place** -- see the DB-recreate caveat in the report.
- [x] 11.2 **[Medium] DEFERRED (explicit decision) -- caching/ETag descoped to a dedicated CR.** The
  locked "pre-resolved scaffold, cached per version" decision stays unimplemented: `FormSchema.contentHash`
  exists as the intended cache key / HTTP `ETag`, but no server cache or `ETag`/`If-None-Match` (304)
  wiring exists. Not built here because (a) the catalog DTOs carry **no** `contentHash` (it lives on the
  M3-owned `FormSchema`/form controller), so an ETag on the catalog controller is **not** the intended
  "existing content hash" mechanism and would need response-hashing, and (b) the form controller is
  M3-owned and mid-flight. Resolution: tracked as a follow-up in
  `document-templating-platform/exploration.md` (Open questions -> "Caching per version"), with
  `contentHash` -> `ETag`/304 recorded as the intended mechanism. Not a silently-open box.
- [x] 11.3 **[Low] TRACKED ELSEWHERE (no M4 code change).** `agreement.template_id` (M4's V8 column) is
  correct; it is recorded server-side via `Agreement.selectTemplate` / `recordSelectedTemplate`. It stays
  inert in the render because generate-as-draft is **not yet dimension-aware**: confirmed
  `AgreementDocumentService` still renders with default dimensions ("documents resolves (IN, residential)
  when omitted"). The archived `agreement-template-pin` CR landed the reproducibility **pin** columns
  (`template_content_hash`/`template_layer_versions`) but **not** dimension-aware generate -- so closing
  the read/write wiring remains a cross-CR follow-on (dimension-aware generate-as-draft), tracked there,
  not an M4 change.
- [x] 11.4 **[Low] FIXED -- `q` search escapes LIKE metacharacters.** `TemplateCatalogService.likePattern`
  now escapes `\`, `%`, `_` in user input (backslash first) and the repository `@Query` matches with an
  explicit `ESCAPE '\'`, so a user typing `%`/`_` searches for them literally, not as wildcards. Added
  two unit tests proving a literal `%`/`_` and a literal backslash search as literals. Pagination nit
  left as a noted future item (NOT added -- fine for a small curated catalog).
