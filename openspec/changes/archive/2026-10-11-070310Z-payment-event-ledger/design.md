## Context

Every gateway confirmation goes through one method, `PaymentOrderService.applyConfirmation`.
The webhook calls it directly; the browser callback and the reconciliation job reach it through
`readAuthoritatively`. It calls the transactional `PaymentConfirmations.apply` and catches a
`DataIntegrityViolationException` **outside** that transaction: a refusal by one of the two
payment uniqueness rules becomes `DUPLICATE_REFERENCE`, any other refusal is thrown as
`PaymentRecordingFailedException` (`PaymentIntegrity`).

No caller of `applyConfirmation` holds a transaction: `PaymentOrderService`,
`RazorpayWebhookService` and `PaymentReconciliationJob` carry no `@Transactional`. So when the
catch runs, the failed transaction is already over and nothing durable was written.

`StampIntakeAuditor` is the existing precedent for "record the attempt that rolled back": a
separate bean, `REQUIRES_NEW`, never throws.

## Goals / Non-Goals

**Goals:**

- Every confirmation attempt is recorded, on every path through `applyConfirmation` including
  the paths that throw: one row per distinct attempt, and a count of its repeats (D9).
- The table cannot be grown by repeating an attempt.
- The ledger cannot alter a confirmation's outcome or the webhook's answer.
- The row is enough to troubleshoot from: what arrived, from where, and what we did.
- The two later CRs (staff alert, console panel) can read it without a schema change to what
  is stored here.

**Non-Goals:**

- Reading the ledger. No endpoint, job or screen.
- Recording attempts that never reach `applyConfirmation` (see D3).
- Recording a gateway read that throws: no confirmation was attempted.
- A guaranteed record. The write is best-effort: a database that is unreachable, or a process
  that dies between the confirmation and the ledger write, loses the row. Readers must not
  assume the ledger is complete.
- Deleting rows. Rows are kept, like `payment_order` rows (D10).

## Decisions

### D1. The row is written in `applyConfirmation`, on all four exits

`applyConfirmation` is the one place that sees every attempt and every result. It records:

1. after `confirmations.apply` returns, with the returned outcome;
2. when a uniqueness rule refused: `DUPLICATE_REFERENCE` plus the rule and SQL state;
3. when the database refused for another reason: `RECORDING_FAILED` plus the rule (when named)
   and SQL state, **before** the throw;
4. when `confirmations.apply` throws anything else: `ERRORED`, then the same exception is
   rethrown unchanged. `PaymentOrder` is versioned (`PaymentOrder.java:86`) and the
   confirmation needs a connection, so an optimistic-lock failure, a commit-time failure or a
   connection pool that times out can end an attempt in a server error. The payment may be as
   unrecorded as in exit 3, and a reader of the ledger must be able to see it. The SQL state
   is stored when the error's cause chain carries one (`PaymentIntegrity.sqlState`, widened to
   take any `Throwable`); nothing else from the exception is, and no refused rule. Which class
   threw is not stored: every `ERRORED` attempt ends as a server error, which the server log
   already records with its stack trace, and `last_received_at` says where to look.

The shape is fixed, because the order matters:

```java
Instant receivedAt = Instant.now();
ConfirmationOutcome outcome;
try {
  outcome = confirmations.apply(...);
} catch (DataIntegrityViolationException refused) {
  // exits 2 and 3: record, then return DUPLICATE_REFERENCE or throw
} catch (RuntimeException other) {
  // exit 4: record ERRORED, rethrow other
}
ledger.record(...);   // exit 1, outside the try: a ledger fault can never be classified as a refusal
return outcome;
```

`applyConfirmation` adds no guard of its own around the ledger call. The guarantee that the
ledger cannot fail its caller has one home, `PaymentEventLedger` (D2).

The received time is taken once at the top of `applyConfirmation`, so it is when the attempt
arrived, not when the row was written.

*Alternative: write inside `PaymentConfirmations.apply`.* Rejected: a row written in that
transaction rolls back with it, which loses exactly the rows this change exists for.

*Alternative: write in each of the three callers.* Rejected: three copies of one rule, and the
callback path swallows the failure before its caller could see the rule and SQL state.

### D2. A separate bean, its own transaction, and a caller that cannot be failed

`PaymentEventLedger.record(...)` runs the write (one statement, D9) in a `TransactionTemplate`
set to `REQUIRES_NEW`, and wraps the **whole template call** in `try/catch (RuntimeException)`.

- `REQUIRES_NEW` is defensive: no caller holds a transaction today, so the write would commit
  on its own anyway. It keeps the guarantee if a caller ever becomes transactional.
- The catch sits outside the template, not inside the transactional body. A failure at flush or
  commit, or a transaction already marked rollback-only, surfaces from the
  template call and is caught there. (`StampIntakeAuditor` catches inside its
  `@Transactional` method, where a commit-time failure escapes. That is a small defect in
  another aggregate; it is noted in the wrap-up, not changed here.)
- On failure it logs one WARN carrying the source and the outcome token, nothing else.
- It takes one connection, after the confirmation's connection has been returned, so an
  attempt never holds two at once. That stops being true if a caller becomes transactional;
  such a change must revisit this. A ledger that *blocks* (an exhausted pool) delays the
  webhook's answer rather than changing it; the pool's own acquire timeout is the bound.

`TransactionTemplate` rather than `@Transactional(REQUIRES_NEW)` because the catch must
enclose the commit, and `PaymentOrderService` already uses the same idiom.

### D3. What counts as an attempt

An attempt is a call that reaches `applyConfirmation`. That gives:

| Situation | Row? |
|---|---|
| Verified webhook, `payment.captured` or `order.paid`, naming an order | yes |
| Webhook signature invalid (`401`) | no - unverified data |
| Verified body that does not parse, another event type, or no order id | no - nothing attempted |
| Callback or reconciliation, gateway reports paid and captured | yes |
| Callback or reconciliation, gateway reports unpaid, or paid with no captured payment | no |
| Callback with an invalid handler signature, or naming another agreement's order | no |

The reconciliation job reads every outstanding order every five minutes. Recording the
"still unpaid" reads would write a row per outstanding order per sweep and bury the rows that
matter.

"Paid with no captured payment" is excluded for a different reason: nothing was reported that
could be applied. No payment id and no amount exist to record, and the state is the gateway's
own, visible by re-reading the order. The console CR re-reads the gateway for exactly this.

The callback's stored payment id is the one the **gateway read** returned, never the one the
browser presented: `readAuthoritatively` already passes the former, and tasks 2.5 and 3.7 pin it.

### D4. The origin is passed in, not inferred

`applyConfirmation` cannot know who called it. A small record carries it:

```java
record PaymentEventOrigin(Source source, String presentedEventId, String eventType) {
  enum Source { WEBHOOK, BROWSER_CALLBACK, RECONCILIATION }
  static PaymentEventOrigin webhook(String presentedEventId, String eventType) { ... }
  static PaymentEventOrigin browserCallback() { ... }
  static PaymentEventOrigin reconciliation() { ... }
}
```

`applyConfirmation` and both `readAuthoritatively` overloads take it as their first parameter.
The later "retry now" action adds a `Source` value in its own CR.

### D5. The event id is an unauthenticated label

`X-Razorpay-Event-Id` is a header. The webhook HMAC covers the body only, so anyone replaying a
captured, validly signed body can present any event id. Therefore:

- `PaymentEventOrigin.webhook` keeps the presented value only when it matches
  `[A-Za-z0-9_-]{1,64}`; anything else, or a missing header, is stored as `NULL`.
- Nothing branches on it. It is not unique in the table. A later CR must not deduplicate on it
  either; the alert is keyed on the payment order.
- The column is named `presented_event_id`, not `gateway_event_id`: a replay with a fresh,
  plausible id must not read to staff as a distinct genuine delivery.
- The controller declares the header `required = false`.

The event **type** comes from the signed body and is one of the two constants the webhook
service already matches, so it needs no check.

### D6. Every stored string is bounded before the write

The fixture that provokes a recording failure is a 65-character payment id against
`payment_order.provider_payment_id VARCHAR(64)`. A ledger column of the same width would refuse
the same value and the row this change exists for would be lost.

Columns are wider than any real gateway value, and `PaymentEvent`'s factory makes every string
storable before the write: it removes NUL characters (Postgres refuses them in text), truncates
to the column width, and drops a trailing half of a surrogate pair left by the cut. A value
that broke the payment write for one of those reasons therefore does not break the ledger
write. Truncation would shorten the order id a reader joins on, but only past 255 characters,
against a 64-character order column and real ids of about twenty.

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT GENERATED ALWAYS AS IDENTITY` PK | database-assigned |
| `attempt_key` | `CHAR(64) NOT NULL`, unique | SHA-256 of the nine values that make an attempt distinct (D9) |
| `source` | `VARCHAR(24) NOT NULL` | a `Source` name |
| `presented_event_id` | `VARCHAR(64)` | D5 |
| `event_type` | `VARCHAR(32)` | webhook only |
| `provider_order_id` | `VARCHAR(255) NOT NULL` | made storable |
| `provider_payment_id` | `VARCHAR(255)` | made storable; `NULL` when none was reported |
| `amount_minor_units` | `BIGINT NOT NULL` | as reported; `0` means the webhook carried no integral amount, exactly as the confirmation reads it |
| `currency` | `VARCHAR(16)` | made storable |
| `outcome` | `VARCHAR(24) NOT NULL` | a `PaymentEventOutcome` name |
| `refused_rule` | `VARCHAR(128)` | from `PaymentIntegrity.violatedConstraint`, truncated |
| `sql_state` | `VARCHAR(8)` | from `PaymentIntegrity.sqlState`, truncated |
| `first_received_at` | `TIMESTAMPTZ NOT NULL` | when the first such attempt arrived |
| `last_received_at` | `TIMESTAMPTZ NOT NULL` | when the latest such attempt arrived |
| `attempt_count` | `BIGINT NOT NULL` | `1` on insert, raised by each repeat |

**No check constraint on `source` or `outcome`.** The enums are the one home for those lists.
A database copy would be a second one, and the failure it produces is silent by design: a new
enum value without its migration would have every such row refused and swallowed (D2). One
class writes this table, through the enums, so the constraint would guard nothing else. An
integration test writes every `Source` and every `PaymentEventOutcome` through the ledger and
reads them back, which also pins the column widths.

Two indexes: the unique one on `attempt_key`, and `(provider_order_id, first_received_at)` for
"the history of this order". No index for the alert sweep; that CR knows its query and adds
its own.

The table is written by one native statement and read by nothing in this change, so there is
**no JPA entity** for it: `PaymentEvent` is a plain record that makes the values storable and
computes the key, and `PaymentEventLedger` writes it with `JdbcClient`. The later CRs add the
read mapping they need.

**Contract for readers.** The received time is taken before the confirmation waits on the
order's row lock, so rows are not written in received order, and identity values are not commit
order either. A row also changes after it is written (D9). So a reader:

- must find "what have I not handled yet" by comparing against its own state (as the alert
  sweep does against `staff_alert`), never by a time or id watermark;
- must not read "a row exists" as "this was already handled". A failure that comes back after
  it was dealt with only raises the count on the same row, and every `ERRORED` attempt for one
  order and source shares one row. Compare `last_received_at` with the reader's own
  handled-at time;
- must not read `attempt_count` as severity or as the gateway's retry count, nor
  `last_received_at` as a gateway delivery time. On a `WEBHOOK` row a replay raises both, and
  on a `BROWSER_CALLBACK` row the payer can;
- must not read a `RECORDING_FAILED` or `ERRORED` row as "this order is unpaid". A replay can
  leave one on an order that was paid long ago. `payment_order` and the agreement say whether
  it is paid;
- must encode the strings when rendering them: they are gateway-supplied free text;
- must not treat `presented_event_id` as the gateway's. It is whichever value was presented
  first for that distinct attempt, by whoever got there first;
- must not assume the ledger is complete (best-effort), and must read `payment_order.surplus`
  to tell a surplus `CONFIRMED` from the payment that paid the agreement.

A later change to which values make an attempt distinct, to a column width, or to the key's
encoding gives an identical attempt a second row from then on. That is harmless to these
readers, which group by order, but such a change must say so.

The exception message is never stored: it is the SQL and the failing row.

### D7. No foreign key

The row is keyed on the gateway order id as text, with no reference to `payment_order` or
`agreement`.

- An `UNKNOWN_ORDER` attempt has no order row to reference.
- A reader joins on `payment_order.provider_order_id`, which is unique.
- `staff_alert` made the same choice for its own reason (V28).

The stale-draft purge is unaffected: it already skips any agreement that has a payment order,
and the ledger references neither table.

### D8. The outcome is its own enum, and the HTTP status is not stored

`PaymentEventOutcome` holds the six `ConfirmationOutcome` names, `RECORDING_FAILED` and
`ERRORED`, with `of(ConfirmationOutcome)`. A unit test fails if a `ConfirmationOutcome` value
has no mapping, so adding an outcome cannot silently break the ledger. The last two are not
added to `ConfirmationOutcome`, which stays "what a confirmation returned"; those attempts
return nothing, they throw.

The HTTP status is a function of source and outcome, decided by the controller. `applyConfirmation`
could only store a prediction of it, and the stored copy would drift when the controller's
mapping changes. It is derived by whoever reads the ledger. As the code stands:

| Outcome | Webhook | Browser callback | Reconciliation |
|---|---|---|---|
| `RECORDING_FAILED` | server error | `200` with progress (`acknowledgeCheckout` catches it) | none; the order is skipped |
| `ERRORED` | server error | server error (only the recording failure is caught) | none; the order is skipped |
| anything else | `202` | `200` with progress | none |

`docs/ARCHITECTURE.md` carries this table (task 4.1); the proposal points here.

### D9. A repeat raises a counter instead of adding a row

**Decided by the product owner, 2026-10-11, after review round 1** (option B of three; the
alternatives are below).

A row is one **distinct** attempt: an attempt that differs from every earlier one in something
other than its time and its presented event id. An attempt identical to an earlier one in

    order id, source, event type, outcome, payment id, amount, currency, refused rule, SQL state

does not insert. It adds one to that row's `attempt_count` and moves its `last_received_at`
forward. `first_received_at` is the earliest received time among them and `last_received_at`
the latest, whatever order they reach the ledger in. `presented_event_id` stays as first
written. That is a choice of which unverified value to keep, not a protection: whoever reaches
a distinct attempt first sets it, and it is no more trustworthy than a later one (D5).

Why, from review round 1:

- *Honest traffic.* A stuck order is re-read every reconciliation sweep, about 288 times a day,
  with no exit: a recording failure throws before the job can expire the order, and an amount
  mismatch or refused duplicate returns before the expiry branch
  (`PaymentReconciliationJob.java:101-105`). The alert CR only notifies; only the console CR's
  actions end it. One row then reads "failing since 10:02, last seen 14:07, 50 times", which is
  what a reader wants and what it would otherwise have to compute from 50 rows.
- *Hostile traffic.* The webhook route is excluded from rate limiting because HMAC was judged
  enough (`RouteClassifier.java:78`), and it has no replay window. Today a replayed signed body
  writes nothing. A payer can also re-send their own valid browser callback at the
  60-a-minute-per-agreement write limit. With a row per attempt both were unbounded durable
  writes. Now a replay can only raise a counter: every value in the key comes from a signed
  body or from the gateway's own answer, so the number of rows is bounded by the number of
  distinct things the gateway has actually said.

**How.** The key is one column, `attempt_key`: the SHA-256, in hex, of the nine stored values
joined with a separator that cannot occur in them (the unit separator, U+001F, which is
removed from every value when it is made storable, D6). A `NULL` is encoded as a single NUL
character, which no stored value can contain, so `NULL`, the empty string and the text "null"
all differ. The encoding is fixed: UTF-8 bytes, the amount as `Long.toString`, enums by
`name()`, lower-case hex.
`PaymentEvent` computes it from the values it is about to store, so the key and the row cannot
disagree. The write is one statement:

```sql
INSERT INTO payment_event (attempt_key, source, ..., first_received_at, last_received_at, attempt_count)
VALUES (:key, :source, ..., :receivedAt, :receivedAt, 1)
ON CONFLICT (attempt_key) DO UPDATE
   SET attempt_count    = payment_event.attempt_count + 1,
       first_received_at = LEAST(payment_event.first_received_at, EXCLUDED.first_received_at),
       last_received_at  = GREATEST(payment_event.last_received_at, EXCLUDED.last_received_at)
```

`LEAST` and `GREATEST`, because attempts do not arrive at the ledger in the order they were
received (D6). The received time is bound as `OffsetDateTime.ofInstant(receivedAt,
ZoneOffset.UTC)`: neither the Postgres driver nor Spring's statement utilities can bind an
`Instant`, and the failure would be swallowed by D2 as one WARN per write. Nullable strings
are bound with an explicit `Types.VARCHAR`.
A hash column rather than a nine-column unique index: two of the columns are 255 characters
wide and a multi-byte value could pass the index row limit, which would fail the write.

*Alternative A: one row per attempt, unbounded.* The first draft. The complete record, and
strictly append-only, but it leaves the growth above and needs a purge.

*Alternative C: one row per attempt, capped per order, source and outcome.* Append-only and
bounded, but past the cap the ledger no longer shows that the order is still failing.

What B gives up: the time and presented event id of each repeat between the first and the
last. The gateway's own dashboard has every delivery.

### D10. Retention

Rows are kept. They hold no party or payer PII, and the categories match the payment records
the privacy policy already says are kept as long as tax law requires.

`UNKNOWN_ORDER`, `AMOUNT_MISMATCH`, `RECORDING_FAILED` and `ERRORED` rows are not records of a
payment we took, so "as long as tax law requires" does not obviously cover them. That is
picked up by the existing release checklist item in `docs/ROADMAP.md` ("the privacy inventory
is re-checked against every change merged since counsel's review"); no separate row. With D9
the table grows with distinct payments, not with time, so no purge is planned.

## Risks / Trade-offs

- [The ledger is no longer strictly append-only] → Only `attempt_count` and `last_received_at`
  of an existing row ever change, in one statement, and only for an identical attempt (D9).
- [A replay storm contends on one row] → Each replay is one short single-row update, taken
  after the confirmation's locks are released. It delays replays, not payments.
- [The ledger write adds a second database round trip to every confirmation] → One small
  statement after the confirmation committed; the order and agreement locks are already
  released.
- [A ledger write fails silently] → It logs a WARN with the outcome. The confirmation result is
  unaffected by design; losing a ledger row is strictly better than failing a payment.
- [The event id can be forged on a replayed body] → It is a label only (D5). Keeping the first
  one presented is not a defence; readers are told not to trust it (D6).
- [A replay of a settled order was a read and is now a committed write] → One single-row
  update per replay, bounded in rows (D9); it can also leave an `ERRORED` row on a paid order
  when the pool is exhausted, which is why readers decide "paid" from `payment_order` (D6).
- [Every ledger write could fail the same way and nobody notices, because it is swallowed] →
  The integration tests read real rows back (tasks 3.2, 3.9), so a statement that cannot run
  fails the suite, not just a WARN.
- [Rows for the same stuck order exist once per source] → Intended. The source column tells
  them apart; readers group by order.

## Migration Plan

`V29__payment_event.sql` creates one new table, one unique index and one index. No existing
table is altered and nothing is backfilled: attempts before the migration were never recorded.
Rollback is a forward migration dropping the table; no other code depends on it in this change.

## Open Questions

None.
