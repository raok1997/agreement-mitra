## Context

Two things already exist and this change joins them.

**The staff alert sweep** (`signing.staffalert`, every 30 seconds). `StaffAlertDispatcher.enqueue`
reads two look-backs through the `PaymentOrderQuery` seam (agreements with an order paid in the
last 24 hours, and surplus orders paid in the last 24 hours) and inserts a pending `staff_alert`
row for each one that has none (`INSERT ... ON CONFLICT (id) DO NOTHING`). `dispatchOne` then
claims, sends and settles each due row. The key is `staff_alert.id` alone: the agreement id for
`ORDER_PAID`, the payment order id for `DUPLICATE_PAYMENT` (V28).

**The payment event ledger** (`payment_event`, V29). One row per distinct confirmation attempt
that reached `PaymentOrderService.applyConfirmation`, with its outcome, a repeat count and
`first_received_at` / `last_received_at`. It is written outside the confirmation transaction, so
a refused or rolled-back confirmation still leaves a row. Nothing reads it yet. Its reader
contract is in `docs/ARCHITECTURE.md`.

Three ledger outcomes mean "the gateway captured a payment and it was not recorded against this
order": `RECORDING_FAILED` (the database refused the write; the webhook answers `500`),
`DUPLICATE_REFERENCE` (the payment id is already held elsewhere; acknowledged with a WARN) and
`AMOUNT_MISMATCH` (refused; acknowledged with a WARN). In all three the order is left as it
was, normally `CREATED`.

User decisions, 2026-10-11: key on `(id, kind)`; a distinct kind for each case; alert on those
three outcomes and not on `ERRORED`; no alert for an order already recorded as paid.

## Goals / Non-Goals

**Goals:**

- Staff are told, once per payment order and kind, that a captured payment was not recorded.
- The alert is derived from the ledger by the existing sweep. The payment confirmation path is
  not touched.
- One payment order can hold alerts of different kinds.

**Non-Goals:**

- The staff console panel and its actions, and any link to it.
- Alerting on `ERRORED`, `MISSING_REFERENCE` or `UNKNOWN_ORDER`.
- Re-alerting when a failure recurs.
- Any new field in the message, any new configuration, any change to delivery or retry.

## Decisions

### D1. The sweep reads the ledger; the confirmation is not touched

`StaffAlertDispatcher.enqueue` gains a third read, `PaymentOrderQuery.unrecordedPaymentsSince(cutoff)`,
beside the two it has. It runs **before** them (decided 2026-10-11, after code review): the two
older reads are unguarded, so placed after them the new look-back would be skipped whenever one of
them threw. The existing requirement "Staff alerts stay off the payment confirmation
path" therefore still holds, and no webhook or confirmation code changes.

**The third look-back cannot stop the other two.** `enqueue` runs before the per-alert `try`,
so a fault in the new look-back would otherwise abort the whole sweep and silence `ORDER_PAID`
and `DUPLICATE_PAYMENT` too. Both halves are isolated, the read and the write:

- The unrecorded payments are inserted by their own `StaffAlertPersistence` method in their own
  transaction, not in the transaction that inserts the paid and surplus alerts. A refused insert
  of a new kind therefore rolls back nothing else.
- The read and that insert sit in one `try`. On a `RuntimeException` the dispatcher logs one
  line naming the look-back and the exception class only, and carries on to the dispatch loop.
- The line is an **ERROR**, not the WARN the sweep uses elsewhere. A fault here is persistent,
  not transient; it means no unrecorded-payment alert at all; and the deploy post-check reads
  ERROR lines only.

*Alternative: raise the alert from `applyConfirmation` where the outcome is known.* Rejected. It
would put a write on the confirmation path, and the ledger row is already the durable record of
the trigger, exactly as the paid order row is for the other two kinds.

### D2. Three outcomes alert; the rest do not

| Ledger outcome | Alerts | Why |
|---|---|---|
| `RECORDING_FAILED` | yes | Captured, the database refused it, the order is stuck |
| `DUPLICATE_REFERENCE` | yes | Refused and acknowledged; the gateway will not redeliver |
| `AMOUNT_MISMATCH` | yes | Refused and acknowledged; the order is stuck the same way |
| `ERRORED` | no | Any exception, including a database outage a redelivery clears |
| `MISSING_REFERENCE` | no | Deliberately left for the sibling event or the reconciliation read |
| `UNKNOWN_ORDER` | no | No order of ours, so no agreement to name |
| `CONFIRMED`, `ALREADY_CONFIRMED` | no | Nothing is wrong |

An order whose only attempts end `ERRORED` for a long time is still told to no one. That is the
accepted gap of this decision; the console panel lists it.

### D3. One kind per outcome, and the mapping has two halves

`StaffAlertKind` gains `PAYMENT_NOT_RECORDED`, `PAYMENT_ID_REUSED` and `PAYMENT_AMOUNT_MISMATCH`.
All fit `staff_alert.kind VARCHAR(24)` (the longest is 23).

The seam returns a public record at the module root, beside `SurplusPayment`:

```java
public record UnrecordedPayment(UUID paymentOrderId, UUID agreementId, Reason reason) {
  public enum Reason { NOT_RECORDED, ID_REUSED, AMOUNT_MISMATCH }
}
```

- `signing.payment` maps a ledger outcome to a `Reason` in **one** place: a constant
  `Map<PaymentEventOutcome, Reason>` on `PaymentOrderQueryAdapter`. Its keys are the outcomes the
  query asks for (D7) and its values are what the rows are mapped to. `PaymentEventOutcome` stays
  package-private.
- "Unrecorded" means not recorded against this order. For `ID_REUSED` the payment id is already
  recorded somewhere else; the javadoc says so.
- `signing.staffalert` maps a `Reason` to a `StaffAlertKind` with an exhaustive `switch`, so a
  new reason fails the build there.

`toString()` redacts both ids, as `SurplusPayment` does.

*Alternative: one kind with one vague lead.* Rejected by the user: staff do different things for
each case.

*Alternative: return the outcome name as a string.* Rejected: it would make the ledger's
vocabulary part of the seam, and a typo would be a runtime miss.

### D4. The key is `(id, kind)`

`staff_alert`'s primary key becomes `(id, kind)`. `id` keeps its meaning, the alert's subject:
the agreement id for `ORDER_PAID`, the payment order id for every other kind.

- A new package-private record `StaffAlertKey(UUID id, StaffAlertKind kind)` is the JPA id class
  (`@IdClass`), so `StaffAlert` keeps its two columns as they are.
- `StaffAlertRepository` becomes `Repository<StaffAlert, StaffAlertKey>`. `findById`, `claim`,
  `markSent` and `markFailed` take the key and add `and a.kind = :kind`. `findDueIds` returns
  keys through a JPQL constructor expression and orders by `nextAttemptAt, id, kind`, so the
  order stays total. The insert becomes `ON CONFLICT (id, kind) DO NOTHING`.
- The codebase has no `@IdClass` today. A record works as the id class only because the
  repository is the bare `Repository`: Spring Data's `save` and `delete` populate an id-class
  instance through setters, which a record has none of. `StaffAlertKey`'s javadoc says so. If
  the record does not hold up, `@EmbeddedId` is the fallback; the tasks prove it first.
- `StaffAlertPersistence` and `StaffAlertDispatcher` pass the key where they passed the UUID.
- `staff_alert_order_paid_key_check` (`kind <> 'ORDER_PAID' OR id = agreement_id`) is kept, so
  "one paid-order alert per agreement" is still enforced by the key.
- Existing rows already have both columns `NOT NULL`, so the migration rewrites no row.

*Alternative: keep `id` as the key and derive it from the kind and the order id.* Rejected by the
user: `id` would stop being the order id, which V28's comments and the `StaffAlert` javadoc
promise and which the runbook SQL relies on.

### D5. Only an order that is not paid, decided when the alert is recorded

The look-back keeps an order whose status is anything but `PAID`. Not "is `CREATED`": a stuck
order becomes `EXPIRED` when the customer reopens checkout after the order's lifetime
(`PaymentOrderService.reusableOrder`), which is likely within hours for someone who paid and
still sees "unpaid", and its money is still captured. Reconciliation expires a captured order
only when the gateway read itself fails past that lifetime. Nothing writes `FAILED` today.

Only the order is consulted, never the agreement's payment state. An expired order whose
customer then paid again on a new order, and a surplus payment whose order write was refused,
are both "not recorded" on an agreement that is already paid. Reading the agreement would hide
exactly those, and they are possible double charges. This is why no lead says the agreement is
unpaid (D8).

The check is made once, when the pending row is inserted. An order that is recorded in the
seconds between the insert and the send is alerted anyway. Re-checking at send time would need a
status for "no longer needed", which `PENDING / SENT / FAILED` does not have, and `FAILED` logs
an ERROR. The window is one sweep on the first attempt, and a stale alert costs staff one look.

### D6. The look-back is on `last_received_at`, and an alert is raised once

- The window is `StaffAlertBackoff.WINDOW` (24 hours), compared with `last_received_at`. The
  reader contract forbids an id or first-received watermark, because a ledger row changes after
  it is written.
- "Not handled yet" is the reader's own state: the `staff_alert` row for `(order id, kind)`.
  Insert-if-absent is the whole comparison. A row in any status blocks a second one, so a
  failure that recurs after the alert was sent, or after it failed, raises nothing.
- A stuck `CREATED` order is re-read by the reconciliation job every run, which keeps
  `last_received_at` fresh. So when the channel is switched on, a `CREATED` order that has been
  failing for days **is** alerted. That differs from the other two kinds, where switching on
  does not alert old orders, and it is intended: this order is still stuck now. Once the order
  is `EXPIRED` it is no longer re-read, so it leaves the window 24 hours after its last attempt.
- While it is `CREATED`, the look-back does not drain: the read returns the order on every sweep
  and the insert is a no-op each time. That is one short transaction every 30 seconds, accepted.
- **A failed alert is not re-raised**, the same as the other kinds. Here the cost is higher,
  because the order is still stuck and no console queue lists it yet. The runbook therefore
  gains a query that lists every stuck order without reading `staff_alert` (any order that is
  not `PAID`, with no time bound), and a re-queue statement for these three kinds with no
  24-hour bound that skips orders since recorded as paid (task 5.2). Re-raising a failed row automatically
  was considered and left out: it changes the delivery rules for every kind's shared machinery,
  and the console panel is the durable answer.
- Several ledger rows can name one order and outcome (one per source, or per distinct payment
  id). The query returns distinct `(order id, agreement id, outcome)`, and the key absorbs the
  rest.

### D7. One native query, one home for the outcome list, and a plain index

`PaymentOrderRepository` gains a native query (the ledger has no entity):

```sql
SELECT DISTINCT o.id AS paymentOrderId, o.agreement_id AS agreementId, e.outcome AS outcome
  FROM payment_event e
  JOIN payment_order o ON o.provider_order_id = e.provider_order_id
 WHERE e.outcome IN (:outcomes)
   AND e.provider_payment_id IS NOT NULL
   AND e.last_received_at >= :cutoff
   AND o.status <> 'PAID'
```

`provider_payment_id IS NOT NULL` (decided 2026-10-11, after code review): the confirmation checks
the amount before the missing payment id, and the webhook parses an absent amount as 0, so a
gateway event without payment detail is recorded as `AMOUNT_MISMATCH`. That is not a captured
payment. A real `RECORDING_FAILED` or `DUPLICATE_REFERENCE` is reached only after the missing-id
check, so it always carries the id and the predicate drops nothing else. The aliases are quoted in
the code so Postgres keeps their case.

It returns an interface projection whose getter names equal the aliases, and
`PaymentOrderQueryAdapter` maps each row to an `UnrecordedPayment`. The inner join is what drops
an attempt for an order we do not hold; `uq_payment_order_provider_order_id` keeps it one to one.

`:outcomes` is bound from the keys of the adapter's outcome-to-reason map (D3), by name. The
three names are therefore written once, in Java. V29 put no check constraint on `outcome` so the
enum would be the only home, and this keeps it so: no SQL literal list, no test that parses a
query.

It is a repository query and not a `JdbcClient` call: `RazorpayPaymentIntegrationTest` makes
`JdbcClient` throw to prove the ledger cannot fail a confirmation, on the stated basis that
nothing but the ledger uses it.

V30 adds a plain index:

```sql
CREATE INDEX idx_payment_event_last_received ON payment_event (last_received_at);
```

V29's indexes are on `attempt_key` and `(provider_order_id, first_received_at)`, so without it
the 30-second sweep reads the whole table. A partial index on the three outcomes was considered
and dropped: it would be a second copy of the outcome list, in an immutable migration, and the
time bound is already selective because a repeat raises a count instead of adding a row.

### D8. The message gains three leads and nothing else

`StaffAlertMessage` is unchanged. `DiscordStaffNotifier.lead` is an exhaustive `switch`, so the
three kinds must each be given a lead to compile:

| Kind | Lead |
|---|---|
| `PAYMENT_NOT_RECORDED` | `Payment captured but not recorded - check before recording or refunding` |
| `PAYMENT_ID_REUSED` | `Payment refused: its reference is already recorded elsewhere - check before recording or refunding` |
| `PAYMENT_AMOUNT_MISMATCH` | `Payment refused: the amount paid differs from the order - check before recording or refunding` |

No lead says the agreement is unpaid or that checkout is blocked: neither is checked (D5).

The register row says the alert links to the console panel. The panel does not exist, so the
link stays the public site. Staff find the payment by tracking reference.

## Risks / Trade-offs

- [The ledger is best-effort: a row that could not be written means no alert] → The ERROR line
  for a recording failure is unchanged, and the webhook still answers `500`. Stated in the
  runbook.
- [An `ERRORED`-only order is still silent] → D2. The console panel covers it.
- [An order recorded between the insert and the send is alerted] → D5. One sweep wide.
- [The alert says a payment has a problem but not which payment] → By design: no gateway id may
  reach the channel. The runbook gives the ledger query by tracking reference.
- [A failed alert is not re-sent, and the key then blocks it for good, while the order is still
  stuck and no console queue lists it] → D6. The runbook gets a ledger-only query for stuck
  orders and an unbounded re-queue for these kinds.
- [The alert prompts a manual write to money state, and anyone holding the channel URL can post
  a look-alike message; a re-queue, a resend or a redeploy after a rollback can also repeat an
  alert staff already handled] → The runbook's first step is "is this payment already
  recorded?": compare the agreement's payment state and reference with the ledger row's gateway
  payment id, then confirm the capture in the gateway. A channel message is never proof.
- [Recording by hand under some other reference leaves the order to confirm later as surplus,
  raising "Possible duplicate payment" for one payment] → The runbook says to record under the
  exact gateway payment id from the ledger row. The later confirmation then finds the payment
  already recorded (`PaymentRecording.ALREADY_RECORDED`) and marks the order paid, not surplus.
  If a duplicate-payment alert follows anyway, staff follow its existing "Confirm two captured
  payments" step; it can be a real second charge (D5).
- [For a reused id, recording under an altered reference would defeat the refusal] → The
  uniqueness rule that refused the gateway also refuses the console; the runbook says that when
  the console refuses the reference, stop and find where it is already recorded.
- [An amount mismatch needs a decision the system cannot make] → The runbook says not to record
  by hand until someone has decided to refund or accept, and that an accepted payment is
  recorded at the amount the gateway captured.
- [The channel is off, or was off for more than 24 hours after a one-off failure] → No alert,
  as for the other kinds. A failure still recurring is picked up when the channel comes on (D6).
- [Changing a primary key under a running sweep] → One short `ALTER TABLE` on a small table. The
  backend restarts on deploy, so no old code runs against the new key. The previous build cannot
  run against it at all; see the rollback step.

## Migration Plan

1. `V30__unrecorded_payment_alert.sql`, applied on startup: drop and re-add the primary key as
   `(id, kind)`, replace `staff_alert_kind_check` with the five-value list, create the index. No
   row is rewritten and nothing is backfilled.
2. V30 is the next free number today. Three other active changes carry migrations; if one lands
   first, renumber at apply time, including the `target(...)` versions in the migration test.
3. **Rollback needs a schema step, and so does coming back.** The previous build inserts with
   `ON CONFLICT (id)`, which Postgres rejects once no unique index covers `id` alone, and that
   insert runs before any dispatch, so every kind of alert would stop. Flyway tolerates the
   future V30 row, so the previous build does start. Before starting it, in one transaction:
   list the stuck orders (the runbook query) and keep the output off the channel; delete the
   rows of the three new kinds; drop the key and re-add `PRIMARY KEY (id)`; drop and re-add
   `staff_alert_kind_check` under that exact name with the two old values; drop
   `idx_payment_event_last_received`; and delete V30's row from `flyway_schema_history`. Without
   the last two, redeploying this build skips V30 and its `ON CONFLICT (id, kind)` insert fails
   the same way in the other direction, with a clean startup. With them, V30 re-applies. The
   statements go in the runbook (task 5.2). Deleting those rows also deletes "already alerted",
   so a redeploy alerts again every order still inside the window.
4. On first start after deploy, orders that have been failing inside the last 24 hours are
   alerted once each (D6). On production today that is expected to be none.

## Open Questions

None.
