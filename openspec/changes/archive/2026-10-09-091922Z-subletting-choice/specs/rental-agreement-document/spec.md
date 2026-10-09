## ADDED Requirements

### Requirement: The user chooses one of three sub-letting covenants

The rental set SHALL declare a required enum field `subletting` (label `Sub-letting`) with exactly the
options `with_owner_consent`, `not_allowed` and `allowed`, with **no default**, placed in the mandatory
`Term` section, after `noticePeriodMonths`. The set SHALL declare three covenant clauses, each gated by
`showWhen` on one option, and list all three in the `Now This Agreement Witnesseth` section immediately
after `careOfPremisesClause` and before `inspectionClause`, in every published residential dimension
(IN, TG, KA):

- `with_owner_consent`: "The Tenant shall not sublet, assign, or part with possession of the Premises, in
  whole or in part, without the Owner's prior written consent."
- `not_allowed`: "The Tenant shall not sublet, assign, or part with possession of the Premises, in whole
  or in part, under any circumstances."
- `allowed`: "The Tenant may sublet the Premises, in whole or in part, after giving the Owner prior
  written notice of the sub-tenant's name, and shall remain liable to the Owner for the rent and every
  obligation under this Agreement. The Tenant shall not assign this Agreement without the Owner's prior
  written consent."

A compiled document with a `subletting` value SHALL carry exactly one sub-letting covenant; because a
`showWhen` whose literal matches no option silently drops its clause, this SHALL be verified for every
declared option of every published dimension. A value outside the options SHALL be rejected, never
matched approximately. The fixed `noSublettingClause` SHALL NOT exist in the set.

#### Scenario: Each option renders its own covenant and only that one

- **GIVEN** each of the `(IN, residential)`, `(TG, residential)` and `(KA, residential)` sets
- **WHEN** it is compiled with `subletting` set to each option the field declares, in turn
- **THEN** the document carries exactly one sub-letting covenant -- the text for that option -- and its
  terms table shows the `Sub-letting` row as "With Owner Consent", "Not Allowed" or "Allowed"
  respectively

#### Scenario: A value outside the options is rejected

- **WHEN** a projection of the `(TG, residential)` set is fed `subletting` = `Allowed` (wrong case) or
  another non-option value
- **THEN** validation fails with an `enum` error on `subletting` in both PREVIEW and GENERATE, and no
  sub-letting covenant is compiled

#### Scenario: The sub-letting choice is a required capture field with no default

- **GIVEN** the served capture-form schema for `(IN, residential)`, `(TG, residential)` and
  `(KA, residential)`
- **WHEN** the `Term` section is inspected
- **THEN** it carries a `subletting` field marked `required` true, with no default and exactly the three
  options

#### Scenario: A blank choice previews with a placeholder and no covenant

- **GIVEN** a PREVIEW projection of the `(KA, residential)` set with `subletting` absent or blank
- **WHEN** it is compiled
- **THEN** compilation succeeds, the `Sub-letting` row renders the `[ Sub-letting ]` placeholder, and no
  sub-letting covenant renders

#### Scenario: Generate refuses a blank choice

- **GIVEN** a GENERATE projection of the `(TG, residential)` set fed every aggregate-backed key but no
  `subletting`
- **WHEN** it is validated
- **THEN** validation fails with a `required` error on `subletting`, and no document is produced

#### Scenario: Every overlay lists the three covenants and none dangles

- **WHEN** the `(IN, residential)`, `(TG, residential)` and `(KA, residential)` sets are resolved
- **THEN** each `Now This Agreement Witnesseth` section lists all three sub-letting clauses, every listed
  clause exists, and no section lists `noSublettingClause`

#### Scenario: The capture form blocks Save and continue until the choice is made

- **GIVEN** the capture form for a residential agreement whose other required fields are filled
- **WHEN** `subletting` is still blank
- **THEN** the field shows the "Select..." option, the `Term` section is not counted complete, and
  Save & continue is disabled; choosing an option enables it

## MODIFIED Requirements

### Requirement: The covenant clauses render as a document-only witnesseth section

The rental set SHALL declare a `Now This Agreement Witnesseth` section with render kind `clauses` and
**zero fields** (its entries are clause ids only) that aggregates the covenant clauses (care of premises,
the three option-gated sub-letting covenants, inspection, handover, permitted use, and the always-on
covenants). Because it projects to zero fields, this section SHALL be omitted from the capture
`FormSchema` (it is document structure, not a capture step) while still rendering in the compiled
document. It SHALL be mandatory (`optional: false`). No covenant clause SHALL be orphaned and no other
section SHALL still list a clause that was moved into the witnesseth section.

#### Scenario: The witnesseth section is field-less and rendered

- **WHEN** the rental set is resolved for `(IN, residential)`
- **THEN** the `Now This Agreement Witnesseth` section has render kind `clauses`, projects to zero
  fields, is mandatory, and lists the covenant clauses

#### Scenario: The field-less witnesseth section is omitted from the capture form

- **WHEN** the capture `FormSchema` is projected from the resolved set
- **THEN** the `Now This Agreement Witnesseth` section does not appear as a capture step, while it still
  renders in the compiled document

#### Scenario: No clause dangles after the covenants move

- **WHEN** the resolved set is re-validated
- **THEN** every covenant clause referenced by the witnesseth section exists, and no other section lists a
  moved clause (no dangling entry)

### Requirement: The parity contract is preserved

The rental set SHALL keep the `AgreementDocumentMapper` parity contract: every field marked
`required: true` across the whole composed set (every published residential dimension -- IN, TG, KA)
SHALL be one of the aggregate-backed keys (`ownerName`, `ownerFatherName`, `ownerAddress`,
`tenantName`, `tenantFatherName`, `tenantAddress`, `propertyAddress`, `monthlyRent`, `securityDeposit`,
`durationMonths`, `startDate`, `endDate`), carry a system-authored default, or be a **user-answered**
field. A user-answered field is required, has no default, is not aggregate-backed, is user-sourced (no
`source`), is named in the single shared user-answered allowlist the parity guards read (today
`subletting` alone), and SHALL appear in the projected capture `FormSchema` as a `required` field inside
a section with `optional` false -- so the capture form always shows it and blocks Save & continue until
it is answered, while generate's server-side refusal remains the enforcing control. The parity guards
SHALL reject a required field with no default that is not aggregate-backed and is either absent from the
allowlist, absent from every projected mandatory section (including a field listed in no section), or
listed in an optional section. Every aggregate-backed key except the derived `durationMonths` SHALL be marked
`required: true` -- in particular the owner's and tenant's name, father's/spouse's name, and current
address, which the agreement API requires to be non-blank on every create and edit, so the capture form
and the server agree that they are mandatory. The party keys are aggregate-backed from the first
owner's and first tenant's stored party record. Every other field -- including any moved into an
optional add-on section (e.g. lock-in months, notice-period months) -- SHALL be `required: false` or
carry a system-authored default, so a generate-as-draft fed the aggregate keys plus the user-answered
fields validates and compiles without a missing-required error.

#### Scenario: Only aggregate-backed, defaulted or user-answered fields are required

- **WHEN** the rental set is resolved for `(IN, residential)`, `(TG, residential)`, and `(KA, residential)`
- **THEN** every field marked `required` true is one of the twelve aggregate-backed keys, carries a
  default, or is a user-answered field in a mandatory capture section; every aggregate-backed key other
  than `durationMonths` is marked `required` true; and every other field is optional

#### Scenario: A required undefaulted field outside a mandatory capture section is rejected

- **GIVEN** a composed set in which a required field with no default, not aggregate-backed, sits in an
  optional section, or in no section at all
- **WHEN** the parity guard inspects it
- **THEN** the guard reports a violation naming that field

#### Scenario: A required undefaulted field missing from the allowlist is rejected

- **GIVEN** a composed set in which a required field with no default, not aggregate-backed and not in
  the user-answered allowlist, sits in a mandatory capture section
- **WHEN** the parity guard inspects it
- **THEN** the guard reports a violation naming that field, so a new user-answered field is a
  deliberate allowlist edit rather than a side effect of placement

#### Scenario: The party father's name and address are required in the capture form

- **GIVEN** the served capture-form schema for `(TG, residential)`
- **WHEN** the `Owner` and `Tenant` sections are inspected
- **THEN** `ownerFatherName`, `ownerAddress`, `tenantFatherName`, and `tenantAddress` are each marked
  `required` true

#### Scenario: The capture form does not report a party section complete while a party field is blank

- **GIVEN** an `Owner` section whose `ownerFatherName` field is marked `required` true
- **WHEN** the owner's name is filled and the father's name is blank
- **THEN** the form does not count the `Owner` section as complete

#### Scenario: The mapper supplies the party father's name and address from the aggregate

- **GIVEN** an agreement whose first owner and first tenant carry a father's name and current address
- **WHEN** it is mapped to template data
- **THEN** `ownerFatherName`/`ownerAddress` and `tenantFatherName`/`tenantAddress` carry those stored
  values, and they overwrite any differing value for the same keys in the stored capture map

#### Scenario: A generate projection with the aggregate keys and the user answers validates and compiles

- **WHEN** a GENERATE projection is fed the twelve aggregate-backed keys plus a `subletting` answer and
  validated + compiled over the `(TG, residential)` set
- **THEN** validation fills the defaults (e.g. permitted use defaults to residential), no missing-required
  error is raised, and compilation succeeds

#### Scenario: Moving a field into an optional add-on keeps it non-required

- **WHEN** a field such as lock-in months is authored inside an optional add-on section
- **THEN** it remains `required` false or defaulted, so gating the add-on out of a generated draft never
  trips required-validation
