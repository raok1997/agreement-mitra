## ADDED Requirements

### Requirement: A stamp certificate is accepted only for an eligible duty jurisdiction

Stamp intake SHALL refuse an agreement whose duty jurisdiction is not eligible for paid
fulfilment, **before the uploaded certificate is stored or attached**. Stamp duty is state
law, so an agreement without an eligible duty jurisdiction has no state whose duty could
have been paid and no defined place the certificate could have been bought.

This gate SHALL be independent of the payment gate rather than implied by it. The payment
state can be satisfied by a staff waiver, so a paid-or-waived agreement is not thereby a
fulfillable one. Staff acting deliberately is a different threat model from a customer
driving the public flow, but it warrants a different response rather than none: the outcome
being prevented is an undefined real-world purchase, not an unpaid one.

The refusal SHALL use the distinct unsupported-jurisdiction error kind, so staff can tell it
apart from a payment-required, already-attached or certificate-reused refusal at the same
step.

#### Scenario: Stamp intake is refused for an ineligible jurisdiction

- **WHEN** staff submit an e-stamp certificate for an agreement whose duty jurisdiction is
  not eligible
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** no certificate blob is stored
- **AND** no stamp is attached to the agreement

#### Scenario: A waived payment does not satisfy the jurisdiction requirement

- **WHEN** staff submit an e-stamp certificate for an agreement whose payment has been
  waived and whose duty jurisdiction is not eligible
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** no stamp is attached to the agreement

#### Scenario: Stamp intake proceeds for an eligible jurisdiction

- **WHEN** staff submit an e-stamp certificate for an agreement whose duty jurisdiction is
  eligible and which satisfies every existing precondition
- **THEN** intake proceeds exactly as before this change
