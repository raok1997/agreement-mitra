# jurisdiction-eligibility Specification

## Purpose

Which duty jurisdictions may reach **paid fulfilment** — order placement, payment, e-stamp
purchase and eSign initiation — versus draft-and-download only. Stamp duty is state law and there
is no national rate, so an agreement without an eligible duty jurisdiction has no computable duty
and no defined state in which to buy a certificate; taking money for one would be an unbounded
liability against a fulfilment path that does not exist.

Established by `jurisdiction-checkout-gating` as a configured allowlist, which was **temporary by
design**. `state-stamp-duty-quoting` replaced it with the stamp duty rules: a jurisdiction is eligible
when it has a rule in effect that may be charged (counsel-reviewed, or unreviewed rules explicitly
allowed) and the agreement is quoted with a plannable stamp option. There is no allowlist anymore.
## Requirements
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

### Requirement: Chargeability is decided per duty rule, not per state

The system SHALL decide whether an agreement may be charged from the duty rule that quotes it — the rule for the agreement's state **and usage** — so a chargeable rule for one usage SHALL NOT make another usage in the same state chargeable.

The eligible-jurisdiction list served to the picker is per state (a state is listed when any of its
rules is chargeable); it is disclosure only. Order placement and checkout SHALL evaluate the
agreement's own rule, and so SHALL e-stamp intake and eSign initiation for an agreement that is not
already paid with a frozen stamp quote (a paid agreement passes on its frozen quote, per "Paid
fulfilment requires a chargeable, quotable duty rule"). A state listed as eligible can therefore still
refuse an agreement whose usage has no chargeable rule.

#### Scenario: A reviewed residential rule does not admit commercial in the same state

- **GIVEN** unreviewed rules are not allowed by configuration
- **AND** a state's residential rule carries a counsel review matching its content hash
- **AND** that state's commercial rule carries no counsel review
- **WHEN** the residential and commercial rules are checked for chargeability
- **THEN** the residential rule is chargeable and the commercial rule is not
- **AND** the state is in the eligible-jurisdiction list

#### Scenario: A commercial order is refused when unreviewed rules are disallowed

- **GIVEN** unreviewed rules are not allowed by configuration
- **AND** a TG commercial agreement drafted through the API
- **WHEN** its stamp quote is requested
- **THEN** the quote reports that the rule is not chargeable
- **AND WHEN** its order is placed, and when checkout is requested for it
- **THEN** each is refused with `409 JURISDICTION_UNSUPPORTED`

### Requirement: Commercial is withheld from paid fulfilment for the residential-only release

Every shipped stamp duty rule whose usage is commercial SHALL NOT be reviewed — it carries no counsel review matching its content hash — while commercial templates are withheld from the picker, so that with unreviewed rules disallowed no new commercial order is admitted to paid fulfilment.

This governs orders placed after unreviewed rules are disallowed. A commercial agreement already paid
with a frozen stamp quote before then passes fulfilment on that quote; the release removes any such
order operationally rather than through this rule.

Adding a matching counsel review to a commercial rule is the act that brings commercial back. It SHALL
fail a test that names the picker rule and FAQ wording to lift alongside it, so the review and the
un-hiding land together rather than leaving a payable usage that no customer can select.

#### Scenario: Shipped commercial rules are unreviewed

- **WHEN** the shipped stamp duty rules are loaded, test fixtures excluded
- **THEN** the states holding a commercial rule are exactly TG and KA
- **AND** neither commercial rule is reviewed

