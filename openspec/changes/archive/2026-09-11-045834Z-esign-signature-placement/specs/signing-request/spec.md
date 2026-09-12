## MODIFIED Requirements

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
correlation). Each invitee SHALL carry an **ordered set of signature placements** rather
than a single anchor, so that a signer can be placed in more than one position on the
instrument without any adapter inventing extra positions of its own; an adapter that
supports only one position SHALL use the first and SHALL NOT silently drop the rest.
The authoritative-status read SHALL return a vendor-neutral **per-invitee**
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

#### Scenario: An invitee carries every placement it was given

- **WHEN** a create request is built for a signer who is to be placed both on the
  execution page and on every page
- **THEN** the vendor-neutral invitee carries both placements in order, and the adapter
  submits both rather than only the first
