## MODIFIED Requirements

### Requirement: Create a multi-party rental agreement

The system SHALL provide `POST /api/agreements` that creates a persisted rental agreement
from a JSON body carrying the property and tenancy terms and a collection of parties. On
success it SHALL return `201 Created` with the persisted agreement, including a
server-assigned agreement id, a server-assigned id for each party, and the server-computed
tenancy duration.

The client-settable fields SHALL be exactly: the **property address**, the **monthly
rent**, the **security deposit**, the **tenancy start date**, the **tenancy end date**, the
**party list**, and -- for an agreement whose document is customer-supplied rather than
generated -- the **duty state** (the state in which the property lies). Each party SHALL
carry a **first name**, **last name**, **father's name**, **current address**, a **role** of
`OWNER` or `TENANT`, an **optional email** and an **optional mobile**, and an **optional
full-name override**. The agreement id, each party id, the creation timestamp, the derived
term in months, the computed duration, the **document source**, and the **uploaded-document
content hash** SHALL be server-assigned and SHALL NOT be accepted from the client
(anti-mass-assignment).

Each party SHALL have a stored **full name (as per Aadhaar)**: when the client supplies a
non-blank full-name override the system SHALL use it; otherwise the system SHALL derive it
as the first name followed by the last name. This full name is the name the system uses for
the eSign invitee, so it SHALL be editable by the client to match the signer's Aadhaar
record.

An agreement SHALL be creatable with **no selected template**, as a customer-supplied
(bring-your-own) agreement. Such an agreement SHALL record its document source explicitly
rather than by the absence of a selected template, because an agreement created without a
state and type already resolves to the default template dimensions and so already carries
no selected template. A bring-your-own agreement SHALL derive its duty jurisdiction from its
declared duty state rather than from a template's state dimension, and SHALL carry a content
hash of the uploaded document in place of a template pin.

The created agreement SHALL remain **status-less** (the signing-status FSM lives on the
signing-request aggregate).

#### Scenario: Valid richly-detailed agreement is created

- **WHEN** a client POSTs an agreement with a property address, monthly rent, security
  deposit, a start date and a later end date, and two owners and one tenant, each with a
  first name, last name, father's name, and current address
- **THEN** the system persists one agreement and three party rows and responds `201
  Created` with server-assigned ids, the creation timestamp, and the computed duration

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

#### Scenario: Client-supplied ids, timestamp, term, and duration are ignored

- **WHEN** a client POSTs an agreement body that also includes an `id`, `createdAt`,
  `termMonths`, or `duration` value
- **THEN** the system ignores those fields, assigns its own id and timestamp, and derives
  the term and duration itself from the dates
- **AND** the response reflects the server-assigned values, not the client's

#### Scenario: A bring-your-own agreement is created with no template

- **WHEN** a client creates an agreement whose document will be customer-supplied
- **THEN** the system persists it with its document source recorded explicitly, no selected
  template, and no template pin
- **AND** its declared duty state is persisted as the duty jurisdiction

#### Scenario: Document source and upload hash are not client-settable

- **WHEN** a create or edit body supplies a document source or an uploaded-document content
  hash
- **THEN** the system ignores both and keeps its own server-managed values

#### Scenario: An agreement created without a state and type is still templated

- **WHEN** a client POSTs an agreement with no state and no type
- **THEN** it is recorded as a templated agreement resolving to the default template
  dimensions, not as a bring-your-own agreement
