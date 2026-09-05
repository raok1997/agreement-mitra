## 1. Schema (Flyway) -- additive, no drops

- [x] 1.1 Add `backend/src/main/resources/db/migration/V7__rich_agreement_capture.sql`.
  On `signer`: add `first_name text`, `last_name text`, `father_name text`,
  `current_address text`, `mobile text`; backfill `first_name = name` and empty strings for
  the other new name/address columns on any existing rows; set the four name/address columns
  `NOT NULL`; alter `email` to nullable. **Keep `name`** (still `NOT NULL`). Forward-only;
  do not edit V1..V6. (Number is `V7` -- this CR lands before `mobile-otp-auth`, which
  shifts to `V8`.)
- [x] 1.2 In the same migration, on `agreement`: add `start_date date`, `end_date date`;
  backfill `start_date = created_at::date` and `end_date = (created_at + term_months *
  interval '1 month')::date`; set both `NOT NULL`. **Keep `term_months`** (still populated).

## 2. Domain (`in.agreementmitra.signing.agreement`)

- [x] 2.1 Update `Signer`: add `firstName`, `lastName`, `fatherName`, `currentAddress`, and
  optional `mobile`; **keep `name`** and make `email` nullable. Update the factory to take
  the new fields and to set `name` = a supplied non-blank override, else
  `firstName + " " + lastName`. Keep id-based equals/hashCode and **re-verify the id-only
  `toString()`** emits none of the new PII fields (nor `name`).
- [x] 2.2 Update `Agreement`: add `startDate`/`endDate` (`LocalDate`); **keep `termMonths`**
  but set it server-side from the dates (whole months between start and end). Keep
  `monthlyRent`/`securityDeposit` fields/columns unchanged (the UI labels them "Monthly
  Rent" / "Security Deposit").
- [x] 2.3 Add a package-private duration calculator that derives, from
  `startDate`/`endDate`, the **whole months between the two dates**
  (`Period.between(...).toTotalMonths()`) -- this value is both `termMonths` and the
  months-unit duration shown to the user. Pure, no Spring, no persistence.
- [x] 2.4 Update `AgreementService.create` and the read mapping: accept the new fields;
  derive each party's `name` (override else first+last); derive `termMonths` and the
  duration from the dates; include the duration in the response (mapping inside the
  transaction; no entity crosses the boundary).

## 3. HTTP surface (`in.agreementmitra.signing.api`)

- [x] 3.1 Update `CreateAgreementRequest` + nested party record to
  `{firstName, lastName, fatherName, currentAddress, role, email?, mobile?, name?}` (where
  `name?` is the optional Aadhaar full-name override) and top-level
  `{propertyAddress, monthlyRent, securityDeposit, startDate, endDate, parties}` -- no id /
  createdAt / termMonths / duration fields (anti-mass-assignment). Add per-field constraints
  per design D6 (`@NotBlank` name parts + address + property; `@Email` on optional email;
  optional `name` override non-blank when present; money `@Positive`/`@PositiveOrZero` +
  `@Digits`; dates `@NotNull`).
- [x] 3.2 Add a class-level `@EndAfterStart` constraint (endDate strictly after startDate),
  null-safe; and update `@ValidSignerSet`: keep `>=1 OWNER` and `>=1 TENANT`, and apply the
  duplicate-email rule only among parties that supply an email. Both unit-testable with a
  plain `Validator`.
- [x] 3.3 Update `AgreementResponse` + nested party response to carry the new fields, the
  stored full `name`, the server-assigned ids, `createdAt`, and the **derived duration in
  months** (`termMonths`). `LocalDate` serialized as ISO-8601 (Boot default).

## 4. Signing precondition (`in.agreementmitra.signing.signingrequest`)

- [x] 4.1 In `SigningRequestService.create`, before persisting the pre-request row / stamp /
  provider call, verify every party has **at least one** contact (email or mobile); if not,
  throw a `ConflictException` (`409`) -- alongside the existing draft-required check. Build
  the eSign invitee with the party's stored full `name` and **both** its email and mobile
  (phone) when present, so the invite reaches every channel. No FSM state is added or changed.

## 5. Frontend (`frontend/src`)

- [x] 5.1 Update the capture form into Tenant, Owner, and Property sections with the new
  fields (first/last/father name + current address per party; property address, monthly
  rent, security deposit, start date, end date). Show the party's full name **auto-derived**
  from first + last with an **"edit to match Aadhaar"** affordance. Compute and **display
  the duration live** from the entered dates (and show the server-computed duration on the
  response). Update the API records/types in `src/api/`. Still anonymous -- no login to draft.

## 6. Tests -- unit (no Spring context)

- [x] 6.1 Duration calculator: correct whole-month count for representative spans (exact
  whole months, cross-year, a trailing partial month truncates to whole months); consistent
  with the dates.
- [x] 6.2 Name derivation: `name` defaults to `firstName + " " + lastName` when no override
  is given, and equals the override verbatim when supplied.
- [x] 6.3 Validation via a plain `jakarta.validation.Validator`: rejects a blank first/last/
  father name, blank current address, blank property address, a blank `name` override, a
  malformed email when present, a missing date, and an end date not after start; accepts a
  party with **no** contact; `@ValidSignerSet` still fails all-owners / all-tenants and
  duplicate supplied emails, and is null-safe when contacts are absent.
- [x] 6.4 Entity-to-response mapping: a built aggregate maps to a response whose party full
  name, name parts, father's name, current address, role, optional contact, money
  (`monthlyRent`/`securityDeposit`), dates, derived term, and computed duration round-trip
  correctly.

## 7. Tests -- integration (Testcontainers Postgres slice)

- [x] 7.1 `POST /api/agreements` with two owners + one tenant (full details, dates, one
  party with a `name` override, one contact-less party) persists one agreement + three party
  rows and returns `201` with server ids, derived full names, and the computed duration;
  `GET /api/agreements/{id}` returns all parties with their details. A blank name-part /
  end-before-start / missing-role body returns `400` and persists nothing; an unknown id
  returns `404`.
- [x] 7.2 Boot/validate against the `V7`-migrated schema (`ddl-auto: validate` passes; the
  entity mapping matches the altered `signer`/`agreement` tables including the retained
  `name` and `term_months`); assert `V7` is applied with `success = true` and
  `ModularityTests` stays green.
- [x] 7.3 Signing precondition: requesting a signing for an agreement with a contact-less
  party returns `409` and creates no signing request / stamp / provider call; with every
  party contactable and a draft present, the precondition passes and the eSign invitee
  carries the party's stored full `name` and both its email and mobile when present.

## 8. Frontend tests (Vitest)

- [x] 8.1 Unit-test the duration display (given start/end, the shown breakdown matches), the
  auto-derived-name-with-edit behaviour, and a component test that the capture form binds all
  party/property/date fields and submits the expected payload shape (contact optional).

## 9. Verify

- [x] 9.1 Backend: `./run-tests.sh test` green (unit + Testcontainers integration +
  `ModularityTests`), BUILD SUCCESSFUL. NOTE: the full `check` (OSV `securityScan` + JaCoCo)
  was NOT run here because `osv-scanner` is not installed on this box (the gate is
  fail-closed on a missing binary); run `./gradlew check` once `osv-scanner` is installed.
- [x] 9.2 Frontend: `npm run test` (9/9) and `npm run build:only` (vue-tsc + vite) green.
  NOTE: `npm run security:scan` also needs `osv-scanner` (not installed here) -- run once
  available.
- [x] 9.3 Manual: verified live against `:8090` -- created an agreement (tenant name
  derived "Tara Sen", owner Aadhaar override "Asha Kumari Rao", `durationMonths` 11, contact
  optional with either channel); a contactless agreement returns `409 contact-required` on
  signing; V7 confirmed applied (`success = t`).
