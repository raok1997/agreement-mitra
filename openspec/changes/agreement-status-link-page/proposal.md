## Why

The link we email every party after payment (`/agreement/<uuid>`) currently drops the
recipient straight into the capture/edit form. That is the wrong landing for the question
the recipient actually has, which is "where has my agreement got to?" -- and it is a form
they are not allowed to submit, because the agreement froze the moment they finalised. So
the one message we send to keep customers connected to their agreement lands them on a
dead-end screen.

The recipient should see the agreement's position in the fulfilment pipeline -- drafted,
paid, e-stamped, signed by each party -- and, while the document has not yet been committed
to a stamp, be able to correct it.

## What Changes

- **New status landing.** `/agreement/<uuid>` renders an agreement status page: the tracking
  reference, a milestone timeline (Drafted -> Paid -> E-stamped -> each party's signature ->
  Completed), and the signed document when it exists. The URL is preserved so the page is
  refreshable and bookmarkable from the email.
- **Finer-grained signing stage on the read model.** The per-party progress view today
  collapses `PDF_GENERATED`, `STAMPED` and `SIGN_REQUESTED` into a single `IN_PROGRESS`
  aggregate, so no client can tell "awaiting the e-stamp" from "out for signature". Progress
  gains an explicit stage plus a server-decided `editable` flag, so the page renders the
  timeline and the edit affordance from the server rather than inferring either.
- **BREAKING (behavioural): the terms freeze moves from finalisation to stamping.** Terms are
  editable while the signing request rests in `PDF_GENERATED` and freeze at `STAMPED`. Stamp
  duty is a function of rent and term and the certificate is procured against one specific
  document, so the stamp -- not the order -- is the point of no return. This reverses the
  `manual-estamp-upload` requirement "The draft cannot change between finalisation and
  stamping" and the `agreement-recovery` requirement "Recovered access permits continuation
  but not alteration of terms".
- **BREAKING (security posture): terms editing opens to the anonymous link holder.** The
  unguessable agreement id is accepted as the bearer capability for a pre-stamp terms edit,
  not only for the read. This reverses the earlier decision (design D16) that deliberately
  held the anonymous write surface to contacts-only. Accepted risk, with compensating
  controls specified in `design.md`: the route stays narrowly scoped to terms and parties,
  refuses a claimed or stamped agreement, is rate limited, and audits every anonymous edit.
- **Unchanged:** the owner-scoped authenticated edit keeps working for a claimed agreement;
  a claimed agreement still answers an anonymous caller with `404` (no ownership oracle) and
  the page reads that as "saved to an account, sign in", not "not found".

## Capabilities

### New Capabilities
- `agreement-status-view`: the party-facing status landing reached from the emailed link --
  what the milestone timeline shows, what it may never show, how it behaves for a claimed or
  unknown agreement, and when it offers the edit and download actions.

### Modified Capabilities
- `signing-request`: "Signing progress is visible per party" gains an explicit fulfilment
  stage and an `editable` flag; "The order is placed when the customer finalises, and the
  draft freezes then" changes so that finalisation places the order but the freeze falls at
  stamping.
- `agreement-management`: "An owner may fully edit an in-progress agreement" changes its
  freeze condition from "no signing request exists" to "no stamp is attached", and admits an
  anonymous caller holding the agreement id for an unowned agreement.
- `agreement-recovery`: "Recovered access permits continuation but not alteration of terms"
  changes to permit alteration of terms before stamping, and to refuse it after.
- `backend-security-baseline`: the agreement edit route moves from `authenticated()` in the
  filter chain to a permitted route whose capability/ownership scoping is decided in the
  handler, matching the read; a per-source rate limit is added to it.

## Impact

- **Backend (`signing` module).** `AgreementDisplayStatus` (stage projection),
  `SigningProgressResponse`, `SigningRequestService.progress`, `AgreementService.update`
  (freeze predicate + anonymous caller), `AgreementController.update`, `SecurityConfig`
  (route authorization + rate limit), a new anonymous-edit audit record and its Flyway
  migration. No change to the FSM's states or transitions.
- **Frontend.** `App.vue` route `openLink` mounts a new `AgreementStatus.vue` instead of
  jumping to edit mode; a new `src/api/signingProgress.ts`; no new dependency (no
  `vue-router` -- the router-less view-switch stands, per flow-journal 8.3).
- **Staff fulfilment.** An agreement edited after finalisation but before stamping changes
  the document staff are about to buy a stamp for; the queue must reflect the edit. See
  `design.md`.
- **APIs.** `GET /api/signing/{id}/progress` gains fields (additive). `PUT
  /api/agreements/{id}` widens its accepted callers and narrows its freeze.

## Signing FSM

No state or transition is added, removed, or re-targeted. The change reads the FSM at finer
resolution (`PDF_GENERATED` vs `STAMPED` vs `SIGN_REQUESTED` are no longer collapsed) and
moves an *external* freeze rule from "a signing request exists" to "the request has reached
`STAMPED`". The `PDF_GENERATED -> STAMPED` transition remains driven solely by staff stamp
intake.

## PII / security review

- **New outbound PII flow:** none. The status page shows only data the requester's own
  agreement already contains, and the progress view keeps its existing prohibitions -- no
  eKYC-derived signer data, no signing URL, no provider credential. No Aadhaar number, OTP,
  or VID is read, stored, transmitted, or logged by anything in this change.
- **Moved PII:** none moves between systems. The anonymous-edit audit record stores the
  agreement id, the outcome, and a timestamp; it SHALL NOT store party PII, and any recipient
  or contact value in a log line stays redacted through the existing `RecipientRedaction`.
- **New secrets:** none. No credential is introduced, read, or returned.
- **Widened attack surface (declared, not incidental):** anonymous terms editing before
  stamping, per the accepted risk above. Compensating controls are specified in `design.md`
  and are part of this change's scope, not a follow-up.
- **Sandbox + dummy data only** is preserved; nothing here touches a production credential or
  a real identity provider.
