## Context

The `Agreement` aggregate models each party as a single `name` + required `email` + `role`,
and the term as a bare `termMonths` int. The guided drafting screens require far more: each
tenant and owner with first name, last name, father's name, and current address; the
property with a monthly rent and a security deposit; and the tenancy as real start and end
dates with a duration shown to the user. This CR extends the capture **additively** (keeping
`name` and `termMonths`) on the **anonymous** create path (no login). It is the data
foundation the template CRs embed into.

Constraints: Java 21 + Spring Boot 3.5.x + Spring Modulith; records for DTOs; constructor
injection; package-private by default; Flyway is the single schema source
(`ddl-auto: validate`, forward-only, never edit an applied migration); keep
`ModularityTests` green; sandbox + dummy data only; never log party PII (redact / id-only
`toString`).

## Goals / Non-Goals

**Goals:**
- Capture each party as first name, last name, father's name, current address, role, and an
  optional contact; support N owners and N tenants.
- Keep a party's **full `name` as per Aadhaar**: auto-derived from first + last, editable to
  match the signer's Aadhaar record, and used as the eSign invitee name.
- Capture property address, monthly rent, security deposit, and a **required** start and end
  date; derive `termMonths` and a display duration from the dates.
- Keep drafting anonymous and low-friction: contact optional at draft, enforced only before
  a signing request.
- Unit-test the validation, name-derivation, and duration logic without a Spring context;
  integration-test the create/read round-trip against the migrated schema.

**Non-Goals:**
- No template catalog, rendering, or preview (CR-2/CR-3).
- No auth, ownership, claim, or list-mine (that is `mobile-otp-auth`); no security-matcher
  change here.
- No new signing-status states/transitions; the only signing touch is one pre-request
  precondition (a `409`).
- No rename of the money fields/columns; no i18n of party fields.

## Decisions

### D1: Party model is additive; `name` is retained as the Aadhaar full name

`Signer` adds `firstName`, `lastName`, `fatherName`, `currentAddress`, and an optional
`mobile`, and **keeps `name`**. `name` now means the party's **full name as per Aadhaar**:
the create service sets it to the client's override when supplied, else derives
`firstName + " " + lastName`; the UI shows it auto-filled with an "edit to match Aadhaar"
affordance. `name` is what the eSign adapter sends as the invitee name (it must match the
Aadhaar record), while the structured first/last/father/address fields feed the agreement
document. `email` becomes optional (D2). `Signer` stays a child `@Entity` of the aggregate;
its **id-only `toString()`** is re-checked so no new PII field is emitted at DEBUG.
**Alternative rejected:** dropping `name` in favour of first/last only -- loses the
Aadhaar-exact name the eSign step depends on.

### D2: Capture both email and mobile; either suffices, enforced before signing

Both `email` and `mobile` are captured per party and are **optional** in
`CreateAgreementRequest` (the screens collect them later). The eSign step needs a reachable
invitee, so `SigningRequestService.create` gains a precondition: if any party has **neither**
email nor mobile it throws a `ConflictException` (`409`) **before** persisting a pre-request
row, procuring a stamp, or calling the provider -- mirroring the existing "draft PDF
required" `409`. When a party has **both**, the eSign invite is addressed to **both**
channels (the invitee is built with email and phone), so the signer is reached either way.
**Alternatives rejected:** requiring contact at draft (contradicts the flow); requiring
*both* channels (too strict -- either reaches the signer); deferring the check to the
provider call (leaves a half-open signing request).

### D3: Tenancy is defined by required start + end dates; duration is shown in months

`Agreement` adds `startDate` and `endDate` (`LocalDate`), **both required**, as the source
of truth -- a month count alone is insufficient, so the dates are primary. `termMonths` is
**retained** but becomes **server-derived**: the **whole months between the two dates**
(`Period.between(start, end).toTotalMonths()`), never client-supplied. This derived
`termMonths` **is** the duration shown to the user (display unit: **months**, for example
"11 months") -- per the product decision we do not surface a years/months/days breakdown.
The span is measured **exclusive of the end date** (standard `Period.between` semantics: 1
Jan to 1 Dec is 11 months); a trailing partial month beyond the whole-month count is not
shown. Because `termMonths` is always recomputed from the dates it can never drift.
**Alternatives rejected:** letting the client set `termMonths` (reintroduces ambiguity); a
full years/months/days + total-days breakdown (more than the UI wants).

### D4: Money field and column names are unchanged

The existing `monthlyRent` (`monthly_rent`) and `securityDeposit` (`security_deposit`)
fields and columns are kept as-is across the entity, the API records, and the DB; the
capture screen labels them **"Monthly Rent"** and **"Security Deposit"** (user decision).
Money bounds (strictly positive rent, non-negative deposit, two-decimal) are unchanged.
**Alternative rejected:** renaming to `rentalValue` / `advanceAmount` -- unnecessary churn.

### D5: `V7__rich_agreement_capture.sql` is additive -- no drops

Forward-only, never edits V1 through V6, and **drops nothing**:
- `signer`: `add column first_name text`, `last_name text`, `father_name text`,
  `current_address text`, `mobile text`; backfill `first_name = name` and empty strings for
  the other new name/address columns on any existing rows; set the four name/address columns
  `NOT NULL`; alter `email` to nullable. `name` is **kept** (still `NOT NULL`).
- `agreement`: `add column start_date date`, `end_date date`; backfill
  `start_date = created_at::date` and `end_date = (created_at + term_months * interval '1
  month')::date`; set both `NOT NULL`. `term_months` is **kept** (still populated; new
  writes recompute it from the dates).

Because nothing is dropped, this is a **non-destructive** migration -- no existing column or
its data is removed, so it is far lower risk than a drop. The backfill only fills the new
columns for any pre-existing sandbox rows. A boot/validate integration test catches mapping
drift. **Migration number:** this CR lands before `mobile-otp-auth`, so it takes `V7`
(the next free slot after V6); `mobile-otp-auth` shifts to `V8` when it is un-parked.

### D6: Validation -- per-field plus the adapted cross-field constraint

Per-field on `CreateAgreementRequest` / nested party record: `@NotBlank` on `firstName`,
`lastName`, `fatherName`, `currentAddress`, and `propertyAddress`; `@Email` on `email`
**only when present**; `monthlyRent` `@NotNull @Positive @Digits(integer=10, fraction=2)`;
`securityDeposit` `@NotNull @PositiveOrZero @Digits(integer=10, fraction=2)`; `startDate`
and `endDate` `@NotNull`. `name` is **not** a required request field (the server derives it
when absent), but an override, if present, must be non-blank. Two cross-field rules: (a) a
new class-level `@EndAfterStart` (endDate strictly after startDate), and (b) the existing
`@ValidSignerSet` adapted -- still `>=1 OWNER` and `>=1 TENANT`, and duplicate-email
detection applies only among parties that **supply** an email. The validators stay null-safe
and unit-testable with a plain `Validator` (no Spring). **Alternative rejected:**
service-layer guards -- splits validation across layers.

### D7: Cross-CR note -- un-owned PII retention rides with `mobile-otp-auth`

This CR is the first to persist substantial un-owned party PII (father's name, current
address) on anonymous drafts. The retention/purge control lives in `mobile-otp-auth` (which
introduces ownership and the purge job). If this CR ships first, unclaimed sandbox drafts
are not auto-purged in the interim -- accepted for sandbox, and flagged so the purge is
wired when ownership lands (or pulled earlier if desired).

## Risks / Trade-offs

- **Additive migration, low risk** -- because `V7` only adds columns and keeps `name` /
  `term_months`, no existing data is removed and rollback is simpler (drop the new columns).
  The only write to existing rows is the backfill of the new columns.
- **Name-split backfill fidelity** -- for any pre-existing rows, `first_name = name` and the
  other parts default to empty; best-effort and acceptable because existing rows are dummy.
  New writes are fully validated and derive `name` from the parts.
- **Aadhaar-name accuracy** -- `name` is what the eSign step matches against Aadhaar; the
  editable field puts that in the user's hands. A mismatch surfaces at the eSign step, not
  here; the capture UI should make the "must match Aadhaar" intent clear.
- **Optional contact vs eSign** -- a contact-less draft is legal but cannot be signed; the
  `409` precondition (D2) makes that explicit rather than failing in the provider call.
- **Schema/mapping drift** -- `ddl-auto: validate` + a boot integration test catch it at
  build time.

## Migration Plan

Forward-only Flyway `V7__rich_agreement_capture.sql` applied after V6: add the new `signer`
and `agreement` columns with the in-migration backfills above; keep `name` and
`term_months`. No column drop, no separate data-migration step, no external backfill job.
Rollback in this pre-production sandbox phase is a manual reverse migration (drop the added
columns); `flyway.clean` stays disabled. No deployed consumers.

## Open Questions

None outstanding -- the design forks are resolved:
- **Contact** (resolved) -- capture **both** email and mobile; **either** one present
  satisfies the pre-signing check; deliver the eSign invite to both when present (D2).
- **Duration** (resolved) -- displayed **in months** (the derived `termMonths`), measured
  exclusive of the end date (D3).
