## MODIFIED Requirements

### Requirement: Create a multi-party rental agreement

The system SHALL provide `POST /api/agreements` that creates a persisted rental
agreement from a JSON body carrying the rental terms and a collection of signers. The
endpoint SHALL be reachable **without authentication** (anonymous drafting is the default
path). On success it SHALL return `201 Created` with the persisted agreement, including a
server-assigned agreement id and a server-assigned id for each signer.

An agreement created anonymously SHALL have **no owner** (`owner_identity_id` is null) and
SHALL be addressable only by its **unguessable agreement id**. The request body SHALL
remain the only source of client-settable fields (property address, monthly rent,
security deposit, term in months, and the signer list); the agreement id, each signer id,
the creation timestamp, **and the owner** are server-managed and SHALL NOT be accepted
from the client (anti-mass-assignment) — in particular the client SHALL NOT be able to set
an owner at create time.

The created agreement SHALL remain **status-less** (the signing-status FSM lives on the
signing-request aggregate, unchanged by this capability).

#### Scenario: Anonymous create succeeds with no owner

- **WHEN** a client POSTs a valid agreement with no session
- **THEN** the system persists the agreement with a null owner, and responds
  `201 Created` with server-assigned ids and the creation timestamp

#### Scenario: Client-supplied owner, ids, and timestamp are ignored

- **WHEN** a client POSTs an agreement body that also includes an `id`, `createdAt`, or an
  owner/identity field
- **THEN** the system ignores those fields and assigns its own id and timestamp and leaves
  the owner unset
- **AND** the response reflects the server-assigned values, not the client's

### Requirement: Retrieve an agreement by id

The system SHALL provide `GET /api/agreements/{id}` that returns the persisted agreement
and its signers. Access depends on ownership:

- while the agreement is **unowned** (an anonymous draft), it SHALL be retrievable by
  **any caller that presents its id** (the unguessable id is a capability), authenticated
  or not;
- once the agreement is **claimed** (owned), it SHALL be retrievable **only by its owner**;
  a non-owner or unauthenticated caller SHALL receive `404 Not Found`, identical to the
  response for an id that does not exist (no existence oracle).

#### Scenario: Anyone with the id can read an unowned draft

- **WHEN** a caller GETs `/api/agreements/{id}` for an unowned draft, presenting a valid id
- **THEN** the system responds `200 OK` with the agreement's rental terms, its creation
  timestamp, and every signer's id, name, email, and role

#### Scenario: Owner reads their claimed agreement

- **WHEN** the owning caller GETs `/api/agreements/{id}` for one of their claimed
  agreements
- **THEN** the system responds `200 OK` with the agreement and its signers

#### Scenario: Non-owner cannot read a claimed agreement

- **WHEN** a caller (unauthenticated, or a different identity) GETs `/api/agreements/{id}`
  for an agreement claimed by someone else
- **THEN** the system responds `404 Not Found` (identical to an unknown id)

## ADDED Requirements

### Requirement: Claim an anonymous draft (Save)

The system SHALL provide `POST /api/agreements/{id}/claim` that binds an **unowned**
agreement to the **authenticated** caller's `MobileIdentity` — the "Save" action. It SHALL
require an authenticated caller; an unauthenticated request SHALL receive `401
Unauthorized`. The owner SHALL be sourced from the caller's session, never from the request
body.

On success it SHALL set the agreement's owner to the caller and respond `200 OK`. The
operation SHALL be **idempotent for the same owner** (re-claiming one's own agreement
returns `200`). For an id that does not exist, or that is already owned by a **different**
identity, it SHALL respond `404 Not Found` — the same response in both cases, so it is not
an ownership oracle.

#### Scenario: Authenticated caller claims an unowned draft

- **WHEN** an authenticated caller POSTs `/api/agreements/{id}/claim` for an unowned draft
- **THEN** the system sets the agreement's owner to the caller's identity and responds
  `200 OK`
- **AND** the draft thereafter appears in that caller's `GET /api/agreements` and is
  owner-scoped on read

#### Scenario: Re-claiming one's own agreement is idempotent

- **WHEN** the owning caller POSTs `/api/agreements/{id}/claim` again
- **THEN** the system responds `200 OK` and the owner is unchanged

#### Scenario: Claiming someone else's agreement is indistinguishable from not-found

- **WHEN** a caller POSTs `/api/agreements/{id}/claim` for an agreement already owned by a
  different identity, or for an unknown id
- **THEN** the system responds `404 Not Found` in both cases and changes no ownership

#### Scenario: Unauthenticated claim is rejected

- **WHEN** a client POSTs `/api/agreements/{id}/claim` with no valid session
- **THEN** the system responds `401 Unauthorized`

### Requirement: List the caller's own agreements (Resume)

The system SHALL provide `GET /api/agreements` that returns a summary list of the
agreements **claimed by the authenticated caller**, most-recent first. It SHALL require an
authenticated caller and SHALL return only that caller's claimed agreements — never a
global list, never unowned drafts, and never a list selected by a client-supplied mobile
number or identity id. Each summary SHALL carry enough to review and resume (at least the
agreement id, property address, creation timestamp, and — where a signing request exists —
its current signing status). A caller with no claimed agreements SHALL receive `200 OK`
with an empty list.

#### Scenario: Caller sees only their own claimed agreements

- **WHEN** an authenticated caller GETs `/api/agreements`, having claimed two agreements
  while other agreements are unowned or claimed by a different identity
- **THEN** the system responds `200 OK` with exactly the caller's two claimed agreements
  (most-recent first) and nothing else

#### Scenario: New caller has an empty list

- **WHEN** an authenticated caller who has claimed nothing GETs `/api/agreements`
- **THEN** the system responds `200 OK` with an empty list

#### Scenario: Unauthenticated list is rejected

- **WHEN** a client GETs `/api/agreements` with no valid session
- **THEN** the system responds `401 Unauthorized`

### Requirement: Unclaimed anonymous drafts are retained then purged

Because an anonymous draft persists server-side before any login, the system SHALL retain
an **unclaimed** (owner-null) agreement only for a configured retention window and SHALL
purge it (and its signers and any uploaded draft blob) after that window elapses. A
**claimed** agreement SHALL never be purged by this mechanism.

#### Scenario: An abandoned unclaimed draft is purged after its window

- **WHEN** an anonymous draft has remained unowned past the configured retention window
- **THEN** the system deletes the agreement, its signers, and any associated draft blob

#### Scenario: A claimed agreement is never purged

- **WHEN** the retention purge runs and an agreement has an owner
- **THEN** the system leaves it untouched regardless of age