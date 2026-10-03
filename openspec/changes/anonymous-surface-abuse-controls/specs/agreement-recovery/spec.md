## MODIFIED Requirements

### Requirement: The recovery request endpoint is rate limited and audited

The system SHALL limit the rate of recovery requests per source and per reference, and SHALL
apply a lockout after repeated failures. Rate limiting SHALL NOT change the shape of the
response in a way that reveals whether a reference exists.

The **source** SHALL be the requester's own address as resolved from the trusted reverse-proxy
chain, never the address of the proxy in front of the application. Keyed on a proxy address, a
per-source limit stops being per-source: every customer shares one bucket, so one caller's
lockout refuses every other customer, and the limit this requirement describes is not in force.
Source resolution is specified by `anonymous-abuse-controls`.

The limiter SHALL NOT retain an entry for a key whose window and lockout have elapsed, so that
the control cannot be turned into a memory-exhaustion vector by rotating sources.

Every recovery request SHALL produce an audit record capturing the reference, the outcome, and
the time. Logs and audit records SHALL record recipient addresses only in redacted form.

#### Scenario: Repeated requests are throttled

- **WHEN** recovery requests exceed the configured rate from one source
- **THEN** further requests are refused for a lockout period

#### Scenario: One source's lockout does not refuse another customer

- **GIVEN** two customers reaching the application through the same reverse proxy
- **WHEN** one of them is placed in lockout by the per-source limit
- **THEN** the other's recovery request is still accepted

#### Scenario: Throttling is not an oracle

- **WHEN** a throttled request is answered
- **THEN** its response is identical in shape to an unthrottled one

#### Scenario: Recipients are redacted in audit output

- **WHEN** any recovery request is processed
- **THEN** recipient addresses appear redacted in every log line and audit record
