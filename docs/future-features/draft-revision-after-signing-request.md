# Draft revision after a signing request

**Status:** Deferred (future feature)
**Origin:** CR-5 `draft-ingestion` — v1 finalizes the draft the moment a signing request
is created; this feature covers the flow that lifts that restriction.
**Index:** [future-features/index.md](index.md)

## Problem

In v1 (`draft-ingestion`), a user can upload and re-upload their rental-agreement draft
freely **until** a signing request is created. From that point the draft is **finalized** —
any further upload is rejected `409 Conflict` (`draft-frozen`), regardless of the signing
request's outcome, including terminal `FAILED`/`EXPIRED`.

That hard lock is deliberate, but it leaves two legitimate needs unmet:

1. **Failed-integration retry.** If a signing attempt fails for technical/integration
   reasons (provider error, expiry, missed webhook), we — as the eSign provider — should
   be able to make it succeed without the purchaser re-doing anything. In v1 this is the
   responsibility of the **existing signing request** (the CR-4 reconciliation job /
   provider retry on the same document), *not* a draft re-upload. No new feature needed for
   this case; it's called out here only to distinguish it from (2).
2. **Purchaser-initiated revision.** If the purchaser genuinely wants to change the
   document after signing was requested (they spotted an error, terms changed), they need a
   way to revise and re-sign. This is **not free**: eSign incurs real provider cost per
   request, so a revision means paying again and superseding the prior attempt.

## Why it's deferred

Allowing edits after a signing request is not a relaxed validation rule — it needs its own
**flow** with money and state attached:

- **Cost / payment.** Each eSign request costs the provider. A revision must be gated on a
  fresh payment (or a defined allowance/refund policy), so it can't be a silent overwrite.
- **Supersede semantics.** The prior signing request (and any partially-collected
  signatures) must be explicitly invalidated/superseded, with an audit trail — not
  orphaned. Signers who already signed the old draft must be re-invited to the new one.
- **Legal/audit integrity.** This is legal infra; the relationship between the old and new
  documents, and why the change happened, must be recorded.

None of that belongs in the v1 ingestion slice, whose job is just "get the real document
in." Folding it in would couple ingestion to payment and to the signing FSM's supersede
behavior — a much larger change.

## Sketch of the eventual flow (not a commitment)

- A distinct `POST /api/agreements/{id}/draft/revise` (or a versioned-draft model) that is
  allowed only after a payment authorization for a re-sign.
- Supersede the existing signing request: mark it `SUPERSEDED` (new terminal/branch state),
  stop reconciling it, and record the link to the new draft + request.
- Version the stored blob (e.g. `drafts/{agreementId}/v{n}.pdf`) instead of overwriting, so
  the superseded document is retained for audit.
- Re-invite all signers to the new request; never silently carry over old signatures.

## Dependencies / related

- **Payments** — there is no payment module yet; this feature can't land before one exists.
- **Signing FSM** — needs a supersede transition (touches the load-bearing state machine).
- **`signing-auth`** — ownership/authorization must exist so only the purchaser can revise.
- **Stamp composition (CR-6)** — a revised draft must be re-stamped before re-signing.

## Acceptance (when picked up)

Graduate this file into an OpenSpec change under `openspec/changes/`, then update the index
entry to point at it.
