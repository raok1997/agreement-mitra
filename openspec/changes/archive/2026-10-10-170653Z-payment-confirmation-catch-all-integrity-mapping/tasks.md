## 1. Classifier and failure type

- [x] 1.1 Add `PaymentIntegrity` (package-private, `signing.payment`): constants for `uq_agreement_payment_reference` and `uq_payment_order_provider_payment_id`; `violatedConstraint(DataIntegrityViolationException)` walking the cause chain to Hibernate's `ConstraintViolationException.getConstraintName()`, lower-cased with `Locale.ROOT`; `sqlState(...)` from the first `java.sql.SQLException` in the chain (design D1)
- [x] 1.2 Add `PaymentRecordingFailedException` (package-private `RuntimeException`): built from the reported name (or `unnamed`) and SQL state only, with **no cause** and no database message (design D3)
- [x] 1.3 Unit test `PaymentIntegrityTest`: a named duplicate constraint in either letter case is recognised; another constraint name is returned but is not a duplicate; a chain with no Hibernate cause yields empty; SQL state is read from a nested `SQLException`
- [x] 1.4 Unit test `PaymentRecordingFailedExceptionTest`: the message names the constraint (or `unnamed`) and SQL state, has no cause and no suppressed exceptions, and does not contain text from the original exception's message

## 2. Gateway confirmation path

- [x] 2.1 `PaymentOrderService.applyConfirmation`: return `DUPLICATE_REFERENCE` only when `PaymentIntegrity` names one of the two constraints; otherwise log one ERROR line (redacted order id, reported name, SQL state - no payment id, no amount) and throw `PaymentRecordingFailedException` (design D2, D3)
- [x] 2.2 `PaymentOrderService.acknowledgeCheckout`: catch `PaymentRecordingFailedException` (only that) around the authoritative read and still return `progress(...)` (design D5)
- [x] 2.3 Update the javadoc that describes the old mapping: `PaymentOrderService.applyConfirmation`, `PaymentConfirmations` (class + `apply` `@throws`), `ConfirmationOutcome.DUPLICATE_REFERENCE`, `RazorpayWebhookService.handle`/`apply` and `RazorpayWebhookController` (a verified webhook is `202` unless handling throws - a payment-recording failure is one such case, not the only one), and the `PaymentReconciliationJob.reconcileOne` comment
- [x] 2.4 New unit test class `PaymentOrderServiceTest` (mock `PaymentConfirmations`; the other constructor collaborators as mocks): `applyConfirmation` returns `DUPLICATE_REFERENCE` for each of the two constraints, and throws `PaymentRecordingFailedException` for another constraint and for a violation with no constraint name
- [x] 2.5 Unit test in `RazorpayWebhookServiceTest`: a verified body whose confirmation throws `PaymentRecordingFailedException` propagates out of `handle` (it does not return `true`)
- [x] 2.6 Integration test in `RazorpayPaymentIntegrationTest`: staff record a **lower-case** gateway payment id by hand on agreement A (stored upper-cased); the gateway confirms the same lower-case id on agreement B's order -> `DUPLICATE_REFERENCE`, webhook `202`, B's order not paid and not surplus, B unpaid (pins `uq_agreement_payment_reference` as an expression index)
- [x] 2.7 Integration test in `RazorpayPaymentIntegrationTest`: a signed webhook carrying a 65-character payment id is answered `500` with a body containing neither a constraint name nor the SQL state nor the payment id; the order is still `CREATED` and not surplus, the agreement is unpaid, no mail is sent; delivering the same webhook again answers `500` with the same unchanged state (design D7)
- [x] 2.8 Integration test in `RazorpayPaymentIntegrationTest`: `applyConfirmation` called directly with a 65-character payment id throws `PaymentRecordingFailedException` rather than returning `DUPLICATE_REFERENCE` - once for an unpaid agreement, once for an already-paid agreement (the surplus route, where only the order write is refused)
- [x] 2.9 Integration test in `RazorpayPaymentIntegrationTest`: the browser callback with a valid handler signature answers `200` with progress showing the agreement unpaid, and no mail is sent. The callback body and its signature use a payment id of 64 characters or fewer; only the stubbed provider `/payments` response carries the 65-character id
- [x] 2.10 Integration test in `PaymentReconciliationIntegrationTest`: two outstanding paid orders with explicit `created_at` values so the failing one sorts first, the first with a 65-character captured payment id - after one run the first is still `CREATED` and the second is `PAID`
- [x] 2.11 Log assertion inside 2.7, capturing the **whole** test output (not one logger): the 65-character payment id appears nowhere, and the application's ERROR line carries the redacted order id and the SQL state. Note in the test that the harness runs without `logServerErrorDetail=false`, so this is the stricter configuration for this case

## 3. Manual staff path

- [x] 3.1 `PaymentService.confirm`: throw `ConflictException.paymentReferenceAlreadyUsed()` only when `PaymentIntegrity` names `uq_agreement_payment_reference`; otherwise log one ERROR line (redacted agreement id, reported name, SQL state) and throw `PaymentRecordingFailedException` (design D3, D6); update its javadoc
- [x] 3.2 New unit test class `PaymentServiceTest`: the reference constraint -> `ConflictException`; the order-payment-id constraint, another constraint, and no constraint name -> `PaymentRecordingFailedException`
- [x] 3.3 Integration test in `PaymentGateIntegrationTest`: extend the existing reused-reference case to assert the second agreement's payment state is unchanged after the `409`
- [x] 3.4 Integration test in `PaymentGateIntegrationTest`: a STAFF confirmation with an amount of 10^10 answers `500` (not `409`), the body carries neither the reference nor database text, and the agreement is still unpaid

## 4. Gates and register

- [x] 4.1 `./gradlew spotlessApply`, then `./run-tests.sh check` from `backend/`; report the wall-clock time (budget 3 minutes) and keep `ModularityTests` green
- [x] 4.2 `docs/ROADMAP.md` follow-up register: delete the `payment-confirmation-catch-all-integrity-mapping` row (this change is its completion record)
- [x] 4.3 `docs/ROADMAP.md` follow-up register: add `payment-recording-failure-staff-alert` (done during review, 2026-10-10)
- [x] 4.4 Manual-test items to hand to the user: confirm in the gateway dashboard that the webhook alert email address is set (the gateway retries a non-2xx for 24 hours and then disables the webhook, notifying that address); run `SHOW lc_messages` on the production database and confirm it is English

## Coverage

| # | Capability | Scenario | Disposition | Covered by |
|---|---|---|---|---|
| 1 | payment-processing | A payment id staff already recorded against another agreement | COVERED | 2.6 |
| 2 | payment-processing | A refusal that is not a duplicate is not acknowledged to the gateway | COVERED | 2.7 |
| 3 | payment-processing | A refusal that is not a duplicate is never reported as one | COVERED | 2.4 (unit), 2.8 (integration) |
| 4 | payment-processing | Reconciliation meets a payment-recording failure | COVERED | 2.10 |
| 5 | payment-processing | The browser callback meets a payment-recording failure | COVERED | 2.9 |
| 6 | payment-processing | A payment-recording failure does not expose the payment | COVERED | 1.4 (the failure), 2.11 (the log) |
| 7 | payment-gate | A reused reference is still refused as a conflict | COVERED | 3.3 |
| 8 | payment-gate | Another database refusal is not reported as a reused reference | COVERED | 3.2 (unit), 3.4 (integration) |

8 scenarios - 8 COVERED, 0 GROUPED, 0 MANUAL, 0 WAIVED, 0 UNMAPPED.
