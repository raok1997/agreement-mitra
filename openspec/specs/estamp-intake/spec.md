# estamp-intake

## Purpose

Staff-authenticated intake of an e-stamp certificate that AgreementMitra staff purchased
manually from the SHCIL portal, printed, and scanned. Covers the upload contract, the
certificate metadata recorded against the agreement, the single-use certificate ledger that
prevents one stamp being spent twice, and the deterministic reference staff use to find the
right agreement.

## Requirements

### Requirement: Staff upload a purchased e-stamp certificate against an agreement

The system SHALL expose an endpoint that accepts a **scanned e-stamp certificate image**
together with its certificate metadata, and attaches it to a named agreement. The upload
SHALL be the only way a stamp becomes attached; the system SHALL NOT generate, procure, or
synthesise a stamp itself.

The request SHALL carry the scan and these SHCIL certificate fields: **certificate number**,
**certificate issue date**, **stamp duty amount**, **state/jurisdiction**, **description of
document**, and **purchased by**. Certificate number, issue date, duty amount, and
jurisdiction SHALL be mandatory; description and purchased-by MAY be optional. A request
missing a mandatory field SHALL be rejected with `400` and a field-level error list (per
`api-error-handling`), before any blob is written or state changed.

On success the system SHALL composite the scan onto the agreement's instrument, persist the
certificate metadata as the agreement's stamp info with `dutyPaid = true`, and transition the
signing request to `STAMPED`.

#### Scenario: Successful stamp intake attaches the stamp

- **WHEN** a staff user uploads a valid scan plus complete certificate metadata for an
  agreement awaiting a stamp
- **THEN** the scan is stored, the stamped instrument is composited and stored, the
  agreement's stamp info records the certificate number, issue date, duty amount and
  jurisdiction with `dutyPaid = true`, and the signing request transitions to `STAMPED`

#### Scenario: Missing mandatory certificate metadata is rejected

- **WHEN** an upload omits the certificate number, issue date, duty amount, or jurisdiction
- **THEN** the request is rejected with `400` and a field-level error list
- **AND** no blob is written, no stamp info is persisted, and the request state is unchanged

#### Scenario: Upload against an unknown agreement

- **WHEN** a staff user uploads a stamp for an agreement reference that does not exist
- **THEN** the request is rejected with `404` and nothing is persisted

### Requirement: Only staff may upload a stamp

Stamp intake SHALL be restricted to authenticated users holding the **STAFF** role. A request
from an unauthenticated caller SHALL be rejected with `401`; a request from an authenticated
non-staff caller SHALL be rejected with `403`.

The endpoint SHALL NOT behave as an existence oracle: for a non-staff caller the response
SHALL be identical whether or not the referenced agreement exists, so a customer cannot probe
for other customers' agreements. Authorization SHALL be decided **before** the agreement is
looked up.

#### Scenario: Unauthenticated upload is rejected

- **WHEN** an unauthenticated caller posts to the stamp-intake endpoint
- **THEN** the response is `401` and nothing is persisted

#### Scenario: Authenticated non-staff caller is refused

- **WHEN** an authenticated user without the STAFF role posts to the stamp-intake endpoint
- **THEN** the response is `403` and nothing is persisted

#### Scenario: Refusal does not reveal whether the agreement exists

- **WHEN** a non-staff caller posts a stamp for an existing agreement, and again for an
  agreement reference that does not exist
- **THEN** both responses are `403` and indistinguishable in status, body, and timing class

### Requirement: An e-stamp certificate is single-use

A certificate number SHALL be usable for **at most one** agreement. The system SHALL enforce
uniqueness at the **database level**, not only in application code, so two concurrent uploads
of the same certificate cannot both succeed.

An upload whose certificate number is already recorded against any agreement SHALL be rejected
with `409` and SHALL leave the existing attachment untouched. The rejection message SHALL NOT
disclose which agreement already consumed the certificate.

#### Scenario: Reusing a certificate number is refused

- **WHEN** a staff user uploads a certificate number already attached to another agreement
- **THEN** the request is rejected with `409`, the first agreement's stamp is unchanged, and
  the response does not identify the other agreement

#### Scenario: Concurrent uploads of the same certificate

- **WHEN** two uploads carrying the same certificate number are processed concurrently
- **THEN** exactly one succeeds and the other is rejected with `409`

#### Scenario: Re-uploading to the same agreement is refused

- **WHEN** a staff user uploads a stamp for an agreement that already has one attached
- **THEN** the request is rejected with `409` and the existing stamp is unchanged

### Requirement: Only validated raster scans are accepted

The uploaded scan SHALL be treated as **untrusted input**. The system SHALL accept only
**JPEG and PNG** images and SHALL verify the declared type against the file's **magic bytes**,
rejecting any mismatch. It SHALL bound both the **byte size** and the **decoded pixel
dimensions**, so a small compressed file that decodes to an enormous raster (a decompression
bomb) is refused rather than exhausting heap.

Every validation or decode failure SHALL fail **closed**: the upload is rejected, no partial
state is written, and the failure SHALL NOT surface as an unmapped server error, a hung
request thread, or an out-of-memory condition.

#### Scenario: Non-image upload is rejected

- **WHEN** a file that is not a JPEG or PNG is uploaded (including a PDF, or a file whose
  extension or declared content type disagrees with its magic bytes)
- **THEN** the upload is rejected with `400` and nothing is persisted

#### Scenario: Oversized or bomb image is rejected

- **WHEN** an image exceeding the byte-size ceiling, or one whose decoded dimensions exceed
  the pixel ceiling, is uploaded
- **THEN** the upload is rejected cleanly with `400`, without exhausting memory

#### Scenario: Corrupt image fails closed

- **WHEN** a truncated or corrupt JPEG/PNG is uploaded
- **THEN** the upload is rejected with a mapped client error, not an unmapped `500`

### Requirement: Staff locate an agreement by a unique, persisted reference

Staff SHALL locate the agreement for a stamp upload by a **persisted, unique** reference
carried by the agreement. The system SHALL NOT accept the display-only tracking number
(`AM-<LAST6>-<DDMMYY>` from `document-rendering`) as a lookup key, because it is derived at
render time and is not collision-free.

A lookup that matches no agreement SHALL yield `404`. The reference SHALL be stable for the
life of the agreement and SHALL be surfaced to staff alongside the order.

#### Scenario: Lookup by the persisted reference resolves exactly one agreement

- **WHEN** staff submit a stamp using an agreement's persisted reference
- **THEN** exactly one agreement is resolved and the stamp is attached to it

#### Scenario: Display-only tracking number is not a lookup key

- **WHEN** a value in the display-only `AM-<LAST6>-<DDMMYY>` tracking format is submitted as
  the agreement reference
- **THEN** it is not resolved as a lookup key and the request is rejected

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

### Requirement: A stamp upload may also start signing, at the operator's choice

The upload SHALL accept an optional instruction to start the signing workflow once the stamp is
attached. Absent that instruction the upload SHALL attach the stamp and nothing else, exactly as
before: starting an eSign spends a billable transaction and puts a signing invitation in front of
both parties, so it SHALL NOT happen implicitly.

When the instruction is given, signing SHALL be started only after the stamp is durably attached,
and SHALL NOT be able to undo it. A purchased certificate cannot be re-acquired, so a failure to
start signing SHALL leave the stamp in place and the agreement in its stamped state, ready for
signing to be started again.

The response SHALL tell the operator, separately, whether the stamp was attached and whether
signing started, so that a partial outcome is legible rather than being reported as a single
success or a single failure. A reason for a signing failure MAY be reported, and SHALL carry no
party detail and no vendor payload.

#### Scenario: Upload without the instruction attaches the stamp only

- **WHEN** a staff member uploads a certificate without asking for signing to start
- **THEN** the stamp is attached, no eSign transaction is created, and no party is invited

#### Scenario: Upload with the instruction attaches and then starts signing

- **WHEN** a staff member uploads a certificate for a paid, reachable agreement and asks for
  signing to start
- **THEN** the stamp is attached and the signing workflow is started for that agreement

#### Scenario: A signing failure does not cost the stamp

- **WHEN** the stamp is attached but starting the signing workflow fails
- **THEN** the stamp remains attached, the agreement remains in its stamped state, and the
  response reports the stamp as attached and signing as not started

#### Scenario: The operator can tell the two outcomes apart

- **WHEN** an upload asked for signing to start
- **THEN** the response distinguishes the stamp outcome from the signing outcome rather than
  collapsing them into one result

### Requirement: Stamp intake is audited without leaking certificate contents

Every intake attempt - accepted or rejected - SHALL be recorded with the acting staff
identity, the target agreement, the outcome, and the time.

The scan bytes SHALL be written to object storage only; they SHALL NOT be stored in
PostgreSQL and SHALL NOT be written to logs. The certificate number SHALL be **redacted** in
logs (last 4 characters only), because it is the single-use token evidencing duty payment.
The scanned certificate carries party names and a property description, so logs SHALL NOT
echo any submitted metadata value verbatim, and error bodies SHALL NOT echo submitted values.

#### Scenario: Intake attempts are audited

- **WHEN** a stamp upload succeeds or is rejected
- **THEN** an audit record captures the acting staff identity, target agreement, outcome, and
  timestamp

#### Scenario: No certificate contents or PII in logs

- **WHEN** the system logs around stamp intake
- **THEN** no scan bytes, no full certificate number, and no party names or property
  description appear; the certificate number appears only in redacted form

### Requirement: A stamp certificate is accepted only for an eligible duty jurisdiction

Stamp intake SHALL refuse an agreement whose duty jurisdiction is not eligible for paid
fulfilment, **before the uploaded certificate is stored or attached**. Stamp duty is state
law, so an agreement without an eligible duty jurisdiction has no state whose duty could
have been paid and no defined place the certificate could have been bought.

This gate SHALL be independent of the payment gate rather than implied by it. The payment
state can be satisfied by a staff waiver, so a paid-or-waived agreement is not thereby a
fulfillable one. Staff acting deliberately is a different threat model from a customer
driving the public flow, but it warrants a different response rather than none: the outcome
being prevented is an undefined real-world purchase, not an unpaid one.

The refusal SHALL use the distinct unsupported-jurisdiction error kind, so staff can tell it
apart from a payment-required, already-attached or certificate-reused refusal at the same
step.

#### Scenario: Stamp intake is refused for an ineligible jurisdiction

- **WHEN** staff submit an e-stamp certificate for an agreement whose duty jurisdiction is
  not eligible
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** no certificate blob is stored
- **AND** no stamp is attached to the agreement

#### Scenario: A waived payment does not satisfy the jurisdiction requirement

- **WHEN** staff submit an e-stamp certificate for an agreement whose payment has been
  waived and whose duty jurisdiction is not eligible
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** no stamp is attached to the agreement

#### Scenario: Stamp intake proceeds for an eligible jurisdiction

- **WHEN** staff submit an e-stamp certificate for an agreement whose duty jurisdiction is
  eligible and which satisfies every existing precondition
- **THEN** intake proceeds exactly as before this change
