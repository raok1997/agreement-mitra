## 1. Schema and configuration

- [x] 1.1 Check `flyway_schema_history` and `db/migration` for the next free version; add `V27__staff_alert.sql` (or next free) exactly as design D9: `agreement_id` primary key with a plain FK, `status` with a CHECK, partial index on `next_attempt_at`, no `version` column, plus the partial index on `payment_order (confirmed_at) WHERE status = 'PAID'`
- [x] 1.2 Add the `staff-alert.*` block to `application.yml` (`discord.webhook-url` from `STAFF_ALERT_DISCORD_WEBHOOK_URL` default blank, `dispatch-enabled`, `interval`, `initial-delay`); raise `spring.task.scheduling.pool.size` 4 → 5 and update the comment that counts the jobs; correct the `SchedulingConfig` javadoc that names one job
- [x] 1.3 Set `staff-alert.dispatch-enabled=false` in `application-test.yml`
- [x] 1.4 Add `STAFF_ALERT_DISCORD_WEBHOOK_URL=` to `deploy/env/backend.env.example` with the tag in D8 (`secret vendor optional pattern=…`, no optional-group wrapper); run `deploy/test/templates.test.sh`, which is the first proof the grammar accepts that combination. If the PII/secret edit hook blocks the edit, stop and report rather than working around it

## 2. Reading paid orders

- [x] 2.1 Add `List<UUID> agreementsWithOrderPaidSince(Instant cutoff)` to `PaymentOrderQuery`, implemented in `PaymentOrderQueryAdapter` by a JPQL query on `PaymentOrderRepository` (distinct agreement ids, status `PAID`, `confirmed_at >= cutoff`) — the only edit under `signing.payment` (D10)

## 3. Alert store and dispatch

- [x] 3.1 Create package `signing.staffalert` with the `StaffAlert` entity (record-style `agreementId()` accessor, no `@Version`, redacting `toString`), `StaffAlertStatus`, and `StaffAlertProperties` with its nested `discord` holder (URL bound as `String`, no Bean Validation, both `toString`s masked)
- [x] 3.2 Add `StaffAlertRepository extends Repository<StaffAlert, UUID>` (not `JpaRepository`): the native `INSERT … ON CONFLICT (agreement_id) DO NOTHING` with the literal `'PENDING'` and every other column bound; JPQL `@Modifying` queries for the claim (`attempts = :seen`), mark-sent and mark-failed (both guarded by `status` and `attempts`), and expire; a find-by-id; a due-ids query ordered by `next_attempt_at, agreement_id` with a limit (D2, D4)
- [x] 3.3 Add `StaffAlertPersistence`: one short `@Transactional` method per database step, the enqueue transaction containing the inserts and nothing else (D2, D4)
- [x] 3.4 Add `StaffAlertBackoff` constants and function (30s doubling to 1h, 10 attempts, batch 20, 24-hour window) — the only place the backoff rule exists (D5)
- [x] 3.5 Add `StaffAlertDispatcher`, non-transactional, carrying `@EnableConfigurationProperties(StaffAlertProperties.class)`, with package-private `enqueue(Instant now)`, `dueAgreementIds(Instant now)`, `dispatchOne(UUID agreementId, Instant now)` and `dispatchDue()`. Each returns at once when `!notifier.configured()`. `dispatchOne` reads the row, expires it instead of sending when stale or out of attempts, otherwise claims with a Java-computed next-attempt time, sends with no transaction open, and records the outcome. `dispatchDue()` reads `Instant.now()` afresh per row (D1, D3, D4)
- [x] 3.6 Add `StaffAlertDispatchJob` (`@Scheduled`, `@ConditionalOnProperty` on `staff-alert.dispatch-enabled` with `matchIfMissing = true`); its catch logs the exception class only, never the throwable (D3, D8)

## 4. Notifier and message

- [x] 4.1 Add `StaffNotifier` (`configured()`, `send`), `StaffAlertMessage` and `StaffAlertDeliveryException` (kind + status, no cause constructor) (D6)
- [x] 4.2 Add `StaffAlertMessages.from(StaffAgreementView, String publicBaseUrl)`: the one place the staff view is read; state only when it matches `^[A-Z]{2}$`; link only when the base URL is an absolute `https` URI. The dispatcher looks the view up with `AgreementService.staffViewsByAgreementId(List.of(id))` and treats a missing entry as a permanent failure (D7)
- [x] 4.3 Add `DiscordStaffNotifier` and its config (D8):
  - constructs with a blank URL; builds and checks the `URI` once (absolute, has a host, `https`, or `http` to a loopback host) in a try/catch that discards the exception; an unusable value logs one ERROR without the value
  - client from the static `RestClient.builder()` over an `HttpClient` with HTTP/1.1, a connect timeout and redirects off; read timeout on the factory; timeouts as constructor parameters; `.uri(URI)`; no custom `ResponseErrorHandler`
  - body with `content`, `allowed_mentions` and `flags: 4`
  - `send` catches `RestClientException` and any `RuntimeException` itself and rethrows only `StaffAlertDeliveryException(kind, status)`, classified per D5; logs class and status only

## 5. Unit tests (no Spring context)

- [x] 5.1 `StaffAlertMessages`: a fully populated `StaffAgreementView` (party names, father's names, city, agreement UUID) yields a message and Discord body containing the tracking reference and state and none of the names, the city or the UUID; state `null`, `Telangana` and `tg` are omitted; base URL blank, `http://localhost:5173` and a trailing-slash https value behave per D7
- [x] 5.2 `StaffAlertBackoff`: the exact sequence 30s, 1m, 2m, 4m, 8m, 16m, 32m, 1h, 1h; the shortest backoff exceeds connect + read timeout
- [x] 5.3 `DiscordStaffNotifier` against WireMock with sub-second timeouts (following `GoogleTokenExchangeTimeoutTest`): 204 sent; 500 and 429 transient; 401, 403, 404 permanent; 302 permanent with no request to the redirect target; a hung response abandoned at the read timeout as transient; a closed port transient; the request body carries `allowed_mentions` with an empty `parse` and `flags` 4. With `LogCapture.root("in.agreementmitra", …)`, the timeout and closed-port cases leave the URL and its token in neither `messages()` nor `throwableMessages()`, and the thrown `StaffAlertDeliveryException` has no cause
- [x] 5.4 Notifier configuration: a blank URL, an unparseable value, a scheme-less value (`discord.com/api/webhooks/1/abc`) and `http://` to a non-loopback host each give `configured()` false, and each non-blank case logs one ERROR that does not contain the value; `https://…` and `http://localhost:…` give `configured()` true; `toString()` of `StaffAlertProperties`, of its nested `discord` holder and of `StaffAlert` contains neither the URL nor a full agreement UUID
- [x] 5.5 `StaffAlertDispatchJob`: a dispatcher that throws an exception whose message contains a webhook URL does not escape `sweep()`, and the captured log has that URL in neither `messages()` nor `throwableMessages()`
- [x] 5.6 `StaffAlertDispatcher` with an unconfigured notifier (mocks): no payment-order query, no insert, no send

## 6. Integration tests

- [x] 6.1 In the existing `RazorpayPaymentIntegrationTest` (package `signing.payment`), with `staff-alert.discord.webhook-url` pointed at its WireMock through the existing `@DynamicPropertySource`: after a verified webhook confirmation the payment is confirmed, `agreementsWithOrderPaidSince` returns the agreement, WireMock received no request on the staff webhook path, and no `staff_alert` row exists; an agreement whose only order is `CREATED` or expired is not returned; a cutoff after `confirmed_at` excludes the order
- [x] 6.2 Add test support: `support.PaymentOrders.insert(jdbc, agreementId, status, confirmedAt)`, supplying every NOT NULL column of `V17` (`id`, `provider`, a unique `provider_order_id`, a unique `receipt` of at most 40 characters, a positive amount, `currency`, `status`, `created_at`); agreements are created through `POST /api/agreements` after `TemplateCatalogFixture.seedEligible`, as `RazorpayPaymentIntegrationTest` does; `Payments.markPaid` gives the paid-by-hand case. At most one `CREATED` order per agreement (`uq_payment_order_open_per_agreement`)
- [x] 6.3 New `StaffAlertIntegrationTest` in `T/signing/staffalert` — the **one** new Spring context (alerts enabled, URL at WireMock). Every assertion is scoped to the test's own agreement id and tracking reference, because other tests' paid orders share the database. Calling `enqueue(now)` only: a `PAID` order in the window → one `PENDING` row; a second `enqueue` → still one; a second `PAID` order for the agreement → still one; an agreement marked paid by hand and then given a `PAID` order → one row; a `PAID` order confirmed 25 hours ago → none; only `CREATED`/`FAILED`/`EXPIRED` orders → none; no order at all, whether unpaid, staff-confirmed or waived → none
- [x] 6.4 Same class, `dispatchOne` with an explicit `now`: 204 → `SENT`, one request whose body contains the tracking reference and none of the agreement UUID, the fixture's party name or its city; 500, then `now` advanced past the backoff and 204 → `SENT` with two attempts; 429 → `PENDING` with a later next-attempt time; 404 → `FAILED` after one attempt; 302 → `FAILED` after one attempt; a transient failure on the last attempt → `FAILED`; a due row already at the maximum attempts → `FAILED` with no request; a row created more than 24 hours ago → `FAILED` with no request
- [x] 6.5 Same class: a second claim of the same row with the same `seen` value updates zero rows, and two `dispatchOne` calls at the same `now` post one request
- [x] 6.6 Same class, with `LogCapture.root("in.agreementmitra", …)` and a `@MockitoSpyBean StaffNotifier`: across a 500, a 404, a hung response and a notifier made to throw a `RuntimeException` whose message contains the webhook URL, neither `messages()` nor `throwableMessages()` contains the URL, its token or the full agreement UUID; the `FAILED` line carries the redacted fragment
- [x] 6.7 Same class: one full `dispatchDue()` — first insert `SENT` rows for every other agreement with a paid order (`INSERT … SELECT DISTINCT agreement_id … ON CONFLICT DO NOTHING`), then a fresh paid order ends `SENT`
- [x] 6.8 `ModularityTests` and `AgreementIdSourceScanTest` stay green

## 7. Docs and register

- [x] 7.1 `docs/ARCHITECTURE.md`: a short section on the staff alert — derived from paid orders, the dispatch job, the retry bounds, what the message may contain
- [x] 7.2 `docs/DEPLOYMENT.md`: run `./provision.sh secrets` before the first deploy that carries the new key; creating the Discord webhook; the channel's phone notification setting; the test-payment check; rotation in the order create new webhook → set URL → restart → delete old; the SQL that resets `FAILED` alerts to `PENDING` after a bad URL; reviewing channel membership when staff change; rollback by blanking the URL; orders that never pass through the gateway raise no alert
- [x] 7.3 `README.md`: add `STAFF_ALERT_DISCORD_WEBHOOK_URL` to the "Delivery and recovery" env table
- [x] 7.4 `docs/ROADMAP.md` follow-up register: append one sentence to the `security-event-alerting` row noting the Discord staff channel now exists as its alert channel, and that failed staff alerts are ERROR-log-only, with no recorded reason, until then
- [x] 7.5 `docs/ROADMAP.md` follow-up register: add `staff-alert-whatsapp-adapter` — Meta prerequisites (number is on the WhatsApp Business app, not the Cloud API; approved utility template needed; verify whether one number can serve both; Groups API not usable, so recipients are a list); recommended action: replace the Discord `StaffNotifier` once the Cloud API number and template exist
- [x] 7.6 `docs/ROADMAP.md` follow-up register: add `double-charge-invisible-to-staff` — a late confirmation of an expired or failed order after another order was paid passes `PaymentConfirmations.apply`, and `Agreement.recordPayment` overwrites without a guard; recommended action: flag and alert when a second order is paid for an already-paid agreement
- [x] 7.7 `docs/ROADMAP.md` follow-up register: add `payment-confirmation-catch-all-integrity-mapping` — `PaymentOrderService.applyConfirmation` reports every `DataIntegrityViolationException` as `DUPLICATE_REFERENCE` and no test asserts that outcome; recommended action: narrow the catch to `uq_agreement_payment_reference` and add the missing test

## 8. Verification

- [x] 8.1 `./gradlew spotlessApply`, then `./run-tests.sh`; report the `check` wall-clock against the 3-minute budget
- [x] 8.2 Manual: point the URL at a real private Discord test channel locally, make a sandbox payment, confirm the alert arrives within a minute, shows only the tracking reference, state and (with an https base URL) a link, and pings nobody; delete the test webhook afterwards

## Coverage

Every scenario in `specs/staff-order-alert/spec.md` and the test task that discharges it.

| Requirement | Scenario | Disposition | Covered by |
|---|---|---|---|
| A gateway-paid order raises one staff alert per agreement | A paid order is alerted | COVERED | 6.3 |
| | The sweep runs again | COVERED | 6.3 |
| | A second paid order for the same agreement | COVERED | 6.3 |
| | A gateway payment after a manual staff confirmation | COVERED | 6.3 |
| | An order paid before the look-back window | COVERED | 6.3 (query boundary also in 6.1) |
| Only a gateway-paid order raises an alert | Order placed without payment | COVERED | 6.3 |
| | An unpaid payment order | COVERED | 6.3 (query also in 6.1) |
| | Staff confirm or waive | COVERED | 6.3 |
| Staff alerts stay off the payment confirmation path | Confirming a payment does not contact the channel | COVERED | 6.1 |
| | The sweep fails | COVERED | 5.5 |
| | Dispatch sends a pending alert | COVERED | 6.4, full sweep in 6.7 |
| Failed sends are retried within bounds | Transient failure then success | COVERED | 6.4 |
| | Rate limited | COVERED | 6.4 (classification in 5.3) |
| | Attempts exhausted | COVERED | 6.4 |
| | Interrupted on the last attempt | COVERED | 6.4 |
| | Permanent refusal | COVERED | 6.4, log assertion in 6.6 |
| | Redirect | COVERED | 6.4 (not followed: 5.3) |
| | Stale alert | COVERED | 6.4 |
| | One alert is claimed once | COVERED | 6.5 |
| The alert carries no personal data and no credential | Message content | COVERED | 5.1, end to end in 6.4 |
| | State is missing or not a two-letter code | COVERED | 5.1 |
| | Site address is not a secure absolute address | COVERED | 5.1 |
| | Mentions and previews are suppressed | COVERED | 5.3 |
| The alert channel is configured by a secret and is off when unset | Channel not configured | COVERED | 5.6 |
| | Channel address is unusable | COVERED | 5.4 |
| | A send fails | COVERED | 6.6, timeout and refused connection in 5.3 |
| | Outbound calls are time-bounded | COVERED | 5.3 |

27 scenarios: 27 COVERED, 0 GROUPED, 0 MANUAL, 0 WAIVED, 0 UNMAPPED.
