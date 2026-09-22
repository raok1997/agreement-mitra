## Why

`openspec/specs/agreement-recovery/spec.md` promises unconditionally that the recovery link
is emailed to every party the moment the server settles payment, and calls that "the primary
means by which a customer retains access." Only **gateway** payments actually do it.
`PaymentConfirmations.apply` publishes `PaymentConfirmedEvent` and `RecoveryOnPaymentListener`
sends the mail; the manual staff paths — `PaymentService.confirm` and `PaymentService.waive` —
call `AgreementService.recordPayment` / `waivePayment` directly and publish nothing. No email,
no log line, no error.

The event was hung on a Razorpay-specific class, one level *above* the genuinely
vendor-neutral convergence point.

`README.md` documents `POST /api/staff/payments/{id}/waive` as "the normal remedy when money
arrives out of band", so waive **is** this product's offline-payment path, not merely a
goodwill lever. An offline customer also never reaches `PaymentConfirmation.vue` —
`CaptureForm.vue` routes there only from the server-confirmed `PAID` branch — so they never
see the screen that puts the reference and the link in front of them.

**The customer is not stranded, and an earlier draft of this proposal wrongly said they were.**
`DraftDeliveryService` emails every party the draft PDF once contacts are confirmed and
**before payment begins** (design D17). That message carries the tracking reference in both
its subject (`Draft rental agreement for your review - <ref>`) and its body, so every
customer who confirmed a contactable address already holds a durable, searchable email
containing the key to the fallback path. `/recover` is reachable from the landing page. So an
offline customer's route back is: find the draft email → open `/recover` → enter the
reference → receive the link. Longer than it should be, but intact.

What remains is therefore a **specification defect with a UX cost**, not a lockout: the spec
states unconditionally that the unprompted link is "the primary means by which a customer
retains access", and on the manual paths that primary means does not exist. The trap is that
a future author reads the spec and builds on a guarantee the code does not honour — which has
already happened once (`contacts-editable-until-payment` reasons from this event, though its
own claim survives either way).

## What Changes

- Move the recovery-link event publish **down one level**, from the Razorpay-specific
  `PaymentConfirmations` to the vendor-neutral seam in `AgreementService`, so every path that
  settles an agreement triggers it: staff confirm, staff waive, and all three existing gateway
  producers (browser callback, webhook, reconciliation job).
- **BREAKING (internal only)**: `PaymentConfirmedEvent` is replaced by `PaymentSettledEvent`,
  declared in `signing.agreement` alongside its new publisher. The old event has exactly one
  listener and zero test references, so this is a clean replacement, not a migration. No
  published API, no persisted payload, and no cross-module contract changes — Modulith
  externalized events are not in play.
- Waivers send the link too. A waived customer never saw a payment screen and is the least
  likely of anyone to have kept the reference.
- `PaymentConfirmedEvent` is **not** simply published from `waivePayment`.
  `Agreement.waivePayment` deliberately invents no amount, currency or reference so "how much
  money came in" and "may this proceed" stay separately answerable; publishing a *confirmed*
  event for a waiver would collapse at the event layer the very distinction the state layer
  protects. One event that means *settled* covers both without lying about either.
- The publish is gated on an **observed transition out of `UNPAID`**, so a repeat
  `POST /confirm` correcting a mistyped reference still overwrites the reference but does not
  re-email. `Agreement.recordPayment` keeps its overwrite behaviour untouched — that overwrite
  is today the only way to fix a mistyped reference, and changing it is a separate conversation.
- `RecoveryMessages` is **not** touched. Its content is deliberately anonymous — no names,
  address, or rent — because the link is a bearer credential that may outlive the mailbox, and
  a test asserts their absence. The same message is simply sent on a new path.
- A README line in the payment-gate escape-hatches section, so staff running a `curl` are not
  surprised by outbound mail.

## Capabilities

### New Capabilities

None. This change makes an existing promise true rather than introducing a new one.

### Modified Capabilities

- `agreement-recovery`: the requirement "The recovery link is emailed at payment confirmation"
  is scoped to *confirmation*, which does not cover waivers, and says nothing about which
  paths must trigger it or how often. It becomes a requirement about **settlement** — `PAID`
  or `WAIVED`, by any path including manual staff action — with the once-per-settlement rule
  stated explicitly rather than left as an implementation detail the gate happens to encode.

## Impact

**Code (backend only — zero SPA diff):**

- `signing/agreement/AgreementService.java` — publish from `recordPayment` and `waivePayment`,
  gated on the prior state read before the mutator call. Signatures unchanged: both still
  return `PaymentStateResponse`, and `AgreementService` *is* the convergence point, so nothing
  needs returning to a caller.
- `signing/agreement/PaymentSettledEvent.java` — new record, carrying forward the design
  rationale currently recorded in `PaymentConfirmedEvent`'s javadoc (three producers, one
  publish point, after-commit deliberately).
- `signing/payment/PaymentConfirmedEvent.java` — deleted.
- `signing/payment/PaymentConfirmations.java` — publish removed; the injected
  `ApplicationEventPublisher` goes with it. Its "Published here and nowhere else" javadoc
  paragraph is rewritten rather than deleted: that reasoning is still true, one level down.
- `signing/recovery/RecoveryOnPaymentListener.java` — re-pointed at the new event.
- `README.md` — one line in the payment-gate escape hatches.

**Behaviour:** the only user-visible effect is that staff-settled agreements now receive the
recovery email they were already promised. After-commit semantics are preserved:
`AgreementService.recordPayment` is `@Transactional` and `PaymentConfirmations.apply` calls it
inside its own transaction, so `@TransactionalEventListener` still fires after the outer
commit — nothing outbound happens for a payment that could still roll back.

**Blast radius:** `PaymentConfirmedEvent` has exactly one listener (the recovery mailer); the
e-stamp queue reads `paymentState` directly rather than subscribing. The worst case from
getting the publish wrong is one extra recovery email — nothing financial, nothing outbound to
a vendor.

**Signing-status FSM:** untouched. `DRAFT → PDF_GENERATED → STAMPED → SIGN_REQUESTED → …` is
not read or written here. The *payment* state (`UNPAID`/`PAID`/`WAIVED`) is read for the
transition gate but its transitions are unchanged.

**Async signing/webhook flow:** unchanged. The Razorpay webhook keeps its existing HMAC
verification and its existing path into `PaymentConfirmations.apply`; only the line that
publishes moves. No sequence diagram is warranted — no new hop, no new ordering.

**PII / security review:**

- **New or moved PII flow: none.** No Aadhaar number, OTP, VID, or KYC datum is read, written,
  logged, or transmitted by this change. The event carries a `UUID` agreement id only.
- The one *outbound* effect is an email whose body is generated by the untouched
  `RecoveryMessages` — deliberately free of party names, property address, rent, and deposit.
  Recipients come only from contacts already on file; no destination is accepted from a caller.
- Delivery still fails closed: with no public base URL configured
  (`RecoveryDeliveryService.hasPublicBaseUrl()`), nothing is sent. The new paths inherit that
  posture rather than bypassing it.
- Logging: the listener's existing catch logs no identifier, and no new log line carries an
  email address, a payment reference, or an amount. Recipient addresses remain redacted in
  audit output.
- **Secrets:** none introduced or moved. **Sandbox + dummy data only is preserved** — no
  provider credential, no live endpoint, and no real contact data is involved.

**Known adjacent gaps — recorded, deliberately not fixed here:**

- `recoveryLinkSent` in `CaptureForm.vue` (~line 723) infers "we emailed you" client-side from
  a party merely having an email address, rather than from a server report of an actual send.
  Because `sendRecoveryLink` fails closed and returns 0 with no public base URL, the screen can
  promise mail that failed or was never attempted. Pre-existing and gateway-path only; a
  separate CR.
- A staff console button for confirm/waive. No frontend calls these endpoints today — the
  trigger is `curl`. Wanted, and already agreed as a separate CR.
- Whether `WAIVED` overloading "no money received" with "money arrived by bank transfer" is an
  accounting problem. It is, but not here.
- `contacts-editable-until-payment` (unarchived) names `PaymentConfirmedEvent` by symbol in its
  `proposal.md`. Its *claim* — "no recovery link has been issued yet while contacts are
  editable" — was verified and holds either way, since links are still issued only at
  settlement. Only the symbol name goes stale; that CR's artifacts are not edited from here.
