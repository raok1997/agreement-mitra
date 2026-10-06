## MODIFIED Requirements

### Requirement: The published terms state which jurisdictions can be stamped

The customer-facing terms of service SHALL name the jurisdictions whose agreements can be
drafted. They SHALL state that stamping and eSign are available only for a jurisdiction whose
duty the product can calculate and whose stamps it can obtain. They SHALL key the no-payment
promise to the server's eligibility decision, which is derived from the duty rules and enforced at
finalise, checkout, e-stamp intake and eSign. They SHALL describe the "Draft and download only"
marking as how that decision is normally shown before a template is filled in, not as the
decision itself, because the marking fails open when its lookup fails. They SHALL refer to the
status board on the home page only as a summary, and SHALL say that for the customer's own
agreement, what the service tells them applies.

The terms SHALL NOT carry their own list of the jurisdictions that are live for stamping today.
Such a list would be a third copy of a fact the server and the board already hold, and it would
drift at every change.

The terms SHALL promise that no payment is taken for an agreement in a state the service cannot
stamp, and SHALL NOT mention a template the picker does not offer.

The generated terms document SHALL be regenerated from the same single source, so the two
faces of the text cannot diverge.

#### Scenario: The terms carry a supported-jurisdictions clause

- **GIVEN** the terms-of-service clause with id `jurisdictions`
- **WHEN** its body is read
- **THEN** it contains "Telangana", "Karnataka" and "residential"
- **AND** it contains "We will not take payment for an agreement in a state we cannot stamp", "Draft and download only" (the marking's displayed name) and "status board on our home page"

#### Scenario: The terms do not restate the live list

- **GIVEN** the terms-of-service clauses with ids `what-the-service-does` and `jurisdictions`
- **WHEN** their bodies are searched case-sensitively for "Today that is" and for each exact status-board label in `RELEASE_STATE_LABEL`
- **THEN** none is found

#### Scenario: The terms do not mention a template the picker hides

- **GIVEN** the terms-of-service clause with id `jurisdictions`
- **WHEN** its body is searched for "national template"
- **THEN** it is not found

#### Scenario: The generated document matches its source

- **WHEN** the terms source is changed
- **THEN** the generated terms document is regenerated from it
- **AND** the drift check between the two passes
