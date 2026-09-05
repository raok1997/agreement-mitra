> **Fourth and last increment** superseding `template-document-projection`. Frontend only. Depends on
> **CR-2 `document-projection-render`** (`POST /api/templates/document/preview`) and **CR-3
> `agreement-template-pin`** (generate-as-draft pins). All API calls stay in `src/api/`.

## 1. Document-preview API client (`frontend/src/api`)

- [x] 1.1 Added `src/api/documentPreview.ts` -- `fetchDocumentPreviewHtml(data, dimensions)` /
  `fetchDocumentPreviewPdf(data, dimensions)` over a shared `postDocumentPreview(data, accept,
  dimensions?)` that POSTs `POST /api/templates/document/preview` with body `{ dimensions?, data }` (a
  flat field-key data map). `Accept: text/html` for the live pane, `application/pdf` for Download PDF.
  Logs nothing; error carries only the status. Dimensions are sent so the preview resolves the SAME
  effective template the form was projected from (design D2). The caller (`downloadPdf`) revokes the
  object URL.
- [x] 1.2 Removed `src/api/preview.ts` (`fetchWorkingPreviewHtml` / `fetchWorkingPreviewPdf` against
  `/api/agreements/preview`) and its nested-`signers[]` `WorkingPreviewInput` / `WorkingSigner`.

## 2. Wire the preview-centric shell (`frontend/src/views`, `frontend/src/components`)

- [x] 2.1 Pointed the `CaptureForm.vue` live-preview pane at the HTML variant on (debounced) section
  save, "Download PDF" at the PDF variant, and left "Save & continue" on the existing create +
  generate-as-draft path. The working set flattens to the `data` map via `flatWorking()`; the old
  well-known-key `buildPreviewInput()` remapping is deleted. Shell layout, section modals, and
  completeness bar unchanged; the HTML variant still renders in the sandboxed iframe (`srcdoc`).
  NOTE (carried, not fixed here): "Save & continue" still pins/renders through the FIXED-getter
  `AgreementDocumentService`, not the projection compiler -- preview/draft parity for non-default
  templates is the documents window's concern (flow-journal 8.5). See 4.2 hold.

## 3. Tests

- [x] 3.1 **Unit** (`src/api/documentPreview.test.ts`, 4 tests): builds the correct request (URL,
  method, `Accept`, `{dimensions, data}` body) for both the HTML and PDF variants; omits `dimensions`
  when none given; surfaces a non-OK response as a status-only error; logs nothing sensitive.
- [x] 3.2 **Component** (`src/views/CaptureForm.test.ts`, updated): on section save the shell calls the
  HTML variant with the flat data map + the fetched dimensions and renders it in the sandboxed iframe;
  "Download PDF" calls the PDF variant and revokes the object URL; "Save & continue" calls create +
  generate-as-draft; completeness bar unaffected. Also covered by the App e2e (`src/App.test.ts`).

## 4. Wrap-up

- [x] 4.1 Frontend tests green (`npx vitest run`: 8 files, 43 tests). ESLint clean on all files this CR
  touches (`src/api/documentPreview*.ts`, `src/views/CaptureForm*`, `src/components/TemplatePicker*`,
  `src/App*`). **No new dependency, no `package-lock.json` change** (only `vue` ships). NOTE: repo-wide
  `npm run lint` reports 2 pre-existing `no-undef` errors in `scripts/security-scan.mjs` (a sibling
  CR's Node shim, untouched here). `npm run build` includes the osv-scanner gate (binary not installed
  locally); verified via `npm run build:only` (vue-tsc typecheck + vite build, both green).
- [x] 4.2 PARITY GUARDRAIL added (resolves the archive hold). Investigation found the preview/draft
  divergence was real and always-on: the picker's single selectable default was `(TG, residential)`
  while generate-as-draft renders `(IN, residential)` (it resolves the default dimensions and maps only
  the aggregate-backed field keys), so the live preview and the signed draft came from **different
  effective templates**, and template-declared optional fields (`furnished`,
  `registrationResponsibility`) the draft never persists diverged even for the default template.
  Guardrail (frontend-only): (a) the picker's selectable default + the shell default are now
  `(IN, residential)` -- the exact template the draft path renders -- so what the user previews is what
  the signed draft will be (`TemplatePicker.vue`, `CaptureForm.vue`); TG stays "Coming soon". (b) the
  shell hides the non-persisted optional fields (`furnished`, `registrationResponsibility`) and strips
  them from the preview data map, so the user cannot set a value the signed draft would ignore. Tests
  updated (`TemplatePicker.test.ts`, `App.test.ts`, `CaptureForm.test.ts` + a guardrail test); `vitest`
  (44 tests) + `vue-tsc` typecheck + ESLint on touched files all green.
- [ ] 4.3 DEFERRED (named follow-on, re-propose M5 `agreement-attributes-and-pinning` fresh): FULL
  preview/draft parity for **non-default** `(state, type)` and for **user-set** template-declared
  fields needs the backend to (i) persist per-agreement attributes (attributes store), (ii) map via the
  pinned `FormSchema` (schema-driven, dimension-aware generate reading core column OR attribute store),
  and (iii) carry the agreement's chosen dimensions into generate-as-draft. When that lands, lift the
  picker constraint (make non-default `(state, type)` selectable) and unhide the persisted fields. The
  eSign/webhook flow, capture form structure, admin builder, and layer registry remain named follow-on
  CRs.
