## MODIFIED Requirements

### Requirement: Create a multi-party rental agreement

The system SHALL provide `POST /api/agreements` that creates a persisted rental agreement
from a JSON body carrying the property and tenancy terms and a collection of parties. On
success it SHALL return `201 Created` with the persisted agreement, including a
server-assigned agreement id, a server-assigned id for each party, and the server-computed
tenancy duration.

The client-settable fields SHALL be exactly: the **property address**, the **monthly
rent**, the **security deposit**, the **tenancy start date**, the **tenancy end date**, and
the **party list**. Each party SHALL carry a **first name**, **last name**, **father's
name**, **current address**, a **role** of `OWNER` or `TENANT`, an **optional email** and
an **optional mobile**, and an **optional full-name override**. The agreement id,
each party id, the creation timestamp, the derived term in months, and the computed duration
SHALL be server-assigned and SHALL NOT be accepted from the client (anti-mass-assignment).

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

### Requirement: Signers are multi-party owners and tenants

An agreement SHALL hold a collection of parties, each an addressable entity with a
server-assigned id, a **first name**, a **last name**, a **father's name**, a **current
address**, a **role** of `OWNER` or `TENANT`, an optional email, an optional mobile, and a
stored **full name (as per Aadhaar)** derived from the first and last name unless
overridden. An agreement SHALL support any number of owners and any number of
tenants.

#### Scenario: Multiple owners and tenants retain their full details

- **WHEN** an agreement is created with three owners and two tenants, each with full name
  parts, father's name, and current address
- **THEN** all five parties are persisted as distinct rows, each with its own id and its own
  first name, last name, father's name, current address, role, and stored full name
- **AND** retrieving the agreement returns all five with those details

### Requirement: Create-time agreement validation

The system SHALL reject a create request that violates any of the following with `400 Bad
Request` and SHALL NOT persist any row:

- any party has a blank first name, blank last name, blank father's name, or blank current
  address;
- a party supplies a full-name override that is blank;
- a party's email is present but not a valid email address, or its mobile is present but
  not a valid mobile number;
- the party collection is empty or exceeds the supported maximum (20);
- there is no party with role `OWNER`, or none with role `TENANT`;
- two or more parties that each supply a contact share the same email within the one
  agreement (compared case-insensitively);
- the property address is blank;
- the monthly rent is null or not strictly positive, or the security deposit is null or
  negative, or either money amount has more than two fractional digits;
- the start date or end date is missing, or the end date is not strictly after the start
  date.

#### Scenario: Missing a required party name part is rejected

- **WHEN** a client POSTs an agreement where a party has a blank father's name or blank
  current address
- **THEN** the system responds `400 Bad Request` and persists nothing

#### Scenario: End date not after start date is rejected

- **WHEN** a client POSTs an agreement whose end date equals or precedes its start date, or
  whose start or end date is missing
- **THEN** the system responds `400 Bad Request` and persists nothing

#### Scenario: Missing a required role is rejected

- **WHEN** a client POSTs an agreement whose parties are all `OWNER` (no `TENANT`)
- **THEN** the system responds `400 Bad Request` and persists nothing

#### Scenario: Duplicate party contacts are rejected

- **WHEN** a client POSTs an agreement where two parties supply the same email
- **THEN** the system responds `400 Bad Request` and persists nothing

### Requirement: Retrieve an agreement by id

The system SHALL provide `GET /api/agreements/{id}` that returns the persisted agreement,
its parties, and the server-computed tenancy duration. For an unknown id it SHALL respond
`404 Not Found`.

#### Scenario: Existing agreement returns full details and duration

- **WHEN** a client GETs `/api/agreements/{id}` for a previously created agreement
- **THEN** the system responds `200 OK` with the property address, monthly rent, security
  deposit, start date, end date, the computed duration, the creation timestamp, and every
  party's id, full name, first name, last name, father's name, current address, role, and
  contact

## ADDED Requirements

### Requirement: Tenancy duration is derived from start and end dates, in months

The tenancy SHALL be defined by its **start and end dates**, which are the source of truth.
The system SHALL derive, on the server, the **tenancy duration in whole months** (the number
of complete months between the start and end dates, exclusive of the end date) and SHALL
include it in create and retrieve responses as the value shown to the user. The duration
SHALL NOT be accepted from the client; it SHALL be recomputed from the dates so it cannot
drift from them.

#### Scenario: Duration reflects the date span in months

- **WHEN** an agreement runs from a start date to an end date eleven whole months later
- **THEN** the response reports the duration as eleven months

#### Scenario: Duration is recomputed, never client-supplied

- **WHEN** a client includes a `termMonths` or `duration` in the create body that disagrees
  with its dates
- **THEN** the system ignores the supplied value and returns the duration computed from the
  start and end dates
