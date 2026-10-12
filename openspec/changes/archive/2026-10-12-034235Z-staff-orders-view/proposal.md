## Why

Staff are alerted when a captured payment was not recorded, but the only way to see what happened
to a payment order is the SQL in the runbook. An order whose attempts only ever ended `ERRORED`
raises no alert at all, so nobody sees it. This is item 1 of the register row
`stuck-payment-order-actions`: a read-only list, built before any action, so the later actions are
decided from what the list shows.

## What Changes

- The staff console gains a second tab, **Orders**, beside the existing e-stamp queue. The queue
  stays the tab the console opens on.
- New endpoint `GET /api/staff/payment-orders`, STAFF only. It is the only backend addition.
- The tab lists payment orders in two groups. An order appears in at most one.
  - **Paid but stuck**, shown first, no date limit: an order that is `CREATED`, `EXPIRED` or
    `FAILED` and has a ledger
    attempt carrying a gateway payment id whose outcome is `RECORDING_FAILED` ("Not recorded"),
    `DUPLICATE_REFERENCE` ("Payment id reused"), `AMOUNT_MISMATCH` (both amounts shown) or
    `ERRORED` ("Error while recording").
  - **Not paid**, orders of those statuses created in the last 30 days that are not in the first
    group: `CREATED` is "Checkout open", `EXPIRED` is "Abandoned".
- Each order shows its tracking reference, payment order id, amount, status, when it was created,
  the agreement's payment state (unpaid, paid under this payment, paid under another payment,
  waived) and its ledger attempts, each with "seen N times".
- For "Payment id reused", the entry shows the tracking reference of the agreement that already
  holds that payment while one still does, so staff do not look it up in SQL; otherwise it says
  nothing holds it now.
- Each stuck entry states its next step in plain words, worked out from its kind and the
  agreement's payment state (the spec's next-step table: nothing to record, wait for a decision on
  a differing amount, where a reused id is recorded, do not record on a settled agreement, or
  confirm and record). The runbook stays the reference for the rest.
- Each group is capped and says so when it is cut short.
- The screen has no action. Beside the next step it names the runbook section. It states that an
  agreement staff confirmed or waived by hand has no payment order and is not listed.
- Docs: the three places that say no console screen lists these orders are corrected, and item 1
  leaves the register row.

Not in this change: `payment.failed` intake and a "Failed at gateway" label (the next change),
resolve/close (parked change `payment-order-close`), retry, record by hand, pointing the alert's
link at this view, an alert for `ERRORED`-only orders, a Paid group.

## Capabilities

### New Capabilities

- `staff-orders-view`: the staff-only read of payment orders in two groups - which orders are
  listed, what each entry carries and withholds, and how the console presents it.

### Modified Capabilities

None. The requirements of `payment-event-ledger`, `payment-processing`, `staff-order-alert` and
`estamp-intake` are unchanged. The parked change `payment-order-close` holds deltas on the first
three; a new capability keeps this change free of any archive order with it.

## Impact

- **Backend, `signing.payment`:** `PaymentOrderRepository` (read queries), `PaymentOrderQueryAdapter`
  (the stuck-outcome set, derived from `UNRECORDED_OUTCOMES`), `PaymentEventLedger` (a first read
  method), one new read service.
- **Backend, `signing.agreement` and the `signing` root:** one new batch read on `AgreementService`,
  returning a new public interface `AgreementPaymentStanding` (tracking reference, payment state,
  "paid under" check) for a set of agreements.
- **Backend, `signing.api` and root:** one controller, one response record, one `SecurityConfig`
  matcher.
- **Schema:** none. No migration, no new column, index or status.
- **Frontend:** `StaffConsole.vue` (tab switch), new `StaffOrders.vue`, new `src/api/staffOrders.ts`.
- **Parked change:** a dated "Since this was parked" note at the top of
  `openspec/changes/payment-order-close/design.md` (what now exists for it to reuse). Nothing else
  in that change is edited.
- **Docs:** `docs/ARCHITECTURE.md`, `docs/DEPLOYMENT.md`, `docs/ROADMAP.md`. No Terms or FAQ change:
  the screen is staff-only and changes no customer promise.
- **Dependencies:** none added.

### Signing-status FSM

Not touched. `SignatureStatus` and its transitions are unchanged, and so is `PaymentOrderStatus`.

### Payment and webhook path

Not touched. `RazorpayWebhookService`, `PaymentConfirmations`, `PaymentOrderService.applyConfirmation`,
the reconciliation job and the alert sweep are not edited, and the alert's outcome list
(`UNRECORDED_OUTCOMES`) keeps its three entries. No sequence diagram: no asynchronous flow changes.

### PII / security review

- **Aadhaar, OTP, VID, signer PII:** none. The response carries no party name, contact or address.
- **Secrets:** none added; no new environment variable.
- **New outbound flow:** none. The endpoint calls no gateway and sends nothing.
- **Identifiers in the response:** the tracking reference, the payment order id, on an attempt the
  gateway payment id, and for a reused payment id the tracking reference of the agreement holding
  it (another customer's order, visible to staff only, and not a credential). The gateway payment id is included deliberately: the runbook has staff
  record a payment by hand under that exact id, and without it the screen does not replace the SQL.
  The response never carries the agreement id (a bearer credential), the receipt (which is the
  agreement id), the gateway order id, or the agreement's stored payment reference, which never
  leaves `signing.agreement`.
- **Logs:** the endpoint logs no gateway id and no amount; the records holding the gateway payment
  id leave it out of `toString`.
- **Gateway-supplied text** (currency, gateway payment id) and every other response string is
  rendered through Vue text interpolation only; the view uses no `v-html`.
- **Authorization:** STAFF role, decided in the filter chain by an exact method-and-path matcher,
  before any lookup. The handler is a GET with no side effect.
- **Sandbox and dummy data only:** preserved.
