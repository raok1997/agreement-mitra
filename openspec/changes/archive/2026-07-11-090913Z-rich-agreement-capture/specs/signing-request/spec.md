## ADDED Requirements

### Requirement: A signing request requires a reachable contact for every party

Because a party's email and mobile are optional when an agreement is drafted, the system
SHALL verify that **every** party on the agreement has **at least one** reachable contact
(email or mobile) before a signing request is created. When any party has neither,
`POST /api/signing/{id}/request` SHALL respond `409 Conflict` and SHALL NOT create a signing
request, procure a stamp, or call the eSign provider. This check SHALL occur before any
state transition, alongside the existing "draft PDF present" precondition. When a party has
both an email and a mobile, the eSign invite SHALL be addressed to both channels.

#### Scenario: Signing is blocked when a party has no contact

- **WHEN** a client requests a signing for an agreement on which at least one party has no
  email or mobile
- **THEN** the system responds `409 Conflict`, creates no signing request, procures no
  stamp, and makes no provider call

#### Scenario: Signing proceeds when every party has at least one contact

- **WHEN** every party on the agreement has an email or a mobile (or both) and a draft PDF
  is present
- **THEN** the signing-request precondition passes and the existing flow proceeds
  (`PDF_GENERATED` and onward) unchanged, addressing each invite to every channel the party
  provided
