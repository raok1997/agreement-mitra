## MODIFIED Requirements

### Requirement: Signing request owns the status FSM

A signing request SHALL be a persisted aggregate that owns the signing-status state
machine. For this capability the lifecycle is `SIGN_REQUESTED → SIGNED | FAILED |
EXPIRED`. State changes SHALL go through the aggregate's state-machine method, not
ad-hoc setters. `SIGNED`, `FAILED`, and `EXPIRED` are terminal: a transition out of a
terminal state SHALL be rejected. The `Agreement` aggregate SHALL remain status-less —
the signing status lives only on the signing request.

The aggregate FSM transition SHALL be driven by **aggregating per-invitee statuses**,
not a single document-level status. Each invitee of the request SHALL have a recorded
per-invitee status (`PENDING`, `SIGNED`, `REJECTED`, or `EXPIRED`), which is a sub-state
of the aggregate's `SIGN_REQUESTED` state (not a new aggregate FSM state). The
aggregation rule SHALL be: if **any** invitee is `REJECTED` the request transitions to
`FAILED`; else if **any** invitee is `EXPIRED` the request transitions to `EXPIRED`;
else if **all** invitees are `SIGNED` the request transitions to `SIGNED`; otherwise the
request stays `SIGN_REQUESTED` (a safe no-op). `FAILED` SHALL outrank `EXPIRED`. The
terminal decision SHALL depend only on the set of per-invitee statuses relative to the
invitee count, so it cannot be corrupted by a per-invitee-to-row correlation mismatch.

Concurrent transition attempts (e.g. webhook delivered more than once or to more than
one instance at the same time) SHALL be made safe by optimistic locking (a version
column): at most one of two racing transitions SHALL win and the other SHALL be retried
or rejected without corrupting state.

#### Scenario: Legal completion transition is accepted

- **WHEN** a signing request in `SIGN_REQUESTED` is driven to `SIGNED`, `FAILED`, or
  `EXPIRED`
- **THEN** the transition is applied and persisted

#### Scenario: Illegal transition is rejected

- **WHEN** a transition is attempted from a terminal state (e.g. `SIGNED → FAILED`), or
  to a state not reachable from the current one
- **THEN** the state machine rejects it and the persisted status is unchanged

#### Scenario: Redundant terminal transition is idempotent

- **WHEN** a webhook drives a signing request to a terminal state it is already in
- **THEN** the operation is a no-op (the status and the persisted row are unchanged) and
  no error is raised

#### Scenario: Concurrent transitions do not corrupt state

- **WHEN** two verified webhooks for the same document id are processed concurrently
- **THEN** optimistic locking ensures only one transition is committed; the persisted
  status is a single legal terminal value, never an interleaved/corrupted one

#### Scenario: Request stays non-terminal until every invitee has signed

- **WHEN** the resolved per-invitee statuses show some invitees `SIGNED` and at least one
  still `PENDING`
- **THEN** no aggregate transition is applied and the request stays `SIGN_REQUESTED`

#### Scenario: All invitees signed drives SIGNED

- **WHEN** every invitee of a `SIGN_REQUESTED` request is resolved to `SIGNED`
- **THEN** the request transitions to `SIGNED`

#### Scenario: A single rejection drives FAILED regardless of others

- **WHEN** at least one invitee is `REJECTED` (even if others have `SIGNED`)
- **THEN** the request transitions to `FAILED`, taking precedence over any `EXPIRED`
  invitee

### Requirement: Authoritative status comes from the Details API, not the webhook body

Because the `mac` covers only the document id, the rest of the webhook payload SHALL be
treated as untrusted. After verifying the `mac`, the system SHALL call the provider
Details API to read the authoritative **per-invitee** statuses and SHALL drive the
signing request's FSM off the aggregation of those statuses (per the aggregation rule in
"Signing request owns the status FSM") — never off status/action fields in the webhook
body, and never off a single document-level status. The per-invitee event-to-status
mapping SHALL be: signer signed → `SIGNED`; signer rejected or certificate verification
failed → `REJECTED`; signer's invitation expired → `EXPIRED`; otherwise → `PENDING`. A
non-terminal aggregate result (not all signed, none rejected/expired) SHALL cause **no
FSM transition** (a safe no-op), not an error.

If the Details API call fails (unreachable, timeout, or error), the system SHALL
acknowledge the webhook without applying a transition, leaving completion to the
scheduled reconciliation job — it SHALL NOT return an error that induces unbounded
vendor redelivery. This Details-API-as-source-of-truth path SHALL be the same code path
reused by that reconciliation job for missed webhooks.

#### Scenario: Completion drives SIGNED off the Details API

- **WHEN** a verified webhook triggers a status read and the Details API reports every
  invitee has signed
- **THEN** the signing request transitions to `SIGNED`

#### Scenario: Rejection or certificate failure drives FAILED

- **WHEN** a verified webhook triggers a status read and the Details API reports any
  invitee rejected or certificate verification failed
- **THEN** the signing request transitions to `FAILED`

#### Scenario: Expiry drives EXPIRED

- **WHEN** a verified webhook triggers a status read and the Details API reports the
  document/invitation expired with no rejection
- **THEN** the signing request transitions to `EXPIRED`

#### Scenario: Partial signing is a safe no-op

- **WHEN** a verified webhook triggers a status read and the Details API reports some but
  not all invitees signed (none rejected/expired)
- **THEN** no FSM transition is applied and no error is raised

#### Scenario: Details API failure defers to reconciliation

- **WHEN** a verified webhook triggers a status read and the Details API call fails
- **THEN** the system acknowledges the webhook, applies no transition, and leaves
  completion to the reconciliation fallback (no error that forces vendor redelivery)

#### Scenario: Webhook body status is not trusted

- **WHEN** a verified webhook body claims a status that disagrees with the Details API
- **THEN** the system uses the Details API per-invitee statuses, not the body's claim

### Requirement: Provider specifics stay behind the EsignProvider seam

All Leegality-specific details SHALL live behind the `EsignProvider` interface in the
provider adapter package — the base URL, per-endpoint API version, `X-Auth-Token` auth,
request/response shapes, and `mac` algorithm. No code outside the `signing` module SHALL
reference the adapter package, and `ModularityTests` SHALL remain green.

`EsignProvider` SHALL expose webhook verification **without a transport-header
parameter** (`verifyWebhook(payload)`; the `mac` and document id are parsed from the body
by the adapter). Verification SHALL surface the **verified document id** to the caller
(present when the `mac` matches, absent on failure) so the controller can look up the
signing request without itself parsing the vendor payload. The provider request/response
value objects SHALL be vendor-neutral and multi-invitee: the create request carries a
list of invitees, and the create result carries the document id plus a per-invitee
signing URL, expiry, and the provider's per-invitee identifier (captured for later
correlation). The authoritative-status read SHALL return a vendor-neutral **per-invitee**
status view (each invitee's status plus a correlation token — the provider per-invitee id
when available, an ordinal otherwise — with non-terminal/`PENDING` representable), not a
single document-level status. Downloading the signed document and audit trail SHALL be
implemented: given a provider document id it SHALL return the signed PDF bytes and the
audit-trail bytes **each with its provider-declared content type** (default
`application/octet-stream` when absent), staying vendor-neutral.

#### Scenario: Module boundaries hold

- **WHEN** the module-boundary verification runs
- **THEN** no module outside `signing` references the provider adapter package and the
  verification passes

#### Scenario: Webhook verification needs no transport header

- **WHEN** the webhook is verified
- **THEN** verification uses only the payload (the `mac` is parsed from the body), with
  no HTTP-header signature parameter

#### Scenario: Status read returns per-invitee statuses

- **WHEN** the authoritative-status read is invoked for a document id
- **THEN** the provider returns a vendor-neutral per-invitee status view (one status per
  invitee), not a single collapsed document status

#### Scenario: Signed-document download returns the signed artifact

- **WHEN** the signed-document download is invoked for a completed document id
- **THEN** the provider returns the signed PDF bytes and the audit-trail bytes (no
  not-yet-supported error)

### Requirement: Signing-request schema is Flyway-managed

The `signing_request` table and its per-signer signing-URL child table SHALL be created
by forward-only Flyway migrations; the JPA mapping SHALL match the migrated schema so the
application boots under `ddl-auto: validate`. The `signing_request` table SHALL carry a
foreign key to `agreement`, a unique constraint on the provider document id, and a
version column for optimistic locking; its `status` column SHALL have an explicit,
bounded type sized for the status vocabulary. The per-signer child table SHALL carry a
foreign key to `signer` (not just an email string).

Signing-completion state SHALL be added by a new forward-only migration
(`V4__signing_completion.sql`) that SHALL NOT edit any existing migration: the per-signer
child table SHALL gain a bounded per-invitee `status` column and a `signing_order`
ordinal, and the `signing_request` table SHALL gain `signed_pdf_key` and
`audit_trail_key` columns that hold **object-storage keys** (never artifact bytes). The
new columns SHALL be nullable so existing rows validate.

#### Scenario: Application boots against the migrated schema

- **WHEN** the application starts against a database where the Flyway migrations
  (including `V4`) have been applied
- **THEN** Hibernate schema validation passes and the context starts

#### Scenario: V4 does not edit an applied migration

- **WHEN** the signing-completion schema change is introduced
- **THEN** it ships as a new `V4__signing_completion.sql` and V1–V3 are left unchanged
