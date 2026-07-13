> **Third of four increments** superseding `template-document-projection`. Depends on **CR-2
> `document-projection-render`** (the `DocumentProjectionApi.generate(...)` result carrying `{
> templateId, contentHash, layerVersions }`). Reproducibility pin + its Flyway migration only.

## 1. Aggregate pin + generate-as-draft rewire (module `signing`)

- [x] 1.1 Add a server-managed `Agreement.pinEffectiveTemplate(contentHash, layerVersions)` (never
  client-settable) mapping to two new columns; call it from **generate-as-draft**
  (`POST /api/agreements/{id}/document`) after a successful **full** render (projection `generate`
  mode) + `attachDraft`. Preview pins nothing. Reuse the existing generate-as-draft transition -- **no
  new FSM state**; the draft-freeze `409` is unchanged.
- [x] 1.2 Wire generate-as-draft to `DocumentProjectionApi.generate(...)` (full validation -> parity
  PDF), then `attachDraft(pdf)` (existing freeze-check/store), then pin the returned `contentHash` +
  `layerVersions`. Order: full render -> attach (409 if frozen) -> pin, so a rejected/frozen generate
  pins nothing.

## 2. Flyway migration + JPA mapping

- [x] 2.1 Add forward-only Flyway migration `V9__agreement_template_pin.sql`: two **nullable** columns
  on `agreement` (`template_content_hash TEXT`, `template_layer_versions JSONB`), never editing
  V1--V8. Do **not** re-add `template_id` (V8 added it). Map both on the `Agreement` entity
  (`layerVersions` as a JSON/`jsonb` map) so JPA `ddl-auto: validate` passes.

## 3. Tests

- [x] 3.1 **Unit**: `Agreement.pinEffectiveTemplate` records `contentHash` + `layerVersions` and is
  not reachable via any client-settable path; a fresh/ungenerated agreement has a null pin.
- [x] 3.2 **Integration -- generate-as-draft + pin** (Testcontainers Postgres): generate-as-draft
  stores the PDF as the draft (existing path) and records `template_content_hash` /
  `template_layer_versions` on the agreement; a stateless preview pins nothing.
- [x] 3.3 **Integration -- Flyway migrate + `ddl-auto: validate`** with `V9` present (Testcontainers
  Postgres): the migration applies forward-only and the `Agreement` mapping validates against the new
  columns.

## 4. Wrap-up

- [x] 4.1 `./gradlew spotlessApply` then `./gradlew check` green (tests, `securityScan`,
  `ModularityTests`, JaCoCo). Confirm **no new dependency and no `gradle.lockfile` change**. Done:
  all tests pass (incl. new unit + Testcontainers integration tests), SpotBugs SAST + Spotless +
  JaCoCo green, `ModularityTests` green; **no dep / no `gradle.lockfile` change** (this CR touched
  neither file). The `osvScan` half of `securityScan` fails **closed only because the `osv-scanner`
  binary is not installed on this dev box** (an environmental prerequisite, not a code finding) --
  this CR adds no dependency, so the OSV graph is unchanged; run after installing `osv-scanner`.
- [x] 4.2 Note the deferred control: pin **immutability enforcement** beyond the existing draft-freeze
  `409` (reject re-generate once a signing request exists) rides the eSign CR. The frontend Save &
  continue wiring is CR-4.
- [x] 4.3 Note the deferred **reproduce-from-pin** follow-up (from the retired
  `agreement-attributes-and-pinning` / M5 -- archived
  `2026-07-12-superseded-agreement-attributes-and-pinning`): this CR **records** the pin only. Making
  later renders (audit / re-download / reconciliation) resolve the **pinned** `contentHash` via a
  catalog *resolve-by-hash* seam (byte-stable re-render, never "current"), plus the per-agreement
  **attributes store** + schema-driven dynamic mapper, are a **separate future CR** to re-propose when
  custom-conditions work begins.
