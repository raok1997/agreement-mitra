Order: group 1 before 2 and 3; group 3 before 4; task 5.1 (the fixture) before the other tests;
tests alongside the group they cover. Tasks 7.4 and 7.5 edit other changes' files and are committed
separately.

## 1. Order lifecycle and schema

- [ ] 1.1 Confirm `V31` is the next free version, is not in the local `flyway_schema_history`, and that no other in-flight change will ship it first (the paused parent change names `V31` in text it is due to drop); add `V31__payment_order_close.sql`: nullable `resolved_by UUID REFERENCES identity (id)` (default delete action, no cascade), `resolved_at TIMESTAMPTZ`, `closed_reason VARCHAR(24)` on `payment_order`, and the named check `ck_payment_order_closed` exactly as design D2 gives it; restate the five statuses in the migration header (V17's column comment cannot be edited)
- [ ] 1.2 `PaymentOrderStatus`: add `CLOSED`, `terminalStatuses()`, `terminal()` and `customerFacing()` (`CLOSED` -> `EXPIRED`); `settled()` stays `PAID` only (D1, D7, D8)
- [ ] 1.3 Add the `public` enum `PaymentOrderCloseReason` (`REFUNDED`, `RECORDED_ELSEWHERE`); it is bound from JSON by name only - a number is refused (D6)
- [ ] 1.4 `PaymentOrder`: the three fields, `close(reason, actor, at)` that throws on a terminal order and stores `at` truncated to microseconds, and `markPaid` / `markFailed` / `markExpired` guarding on `terminal()` (D1)
- [ ] 1.5 Add `ORDER_CLOSED` to `ConfirmationOutcome` and `PaymentEventOutcome`, and map it in `PaymentEventOutcome.of`; `PaymentConfirmations.apply` returns it after the unknown-order check and before every other check (D3)
- [ ] 1.6 Confirm each reader of `ConfirmationOutcome` (`RazorpayWebhookService`, `acknowledgeCheckout`, `PaymentReconciliationJob`) treats `ORDER_CLOSED` as an acknowledged no-op with no code change; correct the comments that list outcomes or statuses: `PaymentReconciliationJob` ("Already confirmed / mismatched / a true duplicate"), `PaymentConfirmations.apply` javadoc (order of the checks), `PaymentOrderService.reusableOrder` ("FAILED / EXPIRED - a new order is legal"), `PaymentOrder.markFailed` / `markExpired` javadoc ("a settled order")

## 2. Readers of the order status

- [ ] 2.1 `findUnrecordedSince`: replace `o.status <> 'PAID'` with `o.status NOT IN (:terminalStatuses)`; `PaymentOrderQueryAdapter` binds the names from `PaymentOrderStatus.terminalStatuses()` (D7). The signature change breaks `PaymentOrderQueryAdapterTest` at compile time; fix it in the same step
- [ ] 2.2 `progress()` and `toSession()` emit `status().customerFacing().name()` (D8)
- [ ] 2.3 `reusableOrder`: on `OptimisticLockingFailureException` from the expiring save, decide again from a fresh read, once (D9)

## 3. Resolve

- [ ] 3.1 `PaymentOrderRepository.findByIdForUpdate`: an explicit `@Query` with `PESSIMISTIC_WRITE`, the shape of `findByProviderOrderIdForUpdate`
- [ ] 3.2 `PaymentOrderQueryAdapter.ATTENTION_OUTCOMES`, derived as `UNRECORDED_OUTCOMES` keys plus `ERRORED` (D4)
- [ ] 3.3 `PaymentEventLedger.capturedPaymentIds(providerOrderId, outcomes)`: distinct non-null payment ids of the order's rows with one of the outcomes; a plain read on the caller's transaction (not the writer's own-transaction template), named parameters only, and the ids are never logged (D4)
- [ ] 3.4 `AgreementService.paymentStateForUpdate(agreementId)`: payment state and recorded reference, read under the agreement's row lock, joining the caller's transaction (D5)
- [ ] 3.5 `PaymentOrderResolutions.resolve(orderId, reason, actor)`, one transaction: lock the order, refuse unknown (404), closed (409) or paid (409), read the captured payment ids and refuse none (409), lock-read the agreement and refuse paid-under-one-of-them using `PaymentConfirmation.normalizeReference` (409), `close`, save, log one `INFO` line with the order id, reason and actor only (D4, D5, D2)
- [ ] 3.6 `ConflictException.paymentOrderAlreadyClosed()`, `.paymentOrderNotResolvable()` and `.paymentRecordedUnderThisPayment()`, with their problem types registered in `GlobalExceptionHandler`; each has constant detail text carrying no order id, payment id or reference, and the 404's message carries none either (D6)

## 4. Endpoint

- [ ] 4.1 `PaymentAttentionController`: `POST /api/staff/payments/attention/{paymentOrderId}/resolve`; a request record with `@NotNull PaymentOrderCloseReason reason`, bound with `@Valid`; the actor is `@AuthenticationPrincipal`; a response record with the payment order id, reason and time, and nothing else (D6)
- [ ] 4.2 `SecurityConfig`: one exact STAFF matcher for `POST /api/staff/payments/attention/*/resolve` (D6)

## 5. Unit tests

- [ ] 5.1 Test fixture first: a closed-order helper in `support/PaymentOrders` (status, reason, a real identity as actor, time - so the `V31` check and foreign key pass), the `insert` javadoc's status list updated, and a way to get a staff session together with its identity id (`identityService.findOrCreate` + `StaffSessions.forIdentity`)
- [ ] 5.2 `PaymentOrderStatusTest`: `settled`, `terminal`, `outstanding` and `customerFacing` for every value; `terminalStatuses()` is `PAID` and `CLOSED`
- [ ] 5.3 `PaymentOrderTest`: close from created, failed and expired sets reason, actor and time; close of a paid or closed order throws and changes nothing; a closed order ignores `markPaid`, `markFailed` and `markExpired`
- [ ] 5.4 `PaymentConfirmationsTest`: a closed order returns `ORDER_CLOSED`, with a mismatched amount as well, and records nothing on the agreement or the order
- [ ] 5.5 `PaymentOrderQueryAdapterTest`: `ATTENTION_OUTCOMES` is the alert set plus `ERRORED`, and the terminal names are bound into the look-back (`PaymentEventOutcomeTest`'s existing name-parity test already covers `of(ORDER_CLOSED)`)
- [ ] 5.6 `PaymentOrderResolutionsTest` (mocked collaborators; decision logic only - the lock and the rollback are proved in 6.2): each refusal and the accepted path; the reference comparison ignores case and surrounding spaces; an agreement paid under another reference, waived or unpaid does not block; several payment ids, one matching, refuses
- [ ] 5.7 `PaymentOrderServiceTest`: `reusableOrder` after an optimistic-lock failure reads again (verified) and returns null when the fresh read is closed and the order when it is paid - the stale and fresh orders are distinct instances, and the `PaymentProperties` mock becomes a field stubbed with a real `Order` ttl; `progress()` reports `EXPIRED` for a closed order
- [ ] 5.8 `RouteClassifierTest`: the resolve path classifies as the default class with no id in its label, beside the existing waive assertion

## 6. Integration tests

`PaymentOrderResolveIntegrationTest` is declared exactly as `PaymentEventLedgerIntegrationTest` is
(random port, `HarnessTestConfig`, `test` profile, no dynamic or test property source, no spy
bean) so it reuses a cached context; it needs no gateway stub.

- [ ] 6.1 `PaymentOrderResolveIntegrationTest`, accepted: an amount mismatch closed as refunded (row holds reason, that staff identity, time; agreement unchanged; response body has no agreement id, receipt or gateway id); an expired order with a duplicate reference closed as recorded elsewhere; an errored-only order; an agreement paid under another payment
- [ ] 6.2 Same class, refused: no ledger row; a ledger row with no payment id; a paid order; an already closed order (its own problem type; row unchanged); agreement paid under the captured payment written in capitals with spaces (its own problem type); unknown reason, numeric reason and missing reason (400); a path id that is not a UUID (400); unknown order (404)
- [ ] 6.3 Same class, authorization, using `RawClient` (the default client throws on a 401 to a POST): no session with a token is 401 and a customer with a token is 403, each identical for an existing and a missing order; no token is the CSRF 403 and the order is unchanged
- [ ] 6.4 Closed order end to end, in `RazorpayPaymentIntegrationTest` / `PaymentEventLedgerIntegrationTest`: a signed webhook for it is answered 200, the order and agreement are unchanged and the ledger gains one `ORDER_CLOSED` row; resolving adds no ledger row; checkout creates a new order; payment progress reports `EXPIRED`
- [ ] 6.5 In `PaymentReconciliationIntegrationTest` (the only context with the job): the job does not ask the stubbed gateway about a closed order; and a stale copy of an order closed since it was loaded, expired and saved, fails with `OptimisticLockingFailureException` and the row is still `CLOSED` (pins the exception type D9 catches)
- [ ] 6.6 `StaffAlertIntegrationTest`: a closed order with an amount mismatch inside the look-back raises no alert
- [ ] 6.7 `FlywayMigrationIntegrationTest`: `ck_payment_order_closed` refuses a `CLOSED` row with no reason or no actor, a reason on a row that is not closed, and an actor without a time
- [ ] 6.8 `ModularityTests` green

## 7. Docs

- [ ] 7.1 `docs/DEPLOYMENT.md`, "A captured payment that was refused or not recorded": add the resolve step (the curl, the two reasons, the refusals, and the SQL that shows a closed order's reason, actor and time - no API reads them back in this slice); change both hand-typed `status <> 'PAID'` to `NOT IN ('PAID', 'CLOSED')` and say the list's home is `PaymentOrderStatus`; say the stuck-order query lists the three alerted outcomes and give the variant that includes `ERRORED`; say an order whose ledger row was never written cannot be resolved through the API, and what to do then; say an order stays re-read until it is paid or resolved
- [ ] 7.2 `docs/ARCHITECTURE.md`: the payment order's `CLOSED` state and its one writer; the `ORDER_CLOSED` outcome in the ledger's outcome list and the source-by-outcome response table; the alert excludes a closed order
- [ ] 7.3 `docs/ROADMAP.md`: reword the `payments-needing-attention-panel` row for what is now true (a stuck order has an exit through the resolve API; the panel, retry and links remain); record any follow-up before archive
- [ ] 7.4 Separate change set: in `openspec/changes/razorpay-payment/specs/payment-processing/spec.md`, change "has expired or failed" to "has expired, failed, or been closed by staff" (D10)
- [ ] 7.5 Separate change set: append to `openspec/changes/payments-needing-attention-panel/.flow-journal.md` what its resume must do because of this change - drop its `payment-processing` delta (same requirement name), rebase its ledger and alert deltas on the folded baseline, drop `V31`, and bring the panel spec's resolve rule in line with this change's (D10)
- [ ] 7.6 Confirm no customer-facing terms or FAQ text changes (staff-only action; the customer sees an expired order, as today)

## 8. Verify

- [ ] 8.1 `./run-tests.sh` from `backend/` is green; report the wall-clock time against the 3-minute budget
- [ ] 8.2 Manual, test mode: force an amount mismatch locally, resolve it with curl as staff, then confirm by SQL that the order is `CLOSED` with the reason, actor and time, that a redelivered webhook is answered 200 and stops, that checkout opens a new order, and that a second resolve is a 409

## Coverage

Every `#### Scenario:` in this change's deltas, with one disposition. 46 scenarios. A scenario a
`MODIFIED` delta carries over unchanged from the baseline is `COVERED` by the existing suite named
beside it, which must stay green.

### payment-processing (21)

| Scenario | Disposition | Where |
|---|---|---|
| A confirmation arrives for a closed order | COVERED | 6.4 (unit: 5.4) |
| A closed order is not reconciled | COVERED | 6.5 |
| A closed order does not expire, fail or become paid | COVERED | 5.3 |
| A customer returns after their order was closed as refunded | COVERED | 6.4 |
| A customer reads payment progress after their order was closed | COVERED | 6.4 (unit: 5.7) |
| An order is closed while checkout is expiring it | COVERED | 5.7 (the re-read and both outcomes) with 6.5 (the real stale save fails as the type caught, row stays closed) |
| A refunded amount mismatch is resolved | COVERED | 6.1 |
| A reference recorded elsewhere is resolved | COVERED | 6.1 |
| An order whose attempts only ended in an error is resolved | COVERED | 6.1 |
| A healthy outstanding order cannot be closed | COVERED | 6.2 |
| An attempt with no payment identifier does not qualify | COVERED | 6.2 |
| A paid order cannot be closed | COVERED | 6.2 (unit: 5.3) |
| A closed order cannot be closed again | COVERED | 6.2 |
| The agreement is already paid under the captured payment | COVERED | 6.2 (unit: 5.6) |
| The agreement is paid under another payment | COVERED | 6.1 |
| An unknown reason | COVERED | 6.2 |
| An unknown order | COVERED | 6.2 |
| Unauthenticated caller | COVERED | 6.3 |
| Non-staff caller | COVERED | 6.3 |
| A request without a CSRF token | COVERED | 6.3 |
| Response content | COVERED | 6.1 |

### payment-event-ledger (8)

| Scenario | Disposition | Where |
|---|---|---|
| A webhook confirms a payment; The second event for one payment; A webhook for an order the system does not hold; The browser callback confirms a payment; Reconciliation confirms a payment; A reported amount that differs from the order (6, carried over) | COVERED | existing `PaymentEventLedgerIntegrationTest` |
| A confirmation for a closed order | COVERED | 6.4 |
| Closing an order adds no record | COVERED | 6.4 |

### staff-order-alert (17)

| Scenario | Disposition | Where |
|---|---|---|
| The order was closed before the sweep ran | COVERED | 6.6 |
| The other 16 scenarios of "A captured payment that was refused or not recorded raises a staff alert" (carried over) | COVERED | existing `StaffAlertIntegrationTest`, `StaffAlertMessagesTest` |

**Totals:** 46 COVERED, 0 GROUPED, 0 MANUAL, 0 WAIVED, 0 UNMAPPED.
