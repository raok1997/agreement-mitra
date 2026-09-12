## ADDED Requirements

### Requirement: The agreement's duty jurisdiction is eligible before an order is created

Checkout SHALL refuse to place a payment order, or to resume an **outstanding** one, for an
agreement whose duty jurisdiction is not eligible for paid fulfilment. The check SHALL run
**before any order is created or resumed**, so no customer is ever charged for an agreement
we have no defined way to stamp, and no call is made to the payment provider.

The check SHALL be applied on **every** call that would create or resume an outstanding
order, not only the first: an order placed earlier proves eligibility as it stood then, not
as it stands now, and the configured allowlist can change between the two.

A **settled** order SHALL continue to be reported rather than refused. A customer who has
already paid must be shown that they have paid, whatever the jurisdiction rule now says —
refusing there would deny a completed payment and recreate the very hazard this rule exists
to prevent.

The refusal SHALL use the distinct unsupported-jurisdiction error kind, not the
payment-required kind.

#### Scenario: Checkout is refused for an ineligible jurisdiction

- **WHEN** checkout is started for an agreement whose duty jurisdiction is not eligible
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** no payment order is created
- **AND** no call is made to the payment provider

#### Scenario: An outstanding order is not resumed for a jurisdiction that is no longer eligible

- **WHEN** checkout is started for an agreement that has an outstanding, unsettled order and
  whose duty jurisdiction is not eligible
- **THEN** the response is `409` with the unsupported-jurisdiction problem type
- **AND** the outstanding order is not returned to the caller

#### Scenario: A settled order is still reported for an ineligible jurisdiction

- **WHEN** checkout is started for an agreement that has already been paid and whose duty
  jurisdiction is not eligible
- **THEN** the settled order is reported to the caller as it was before this change
- **AND** the response is not an unsupported-jurisdiction refusal

#### Scenario: Checkout proceeds for an eligible jurisdiction

- **WHEN** checkout is started for an agreement whose duty jurisdiction is eligible and
  whose parties are reachable
- **THEN** checkout proceeds exactly as before this change
