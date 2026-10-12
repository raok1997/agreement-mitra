Tasks run in order: group 2 needs group 1, group 3 needs group 2, and the fixtures in 3.1 come
before the integration tests.

## 1. Backend: the agreement's payment standing

- [x] 1.1 Move the body of `Agreement.paidUnder` into one package-private static in
      `signing.agreement` (state is `PAID`, both references non-null, equal after
      `PaymentConfirmation.normalizeReference`) and have `Agreement.paidUnder` call it
- [x] 1.2 Add the public interface `in.agreementmitra.signing.AgreementPaymentStanding`
      (`trackingReference()`, `paymentState()`, `paidUnder(String)`) and its package-private record
      implementation in `signing.agreement`, calling the static from 1.1; the record's `toString`
      omits the reference
- [x] 1.3 Add `AgreementService.paymentStandings(Collection<UUID>)`: one JPQL projection query in
      `AgreementRepository` (id, tracking reference, payment state, payment reference; no entity
      load), a map by agreement id, `Map.of()` for a null or empty input
- [x] 1.5 Add `AgreementService.trackingReferencesHolding(Collection<String> paymentIds)`: one
      query in `AgreementRepository` matching `UPPER(BTRIM(payment_reference))` against the ids
      bound upper-cased and trimmed, returning payment id to tracking reference, `Map.of()` for an
      empty input
- [x] 1.4 Unit test `AgreementPaymentStandingTest`: paid under the same id in another case and with
      spaces, paid under another id, waived, unpaid, null on either side

## 2. Backend: the reads

- [x] 2.1 In `PaymentOrderQueryAdapter`, add `ATTENTION_OUTCOMES` derived from
      `UNRECORDED_OUTCOMES.keySet()` plus `ERRORED`; leave `UNRECORDED_OUTCOMES` as it is
- [x] 2.2 In `PaymentOrderRepository`, add the two native queries of design D4 (stuck orders driven
      from the ledger; not-paid orders with `NOT EXISTS`), each with `status IN (:listed)`, the
      order id as tie-breaker and `LIMIT :limit`, every column under a quoted camelCase alias,
      and an interface projection with an `Instant` `getCreatedAt()` and no receipt getter
- [x] 2.7 In `PaymentOrderRepository`, add the holder query of design D12 (orders whose
      `provider_payment_id` is one of the ids; order id, payment id, agreement id, quoted aliases)
- [x] 2.3 In `PaymentEventLedger`, add `attemptsFor(Collection<String> providerOrderIds)`: a
      `JdbcClient` query in the caller's transaction (not `ownTransaction`), empty list for an
      empty input, timestamps read as `OffsetDateTime` and converted to `Instant`, returning a
      package-private record that keeps the outcome as its stored string, with an `isStuck()`
      predicate (outcome name in `ATTENTION_OUTCOMES` and a non-null payment id) and a `toString`
      without the payment id
- [x] 2.4 Add `PaymentOrderOverview` (`signing.payment`, public,
      `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`): `LISTED_STATUSES`
      (`CREATED`, `FAILED`, `EXPIRED`), the 30-day window, the cap of 200 read as 201 with
      `truncated`, no further read when no order is listed, attempts grouped by order and ordered
      oldest first, `stuckOutcomes` from `isStuck()`, `heldBy` per design D12 (both lookups skipped when no
      stuck `DUPLICATE_REFERENCE` attempt is listed; own order dropped), and the agreement payment
      mapping (`UNPAID`,
      `WAIVED`, `PAID_THIS_PAYMENT`, `PAID_OTHER_PAYMENT`)
- [x] 2.5 Unit test `PaymentOrderOverviewTest` (repository, ledger and agreement service stubbed):
      201 rows give 200 entries in the given order and `truncated`; 3 rows give 3 and not
      truncated; no rows give two empty groups and no ledger or agreement read; attempts attached
      to the right order oldest first; `stuckOutcomes` distinct and only from stuck attempts; an
      attempt with an outcome name the enum does not hold is passed through and is not stuck;
      `heldBy` merges both lookups, drops the entry's own order, is empty for other kinds, and
      neither lookup runs without a `DUPLICATE_REFERENCE` attempt; each
      of the four agreement payment values
- [x] 2.6 Unit test `PaymentOrderQueryAdapterTest`: `ATTENTION_OUTCOMES` is the three alert outcomes
      plus `ERRORED`, and `UNRECORDED_OUTCOMES` still has exactly three keys

## 3. Backend: the endpoint

- [x] 3.1 Test fixtures in `support/`: a `PaymentOrders.insert` overload taking `createdAt` and the
      receipt; confirm `PaymentEvents.insert` can set outcome, payment id (or none), amount,
      currency, refused rule, count and both times, extending it if not
- [x] 3.2 Add the response records in `signing.api` (`PaymentOrdersResponse`, group, entry,
      attempt) per design D7, with no agreement id, receipt, gateway order id or agreement
      reference field; the attempt record's `toString` omits the payment id
- [x] 3.3 Add `StaffPaymentOrderController` with `GET /api/staff/payment-orders`; no log statement
      takes an entry or attempt
- [x] 3.4 Add the exact `GET` matcher for `/api/staff/payment-orders` with `hasRole(STAFF)` in
      `SecurityConfig`, with a comment saying why the route sits outside `/api/staff/payments/*`
- [x] 3.5 Integration test `StaffPaymentOrdersAccessIntegrationTest`: 401 without a session, 403
      for a customer, 200 with a `Cache-Control` header containing `no-store` for staff, POST with a valid CSRF token
      and a staff session is 403
- [x] 3.6 Integration test `StaffPaymentOrdersMembershipIntegrationTest` (real Postgres; every
      assertion looks up the test's own payment order ids or tracking reference, never a group's
      size): a `PAID` order
      and a hand-confirmed agreement absent; `RECORDING_FAILED` listed; `ERRORED`-only listed; a 90-day-old stuck order
      listed; an attempt without a payment id lands in not-paid; a stuck attempt plus a
      no-payment-id attempt names one outcome and shows both attempts; two stuck outcomes are one
      entry; a stuck order since marked `PAID` in neither group; a stuck order absent from
      not-paid; `CREATED` and `EXPIRED` listed, a 31-day-old order not; own stuck orders in
      newest-attempt order and own not-paid orders in newest-created order; two calls return the
      own entries identically and leave the own `payment_order`, `payment_event` and `staff_alert`
      rows unchanged
- [x] 3.7 Integration test `StaffPaymentOrdersEntryIntegrationTest`: tracking reference and payment
      order id present; `createdAt` equals the fixture's instant; with the fixture's receipt set to the agreement id, the body holds no
      `agreementId`, `receipt` or `providerOrderId` key and neither the agreement id nor the
      gateway order id anywhere in the raw body (a substring search, so a suffixed receipt is
      caught too); amount mismatch carries both amounts; attempt count and both
      times; paid under this payment by a hand-typed upper-case reference; paid under another, with
      that reference absent from the body; waived; a reused payment id held by another agreement
      (recorded by hand in upper case) names its tracking reference; one held by another
      agreement's `PAID` order names that agreement's; one held by nothing has an empty `heldBy`; a
      `RECORDING_FAILED` entry has an empty `heldBy`; no holder's agreement id is in the body
- [x] 3.8 Integration test `StaffPaymentOrdersCapIntegrationTest`: 201 `EXPIRED` orders on one
      agreement, created one second apart ending now (distinct receipts), give 200 entries, `truncated`, and the oldest of
      the 201 left out; the rows are deleted in a `finally` so later tests are not pushed past the
      cap
- [x] 3.9 Run `ModularityTests` and `./gradlew spotlessApply`

## 4. Frontend

- [x] 4.1 Add `src/api/staffOrders.ts`: types and `fetchStaffOrders()` through `apiFetch`
- [x] 4.2 Unit test `staffOrders.test.ts`: the request path and method, a non-2xx becomes an error
- [x] 4.3 Add `src/views/staffOrderFormat.ts`: outcome and status labels (unknown code returned as
      itself), the agreement payment text, the next step and double-charge note per the spec's
      table (design D13), money from minor units without `Intl` currency style
      (`INR` as `₹`, any other code as amount then code)
- [x] 4.4 Unit test `staffOrderFormat.test.ts`: every label, an unknown outcome and `FAILED`
      returned as their codes, `₹499.00`, an invalid currency code does not throw; the next step for
      each row of the spec's table in its precedence (paid under this payment wins over a mismatch,
      a mismatch over a reused id), the holder named, "this agreement" when the holder is the
      entry's own, "nothing holds this payment id now" when none, the settled-agreement step in
      place of "record it by hand", the double-charge note beside a mismatch or a named holder,
      and no next step without a stuck outcome
- [x] 4.5 Add `StaffOrders.vue`: both groups, "seen N times", the next step on each stuck entry
      and none on a not-paid entry, the runbook text, the "not listed"
      note, the cut-short note, loading, empty and error states; no `v-html` and no control that
      acts on an order
- [x] 4.6 Add the tab switch to `StaffConsole.vue`: queue by default and kept mounted with
      `v-show`, `StaffOrders` rendered with `v-if`
- [x] 4.7 Component test `StaffOrders.test.ts`: stuck group first and "Checkout open" under Not
      paid; "Amount mismatch" with both amounts and "seen 3 times"; paid under this payment;
      `FAILED` shown as its code; a stuck entry shows its next step with the holder's tracking
      reference and a not-paid entry shows none; the holder is shown on its own line even when
      the next step is the amount mismatch; the settled-agreement step without "record it by
      hand"; the double-charge note beside a mismatch on a waived agreement; no button or link inside an entry; the runbook and "not listed"
      text; markup in a payment id shown literally with no element created; an unknown currency
      leaves the other entries shown; the cut-short note; the load-error state
- [x] 4.8 Extend `StaffConsole.test.ts` (adding a mock of `../api/staffOrders`): the console opens
      on the queue and does not call `fetchStaffOrders`; opening the Orders tab calls it once; a
      certificate number typed into an open upload is still there after visiting the Orders tab,
      and the upload's file input is the same DOM node as before (so a chosen file is kept)
- [x] 4.9 Run `npm run build` and `npm run lint`

## 5. Docs

- [x] 5.1 `docs/ARCHITECTURE.md`: the ledger has two readers; replace the two sentences saying no
      console screen lists these orders; note that the stuck read scans the ledger and the 500 ms
      trigger for a partial index (design Risks)
- [x] 5.2 `docs/DEPLOYMENT.md`: the runbook section says the Orders tab replaces the "find the
      attempts" and "every order still stuck" SELECTs and step 3's two "where is it recorded"
      SELECTs, and that recording by hand still needs the agreement id from SQL
- [x] 5.3 `docs/ROADMAP.md`: remove item (1) from the `stuck-payment-order-actions` row and
      renumber
- [x] 5.4 Add the dated "Since this was parked" section of design D11 at the top of
      `openspec/changes/payment-order-close/design.md`

## 6. Verify

- [x] 6.1 Run `./run-tests.sh check` from `backend/` and report the wall-clock time

## Coverage

One row per `#### Scenario:` in `specs/staff-orders-view/spec.md`.

| Scenario | Disposition | Test |
|---|---|---|
| A paid order is not listed | COVERED | 3.6 |
| A hand-confirmed agreement with no payment order is not listed | COVERED | 3.6 |
| Nothing to list | COVERED | 2.5 |
| Reading the list changes nothing | COVERED | 3.6 |
| A recording failure is listed | COVERED | 3.6 |
| An order that only ever errored is listed | COVERED | 3.6 (listed); 2.6 and the existing `StaffAlertIntegrationTest.attemptsThatEndedAnotherWayRaiseNoAlert` (no alert) |
| An old stuck order is still listed | COVERED | 3.6 |
| An attempt without a payment id does not make an order stuck | COVERED | 3.6 |
| Only attempts with a payment id name an outcome | COVERED | 3.6 |
| Stuck orders are newest attempt first | COVERED | 3.6 |
| Two outcomes are one entry | COVERED | 3.6, 2.5 |
| A stuck order since recorded as paid leaves the list | COVERED | 3.6 |
| An open checkout is listed | COVERED | 3.6 |
| An expired order is listed | COVERED | 3.6 |
| Not-paid orders are newest first | COVERED | 3.6 |
| An order older than 30 days is not listed | COVERED | 3.6 |
| A stuck order is in one group only | COVERED | 3.6 |
| An entry carries the tracking reference and not the agreement id | COVERED | 3.7 |
| An amount mismatch shows both amounts | COVERED | 3.7 |
| A repeated attempt reports its count | COVERED | 3.7 |
| The agreement is paid under this order's payment | COVERED | 3.7, 1.4 |
| The agreement is paid under another payment | COVERED | 3.7, 1.4 |
| The agreement was waived | COVERED | 3.7 |
| The payment is held by another agreement | COVERED | 3.7 |
| The payment is held by another payment order | COVERED | 3.7 |
| Nothing holds the payment id | COVERED | 3.7 |
| Other kinds carry no holder | COVERED | 3.7, 2.5 |
| A group over the limit is cut short and says so | COVERED | 3.8, 2.5 |
| A group within the limit is complete | COVERED | 2.5 |
| An unauthenticated caller is refused | COVERED | 3.5 |
| A signed-in customer is refused | COVERED | 3.5 |
| Staff are served | COVERED | 3.5 |
| No other method is opened on the route | COVERED | 3.5 |
| The console opens on the e-stamp queue | COVERED | 4.8 |
| Opening the Orders tab shows both groups | COVERED | 4.7, 4.8 |
| A stuck order shows its kind, both amounts and its attempts | COVERED | 4.7 |
| The agreement's payment state is shown | COVERED | 4.7 |
| An unlabelled status is shown as its code | COVERED | 4.7, 4.4 |
| The tab has no action and points at the runbook | COVERED | 4.7 |
| An already recorded payment needs nothing | COVERED | 4.4 |
| An amount mismatch waits for a decision | COVERED | 4.4 |
| A reused payment id names where it is recorded | COVERED | 4.4, 4.7 |
| An unrecorded payment is to be confirmed and recorded | COVERED | 4.4 |
| A possible double charge is flagged | COVERED | 4.4, 4.7 |
| A mismatch on a settled agreement adds the double-charge note | COVERED | 4.4, 4.7 |
| A reused payment id that nothing holds is to be recorded | COVERED | 4.4 |
| A not-paid entry states no next step | COVERED | 4.4, 4.7 |
| A begun stamp upload survives a look at the Orders tab | COVERED | 4.8 |
| An unknown currency does not break the tab | COVERED | 4.7, 4.4 |
| Response text is not rendered as markup | COVERED | 4.7 |
| A failed load is reported | COVERED | 4.7 |

51 scenarios: 51 COVERED, 0 GROUPED, 0 MANUAL, 0 WAIVED, 0 UNMAPPED.

"Nothing to list" and "A group within the limit is complete" are covered at unit level only: the
integration suite shares one database across test classes, so an empty or three-row group cannot
be asserted there.
