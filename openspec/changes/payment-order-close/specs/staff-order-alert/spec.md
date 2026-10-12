## MODIFIED Requirements

### Requirement: A captured payment that was refused or not recorded raises a staff alert
The system SHALL record exactly one staff alert per payment order and kind, while staff alerts are enabled, when the payment event ledger holds, for that order, a confirmation attempt last received inside the look-back window whose outcome is a recording failure, a refused duplicate reference or an amount mismatch, and the order is neither recorded as paid nor closed by staff.

The kind follows from the outcome: a recording failure raises a payment-not-recorded alert, a
refused duplicate reference raises a payment-id-reused alert, and an amount mismatch raises a
payment-amount-mismatch alert. The alert is derived from the ledger by the same scheduled sweep,
under the same delivery, retry, failure and configuration rules as the other kinds. The look-back
is 24 hours, measured on when the attempt was last received, so an outstanding order that is
still being re-read and still failing stays inside it for as long as that lasts. Whether the
order is recorded as paid or closed is decided when the alert is recorded, not again when it is sent, and
only the order is consulted: an order that is expired or failed is alerted like an outstanding
one, and the agreement's own payment state is not read. The alert is raised once: an attempt that recurs after the alert was
sent or failed raises no second alert. An attempt that ended in any other way, that names an
order the system does not hold, or that carried no gateway payment identifier, raises none. The system records and refunds nothing because of
the alert; its purpose is that staff look at the payment.

#### Scenario: A recording failure is alerted
- **GIVEN** staff alerts are enabled and the ledger holds a recording failure, last received an hour ago, for a payment order that is not paid
- **WHEN** the sweep's enqueue step runs
- **THEN** one pending payment-not-recorded alert exists for that order

#### Scenario: A refused duplicate reference is alerted
- **GIVEN** staff alerts are enabled and the ledger holds a refused duplicate reference, last received an hour ago, for a payment order that is not paid
- **WHEN** the sweep's enqueue step runs
- **THEN** one pending payment-id-reused alert exists for that order

#### Scenario: An amount mismatch is alerted
- **GIVEN** staff alerts are enabled and the ledger holds an amount mismatch, last received an hour ago, for a payment order that is not paid
- **WHEN** the sweep's enqueue step runs
- **THEN** one pending payment-amount-mismatch alert exists for that order

#### Scenario: An expired order is alerted
- **GIVEN** staff alerts are enabled and the ledger holds a recording failure, last received an hour ago, for a payment order that has expired
- **WHEN** the sweep's enqueue step runs
- **THEN** one pending payment-not-recorded alert exists for that order

#### Scenario: A recording failure reported by the gateway is alerted end to end
- **GIVEN** staff alerts are enabled and an outstanding payment order
- **WHEN** the gateway webhook reports a captured payment the database refuses to record, and the sweep runs
- **THEN** the webhook is answered with a server error and no alert is recorded while confirming
- **AND** one payment-not-recorded alert is posted to the staff channel by the sweep

#### Scenario: The same failure from several routes
- **GIVEN** a payment order that is not paid, with a recording failure in the ledger from the webhook and another from the reconciliation job
- **WHEN** the alert sweep runs
- **THEN** that order has exactly one payment-not-recorded alert

#### Scenario: The failure recurs after the alert was sent
- **GIVEN** a payment order whose payment-not-recorded alert was sent
- **WHEN** the same failure is received again and the alert sweep runs
- **THEN** that order still has exactly one payment-not-recorded alert and nothing is posted

#### Scenario: Two kinds of failure on one order
- **GIVEN** a payment order that is not paid, with an amount mismatch and a recording failure in the ledger
- **WHEN** the alert sweep runs
- **THEN** that order has one payment-amount-mismatch alert and one payment-not-recorded alert

#### Scenario: The order was recorded before the sweep ran
- **GIVEN** a payment order with a recording failure in the ledger that a later delivery then confirmed as paid
- **WHEN** the alert sweep runs
- **THEN** no payment-not-recorded alert is raised for it

#### Scenario: The order was closed before the sweep ran
- **GIVEN** a payment order with an amount mismatch in the ledger, last received an hour ago, that staff have closed, with no alert
- **WHEN** the alert sweep runs
- **THEN** no payment-amount-mismatch alert is raised for it

#### Scenario: An attempt that ended another way
- **GIVEN** a payment order that is not paid, whose only ledger attempts ended in an error, a missing reference or an already-confirmed outcome
- **WHEN** the alert sweep runs
- **THEN** no staff alert is raised for it

#### Scenario: An attempt for an order the system does not hold
- **GIVEN** a ledger attempt naming a gateway order that matches no payment order
- **WHEN** the alert sweep runs
- **THEN** no staff alert is raised

#### Scenario: An attempt that carried no payment identifier
- **GIVEN** a payment order that is not paid, whose only ledger attempt is an amount mismatch with no gateway payment identifier
- **WHEN** the alert sweep runs
- **THEN** no staff alert is raised for it

#### Scenario: A failure last received before the look-back window
- **GIVEN** a payment order that is not paid, whose recording failure was last received more than 24 hours ago, with no alert
- **WHEN** the alert sweep runs
- **THEN** no staff alert is raised for it

#### Scenario: A failure first received long ago and still recurring
- **GIVEN** a payment order that is not paid, whose recording failure was first received three days ago and last received an hour ago, with no alert
- **WHEN** the alert sweep runs
- **THEN** one pending payment-not-recorded alert exists for that order

#### Scenario: Dispatch sends an unrecorded-payment alert
- **GIVEN** a pending payment-not-recorded alert that is due
- **WHEN** the alert sweep runs
- **THEN** it is posted to the staff channel once and marked sent

#### Scenario: The three messages are told apart
- **GIVEN** a payment-not-recorded alert, a payment-id-reused alert and a payment-amount-mismatch alert
- **WHEN** each is composed
- **THEN** each message opens with a different fixed lead, and none shares a lead with the paid-order or duplicate-payment alert
- **AND** none contains an amount, a gateway payment or order identifier, or the agreement identifier
