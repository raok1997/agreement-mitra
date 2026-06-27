# Tasks — create-signing-request (CR-3)

## 1. Config & dependencies

- [x] 1.1 Add `esign.leegality.profile-id` to `application.yml` (non-secret config; leave
  `base-url`/`auth-token`/`webhook-secret` env-only). Pin a dummy `profile-id` in
  `application-test.yml` so the context boots without real config.
- [x] 1.2 Add a Leegality `RestClient` config (base-url + default `X-Auth-Token` header,
  constructor-injected) in the `.leegality` package; version segment appended per call.
- [x] 1.3 Add the WireMock test dependency to `backend/build.gradle.kts` and regenerate
  `gradle.lockfile` (`./gradlew dependencies --write-locks`). Run the OSV scan **now** to
  confirm WireMock's transitives (Jetty/Jackson) introduce no flagged advisory — fix/suppress
  per policy before proceeding, not at the end.
- [x] 1.4 Add request body-size limits covering `POST /api/signing/*/request` and
  `POST /api/webhooks/esign` (e.g. Spring max request/multipart size or an explicit guard) so
  an oversized body is rejected before provider work.

## 2. Persistence (V3 migration + aggregate)

- [x] 2.1 Write `V3__signing_request.sql`: `signing_request` (id, agreement_id FK,
  provider_document_id `UNIQUE` nullable, status `varchar(32)`, `version` bigint NOT NULL,
  created_at) + `signing_request_invitee` (id, signing_request_id FK, **signer_id FK**,
  sign_url, expiry) + FK indexes. Do not edit V1/V2.
- [x] 2.2 Create the `SigningRequest` aggregate (package-private entity in
  `signing.signingrequest`) with: a factory for the pre-request state, a `@Version` field, and
  a **state-machine** transition method (`SIGN_REQUESTED → SIGNED | FAILED | EXPIRED`; terminal
  states; idempotent same-terminal; reject illegal; non-terminal status = no-op). Map `status`
  with `@Enumerated(STRING)`. Store invitee URL/expiry as a child collection linked by signer.
- [x] 2.3 Add the `SigningRequestRepository` (Spring Data) with lookup by
  `provider_document_id`, and a package-private `SigningRequestService` exposing: (a) persist
  pre-request row, (b) attach provider result → `SIGN_REQUESTED`, (c) webhook-driven transition.
  No ad-hoc setters.

## 3. EsignProvider seam + DTO reshape

- [x] 3.1 Reshape `SignRequest` to multi-invitee (agreementId, server-sourced unsigned PDF,
  list of invitee {name, email, phone, verifyName}) and `SignSession` to (documentId, list of
  {signUrl, expiryDate}). Update the `EsignProvider` interface accordingly. Keep value objects
  vendor-neutral.
- [x] 3.2 Change `EsignProvider.verifyWebhook` to drop the `signatureHeader` param and
  return the verified document id (`verifyWebhook(String payload)` → `Optional<String>`,
  empty on failure); ensure `getStatus` can represent a non-terminal status. Update all
  references.

## 4. Leegality adapter

- [x] 4.1 Implement `createSignRequest`: map `SignRequest` → create body
  (`profileId`, `file{name,file:<base64>}`, `invitees[...aadhaarConfig.verifyName]`),
  POST `v3.0/sign/request`, map response → `SignSession` (documentId + per-invitee
  signUrl/expiryDate). Internal Leegality request/response DTOs stay in `.leegality`.
- [x] 4.2 Implement `getStatus`: GET `v3.3/document/details?documentId=X`, read
  `data.document.status` only, map (completed→SIGNED, rejected/cert-fail→FAILED,
  expired→EXPIRED, in-flight/SENT→non-terminal). Do not fetch file/auditTrail bytes.
- [x] 4.3 Implement `verifyWebhook`: parse `mac` + `documentId` from the body, compute
  `HMAC-SHA1(documentId, webhookSecret)`, **constant-time** compare. Keep `download()` a stub
  (`UnsupportedOperationException` TODO).
- [x] 4.4 Add a redaction helper and apply it at every log site (redact documentId, omit
  signer PII/signing URLs; never log the webhook payload verbatim).

## 5. HTTP endpoints

- [x] 5.1 Implement `SigningController POST /api/signing/{agreementId}/request`: load
  agreement (404 problem+json if absent; 400 for non-UUID), **persist the pre-request row
  first**, build `SignRequest` (server-sourced dummy PDF) from its signers, call the adapter
  **outside any open transaction**, update the row to `SIGN_REQUESTED` with documentId + URLs,
  return `201` with documentId + per-signer URLs. Never block on signing.
- [x] 5.2 Implement `WebhookController POST /api/webhooks/esign` (single endpoint,
  `consumes=application/json`, one unified path — no branch on an untrusted body field):
  call `verifyWebhook(payload)` →
  verified documentId (reject with no side effect on failure), look up `SigningRequest` by
  that documentId, call `getStatus`, drive the FSM. On unknown documentId or a
  Details-API failure, **ack indistinguishably** (no transition, defer to reconciliation).
  Errors via RFC 9457; never echo the payload.
- [x] 5.3 Update `SecurityConfig`: confirm both routes are permitted, and update the now-stale
  webhook `(deferred)` HMAC comment. Update `SecurityBaselineIntegrationTest` if it asserts the
  old posture. No payload/PII leakage in any error path.

## 6. Unit tests (no Spring, no network)

- [x] 6.1 `mac` verification: valid accepted; tampered mac rejected; mac for a different
  documentId rejected; constant-time path exercised.
- [x] 6.2 Create request mapping (SignRequest → Leegality body) and response mapping
  (Leegality response → SignSession), including multi-invitee.
- [x] 6.3 Details `document.status` → `SignatureStatus` mapping (incl. non-terminal SENT) and
  webhook event → FSM mapping (all four outcomes).
- [x] 6.4 `SigningRequest` state machine: legal transitions; illegal/terminal rejected;
  same-terminal idempotent; non-terminal status = no-op.
- [x] 6.5 Redaction helper hides documentId/PII/URLs.

## 7. Integration tests (stubbed Leegality, Testcontainers for persistence)

- [x] 7.1 Adapter against WireMock: `createSignRequest` hits `v3.0/sign/request` with
  `X-Auth-Token` and correct body, maps the response; `getStatus` hits
  `v3.3/document/details` and maps status.
- [x] 7.2 Create-endpoint slice: valid agreement → `201` + persisted `SIGN_REQUESTED`;
  response returns without waiting on completion (non-blocking); unknown agreement → `404`
  problem+json with nothing persisted and no provider call; oversized body rejected.
- [x] 7.3 Webhook-endpoint slice: valid `mac` → Details read → FSM transition persisted;
  tampered/forged `mac` → rejected, no FSM change; replay of a valid webhook → no extra change;
  unknown documentId → indistinguishable ack, no change; Details-API failure (WireMock 5xx) →
  acked, no transition.
- [x] 7.4 Concurrency: two concurrent verified webhooks for the same documentId → optimistic
  locking yields a single legal terminal state (no corruption).
- [x] 7.5 Keep `ModularityTests` green (no external reference to `.leegality`).

## 8. Verify

- [x] 8.1 Run `./run-tests.sh` (or `./gradlew check`) — all tests + `ModularityTests` +
  coverage + security scan green; confirm no secrets/PII/signing-URLs in logs.
