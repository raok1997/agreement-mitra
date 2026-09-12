# Parked — 2026-09-06

Deliberately deprioritised, not abandoned. Read this before picking it up.

## Why parked

The proposal was drafted on the belief that a customer settling offline had no durable
record of their tracking reference. That was wrong, and the `## Why` section has been
corrected in place.

`DraftDeliveryService` emails every party the draft PDF once contacts are confirmed and
**before payment** (design D17). The subject line is
`Draft rental agreement for your review - <reference>` and the body repeats
`Reference: <reference>`. So every customer with a contactable address is already holding a
durable, searchable email carrying the key to the recovery path, and `/recover` is linked
from the landing page.

The offline customer's route back therefore exists end to end:

    draft email (has the reference) -> /recover -> enter reference -> recovery link emailed

That reduces this change from "offline customers are locked out" to "offline customers take
a four-step path where online customers take none". Real, worth fixing, not urgent.

Reinforcing the deprioritisation: no frontend calls `POST /api/staff/payments/{id}/confirm`
or `/waive` — the manual paths are curl-only today, so present volume is near zero.

## Caveat on the parking rationale -- observed 2026-09-06, do not ignore

The argument above rests on the draft email reliably reaching every party. **On the first real
local run it did not.** The log shows:

    WARN  Draft delivery failed for one recipient on EMAIL (j***@gmail.com)

Not a typo: the address was `jana.purchases@gmail.com`, correctly spelled, and the **same address
accepted the recovery email roughly thirty seconds later** on the same run. So it was a transient
SMTP failure to a valid, reachable address -- most likely provider rate-limiting two sends in quick
succession, or the PDF attachment being rejected (the draft carries one; the recovery message
deliberately does not). `DraftDeliveryService` redacts the exception message by design, so the log
cannot say which.

`DraftDeliveryService` is **best-effort by contract** -- it swallows every failure so a mail problem
cannot stand between a customer and paying. That is correct behaviour, and it is exactly why the
draft email is not a guarantee.

On that run it did not bite: the payment was **gateway**-confirmed, so `PaymentConfirmedEvent` fired
and the recovery link reached the party anyway. **Had it been staff-confirmed, that party would have
had nothing at all** -- no draft, no reference on screen, no recovery link. That is the compounding
case this change addresses, and it is now known to be reachable rather than theoretical.

This does not by itself unpark the change; the priority call stands on volume (the manual paths are
curl-only today). But the "they already have the reference in their inbox" argument is weaker than
it reads above, and anyone reweighing this should reweigh it knowing that.

**Separate, smaller gap noticed alongside:** a draft-delivery failure is invisible to the customer
and produces only a redacted WARN. No delivery-outcome record, no retry. That is its own candidate
CR and should not be bundled here.

## What is still true, and is the actual reason to keep this open

`openspec/specs/agreement-recovery/spec.md`, requirement "The recovery link is emailed at
payment confirmation", still promises unconditionally that the link goes out when the server
confirms payment, and calls that "the primary means by which a customer retains access". The
manual paths do not honour it. **The spec and the code disagree, and nothing in the spec says
so.**

That is a trap for a future author, who will read the guarantee and build on it.
`contacts-editable-until-payment` already reasons from this event (its own conclusion happens
to survive either way — verified, not assumed).

## Triggers to unpark

Any one of these should promote it:

1. **A staff console button for confirm/waive ships.** Agreed as a follow-up CR. The moment
   settling a payment becomes a one-click action rather than a curl command, volume on this
   path stops being negligible and the gap starts affecting real customers. Do this change
   first, or the button inherits the gap silently.
2. **Offline settlement becomes a routine flow** rather than an escape hatch.
3. **Draft delivery stops preceding payment**, or stops carrying the reference. The whole
   argument for parking rests on that email existing.

## Cheaper alternative if it stays parked

If this is still parked when someone next touches `agreement-recovery`, consider the
one-hour version instead: qualify the spec requirement to gateway-confirmed payments and
weaken the "primary means" clause. That defuses the trap without the code change. It is
strictly worse for offline customers than fixing it, but it is far better than leaving a
spec that lies.

## Known adjacent gap (not this change, do not bundle)

`CaptureForm.vue` (~line 723) computes `recoveryLinkSent` client-side from "some party has an
email address" rather than from a server report of an actual send. The confirmation screen can
therefore promise mail that failed or was never attempted — `RecoveryDeliveryService` fails
closed and returns 0 when no public base URL is configured. Pre-existing, gateway path only,
and it contradicts that file's own header comment ("WHAT IT PROMISES MUST BE TRUE").
