## MODIFIED Requirements

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

The 400 response body SHALL be an RFC 9457 `application/problem+json` document
carrying a field-level `errors` list (per the `api-error-handling` capability):
one `{field, message}` entry per violated constraint, with the cross-field
signer-set rules mapped to a stable non-null `field` token. The body SHALL NOT
echo the rejected input value or any submitted PII.

#### Scenario: Empty signer list is rejected

- **WHEN** a client POSTs an agreement with an empty signer list
- **THEN** the system responds `400 Bad Request` and persists nothing

#### Scenario: Missing a required role is rejected

- **WHEN** a client POSTs an agreement whose signers are all `OWNER` (no `TENANT`)
- **THEN** the system responds `400 Bad Request` and persists nothing
- **AND** the problem+json `errors` list identifies the signer-set rule

#### Scenario: Duplicate signer emails are rejected

- **WHEN** a client POSTs an agreement where two signers share the same email
- **THEN** the system responds `400 Bad Request` and persists nothing
- **AND** the response body does not echo the duplicated email value

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
agreement and its signers. For an unknown id it SHALL respond `404 Not Found` with
an RFC 9457 `application/problem+json` body (per the `api-error-handling`
capability); the body SHALL NOT leak internal detail. A syntactically invalid id
(not a UUID) SHALL respond `400 Bad Request` as problem+json, not 404.

#### Scenario: Existing agreement is returned

- **WHEN** a client GETs `/api/agreements/{id}` for a previously created agreement
- **THEN** the system responds `200 OK` with the agreement's rental terms, its
  creation timestamp, and every signer's id, name, email, and role

#### Scenario: Unknown agreement id returns 404

- **WHEN** a client GETs `/api/agreements/{id}` for an id that does not exist
- **THEN** the system responds `404 Not Found` as `application/problem+json`

#### Scenario: Non-UUID agreement id returns 400

- **WHEN** a client GETs `/api/agreements/not-a-uuid`
- **THEN** the system responds `400 Bad Request` as `application/problem+json`
