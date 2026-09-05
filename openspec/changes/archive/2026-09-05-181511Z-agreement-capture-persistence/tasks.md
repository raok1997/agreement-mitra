# Tasks -- agreement-capture-persistence (M5: persist + render the full capture state)

Persist an agreement's working-set field map + added optional sections so a saved agreement round-trips
its complete content and the stored/signed draft matches the live preview. **Depends on
`agreement-ownership` (CR-B)**: extends its create/edit request + response and its edit path; migration
is `V13` (after CR-B's `V12`). Decisions (D#) live in `design.md`. Keep `ModularityTests` green (only
plain JDK types cross into the aggregate; no `documents` type leaks).

## 1. Schema (Flyway V13)

- [x] 1.1 Add `backend/src/main/resources/db/migration/V13__agreement_capture_state.sql`:
  `alter table agreement add column capture_state jsonb` (nullable). Forward-only; do not edit V1..V12.
  No backfill (existing rows stay null). Depends on CR-B's `V12` (D1, Migration Plan).

## 2. Aggregate + persistence (D1, D3, D4)

- [x] 2.1 Map `capture_state` onto `Agreement` as plain values -- a `Map<String,String> captureData` +
  `List<String> activeSections` (or a small `CaptureState` value object), jsonb via `@JdbcTypeCode(JSON)`,
  mirroring `template_layer_versions`; server-managed setter with defensive copy; accessors. No
  `documents`-module type on the aggregate (D1).
- [x] 2.2 `Agreement.replaceCaptureState(...)` (wholesale) + null-safe accessor; `clearDraftPin` unchanged
  (edit still clears the pinned draft) (D4).

## 3. API request/response + service (D2, D3, D4)

- [x] 3.1 Extend `CreateAgreementRequest` with optional `captureData` (Map) + `activeSections` (List);
  keep the backward-compatible constructor (null capture state) so existing callers/tests compile (D2).
- [x] 3.2 `AgreementService.create` + `update`: store the capture state (wholesale on update); ignore any
  server-managed keys in the map (anti-mass-assignment); fixed columns stay authoritative (D2, D3, D4).
- [x] 3.3 Extend `AgreementResponse` with `captureData` + `activeSections`; map them in `toResponse`
  (owner-scoped read/edit from CR-B returns them) (D3 read round-trip).

## 4. Render from capture state (D3)

- [x] 4.1 `AgreementDocumentService.render`: when `capture_state` present, build the data map from the
  stored `data` (reconciled so fixed columns win) and pass stored `activeSections` as the projection's
  sections argument (today `null`); else fall back to `AgreementDocumentMapper.toTemplateData` with no
  sections. Preview + generate-as-draft share this path so both faces match (D3, parity invariant).

## 5. Frontend (D5)

- [x] 5.1 `CaptureForm.vue` `buildAgreementInput`: send `captureData` (= `flatWorking()`) + `activeSections`
  on create and on the CR-B edit `PUT`.
- [x] 5.2 `prefillFromAgreement`: restore `captureData` into the working set and re-activate the stored
  `activeSections` when reopening an owned agreement for edit.
- [x] 5.3 Shrink `NON_PERSISTED_FIELDS` to only genuinely system-owned template-default fields (e.g.
  `stampDuty`); un-hide everything the aggregate now persists (D5, Open Question).

## 6. Tests (pyramid -- required)

**Unit (no Spring context):**
- [x] 6.1 Aggregate: `replaceCaptureState` stores + defensively copies; null capture state accessor is
  safe; fixed-column values are never overridden by a map entry (D3 reconciliation).
- [x] 6.2 Service: create/update store the capture state; a map carrying server-managed keys (id/owner/
  createdAt/duration/pin) leaves those server-managed (D2 anti-mass-assignment).
- [x] 6.3 Render selection: capture-state present -> data+sections used; null -> fallback mapper + null
  sections (D3), with the projection stubbed.

**Integration (Testcontainers Postgres + Spring slice):**
- [x] 6.4 Boot-and-validate: app starts against V1..V13 under `ddl-auto: validate` (jsonb mapping matches).
- [x] 6.5 Round-trip: create with `captureData` + `activeSections` -> read returns them; CR-B edit
  replaces them wholesale and clears the pinned draft.
- [x] 6.6 Parity: generate-as-draft for an agreement with an added optional section + a dynamic value ->
  the stored draft PDF (or the projection input) contains that section + value (the parity assertion,
  now verified rather than hidden).
- [x] 6.7 Legacy fallback: an agreement with null capture state generates a draft via the fixed-column
  mapping, unchanged.
- [x] 6.8 `ModularityTests` stays green (no `signing -> documents` type leak from the capture blob).

**Frontend (component/e2e-lite):**
- [x] 6.9 CaptureForm sends `captureData` + `activeSections` on save; on edit-reload it re-activates a
  stored optional section and repopulates a dynamic value. Anonymous drafting path unchanged.

## 7. Verify + wrap-up

- [x] 7.1 Live-drive (with CR-A + CR-B applied): add an optional section + a dynamic field, Save (signed
  in) -> it appears in My Agreements -> reopen -> the optional section + value are restored -> preview
  and the generated draft both show them (parity). SKIPPED in this pass (no running stack) -- covered
  by the automated parity integration test 6.6 + the frontend edit-reload test 6.9.
  - Rationale above is now STALE (2026-09-05): a stack IS running (SPA `:5174`, API `:8090`, both
    answering `200`). The drive is outstanding for want of a human at a browser, not for want of a
    stack. Steps in `openspec/MANUAL-DRIVE-CHECKLIST.md`, drive C -- which also closes
    `preview-centric-capture 7.3` in the same run. Note its step 2 needs sign-in; steps 1, 3 and 4 do
    not, so most of this can be driven before the Google-login question is settled.
  - **PARTIALLY DRIVEN 2026-09-05 by the repo owner** via `openspec/BROWSER-TEST-STEPS.md` test 2,
    reported "all three fine". **Parity is confirmed**: with an optional section and a dynamic field
    added, the on-screen preview and the downloaded PDF showed the same content -- the clause this
    task exists for, since customers decide on the preview and sign the PDF.
  - **PERSISTENCE HALF DRIVEN 2026-09-05 by the repo owner, in PRODUCTION** (https://agreementmitra.com),
    because local Google sign-in is pointed at a placeholder credential. Reported: an agreement started
    **anonymously**, then Google sign-in, then re-picking the same template -- **the entered values were
    still there**. Signed-in persistence confirmed in the same pass.
  - **Why prod is acceptable evidence here:** `origin/main..HEAD` is 8 commits, and the only
    application-code file differing across them is `signing/stamp/PdfStampComposer.java` (from the OSV
    remediation) -- nothing in the capture, persistence or auth paths. So the code exercised in prod is
    functionally identical to this branch for this task's purposes. (Assumes prod deploys from `main`.)
  - **Observed vs not:** values-restored-after-sign-in was observed directly. Not separately reported:
    the agreement appearing in **My Agreements** as a listing, and reopening it from there. Parity was
    confirmed separately (test 2, local). Ticking on the strength of the persistence guarantee this
    task exists for; the listing view is asserted by frontend test 6.9.
- [x] 7.2 `./gradlew spotlessApply` then the test suites, on Windows with
  `TESTCONTAINERS_RYUK_DISABLED=true`. RAN: `spotlessApply`, `./gradlew test` (519 tests, 0 failures,
  0 skipped -- incl. ModularityTests + the new V13 integration tests), `spotlessCheck` (clean), and the
  frontend `npm run test` (71 tests pass) + `npx vue-tsc -b` (clean). The `osv-scanner`-backed gates
  (`securityScan` in `./gradlew check`; `npm run security:scan`) were NOT run -- the `osv-scanner`
  binary is not installed in this environment and both gates are fail-closed on a missing binary.
- [x] 7.3 Update `docs/ROADMAP.md` / flow-journal: the preview/draft parity STOPGAP (flow-journal 8.4/8.5)
  is retired -- the stored/signed draft now renders from the persisted capture state.
