## ADDED Requirements

### Requirement: Paid fulfilment requires an eligible duty jurisdiction

The system SHALL maintain an **allowlist** of duty jurisdictions eligible for paid
fulfilment — order placement, payment, e-stamp purchase and eSign initiation. An agreement
whose duty jurisdiction is not on that allowlist SHALL NOT reach any of those steps.

The list SHALL be expressed as **configuration**, so that admitting a further jurisdiction
requires no code change. It SHALL be an allowlist and not a denylist: a jurisdiction is
ineligible until it is deliberately admitted, because a jurisdiction nobody has assessed has
no computable stamp duty and no defined place to buy a certificate.

Stamp duty is state law and there is no national rate, so the national dimension (`IN`)
SHALL NOT itself be a duty jurisdiction. This requirement constrains the agreement's **duty
jurisdiction**, not the state dimension of the template it was drafted from, so that a later
capability MAY establish a duty jurisdiction for an agreement drafted from a national
template without contradicting this rule.

#### Scenario: An eligible jurisdiction proceeds

- **WHEN** an agreement's duty jurisdiction is on the configured allowlist
- **THEN** the eligibility check permits the agreement to proceed
- **AND** no other behaviour changes

#### Scenario: An agreement with no duty jurisdiction is refused

- **WHEN** an agreement's only jurisdiction indication is the national dimension, and no
  duty jurisdiction has been established for it
- **THEN** the eligibility check refuses the agreement

#### Scenario: Admitting a jurisdiction is a configuration change

- **WHEN** a further state code is added to the configured allowlist
- **THEN** agreements in that duty jurisdiction proceed
- **AND** no source-code change was required to admit it

#### Scenario: Jurisdiction codes are compared without regard to case or padding

- **WHEN** a configured allowlist entry differs from the agreement's state code only by
  letter case or surrounding whitespace
- **THEN** the two are treated as the same jurisdiction

### Requirement: The national dimension cannot be admitted by configuration

The national dimension (`IN`) SHALL NOT be admissible as an eligible duty jurisdiction, and
SHALL NOT become eligible by being added to the configured allowlist. There is no national
stamp-duty rate to admit, so admitting it would re-open the hazard the allowlist exists to
close.

This SHALL hold as a property of the rule rather than of the default configuration, so that
a well-intentioned configuration edit cannot restore the hazard.

#### Scenario: Adding the national dimension to the allowlist does not admit it

- **WHEN** the national dimension is present in the configured allowlist
- **THEN** an agreement whose only jurisdiction indication is the national dimension is
  still refused

#### Scenario: The refusal to admit it is visible to an operator

- **WHEN** the application starts with the national dimension present in the configured
  allowlist
- **THEN** the startup record of eligible jurisdictions does not present it as eligible

### Requirement: An unknown or unconfigured jurisdiction fails closed

The check SHALL refuse whenever the agreement's duty jurisdiction cannot be established — in
particular when the agreement has **no pinned template**, and when a pinned template can no
longer be resolved. An agreement whose jurisdiction we cannot name is one whose duty we
cannot compute, so "unknown" SHALL be treated as ineligible rather than permitted.

An **empty or absent** allowlist SHALL refuse every jurisdiction. A misconfigured deployment
therefore stops paid fulfilment rather than opening it, and SHALL do so by refusing cleanly
rather than by failing unexpectedly.

The resolved allowlist SHALL be observable at startup, so an operator can see which
jurisdictions the running instance admits without reading configuration files.

#### Scenario: An agreement with no pinned template is refused

- **WHEN** the eligibility check runs for an agreement that has no selected template
- **THEN** it refuses the agreement
- **AND** it does not fall back to any default jurisdiction

#### Scenario: An unresolvable pinned template is refused as a jurisdiction failure

- **WHEN** the eligibility check runs for an agreement whose pinned template can no longer
  be resolved
- **THEN** it refuses the agreement as an unsupported jurisdiction
- **AND** the refusal is not reported as a missing resource

#### Scenario: An empty allowlist refuses everything

- **WHEN** the configured allowlist is empty or absent
- **THEN** every agreement is refused, whatever its jurisdiction
- **AND** the refusal is the ordinary unsupported-jurisdiction refusal, not an unexpected
  server error

#### Scenario: The allowlist is observable

- **WHEN** the application starts
- **THEN** the resolved set of eligible jurisdictions is recorded in the startup log

### Requirement: An ineligible jurisdiction is refused with its own distinct error

A refusal SHALL be reported as a `409 Conflict` carrying a **distinct** error kind reserved
for this cause, never folded into the payment-required, contact-required, draft-required or
stamp-required kinds. An operator reading the response must be able to tell this failure
apart from every other precondition on the pipeline, because a different person resolves
each one.

The response SHALL follow the established RFC 9457 ProblemDetail contract with its own
`type`, and SHALL name the rejected jurisdiction and the eligible ones so the refusal is
actionable. Both values SHALL be server-derived rather than echoed from the request. The
response SHALL NOT contain any party name, address, email address, mobile number or other
submitted personal data.

Each refusal SHALL be recorded in the application log, naming the rejected jurisdiction and
no personal data, so that a spike of refusals — or a misconfiguration — is visible, and so
that demand for an unsupported jurisdiction can be observed.

#### Scenario: The refusal is a distinct 409

- **WHEN** paid fulfilment is attempted for an ineligible jurisdiction
- **THEN** the response status is `409`
- **AND** the body is `application/problem+json` with a `type` reserved for an unsupported
  jurisdiction
- **AND** the error kind is distinct from the payment-required kind

#### Scenario: The refusal explains itself without leaking personal data

- **WHEN** a refusal body is produced
- **THEN** it names the rejected jurisdiction and the eligible jurisdictions
- **AND** it contains no party name, address, email address or mobile number

#### Scenario: A refusal is observable in the log

- **WHEN** a refusal occurs
- **THEN** a log record names the rejected jurisdiction
- **AND** that record contains no personal data

### Requirement: An ineligible jurisdiction remains fully usable for drafting

Ineligibility SHALL restrict **paid fulfilment only**. Creating, editing, generating,
previewing and downloading an agreement in an ineligible jurisdiction SHALL continue to work
unchanged, because those steps take no money and commit the customer to nothing.

#### Scenario: Drafting and preview are unaffected

- **WHEN** an agreement in an ineligible jurisdiction is created, edited, generated or
  previewed
- **THEN** each step succeeds exactly as it does for an eligible jurisdiction

### Requirement: The limit is disclosed before the customer invests effort

The interface SHALL mark a draft-only jurisdiction **at the point of template selection**
and in the capture screen, so a customer does not complete an entire agreement before
discovering it cannot be stamped or signed. The wording SHALL say the template is available
to draft and download, rather than implying it is unavailable.

The interface SHALL derive this from a **server-provided** list of eligible jurisdictions
rather than a list held in the client, so the disclosure cannot drift from the rule the
server enforces. That list SHALL be readable without authentication and SHALL be static —
carrying no agreement identifier and returning nothing specific to any agreement.

The disclosure is not the control: the server-side refusal remains authoritative. Where the
eligibility list cannot be retrieved, the interface SHALL omit the marking rather than mark
every jurisdiction as draft-only, since enforcement is unaffected and mislabelling an
eligible jurisdiction would deter a customer who could in fact be served.

#### Scenario: A draft-only jurisdiction is marked in the picker

- **WHEN** the template picker lists a jurisdiction absent from the server's eligible list
- **THEN** that entry is marked as draft-and-download only

#### Scenario: The disclosure comes from the server

- **WHEN** the eligible-jurisdiction list served by the backend changes
- **THEN** the interface's marking changes with it
- **AND** no client-side list of jurisdictions had to be edited

#### Scenario: The eligibility list is anonymous and carries no agreement data

- **WHEN** the eligible-jurisdiction list is requested without authentication
- **THEN** it is returned
- **AND** it contains only jurisdiction identifiers, with nothing specific to any agreement

#### Scenario: A failed eligibility lookup degrades the marking, not the gate

- **WHEN** the interface cannot retrieve the eligible-jurisdiction list
- **THEN** no jurisdiction is marked draft-only
- **AND** the server still refuses paid fulfilment for an ineligible jurisdiction

### Requirement: The published terms state which jurisdictions can be stamped

The customer-facing terms of service SHALL state which jurisdictions are available for
stamping and eSign and which are available to draft and download only, so the published
promise matches what the product does.

The generated terms document SHALL be regenerated from the same single source, so the two
faces of the text cannot diverge.

#### Scenario: The terms carry a supported-jurisdictions clause

- **WHEN** the terms of service are read
- **THEN** they state which jurisdictions can be stamped and eSigned, and which are
  draft-and-download only

#### Scenario: The generated document matches its source

- **WHEN** the terms source is changed
- **THEN** the generated terms document is regenerated from it
- **AND** the drift check between the two passes
