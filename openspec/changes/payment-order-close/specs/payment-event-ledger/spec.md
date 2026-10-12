## MODIFIED Requirements

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
  reference, duplicate reference, order closed, recording failed, or errored;
- when the attempt was first received, when it was last received, and how many times.

Recording is best-effort: an attempt whose record cannot be written is still applied (see
"Recording an attempt never changes its result"), so the ledger is not guaranteed complete.
Recording an attempt never changes a payment order or an agreement.

A browser callback's record holds the payment id the gateway reported for the order, never the
one the browser presented. A surplus payment is recorded as confirmed; the payment order says
that it was surplus.

A confirmation attempt for a payment order staff have closed is recorded with the outcome order
closed. A staff member closing an order is not a confirmation attempt and adds no record.

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

#### Scenario: A confirmation for a closed order
- **GIVEN** a payment order staff have closed
- **WHEN** a verified gateway webhook reports a captured payment for it
- **THEN** one record exists for that order with the outcome order closed

#### Scenario: Closing an order adds no record
- **GIVEN** a payment order with one recording failure in the ledger
- **WHEN** a staff member marks it resolved
- **THEN** the ledger still holds exactly that one record for the order, unchanged
