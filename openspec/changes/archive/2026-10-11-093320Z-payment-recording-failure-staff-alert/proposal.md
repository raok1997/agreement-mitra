## Why

A payment the gateway captured but the system did not record is told to no one. The customer
has paid, the payment is not recorded against the order, an outstanding order can block a fresh
checkout, and the only evidence is a `payment_event` row and a log line. Staff find out when the customer
complains or when the gateway emails that the webhook was disabled.

The payment event ledger (`payment-event-ledger`, shipped) now holds a durable row for each of
these attempts, so the existing staff alert sweep has something to read. This is the second of
the three changes split out of the `payment-recording-failure-staff-alert` register row.

## What Changes

- The staff alert sweep gains a third look-back, of the payment event ledger. It raises **one
  alert per payment order and kind** when a confirmation attempt for that order ended in one of
  three ways and the order is still not recorded as paid:

  | Ledger outcome | New alert kind | What it tells staff |
  |---|---|---|
  | `RECORDING_FAILED` | `PAYMENT_NOT_RECORDED` | The database refused to record a captured payment |
  | `DUPLICATE_REFERENCE` | `PAYMENT_ID_REUSED` | The payment's gateway reference is already recorded elsewhere, so it was refused |
  | `AMOUNT_MISMATCH` | `PAYMENT_AMOUNT_MISMATCH` | The amount or currency paid differs from the order, so it was refused |

- The alerts go out on the existing channel through the existing claim, lease, backoff and
  failure machinery. Nothing about delivery changes.
- **An order already recorded as paid raises no alert** (decided 2026-10-11). A failure that a
  later redelivery fixed before the sweep saw it says nothing.
- **A failure that recurs raises no second alert.** One alert per order and kind, ever. The
  ledger row's count keeps rising; the alert does not repeat.
- `staff_alert` is keyed on **the subject and the kind** (decided 2026-10-11). Today `id` alone
  is the key and `DUPLICATE_PAYMENT` already uses the payment order id, so an order that fails
  recording and is later confirmed as surplus could hold only one of its two alerts.
- The message is unchanged in shape: a fixed lead for the kind, the tracking reference, the
  state code and the site link. No amount, no gateway identifier, no agreement id.
- The requirement "Only a gateway-paid order raises an alert" is renamed and reworded: an
  unpaid payment order now raises an alert when the ledger holds one of the three outcomes for
  it, and still raises none otherwise.

**Not in this change:**

- The staff console panel "Payments needing attention" and its actions (retry now, record by
  hand, open in the gateway). It stays in the register. Until it exists, the alert tells staff
  there is a problem; the fix is still manual.
- A link from the alert to that panel. The alert links to the public site, as the other kinds do.
- An alert for `ERRORED` (decided 2026-10-11). It covers any exception, including a brief
  database outage that the next redelivery clears, so it would alert on failures that fix
  themselves.
- An alert for `MISSING_REFERENCE` or `UNKNOWN_ORDER`. The first waits for the sibling event;
  the second has no order of ours to alert about.
- Any change to the payment confirmation, the webhook, the reconciliation job, or what the
  ledger records.
- Re-sending an alert when the failure recurs after staff handled it, and re-raising an alert
  that failed to deliver. A failed alert of these kinds is re-queued by hand from the runbook,
  which also gets a query listing every stuck order without reading `staff_alert`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `staff-order-alert`: a new requirement for the unrecorded-payment alerts and one for the
  alert key; "Only a gateway-paid order raises an alert" is renamed to "Only a gateway payment
  raises an alert" and reworded to admit them. No other active change holds a delta for this
  capability.

`payment-event-ledger` and `payment-processing` are unchanged: the ledger gains a reader, not a
requirement.

## Impact

**Code (backend only, `signing` module, no SPA diff):**

- `db/migration/V30__unrecorded_payment_alert.sql`: `staff_alert` primary key becomes
  `(id, kind)`, the `kind` check gains three values, and `payment_event` gains an index on
  `last_received_at` for the look-back.
- `signing/PaymentOrderQuery.java` and a new public record `signing/UnrecordedPayment.java`:
  one more read on the existing seam.
- `signing/payment/PaymentOrderRepository.java`, `PaymentOrderQueryAdapter.java`: the read.
- `signing/staffalert/`: `StaffAlertKind` (three values), a new `StaffAlertKey`, `StaffAlert`,
  `StaffAlertRepository`, `StaffAlertPersistence`, `StaffAlertDispatcher` (keyed on the pair,
  third look-back), `DiscordStaffNotifier` (three leads).
- `signing/payment/PaymentEvent.java`: one comment line ("nothing here reads it").
- `docs/ARCHITECTURE.md` (the ledger has a reader), `docs/DEPLOYMENT.md` (the three leads and
  what to do for each), `docs/ROADMAP.md` (the register row is trimmed to the console panel).

**Signing-status FSM:** untouched. No `SignatureStatus` transition is read or written. Payment
order status is read, never written. The agreement's payment state is not read at all.

**Async webhook flow:** unchanged, so no sequence diagram. The sweep reads the ledger on its own
schedule; nothing is added to the webhook, the browser callback or the reconciliation job.

**PII / security review:**

- **Aadhaar / OTP / VID / KYC:** none read, written, logged or transmitted.
- **Outbound flow:** the existing one, to the staff channel. The message gains three fixed
  leads and no new field. It still carries no agreement id, amount, gateway order or payment
  id, party name or user-entered text.
- **What a lead reveals:** that a tracking reference has a payment problem of one of three
  kinds. The channel is staff-only and already receives tracking references.
- **Untrusted input:** none new. The sweep reads only ledger rows, which are written after the
  webhook signature verified or after an authoritative gateway read. A payer replaying the
  browser callback can refresh a row's last-received time; that cannot raise a second alert,
  because the alert row itself is the only comparison.
- **Logging:** one new line, an ERROR naming the look-back and the exception class when the
  ledger look-back fails. It carries no identifier. Existing alert lines name the kind and a
  redacted agreement id.
- **Secrets:** none introduced or moved. Sandbox and dummy data only is preserved.
