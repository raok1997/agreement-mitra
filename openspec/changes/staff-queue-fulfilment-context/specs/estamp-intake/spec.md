## MODIFIED Requirements

### Requirement: Staff have a console listing orders awaiting a stamp

The system SHALL provide a STAFF-only screen listing every agreement **awaiting stamp intake** -
the fulfilment work queue - so staff can see outstanding orders without querying the database.

Each entry SHALL carry the context an operator needs to **purchase the certificate**, because
naming the parties and the state on the stamp certificate is the work the queue exists to
support. An entry SHALL carry:

- the agreement's **tracking reference**;
- the **template name** and the **template state** the agreement was drafted against;
- **every party**, grouped by role - first party (`OWNER`) and second party (`TENANT`) - each
  with their **full name** and their **father's name**;
- the property **city**, the agreement date, how long it has been waiting, and its payment
  state where the payment gate is in use.

The **state** SHALL be the state dimension of the template the agreement is pinned to, NOT a
value derived from the property address: stamp duty follows the state whose law the instrument
was drafted under, and the stored address is free text.

All parties of a role SHALL be listed, not only the first, so that an agreement with several
owners or several tenants does not present an operator with an incomplete set of names.

The entry SHALL NOT carry any party's **contact details**, the **monthly rent**, the **security
deposit**, or the **full street address**. The projection is scoped to what purchasing a
certificate requires; it is not a window onto the customer's agreement.

Entries SHALL be ordered so the longest-waiting work is visible first.

The list SHALL exclude agreements that already have a stamp attached and those that are closed or
terminally failed, so completed and dead work does not accumulate in the queue.

Where the pinned template is no longer available for lookup - superseded, archived, or otherwise
unpublished - the entry SHALL still appear, with the template name and state reported as absent.
An unavailable template SHALL NOT remove outstanding work from the queue.

From an entry, staff SHALL be able to **upload the scanned certificate** for that agreement
directly, without re-typing the tracking reference - eliminating the transcription step that the
check character otherwise has to catch.

The console SHALL require the STAFF role. A non-staff caller SHALL be refused, and the refusal
SHALL NOT disclose how many orders exist or reveal any agreement's details. Because entries now
carry party names, this authorization SHALL be enforced server-side before the projection is
assembled, and party names SHALL NOT appear in any log line.

#### Scenario: Outstanding orders are listed with purchasing context

- **WHEN** a STAFF user opens the console
- **THEN** they see every agreement awaiting stamp intake, longest-waiting first
- **AND** each entry shows its tracking reference, template name, template state, property city,
  agreement date, and waiting time
- **AND** each entry shows every party with their full name and father's name, identified as
  first party or second party

#### Scenario: The state comes from the pinned template, not the address

- **GIVEN** an agreement pinned to a template whose state dimension is Telangana
- **AND** whose property address ends in a different place name
- **WHEN** the entry is listed
- **THEN** the entry's state is Telangana

#### Scenario: Every party of a role is listed

- **GIVEN** an agreement with two owners and one tenant
- **WHEN** the entry is listed
- **THEN** all three parties appear, the two owners as first party and the tenant as second party
- **AND** each carries its own father's name

#### Scenario: An unavailable pinned template does not hide the work

- **GIVEN** an agreement awaiting a stamp whose pinned template is no longer published
- **WHEN** the queue is listed
- **THEN** the entry still appears
- **AND** its template name and state are reported as absent rather than the entry being omitted

#### Scenario: Excluded fields stay excluded

- **WHEN** a STAFF user lists the queue
- **THEN** no entry carries a party's email or mobile, the monthly rent, the security deposit, or
  the full street address

#### Scenario: Stamped and closed work is excluded

- **WHEN** an agreement has a stamp attached, or is closed or terminally failed
- **THEN** it does not appear in the outstanding queue

#### Scenario: Upload from the queue needs no re-typing

- **WHEN** staff upload a scan from a queue entry
- **THEN** the agreement is resolved from that entry and staff do not re-enter the tracking
  reference

#### Scenario: The console is STAFF-only

- **WHEN** a caller without the STAFF role requests the console
- **THEN** the request is refused and no agreement detail or queue size is disclosed
- **AND** no party name is disclosed in the refusal or written to a log

#### Scenario: Party names never reach the logs

- **WHEN** the queue is assembled and returned to a STAFF caller
- **THEN** no log line emitted on that path contains a party's name or father's name
