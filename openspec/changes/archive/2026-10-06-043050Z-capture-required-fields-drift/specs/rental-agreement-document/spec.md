## MODIFIED Requirements

### Requirement: A fixed mandatory section set forms the minimum agreement

The rental set SHALL mark exactly the following sections mandatory (`optional: false`, always render):
`Owner`, `Tenant`, `Schedule of Property` (the property section, retitled), `Term`, `Financial`, the
document-only `Now This Agreement Witnesseth` clauses section, and the `In Witness Whereof` execution
block. All other sections SHALL be optional (`optional: true`). A section being mandatory SHALL mean it
always renders; it SHALL NOT force its individual fields to be required (field-level required is
governed by the parity contract).

#### Scenario: The mandatory sections are marked non-optional

- **WHEN** the rental set is resolved for `(IN, residential)`
- **THEN** `Owner`, `Tenant`, `Schedule of Property`, `Term`, `Financial`, `Now This Agreement
  Witnesseth`, and `In Witness Whereof` are each marked `optional` false, and every other section is
  marked `optional` true

#### Scenario: A mandatory section may hold optional fields

- **WHEN** the `Schedule of Property` section is inspected
- **THEN** it is mandatory while its non-aggregate-backed fields (e.g. carpet area, furnishing status)
  remain `required` false

### Requirement: The parity contract is preserved

The rental set SHALL keep the `AgreementDocumentMapper` parity contract: the only fields marked
`required: true` across the whole composed set (every published residential dimension -- IN, TG, KA)
SHALL be drawn from the aggregate-backed keys (`ownerName`, `ownerFatherName`, `ownerAddress`,
`tenantName`, `tenantFatherName`, `tenantAddress`, `propertyAddress`, `monthlyRent`, `securityDeposit`,
`durationMonths`, `startDate`, `endDate`) or carry a system-authored default. Every aggregate-backed key
except the derived `durationMonths` SHALL be marked `required: true` -- in particular the owner's and
tenant's name, father's/spouse's name, and current address, which the agreement API requires to be
non-blank on every create and edit, so the capture form and the server agree that they are mandatory.
The party keys are aggregate-backed from the first owner's and first tenant's stored party record.
Every other field -- including any moved into an optional add-on section (e.g. lock-in months,
notice-period months) -- SHALL be `required: false` or carry a system-authored default, so a
generate-as-draft fed only the aggregate keys validates and compiles without a missing-required error.

#### Scenario: Only aggregate-backed or defaulted fields are required

- **WHEN** the rental set is resolved for `(IN, residential)`, `(TG, residential)`, and `(KA, residential)`
- **THEN** every field marked `required` true is one of the twelve aggregate-backed keys or carries a
  default, every aggregate-backed key other than `durationMonths` is marked `required` true, and every
  other field is optional

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

#### Scenario: A generate projection with only aggregate keys validates and compiles

- **WHEN** a GENERATE projection is fed only the twelve aggregate-backed keys and validated + compiled
  over the `(TG, residential)` set
- **THEN** validation fills the defaults (e.g. permitted use defaults to residential), no missing-required
  error is raised, and compilation succeeds

#### Scenario: Moving a field into an optional add-on keeps it non-required

- **WHEN** a field such as lock-in months is authored inside an optional add-on section
- **THEN** it remains `required` false or defaulted, so gating the add-on out of a generated draft never
  trips required-validation
