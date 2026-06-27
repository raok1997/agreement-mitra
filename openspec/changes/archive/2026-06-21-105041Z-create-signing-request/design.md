## Context

The `signing` module has the seams but no behavior: `LeegalityEsignProvider` and both
controllers (`SigningController`, `WebhookController`) are stubs, and `SignatureStatus`
is an enum with no persisted home. CR-2 deliberately made `Agreement` **status-less**.
This change wires the first end-to-end Aadhaar eSign slice (create → webhook completion)
against the Leegality sandbox API behind the `EsignProvider` interface.

Confirmed Leegality facts this design relies on (researched, not re-derived):

- Hosts: sandbox `https://sandbox.leegality.com/api/`, prod `https://app1.leegality.com/api/`.
  `LEEGALITY_BASE_URL` is the host root; the adapter appends the **per-endpoint** version.
- Auth header is `X-Auth-Token` (not `Authorization: Bearer`).
- Versions are per-endpoint: create = `v3.0`, document-details = `v3.3`.
- Create: `POST {base}/v3.0/sign/request` with
  `{ profileId, file:{name, file:<base64-pdf>}, invitees:[{name,email,phone,aadhaarConfig:{verifyName}}] }`
  → `{ status, data:{ documentId, invitees:[{ signUrl, expiryDate }] } }`.
- Details: `GET {base}/v3.3/document/details?documentId=X` → `data.document.status` is the
  authoritative lifecycle state. The in-flight/success values are `DRAFT`/`SENT`/`COMPLETED`;
  terminal non-success outcomes (signer rejected, certificate-verification failure, document
  expired) are reported by the Details response too (exact tokens/fields — a `status` value
  vs. a sibling reason field — are pinned against the sandbox when provisioned; the adapter
  maps whatever the Details response reports for these outcomes, the tests are the contract
  until then). CR-3 reads document status/outcome only — it does not download file/auditTrail
  bytes.
- Webhook `mac` is in the JSON **body**; `mac = HMAC-SHA1(documentId, webhookSecret)`.

## Goals / Non-Goals

**Goals:**
- Create a real (sandbox) sign request through `EsignProvider` and return per-signer URLs.
- Persist a `SigningRequest` aggregate owning the `SIGN_REQUESTED → SIGNED|FAILED|EXPIRED`
  FSM, keeping `Agreement` status-less.
- Verify the inbound webhook, then drive the FSM off an authoritative Details-API read.
- Keep all vendor specifics behind the adapter; keep `ModularityTests` green.
- Full test pyramid against a stubbed Leegality (WireMock) — no live account.

**Non-Goals:**
- Signed-PDF / audit-trail download and object storage (MinIO/S3), and the 15s CDN-expiry
  handling — deferred to a later CR (`download()` stays a stub).
- Real PDF rendering — the unsigned PDF is server-sourced dummy bytes for this slice; the
  `documents` module integration comes later.
- A live sandbox manual happy-path (account not yet provisioned).
- The scheduled reconciliation job itself (only its shared code path is established here).
- **Authentication/authorization, rate-limiting, and security-event logging on the
  endpoints** — deferred to a dedicated follow-up `signing-auth` change (see D13). The
  endpoints stay unauthenticated in this CR, consistent with the existing API; the
  documented compensating control is sandbox + dummy data only, plus body-size limits.

## Decisions

### D1: A new `SigningRequest` aggregate is the FSM home (not status on `Agreement`)
`Agreement` is deliberately status-less (CR-2). The status belongs to a *signing attempt*,
which has its own lifecycle, provider `documentId`, and per-invitee URLs — so it is its
own aggregate (`in.agreementmitra.signing.signingrequest`, package-private entity reached
only through a service, mirroring the `agreement` package). One agreement can have signing
requests over time. **Alternative considered:** add `SignatureStatus` + provider fields to
`Agreement` — rejected: reverses a deliberate decision and conflates the contract with one
attempt to sign it.

### D2: Webhook is a trigger; the Details API is the source of truth
The `mac` signs only `documentId`, so status/action/signer in the body are unauthenticated
and untrusted. After verifying `mac`, the handler calls the Details API and maps
`document.status` → FSM. **Alternative:** trust the body's status field — rejected: forge-able
(the body isn't covered by the mac). This Details-read path is intentionally identical to
the one the future reconciliation job reuses, so missed hooks self-heal.

### D3: One webhook endpoint, no untrusted body-field branch
A single `POST /api/webhooks/esign` handles both the Success and Error channels through one
unified verify → Details → FSM path. **Alternative:** two endpoints (success/error) — rejected:
both run the identical path; one endpoint avoids duplicated security-critical code and a second
permit rule. **We deliberately do NOT branch on a body field** such as `webhookType`/status: the
`mac` covers only the document id, so those fields are untrusted, and the authoritative state is
re-read from the Details API regardless of channel — a body-field branch would add nothing but a
spoofable input. (Should the channel ever be needed for *observability only*, it could be logged
redacted, never used to decide an outcome.)

### D4: DTOs reshaped to multi-invitee
`SignRequest` carries the agreement id, the unsigned PDF, and a list of invitees
(name/email/phone + Aadhaar `verifyName`); `SignSession` carries `documentId` + a list of
per-invitee `{signUrl, expiryDate}`. This matches the multi-party `Agreement` and the
Leegality `invitees[]` contract. **Alternative:** keep single-signer DTOs and loop — rejected:
one provider call creates all invitees; single-signer shape would misrepresent the API.

### D5: `EsignProvider.verifyWebhook` drops `signatureHeader` and surfaces the documentId
New signature: `verifyWebhook(String payload)` returning the **verified document id**
(e.g. `Optional<String>`: present when the `mac` matches, empty when it fails). The `mac`
is in the body, so a transport header parameter is misleading; the adapter parses both
`mac` and `documentId` from the payload. Returning the documentId (rather than a bare
boolean) keeps the controller vendor-neutral — it never parses the Leegality payload
itself, it just takes the verified id and looks up the `SigningRequest`. Internal-API
breaking change confined to the module (controller + adapter + tests).

### D6: A dedicated Leegality `RestClient` bean
A `RestClient` configured with the base URL and a default `X-Auth-Token` header, built in
the adapter's config (constructor injection). Version path segment is appended per call
(`v3.0/...`, `v3.3/...`). `RestClient` (Spring 6) over `RestTemplate` (modern, synchronous —
fine here since the create call is the only blocking hop and returns fast) and over
`WebClient` (no need to pull in reactive for two calls).

### D7: PII redaction helper
A small redaction helper (e.g. last-4 / short hash of `documentId`, signer count instead of
names) used at every log site. Signing URLs, signer PII, and webhook payloads are never
logged verbatim; the webhook body is never echoed in an error response.

### D8: Persistence shape
`signing_request` row: `id` (UUID, server-assigned), `agreement_id` (FK),
`provider_document_id` (UNIQUE, nullable until the provider call returns — see D9),
`status` (`varchar(32)`, mapped `@Enumerated(STRING)` against the FSM subset),
`version` (optimistic-lock column — see D10), `created_at`. Per-invitee signing URLs in a
child table `signing_request_invitee` (`id`, `signing_request_id` FK, `signer_id` FK,
`sign_url`, `expiry`) — the `signer_id` FK preserves the link to the canonical signer row
(we join the Leegality response to a signer by email, then persist the FK, not the email).
URLs are sandbox/dummy in this repo. `V3__signing_request.sql`; JPA stays `validate`. The
`status` column type is pinned so the boot-time `validate` is deterministic.

### D9: Persist intent before the provider call (no lost completion, no tx across HTTP)
The create flow persists the `signing_request` row **before** calling Leegality (in a
pre-request state, `provider_document_id` null), commits, then calls the provider
**outside any open transaction**, then updates the row to `SIGN_REQUESTED` with the
returned `documentId` + URLs in a second short transaction. This closes the
provider-succeeds-but-DB-fails gap: a live vendor document always has a local row to
reconcile against, and no DB connection is held across the network round-trip.
**Alternative:** call provider first, persist after (the naive order) — rejected: a post-call
DB failure orphans a live document and the later webhook no-ops on an unknown id, silently
losing the signature.

### D10: Optimistic locking on the FSM
The `SigningRequest` carries a JPA `@Version`. Concurrent/duplicate webhook deliveries that
both read `SIGN_REQUESTED` and try to transition will collide on the version; the loser gets
an optimistic-lock failure and is retried (re-reading current state) or safely dropped. Combined
with idempotent same-terminal transitions (D-spec), this makes redelivery and races safe
without DB-level locks. **Alternative:** pessimistic lock / `SELECT … FOR UPDATE` — rejected:
heavier, and the contention here is rare (a few webhooks per document).

### D11: Webhook resilience — Details-API failure defers to reconciliation
If the post-verify Details API read fails (timeout/unreachable/error), the handler **acks the
webhook** (no transition) rather than returning 5xx. Returning an error would make Leegality
redeliver and amplify load against an already-struggling Details API; the future reconciliation
job (which uses this very path) will complete the request. The Details call is kept off any DB
transaction. **Alternative:** 5xx to force vendor retry — rejected: redelivery storms + no
bound on attempts; reconciliation is the designed fallback.

### D12: Replay & enumeration hardening
The `mac` (HMAC-SHA1 over `documentId`) is replay-able by anyone who captures a valid webhook.
The compensating controls: (a) transitions are **idempotent + Details-driven**, so replaying a
valid webhook re-reads truth and changes nothing beyond the legitimate transition; (b) MAC
comparison is **constant-time** (no timing oracle on the secret); (c) a verified webhook returns
the **same ack whether or not the documentId is known**, so the endpoint isn't an existence
oracle. Full anti-abuse (rate-limit, nonce) is out of scope (D13).

### D13: AuthZ / abuse hardening deferred to a `signing-auth` CR
Both endpoints stay unauthenticated in this CR (no auth mechanism exists yet; CR-2 left the API
open). Because create now egresses signer PII to the vendor and can spam invites / burn quota,
this is a **documented, accepted risk** for the sandbox-only repo. This CR adds only a cheap,
universal guard — **request body-size limits** on both endpoints (DoS/memory-pressure). Real
ownership-based authorization (caller may only request signing for their own agreement),
rate-limiting, and redacted security-event logging of verify-failures/enumeration attempts are
deferred to a dedicated follow-up `signing-auth` change. **Alternative:** build authZ now —
rejected: requires an identity mechanism that doesn't exist, far beyond a tracer bullet.

## Async create → webhook completion flow

```
Client                SigningController         EsignProvider(Leegality)        Leegality API           SigningRequest store
  | POST /signing/{id}/request |                        |                            |                          |
  |--------------------------->|                        |                            |                          |
  |                            | load agreement + dummy PDF (server-sourced)         |                          |
  |                            | persist row (pre-request, no documentId) [tx1] ------------------------------->|
  |                            | build SignRequest      |                            |                          |
  |                            |----------------------->| POST v3.0/sign/request (NO open tx)                    |
  |                            |                        |--------------------------->|                          |
  |                            |                        |  {documentId, invitees[]}  |                          |
  |                            |                        |<---------------------------|                          |
  |                            | SignSession            |                            |                          |
  |                            |<-----------------------|                            |                          |
  |                            | update row -> SIGN_REQUESTED + documentId + urls [tx2] ----------------------->|
  |  201 {documentId, urls[]}  |                        |                            |   (if tx2 fails, row from |
  |<---------------------------|                        |                            |    tx1 remains: reconcilable)|
  |        ( ... signer authenticates with Aadhaar + OTP on the Leegality page, out of band ... )               |
                                                        |                            |                          |
WebhookController        EsignProvider(Leegality)       |                            |                          |
  | POST /webhooks/esign {..., mac} |                   |                            |                          |
  |-------------------------------->| verifyWebhook(body)|                           |                          |
  |                                 | mac==HMAC-SHA1(documentId, secret)?            |                          |
  |                                 |---- reject if not (no state change) ----       |                          |
  |                                 | getStatus(documentId) --> GET v3.3/document/details                       |
  |                                 |------------------------------------------>| {document.status}             |
  |                                 |<------------------------------------------|                              |
  |                                 | map status -> SIGNED|FAILED|EXPIRED       |                              |
  |                                 | transition FSM via state machine --------------------------------------->|
  |   202 Accepted                  |                                                                          |
  |<--------------------------------|                                                                          |
```

## Risks / Trade-offs

- **HMAC-SHA1 is weak (vendor-dictated).** → We cannot change the algorithm; the
  compensating control is treating the webhook as a trigger only and re-reading
  authoritative state from the Details API (D2). Documented as a known vendor constraint.
- **Webhook covers only `documentId` → forgery/replay.** A valid `mac` binds to its own
  documentId (can't be retargeted), but an attacker who captures a valid webhook can replay
  it, or who learns a real `documentId` + secret could forge. → Mitigated by: secret env-only,
  constant-time compare, and the Details-API re-read making the *outcome* authoritative +
  idempotent so a replay/forge changes nothing beyond the legitimate transition (D2/D12).
  Rate-limit/nonce-level anti-abuse is deferred (D13).
- **Unauthenticated, side-effecting create endpoint.** Anyone with an agreement UUID can
  trigger outbound PII egress + invites + quota burn. → Accepted, documented risk for the
  sandbox-only repo; body-size limits added now; real authZ + rate-limit deferred to
  `signing-auth` (D13).
- **Provider-success / DB-failure split.** → D9 persists intent before the provider call so a
  live vendor document always has a reconcilable local row; no tx is held across the HTTP hop.
- **Concurrent / duplicate webhooks.** Leegality may re-deliver, possibly to multiple instances.
  → Optimistic locking (`@Version`, D10) + idempotent same-terminal transitions + illegal-
  transition rejection make races and redelivery safe.
- **Details API down when a webhook arrives.** → Ack and defer to reconciliation (D11); never
  5xx-storm the vendor into redelivery.
- **Endpoint as an existence oracle.** → Verified webhooks return an indistinguishable ack
  whether or not the documentId is known (D12).
- **Extra Details API call per webhook** adds latency/quota use. → Acceptable: correctness
  over a saved round-trip; it's the same call reconciliation needs anyway.
- **Body-size limit could reject a legitimately large PDF.** → Limit sized for the dummy/expected
  agreement PDF; revisit when real rendering lands.
- **Sending signer PII + unsigned PDF outbound.** → Sandbox + dummy data only in this repo;
  PDF bytes are server-sourced (not client-settable); redaction on all log sites; secrets
  env-only; signing URLs treated as bearer capabilities (never logged).

## Migration Plan

- Add `V3__signing_request.sql` (forward-only; V1/V2 untouched). Regenerate `gradle.lockfile`
  after adding the WireMock test dependency; OSV scan must stay clean.
- No rollback of data needed (additive table). If reverted before release, drop `V3` is a
  manual forward migration in a later change (never edit an applied migration).

## Open Questions

- None blocking. Profile/invitee field nuances (e.g. whether `phone` is mandatory in sandbox)
  will be confirmed when the live sandbox account is provisioned; tests pin the documented
  shape and are the contract until then.

## Follow-up changes (out of scope here)

- **`signing-auth`** — ownership-based authorization on the create endpoint (caller may only
  request signing for their own agreement), rate-limiting on create + webhook, and redacted
  security-event logging of webhook verify-failures / documentId-enumeration attempts.
- **Reconciliation job** — the scheduled missed-hook fallback that reuses the verify→Details→FSM
  path; also recovers any pre-request rows whose provider call/update was interrupted (D9).
- **Signed-document download** — `download()` + audit-trail bytes + object storage (MinIO/S3) +
  CDN-expiry handling, alongside stamp-composition/documents.
