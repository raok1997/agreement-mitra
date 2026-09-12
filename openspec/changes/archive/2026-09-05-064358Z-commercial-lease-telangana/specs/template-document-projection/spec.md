## ADDED Requirements

### Requirement: A commercial lease product line resolves from its own layer set

The system SHALL serve a **Commercial Lease Agreement** product line at the dimensions `(state, type)
= (IN, commercial)` and `(TG, commercial)`, resolved from a layer set separate from the residential
one. The commercial base SHALL declare its own document header -- title `Commercial Lease Agreement`,
its own subtitle, and its own execution line -- because the effective definition takes `meta` verbatim
from the base and no patch operation edits `meta`; reusing the residential base would title a
commercial document "Residential Tenancy".

The commercial set SHALL carry commercial party roles (Lessor / Lessee, mapped onto the same
aggregate name keys the residential set uses) and commercial-specific fields and clauses, and SHALL
NOT carry the residential-only fields (BHK, furnishing, pets, occupants). The Telangana layers SHALL
apply over the composed commercial structure along the engine's fixed precedence chain
(`base -> type -> state -> state_type`), so `state-TG` layers over the base and type layer and
`state_type-TG-commercial` applies above it -- exactly as for residential.

This SHALL require no engine change: the existing resolver, compiler, catalog, and projection API
SHALL serve the new dimensions from added classpath content alone. The catalog SHALL remain the
dimension-validation authority -- an unpublished or unknown dimension SHALL `404`.

#### Scenario: The commercial document declares the commercial header, not the residential one

- **WHEN** the `(IN, commercial)` template resolves
- **THEN** its document title is `Commercial Lease Agreement`
- **AND** its subtitle is not the residential `Residential Tenancy (Leave & Licence)`

#### Scenario: Telangana overlays the commercial structure

- **WHEN** the `(TG, commercial)` template resolves
- **THEN** the effective template carries the Telangana fields (`stampDutyAmount`,
  `registrationChargesBorneBy`) and defaults `jurisdictionCity` to `Hyderabad`
- **AND** the `Statutory (Telangana)` section is ordered after the covenant and annexure sections and
  before the `In Witness Whereof` execution block

#### Scenario: The execution block renders for the commercial line too

- **WHEN** a commercial agreement document renders
- **THEN** its `In Witness Whereof` section renders as a signature block carrying the `esign:owner`
  and `esign:tenant` anchors, the same as the residential line

#### Scenario: Every required commercial field is aggregate-backed or defaulted

- **WHEN** the commercial template resolves
- **THEN** each required field is either backed by a key the agreement aggregate holds or carries a
  default, so generate-as-draft reaches parity without a capture gap

### Requirement: The commercial type layer pins the commercial character of the document

The commercial `type` layer SHALL make the `permittedUse` field **required** and SHALL default it to
`commercial`, so a generated draft always carries a commercial-use covenant even when the agreement
aggregate supplies no value for it. Because the default satisfies generate's required-check, this
SHALL NOT break generate/preview parity: `permittedUse` is not an aggregate-backed key, and the
required-with-default shape keeps it out of the capture gap.

#### Scenario: A generated draft carries the commercial use covenant without user input

- **GIVEN** only the aggregate-backed field keys are supplied
- **WHEN** a commercial agreement is generated as a draft
- **THEN** `permittedUse` resolves to `commercial` from its default
- **AND** generate succeeds -- the required-check is satisfied by the default, not by captured data

### Requirement: The Telangana statutory overlay is opt-in, and supersedes the national stamp clause

The commercial state layer SHALL declare its `Statutory (Telangana)` section **optional**, so the
engine's existing opt-in gating keeps it out of the preview and out of a generated draft until the
user adds it via `activeSections` -- matching the residential set's treatment rather than making the
overlay unconditional. Its fields SHALL be optional or defaulted so opting in never introduces a
capture gap.

For `(TG, commercial)` the `state_type` layer SHALL remove the base's generic national
stamp/registration clause, because the state layer's Telangana-specific stamp/registration clause
supersedes it, and SHALL re-author the `Now This Agreement Witnesseth` covenant list without the
removed clause so no section entry dangles at a clause that no longer exists.

#### Scenario: The statutory section is absent until opted into

- **WHEN** a `(TG, commercial)` document is previewed or generated without `Statutory (Telangana)` in
  `activeSections`
- **THEN** the `Statutory (Telangana)` section does not render
- **AND** the same document with that section active renders it without requiring new captured data

#### Scenario: The national stamp clause is superseded, leaving no dangling entry

- **WHEN** the `(TG, commercial)` template resolves
- **THEN** the base's generic national stamp/registration clause is absent
- **AND** the `Now This Agreement Witnesseth` section's entries reference only clauses that exist in
  the effective template

### Requirement: Templates register by layer-set discovery, not per-template code

The catalog SHALL be seeded by **discovering layer-set folders on the classpath** under the templates
root, so a new template is registered by dropping in its folder with no per-template code change. For
each discovered set the seeder SHALL derive the national `(state, type)` row from the base's `meta`
and one further row per `state-<XX>` overlay beside it, taking the row's version, name, and
description **from the base definition** so catalog metadata cannot drift from the layer set it
points at. A row's display name composes the base's document title with a human name for the state
code, which the seeder holds as a lookup -- so a **new state code** needs a display-name entry or its
row shows the raw code; a new **template type** needs no code change at all.

Only sets whose base is `published` SHALL be seeded, and every seeded row SHALL be `published`.
Seeding SHALL be idempotent per `(state, type)` and SHALL deduplicate by classpath-relative root,
because one set can surface under more than one classpath entry. Test fixture sets SHALL live outside
the production templates root so discovery sees only production sets. The catalog SHALL continue to
store **metadata only, never template bodies**. Seeding SHALL be confined to the non-production
profiles that carry sandbox/dummy data.

#### Scenario: A dropped-in layer set appears in the catalog with no code change

- **GIVEN** a published layer set on the classpath under the templates root
- **WHEN** the application starts under the `local` or `sandbox` profile
- **THEN** the catalog holds its national row plus one row per state overlay beside it
- **AND** each row's version, name, and description derive from that base definition

#### Scenario: Unpublished sets and test fixtures are not seeded

- **GIVEN** a layer set whose base is not `published`, and a test fixture set outside the production
  templates root
- **WHEN** the application starts under the `local` or `sandbox` profile
- **THEN** neither is seeded into the catalog

#### Scenario: Seeding is idempotent and duplicate-safe

- **GIVEN** the application runs under the `local` or `sandbox` profile
- **WHEN** the same layer set surfaces under more than one classpath entry, or the application starts
  more than once
- **THEN** each `(state, type)` is seeded once, first-wins, with no duplicate rows

#### Scenario: No seeding outside the sandbox profiles

- **WHEN** the application starts under a profile other than `local` or `sandbox`
- **THEN** no catalog row is seeded from classpath discovery
