## ADDED Requirements

### Requirement: An order is placed only for an eligible duty jurisdiction

Finalising SHALL refuse an agreement whose duty jurisdiction is not eligible for paid
fulfilment. The check SHALL run **before the agreement is frozen and before any signing
request is created**, so an ineligible agreement never reaches the durable awaiting-stamp
state and never appears in the staff stamp queue.

This is deliberately one gate among several rather than a substitute for the others.
Finalise and checkout are separately reachable endpoints, and finalise is what commits the
order to staff, so each enforces the rule independently.

This adds a **precondition** to the existing transition into the awaiting-stamp state; it
introduces no new signing status and changes no existing transition. Finalise SHALL remain
idempotent for an eligible jurisdiction.

The refusal SHALL use the distinct unsupported-jurisdiction error kind.

#### Scenario: Finalise is refused for an ineligible jurisdiction

- **WHEN** finalise is called for an agreement whose duty jurisdiction is not eligible
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** no signing request is created
- **AND** the agreement's terms are not frozen
- **AND** the agreement does not appear in the staff stamp queue

#### Scenario: Finalise is refused for an agreement with no pinned template

- **WHEN** finalise is called for an agreement that has no selected template
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** no signing request is created

#### Scenario: Finalise proceeds for an eligible jurisdiction

- **WHEN** finalise is called for an agreement whose duty jurisdiction is eligible and which
  satisfies every existing precondition
- **THEN** the order is placed and the draft freezes exactly as before this change
- **AND** finalising again remains idempotent

### Requirement: A billable eSign transaction requires an eligible duty jurisdiction

Initiating an eSign request SHALL refuse an agreement whose duty jurisdiction is not
eligible for paid fulfilment, and SHALL do so **before any call to the eSign provider**, so
no billable vendor transaction is incurred for an agreement we have no defined way to stamp.

This gate SHALL be independent of the payment gate rather than implied by it. Payment state
can be satisfied by a staff waiver, so a paid-or-waived agreement is not thereby a
fulfillable one; the two questions are separate and are asked separately.

The refusal SHALL use the distinct unsupported-jurisdiction error kind, so it is
distinguishable from a payment-required or stamp-required refusal at the same step.

#### Scenario: eSign initiation is refused for an ineligible jurisdiction

- **WHEN** an eSign request is initiated for an agreement whose duty jurisdiction is not
  eligible
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** no call is made to the eSign provider
- **AND** no signing status transition occurs

#### Scenario: A waived payment does not satisfy the jurisdiction requirement

- **WHEN** an eSign request is initiated for an agreement whose payment has been waived and
  whose duty jurisdiction is not eligible
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** no call is made to the eSign provider

#### Scenario: eSign initiation proceeds for an eligible jurisdiction

- **WHEN** an eSign request is initiated for an agreement whose duty jurisdiction is
  eligible and which satisfies every existing precondition
- **THEN** initiation proceeds exactly as before this change
