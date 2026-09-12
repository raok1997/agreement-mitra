## Context

See `proposal.md` - Why. What shapes the approach:

- `SigningRequestService.fetchAndStoreArtifacts` already runs outside any transaction after the
  FSM reaches `SIGNED`, and is re-entered by both the webhook path and the reconciliation job.
  That re-entrancy is the central constraint on delivery: the natural trigger point is called
  more than once.
- `SIGNED`, `FAILED`, `EXPIRED`, and `STAMP_FAILED` are specified as **terminal** signing
  states, and the `Agreement` aggregate is deliberately status-less **with respect to signing**.
  `zoop-aadhaar-esign` already put **payment** state on the agreement, establishing that
  fulfilment-level state lives there.
- No outbound email exists. `docs/DOMAIN-AND-EMAIL-SETUP.md` provisions Zoho Mail Forever Free,
  a mailbox service rather than a transactional sender.
- The signed PDF now carries a prepended scanned SHCIL certificate page, so it is materially
  larger than a plain rendered agreement.

## Goals / Non-Goals

**Goals:**

- Both parties reliably receive their signed agreement, exactly once.
- A finished agreement is unambiguously finished, and dead work leaves the queue.
- Delivery problems never corrupt or contradict the signing record.

**Non-Goals:**

- Choosing the email provider; SMS/WhatsApp delivery; reminders.
- Emailing the audit trail.
- Retention, archival, or deletion policy.
- Reopening a closed agreement.

## Decisions

### D1: Closure is fulfilment state on the agreement, not a new signing state

Add `CLOSED` as agreement-level fulfilment state beside payment state. Leave the signing FSM
untouched.

*Why:* the signing FSM answers "what happened to the signatures", and its terminal states are a
legal record. Closure answers "is there work outstanding" - an operational question that
continues past signing and also applies to agreements that never signed at all. Bolting
`DELIVERED`/`CLOSED` onto the signing FSM would make `SIGNED` non-terminal, which the spec
relies on, and would leave no way to close a `STAMP_FAILED` agreement that has no signature to
speak of.

*On the status-less rule:* the existing constraint is specifically that the agreement carries no
**signing** status. This does not breach it - and payment state already established fulfilment
state at the agreement level. Worth being explicit, because a future reader will otherwise see
two status-shaped fields on a supposedly status-less aggregate.

### D2: Deliver only to signing-verified addresses

Resolve each recipient from the address at which that party actually completed signing, never
from the draft record.

*Why:* this is the single highest-consequence decision here. The signed PDF contains both
parties' names, the property address, the financial terms, and a stamp certificate naming both
parties. A typo in a draft-time email address would send all of it to a stranger, irreversibly.
An address that received a ZOOP invitation and at which someone then passed Aadhaar
authentication is *evidence* the address belongs to the right person - far stronger than
anything typed into a form.

*Consequence:* a party with no verified address gets no email and is escalated. Escalating is
strictly better than guessing.

### D3: Delivery is a separate lifecycle with per-recipient records

Per-recipient rows with status, attempts, and last error - not a boolean on the signing request.

*Why:* recipients fail independently. One party's mailbox bouncing must not block the other's
delivery, must not retry the successful one, and must be individually diagnosable and
individually re-sendable. A single flag cannot express "delivered to the owner, hard-bounced for
the tenant", which is exactly the state staff will need to act on.

### D4: Exactly-once is enforced at the recipient record, not by trusting the trigger

Claim each recipient's delivery in a guarded transition before sending, so re-entry finds it
already claimed.

*Why:* the trigger is re-entrant by design - webhook redelivery and the reconciliation job both
re-run completion. Any scheme relying on "we only call this once" is wrong on the first
redelivery. The failure mode is emailing a legal document twice, which looks like a system that
cannot be trusted with the document.

### D5: Delivery never blocks or rolls back completion

Store artifacts and record completion first; attempt delivery after, and let it fail
independently.

*Why:* a signature is a legal fact that has already occurred. If a mail provider is down at that
moment, refusing to record completion - or worse, rolling it back - would let an email outage
corrupt the legal record. Delivery is downstream of the truth, not part of it.

### D6: Attachment, with an in-app copy, and a size-ceiling fallback

Attach the signed PDF; also keep it authenticated-downloadable indefinitely. Above a configured
size, send a notification pointing at the in-app copy instead.

*Why:* attachments are what people expect and remain useful years later, when a link would have
expired - and a tenancy agreement is exactly the document someone digs out long afterwards. The
in-app copy covers the cases attachments do not: oversize documents, bounced mail, and a party
who deleted the email.

*Why a ceiling:* the signed PDF now includes a scanned certificate page, and scans can be large.
Silently exceeding a provider's attachment limit produces a delivery that appears to succeed and
does not arrive.

### D7: One SMTP adapter behind the seam; Zoho Mail Free in dev, ZeptoMail in production

**Decided (2026-08-03):** development uses the existing free Zoho Mail account; production
upgrades to **Zoho ZeptoMail**. The seam stays vendor-neutral, but only one adapter is needed
now, because both speak SMTP:

| | Development | Production |
| --- | --- | --- |
| Host | `smtp.zoho.*` (mailbox) | `smtp.zeptomail.*` |
| Ports | 465 (SSL) / 587 (TLS) | 465 (SSL) / 587 (TLS) |
| Username | the mailbox address | `emailapikey` |
| Password | mailbox credential | ZeptoMail send token |

*Why this is a good outcome:* the dev-to-production upgrade is **host plus credentials**, not a
new adapter. Building one SMTP adapter now serves both, and the seam still earns its keep if we
ever leave Zoho.

*What does not carry over:* **bounce reporting.** Plain SMTP tells us the provider accepted the
message, not that it arrived. ZeptoMail offers **bounce webhooks**; the free mailbox does not.
So in development a hard bounce is invisible, and `SENT` means "handed to the provider". Wiring
ZeptoMail's bounce webhook closes that gap at productionisation, feeding the existing
permanent-failure path (D3). Deliberately not built now, since there is nothing in development to
receive from.

*Correction (2026-09-11, as shipped):* this decision originally called that webhook "a small
**additive** change ... rather than reshaping anything". **It is not additive.** The seam shipped
as `void send(EmailMessage)` and `signed_document_delivery` carries no provider message id, so
nothing links a bounce notification back to the recipient row it belongs to. Wiring it needs a
forward-only migration for the correlation key, a change to the `EmailSender` signature (and
therefore to both adapters and `markSent`), and a bounce payload contract that cannot be
established without a live ZeptoMail account - plausibly forcing ZeptoMail's HTTP API over SMTP
and reopening the one-adapter choice above. Carried as `zeptomail-bounce-webhook` in the
follow-up register rather than guessed at here.

### D7a: Attachment ceiling is derived from the provider's total-message limit

ZeptoMail caps a message at **15 MB total** - headers, body, inline content, and attachments
combined. MIME base64 inflates binary content by roughly a third, so the ceiling on the **raw
PDF** must be well under that; approximately **10 MB** leaves proper headroom.

*Why it matters here:* the signed PDF now carries a prepended scanned SHCIL certificate page,
and scans are the one part of the document with no natural size bound. Setting the ceiling from
the encoded-and-assembled message size rather than the raw file size is the difference between
the fallback (D6) firing correctly and a message being rejected by the provider after we have
recorded it as sent.

*Note:* this is also an argument for bounding scan size at intake in `manual-estamp-upload` -
the two ceilings should be chosen together.

### D8: Party-facing retrieval is application-mediated, not a presigned URL

Stream from the private bucket after an authorization check.

*Why:* keeps a single authorization point and preserves the existing private-bucket posture. A
presigned URL is a bearer capability that outlives the check that issued it and can be forwarded;
for a document of this sensitivity, an authenticated request each time is the simpler and safer
default. Revisit only if streaming becomes a measured bottleneck.

## Risks / Trade-offs

- **The document is emailed to the wrong person.** Irreversible disclosure of both parties'
  identity and property details. -> D2 restricts delivery to signing-verified addresses; no
  draft-time fallback exists.
- **The document is emailed twice.** -> D4 claims the recipient record before sending.
- **Development send limits and deliverability.** The free Zoho Mail account has low outbound
  caps and is a mailbox rather than a transactional sender. -> Accepted for development, where
  volume is a handful of messages to our own addresses. It must not carry real user traffic;
  production moves to ZeptoMail (D7).
- **ZeptoMail account review is a lead-time item, not a switch.** New accounts are reviewed for
  fit with its transactional-only policy via a Customer Validation form, taking **2-3 business
  days**. -> Start the review *before* it is needed. Discovering this on launch day would stall
  production sending, and this is the kind of dependency that is invisible until it blocks.
- **Bounces are invisible in development.** Plain SMTP reports acceptance, not arrival, so a
  hard bounce looks like success and the permanent-failure path never fires. -> Accepted for
  development; ZeptoMail bounce webhooks close it in production (D7). Until then treat delivery
  status as best-effort and rely on the durable in-app copy.
- **Attachment size.** -> Ceiling derived from ZeptoMail's 15 MB total-message limit with
  base64 headroom (D7a), plus the explicit fallback (D6).
- **Closure hides problems.** Auto-closing on delivery could mask a document that was delivered
  but wrong. -> Closure records reason and time, artifacts remain retrievable indefinitely, and
  closure is not deletion.
- **Two status-shaped fields on a "status-less" aggregate.** -> D1 states the distinction
  explicitly so it reads as a decision rather than drift.
- **Abandoned closure could hide genuine failures** that someone should have fixed. -> Abandoned
  stays distinguishable from completed everywhere, so it is a filter, not an eraser.

## Migration Plan

1. One forward-only Flyway migration: per-recipient delivery records; agreement closure state
   with closed-at and reason. Nullable/defaulted so existing rows validate.
2. Ship with the **stub** email seam active by default; a real provider is configuration.
3. Backfill: existing `SIGNED` agreements are dev data and need no delivery; leave them open
   rather than emitting a burst of emails on deploy. **Confirm this before deploying anywhere
   with real signed agreements** - an accidental mass send is the obvious hazard here.

   *Confirmed as shipped (2026-09-11), and pinned as tests rather than left as a promise.* Nothing
   re-enters delivery for such an agreement: the reconciliation scan selects `SIGNED` rows only
   while `signed_pdf_key IS NULL`, and the delivery retry sweep selects **due rows**, of which a
   pre-existing agreement has none - record creation is reachable only *through* a row the sweep
   already found. Both are characterization tests
   (`SigningCompletionIntegrationTest.reconciliationDoesNotDeliverPreExistingSignedAgreementsThatAlreadyHoldTheirArtifacts`,
   `SignedDeliveryIntegrationTest.theRetrySweepNeverManufacturesDeliveriesForAnAgreementThatHasNoDeliveryRecords`),
   so widening either selection fails the build instead of mailing real parties.

**Rollback:** delivery and closure are additive and downstream of the legal record; disabling
delivery stops sends without affecting signing, storage, or stored artifacts.

**Sequencing:** depends on `zoop-aadhaar-esign` (completion path and payment-state precedent)
and therefore on `manual-estamp-upload`.

## Open Questions

- **Which Zoho data centre** (`.in` vs `.com`) for ZeptoMail? The domain setup notes the India
  DC for Zoho Mail; hosts and tokens differ per DC. A configuration value, but it must match the
  account or authentication fails confusingly.
- **Should a party be notified when delivery to the *other* party fails?** Arguably yes for
  transparency, arguably no as it exposes the other party's mail problems. A product call that
  adds a message, not a mechanism.
- **Retention** - how long artifacts are kept after closure. Deliberately untouched here;
  closure explicitly does not imply expiry.
