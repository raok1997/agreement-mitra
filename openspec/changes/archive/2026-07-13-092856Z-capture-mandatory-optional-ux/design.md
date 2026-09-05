## Context

This is module **M4** of the `agreement-document-format` umbrella (see its `flow-journal.md`). The
capture shell already exists: `CaptureForm.vue` fetches a `FormSchema` via
`getTemplateForm(state, type)`, builds a section rail + section-edit modals from it, and drives a live
preview through the stateless `POST /api/templates/document/preview` endpoint
(`documentPreview.ts`), holding the whole in-progress working set client-side (with a TTL-bounded
localStorage draft) until the deliberate "Save & continue" that runs the existing create +
generate-as-draft path.

Two upstream contracts are frozen (flow-journal section 4) and consumed here:

- **M3 -- `FormSection { title, fields[], optional: bool, renderKind: string }`**, field-less
  (document-only) sections omitted from the `FormSchema`.
- **M2 -- `DocumentProjectionRequest { dimensions?{state,type}, data, activeSections: string[] }`**;
  the compiler renders a section iff it is mandatory OR its title is in `activeSections`; unknown
  titles are ignored (no error).

Constraints unchanged: Vue 3 `<script setup>` + Tailwind; API calls live in `src/api/`; **no router
and no state library** (adding one widens the frontend OSV surface the `securityScan` gate locks --
flow-journal / template-catalog addendum 8.3); responsive is a CSS concern. Frontend-only: no backend,
no dependency, no `package-lock.json` change.

## Goals / Non-Goals

**Goals**

- Mark each left-panel section MANDATORY vs OPTIONAL from `FormSection.optional`, not from field-level
  required-ness.
- Present optional sections in an **"Add optional" catalog**; an optional section is **opt-in** --
  absent from preview + Download PDF until added.
- Track the added optional sections as an `activeSections` set and send it on **every** preview POST
  (live HTML + PDF), so the preview and the downloaded PDF reflect exactly the added optional content.
- **Hard-block** "Save & continue" until every mandatory section is complete: the button is
  disabled/blocked (not a soft warning) with a clear "complete N more required section(s)" affordance.
- Add `optional` + `renderKind` to the `templateForm.ts` `FormSection` type (mirror M3) and
  `activeSections` to the `documentPreview.ts` request type (mirror M2).

**Non-Goals**

- Backend schema/compiler/projection (M0-M3) or rental content (M5) -- consumed here as frozen
  contracts, not built.
- Persisting the added optional set onto the saved agreement -- generate-as-draft's active-set is
  deferred (flow-journal section 6); this CR carries `activeSections` for preview only. Save continues
  through the existing create + generate path unchanged.
- A router or state library, a new npm dependency, or any change to the stateless preview endpoint
  beyond the additive `activeSections` field (M2's).
- Rendering optional sections' `renderKind` in the client -- `renderKind` is carried on the type for
  parity with M3 and future use (e.g. catalog grouping), but the document layout is the server-owned
  compiler's job (M1). The client only reads `optional`.

## Decisions

### D1: Mandatory vs optional comes from `FormSection.optional`, not field required-ness

Today `UiSection.required` is derived from `isSectionRequired(fields)` (does the section carry any
required field). That conflates two different ideas. M3 makes section optionality a **first-class,
template-declared** property (`FormSection.optional`). This CR switches the rail's mandatory/optional
split to `!section.optional`: a **mandatory** section always renders and counts toward completeness; an
**optional** section is opt-in and never blocks save. `formModel.ts` gains a helper that reads
`section.optional` (defaulting a missing value to `false` = mandatory, matching the frozen default), so
a schema that predates M3 still behaves as today. Completeness of a mandatory section still uses the
existing field-level `isSectionComplete` (every required field valid). **Alternative rejected:** keep
inferring from fields -- it cannot express a mandatory section whose fields are all individually
optional, nor an optional section that happens to contain a required field, both of which the template
model now allows.

### D2: Optional sections are opt-in via an `activeSections` set of titles

The SPA holds an `activeSections` reactive set (a `Set<string>` / string array) of the **titles** of
optional sections the user has added -- titles because M2's contract keys the compiler's render
decision on section **title**. The rail renders in two zones: the **mandatory** sections (as today,
with `Needs-input`/`Ready`), then an **"Add optional" catalog** listing every optional section not yet
added (each with an "Add" affordance); adding one moves it into an **active-optional** zone where it
edits like any section, and a "Remove" affordance takes it back out. `flatWorking()` still contributes
a section's field data only when relevant, and `activeSections` is what tells the server to render an
added optional section even when partially filled (matching locked decision 1: added optional sections
render even if partially filled). The set is part of the client-held draft (persisted/restored/cleared
with it) and is reconciled against the fetched schema on load (a stored title that is no longer an
optional section in the schema is dropped). **Alternative rejected:** keying on section id/slug -- the
frozen M2 contract is title-based, and titles are what the compiler matches; sending slugs would
require a client-side title<->slug map the server does not share.

### D3: `activeSections` is sent on every preview POST (HTML and PDF)

`documentPreview.ts`'s request body gains `activeSections: string[]`, sent on both
`fetchDocumentPreviewHtml` and `fetchDocumentPreviewPdf`, so the live pane and Download PDF render the
**same** added-optional content (parity between the two preview faces). `CaptureForm.vue` passes the
current `activeSections` array on every `schedulePreview` / `refreshPreview` and on `downloadPdf`. When
empty, the array is sent as `[]` (mandatory-only render). This is additive to the request the endpoint
already accepts (M2 owns the server side). **Alternative rejected:** sending `activeSections` only when
non-empty / omitting it -- an explicit `[]` is unambiguous and keeps the two faces identical.

### D4: "Save & continue" is hard-blocked until every mandatory section is complete

`allRequiredDone` already computes whether every mandatory (formerly "required") section is complete.
This CR wires it to **disable** the Save button (`:disabled` includes `!allRequiredDone`), not just to
show a warning after a click. The completeness meter and a persistent "complete N more required
section(s)" affordance name the remaining count, where N = mandatory sections not yet complete. The
click handler keeps its guard as defence-in-depth (so a programmatic click still cannot save an
incomplete set), but the **primary** control is the disabled state. Optional sections -- added or not,
complete or not -- never affect the block. This tightens the existing behavior (locked decision:
"the frontend hard-blocks Save & continue until every mandatory section is complete") without changing
the persistence path itself. **Alternative rejected:** blocking on all sections including added
optional ones -- optional content may be intentionally partial (locked decision 1), so it must not gate
save.

### D5: No router, no state library, no new dependency (OSV surface unchanged)

The mandatory/optional split, the add-optional catalog, and `activeSections` are all local
component/reactive state plus pure helpers in `formModel.ts` -- the same architecture the shell already
uses. Nothing here needs cross-view routing or a global store, so no `vue-router`/`pinia`/other package
is added; `frontend/package-lock.json` and the frontend `securityScan` graph stay byte-identical. This
is a hard constraint (flow-journal 8.3), not merely a preference.

## Risks / Trade-offs

- **Title-keyed active set is brittle to renames.** Because M2 keys on section **title**, a template
  that renames an optional section between schema fetch and a resumed draft would drop the stored title
  (D2's reconcile). Accepted: titles are the frozen contract; the reconcile-on-load prevents a stale
  title from silently activating nothing, and the server ignores unknown titles anyway.
- **Preview/draft parity for added optional content.** `activeSections` drives the **preview** only;
  the saved draft (generate-as-draft) does not yet record which optional sections were added
  (flow-journal section 6 -- a named follow-on). So a preview with added optional sections can differ
  from the signed draft until that follow-on lands. This CR does not close that gap (out of scope) and
  must not imply it does; the existing preview/draft parity caveat (flow-journal 8.5) still stands.
- **Two "optional" meanings during the transition.** Until M3 is live, `FormSection.optional` may be
  absent on the fetched schema; D1's default (`false` = mandatory) keeps the shell behaving as today,
  so M4 can land against a fixture/mock and light up fully once M3 ships.
- **Hidden non-persisted fields can empty a section.** The shell already hides `NON_PERSISTED_FIELDS`
  and filters field-less sections client-side; an optional section reduced to zero visible fields would
  not appear in the catalog. Accepted -- it matches today's behavior and M3 already omits document-only
  sections server-side.

## Migration Plan

Frontend-only and additive; no schema, no dependency, no server change. Steps:

1. `templateForm.ts`: add `optional: boolean` and `renderKind: string` to `FormSection` (mirror M3).
2. `documentPreview.ts`: add `activeSections: string[]` to the request body type and send it on both
   the HTML and PDF calls (mirror M2).
3. `formModel.ts`: add a mandatory/optional helper reading `section.optional` (default false) and any
   helper needed to reconcile a stored `activeSections` set against the fetched schema.
4. `CaptureForm.vue`: split the rail into mandatory + add-optional catalog + active-optional; track the
   `activeSections` set (persisted with the draft); pass it on every preview/PDF call; disable Save
   until every mandatory section is complete with the "complete N more required section(s)" affordance.
5. Tests: component tests (rail marks mandatory/optional from a fixture; adding an optional section adds
   its content to the preview request + pane; Save disabled until mandatory complete) and a thin App
   e2e (pick dimensions, fill mandatory, add one optional, preview reflects it, Save enables).

Rollback: revert the four frontend files; no data or dependency to undo.

## Open Questions

- **Remove vs keep an added optional section's data.** When a user removes an added optional section,
  should its already-entered field values be discarded or retained (in case they re-add it)? Proposing
  **retain in the client draft but drop the title from `activeSections`** (so it stops rendering) --
  cheap, non-destructive, and reversible; confirm during build.
- **Catalog grouping by `renderKind`.** `renderKind` is carried on the type but unused by the client in
  this CR; a later polish could group the add-optional catalog by render kind. Out of scope here.
