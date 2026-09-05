## MODIFIED Requirements

### Requirement: Inbound webhook is verified before any side effect

The system SHALL provide a single `POST /api/webhooks/esign` endpoint that accepts the
provider's completion callback for both the success and error channels and handles them
through one unified path. Because authoritative state is re-read from the provider's fetch API
(see the next requirement), the endpoint SHALL NOT branch on any untrusted body field (e.g. a
status or `success` field) to decide the outcome - both channels run the same verify -> fetch
-> FSM path.

Webhook authentication SHALL be provider-specific and SHALL live behind the `EsignProvider`
seam. Two mechanisms SHALL be supported:

- a **message authentication code carried in the request body**, computed over the document id
  with a configured secret (the Leegality adapter); and
- a **shared key carried in an HTTP header**, issued **per transaction** when the signing
  request was created (the ZOOP adapter, header `webhook-security-key`).

For the per-transaction mechanism the system SHALL parse the **untrusted** transaction id from
the body, look up **that transaction's** stored key, and compare it to the header value. The
comparison SHALL be **constant-time** (no early-exit timing oracle). A webhook naming an
unknown transaction SHALL be rejected without disclosing that the transaction is unknown.

The endpoint SHALL accept only a JSON request body (`application/json`) and SHALL bound the
request body size, rejecting an oversized body. Verification SHALL complete before any state
change.

If verification fails - a missing, tampered, or forged credential, or a credential valid for a
different transaction - the system SHALL reject the request and SHALL NOT change any signing
request. The webhook body and the credential SHALL NOT be logged verbatim and SHALL NOT be
echoed in any error response.

After a successful verification, the endpoint's response SHALL NOT reveal whether a matching
signing request exists (no existence oracle): a verified webhook SHALL return the same
acknowledgement regardless of whether its transaction id is known.

#### Scenario: Valid per-transaction header key is accepted

- **WHEN** a webhook arrives whose `webhook-security-key` header matches the key stored for
  the transaction its body names
- **THEN** verification passes and the system proceeds to resolve authoritative status

#### Scenario: Valid body MAC is accepted

- **WHEN** a webhook arrives whose body MAC is the correct value over its document id under
  the configured secret
- **THEN** verification passes and the system proceeds to resolve authoritative status

#### Scenario: A key valid for another transaction is rejected

- **WHEN** a webhook presents a `webhook-security-key` that is a genuine key for a *different*
  transaction
- **THEN** the system rejects the request and makes no change to any signing request

#### Scenario: Missing or tampered credential is rejected

- **WHEN** a webhook arrives with no credential, or one that does not match
- **THEN** the system rejects the request and makes no change to any signing request

#### Scenario: Replay of a valid captured webhook is harmless

- **WHEN** a previously-valid webhook is replayed (same body and credential)
- **THEN** the system re-reads authoritative status and the transition is idempotent, so a
  replay produces no additional state change beyond the legitimate one

#### Scenario: Verified webhook for an unknown transaction is acknowledged indistinguishably

- **WHEN** a verified webhook references a transaction with no matching signing request
- **THEN** the system makes no state change and returns the same acknowledgement as for a
  known transaction (no internal detail leaked, no existence oracle)

### Requirement: Authoritative status comes from the Details API, not the webhook body

The webhook body SHALL be treated as **entirely untrusted**, regardless of which
authentication mechanism verified the call.

This matters more for the per-transaction header key than for a body MAC: a header key proves
only that the caller **holds the key**, and binds nothing to the payload. Anyone in possession
of that key could submit an arbitrary body. The webhook SHALL therefore be treated as a
**trigger only**.

After verifying, the system SHALL call the provider's authoritative status read to obtain the
**per-invitee** statuses and SHALL drive the signing request's FSM off the aggregation of
those statuses (per the aggregation rule in "Signing request owns the status FSM") - never off
status fields in the webhook body, and never off a single document-level status. The
per-invitee mapping SHALL be: signer signed -> `SIGNED`; signer rejected or certificate
verification failed -> `REJECTED`; invitation expired -> `EXPIRED`; otherwise -> `PENDING`. A
non-terminal aggregate result SHALL cause **no FSM transition** (a safe no-op), not an error.

If the status read fails (unreachable, timeout, or error), the system SHALL acknowledge the
webhook without applying a transition, leaving completion to the scheduled reconciliation job
- it SHALL NOT return an error that induces unbounded vendor redelivery. This
status-read-as-source-of-truth path SHALL be the same code path reused by that reconciliation
job for missed webhooks.

#### Scenario: Completion drives SIGNED off the authoritative status read

- **WHEN** a verified webhook triggers a status read reporting that every invitee has signed
- **THEN** the signing request transitions to `SIGNED`

#### Scenario: Rejection or certificate failure drives FAILED

- **WHEN** a verified webhook triggers a status read reporting any invitee rejected or
  certificate verification failed
- **THEN** the signing request transitions to `FAILED`

#### Scenario: Expiry drives EXPIRED

- **WHEN** a verified webhook triggers a status read reporting the transaction expired with no
  rejection
- **THEN** the signing request transitions to `EXPIRED`

#### Scenario: Partial signing is a safe no-op

- **WHEN** a verified webhook triggers a status read reporting some but not all invitees
  signed (none rejected/expired)
- **THEN** no FSM transition is applied and no error is raised

#### Scenario: Status-read failure defers to reconciliation

- **WHEN** a verified webhook triggers a status read and the call fails
- **THEN** the system acknowledges the webhook, applies no transition, and leaves completion
  to the reconciliation fallback

#### Scenario: A forged body cannot drive a transition

- **WHEN** a caller holding a valid per-transaction key submits a body claiming a status that
  disagrees with the authoritative status read
- **THEN** the system uses the authoritative per-invitee statuses, and the forged claim has no
  effect on the FSM

### Requirement: Provider specifics stay behind the EsignProvider seam

All provider-specific details SHALL live behind the `EsignProvider` interface in a
provider-specific adapter package - base URL, API version, authentication headers,
request/response shapes, the webhook authentication mechanism, and signature-placement
translation. More than one adapter MAY exist; the active provider SHALL be selected by
configuration. No code outside the `signing` module SHALL reference any adapter package, and
`ModularityTests` SHALL remain green.

`EsignProvider` SHALL expose webhook verification in a form that supports **both** a
body-carried MAC and a **transport-header** credential: verification SHALL receive the request
headers alongside the payload. Because a per-transaction credential cannot be checked from
configuration alone, verification SHALL be expressible in two steps - parse the untrusted
transaction id from the body, then verify the presented credential against the secret stored
for that transaction. Verification SHALL surface the **verified transaction id** to the caller
(present on success, absent on failure) so the controller never parses the vendor payload
itself.

The provider request/response value objects SHALL remain vendor-neutral and multi-invitee: the
create request carries a list of invitees; the create result carries the provider document id
plus, per invitee, a signing URL (where the provider exposes one), an expiry, and the
provider's per-invitee identifier for later correlation. The authoritative-status read SHALL
return a vendor-neutral **per-invitee** status view (each invitee's status plus a correlation
token - the provider per-invitee id when available, an ordinal otherwise - with
non-terminal/`PENDING` representable). Downloading SHALL return the signed PDF bytes and the
audit-trail bytes **each with its provider-declared content type** (default
`application/octet-stream` when absent).

Where a provider returns artifact URLs, the adapter SHALL fetch them only after pinning the
host against a **configured allowlist** of provider hosts. An arbitrary URL from a response
SHALL NOT be fetched.

#### Scenario: Module boundaries hold

- **WHEN** the module-boundary verification runs
- **THEN** no module outside `signing` references any provider adapter package and the
  verification passes

#### Scenario: Webhook verification can use a transport header

- **WHEN** the webhook is verified for a provider that authenticates with a header credential
- **THEN** verification receives the request headers and validates the credential against the
  secret stored for the transaction named in the body

#### Scenario: Status read returns per-invitee statuses

- **WHEN** the authoritative-status read is invoked for a provider document id
- **THEN** the provider returns a vendor-neutral per-invitee status view, not a single
  collapsed document status

#### Scenario: Signed-document download returns the signed artifact

- **WHEN** the signed-document download is invoked for a completed document id
- **THEN** the provider returns the signed PDF bytes and the audit-trail bytes

#### Scenario: Artifact URL on an unlisted host is refused

- **WHEN** a provider response carries an artifact URL whose host is not in the configured
  allowlist
- **THEN** the adapter refuses to fetch it

#### Scenario: The active provider is selected by configuration

- **WHEN** the provider selector is set to one of the configured adapters
- **THEN** that adapter serves every `EsignProvider` call, with no caller change

### Requirement: Secrets, config, and PII handling

Provider credentials - base URL, and per-provider authentication values (`app-id` / `api-key`
for ZOOP; auth token and webhook secret for Leegality) - SHALL come from environment variables
only. Non-secret provider settings (transaction expiry, the artifact host allowlist, the
provider selector) MAY be plain configuration.

A **per-transaction webhook key** issued by a provider SHALL be treated as a credential: it
SHALL be stored **encrypted at rest**, SHALL NOT be logged in any form, and SHALL be compared
in constant time.

The system SHALL NOT log Aadhaar numbers, OTPs, virtual IDs, signer PII, signing URLs, or
webhook and status payloads verbatim - identifiers SHALL be redacted before logging. Provider
status and webhook responses MAY carry **eKYC-derived signer PII** (the name read from
Aadhaar, given name, email, city, postal code, and a name-match score); this SHALL be treated
as signer PII, SHALL NOT be logged, and SHALL NOT be exposed beyond what a party needs to see
about their own signing request. A signing URL SHALL be treated as a bearer capability and
SHALL NOT appear in logs.

Optional provider features that widen the PII surface without a stated requirement - such as
capturing the signer's location or photograph - SHALL NOT be enabled.

Tests and local development SHALL NOT require real Aadhaar/OTP or live provider credentials
(sandbox + dummy data only), and the production provider host SHALL NOT be a default.

#### Scenario: Sensitive values are redacted in logs

- **WHEN** the system logs around create, status read, or webhook handling
- **THEN** signing URLs, signer PII, per-transaction keys, and payloads do not appear
  verbatim; only redacted identifiers are logged

#### Scenario: eKYC-derived signer data is not logged

- **WHEN** a status read or webhook returns the Aadhaar-derived name, given name, postal code,
  or name-match score
- **THEN** none of those values appear in logs

#### Scenario: Per-transaction webhook key is stored encrypted

- **WHEN** a per-transaction webhook key is persisted
- **THEN** it is encrypted at rest and is never returned by any API

#### Scenario: Tests run without live credentials

- **WHEN** the test suite runs with no real provider credentials configured
- **THEN** every signing-request test passes against a stubbed provider, requiring no real
  Aadhaar/OTP

#### Scenario: PII-widening provider options stay off

- **WHEN** a signing request is created
- **THEN** signer location capture and photo capture are not requested

## ADDED Requirements

### Requirement: Both parties are invited in a single provider call

Creating an eSign request for a multi-party agreement SHALL send **all** signers to the
provider in **one** call. The system SHALL NOT create one transaction per signer, and SHALL
NOT submit the same document to the provider more than once for the same signing request.

The request SHALL specify **sequential** signing order, so the owner signs before the tenant
and the resulting document carries both signatures. The provider's per-signer identifiers and
signing order SHALL be persisted so each invitee's status can be correlated back to the
canonical signer.

The document submitted SHALL be the **stamped** PDF (per `estamp-intake`), never the bare
draft. The encoded document size SHALL be checked against the provider's documented ceiling
before the call, and an oversized document SHALL be refused with a clear error rather than
sent.

#### Scenario: One call invites every party

- **WHEN** an eSign request is created for an agreement with an owner and a tenant
- **THEN** exactly one provider call is made carrying both signers, and one provider
  transaction id is recorded

#### Scenario: Signing order is preserved

- **WHEN** the provider returns per-signer identifiers and ordering
- **THEN** each is persisted against the matching canonical signer, and the recorded order
  places the owner before the tenant

#### Scenario: Oversized document is refused before the call

- **WHEN** the stamped PDF exceeds the provider's documented size ceiling once encoded
- **THEN** the request is refused with a clear error and no provider call is made

### Requirement: The provider delivers the signing invitations

The system SHALL request that the **provider** deliver each signer's invitation to the email
address held for that party, rather than the system emailing signing links itself.

The signing window SHALL be configurable and long enough for parties who sign days apart; it
SHALL NOT rely on a window measured in minutes. Where the provider supports extending a
pending transaction or re-sending an invitation, the system SHALL be able to invoke both
without creating a new transaction or incurring a second charge.

Any signing URL the provider returns SHALL be persisted as a bearer capability - usable as a
fallback if an invitation does not arrive - and SHALL never be logged.

#### Scenario: Invitations are sent by the provider

- **WHEN** an eSign request is created
- **THEN** the provider is instructed to send each signer an invitation, and the system sends
  no signing link of its own

#### Scenario: The signing window spans days

- **WHEN** a signing request is created
- **THEN** its expiry is set from configuration to a window measured in days, and the recorded
  expiry reflects it

#### Scenario: A pending request can be extended and re-invited

- **WHEN** a signing request is still pending and an operator extends it or re-sends the
  invitation
- **THEN** the same transaction continues, no new transaction is created, and no second charge
  is incurred

### Requirement: Signature placement is derived from the document's eSign anchors

Where a provider places signatures by page coordinates, the adapter SHALL derive those
coordinates from the `esign:<role>` anchors the renderer emitted, by locating each anchor in
the **stamped** PDF's text layer. The `documents` module SHALL NOT learn about any provider,
and no provider type SHALL cross the module boundary.

The translation SHALL account for the provider's coordinate origin, which may differ from
PDF's native bottom-left origin - including a horizontal axis measured from the **right**
edge, which requires mirroring. Placement SHALL be verified **visually** on a real rendered
document, because an incorrect origin produces wrongly-placed signatures with no error.

If an expected anchor cannot be located, the request SHALL be refused before the provider call
rather than signed at a default or guessed position.

#### Scenario: Each signer's coordinates come from their own anchor

- **WHEN** an eSign request is created for a document containing `esign:owner` and
  `esign:tenant` anchors
- **THEN** each signer's placement is derived from their own anchor's position in the stamped
  PDF

#### Scenario: Coordinate origin is translated correctly

- **WHEN** the provider measures the horizontal axis from the right edge of the page
- **THEN** the derived coordinate is mirrored accordingly, and a rendered test document shows
  each signature within its intended signature zone

#### Scenario: A missing anchor refuses the request

- **WHEN** an expected `esign:<role>` anchor cannot be located in the stamped PDF
- **THEN** the request is refused before the provider call, and no signature is placed at a
  default position

#### Scenario: Module boundaries hold for anchor translation

- **WHEN** the module-boundary verification runs
- **THEN** the anchor-to-coordinate translation lives inside the `signing` module's adapter
  package and no `documents` type crosses the boundary

### Requirement: Signing progress is visible per party

The system SHALL expose the signing progress of an agreement, showing **each party's**
individual status rather than only the aggregate.

A party SHALL be able to see the progress of their own agreement. The view SHALL NOT expose
another customer's agreement, and SHALL NOT expose eKYC-derived signer PII returned by the
provider, signing URLs belonging to other parties, or any provider credential.

#### Scenario: Per-party progress is visible

- **WHEN** an authorised caller reads the signing progress of an agreement mid-flow
- **THEN** the response shows each party's individual status and the aggregate state

#### Scenario: Progress does not leak PII or capabilities

- **WHEN** signing progress is read
- **THEN** the response contains no eKYC-derived signer data, no other party's signing URL,
  and no provider credential

#### Scenario: Progress is not readable across customers

- **WHEN** a caller requests the progress of an agreement they do not own and have no staff
  role for
- **THEN** the request is refused
