> **Module M4** of the `agreement-document-format` umbrella (frontend capture UX). **Frontend only** --
> no backend, no Flyway, no dependency, no `package-lock.json`/OSV change. Consumes the frozen contracts
> M2 (`activeSections` on the document-preview request) and M3 (`FormSection.optional` + `renderKind`,
> field-less sections omitted). Can be built against a schema fixture / mock and swapped to the live
> endpoints once M2 + M3 land. Keep all API calls in `src/api/`; add no router or state library.
>
> Every behavioral change below ships a **component/unit** test task AND a thin **e2e/integration** test
> task (the frontend pyramid; CLAUDE.md tasks rule). Windows note (project memory): write files in pure
> ASCII (the PII/secret guard fails closed on non-ASCII).

## 1. API types (mirror the frozen upstream contracts) -- `frontend/src/api/`

- [x] 1.1 `templateForm.ts`: add `optional: boolean` and `renderKind: string` to the `FormSection`
  interface (mirroring M3's `FormSection { title, fields[], optional, renderKind }`). Do not change any
  other DTO type or the `getTemplateForm` call.
- [x] 1.2 `documentPreview.ts`: add `activeSections: string[]` to the request body type
  (`DocumentProjectionRequestBody`, mirroring M2's
  `DocumentProjectionRequest { dimensions?, data, activeSections }`), and send it on **both**
  `fetchDocumentPreviewHtml` and `fetchDocumentPreviewPdf` (as `[]` when none are active). Keep the
  never-log / status-code-only error contract unchanged.

## 2. Section model helpers -- `frontend/src/views/formModel.ts`

- [x] 2.1 Add a pure helper that reports whether a schema section is **mandatory** from
  `FormSection.optional` (a missing/undefined `optional` defaults to `false` = mandatory, per the frozen
  M2/M3 default), decoupled from field-level required-ness. Keep `isSectionComplete` (every required
  field valid) as the completeness rule for a mandatory section.
- [x] 2.2 Add a pure helper to reconcile a stored `activeSections` set (array of optional section
  **titles**) against a fetched `FormSchema`: drop any title that is no longer an optional section in
  the schema; keep the order stable. (Used to restore the client draft safely.)

## 3. Capture shell -- `frontend/src/views/CaptureForm.vue`

- [x] 3.1 Drive the rail's MANDATORY vs OPTIONAL split from `FormSection.optional` (task 2.1), not from
  `isSectionRequired(fields)`. Mandatory sections keep the `Needs-input` / `Ready` state and count
  toward the completeness meter; optional sections do not count toward completeness.
- [x] 3.2 Render optional sections in an **"Add optional" catalog**: optional sections not yet added
  list with an "Add" affordance; added optional sections move to an active zone that edits like any
  section and offers a "Remove" affordance. Track the added set as an `activeSections` reactive
  `string[]` of section titles.
- [x] 3.3 Send `activeSections` on **every** preview call -- `schedulePreview`/`refreshPreview` (live
  HTML) and `downloadPdf` (PDF) -- so added optional content appears in the preview pane and the
  downloaded PDF, and un-added optional content does not.
- [x] 3.4 Persist the `activeSections` set inside the existing client-held localStorage draft (saved on
  change, restored via task 2.2's reconcile on load, cleared on successful Save and on explicit reset).
- [x] 3.5 **Hard-block "Save & continue"**: disable the button while any mandatory section is
  incomplete (`:disabled` includes `!allRequiredDone`), and show a clear "complete N more required
  section(s)" affordance naming the remaining count. Keep the click-handler guard as defence-in-depth.
  Optional sections (added or not, complete or not) never affect the block. Do not change the create +
  generate-as-draft persistence path.

## 4. Tests -- component / unit (Vitest + Vue Test Utils; many, fast; no network)

- [x] 4.1 **Rail marks mandatory vs optional from a schema fixture**: given a `FormSchema` fixture with
  a mix of `optional: false` and `optional: true` sections, the rail lists the mandatory ones with
  `Needs-input`/`Ready` and puts the optional ones in the Add-optional catalog; the completeness meter
  counts only mandatory sections. (Covers 3.1, 2.1.)
- [x] 4.2 **Adding an optional section adds its content to the preview request + pane**: with a mocked
  preview fetch, adding an optional section (a) includes its title in the `activeSections` array sent on
  the next preview POST and (b) moves it into the active-optional zone; removing it drops the title from
  `activeSections`. Assert the request body carries `activeSections` and `data`. (Covers 3.2, 3.3, 1.2.)
- [x] 4.3 **Save disabled until every mandatory section is complete**: the Save button is `disabled` and
  the "complete N more required section(s)" affordance shows the correct N while any mandatory section
  is incomplete; completing the last mandatory section enables Save; adding/leaving an optional section
  incomplete does not disable Save. (Covers 3.5.)
- [x] 4.4 `formModel.ts` unit: the mandatory helper reads `section.optional` (undefined -> mandatory)
  and the `activeSections` reconcile helper drops titles absent from / non-optional in the schema.
  (Covers 2.1, 2.2.)

## 5. Tests -- thin e2e / integration (Vitest App-level, mocked fetch; few)

- [x] 5.1 **Pick -> fill mandatory -> add one optional -> preview reflects it -> Save enables**: mount
  the App/CaptureForm against a mocked schema + mocked preview endpoint; select dimensions, fill every
  mandatory section (Save stays disabled until the last one completes), add one optional section and
  assert the subsequent preview POST body includes its title in `activeSections` and the pane reflects
  it, then assert Save becomes enabled and the existing create + generate-as-draft calls fire on save.
  (End-to-end of 3.1-3.5.)

## 6. Wrap-up

- [x] 6.1 `vitest run` green (new component + App tests), `vue-tsc -b` clean (validates the
  `FormSection.optional`/`renderKind` and request `activeSections` type additions), `eslint` +
  `prettier` clean on the changed files.
- [x] 6.2 Confirm **no new dependency and no `frontend/package-lock.json` change** (frontend OSV
  `securityScan` surface unchanged); no router or state library added.
- [x] 6.3 Update the `agreement-document-format` flow-journal section 5 tracking table:
  `capture-mandatory-optional-ux` -> `applied`. Note the standing follow-on: recording the added
  optional set onto the saved agreement so generate-as-draft parity holds (flow-journal section 6).
