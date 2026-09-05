> **Prerequisite:** this change consumes the `EffectiveTemplate` from `template-resolution-engine` and,
> transitively, the `Field` / `FieldType` / `FieldValidation` / `Section` records from
> `template-definition-model`. Apply **`template-definition-model`** then **`template-resolution-engine`**
> first; reuse their records (do not fork them).

## 1. FormSchema DTO + public API surface (package `in.agreementmitra.documents.api`, `@NamedInterface`)

- [x] 1.1 Add a `package-info.java` for `in.agreementmitra.documents.api` declaring
  `@org.springframework.modulith.NamedInterface("api")` (mirror `in.agreementmitra.signing.api`) so
  this becomes the `documents` module's first public surface.
- [x] 1.2 Add the immutable **public** `FormSchema` record tree: `FormSchema`
  (`dimensions{state,type}`, `templateId`, `version`, `contentHash`, `List<FormSection>`),
  `FormSection` (`title`, `List<FormField>`), and `FormField` (`key`, `label`, `widget`, `type`,
  `required`, `default?`, `options?`, `group?`, `validation{min?,max?,minLength?,maxLength?,pattern?}`,
  `showWhen?`). Records with defensive copies of list fields; JSON-serializable via the existing
  Jackson (no new dependency).
- [x] 1.3 Add the **public** `TemplateFormApi` port: `FormSchema formFor(String state, String type)`.
  Document that an unknown/unresolvable `(state, type)` raises the app-wide `ResourceNotFoundException`
  (mapped to 404 by the root `GlobalExceptionHandler`, whose detail never echoes the input).

## 2. FormProjector + port implementation (package `in.agreementmitra.documents.template`, package-private)

- [x] 2.1 Add a package-private `FormProjector` co-located with the definition records so **no record
  visibility is widened**: `project(EffectiveTemplate) -> FormSchema`. Pure and data-independent -- no
  clock/IO/random/user-data input; evaluates no `showWhen`.
- [x] 2.2 Implement the closed `FieldType -> widget` mapping as an exhaustive switch: `text->text`,
  `longtext->textarea`, `int->number`, `money->money`, `date->date`, `bool->checkbox`, `enum->select`.
- [x] 2.3 Build sections from the effective template's `Section` list in order; within each section
  keep field-key entries as `FormField`s in authored order and **skip clause-id entries**. Project each
  `Field` -> `FormField` carrying `required`, `type`, `default`, `options` (enum only), `group`, and the
  `FieldValidation` bounds; carry any `showWhen` through verbatim as opaque metadata (unevaluated).
- [x] 2.4 Add a package-private `TemplateFormApi` implementation (a `@Service` implementing the public
  port): resolve `(state, type)` via the resolution engine's resolver/`LayerSource`, project the
  `EffectiveTemplate`, and return the `FormSchema`; translate an unresolvable `(state, type)` into
  `ResourceNotFoundException`. Constructor injection; no entity/internal type crosses to the caller.

## 3. HTTP surface (package `in.agreementmitra.documents.api`)

- [x] 3.1 Add `TemplateFormController`: `GET /api/templates/form` with required `state` and `type`
  query params; call `TemplateFormApi.formFor`, return `200` with the `FormSchema` JSON, set a strong
  `ETag` equal to the effective template's `contentHash`, and a `Cache-Control: public, max-age=<small>`
  (cacheable per version -- no `no-store`, the body has no PII). Support conditional `GET`: an
  `If-None-Match` equal to the current hash returns `304`.
- [x] 3.2 Wire the endpoint into `SecurityConfig` as a public read alongside the other anonymous read
  endpoints (system-owned metadata, no PII, no auth). Do not touch any authenticated matcher, the
  signing permit, or the webhook permit.
- [x] 3.3 Confirm an unknown/unresolvable `(state, type)` returns `404` via the existing
  `ResourceNotFoundException` -> RFC 9457 path, and that the response echoes neither `state` nor `type`.

## 4. Frontend -- schema-driven form renderer (`frontend/src`)

- [x] 4.1 Add a form-schema API client in `src/api/` (`getTemplateForm(state, type)`) that calls
  `GET /api/templates/form` and returns the typed `FormSchema`; keep all API calls in `src/api/` per
  conventions.
- [x] 4.2 Add one widget component per `widget` value (`text`, `textarea`, `number`, `money`, `date`,
  `checkbox`, `select`) using `<script setup>` + Tailwind; each binds its value and surfaces its
  field's validation state.
- [x] 4.3 Replace the **hardcoded section registry** in the `preview-centric-capture` shell with a
  **schema-fed** one: render sections + fields from the fetched `FormSchema` into the existing
  section-modal shell; derive each section's completeness from its `required` fields; wire per-field
  client validation from the schema metadata (required/type/min-max/minLength-maxLength/pattern/enum
  options). Leave the live-preview pane, stateless preview endpoint, completeness bar, and Save &
  continue persistence unchanged.

## 5. Tests -- unit (many, fast; no Spring context, no I/O)

- [x] 5.1 `FormProjector` structure: an effective template projects to a FormSchema preserving section
  order and within-section field order; clause-id entries are excluded from `fields`; dimensions, id,
  version, and content hash are carried.
- [x] 5.2 `FieldType -> widget` mapping is exhaustive and correct for all seven types (a mapping test
  that fails loudly if a new `FieldType` is added without a widget case).
- [x] 5.3 Validation-metadata projection: numeric `min`/`max`, text `minLength`/`maxLength`/`pattern`,
  `enum` `options`, `required`, and a type-typed `default` all reach the corresponding `FormField`
  verbatim.
- [x] 5.4 Determinism + data-independence: repeat projection of the same effective template yields an
  equal FormSchema; the projector takes no user data and evaluates no `showWhen` (a `showWhen`-bearing
  clause is carried opaque, and no field is marked hidden/shown).
- [x] 5.5 No-user-data / immutability: a projected FormSchema contains only metadata (keys, labels,
  widgets, types, defaults, validation, options, groups, dimensions) and is immutable (list mutation
  attempts fail / have no effect).
- [x] 5.6 Frontend (Vitest): the `src/api/` client calls the right path with `state`/`type`; a small
  schema renders the expected widgets and enforces `required`/bounds/`options` client-side; a `404` is
  surfaced as an error without leaking the requested dimensions.

## 6. Tests -- integration (fewer; real wiring + module boundary)

- [x] 6.1 MockMvc/slice test of `GET /api/templates/form`: for the reference layer set, resolve ->
  project -> serialize, asserting `200`, the JSON FormSchema reflects the resolved fields/sections/
  widgets/validation, and the `ETag` equals the effective-template content hash. Assert a matching
  `If-None-Match` returns `304`, and an unknown `(state, type)` returns `404` without echoing input.
- [x] 6.2 Cache-per-version behavior: after a content-bearing change to a contributing layer, the
  endpoint's `ETag` for that `(state, type)` changes (stale schema not served). (May be exercised with a
  test `LayerSource` / fixture layer set.)
- [x] 6.3 Keep `ModularityTests` green: the new `documents.api` named interface is the only new public
  surface; the `FormProjector` and port implementation stay package-private in `documents.template`; no
  new disallowed cross-module dependency is introduced.

## 7. Wrap-up

- [~] 7.1 `./gradlew spotlessApply` then `./gradlew check` (tests, `securityScan`, `ModularityTests`,
  JaCoCo gate) all green; `npm run lint` + `npm run test` (frontend) green. Confirm **no new dependency
  and no `gradle.lockfile` change** (Jackson is already present).
  - Frontend: `npm run test` (Vitest, 30/30 green incl. the new schema-client, widget/renderer, and
    client-validation suites), `npm run lint` (clean for all new files; the only 2 errors are the
    pre-existing `scripts/security-scan.mjs` `no-undef`, out of scope), and `npm run build:only`
    (`vue-tsc -b && vite build`) green. **No dependency change** (frontend or backend `gradle.lockfile`).
  - Backend: this CR is **frontend-only** (backend `FormProjector` / `TemplateFormApi` /
    `TemplateFormController` / `documents.api` DTOs already landed and green), so 7.1 ran the targeted
    regression `spotlessApply test --tests "in.agreementmitra.documents.*" --tests "*Modularity*"`
    rather than a full `./gradlew check`. **Owed** (gate deferral, matching sibling CRs): the full
    `./gradlew check` with `securityScan` (backend OSV + SpotBugs/FindSecBugs) and the frontend
    `npm run security:scan` (OSV over the npm lockfile) -- no new dependency was added, so the OSV
    surface is unchanged, but the gates were not re-run here.
- [x] 7.2 Note that this CR ships only **client-side** validation metadata and the **data-independent**
  form projection; **server-side validation of submitted data**, **document rendering / PDF**,
  **`showWhen` evaluation with real data**, **draft persistence**, the **template catalog**, and the
  **admin builder** remain named follow-on CRs per the `document-templating-platform` exploration.
  - Frontend integration note: the schema-fed shell drives the generic capture form (sections /
    fields / widgets / validation / completeness) from `GET /api/templates/form`, resolved for a single
    named default `(state, type)` = `TG`/`residential` (the reference layer set's fully-composed pair;
    constants `DEFAULT_STATE`/`DEFAULT_TYPE` in `CaptureForm.vue`). **Wiring these to a catalog picker's
    selection is a follow-up** (template-catalog CR). The live-preview pane and Save & continue paths
    are bridged from the generic working-set into the existing typed preview / create endpoints by
    well-known field keys as a documented **stopgap**; the general schema-driven submit/preview mapping
    (arbitrary fields -> a rendered/persisted agreement) is the **document-projection submit path**
    (named follow-on).
