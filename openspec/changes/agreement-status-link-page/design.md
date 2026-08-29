## Context

See `proposal.md` -- Why. The constraints that shape the approach:

- **The read side already exists and is already anonymous.** `GET /api/agreements/{id}`,
  `GET /api/agreements/{id}/payment`, `GET /api/signing/{id}/progress` and
  `GET /api/agreements/{id}/signed-document` are all `permitAll` in the chain with
  ownership scoping decided in the handler, precisely so a link holder can reach an
  unowned agreement. The status page needs no new read endpoint -- only a finer stage on
  one of them.
- **The freeze predicate is currently expressed twice and in the wrong terms.**
  `AgreementService.update` gates on `signingRequestQuery.existsForAgreement(id)`, and
  `AgreementDisplayStatus.editable()` returns `this == DRAFT`. Both encode "a signing
  request exists" and neither can see the difference between `PDF_GENERATED` and
  `STAMPED`, because `AgreementDisplayStatus` collapses them.
- **`PDF_GENERATED` is durable and long-lived** (per `signing-request`): a request rests
  there for hours or days while staff buy the e-stamp out of band. That window is exactly
  where the new edit right lives, and exactly where staff may already be spending money.
- **No router on the frontend.** `App.vue` resolves routes from `window.location.pathname`
  by hand (flow-journal 8.3). A new landing is a new branch in that switch, not a new
  dependency.
- **The signing FSM is untouched.** This change reads it at higher resolution and moves an
  external rule that keys off it.

## Goals / Non-Goals

**Goals:**

- One authoritative freeze predicate, computed from `SignatureStatus`, used by the edit
  gate and the `editable` flag alike, so the button the customer sees and the answer the
  server gives can never disagree.
- A status landing that renders from server-reported state only, with no client-side
  inference of progress.
- Compensating controls for the widened anonymous write surface that ship *with* it.
- A fulfilment-side signal so staff never buy a stamp against superseded terms.

**Non-Goals:**

- No change to the signing FSM's states or transitions.
- No new read endpoint, no aggregate read model; the page composes three existing reads.
- No `vue-router`, no new frontend dependency.
- No re-pricing or refund logic when terms change after payment. An edit that changes the
  rent can change the duty owed; reconciling that is staff work through the existing queue,
  not an automated flow in this change. Flagged in Risks.
- No change to how the recovery email is worded or when it is sent.

## Decisions

### D1: One freeze predicate, keyed on the stamp, in the signing module

Introduce a single predicate -- "the terms edit window is open" -- computed from the
most-recent signing request's `SignatureStatus`: open when there is **no** request or the
request is in `PDF_GENERATED`; closed for `STAMPED`, `SIGN_REQUESTED`, `SIGNED`, `EXPIRED`,
`FAILED`, `STAMP_FAILED`. `AgreementService.update` gates on it and the progress projection
reports it as `editable`.

*Why:* the bug this change is most likely to introduce is a status page that offers an edit
the server then refuses (or hides one it would allow). Deriving both from the same function
makes that class of bug unrepresentable.

*Alternative rejected:* keep `AgreementDisplayStatus.editable()` and add a second rule for
the new route. That is how the two-places drift starts, and `AgreementDisplayStatus` cannot
express the rule anyway -- it has already discarded the distinction the rule turns on. So
`AgreementDisplayStatus.editable()` is **removed**, and every caller (including the "My
Agreements" summary) moves to the new predicate. Note this makes `AgreementSummaryResponse`
report a *wider* editable window than before, which is correct and intended -- the list and
the status page must agree.

### D2: The stage is an additive field, not a redefinition of `status`

`SigningProgressResponse` gains `stage` (the `SignatureStatus` name, or `NOT_STARTED` when
no request exists) and `editable`. The existing `status` (`AgreementDisplayStatus`) field
stays exactly as it is.

*Why:* "My Agreements" and its tests already consume `status`; redefining it to be
finer-grained would ripple through the list UI for no benefit. Additive fields cost nothing
and leave a rollback path -- the page degrades to today's behaviour if `stage` is absent.

*Alternative rejected:* widening `AgreementDisplayStatus` with `AWAITING_STAMP` /
`OUT_FOR_SIGNATURE` members. It is documented as the *display* status for the list; adding
members changes the meaning of a value other code switches on exhaustively.

The response still carries no eKYC-derived data, no signing URL, and no provider credential
-- `stage` and `editable` are both derived from our own FSM column.

### D3: The edit route is permitted in the chain and scoped in the handler

`PUT /api/agreements/*` moves from `authenticated()` to `permitAll`, with the handler
deciding: unowned -> the id is the capability, proceed; owned by the caller -> proceed;
owned by anybody else, or unknown -> `404`; edit window closed -> `409`.

*Why:* this mirrors `GET /api/agreements/{id}` exactly. The filter chain cannot see the
row's owner or its stamp state, so the decision has to be where those are visible. The
matcher for `PUT` must stay ordered after the authenticated `GET /api/agreements` and
`POST .../claim` matchers, which is where it already sits.

*This is the accepted risk from the proposal, stated plainly:* an email inbox is now a
sufficient credential to rewrite the rent on an unowned agreement before it is stamped.
A forwarded copy of the recovery mail grants the same power. This deliberately reverses
design D16 (which held the anonymous write surface to contacts-only) at the user's
direction. The controls below are what make it defensible, and they are in scope.

### D4: Compensating controls, all in scope

1. **Narrow surface.** The route accepts the create-shaped body only. Owner, payment state,
   stamp info, tracking reference, creation timestamp, and duration stay server-managed and
   are not fields on the request record -- so they cannot be mass-assigned, they are
   *unrepresentable*.
2. **Revocation is unchanged.** A claimed agreement answers `404` to any non-owner, read and
   write alike. Claiming still retires every emailed link, exactly as `agreement-recovery`
   already promises.
3. **Hard stop at the stamp.** `409` once `STAMPED` is reached. This is the money line.
4. **Audit.** Every edit attempted without an authenticated owner writes an audit row:
   agreement id, outcome, timestamp. **No party PII, no contact address, no edited values**
   -- the row records that an edit happened, not what it said. Sized to answer "was this
   agreement changed through a link, and when", which is the question a disputed agreement
   raises.
5. **Rate limit.** Per source and per agreement, evaluated **before** the agreement lookup
   so a throttled response is not an existence oracle.

### D5: Reuse the recovery limiter's shape, not its class

`RecoveryRateLimiter` is package-private in `signing.recovery` and its limits are tuned for
a different thing (mail nuisance). Extract its sliding-window mechanism into a small shared
type in the signing module and configure two instances: the existing recovery limits, and
new edit limits.

*Why:* copying the algorithm a second time guarantees the two drift. Making the recovery
class public to reuse it would export a tuned-for-mail policy as if it were general.

Same caveat as the original, carried forward verbatim: **in-memory, therefore per-instance**.
On one deployment that is the whole population; scaled out it weakens proportionally rather
than disappearing. Acceptable for a nuisance bound; the time to move it to shared storage is
when the app is actually scaled out.

### D6: Surface post-order edits to staff fulfilment

The agreement records when its terms last changed, and `StampQueueEntry` carries whether
that happened **after** the order was placed. The staff console flags such a row.

*Why:* this is the operational hole D1 opens. A request rests in `PDF_GENERATED` for hours
or days precisely because staff are buying a certificate against those terms, out of band,
with real money -- and duty is a function of rent and term. Without this signal, a customer
could raise the rent between an operator reading the row and the operator filing the
purchase, and the mismatch would surface only at intake. The flag does not prevent that race;
it makes it visible and re-checkable, which is the honest bound for a manual process.

*Alternative considered and rejected for now:* blocking edits once a row has been *opened*
by staff. There is no "claimed by an operator" concept in the queue today, and inventing one
to serve this is a larger change than the risk warrants.

### D7: The status page composes three reads and polls only while in flight

`AgreementStatus.vue`, mounted on the `openLink` route, calls `getAgreement`,
`getPaymentProgress`, and a new `getSigningProgress`. The address bar keeps
`/agreement/<uuid>` (today's code rewrites it to `/app`, which is what makes the page
unreloadable). Milestones are derived from server fields only; payment shows as settled
only on `PAID`/`WAIVED`, per the rule `payments.ts` is built around. Polling runs while the
stage is non-terminal, stops on a terminal stage, and pauses while the tab is hidden.

Keeping the id in the address bar is safe for the same reason it is safe today: the referrer
policy in `index.html` is what stops it crossing an origin, and the id is already in the
customer's history via the emailed link.

*Choosing edit:* the page hands the loaded agreement to the existing `CaptureForm` in edit
mode -- the same component "My Agreements" already uses. No second edit UI is built.

## Risks / Trade-offs

- **A link holder can rewrite terms an unowned agreement's other party agreed to.** -> The
  hard stop at the stamp, the `404` on a claimed agreement, the audit row, and the rate limit
  (D4). Residual risk is real and accepted at the user's direction: before stamping, whoever
  holds the mail can change the rent, and the other party is not notified. **Mitigation worth
  taking in a follow-up:** notify every party by email when terms change after the order is
  placed. Out of scope here only because it widens the change; called out so it is not lost.
- **Staff buy a stamp against terms that then change.** -> D6's flag plus the regenerated
  draft. Not eliminated -- a manual out-of-band purchase cannot be transactionally coupled to
  an edit. Operators re-check duty on a flagged row.
- **Money already taken may no longer match the terms.** -> Out of scope (Non-Goals). An edit
  that changes the rent can change the duty owed; the queue flag is what routes it to a human.
- **`editable` widens for existing agreements** -- an agreement sitting in `PDF_GENERATED`
  today becomes editable the moment this deploys, including from "My Agreements". -> Intended
  and specified, but it is a behaviour change for live data, not only for new flows. Worth
  saying out loud at deploy.
- **The removed `AgreementDisplayStatus.editable()` has callers.** -> Compile-time break, which
  is the desired failure mode: every caller is found by the compiler and moved deliberately.
- **Rate limiting is per-instance.** -> Accepted, carried forward from the existing limiter
  (D5).

## Migration Plan

1. Two forward-only Flyway migrations: the anonymous-edit audit table, and the agreement's
   terms-last-changed timestamp. Both additive; no backfill (a null timestamp means "never
   edited after creation", which is the correct reading for existing rows).
2. Backend, then frontend. The added progress fields are additive, so the current SPA keeps
   working against the new backend during the gap.
3. **Rollback:** revert the frontend to restore today's landing behaviour. The backend's
   widened edit window is the part with no clean rollback once an agreement has been edited
   post-finalisation -- reverting re-freezes it but does not un-edit it. The migrations are
   additive and can be left in place.
4. No config or secret change. No new dependency in either build.

## PII / security review

No Aadhaar number, OTP, VID, or eKYC-derived value is read, stored, transmitted, or logged by
anything in this change. The audit row holds an agreement id, an outcome, and a timestamp --
no party PII and no edited values. Progress keeps its existing prohibitions. Contact values in
any related log line stay redacted through the existing redaction helper. Sandbox + dummy data
only is preserved. The one deliberate posture change is D3, recorded as an accepted risk with
the controls in D4.
