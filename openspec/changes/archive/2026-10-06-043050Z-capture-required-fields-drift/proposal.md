## Why

The rule "every party needs a father's/spouse's name and a current address" lives in two places that
disagree. The server enforces it (`CreateAgreementRequest.SignerRequest` — `@NotBlank` on `fatherName`
and `currentAddress`), but the rental and commercial template sets declare the matching fields
(`ownerFatherName`, `ownerAddress`, `tenantFatherName`, `tenantAddress`) `required: false`. The capture
form takes required-ness from the template, so it shows "6 of 6 required sections ready", enables Save,
and the save then fails with "Father's name is required. Current address is required." — naming neither
the party nor the section. Every first-time customer who skips those fields meets it (register row
`capture-required-fields-drift`, raised 2026-10-04).

Product decision (2026-10-06): the fields are **mandatory**. They name the parties in the agreement's
opening recital ("son / daughter / spouse of …, residing at …") and on the stamp vendor's form, so a blank
weakens the document itself.

## What Changes

- The four party fields become **aggregate-backed** keys: `AgreementDocumentMapper` emits
  `ownerFatherName` / `ownerAddress` / `tenantFatherName` / `tenantAddress` from the first owner/tenant
  signer's stored `fatherName` / `currentAddress`, alongside the existing eight keys. The stored party
  columns therefore win over the capture map for these keys too ("fixed columns win").
- The rental and commercial base template sets mark those four fields `required: true`. The capture form
  already derives asterisks and section completeness from the template, so "Save & continue" stays
  disabled until they are filled (per-section saves stay progressive) — the server would have refused
  the save anyway — and the incomplete section is the one shown.
- The rental parity contract's aggregate-backed key list widens from eight to twelve. The rule itself
  — "required ⇒ aggregate-backed or defaulted" — is unchanged; this change keeps it true.
- Template versions bump (rental base 3 → 4, commercial base 2 → 3): the composed template and potentially
  the rendered deed change, and two materially different deeds must not share one authored version.
- No API, DTO, migration, or signing-state change. The server rule is untouched.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `rental-agreement-document`: "The parity contract is preserved" lists twelve aggregate-backed keys;
  "A fixed mandatory section set forms the minimum agreement" no longer cites the party father's name /
  address as its example of an optional field, and now names the `In Witness Whereof` execution block it
  already marks mandatory (pre-existing spec drift, corrected while the requirement is open).
- `agreement-management`: "Generate the signable draft from the agreement" and "An agreement persists its
  full capture state" no longer promise a no-capture render "exactly as before" — the fixed-column
  mapping now carries the party father's name and address, and a blank-party legacy row fails with field
  errors.
- `agreement-preview`: "Preview the filled agreement document" — the id-bound preview returns `400` with
  field errors for an agreement with blank party details, instead of always `200`.

(`template-document-projection`'s commercial requirement — "every required commercial field is
aggregate-backed or defaulted" — is phrased generically and holds unchanged once the mapper supplies the
four keys; no delta.)

## Impact

- **Backend**: `signing/agreement/AgreementDocumentMapper.java`; `documents/template/sets/rental/base.yaml`
  and `sets/commercial/base.yaml`; the tests that pin the required-key set and the mapper output.
- **Frontend**: no code change — `CaptureForm` / `formModel.isSectionComplete` already read `required`
  from the served schema.
- **Legacy rows**: an agreement created before `V7` with no capture state holds `''` (the V7 backfill) in
  `father_name` / `current_address`; generating or server-previewing it now fails with field-level errors
  instead of rendering blanks. Production has no such rows (it was created after `V7`); this is
  dev/sandbox data only. See design D3.
- **Stamp intake**: drafts generated before deploy no longer match the current template hash, so stamp
  intake takes the documented `PIN_DRIFT` fallback (stamp onto the stored draft) — as every previous
  template revision did. See design D6.
- **Signing FSM**: none — no `SignatureStatus` transition is touched.
- **PII / security**: no new PII *type* or destination. Father's name and address already reach the
  `documents` module through the capture map; they now also come from the mapper (as plain `Map` values —
  no PII type crosses the module boundary), including for agreements with no capture state. The signer
  columns winning closes an over-binding gap (an unvalidated capture-map value could previously reach the
  deed). The `400` names keys only, never values. No Aadhaar / OTP / VID or secret is involved, nothing
  new is logged, and the repo stays sandbox + dummy data only.
