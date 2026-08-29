## Purpose

Determines, for a given state and set of commercial lease terms, how much stamp duty an agreement
statutorily attracts and which e-stamp denominations may be purchased to pay it. The rules are
configuration, not code, so a new state is onboarded by adding data rather than by shipping a release.

## ADDED Requirements

### Requirement: Assess stamp duty for a jurisdiction and lease terms

The system SHALL expose a duty-assessment operation that takes a **duty jurisdiction** (a state
code), a **property use** (residential, commercial, or industrial), a **term in months**, a
**monthly rent**, a **security deposit**, and optionally a **premium**, a **fine**, and a
**special-case qualifier**, and returns an assessment carrying:

- the **statutory duty** for those terms;
- a human-readable **basis** stating the rate, the components of the base, any cap or floor
  applied, and the statutory citation the rule came from;
- whether **registration is compulsory** at that term, and the registration fee basis where one
  applies;
- the **denominations** purchasable in that jurisdiction;
- a **supported** indicator.

The assessment SHALL depend only on commercial terms and the jurisdiction. It SHALL NOT accept, read,
or return any party name, contact detail, address, Aadhaar number, virtual ID, or other signer PII.

Monetary values SHALL be integer minor units with an explicit currency at every point they are
calculated, returned, stored, or compared. Floating-point types SHALL NOT be used for money.

#### Scenario: An assessment states its own basis

- **WHEN** duty is assessed for a supported jurisdiction and set of terms
- **THEN** the result carries the statutory duty, a basis naming the rate, base components, any cap
  or floor applied, and the statutory citation
- **AND** the amount is an integer number of minor units with an explicit currency

#### Scenario: The assessment carries no party data

- **WHEN** a duty assessment is requested
- **THEN** the request and the result carry no party name, contact detail, address, or identity
  number, and none appears in any log line on that path

### Requirement: Duty rules are configuration, not code

A jurisdiction's duty rules SHALL be expressed as **data** that the system reads at runtime.
Onboarding a new state SHALL require no change to application code, no new class, and no
recompilation - only the addition of configuration.

The configuration SHALL be able to express, per jurisdiction:

- a set of **slabs**, each selected by a property use and a term range;
- a **rate** as a percentage of a computed base;
- the **base composition** - whether the base is built from the **average annual rent** or the
  **total rent over the whole term** - and which components are included in it (rent, money
  advanced / security deposit, premium, fine);
- an optional **maximum** (cap) and an optional **minimum** (floor) on the resulting duty;
- optional **flat-rate overrides** selected by a special-case qualifier, which replace the slab
  calculation entirely;
- the **term threshold** above which registration becomes compulsory, and the registration fee
  basis.

Slabs SHALL be non-overlapping within a property use, and the term ranges SHALL be contiguous so no
term falls between two slabs. Configuration that overlaps, leaves a gap, or names an unknown base
composition SHALL be rejected at load time rather than producing a wrong duty at request time.

#### Scenario: A new jurisdiction is added by configuration alone

- **WHEN** a jurisdiction's slabs, rates, base composition, cap, floor and registration threshold are
  added as configuration
- **THEN** duty is assessable for that jurisdiction with no application-code change

#### Scenario: Invalid configuration fails at load

- **WHEN** a jurisdiction's configuration contains overlapping term ranges, a gap between term
  ranges, or an unrecognised base composition
- **THEN** loading fails with a diagnostic naming the jurisdiction and the offending slab
- **AND** no assessment is served from that configuration

### Requirement: Karnataka duty rules

Karnataka SHALL be configured per the Karnataka Stamp Act 1957, Article 30, with the base built from
the **average annual rent** plus premium, fine, and money advanced (which includes the security
deposit):

- term of one year or less, **residential**: 0.5% of the base, **capped at Rs. 500**;
- term of one year or less, **commercial or industrial**: 0.5% of the base, **no cap**;
- term over one year up to ten years: 1% of the base;
- term over ten years up to twenty years: 2% of the base;
- term over twenty years up to thirty years: 3% of the base;
- term over thirty years or in perpetuity: assessed as a conveyance on the base or on the property's
  market value, whichever is higher.

A **family lease** (to a spouse, parent, child, or sibling) SHALL attract a flat duty regardless of
term or rent: Rs. 5,000 within BBMP or BMRDA limits, Rs. 3,000 within a City or Town Municipal Council
or Town Panchayat, and Rs. 1,000 elsewhere.

Registration SHALL be marked compulsory for a term exceeding one year (Registration Act 1908, section
17(1)(d)), at a registration fee of 2% (raised from 1% by Notification RD/46/MNMU/2025 with effect
from 31 August 2025).

#### Scenario: A capped Karnataka residential lease

- **WHEN** duty is assessed for a Karnataka residential lease of eleven months at Rs. 25,000 per
  month with a Rs. 75,000 deposit
- **THEN** the statutory duty is Rs. 500, the cap having been applied to a 0.5% calculation that
  would otherwise yield more

#### Scenario: An uncapped Karnataka commercial lease

- **WHEN** duty is assessed for a Karnataka commercial lease of eleven months at Rs. 80,000 per month
  with a Rs. 8,00,000 deposit
- **THEN** the duty is 0.5% of the base with no cap applied

#### Scenario: A Karnataka family lease takes the flat rate

- **WHEN** duty is assessed for a Karnataka lease qualified as a family lease within BBMP limits
- **THEN** the duty is Rs. 5,000 regardless of the rent, deposit, and term

#### Scenario: Karnataka registration threshold

- **WHEN** duty is assessed for a Karnataka lease of a term exceeding one year
- **THEN** the assessment reports registration as compulsory at a 2% fee basis

### Requirement: Telangana duty rules

Telangana SHALL be configured at 0.4% of a base built from the **aggregate rent over the entire lease
term** plus the advance or security deposit, with **no cap**.

Registration SHALL be marked optional below twelve months and compulsory at twelve months and above,
at a 0.2% registration fee. Where a sub-twelve-month residential lease is registered by choice, the
registration fee basis SHALL be reported as a flat Rs. 1,000.

Telangana's rules deliberately differ in shape from Karnataka's - uncapped rather than capped, and
total-term-rent based rather than average-annual-rent based - and both SHALL be expressible in the
same configuration model with no jurisdiction-specific code.

#### Scenario: Telangana duty scales with rent

- **WHEN** duty is assessed for a Telangana lease of eleven months at Rs. 25,000 per month with a
  Rs. 75,000 deposit
- **THEN** the duty is 0.4% of (eleven months of rent plus the deposit), with no cap applied

#### Scenario: Two jurisdictions of different shape share one model

- **WHEN** Karnataka and Telangana are both assessed for identical lease terms
- **THEN** each returns the duty its own configuration prescribes, and neither jurisdiction's rules
  are expressed in code specific to it

### Requirement: Seeded rates carry their citation and a verification marker

Every seeded jurisdiction rule SHALL record the **statutory citation** it derives from and an
explicit marker that the rate is **pending verification by counsel**. The seeded rates are drawn from
the statutory schedules and secondary sources, not from a legal opinion, and the system SHALL make
that provenance visible rather than presenting the figures as settled advice.

#### Scenario: A seeded rule states where it came from

- **WHEN** a seeded jurisdiction's configuration is inspected
- **THEN** each slab carries its statutory citation and a pending-verification marker

### Requirement: A jurisdiction's purchasable denominations are mastered

The system SHALL hold, per jurisdiction, the **set of e-stamp denominations actually purchasable** in
that jurisdiction. The denomination master SHALL be configuration alongside the duty rules, added for
a new state without an application-code change.

An assessment SHALL return that jurisdiction's denominations together with the **denomination that
satisfies the statutory duty** - the lowest available denomination at or above the assessed duty.
Where no available denomination reaches the assessed duty, the assessment SHALL say so explicitly
rather than silently proposing a lower one.

#### Scenario: The satisfying denomination is identified

- **GIVEN** a jurisdiction whose denominations include Rs. 100, Rs. 200 and Rs. 500
- **WHEN** duty is assessed at Rs. 500
- **THEN** the assessment returns all three denominations and identifies Rs. 500 as the one that
  satisfies the statutory duty

#### Scenario: No denomination reaches the assessed duty

- **GIVEN** an assessed duty above every denomination in the jurisdiction's master
- **WHEN** the assessment is returned
- **THEN** it explicitly reports that no single available denomination satisfies the duty
- **AND** it does not identify a lower denomination as satisfying

### Requirement: An unconfigured jurisdiction fails closed

A jurisdiction with no configured duty rules SHALL yield an assessment marked **unsupported**, with
no duty amount and no denominations. The system SHALL NOT fall back to a default rate, to another
jurisdiction's rules, or to a previously configured flat price.

The national (non-state) template dimension SHALL NOT itself be a duty jurisdiction, and SHALL NOT be
configurable as one: no stamp law is levied nationally, so a national pseudo-jurisdiction would invent
a duty no state charges.

An agreement whose duty jurisdiction is unsupported SHALL NOT be able to reach checkout.

#### Scenario: An unconfigured state yields no duty

- **WHEN** duty is assessed for a state that has no configured rules
- **THEN** the assessment is marked unsupported, carries no duty amount and no denominations
- **AND** no default or fallback rate is applied

#### Scenario: The national dimension is not itself a jurisdiction

- **WHEN** the national dimension is passed as a duty jurisdiction
- **THEN** the assessment is marked unsupported, and no national duty rules may be configured

#### Scenario: An unsupported jurisdiction cannot be paid for

- **WHEN** checkout is attempted for an agreement whose duty jurisdiction is unsupported
- **THEN** the request is refused and no payment order is created

### Requirement: The duty jurisdiction is resolved from the template, or chosen when the template is national

Where the template an agreement is pinned to carries a **state dimension**, that state SHALL be the
agreement's duty jurisdiction. It SHALL NOT be parsed from the property address: stamp duty follows
the state whose law the instrument was drafted under, and the stored address is free text.

Where the pinned template is **national**, the customer SHALL explicitly **choose the property's
state** from the jurisdictions the system supports, and that choice SHALL become the agreement's duty
jurisdiction. The choice SHALL be persisted on the agreement; it SHALL NOT be inferred from the
address, and the system SHALL NOT choose a default on the customer's behalf.

The resolved jurisdiction SHALL be fixed once a payment order exists for the agreement, so the duty
that was quoted and paid cannot be reattributed to a different state.

#### Scenario: Jurisdiction comes from the template, not the address

- **GIVEN** an agreement pinned to a template whose state dimension is Telangana
- **AND** whose property address names a different place
- **WHEN** its duty jurisdiction is resolved
- **THEN** the jurisdiction is Telangana
- **AND** the customer is not asked to choose one

#### Scenario: A national template requires an explicit state choice

- **GIVEN** an agreement pinned to a national template
- **WHEN** the customer reaches stamp selection
- **THEN** they are required to choose the property's state from the supported jurisdictions
- **AND** no jurisdiction is defaulted for them

#### Scenario: An unchosen jurisdiction blocks checkout

- **GIVEN** an agreement pinned to a national template with no state chosen
- **WHEN** checkout is attempted
- **THEN** the request is refused and no payment order is created

#### Scenario: The jurisdiction is fixed once an order exists

- **GIVEN** an agreement with a payment order already created
- **WHEN** a request attempts to change its duty jurisdiction
- **THEN** the request is refused and the recorded jurisdiction is unchanged

### Requirement: Duty rules are reached only through the module's public interface

The duty-assessment capability SHALL be exposed as a **public module interface**. No module outside
it SHALL reference its internal configuration types, slab-matching logic, or persistence. Module
boundary verification SHALL remain green.

#### Scenario: Module boundaries hold

- **WHEN** module-boundary verification runs
- **THEN** no consumer references the duty capability's internals, and the verification passes
