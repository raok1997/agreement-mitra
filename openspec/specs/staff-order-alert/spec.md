# staff-order-alert Specification

## Purpose
TBD - created by archiving change staff-paid-order-alert. Update Purpose after archive.
## Requirements
### Requirement: A gateway-paid order raises one staff alert per agreement
The system SHALL record exactly one staff alert for an agreement once a gateway payment order for that agreement is paid, however many times and by whichever route that payment is reported.

The alert is derived from the paid payment order, so the gateway webhook, the browser callback and
the payment reconciliation job produce one alert record between them. Only orders paid within the
last 24 hours are considered, so switching the feature on does not alert on old orders. Delivery of
that one record is at-least-once: a send interrupted after the channel accepted it may be repeated.

#### Scenario: A paid order is alerted
- **GIVEN** staff alerts are enabled and the gateway has marked an agreement's payment order paid
- **WHEN** the sweep's enqueue step runs
- **THEN** one pending staff alert exists for that agreement

#### Scenario: The sweep runs again
- **GIVEN** an agreement that already has a staff alert
- **WHEN** the alert sweep runs again
- **THEN** the agreement still has exactly one staff alert

#### Scenario: A second paid order for the same agreement
- **GIVEN** an agreement that already has a staff alert
- **WHEN** another payment order for the same agreement is marked paid
- **THEN** no second staff alert is raised

#### Scenario: A gateway payment after a manual staff confirmation
- **GIVEN** an agreement that staff confirmed as paid by hand, with no staff alert
- **WHEN** the gateway later marks a payment order for it paid
- **THEN** one staff alert is raised for that agreement

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
The system SHALL record and send staff alerts only from a scheduled job, and SHALL NOT add a write or an outbound call to the confirmation of a payment.

#### Scenario: Confirming a payment does not contact the channel
- **GIVEN** staff alerts are enabled
- **WHEN** the gateway webhook confirms a payment
- **THEN** the payment is confirmed and the webhook is acknowledged
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
The system SHALL limit the alert message to the agreement's tracking reference, a two-letter state code and a link to the public site, and SHALL NOT include the agreement identifier or any user-entered text.

#### Scenario: Message content
- **GIVEN** a paid agreement with party names, a city and an agreement identifier in its staff view
- **WHEN** its staff alert is composed
- **THEN** the message contains the tracking reference and the state code
- **AND** the message contains no party name, no city and no agreement identifier

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

