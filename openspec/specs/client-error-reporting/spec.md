# client-error-reporting Specification

## Purpose
TBD - created by archiving change agreement-error-problem-type-plumbing. Update Purpose after archive.
## Requirements
### Requirement: The API client carries the problem type on every refusal

Every non-2xx response that `apiFetch` returns to the agreement, payment or staff-queue API modules (`agreements.ts`, `payments.ts`, `staffQueue.ts`) SHALL surface as an error carrying both the HTTP status and the RFC 9457 problem `type` read from the JSON body. When the body is not JSON, or carries no string `type`, the type SHALL be `null` and the error SHALL still be thrown with its status.

`apiFetch` itself turns a load refusal (a `429`, or a `503` with the render-busy type) into its own retry error before any module sees it, and that behaviour is unchanged.

Problem types SHALL be compared as exact URNs taken from one shared set of constants, never by suffix. Any of these errors SHALL answer one predicate, "is this refusal of type T?", identically, whichever module threw it.

#### Scenario: A typed refusal keeps its type

- **GIVEN** an agreement, payment or staff-queue request
- **WHEN** the server responds `409` with a JSON body whose `type` is a problem URN
- **THEN** the thrown error carries that status and that exact `type`

#### Scenario: A body that is not a problem document degrades to a null type

- **GIVEN** an agreement, payment or staff-queue request
- **WHEN** the server responds non-2xx with a body that is not JSON, or JSON without a string `type`
- **THEN** the thrown error carries the status and a `null` type, and no parsing error escapes

#### Scenario: The same refusal is recognised from either module

- **GIVEN** a `409` with the unsupported-jurisdiction type
- **WHEN** it is raised by the finalise call (an agreement error) or by the checkout-order call (a payment error)
- **THEN** the shared predicate reports it as the unsupported-jurisdiction refusal in both cases

### Requirement: A refusal whose remedy differs is explained in its own words

Where a refused customer action has a remedy different from "try again", the interface SHALL show a message naming that remedy, and SHALL NOT invite a retry that cannot succeed. At minimum this SHALL hold for:

- **The unsupported-jurisdiction refusal, wherever payment is started.** The message SHALL say that stamping and eSign are not available for this agreement's jurisdiction and that the draft can still be previewed and downloaded. The capture form, the status view and the stamp-value step's unavailable notice SHALL use the same wording, except that when the stamp quote is unavailable only because no stamp-paper plan covers the duty (status `UNPLANNABLE`), the stamp-value step SHALL instead say that stamping is not available for this agreement yet, without naming the jurisdiction.
- **The draft-frozen refusal of an edit.** The message SHALL say that the agreement's order has been placed, so its terms can no longer be changed.
- **The contacts-frozen refusal of a contact change.** Its existing message is unchanged.

#### Scenario: Jurisdiction refused at finalise in the capture form

- **GIVEN** a saved agreement whose jurisdiction became ineligible for paid fulfilment after its stamp quote loaded (the stamp step hides the choice for an ineligible jurisdiction, so this is the race path)
- **WHEN** the customer chooses a stamp value and the finalise call is refused `409` with the unsupported-jurisdiction type
- **THEN** the capture form shows the jurisdiction message
- **AND** it does not show `Agreement request failed: 409` or any "please try again" wording

#### Scenario: Jurisdiction refused at checkout in the capture form

- **GIVEN** finalise succeeded
- **WHEN** the checkout-order call is refused `409` with the unsupported-jurisdiction type
- **THEN** the capture form shows the same jurisdiction message

#### Scenario: Jurisdiction refused from the status view

- **GIVEN** an agreement whose payment is outstanding, opened in the status view
- **WHEN** the customer starts payment and the checkout-order call is refused `409` with the unsupported-jurisdiction type
- **THEN** the status view shows the same jurisdiction message, not the "contact support" message

#### Scenario: An unplannable duty is not called a jurisdiction refusal

- **GIVEN** a stamp quote that is unavailable with status `UNPLANNABLE`
- **WHEN** the stamp-value step renders
- **THEN** its notice says stamping is not available for this agreement yet and does not mention the jurisdiction
- **AND** it offers no payment

#### Scenario: An edit refused because the order is placed

- **GIVEN** an owner editing an agreement whose order has since been placed
- **WHEN** the save is refused `409` with the draft-frozen type
- **THEN** the capture form says the order has been placed and the terms can no longer be changed
- **AND** it does not show `Agreement request failed: 409`

### Requirement: A raw HTTP status is never shown to a customer

The capture form's save, contact, finalise and payment actions, and the status view's payment action, SHALL show an error's own message only when that error is known to carry customer-facing text:

- the load-refusal retry message;
- the problem description built by the agreement-create client, which never includes a status code;
- the payment window failing to load.

Every other error SHALL show the action's generic message. This includes every API HTTP error, whose message is a status string of the form `… request failed: <status>`, and runtime errors such as a failed fetch.

#### Scenario: An untyped or unrecognised refusal falls back to the generic message

- **GIVEN** a customer performing a finalise, checkout or edit-save action
- **WHEN** the call is refused with a status or type that has no specific message (for example a `500` with a non-JSON body, or a `409` of another type)
- **THEN** the view shows its generic message for that action
- **AND** the text contains no `request failed:` status string

#### Scenario: A load refusal still shows when to retry

- **GIVEN** a customer performing a finalise or save action
- **WHEN** the call is refused for load (`429`, or `503` render-busy from document generation)
- **THEN** the view shows the "try again in N seconds" message, as before

