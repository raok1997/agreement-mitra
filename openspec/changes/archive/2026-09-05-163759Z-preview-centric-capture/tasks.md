# Tasks -- preview-centric-capture

Only **Phase 1** is tasked here (the preview-centric shell + stateless partial preview). Later phases
(the section catalog in proposal.md) are listed as a backlog at the bottom and become their own CRs.

## 1. Renderer: partial data + HTML output (`in.agreementmitra.documents`)

- [x] 1.1 Make the template + assembler **partial-tolerant**: empty/missing party lists **and blank
  scalar fields** (dates, money, `agreementDate`, property address) render placeholders via
  `th:if`/`th:text` defaults (e.g. "[ add owner details ]", "[ rent not set ]"), never an error and
  never a bare `null`. Define the preview value of `agreementDate` when there is no persisted
  `createdAt` (today's date on the render host, or a "[ date on signing ]" placeholder — pick one and
  state it in the template comment).
- [x] 1.2 Expose composed **HTML** from the module: add `DocumentRenderer.renderHtml(templateId, data)`
  (or a sibling) returning the self-contained escaped HTML (the assembler's output), alongside
  `renderPdf`. Keep it package-private-internal except the interface method. `ModularityTests` green.
- [x] 1.3 **Embed the Noto faces as `@font-face` data-URIs** in the composed HTML (or a browser
  variant) so the live pane shapes Devanagari/Telugu/complex scripts the same way Gotenberg does — the
  browser has no fontconfig access to the Gotenberg image's faces, so an un-embedded HTML pane falls
  back to system fonts and mis-shapes exactly the vernacular scripts the preview must reassure users
  about. (PDF path is unaffected — Gotenberg keeps using its bundled faces.)

## 2. Stateless preview endpoint (`in.agreementmitra.signing.api`)

- [x] 2.1 A **distinct** in-progress agreement DTO (not the same record — `CreateAgreementRequest`
  bakes in `@NotBlank`/`@NotNull`, which cannot be "reused without constraints"). Relax
  **required-ness** (blank/missing fields, empty party lists allowed) but **retain the bounds**:
  `@Size` on the signer list and per-field length caps, so a partial preview can't be weaponised into
  a giant-payload render (see 2.3). Preserve the anti-mass-assignment posture — no
  `id`/`createdAt`/`termMonths`/`duration` on the DTO (server-derived only).
- [x] 2.2 A partial-tolerant DTO->data-map mapper that yields **the same data map** the persisted path
  produces. **Single-source the mapping** so preview and the final signed PDF cannot diverge: either
  route the DTO through the existing `AgreementDocumentMapper` (via a transient, non-persisted
  aggregate — verify `Agreement` construction tolerates partial data; if its invariants reject a
  half-filled draft, record that and map directly instead), or extract one shared map-builder both
  paths call. Derive `durationMonths` (only when both dates present), `agreementDate`, and the party
  full-name (first+last fallback) here — do not leave them null. A **parity test** (4.2) locks the two
  paths together.
- [x] 2.3 `POST /api/agreements/preview`: render the posted in-progress data and return the document
  **inline**, **content-negotiated** -- `application/pdf` (default) or `text/html` (`Accept:
  text/html`) for the live pane; `Cache-Control: no-store`; a `Content-Security-Policy` on the HTML
  variant; **persists nothing**; never logs bytes/HTML **or validation-error field values**. Permit
  the path in `SecurityConfig` (method+path scoped, fail-closed). Ensure CORS covers the `text/html`
  response for the dev cross-origin (Vite->backend) fetch.
- [x] 2.4 **Rate-limiting is a required companion before this leaves sandbox** -- the preview render
  path has no persisted-agreement precondition (unlike `GET /{id}/preview`), so it is the cheapest
  render to abuse; record it as an owed follow-up alongside the other deferred ownership/rate-limit
  work.

## 3. Frontend: preview-centric shell (`frontend/src`)

- [x] 3.1 A **working-data store** (reactive, section-keyed) holding the in-progress agreement.
  If localStorage is used for refresh-resume, treat it as **client-side PII at rest** (names,
  father's names, addresses) on a possibly-shared/kiosk device: **clear on successful Save & continue
  and on explicit abandon/reset**, and apply a short TTL on stale drafts. Update the PII checklist —
  "persists nothing" holds server-side only.
- [x] 3.2 A **section registry** (`{id, label, completeness rule, modal, data slice}`) driving the
  status bar and the edit controls -- so future sections are additive. The per-section **completeness
  rule must mirror the server's final-save validation** (single source or derived), so the status bar
  cannot read "complete" and then have Save & continue reject with a 400.
- [x] 3.3 Responsive **shell**, implemented to the **locked reference UI**
  (`assets/preview-centric-mockup.html`, see design D5): desktop two-pane (sections left / sticky live
  preview right); tablet one-column guided sections + a top-bar "Sections/Preview" toggle; phone
  full-screen bottom-sheet section editors + single-column form (focus-trap + Esc at every width).
  Live preview embeds the HTML in a **sandboxed iframe**, debounced on save.
- [x] 3.4 Section modals for **Tenant / Owner / Property** editing the store; per-section validation.
- [x] 3.5 A **completeness status bar** (which required sections are done / pending).
- [x] 3.6 **Download PDF** (request the PDF variant) and **Save & continue** -> existing
  `createAgreement` then `generateAgreementDocument`. Keep API calls in `src/api/client.ts`
  (`fetchWorkingPreview` for HTML/PDF).
- [x] 3.7 Keep the current sequential form reachable until the new shell is validated (swap the route
  when ready); **file a retirement task to remove the old form** once the shell is validated, so two
  capture paths don't drift indefinitely.
  - Resolved 2026-09-05 by inspection: **the transitional two-path state never existed and cannot
    now be created.** `CaptureForm.vue` was converted in place into the preview-centric shell (two-pane
    `lg:grid-cols-[minmax(320px,420px)_1fr]`, sticky sandboxed-iframe preview) rather than being added
    alongside the sequential form, and `App.vue` imports exactly one capture view. There is no second
    route to keep reachable and no old form left to retire, so both halves of this task are moot.
  - Consequence to be honest about: the intended safety net -- validate the shell *before* dropping
    the old path -- was skipped. 7.3's validation drive is therefore not a gate before a swap; it is a
    check on the only capture path the app has. That raises its importance rather than lowering it.

## 4. Tests -- unit

- [x] 4.1 Partial-tolerant mapper/assembler: a data map missing parties **and blank scalar fields**
  assembles HTML with the placeholder text and no error (no bare `null`); present fields appear;
  markup in a value is escaped.
- [x] 4.2 **Mapping parity**: the same in-progress input mapped by the preview path and (once
  persisted) by `AgreementDocumentMapper` yields the **same data map** — guards against preview/final
  divergence (finding: two mappers).
- [x] 4.3 The in-progress DTO **rejects oversized input** (signer list past `@Size` max, over-long
  fields) with a 400 even though required-ness is relaxed — locks the render-DoS bound (2.1/2.3).

## 5. Tests -- integration (Testcontainers; skips cleanly if Docker absent)

- [x] 5.1 `POST /api/agreements/preview` with **partial** data -> `200`, inline, `no-store`; PDF
  variant begins `%PDF-`; HTML variant (`Accept: text/html`) returns escaped HTML with placeholders;
  **nothing persisted** (no agreement row created).
- [x] 5.2 Full data -> the document composes the parties/property/terms (as CR-3b). Log hygiene: no
  PDF/HTML bytes or party PII in logs. `ModularityTests` green.

## 6. Frontend tests (Vitest)

- [x] 6.1 Section save updates the working set and triggers a preview refresh (mocked fetch);
  completeness bar reflects filled vs pending; **no PII logged**.
- [x] 6.2 "Save & continue" calls create then generate; "Download PDF" requests the PDF variant.
- [x] 6.3 If localStorage resume is implemented: the working-set draft is **cleared after a successful
  Save & continue and on explicit reset** (guards client-side PII at rest).

## 7. Verify

- [x] 7.1 Backend: unit + integration + `spotbugsMain` + `ModularityTests` green (Windows: gradle
  directly, Ryuk disabled, `-Duser.timezone=Asia/Kolkata`; `osvScan` owed).
- [x] 7.2 Frontend: vitest + `vue-tsc` + `vite build` clean.
- [x] 7.3 Manual: on desktop, edit sections and watch the live preview fill in; on a narrow viewport,
  confirm the guided + toggle-preview flow and phone bottom-sheet editors (against the locked
  reference UI); Download PDF; Save & continue persists a draft. **On a real phone** additionally
  verify the embedded-Noto font payload on a slow connection and iframe scroll/zoom on iOS Safari
  (design D5).

## Backlog -- future phases (become their own CRs; see proposal.md catalog)

- **Phase 2 (custom-conditions starter set, absorbs CR-3d):** financial knobs (due date, escalation,
  penalty, deposit terms, maintenance, utilities, painting), tenancy rules (lock-in, notice,
  occupants, pets, parking), furnishing + **fixtures inventory annexure**; a flexible
  agreement-attributes store for persistence (+ migration).
- **Phase 3+:** additional parties / co-signers (catalog E), clause library (F = CR-3e), template
  catalog + state + language / bilingual (G = CR-2/CR-4), computed rent-schedule table, click-to-edit
  regions in the preview.
  - **DRIVEN 2026-09-05 by the repo owner against the live stack** (SPA `:5174` / API `:8090`), via
    `openspec/BROWSER-TEST-STEPS.md` tests 1 and 3; reported "all three fine".
    - Desktop: added an optional section + a field and the live preview filled in as typed.
    - Narrow viewport: full-screen bottom-sheet section editor, focus trap held under Tab, Esc closed
      it.
  - This is the drive that 3.7 says the shell was supposed to be validated by. It has now happened --
    after the old sequential form was already gone rather than before, but it has happened.
