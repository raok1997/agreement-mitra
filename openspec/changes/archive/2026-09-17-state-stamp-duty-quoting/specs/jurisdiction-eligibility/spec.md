## REMOVED Requirements

### Requirement: Paid fulfilment requires an eligible duty jurisdiction

**Reason**: Eligibility came from a configured allowlist that was temporary by design; this change derives it from reviewed, quotable duty rules instead, so the allowlist would be a second source of eligibility truth.

**Migration**: Replaced by "Paid fulfilment requires a chargeable, quotable duty rule". Delete `jurisdiction.eligible` / `ELIGIBLE_JURISDICTIONS` from deployment configuration; admit a jurisdiction by seeding and reviewing its duty rule and stamp paper catalog.

### Requirement: The national dimension cannot be admitted by configuration

**Reason**: Its scenarios are phrased against the removed allowlist; the property it protects is kept.

**Migration**: Replaced by "The national dimension can never be a duty jurisdiction", which enforces the same property at rule load and in the calculator.

### Requirement: An unknown or unconfigured jurisdiction fails closed

**Reason**: Its empty-allowlist and allowlist-observability scenarios no longer apply once the allowlist is removed.

**Migration**: Replaced by "An unknown or unchargeable jurisdiction fails closed", which keeps the no-template and unresolvable-template refusals and makes chargeable rules observable at startup.

## ADDED Requirements

### Requirement: Paid fulfilment requires a chargeable, quotable duty rule

The system SHALL admit an agreement to paid fulfilment -- order placement, payment, e-stamp purchase and eSign initiation -- only when its duty jurisdiction has a stamp duty rule in effect that may be charged and the stamp duty calculator quotes the agreement with at least one plannable stamp option.

A rule may be charged when it carries a counsel review matching its content hash, or when unreviewed
rules are explicitly allowed by configuration. Eligibility SHALL be derived from the duty rules alone;
there SHALL be no separate list of eligible jurisdictions, so admitting a jurisdiction means seeding
and reviewing its rules and no code change.

Stamp duty is state law and there is no national rate, so the national dimension (`IN`) SHALL NOT
itself be a duty jurisdiction. This requirement constrains the agreement's **duty jurisdiction**, not
the state dimension of the template it was drafted from, so that a later capability MAY establish a
duty jurisdiction for an agreement drafted from a national template without contradicting this rule.

Order placement and checkout SHALL evaluate eligibility against the agreement's current terms. E-stamp
intake and eSign initiation for an agreement with a frozen stamp quote SHALL evaluate it against the
rule and catalog identified by that quote, so a rule change after payment does not strand a paid
order.

#### Scenario: A quotable jurisdiction with a chargeable rule proceeds

- **WHEN** an agreement's duty jurisdiction has a chargeable rule in effect and the agreement is
  quoted with a plannable stamp option
- **THEN** the eligibility check permits the agreement to proceed
- **AND** no other behaviour changes

#### Scenario: An agreement with no duty jurisdiction is refused

- **WHEN** an agreement's only jurisdiction indication is the national dimension, and no duty
  jurisdiction has been established for it
- **THEN** the eligibility check refuses the agreement

#### Scenario: Admitting a jurisdiction is a rule-data change

- **WHEN** a further state's duty rule and stamp paper catalog are added and reviewed
- **THEN** agreements in that duty jurisdiction proceed
- **AND** no source-code change was required to admit it

#### Scenario: A term the rule cannot quote is refused

- **WHEN** an agreement's duty jurisdiction has a chargeable rule but the agreement's term falls outside
  every slab
- **THEN** the eligibility check refuses the agreement

#### Scenario: A paid agreement is not stranded by a later rule change

- **GIVEN** an agreement paid for with a frozen stamp quote
- **WHEN** the jurisdiction's rule is replaced and staff attach the stamp
- **THEN** the eligibility check at intake uses the frozen quote and permits the agreement

### Requirement: The national dimension can never be a duty jurisdiction

The national dimension (`IN`) SHALL NOT be admissible as an eligible duty jurisdiction by any rule data or configuration.

A duty rule or stamp paper catalog declaring the national dimension SHALL be rejected when rules are
loaded, and the calculator SHALL refuse the national dimension regardless of loaded data. There is no
national stamp-duty rate to admit, so admitting it would re-open the hazard this capability exists to
close. This SHALL hold as a property of the rule rather than of the shipped data, so that a
well-intentioned data edit cannot restore the hazard.

#### Scenario: A national rule file is rejected

- **WHEN** a duty rule declaring the national dimension is present among the rule files
- **THEN** the application refuses to start, naming the rule

#### Scenario: The national dimension is refused even by the calculator

- **WHEN** an agreement whose duty jurisdiction resolves to the national dimension is checked
- **THEN** it is refused

### Requirement: An unknown or unchargeable jurisdiction fails closed

The check SHALL refuse whenever the agreement's duty jurisdiction cannot be established or has no chargeable rule -- in particular when the agreement has **no pinned template**, when a pinned template can no longer be resolved, and when the calculator returns anything other than a Quoted outcome with a plannable option.

An agreement whose jurisdiction we cannot name, or whose duty we cannot compute, SHALL be treated as
ineligible rather than permitted. When no chargeable rule is loaded at all, every jurisdiction SHALL be
refused, cleanly rather than by failing unexpectedly.

The loaded rules, whether each is reviewed, and whether unreviewed rules are allowed SHALL be
observable at startup, so an operator can see which jurisdictions the running instance may charge
without reading rule files.

#### Scenario: An agreement with no pinned template is refused

- **WHEN** the eligibility check runs for an agreement that has no selected template
- **THEN** it refuses the agreement
- **AND** it does not fall back to any default jurisdiction

#### Scenario: An unresolvable pinned template is refused as a jurisdiction failure

- **WHEN** the eligibility check runs for an agreement whose pinned template can no longer be resolved
- **THEN** it refuses the agreement as an unsupported jurisdiction
- **AND** the refusal is not reported as a missing resource

#### Scenario: No chargeable rule refuses everything

- **WHEN** no loaded rule is reviewed and unreviewed rules are not allowed
- **THEN** every agreement is refused, whatever its jurisdiction
- **AND** the refusal is the ordinary unsupported-jurisdiction refusal, not an unexpected server error

#### Scenario: An agreement needing adjudication is refused

- **WHEN** the calculator returns NeedsAdjudication for an agreement
- **THEN** the eligibility check refuses the agreement as an unsupported jurisdiction

#### Scenario: Chargeable rules are observable

- **WHEN** the application starts
- **THEN** the startup log records each loaded rule, whether it is reviewed, and whether unreviewed
  rules are allowed

