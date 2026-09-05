## 1. Gap A -- record the selected template at create (`in.agreementmitra.signing`)

- [x] 1.1 `CreateAgreementRequest`: add OPTIONAL `state` + `type` (trailing record components)
  plus a backward-compatible 6-arg constructor (delegates with `null, null`) so existing call
  sites and no-dimension JSON bodies keep today's default behaviour.
- [x] 1.2 `AgreementService.create`: inject `documents.api.TemplateCatalogApi`; when both
  dimensions are present, resolve via `publishedTemplateIdFor(state, type)` and record through
  `Agreement.selectTemplate(UUID)` (server-managed); reject an uncovered pair with the no-oracle
  `ResourceNotFoundException` (404) BEFORE persisting; absent pair -> no selection.

## 2. Gap B -- dimension-aware generate + per-agreement preview (`in.agreementmitra.signing`)

- [x] 2.1 `AgreementDocumentService`: inject `TemplateCatalogApi`; in the shared `render` path
  (feeds both `renderForDraft` and `renderPreview`), pass `new DocumentDimensions(state, type)`
  resolved from `detail(templateId).dimensions()` when the agreement has a `templateId`, else
  `null`. Do NOT change the pin code -- it auto-corrects to the rendered template's identity.

## 3. Fixture -- TG stampDuty default (`documents` resources)

- [x] 3.1 `layers/state-TG.patch.yaml`: give the required `stampDuty` field a system-authored
  default so generate-as-draft renders it from the template default (no attributes store).

## 4. Frontend (`frontend/src`)

- [x] 4.1 `api/client.ts`: `CreateAgreementInput` gains optional `state`/`type`; the create call
  passes them through.
- [x] 4.2 `views/CaptureForm.vue`: thread `props.state`/`props.type` into the create payload;
  add `stampDuty` to `NON_PERSISTED_FIELDS` (hide + strip); refresh the parity comment.
- [x] 4.3 `components/TemplatePicker.vue` + `App.vue`: LIFT the "Coming soon" constraint -- every
  published template is selectable now that generate-as-draft is dimension-aware. **M6-owned;
  flagged in handoff.**

## 5. Tests -- unit (no Spring context)

- [x] 5.1 `AgreementServiceTest`: create with `(state, type)` resolves via `TemplateCatalogApi`
  and records the returned UUID on the aggregate; an unknown pair throws
  `ResourceNotFoundException` and persists nothing; an absent pair keeps `templateId` null and
  never touches the catalog. (Mockito.)
- [x] 5.2 `AgreementDocumentServiceTest`: a selected template makes `render` pass the agreement's
  real `(state, type)` dimensions to the projection; an unselected agreement passes `null`
  dimensions and never calls the catalog. (Mockito, argument-captured.)

## 6. Tests -- integration (Testcontainers PG + MinIO + Gotenberg; skips cleanly without Docker)

- [x] 6.1 The money test: seed IN + TG catalog rows, create with `(TG, residential)` ->
  generate-as-draft -> assert the pinned `template_content_hash` equals the TG effective-template
  contentHash (from the public form port) and NOT the IN hash; assert the stateless preview for
  the same `(TG, data)` renders the TG overlay (stamp-duty clause + "in advance") that the draft
  used, while the IN preview does not (parity).
- [x] 6.2 Create with a `(state, type)` no published template covers -> clean 404 rejection, no
  echoed dimensions.

## 7. Frontend tests (Vitest)

- [x] 7.1 `TemplatePicker.test.ts`: every published template is selectable and none shows
  "Coming soon"; selecting a non-default (TG residential) emits `(TG, residential)`.
- [x] 7.2 `App.test.ts`: pick -> fill -> preview -> Save & continue still creates + generates
  (unchanged happy path).

## 8. Verify

- [x] 8.1 Backend: unit (5.1, 5.2) + integration (6.1, 6.2) + `ModularityTests` green (Windows:
  gradle directly, `TESTCONTAINERS_RYUK_DISABLED=true`, `-Duser.timezone=Asia/Kolkata`);
  `spotlessApply`. Existing generate/create/selection integration tests still green.
- [x] 8.2 Frontend: vitest + `vue-tsc --noEmit` clean.
- [x] 8.3 Live smoke: create `(TG, residential)` + generate on the running local stack; pinned
  `template_content_hash` equals the TG form contentHash and differs from a default IN agreement.
