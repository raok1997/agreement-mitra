## Why

Today an agreement captures almost nothing about the people or the tenancy: each party is
a single `name` plus a required `email`, and the term is a bare `termMonths` integer. A
real Indian rental agreement -- and the guided drafting screens we are building -- needs
the actual **party details** (each tenant and owner with first name, last name, father's
name, and current address), the **property and money** (property address, monthly rent,
security deposit), and the **tenancy period** as real **start and end dates** with a
**computed duration** shown to the user.

This CR extends `agreement-management` to capture those fields **additively** -- it keeps
the existing `name` and `termMonths` and adds structure around them. It is the first CR of
the guided-flow arc and the data foundation the template CRs embed into (CR-2 catalog, CR-3
render + preview). It stays on the **anonymous** create path (no login) established as the
product model; the optional mobile-OTP save/resume layer is a separate CR.

## What Changes

- **Party model (additive)** -- add **`firstName`**, **`lastName`**, **`fatherName`**,
  **`currentAddress`**, and an optional **`mobile`** to each party; keep `role`
  (`OWNER` | `TENANT`). **Retain `name`** as the party's **full name as per Aadhaar** -- it
  is required, **auto-derived** as `firstName + " " + lastName` when the client does not
  supply it, and **user-editable** so it can be corrected to match the signer's Aadhaar
  record. `name` is the name handed to the eSign provider (which must match Aadhaar); the
  first/last/father/address fields populate the agreement document. `email` becomes
  optional (see the contact rule below).
- **Tenancy period** -- add **`startDate`** and **`endDate`** (`LocalDate`), **both
  required**, as the source of truth (a start/end pair, not a month count alone, defines a
  tenancy). The server derives **`termMonths`** (whole months between the two dates, never
  client-supplied) and returns it as the **duration displayed to the user, in months**.
- **Money** -- the existing **`monthlyRent`** and **`securityDeposit`** fields and columns
  are unchanged; the capture screen labels them **"Monthly Rent"** and **"Security
  Deposit"**. Money bounds (positive, two-decimal) are unchanged.
- **Party contact -- capture both, either suffices** -- both **`email`** and **`mobile`**
  are captured for each party and are **optional at draft** (the screens collect them
  later). Before a signing request is created, **at least one contact (email or mobile) is
  required** per party (a `409` if a party has neither); when both are present the eSign
  invite is delivered to **both** channels. Enforced at signing, not at draft.
- **Validation** -- `firstName`, `lastName`, `fatherName`, `currentAddress`, and
  `propertyAddress` are non-blank; `startDate` and `endDate` are required and `endDate` is
  strictly after `startDate`; `email` and `mobile`, if present, are well-formed; the
  existing signer-set rules (>=1 owner and >=1 tenant, no duplicate contact) are retained.
- **Schema (additive)** -- Flyway `V7__rich_agreement_capture.sql`: on `signer`, add
  `first_name`, `last_name`, `father_name`, `current_address`, `mobile`, and make `email`
  nullable (backfill the name parts from the existing `name` for any current rows); on
  `agreement`, add `start_date`, `end_date` (backfill from `created_at` + `term_months`).
  **No column is dropped** -- `name` and `term_months` are kept. Forward-only; JPA stays
  `ddl-auto: validate`.
- **Frontend** -- the capture form gains Tenant, Owner, and Property sections with the new
  fields; the full name shows auto-derived with an "edit to match Aadhaar" affordance; and a
  live **duration display** from the entered dates. API records in `src/api/` are updated.
  Still anonymous (no login to draft).

The signing FSM, `EsignProvider` / webhook flow, stamping, and object storage are
**unchanged** apart from the one new pre-signing contact check.

## Capabilities

### Modified Capabilities
- `agreement-management`: the create and read contracts carry richer parties (first/last/
  father name + current address per owner/tenant, plus the Aadhaar full `name`), the
  unchanged money fields `monthlyRent` / `securityDeposit`, and a tenancy period as required
  `startDate` + `endDate` with a server-derived `termMonths` and a computed duration. Party
  contact is optional at draft and required before signing.

## Impact

- **Code** (`in.agreementmitra.signing.agreement` + `signing.api`): the `Signer` entity
  gains name-part + address + `mobile` fields and **keeps `name`** (now derivable +
  editable); `Agreement` gains `startDate`/`endDate` and **keeps `termMonths`** (now
  derived from the dates); `CreateAgreementRequest`/`AgreementResponse` (+ nested party
  records) and the `@ValidSignerSet` constraint are updated; a duration calculator is added;
  the response gains derived duration fields.
- **Signing precondition** (`signing.signingrequest`): `SigningRequestService.create`
  gains a check that every party has at least one contact (email or mobile), returning `409`
  if not; the eSign invite is addressed to both channels when present.
- **Schema**: new `V7__rich_agreement_capture.sql` (forward-only, **additive**; never edits
  V1–V6) adding columns to `signer` and `agreement` with in-migration backfills; nothing is
  dropped.
- **Frontend**: capture form sections + duration display + updated API types and tests.
- **No** change to: the signing FSM states/transitions, the Leegality adapter, webhook
  intake, stamping, object storage, or the reconciliation job.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **Yes -- more party PII.** This
  adds **father's name** and **current address** for each party (sensitive personal data),
  and gives `name` an explicit **Aadhaar-name** meaning. It stores an ordinary personal
  name intended to match a government id **string**, but **no Aadhaar number, OTP, virtual
  id, biometric, or government identifier value** is collected or stored; no secrets are
  introduced.
- **How redacted/secured?** The new fields are ordinary contact/identity PII. No log
  statement logs party fields or the request body; `Agreement` and `Signer` keep their
  **id-only `toString()`** (re-checked so the new name/address fields are never emitted).
  Standard validation rejects malformed input at the boundary.
- **Un-owned PII note (cross-CR).** On the anonymous create path these details persist with
  no owner until (optionally) claimed. The retention/purge control for unclaimed drafts
  lives in `mobile-otp-auth` (which introduces ownership); until that lands, unclaimed
  sandbox drafts are not auto-purged. Acceptable for **sandbox + dummy data only**; flagged.
- **Sandbox + dummy data only?** Preserved -- local schema/API work with dummy data; no live
  provider, no real PII, no production credentials.
- **Signing-status FSM transitions touched?** **None** -- the pre-signing contact check gates
  entry to the existing flow (a `409` before any transition) but adds/alters no FSM state.
- **Async signing / webhook flow touched?** **None** -- no sequence diagram required.
