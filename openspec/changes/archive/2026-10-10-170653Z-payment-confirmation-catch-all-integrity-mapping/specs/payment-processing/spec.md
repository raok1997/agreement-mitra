## ADDED Requirements

### Requirement: A refused gateway confirmation is a duplicate only for the payment uniqueness rules
The system SHALL report a gateway payment confirmation the database refuses as a duplicate reference only when the refusal comes from the rule that one payment reference is recorded against at most one agreement or the rule that one gateway payment id is held by at most one payment order, and SHALL treat any other refusal as a payment-recording failure.

A **payment-recording failure** records nothing: the payment order keeps its status, it is not
marked surplus, the agreement's payment state is unchanged, and none of the effects of a first
payment confirmation happen. It is never reported as a duplicate reference and never as a confirmed
payment.

What each producer of a confirmation does with a payment-recording failure:

- The gateway webhook SHALL NOT acknowledge it. A verified webhook whose confirmation fails this way
  is answered with a server error, so the gateway delivers it again.
- The reconciliation job SHALL leave the order outstanding and read it again on a later run; one
  order's failure does not stop the rest of the batch.
- The browser checkout callback SHALL still answer with the current payment progress.

The failure the system raises, and the line it logs for that failure, SHALL name the refused rule
where the database reports one, and SHALL NOT carry the payment id, the amount, or any row content
reported by the database.

#### Scenario: A payment id staff already recorded against another agreement
- **GIVEN** an agreement staff confirmed as paid by hand using a gateway payment id as the reference
- **WHEN** the gateway confirms a payment order of a different agreement with that same payment id in another letter case
- **THEN** the confirmation is refused as a duplicate reference
- **AND** that order is not marked paid and the second agreement stays unpaid

#### Scenario: A refusal that is not a duplicate is not acknowledged to the gateway
- **GIVEN** an outstanding payment order
- **WHEN** a verified gateway webhook reports a payment for it that the database refuses for a reason other than the two uniqueness rules
- **THEN** the webhook is answered with a server error whose body names neither the refused rule nor the payment
- **AND** the order is still outstanding and not surplus, the agreement is still unpaid, and no recovery link is sent
- **AND** delivering the same webhook again gives the same answer and changes nothing

#### Scenario: A refusal that is not a duplicate is never reported as one
- **GIVEN** an outstanding payment order
- **WHEN** a confirmation for it is refused by the database for a reason other than the two uniqueness rules
- **THEN** the confirmation fails as a payment-recording failure
- **AND** it is not reported as a duplicate reference

#### Scenario: Reconciliation meets a payment-recording failure
- **GIVEN** two outstanding payment orders the provider reports as paid, the first of which fails as a payment-recording failure
- **WHEN** the reconciliation job runs
- **THEN** the first order is still outstanding
- **AND** the second order is confirmed

#### Scenario: The browser callback meets a payment-recording failure
- **GIVEN** an outstanding payment order whose confirmation fails as a payment-recording failure
- **WHEN** the customer's browser reports the checkout closed with a valid handler signature
- **THEN** the response is the current payment progress, showing the agreement unpaid
- **AND** no error is returned to the customer

#### Scenario: A payment-recording failure does not expose the payment
- **GIVEN** a confirmation the database refuses for a reason other than the two uniqueness rules
- **WHEN** the failure is raised and logged
- **THEN** it names the refused rule or reports that none was named
- **AND** neither the failure nor its log line carries the payment id, the amount, or the text of the database's own error
