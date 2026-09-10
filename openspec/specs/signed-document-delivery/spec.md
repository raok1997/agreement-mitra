# signed-document-delivery Specification

## Purpose
TBD - created by archiving change signed-delivery-and-closure. Update Purpose after archive.
## Requirements
### Requirement: Each party receives the signed agreement on completion

When a signing request completes successfully, the system SHALL deliver the **signed agreement
PDF** to every party as an **email attachment**, and SHALL keep an authenticated copy
retrievable in the application.

The **audit trail SHALL NOT be delivered** by email. It SHALL be retained and produced on
request or in a dispute, because it carries eKYC-derived detail that should not be distributed
into mailboxes.

Delivery SHALL be triggered by the completion of signing, whether that completion was applied
by the webhook path or by the reconciliation job. Delivery SHALL NOT be a manual step.

#### Scenario: Both parties receive the signed agreement

- **WHEN** a signing request transitions to `SIGNED` and its artifacts are stored
- **THEN** each party is sent an email carrying the signed agreement PDF as an attachment
- **AND** the signed agreement remains retrievable in the application

#### Scenario: The audit trail is not emailed

- **WHEN** the signed agreement is delivered
- **THEN** the audit trail is not attached to or linked from the delivery, and remains
  retained for production on request

#### Scenario: Reconciliation-driven completion also delivers

- **WHEN** completion is applied by the reconciliation job rather than a webhook
- **THEN** delivery occurs exactly as it would have on the webhook path

### Requirement: Delivery goes only to addresses proven by the signing process

The system SHALL deliver only to a party's **signing-verified** address - the address to which
that party's signing invitation was issued and at which that party completed signing.

An address captured while drafting but never exercised by a completed signature SHALL NOT
receive the document. If a party has no signing-verified address, the system SHALL NOT
substitute a draft-time address; it SHALL record the delivery as unresolvable and surface it
for staff.

This constraint exists because the signed agreement is a complete identity-and-property
document: a mistyped draft address would otherwise disclose both parties' names, the property
address, and the financial terms to an uninvolved third party.

#### Scenario: Delivery uses the signing-verified address

- **WHEN** the signed agreement is delivered to a party
- **THEN** it is sent to the address at which that party completed signing

#### Scenario: An unverified draft address is never used

- **WHEN** a party's draft-time address differs from the address at which signing completed
- **THEN** the draft-time address receives nothing

#### Scenario: A party with no verified address is escalated, not guessed

- **WHEN** no signing-verified address can be resolved for a party
- **THEN** no email is sent for that party and the condition is surfaced for staff action

### Requirement: Delivery is recorded per recipient and happens exactly once

The system SHALL hold a **delivery record per recipient**, carrying at least the recipient, the
artifact delivered, a status, the attempt count, the last failure reason, and timestamps.

Delivery SHALL be **exactly once per recipient**. Because both webhook redelivery and the
reconciliation job re-enter the completion path, re-entry SHALL NOT produce a second email.
Concurrent attempts SHALL be made safe so two deliveries cannot both send.

A recipient whose delivery already succeeded SHALL NOT be re-sent to, unless a **deliberate
re-send** is requested by staff, which SHALL be recorded as a distinct attempt.

#### Scenario: Webhook redelivery does not re-send

- **WHEN** a completion webhook is delivered again after delivery already succeeded
- **THEN** no further email is sent and the delivery record is unchanged

#### Scenario: Reconciliation after delivery does not re-send

- **WHEN** the reconciliation job re-enters completion for an already-delivered request
- **THEN** no further email is sent

#### Scenario: Concurrent completion attempts send once

- **WHEN** two completions for the same request are processed concurrently
- **THEN** each recipient receives exactly one email

#### Scenario: Staff can deliberately re-send

- **WHEN** staff request a re-send for a recipient
- **THEN** the document is sent again and the re-send is recorded as a separate, attributed
  attempt

### Requirement: Delivery failure is retried and never alters the signing record

A **transient** failure (timeout, provider unavailability, rate limiting) SHALL be retried with
backoff up to a bounded number of attempts. A **permanent** failure (hard bounce, rejected or
non-existent address) SHALL NOT be retried indefinitely; it SHALL be marked failed and surfaced
for staff.

Delivery outcome SHALL NOT change the signing request's state. A signing request that reached
`SIGNED` SHALL remain `SIGNED` regardless of whether delivery succeeds - a signature is a legal
fact and an undeliverable mailbox does not undo it.

Delivery SHALL NOT block the completion path: storing artifacts and recording completion SHALL
NOT be held up by, or rolled back because of, a delivery attempt.

**What "delivered" means, precisely.** A successful delivery means the **email provider accepted
the message**, not that it reached the recipient's mailbox. Plain SMTP has no return channel for a
bounce, so a hard bounce raised *after* acceptance is invisible and the permanent-failure path
above fires only for a rejection raised **during** the send. Any read that treats a successful
delivery as proof of arrival - closure included - inherits that limit and SHALL NOT be presented
as arrival confirmation. Closing the gap requires the provider's bounce webhook (follow-up
`zeptomail-bounce-webhook`), which is out of scope here.

#### Scenario: Transient failure is retried

- **WHEN** a delivery attempt fails transiently
- **THEN** it is retried with backoff, bounded by the configured attempt limit

#### Scenario: Permanent failure stops and escalates

- **WHEN** a delivery attempt fails permanently, such as a hard bounce
- **THEN** it is not retried indefinitely, is marked failed with its reason, and is surfaced
  for staff

#### Scenario: Delivery failure leaves the agreement signed

- **WHEN** every delivery attempt for a recipient fails
- **THEN** the signing request remains `SIGNED` and the stored artifacts are unaffected

#### Scenario: Completion is not blocked by delivery

- **WHEN** the email seam is unavailable at the moment of completion
- **THEN** artifacts are still stored and completion is still recorded, with delivery pending
  retry

### Requirement: Outbound email goes through a vendor-neutral seam

All outbound email SHALL go through a **vendor-neutral seam**, so the provider is one adapter
behind it. The seam SHALL carry the recipient, subject, body, and an optional attachment with
its filename and content type. No provider specifics SHALL leak past it.

The system SHALL enforce a configured **attachment size ceiling** before sending. A document
exceeding it SHALL NOT be silently dropped or truncated: delivery SHALL fall back to notifying
the party that the document is available in the application, and the oversize condition SHALL
be recorded.

Provider credentials SHALL come from **environment variables only**. Tests SHALL run against a
stub seam that captures messages without sending, requiring no mailbox, credential, or network
access.

#### Scenario: Provider specifics stay behind the seam

- **WHEN** an email is sent
- **THEN** the calling code depends only on the vendor-neutral seam, and no provider type
  crosses it

#### Scenario: Oversized attachment falls back rather than failing silently

- **WHEN** the signed document exceeds the configured attachment ceiling
- **THEN** the party is notified that the document is available in the application, no
  truncated or partial attachment is sent, and the condition is recorded

#### Scenario: Tests send no real mail

- **WHEN** the test suite runs
- **THEN** messages are captured by a stub seam, and no mailbox, credential, or outbound
  connection is required

### Requirement: Delivery logs no document bytes and no full addresses

Logging around delivery SHALL NOT emit attachment bytes, document content, or the full
recipient address. Recipient addresses SHALL be **redacted** (local part masked) wherever they
appear in logs. Email provider credentials SHALL never be logged.

Error responses and staff-facing views MAY show enough to act on a failure - the party, the
failure reason - without exposing the document itself.

#### Scenario: No document bytes or full addresses in logs

- **WHEN** the system logs around a delivery attempt, success, or failure
- **THEN** no attachment bytes or document content appear, and recipient addresses appear only
  in redacted form

#### Scenario: Staff can diagnose a failure without the document

- **WHEN** staff inspect a failed delivery
- **THEN** they see the affected party and the failure reason, and the document itself is not
  exposed in the diagnostic view

