## ADDED Requirements

### Requirement: Create a multi-party rental agreement

The system SHALL provide `POST /api/agreements` that creates a persisted rental
agreement from a JSON body carrying the rental terms and a collection of signers.
On success it SHALL return `201 Created` with the persisted agreement, including a
server-assigned agreement id and a server-assigned id for each signer.

The request body SHALL be the only source of client-settable fields (property
address, monthly rent, security deposit, term in months, and the signer list).
The agreement id, each signer id, and the creation timestamp SHALL be assigned by
the server and SHALL NOT be accepted from the client (anti-mass-assignment).

The created agreement SHALL be **status-less**: it carries no signing-status field.
The signing-status FSM (`DRAFT → PDF_GENERATED → SIGN_REQUESTED → SIGNED | FAILED |
EXPIRED`) is out of scope for this capability and is introduced by a later change.

#### Scenario: Valid multi-party agreement is created

- **WHEN** a client POSTs an agreement with valid rental terms and two owners and
  one tenant, each with a name and a distinct valid email
- **THEN** the system persists one agreement row and three signer rows
- **AND** responds `201 Created` with a server-assigned agreement id, each signer's
  server-assigned id and role, and the creation timestamp

#### Scenario: Client-supplied ids and timestamp are ignored

- **WHEN** a client POSTs an agreement body that also includes an `id` or
  `createdAt` value
- **THEN** the system ignores those fields and assigns its own id and timestamp
- **AND** the response reflects the server-assigned values, not the client's

### Requirement: Signers are multi-party owners and tenants

An agreement SHALL hold a collection of signers, each an addressable entity with a
server-assigned id, a name, an email, and a role of `OWNER` or `TENANT`. An
agreement SHALL support any number of owners and any number of tenants.

#### Scenario: Multiple owners and tenants are retained distinctly

- **WHEN** an agreement is created with three owners and two tenants
- **THEN** all five signers are persisted as distinct rows, each with its own id,
  its name, its email, and its role
- **AND** retrieving the agreement returns all five signers

### Requirement: Create-time agreement validation

The system SHALL reject a create request that violates any of the following, with
`400 Bad Request` and SHALL NOT persist any row:

- the signer collection is empty, or holds more than the supported maximum (20);
- any signer has a missing name or an invalid email;
- the agreement has no signer with role `OWNER`, or no signer with role `TENANT`;
- two or more signers share the same email within the one agreement (compared
  case-insensitively);
- the property address is blank;
- the monthly rent is null or not strictly positive (zero or negative), or the
  security deposit is null or negative;
- either money amount has more than two fractional digits (it would not fit the stored
  scale);
- the term in months is not strictly positive.

The 400 response body MAY be empty (the platform is configured not to echo binding
errors or messages); clients SHALL rely on the status code, not a field-level error
body, in this change.

#### Scenario: Empty signer list is rejected

- **WHEN** a client POSTs an agreement with an empty signer list
- **THEN** the system responds `400 Bad Request` and persists nothing

#### Scenario: Missing a required role is rejected

- **WHEN** a client POSTs an agreement whose signers are all `OWNER` (no `TENANT`)
- **THEN** the system responds `400 Bad Request` and persists nothing

#### Scenario: Duplicate signer emails are rejected

- **WHEN** a client POSTs an agreement where two signers share the same email
- **THEN** the system responds `400 Bad Request` and persists nothing

#### Scenario: Invalid signer email is rejected

- **WHEN** a client POSTs an agreement where a signer's email is not a valid email
  address
- **THEN** the system responds `400 Bad Request` and persists nothing

#### Scenario: Invalid rental terms are rejected

- **WHEN** a client POSTs an otherwise-valid agreement whose monthly rent is zero or
  negative, or whose term in months is zero or negative, or whose property address is
  blank
- **THEN** the system responds `400 Bad Request` and persists nothing

#### Scenario: Too many signers is rejected

- **WHEN** a client POSTs an agreement with more than the supported maximum number of
  signers
- **THEN** the system responds `400 Bad Request` and persists nothing

### Requirement: Retrieve an agreement by id

The system SHALL provide `GET /api/agreements/{id}` that returns the persisted
agreement and its signers. For an unknown id it SHALL respond `404 Not Found`.

#### Scenario: Existing agreement is returned

- **WHEN** a client GETs `/api/agreements/{id}` for a previously created agreement
- **THEN** the system responds `200 OK` with the agreement's rental terms, its
  creation timestamp, and every signer's id, name, email, and role

#### Scenario: Unknown agreement id returns 404

- **WHEN** a client GETs `/api/agreements/{id}` for an id that does not exist
- **THEN** the system responds `404 Not Found`

### Requirement: Agreement schema is Flyway-managed

The `agreement` and `signer` tables SHALL be created by a forward-only Flyway
migration (`V2__agreement.sql`); the JPA mapping SHALL match the migrated schema so
the application boots under `ddl-auto: validate`. The `signer` table SHALL carry a
foreign key to `agreement` and an index on that foreign key. The migration SHALL
NOT edit the existing `V1` baseline.

#### Scenario: Application boots against the migrated schema

- **WHEN** the application starts against a database where the Flyway migrations
  have been applied
- **THEN** Hibernate schema validation passes (the entity mapping matches the
  migrated `agreement` and `signer` tables) and the context starts
