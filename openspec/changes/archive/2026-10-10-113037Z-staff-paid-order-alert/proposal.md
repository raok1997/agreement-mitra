## Why

Staff learn that a paid order is waiting for a stamp only by opening the staff console and
reloading it — the console does not refresh itself and nothing is pushed anywhere. With launch
close and one staff member working the queue, a paid order can sit unnoticed for hours while the
customer, who has nothing left to do, waits.

## What Changes

- A new scheduled job finds payment orders the gateway has marked paid and sends **one alert per
  agreement** to a staff Discord channel through a Discord incoming webhook.
- The job derives alerts from the `payment_order` table. **The payment confirmation code is not
  changed**: no listener, no extra write in its transaction, no outbound call on the Razorpay
  webhook, browser-callback or payment reconciliation threads.
- Each alert's delivery state is kept in a new `staff_alert` table. Failed sends are retried with
  bounded attempts and backoff; an alert that exhausts its attempts, meets a permanent refusal or
  goes stale is marked failed and logged.
- The alert carries the tracking reference, the template state and a link to the site. It carries
  no party name, contact detail, address, amount or agreement identifier.
- The sender sits behind a small `StaffNotifier` seam so a WhatsApp Cloud API adapter is a later
  one-adapter addition.
- A blank webhook URL disables the feature entirely: nothing is recorded and nothing is sent.

**Not in this change** (each is its own CR or stays as it is):

- **WhatsApp alerts.** Blocked on Meta setup: the business number is on the WhatsApp Business app,
  not registered on the Cloud API, and a business-initiated alert needs an approved utility
  template. Recorded in the follow-up register.
- **WhatsApp manual order intake.** A separate feature.
- **Alerts for "order placed" (finalise).** Decided: paid only. Unpaid orders are not actionable.
- **Alerts for staff confirm / waive.** These are the staff member's own actions. This keeps the
  parked `recovery-link-on-manual-settlement` change out of scope.
- **A second alert when one agreement is paid twice.** One alert per agreement is kept. That a
  double charge is invisible to staff is an existing gap; it is recorded in the follow-up register.
- **Narrowing the confirmation path's catch-all integrity-error mapping**
  (`PaymentOrderService.applyConfirmation`). Found by review, not needed by this design, recorded
  in the follow-up register.
- **Routing security events to the channel** (`security-event-alerting` register row). This change
  creates the channel that row was waiting for; the row is annotated, not folded in.
- **A privacy-policy change.** Product owner decision, 2026-10-10: the policy is not changed for
  this internal staff tool.

**Signing-status FSM:** no transition is added, removed or changed. The alert follows a payment
order reaching `PAID`, which is not part of `SignatureStatus`.

## Capabilities

### New Capabilities

- `staff-order-alert`: staff are alerted on an operations channel when a gateway payment order is
  paid — trigger, one-per-agreement recording, dispatch off the payment path, retry bounds, message
  content and the disabled state.

### Modified Capabilities

None. `payment-processing`, `payment-gate` and `estamp-intake` keep their requirements; the alert
only reads the outcome of the existing payment confirmation.

## Impact

- **Code (backend, `signing` module only):**
  - new package `signing.staffalert` (entity, repository, dispatcher, scheduled job, `StaffNotifier`
    seam, Discord adapter, properties);
  - one read-only method added to the existing module-root seam `PaymentOrderQuery` and its adapter
    in `signing.payment`.
  - `ModularityTests` is unaffected: everything stays inside the `signing` module.
- **Schema:** new Flyway migration `V27__staff_alert.sql`.
- **Config:** `spring.task.scheduling.pool.size` 4 → 5; new `staff-alert.*` block in
  `application.yml`; dispatch disabled in the test profile.
- **Env:** `STAFF_ALERT_DISCORD_WEBHOOK_URL` added and tagged in `deploy/env/backend.env.example`.
  A new key fails the deploy until `./provision.sh secrets` has run on the server, so provisioning
  comes before the first deploy.
- **Dependencies:** none added. Uses the existing `RestClient` and WireMock.
- **Frontend:** none.
- **Docs:** `docs/ARCHITECTURE.md`, `docs/DEPLOYMENT.md`, `README.md`, `docs/ROADMAP.md` follow-up
  register.

### PII / security review

- **Aadhaar / OTP / VID / KYC:** none. The change does not touch signer authentication data.
- **New outbound flow:** yes — to Discord, a third party. The payload is limited to the tracking
  reference, a two-letter state code and a link to the public site. No user-entered text is sent.
  A tracking reference alone grants no access to an agreement (`agreement-recovery`).
- **Never sent:** agreement UUID (a bearer credential), party names, phones, emails, addresses,
  rent, deposit, amounts.
- **New secret:** the Discord webhook URL. Anyone holding it can post to the channel. It comes
  from an env var only, is tagged `secret vendor`, and is never logged — not through an exception
  message, an exception cause, a URL-parsing error, a metrics tag or `toString`.
- **Logging:** failures log the exception class, the HTTP status and a redacted agreement fragment
  through `AgreementIds.redact`. Never the payload, the URL or the throwable.
- **Sandbox + dummy data only:** preserved. Tests use WireMock; local and test profiles leave the
  URL blank.
