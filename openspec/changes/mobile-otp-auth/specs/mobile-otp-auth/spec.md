## ADDED Requirements

### Requirement: Request an OTP login challenge

The system SHALL provide `POST /api/auth/otp/request` that accepts a mobile number,
creates a short-lived, single-use OTP challenge for it, dispatches the code through the
`OtpSender` seam, and responds `202 Accepted` with an opaque **challenge id**.

The endpoint SHALL be reachable without authentication. Its response SHALL be
**identical whether or not the mobile number already has an identity** — it SHALL NOT
reveal whether the number is known (no account-enumeration oracle). The generated code
SHALL NOT appear in the response body, and neither the code nor the mobile number SHALL
be written to any log.

A mobile number that is missing or not a valid E.164 number SHALL be rejected with
`400 Bad Request` and SHALL NOT create a challenge.

#### Scenario: Challenge is created for a new number

- **WHEN** a client POSTs `/api/auth/otp/request` with a valid, previously-unseen
  mobile number
- **THEN** the system creates one unconsumed, unexpired challenge, dispatches the code
  via the sender, and responds `202 Accepted` with a challenge id
- **AND** the response body does not contain the code, and no log line contains the code
  or the full mobile number

#### Scenario: Response does not reveal whether a number is known

- **WHEN** a client POSTs `/api/auth/otp/request` for a number that already has an
  identity, and separately for a number that does not
- **THEN** both responses are `202 Accepted` and indistinguishable (same shape, no
  field revealing prior existence)

#### Scenario: Invalid mobile number is rejected

- **WHEN** a client POSTs `/api/auth/otp/request` with a blank or non-E.164 mobile
  number
- **THEN** the system responds `400 Bad Request` and creates no challenge

### Requirement: Verify an OTP and establish a session

The system SHALL provide `POST /api/auth/otp/verify` that accepts a challenge id and a
submitted code. When the code matches the referenced challenge and the challenge is
unexpired, unconsumed, and within its attempt limit, the system SHALL **consume** the
challenge (making it single-use), find-or-create the `MobileIdentity` for the
challenge's mobile number, open an authenticated session, and respond `200 OK` with an
opaque session value.

A submitted code that is wrong, or a challenge that is expired, already consumed, has
exceeded its attempt limit, or does not exist SHALL yield `401 Unauthorized` with no
session created. Verification SHALL compare codes in **constant time** and SHALL count
the failed attempt against the challenge's attempt limit.

#### Scenario: Correct code establishes a session and identity

- **WHEN** a client POSTs `/api/auth/otp/verify` with a valid challenge id and the
  correct code, before expiry and within the attempt limit
- **THEN** the system consumes the challenge, ensures a `MobileIdentity` exists for that
  number (creating it on first login, reusing it thereafter), opens a session, and
  responds `200 OK` with an opaque session value

#### Scenario: A consumed challenge cannot be reused

- **WHEN** a client POSTs `/api/auth/otp/verify` a second time with a challenge id that
  was already consumed by a successful verify
- **THEN** the system responds `401 Unauthorized` and opens no new session

#### Scenario: Wrong code is rejected and counts against the attempt limit

- **WHEN** a client submits an incorrect code for a live challenge
- **THEN** the system responds `401 Unauthorized`, records the failed attempt, and once
  the attempt limit is exceeded the challenge can no longer be verified even with the
  correct code

#### Scenario: Expired challenge is rejected

- **WHEN** a client POSTs `/api/auth/otp/verify` for a challenge whose time-to-live has
  elapsed
- **THEN** the system responds `401 Unauthorized` and opens no session

### Requirement: OTP challenge storage is non-recoverable

An OTP code SHALL be persisted only as a salted, one-way hash — never in plaintext and
never in a reversible form. The stored challenge SHALL carry an expiry timestamp, an
attempt counter, and a consumed marker. The code SHALL never be returned by any endpoint
and SHALL never be logged.

#### Scenario: Stored challenge does not expose the code

- **WHEN** a challenge has been created
- **THEN** the persisted row holds only a hash of the code (plus expiry, attempts, and
  consumed state), and no plaintext code is stored or retrievable

### Requirement: Session authentication is opaque, hashed, and expiring

The system SHALL authenticate requests to protected endpoints by an opaque session value
presented by the caller. The session value SHALL be high-entropy random, persisted
**only as a hash**, and carry an expiry. A Spring Security filter SHALL resolve the
caller's identity by hashing the presented value and looking up a live, unexpired
session in constant time; a missing, unknown, or expired session SHALL result in
`401 Unauthorized` on a protected endpoint. No personal data SHALL be encoded in the
session value itself.

#### Scenario: Valid session authenticates a protected request

- **WHEN** a caller presents a live session value on a protected endpoint
- **THEN** the request is authenticated as the owning `MobileIdentity` and proceeds

#### Scenario: Expired or unknown session is rejected

- **WHEN** a caller presents an expired, revoked, or never-issued session value on a
  protected endpoint
- **THEN** the system responds `401 Unauthorized`

### Requirement: Logout revokes the session

The system SHALL provide `POST /api/auth/logout` that revokes the caller's current
session so it can no longer authenticate. It SHALL respond `204 No Content`, and SHALL be
idempotent (revoking an already-revoked or absent session still returns `204`).

#### Scenario: Logout invalidates the session

- **WHEN** an authenticated caller POSTs `/api/auth/logout`, then reuses the same session
  value on a protected endpoint
- **THEN** the logout responds `204 No Content` and the subsequent protected request
  responds `401 Unauthorized`

### Requirement: OTP request and verify are rate-limited

The system SHALL bound how often OTP challenges may be requested for a given mobile
number and how often verification may be attempted for a given challenge, within a
configured window. Exceeding a limit SHALL be rejected with `429 Too Many Requests`
without creating a challenge or consuming one, and SHALL NOT leak whether the number is
known.

#### Scenario: Excessive OTP requests are throttled

- **WHEN** a client requests OTP challenges for the same mobile number more times than
  the configured limit within the window
- **THEN** the system responds `429 Too Many Requests` for the excess requests and
  creates no further challenge

#### Scenario: Excessive verify attempts are throttled

- **WHEN** a client submits verification attempts for one challenge beyond the configured
  attempt limit
- **THEN** the system stops accepting attempts for that challenge and responds `401`
  (attempt limit) or `429` (rate window), never eventually succeeding by brute force

### Requirement: Sandbox OTP delivery sends no real SMS

OTP delivery SHALL go through an `OtpSender` interface so the transport is swappable. The
default (sandbox) sender SHALL NOT contact any real SMS provider and SHALL NOT log the
code or the mobile number. A real SMS provider adapter SHALL be a one-adapter swap behind
`OtpSender`, taking any credential from environment variables only.

A dev-only fixed code MAY be enabled for local testing, but SHALL be disabled by default
and SHALL NOT be active in any non-local profile.

#### Scenario: Default sender does not emit real messages or logs

- **WHEN** the application runs with the default `OtpSender` and an OTP challenge is
  created
- **THEN** no real SMS is sent, and no log line at any level contains the code or the
  full mobile number

### Requirement: Identity and auth schema is Flyway-managed

The `mobile_identity`, `otp_challenge`, and `auth_session` tables SHALL be created by a
forward-only Flyway migration (`V7__mobile_identity_auth.sql`); the JPA mapping SHALL
match the migrated schema so the application boots under `ddl-auto: validate`. The
migration SHALL NOT edit any existing `V1`–`V6` migration.

#### Scenario: Application boots against the migrated schema

- **WHEN** the application starts against a database where the Flyway migrations,
  including `V7`, have been applied
- **THEN** Hibernate schema validation passes and the context starts, and
  `flyway_schema_history` records `V7` applied successfully