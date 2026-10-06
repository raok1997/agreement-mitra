## Context

The agreement UUID (`UUID.randomUUID()`, `Agreement.java:262`) is the only credential on the anonymous
capability surface (`SecurityConfig` permits `GET /api/agreements/*`, draft, finalise, contacts,
payment, preview, stamp-quote and signing progress on it; recovery links embed it). `docs/DEPLOYMENT.md:42-47`
says never log it. Today:

- 12 log statements carry it — 9 at DEBUG, 3 at INFO (`AgreementDocumentService:206`,
  `SignedDocumentDeliveryService:266,290`). `MinioBlobStore:42` logs blob keys that embed it
  (`drafts/<id>.pdf`, `stamped/<id>.pdf`, `estamp-scans/<id>`).
- `application.yml:406-408` sets `in.agreementmitra: DEBUG` for every profile; there is no logback XML
  and no `application-sandbox.yml` (production runs the `sandbox` profile). `deploy/env/backend.env.example`
  already overrides to INFO, but the shipped default and `DEPLOYMENT.md:307` say DEBUG.
- Exception messages embed it (`ResourceNotFoundException("Agreement not found: " + id)` in ~20 signing
  sites, `IllegalStateException("Agreement vanished: " + id)`, MinIO `"Failed to store/read object " + key`).
  `GlobalExceptionHandler` itself never logs and returns constant details, but Spring's
  `ExceptionHandlerExceptionResolver` logs every handled exception at WARN (`Resolved [<exception>: <message>]`,
  default level), so every 404 on an agreement route wrote the raw id in every profile. With no catch-all handler, an
  uncaught one is also logged at ERROR by Spring/Tomcat. Neither logger is under `in.agreementmitra`, so the level
  change does not touch them; only the message redaction does. (Corrected 2026-10-04 after the manual test showed the
  WARN line.)
- Hand-written `toString()` of `Agreement`, `SigningCompletionView`, `StaffAgreementView`, `StampInfo`, `StampQueueEntry` include the id or id-bearing keys; ~15 records (`PaymentConfirmedEvent`, `PaymentConfirmation`, response DTOs) print it via the generated record `toString()`.
- No MDC, no request logging. The only id-absence test covers `SecurityEvents` (`SecurityEventsTest`).

## Goals / Non-Goals

**Goals:**
- No raw agreement id in any application log line, constructed exception message or `toString()`.
- Operators can still correlate a log line to an agreement row.
- `INFO` is the shipped default; `DEBUG` only in `local`.
- A regression guard that fails the build when a future log call reintroduces a raw id.

**Non-Goals:**
- Signing-request / delivery / identity ids (never in a public route; not capabilities).
- Tracking references (authorise nothing alone — `TrackingReference.java:25-27`).
- Framework loggers raised above their defaults by an operator (outside `in.agreementmitra`) — documented, not redacted. There is no Modulith event-publication registry (only `spring-modulith-starter-core`; no `event_publication` table), so no event is serialised or logged by the framework.
- Response DTO records' generated `toString()` (serialised to JSON, never logged).
- The agreement id sent to vendors (Razorpay order `receipt`, ZOOP client reference) — not a log line; see the follow-up register.
- Replacing the primary key as the bearer credential with a separate capability token.
- A catch-all exception handler or request access logging — separate concerns.

## Decisions

### D1: 8-hex prefix + `…`, not a digest or last-4
`AgreementIds.redact(UUID)` → `"1a2b3c4d…"`. 32 of 122 random bits disclosed leaves 2^90 candidates — not a
usable credential, and no endpoint accepts a prefix. Correlation is a plain query: `WHERE id::text LIKE '1a2b3c4d%'`.
Birthday collisions between 32-bit prefixes become likely around 65–77k agreements, so the query can return more
than one row; the operator disambiguates by log timestamp or tracking reference.
- *Per-process HMAC digest* (`SecurityEvents.digest`): unlinkable to a row after a restart and not computable by an operator — rejected for debugging logs. `SecurityEvents` keeps its digest; it serves a different purpose.
- *Last 4 chars* (provider-id convention): 16 bits collide across agreements — rejected.
- The prefix is taken from `UUID.toString()`, so it is the canonical lowercase form a DB query matches.
- `redact(null)` returns `"null"` so a log call never throws.

### D2: `redactIn(String)` for ids embedded in strings
Blob keys and some messages carry the id as text. `AgreementIds.redactIn(String)` replaces every
canonical-UUID match (case-insensitive) with its redacted form, so `drafts/<uuid>.pdf` → `drafts/1a2b3c4d….pdf`;
for the same UUID it yields the same prefix as `redact(UUID)`. `MinioBlobStore` uses it for the log line and both
exception messages. It also redacts signing-request UUIDs inside `signed/<id>.pdf` / `audit/<id>` keys — accepted
over-redaction: it keeps the storage layer ignorant of which key prefixes are capabilities, and
`SigningRequestService:392` still logs the full signing-request id, so correlation there needs one prefix match.
`null` passes through.

### D3: Helper lives in the root `in.agreementmitra` package, `public final`
Callers span `signing.agreement`, `.payment`, `.signingrequest`, `.delivery`, `.storage`, `.api` and the signing
root `SigningCompletionView` — Java package-private cannot reach across sub-packages. The root package is not a
Modulith module and already holds cross-cutting error and logging types
that modules already import (`ResourceNotFoundException`, `ConflictException`, `SecurityEvents`), so `in.agreementmitra.AgreementIds` is
reachable from any module without entering signing's published API.
- *Signing root package (module API)*: makes a logging utility part of signing's cross-module contract, and a future caller in `documents` (which signing depends on) would create a module cycle and fail `ModularityTests` — rejected.
- *A per-sub-package copy* (as provider adapters do for their ids): 6+ duplicates of one rule — rejected.

### D4: Redact at the call site, enumerated by concatenation, not by message prefix
`ResourceNotFoundException` takes a `String` and cannot know which part is an id, so each signing throw site wraps
the id with `AgreementIds.redact`. The set is every exception constructed in `signing` whose message concatenates an
agreement id — today 20 `"Agreement not found: "` sites, `SignedDocumentController:87` (`"No signed document: "`),
`AgreementService:575,755` (`"Agreement vanished: "`) and `MinioBlobStore:44,54` (keys, via `redactIn`). Eight of
these concatenate a local named plain `id` (`AgreementController:110,124`, `SignedDocumentController:82,87`,
`AgreementService:575,609,624,639`); those parameters/locals are renamed to `agreementId` so every site is visible to
the D6.2 scan, which then enforces the set rather than this list. RNFE is handled by `GlobalExceptionHandler:159`, but Spring's
`ExceptionHandlerExceptionResolver` still logs the message at WARN on the request path, and an uncaught one on a
scheduled/async path reaches framework ERROR logging, so redacting the message is the primary control for both, not
defence-in-depth. Signing-request ids in `SigningRequestPersistence` messages are left as-is (non-goal).

### D5: Level default in YAML, not a logback file
`application.yml`: `in.agreementmitra: INFO`. `application-local.yml`: `in.agreementmitra: DEBUG`.
`LOGGING_LEVEL_IN_AGREEMENTMITRA` still overrides via Spring relaxed binding. No `logback-spring.xml` is
introduced. `application-test.yml` sets no level, so a Spring-started test JVM runs `in.agreementmitra` at INFO,
while a plain unit test under Logback's BasicConfigurator runs at DEBUG — the effective level depends on fork order.
So any test that asserts on DEBUG lines pins its own logger (D7). `LoggingLevelDefaultsTest` checks the shipped YAML;
there is no `application-sandbox.yml` or multi-document YAML that could override it.

### D6: Regression guards
1. **`AgreementIdLogRedactionIntegrationTest`** (integration, Testcontainers): uses the D7 extension to pin
   `in.agreementmitra` at DEBUG and capture the **root** logger. Drives the real order with
   `TemplateCatalogFixture.seedEligible`, `StaffSessions`, the stamp-scan multipart, and the Leegality WireMock server +
   `@DynamicPropertySource` + create stub from `SigningRequestApiIntegrationTest` (there is no stub `EsignProvider`
   bean; the test profile pins `leegality` with a blank base URL). Shared stamp-upload/WireMock helpers move to
   `support/` rather than being copied. Order: create → draft upload → finalise (order placed + `PDF_GENERATED`
   signing request) → staff `POST /api/staff/payments/{agreementId}/waive` (drives `PaymentService.waive`; not the
   `support/Payments` JDBC fixture, which bypasses the DEBUG lines) → e-Stamp intake (reaches the
   `AgreementDocumentService:206` INFO fallback via `NOT_A_RECORDED_RENDER`) → `SigningRequests.post(...)` asserting
   201 and `SIGN_REQUESTED`. Nothing on this path is async (the only listener is a synchronous
   `@TransactionalEventListener`, and waive publishes no event), so no awaiting. Asserts, in order: the capture contains
   `Draft stored for agreement <prefix>…`, `Stored object drafts/<prefix>….pdf` and `Signing request … created for
   agreement <prefix>…` (positive: the level took effect and the redacted form is what prints); then no formatted
   message and no message in any captured throwable's cause chain contains the full id. The cause-chain walker gets a
   self-test with a synthetic `ILoggingEvent` carrying a cause, so it is shown to run and not merely pass on nothing.
   Expect one extra Spring context (dynamic WireMock port).
2. **`AgreementIdSourceScanTest`** (unit, no Spring, working dir `backend/`): reads `src/main/java/**/*.java`, strips
   comments and string-literal and text-block contents, extracts every
   `\b(log|LOG|logger)\.(trace|debug|info|warn|error)\(` call and every `new \w*(Exception|Error)\(` construction
   up to its paren-depth-matched close, and fails if an argument contains `\bagreementId\b`, `\.agreementId\(\)`,
   `agreement\.id\(\)` or `agreement\.getId\(\)` outside an `AgreementIds.redact(`/`AgreementIds.redactIn(` call
   (qualifier required — other `redact` helpers in the codebase are last-4 provider-id redactors; paren-matched). Names
   file:line. A self-test runs the matcher over positive and negative samples, including a multi-line call, a javadoc
   `{@code log.info(...)}`, a wrongly wrapped `CertificateNumbers.redact(agreementId)`, and a correctly wrapped
   `AgreementIds.redactIn`. It also asserts at least one log call was found, so a logger rename cannot silently disable
   it. A prototype over today's code flags exactly the enumerated sites and no others. Known blind spots, documented in
   the test: a blob-key local (`key` in `MinioBlobStore`, covered by task 3.5 and D6.1), and logging an id-bearing
   object whose `toString()` is not one of the redacted named types.
3. **INFO-line unit assertions**: `SignedDocumentDeliveryServiceTest` (closed as completed / abandoned — the lines are
   inside `if (agreementService.close(...))`, so the tests stub `close` to return `true`; completed-closure needs a new
   test whose signing-request rows are all non-outstanding and cover every party) and `AgreementDocumentServiceTest`
   (fallback; existing `anUploadedDraftIsNotReRendered` reaches it) capture their class logger at INFO and assert the
   redacted form. D6.1 reaches the fallback line but not the delivery-closure lines.
- *ArchUnit*: cannot see log-call arguments — rejected.

### D7: One `LogCapture` JUnit extension
A test-support extension that attaches a `ListAppender` to a named logger (or root), sets a level on a named logger,
and restores both in `afterEach`. Used by D6.1, D6.3 and retrofitted into `RazorpayWebhookServiceTest`,
`LeegalityEsignProviderWireMockTest` and `ZoopEsignProviderWireMockTest`, whose negative-only assertions would
otherwise pass vacuously over an INFO-filtered capture. Each retrofitted test asserts **at least one `Level.DEBUG`
event** was captured — "non-empty" is not enough, since their forged-webhook paths log at WARN. The existing
`SecurityEventCapture` stays as is (it targets the security logger's fixed format and has 4 users); `LogCapture` is
the general form for new tests.

### D8: `toString()` scope
Redacted: the five hand-written overrides (`Agreement`, `SigningCompletionView`, `StaffAgreementView`,
`StampQueueEntry`, `StampInfo`) — their javadoc already claims DEBUG-safety — plus new overrides on the internal
(non-response) records that carry the id: `PaymentConfirmation` (synchronous payment seam; its `reference` provider
id is last-4 redacted too, per the provider-id convention), `PaymentConfirmedEvent`, `RazorpayClient.ProviderOrder`
(`receipt` is the raw id; the order id is last-4 redacted) and `SignRequest` (also carries invitee emails — redacted
via the existing email redaction, counts only). Response DTO records are exempt (serialised to JSON, never logged);
`SigningRequestPersistence.AwaitingStamp` is a one-field internal carrier never logged — exempt, listed as a scan blind
spot.

### D9: Suppress pgjdbc server-error detail
Hibernate's `SqlExceptionHelper` logs the driver message at ERROR — at its **default** level — before Spring
translates the exception. pgjdbc's default `logServerErrorDetail=true` puts the Postgres `Detail:` line into that
message, so a unique violation on `uq_payment_order_open_per_agreement` / `uq_payment_order_receipt` (V17:50,57; the
checkout race `PaymentOrderService:228-235` relies on it) logs `Key (agreement_id)=(<full uuid>)`, and
`uq_agreement_payment_reference` logs a full Razorpay reference. The default `DB_URL` (`application.yml:18`) and
`deploy/env/backend.env.example` gain `?logServerErrorDetail=false`. The main message still names the constraint, so
Hibernate's constraint-name extraction and the race handling are unaffected. The Postgres server's own log carries
the same DETAIL; tuning `log_error_verbosity` on the production server is left out of this change (not chosen).

## Risks / Trade-offs

- [Existing log-absence tests go vacuous at INFO without failing] → D7 retrofit; a DEBUG-level presence assertion makes the failure mode visible.
- [Source scan misses a new shape, e.g. a local named `id`] → documented blind spots; D6.1 and unit tests cover the exercised paths.
- [MinIO cause chain carries the object URL] → in minio 8.6.0 `ErrorResponseException.toString()` includes the request URL with the raw key. Logback renders a stack trace with `getMessage()`, and no app code logs `e` or `String.valueOf(e)` today, so it is a residual, not a live leak; D6.1 walks cause chains on the exercised path.
- [Operators lose DEBUG in production by default] → intended; `LOGGING_LEVEL_IN_AGREEMENTMITRA=DEBUG` restores it per deployment.
- [Framework loggers raised by an operator log ids] → Spring Web DEBUG logs request URIs (`/api/agreements/<uuid>/…`), Hibernate bind TRACE logs parameters, root DEBUG does both, and enabling a Caddy `log` directive would record request URIs. Out of scope to redact; the DEPLOYMENT.md row warns against all four.
- [Prefix collisions] → D1; disambiguate by timestamp/tracking reference.

## Migration Plan

No data or schema change. Deploy as usual. Rollback = revert the commit; a deployment that wants DEBUG sets
`LOGGING_LEVEL_IN_AGREEMENTMITRA=DEBUG` either way.

## Open Questions

None.
