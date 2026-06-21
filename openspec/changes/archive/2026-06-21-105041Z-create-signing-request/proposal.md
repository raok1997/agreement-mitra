## Why

The signing module has an `EsignProvider` seam and an `Agreement` aggregate, but
nothing actually starts an eSign or reacts to its completion — `LeegalityEsignProvider`
and both controllers are stubs. This change is the **tracer bullet**: the first thin
end-to-end slice that creates a real Leegality (sandbox) sign request, returns the
per-signer signing URLs, and lets an inbound webhook drive the request to completion.
It proves the async signing architecture (create now, complete later via webhook) and
the vendor-behind-an-interface boundary before we invest in PDF rendering, stamping, or
signed-document download.

## What Changes

- **New `SigningRequest` aggregate** in the `signing` module — the home for the
  signing-status FSM (`SIGN_REQUESTED → SIGNED | FAILED | EXPIRED` for this CR). It
  references an agreement, stores the provider `documentId` and per-invitee
  `signUrl`/`expiryDate` (linked to the canonical `signer` row), carries an optimistic-lock
  `version`, and owns its transitions through a state machine (no ad-hoc setters). The
  `Agreement` aggregate stays **status-less** (CR-2 decision preserved).
- **Create endpoint** — implement `POST /api/signing/{agreementId}/request`: load the
  agreement, **persist the signing request before calling the provider** (so a DB failure
  can't orphan a live vendor document), call the adapter outside any open transaction, then
  update the row to `SIGN_REQUESTED` with the document id + signing URLs, and return them.
  The call **never blocks** on the signature.
- **Webhook endpoint** — implement `POST /api/webhooks/esign` as a **single** endpoint
  handling both the success and error channels through one path (no branch on an untrusted
  body field — see design D3). It verifies the body `mac` (constant-time), then treats
  the webhook as a **trigger only**: it calls the Leegality Details API for authoritative
  status and drives the FSM off *that*, never off the (unsigned) webhook body fields. It is
  hardened against replay (idempotent + Details-driven), returns an indistinguishable ack
  for unknown document ids (no existence oracle), and acks + defers to reconciliation if the
  Details API is down (no vendor-redelivery storm).
- **Leegality adapter** — implement `createSignRequest` (POST `v3.0/sign/request`),
  `getStatus` (GET `v3.3/document/details`, read `document.status` only), and
  `verifyWebhook`. `download()` stays a stub. **BREAKING (internal API):**
  `EsignProvider.verifyWebhook` drops its `signatureHeader` parameter — the Leegality
  `mac` lives in the JSON body, not an HTTP header.
- **DTO reshape (internal API):** `SignRequest` and `SignSession` become **multi-invitee**
  to match the multi-party agreement and the Leegality `invitees[]` contract.
- **Config + wiring:** add a `profileId` config value (config, not a secret) and a
  `RestClient` configured for Leegality (auth via `X-Auth-Token`, per-endpoint version).
- **V3 Flyway migration** for the `signing_request` table (unique provider document id,
  optimistic-lock `version`, bounded `status` type) and the per-invitee child table (FK to
  `signer`); JPA stays `ddl-auto: validate`.
- **Request body-size limits** on the create + webhook endpoints (a cheap DoS guard;
  full anti-abuse is deferred — see below).
- **WireMock** added as a test dependency to stub Leegality for adapter integration tests.

**Deferred to a follow-up `signing-auth` change (documented risk):** both endpoints remain
**unauthenticated** in this CR (no auth mechanism exists yet). Because create now egresses
signer PII to the vendor and can generate invites / burn quota, real ownership-based
authorization, rate-limiting, and security-event logging are deferred; sandbox + dummy data
only is the compensating control here.

## Capabilities

### New Capabilities
- `signing-request`: create an Aadhaar eSign request for an agreement through the
  `EsignProvider` seam, persist its status FSM, and complete it via a verified inbound
  webhook backed by an authoritative Details-API status read.

### Modified Capabilities
<!-- none — Agreement stays status-less (no requirement change to agreement-management);
     api-error-handling and the test/security capabilities are reused, not changed. -->

## Impact

- **Code (signing module):** `EsignProvider` (signature change), `LeegalityEsignProvider`
  (implement 3 of 4 methods), `SignRequest`/`SignSession` (reshape), new `SigningRequest`
  aggregate + repository + state machine, `SigningController` + `WebhookController`
  (implement), a new Leegality `RestClient` config and request/response DTOs in
  `.leegality`. Nothing outside the module references `.leegality`; `ModularityTests`
  stays green.
- **Schema:** new `V3__signing_request.sql` (forward-only; V1/V2 untouched).
- **Config:** `application.yml` gains `esign.leegality.profile-id`; secrets
  (`base-url`/`auth-token`/`webhook-secret`) still env-only.
- **Build:** WireMock test dependency added; `gradle.lockfile` regenerated; OSV scan
  must stay clean.
- **Cross-CR:** the verify→Details→FSM path is deliberately the same one the future
  scheduled reconciliation job (missed-hook fallback) will reuse. Signed-PDF/audit-trail
  download + object storage are explicitly deferred.

## PII / Security review checklist

- **New outbound PII flow?** Yes — signer name/email/phone go to Leegality in the create
  request, and the unsigned PDF is sent base64. This is **sandbox + dummy data only** in
  this repo; real Aadhaar/OTP are never required by tests or local dev (signers
  authenticate on the Leegality page, never via our API).
- **Secrets:** `base-url`/`auth-token`/`webhook-secret` from env only; `profileId` is
  non-secret config. No secret is logged or committed.
- **Redaction:** signing URLs, `documentId`, signer PII, and webhook payloads are
  **never logged verbatim** — log redacted identifiers (e.g. a short hash/last-4 of
  `documentId`) only. The webhook payload is not echoed to logs or to error responses.
- **Untrusted inbound webhook:** the body `mac` (HMAC-SHA1 over `documentId`) is verified
  (constant-time) before any side effect; on failure the request is rejected with no FSM
  change. Because the `mac` covers only `documentId`, the rest of the payload is treated as
  untrusted and the authoritative state is re-read from the Details API (the compensating
  control for the vendor-dictated weak HMAC). Replay of a captured valid webhook is harmless
  (idempotent + Details-driven), and the endpoint is not an existence oracle. Rate-limiting
  and security-event logging are deferred to `signing-auth`.
- **Endpoint authorization:** deferred — see the deferred-CR note above; accepted risk for
  the sandbox-only repo, with body-size limits as the only abuse guard in this CR.
- **Errors:** controller/webhook error responses use the existing RFC 9457 ProblemDetail
  contract and never echo PII or rejected input.
