## 1. Redaction helper

- [x] 1.1 Add `public final class AgreementIds` in `backend/src/main/java/in/agreementmitra/AgreementIds.java` (root package, design D3) with `static String redact(UUID)` (first 8 hex + `…`; `null` → `"null"`) and `static String redactIn(String)` (replace every canonical UUID, case-insensitive; `null` passes through) (D1–D2)
- [x] 1.2 Unit test `AgreementIdsTest`: prefix form, null handling, `redactIn` on `drafts/<uuid>.pdf`, on a message with two UUIDs, on uppercase UUIDs, on a string with no UUID (unchanged), and `redact(u)` equals the prefix `redactIn(u.toString())` yields

## 2. Test support

- [x] 2.1 Add a `LogCapture` JUnit 5 extension in `backend/src/test/java/in/agreementmitra/support/`: attach a `ListAppender` to a named logger or root, set a level on a named logger, restore both in `afterEach`; expose formatted messages and throwable cause-chain messages (D7)
- [x] 2.2 Retrofit `RazorpayWebhookServiceTest`, `LeegalityEsignProviderWireMockTest` and `ZoopEsignProviderWireMockTest` to pin their class logger at `DEBUG` via `LogCapture` and assert at least one `Level.DEBUG` event was captured before their existing absence assertions (non-empty is not enough — their forged-webhook paths log at WARN). Also pin `in.agreementmitra` at `DEBUG` (via `LogCapture.root`) in the root-logger PII-absence tests `AgreementPreviewIntegrationTest.previewLeavesNoPiiInLogs` and `DocumentProjectionApiIntegrationTest` (stateless + id-bound preview); those paths emit no DEBUG line today, so they assert absence only (added in fix, from code review)

## 3. Log statements

- [x] 3.1 Wrap the agreement id with `AgreementIds.redact` in `DraftService:74`, `PaymentService:72,86`, `SigningRequestService:190,231,288,299`, `StampIntakeService:305`
- [x] 3.2 Same for the INFO lines `AgreementDocumentService:206` and `SignedDocumentDeliveryService:266,290`
- [x] 3.3 In `SignedDocumentDeliveryServiceTest`, stub `agreementService.close(...)` to return `true` and capture the class logger at INFO: assert the abandoned line (existing terminal-failure test) and a new completed-closure test (all signing-request rows non-outstanding, covering every party) carry the redacted id and not the full id. In `AgreementDocumentServiceTest`, add the same assertion to `anUploadedDraftIsNotReRendered` (fallback line) (D6.3)
- [x] 3.4 `MinioBlobStore`: log `AgreementIds.redactIn(key)`; build both `IllegalStateException` messages with `redactIn(key)`
- [x] 3.5 New unit test `MinioBlobStoreTest` (package `in.agreementmitra.signing.storage`; none exists today; ctor `(MinioClient, StorageProperties)` with a mocked `MinioClient` whose `putObject`/`getObject` throw `IOException`; `bucketExists` stubbed true): the thrown `IllegalStateException`'s own message carries the redacted key and no full UUID

## 4. Exception messages and toString

- [x] 4.1 Rename the parameter/local `id` to `agreementId` at the eight sites that concatenate it (`AgreementController:110,124`, `SignedDocumentController:82,87`, `AgreementService:575,609,624,639`) so the scan can see them, then wrap the agreement id with `AgreementIds.redact` in every exception constructed in the signing module whose message concatenates an agreement id: the 20 `"Agreement not found: "` sites (`AgreementController`, `SignedDocumentController`, `AgreementService`, `DraftService`, `AgreementDocumentService`, `PaymentOrderService`, `SigningRequestService`), `SignedDocumentController:87` (`"No signed document: "`), and `AgreementService:575,755` (`"Agreement vanished: "`) (D4)
- [x] 4.2 Unit test `AgreementIdExceptionMessageTest`: `DraftService` (unknown id), `AgreementService.paymentView` (unknown id, mocked repository) and one `"Agreement vanished"` path throw messages carrying the 8-char prefix and not the full id
- [x] 4.3 Redact the id in the hand-written `toString()` of `Agreement`, `SigningCompletionView`, `StaffAgreementView`, `StampQueueEntry`, and the `stampedPdfKey`/`scanKey` in `StampInfo` (via `redactIn`); add redacting `toString()` overrides to `PaymentConfirmation` (also last-4 its `reference`), `PaymentConfirmedEvent`, `RazorpayClient.ProviderOrder` (`receipt` redacted, order id last-4) and `SignRequest` (invitees as a count, no emails) (D8)
- [x] 4.4 Unit test `AgreementIdToStringRedactionTest`: each of the nine named types' `toString()` contains the 8-char prefix and not the full id

## 5. Log level default

- [x] 5.1 `application.yml`: `logging.level.in.agreementmitra: INFO`; `application-local.yml`: add `logging.level.in.agreementmitra: DEBUG` (D5)
- [x] 5.2 Unit test `LoggingLevelDefaultsTest`: load `application.yml` and `application-local.yml` with `YamlPropertySourceLoader` and assert `INFO` / `DEBUG` respectively
- [x] 5.3a `application.yml`: default `DB_URL` gains `?logServerErrorDetail=false`; same in `deploy/env/backend.env.example` (design D9). Extend `LoggingLevelDefaultsTest` to assert the default `spring.datasource.url` carries it
- [x] 5.3 `docs/DEPLOYMENT.md`: rewrite the `LOGGING_LEVEL_IN_AGREEMENTMITRA` row (default now `INFO`; `DEBUG` only deliberately), and warn that `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB=DEBUG`, Hibernate bind `TRACE`, root `DEBUG`, or enabling a Caddy `log` directive record raw agreement ids via request URIs and bind values; note that a `DB_URL` override must keep `logServerErrorDetail=false`; update the `deploy/env/backend.env.example` `[FIXED]` comment to match

## 6. Regression guards (depend on §3–§4 being complete)

- [x] 6.1 Unit test `AgreementIdSourceScanTest` per D6.2: comment/string/text-block stripping, paren-depth extraction of log calls and `new …Exception(`/`new …Error(` constructions, the four id patterns, only `AgreementIds.redact(`/`AgreementIds.redactIn(` accepted as wrappers, file:line in the failure, matcher self-test (multi-line, javadoc `{@code log…}`, `CertificateNumbers.redact(agreementId)` rejected, `AgreementIds.redactIn` accepted), non-zero log-call count
- [x] 6.2 Move the stamp-upload and Leegality WireMock helpers (`stubCreate`, `@DynamicPropertySource` wiring) out of `SigningRequestApiIntegrationTest` into `support/` and point that test at them
- [x] 6.3 Integration test `AgreementIdLogRedactionIntegrationTest` per D6.1: create → draft → finalise → staff `POST /api/staff/payments/{agreementId}/waive` → e-Stamp intake → `SigningRequests.post(...)` (201, `SIGN_REQUESTED`); assert the draft-stored, stored-object and signing-request-created lines are captured in redacted form, then no full id in any formatted message or cause-chain message; cause-chain walker self-test with a synthetic event
- [x] 6.4 Run the full suite (`./run-tests.sh check`), last; `ModularityTests` green; report wall-clock (expect one extra Spring context from 6.3)

## 7. Close-out

- [x] 7.1 Delete the `agreement-id-debug-logging` row from the `## Follow-up register` in `docs/ROADMAP.md`

## Coverage

| Scenario | Disposition | Covered by |
|---|---|---|
| An id-bearing log statement prints the redacted form | COVERED | 1.2 (format), 6.1 (every call site, syntactic), 6.3 (exercised DEBUG path), 3.3 (INFO sites) |
| INFO-level closure and fallback lines are redacted | COVERED | 3.3, 6.3 (fallback) |
| A stored-object log line redacts the id inside the key | COVERED | 3.5 (exception messages), 6.3 (DEBUG line for `drafts/<id>.pdf`) |
| A constructed exception message carries no raw id | COVERED | 4.2 (behaviour), 6.1 (every construction site, syntactic) |
| Named toString output redacts the id | COVERED | 4.4 |
| A full lifecycle at DEBUG leaks no agreement id | COVERED | 6.3 |
| A new raw agreement id in a log call or exception message fails the build | COVERED | 6.1 |
| A deployment without an override logs at INFO | COVERED | 5.2 |
| The local profile keeps DEBUG | COVERED | 5.2 |
| A unique-constraint violation does not log the key value | COVERED | 5.3a |
| Existing DEBUG redaction tests still observe the DEBUG lines | COVERED | 2.2 |
