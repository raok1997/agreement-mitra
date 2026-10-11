## ADDED Requirements

### Requirement: Every gateway payment confirmation attempt is recorded
The system SHALL keep a durable record of every gateway payment confirmation attempt, whether it came from a verified gateway webhook, the browser checkout callback, or the reconciliation job.

A **confirmation attempt** is a confirmation the system tries to apply for a gateway order: a
verified webhook for an event the system acts on that names an order, or an authoritative read
in which the gateway reports the order paid with a captured payment.

Each record holds:

- the source of the attempt: webhook, browser callback, or reconciliation;
- for a webhook, the gateway's event type, and the event id presented with it when there was
  one;
- the gateway order id, and the gateway payment id when one was reported;
- the reported amount and currency;
- the outcome: confirmed, already confirmed, unknown order, amount mismatch, missing
  reference, duplicate reference, recording failed, or errored;
- when the attempt was first received, when it was last received, and how many times.

Recording is best-effort: an attempt whose record cannot be written is still applied (see
"Recording an attempt never changes its result"), so the ledger is not guaranteed complete.
Recording an attempt never changes a payment order or an agreement.

A browser callback's record holds the payment id the gateway reported for the order, never the
one the browser presented. A surplus payment is recorded as confirmed; the payment order says
that it was surplus.

#### Scenario: A webhook confirms a payment
- **GIVEN** an outstanding payment order
- **WHEN** a verified gateway webhook reports a captured payment for it and presents an event id
- **THEN** one record exists for that order with source webhook, that event id and event type, the payment id, the amount and currency, the outcome confirmed, and a count of one
- **AND** its first and last received times are the same, and not before the webhook was sent

#### Scenario: The second event for one payment
- **GIVEN** a payment order a first webhook event has already confirmed
- **WHEN** the gateway's second event for the same payment arrives, of the other event type
- **THEN** a second record exists for that order with that event type and the outcome already confirmed
- **AND** the first record is unchanged

#### Scenario: A webhook for an order the system does not hold
- **GIVEN** no payment order with a given gateway order id
- **WHEN** a verified gateway webhook reports a captured payment for that order id
- **THEN** one record exists with that order id and the outcome unknown order

#### Scenario: The browser callback confirms a payment
- **GIVEN** an outstanding payment order the gateway reports paid with a captured payment
- **WHEN** the customer's browser reports the checkout closed with a valid handler signature and a payment id that differs from the one the gateway reports
- **THEN** one record exists for that order with source browser callback, no event id, no event type, and the outcome confirmed
- **AND** the record holds the payment id the gateway reported

#### Scenario: Reconciliation confirms a payment
- **GIVEN** an outstanding payment order the gateway reports paid with a captured payment
- **WHEN** the reconciliation job runs
- **THEN** one record exists for that order with source reconciliation and the outcome confirmed

#### Scenario: A reported amount that differs from the order
- **GIVEN** an outstanding payment order
- **WHEN** a verified gateway webhook reports a payment for it with a different amount
- **THEN** one record exists for that order with the reported amount and the outcome amount mismatch

### Requirement: A repeated attempt is counted, not recorded again
The system SHALL record an attempt that repeats an earlier one by raising that record's count and widening its first-received and last-received times to include it, and SHALL NOT add a record for it.

An attempt **repeats** an earlier one when it has the same gateway order id, source, event
type, outcome, gateway payment id, reported amount, reported currency, refused rule and SQL
state. Its time and the event id presented with it do not make it a different attempt.

The first-received time is the earliest received time among the attempts and the last-received
time the latest, in whatever order they are recorded. A repeat changes nothing else in the
record: the event id first presented stays as it was written.

#### Scenario: A webhook delivered again
- **GIVEN** a webhook recorded with the outcome already confirmed
- **WHEN** the same webhook is delivered again three times
- **THEN** there is still one record for it, with a count of four
- **AND** its last-received time is later than its first-received time

#### Scenario: A replay presenting a different event id
- **GIVEN** a webhook recorded with an event id
- **WHEN** the same signed body is delivered again presenting a different event id
- **THEN** it is applied like any other attempt
- **AND** no record is added, the count rises by one, and the record still holds the first event id

#### Scenario: The same attempt from another source
- **GIVEN** a payment order a webhook has confirmed
- **WHEN** the customer's browser then reports the checkout closed with a valid handler signature
- **THEN** a separate record exists for the order with source browser callback and the outcome already confirmed

#### Scenario: A repeat received out of order
- **GIVEN** a record of one attempt, and an identical attempt that was received before it but is only now being recorded
- **WHEN** that earlier attempt is recorded
- **THEN** the count rises by one and the last-received time is unchanged
- **AND** the first-received time moves back to when the earlier attempt was received

### Requirement: A refused confirmation is recorded although nothing else is
The system SHALL record a confirmation attempt the database refuses, with the name of the refused rule where the database reports one and the SQL state, and that record SHALL persist although the refused confirmation itself recorded nothing.

A refusal by one of the two payment uniqueness rules is recorded with the outcome duplicate
reference. Any other refusal is recorded with the outcome recording failed. A confirmation that
ends in any other error is recorded with the outcome errored and the SQL state when the error
carries one, and the error reaches the caller unchanged. The record SHALL NOT hold the
database's error text, any row content it reports, or anything else taken from the error.

This record is separate from the failure the system raises and the line it logs for it, which
continue to carry neither the payment id nor the amount.

#### Scenario: A payment the database refuses to record
- **GIVEN** an outstanding payment order
- **WHEN** a verified gateway webhook reports a payment for it that the database refuses for a reason other than the two uniqueness rules
- **THEN** the order is still outstanding and the agreement is still unpaid
- **AND** one record exists for that order with the outcome recording failed and the SQL state of the refusal
- **AND** the record holds no text from the database's error

#### Scenario: A refused payment is delivered again
- **GIVEN** a webhook whose confirmation was recorded as recording failed
- **WHEN** the gateway delivers the same webhook again
- **THEN** it is refused again and answered the same way
- **AND** there is still one record for it, with a count of two

#### Scenario: A refused duplicate reference
- **GIVEN** an agreement that holds a payment under a gateway payment id
- **WHEN** the gateway confirms a payment order of a different agreement with that same payment id
- **THEN** one record exists for the second order with the outcome duplicate reference and the name of the uniqueness rule that refused it

#### Scenario: A confirmation that ends in another error
- **GIVEN** an outstanding payment order whose confirmation fails with an error that is not a database refusal and carries no SQL state
- **WHEN** a confirmation is attempted for it
- **THEN** the same error reaches the caller, exactly as without the ledger
- **AND** one record exists for that order with the outcome errored, no refused rule and no SQL state

#### Scenario: Another error that carries a SQL state
- **GIVEN** an outstanding payment order whose confirmation fails with an error that is not a database refusal and carries a SQL state
- **WHEN** a confirmation is attempted for it
- **THEN** one record exists for that order with the outcome errored and that SQL state, and no refused rule

#### Scenario: Reconciliation keeps meeting a refused payment
- **GIVEN** an outstanding payment order the gateway reports paid, whose confirmation the database refuses for a reason other than the two uniqueness rules
- **WHEN** the reconciliation job runs twice
- **THEN** one record exists for that order with source reconciliation, the outcome recording failed and a count of two

### Requirement: Recording an attempt never changes its result
The system SHALL give every confirmation attempt the same result, and the gateway the same answer, whether or not the attempt could be recorded.

#### Scenario: The record cannot be written for a confirmed payment
- **GIVEN** an outstanding payment order, and a ledger that cannot be written
- **WHEN** a verified gateway webhook reports a captured payment for the order
- **THEN** the order is paid and the webhook is acknowledged

#### Scenario: The record cannot be written for a refused payment
- **GIVEN** a confirmation the database refuses for a reason other than the two uniqueness rules, and a ledger that cannot be written
- **WHEN** a verified gateway webhook reports that payment
- **THEN** the webhook is answered with a server error, as it is when the ledger can be written

#### Scenario: A gateway value the payment record cannot hold
- **GIVEN** an outstanding payment order
- **WHEN** a verified gateway webhook reports a payment id longer than a payment order can hold
- **THEN** the confirmation fails as a payment-recording failure
- **AND** a record of the attempt exists, holding that payment id whole

### Requirement: Only verified data about a confirmation is recorded
The system SHALL NOT record anything from a webhook whose signature does not verify, and SHALL NOT keep the webhook body or any payer detail from it.

A verified webhook the system does not act on writes no record: a body that cannot be parsed,
an event type other than the ones that describe a successful payment, or an event naming no
order. An authoritative read in which the gateway reports the order unpaid, or paid with no
captured payment, is not a confirmation attempt and writes no record.

The gateway's event id is presented outside the signed body. The system SHALL keep it only as a
label: it SHALL NOT decide whether or how a confirmation is applied, and a presented value that
is not a short plain token SHALL be recorded as absent.

#### Scenario: A webhook whose signature does not verify
- **WHEN** a webhook arrives whose signature does not verify
- **THEN** it is rejected and no record is written

#### Scenario: An event the system does not act on
- **WHEN** a verified gateway webhook reports a failed payment
- **THEN** it is acknowledged and no record is written

#### Scenario: The gateway reports the order unpaid
- **GIVEN** an outstanding payment order the gateway reports unpaid
- **WHEN** the reconciliation job runs
- **THEN** no record is written for that order

#### Scenario: Payer details in the webhook body
- **GIVEN** a verified gateway webhook whose payment carries the payer's email, phone number and payment-method detail
- **WHEN** the attempt is recorded
- **THEN** the record holds none of them

#### Scenario: An event id that is not a plain token
- **WHEN** a verified gateway webhook presents an event id that is longer than the limit or holds characters outside letters, digits, hyphen and underscore
- **THEN** the attempt is recorded with no event id
- **AND** the confirmation is applied exactly as it would be with none presented
