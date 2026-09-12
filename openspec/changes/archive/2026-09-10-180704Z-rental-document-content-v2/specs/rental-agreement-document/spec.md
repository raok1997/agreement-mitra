## ADDED Requirements

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
`Owner`, `Tenant`, `Schedule of Property` (the property section, retitled), `Term`, `Financial`, and the
document-only `Now This Agreement Witnesseth` clauses section. All other sections SHALL be optional
(`optional: true`). A section being mandatory SHALL mean it always renders; it SHALL NOT force its
individual fields to be required (field-level required is governed by the parity contract).

#### Scenario: The mandatory sections are marked non-optional

- **WHEN** the rental set is resolved for `(IN, residential)`
- **THEN** `Owner`, `Tenant`, `Schedule of Property`, `Term`, `Financial`, and `Now This Agreement
  Witnesseth` are each marked `optional` false, and every other section is marked `optional` true

#### Scenario: A mandatory section may hold optional fields

- **WHEN** the `Owner` section is inspected
- **THEN** it is mandatory while its non-name fields (e.g. owner father's/spouse's name, owner address)
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
no subletting, inspection, handover, permitted use, and the always-on covenants). Because it projects to
zero fields, this section SHALL be omitted from the capture `FormSchema` (it is document structure, not a
capture step) while still rendering in the compiled document. It SHALL be mandatory (`optional: false`).
No covenant clause SHALL be orphaned and no other section SHALL still list a clause that was moved into
the witnesseth section.

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

The rental set SHALL keep the `AgreementDocumentMapper` parity contract: the only fields marked
`required: true` across the whole composed set (IN and TG) SHALL be the aggregate-backed keys
(`ownerName`, `tenantName`, `propertyAddress`, `monthlyRent`, `securityDeposit`, `durationMonths`,
`startDate`, `endDate`). Every other field -- including any moved into an optional add-on section (e.g.
lock-in months, notice-period months) -- SHALL be `required: false` or carry a system-authored default,
so a generate-as-draft fed only the aggregate keys validates and compiles without a missing-required
error.

#### Scenario: Only the aggregate-backed keys are required

- **WHEN** the rental set is resolved for `(IN, residential)` and for `(TG, residential)`
- **THEN** the only required fields are the eight aggregate-backed keys, and every other field is optional
  or defaulted

#### Scenario: A generate projection with only aggregate keys validates and compiles

- **WHEN** a GENERATE projection is fed only the eight aggregate-backed keys and validated + compiled over
  the `(TG, residential)` set
- **THEN** validation fills the defaults (e.g. permitted use defaults to residential), no missing-required
  error is raised, and compilation succeeds

#### Scenario: Moving a field into an optional add-on keeps it non-required

- **WHEN** a field such as lock-in months is authored inside an optional add-on section
- **THEN** it remains `required` false or defaulted, so gating the add-on out of a generated draft never
  trips required-validation
