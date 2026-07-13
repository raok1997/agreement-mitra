## Context

CR-3a exposed `DocumentRenderer.renderPdf(templateId, data)` (the `documents` module's public API)
and a bundled `rental-agreement.html`. This increment wires an `Agreement` to it and previews the
result inline, and upgrades the template content to an India-standard format. Decisions D3/D5/D6
from the retired proposal are realized here; new decisions cover the mapper, the endpoint, and the
frontend embedding.

Constraints unchanged: Java 21 + Spring Boot 3.5.x + Spring Modulith; records for DTOs; constructor
injection; package-private by default; keep `ModularityTests` green; sandbox + dummy data only;
never log PII or PDF bytes.

## Goals / Non-Goals

**Goals:**
- Map an `Agreement` (parties by role, property, money, dates, duration) to the template data map.
- `GET /api/agreements/{id}/preview` -> inline `application/pdf`, `no-store`, rendered on demand,
  not stored; 404 for unknown id.
- Upgrade the bundled template to a complete India-standard residential rental agreement.
- Frontend Preview button that embeds the returned PDF.

**Non-Goals:**
- No storing the preview (that is CR-3c's generate-as-draft). No new captured agreement fields
  (standard clauses are boilerplate). No auth/ownership (anonymous, like the rest of the draft
  path). No template catalog/selection (CR-2). No schema change; no FSM/stamping/eSign change.

## Decisions

### D-B1: `signing` owns the agreement-to-data mapping; `documents` stays generic

An `AgreementDocumentMapper` in `in.agreementmitra.signing.agreement` builds a
`Map<String,Object>` from an `Agreement` -- `owners` and `tenants` as lists of `{name, fatherName,
currentAddress}` maps, plus `propertyAddress`, `monthlyRent`, `securityDeposit`, `startDate`,
`endDate`, `durationMonths`, and `agreementDate` (the agreement's created date). It lives in the
`agreement` package so it can read the aggregate's package-private accessors. `documents` still
knows only a template id + a generic map. **Alternative rejected:** agreement knowledge inside
`documents` -- couples the render module to the domain (D3).

### D-B2: A read-only `AgreementDocumentService` renders on demand

`AgreementDocumentService` (Java-`public`, injected into the controller) depends on
`AgreementRepository`, `AgreementDocumentMapper`, and the `documents` `DocumentRenderer`. Its
`renderPreview(UUID)` is `@Transactional(readOnly = true)` so the lazy signer collection initializes
inside the tx (like `AgreementService`), maps, and calls `renderPdf("rental-agreement", data)`.
Unknown id -> `ResourceNotFoundException` (mapped to 404 problem+json). It **stores nothing**.
`signing` depends only on the public `DocumentRenderer` interface -> `ModularityTests` stays green.

### D-B3: Preview streams inline, non-cacheable, never logged (D6)

`AgreementController.preview` returns `ResponseEntity<byte[]>` with `Content-Type: application/pdf`,
`Content-Disposition: inline`, and `Cache-Control: no-store`. The bytes are never logged; no party
detail is logged on this path. Rendered on demand; nothing persisted. **Alternative rejected:**
store-on-preview -- wasteful and pins PII for every preview (that is the explicit generate step,
CR-3c).

### D-B4: The bundled template becomes a complete India-standard rental agreement

`documents/rental-agreement.html` is rewritten to a standard Indian residential rental (leave &
licence / rental) agreement: a heading with the agreement date; the **Lessor(s)/Owner(s)** and
**Lessee(s)/Tenant(s)** blocks (full name as per Aadhaar, father's name, current address); the
**scheduled property**; **term** (start, end, duration in months); **rent** (amount in figures) and
**security deposit**; and the standard **clauses** -- residential use only, payment/among-parties
terms, maintenance & utilities, care of premises, notice & termination, return of deposit, and a
**stamp-duty / registration** note; ending with a **party signatures** block. **No
attesting-witness block** -- the agreement is executed by Aadhaar eSign (only the parties sign; a
rental/leave-and-licence agreement requires no witness attestation, and the eSign audit trail serves
the evidentiary role that witnesses traditionally did). All dynamic values are `th:text` (escaped);
the clauses are static, reviewed markup. Still one bundled template,
still fully offline (system Noto fonts). This changes only template *content*, not the render
contract. **Alternative rejected:** adding new captured fields (rent due-date, notice period, etc.)
-- out of scope; sensible standard defaults are used in the boilerplate instead.

### D-B5: Frontend embeds the preview via an object URL

`src/api/client.ts` gains `fetchAgreementPreview(id): Promise<string>` -- `GET`s the preview,
verifies `ok` + `application/pdf`, and returns `URL.createObjectURL(blob)`. `CaptureForm.vue` shows
a **Preview** button once an agreement exists; clicking fetches and binds the object URL into an
`<iframe>`/`<object>`; the URL is revoked on replace/unmount. No PII is written to the console.
**Alternative rejected:** pointing the iframe `src` straight at the API path -- works, but the
fetch path gives clean error handling and a testable seam, and avoids a second uncontrolled GET.

## Risks / Trade-offs

- **PII in a streamed document** -- mitigated by `no-store`, never-logged, not-stored, escaped
  (checklist in the proposal).
- **Template correctness** -- an India-standard template is longer; covered by a render assertion
  that the filled document contains the party/property/term data, and the existing escaping test.
- **Preview latency** -- one synchronous Gotenberg call (a few seconds); acceptable for an
  on-demand preview, bounded by the CR-3a client timeout.

## Migration Plan

**No database migration.** A template-resource rewrite, new `signing` classes, one endpoint, one
security matcher, and frontend changes. No dependency changes (Gotenberg + Thymeleaf already land in
CR-3a), so no lockfile change.

## Open Questions (resolved)

- **Preview format** -- PDF inline (highest fidelity to the signed artifact). Confirmed.
- **New captured fields for the template?** No -- standard clauses are boilerplate; the template
  uses the existing captured data + sensible defaults.
