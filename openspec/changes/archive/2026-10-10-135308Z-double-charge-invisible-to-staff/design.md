## Context

Three producers confirm a gateway payment (webhook, browser-callback authoritative read,
reconciliation) and all converge on `PaymentConfirmations.apply`. Its idempotency check is
`order.status().settled()`, which is `PAID` only. An `EXPIRED` or `FAILED` order therefore still
accepts a late confirmation, and `apply` then calls `AgreementService.recordPayment`, which
overwrites the agreement's payment fields unconditionally and publishes `PaymentConfirmedEvent`
(re-sending the recovery link through `RecoveryOnPaymentListener`).

The staff alert shipped in `staff-paid-order-alert` (V27) is derived from paid orders by a
scheduled sweep and keyed by `agreement_id`, so a second paid order is a no-op by construction.

Constraints carried in:
- `staff-order-alert` keeps alert records and outbound calls off the confirmation path.
- `V27` is applied; schema changes are forward-only.
- Lock order on every payment path is payment order, then agreement.
- The gateway-confirmation requirements are still `ADDED` deltas in the active, unarchived change
  `razorpay-payment`. They cannot be `MODIFIED` from here, so this change adds its rule as a new
  requirement that names itself the one exception to "a confirmation updates the agreement's
  payment state through the seam". The two changes can archive in either order: both only `ADD`
  to `payment-processing`, under different requirement names.
- User decisions of 2026-10-10 (see `.flow-journal.md`): stop the overwrite; a gateway payment
  after a manual confirmation is a double charge, after a waiver it is not; alert only, no queue
  flag; checkout prevention and ToS wording are out.

## Goals / Non-Goals

**Goals:**
- The agreement's first payment record survives a second payment.
- Every surplus payment is durably marked, and raises one staff alert that asks for a check.
- The fact "this payment was surplus" is decided once, where it is known for certain, and stored.

**Non-Goals:**
- Preventing the second payment (`checkout-ignores-agreement-paid-state`).
- Making the readers that take an agreement's *newest* order or frozen quote
  (`StampValueReference`, the stamp queue in `StampIntakeService`, `PaymentOrderService.progress`)
  prefer the credited order. That defect exists today without any surplus payment and is recorded
  on the same register row.
- Refunding, or tracking that a refund was issued.
- Any staff-queue flag, API field or frontend change.
- Guarding the manual staff paths. Known limits that follow, both curl-only staff actions:
  a manual confirmation recorded over a gateway-paid agreement still overwrites; and an agreement
  that was paid, then waived, then paid again records the last payment as a first one.
- Backfilling surplus marks onto orders paid before V28.
- Refusing a surplus payment whose payment id is already the recorded reference of a *different*
  agreement (staff typed a gateway id by hand against the wrong agreement). Before this change that
  was `DUPLICATE_REFERENCE`, because the agreement's reference was always written; a surplus
  confirmation writes none, so it is now marked surplus and alerted like any other. Known limit,
  found in code review: the alert still brings staff to it.

## Decisions

### D1. Decide "surplus" at the agreement, under its row lock

`AgreementService` gains one public method beside `recordPayment`:

```java
public PaymentRecording recordGatewayPayment(UUID agreementId, PaymentConfirmation confirmation)
```

It loads the agreement with `findByIdForUpdate` and returns one of:

| Agreement state on entry | Result | Agreement written? |
|---|---|---|
| `UNPAID` or `WAIVED` | `RECORDED` | yes - as `recordPayment` does today, actor `null` |
| `PAID`, reference equal to the confirmation's after normalisation | `ALREADY_RECORDED` | no |
| `PAID`, any other reference (including none) | `SURPLUS` | no |

`PaymentRecording` is a public enum at the `signing` module root, next to `PaymentConfirmation`
(same precedent: a seam type shared by sub-packages of the one module).

**One normaliser.** Reference normalisation exists today only as the private
`PaymentService.normalizeReference` (trim, collapse inner whitespace, upper-case with
`Locale.ROOT`), mirrored by the `UPPER(BTRIM(...))` index. It moves to a public static
`PaymentConfirmation.normalizeReference`; `PaymentService` calls it, and the new
`Agreement.paidUnder(String reference)` compares `normalizeReference` of both sides. No third copy.

`recordPayment` (the manual STAFF seam) is **not changed**: `payment-gate` specifies it and staff
must stay able to record a payment taken out of band.

The decision reads the agreement's state at confirmation time, not the order history. That is what
makes the two known limits above possible, and it is also what keeps it a single locked read.

*Alternative rejected - guard inside `Agreement.recordPayment`:* it would silently change the
manual staff path and make the method's name a lie.
*Alternative rejected - read `paymentState` in `apply` before calling the seam:* that read is not
under the agreement lock, so a concurrent manual confirmation, or a second order confirmed at the
same instant, could slip between check and write.

### D2. `apply` records the result on the order and gates the event on it

After the existing checks (unknown order, already settled, amount, missing payment id):

1. `recording = agreementService.recordGatewayPayment(...)`
2. `order.markPaid(providerPaymentId, confirmedAt, recording == SURPLUS)`
3. publish `PaymentConfirmedEvent` **only when `recording != SURPLUS`**
4. return `CONFIRMED` in every case

- The order is still marked `PAID`: the money was captured and the order row is the durable truth.
- `ALREADY_RECORDED` still publishes the event. It is the first gateway confirmation for an
  agreement staff confirmed by hand with the same payment id, and the manual path sends no recovery
  link, so this is the one the customer gets - today's behaviour, kept.
- A customer whose agreement staff confirmed by hand under a *different* reference, and who then
  pays through the gateway, gets no recovery link from the surplus confirmation (today they do).
  Accepted: the alert brings staff to that agreement, and `/recover` by tracking reference works.
- No new `ConfirmationOutcome`. `PaymentReconciliationJob` treats `CONFIRMED` as "done" and
  `UNKNOWN_ORDER` as "still outstanding"; a surplus order is done. The webhook acknowledges as
  before, so the gateway does not redeliver.
- A surplus confirmation logs one `WARN` with the redacted order fragment - no amount, no payment
  id.

Lock order is unchanged: the order row (already locked by `findByProviderOrderIdForUpdate`), then
the agreement row inside `recordGatewayPayment`, which joins `apply`'s transaction. Two orders of
one agreement confirmed concurrently serialise on the agreement lock: one `RECORDED`, one `SURPLUS`.

### D3. Store the surplus mark on the payment order

`payment_order.surplus BOOLEAN NOT NULL DEFAULT FALSE`, set by `markPaid` and never changed after
(`markPaid` already returns early on a settled order).

*Alternative rejected - derive it in the sweep* (two or more `PAID` orders, or an order whose
payment id differs from the agreement's reference): it is the same fact computed in a second place,
it cannot see a manual confirmation without joining to the agreement, and it goes wrong when the
agreement's reference later changes. The confirmation is the only moment the answer is certain.

The column is part of the order row the confirmation already updates - no added statement, no
alert row, no outbound call. The `staff-order-alert` requirement is reworded in this change to say
exactly that, so the spec of record does not read as contradicted.

### D4. One gateway payment id, one payment order

Today the only uniqueness check on a gateway payment id is `uq_agreement_payment_reference`, which
fires when the agreement's reference is written. A surplus confirmation writes no agreement
reference, so that backstop would be lost for it, and `ALREADY_RECORDED` could not tell "staff
typed this id" from "another order already holds this id".

V28 adds `CREATE UNIQUE INDEX uq_payment_order_provider_payment_id ON payment_order
(provider_payment_id) WHERE provider_payment_id IS NOT NULL`. A second order reporting a payment
id another order holds now fails at flush. `PaymentOrderService.applyConfirmation` already maps a
`DataIntegrityViolationException` from `apply` to `DUPLICATE_REFERENCE` outside the transaction,
which is the right outcome here; nothing in that mapping changes. (Register row
`payment-confirmation-catch-all-integrity-mapping` is about narrowing that catch. This index is a
second constraint it must name when it is done; a sentence is appended to that row.)

### D5. Re-key `staff_alert` so an agreement can hold more than one alert

V28 changes `staff_alert` from "primary key = agreement" to:

| Column | Meaning |
|---|---|
| `id UUID PRIMARY KEY` | the alert's subject: the **agreement id** for `ORDER_PAID`, the **payment order id** for `DUPLICATE_PAYMENT` |
| `kind VARCHAR(24) NOT NULL` | `ORDER_PAID` or `DUPLICATE_PAYMENT`, with a CHECK; no default once backfilled |
| `agreement_id UUID NOT NULL REFERENCES agreement` | kept; no longer unique; plain index added |

plus `CHECK (kind <> 'ORDER_PAID' OR id = agreement_id)`, so "one paid-order alert per agreement"
stays enforced by the key and is not a convention.

Existing rows are backfilled `id = agreement_id`, `kind = 'ORDER_PAID'`, so every sent or failed
alert keeps its key and is not raised again. The insert stays one statement,
`ON CONFLICT (id) DO NOTHING`. `kind` is bound in the native insert as a **String**
(`kind.name()`), because Hibernate does not bind a Java enum to a native parameter as its name
(the existing repository javadoc records this).

No foreign key from `id` to `payment_order`: the insert would take a key-share lock on the order
row that `apply` locks, the same effect `StaffAlertPersistence` already notes for the agreement.

In code the dispatch machinery (claim, lease, backoff, sent/failed) is re-keyed from
`agreementId` to `id` and otherwise untouched; one sweep handles both kinds. Failure and retry log
lines name the alert kind beside the redacted agreement id, and never print the alert id.

*Alternative rejected - a second table and a second dispatcher:* duplicates the claim/lease/backoff
logic that was just reviewed, and the two copies would drift.
*Alternative rejected - nullable `payment_order_id` with two partial unique indexes, or a composite
key `(kind, subject_id)`:* same uniqueness, but the native upsert then needs a conflict target per
kind, or an `@IdClass` through every repository method. One key column plus one CHECK is less.

### D6. The sweep enqueues each kind from its own query

`PaymentOrderQuery` gains:

```java
List<SurplusPayment> surplusOrdersPaidSince(Instant cutoff);   // record SurplusPayment(UUID paymentOrderId, UUID agreementId)
```

backed by JPQL on `status = PAID and surplus = true and confirmedAt >= :cutoff`. The existing
`findAgreementIdsPaidSince` gains `and o.surplus = false`. So the two kinds are disjoint by
construction: a credited order raises the paid-order alert, a surplus order raises the
duplicate-payment alert, never both. Without that predicate a surplus payment on an agreement paid
more than 24 hours earlier would announce "Paid order waiting for a stamp" for work already done.

Both queries are bounded by V27's `idx_payment_order_paid_confirmed_at`; no new index.

`StaffAlertDispatcher.enqueue` reads both before the insert transaction and calls
`StaffAlertPersistence.enqueue(Collection<UUID> paidAgreementIds, Collection<SurplusPayment>
surplus, Instant now)`. The 24-hour window, batch size, attempts and backoff are shared.

### D7. The message gains a kind, not new data

`StaffAlertMessage` gains a `kind` field. `StaffAlertMessages.from` passes it through;
`DiscordStaffNotifier.content` picks a fixed lead by kind:

- `ORDER_PAID` - `Paid order waiting for a stamp: **<ref>**` (unchanged)
- `DUPLICATE_PAYMENT` - `Possible duplicate payment - check before refunding: **<ref>**`

The wording asks for a check because the system infers a double charge; it cannot know that a
staff member's hand-typed reference (a UTR, or none) and a later gateway payment are one payment.

No amount, no gateway id, and no ordinal: two duplicate-payment alerts for one agreement read the
same, and delivery is at-least-once, so the message is a prompt and the database is the truth.
The runbook (task 7.1) gives staff the query that lists an agreement's payment orders by tracking
reference - status, surplus mark, amount, confirmation time, receipt - and the rule that a refund
is issued only after two captured payments are seen in the gateway dashboard, only for the order
marked surplus, and only through the gateway to the original payment method.

## Risks / Trade-offs

- **[Behaviour change in webhook confirmation code]** → one branch, decided under the agreement
  lock; every existing confirmation test must stay green, and each new case has a test.
- **[A surplus payment that staff never see]** (channel unset, down past the retry budget, or the
  sweep down for 24 hours) → the `surplus` mark is durable; the runbook carries a query for all
  surplus orders; the failure `ERROR` names the alert kind.
- **[A false "possible duplicate"]** (staff confirmed a gateway payment by hand under some other
  reference) → the wording and the runbook require two captured payments before any refund.
- **[Unique index on payment ids rejects a legitimate confirmation]** → one gateway payment
  belongs to one order; a violation is refused exactly as a cross-agreement duplicate is today.
- **[Orders paid before V28 are never marked surplus]** → production is beta, founding team only.
- **[Fulfilment readers take the newest order]** → pre-existing, out of scope, on the register;
  the runbook tells staff to check which order was credited before buying the stamp.
- **[Re-keying a live table]** → V28 runs in one transaction on a table with at most a handful of
  rows; the app is one deployable and migrates on startup.

## Migration Plan

1. `V28__surplus_payment_alert.sql`: `payment_order.surplus`; the unique partial index on
   `provider_payment_id`; `staff_alert.id` and `kind` added and backfilled, default dropped,
   primary key `staff_alert_pkey` moved to `id`, the CHECKs, and an index on `agreement_id`.
2. Deploy as usual; Flyway applies V28 on startup. No env var, no config change.
3. Rollback: the previous build does not populate `staff_alert.id`, so its alert inserts would fail
   on the new `NOT NULL` key (payments themselves are unaffected). A rollback therefore needs a
   forward `V29` restoring the old shape. Acceptable for a beta with no surplus rows.

## Open Questions

None.
