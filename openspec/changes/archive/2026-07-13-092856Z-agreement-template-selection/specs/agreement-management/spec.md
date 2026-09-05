## MODIFIED Requirements

### Requirement: Create a multi-party rental agreement

The system SHALL provide `POST /api/agreements` that creates a persisted rental agreement
from a JSON body carrying the property and tenancy terms and a collection of parties. On
success it SHALL return `201 Created` with the persisted agreement, including a
server-assigned agreement id, a server-assigned id for each party, and the server-computed
tenancy duration.

The client-settable fields SHALL be exactly: the **property address**, the **monthly
rent**, the **security deposit**, the **tenancy start date**, the **tenancy end date**, the
**party list**, and an **OPTIONAL catalog selection** of **state** and **type**. Each party
SHALL carry a **first name**, **last name**, **father's name**, **current address**, a
**role** of `OWNER` or `TENANT`, an **optional email** and an **optional mobile**, and an
**optional full-name override**. The agreement id, each party id, the creation timestamp, the
derived term in months, the computed duration, and the **selected template id** SHALL be
server-assigned and SHALL NOT be accepted from the client (anti-mass-assignment).

When the body supplies **both** a `state` and a `type`, the system SHALL resolve the
**published** catalog template for that `(state, type)` and SHALL record its
server-owned template id on the agreement. When no published template covers the supplied
`(state, type)`, the system SHALL reject the request with `404 Not Found` as an RFC 9457
problem whose detail is a fixed constant and SHALL NOT echo the requested dimensions (no
existence oracle), and SHALL persist nothing. When the selection is absent (neither or only
one of `state`/`type` supplied), the system SHALL create the agreement with **no** selected
template, preserving the pre-selection default behaviour.

Each party SHALL have a stored **full name (as per Aadhaar)**: when the client supplies a
non-blank full-name override the system SHALL use it; otherwise the system SHALL derive it
as the first name followed by the last name. This full name is the name the system uses for
the eSign invitee, so it SHALL be editable by the client to match the signer's Aadhaar
record.

The created agreement SHALL remain **status-less** (the signing-status FSM lives on the
signing-request aggregate).

#### Scenario: Valid richly-detailed agreement is created

- **WHEN** a client POSTs an agreement with a property address, monthly rent, security
  deposit, a start date and a later end date, and two owners and one tenant, each with a
  first name, last name, father's name, and current address
- **THEN** the system persists one agreement and three party rows and responds `201
  Created` with server-assigned ids, the creation timestamp, and the computed duration

#### Scenario: A supplied (state, type) records the published template server-side

- **WHEN** a client POSTs an otherwise-valid agreement with `state` and `type` naming a pair
  a published template covers
- **THEN** the system resolves that published template and records its server-owned template
  id on the agreement (never a client-set id)
- **AND** the recorded selection drives the dimension-aware document render and reproducibility
  pin at generate-as-draft

#### Scenario: A (state, type) no published template covers is cleanly rejected

- **WHEN** a client POSTs an agreement with a `state`/`type` pair that no published template
  covers
- **THEN** the system responds `404 Not Found` with a fixed problem detail that does not echo
  the requested `state` or `type`
- **AND** no agreement is persisted

#### Scenario: Omitting the selection keeps the default behaviour

- **WHEN** a client POSTs an otherwise-valid agreement with no `state`/`type`
- **THEN** the system creates the agreement with no selected template and the document render
  resolves the default effective template (unchanged from before this change)

#### Scenario: Full name is derived when not supplied, and honoured when overridden

- **WHEN** a client POSTs a party with first name "Asha" and last name "Rao" and no
  full-name override
- **THEN** the system stores that party's full name as "Asha Rao"
- **AND** when another party supplies a full-name override, the system stores the override
  verbatim (so it can match that signer's Aadhaar record)

#### Scenario: Party contact is optional at create

- **WHEN** a client POSTs an otherwise-valid agreement in which one or more parties have no
  email or mobile
- **THEN** the system accepts and persists the agreement `201 Created` (contact is not
  required to draft)
