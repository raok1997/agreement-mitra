## MODIFIED Requirements

### Requirement: The amount is computed by the server and never accepted from the client

The payable amount SHALL be produced by a **server-side calculation** for the agreement. The
system SHALL NOT accept an amount, currency, or discount from the client on any request, and
SHALL ignore such a value if supplied.

The calculation SHALL be a **per-agreement, per-jurisdiction line-item quote**, not a flat configured
price. It SHALL compose the total from the agreement's selected stamp denomination and the charges
configured for its duty jurisdiction, so that a change to a state's rates or fees changes what is
charged in that state with no other code change.

Money SHALL be represented in **minor units (paise) as an integer** everywhere it is
calculated, stored, transmitted, or compared. Floating-point types SHALL NOT be used for
monetary values at any point. The currency SHALL be recorded explicitly alongside every amount.
Every line item SHALL be an integer minor-unit amount, and the total SHALL equal the exact sum of the
line items with no rounding drift.

#### Scenario: A client-supplied amount is ignored

- **WHEN** a create-order request carries an amount, currency, or discount field
- **THEN** the value is ignored and the order is created for the server-calculated amount

#### Scenario: Amount is computed through the pricing operation

- **WHEN** an order is created for an agreement
- **THEN** the amount comes from the quote calculation for that agreement's jurisdiction and
  selected denomination, and changing the configured per-state charges changes the amount charged
  with no other code change

#### Scenario: Two states priced differently for identical terms

- **GIVEN** two agreements with identical rent, deposit and term in different supported jurisdictions
- **WHEN** each is quoted
- **THEN** each total reflects its own jurisdiction's duty and configured charges

#### Scenario: Money is integer minor units

- **WHEN** an amount is calculated, persisted, or sent to the provider
- **THEN** it is an integer number of minor units with an explicit currency, and no
  floating-point representation is used

#### Scenario: The total is the exact sum of its line items

- **WHEN** a quote is produced
- **THEN** the total equals the arithmetic sum of its line items in minor units, with no rounding
  discrepancy

## ADDED Requirements

### Requirement: The quote is a line-item breakdown, configurable per state

A quote SHALL be composed of named line items:

- **stamp duty** - the customer's selected denomination, a **pass-through** collected on the
  customer's behalf and remitted as duty, not revenue for the service;
- **service fee** - the platform's own charge, configurable per jurisdiction and per template type;
- **procurement fee** - the cost of obtaining the certificate in that jurisdiction;
- **delivery fee** - configurable per jurisdiction, and zero where no physical instrument moves;
- **tax** - see the tax requirement below.

Each line item SHALL be individually visible to the customer before payment, with the stamp duty
identified as a statutory pass-through rather than a charge for the service.

Charges SHALL be configuration per jurisdiction. Onboarding a state's charges SHALL require no
application-code change.

#### Scenario: The customer sees what they are paying for

- **WHEN** a customer reaches checkout
- **THEN** they see each line item separately with its amount
- **AND** the stamp duty line is identified as a statutory pass-through

#### Scenario: A state's charges are configuration

- **WHEN** a jurisdiction's service, procurement and delivery fees are configured
- **THEN** quotes for that jurisdiction reflect them with no application-code change

#### Scenario: Delivery is zero where nothing ships

- **GIVEN** a jurisdiction configured with no physical delivery
- **WHEN** a quote is produced
- **THEN** its delivery line item is zero

### Requirement: Tax applies to the service components, not to the duty pass-through

Tax SHALL be computed on the **service, procurement and delivery** line items only. The **stamp duty
pass-through SHALL be excluded from the taxable value**: duty is a statutory levy collected on the
customer's behalf, not consideration for a service.

The tax rate SHALL be configuration per jurisdiction, not a constant in code.

#### Scenario: Duty is outside the taxable value

- **WHEN** a quote is produced
- **THEN** the tax line is computed on the service, procurement and delivery items only
- **AND** the stamp duty amount is not part of the taxable value

#### Scenario: The tax rate is configurable

- **WHEN** a jurisdiction's tax rate is configured
- **THEN** quotes for that jurisdiction apply it with no application-code change

### Requirement: The quote is frozen when the order is created

At order creation the system SHALL persist the **complete line-item breakdown** and the **selected
stamp denomination** alongside the order amount.

A later change to a jurisdiction's rates, fees, tax rate, or denomination master SHALL NOT alter what
a previously created order shows or what was charged. Re-reading an existing order SHALL return the
frozen breakdown, never a recomputed one.

#### Scenario: A rate change does not rewrite a placed order

- **GIVEN** an order created against a jurisdiction's configured charges
- **WHEN** those charges are subsequently changed
- **THEN** the order still shows and still charged the frozen breakdown from creation time

#### Scenario: The breakdown persists with the order

- **WHEN** an order is created
- **THEN** its stored record carries every line item and the selected denomination, not only the
  total

### Requirement: Duty and fees are collected in a single order

The stamp duty, the service and procurement fees, the delivery fee, and the tax SHALL be collected in
**one payment order** through the existing payment flow. The system SHALL NOT create a second payment
surface, a second order, or a separate duty collection step.

#### Scenario: One order covers the whole quote

- **WHEN** a customer pays for an agreement
- **THEN** a single order is created for the quote total, and no separate duty payment is requested

### Requirement: An agreement without a resolved quote cannot be paid for

Order creation SHALL require a supported duty jurisdiction and a recorded stamp denomination for the
agreement. Where either is absent - an unsupported or unconfigured jurisdiction, or no denomination
selected - order creation SHALL be refused.

The system SHALL NOT substitute a default price, a previous flat amount, or another jurisdiction's
charges to let checkout proceed.

#### Scenario: No jurisdiction, no order

- **WHEN** order creation is attempted for an agreement whose duty jurisdiction is unsupported
- **THEN** the request is refused and no order is created

#### Scenario: No denomination, no order

- **WHEN** order creation is attempted for an agreement with no recorded stamp denomination
- **THEN** the request is refused and no order is created
- **AND** no default amount is substituted
