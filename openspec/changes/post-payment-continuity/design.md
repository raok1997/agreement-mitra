## Context

Anonymous drafting is deliberate: the roadmap commits to a no-login self-serve flow, and
`mobile-otp-auth` -- the change that would have added optional login with save-and-resume --
is parked. Payment then became `REQUIRED` before fulfilment. Those three decisions are
individually sound and jointly produce a trap: an anonymous customer can pay and be left with
a reference that only staff can act on.

The existing access model is a **bearer capability**: the agreement UUID is unguessable (122
bits), and `AgreementController.get` lets any caller holding it read an *unowned* agreement,
while a *claimed* agreement is readable only by its owner. The tracking reference is
explicitly not part of that model -- `TrackingReference` records that possession "authorises
nothing by itself", and it carries only about 40 bits.

Two consequences follow, and together they determine the whole design.

**First**, the capability the customer needs is already permanent. From the moment they draft,
the UUID sits in their browser history and address bar. Nothing in this change makes access
more durable than it already was; it only makes it *recoverable* after the tab is closed.

**Second**, the problem is therefore delivery, not authorisation: **get the UUID back to the
legitimate customer, without turning the 40-bit reference into a credential.**

### Sequence

```
Customer            SPA                 Backend                            Mail
   |                 |                     |                                |
   |    (at payment confirmation)          |                                |
   |                 |                     |-- payment confirmed            |
   |                 |                     |---- link containing UUID ----->|
   |<------------------------ email (kept by the customer) ---------------- |
   |                                                                        |
   |    (later -- lost the email)                                           |
   |--reference----->|                     |                                |
   |                 |--POST recovery----->|                                |
   |                 |                     |-- normalise + checksum         |
   |                 |                     |-- require PAID + unowned       |
   |                 |                     |-- require signer email         |
   |                 |                     |---- same link re-sent -------->|
   |                 |<--202 (uniform)-----|                                |
   |<--"check your email" (always the same) |                               |
   |                                                                        |
   |--opens link---->|-- loads the agreement via EXISTING endpoints         |
```

The link carries the agreement UUID. There is no token to redeem and no second credential to
manage: the customer opens the link and the SPA resumes through the endpoints that already
serve unowned agreements. Recovery adds a way back in, not a second way to do things.

## Goals / Non-Goals

**Goals**
- A customer who paid anonymously can regain access using only what they were given.
- The tracking reference remains non-authorising, exactly as documented today.
- The payment moment both tells the customer what to keep and emails it to them.
- A recovered agreement remains claimable into an account later -- and claiming is the
  revocation.

**Non-Goals**
- Not a login system. This is recovery of one agreement, not identity. `mobile-otp-auth` stays
  parked and is not revived here.
- No editing of terms or parties through recovery.
- No recovery for unpaid agreements.
- No SMS. Email only, because email infrastructure exists and SMS does not.
- No change to the signing FSM, the payment gate, or the webhook path.

## Decisions

### D1: The reference triggers an email; it never returns the agreement

The recovery endpoint accepts a reference and answers the same way regardless of outcome. It
never returns agreement data, never confirms the reference exists, and never reveals which
address was mailed. The credential is the emailed link; the reference is only a selector.

This is what preserves the `TrackingReference` invariant. An attacker enumerating references
gains nothing: every guess produces an identical response, and any mail produced goes to the
legitimate signer, not to them. The 40-bit entropy question is thereby moved off the security
path entirely rather than being mitigated.

Rejected: returning the agreement for a valid reference (the literal request). At about 40
bits with no second factor, that makes every paid agreement enumerable and leaks full signer
PII. Rejected: reference plus last-4 of a signer mobile. It needs no infrastructure, but it is
a secret the counterparty and anyone who has seen the agreement already knows, and signer
mobile is optional in the current DTO.

### D2: The emailed link carries the agreement UUID and is itself the capability

The link contains the agreement identifier. Opening it is not a redemption step that trades a
token for access -- it *is* access, resolved through the endpoints that already exist.

Consequences, all deliberate: no token table, no Flyway migration for tokens, no redemption
endpoint, no expiry sweep, and no "this link has expired" dead end. The SPA reads the
identifier from the link and proceeds exactly as it would for a customer who never closed
their tab.

Rejected: a short-lived single-use token redeemed for the UUID. It protects the *delivery* of
a capability that is permanent anyway (the UUID is already in browser history), so its benefit
is confined to old email in a later-compromised mailbox -- while its cost is a table, a
migration, an endpoint, and a failure mode where a slow-delivering email produces a dead link
and a customer with no way forward. Rejected: a scoped anonymous session authorising one
agreement, for the reasons in D3.

### D3: No new principal type, no new session

Access after opening the link is the ordinary anonymous access an unowned agreement already
permits. `SecurityConfig` gains no second principal type, no filter that must not collide with
`SessionAuthenticationFilter`, and no signing/identity boundary that `ModularityTests` would
have to police.

Revisit only if recovery is ever extended to *claimed* agreements, where the UUID alone is
deliberately insufficient.

### D4: The link is permanent, and claiming is the revocation

The link does not expire. It stops working when the agreement acquires an owner: once
`owner_identity_id` is set, `findByIdForReader` refuses anonymous callers, and every link
previously emailed becomes inert.

This gives the customer a real off-switch that is also a feature they want anyway. The
confirmation view and the recovery email SHALL both say so plainly -- "this link works until
you sign in and save this agreement to your account" -- so the revocation is discoverable
rather than an accident of the access model.

The accepted cost is stated rather than mitigated: a recovery email is a durable key. Forwarded
mail, a shared inbox, or a mailbox compromised years later all still open the agreement. The
counterweight is that the same is already true of the URL in the customer's browser history,
and that the alternative traded that narrow exposure for a materially worse failure mode (D2).

### D5: The link is emailed at payment confirmation, not only on request

On confirmed payment the system emails the link to a signer address on file, unprompted. The
reference-entry page is the fallback for a customer who lost that mail, not the primary path.

This is what actually prevents the trap. A customer who never learns the recovery page exists
is still protected, because the link was in their inbox from the moment they paid.

### D6: Only PAID, only unowned

Recovery SHALL be offered only for agreements whose payment state is `PAID` or `WAIVED`, and
only while the agreement has no owning identity.

Payment is the evidence that the requester has a real stake. Unowned matters more: once an
agreement is claimed, anonymous access is deliberately closed, and recovery must not reopen
it. A claimed agreement is recovered by its owner signing in, which already works.

### D7: The email discloses the reference and the link, nothing else

The message SHALL NOT restate parties, property address, rent, or deposit. A misdirected mail
should not itself be a disclosure. This matters more under D4 than it would under a short-lived
token, because the mail persists. The deliberate cost is that the recipient gets less context
to recognise the mail by.

### D8: The recovery endpoint is rate limited and audited

Per-source and per-reference limits with lockout on repeated misses. Throttled responses SHALL
NOT differ in shape from unthrottled ones, or the limiter becomes the oracle D1 exists to
prevent.

Every recovery request records an audit row -- reference, outcome, timestamp, redacted
recipient -- so abuse is visible and a support conversation has evidence. This is the only
storage this change adds.

Rate limiting is defence in depth, not the control. D1 is the control.

### D9: Keep the identifier out of referrer headers and analytics

Because the identifier now travels in a URL that is emailed and clicked, it can leak through
`Referer` on any outbound navigation. The recovery landing route SHALL send a
`Referrer-Policy` that prevents cross-origin referrer disclosure, and the identifier SHALL NOT
be sent to any analytics or error-reporting sink.

This risk exists today for the drafting URL; the change makes it materially more likely by
putting the URL in email, so it is handled here rather than assumed.

### D10: Fail closed when email cannot be delivered

`mail.provider` defaults to `stub`. With a stub provider or no signer email on file, no mail is
sent. The caller-facing response is unchanged (D1); the operator-facing signal is a log line
plus an audit row.

Under D13 this is a backstop rather than a routine path: a newly paid agreement always has a
contactable signer. It still applies to agreements paid before D13 was enforced, and to any
environment where mail is not configured.

### D11: Confirmation is a view, not a banner

On server-confirmed payment the capture flow navigates to a confirmation view rendering the
tracking reference prominently, the confirmed amount and currency **as returned by the
server**, and the next step. It states that the link has been emailed, and that signing in will
end anonymous link access.

Reusing the server-confirmed values matters: the UI already refuses to claim success from the
browser handler alone, and the confirmation screen must not become the place that regresses
that.

### D12: Recovery does not claim, and does not block claiming

A recovered agreement stays unowned. If the customer signs in during or after recovery, the
existing claim path binds it to their identity with no special case -- and, per D4, thereby
retires every emailed link. Recovery and ownership stay orthogonal in mechanism while being
complementary in effect.

### D13: Contact confirmation is its own step between finalise and checkout

A dedicated step SHALL sit between finalising the agreement and starting payment. It presents
every party, shows the contact details held for each, lets the customer complete or correct
them, and states plainly what each detail is for: *this is how that party receives the
agreement*.

Placing it here is a deliberate choice among four. Enforcing during drafting puts a data wall
in front of a visitor who has not decided to buy, against the roadmap's no-login self-serve
commitment. Enforcing only at the checkout button turns a missing field into a refusal at the
worst possible moment, with the customer's intent already formed. Enforcing at eSign initiation
-- where the only gate lives today -- is far too late: money has already moved. A named step
asks for the data at the moment its purpose is obvious and correction is cheap.

**The step is UX, not the control.** A client-side step enforces nothing. The server SHALL
still refuse to create an order for an agreement whose parties are not reachable, and that
refusal is the actual gate. The step exists so that customers satisfy the gate before they
reach it, not instead of it.

This resolves what was Q1, and makes D10 a backstop rather than a live dead end: once enforced,
a `PAID` agreement with an unreachable party can only be a pre-existing row.

### D14: Delivery channels are modelled; only email is enabled

Contactability SHALL be expressed as **reachable on at least one enabled delivery channel**,
not as "has an email address". Channels -- email, SMS, WhatsApp -- are a modelled concept with
per-channel enablement in configuration, and each is reached through a common interface, in the
same shape as `EsignProvider`: the vendor specifics of a channel live behind the interface so
adding one is an adapter, not a redesign.

**Only email is enabled.** There is no SMS or WhatsApp provider integrated, and
`mobile-otp-auth` is parked. SMS and WhatsApp are declared as channels and are disabled. In
practice, today, "reachable on an enabled channel" means "has a valid email address" -- but the
rule is written once and does not need revisiting when a channel is switched on.

Mobile numbers are still collected at the confirmation step, because asking again later is
worse than asking now and the eSign provider is already given a phone channel when one is
present. The system SHALL NOT imply that an SMS or WhatsApp message will be sent while those
channels are disabled: the step SHALL describe mobile as used for signing notifications and for
future delivery, not as a delivery route available today.

**One definition of contactable.** The existing gate at eSign initiation
(`SigningRequestService.requireContacts`) uses a different rule -- email **or** mobile -- and
that rule is wrong for its own purpose: signed-document delivery is email-only, so a party with
only a mobile passes the gate, signs, and can never be sent the finished document. That check
SHALL be reconciled to the channel rule so there is exactly one definition, evaluated in one
place, and the eSign gate becomes defence in depth behind the payment gate rather than a second
divergent rule.

Rejected: requiring both email and mobile unconditionally. It would collect mobile numbers for
every party ahead of any capability to use them, and it hard-codes today's channel mix into a
validation rule that a WhatsApp-first customer base would immediately outgrow.

Rejected: requiring only one contactable party per agreement. One address is enough to deliver
a recovery link, but not to satisfy the purpose -- a tenant who never receives the agreement
they signed is exactly the failure this prevents.

### D15: Every party gets the link, and any party may complete fulfilment

The recovery link SHALL be sent to every party on the agreement, on their contact for an
enabled channel -- not only to whoever paid. Any party holding it may restore access and drive
the remaining fulfilment steps to completion.

The business position is explicit: the transaction is complete once payment is received, and
which party pushes the agreement over the line is not something the system needs an opinion
about. Encoding a payer-only rule would mean tracking who paid as an authorisation input,
inventing a distinction the parties themselves do not make, and stranding an agreement whenever
that one person becomes unavailable -- which is the same trap this change exists to remove,
merely narrowed to one person instead of everyone.

**This does not weaken signing.** Continuing fulfilment is not the same as executing the
agreement. Each signature is still an individual Aadhaar-authenticated act by that signer; no
party can sign for another by holding a link. What a party gains is the ability to move the
process forward, not to bind anyone.

**Terms remain immutable through this path** (D2 and the recovery spec). A party who opens a
link cannot alter what was agreed -- only proceed with it.

**Interaction with D4, stated rather than hidden.** Because claiming retires every link, one
party claiming the agreement into their account ends link access for the others. This is
accepted: link access exists to drive fulfilment, not to deliver the outcome. The signed
agreement is delivered to every party independently through `SignedDocumentDeliveryService`,
which emails each invitee at their signing-verified address regardless of who holds a link. A
party locked out of the link still receives what they are entitled to receive.

Rejected: recovery links that survive claiming. It would keep every party's access alive, but
it removes the only off-switch a permanent link has (D4) and contradicts the existing rule that
a claimed agreement refuses anonymous callers. Rejected: forbidding claiming from a recovered
session. It protects the other parties' links at the cost of making the account feature
unreachable for the customer most likely to want it.

### D16: Contacts are saved through a narrow anonymous endpoint

Party contacts are persisted by a dedicated route that accepts **contacts and nothing else**, for an
agreement that is **not yet owned**. It does not accept rent, dates, addresses, the party list, or
any other agreement field, and it refuses once the agreement has an owner or a signing request.

The general edit route (`PUT /api/agreements/{id}`) is authenticated, and must stay that way. But
the customer this change exists for is anonymous by construction, so the contact step had nowhere
to write. Widening the edit route to anonymous callers would hand a bearer-capability holder the
ability to rewrite rent and parties; a route that can only set contacts cannot.

This is consistent rather than novel: draft upload, finalise, and the whole payment surface are
already `permitAll` on exact sub-paths, scoped by the unguessable identifier and owner-checked in
the handler. This route joins them on the same terms.

**These contacts are the durable record.** They are what later notifies each party of payment, what
addresses the signing invitations, and what the signed agreement is delivered to. They are stored
against the agreement, not held in a browser.

### D17: The draft is emailed to both parties at the contact step

Once contacts are confirmed and before payment begins, the current draft agreement is emailed to
every party. Each party sees what they are about to be asked to sign, at the moment their address is
first known to be good -- which also proves the address works before anything depends on it.

**This is a different message from the recovery link, deliberately.** The recovery email discloses
nothing but a reference and a link (D7), because it is a permanent credential sitting in a mailbox.
This one carries the agreement itself, because its entire purpose is that the parties read it. The
two must not be merged, and the recovery message must not acquire an attachment.

**It does not weaken the payment gate.** The draft is already downloadable before payment from the
capture screen, so emailing it discloses nothing a customer could not already take. What the gate
protects is fulfilment -- the e-stamp and the signatures -- and none of that moves here.

Sending failure SHALL NOT block payment. A party who does not receive the draft still receives the
agreement through the signing invitation and the signed-document delivery that follow.

## Risks / Trade-offs

- **One party can lock the others out by claiming.** D15 accepts this because the signed
  document is delivered independently, but the party who tries a retired link gets a dead end
  unless task 8.4's message explains it. Q1b asks whether that is enough.
- **The permanent key now exists in as many mailboxes as there are parties.** D15 multiplies
  the exposure surface of D4 by the party count. Bounded by the same three things: the message
  discloses nothing on its own, claiming revokes, and terms cannot be altered through a link.
- **A recovery email is a permanent key.** Forwarding, shared inboxes, and later mailbox
  compromise all grant agreement access. Accepted under D4; the counterweights are that the
  URL is already permanent in browser history, that claiming revokes, and that the email
  discloses nothing by itself (D7).
- **Only claiming revokes.** A customer who does not want an account has no way to invalidate
  a leaked link. Q4 asks whether to offer explicit invalidation; doing so would reintroduce
  per-link state and much of what D2 removed.
- **Identifier leakage through URLs.** Email clients, proxies, and referrer headers all see it.
  D9 handles referrers; the rest is inherent to link-based delivery.
- **Email is on the critical path.** Deliverability problems become "I never got the link".
  Mitigation: the reference is still shown at payment time (D11), and staff lookup by reference
  exists as the human fallback.
- **Agreements paid before D13 may have no contactable party.** The gate binds at order
  creation, so rows paid earlier can still hit the D10 dead end and need the staff fallback.
  Deliberately not backfilled: there is no contact information to invent.
- **D13 adds a step to the flow.** One more screen between finalise and payment, on the path of
  every customer including those whose details are already complete. Mitigation: when nothing
  is missing the step is a confirmation, not a form -- it should read as a summary the customer
  agrees to, not as work.
- **Tightening the eSign gate (D14) may reject agreements that pass today.** A party with only
  a mobile currently clears `requireContacts` and would not clear the channel rule. That is the
  point -- they cannot be delivered the signed document -- but it is a behaviour change for any
  in-flight agreement, and existing tests asserting the OR rule will need revisiting.
- **Mobile numbers are collected ahead of their use.** No SMS or WhatsApp channel is enabled,
  so the data outruns its purpose until one is. Bounded by not making mobile mandatory (D14)
  and by not implying delivery that cannot happen.
- **A modelled channel with no adapter invites premature abstraction.** Declaring SMS and
  WhatsApp while implementing neither risks an interface shaped by guesses. Mitigation: build
  the interface to what email actually needs, and let the first real second channel reshape it.
- **Enumeration is not eliminated, only made pointless.** An attacker still learns nothing, but
  can still cause mail to be sent to real signers. Rate limiting (D8) bounds the nuisance.

## Migration Plan

1. Flyway migration adding the recovery audit table only. No token storage, no change to
   `agreement`, nothing to backfill.
2. Backend: recovery endpoint, recipient resolution, mail template, link construction. Inert
   wherever `mail.provider` is `stub`, so it can ship dark.
3. Frontend: confirmation view, then the recovery request page and the link landing route.
4. Configure a real mail provider in the target environment to activate it.

Reversible: dropping the route disables the feature; the audit table is additive and orphaned
if abandoned.

## Open Questions

- **Q1:** *(Resolved by D13 -- contact confirmation is a step before checkout, enforced at
  order creation.)*
- **Q1a:** *(Resolved by D15 -- every party receives the link and any party may complete
  fulfilment.)*
- **Q1b:** Should one party claiming the agreement notify the others that their link has been
  retired? D15 accepts the lockout because delivery is independent, but a silent loss of access
  is a poor experience for a party who tries the link later. A notice on the claimed-agreement
  landing message (task 8.4) may be sufficient.
- **Q2:** Should the confirmation view also offer a direct PDF download, or does that encourage
  treating the unstamped draft as the finished document?
- **Q3:** Should recovery be rate limited per email address as well as per source and reference,
  to bound how much mail one signer can be made to receive?
- **Q4:** Should a customer be able to invalidate a leaked link without creating an account? Any
  answer reintroduces per-link state, which is precisely what D2 removed -- so the question is
  whether the leak scenario justifies that cost.
