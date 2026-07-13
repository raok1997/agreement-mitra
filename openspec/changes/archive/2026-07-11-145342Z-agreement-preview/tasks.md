## 1. Agreement mapping + render service (`in.agreementmitra.signing.agreement`)

- [x] 1.1 `AgreementDocumentMapper` (package-private): from an `Agreement`, build the template data
  map -- `owners`/`tenants` as lists of `{name, fatherName, currentAddress}` grouped by role, plus
  `propertyAddress`, `monthlyRent`, `securityDeposit`, `startDate`, `endDate`, `durationMonths`,
  `agreementDate`. No PII dropped or mislabelled.
- [x] 1.2 `AgreementDocumentService` (Java-`public`, `@Transactional(readOnly = true)`): load the
  agreement (404 -> `ResourceNotFoundException`), map it, call the `documents`
  `DocumentRenderer.renderPdf("rental-agreement", data)`, return PDF bytes. Stores nothing. Depends
  only on the public `DocumentRenderer` interface.

## 2. Preview endpoint (`in.agreementmitra.signing.api`)

- [x] 2.1 `AgreementController.preview`: `GET /api/agreements/{id}/preview` -> `ResponseEntity<byte[]>`
  with `Content-Type: application/pdf`, `Content-Disposition: inline`, `Cache-Control: no-store`.
  `{id}` bound as `UUID` (non-UUID -> 400). Never logs bytes or party details.

## 3. India-standard template (`in.agreementmitra.documents` resource)

- [x] 3.1 Rewrote `documents/rental-agreement.html` into a complete India-standard residential
  rental agreement: agreement date; lessor/owner + lessee/tenant blocks (name/father/address);
  scheduled property; term (start/end/duration); rent + security deposit; standard clauses
  (residential use, rent payment, maintenance/utilities, care of premises, subletting, notice &
  termination, inspection, handover, stamp-duty/registration); party signatures (no witness block --
  Aadhaar-eSigned, only parties sign). Dynamic values via `th:text` (escaped); clauses static. Still
  one bundled template, fully offline.

## 4. Security wiring

- [x] 4.1 Permit `GET /api/agreements/*/preview` in `SecurityConfig` (method-and-path-scoped),
  consistent with the anonymous agreement read/draft paths; TEMPORARY/sandbox comment kept.

## 5. Frontend (`frontend/src`)

- [x] 5.1 `src/api/client.ts`: `fetchAgreementPreview(id): Promise<string>` -- GET the preview,
  verify `ok`, return `URL.createObjectURL(blob)`; friendly error via `describeProblem`.
- [x] 5.2 `CaptureForm.vue`: after an agreement is created, a **Preview** button fetches and
  **embeds** the PDF (`<iframe>` bound to the object URL); URL revoked on replace/unmount. Anonymous.

## 6. Tests -- unit (no Spring context)

- [x] 6.1 `AgreementDocumentMapperTest`: owners + tenants map to a data map grouped by role with
  every field present and correctly placed. Green (1 test).

## 7. Tests -- integration (Testcontainers; skips cleanly if Docker absent)

- [x] 7.1 `AgreementPreviewIntegrationTest`: preview -> `200`, `application/pdf`, inline,
  `no-store`, `%PDF-`; nothing persisted (draft key still null). Green.
- [x] 7.2 Unknown id -> `404`; non-UUID -> `400`. Green.
- [x] 7.3 India-standard sections: PDFBox text extraction asserts parties (by role, with father's
  name), property, rent/deposit, and clause content (rental agreement, security deposit,
  residential, termination). Green.
- [x] 7.4 Log hygiene: root `ListAppender` over a preview -- no PDF bytes, no party PII in logs.
  `ModularityTests` green (signing -> documents via the public interface only). Green.
  Note: signing's first cross-module dependency required a `@MockitoBean DocumentRenderer` in the
  standalone `SigningModuleSliceTest`.

## 8. Frontend tests (Vitest)

- [x] 8.1 `CaptureForm.test.ts`: the Preview button calls `fetchAgreementPreview` (mocked) and
  embeds the returned object URL; asserts the request targets the id and no PII is logged. Green (6
  frontend tests total).

## 9. Verify

- [x] 9.1 Backend: full `test` (172) + `spotbugsMain` + jacoco + `ModularityTests` green (Windows:
  gradle directly, Ryuk disabled, `-Duser.timezone=Asia/Kolkata`; `osvScan` owed -- scanner absent).
- [x] 9.2 Frontend: vitest + `vue-tsc` + `vite build` clean.
- [x] 9.3 Manual: live stack -- `GET /api/agreements/{id}/preview` returned a 39 KB inline
  `%PDF-1.4` (no-store) India-standard document for the user's agreement. Awaiting user eyeball of
  the Preview button in the app.
