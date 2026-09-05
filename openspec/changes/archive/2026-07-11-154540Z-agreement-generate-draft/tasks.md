## 1. Generate endpoint (`in.agreementmitra.signing.api`)

- [x] 1.1 `AgreementController.generateDocument`: `POST /api/agreements/{id}/document` -- render via
  `AgreementDocumentService.renderPreview(id)` then store via `DraftService.attachDraft(id, pdf)`;
  respond `200 OK` with `{"agreementId": id}`. `{id}` bound as `UUID` (non-UUID -> 400). The
  controller orchestrates the two proxied calls (render tx, then store tx). Never logs bytes.
  - 404 (unknown agreement) comes from `renderPreview`; 409 (draft locked) from `attachDraft`.

## 2. Security wiring

- [x] 2.1 Permit `POST /api/agreements/*/document` in `SecurityConfig` (method-and-path-scoped),
  consistent with the anonymous draft-upload path; keep the TEMPORARY/sandbox comment style.

## 3. Frontend (`frontend/src`)

- [x] 3.1 `src/api/client.ts`: `generateAgreementDocument(id): Promise<void>` -- POST the generate
  endpoint; throw a friendly message via `describeProblem` on non-2xx (incl. the `409` locked case).
- [x] 3.2 `CaptureForm.vue`: a **"Use this document"** button (next to Preview, shown once an
  agreement exists) that generates the draft and confirms it is saved as the signable draft, ready
  for signing; a `409` shows the "already being signed" message. Anonymous.

## 4. Tests -- unit (no Spring context)

- [x] 4.1 `AgreementController` generate orchestration (Mockito): `generateDocument` calls
  `renderPreview(id)` and passes the returned bytes to `DraftService.attachDraft(id, bytes)`, and
  returns `200` with the agreement id. (Order + wiring, no Spring, no I/O.)

## 5. Tests -- integration (Testcontainers; skips cleanly if Docker absent)

- [x] 5.1 Generate stores the draft: `POST /api/agreements/{id}/document` -> `200`; `draft_pdf_key`
  is set and the stored blob (`drafts/{id}.pdf`) begins with `%PDF-`; a subsequent draft is present
  for signing.
- [x] 5.2 Overwrite while unsigned: a second `POST .../document` succeeds and replaces the stored
  draft (still a valid PDF under the same key).
- [x] 5.3 Locked once signing exists: after inserting a `signing_request` row, `POST .../document`
  -> `409` (`draft-frozen`), and the previously stored draft is unchanged.
- [x] 5.4 Unknown id -> `404`; non-UUID -> `400`. `ModularityTests` stays green.

## 6. Frontend tests (Vitest)

- [x] 6.1 Component test: the "Use this document" button calls `generateAgreementDocument` (mocked)
  and shows the saved/ready confirmation; a rejected call surfaces the error; no PII logged.

## 7. Verify

- [x] 7.1 Backend: unit (controller orchestration) + integration (generate, 4) + `spotbugsMain`
  (SAST clean) + `ModularityTests` green (Windows: gradle directly, Ryuk disabled,
  `-Duser.timezone=Asia/Kolkata`). Verified per-class + container-free gates: the single-JVM full
  `test` is flaky at exit (Ryuk-disabled container reaper, 3 Gotenberg containers) but every test
  passes (no failure in any result XML). `osvScan` owed -- scanner absent.
- [x] 7.2 Frontend: vitest + `vue-tsc` + `vite build` clean.
- [x] 7.3 Manual: on the live stack, create an agreement, Preview, then "Use this document"; confirm
  a draft is stored (and that a second generate overwrites while unsigned).
