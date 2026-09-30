## ADDED Requirements

### Requirement: The Karnataka residential layers contribute a mandatory statutory overlay

The Karnataka `state` and `state_type` layers over the residential set SHALL contribute a statutory
section that always renders -- in the live preview and in every generated Karnataka draft -- with no
user action, and SHALL NOT be offered as an opt-in add-on.

The overlay SHALL state the law governing the tenancy in Karnataka, SHALL state that the agreement is
to be stamped under the Karnataka Stamp Act 1957 and registered before the jurisdictional
Sub-Registrar under the Registration Act 1908 where the term passes the threshold the Karnataka duty
rule itself reports, and SHALL name who bears the stamp duty and registration charges. The stated
registration threshold SHALL agree with the threshold the Karnataka rule applies; the two SHALL NOT
be allowed to drift.

Mandatory is a correctness requirement here, not a preference. The Karnataka `state_type` layer
removes the national stamp-and-registration clause on the ground that the Karnataka clause supersedes
it. If the statutory section were opt-in, a default Karnataka deed would carry no stamp or
registration clause at all -- strictly worse than the national template it overlays. Either the
section is mandatory, or the national clause is restored to the covenant list in the same edit.

The stamp duty amount SHALL remain system-sourced: the capture form SHALL NOT ask for it, a submitted
value SHALL be discarded, and the rendered deed SHALL show a visible provision in its place until
stamp intake supplies the amount from the attached certificate.

The Karnataka layers SHALL default the jurisdiction city, so that the always-on exclusive-jurisdiction
covenant names a court rather than an unfilled placeholder.

#### Scenario: The statutory overlay renders without being added

- **WHEN** a Karnataka residential agreement is previewed with no optional sections active
- **THEN** the Karnataka statutory section renders
- **AND** it carries the tenancy-law, stamp-and-registration and charges-borne-by clauses

#### Scenario: No deed is left without a stamp and registration clause

- **WHEN** a Karnataka residential draft is generated with no optional sections active
- **THEN** exactly one stamp-and-registration clause renders
- **AND** it is the Karnataka clause, not the national one

#### Scenario: The jurisdiction covenant names a court

- **WHEN** a Karnataka residential draft is generated without the optional dispute-resolution section
- **THEN** the exclusive-jurisdiction covenant names the Karnataka default city
- **AND** it does not render an unfilled jurisdiction-city placeholder

#### Scenario: The stamp duty amount is not asked for

- **WHEN** the capture form for a Karnataka residential agreement is projected
- **THEN** it contains no stamp duty amount input
- **AND** a draft generated before stamping shows a visible provision in the amount's place

#### Scenario: The stated registration threshold matches the rule

- **WHEN** the Karnataka statutory clause and the Karnataka duty rule are compared
- **THEN** the term threshold at which the clause says registration becomes compulsory is the same
  threshold at which the rule reports registration as required
