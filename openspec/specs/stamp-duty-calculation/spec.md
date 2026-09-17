# stamp-duty-calculation Specification

## Purpose
Compute the stamp duty owed on a rental instrument from normalized agreement facts and versioned,
per-state rule data, producing an auditable breakdown and failing closed whenever no reviewed rule
can determine the amount.

## Requirements

### Requirement: Duty is computed from state-agnostic agreement facts

The calculator SHALL accept one normalized set of agreement facts that is the same for every state:
duty state, instrument kind, usage, execution date, term, rent schedule (monthly rent, escalation
percentage and interval, rent-free months), refundable and non-refundable deposit, advance rent,
premium, and number of counterparts.

The duty state SHALL be the state in which the property lies, independent of the state dimension of
any template the agreement was drafted from. All monetary inputs SHALL be exact decimal amounts in
Indian rupees; the calculator SHALL NOT use binary floating point for money.

#### Scenario: The same facts shape serves any state

- **GIVEN** two agreements identical except for their duty state
- **WHEN** each is quoted
- **THEN** both are evaluated from the same set of fact fields
- **AND** only the selected rule differs

#### Scenario: A negative or inconsistent input is rejected

- **GIVEN** facts with a negative rent, a non-positive term, or rent-free months exceeding the term
- **WHEN** the facts are constructed
- **THEN** construction fails with a validation error naming the field
- **AND** no quote is produced

### Requirement: Every quote resolves to exactly one of three outcomes

The calculator SHALL return exactly one outcome: **Quoted** (the legal duty amount in paise, a
breakdown, the rule reference, whether registration is required, and a stamp plan for each stamp
medium the duty state offers), **NeedsAdjudication** (a reason and the rule reference), or
**Unsupported** (a reason).

The calculator SHALL NOT return a zero or default amount in place of NeedsAdjudication or
Unsupported. A caller that receives anything other than Quoted has no computable duty.

#### Scenario: A computable agreement is quoted

- **GIVEN** a rule in effect for the agreement's state, instrument kind and usage on its execution date
- **WHEN** the agreement is quoted
- **THEN** the outcome is Quoted with a non-negative amount in paise

#### Scenario: A rule refuses a case it cannot compute

- **GIVEN** a rule whose extension declares a fact pattern (for example, variable rent) non-computable
- **WHEN** an agreement matching that pattern is quoted
- **THEN** the outcome is NeedsAdjudication carrying the reason and the rule reference
- **AND** no amount is present

### Requirement: Calculation fails closed when no rule applies

The calculator SHALL return Unsupported when no rule is in effect for the duty state, instrument kind
and usage on the execution date. It SHALL return Unsupported for the national dimension (`IN`)
regardless of any rule data present, because there is no national stamp-duty rate.

An abstract rule (one that declares no term slabs of its own) SHALL NOT be selectable for a quote.

#### Scenario: Unknown state

- **GIVEN** no rule exists for duty state `XX`
- **WHEN** an agreement with duty state `XX` is quoted
- **THEN** the outcome is Unsupported

#### Scenario: National dimension

- **GIVEN** any rule data, including a file that claims state `IN`
- **WHEN** an agreement with duty state `IN` is quoted
- **THEN** the outcome is Unsupported

#### Scenario: Execution date outside every effective window

- **GIVEN** a rule for the state effective only from 2025-01-01
- **WHEN** an agreement executed on 2024-12-31 is quoted
- **THEN** the outcome is Unsupported

### Requirement: The rule is selected by execution date and never by quote date

The calculator SHALL select the rule whose effective window contains the agreement's execution date.
Effective windows for the same state, instrument kind and usage SHALL NOT overlap. Quoting the same
facts against the same rule data SHALL always produce the same outcome, regardless of when the quote
is requested.

#### Scenario: A rate change does not alter an earlier agreement

- **GIVEN** rule version A effective until 2025-03-31 and version B effective from 2025-04-01
- **WHEN** an agreement executed on 2025-03-15 is quoted on 2025-06-01
- **THEN** the quote references version A

### Requirement: Rent quantities are derived from the full rent schedule

The calculator SHALL derive total rent for the term and average annual rent from the month-by-month
rent schedule, applying escalation at each configured interval and excluding rent-free months. It
SHALL NOT approximate either quantity as monthly rent multiplied by twelve. Average annual rent SHALL
equal total rent multiplied by twelve and divided by the term in months.

#### Scenario: Escalation is reflected in total rent

- **GIVEN** monthly rent 10,000.00, a 24-month term and 10 percent escalation every 12 months
- **WHEN** quantities are derived
- **THEN** total rent is 252,000.00
- **AND** average annual rent is 126,000.00

#### Scenario: Rent-free months are excluded

- **GIVEN** monthly rent 10,000.00, an 11-month term and 1 rent-free month
- **WHEN** quantities are derived
- **THEN** total rent is 100,000.00

### Requirement: Duty follows one fixed calculation order

For a selected rule the calculator SHALL apply, in this order: choose the term slab; sum that slab's
named quantities into the consideration; apply the slab's rate or fixed amount; apply the rule's
minimum and maximum; add surcharges computed on the bounded duty; add counterpart duty; and round
once, as the final step, using the rule's rounding mode and unit. No intermediate result SHALL be
rounded to a currency unit.

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

### Requirement: The term slab is selected by term length

Each rule SHALL declare term slabs that together cover every term length the rule accepts, with no
gaps and no overlaps. The calculator SHALL select the single slab containing the agreement's term,
with boundaries inclusive as declared.

#### Scenario: Boundary term selects the declared slab

- **GIVEN** slabs "up to 12 months" and "13 to 60 months"
- **WHEN** agreements with terms of 12 and 13 months are quoted
- **THEN** the 12-month agreement uses the first slab and the 13-month agreement uses the second

#### Scenario: Term beyond every slab

- **GIVEN** slabs covering at most 60 months
- **WHEN** a 72-month agreement is quoted
- **THEN** the outcome is Unsupported

### Requirement: Every quote carries an auditable breakdown and rule reference

A Quoted outcome SHALL include an ordered breakdown with one line per contributing step (each
quantity in the consideration, the rate or fixed amount, any minimum or maximum applied, each
surcharge, counterpart duty, and rounding), such that the lines reproduce the quoted amount. It SHALL
include the rule's id, legal reference, and a content hash that changes whenever the rule's
computational content changes.

#### Scenario: Breakdown reconciles to the amount

- **WHEN** any agreement is Quoted
- **THEN** replaying the breakdown lines in order yields the quoted amount

#### Scenario: Content hash tracks the rule

- **GIVEN** two rule files differing only in a slab rate
- **WHEN** both are loaded
- **THEN** their content hashes differ

### Requirement: Registration requirement is reported separately from duty

A Quoted outcome SHALL state whether the instrument requires registration, derived from the rule's
registration threshold. The registration requirement SHALL NOT change the duty amount, and
registration fees SHALL NOT be included in it.

#### Scenario: Term above the registration threshold

- **GIVEN** a rule requiring registration when the term exceeds 11 months
- **WHEN** a 12-month agreement is quoted
- **THEN** the outcome reports registration required
- **AND** the amount equals the quote for the same agreement under an otherwise identical rule with
  no registration threshold

### Requirement: Rules are declarative data layered on an abstract base

Stamp duty rules SHALL be defined as data, one rule per state, instrument kind, usage and effective
window, each extending a shared abstract base that supplies defaults (rounding, registration
threshold, counterpart duty, surcharges) and declares no rates. A state rule MAY override any base
default. Adding or changing a state's rates SHALL NOT require a code change unless the rule uses a
state extension.

#### Scenario: A state inherits base rounding

- **GIVEN** a base rounding mode and a state rule that does not declare rounding
- **WHEN** an agreement is quoted under the state rule
- **THEN** the base rounding mode is applied

### Requirement: A state extension may refuse, add a quantity, or adjust duty

The calculator SHALL allow a named extension, bound to one rule id, to: refuse a fact pattern with
NeedsAdjudication or Unsupported before calculation; contribute additional named quantities usable
in that rule's slabs; and adjust the bounded duty. An extension SHALL NOT alter the calculation
order, and any adjustment SHALL appear as a breakdown line.

#### Scenario: Extension-provided quantity is usable in slabs

- **GIVEN** an extension contributing quantity `EXCESS_DEPOSIT` and a slab summing it
- **WHEN** an agreement is quoted
- **THEN** the consideration includes `EXCESS_DEPOSIT`
- **AND** the breakdown shows it as a line

### Requirement: Malformed rule data fails at startup

The application SHALL refuse to start when any rule is malformed, naming the rule and the defect.
Malformed includes: slab gaps or overlaps; a slab referencing a quantity that neither the standard
set nor the rule's extension provides; a blank legal reference; overlapping effective windows for the
same state, instrument kind and usage; an extension bound to a rule id that does not exist; and a
non-abstract rule with no slabs.

#### Scenario: Slab gap

- **GIVEN** a rule with slabs "up to 11 months" and "13 to 60 months"
- **WHEN** the application starts
- **THEN** startup fails naming the rule and the 12-month gap

#### Scenario: Unknown quantity

- **GIVEN** a slab summing `TOTAL_RNT`
- **WHEN** the application starts
- **THEN** startup fails naming the rule and the unknown quantity

### Requirement: Each rule ships with worked cases that are verified in the build

Each non-abstract rule SHALL declare worked cases (facts and expected outcome, including expected
amount for Quoted cases), including at least one case at each slab boundary. The build SHALL evaluate
every declared case and fail when any outcome differs from its expectation.

#### Scenario: A wrong expectation fails the build

- **GIVEN** a rule case expecting 10,000 paise where the rule computes 12,000 paise
- **WHEN** the test suite runs
- **THEN** the suite fails naming the rule and case

### Requirement: Each state declares the stamp paper media it issues

The system SHALL maintain, per duty state and effective window, a stamp paper catalog of one or more
stamp media. Each medium SHALL have an identifier and be exactly one of: **denominations** (the
issued face values and the maximum number of papers one instrument may carry), or **any amount** (an
exact-amount certificate with a minimum face value). The catalog SHALL be selected by the agreement's
execution date and SHALL carry a content hash reported with every quote that uses it.

A duty state with a rule but no catalog in effect SHALL be treated as having no computable payable
value: the calculator SHALL return Unsupported. The application SHALL refuse to start when a catalog
declares no media, a denominations medium with no values, a non-positive denomination or paper
limit, or overlapping effective windows for the same state.

#### Scenario: Catalog missing for a state with a rule

- **GIVEN** a duty rule for state `ZZ` and no stamp paper catalog for `ZZ` on the execution date
- **WHEN** an agreement with duty state `ZZ` is quoted
- **THEN** the outcome is Unsupported

### Requirement: Payable stamp value is the smallest achievable value at or above the duty

For each medium in the catalog, a Quoted outcome SHALL include a stamp plan. For a denominations
medium the plan SHALL be the multiset of issued denominations, of at most the medium's paper limit,
whose total is the smallest achievable total greater than or equal to the legal duty; among equal
totals it SHALL choose the fewest papers, then the combination with the larger denominations first.
For an any-amount medium the plan SHALL be a single certificate of the legal duty or the medium's
minimum, whichever is greater. Each plan SHALL state its papers, total and the excess of total over
legal duty.

The plan total SHALL NOT be below the legal duty. The legal duty amount SHALL NOT be changed by
planning; the payable value and the legal duty are reported separately.

#### Scenario: Exact denomination available

- **GIVEN** denominations 10, 20, 50, 100, 500, 1000 with a paper limit of 3
- **WHEN** the legal duty is 100.00
- **THEN** the plan is one 100 paper with total 100.00 and excess 0.00

#### Scenario: Duty between denominations rounds up to the nearest achievable value

- **GIVEN** denominations 10, 20, 50, 100, 500, 1000 with a paper limit of 3
- **WHEN** the legal duty is 660.00
- **THEN** the plan is 500 + 100 + 100 with total 700.00 and excess 40.00

#### Scenario: A tighter paper limit raises the payable value

- **GIVEN** denominations 10, 20, 50, 100, 500, 1000 with a paper limit of 1
- **WHEN** the legal duty is 660.00
- **THEN** the plan is one 1000 paper with total 1000.00 and excess 340.00

#### Scenario: Equal totals prefer fewer papers

- **GIVEN** denominations 500 and 1000 with a paper limit of 2
- **WHEN** the legal duty is 900.00
- **THEN** the plan is one 1000 paper, not 500 + 500

#### Scenario: Any-amount medium below its minimum

- **GIVEN** an any-amount medium with minimum 10.00
- **WHEN** the legal duty is 5.00
- **THEN** the plan is one certificate of 10.00 with excess 5.00

### Requirement: A medium that cannot reach the duty is unplannable

When no combination within a denominations medium's paper limit reaches the legal duty, the plan for
that medium SHALL be reported as unplannable with a reason. It SHALL NOT offer a combination below
the duty. Other media in the same quote SHALL still be planned.

#### Scenario: Duty above the medium's reach

- **GIVEN** denominations up to 1000 with a paper limit of 2 and an any-amount medium
- **WHEN** the legal duty is 2,500.00
- **THEN** the denominations plan is unplannable
- **AND** the any-amount plan is one certificate of 2,500.00

### Requirement: The calculator handles no personal data

The calculator's inputs SHALL be limited to monetary amounts, dates, term lengths, a state code and
enumerated classifiers. It SHALL NOT accept or log party names, contact details, addresses, or
identity numbers, and SHALL NOT make outbound network calls.

#### Scenario: Logging on a quote

- **WHEN** an agreement is quoted
- **THEN** any log line identifies the rule id and outcome type only
