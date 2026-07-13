## MODIFIED Requirements

### Requirement: Map rendered eSign anchors to provider signature fields

When creating a signing request, the signing module SHALL derive each signer's `esign:<role>` anchor
from its role -- the same deterministic token the renderer emits at each signature zone -- and map it
to the eSign provider's signature field for that signer. The mapping SHALL keep the `documents` module
eSign-agnostic (the anchor is a plain role key, not a provider type; `signing` holds no
`documents.template` type) and SHALL NOT introduce a new signing-status FSM state -- anchors are
produced at `PDF_GENERATED` (generate-as-draft) and consumed at `SIGN_REQUESTED`
(create-signing-request).

#### Scenario: Anchors mapped to signature fields

- **GIVEN** a drafted two-party agreement with an Owner and a Tenant (whose rendered PDF carries the
  `esign:owner` and `esign:tenant` zones)
- **WHEN** a signing request is created
- **THEN** the provider request contains one signature field for the Owner and one for the Tenant,
  each bound to its `esign:<role>` anchor
- **AND** the signing-status transition is the existing `SIGN_REQUESTED` (no new state)

#### Scenario: Exercised against the stub provider, no live credentials

- **GIVEN** the sandbox stub / WireMock eSign provider
- **WHEN** a signing request is created for a two-party agreement
- **THEN** the anchor -> field mapping succeeds without any live vendor credential
- **AND** no Aadhaar / OTP / VID is logged

#### Scenario: No anchors -> clear failure, nothing submitted

- **GIVEN** an agreement whose signer set yields no eSign anchors (no signers -- nothing signable)
- **WHEN** a signing request is attempted
- **THEN** it fails clearly before any provider call and no partial request is submitted to the
  provider
