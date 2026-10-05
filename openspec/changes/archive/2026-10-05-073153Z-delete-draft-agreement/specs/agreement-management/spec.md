## ADDED Requirements

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
tracking reference, the owner identity id and the time of deletion, and no party name, contact, address or
money value. A `stamp_intake_audit` row that referenced the deleted agreement SHALL be kept, with its
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
- **THEN** exactly one deletion record exists holding the agreement id, R, the owner identity id and the
  deletion time, and it holds no party name, contact, address or money value
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

### Requirement: The My agreements screen lets the owner delete a draft

The My agreements screen SHALL show a Delete action on every row whose summary is `deletable`, and on no other
row. The Delete action SHALL NOT toggle the row. Activating it SHALL open a confirmation dialog that names the
agreement (first owner, first tenant and the tracking number, each shown as "—" when absent, all rendered as
text, never as markup), states that the draft is removed from the service and cannot be restored, and states that copies of the draft already
emailed to the parties cannot be recalled. The dialog SHALL be modal and keyboard-operable: focus SHALL move
into it when it opens and stay inside it, and Escape or a click outside it SHALL cancel. Only an explicit
confirm SHALL send the delete; cancelling SHALL change nothing and return focus to the Delete action that
opened the dialog. While the delete is in flight the confirm control SHALL be disabled. On success the row
SHALL be removed from the list without a reload, and when that was the last agreement the existing empty
state SHALL show. On a `409` with the `draft-not-deletable` problem type the screen SHALL say the agreement
is no longer a draft and can no longer be deleted, and SHALL reload the list; on a `404` it SHALL say the
agreement no longer exists and remove the row; on any other failure it SHALL say the delete failed and keep
the row.

#### Scenario: Only deletable rows offer Delete

- **GIVEN** a list with a deletable `DRAFT` agreement, a `DRAFT` agreement that is not deletable, an
  `IN_PROGRESS` and a `SIGNED` agreement
- **WHEN** the My agreements screen renders
- **THEN** only the deletable row shows a Delete action

#### Scenario: Confirming deletes and removes the row

- **GIVEN** a rendered deletable row
- **WHEN** the user presses Delete and confirms in the dialog
- **THEN** the screen calls `DELETE /api/agreements/{id}` once, the confirm control is disabled until it
  answers, and the row disappears without the row expanding

#### Scenario: Deleting the last agreement shows the empty state

- **GIVEN** a list with exactly one agreement, which is deletable
- **WHEN** the user deletes it
- **THEN** the existing empty state is shown

#### Scenario: The dialog warns that emailed copies cannot be recalled

- **WHEN** the user presses Delete on a deletable row
- **THEN** the dialog names the agreement and says the draft is removed and cannot be restored and that copies already emailed
  to the parties cannot be recalled
- **AND** a party name containing markup is shown as literal text

#### Scenario: Cancelling changes nothing

- **WHEN** the user presses Delete and then cancels the dialog with the Cancel button, Escape, or a click
  outside it
- **THEN** no request is sent, the row remains, and focus is back on that row's Delete action

#### Scenario: A draft finalised elsewhere is explained, not silently dropped

- **GIVEN** a deletable row whose agreement was finalised in another tab
- **WHEN** the user confirms its delete and the server returns `409` `draft-not-deletable`
- **THEN** the screen says the agreement is no longer a draft and can no longer be deleted, and reloads the
  list

#### Scenario: A draft already gone is removed with an explanation

- **WHEN** the user confirms a delete and the server returns `404`
- **THEN** the screen says the agreement no longer exists and removes the row

#### Scenario: An unexpected failure keeps the row

- **WHEN** the user confirms a delete and the server returns `500`
- **THEN** the screen says the delete failed and the row remains

## MODIFIED Requirements

### Requirement: Resume lists the caller's agreements with a derived status

The system SHALL provide `GET /api/agreements` (authenticated) that returns a summary of the agreements
owned by the caller, most recently edited first (by the last-edit timestamp, ties broken by creation
timestamp newest first, then by id). Each summary SHALL include the property address, monthly rent, start and
end dates, the server-derived duration, the creation timestamp, the last-edit timestamp (`lastEditedAt`), the
names of the agreement's owners (`ownerNames`) and of its tenants (`tenantNames`), a **derived status**, an
`editable` flag, and a `deletable` flag. `ownerNames` and `tenantNames` SHALL each be a list holding every party of that role in
entry order. A summary SHALL carry party **names only** and never a party's Aadhaar number, virtual ID,
father's name, mobile, email, or address. The status SHALL be a read-only projection of the signing state (the
agreement itself stays status-less): an agreement with no signing request SHALL report `DRAFT`
(`editable = true`); one whose signing request is in a non-terminal state (`PDF_GENERATED`, `STAMPED`, or
`SIGN_REQUESTED`) SHALL report `IN_PROGRESS` (`editable = false`); `SIGNED` SHALL report `SIGNED`; `EXPIRED`
SHALL report `EXPIRED`; `FAILED` or `STAMP_FAILED` SHALL report `ACTION_NEEDED`. `deletable` SHALL be true
exactly when `DELETE /api/agreements/{id}` would accept the agreement's deletion by its owner (the unpaid-draft
rule), so a `DRAFT` agreement with a payment order, or that is `PAID` or `WAIVED`, SHALL report
`deletable = false`. The list SHALL include both
in-progress and signed agreements. It SHALL return only the caller's agreements and never another identity's.
The response SHALL NOT be cacheable (`Cache-Control: no-store`), and reading the list SHALL change nothing.

#### Scenario: The list shows in-progress and signed agreements, scoped to the caller

- **GIVEN** an authenticated caller who owns a draft, an in-progress agreement, and a signed one, and another
  identity who owns further agreements
- **WHEN** the caller calls `GET /api/agreements`
- **THEN** the system returns exactly the caller's three agreements, most recently edited first, each with
  the correct derived status and `editable` flag, and none belonging to the other identity

#### Scenario: Editing an older agreement moves it to the top

- **GIVEN** an authenticated caller who created agreement A and then agreement B
- **WHEN** the caller edits A's terms and then calls `GET /api/agreements`
- **THEN** A is listed before B, and A's `lastEditedAt` is later than its creation timestamp

#### Scenario: Each summary carries every party's name by role, in entry order

- **GIVEN** an authenticated caller who owns an agreement created with owners "Ramesh Kumar Reddy" then
  "Lakshmi Devi Reddy" and tenant "Priya Sharma"
- **WHEN** the caller calls `GET /api/agreements`
- **THEN** that summary's `ownerNames` is `["Ramesh Kumar Reddy", "Lakshmi Devi Reddy"]` and its
  `tenantNames` is `["Priya Sharma"]`
- **AND** the summary has exactly the documented fields, so no party mobile, email, address, father's name,
  Aadhaar number, or virtual ID
- **AND** the response carries `Cache-Control: no-store`

#### Scenario: Only an unpaid draft is reported deletable

- **GIVEN** an authenticated caller who owns an unpaid draft, a draft with a payment order, a draft whose
  payment was waived, and an in-progress agreement
- **WHEN** the caller calls `GET /api/agreements`
- **THEN** only the unpaid draft has `deletable = true`; the other two drafts report status `DRAFT` with
  `deletable = false`, and the in-progress agreement reports `deletable = false`
