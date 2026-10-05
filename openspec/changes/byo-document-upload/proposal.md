## Why

The customer picks a template today and cannot change a word of it.
`PRODUCT-FEATURE-SET.md` tiers upload-your-own-draft as MVP, near-table-stakes
(NoBroker and AgreementKart both offer it), and it *reduces* our authorship
exposure rather than increasing it the way a template editor would: with a
bring-your-own (BYO) document we did not draft the instrument.

The blocker is not storage. `POST /api/agreements/{id}/draft` already accepts and
stores an uploaded PDF, and nothing downstream distinguishes an uploaded draft
from a generated one -- but `esign-signature-placement` requires an `esign:<role>`
anchor and refuses the request rather than guess a position. That anchor is
emitted by our template renderer, so an uploaded PDF carries none. Upload works
at step one and signing refuses at step N. This change closes that gap.

Direction, rejected alternatives and mock screens: `docs/BYO-DOCUMENT-UPLOAD.md`.
This is CR-2 of the four-CR sequence in its section 5.

## What Changes

- **A new entry point on the `/start` picker.** An "Upload your own document"
  card sits as a peer of the template cards in `TemplatePicker.vue` and starts
  the BYO flow. It is a **pseudo-card rendered by the picker, not a
  `template_catalog` row**: a catalog row is published metadata for a renderable
  template with a content hash and layer versions, none of which a BYO agreement
  has. (See design D1 for the rejected catalog-row alternative.)
- **A BYO agreement shape.** An agreement may exist with **no selected template**
  and therefore no template pin. It instead carries a **content hash of the
  uploaded bytes**, so "is this the document the customer approved?" survives the
  draft freeze.
- **PDF or Word (`.docx`) uploads.** A Word file is converted to PDF on receipt
  and the converted PDF is what is composed, stamped and signed. This reverses
  `docs/BYO-DOCUMENT-UPLOAD.md`'s "`.docx` stays out", whose two stated objections
  no longer hold: our `gotenberg/gotenberg:8` image **already serves
  `/forms/libreoffice/convert`** (verified against the running container -- it
  answers `415` on a wrong content type, not `404`), so no LibreOffice service is
  added; and the never-parse invariant is being modified by this change anyway.
  The consequence that does not disappear is that **the signed instrument is a
  rendering of the customer's file, not the file itself** -- which is exactly what
  the review step exists to make safe (design D11).
- **A declaration step, which is a duty-facts step.** With no template, nothing
  but the customer establishes the instrument and its duty basis. The declaration
  collects the **duty jurisdiction (the state the property lies in), instrument
  kind, usage, execution date, term, rent schedule, and deposits** -- the
  normalized fact set `stamp-duty-calculation` already accepts -- plus the
  **party list**, which BYO still needs for the eSign invitees and the execution
  page. What BYO drops is the document *body*, not the facts about it.
- **A system-generated execution page, composed at upload time.** Rendered
  through the existing compiler's `render: signatures` path so anchor-token
  derivation stays a single source of truth, then appended to the uploaded PDF
  with PDFBox using the same `importPage` pattern `PdfStampComposer` uses to
  prepend the certificate. It carries **execution furniture only and never a
  covenant** -- a clause there would make us a partial author, which is the
  exposure BYO exists to avoid. It matches the uploaded first page's size rather
  than hardcoding A4.
- **Block-only signature placement for BYO.** Signatures land on the appended
  execution page and nowhere else. No footer-strip detection, no disclosure, no
  per-page rail -- those are `byo-every-page-signatures` (CR-3). This is the
  scope cut that makes this change tractable: honest (we state where signatures
  appear), reversible, and nothing already signed changes when CR-3 upgrades it.
  The cost is one release where BYO output is weaker at a bank or registrar's
  counter than templated output.
- **Uploaded bytes are parsed at upload, and a Word upload is converted.**
  Composition requires `Loader.loadPDF`, and a Word upload is additionally
  rendered to PDF by Gotenberg. **MODIFIED** invariant, not a discarded one: the
  bytes are still never executed, logged, or trusted for their declared type or
  filename, and the format is determined from the content rather than the
  extension. The upside is real -- encrypted, corrupt, zero-page and
  unconvertible files fail at the upload step where the customer can act, instead
  of surfacing days later in the staff stamp queue as `STAMP_FAILED`.
- **An owner-scoped fetch-my-draft endpoint.** `GET /api/agreements/{id}/preview`
  renders from the template, so it cannot show a BYO document. Review needs the
  stored composed draft served back.
- **Terms-of-service and disclaimer copy.** The terms say we generate documents
  from templates; the promise changes in the change that changes the product. The
  shipped `LegalDisclaimer` ("your agreement is generated from a template") is
  false for BYO and needs its drafted variant. Neither may be split out.

Not in scope, deliberately: legacy `.doc` and `.odt` (LibreOffice converts both,
so admitting them later is a configuration change plus its own security review --
each additional format is more parser surface for no new customer today),
drag-to-place signature positioning (rejected in the direction doc, section 2),
and per-page strips.

**No signing-status FSM change.** `PDF_GENERATED` is reached by upload-plus-compose
instead of by template generation; the active path
`PDF_GENERATED -> STAMPED -> SIGN_REQUESTED -> SIGNED | FAILED | EXPIRED` and the
terminal `STAMP_FAILED` branch are untouched, and no new state is introduced. The
async signing/webhook flow is unchanged, so no sequence diagram is owed.

## Capabilities

### New Capabilities
- `byo-document-upload`: the BYO agreement shape (no template, declared duty
  facts, upload content hash), the appended execution page and its constraints,
  block-only placement, the declaration step, and the owner-scoped fetch of the
  composed draft.

### Modified Capabilities
- `draft-ingestion`: the "uploaded bytes SHALL NOT be parsed" invariant becomes
  "SHALL NOT be executed, and SHALL be parsed only to validate, convert and
  compose"; the accepted formats widen from PDF-only to PDF or Word, determined by
  content rather than by the declared type or filename; adds encrypted / corrupt /
  zero-page / unconvertible rejection at upload.
- `esign-signature-placement`: "Each signer signs the execution page and every
  page" is scoped to **system-generated** documents. A BYO instrument gets the
  block placement only, and where signatures appear is stated to the customer
  before payment.
- `agreement-management`: an agreement may be template-less; the integrity record
  for a BYO agreement is the uploaded-bytes hash rather than
  `template_content_hash` / `template_layer_versions`, and that hash is
  server-managed and not client-settable.
- `agreement-preview`: for a BYO agreement, review serves the stored composed
  draft under owner scope instead of a template render.
Two neighbouring capabilities are deliberately **not** modified, because both were
written to accommodate exactly this:

- `stamp-duty-calculation`: its fact set is already state-agnostic and its duty
  state is already "independent of the state dimension of any template", so BYO
  feeds the existing calculator unchanged.
- `jurisdiction-eligibility`: its requirement already constrains "the agreement's
  **duty jurisdiction**, not the state dimension of the template it was drafted
  from", explicitly so that "a later capability MAY establish a duty jurisdiction
  for an agreement drafted from a national template". A BYO agreement establishing
  its duty jurisdiction by declaration is that later capability, and `IN` remains
  inadmissible. No requirement text changes.

## Impact

**Backend (`signing`)**: `DraftService.attachDraft` (parse, validate, compose,
hash), `AgreementController` (+ fetch-my-draft, declaration on create/update),
`Agreement` (template-less agreements, upload-hash column), a forward-only Flyway
migration for the upload hash and the declared duty facts, `PdfStampComposer`
(page-size handling shared with the new composer), `EsignAnchorLocator` and the
placement builder (block-only for BYO).

**Backend (`documents`)**: a standalone execution-page render through the existing
`render: signatures` path; Gotenberg paper size driven by the uploaded page size;
a Word-to-PDF conversion call on Gotenberg's existing LibreOffice route.

**Infrastructure**: `docker/gotenberg/Dockerfile` gains the metric-compatible font
families a Word document is likely to name (design D11), and the LibreOffice
route's network behaviour needs the same deny posture the Chromium route already
has in `docker-compose.yml`.

**Frontend**: `TemplatePicker.vue` (the new card), a BYO upload + declaration
flow, a review step showing the composed PDF, `LegalDisclaimer` variant copy, and
`src/api/` additions for upload, declaration and draft fetch.

**Docs**: `docs/TERMS-OF-SERVICE.md` via its generator (never edited directly),
`docs/BYO-DOCUMENT-UPLOAD.md` status, `docs/ROADMAP.md` Track A.

**Sequencing**: `signing-auth` is the change ahead of this one. `signing-auth` matters because a public upload UI widens the
`permitAll` hole on `/api/agreements/*/draft`, and the new fetch-my-draft endpoint
inherits it. If `signing-auth` has not landed when this starts, this change
carries ownership authZ and a rate limit on those two endpoints only, and the
register row stays open recording the partial. The certificate-page strip band
(`estamp-signature-band`, CR-3's prerequisite) landed 2026-10-05 as a direct fix.

**Owed to the follow-up register before this change archives**: the image-XObject
tightening of the footer-band check (CR-3's concern, raised here), the remaining
mobile frames (only the upload step is mocked at 390px), and any partial of the
`signing-auth` row this change absorbs.

### PII / security review

- **New PII flow: yes, but no new category.** The uploaded PDF is customer PII (a
  rental agreement carries names, addresses, and financial terms). It is stored in
  object storage through the existing `BlobStore` seam under the deterministic
  `drafts/{agreementId}.pdf` key, never in Postgres, and the bytes are never
  logged verbatim or otherwise. The client-declared content type and filename stay
  untrusted, unreflected and unlogged, and the 10 MiB ceiling and `%PDF-` magic-byte
  check are retained.
- **No Aadhaar, OTP, VID or eKYC data is introduced or moved.** The declaration
  collects commercial terms and party names, the same categories the templated
  flow already collects. Signer PII continues to reach the eSign vendor only
  through the existing `EsignProvider` seam at signing-request time.
- **Parsing untrusted bytes is the new attack surface** and must fail closed: a
  parse failure, an encrypted document, a zero-page document or a page-count or
  page-size outside sane bounds is rejected `400` as problem+json with nothing
  stored and no parser detail echoed to the client. For PDF this moves an existing
  exposure earlier rather than adding one -- `PdfStampComposer` already parses the
  same bytes downstream.
- **Word conversion is a genuinely new attack surface**, and the larger of the
  two. LibreOffice parsing an attacker-supplied `.docx` is a wider surface than
  PDFBox parsing a PDF, and it is reached by an endpoint that is `permitAll`
  today. Two things make it acceptable and both are **tasks, not assumptions**:
  it runs inside the Gotenberg container rather than in our JVM, and the
  container's outbound network must be denied for the LibreOffice route as it
  already is for Chromium (`CHROMIUM_DENY_PUBLIC_IPS` /
  `CHROMIUM_DENY_PRIVATE_IPS` are Chromium-scoped, so they do **not** cover it).
  A document with linked or embedded external references is the SSRF/exfil case to
  close before this ships.
- **No new secret, no new outbound integration.** Sandbox and dummy data only is
  preserved: the fixtures for this change are synthetic PDFs carrying no party
  data, consistent with `esign-signature-placement`'s calibration rule.
