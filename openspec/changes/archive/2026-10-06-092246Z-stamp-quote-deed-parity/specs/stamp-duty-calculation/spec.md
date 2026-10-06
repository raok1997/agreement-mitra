## MODIFIED Requirements

### Requirement: Every quote carries an auditable breakdown and rule reference

A Quoted outcome SHALL include an ordered breakdown with one line per contributing step (each
quantity in the consideration, the rate or fixed amount, any minimum or maximum applied, each
surcharge, counterpart duty, and rounding), such that the lines reproduce the quoted amount. It SHALL
include the rule's id, legal reference, and a content hash that changes whenever the rule's
computational content changes.

When the basis escalates rent and escalation raised the consideration, the breakdown SHALL also
carry one informational **escalation** line, placed after the quantity lines and before the base
line. Its label SHALL read `<p>% rent escalation every <n> months`, with the percentage in plain
notation, and its amount SHALL be the consideration computed with escalation minus the consideration
computed from the same basis without escalation -- including any quantity a state extension adds --
rounded half-up to two decimal places. The escalation line is neither a quantity nor a delta: it SHALL NOT take part
in the replay. When escalation does not raise the consideration -- no escalation, a term that ends
before the first escalation, a fixed-amount slab, or a consideration with no rent quantity -- no
escalation line SHALL be emitted.

#### Scenario: Breakdown reconciles to the amount

- **WHEN** any agreement is Quoted
- **THEN** replaying the breakdown lines in order, skipping escalation lines, yields the quoted
  amount

#### Scenario: Content hash tracks the rule

- **GIVEN** two rule files differing only in a slab rate
- **WHEN** both are loaded
- **THEN** their content hashes differ

#### Scenario: Escalation that raised the consideration is itemised

- **GIVEN** a basis of 24 months at INR 10 per month with 5% escalation every 12 months, quoted under
  a slab whose consideration is `AVERAGE_ANNUAL_RENT`
- **WHEN** it is Quoted
- **THEN** the breakdown has an `AVERAGE_ANNUAL_RENT` quantity of INR 123
- **AND** an escalation line labelled `5% rent escalation every 12 months` with an amount of INR
  3.00, between the quantity and the base line

#### Scenario: A duty too large to represent is unsupported, not an error

- **GIVEN** a basis of 3,599 months at the largest accepted monthly rent with 100% escalation every
  12 months, under a slab with no maximum
- **WHEN** it is quoted
- **THEN** the outcome is Unsupported and no exception escapes the calculator

#### Scenario: Escalation that changed nothing is not itemised

- **GIVEN** a basis of 11 months with 5% escalation every 12 months
- **WHEN** it is Quoted
- **THEN** the breakdown has no escalation line
