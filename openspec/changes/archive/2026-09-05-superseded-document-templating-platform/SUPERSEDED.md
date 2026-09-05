# SUPERSEDED -- not implemented as a single change

This `document-templating-platform` change is `kind: exploration`: the **umbrella map** for the whole
schema-driven templating engine -- moving rental agreements off a single hardcoded
`rental-agreement` template onto layered, versioned, dimension-resolved template definitions with a
system-owned compiler. It decomposed that into **eight modules (M0-M7)** with frozen contracts, each
scoped by its own OpenSpec CR. Its `exploration.md` carries the problem framing and the locked
decisions; its `flow-journal.md` carries the module contracts, the dependency graph, the section-4
seams every module binds to, and a later integration review (section 8) -- that reasoning is why this
directory is archived rather than deleted.

The descendants **shipped**: M0-M4 and the M5 pinning half are archived, and the M6 frontend landed
across two CRs. This umbrella owns no code and has no spec delta, so nothing folds into
`openspec/specs/` from here. Note that M2 was decomposed a second time -- via the intermediate
`2026-07-12-superseded-template-document-projection`, itself retired unimplemented and split into
`template-html-compiler` + `document-projection-render`.

Superseded by (apply in dependency order; critical path M0 -> M1 -> M2, with M3 parallel to M2):

- **M0 `template-definition-model`** (archived `2026-07-13-092856Z-`) -- the canonical
  `TemplateDefinition` value types (`meta` / `fields` / `clauses` / `sections`), the definition loader,
  compile-to-canonical-JSON, and JSON-Schema validation of definitions. Pure; no Spring, no I/O beyond
  resource load. **Depends on nothing.**

- **M1 `template-resolution-engine`** (archived `2026-07-13-092856Z-`) -- layered-patch composition:
  `resolve(base, [type, state, state+type]) -> effective template` with fixed precedence,
  add/replace/remove/reorder, last-wins, deterministic, plus a stable **content hash** of the result.
  **Depends on M0.**

- **M2, the single compiler** -- decomposed through
  `2026-07-12-superseded-template-document-projection`, which was itself retired unimplemented and
  split into **four** increments. Two of those four are M2's and are listed here; the other two carry
  work belonging to later modules and appear below under M5 (`agreement-template-pin`) and M6
  (`document-capture-shell-wiring`):
  - **`template-html-compiler`** (archived `2026-07-12-132219Z-`) -- the pure engine.
    `TemplateCompiler` (escape-at-compile + the sandboxed `showWhen` DSL) + `SubmittedDataValidator`.
    Delivered-but-unwired; unit tests only.
  - **`document-projection-render`** (archived `2026-07-12-141605Z-`) -- wiring the engine into the
    render path: the `HtmlPdfRenderer` seam over `GotenbergClient`, `DocumentProjectionService`, the
    public `DocumentProjectionApi` + `POST /api/templates/document/preview`. Retires
    `TemplateAssembler` / `DocumentRenderer` and the old `POST /api/agreements/preview`. This is the
    "one system-owned compiler" guarantee: preview and signed PDF render from the same path.
  **Depends on M0, M1.**

- **M3 `template-form-projection`** (archived `2026-07-13-092856Z-`) --
  `project(effectiveTemplate) -> FormSchema`: sections plus typed fields with
  required/default/options/validation, the JSON the frontend renders. `showWhen` is carried verbatim
  and unevaluated on `FormField`. **Depends on M0, M1; parallel with M2.**

- **M4 `template-catalog`** (archived `2026-07-13-092856Z-`) -- the template registry (Postgres:
  id/name/category/state/version/status, with bodies as resources/object storage, never inline),
  `GET /api/templates?state=&type=` list/current plus detail, and per-version resolution caching.
  **Depends on M1, M3.**

- **M5, agreement-to-template binding** -- **SPLIT (2026-07-12)**, because the original single CR
  (attributes store **plus** pinning) duplicated work already owned elsewhere:
  - **Pinning -> `agreement-template-pin`** (archived `2026-07-12-150356Z-`) -- the canonical pin CR.
    `V9__agreement_template_pin.sql` records `template_content_hash` + `template_layer_versions` at
    generate-as-draft; no new FSM state. **Depends on M2's render path.**
  - **Attributes store -> DEFERRED, not dropped.** Persisting *user-set* template-specific fields is
    only needed once users can customize them per agreement (the custom-conditions work); until then
    they render from the effective template's declared defaults via `AgreementDocumentMapper`.
    Re-propose attributes-only (JSONB on `agreement`, a migration after V9, no pinning) when that
    starts, after the pin has landed so attribute values snapshot with it.
  - The earlier `agreement-attributes-and-pinning` CR was retired to remove the duplicate and is
    archived at `2026-07-12-superseded-agreement-attributes-and-pinning`. **Do not implement it.**

- **M6, the frontend** (State x Type picker, schema-driven section registry replacing the hardcoded
  one, preview wired to the resolved template) -- built on the preview-centric capture shell
  (the D4 registry seam, still an active change), landed across two CRs:
  - **`document-capture-shell-wiring`** (archived `2026-07-12-152551Z-`) -- the `src/api/`
    document-preview client and the rewire of the capture shell's live pane / Download PDF / Save &
    continue onto the new endpoints, retiring `src/api/preview.ts`. This is the fix for the journal's
    section 8.2 contract correction; the journal's "unapplied" prose there is stale.
  - **`agreement-template-selection`** (archived `2026-07-13-092856Z-`) -- the picker view, carrying
    `(state, type)` into capture so the form fetch and the preview request resolve one effective
    template.
  **Depends on M3 + M4 contracts.**

- **M7, admin authoring** (structured builder, clause-library CRUD, template lifecycle
  draft/approved/published/deprecated with immutable versions, admin role) -- **PARKED (2026-07-12),
  never proposed as a CR.** Its prerequisite `mobile-otp-auth` is itself parked, and the end-user flow
  M0-M6 does not need it. Unpark the two together. Parking M7 is why this umbrella is retired as
  superseded rather than completed: the map outlived the modules that were actually built from it.

Section 8.5 of the journal records the one end-to-end risk it could not close -- preview/draft parity
for non-default templates, where the live preview and the saved signable draft rendered through
different paths. `agreement-template-pin` plus the single-compiler wiring in `document-projection-render`
are what close it; read that section before touching either path.
