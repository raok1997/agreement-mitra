# Flow Journal -- Agreement Document Format

> **Purpose.** This is the decomposition + kickoff handoff for making the rental-agreement preview +
> PDF match the reference artifact (`claude.ai/code/artifact/fd23f28f-cdcb-4883-83ca-ac966285c12a`)
> and for moving mandatory/optional, section layout, and the document header out of code and into the
> **template definition**. It reviews the target as an end-to-end flow, records the locked decisions,
> and decomposes the work into **independently-startable modules, each scoped by its own OpenSpec CR**,
> with frozen contracts and done-criteria -- so any CR can be picked up and built without re-deriving
> the whole design.
>
> **How to use.** Read `CLAUDE.md` + this journal. Freeze the contracts in section 4 first. Then open
> a CR per module (section 3), build it, ship unit + integration tests (the pyramid), keep
> `ModularityTests` green, and mark the CR closed in the tracking table (section 5). This umbrella is
> an `exploration`-kind change: it is the map, not an implementable change -- the sub-CRs carry the
> code. (Written in ASCII on purpose: the local PII/secret edit guard fails closed on
> em-dash/arrows/curly quotes under Windows Git Bash.)

This builds directly on the just-landed production rental layer set
(`backend/src/main/resources/documents/template/sets/rental/`, National `IN` + Telangana `TG`) and the
existing `template-document-projection` compiler (one system-owned compiler, byte-for-byte parity
between the live preview and the signed PDF).

---

## 1. Target end-to-end flow

```
  User                         Frontend (Vue)                     Backend (documents)
   |                              |                                   |
   | pick State x Type            | GET /api/templates/form           | resolve(state,type)->effective
   |----------------------------->|---------------------------------->|  (carries meta.document,
   |                              |  FormSchema {sections[] with       |   section optional+renderKind)
   |                              |   optional, renderKind}            |
   | left panel:                  |<----------------------------------|
   |  MANDATORY sections shown,   |                                   |
   |  OPTIONAL sections in an      |                                   |
   |  "Add optional" catalog       |                                   |
   |                              |                                   |
   | fill a section / add optional| POST /api/templates/document/     | compile(effective, data,
   |----------------------------->|   preview {dimensions, data,      |   activeSections, execDate)
   |  live preview updates <-------|   activeSections}  -------------->|  -> escaped HTML (artifact
   |                              |                                   |     layout: header, party
   |  Download PDF -------------->|  Accept: application/pdf --------->|     cards, terms table,
   |                              |                                   |     witnesseth list) -> PDF
   |                              |                                   |
   | Save & continue (HARD-BLOCKED until every MANDATORY section is complete)
```

**Key properties (must hold):**
- The document layout (header, party cards, Terms-of-Tenancy table, "Now This Agreement Witnesseth"
  list, annexure, margins, serif body + Noto for Indic) is produced by the **one** system-owned
  compiler, so preview == signed PDF (parity preserved).
- Mandatory/optional, section render style, and the document header are **declared in the template**,
  never hardcoded in the compiler or the frontend.
- An **optional** section contributes nothing to the document until the user **adds** it
  (`activeSections`); mandatory sections always render.
- The compiler stays a **pure function** of its inputs -- the execution-date fallback is resolved to a
  concrete date at the projection layer and passed in.

---

## 2. Locked decisions (do not relitigate)

Confirmed with the requester (2026-07-13):

1. **Optional = opt-in add-on catalog** (match artifact). Optional sections/clauses are absent from the
   document until explicitly added from a left-panel catalog; once added they render in preview + PDF
   even if partially filled. Mandatory sections always render.
2. **Full artifact document layout** in the system-owned `TemplateCompiler`, for preview + PDF and for
   BOTH National + Telangana: centred header (title + subtitle + execution line), separate Owner and
   Tenant party cards, "Schedule of Property", a "Terms of Tenancy" key/value table, a single numbered
   "Now This Agreement Witnesseth" clause list, an optional annexure, then the signature block. Serif
   Latin body, bundled Noto faces kept for Indic shaping. Real page margins (CSS `@page` + body
   padding). The compiler learns each section's layout from a template-declared **render kind**.
3. **Header configurable in the template**: a `meta.document` block (title, subtitle, execution-line
   text). National vs Telangana can differ. Carried by loader + validator + `CanonicalJson` + resolver;
   covered by the content hash.
4. **Execution date**: use the `agreementDate` field value if present; else the current system date
   (SYSDATE) at render time. Injected `Clock` at the projection-service layer, resolved to a date value
   passed into the compiler (compiler stays pure). Determinism trade-off noted below.

Unambiguous consequences (also locked): split the single "Parties" section into **Owner** and
**Tenant**; mandatory set = Owner, Tenant, Property, Term, Financial; the rest optional; the frontend
**hard-blocks** "Save & continue" until every mandatory section is complete; and the Telangana template
gains the artifact's full optional add-on catalog (rent escalation, security-deposit terms,
late-payment penalty, maintenance charges, utilities split, lock-in, notice period, permitted
occupants, pets, parking, furnishing, fixtures/inventory annexure, dispute resolution, custom clause).

---

## 3. Modular development plan

Each module is an independently-startable unit with a contract, dependencies, deliverable, tests, and
done-criteria. Backend modules live in `documents` unless noted. Map to CRs in section 5.

```
  dependency graph (build left-to-right; M0->M1->M2->M4 is the critical path)

     M0 schema/semantics ---> M1 compiler layout ---> M2 opt-in optional ---> M4 capture UX
                        \                                            /
                         \---> M3 form projection -----------------/
                          \--> M5 rental content (verify visually once M1 lands)
```

### M0 -- Template document + section semantics (schema)  *(documents)*
- **Owns:** the declarative additions -- `meta.document {title, subtitle, executionLine}`; per-section
  `optional` (bool, default false) and `render` kind (`parties | keyvalue | clauses | annexure`,
  default `keyvalue`). Updates `template-definition.schema.json` + `layer-patch.schema.json`
  (so `addSection`/`replaceSection` carry the new section fields), the `Meta`/`Section` records,
  `TemplateDefinitionLoader`, `TemplateDefinitionValidator`, and `CanonicalJson` + the resolver so the
  content hash changes deterministically with the new fields.
- **Contract out:** `Meta.document`, `Section.optional`, `Section.renderKind` on the effective template.
- **Depends on:** nothing (pure). No Spring, no I/O beyond resource load.
- **Tests:** unit -- loader parses new fields; validator accepts/rejects (unknown render kind rejected;
  `document` slots resolve to declared fields; optional default false); canonicalizer includes new
  fields and the hash changes deterministically; existing invariants unchanged.
- **Done when:** a template declaring a `meta.document` block, an optional section, and each render kind
  round-trips through load + validate + canonical hash, and the reference/production sets still resolve.

### M1 -- Document artifact layout compiler  *(documents)*
- **Owns:** `TemplateCompiler` reworked to emit the artifact layout: a centred header from
  `meta.document` (title, subtitle, execution line with `{{slot}}` fills), section bodies dispatched by
  `render` kind (parties -> party card; keyvalue -> the current label/value table; clauses -> a numbered
  list; annexure -> a bulleted annexure), serif Latin body font with the bundled Noto faces kept for
  Indic, and page margins (CSS `@page` + body padding) on both preview and PDF. Execution date resolved
  from `agreementDate` else SYSDATE via an injected `Clock` at `DocumentProjectionService`, passed into
  `compile` as a value.
- **Contract out:** unchanged compile signature except the resolved execution date is an input; HTML is
  still system-owned + fully escaped.
- **Depends on:** M0.
- **Tests:** unit -- each render kind emits its expected escaped structure; header renders from
  `meta.document`; execution date uses `agreementDate` when present and the injected clock's date when
  blank; injection-as-data stays inert; margins present. Integration -- resolve+compile IN and TG
  produce the artifact layout; compile->Gotenberg PDF renders (parity).
- **Done when:** the National + Telangana documents render with the artifact header, Owner/Tenant party
  cards, a Terms-of-Tenancy table, a Witnesseth clause list, margins, and serif body -- preview and PDF
  identical.

### M2 -- Opt-in optional sections (projection)  *(documents + api)*
- **Owns:** `DocumentProjectionRequest` gains `activeSections` (the optional section titles the user
  added); the compiler renders a section iff it is mandatory OR present in `activeSections`; an unknown
  title is ignored. Defined for both faces: PREVIEW takes `activeSections` from the request; GENERATE's
  active-set is documented as deferred (save/sign wiring is out of scope -- generate renders mandatory
  sections plus any it is given, default none).
- **Contract out:** `DocumentProjectionRequest {dimensions?, data, activeSections}` on `documents.api`
  (additive).
- **Depends on:** M0, M1.
- **Tests:** unit -- an optional section absent from `activeSections` contributes no header/fields/
  clauses; added, it renders; mandatory always renders; unknown title ignored (no error). Integration
  -- preview with/without an active optional section over the TG set differs exactly by that section.
- **Done when:** optional sections are invisible in preview + PDF until added, mandatory always present,
  and the API shape is frozen for both faces.

### M3 -- Form-schema section semantics  *(documents)*
- **Owns:** `FormProjector` + `FormSection` DTO expose each section's `optional` + `render` kind so the
  client can mark Mandatory/Optional and build the add-optional catalog. Rule: a section that projects
  to **zero fields** (a clause-only / document-only section, e.g. the Witnesseth list) is **omitted**
  from the `FormSchema` -- it is document structure, not a capture step.
- **Contract out:** `FormSection {title, fields, optional, renderKind}`; field-less sections omitted.
- **Depends on:** M0. Parallel with M1/M2.
- **Tests:** unit -- optional + renderKind surfaced; a field-less section is omitted; a mandatory
  field-bearing section is present and marked mandatory.
- **Done when:** the `FormSchema` carries optionality + render kind and drops document-only sections.

### M4 -- Capture UX: mandatory/optional + opt-in catalog + forced save  *(frontend)*
- **Owns:** `CaptureForm.vue` + section-rail components mark each left-panel section Mandatory vs
  Optional; optional sections appear in an **Add-optional catalog** and are **absent from the preview
  until added**; `documentPreview.ts` sends `activeSections`; `templateForm.ts` types gain
  `optional` + `renderKind`; **"Save & continue" is hard-blocked** until every mandatory section is
  complete (with a clear "complete N more required section(s)" affordance).
- **Contract in:** M2 (`activeSections` on the preview request) + M3 (`FormSchema` section semantics).
- **Depends on:** M2, M3 contracts frozen (section 4) -- can start against a mock, swap when real.
- **Tests:** component -- rail marks mandatory/optional from a schema fixture; adding an optional
  section adds its content to the preview request + pane; Save disabled until mandatory complete. Thin
  e2e -- pick dimensions, fill mandatory, add one optional, preview reflects it, Save enables.
- **Done when:** the rail is driven by the schema's optionality, optional content is opt-in end to end,
  and Save is blocked until mandatory sections are complete.

### M5 -- Production rental document content  *(documents resources)*
- **Owns:** the `sets/rental/` layer set content: split "Parties" into **Owner** + **Tenant** sections
  (render kind `parties`); mark Owner/Tenant/Property/Term/Financial mandatory and the rest optional;
  add a `meta.document` header (National vs Telangana wording); tag each section's render kind
  (Financial/Term -> keyvalue "Terms of Tenancy"; a document-only "Now This Agreement Witnesseth"
  clauses section; a "Schedule of Property" section; an optional fixtures/inventory annexure); expand
  the Telangana optional add-on catalog to the artifact's items, each an individually-addable optional
  section/clause.
- **Contract in:** M0 schema.
- **Depends on:** M0 (schema). Visually verified once M1 lands; can be authored in parallel.
- **Tests:** unit/integration -- the set resolves + compiles for IN and TG with the new sections +
  header; mandatory vs optional flags correct; optional add-ons gated. Keep `ProductionRentalLayerSetTest`
  green (or update deliberately).
- **Done when:** the National + Telangana documents read like the artifact and every optional item is
  individually addable.

---

## 4. Contracts to freeze FIRST (so CRs can proceed in parallel)

Nail these shapes in M0/M2/M3 before splitting work; treat later changes as breaking.

- **`Meta.document`** -- `{ title: string, subtitle: string, executionLine: string }`. `executionLine`
  is system-authored text with `{{slot}}` fills (e.g. `{{agreementDate}}`), HTML-escaped at compile.
  (M0)
- **`Section`** -- `{ title, entries[], optional: bool = false, render: parties|keyvalue|clauses|annexure = keyvalue }`. (M0)
- **Execution date** -- resolved at `DocumentProjectionService`: `agreementDate` value if present, else
  `clock` today; passed into `compile` as a concrete date. Reserved binding key documented in M1. (M1)
- **`DocumentProjectionRequest`** -- `{ dimensions?{state,type}, data: Map<key,value>, activeSections: string[] }`.
  `activeSections` = added optional section titles; mandatory sections always render; unknown titles
  ignored. (M2)
- **`FormSection`** -- `{ title, fields[], optional: bool, renderKind: string }`; field-less sections
  omitted from `FormSchema`. (M3)

---

## 5. CR mapping + closure tracking

Each module becomes one OpenSpec CR. Status legend: `planned` (journal only) -> `proposed`
(artifacts written) -> `applied` (code landed + tests green) -> `archived` (closed via
`/opsx:archive`). Update this table as each CR closes.

| CR slug | Module | Covers requester items | Depends on | Status |
| --- | --- | --- | --- | --- |
| `template-document-metadata` | M0 | #7 (config in templates), part of #2/#3/#5/#6 | -- | applied |
| `document-artifact-layout` | M1 | #1 margins, #2 header, #3 fonts/sections, #4 Owner/Tenant blocks | M0 | applied |
| `optional-section-opt-in` | M2 | #6 optional-only-when-added | M0, M1 | applied |
| `form-schema-section-semantics` | M3 | #5 mandatory/optional markings (data) | M0 | applied |
| `capture-mandatory-optional-ux` | M4 | #5 force-validate mandatory, #6/#8 opt-in add catalog | M2, M3 | applied |
| `rental-document-content-v2` | M5 | #4 split parties, #5/#6 marks, #8 Telangana optional catalog | M0 | applied |

> Status log:
> - 2026-07-13: all six CRs authored (proposal + design + tasks + spec deltas); status `planned` -> `proposed`.
> - 2026-07-13: M0 `template-document-metadata` implemented + green (schema JSONs, `Meta.document` /
>   `Section.optional` / `Section.render` records, `RenderKind` + `DocumentMeta`, binder/loader/validator/
>   resolver carry-through; unit + existing documents suite + `ModularityTests` pass) -> `applied`. The
>   section-4 contracts `Meta.document` and `Section { optional, render }` are now frozen for M1/M2/M3/M5.
> - 2026-07-13: M1 `document-artifact-layout` implemented + green (TemplateCompiler render-kind dispatch
>   + meta.document header + serif/Noto + @page/body margins; DocumentProjectionService injected Clock
>   resolving execution date agreementDate-else-SYSDATE on the single compile path; full backend suite
>   369 tests + ModularityTests pass) -> `applied`.
> - 2026-07-13: M2 `optional-section-opt-in` implemented + green (DocumentProjectionRequest.activeSections
>   additive; TemplateCompiler renders a section iff mandatory or title in the active-set, unknown titles
>   ignored; PREVIEW drives from request, GENERATE deferred active-set; unit + OptionalSectionProjectionIntegrationTest
>   + ModularityTests pass) -> `applied`. Persist-active-set-into-generate follow-on tracked in section 6.
> - 2026-07-13: M3 `form-schema-section-semantics` implemented + green (public `FormSection` DTO gains
>   `optional` + `renderKind`; `FormProjector` projects `Section.optional` / `Section.render` verbatim --
>   render enum -> lowercase opaque token -- and omits any section that projects to zero form fields;
>   frontend `templateForm.ts` mirror gains the two fields. Unit `FormProjectorTest`, real-wiring
>   `FormSectionSemanticsProjectionIntegrationTest` + HTTP `TemplateFormApiIntegrationTest`, full documents
>   suite + `ModularityTests`, and `vitest` + `vue-tsc` pass; no new dependency, no lockfile change) ->
>   `applied`. The `FormSection.optional` / `renderKind` contract is now live for M4
>   (`capture-mandatory-optional-ux`), which drives the section rail + Add-optional catalog from it.
> - 2026-07-13: M4 `capture-mandatory-optional-ux` implemented + green (frontend only). `documentPreview.ts`
>   request gains `activeSections: string[]`, sent on BOTH the HTML and PDF faces (`[]` when none active);
>   `formModel.ts` gains `isSectionMandatory` (reads `FormSection.optional`, default false = mandatory) +
>   `reconcileActiveSections` (drops stale titles vs the schema); `CaptureForm.vue` splits the rail into a
>   MANDATORY list + an active-optional zone + an Add-optional catalog, tracks the added set as an
>   `activeSections` title array (persisted in the client draft, reconciled on load, cleared on save/reset),
>   sends it on every preview/PDF call, and HARD-BLOCKS Save (`:disabled` + a "complete N more required
>   section(s)" affordance) until every mandatory section is complete. `vitest` (53 tests) + `vue-tsc -b` +
>   `eslint` pass; no new dependency, no `package-lock.json` change (no router / state library added) ->
>   `applied`. STANDING FOLLOW-ON: `activeSections` drives the PREVIEW only -- recording the added optional
>   set onto the saved agreement so generate-as-draft renders the same set (preview/draft parity) is still
>   deferred (section 6, and mirrors M2's generate-side deferral).
> - Integrator role (this window): implement none of M1-M5; on all-landed, run full-build integration +
>   the single end-to-end integration test across M0-M5, then close the tracking table.
> - 2026-07-13 (POST-INTEGRATION CHANGE, requester): made BOTH "Statutory (Telangana)" and the
>   "In Witness Whereof" signature block OPT-IN OPTIONAL in the Telangana template (off by default;
>   added from the catalog). Cross-CR change owned by the integrator: M0 `RenderKind` gains
>   `SIGNATURES` + both schema render-enums; M1 `TemplateCompiler` gains a `signatures` render kind and
>   appends the hardcoded witness block ONLY as a fallback when no section declares `signatures`
>   (fixtures unchanged); M3 `FormProjector` refined -- a field-less section is omitted only when
>   MANDATORY (document-only, e.g. Witnesseth); a field-less OPTIONAL section (the opt-in signature
>   block) is RETAINED so the Add-optional catalog surfaces it; M5 content -- the national base declares
>   an "In Witness Whereof" signatures section (mandatory for IN), the TG state layer marks
>   "Statutory (Telangana)" optional, and the TG state_type layer re-marks the signature block optional
>   and orders both last. Tests updated (ProductionRentalLayerSetTest, FormProjectorTest,
>   AgreementDocumentFormatE2EIntegrationTest) + full `./gradlew test` GREEN (6m59s). Frontend note: the
>   add-catalog now includes a field-less optional section (a pure toggle with no fields to fill) -- M4
>   should render such an entry as an add/remove toggle, not a fill-fields modal (follow-on for the M4
>   window).
> - 2026-07-13 (ARCHIVE HELD, requester decision): NOT archiving yet -- hold for a proper CLI-based
>   archive. Two blockers: (1) no OpenSpec CLI in this environment (`openspec` / `npx openspec` fail),
>   and archiving merges each CR's spec deltas into the published `openspec/specs/` capabilities, which
>   should not be hand-hacked; (2) dependency ordering -- 3 of the 6 CRs modify capabilities whose base
>   specs are NOT published yet: `template-document-metadata` -> `template-definition`,
>   `form-schema-section-semantics` -> `template-form-projection`, `capture-mandatory-optional-ux` ->
>   `preview-centric-capture`. Those base capabilities are defined by still-active changes
>   (`template-definition-model`, `template-form-projection`, `preview-centric-capture`) that must be
>   archived FIRST. (The other 3 could archive today: `rental-document-content-v2` creates the new
>   `rental-agreement-document` capability; `document-artifact-layout` + `optional-section-opt-in` both
>   modify the already-published `template-document-projection` -- apply their two shared `MODIFIED`
>   deltas in order.) ARCHIVE PROCEDURE when unblocked: install the OpenSpec CLI; archive the base
>   templating changes first; then `openspec archive <slug>` each of the six sub-CRs in dependency
>   order (M0 -> {M1, M2, M3, M5} -> M4), then the umbrella. All six remain `applied` (code landed +
>   green) until then; archiving is a bookkeeping step, not a code gate.
> - 2026-07-13 (INTEGRATION CLOSE): all six CRs `applied`. Full backend suite `./gradlew test` GREEN
>   (every per-CR integration test -- M2 `sets/optional`, M3 `sets/formsection`, M5 restructured
>   `sets/rental` -- plus `ModularityTests`, run together on the integrated tree for the first time).
>   Authored + ran the single top-of-pyramid `AgreementDocumentFormatE2EIntegrationTest` (documents
>   module, `@ActiveProfiles({"test","sandbox"})` so the seeder + registry LayerSource resolve the real
>   production Telangana template over Postgres + MinIO + Gotenberg) -- 5 methods GREEN, proving M0-M5
>   compose end to end over HTTP: (a) `GET /api/templates/form` marks mandatory Owner/Tenant/Schedule/
>   Term/Financial `optional:false`, add-ons `optional:true`, carries each `renderKind`, and OMITS the
>   field-less "Now This Agreement Witnesseth" section (M0+M3+M5); (b) `POST .../document/preview`
>   renders the artifact layout -- `meta.document` header, Owner/Tenant party cards, witnesseth clause
>   list, TG statutory overlay, `@page` margins (M0+M1+M5); (c) an optional section is absent until its
>   title is in `activeSections`, appears when added, unknown titles ignored (M2+M5); (d) execution date
>   uses a submitted `agreementDate` verbatim else the SYSDATE fallback (M1); (e) the PDF face renders
>   from the same compiled document (M1 parity). The umbrella `agreement-document-format` is
>   integration-complete and ready to archive alongside its six sub-CRs.

Coverage check against the requester's 8 items: #1 margins -> M1; #2 header -> M1 (+M0 config); #3
fonts/sections -> M1; #4 Owner/Tenant split -> M5 (+M1 party cards); #5 mandatory markings + forced
validation -> M3 + M4 (+M5 marks); #6 optional-only-when-added -> M2 + M4 (+M5 marks); #7 configured in
templates not code -> M0 (schema) applied by M1/M2/M3/M5; #8 Telangana optional catalog -> M5 (+M4 UX).

### Sequencing
- **Critical path (sequential):** M0 -> M1 -> M2 -> M4.
- **Parallel once M0 lands:** M3 (form projection) and M5 (content) alongside M1.
- **M4** after M2 + M3 contracts are frozen (can start on mocks).
- Land M0 + M1 + M5 first for the visible win (the document looks right in the live preview), then M2 +
  M3 + M4 for the opt-in/forced-validation behaviour.

---

## 6. Open design points (settle in the owning CR)

- **Document-only sections vs capture sections (M0/M3).** The artifact's single "Now This Agreement
  Witnesseth" list aggregates clauses that, in our model, could live across capture sections. Decision
  taken: a section may be **document-only** (clause-only, zero fields) and is then omitted from the
  `FormSchema` (M3) while still rendered in the document (M1). M5 authors a dedicated clauses-render
  section for the witnesseth list. Confirm this reads correctly for both IN and TG before archiving M1.
- **Execution-date determinism (M1).** The SYSDATE fallback makes the header change across calendar
  days when `agreementDate` is blank. Accepted per locked decision 4; the render stays reproducible
  given (template, data, activeSections, date). Document the reserved date-binding key.
- **`activeSections` on generate/signing (M2).** Preview carries it from the client; the persisted
  agreement does not yet record which optional sections were added, so GENERATE's active-set is
  deferred (renders mandatory + any explicitly passed). Note the parity caveat: a preview with added
  optional sections will differ from a signed draft until save/sign records the active-set (a named
  follow-on, out of scope here).

---

## 7. Kickoff checklist for a CR window

1. Read `CLAUDE.md` + this journal. Confirm the section 2 locked decisions still hold.
2. Bring up infra: `docker compose up -d`. Windows note (memory): run tests via gradle directly with
   `TESTCONTAINERS_RYUK_DISABLED=true`; set `-Duser.timezone=Asia/Kolkata` or Flyway trips on
   `Asia/Calcutta`. Write files in pure ASCII (PII/secret guard fails closed on non-ASCII).
3. Pick a module from section 3. Propose its CR (`/opsx:propose <slug>`), freeze/consume the section 4
   contracts, keep `documents` reach behind its public seams (`ModularityTests`).
4. Ship unit AND integration tests (pyramid). Keep the markup/data boundary: data + template text stay
   escaped; `showWhen` stays the sandboxed DSL; previews persist/log nothing; preview == PDF.
5. Run `./gradlew check` before proposing archive; update the section 5 status to `applied`, then
   `archived` on `/opsx:archive`.
