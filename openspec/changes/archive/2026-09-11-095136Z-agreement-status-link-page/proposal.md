## Why

The link we email every party after payment (`/agreement/<uuid>`) currently drops the
recipient straight into the capture/edit form. That is the wrong landing for the question
the recipient actually has, which is "where has my agreement got to?" -- and it is a form
they cannot submit, because the agreement froze when they finalised. So the one message we
send to keep customers connected to their agreement lands them on a dead-end screen.

The recipient should see the agreement's position in the fulfilment pipeline -- drafted,
paid, e-stamped, signed by each party, completed -- and be able to download the signed
document when it exists.

## What Changes

- **New status landing.** `/agreement/<uuid>` renders an agreement status page: the tracking
  reference, the agreement's own terms, a milestone timeline (Drafted -> Paid -> E-stamped ->
  each party's signature -> Completed), the signed document when it exists, and the payment
  route while payment is outstanding. The URL is preserved so the page is refreshable and
  bookmarkable from the email. It refreshes itself while the agreement is in flight.
- **Finer-grained signing stage on the read model.** The per-party progress view today
  collapses `PDF_GENERATED`, `STAMPED` and `SIGN_REQUESTED` into a single `IN_PROGRESS`
  aggregate, so no client can tell "awaiting the e-stamp" from "out for signature". Progress
  gains an explicit `stage`, a server-decided `terminal` flag, and `signedDocumentReady` (the
  row can be `SIGNED` before the PDF is stored). Additive; the existing `status` field is
  untouched.
- **Nothing else changes.** The terms freeze stays at finalisation. No edit is offered from
  the link; no claim is offered from the link; no write path, migration, `SecurityConfig`,
  or stamp-intake behaviour is touched. Editing after payment was explored in this change's
  first two review rounds and deliberately dropped -- see `.flow-journal.md` and the
  follow-up register entry `terms-correctable-until-stamping`.
- **Unchanged:** a claimed agreement still answers an anonymous caller with `404` (no
  ownership oracle) and the page keeps today's "saved to an account, sign in" message.

## Capabilities

### New Capabilities
- `agreement-status-view`: the party-facing status landing reached from the emailed link --
  what the milestone timeline shows, what it may never show, how it refreshes, and how it
  behaves for a claimed or unknown agreement.

### Modified Capabilities
- `signing-request`: "Signing progress is visible per party" gains an explicit fulfilment
  `stage`, a `terminal` flag and `signedDocumentReady`. **This requirement is `ADDED` by the active
  `zoop-aadhaar-esign` change (60/61 tasks); that change archives before this one.**

## Impact

- **Backend (`signing` module).** A `FulfilmentStage` enum at the module root;
  `SigningProgressResponse` and `SigningRequestService.progress` gain three fields. No change
  to the FSM, to `AgreementDisplayStatus`, to any write path, or to `SecurityConfig`. No
  migration.
- **Frontend.** `App.vue` route `openLink` mounts a new `AgreementStatus.vue` instead of
  jumping to edit mode and stops rewriting the URL; new `src/api/signingProgress.ts` (progress
  read + header-authenticated signed-document download) and a `usePolling` composable. The
  page shows party name + role only -- no contacts. No new dependency (no `vue-router` -- the router-less
  view-switch stands, per flow-journal 8.3).
- **APIs.** `GET /api/signing/{id}/progress` gains `stage`, `terminal` and
  `signedDocumentReady` (additive).
- **Archive order.** After `zoop-aadhaar-esign`.

## Signing FSM

No state or transition is added, removed, or re-targeted. The change reads the FSM at finer
resolution (`PDF_GENERATED` vs `STAMPED` vs `SIGN_REQUESTED` are no longer collapsed in the
progress view) and changes nothing that drives it.

## PII / security review

- **New outbound PII flow:** none. The status page shows only data the requester's own
  agreement already exposes through the three existing anonymous, owner-scoped reads
  (`GET /api/agreements/{id}`, `/payment`, `/api/signing/{id}/progress`). Progress keeps its
  prohibitions -- no eKYC-derived signer data, no signing URL, no provider credential. No
  Aadhaar number, OTP, or VID is read, stored, transmitted, or logged by anything here.
- **Moved PII:** none. No new column.
- **New secrets:** none.
- **Attack surface:** no new route, no new write. `stage`/`terminal` derive from our own FSM
  column and disclose nothing the existing `status` plus per-party statuses did not already
  imply. Keeping the id in the address bar is safe for the reason it is safe today: the
  referrer policy in `index.html` keeps it same-origin and it is already in the customer's
  history via the emailed link.
- **Sandbox + dummy data only** is preserved.
