## ADDED Requirements

### Requirement: A slab may bound the duty it computes

A term slab SHALL be able to declare its own minimum and maximum, bounding only the duty computed
from that slab. A slab bound SHALL NOT apply to any other slab of the same rule.

This exists because a state's rate table can cap one band and leave the rest open -- expressing such
a cap as the rule's maximum would silently bound every other band of the same rule, understating the
duty on a longer term. A slab bound SHALL therefore be declared on the slab, never inferred from the
rule.

A slab bound SHALL be rejected at startup, with the rule, if it is negative, if its minimum exceeds
its maximum, or if it is declared on a slab that has no computed consideration to bound.

Slab bounds SHALL be reported in the quote's breakdown as their own line, distinguishable from the
rule-level minimum and maximum, so that a customer or auditor reading a capped quote can see which
bound reduced it.

#### Scenario: A slab maximum bounds its own slab

- **GIVEN** a rule whose first slab is 0.5 percent with a slab maximum of 500.00
- **WHEN** an agreement falling in that slab computes a duty of 1,700.00
- **THEN** the quoted duty is 500.00
- **AND** the breakdown carries a line attributing the reduction to the slab maximum

#### Scenario: A slab maximum does not bound a neighbouring slab

- **GIVEN** the same rule, whose second slab is 1 percent and declares no maximum
- **WHEN** an agreement falling in the second slab computes a duty of 600.00
- **THEN** the quoted duty is 600.00

#### Scenario: A malformed slab bound fails at startup

- **GIVEN** a rule file declaring a negative slab maximum, or a slab minimum above its slab maximum
- **WHEN** the rules are loaded
- **THEN** loading fails, naming the offending rule

## MODIFIED Requirements

### Requirement: Duty follows one fixed calculation order

For a selected rule the calculator SHALL apply, in this order: choose the term slab; sum that slab's
named quantities into the consideration; apply the slab's rate or fixed amount; apply that slab's own
minimum and maximum; apply the rule's minimum and maximum; add surcharges computed on the bounded
duty; add counterpart duty; and round once, as the final step, using the rule's rounding mode and
unit. No intermediate result SHALL be rounded to a currency unit.

Slab bounds SHALL be applied before rule bounds, so that a rule-level bound remains the outer bound
on the quoted duty however the slab bounded it.

#### Scenario: Minimum applies before rounding

- **GIVEN** a slab at 0.4 percent, a minimum of 100.00 and rounding up to the whole rupee
- **WHEN** the consideration is 20,000.00
- **THEN** the computed duty 80.00 is raised to the minimum 100.00
- **AND** the quoted amount is 10,000 paise

#### Scenario: Surcharge is computed on bounded duty

- **GIVEN** a slab duty of 5,000.00, a maximum of 2,000.00 and a 10 percent surcharge
- **WHEN** the agreement is quoted
- **THEN** the quoted amount is 220,000 paise

#### Scenario: Rounding happens once

- **GIVEN** a rule rounding up to the whole rupee and a computed duty of 100.004 after surcharge
- **WHEN** the agreement is quoted
- **THEN** the quoted amount is 10,100 paise

#### Scenario: Slab bounds apply before rule bounds

- **GIVEN** a slab maximum of 500.00 and a rule minimum of 600.00
- **WHEN** a slab computes a duty of 1,700.00
- **THEN** the slab maximum reduces it to 500.00
- **AND** the rule minimum then raises it to 600.00
