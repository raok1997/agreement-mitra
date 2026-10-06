## ADDED Requirements

### Requirement: The Karnataka commercial statutory overlay is mandatory

The Karnataka layers over the commercial set SHALL contribute a statutory section that always renders
-- in the live preview and in every generated Karnataka commercial draft -- with no user action.

This deliberately differs from the Telangana commercial overlay, which is opt-in. The Karnataka
`state_type` layer supersedes and removes the national stamp-and-registration clause, so an opt-in
Karnataka section would leave a default Karnataka commercial deed with no stamp or registration
clause at all. The same reasoning already made the Telangana *residential* overlay mandatory; the
Karnataka commercial set SHALL NOT repeat the defect the Telangana commercial set still carries.

The overlay SHALL state the law governing the lease in Karnataka, the stamping and registration
obligation before the jurisdictional Sub-Registrar, and who bears those charges; and the stamp duty
amount SHALL remain system-sourced, filled from the attached certificate at stamp intake rather than
typed by the customer.

#### Scenario: The commercial statutory overlay renders without being added

- **WHEN** a Karnataka commercial agreement is previewed with no optional sections active
- **THEN** the Karnataka statutory section renders
- **AND** exactly one stamp-and-registration clause renders, being the Karnataka clause

#### Scenario: The commercial jurisdiction covenant names a court

- **WHEN** a Karnataka commercial draft is generated without the optional dispute-resolution section
- **THEN** the exclusive-jurisdiction covenant names the Karnataka default city

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
NOT carry the residential-only fields (BHK, furnishing, pets, occupants). The Telangana and Karnataka
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

#### Scenario: Every required commercial field is aggregate-backed or defaulted

- **WHEN** the commercial template resolves
- **THEN** each required field is either backed by a key the agreement aggregate holds or carries a
  default, so generate-as-draft reaches parity without a capture gap
