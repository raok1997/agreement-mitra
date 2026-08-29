## Purpose

A configurable payment precondition on the fulfilment pipeline. It records whether an
agreement has been paid for and decides whether unpaid work may proceed. It exists now, in a
permissive mode, so that onboarding a real payment gateway later is an adapter plus a
configuration change rather than a re-plumbing of the pipeline.

## ADDED Requirements

### Requirement: Payment state is tracked on every agreement

Each agreement SHALL carry a **payment state** with at least the values `UNPAID`, `PAID`, and
`WAIVED`. New agreements SHALL start `UNPAID`.

`WAIVED` SHALL record a deliberate decision to proceed without payment (a manual override, a
test agreement, a goodwill case) and SHALL be distinguishable from `PAID` in every read and
report - the two SHALL NOT be collapsed, because one represents money received and the other
does not.

Payment state SHALL be **server-managed**: it SHALL NOT be settable by a client on create or
any other request. Any transition SHALL record who or what caused it and when.

#### Scenario: New agreements start unpaid

- **WHEN** an agreement is created
- **THEN** its payment state is `UNPAID`

#### Scenario: Payment state is not client-settable

- **WHEN** a client supplies a payment state in a request body
- **THEN** the supplied value is ignored and the server-managed state is unchanged

#### Scenario: Waived is distinguishable from paid

- **WHEN** an agreement is marked `WAIVED` and another is marked `PAID`
- **THEN** the two states remain distinguishable wherever payment state is read or reported

### Requirement: The payment gate has an OPTIONAL and a REQUIRED mode

The system SHALL enforce payment through a single **gate** whose behaviour is set by
configuration:

- **OPTIONAL** (the default): the gate records payment state and **permits** the pipeline to
  proceed whatever that state is.
- **REQUIRED**: the gate **blocks** the pipeline unless payment state is `PAID` or `WAIVED`.

Switching modes SHALL require no code change and no schema change. The active mode SHALL be
observable at runtime, so an operator can tell whether payment is being enforced without
reading configuration files.

Because no payment gateway exists yet, the system SHALL default to `OPTIONAL`. `REQUIRED` mode
SHALL be fully specified and testable now, so enabling it later is a configuration change
rather than new behaviour.

#### Scenario: OPTIONAL mode permits unpaid work

- **WHEN** the gate is `OPTIONAL` and an `UNPAID` agreement reaches a gated step
- **THEN** the step proceeds and the unpaid state is recorded, not blocked

#### Scenario: REQUIRED mode blocks unpaid work

- **WHEN** the gate is `REQUIRED` and an `UNPAID` agreement reaches a gated step
- **THEN** the step is refused with an error identifying payment as the cause, and no side
  effect occurs

#### Scenario: REQUIRED mode admits paid and waived agreements

- **WHEN** the gate is `REQUIRED` and an agreement is `PAID` or `WAIVED`
- **THEN** the gated step proceeds

#### Scenario: Mode is observable

- **WHEN** an operator inspects the running system
- **THEN** the active payment-gate mode is visible

### Requirement: The gate is enforced before money is spent and before signing

The gate SHALL be evaluated at **both** of these steps:

1. **E-stamp intake** (per `estamp-intake`) - because staff spend real money buying an SHCIL
   certificate, an unpaid agreement must not reach that queue when the gate is `REQUIRED`.
2. **eSign initiation** (per `signing-request`) - because each signature is a billable vendor
   transaction.

The gate SHALL be evaluated **before** any side effect at the gated step: no blob written, no
provider called, no state transitioned, no vendor charge incurred.

A refusal SHALL be **distinguishable** from the other preconditions on those steps (missing
draft, missing stamp, uncontactable party) so an operator can tell why the pipeline stopped.

#### Scenario: Stamp intake is gated before any side effect

- **WHEN** the gate is `REQUIRED` and stamp intake is attempted for an `UNPAID` agreement
- **THEN** the request is refused before any blob is stored or state changed

#### Scenario: eSign initiation is gated before the provider call

- **WHEN** the gate is `REQUIRED` and eSign initiation is attempted for an `UNPAID` agreement
- **THEN** the request is refused before any signing-request row is persisted and before the
  provider is called, so no vendor charge is incurred

#### Scenario: Payment refusal is distinguishable from other preconditions

- **WHEN** the pipeline is refused for non-payment
- **THEN** the error identifies payment as the cause, distinctly from a missing draft, a
  missing stamp, or an uncontactable party

### Requirement: Payment confirmation is recorded through a seam, not a gateway

The system SHALL record payment confirmation through a **vendor-neutral seam**, so that a
future payment gateway becomes one adapter behind it. The seam SHALL carry at minimum the
agreement, the amount, a currency, an external payment reference, and the confirmation time.

This change SHALL NOT itself integrate any payment gateway, handle card or UPI credentials, or
process refunds; a gateway-backed implementation is supplied separately by
`payment-processing`. The implementations introduced here SHALL be a manual staff-recorded
confirmation and a waiver - both requiring the STAFF role (per `backend-security-baseline`) -
and they SHALL remain available alongside any gateway-backed implementation, since a payment
taken out of band still has to be recordable.

The external payment reference SHALL be **unique** where present, so one payment cannot be
recorded against two agreements.

#### Scenario: Staff record a manual payment confirmation

- **WHEN** a STAFF user records a payment confirmation for an agreement
- **THEN** the agreement becomes `PAID`, and the amount, reference, actor, and time are
  recorded

#### Scenario: Non-staff cannot change payment state

- **WHEN** a caller without the STAFF role attempts to record payment or a waiver
- **THEN** the request is refused and the payment state is unchanged

#### Scenario: A payment reference cannot be reused

- **WHEN** an external payment reference already recorded against one agreement is recorded
  against another
- **THEN** the second attempt is refused

#### Scenario: No gateway credentials are required

- **WHEN** the system runs with no payment-gateway configuration
- **THEN** it starts normally, the gate operates in its configured mode, and no payment
  credential is needed
