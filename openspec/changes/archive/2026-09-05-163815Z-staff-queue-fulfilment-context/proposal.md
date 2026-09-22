## Why

A staff operator cannot buy the e-stamp from the queue as it stands. Purchasing on the SHCIL
portal requires naming the **first party**, the **second party**, and the **state** whose stamp
paper is being bought -- and the queue row carries none of them. It shows a tracking reference,
the property city, the agreement date, a waiting time, and a payment badge. The operator's only
route to the missing fields today is to query the database by hand, which is exactly the thing
the console was built to remove.

The omission is deliberate, not an oversight: `manual-estamp-upload` specified the row as
non-PII on the reasoning that "staff need to disambiguate, not to read the customer's
agreement". That reasoning was right about *disambiguation* and wrong about *fulfilment* -- it
was written before the queue was the operator's actual workbench. Naming the parties on a stamp
certificate IS the job, so the data the job needs has to be on the row.

## What Changes

- **Party names appear on the queue row.** Each entry carries every party's full name and
  **father's name**, grouped by role: `OWNER` (first party) and `TENANT` (second party). All
  parties, not just the first of each -- an agreement may carry several owners or tenants, and
  showing one would hide a name the certificate needs.
- **The pinned template's name and state appear on the row.** The **state** is the operative
  field: stamp duty follows the state the instrument was drafted against, which is the pinned
  template's `state` dimension -- NOT the free-text property address (whose only extractable
  part is a comma-separated city tail).
- **A deliberate, scoped reversal of the non-PII queue invariant.** The
  `StaffAgreementView` rationale changes from "staff need to disambiguate, not to read the
  customer's agreement" to a fulfilment rationale that names what staff may see and why. This
  is recorded as a decision, not patched in silently: it widens what an authenticated operator
  can read about a customer's agreement.
- **The projection stays narrow.** Contact details (email / mobile), monthly rent, security
  deposit, and the full street address stay OUT. The row gains identity fields required to
  purchase a certificate and nothing else. Property city stays as-is.
- **Degrade gracefully when the pinned template is no longer published.** `TemplateCatalogApi.detail`
  serves `PUBLISHED` entries only, so an agreement pinned to a since-superseded or archived
  template must still appear on the queue with its template name and state absent, rather than
  dropping off it. A vanished template is a reason to show less, never a reason to hide
  outstanding work.
- **No change to authorization.** `/api/staff/**` remains `hasRole("STAFF")`, evaluated in the
  filter chain before any handler runs. No new endpoint, no new role, no widening of who may
  call the queue.

## Capabilities

### New Capabilities

None. This extends an existing capability's projection.

### Modified Capabilities

- `estamp-intake`: the requirement **"Staff have a console listing orders awaiting a stamp"**
  changes. Its "enough non-PII context to act on it" clause becomes an explicit, enumerated
  fulfilment projection: tracking reference, property city, agreement date, waiting time,
  payment state, **template name, template state, and each party's name + father's name by
  role**, with an equally explicit exclusion list (contacts, rent, deposit, full address).

  Note: `estamp-intake` has no baseline under `openspec/specs/` yet -- it lives in the
  unarchived `manual-estamp-upload` change (63/63 tasks complete). The delta targets that
  capability path so the two merge correctly when `manual-estamp-upload` is archived.

## Impact

**Signing module (`in.agreementmitra.signing`)**
- `agreement/StaffAgreementView` -- record gains template name, template state, and a party
  list; javadoc rationale replaced.
- `agreement/AgreementService.toStaffView` / `staffViewsByAgreementId` -- projects signer names
  + father names by role, and resolves the pinned `templateId` through the **already-injected**
  `TemplateCatalogApi` (the field exists on this class today).
- `api/StampQueueEntry` -- the wire DTO gains the same fields.
- `signingrequest/StampIntakeService.awaitingStampQueue` -- assembles the richer row.

**Modularity**: no new cross-module dependency. `signing -> documents.api` already exists and
`AgreementService` already holds `TemplateCatalogApi`. `ModularityTests` stays green by
construction; the change adds no `documents.template` type to `signing`.

**Frontend**
- `api/staffQueue.ts` -- `StampQueueEntry` interface widened.
- `views/StaffConsole.vue` -- row layout shows template + state prominently (state is what the
  operator acts on) and the parties with their father's names.

**Data / schema**: none. `signer.first_name`, `signer.last_name`, `signer.father_name`,
`signer.role`, and `agreement.template_id` all exist. **No migration.**

**Signing-status FSM**: untouched. The queue is a read over `PDF_GENERATED` signing requests;
this change adds no state and no transition.

## PII / Security Review

- **Does this change introduce or move Aadhaar / OTP / VID data?** No. No Aadhaar number,
  virtual ID, OTP, or KYC artifact is read, stored, transported, or displayed by this change.
- **Does it move PII?** **Yes, and that is the point of the change.** Party names and father's
  names -- personal data, though not identity-document data -- move from the agreement
  aggregate into a STAFF-only API response and a STAFF-only screen. This is a widening of an
  existing authenticated surface, not a new public one.
- **How is it secured?** Authorization is unchanged and enforced server-side in the filter
  chain (`/api/staff/**` -> `hasRole("STAFF")`), before any handler executes, so the projection
  is unreachable without the role. The frontend `isStaff` check stays presentational only. The
  STAFF role remains grantable solely by an out-of-band database action, never from a request
  body, header, or IdP claim.
- **How is it kept out of logs?** Party names MUST NOT reach any log line. `Signer.toString()`
  already emits id + role only; the new projection records MUST carry the same discipline --
  their `toString()` must not print names, and no queue assembly path may log a row. This is a
  test obligation in `tasks.md`, not a convention.
- **What stays excluded?** Contact details, rent, deposit, and the full street address remain
  outside the projection. Widening the row is not licence to widen it again.
- **Sandbox + dummy data only**: preserved. No vendor call, no live credential, no new outbound
  flow of any kind -- this change reads data the database already holds.
