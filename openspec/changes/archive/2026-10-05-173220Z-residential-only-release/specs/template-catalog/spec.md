## ADDED Requirements

### Requirement: The template picker offers only the templates in the release

The template picker SHALL offer only state-specific residential templates: a catalog entry whose state is the national dimension (`IN`), or whose type is anything other than `residential`, SHALL NOT be listed, counted in a filter option, or selectable from the picker.

Both exclusions are a presentation rule of the picker. The entries stay published, the catalog API
continues to list them, and an agreement already pinned to one stays readable and editable from
My Agreements. Whether such an agreement can be **paid for** is decided by the server, never by this
rule (see `jurisdiction-eligibility`).

#### Scenario: National templates are not offered

- **GIVEN** the catalog lists a published national (`IN`) residential template
- **WHEN** the template picker renders
- **THEN** no card is shown for it
- **AND** `IN` is not among the state filter options

#### Scenario: Commercial templates are not offered

- **GIVEN** the catalog lists published TG residential and TG commercial templates
- **WHEN** the template picker renders
- **THEN** a card is shown for TG residential and none for TG commercial
- **AND** no type filter offers `commercial`
- **AND** a search for "commercial" shows no card

#### Scenario: A hidden template stays published

- **WHEN** the catalog API lists templates without filters
- **THEN** the commercial and national entries are still returned
