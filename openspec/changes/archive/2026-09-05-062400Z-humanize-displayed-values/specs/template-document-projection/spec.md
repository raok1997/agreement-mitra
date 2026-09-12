## ADDED Requirements

### Requirement: Enum options carry a human display label

The projected form schema SHALL carry, for every enum field, a human display **label** for each
option alongside its stored **value**. The label SHALL be derived from the value by replacing `_`
with a space, applying Initial Capitals to each word, keeping a defined acronym set upper-case (e.g.
`UPI`, `PG`), and preserving a token that already contains an upper-case letter (e.g. `1BHK`)
verbatim. The stored/submitted value SHALL be unchanged: submission, persistence, `showWhen`
evaluation, defaults, and enum-membership validation continue to use the value, not the label. The
label is a pure, deterministic function of the value, so the form schema stays data-independent and
cacheable.

#### Scenario: Each enum option exposes value and humanised label

- **GIVEN** an enum field `propertyType` with options `apartment`, `independent_house`, `pg_room`
- **WHEN** the form schema is projected
- **THEN** each option carries its value and a label -- `apartment`/"Apartment",
  `independent_house`/"Independent House", `pg_room`/"PG Room"
- **AND** the list box displays the labels while the submitted value remains the token
  (`independent_house`)

#### Scenario: Acronyms and already-capitalised tokens

- **GIVEN** options `upi` (paymentMode) and `1BHK` (bhkConfiguration)
- **WHEN** the labels are derived
- **THEN** `upi` renders as `UPI` (acronym set) and `1BHK` is preserved verbatim as `1BHK`

#### Scenario: Submission and gating use the value, not the label

- **GIVEN** a user selects the option shown as "Two Wheeler"
- **WHEN** the form is submitted
- **THEN** the submitted value is `two_wheeler`
- **AND** a `showWhen` such as `parkingType != "none"` and the field default evaluate on the value
  exactly as before (the label change is presentation-only)
