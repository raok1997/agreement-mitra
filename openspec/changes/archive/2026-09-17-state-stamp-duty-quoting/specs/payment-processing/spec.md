## ADDED Requirements

### Requirement: The payable total follows the published pricing rule

The system SHALL compute the payable total as the base fee plus the amount, if any, by which the chosen stamp value exceeds the included stamp value.

With the published terms that is INR 499 + max(0, chosen stamp value - INR 100). Both figures SHALL be
configuration in integer minor units. The total SHALL be computed on the server from the recomputed
stamp options; the client SHALL supply only its chosen option and any acknowledgement, never an
amount.

#### Scenario: Stamp value within the included amount

- **WHEN** checkout is started with a chosen stamp value of INR 100
- **THEN** the order amount is INR 499

#### Scenario: Stamp value above the included amount

- **WHEN** checkout is started with a chosen stamp value of INR 840
- **THEN** the order amount is INR 1,239

#### Scenario: A tampered amount has no effect

- **WHEN** a checkout request carries an extra total or fee field
- **THEN** the order amount is computed from the chosen option alone

### Requirement: The stamp quote is frozen with the payment order

The system SHALL persist with each payment order the legal duty, the chosen stamp value, whether it is below the legal duty, the breakdown, the rule identity and content hash, the catalog content hash, the execution date and the registration flag used to compute its amount.

The frozen quote SHALL be written in the same transaction as the order and SHALL NOT be changed
afterwards. The checkout and payment progress responses SHALL report the frozen legal duty and stamp
value.

#### Scenario: A later rule change does not alter a placed order

- **GIVEN** an order whose frozen legal duty is INR 840
- **WHEN** the Telangana rule's rate changes and the agreement's payment progress is read
- **THEN** the reported legal duty is still INR 840

### Requirement: An agreement without a valid stamp choice cannot be paid for

The system SHALL refuse to create a payment order unless the agreement's duty outcome is Quoted, the chosen stamp value is one of the recomputed options, and any required acknowledgement is present.

A refusal for an unquotable agreement SHALL use the unsupported-jurisdiction error kind; a refusal for
a missing or invalid choice SHALL use a validation error. In neither case SHALL the payment provider be
called.

#### Scenario: No choice supplied

- **WHEN** checkout is started without a stamp choice for an agreement with no existing order
- **THEN** the response is a validation error
- **AND** no call is made to the payment provider

## MODIFIED Requirements

### Requirement: The agreement's duty jurisdiction is eligible before an order is created

Checkout SHALL refuse to place a payment order, or to resume an **outstanding** one, for an
agreement whose duty jurisdiction is not eligible for paid fulfilment. The check SHALL run
**before any order is created or resumed**, so no customer is ever charged for an agreement
we have no defined way to stamp, and no call is made to the payment provider.

The check SHALL be applied on **every** call that would create or resume an outstanding
order, not only the first: an order placed earlier proves eligibility as it stood then, not
as it stands now, and the duty rules, their review status and the agreement's terms can change
between the two.

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
