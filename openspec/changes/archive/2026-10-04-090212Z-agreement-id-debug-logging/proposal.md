## Why

An agreement id is a bearer capability: holding the UUID is what grants anonymous read, draft upload,
finalise, contact edit and payment on that agreement (`docs/DEPLOYMENT.md` "never log an agreement
id"). Yet twelve log statements print it raw — three at INFO — and `application.yml` defaults
`in.agreementmitra` to DEBUG in every environment, so anyone with log access holds working
credentials. Raised by `anonymous-surface-abuse-controls` as the `agreement-id-debug-logging`
follow-up (priority High).

## What Changes

- Add `AgreementIds`, a static helper in the root `in.agreementmitra` package (beside `SecurityEvents`) that renders an agreement id as its
  first 8 hex characters plus an ellipsis (`1a2b3c4d…`), and rewrites any UUID embedded in a string
  (blob keys, messages) the same way. 8 hex = 32 of the UUID's 122 random bits, leaving 90 bits
  unknown — not a usable credential, still correlatable to a row with `id::text LIKE '1a2b3c4d%'`.
- Route every log statement that carries an agreement id through it: `DraftService`,
  `PaymentService` (×2), `SigningRequestService` (×4), `StampIntakeService`,
  `AgreementDocumentService` (INFO), `SignedDocumentDeliveryService` (×2, INFO), and
  `MinioBlobStore` (logs blob keys such as `drafts/<agreementId>.pdf`).
- Redact the agreement id in every exception message the signing module builds by concatenating one
  (20 `"Agreement not found: "` sites, `"No signed document: "`, `"Agreement vanished: "`, MinIO
  `"Failed to store/read object " + key`). Spring's `ExceptionHandlerExceptionResolver` logs every handled one at
  WARN on the request path (`Resolved [ResourceNotFoundException: Agreement not found: <id>]`), and an uncaught
  one on a scheduled/async path is logged at ERROR. Both loggers are outside `in.agreementmitra`, so the level
  change does not cover them.
- Redact the agreement id (and id-bearing blob keys) in the hand-written `toString()` of `Agreement`,
  `SigningCompletionView`, `StaffAgreementView`, `StampQueueEntry` and `StampInfo`, and add redacting
  overrides to the internal records that carry it (`PaymentConfirmation`, `PaymentConfirmedEvent`,
  `RazorpayClient.ProviderOrder`, `SignRequest`).
  Response DTO records (serialised to JSON, never logged) are left alone.
- **BREAKING (ops)**: default `logging.level.in.agreementmitra` to `INFO`; the `local` profile keeps
  `DEBUG`. Correct the `LOGGING_LEVEL_IN_AGREEMENTMITRA` row in `docs/DEPLOYMENT.md`.
- Add regression guards: a log-capture integration test over an agreement's lifecycle at DEBUG
  asserting the raw id never appears, INFO-line assertions for the closure/fallback lines, and a
  source-scan unit test that fails when a log call or exception construction carries an agreement id
  without `AgreementIds`.
- Pin the existing Razorpay/Leegality/Zoop log-redaction tests to DEBUG through a shared `LogCapture`
  test extension — under the new INFO default their absence assertions would otherwise pass over an
  empty capture.
- Default the JDBC URL to `logServerErrorDetail=false`: Hibernate logs pgjdbc's unique-violation `Detail`
  (`Key (agreement_id)=(<uuid>)`) at ERROR by default on the checkout race path.
- Warn in `docs/DEPLOYMENT.md` that raising framework loggers (Spring Web DEBUG, Hibernate bind TRACE,
  root DEBUG) logs raw ids through request URIs and bind values.

Out of scope: signing-request, delivery and identity ids (internal, not capabilities — never in a
public route); tracking references (authorise nothing alone); framework loggers raised above their defaults by an operator (documented in `DEPLOYMENT.md`, not redacted);
the agreement id sent to vendors as a Razorpay `receipt` / ZOOP client reference (not a log line); request/access logging (none exists).

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `backend-security-baseline`: adds requirements that agreement ids never appear raw in application
  logs, exception messages or `toString()` output, and that the application logger defaults to INFO
  outside the local profile.

## Impact

- **Code**: new `in.agreementmitra.AgreementIds`; edits in the `signing` module only (`agreement`,
  `payment`, `signingrequest`, `delivery`, `storage`, `api` sub-packages and `SigningCompletionView`).
  `application.yml`, `application-local.yml`. Tests: new `LogCapture` support extension, three retrofitted
  log-redaction tests.
- **Docs**: `docs/DEPLOYMENT.md` level row; `docs/ROADMAP.md` register row closed on archive.
- **Signing FSM**: no transition touched — log text only, no behavior change on the signing flow.
- **APIs / data / dependencies**: none. Response bodies are unchanged (problem details already use
  constant text).
- **Ops**: production deployments that relied on the `DEBUG` default lose debug lines unless they set
  `LOGGING_LEVEL_IN_AGREEMENTMITRA=DEBUG`; `deploy/env/backend.env.example` already sets `INFO`.

### PII / security checklist

- Introduces or moves Aadhaar/OTP/VID/PII or secrets: **none** — it removes a credential (the
  agreement id) from logs; no new outbound flow.
- Redaction: 8-hex prefix via `AgreementIds`; existing provider-id, email and certificate redaction
  unchanged.
- Sandbox + dummy data only: preserved; tests use random UUIDs.
