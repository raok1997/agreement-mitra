## Why

A customer who pays without signing in has **no way back to their agreement**. This was
observed live, not theorised: agreement `AM3G3VXSAKD` was drafted anonymously, paid in
Razorpay test mode (Rs. 499.00 captured), and landed with `payment_state = PAID` and
`owner_identity_id = NULL`. Money changed hands for an artifact the payer cannot reach.

The dead end is structural, not a bug:

- Reaching an agreement requires its **UUID**, which an anonymous customer holds only in the
  browser tab they are about to close.
- Claiming requires that same UUID (`POST /api/agreements/{id}/claim`).
- The only lookup by tracking reference (`AgreementService.findByTrackingReference`) returns a
  `StaffAgreementView` and is **STAFF-only**.
- So the sole recovery route today is a support request quoting the reference to staff.

Two things compound it. First, the moment of payment is an inline green banner inside the
capture form ("Payment received (INR 499.00). Nothing more to do.") -- the customer is never
told that anything is worth keeping, so they close the tab. Second, `mobile-otp-auth`, the
change that would have delivered login-based save-and-resume, is **PARKED** ("do not start"),
so there is no authenticated path either.

The result: the payment gate ships `REQUIRED`, fulfilment is blocked until PAID, and a paying
anonymous customer can strand themselves permanently between those two facts.

## What Changes

1. **A contact confirmation step before checkout, and a channel rule behind it.** A dedicated
   step between finalise and payment shows every party and the contact details held for each,
   and states what they are for. Behind it, the server refuses to create an order unless every
   party is reachable on an **enabled delivery channel**. Channels -- email, SMS, WhatsApp --
   become a modelled concept with per-channel enablement, in the same shape as `EsignProvider`;
   **only email is enabled**, so today the rule means "has a valid email". Drafting is
   unaffected, so anonymous self-serve is preserved.

   This also corrects an existing defect. The only contact gate today,
   `SigningRequestService.requireContacts`, requires email **or** mobile -- but signed-document
   delivery is email-only, so a party with just a mobile passes the gate, signs, and can never
   be sent the finished document. That check is reconciled to the channel rule so there is one
   definition of contactable.

2. **A recovery link, sent to every party at payment.** On server-confirmed payment the system
   sends a link carrying the agreement identifier to **each** party, at a contact already on
   file -- unprompted, and not only to whoever paid. This is the primary fix: a customer who
   closes the tab immediately is already covered, and so is the counterparty. Any party holding
   the link may drive the remaining fulfilment steps; signing itself remains an individual
   authenticated act, so no party can sign for another.

2. **A payment confirmation view.** The capture flow hands off to a dedicated screen showing
   the tracking reference prominently, the server-confirmed amount and currency, the next step,
   that a link has been emailed, and that signing in will end access by that link.

3. **A recovery request page.** A customer who lost the email enters their reference. The
   system does not return the agreement -- it re-sends the link to the address on file, and
   answers identically whatever the outcome.

4. **Continuation through the existing surface.** Opening the link reaches the agreement
   through the endpoints that already serve unowned agreements by identifier. The customer can
   view, download, and complete fulfilment; they cannot edit terms or parties.

5. **Claiming as revocation.** The link does not expire on a timer. It stops working when the
   agreement is claimed into an account, because anonymous access to a claimed agreement is
   already refused. This is disclosed to the customer rather than left implicit.

6. **Abuse controls.** Rate limiting and lockout on the request endpoint, uniform responses so
   it cannot be used to test whether a reference exists, and an audit record per request.

**Signing-status FSM:** this change adds **no new transition and alters none**. Recovery is an
access and navigation concern; a recovered customer drives the *existing* transitions through
the *existing* endpoints. `payment_state` is read, never written.

## Capabilities

### New Capabilities

- **agreement-recovery** -- restoring a paid, unowned agreement to the customer who paid for
  it, by emailing a link to an address on file, and the constraints on what that link permits.

### Modified Capabilities

- **payment-processing** -- adds the confirmation-moment requirements: what the customer is
  shown, and told, once payment is server-confirmed.

## Impact

**Backend**
- `signing` module: a delivery-channel abstraction with per-channel enablement (email enabled;
  SMS and WhatsApp declared and disabled), and a single reachability rule expressed against it.
- Order creation gains a precondition: every party reachable on an enabled channel.
- `SigningRequestService.requireContacts` reconciled to that same rule, replacing today's
  divergent email-or-mobile check. Existing tests asserting the OR rule will need revisiting.
- `signing` module: a recovery service (reference lookup, eligibility, recipient resolution,
  link construction, mail dispatch), a customer-facing endpoint, and an audit record. A
  reference lookup usable outside STAFF, kept distinct from the existing staff view so the
  staff projection is not widened.
- Payment confirmation gains a hook that emails the link on transition to `PAID`.
- `SecurityConfig`: one new anonymous route, scoped to an exact sub-path. **No new principal
  type and no new authentication filter** -- recovered access is the anonymous access an
  unowned agreement already permits.
- Reuses the existing `SmtpEmailSender` / `MailConfig`; adds one template.

**Frontend**
- New contact confirmation step between finalise and checkout: every party, their details, what
  each is for, and correction in place. A confirmation rather than a form when nothing is
  missing.
- New payment confirmation view and new recovery request page, plus a landing route for the
  link, wired into the path-based switch in `App.vue` (no vue-router).
- `CaptureForm.vue` hands off to the confirmation view instead of ending at the banner.
- A referrer policy on the landing route so the identifier is not disclosed cross-origin.

**Data**
- One Flyway migration: the recovery audit table. **No token storage** -- the link carries the
  identifier and there is nothing to redeem. No change to `agreement`.

**Config**
- Recovery depends on real outbound email. `mail.provider` defaults to `stub`, so the feature
  is inert until a provider is configured -- deliberately fail-closed, and stated as such.

**Docs**
- `README.md` gains the recovery flow and its env requirements.
- `docs/ARCHITECTURE.md` records that the tracking reference remains non-authorising.

## PII / security review

**Does this change introduce or move PII or secrets?** Yes -- and it deliberately preserves a
documented invariant that the obvious implementation would have broken.

`TrackingReference` states: *"Possessing a valid reference authorises nothing by itself."* The
reference carries roughly 40 bits of entropy (`AM` + 8 characters from a 31-character alphabet
+ a check character) against the 122-bit UUID that `AgreementController` relies on as an
unguessable bearer capability. Making the short reference an access credential would make
agreements enumerable and would expose full signer PII -- names, fathers' names, current
addresses, property address, rent and deposit.

**The invariant is kept.** The reference remains insufficient on its own: it only causes mail
to be sent to an address already on file. The credential is the emailed link. An attacker
enumerating references learns nothing and receives nothing, because any mail goes to the
legitimate signer.

- **Aadhaar / OTP / virtual IDs:** none. The eSign path is touched only to reconcile its
  contact check to the shared channel rule; no eSign payload, credential, or identity data is
  read, stored, or transmitted differently.
- **More contact PII, collected earlier.** Every party's details are now confirmed before
  payment rather than before signing. Mobile numbers are captured while no SMS or WhatsApp
  channel is enabled, so that data has no current use -- bounded by not making mobile mandatory
  and by not presenting disabled channels as delivery routes.
- **New outbound PII flow:** one -- email to a signer address already held for that agreement.
  The message SHALL carry the tracking reference, the link, and the revocation notice only, and
  SHALL NOT restate agreement contents, so a misdirected mail is not itself a disclosure.
- **A permanent credential now lives in email.** This is the change's principal accepted risk.
  Forwarded mail, shared inboxes, or a mailbox compromised later all grant access. It is
  bounded by three things: claiming the agreement revokes every link; the mail discloses
  nothing on its own; and the equivalent URL already persists in the customer's browser
  history, so the capability's lifetime is not newly created here, only newly delivered.
- **Identifier in URLs.** Mitigated by a referrer policy on the landing route and by excluding
  the identifier from analytics and error reporting.
- **Logging:** recipient addresses SHALL be redacted before logging, consistent with existing
  practice. Agreement identifiers SHALL NOT be written to any external telemetry sink.
- **Enumeration:** the request endpoint SHALL answer identically whether or not the reference
  exists, is paid, is claimed, or has a usable email on file -- including when throttled.
- **Sandbox and dummy data only** is preserved; this change adds no vendor integration and no
  production credential.
