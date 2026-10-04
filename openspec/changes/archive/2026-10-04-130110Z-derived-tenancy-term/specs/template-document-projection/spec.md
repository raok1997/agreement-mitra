## ADDED Requirements

### Requirement: The tenancy term is derived from the submitted dates on every render path

The system SHALL compute the tenancy term in whole months from the submitted start and end dates and
SHALL substitute that computed value for `durationMonths` in the data map it compiles, discarding any
`durationMonths` present in the submitted data. This SHALL apply on **every** render path -- the
stateless preview and the generated draft alike -- so that a preview and the document later signed
state the same term.

The term SHALL be the number of **complete** months between the start date and the end date, measured
inclusive of the end date (the end date is the tenancy's last day, so 1 Sep to 31 Jul is eleven
months), with a trailing partial month truncated. This is the same whole-month count
the `agreement-management` capability requires the server to derive, so a rendered document and the
agreement record can never report different terms.

When either date is absent or unusable, the system SHALL leave `durationMonths` unset rather than
substitute a guessed or defaulted term, and the document SHALL render the same way it renders any
other unfilled field. A date whose year lies outside the representable range SHALL be treated as
unusable rather than raising an error, so the submitted-data validator reports the date fault itself.

The system SHALL likewise leave `durationMonths` unset when the computed term is **not positive** --
a reversed range yields a negative count and an end date inside the start month yields zero, and
neither is a term a deed can state. `durationMonths` carries no `validation: { min: 1 }` once it is
derived (input bounds do not belong on a computed value), so nothing downstream would otherwise
reject it and the document body would read "a term of -4 month(s)".

This requirement is the data-map counterpart to single-compiler parity: parity of the compiler
guarantees one renderer for a *given* data map, and this guarantees the preview path and the generate
path present the *same* map for the term.

#### Scenario: A non-positive term is left unstated

- **WHEN** a preview is requested with a start date of 2026-06-01 and an end date of 2026-01-01
- **THEN** `durationMonths` is left unset and the document does not state a negative term

#### Scenario: A submitted duration that disagrees with the dates is discarded

- **WHEN** a preview is requested with a start date of 2026-01-08, an end date of 2028-01-08, and a
  submitted `durationMonths` of 11
- **THEN** the rendered document states a term of 24 months, not 11

#### Scenario: Preview and generated draft state the same term

- **GIVEN** the same effective template, dates, and captured data
- **WHEN** the document is rendered once through the stateless preview and once through the generated
  draft
- **THEN** both state the same term in months

#### Scenario: A trailing partial month is truncated

- **WHEN** a document is rendered for a tenancy from 2026-01-01 to 2026-12-01
- **THEN** it states a term of 11 months

#### Scenario: A missing date leaves the term unfilled

- **WHEN** a preview is requested with a start date but no end date
- **THEN** the rendered document does not state a term computed from a defaulted or guessed date
