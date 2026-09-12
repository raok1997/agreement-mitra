## Why

Stamping today is a **synthetic** Karnataka INR 100 stamp composited automatically inside
`SigningRequestService.create()` with `dutyPaid = false` - dummy data that is not a legally
stampable instrument. The product decision is that **AgreementMitra staff purchase a real
e-stamp from the SHCIL portal out-of-band**, print it, scan it, and upload the scan against
the customer's order. Only then is eSign initiated.

That makes stamping a **human, asynchronous step** rather than an in-process function call.
The current design cannot express it: procurement is synchronous, the serial is derived from
the agreement id rather than a real certificate, and no role exists that could authorise a
staff upload. Nor is there a reliable way for staff to *find* the agreement - the
`AM-<LAST6>-<DDMMYY>` tracking number from `document-footer-tracking-url` is derived at render
time, never persisted, and its own spec records that its 24-bit fragment is **not
collision-free**. Staff resolving a stamped legal instrument by a colliding reference is a
correctness defect, not a UX wrinkle.

## What Changes

- **BREAKING - stamping stops being automatic.** The auto-stamp step is removed from
  `SigningRequestService.create()`. `PDF_GENERATED` becomes the durable "awaiting stamp
  upload" state, and initiating eSign without an attached stamp is rejected with `409`.
- **BREAKING - the synthetic stamp adapter is removed.** `SyntheticKarnatakaStampProvider`
  and the deterministic BW-series serial go away. `StampProvider.procure(agreementId,
  draftPdf)` is replaced by an intake operation that takes the **staff-supplied scan plus its
  SHCIL certificate metadata**. `dutyPaid` becomes genuinely `true`.
- **New staff-only stamp intake endpoint.** Accepts a scanned certificate image and the SHCIL
  fields: certificate number, issue date, stamp duty amount, state/jurisdiction, description
  of document, and purchased-by.
- **Single-use certificate ledger.** The SHCIL certificate number carries a **uniqueness
  constraint**. An SHCIL e-stamp is single-use; reusing one across two agreements is a legal
  defect, so a duplicate upload is rejected with `409` rather than silently accepted.
- **Composition switches source.** `PdfStampComposer` prepends the **uploaded scan** as page 1
  (fit-to-page, aspect preserved) instead of a generated template, and the per-page overlay
  carries the **real certificate number** instead of a synthetic serial. Only JPEG/PNG are
  accepted; magic bytes, pixel dimensions, and byte size are validated, failing closed exactly
  as untrusted-draft parsing already does.
- **New STAFF role.** No role concept exists today (V11 added OAuth identity, V12 added
  agreement ownership). Only `STAFF` may upload a stamp; everyone else gets `403`, and the
  endpoint must not become an existence oracle for agreements the caller does not own.
- **Persisted, unique staff reference.** A collision-free reference is persisted on the
  agreement so staff can locate it deterministically. The display-only tracking number is
  **not** promoted to a lookup key.
- **StampInfo extended** with the real certificate fields; the existing nullable-until-stamped
  contract is preserved.

Deliberately **not** in scope, and noted as dependencies:

- **Payment / order capture** - absent from the backend entirely. This change models a
  "ready to stamp" trigger without assuming a payment integration.
- **The ZOOP eSign adapter** - separate change (`zoop-esign-provider`).
- **Automated SHCIL certificate verification** against their portal. For v1 staff attest to
  what they uploaded; automated verification is recorded as future hardening.

## Capabilities

### New Capabilities

- `estamp-intake`: staff-authenticated intake of a purchased SHCIL e-stamp certificate - the
  upload contract, certificate-metadata validation, the single-use certificate ledger, the
  deterministic staff-facing agreement lookup, and the intake audit record.

### Modified Capabilities

- `document-stamping`: the stamp source changes from a synthetic generated template to an
  uploaded scanned certificate. Removes the synthetic-adapter and deterministic-serial
  requirements; retargets composition and fail-closed parsing at image input; `dutyPaid`
  becomes true.
- `signing-request`: "Auto-stamp before the provider call" is replaced by "a stamp is a
  precondition of the provider call". `PDF_GENERATED` is redefined as the durable
  awaiting-stamp state.
- `agreement-management`: `StampInfo` gains the SHCIL certificate fields, and the agreement
  gains a persisted, unique staff-facing reference.
- `backend-security-baseline`: role-based authorization is introduced; the stamp-intake
  endpoint is STAFF-only under the existing default-deny posture.

## Impact

**Code** - `signing.stamp` (`StampProvider`, `StampResult`, `PdfStampComposer`; delete
`SyntheticKarnatakaStampProvider`), `signing.signingrequest.SigningRequestService`
(`ensureStamped` removed, precondition added), `signing.agreement` (`StampInfo`, `Agreement`,
`AgreementService`), `signing.api` (new intake controller), `SecurityConfig` (role wiring),
`identity` (role on the authenticated principal).

**Schema** - one new forward-only Flyway migration: SHCIL columns on `agreement`, the unique
staff reference, the unique certificate-number index, and the role column. No existing
migration is edited; `ddl-auto: validate` must stay green.

**API** - new `POST` stamp-intake endpoint; `POST /api/signing/...` gains a `409` when no
stamp is attached. No existing response shape changes.

**Storage** - the uploaded scan is a new blob under an agreement-scoped key, alongside the
existing `stamped/{agreement-id}.pdf`.

**Dependencies** - none new. PDFBox already handles image embedding (`PDImageXObject`).

**FSM** - touches `PDF_GENERATED -> STAMPED` (now staff-triggered, previously automatic) and
`PDF_GENERATED -> STAMP_FAILED` (now a rejected/unusable upload). `STAMPED -> SIGN_REQUESTED`
is unchanged but becomes a guarded transition. The async signing/webhook flow itself is
untouched, so no webhook sequence diagram is warranted; the pre-signing sequence is diagrammed
in `design.md`.

## PII / security review

- **Does this change introduce or move Aadhaar/OTP/VID/PII or secrets?** **Yes - a new inbound
  PII flow.** The scanned SHCIL certificate carries the First Party and Second Party names and
  the property description. No Aadhaar number, OTP, or VID is involved.
- **How is it redacted/secured?** Scan bytes go to object storage, never to PostgreSQL and
  never to logs. Logging around intake is restricted to the agreement id and a **redacted**
  certificate number (last 4 characters only, matching the existing document-id redaction
  helper). The certificate number is treated as sensitive because it is the single-use token
  that proves duty payment. The intake endpoint is STAFF-only, and error bodies must not echo
  submitted values (per `api-error-handling`).
- **Sandbox + dummy data only preserved?** **Yes.** Real SHCIL purchases happen only in
  production operations; this repo's tests use synthetic fixture images and fabricated
  certificate numbers. No SHCIL credential, endpoint, or portal integration is added - the
  purchase stays entirely out-of-band and manual.
