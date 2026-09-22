## Why

**Bug (observed 2026-09-15, agreement `AMPSFTXU5KV`, Telangana residential).** In the executed deed,
the `Statutory (Telangana)` section prints the literal placeholder **`[ Stamp duty paid (INR) ]`**
where the duty amount belongs.

Staff had already attached a real certificate for **INR 100.00** (`agreement.stamp_duty_amount =
100.00`, `stamp_duty_paid = true`). The agreement was out for signature (`SIGN_REQUESTED`), so the
parties were being asked to eSign an instrument with a visible blank in its statutory section.

The root cause is a modelling error, not a rendering glitch:

1. **The customer was asked for a number they cannot know.** `stampDutyAmount` is declared in
   `state-TG.patch.yaml` (rental and commercial) as an ordinary customer-entered `money` field,
   `required: false`. The duty is fixed only by the certificate staff buy after payment, so the field
   was left blank.
2. **A blank field printed a placeholder.** The rental section is **mandatory**, and the compiler
   renders a blank key/value row as `[ label ]`. The patch comment's claim that "no empty amount ever
   renders" held only for the `showWhen`-gated `tgStampAmount` clause, not for the row.
3. **The certificate amount never reached the body.** The draft is rendered before stamping, and
   intake only prepended the scan, so `StampInfo.dutyAmount` never reached the document body.

There is also a legal hazard: a free-text amount typed by the customer can disagree with the
certificate bound into the same file. `docs/LEGAL-POSTURE.md` assigns retiring that field to this
change.

**Why now, after `state-stamp-duty-quoting` (archived 2026-09-17).** That change computes the legal
duty and freezes the customer's chosen stamp value at checkout, and intake now refuses a certificate
below it. The template field was deliberately left alone. The frozen quote still does not fix the
deed:

- the quote is taken **after** the draft is rendered;
- the certificate staff actually buy may exceed the paid-for value.

The deed must therefore state the **certificate's** amount, and that is only known at intake.

## What Changes

- **The deed's stamp duty amount comes from the attached certificate, never from the customer.**
  At stamp intake, when the stored draft is a recorded render of a still-current template, the system
  re-renders it with the certificate's duty amount and composites the scan onto **that** render. The
  stamped instrument then reads `Stamp duty paid (INR) 100.00`, and the gated clause "The stamp duty
  paid on this Agreement is INR 100.00." appears.
- **A template field can be declared system-sourced** (`source: system`). Such a field:
  - is not offered in the capture form;
  - has any submitted value discarded;
  - receives a value only through a Java-only generate entry point, never over HTTP;
  - while unset, shows a declared provision text, or is omitted when it declares none, instead
    of printing a `[ label ]` blank.

  `stampDutyAmount` becomes system-sourced in both Telangana layer sets, which bump version: rental
  `state:TG` 2 → 3, commercial `state:TG` 1 → 2.
- **The draft records the execution date it printed** (new nullable column), and every draft store
  clears it. A re-render therefore reproduces the draft's date, and an uploaded draft is recognisably
  not a render.
- **The re-render is guarded, and never costs a purchased certificate.**
  - If the draft is not a recorded render (uploaded, or rendered before this change), no template is
    selected, or the template drifted since the draft, intake composites onto the stored draft as
    before.
  - A renderer outage is a **retryable `503`** that writes nothing. It is **not** `STAMP_FAILED`,
    which would abandon a paid order whose certificate is already bought.
- **One draft, before payment, with a visible provision (product owner, 2026-09-17).** The draft
  emailed when contacts are confirmed, which the customer reviews before paying, shows the row as
  `Stamp duty paid (INR) [ Provision for stamp duty ]`. The template declares that text through a new
  optional field attribute, `placeholder`. No second draft is sent after payment. The figure first
  appears in the stamped deed, filled from the certificate. The gated clause "The stamp duty paid on
  this Agreement is INR …" appears only there.
- **Not retroactive.** `AMPSFTXU5KV` and any other agreement already `STAMPED` or later keep their
  stored instrument. Agreements drafted before deploy take the fallback path (their pin predates the
  version bump). Dev/sandbox rows only.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `estamp-intake`: adds that the stamped instrument states the duty amount from the attached
  certificate, with the guarded fallback and the retryable render-failure rule.
- `template-definition`: adds the system-sourced field declaration and the optional `placeholder`
  text. A system field's submitted value is ignored, and while unset it renders as its declared
  provision, or as no row when it declares none.
- `template-form-projection`: adds that a system-sourced field is excluded from the FormSchema.

## Impact

**Backend: `documents`**

- `Field` gains `source` and `placeholder`. Both are omitted from the canonical JSON when unset, so
  every template that uses neither keeps its content hash.
- The schema, binder and resolver carry `source` through. An `overrideField` cannot change it.
- The validator rejects a required system field.
- `FormProjector` excludes system fields.
- `DocumentProjectionService` strips submitted values for system fields and overlays server values.
- `TemplateCompiler` renders a blank field as `[ placeholder ]` when one is declared (otherwise
  `[ label ]` as before), and omits an unset system field's entry only when it declares no
  placeholder.
- Public API, additive only:
  - `DocumentProjectionApi.generate(request, systemValues)`;
  - `DocumentProjectionResult.executionDate`.

**Backend: `signing`**

- `Agreement.draftExecutionDate`: set with the pin, cleared by `attachDraft` and `clearDraftPin`.
- `AgreementDocumentService.renderForStamp`: pin check through `TemplateFormApi`, date
  reproduction, and `DocumentRenderException` translated to `StampRenderUnavailableException`.
- `StampIntakeService` uses the re-render, with its own audit outcome `REJECTED_RENDER_UNAVAILABLE`.
- `AgreementController` records the draft's execution date.
- `GlobalExceptionHandler` maps the new exception to `503` with the problem type
  `stamp-render-unavailable`.
- `ModularityTests` stays green.

**Templates**

- `sets/rental/state-TG.patch.yaml` and `sets/commercial/state-TG.patch.yaml`: `stampDutyAmount` is
  now `source: system` with `placeholder: "Provision for stamp duty"`, the version is bumped, and
  the misleading comment is corrected.

**Schema**

- `V21__draft_execution_date.sql`: nullable `agreement.draft_execution_date DATE`, forward-only,
  no backfill.

**API**

- No endpoint or DTO shape change. The stamp-intake request already carries a mandatory
  `dutyAmount`.
- A renderer outage at intake now answers `503` (`urn:agreementmitra:problem:stamp-render-unavailable`)
  instead of a generic `500`.

**Frontend** — no code change. The capture form is driven by the FormSchema, so the field disappears
when the backend stops projecting it.

**Related work**

- `stamp-certificate-price-reconciliation` (register) handles the money side when the certificate
  differs from the paid-for stamp value. This change handles only what the deed says.
- Adjacent, not folded in: `national-jurisdiction-city-unfilled`, another `[ label ]` placeholder in an
  executed deed, for a **user-sourced** field. Whether generate mode should ever print a placeholder
  for an optional blank user field is a drafting decision and stays out of scope.
- Noticed, not fixed here:
  - `document-stamping` still requires a per-page certificate-number header that `PdfStampComposer`
    deliberately removed;
  - uploading a draft after generating one keeps the stale template pin. This change stops trusting
    that pin, but does not clear it.

## PII / Security Review

- **Aadhaar / OTP / VID / signer PII:** none introduced or moved. The only new value flowing into a
  render is the certificate's duty amount, a non-PII rupee figure already stored on the agreement.
  The re-render uses party data the draft render already used, through the same escaped,
  never-logged compiler. PDF bytes are never logged, and the fallback log carries only the agreement
  id and a reason code.
- **Untrusted input:** this closes a hole rather than opening one. Before this change, a client could
  type any duty amount into the deed, including via the anonymous preview endpoint. Now a submitted
  `stampDutyAmount` is discarded on every projection. System values cannot travel in the
  HTTP-bound request record at all: they reach the renderer only through a Java-only method, from
  staff-authenticated intake, where the amount is already validated (`@DecimalMin 0`, 2 decimal
  places).
- **Secrets:** none.
- **Sandbox + dummy data only:** preserved. No real certificate is procured by the system.

## Signing FSM Impact

No new states and no changed transitions.

- A successful intake still moves the request `PDF_GENERATED → STAMPED`.
- Intake still moves it `PDF_GENERATED → STAMP_FAILED` only when the inputs cannot be composited.
- A renderer outage during the new re-render step leaves the request in `PDF_GENERATED` (retryable,
  nothing written). It is deliberately **not** routed to `STAMP_FAILED`.

The async signing and webhook flow is untouched, so no sequence diagram is required. The intake
sequence is in design D1.
