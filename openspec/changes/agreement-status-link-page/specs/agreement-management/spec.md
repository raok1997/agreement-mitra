## MODIFIED Requirements

### Requirement: An owner may fully edit an in-progress agreement

The system SHALL provide `PUT /api/agreements/{id}` that fully replaces the mutable terms of an
agreement -- the property address, monthly rent, security deposit, tenancy start and end dates,
and the party collection -- using the same request shape and the same create-time validation as
`POST /api/agreements`.

**Who may edit.** An authenticated caller SHALL be able to edit an agreement they own. A caller
presenting only the agreement id SHALL be able to edit an agreement that is **unowned**: the
unguessable id is the bearer capability, the same capability that already permits reading it.
An agreement owned by a *different* identity SHALL respond `404 Not Found` to any other caller,
authenticated or not (never `403`; no oracle), and an unknown id SHALL respond `404 Not Found`
likewise.

**When editing is permitted.** Editing SHALL be permitted **only while no stamp is attached** to
the agreement's signing request. Once the request has reached `STAMPED` -- or any state beyond it
-- the agreement is frozen and the edit SHALL respond `409 Conflict`. The existence of a signing
request in `PDF_GENERATED` SHALL NOT by itself freeze the agreement.

The agreement id, owner, creation timestamp, payment state, stamp information, tracking
reference, and derived duration SHALL remain server-managed and SHALL NOT be accepted from the
body. A successful edit SHALL clear the pinned draft reference so a subsequent generate produces
a fresh document from the edited terms.

#### Scenario: Owner edits terms and parties before a stamp is attached

- **GIVEN** an authenticated caller who owns an agreement with no stamp attached
- **WHEN** the caller PUTs a body changing the monthly rent, the dates, and the party list
- **THEN** the system revalidates and persists the new terms and parties, recomputes the
  duration, clears the pinned draft, and returns `200 OK` with the updated agreement

#### Scenario: A link holder edits an unowned agreement awaiting its stamp

- **GIVEN** an unowned agreement whose signing request rests in `PDF_GENERATED`
- **WHEN** a caller presenting only the agreement id PUTs an edit
- **THEN** the system revalidates and persists the edit, clears the pinned draft, and returns
  `200 OK`

#### Scenario: Editing after a stamp is attached is frozen

- **GIVEN** an agreement whose signing request has reached `STAMPED` or beyond
- **WHEN** any caller PUTs an edit
- **THEN** the system responds `409 Conflict` and changes nothing

#### Scenario: Editing a claimed agreement without owning it is indistinguishable from unknown

- **WHEN** a caller PUTs an edit to an agreement owned by another identity, or to an unknown id
- **THEN** the system responds `404 Not Found` in both cases

#### Scenario: An invalid edit is rejected by the same rules as create

- **WHEN** a caller PUTs an edit whose body violates a create-time rule (for example a blank
  party name, no `TENANT`, a non-positive rent, or an end date not after the start)
- **THEN** the system responds `400 Bad Request` as `application/problem+json` and changes
  nothing

#### Scenario: Server-managed fields in the body are ignored

- **WHEN** a caller PUTs an edit whose body attempts to set the owner, the payment state, the
  stamp information, or the tracking reference
- **THEN** those values are ignored and the server-managed values are unchanged

## ADDED Requirements

### Requirement: Every anonymous terms edit is audited

Because a terms edit may be performed by a caller presenting only the agreement id, the system
SHALL record an audit entry for each such edit, capturing the agreement, the outcome, and the
time it occurred.

The audit entry SHALL NOT store party PII, contact addresses, or the edited values themselves.
Any recipient or contact value appearing in a related log line SHALL be redacted.

#### Scenario: An anonymous edit produces an audit record

- **WHEN** a caller presenting only the agreement id successfully edits an agreement
- **THEN** an audit entry records the agreement, the outcome, and the time

#### Scenario: A refused anonymous edit is audited too

- **WHEN** an anonymous edit is refused because the agreement is stamped or owned by somebody
  else
- **THEN** an audit entry records the refusal

#### Scenario: The audit record carries no party PII

- **WHEN** any anonymous edit is audited
- **THEN** the entry contains no party name, contact address, or edited term value
