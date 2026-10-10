## ADDED Requirements

### Requirement: A gateway payment on an already-paid agreement is kept as a surplus payment
The system SHALL, when a gateway payment is confirmed for an agreement that is at that moment already paid under a different payment reference, mark that payment order paid and surplus, and SHALL NOT change the agreement's recorded payment or repeat any effect of a first payment confirmation.

A payment order is **surplus** when its payment was captured but was not recorded as the
agreement's payment, because the agreement already held one when it was confirmed. This is the one
exception to the rule that a gateway confirmation updates the agreement's payment state through the
payment-gate seam: the agreement's recorded amount, currency, reference, actor and time stay those
of the first payment, whether that was a gateway payment or a manual staff confirmation.

References are compared by one shared normalisation - the same one a manually recorded reference
is stored under - so case and surrounding whitespace do not matter. The surplus mark is decided
while the agreement is locked, is set once when the order is marked paid, and never changes
afterwards. The confirmation is still acknowledged to the gateway and is still idempotent under
redelivery. One gateway payment id SHALL be held by at most one payment order.

#### Scenario: A late payment on an expired order after another order was paid
- **GIVEN** an agreement paid through one payment order, and an earlier order for it that expired
- **WHEN** the gateway confirms a payment on the expired order
- **THEN** that order is marked paid and surplus
- **AND** the agreement's recorded amount, reference and time are unchanged
- **AND** the confirmation is acknowledged as it is for any confirmed payment

#### Scenario: A late payment on a failed order after another order was paid
- **GIVEN** an agreement paid through one payment order, and an earlier order for it that failed
- **WHEN** the gateway confirms a payment on the failed order
- **THEN** that order is marked paid and surplus and the agreement's recorded payment is unchanged

#### Scenario: A gateway payment after a manual staff confirmation
- **GIVEN** an agreement staff confirmed as paid by hand under one reference
- **WHEN** the gateway confirms a payment order for it with a different payment id
- **THEN** that order is marked paid and surplus
- **AND** the agreement's recorded reference and actor are still those of the staff confirmation

#### Scenario: Staff recorded the same gateway payment by hand
- **GIVEN** an agreement staff confirmed as paid using a gateway payment id as the reference, typed in another case or with surrounding whitespace
- **WHEN** the gateway confirms the payment order carrying that payment id
- **THEN** that order is marked paid and is not surplus
- **AND** the agreement's recorded payment is unchanged
- **AND** the parties are sent the recovery link, as for a first gateway confirmation

#### Scenario: A gateway payment after a waiver
- **GIVEN** an agreement whose payment staff waived
- **WHEN** the gateway confirms a payment order for it
- **THEN** the agreement becomes paid with that payment recorded
- **AND** the order is marked paid and is not surplus

#### Scenario: A first payment is unaffected
- **GIVEN** an unpaid agreement
- **WHEN** the gateway confirms a payment order for it
- **THEN** the agreement becomes paid with that payment recorded and the order is not surplus

#### Scenario: A surplus payment sends no recovery link
- **GIVEN** an agreement that is already paid
- **WHEN** a surplus payment is confirmed for it
- **THEN** no recovery link is sent for that confirmation

#### Scenario: A surplus confirmation is redelivered
- **GIVEN** a payment order already marked paid and surplus
- **WHEN** the same confirmation is delivered again
- **THEN** nothing changes and the order is still surplus

#### Scenario: A third payment
- **GIVEN** an agreement with one recorded payment and one surplus payment order
- **WHEN** the gateway confirms a payment on yet another of its orders
- **THEN** that order is also marked paid and surplus and the agreement's recorded payment is unchanged

#### Scenario: Two orders for one agreement are confirmed at the same instant
- **GIVEN** an unpaid agreement with two payment orders, each with a captured payment
- **WHEN** both confirmations are applied concurrently
- **THEN** exactly one payment is recorded on the agreement
- **AND** the other order is marked paid and surplus

#### Scenario: A payment id already held by another order
- **GIVEN** a payment order already marked paid under one gateway payment id
- **WHEN** a confirmation for a different order reports that same payment id
- **THEN** the confirmation is refused as a duplicate reference
- **AND** that order is not marked paid and is not surplus
