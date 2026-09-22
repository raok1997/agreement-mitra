# Flow Journal — Document Templating Platform

> **Purpose.** This is the **development-kickoff handoff** for the templating platform. It reviews the
> agreed vision (`exploration.md`) as an end-to-end flow, surfaces the gaps that review exposed, and
> decomposes the work into **independently-startable modules** with frozen contracts — so a fresh
> window can pick any module and build it without re-deriving the whole design.
>
> **How to use.** Read `CLAUDE.md` + `exploration.md` + this file. Freeze the contracts in §4 first.
> Then open a window per module (§3), each scoped by its own OpenSpec CR (`/openspec-propose <slug>`).
> Keep `ModularityTests` green; ship unit + integration tests per the pyramid.

Everything here inherits the three locked decisions in `exploration.md`: **layered composition**,
**YAML to canonical JSON**, and **pre-resolved scaffold + live document render** (single renderer).

---

## 1. End-to-end flow (reviewed)

### 1A. End-user runtime — the golden path

```
  User                    Frontend (Vue)              Backend (documents + signing)          Gotenberg
   |                          |                                |                                 |
   | 1. New agreement         |                                |                                 |
   |------------------------->| 2. GET /templates?state=&type= |                                 |
   |  pick State x Type       |------------------------------->| resolve (state,type)->effective |
   |                          |                                |  template  [CACHED per version] |
   |                          | 3. {templateRef, hash,         |                                 |
   |                          |     formSchema}  <-------------|                                 |
   |                          | 4. render dynamic form from    |                                 |
   |                          |    formSchema (sections/fields)|                                 |
   |                          | 5. POST /agreements/preview    |                                 |
   |                          |    {templateRef, workingSet}   | compile(effective+data)->HTML   |
   |  fill a section --------->|   (debounced on save) ------->|  [LIVE, escaped, no persist]    |
   |  see live preview <------ | 6. HTML pane updates <--------|                                 |
   |                          |                                |                                 |
   |  Download PDF ---------->| POST /preview Accept: pdf ---->| compile->HTML -> render -------->| PDF
   |                          |                                |                                 |
   | 7. Save & continue ----->| POST /agreements (persist)     | pins {templateRef, hash}        |
   |                          | POST /{id}/document (generate) | compile->HTML->Gotenberg->store |
   |                          |                                |  draft_pdf_key (existing path)  |
   |                          |                                |                                 |
   |            ... existing signing flow: STAMPED -> SIGN_REQUESTED -> webhook -> SIGNED ...     |
```

**Key properties (must hold):**
- Steps 2–4 are **data-independent** so the effective template + form schema are **cached** per
  `(state, type, version)`. Picking dimensions is the only trigger.
- Step 5–6 (the filled document) is **data-dependent** so it renders **on the fly**, from the **same
  compiler** that will produce the signed PDF (step 7). No client-side template. Nothing persisted.
- Step 7 is the **only** persistence, and it **pins** the resolved template ref + hash on the
  agreement. Everything downstream (stamp/eSign) is unchanged and never re-resolves.

### 1B. Admin authoring — the trusted path (last, high-risk)

```
  Admin (legal team, mobile-otp-auth + admin role)
   -> Structured builder UI  (sections + clause-library picks + typed variables)
   -> emits a TEMPLATE DEFINITION (YAML)          [never HTML, never code]
   -> validate: JSON-Schema + safe-compile + showWhen-DSL lint
   -> preview (same compiler as end-user)
   -> publish  => IMMUTABLE version in the registry, status: published
   -> becomes the "current" version for its (state, type) => end-users resolve to it
```

### 1C. What is cached vs live (the three layers, restated as a contract)

| Layer | Depends on user data? | Where | Lifetime |
| --- | --- | --- | --- |
| (1) Resolve `(state,type)` to effective template | no | `documents` engine | cached per template **version** (immutable) |
| (2) Effective template to form schema | no | `documents` engine | cached per version |
| (3) Effective template + working data to HTML/PDF | **yes** | `documents` compiler | **live**, never cached, never persisted (until final commit) |

---

## 2. Flow review — gaps & decisions this surfaced

Honest output of walking the flow against the current code. Each is a real design point to settle in
the owning module's CR (not hand-waved):

1. **Where the engine lives = `documents`.** The engine is domain-agnostic (templates + data maps,
   no agreement knowledge). It stays behind `documents`' public interface; `signing` depends only on
   that. New public seams `documents` will expose: **`TemplateCatalog`** (list/resolve),
   **`DocumentCompiler`** (definition/ref + data to HTML), and **`FormSchemaProvider`** (ref to
   form schema). Everything else package-private. `ModularityTests` guards this.

2. **The `renderPdf(templateId, Map)` contract evolves.** Today `templateId` selects one hardcoded
   Thymeleaf file. New model: `(state,type)` to **resolve** to effective definition to **compile**.
   Decision to lock in M2/M4: make **resolve explicit** and return `{ref, hash, formSchema}` (so the
   hash can be pinned and the schema projected), then `compile(ref, dataMap) -> html`, with the PDF
   renderer wrapping `compile + Gotenberg`. The old `renderPdf` becomes a thin adapter during
   migration.

3. **The mapper becomes schema-driven.** `AgreementDocumentMapper.toTemplateData` maps **fixed**
   columns. With template-declared fields + an **attributes store**, the data map must be built from
   *the template's declared fields plus the agreement's stored attributes*. The preview-centric
   single-source/parity rule (persisted vs in-progress mapper) extends here — keep **one** map
   builder. (M5)

4. **Dynamic fields need persistence = attributes store + migration.** New template fields the fixed
   `Agreement` schema lacks (amenities, occupants, lock-in) persist in a flexible
   **agreement-attributes** store (Flyway migration; JPA stays `validate`). Reproducibility: persist
   the **pinned template hash** + the attribute values used. (M5, Phase 2 of preview-centric.)

5. **`showWhen` needs a sandboxed evaluator — security-critical.** A tiny boolean DSL over declared
   fields only; **never SpringEL**. It is its own well-tested, security-reviewed component. Grammar
   is an open question (see exploration). (M2)

6. **Version pinning needs a schema column.** The agreement must store `{templateRef, templateHash,
   layerVersions}`. Migration + FSM-adjacent (pinned at generate/commit). (M5)

7. **New entry step: the State x Type picker.** The current mockup has **no** dimension picker (those
   sections are disabled "Phase 3"). A template-driven flow **requires** picking `(state, type)` as
   step 1 — a new screen/step to design for CR-2. Until CR-2, the single `rental-agreement` template
   is the implicit default. (M6)

8. **Preview endpoint signature extends.** Current `POST /agreements/preview` takes the working set
   only (one template). Template-driven needs `{templateRef, workingSet}` and compiles from the
   resolved template. **Backward-compatible** extension (default ref = the current single template).

9. **Cache invalidation is easy *because* versions are immutable.** Cache resolution keyed by
   **version**; the only moving pointer is "current published version for `(state,type)`" — a cheap
   DB lookup (or short TTL). Publishing a new version never mutates an old cached entry. (M4)

10. **Frontend section-registry is not thrown away.** preview-centric-capture's D4 registry (`{id,
    label, completeness rule, modal, data slice, template binding}`) is the **seam** that becomes
    **schema-driven** at CR-2: the registry stops being hardcoded and is **generated from
    `formSchema`**. Build the registry now (Phase 1), swap its source later. (M6)

---

## 3. Modular development plan

Each module is an **independently-startable unit** with a contract, dependencies, deliverable, tests,
and done-criteria. Map to CRs at the bottom. Backend modules live in `documents` unless noted.

```
  dependency graph (build left-to-right; M0->M1->M2 is the critical path)

     M0 definition        M3 form-schema projection ----\
      model/loader  --->  M1 resolver  ---> M2 compiler   >--- M4 catalog+API ---> M5 signing bind
                                       \       (+showWhen)/                              |
                                        \-----------------/                             |
     M6 frontend (picker + dynamic form + preview) --- consumes M3/M4/preview contract --/
     M7 admin authoring (builder + clause mgmt + lifecycle + admin role)  ---- last
```

### M0 — Template definition model & loader  *(documents)*
- **Owns:** the canonical `TemplateDefinition` types (`meta/fields/clauses/sections`); YAML loader;
  compile-to-canonical-JSON; **JSON-Schema validation** of definitions.
- **Contract out:** `TemplateDefinition` (immutable value types) + `parse(yaml) -> TemplateDefinition`.
- **Depends on:** nothing. Pure, unit-testable in isolation (no Spring, no I/O beyond resource load).
- **Tests:** unit — valid/invalid YAML, schema violations, field-type coverage. (No integration.)
- **Done when:** a fixtures YAML round-trips to a validated canonical JSON; malformed defs rejected.

### M1 — Resolution / composition engine  *(documents)*
- **Owns:** layered-patch composition — `resolve(base, [type, state, state+type]) -> effective
  template`. Fixed precedence; add/replace/remove/reorder; last-wins; **deterministic**.
- **Contract out:** `EffectiveTemplate resolve(dimensions)`; a stable **content hash** of the result.
- **Depends on:** M0.
- **Tests:** unit — precedence, override semantics, determinism (same input, same hash), diamond
  cases. Golden-file fixtures per layer combo.
- **Done when:** `(KA, residential)` and `(KA, commercial)` resolve to distinct, stable effective
  templates from shared base + layers.

### M2 — Compiler + `showWhen` DSL  *(documents)*  — the single renderer
- **Owns:** `compile(effectiveTemplate, dataMap) -> system-owned escaped HTML`; the **sandboxed
  `showWhen` evaluator**; slot interpolation (all values escaped at compile time).
- **Contract out:** `DocumentCompiler.compile(ref|definition, Map data) -> html`. PDF path =
  `compile + Gotenberg` (reuse `GotenbergDocumentRenderer`).
- **Depends on:** M0, M1. Replaces/absorbs `TemplateAssembler`.
- **Tests:** unit — escaping (injection attempts as data render inert), `showWhen` true/false/edge,
  slot binding; **security review of the DSL**. Integration — compile to Gotenberg PDF renders.
- **Done when:** one compiler produces both the live HTML and the PDF; parity test green.

### M3 — Form-schema projection  *(documents)*
- **Owns:** `project(effectiveTemplate) -> FormSchema` (sections + typed fields + required/default/
  options/validation) — the JSON the frontend renders.
- **Contract out:** `FormSchemaProvider.schemaFor(ref) -> FormSchema`.
- **Depends on:** M0, M1. Parallel with M2.
- **Tests:** unit — required/optional/default surfaced; layer overrides reflected.
- **Done when:** the resolved template drives a complete, ordered form schema.

### M4 — Registry + selection API + caching  *(documents + api)*
- **Owns:** template **registry** (Postgres: id/name/category/state/version/status; bodies as
  resources/object-storage, never inline); `GET /api/templates?state=&type=` (list/current);
  resolve endpoint returning `{ref, hash, formSchema}`; **cache** resolution per version.
- **Contract out:** `TemplateCatalog.current(state,type)`, `.resolve(...)`; the REST surface.
- **Depends on:** M1, M3. Migration for the registry table.
- **Tests:** unit — selection/current-version logic. Integration — Testcontainers Postgres; cache
  hit/invalidate on new published version; `ModularityTests`.
- **Done when:** frontend can list templates and resolve one to a form schema + pinnable hash.

### M5 — Agreement to template binding  *(signing)*  — SPLIT (2026-07-12)
M5 as originally drafted (attributes store **+** pinning in one CR) collided with the CR-2 window's
plan. It is split, and the pinning half is **already owned elsewhere**:

- **Pinning -> `agreement-template-pin`** (CR-2 window; `V9__agreement_template_pin.sql`, records
  `template_content_hash` + `template_layer_versions` at generate-as-draft; no new FSM state). This is
  the canonical pin CR -- **not** M5's to build. *(The earlier `agreement-attributes-and-pinning` CR
  was retired/superseded to remove the duplicate -- do not implement it.)*
- **Schema-driven mapper -> already in tree.** `AgreementDocumentMapper` already emits declared field
  keys; template-declared fields the aggregate lacks are filled from the **effective template's
  declared defaults** in the projection service. So no per-agreement persistence is needed today.
- **Attributes store -> DEFERRED (real, but not needed now).** Persisting *user-set* template-specific
  fields (`lockInMonths`, `furnished`, `registrationResponsibility`, ...) is only needed when users can
  **customize** them per agreement -- i.e. **CR-3d custom conditions**. Until then those fields render
  from defaults. **Re-propose attributes-only** (JSONB on `agreement`, migration after V9, no pinning)
  when custom-conditions work starts. Depends on `agreement-template-pin` landing first so attribute
  values snapshot with the pin.

### M6 — Frontend: dimension picker + dynamic form + preview  *(frontend)*
- **Owns:** the **State x Type picker** (new entry step); **schema-driven** section registry
  (generated from `FormSchema`, replacing the hardcoded one); wire preview to `{templateRef,
  workingSet}`. Builds on the preview-centric shell (D4 registry seam).
- **Contract in:** M3 `FormSchema` + M4 selection + `POST /agreements/preview` (extended).
- **Depends on:** M3/M4 **contracts** frozen (§4) — can start against a mock before backend lands.
- **Tests:** component — form generated from a schema fixture; preview refresh on save. E2e (thin) —
  pick dimensions, fill, preview, save.
- **Done when:** the section rail is generated from a real resolved template, not hardcoded.

### M7 — Admin authoring  *(new admin surface)*  — **PARKED (2026-07-12)**, last, high-risk
> **Parked:** its prerequisite `mobile-otp-auth` (admin role) is parked as not-important. M7 is off
> the near-term path — the end-user flow (M0–M6) does not need it. Unpark both together.
- **Owns:** structured builder (emits definitions, never HTML); clause-library CRUD; template
  lifecycle (draft to approved to published to deprecated, immutable versions); **admin role** on
  `mobile-otp-auth`.
- **Depends on:** M0–M5 + `mobile-otp-auth`.
- **Tests:** the full pyramid; authz tests (admin-only); builder-output-is-safe tests.
- **Done when:** the legal team authors + publishes a new state/type template end-to-end, safely.

---

## 4. Contracts to freeze FIRST (so windows work in parallel)

Nail these shapes before splitting work — they are the seams every module binds to. Put them in the
first CR (M0/M4) and treat changes as breaking.

- **`TemplateDefinition`** — canonical JSON of `meta{id,dimensions{state,type},version,status,layers}`
  + `fields[]` + `clauses[]` + `sections[]`. (M0)
- **`EffectiveTemplate` + `templateHash`** — the resolved, pinnable artifact. (M1)
- **`FormSchema`** — `sections[]` of typed `fields[]` (`key,label,type,required,default,options,
  validation,group`) — the frontend data contract. (M3)
- **Selection API** — `GET /api/templates?state=&type=` to list/current; resolve to
  `{templateRef, templateHash, formSchema}`. (M4)
- **Preview API (extended)** — `POST /api/agreements/preview` body `{templateRef, workingSet}`,
  `Accept: text/html | application/pdf`; `no-store`; nothing persisted. (M2/M6)
- **Pin record** — `{templateRef, templateHash, layerVersions}` stored on the agreement at commit.
  (owned by `agreement-template-pin`, not M5)

---

## 5. Sequencing & parallelization

- **Critical path (sequential):** M0 -> M1 -> M2. The engine core.
- **Parallel once M0/M1 land:** M3 (schema) alongside M2 (compiler).
- **M4** after M1+M3. **M5 pinning** = `agreement-template-pin` (after generate-as-draft is
  definition-driven); **M5 attributes store deferred** to CR-3d custom conditions.
- **M6 (frontend)** starts as soon as the §4 contracts are frozen — mock the backend, swap when real.
- **M7 last** (needs the catalog, versioning, and admin auth to exist).
- **Independent of the engine (can proceed now on the single template):** CR-3d custom conditions and
  CR-3e clause library are **escaped-data increments** on the existing `rental-agreement` template +
  preview-centric shell. They do **not** block on M0–M5 and keep delivering value meanwhile.

### CR mapping

| CR (roadmap) | Modules |
| --- | --- |
| CR-3d custom conditions | frontend section knobs + escaped clauses on current template (preview-centric Phase 2) |
| CR-3e clause library | toggle/reorder vetted clauses (still pre-engine) |
| **CR-2 template catalog** | **M0 + M1 + M2 + M3 + M4 + M5 + M6** (the engine; split into sub-CRs by module if large) |
| CR-4 state/language hooks | new **state** (later language) **layers** over M1 + rules-engine stamp/registration |
| Admin authoring | **M7** — **parked** (needs parked `mobile-otp-auth`) |

---

## 6. Kickoff checklist for the new window

1. Read `CLAUDE.md`, `exploration.md`, this journal. Confirm the three locked decisions still hold.
2. Bring up infra: `docker compose up -d` (Postgres + MinIO). **Windows note (see memory):** run
   tests via gradle directly with `TESTCONTAINERS_RYUK_DISABLED=true`; set
   `-Duser.timezone=Asia/Kolkata` or Flyway trips on `Asia/Calcutta`.
3. Pick a module from §3. `/openspec-propose <module-slug>` (e.g. `template-definition-model`).
4. Freeze/consume the §4 contracts. Do not widen a module's reach beyond `documents`' public seams —
   `ModularityTests` will fail if you do.
5. Ship unit **and** integration tests (pyramid). Keep the markup/data boundary: users edit data,
   never markup; `showWhen` is sandboxed, not SpringEL; previews persist/log nothing.
6. Run `./gradlew check` (tests + coverage + security scans) before proposing archive.

---

## 7. Still-open (carried from exploration, decide in the owning CR)

- **Clause library storage** — Postgres rows vs versioned resource files; jurisdiction scoping vs
  layer precedence. (M4 blocks on this.)
- **`showWhen` DSL grammar** — operators/membership; where evaluated. (M2 blocks on this.)
- **Admin role model** — how the role rides on `mobile-otp-auth`. (M7 — **parked**; revisit on unpark.)
- **Which states first** — go-to-market: Hyderabad to Telangana/AP. Language stays English until
  Telugu/bilingual is actually needed. (CR-4 fixtures.)

---

## 8. M6 kickoff addendum (2026-07-12) -- contract reconciliation + integration review

> Written at the start of the M6 frontend build. It reconciles this journal against the code that
> actually landed while M0-M5 were built in the documents window, records two locked frontend
> decisions, and reviews M6's end-to-end integration against every module it binds to. The two
> existing frontend CRs remain the source of truth for the work items -- this section is the map,
> not a new CR. (Written in ASCII on purpose: the local PII/secret edit guard fails closed on
> em-dash/arrows/curly quotes under Windows Git Bash.)

### 8.1 Inventory snapshot (what M6 already has vs. needs)

The preview-centric shell is already schema-fed and ~70% of M6:
- `frontend/src/views/CaptureForm.vue` -- two-pane shell, section rail/modals/widgets generated from a
  fetched `FormSchema`, completeness bar, focus-trap+Esc, localStorage draft (TTL + clear-on-save),
  mobile Sections<->Preview toggle.
- `frontend/src/views/formModel.ts` -- pure schema->registry + client-validation helpers (unit-tested).
- `frontend/src/components/widgets/*` -- text/textarea/number/money/date/checkbox/select + dispatcher.
- `frontend/src/api/templateForm.ts` (M3 client), `templateCatalog.ts` (M4 client), `preview.ts` (stale).

Remaining for "done": (a) the State x Type picker entry step (today the shell hardcodes
`DEFAULT_STATE="TG"` / `DEFAULT_TYPE="residential"`); (b) rewire the preview client off a dead
endpoint; (c) carry the M5 persistence gap as a marked stopgap; (d) keep carrying `showWhen`.

### 8.2 CONTRACT CORRECTION -- preview endpoint moved (supersedes this journal's section 4)

Section 4 froze the preview API as `POST /api/agreements/preview` with a `{templateRef, workingSet}`
body (nested `signers[]`). That is now STALE. While the engine landed, the increments superseding
`template-document-projection` (`document-projection-render` + `agreement-template-pin`) moved the
stateless preview into the `documents` module:

- Live endpoint: `POST /api/templates/document/preview` (`DocumentProjectionController`),
  content-negotiated `text/html | application/pdf`, `Cache-Control: no-store`, iframe-safe CSP on the
  HTML variant. Body is `DocumentProjectionRequest { dimensions{state,type}?, data: Map<key,value> }`
  -- a flat field-key data map, NOT a nested `signers[]` object.
- `POST /api/agreements/preview` no longer exists on the backend and is not in `SecurityConfig`'s
  permit set. The current shell's live pane + Download PDF (via `src/api/preview.ts`) therefore call a
  dead route and are broken against HEAD.

Fixing this is exactly what the (unapplied) `document-capture-shell-wiring` CR specifies. Read
section 4's line item as: Preview API = `POST /api/templates/document/preview`, body `{dimensions, data}`.

### 8.3 Locked frontend decisions (2026-07-12)

1. Picker as entry step via a lightweight view-switch, not vue-router. No router/pinia is installed;
   adding one widens the frontend OSV surface (`package-lock.json`). `App.vue` holds a `selection` ref:
   render the picker first; on pick, mount `CaptureForm` with `state`/`type` props that replace the
   hardcoded defaults. A "change template" affordance returns to the picker and clears the localStorage
   draft.
2. Rewire to the real endpoint. Retire `src/api/preview.ts`; add `postDocumentPreview(data, accept)` ->
   `POST /api/templates/document/preview`. `buildPreviewInput()`'s well-known-key remapping is deleted
   in favour of the flat `data` map the form projection already drives.

### 8.4 Integration review by module

| Module | Seam M6 binds to | Status on disk | Integration verdict |
| --- | --- | --- | --- |
| M1 resolution | `(state,type)` -> effective template + hash (transitive) | applied | OK -- M6 must pass the SAME `(state,type)` to BOTH the form fetch and the preview request so they resolve one effective template. Divergent dimensions = divergent form vs. document. |
| M2 compiler + showWhen | `POST /api/templates/document/preview` (HTML/PDF) | applied | OK after the 8.2 rewire. `showWhen` is carried verbatim and unevaluated on `FormField` (backend confirms) -- M6 carries it identically; no evaluator (awaits the M2 DSL). |
| M3 form projection | `GET /api/templates/form` -> `FormSchema` | applied | OK -- `templateForm.ts` mirrors `FormSchema`/`FormSection`/`FormField`(+`Validation`) field-for-field (verified). `default` <- JSON `"default"`; `min`/`max` are integer bounds. |
| M4 catalog | `GET /api/templates` + `/{id}` | applied (backend); picker view deferred | OK -- `templateCatalog.ts` mirrors `TemplateSummary`/`TemplateDetail`. `TemplateSummary` already carries `state`+`type`, so the picker can carry dimensions into capture without a detail round-trip. This is the deferred `template-catalog` task 6.2, now unblocked. |
| M5 attributes + pinning | agreement persistence of dynamic fields | PROPOSAL ONLY, no code | GAP (expected). Save & continue stays the stopgap: generic working set -> fixed `CreateAgreementRequest` columns; template-declared dynamic fields are dropped on save. Marked pending-M5; do not invent an attribute submit path. |

### 8.5 Top end-to-end risk -- preview/draft parity for non-default templates

The live preview and the saved signable draft render through DIFFERENT paths today:
- Live preview: `/api/templates/document/preview` -> `DocumentProjectionApi` (the M2 schema-driven
  compiler, resolves the picked `(state,type)`).
- Saved draft: `POST /api/agreements/{id}/document` -> `AgreementDocumentService.renderPreview` (the
  fixed-getter mapper; not dimension-aware, not schema-driven; does not pin).

For the reference `(TG, residential)` default with only core fields, they agree. For any other
`(state,type)` or any dynamic field, the document a user previews is NOT the document that gets stored
and signed -- a break of the "what you preview is what you sign" parity guarantee. M6 cannot close
this; it is exactly M5's single-sourced schema-driven mapper + generate-as-draft pin. Until M5 lands,
the picker should not imply full parity for non-default templates. (Confirm the current
`AgreementDocumentService` render path with the documents window before archiving M6.)

### 8.6 Ownership of the remaining M6 work (no new CR)

- `document-capture-shell-wiring` (all tasks open) -- owns 8.2: new `postDocumentPreview` client,
  rewire live pane + Download PDF, delete `preview.ts`, tests.
- `template-catalog` task 6.2 (+ deferred component test 9.1) -- owns the picker view + carrying
  `(state,type)` into capture (the shell it was blocked on now exists).
- `agreement-attributes-and-pinning` (M5) -- owns 8.4's gap and 8.5's parity fix; M6 only marks the
  swap point.
</content>
