# staff-order-alert Specification

## Purpose
TBD - created by archiving change staff-paid-order-alert. Update Purpose after archive.
## Requirements
### Requirement: A gateway-paid order raises one staff alert per agreement
The system SHALL record exactly one paid-order staff alert for an agreement once a gateway payment order for that agreement is paid and is not surplus, however many times and by whichever route that payment is reported.

The alert is derived from the paid payment order, so the gateway webhook, the browser callback and
the payment reconciliation job produce one alert record between them. Only orders paid within the
last 24 hours are considered, so switching the feature on does not alert on old orders. Delivery of
that one record is at-least-once: a send interrupted after the channel accepted it may be repeated.
A surplus payment order never raises a paid-order alert; it raises a duplicate-payment alert
instead, under its own requirement.

#### Scenario: A paid order is alerted
- **GIVEN** staff alerts are enabled and the gateway has marked an agreement's payment order paid
- **WHEN** the sweep's enqueue step runs
- **THEN** one pending paid-order alert exists for that agreement

#### Scenario: The sweep runs again
- **GIVEN** an agreement that already has a paid-order alert
- **WHEN** the alert sweep runs again
- **THEN** the agreement still has exactly one paid-order alert

#### Scenario: A second paid order for the same agreement
- **GIVEN** an agreement that already has a paid-order alert
- **WHEN** another payment order for the same agreement is marked paid
- **THEN** no second paid-order alert is raised

#### Scenario: A gateway payment after a manual staff confirmation
- **GIVEN** an agreement that staff confirmed as paid by hand, with no staff alert
- **WHEN** the gateway later marks a payment order for it paid and that order is not surplus
- **THEN** one paid-order alert is raised for that agreement

#### Scenario: An order paid before the look-back window
- **GIVEN** a payment order marked paid more than 24 hours ago with no staff alert
- **WHEN** the alert sweep runs
- **THEN** no staff alert is raised for it

### Requirement: Only a gateway-paid order raises an alert
The system SHALL NOT raise a staff alert for an order that is placed but unpaid, for a payment order that is not paid, for a manual staff confirmation, or for a staff waiver.

#### Scenario: Order placed without payment
- **GIVEN** staff alerts are enabled
- **WHEN** a customer finalises an agreement and has not paid
- **THEN** no staff alert is raised

#### Scenario: An unpaid payment order
- **GIVEN** an agreement whose only payment orders are created, failed or expired
- **WHEN** the alert sweep runs
- **THEN** no staff alert is raised

#### Scenario: Staff confirm or waive
- **GIVEN** an agreement with no gateway-paid order
- **WHEN** a staff member confirms or waives its payment and the alert sweep runs
- **THEN** no staff alert is raised

### Requirement: Staff alerts stay off the payment confirmation path
The system SHALL record and send staff alerts only from a scheduled job, and SHALL NOT record a staff alert or make an outbound call while confirming a payment.

Marking a payment order surplus is part of confirming the payment, not of alerting: it is written
on the order row the confirmation already updates.

#### Scenario: Confirming a payment does not contact the channel
- **GIVEN** staff alerts are enabled
- **WHEN** the gateway webhook confirms a payment
- **THEN** the payment is confirmed and the webhook is acknowledged
- **AND** no request to the alert channel is made and no staff alert is recorded while confirming

#### Scenario: Confirming a surplus payment does not contact the channel
- **GIVEN** staff alerts are enabled and an agreement already paid
- **WHEN** the gateway webhook confirms a surplus payment for it
- **THEN** the webhook is acknowledged
- **AND** no request to the alert channel is made and no staff alert is recorded while confirming

#### Scenario: The sweep fails
- **GIVEN** a sweep that fails with an unexpected error
- **WHEN** the scheduled job runs it
- **THEN** the error does not escape the job and the next run proceeds normally

#### Scenario: Dispatch sends a pending alert
- **GIVEN** a pending staff alert that is due
- **WHEN** the alert sweep runs
- **THEN** the alert is posted to the staff channel once and marked sent

### Requirement: Failed sends are retried within bounds
The system SHALL retry a staff alert that fails transiently, with increasing backoff, up to a fixed maximum number of attempts, and SHALL mark it failed when the attempts are exhausted, when the channel refuses it permanently, or when it has been pending for more than 24 hours.

A transient failure is a timeout, a connection error, a rate-limit response, a server error, or
any unexpected error while composing or sending. A permanent refusal is a redirect or any other
client-error response, such as a deleted webhook.

#### Scenario: Transient failure then success
- **GIVEN** a pending staff alert and a channel returning a server error
- **WHEN** the alert sweep runs
- **THEN** the alert stays pending with one more attempt counted and a later next-attempt time
- **AND** a later run, with the channel healthy, marks it sent

#### Scenario: Rate limited
- **GIVEN** a pending staff alert and a channel returning a rate-limit response
- **WHEN** the alert sweep runs
- **THEN** the alert stays pending and is retried later

#### Scenario: Attempts exhausted
- **GIVEN** a pending staff alert one attempt short of the maximum
- **WHEN** the alert sweep runs and the send fails transiently
- **THEN** the alert is marked failed and is not attempted again

#### Scenario: Interrupted on the last attempt
- **GIVEN** a pending staff alert that has used every attempt without an outcome being recorded
- **WHEN** the alert sweep runs after its next-attempt time
- **THEN** the alert is marked failed without another send

#### Scenario: Permanent refusal
- **GIVEN** a pending staff alert and a channel returning a not-found response
- **WHEN** the alert sweep runs
- **THEN** the alert is marked failed after that one attempt
- **AND** an error is logged that names neither the webhook URL nor the full agreement identifier

#### Scenario: Redirect
- **GIVEN** a pending staff alert and a channel returning a redirect
- **WHEN** the alert sweep runs
- **THEN** the redirect is not followed and the alert is marked failed

#### Scenario: Stale alert
- **GIVEN** a staff alert that has been pending for more than 24 hours
- **WHEN** the alert sweep runs
- **THEN** the alert is marked failed without being sent

#### Scenario: One alert is claimed once
- **GIVEN** a pending staff alert that is due
- **WHEN** two sweeps try to claim it at the same instant
- **THEN** exactly one claim succeeds and the alert is posted at most once

### Requirement: The alert carries no personal data and no credential
The system SHALL limit the alert message to a fixed label naming the kind of alert, the agreement's tracking reference, a two-letter state code and a link to the public site, and SHALL NOT include the agreement identifier, any amount, any gateway identifier or any user-entered text.

#### Scenario: Message content
- **GIVEN** a paid agreement with party names, a city and an agreement identifier in its staff view
- **WHEN** its staff alert is composed
- **THEN** the message contains the tracking reference and the state code
- **AND** the message contains no party name, no city and no agreement identifier

#### Scenario: A duplicate-payment message is told apart
- **GIVEN** a duplicate-payment alert and a paid-order alert for the same agreement
- **WHEN** each is composed
- **THEN** the duplicate-payment message says the agreement may have been paid more than once and must be checked before any refund
- **AND** the paid-order message does not
- **AND** neither contains an amount, a gateway payment or order identifier, or the agreement identifier

#### Scenario: State is missing or not a two-letter code
- **GIVEN** a paid agreement whose template state is absent or is not two capital letters
- **WHEN** its staff alert is composed
- **THEN** the message is sent with the tracking reference and without a state

#### Scenario: Site address is not a secure absolute address
- **GIVEN** a configured public site address that is blank or not an https address
- **WHEN** a staff alert is composed
- **THEN** the message is sent without a link

#### Scenario: Mentions and previews are suppressed
- **WHEN** a staff alert is posted to Discord
- **THEN** the request disables all mention parsing and suppresses link previews

### Requirement: The alert channel is configured by a secret and is off when unset
The system SHALL read the staff channel address from configuration only, SHALL treat it as a secret that is never logged, and SHALL record and send no staff alert while it is blank or unusable.

#### Scenario: Channel not configured
- **GIVEN** the staff channel address is blank
- **WHEN** the alert sweep runs with a paid order present
- **THEN** no staff alert is recorded and no outbound call is made

#### Scenario: Channel address is unusable
- **GIVEN** a staff channel address that does not parse, has no scheme or host, or uses plain http to a host other than this machine
- **WHEN** the application starts and the alert sweep runs
- **THEN** the application starts, staff alerts are off, and one error is logged that does not contain the address

#### Scenario: A send fails
- **GIVEN** a configured staff channel
- **WHEN** a send fails with an error response, a timeout, a refused connection or an unexpected error
- **THEN** no log line, and no exception attached to one, contains the channel address or its secret token

#### Scenario: Outbound calls are time-bounded
- **GIVEN** a staff channel that accepts a connection and never responds
- **WHEN** an alert is sent
- **THEN** the call is abandoned at the read timeout and counted as a transient failure

### Requirement: A surplus gateway payment raises a duplicate-payment staff alert
The system SHALL record exactly one duplicate-payment staff alert for each surplus payment order found inside the look-back window while staff alerts are enabled, however many times and by whichever route that payment is reported.

A surplus payment order is defined in `payment-processing`: a paid order whose payment was not
recorded as the agreement's payment because the agreement already held one. The alert is derived
from the surplus order by the same scheduled sweep, under the same 24-hour look-back, delivery,
retry, failure and configuration rules as the paid-order alert. Its purpose is that staff check the
payments and start a refund where one is owed; the system itself refunds nothing. The surplus mark
on the order is the durable record and outlives a lost or failed alert.

#### Scenario: A surplus payment is alerted
- **GIVEN** staff alerts are enabled and a payment order has been marked paid and surplus
- **WHEN** the sweep's enqueue step runs
- **THEN** one pending duplicate-payment alert exists for that order

#### Scenario: A surplus payment confirmed by the gateway is alerted end to end
- **GIVEN** staff alerts are enabled and an agreement already paid
- **WHEN** the gateway webhook confirms a payment on another of its orders and the sweep runs
- **THEN** one duplicate-payment alert is posted to the staff channel

#### Scenario: The sweep runs again
- **GIVEN** a surplus payment order that already has a duplicate-payment alert, in any status
- **WHEN** the alert sweep runs again
- **THEN** that order still has exactly one duplicate-payment alert

#### Scenario: A third payment on the same agreement
- **GIVEN** an agreement with two surplus payment orders
- **WHEN** the alert sweep runs
- **THEN** two duplicate-payment alerts exist, one for each surplus order

#### Scenario: A gateway payment after a manual staff confirmation
- **GIVEN** an agreement staff confirmed as paid by hand under one reference
- **WHEN** the gateway marks a payment order for it paid with a different payment id and the sweep runs
- **THEN** one duplicate-payment alert is raised for that order
- **AND** no paid-order alert is raised for it

#### Scenario: A paid order that is not surplus
- **GIVEN** an agreement whose only paid payment order is not surplus
- **WHEN** the alert sweep runs
- **THEN** no duplicate-payment alert is raised

#### Scenario: A surplus payment before the look-back window
- **GIVEN** a surplus payment order marked paid more than 24 hours ago with no duplicate-payment alert
- **WHEN** the alert sweep runs
- **THEN** no duplicate-payment alert is raised for it

#### Scenario: Dispatch sends a duplicate-payment alert
- **GIVEN** a pending duplicate-payment alert that is due
- **WHEN** the alert sweep runs
- **THEN** it is posted to the staff channel once and marked sent

#### Scenario: Two alerts for one agreement in the same sweep
- **GIVEN** a pending paid-order alert and a pending duplicate-payment alert for the same agreement, both due
- **WHEN** the alert sweep runs and the channel refuses the duplicate-payment alert permanently
- **THEN** the paid-order alert is sent and the duplicate-payment alert alone is marked failed
- **AND** the logged error names the kind of alert that failed

