## MODIFIED Requirements

### Requirement: A commercial lease product line resolves from its own layer set

The system SHALL serve a **Commercial Lease Agreement** product line at the dimensions `(state, type)
= (IN, commercial)`, `(TG, commercial)` and `(KA, commercial)`, resolved from a layer set separate
from the residential one. The commercial base SHALL declare its own document header -- title
`Commercial Lease Agreement`, its own subtitle, and its own execution line -- because the effective
definition takes `meta` verbatim from the base and no patch operation edits `meta`; reusing the
residential base would title a commercial document "Residential Tenancy".

The commercial set SHALL carry commercial party roles (Lessor / Lessee, mapped onto the same
aggregate name keys the residential set uses) and commercial-specific fields and clauses, and SHALL
NOT carry the residential-only fields (BHK, furnishing, pets, occupants). It SHALL carry the same
required, undefaulted `subletting` choice as the residential set -- the same field key, options and
`Term` placement, with three option-gated covenants worded for Lessor and Lessee in place of Owner and
Tenant -- and SHALL NOT carry a fixed `noSublettingClause`. The Telangana and Karnataka
layers SHALL apply over the composed commercial structure along the engine's fixed precedence chain
(`base -> type -> state -> state_type`), so `state-TG` or `state-KA` layers over the base and type
layer and the matching `state_type-<XX>-commercial` applies above it -- exactly as for residential.

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

#### Scenario: Karnataka overlays the commercial structure

- **WHEN** the `(KA, commercial)` template resolves
- **THEN** the effective template carries the Karnataka fields (`stampDutyAmount`,
  `registrationChargesBorneBy`) and defaults `jurisdictionCity` to `Bengaluru`
- **AND** the `Statutory (Karnataka)` section is ordered after the covenant and annexure sections and
  before the `In Witness Whereof` execution block

#### Scenario: Karnataka is served as a commercial dimension

- **WHEN** the commercial catalog is listed
- **THEN** `(KA, commercial)` is present as a published dimension
- **AND** it resolves from the shared commercial layer set, not a Karnataka-specific base

#### Scenario: The execution block renders for the commercial line too

- **WHEN** a commercial agreement document renders
- **THEN** its `In Witness Whereof` section renders as a signature block carrying the `esign:owner`
  and `esign:tenant` anchors, the same as the residential line

#### Scenario: The commercial deed carries the chosen sub-letting covenant in Lessor/Lessee terms

- **GIVEN** the `(IN, commercial)`, `(TG, commercial)` and `(KA, commercial)` sets
- **WHEN** each is compiled with `subletting` set to each option in turn
- **THEN** exactly one sub-letting covenant renders, it names the Lessee and the Lessor rather than the
  Tenant and the Owner, and every `Now This Agreement Witnesseth` list references the three clauses and
  not `noSublettingClause`

#### Scenario: Every required commercial field is aggregate-backed, defaulted or user-answered

- **WHEN** the commercial template resolves
- **THEN** each required field is backed by a key the agreement aggregate holds, carries a default, or
  is a user-answered field (required, no default) in a mandatory section of the capture form -- so
  generate-as-draft fed the aggregate keys plus the `subletting` answer reaches parity, and no
  required field is unanswerable

### Requirement: The commercial type layer pins the commercial character of the document

The commercial `type` layer SHALL make the `permittedUse` field **required** and SHALL default it to
`commercial`, so a generated draft always carries a commercial-use covenant even when the agreement
aggregate supplies no value for it. Because the default satisfies generate's required-check, this
SHALL NOT break generate/preview parity: `permittedUse` is not an aggregate-backed key, and the
required-with-default shape keeps it out of the capture gap.

#### Scenario: A generated draft carries the commercial use covenant without user input

- **GIVEN** the aggregate-backed field keys plus the user-answered `subletting` are supplied, and no
  `permittedUse`
- **WHEN** a commercial agreement is generated as a draft
- **THEN** `permittedUse` resolves to `commercial` from its default
- **AND** generate succeeds -- the `permittedUse` required-check is satisfied by the default, not by
  captured data
