## Context

`payment_order` holds one row per gateway order; `payment_event` (the ledger) holds one row per
distinct confirmation attempt with a repeat count. The ledger has one reader today, the staff alert
sweep, which uses `PaymentOrderRepository.findUnrecordedSince` and the three outcomes in
`PaymentOrderQueryAdapter.UNRECORDED_OUTCOMES`. Staff work an alert from SQL in
`docs/DEPLOYMENT.md`.

Facts checked in the code before designing:

- `ERRORED` rows carry the gateway payment id (`PaymentOrderService.applyConfirmation` passes it on
  every exit), so "carrying a payment id" is meaningful for all four outcomes.
- `PaymentOrderStatus.FAILED` is declared but nothing calls `PaymentOrder.markFailed()`. No order
  is `FAILED` today.
- An agreement with a payment order is never purged (`isDeletableDraft`), so every order has its
  agreement.
- `Agreement.paidUnder(reference)` already defines "paid under this payment", normalising both
  sides with `PaymentConfirmation.normalizeReference`. It is package-private to `signing.agreement`.
- `AgreementService.paymentStatesByAgreementId` is the existing batch read behind the stamp queue.
  It returns the state only and hydrates whole entities.
- The staff console (`StaffConsole.vue`, 584 lines) has no tabs and holds a stamp upload form with
  a file input. Its tests mock `../api/staffQueue`.
- `GET /api/staff/payments/*` is already a STAFF matcher and matches any single segment.
- `idx_payment_event_order_first_received` is `(provider_order_id, first_received_at)`. It holds
  neither `outcome` nor `provider_payment_id`. The ledger has no index on `outcome`.
- The ledger is small by construction: a repeat raises a count and adds no row.
- The parked change `payment-order-close` already names the four-outcome set
  (`ATTENTION_OUTCOMES`) and plans a ledger read and a route under `/api/staff/payments/attention/`.
- A new `/api/staff/` route falls into the `DEFAULT` rate-limit class (120 per source), shared with
  the stamp queue.

## Goals / Non-Goals

**Goals:**

- Staff see, without SQL, which orders hold a captured payment that was not recorded and which were
  simply not paid, with the ledger attempts behind each.
- One GET. No write, no migration, no change to the payment path or the alert.
- A response that leaks (a screenshot, a paste, a log) carries no agreement id and no receipt. This
  limits leakage; it is not an authorization boundary, since staff can already read agreement ids
  from the stamp queue.

**Non-Goals:**

- Any action on an order (resolve, retry, record by hand).
- Telling a gateway decline from an abandoned checkout (`payment.failed` intake, next change).
- A Paid group, pagination, search, filtering, auto-refresh.
- An alert for `ERRORED`-only orders.
- Showing attempts for an order we do not hold (`UNKNOWN_ORDER`): they have no payment order.

## Decisions

### D1. Route is `GET /api/staff/payment-orders`

Under `/api/staff/payments/` a single-segment path such as `/orders` is already granted to STAFF by
the `*` matcher, so an exact matcher for it would decide nothing and a test of it would prove
nothing. `/api/staff/payment-orders` is outside that wildcard: without its own matcher it falls to
`anyRequest().denyAll()`, so the exact GET matcher is what grants access, and a POST there is a 403
from `denyAll`.

It also names the resource correctly. The existing `/api/staff/payments/{agreementId}` routes are
keyed by agreement; this list and any later per-order action are keyed by payment order.

A new controller, `StaffPaymentOrderController` in `signing.api`, keeps `PaymentController`'s
agreement-keyed surface apart. The route stays in the `DEFAULT` rate-limit class: a staff session
is required before the read runs, and the read is bounded (D4).

### D2. The four outcomes are `ATTENTION_OUTCOMES`, derived from the alert's list

`UNRECORDED_OUTCOMES` binds the alert look-back; adding `ERRORED` to it would start an alert that
is out of scope. The view's set is defined beside it in `PaymentOrderQueryAdapter`:
`ATTENTION_OUTCOMES` = the keys of `UNRECORDED_OUTCOMES` plus `ERRORED`. The alert's three cannot
drift from the view's four, and the alert is unchanged.

The name is the one `payment-order-close` already planned for the same set, so that change finds
the constant in place when it resumes and adds no second one (D11).

### D3. One entry per order; the entry lists its stuck outcomes and all its attempts

The alert is keyed per order and kind because each kind is a separate message. A screen row per
kind would show one order twice. An entry is one order, with `stuckOutcomes` and `attempts` (every
ledger row for the order, whatever its outcome, oldest first by `first_received_at`, then ledger
id).

All attempts, not only the stuck ones: a `MISSING_REFERENCE` or `ALREADY_CONFIRMED` row is part of
"what happened". A not-paid entry uses the same shape; its attempts are usually empty and its
`stuckOutcomes` always is.

"Stuck attempt" has one definition in SQL (D4) and one in Java: an attempt whose outcome is in
`ATTENTION_OUTCOMES` and whose payment id is not null, as a single predicate method on the ledger
row record. The row keeps its outcome as the stored string and compares names: the column has no
check constraint, and an outcome this build does not know (after a rollback, say) is passed through
to the response and never parsed into the enum. `stuckOutcomes` is computed with it. An integration test pins the two together with an
order holding one stuck attempt and one `AMOUNT_MISMATCH` attempt without a payment id: the order
is stuck and names only the first outcome.

### D4. Three bounded reads in one repeatable-read transaction

A new `PaymentOrderOverview` service in `signing.payment` (public, like `PaymentService`), its one
method annotated `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`.

**Why repeatable read.** At the default isolation each statement sees its own snapshot, and the
ledger commits in its own transaction. An attempt committed between the first and second read
would put its order in neither group. Under repeatable read all three reads and the agreement read
see one snapshot. The transaction is read-only, so it cannot fail on a serialization conflict.
Spring applies the isolation level only when the method starts the transaction, so the method must
stay the outermost transactional call: the controller calls it directly and is not transactional
(`open-in-view` is off).

`LISTED_STATUSES` is the explicit list `CREATED`, `FAILED`, `EXPIRED`, a constant in
`PaymentOrderOverview`. It is not "every status but `PAID`": a status added later (the parked
`CLOSED`) is then left out until someone decides to list it, where the opposite default would keep
every closed order in "Paid but stuck".

1. **Stuck orders** drive from the ledger, the small side:

   ```sql
   SELECT o.id AS "paymentOrderId", o.agreement_id AS "agreementId",
          o.provider_order_id AS "providerOrderId", o.status AS "status",
          o.amount_minor_units AS "amountMinorUnits", o.currency AS "currency",
          o.created_at AS "createdAt"
     FROM payment_order o
     JOIN (SELECT e.provider_order_id, max(e.last_received_at) AS latest
             FROM payment_event e
            WHERE e.outcome IN (:attention) AND e.provider_payment_id IS NOT NULL
            GROUP BY e.provider_order_id) s ON s.provider_order_id = o.provider_order_id
    WHERE o.status IN (:listed)
    ORDER BY s.latest DESC, o.id
    LIMIT :limit
   ```

2. **Not-paid orders:** `status IN (:listed)`, `created_at >= :cutoff`, `NOT EXISTS` a ledger row
   for the order matching the same attention predicate, `ORDER BY o.created_at DESC, o.id`,
   `LIMIT :limit`.
3. **Attempts:** `payment_event` rows whose `provider_order_id` is one of the listed orders.

The order id is the tie-breaker in both orderings, so the cut at the cap is the same on every call.
The caller passes 201 as the limit; the 201st row sets the group's `truncated` flag and is dropped.
When no order is listed, reads 3 and the agreement read are skipped: an empty `IN ()` is a syntax
error in Postgres.

The two order queries are native `@Query` methods in `PaymentOrderRepository` with an interface
projection that has no receipt getter. Every selected column carries a quoted camelCase alias, as
`findUnrecordedSince` does, or the getters do not match; `created_at` maps to `Instant`, which no
native projection here does yet, so a test asserts the value. The ledger read is
`PaymentEventLedger.attemptsFor(Collection<String>)`, its first read method: a `JdbcClient` query
run in the caller's transaction (not through the `ownTransaction` template the write uses), mapping
`TIMESTAMPTZ` through `OffsetDateTime` to `Instant`.

**Cost.** Read 1 scans the ledger once (no index on `outcome`) and joins by the unique
`provider_order_id`. Read 2 uses `ix_payment_order_status_created_at` for the 30-day range and
probes the ledger through `idx_payment_event_order_first_received`, reading the heap for each
probe. Read 3 uses the same index. All three are bounded by ledger size and 30 days of orders, not
by every order ever created. No new index is needed; see Risks.

### D5. The agreement's state comes through `AgreementService`; the stored reference stays inside `signing.agreement`

`signing.payment` may not read `Agreement` directly. A new public interface in the `signing` root:

```java
public interface AgreementPaymentStanding {
  String trackingReference();
  PaymentState paymentState();
  boolean paidUnder(String reference);
}
```

`AgreementService.paymentStandings(Collection<UUID>)` returns `Map<UUID, AgreementPaymentStanding>`
from one projection query (id, tracking reference, payment state, payment reference; no entity
hydration), empty for an empty input. The implementation is a package-private record in
`signing.agreement`, so the stored reference never leaves that package and no response record can
serialise it.

"Paid under" keeps one definition: a package-private static in `signing.agreement` holding the
body of today's `Agreement.paidUnder` (state is `PAID`, both references non-null, equal after
`PaymentConfirmation.normalizeReference`). `Agreement.paidUnder` and the record both call it. No
SQL compares references.

`paymentStatesByAgreementId` is left as it is: the stamp queue needs the state only, and widening
its return type would touch the queue for no gain.

The overview maps each entry to one of `UNPAID`, `WAIVED`, `PAID_THIS_PAYMENT`,
`PAID_OTHER_PAYMENT`: `PAID_THIS_PAYMENT` when the standing is paid under the payment id of any of
the order's attempts.

### D6. The backend sends codes; the frontend owns every label

The response carries `stuckOutcomes` and each attempt's `outcome` as `PaymentEventOutcome` names,
and `status` as the `PaymentOrderStatus` name. The label table lives once, in a helper beside
`StaffOrders.vue`. An unknown code renders as itself, which is where the next change's "Failed at
gateway" label slots in without a response change here. A `FAILED` order (unreachable today) is
listed in not-paid and shown as `FAILED`.

### D7. Response shape

```
{ "stuck":   { "entries": [Entry], "truncated": false },
  "notPaid": { "entries": [Entry], "truncated": false } }

Entry   { trackingReference, paymentOrderId, status, amountMinorUnits, currency, createdAt,
          agreementPayment, stuckOutcomes: [..], heldBy: [trackingReference], attempts: [Attempt] }
Attempt { source, outcome, providerPaymentId, amountMinorUnits, currency, refusedRule, sqlState,
          firstReceivedAt, lastReceivedAt, attemptCount }
```

Records in `signing.api`. Money stays integer minor units; the frontend formats. No `agreementId`,
`receipt`, `providerOrderId` or agreement payment reference field exists on any record, so
withholding is structural, and a test asserts those keys and values are absent from the JSON.

`providerPaymentId` is included: runbook step 2 records by hand under that exact id. It is
staff-only and never sent to the alert channel. The endpoint has no log statement that takes an
entry or an attempt, and the two records that hold the id (the ledger row and `Attempt`) override
`toString` to leave it out, following `PaymentOrder.toString`.

`refusedRule` and `sqlState` are our database's constraint name and SQL state, not gateway text.
The gateway-supplied strings are `currency` and `providerPaymentId`. All four are rendered as text.

### D8. The window and the cap are constants

30 days and 200 are constants in `PaymentOrderOverview`, not properties: an operator has no reason
to tune them, and a production env var would need a deploy template tag for no benefit. The service
reads `Instant.now()` as the rest of the package does; tests backdate rows, not the clock.

### D9. Frontend: a tab switch in `StaffConsole.vue`, the list in its own component

`StaffConsole.vue` gains two tab buttons ("Awaiting stamp", "Orders"). The queue section is hidden
with `v-show`, not removed, so a half-filled stamp upload (including its chosen file) survives a
look at the Orders tab. `<StaffOrders />` is rendered with `v-if`, so it mounts, and requests the
list, only when its tab is opened, and reloads each time it is reopened. The tab is component
state, not a URL: `/staff` is the only staff route and the router is a hand-rolled pathname switch.

`StaffOrders.vue` holds the two groups, the runbook text and the notes. `src/api/staffOrders.ts`
holds the fetch and the types. Labels and money formatting live in `staffOrderFormat.ts` so they
are unit-tested without mounting. Money is formatted without `Intl`'s currency style: `currency` is
a gateway string and an invalid code makes `Intl.NumberFormat` throw. `INR` renders as `₹499.00`;
any other value renders as the amount followed by the code as text.

"No buttons" is read as no control that acts on an order. The tab buttons are navigation. There is
no refresh button; leaving and reopening the tab reloads.

### D10. Docs corrected in the same change

- `docs/ARCHITECTURE.md`: "One reader today" becomes two; "The staff console panel ... does not
  exist yet and reads nothing" and "no console queue lists those orders yet" are replaced with what
  the Orders tab reads.
- `docs/DEPLOYMENT.md`: the runbook section says the Orders tab replaces the "find the attempts"
  and "every order still stuck" SELECTs and the two "where is it recorded" SELECTs of step 3, and
  what stays manual and in SQL: recording by hand (the staff action is keyed by agreement id, which
  the tab does not show).
- `docs/ROADMAP.md`: item (1) is removed from the `stuck-payment-order-actions` row and the rest
  renumbered. This is an ordinary task of this change, done before archive.

### D12. A reused payment id is resolved to its holder in the same read

Runbook step 3 has staff find where a refused payment id is already recorded, with one of two
SELECTs chosen by the attempt's `refused_rule`. The overview does both lookups for the payment ids
of the listed orders' stuck `DUPLICATE_REFERENCE` attempts, skipped when there are none:

- **Held by an agreement:** `AgreementService.trackingReferencesHolding(Collection<String>)`
  returns payment id to tracking reference. Its query matches on `UPPER(BTRIM(payment_reference))`,
  the expression of `uq_agreement_payment_reference`. That index is the rule that refused the
  write, so matching its expression is the definition of "holds it", not a new copy of the
  comparison in D5; the ids are bound upper-cased and trimmed.
- **Held by a payment order:** a `PaymentOrderRepository` query for orders whose
  `provider_payment_id` is one of the ids (`uq_payment_order_provider_payment_id`), returning the
  order id, payment id and agreement id. The entry's own order is dropped; the agreement ids go
  through `paymentStandings` (D5) for their tracking references.

Both run inside the D4 transaction, so they see the same snapshot. `heldBy` is the distinct
tracking references, own agreement included when it holds the payment through its recorded
reference (the screen then says "this agreement"). It is empty for every other kind and for
not-paid entries. Both lookups are by unique index and bounded by the number of listed stuck
attempts.

### D13. The next step is derived in the frontend from what the response already carries

The spec's next-step table and the double-charge note are a pure function of `stuckOutcomes`,
`agreementPayment` and `heldBy`. It lives in `staffOrderFormat.ts` beside the labels (D6), so the
wording has one home and is unit-tested without a server. The backend sends no prose.

Two rows were added after the code review (2026-10-12, user-approved). A reused payment id that
nothing holds any more is not "recorded elsewhere": the entry says so and sends staff to record
it. And an entry never says "record it by hand" on an agreement that is waived or paid under
another payment; it says the agreement is settled and to check for a double charge.

The wording follows the runbook's own steps and claims no more than it does: "nothing to record"
is the runbook's step 1, and "record it by hand" still points staff at the runbook for how, since
the console has no control for it (the register row's "recording a payment by hand from the
order" item).

### D11. The parked `payment-order-close` is told what changed under it

That change resumes with `/opsx:flow payment-order-close from:review`, which reads its own
directory, not the roadmap. A short dated section, "Since this was parked", is added at the top of
its `design.md`:

- `ATTENTION_OUTCOMES` and `PaymentEventLedger.attemptsFor` now exist; reuse them, add no second.
- Its resolve precondition must be the view's stuck predicate (D3), or an order can be listed with
  no exit.
- Its route should move to `POST /api/staff/payment-orders/{paymentOrderId}/resolve`.
- The `staff-orders-view` spec lists only `CREATED`, `EXPIRED` and `FAILED`, so a `CLOSED` order
  leaves the view with no delta on that capability. A delta is needed only if closed orders should
  be shown.

Nothing else in that change is edited.

## Risks / Trade-offs

- **Read 1 scans the whole ledger.** → The ledger holds one row per distinct attempt, so it grows
  with distinct failures and confirmations, not with traffic. If the endpoint passes about 500 ms,
  a partial index on the attention outcomes is the fix; that is a migration and its own change.
  Recorded in ARCHITECTURE beside the reader contract.
- **A stuck order never leaves the list** until it is `PAID`, because nothing can close one. → That
  is the gap `payment-order-close` fills. The cap of 200 and the cut-short note keep the screen
  usable meanwhile.
- **`ERRORED` rows include transient faults** (a database outage the next delivery clears). → Such
  an order becomes `PAID` on the next delivery and leaves the list. One still listed did not
  recover, which is what staff need to see.
- **The ledger is best-effort and rows change after they are written.** → The view holds no state
  of its own and re-reads everything, so the reader contract's watermark and handled-at rules do
  not apply. The count is worded "seen N times", which claims only what we received.
- **The gateway payment id is shown on a staff screen.** → Needed for the runbook; STAFF-only,
  filter-chain authorised, absent from logs, served `no-store`.
- **Recording by hand still needs the agreement id from SQL.** → Out of scope here ("record by
  hand" is its own item of the register row); the runbook says so.
