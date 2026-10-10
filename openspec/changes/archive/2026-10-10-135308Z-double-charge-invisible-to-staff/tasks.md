Build units: the app does not compile or boot between some of these tasks. Land **2.4 + 2.5**
together (`markPaid` has one caller), and **1.1 + 4.1-4.4 + 6.5** together (after V28 the old alert
insert has no matching conflict target and `id` is `NOT NULL`, so the sweep fails until the alert
code and its existing tests are re-keyed).

## 1. Schema

- [x] 1.1 Check `flyway_schema_history` and `db/migration` for the next free version; add `V28__surplus_payment_alert.sql` (or next free) per design D3-D5, in this order: `payment_order.surplus BOOLEAN NOT NULL DEFAULT FALSE`; `CREATE UNIQUE INDEX uq_payment_order_provider_payment_id ON payment_order (provider_payment_id) WHERE provider_payment_id IS NOT NULL`; `staff_alert.id UUID` backfilled from `agreement_id` then `NOT NULL`; `staff_alert.kind VARCHAR(24)` backfilled `'ORDER_PAID'` then `NOT NULL` with no default left; drop `staff_alert_pkey` and add the primary key on `id`; `CHECK (kind IN ('ORDER_PAID','DUPLICATE_PAYMENT'))`; `CHECK (kind <> 'ORDER_PAID' OR id = agreement_id)`; plain index on `staff_alert (agreement_id)`. No new index for the surplus look-back, no foreign key from `id`. Do not edit `V27`

## 2. Deciding and storing "surplus" (payment confirmation path)

- [x] 2.1 Move reference normalisation to one place: public static `PaymentConfirmation.normalizeReference(String)` with the exact behaviour of today's private `PaymentService.normalizeReference` (trim, collapse inner whitespace, `toUpperCase(Locale.ROOT)`, blank to null); `PaymentService` calls it and its private copy is deleted
- [x] 2.2 Add public enum `PaymentRecording { RECORDED, ALREADY_RECORDED, SURPLUS }` at the `signing` module root beside `PaymentConfirmation`
- [x] 2.3 `Agreement.paidUnder(String reference)`: true when the state is `PAID` and `normalizeReference` of the stored reference equals `normalizeReference` of the argument and is not null; leave `recordPayment` unchanged
- [x] 2.4 `AgreementService.recordGatewayPayment(UUID, PaymentConfirmation)` per design D1: load with `findByIdForUpdate`; not `PAID` → record with a null actor and return `RECORDED`; `paidUnder` → `ALREADY_RECORDED`; else `SURPLUS`; write nothing in the last two cases. `PaymentOrder`: map the `surplus` column with an accessor; `markPaid(providerPaymentId, confirmedAt, surplus)` sets it once and still returns early on a settled order
- [x] 2.5 `PaymentConfirmations.apply` per design D2: call `recordGatewayPayment` in place of `recordPayment`; pass the surplus result to `markPaid`; publish `PaymentConfirmedEvent` only when the result is not `SURPLUS`; return `CONFIRMED` in all three cases; one `WARN` for a surplus payment with the redacted order fragment only. Update the class javadoc, including the sentence that says the gateway uses "the same one the manual STAFF path uses"

## 3. Reading paid orders

- [x] 3.1 Add public record `SurplusPayment(UUID paymentOrderId, UUID agreementId)` at the `signing` module root with a redacting `toString`; add `List<SurplusPayment> surplusOrdersPaidSince(Instant cutoff)` to `PaymentOrderQuery`, implemented in `PaymentOrderQueryAdapter` by a JPQL query on `PaymentOrderRepository` (`status = PAID`, `surplus = true`, `confirmedAt >= :cutoff`, status as a literal)
- [x] 3.2 Add `and o.surplus = false` to `PaymentOrderRepository.findAgreementIdsPaidSince`, and say so in the `PaymentOrderQuery.agreementsWithOrderPaidSince` javadoc (design D6)

## 4. Duplicate-payment alert

- [x] 4.1 Add enum `StaffAlertKind { ORDER_PAID, DUPLICATE_PAYMENT }`; `StaffAlert`: `@Id` becomes `id`, add `kind`, keep `agreementId` as a plain column; update the entity javadoc
- [x] 4.2 `StaffAlertRepository`: re-key `findById`, `claim`, `markSent`, `markFailed` and the due query from the agreement id to the alert id; the native `insertPendingIfAbsent` takes id, agreement id and the kind **as a String** (`kind.name()` - Hibernate does not bind an enum to a native parameter as its name) and conflicts on `id`
- [x] 4.3 `StaffAlertPersistence`: re-key to the alert id; `enqueue(Collection<UUID> paidAgreementIds, Collection<SurplusPayment> surplus, Instant now)` inserts `ORDER_PAID` rows (`id` = agreement id) and `DUPLICATE_PAYMENT` rows (`id` = payment order id) in the one transaction
- [x] 4.4 `StaffAlertDispatcher`: `enqueue` reads both queries before the insert transaction; the due list and `dispatchOne` work on alert ids; `compose` resolves the view by the alert's agreement id and passes its kind; every retry and failure log line names the kind beside the redacted agreement id and never prints the alert id
- [x] 4.5 `StaffAlertMessage` gains `kind`; `StaffAlertMessages.from` takes it; `DiscordStaffNotifier.content` uses the fixed lead per kind from design D7

## 5. Tests - unit

- [x] 5.1 `PaymentConfirmation.normalizeReference`: the cases the manual path relies on (case, surrounding and inner whitespace, blank, null) and a lower-case `i` under a Turkish default locale. `AgreementTest.paidUnder`: unpaid, waived, same reference, same reference in another case with surrounding whitespace, another reference, no stored reference, null argument
- [x] 5.2 `AgreementServiceTest.recordGatewayPayment`: the three results from design D1's table, asserting the agreement is saved only for `RECORDED` and the actor is null
- [x] 5.3 New `PaymentConfirmationsTest` (mocks): `RECORDED` and `ALREADY_RECORDED` mark the order paid, not surplus, and publish the event; `SURPLUS` marks it paid and surplus, publishes no event and still returns `CONFIRMED`; the earlier refusals are unchanged. Covers every route, since all three converge on `apply`
- [x] 5.4 `PaymentOrder` unit test: `markPaid` stores the surplus mark; a second `markPaid` on a settled order changes neither the mark nor the payment id
- [x] 5.5 `StaffAlertMessagesTest` and `DiscordStaffNotifierTest`: the duplicate-payment lead, the unchanged paid-order lead, neither message carrying an amount, a gateway id or the agreement id; the existing state, site-link and mention/preview cases kept
- [x] 5.6 `StaffAlertDispatcherTest`: enqueue passes paid agreements and surplus orders to persistence; a duplicate-payment alert is composed with its kind; with a paid-order and a duplicate-payment alert for one agreement both due and the latter refused permanently, the former is sent, the latter alone is failed, and the error line names the kind
- [x] 5.7 `StaffAlertRedactionTest`: `SurplusPayment.toString` shows no full identifier

## 6. Tests - integration

- [x] 6.1 `RazorpayPaymentIntegrationTest` (webhook path, real Postgres): late payment on an expired order after another was paid; late payment on a failed order; third payment; redelivery of a surplus confirmation - asserting the order's status and surplus mark, the agreement's unchanged amount/reference/time, and the acknowledgement. With `LogCapture`, the surplus `WARN` contains no amount, no payment id and no full order id
- [x] 6.2 Same suite: gateway payment after a manual staff confirmation with a different reference (surplus; actor and reference kept); after a manual confirmation made through `/api/staff/payments/{id}/confirm` with the same payment id typed in lower case with surrounding spaces (not surplus); after a waiver (agreement becomes paid, not surplus); a first payment (not surplus)
- [x] 6.3 Same suite: a surplus confirmation sends no recovery link; a first confirmation sends one; the same-payment-id case of 6.2 sends one
- [x] 6.4 Same suite: two orders of one unpaid agreement confirmed concurrently give exactly one recorded payment and one surplus order (modelled on the existing concurrent-delivery test); a confirmation reporting a payment id another order already holds is refused as a duplicate reference and leaves its order unpaid
- [x] 6.5 Re-key the existing staff alert suites and fixtures to the new shape, in the same build unit as section 4: `StaffAlertIntegrationTest` (the raw `INSERT INTO staff_alert` in the settle-everything step, which must also settle surplus orders; `rowOf` and the `UPDATE ... WHERE agreement_id` helpers select on `id`/`kind`; `requestsFor` tells the two leads apart), `StaffAlertDispatcherTest`, `StaffAlertDispatchJobTest`; `support.PaymentOrders.insert` gains a surplus overload. Every existing paid-order scenario still passes with its assertions unchanged in meaning
- [x] 6.6 `StaffAlertIntegrationTest`, new cases: a surplus order yields one pending duplicate-payment alert; a second sweep adds none; two surplus orders yield two; a non-surplus paid order yields none; a surplus order older than 24 hours yields none; a surplus order yields no paid-order alert; a due duplicate-payment alert is posted once with the duplicate lead and marked sent; a `SENT` row with `id = agreement_id` blocks a new paid-order enqueue; confirming a surplus payment through the webhook records no alert and makes no channel request, and the next sweep posts one duplicate-payment alert (end to end)
- [x] 6.7 `FlywayMigrationIntegrationTest`, using its scratch-schema `target(...)` pattern: migrate to 27, insert a `staff_alert` row in the V27 shape, migrate to 28, assert `id = agreement_id` and `kind = 'ORDER_PAID'`, and that a second `ORDER_PAID` row for that agreement and an `ORDER_PAID` row with `id <> agreement_id` are both rejected
- [x] 6.8 Run `./run-tests.sh check` from `backend/`; `ModularityTests` green; report the wall-clock time

## 7. Docs and register

- [x] 7.1 `docs/DEPLOYMENT.md` (staff alert section): the duplicate-payment alert and its runbook - look the agreement up by tracking reference; the SQL that lists that agreement's payment orders (status, surplus mark, amount, confirmed time, receipt) and the SQL that lists every surplus order in a period (the fallback when an alert was lost); refund only after two captured payments are seen in the gateway dashboard, only the order marked surplus, only through the gateway to the original payment method; check which order was credited before buying the stamp; never paste an agreement id into the channel. `docs/ARCHITECTURE.md` and `README.md`: where each says one alert per agreement, mention the second kind
- [x] 7.2 `docs/ROADMAP.md` follow-up register: delete the `double-charge-invisible-to-staff` row (this change is its completion record); append to `checkout-ignores-agreement-paid-state` that `StampValueReference`, the stamp queue and `progress` read the newest order or frozen quote, not the credited one; append to `payment-confirmation-catch-all-integrity-mapping` that `uq_payment_order_provider_payment_id` is a second constraint the narrowed catch must name

## Coverage

Every scenario in `specs/payment-processing/spec.md` and `specs/staff-order-alert/spec.md` and the
test task that discharges it. No scenario is waived: every one concerns money or what leaves for a
third-party channel.

| Requirement | Scenario | Disposition | Covered by |
|---|---|---|---|
| A gateway payment on an already-paid agreement is kept as a surplus payment | A late payment on an expired order after another order was paid | COVERED | 6.1 |
| | A late payment on a failed order after another order was paid | COVERED | 6.1 |
| | A gateway payment after a manual staff confirmation | COVERED | 6.2 |
| | Staff recorded the same gateway payment by hand | COVERED | 6.2, 6.3 (rule also 5.1) |
| | A gateway payment after a waiver | COVERED | 6.2 |
| | A first payment is unaffected | COVERED | 6.2 |
| | A surplus payment sends no recovery link | COVERED | 6.3 (also 5.3) |
| | A surplus confirmation is redelivered | COVERED | 6.1 (also 5.4) |
| | A third payment | COVERED | 6.1 |
| | Two orders for one agreement are confirmed at the same instant | COVERED | 6.4 |
| | A payment id already held by another order | COVERED | 6.4 |
| A surplus gateway payment raises a duplicate-payment staff alert | A surplus payment is alerted | COVERED | 6.6 |
| | A surplus payment confirmed by the gateway is alerted end to end | COVERED | 6.6 |
| | The sweep runs again | COVERED | 6.6 |
| | A third payment on the same agreement | COVERED | 6.6 |
| | A gateway payment after a manual staff confirmation | COVERED | 6.6 (surplus order yields no paid-order alert) |
| | A paid order that is not surplus | COVERED | 6.6 |
| | A surplus payment before the look-back window | COVERED | 6.6 |
| | Dispatch sends a duplicate-payment alert | COVERED | 6.6 |
| | Two alerts for one agreement in the same sweep | COVERED | 5.6 |
| A gateway-paid order raises one staff alert per agreement (MODIFIED) | A paid order is alerted | COVERED | 6.5 (existing test, re-keyed) |
| | The sweep runs again | COVERED | 6.5 |
| | A second paid order for the same agreement | COVERED | 6.5 |
| | A gateway payment after a manual staff confirmation | COVERED | 6.5 |
| | An order paid before the look-back window | COVERED | 6.5 |
| Staff alerts stay off the payment confirmation path (MODIFIED) | Confirming a payment does not contact the channel | COVERED | existing `RazorpayPaymentIntegrationTest.confirmingAPaymentContactsNoStaffChannelAndLeavesThePaidOrderForTheSweep`, unchanged |
| | Confirming a surplus payment does not contact the channel | COVERED | 6.6 |
| | The sweep fails | COVERED | 6.5 (existing `StaffAlertDispatchJobTest`) |
| | Dispatch sends a pending alert | COVERED | 6.5 (existing test) |
| The alert carries no personal data and no credential (MODIFIED) | Message content | COVERED | 5.5 |
| | A duplicate-payment message is told apart | COVERED | 5.5 |
| | State is missing or not a two-letter code | COVERED | 5.5 (existing case kept) |
| | Site address is not a secure absolute address | COVERED | 5.5 (existing case kept) |
| | Mentions and previews are suppressed | COVERED | 5.5 (existing case kept) |
