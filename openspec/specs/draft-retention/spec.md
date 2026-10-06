# draft-retention Specification

## Purpose
TBD - created by archiving change stale-draft-purge. Update Purpose after archive.
## Requirements
### Requirement: Stale unpaid drafts are purged after 90 days without an edit

The system SHALL, once a day, delete every agreement that the single deletability rule admits (no signing
request, no payment order, payment state `UNPAID`, closure state `OPEN`) and whose `lastEditedAt` is more than
90 days before the time of the run, whether or not any identity has claimed it. The 90-day period SHALL be
defined once in the backend and SHALL NOT be settable per environment. Each purged agreement SHALL be removed
exactly as an owner's delete removes it: the agreement and its parties in one transaction, a
`stamp_intake_audit` row that referenced it kept with its reference cleared, and its draft-stage objects
(`drafts/{id}.pdf`) removed from object storage after that transaction commits, best effort. In the same
transaction the system SHALL write a deletion record holding the agreement id, its tracking reference, its
owner identity id (empty for an unclaimed draft), the time of deletion and the reason `RETENTION_PURGE`, and no
party name, contact, address or money value. Each agreement SHALL be purged in its own transaction, so a
failure on one leaves the others to proceed. An agreement that does not qualify SHALL be left unchanged.

#### Scenario: An unclaimed draft untouched for 90 days is purged

- **GIVEN** an unclaimed unpaid draft with two parties and a stored draft PDF, whose `lastEditedAt` is 91 days
  before the run
- **WHEN** the purge runs
- **THEN** the agreement and both party rows no longer exist and `drafts/{id}.pdf` is no longer in object
  storage
- **AND** exactly one deletion record exists for it, with its tracking reference, no owner identity, the
  reason `RETENTION_PURGE`, and no party name, contact, address or money value

#### Scenario: A claimed draft untouched for 90 days is purged

- **GIVEN** an unpaid draft claimed by identity A, whose `lastEditedAt` is 91 days before the run
- **WHEN** the purge runs
- **THEN** the agreement no longer exists and is not in A's `GET /api/agreements`
- **AND** its deletion record names owner identity A and the reason `RETENTION_PURGE`

#### Scenario: A draft edited within the period is kept

- **GIVEN** an unpaid draft created 200 days before the run whose `lastEditedAt` is 89 days before the run
- **WHEN** the purge runs
- **THEN** the agreement, its parties and its draft PDF are unchanged and no deletion record exists for it

#### Scenario: A stale agreement that has left the draft stage is kept

- **GIVEN** agreements whose `lastEditedAt` is 91 days before the run, one with a signing request, one with a
  payment order and no signing request, and one with payment state `PAID` or `WAIVED`
- **WHEN** the purge runs
- **THEN** each agreement and everything attached to it is unchanged and no deletion record exists for any of
  them

#### Scenario: A staff intake audit row survives the purge

- **GIVEN** a stale unpaid draft referenced by a refused `stamp_intake_audit` row
- **WHEN** the purge runs
- **THEN** the agreement no longer exists and the audit row still exists with its submitted reference and
  outcome, and no longer references the agreement

#### Scenario: A failed blob removal does not stop the purge

- **GIVEN** two stale unpaid drafts with stored draft PDFs, and object storage that fails to remove the first
  one's PDF
- **WHEN** the purge runs
- **THEN** both agreements no longer exist and the second one's PDF is no longer in object storage

### Requirement: An edit racing the purge keeps the draft

The purge SHALL decide and delete each agreement only while holding the agreement row's write lock, re-checking
the deletability rule and the 90-day condition against the row as read under that lock. It SHALL NOT wait for a
lock another transaction holds: a row locked by another transaction SHALL be skipped for that run and left
unchanged. An agreement edited, finalised or ordered by a transaction that committed before the purge took the
lock SHALL be kept.

#### Scenario: A draft edited after it was selected is kept

- **GIVEN** a stale unpaid draft selected as a purge candidate, and an edit to its terms that commits before
  the purge locks the row, moving `lastEditedAt` to the current time
- **WHEN** the purge processes that candidate
- **THEN** the agreement is kept and no deletion record exists for it

#### Scenario: A draft finalised after it was selected is kept

- **GIVEN** a stale unpaid draft selected as a purge candidate, and a signing request for it that commits
  before the purge locks the row
- **WHEN** the purge processes that candidate
- **THEN** the agreement and its signing request are unchanged

#### Scenario: A draft locked by an in-flight edit is skipped

- **GIVEN** a stale unpaid draft whose row is locked by an open transaction editing it
- **WHEN** the purge processes that candidate before that transaction ends
- **THEN** the purge does not wait, the candidate is counted as skipped, and after the edit commits the
  agreement still exists with its edit

### Requirement: One failing candidate does not stall the purge

The purge SHALL attempt each candidate at most once per run, so a candidate that fails or is skipped is not
selected again in the same run and later candidates are still reached. A run SHALL attempt at most 10 000
candidates and SHALL end when no candidate remains or that cap is reached.

#### Scenario: A persistently failing candidate does not block later ones

- **GIVEN** three stale unpaid drafts, the oldest of which fails every purge attempt
- **WHEN** the purge runs
- **THEN** the oldest is attempted once and still exists, and the other two no longer exist

### Requirement: Orphaned draft objects are swept

The same daily run SHALL list the object keys under the prefix of each draft-stage key (the keys the delete path
removes; today `drafts/`), taking the prefixes from the same definition as those keys, and remove an object only
when all of these hold: its key is exactly a draft-stage key of some agreement id (the key format the delete path uses), no
agreement with that id exists, a deletion record for that id exists, and its last-modified time is more than 24
hours before the run. The object removed SHALL be the exact listed key. Every other object SHALL be left in
place. A run SHALL remove at most 1 000 objects. A failure to list or to remove an object SHALL NOT fail the run
or the purge of agreements in the same run.

#### Scenario: A PDF left behind by a failed delete is removed

- **GIVEN** `drafts/{id}.pdf` in object storage, last modified two days before the run, no agreement with that
  id, and a deletion record for that id
- **WHEN** the sweep runs
- **THEN** that object is no longer in object storage

#### Scenario: An object with no deletion record is kept

- **GIVEN** `drafts/{id}.pdf` last modified two days before the run, no agreement with that id, and no deletion
  record for that id (as when the application points at an empty or different database)
- **WHEN** the sweep runs
- **THEN** the object is still in object storage

#### Scenario: A live agreement's PDF is kept

- **GIVEN** an unpaid draft edited yesterday, and a paid agreement awaiting stamping, each with a
  `drafts/{id}.pdf` last modified 30 days before the run
- **WHEN** the sweep runs
- **THEN** both objects are still in object storage

#### Scenario: A just-written object is kept

- **GIVEN** `drafts/{id}.pdf` last modified one hour before the run, no agreement with that id, and a deletion
  record for that id
- **WHEN** the sweep runs
- **THEN** the object is still in object storage

#### Scenario: A key of another form is kept

- **GIVEN** objects `drafts/readme.txt`, `drafts/{uuid}.png` and `drafts/{UUID in upper case}.pdf`, all last
  modified two days before the run
- **WHEN** the sweep runs
- **THEN** all three objects are still in object storage

### Requirement: The retention run is scheduled, contained and silent about party data

The purge and the sweep SHALL run from one scheduled job, once a day, only when
`signing.draft-retention.enabled` is `true`; it SHALL be off when the property is absent, so a database restored
or cloned into another environment never runs it by default (tests invoke the run directly). A
failure inside the run SHALL be caught and logged without escaping into the scheduler, so the next day's run is
unaffected. The run's own log lines SHALL carry only counts (purged, skipped, failed, objects removed, and whether
the purge stopped early or the sweep failed) and agreement ids redacted by the project's id redaction. They SHALL NOT carry a throwable or
an exception message, a party field, a tracking reference or an object key; an exception is identified by its
class name only. Key values in the database driver's own error log are kept out by the datasource's existing
`logServerErrorDetail=false` setting, which this requirement relies on and does not replace.

#### Scenario: A storage outage does not escape the scheduler

- **GIVEN** object storage that fails every list and remove call
- **WHEN** the scheduled job fires
- **THEN** stale drafts are still deleted from the database, the job returns normally, the log reports the
  sweep as failed, and no log line contains an unredacted agreement id, an object key or an exception message

#### Scenario: A database failure on one candidate is logged without its message

- **GIVEN** a stale unpaid draft whose purge fails with a database exception whose message contains the full
  agreement id
- **WHEN** the purge runs
- **THEN** the log names the exception class and the redacted id only, and the next candidate is still purged

#### Scenario: The job is off unless enabled

- **WHEN** the application starts with the test profile, or with `signing.draft-retention.enabled` unset
- **THEN** no retention job bean exists, and the retention run service does

