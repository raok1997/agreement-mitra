## Why

The capture form presents "Duration (months)" as an editable, required field seeded to 11, but
the server has always treated the tenancy term as **derived from the start and end dates and never
accepted from the client** (`agreement-management` -> "Tenancy duration is derived from start and
end dates, in months"). The form therefore collects a value the server discards, and the two
disagree in the rendered deed: the stateless live preview compiles the **typed** value while the
generated/signed PDF compiles the **date-derived** one. A tenancy captured as 2026-01-08 to
2028-01-08 with the field left at its default previews as "a term of 11 month(s)" and signs as
"a term of 24 month(s)". Same deed, two different terms, and the stamp duty is quoted off the
derived 24.

Relatedly, client-side validation is strictly per-field (`formModel.ts` validates each field in
isolation), so the capture form has **no cross-field checks at all**. An end date on or before the
start date passes the form and is only rejected by the server as a 400. That matters more once the
term is derived: a reversed range no longer shows a value the user typed, it shows a nonsense
duration computed from their dates, so the range needs checking where the dates are entered.

*(Scope corrected 2026-10-04. This CR also carried a capture-time **registration warning**, which has
been removed from it. That warning duplicated the registration notice at the stamp-quote step --
already server-side and computed from the stamp-duty rules -- as a second, less-informed statement of
the same legal fact by a client that cannot reach `rules`. It was also the whole source of this CR's
churn, and the threshold it asserted risked contradicting the registration clause in the deed being
drafted. A capture-time warning may still be worth building; it needs its own proposal, whose first
question is whether the stamp-quote notice already suffices.)*

## What Changes

- The tenancy term stops being a capture input. `durationMonths` is marked **derived** in the
  rental and commercial template sets, and the form projection carries that marker so the capture
  form renders the term **read-only** instead of collecting it. Being derived, it is no longer
  `required` for capture purposes.
- Document projection **derives `durationMonths` from the submitted `startDate`/`endDate`** for
  every render path -- stateless preview included -- so the preview and the signed PDF agree by
  construction rather than by two code paths happening to compute the same thing.
- The capture form gains its first **cross-field validation**: an error when the end date is on or
  before the start date, pre-empting the 400 the server already returns.
- A cross-field error **blocks the section save** and keeps the section incomplete, because a saved
  reversed range reaches the preview, where the derived term is non-positive. Per-field "required"
  errors stay saveable, so capture remains progressive.
- **Not breaking.** No API contract changes shape; `durationMonths` was already stripped from
  inbound `captureData` as server-managed, so no client can be relying on sending it.

### Deliberately out of scope (recorded in the `docs/ROADMAP.md` follow-up register)

- **Sourcing the warning threshold from the rules engine.** *(Narrowed 2026-10-04 -- the
  state-aware threshold itself was brought INTO scope during validation and is implemented; only the
  plumbing remains deferred.)* The warning now uses each state's own line -- 11 nationally, 12 in
  Karnataka, 0 in Telangana, where every lease is registrable -- because a single fixed 11 told a
  Karnataka customer that a 12-month tenancy must be registered while the Karnataka clause in the
  very deed being drafted sets the line at twelve. **Resolved by removing the warning from this CR
  altogether** (see Why): with no capture-time warning there is no client-side threshold to source,
  and the stamp-quote notice already reads the rules engine directly. What the thresholds actually
  are remains an open question about the rule data and the shipped Karnataka clause, tracked as
  the existing `ka-stamp-duty-counsel-review` and `tg-stamp-duty-counsel-review` rows.

## Capabilities

### New Capabilities

_None. Every behavior here belongs to an existing capability._

### Modified Capabilities

- `template-form-projection`: a field may be declared **derived**; the projection keeps it in the
  FormSchema but marks it read-only so the client displays rather than collects it. This is a third
  field provenance alongside user-sourced and the existing system-sourced (which is dropped from the
  schema entirely, and so cannot express "show it, do not ask for it").
- `template-document-projection`: the compiled data map SHALL carry a server-derived
  `durationMonths` computed from the submitted dates on every render path, so the preview's data map
  and the generate path's data map agree -- extending the existing single-compiler parity
  requirement, which guarantees one compiler for a *given* data map but not that the two paths
  present the same map.
- `preview-centric-capture`: the capture form SHALL render a derived field read-only, and SHALL
  reject an end date on or before the start date before submission, blocking the section save.

## Impact

- **Backend (`documents`)**: `Field` / `FieldSource` gain the derived provenance;
  `FormProjector` projects it with a read-only marker; `TemplateDefinitionValidator` allows a
  derived field to be non-required; `DocumentProjectionService` derives `durationMonths` into the
  data map; `TemplateDefinitionLoader` parses the new `source` value.
- **Backend (resources)**: `documents/template/sets/rental/base.yaml` and the commercial set change
  `durationMonths` from `required: true` to the derived source.
- **Backend (`signing`)**: unchanged in behavior at the time of this proposal. (`TenancyDuration`
  was subsequently changed by `5d39b7f` to count the end date inclusively, keeping the persisted
  term in step with the rendered one -- see the design Non-Goals.) `AgreementDocumentMapper` already overwrites
  `durationMonths` from `Agreement.termMonths()` and `AgreementService` already strips it from
  `captureData`; both stay as the authoritative belt-and-braces for the persisted path.
- **Frontend**: `api/templateForm.ts` (the read-only marker on `FormField`), `views/formModel.ts`
  (month derivation, the end-before-start cross-field rule), `views/CaptureForm.vue` (read-only
  rendering of the Term duration, wiring the cross-field error into the modal),
  `components/widgets/FieldWidget.vue` (dispatching on
  `readOnly` ahead of the widget type) and a new `components/widgets/DerivedWidget.vue` (a display
  with no `<input>`, so there is nothing to tab into).
- **No migration.** `agreement.term_months` is already populated server-side from the dates; no
  stored value changes meaning and no backfill is needed.
- **Docs**: a follow-up-register row in `docs/ROADMAP.md` for the state-aware threshold.

## PII / security review

- **New or moved PII**: none. The change touches the tenancy term (two dates and a month count) and
  a static warning string. No Aadhaar number, OTP, virtual ID, KYC attribute, or signer contact
  detail is read, derived, stored, or transmitted by any part of this change.
- **New outbound flows**: none. No new endpoint, no new external call, no new persisted column.
  Month derivation is arithmetic over dates already present in the request.
- **Logging**: unchanged. The existing prohibition on logging rendered content or submitted values
  (`template-document-projection`) continues to hold; the derivation logs nothing, and the new
  client-side validation messages name only the field label, never a captured value.
- **Sandbox + dummy data only**: preserved. No credential, secret, or environment change.
- **Signing status FSM**: untouched. This change is entirely pre-`PDF_GENERATED` capture and render
  behavior; it adds, removes, and reorders no `SignatureStatus` transition.
- **Trust boundary**: unchanged and worth restating. The new client-side checks are a UX affordance,
  not enforcement -- the server already rejects an end date not strictly after the start date with a
  400 (`agreement-management` -> "Create-time agreement validation") and already recomputes the term
  from the dates. A client that skips the form still cannot supply a term or an inverted date range.
