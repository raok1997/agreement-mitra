## Why

Once both parties sign, the pipeline stops. The signed PDF and audit trail are downloaded and
stored in object storage, and nothing else happens: **the parties never receive their
agreement**, and nothing marks the job finished. A signed rental agreement sitting only in our
bucket is not a delivered product, and a fulfilment queue with no terminal state never clears.

ZOOP does not close this gap. v5 exposes `complete_signed_url` for us to fetch; it does not
email the completed document to the signers. Delivery is ours to build.

This change adds the last two steps of the journey:

**... -> webhook -> SIGNED -> deliver to both parties -> agreement closed.**

## What Changes

- **New `signed-document-delivery` capability.** On completion, each party receives the signed
  agreement as an **email attachment**, and a durable authenticated copy remains available in
  the app. The audit trail is **retained by us**, not emailed - it carries eKYC-derived detail
  that does not belong in inboxes, and it is produced on request or in a dispute.
- **Delivery goes only to signing-verified addresses.** A party's address is used only if
  that party actually completed signing at it. Addresses captured at draft time and never
  exercised SHALL NOT receive the document. This is the safeguard against a draft-time typo
  emailing a complete identity-and-property document to a stranger.
- **Per-recipient delivery records with exactly-once semantics.** Webhook redelivery and the
  reconciliation job both re-enter the completion path; neither may re-send. Transient
  failures retry with backoff; permanent failures (hard bounce) are surfaced for staff.
- **Delivery failure never changes signing state.** The signing request stays `SIGNED`;
  delivery has its own lifecycle. A bounced mailbox does not un-sign an agreement.
- **New `agreement-closure` capability.** An agreement reaches a terminal **fulfilment** state
  when it is signed and delivered to every party. Terminal *failure* paths
  (`FAILED`/`EXPIRED`/`STAMP_FAILED`) also close, as abandoned - otherwise dead work
  accumulates in the staff queue forever.
- **Closed is terminal, but not an archive.** A closed agreement's documents remain
  retrievable indefinitely; closure means "no work outstanding", not "no longer available".
- **Authenticated party-facing artifact retrieval.** The signed PDF becomes downloadable by a
  party who owns the agreement. The bucket stays private and no public URL is ever issued.
- **New vendor-neutral outbound email seam**, with a single **SMTP adapter** serving free Zoho
  Mail in development and Zoho ZeptoMail in production (see Impact).

Deliberately **not** in scope:

- Choosing the email provider (see the risk below), SMS/WhatsApp delivery, or reminder emails.
- Emailing the audit trail.
- Archival, retention policy, or deletion.
- Re-opening a closed agreement; the paid supersede flow remains a separate future change.

## Capabilities

### New Capabilities

- `signed-document-delivery`: delivery of the completed agreement to each party - what is sent,
  to which addresses, exactly-once semantics, retry and failure handling, and the outbound
  email seam.
- `agreement-closure`: the terminal fulfilment state of an agreement - what closes it, what
  closure guarantees, and what remains available afterwards.

### Modified Capabilities

- `signed-artifact-storage`: adds authenticated, party-facing retrieval of the signed document
  while preserving the private-bucket and no-public-URL posture.

## Impact

**Code** - new delivery component (per-recipient records, retry, the email seam) triggered off
signing completion; closure evaluation; a party-facing artifact download endpoint; extension of
the completion path in `SigningRequestService`.

**Schema** - one forward-only migration: per-recipient delivery records (recipient, artifact,
status, attempts, last error, timestamps) and agreement closure state with a closed-at
timestamp and a reason. `ddl-auto: validate` stays green.

**API** - a party-facing signed-document download; delivery status visible to staff.

**Config** - email seam settings, the attachment size ceiling, and retry/backoff parameters.

**Dependencies** - an outbound email mechanism. None exists today. **Decided (2026-08-03):**
development uses the existing **free Zoho Mail** account; production upgrades to **Zoho
ZeptoMail**. Both speak SMTP, so **one SMTP adapter serves both** and the upgrade is host plus
credentials rather than a new integration.

Two consequences to plan for, not to solve now:

- **Bounce reporting does not exist in development.** Plain SMTP reports that the provider
  accepted a message, not that it arrived, so a hard bounce is invisible and the
  permanent-failure path never fires. ZeptoMail's bounce webhook closes this in production, feeding the
  permanent-failure path this proposal already defines. **Correction (2026-09-11, as shipped):
  it is not additive.** `EmailSender.send` returns nothing and the delivery record holds no
  provider message id, so there is no correlation key to join a bounce back to a recipient row;
  wiring the webhook needs a migration, a change to the seam signature, and a payload contract
  that cannot be verified without a live ZeptoMail account. Tracked in the follow-up register as
  `zeptomail-bounce-webhook`.
- **ZeptoMail requires an account review** (a Customer Validation form, typically 2-3 business
  days) because it enforces a transactional-only policy. It is not a same-day switch, so the
  review should be started before production sending is needed.

The free account is suitable for development volume - a few messages to our own addresses - and
must not carry real user traffic.

**FSM** - **no change to the signing FSM.** `SIGNED` stays terminal for signing. Delivery and
closure are separate lifecycles at the fulfilment level, alongside payment state.

## PII / security review

- **Does this change introduce or move Aadhaar/OTP/VID/PII or secrets?** **Yes - it moves PII
  outward, which makes it the highest-exposure change in the pipeline so far.** The signed
  agreement contains both parties' full names, the property address, financial terms, the
  signature block, and the prepended SHCIL certificate page naming both parties. This change
  emails that document out of our control boundary. No Aadhaar number, VID, or OTP is
  involved. The audit trail, which carries eKYC-derived detail (auth mode, timestamps,
  name-match data), is deliberately **not** emailed.
- **How is it redacted/secured?** Delivery is restricted to **addresses proven by the signing
  process** - an address that received a ZOOP invitation and at which the party completed
  Aadhaar authentication - so an unverified draft-time address can never receive the document.
  Recipient addresses are redacted in logs (local-part masked); attachment bytes are never
  logged; the object-storage bucket stays private and no public or long-lived URL is issued.
  Party-facing download requires authentication and ownership. Email provider credentials come
  from env vars only.
- **Sandbox + dummy data only preserved?** **Yes.** Tests use a stub email seam that captures
  messages in memory and sends nothing; no real mailbox, credential, or outbound message is
  required for any test to pass. Real delivery is configuration, never a default.
