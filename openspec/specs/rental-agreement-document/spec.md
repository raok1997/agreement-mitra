# rental-agreement-document Specification

## Purpose
TBD - created by archiving change rental-document-content-v2. Update Purpose after archive.
## Requirements
### Requirement: The rental document declares a configurable header

The production rental layer set SHALL declare a `meta.document` header carrying a `title`, a `subtitle`,
and an `executionLine`. The National (IN) base SHALL declare `title` "Rental Agreement", `subtitle`
"Residential Tenancy (Leave & Licence)", and `executionLine` "This Agreement is executed on
{{agreementDate}} in respect of the property in the Schedule below." The `executionLine` SHALL be
system-authored text with `{{slot}}` fills only, HTML-escaped at compile; it SHALL NOT contain
user-authored markup. The Telangana (TG) `state` / `state_type` layers MAY override the subtitle and/or
execution line; when they do not, the National wording applies. The header SHALL be carried through the
template canonicalizer so a Telangana override changes the effective template's content hash
deterministically.

#### Scenario: The National document declares the reference header

- **WHEN** the rental set is resolved for `(IN, residential)`
- **THEN** the effective template carries a `meta.document` header with title "Rental Agreement",
  subtitle "Residential Tenancy (Leave & Licence)", and an execution line containing the
  `{{agreementDate}}` slot and the phrase "in respect of the property in the Schedule below"

#### Scenario: A Telangana header override changes the content hash deterministically

- **WHEN** the Telangana layers override the `meta.document` subtitle or execution line and the set is
  resolved for `(TG, residential)`
- **THEN** the effective template's header reflects the Telangana wording and its content hash differs
  deterministically from the National header's, and re-resolving yields the same hash

#### Scenario: The execution line carries no user markup

- **WHEN** the `executionLine` is compiled with an `agreementDate` value
- **THEN** it renders the system-authored sentence with the date filled and HTML-escaped, and contains
  no user-authored markup or expression surface

### Requirement: The Parties section is split into separate Owner and Tenant sections

The rental set SHALL declare two distinct party sections instead of a single "Parties" section: an
`Owner` section (owner / lessor identity fields plus the recital) and a `Tenant` section (tenant /
licensee identity fields). Both SHALL be tagged render kind `parties` so the compiler draws each as its
own party card, and both SHALL be mandatory (`optional: false`). The `ownerName` and `tenantName` fields
SHALL remain required; the other party fields SHALL remain optional. The "made between ... and ..."
recital text SHALL still render in the composed document.

#### Scenario: Owner and Tenant are distinct mandatory party sections

- **WHEN** the rental set is resolved for `(IN, residential)`
- **THEN** it contains an `Owner` section and a separate `Tenant` section, each with render kind
  `parties` and `optional` false, and there is no single combined "Parties" section

#### Scenario: The recital still renders after the split

- **WHEN** the resolved set is compiled with owner and tenant data
- **THEN** the rendered document contains the recital naming the Owner and the Tenant

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

### Requirement: Section render kinds drive the document layout

Each rental section SHALL declare a `render` kind so the compiler dispatches its body: `Owner` and
`Tenant` -> `parties`; `Schedule of Property`, `Term`, and `Financial` -> `keyvalue` (the compiler
composes `Term` and `Financial` into the "Terms of Tenancy" table); the witnesseth section -> `clauses`;
the optional fixtures/inventory annexure -> `annexure`. The render kind SHALL be declared in the template
content, never hardcoded in the compiler or the frontend.

#### Scenario: Render kinds are declared per section

- **WHEN** the rental set is resolved for `(IN, residential)`
- **THEN** the `Owner`/`Tenant` sections declare render kind `parties`, `Schedule of Property`/`Term`/
  `Financial` declare `keyvalue`, the `Now This Agreement Witnesseth` section declares `clauses`, and the
  optional `Annexure` section declares `annexure`

#### Scenario: Term and Financial compose the Terms of Tenancy table

- **WHEN** the resolved set is compiled
- **THEN** the `Term` and `Financial` key/value sections render as the "Terms of Tenancy" table content

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

### Requirement: An optional fixtures/inventory annexure

The rental set SHALL provide an optional `Annexure` section with render kind `annexure` and
`optional: true` carrying a fixtures / inventory schedule with a system-authored (dummy) default scaffold.
It SHALL render only when added (opt-in), and its absence SHALL leave no dangling reference.

#### Scenario: The annexure is optional and opt-in

- **WHEN** the rental set is resolved and no annexure is added
- **THEN** the `Annexure` section is marked `optional` true, render kind `annexure`, and contributes no
  content to the compiled document

#### Scenario: The added annexure renders as an annexure block

- **WHEN** the `Annexure` section is added (active) and the set is compiled
- **THEN** its fixtures / inventory content renders in the annexure region of the document

### Requirement: The Telangana optional add-on catalog is individually addable

The Telangana layer SHALL expose each reference-artifact add-on -- rent escalation, security-deposit
terms, late-payment penalty, maintenance charges, utilities split, lock-in, notice period, permitted
occupants, pets, parking, furnishing, fixtures/inventory annexure, dispute resolution, and a
custom/special clause -- as an **individually-addable** optional section or clause. Each add-on SHALL be
marked `optional: true`, SHALL carry a system-authored sensible default, and SHALL be gated so its
content appears only when added (opt-in), consistent with `activeSections` and the existing `showWhen`
DSL. An add-on that is not added SHALL contribute no header, fields, or clauses, and SHALL leave no
dangling reference. No add-on's field or clause SHALL be listed twice across the composed set.

#### Scenario: Each add-on is a separately addable optional section

- **WHEN** the rental set is resolved for `(TG, residential)`
- **THEN** each of the fourteen add-ons is present as an `optional` true section or clause with a
  system-authored default, and each can be added independently of the others

#### Scenario: An add-on contributes nothing until it is added

- **WHEN** the set is compiled with a given add-on not active
- **THEN** that add-on's content is absent from the document, mandatory sections still render, and no
  dangling reference remains

#### Scenario: An added add-on renders with its default

- **WHEN** a given add-on (e.g. rent escalation) is added without the user overriding its value
- **THEN** its content renders using the system-authored default (e.g. the default escalation percentage)

#### Scenario: The Telangana statutory overlay still applies over the new structure

- **WHEN** the rental set is resolved for `(TG, residential)`
- **THEN** the Telangana statutory clauses (governing law, stamp/registration before the Sub-Registrar,
  essential services) are present, the generic National stamp clause is dropped, the jurisdiction city
  defaults to Hyderabad, and no entry dangles after the section re-organisation

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

### Requirement: The Karnataka residential layers contribute a mandatory statutory overlay

The Karnataka `state` and `state_type` layers over the residential set SHALL contribute a statutory
section that always renders -- in the live preview and in every generated Karnataka draft -- with no
user action, and SHALL NOT be offered as an opt-in add-on.

The overlay SHALL state the law governing the tenancy in Karnataka, SHALL state that the agreement is
to be stamped under the Karnataka Stamp Act 1957 and registered before the jurisdictional
Sub-Registrar under the Registration Act 1908 where the term passes the threshold the Karnataka duty
rule itself reports, and SHALL name who bears the stamp duty and registration charges. The stated
registration threshold SHALL agree with the threshold the Karnataka rule applies; the two SHALL NOT
be allowed to drift.

Mandatory is a correctness requirement here, not a preference. The Karnataka `state_type` layer
removes the national stamp-and-registration clause on the ground that the Karnataka clause supersedes
it. If the statutory section were opt-in, a default Karnataka deed would carry no stamp or
registration clause at all -- strictly worse than the national template it overlays. Either the
section is mandatory, or the national clause is restored to the covenant list in the same edit.

The stamp duty amount SHALL remain system-sourced: the capture form SHALL NOT ask for it, a submitted
value SHALL be discarded, and the rendered deed SHALL show a visible provision in its place until
stamp intake supplies the amount from the attached certificate.

The Karnataka layers SHALL default the jurisdiction city, so that the always-on exclusive-jurisdiction
covenant names a court rather than an unfilled placeholder.

#### Scenario: The statutory overlay renders without being added

- **WHEN** a Karnataka residential agreement is previewed with no optional sections active
- **THEN** the Karnataka statutory section renders
- **AND** it carries the tenancy-law, stamp-and-registration and charges-borne-by clauses

#### Scenario: No deed is left without a stamp and registration clause

- **WHEN** a Karnataka residential draft is generated with no optional sections active
- **THEN** exactly one stamp-and-registration clause renders
- **AND** it is the Karnataka clause, not the national one

#### Scenario: The jurisdiction covenant names a court

- **WHEN** a Karnataka residential draft is generated without the optional dispute-resolution section
- **THEN** the exclusive-jurisdiction covenant names the Karnataka default city
- **AND** it does not render an unfilled jurisdiction-city placeholder

#### Scenario: The stamp duty amount is not asked for

- **WHEN** the capture form for a Karnataka residential agreement is projected
- **THEN** it contains no stamp duty amount input
- **AND** a draft generated before stamping shows a visible provision in the amount's place

#### Scenario: The stated registration threshold matches the rule

- **WHEN** the Karnataka statutory clause and the Karnataka duty rule are compared
- **THEN** the term threshold at which the clause says registration becomes compulsory is the same
  threshold at which the rule reports registration as required

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

