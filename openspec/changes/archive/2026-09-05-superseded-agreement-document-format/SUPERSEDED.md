# SUPERSEDED -- not implemented as a single change

This `agreement-document-format` change is `kind: exploration`: an **umbrella map**, not an
implementable change. It decomposed "make the rental-agreement preview + PDF match the reference
artifact, and move mandatory/optional, section layout, and the document header out of code and into
the template definition" into **six independently-startable modules (M0-M5)**, each scoped by its own
OpenSpec CR. Its `flow-journal.md` carries the locked decisions (2026-07-13), the frozen section-4
contracts, the module contracts + done-criteria, and the integration log -- that reasoning is why this
directory is archived rather than deleted.

Unlike the earlier `superseded-template-document-projection`, the descendants here **did ship**: the
journal's 2026-07-13 INTEGRATION CLOSE records all six CRs `applied` with the full backend suite green
and a top-of-pyramid `AgreementDocumentFormatE2EIntegrationTest` proving M0-M5 compose end to end over
HTTP. What was never done is the **bookkeeping**: the journal's 2026-07-13 "ARCHIVE HELD" entry parked
archiving on a missing OpenSpec CLI and on base capabilities that were not published yet. Four of the
six have since been archived; two are still active directories (see below). The umbrella itself owns
no code and has no spec delta, so nothing folds into `openspec/specs/` from here.

Superseded by (apply in dependency order; critical path M0 -> M1 -> M2 -> M4, with M3 and M5 parallel
once M0 lands):

- **M0 `template-document-metadata`** -- the declarative schema additions. `meta.document {title,
  subtitle, executionLine}`; per-section `optional` (default false) and `render` kind
  (`parties | keyvalue | clauses | annexure`, later `+ signatures`). Updates
  `template-definition.schema.json` + `layer-patch.schema.json`, the `Meta`/`Section` records,
  `DocumentMeta` + `RenderKind`, `TemplateDefinitionLoader`, `TemplateDefinitionValidator`, and
  `CanonicalJson` + the resolver so the content hash changes deterministically. Froze the section-4
  contracts for M1/M2/M3/M5. **STILL AN ACTIVE CHANGE DIRECTORY** -- see the note at the bottom.

- **M1 `document-artifact-layout`** (archived `2026-07-13-092856Z-`) -- `TemplateCompiler` reworked to
  emit the artifact layout: centred `meta.document` header, section bodies dispatched by render kind
  (party cards / keyvalue table / numbered clause list / annexure), serif Latin body with bundled Noto
  faces for Indic shaping, and real page margins (CSS `@page` + body padding) on preview and PDF.
  Execution date resolved `agreementDate`-else-SYSDATE via an injected `Clock` at
  `DocumentProjectionService` and passed into `compile` as a value, so the compiler stays pure.
  **Depends on M0.**

- **M2 `optional-section-opt-in`** (archived `2026-07-13-092856Z-`) -- opt-in optional sections.
  `DocumentProjectionRequest` gains `activeSections` (additive); the compiler renders a section iff it
  is mandatory OR its title is in the active set; an unknown title is ignored rather than erroring.
  PREVIEW drives the active set from the request; the GENERATE-side active set is deferred.
  **Depends on M0, M1.**

- **M3 `form-schema-section-semantics`** (archived `2026-07-13-092856Z-`) -- the public `FormSection`
  DTO gains `optional` + `renderKind`, projected verbatim by `FormProjector` (render enum to a
  lowercase opaque token) so the client can mark Mandatory/Optional and build the add-optional
  catalog. A field-less section is omitted from the `FormSchema` when mandatory (document structure,
  not a capture step) but RETAINED when optional (so the catalog can surface a pure toggle).
  **Depends on M0; parallel with M1/M2.**

- **M5 `rental-document-content-v2`** -- the production `sets/rental/` layer-set content. Splits
  "Parties" into Owner + Tenant (render kind `parties`); marks Owner/Tenant/Property/Term/Financial
  mandatory and the rest optional; adds the National vs Telangana `meta.document` header; tags each
  section's render kind; expands the Telangana optional add-on catalog to the artifact's items, each
  individually addable. **Depends on M0; authored in parallel with M1, visually verified once M1
  landed.** **STILL AN ACTIVE CHANGE DIRECTORY** -- see the note at the bottom.

- **M4 `capture-mandatory-optional-ux`** (archived `2026-07-13-092856Z-`) -- the frontend.
  `CaptureForm.vue` splits the section rail into a MANDATORY list, an active-optional zone, and an
  Add-optional catalog; tracks the added set as an `activeSections` title array (persisted in the
  client draft, reconciled against the schema on load, cleared on save/reset) and sends it on both the
  HTML and PDF preview faces; `formModel.ts` gains `isSectionMandatory` + `reconcileActiveSections`;
  Save & continue is HARD-BLOCKED until every mandatory section is complete.
  **Depends on M2 + M3 contracts.**

A post-integration requester change (2026-07-13, owned by the integrator rather than a new CR) made
both "Statutory (Telangana)" and the "In Witness Whereof" signature block opt-in optional, adding a
`SIGNATURES` render kind across M0/M1/M3/M5. The journal records it in full.

## Still-open descendants at archive time (2026-09-05)

Two of the six remain **active change directories**, both recorded `applied` in the journal's tracking
table with their code verified in tree:

- `template-document-metadata` (M0) shows **0/28 tasks ticked**. This is **stale bookkeeping from the
  ARCHIVE HELD entry, not unstarted work** -- its tasks describe artifacts that already exist
  (`DocumentMeta.java`, `RenderKind.java`, the `optional`/`render` components on `Section`, the schema
  additions), and every later module depends on and consumes them.
- `rental-document-content-v2` (M5) shows 22/23 tasks ticked.

Archiving them is a separate judgment call and was deliberately left out of this cleanup. Retiring
this umbrella does not close them.
