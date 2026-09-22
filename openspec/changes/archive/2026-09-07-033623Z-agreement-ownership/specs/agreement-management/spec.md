## ADDED Requirements

### Requirement: An agreement may be created anonymously and later owned

An agreement SHALL be creatable with **no owner**: `POST /api/agreements` requires no authentication and
persists the agreement with a null `owner_identity_id`, addressed by its unguessable server UUID (a
capability handle), exactly as today. An agreement SHALL carry a nullable server-managed
`owner_identity_id` that references an `Identity`. Ownership SHALL be server-sourced from the caller's
session and SHALL NOT be accepted from any request body (anti-mass-assignment). The create body, its
validation, and its anti-mass-assignment guarantee are otherwise unchanged.

#### Scenario: Anonymous create leaves the agreement unowned

- **WHEN** an unauthenticated client POSTs a valid agreement
- **THEN** the system persists it `201 Created` with a null owner, readable by anyone presenting its id

#### Scenario: A client-supplied owner is ignored

- **WHEN** a create (or edit) body includes an `ownerIdentityId` value
- **THEN** the system ignores it; ownership is set only from the session, only by claim

### Requirement: Save binds an unowned draft to the caller (claim)

The system SHALL provide `POST /api/agreements/{id}/claim` (authenticated) that sets the agreement's
`owner_identity_id` to the caller's identity **only if the agreement is currently unowned**. Claim SHALL be
idempotent for the same owner (a repeat claim returns `200`). If the id is unknown, or the agreement is
already owned by a **different** identity, the system SHALL respond `404 Not Found` -- the same response for
both, so claim is not an ownership or existence oracle. The check-and-set SHALL be atomic so two concurrent
claims cannot both succeed.

#### Scenario: Claiming an unowned draft succeeds and is idempotent

- **GIVEN** an unowned agreement
- **WHEN** an authenticated caller claims it, then claims it again
- **THEN** the first claim sets the owner to the caller and the second returns `200` with no change

#### Scenario: Claiming someone else's agreement is indistinguishable from unknown

- **WHEN** an authenticated caller claims an id that is unknown, or one already owned by a different identity
- **THEN** the system responds `404 Not Found` in both cases

### Requirement: Resume lists the caller's agreements with a derived status

The system SHALL provide `GET /api/agreements` (authenticated) that returns a summary of the agreements
owned by the caller, most-recent first. Each summary SHALL include the property address, monthly rent, start
and end dates, the server-derived duration, the creation timestamp, a **derived status**, and an `editable`
flag. The status SHALL be a read-only projection of the signing state (the agreement itself stays
status-less): an agreement with no signing request SHALL report `DRAFT` (`editable = true`); one whose
signing request is in a non-terminal state (`PDF_GENERATED`, `STAMPED`, or `SIGN_REQUESTED`) SHALL report
`IN_PROGRESS` (`editable = false`); `SIGNED` SHALL report `SIGNED`; `EXPIRED` SHALL report `EXPIRED`;
`FAILED` or `STAMP_FAILED` SHALL report `ACTION_NEEDED`. The list SHALL include both in-progress and signed
agreements. It SHALL return only the caller's agreements and never another identity's.

#### Scenario: The list shows in-progress and signed agreements, scoped to the caller

- **GIVEN** an authenticated caller who owns a draft, an in-progress agreement, and a signed one, and another
  identity who owns further agreements
- **WHEN** the caller calls `GET /api/agreements`
- **THEN** the system returns exactly the caller's three agreements, most-recent first, each with the correct
  derived status and `editable` flag, and none belonging to the other identity

### Requirement: An owner may fully edit an in-progress agreement

The system SHALL provide `PUT /api/agreements/{id}` (authenticated) that fully replaces the mutable terms of
an agreement the caller owns -- the property address, monthly rent, security deposit, tenancy start and end
dates, and the party collection -- using the same request shape and the same create-time validation as
`POST /api/agreements`. Editing SHALL be permitted **only while no signing request exists** for the
agreement; once a signing request exists the agreement is frozen and the edit SHALL respond `409 Conflict`
(the same rule that freezes the draft). A caller that does not own the agreement, or an unknown id, SHALL
receive `404 Not Found` (never `403`; no oracle); an unowned anonymous draft SHALL NOT be editable through
this route. The agreement id, owner, creation timestamp, and derived duration SHALL remain server-managed and
SHALL NOT be accepted from the body. A successful edit SHALL clear the pinned draft reference so a subsequent
generate produces a fresh document from the edited terms.

#### Scenario: Owner edits terms and parties before signing is requested

- **GIVEN** an authenticated caller who owns an agreement with no signing request
- **WHEN** the caller PUTs a body changing the monthly rent, the dates, and the party list
- **THEN** the system revalidates and persists the new terms and parties, recomputes the duration, clears the
  pinned draft, and returns `200 OK` with the updated agreement

#### Scenario: Editing after signing is requested is frozen

- **GIVEN** an owned agreement for which a signing request already exists
- **WHEN** the owner PUTs an edit
- **THEN** the system responds `409 Conflict` and changes nothing

#### Scenario: A non-owner edit is indistinguishable from unknown

- **WHEN** an authenticated caller PUTs an edit to an id they do not own, or an unknown id
- **THEN** the system responds `404 Not Found` in both cases

#### Scenario: An invalid edit is rejected by the same rules as create

- **WHEN** an owner PUTs an edit whose body violates a create-time rule (for example a blank party name, no
  `TENANT`, a non-positive rent, or an end date not after the start)
- **THEN** the system responds `400 Bad Request` as `application/problem+json` and changes nothing

## MODIFIED Requirements

### Requirement: Retrieve an agreement by id

The system SHALL provide `GET /api/agreements/{id}` that returns the persisted agreement, its parties, and
the server-computed tenancy duration. **Access SHALL depend on ownership:** while the agreement is
**unowned** it SHALL be readable by any caller presenting its id (the unguessable id is a bearer capability,
unchanged from today); once the agreement is **claimed** it SHALL be readable only by its owner, and a
non-owner or unauthenticated caller SHALL receive `404 Not Found` (indistinguishable from an unknown id, so
ownership cannot be probed). For an unknown id it SHALL respond `404 Not Found` with an RFC 9457
`application/problem+json` body (per the `api-error-handling` capability); the body SHALL NOT leak internal
detail. A syntactically invalid id (not a UUID) SHALL respond `400 Bad Request` as problem+json, not 404.

#### Scenario: An unowned agreement is readable by id

- **WHEN** a client GETs `/api/agreements/{id}` for an unowned agreement
- **THEN** the system responds `200 OK` with the property address, monthly rent, security deposit, start
  date, end date, the computed duration, the creation timestamp, and every party's details

#### Scenario: A claimed agreement is owner-scoped

- **GIVEN** an agreement claimed by one identity
- **WHEN** a different (or unauthenticated) caller GETs `/api/agreements/{id}` for it
- **THEN** the system responds `404 Not Found` as `application/problem+json`, indistinguishable from an
  unknown id

#### Scenario: Unknown agreement id returns 404

- **WHEN** a client GETs `/api/agreements/{id}` for an id that does not exist
- **THEN** the system responds `404 Not Found` as `application/problem+json`

#### Scenario: Non-UUID agreement id returns 400

- **WHEN** a client GETs `/api/agreements/not-a-uuid`
- **THEN** the system responds `400 Bad Request` as `application/problem+json`
