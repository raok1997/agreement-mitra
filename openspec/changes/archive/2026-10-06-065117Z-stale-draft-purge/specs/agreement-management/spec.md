## MODIFIED Requirements

### Requirement: An owner may delete an unpaid draft

The system SHALL provide `DELETE /api/agreements/{id}` (authenticated) that deletes an agreement
the caller owns, **only while it is an unpaid draft**: no signing request exists for it, no payment order
exists for it, its payment state is `UNPAID`, and its closure state is `OPEN`. This rule SHALL be defined once
and SHALL be the same rule that sets the list summary's `deletable` flag. Ownership SHALL use the same strict
rule as `PUT /api/agreements/{id}`: the agreement's owner MUST equal the caller, so an unowned (anonymous)
agreement cannot be deleted through this endpoint. An unknown id, an unowned agreement, and an agreement owned
by another identity SHALL all return the same `404`, decided before any other refusal, so the endpoint reveals
neither existence nor ownership. An owned agreement that is not an unpaid draft SHALL be refused with `409`
and the problem type `urn:agreementmitra:problem:draft-not-deletable`, and nothing SHALL change. The request
body, if any, SHALL be ignored. A successful delete SHALL return `204` with no body and SHALL remove the
agreement and its parties in one transaction, so that afterwards the agreement is absent from
`GET /api/agreements` and `GET /api/agreements/{id}` returns `404`. The agreement's draft PDF object
(`drafts/{id}.pdf`) SHALL be removed from object storage after that transaction commits, whether or not the
agreement currently records a draft reference; a failure to remove it SHALL NOT fail the request or restore
the agreement. In the same transaction the system SHALL record the deletion as a row holding only the agreement id, its
tracking reference, the owner identity id, the time of deletion and the reason `OWNER_DELETE`, and no party
name, contact, address or money value. The deletion rule, the removal of the agreement, its parties and its
draft-stage objects, and the shape of this record SHALL be shared with the retention purge (`draft-retention`),
so the two removals cannot diverge. A `stamp_intake_audit` row that referenced the deleted agreement SHALL be kept, with its
submitted reference and outcome, and its reference to the agreement cleared. The decision and the delete
SHALL run under the agreement row's write lock, so that a signing request or payment order committed for the
agreement before the delete takes the lock causes the delete to be refused.

#### Scenario: The owner deletes an unpaid draft

- **GIVEN** an authenticated caller who owns an agreement with two parties, a stored draft PDF, no signing
  request, no payment order, and payment state `UNPAID`
- **WHEN** the caller calls `DELETE /api/agreements/{id}`
- **THEN** the system returns `204`
- **AND** the agreement and both party rows no longer exist, `GET /api/agreements/{id}` returns `404`, and
  the agreement is not in the caller's `GET /api/agreements`
- **AND** the draft PDF is no longer in object storage

#### Scenario: A draft PDF left behind by an edit is still removed

- **GIVEN** an owned unpaid draft whose PDF was generated and whose terms were then edited, so the agreement
  no longer records a draft reference but `drafts/{id}.pdf` still exists in object storage
- **WHEN** the owner deletes the draft
- **THEN** the system returns `204` and `drafts/{id}.pdf` is no longer in object storage

#### Scenario: A draft that never had a PDF is deleted

- **GIVEN** an owned unpaid draft for which no PDF was ever generated or uploaded
- **WHEN** the owner deletes the draft
- **THEN** the system returns `204` and the agreement no longer exists

#### Scenario: A delete leaves a record without personal data

- **GIVEN** an owned unpaid draft with tracking reference R
- **WHEN** the owner deletes it
- **THEN** exactly one deletion record exists holding the agreement id, R, the owner identity id, the
  deletion time and the reason `OWNER_DELETE`, and it holds no party name, contact, address or money value
- **AND** a refused delete (`404` or `409`) leaves no deletion record

#### Scenario: Another identity's agreement is indistinguishable from an unknown one

- **GIVEN** an agreement owned by identity A
- **WHEN** identity B calls `DELETE /api/agreements/{id}` for it, and separately for an id that does not exist
- **THEN** both calls return the same `404` problem, and A's agreement is unchanged

#### Scenario: An unowned draft cannot be deleted through the endpoint

- **GIVEN** an anonymous draft that no identity has claimed
- **WHEN** an authenticated caller calls `DELETE /api/agreements/{id}` for it
- **THEN** the system returns `404` and the agreement is unchanged

#### Scenario: A caller without a session is refused

- **WHEN** a caller with no session calls `DELETE /api/agreements/{id}` with a valid CSRF token
- **THEN** the system returns `403` and nothing changes

#### Scenario: A finalised agreement is not deletable

- **GIVEN** an authenticated caller who owns an agreement for which a signing request exists
- **WHEN** the caller calls `DELETE /api/agreements/{id}`
- **THEN** the system returns `409` with problem type `urn:agreementmitra:problem:draft-not-deletable`
- **AND** the agreement, its parties, its signing request and its draft PDF are unchanged

#### Scenario: A draft with a payment order is not deletable

- **GIVEN** an authenticated caller who owns an agreement with no signing request but with a payment order
- **WHEN** the caller calls `DELETE /api/agreements/{id}`
- **THEN** the system returns `409` with problem type `urn:agreementmitra:problem:draft-not-deletable` and
  nothing changes

#### Scenario: A paid or waived draft is not deletable

- **GIVEN** an authenticated caller who owns an agreement with no signing request and no payment order whose
  payment state is `PAID` or `WAIVED`
- **WHEN** the caller calls `DELETE /api/agreements/{id}`
- **THEN** the system returns `409` with problem type `urn:agreementmitra:problem:draft-not-deletable` and
  nothing changes

#### Scenario: A delete waits for a concurrent finalise and then refuses

- **GIVEN** an owned unpaid draft, and a transaction that has inserted a signing request for it but not yet
  committed
- **WHEN** the owner calls `DELETE /api/agreements/{id}` and that transaction then commits
- **THEN** the delete completes only after the commit and returns `409` with problem type
  `urn:agreementmitra:problem:draft-not-deletable`, and the agreement still exists

#### Scenario: A staff intake audit row survives the delete

- **GIVEN** an owned unpaid draft against which staff submitted a stamp intake that was refused, leaving a
  `stamp_intake_audit` row that references the agreement
- **WHEN** the owner deletes the draft
- **THEN** the delete succeeds and the audit row still exists with its submitted reference and outcome, and
  no longer references the agreement

#### Scenario: A failed blob removal does not fail the delete

- **GIVEN** an owned unpaid draft with a stored draft PDF, and object storage that fails to remove it
- **WHEN** the owner deletes the draft
- **THEN** the system returns `204` and the agreement no longer exists

#### Scenario: A delete without the CSRF token is refused

- **GIVEN** an authenticated caller who owns an unpaid draft
- **WHEN** the caller sends `DELETE /api/agreements/{id}` without a valid CSRF token
- **THEN** the system returns `403` with the CSRF problem type and the agreement is unchanged

#### Scenario: A body naming another agreement or owner is ignored

- **GIVEN** an authenticated caller who owns unpaid draft X
- **WHEN** the caller sends `DELETE /api/agreements/{X}` with a JSON body naming a different agreement id and
  owner identity
- **THEN** only X is deleted

