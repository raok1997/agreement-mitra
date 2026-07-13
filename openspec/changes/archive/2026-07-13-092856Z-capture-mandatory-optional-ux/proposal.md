## Why

The preview-centric capture shell (`preview-centric-capture` + `template-form-projection`) drives its
left rail from a fetched `FormSchema` and refreshes a live preview as the user fills sections. But it
does not yet reflect the document model the agreement-document-format work locked in (flow-journal
section 2): sections are **either mandatory or optional**, and an **optional** section must be
**opt-in** -- absent from the document until the user explicitly adds it, present in preview + PDF once
added. Today the rail infers "required" from whether a section has any required *field*, treats every
section as always-rendered, and lets "Save & continue" proceed with a soft warning rather than a hard
block.

This CR is **module M4** of the `agreement-document-format` umbrella (the capture UX layer). It
consumes two frozen upstream contracts and turns them into the reference-artifact interaction:

- **M3 (`form-schema-section-semantics`)** adds `FormSection.optional` (bool) and
  `FormSection.renderKind` (string) to the projected `FormSchema`, and omits field-less
  (document-only) sections -- so the client can mark Mandatory vs Optional and build the add-optional
  catalog.
- **M2 (`optional-section-opt-in`)** adds `activeSections: string[]` to the document-preview request;
  the compiler renders an optional section iff its title is present in `activeSections` (mandatory
  always renders, unknown titles ignored).

With those in hand, M4 makes the left panel mark each section MANDATORY vs OPTIONAL from the schema,
puts optional sections behind an **"Add optional" catalog** (opt-in, tracked as an `activeSections`
set, sent on every preview POST so added optional content appears in preview + Download PDF and
un-added content does not), and **hard-blocks "Save & continue"** until every mandatory section is
complete (with a clear "complete N more required section(s)" affordance). This is **frontend only** --
no backend, no new dependency, and no change to the frontend OSV surface (no router or state library
added; the existing view-switch + reactive refs pattern is reused).

## What Changes

- **Mark mandatory vs optional from the schema.** `CaptureForm.vue` marks each left-panel section
  MANDATORY vs OPTIONAL from `FormSection.optional` (from the fetched `FormSchema`), not from
  field-level required-ness. Mandatory sections show a `Needs-input` / `Ready` state as today; optional
  sections move into an **"Add optional" catalog** rather than sitting inline as "optional".
- **Optional sections are opt-in.** An optional section is **absent from the preview until the user
  adds it**. The SPA tracks an `added` / `activeSections` set (of optional section titles). Adding an
  optional section moves it into the active rail and starts contributing to the document; removing it
  takes its content back out. `documentPreview.ts` sends `activeSections` on **every**
  `POST /api/templates/document/preview` (live HTML and Download PDF), so added optional content
  renders in both preview and the downloaded PDF and un-added optional content does not.
- **Hard-block "Save & continue".** "Save & continue" is **disabled/blocked** -- not merely warned --
  until **every mandatory section is complete**. Client validation forces mandatory entry; the button
  is disabled and a clear "complete N more required section(s)" affordance names how many remain.
  Optional completeness never blocks save.
- **Type additions (mirroring upstream).** `templateForm.ts` gains `optional: boolean` and
  `renderKind: string` on `FormSection` (mirroring M3). `documentPreview.ts`'s request type gains
  `activeSections: string[]` (mirroring M2). All API calls stay in `src/api/`.

Explicitly **not** in this change: the backend schema/compiler/projection work (M0-M3, M5); persisting
which optional sections were added onto the saved agreement (generate-as-draft active-set is deferred
per flow-journal section 6 -- a named follow-on); any router/state library; and authoring templates.

## Capabilities

### Modified Capabilities

- `preview-centric-capture`: the capture shell now (a) marks each section MANDATORY vs OPTIONAL from
  `FormSchema` section semantics and presents optional sections in an **Add-optional catalog**;
  (b) treats optional sections as **opt-in** -- absent from the preview + Download PDF until added,
  tracked as an `activeSections` set sent on every preview request; and (c) **hard-blocks** the final
  "Save & continue" until every mandatory section is complete (disabled button + a
  "complete N more required section(s)" affordance), replacing the prior soft warning. The stateless
  preview, section-modal edit flow, live pane, client-held draft, and existing create +
  generate-as-draft save path are otherwise unchanged.

## Impact

- **Frontend only.** `frontend/src/views/CaptureForm.vue` (rail split into mandatory list + add-optional
  catalog; `activeSections` state; hard-blocked save), `frontend/src/api/templateForm.ts`
  (`FormSection.optional` + `renderKind`), `frontend/src/api/documentPreview.ts` (request
  `activeSections`), and `frontend/src/views/formModel.ts` (mandatory/optional helpers). API calls stay
  in `src/api/`.
- **Consumes frozen contracts** M2 (`activeSections` on the preview request) and M3
  (`FormSection.optional` + `renderKind`, field-less sections omitted). Can be built against a schema
  fixture / mock and swapped to the live endpoints once M2 + M3 land.
- **No new dependency; frontend OSV surface unchanged.** No router, no state library, no npm package is
  added, so `frontend/package-lock.json` and the frontend `securityScan` graph do not change.
- **No backend, DB, or module-boundary change.** No Java, no Flyway migration, no `documents`/`signing`
  change; `ModularityTests` is not touched.
- **No** change to: the signing-status FSM, `EsignProvider` / webhook flow, the create +
  generate-as-draft persistence path, or the stateless preview endpoint's contract beyond the additive
  `activeSections` field (owned by M2).

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **None new.** This CR only reshapes how the
  capture UI marks and gates sections. It handles the **same** in-progress working set the shell already
  holds client-side (party names/addresses/amounts). It adds no field, no logging, and no new persisted
  data; `activeSections` carries only **system-owned section titles** (template metadata), never user
  data.
- **Never-log discipline preserved.** The existing shell never logs the working set or the rendered
  document; this CR adds no logging and continues to surface only terse, data-free error messages. The
  new `activeSections` value (section titles) is safe metadata, but is still not logged.
- **Markup/data boundary held.** The user still edits **data**, never markup; the preview remains
  escaped HTML in a sandboxed iframe. Optional sections are declared by the **template** (via the schema
  `optional`/`renderKind`); the client only toggles which declared sections are active by **title**, and
  an unknown/removed title is ignored server-side (M2). No user-authored section or expression is
  introduced.
- **Client-held draft.** The `activeSections` set is part of the same client-held, TTL-bounded
  localStorage working draft; it is cleared on successful Save and on explicit reset, exactly as the
  existing draft is. It contains only section titles, no PII.
- **Persistence stays deliberate and server-side-only while editing.** Nothing is persisted server-side
  until the deliberate "Save & continue" through the existing create + generate-as-draft paths; the
  hard block only makes that gate stricter (mandatory-complete), never looser.
- **Sandbox + dummy data only?** Preserved -- no live provider, credential, or env var is touched.
- **No new dependency / OSV surface.** No npm package added; `package-lock.json` unchanged, so the
  frontend OSV `securityScan` graph is unchanged.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** -- no signing sequence diagram required.
