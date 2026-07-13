## MODIFIED Requirements

### Requirement: Render a template and data to a PDF

The system SHALL provide a document renderer that turns a template id and a data map into a PDF
using an external render service (Gotenberg, which runs headless Chromium). The renderer SHALL
populate the identified template with the supplied data and return the resulting PDF bytes. It
SHALL be domain-agnostic -- it SHALL depend only on a template id and a generic data map, not on
any agreement or signing type.

With the template catalog out of scope for this capability, the system SHALL provide exactly one
bundled rental-agreement template as the default; an unknown template id SHALL be an error, not a
silent empty document.

The bundled rental-agreement template SHALL be a **complete India-standard residential rental
agreement**: it SHALL compose, from the supplied data, the agreement date; the lessor(s)/owner(s)
and lessee(s)/tenant(s) blocks (full name, father's name, current address); the scheduled property;
the term (start date, end date, duration in months); the monthly rent and security deposit; a set
of standard residential-tenancy clauses; and a party signatures block. No attesting-witness block is
included -- the agreement is executed by Aadhaar eSign (only the parties sign; the eSign audit trail
serves the evidentiary role, and a rental/leave-and-licence agreement requires no witness
attestation). All supplied data SHALL remain HTML-escaped; the standard clauses are trusted template
markup.

#### Scenario: A template plus data yields a PDF

- **WHEN** the renderer is asked to render the rental-agreement template with a data map
- **THEN** it returns non-empty PDF bytes that begin with the PDF signature and contain the
  supplied field values laid out by the template

#### Scenario: An unknown template id is rejected

- **WHEN** the renderer is asked for a template id that is not bundled
- **THEN** it fails with an error and returns no document

#### Scenario: The rental-agreement template composes the standard sections

- **WHEN** the rental-agreement template is rendered with a full agreement data map
- **THEN** the composed document contains the parties (by role, with father's name and current
  address), the property, the rent and deposit, the term with its duration, the standard clauses,
  and the party signature block (no attesting-witness block)
