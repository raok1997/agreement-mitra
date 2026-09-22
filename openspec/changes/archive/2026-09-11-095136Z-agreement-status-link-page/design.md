## Context

See `proposal.md` -- Why. The constraints that shape the approach:

- **The read side already exists and is already anonymous.** `GET /api/agreements/{id}`,
  `GET /api/agreements/{id}/payment`, `GET /api/signing/{id}/progress` and
  `GET /api/agreements/{id}/signed-document` are all `permitAll` in the chain
  (`SecurityConfig.java:189-270`) with ownership scoping decided in the handler
  (`AgreementService.findByIdForReader`, `:183`), precisely so a link holder can reach an
  unowned agreement and a claimed one answers `404`. The page needs no new endpoint.
- **Auth is a Bearer header, not a cookie** (`authStore.ts:22-23`). A plain `<a href>` to
  the signed-document route carries no session, so a signed-in owner viewing a claimed
  agreement would get `404` from a bare link (`SignedDocumentController.java:74-84`).
- **Stamp state is not on the agreement read.** `AgreementResponse` carries no stamp info,
  and `SigningProgressResponse.status` collapses `PDF_GENERATED`/`STAMPED`/`SIGN_REQUESTED`
  into `IN_PROGRESS` (`AgreementDisplayStatus.java:29-35`). So "awaiting stamp" vs "out for
  signature" needs additive fields on progress; nothing else.
- **Finalise places the order before payment.** `SigningRequestService.finalise`
  (`:222-228`) creates the `PDF_GENERATED` row; payment follows. So a reachable state is
  "order placed, unpaid": `stage = AWAITING_STAMP`, `paymentState = UNPAID`.
- **`SIGNED` can precede the stored document.** `completeDocument` flips the row to
  `SIGNED`, then fetches the artifacts outside any transaction; on failure the row stays
  `SIGNED` with a null key for reconciliation (`SigningRequestService.java:373-386`), and
  the download route answers `404` until then.
- **Signing is sequential** (owner first, `SIGNING_ORDER`, `:415`); per-party statuses are
  `InviteeStatus` `PENDING / SIGNED / REJECTED / EXPIRED`, defaulting to `PENDING` before any
  request exists; `PartyProgress.role` can be null (`:337`).
- **`progress()` already knows its caller and already scopes ownership**
  (`SigningRequestService.progress(agreementId, callerIdentityId, staff)`, `:318`).
- **No router on the frontend.** `App.vue` resolves routes by hand (flow-journal 8.3);
  `openFromLink` (`:162-183`) loads the agreement, flips to edit mode and rewrites the URL to
  `/app`. Its catch branch already renders the claimed/unknown message with a sign-in button.
- **No view test uses fake timers today**; the one timer-driven module (`payments.ts`) is
  testable because its `wait` is injectable.
- **The signing FSM is untouched**, and so is every write path.
- **Sibling change.** "Signing progress is visible per party" is `ADDED` by the active
  `zoop-aadhaar-esign` change (60/61 tasks). This change `MODIFIES` it, so
  `zoop-aadhaar-esign` archives first (decided with the user).

## Goals / Non-Goals

**Goals:**

- A status landing that renders from server-reported state only, that reloads and
  bookmarks correctly, and keeps itself current while the agreement is in flight.
- The smallest backend change that makes the pipeline legible: a stage, a terminal flag,
  and whether the signed document is ready.

**Non-Goals (deliberate -- see the journal's review rounds 1-2 for why):**

- No edit of any kind from the link, and no change to when terms freeze. Post-payment
  correction is registered as `terms-correctable-until-stamping`.
- No claim ("save to my account") from the status page; no sign-in return path. Today's
  claimed-agreement message and sign-in button stay as they are.
- No change to `AgreementDisplayStatus`, `AgreementSummaryResponse`, "My Agreements",
  `SecurityConfig`, stamp intake, delivery, or the ToS.
- No new read endpoint; no aggregate read model; no `vue-router`; no migration.
- No server-side rate limit on the anonymous reads (pre-existing gap; the `signing-auth`
  register row is widened to name them).

## Decisions

### D1: Three additive fields on progress; `status` is untouched

`SigningProgressResponse` gains:

- `stage` -- a new `FulfilmentStage` enum at the `signing` module root: `NOT_STARTED`,
  `AWAITING_STAMP`, `STAMPED`, `OUT_FOR_SIGNATURE`, `SIGNED`, `EXPIRED`, `FAILED`,
  `STAMP_FAILED`, mapped from `Optional<SignatureStatus>` by exhaustive switch (the reserved
  `SignatureStatus.DRAFT` maps to `NOT_STARTED`). Not `SignatureStatus.name()`: that would
  export an internal enum, including a reserved value, as a wire contract.
- `terminal` -- `true` for `SIGNED`, `EXPIRED`, `FAILED`, `STAMP_FAILED`.
- `signedDocumentReady` -- `true` when the signed PDF key is present, read as a
  `signedPdfStored` flag on the persistence `Progress` record (one query; the key itself
  never leaves the persistence layer), so the page never offers a download the route would
  refuse.

All three derive from our own columns and carry **no reason, certificate detail, staff
identity, or queue position** -- `FulfilmentStage` is a position, not an explanation. The
existing `status` stays exactly as it is (the list UI consumes it); the register row
`agreement-status-detail` can reuse `FulfilmentStage` later. `FulfilmentStage` lives at the
module root (Spring Modulith exports it) beside `SignatureStatus`; it is a pure projection
with no dependencies, so `ModularityTests` are unaffected.

### D2: Milestones are explicit sets over three reads -- never ordinal, never inferred

`AgreementStatus.vue`, on the `openLink` route, calls `getAgreement`, `getPaymentProgress`
and `getSigningProgress`. It shows the tracking reference, the agreement's terms (address,
rent, dates) and each party's **name and role only** -- no father's name, current address,
email, mobile, and no `captureData`. Milestone rules (SPA compares against explicit sets,
never enum order):

| Milestone | done | current | failed | not reached |
|---|---|---|---|---|
| Drafted | always | -- | -- | -- |
| Paid | `paymentState ∈ {PAID, WAIVED}` | otherwise, while the stage is not terminal (payment route offered) | -- | unpaid on a terminal stage: a fact, never an invitation to pay for a dead order |
| E-stamped | `stage ∈ {STAMPED, OUT_FOR_SIGNATURE, SIGNED, FAILED, EXPIRED}` | Paid done and `stage = AWAITING_STAMP` | `stage = STAMP_FAILED` | Paid not done |
| Party *P* signs | *P*'s status `SIGNED` | `stage = OUT_FOR_SIGNATURE` and *P* is the **first** party in signing order still `PENDING` | *P*'s status `REJECTED`/`EXPIRED` | otherwise (incl. `PENDING` under `FAILED`/`EXPIRED`, shown as "signing halted") |
| Completed | `stage = SIGNED` | -- | -- | otherwise |

A null `role` is labelled "Party". Payment shows as settled only on `PAID`/`WAIVED`;
nothing that happened in the browser advances a milestone. On the payment route's `409` the
page shows the generic "payment cannot be started yet -- contact support with your
reference" copy (the problem type is not carried by `PaymentHttpError`; out of scope).

*Signed document:* offered when `stage = SIGNED && signedDocumentReady`. The download is a
`fetch` with `authHeader()` → `Blob` → `URL.createObjectURL`, clicked and revoked -- the
pattern `CaptureForm.vue:885-900` already uses. **Never** a bare URL or a token in the query
string: the former 404s for a signed-in owner, the latter leaks the session.

### D3: The URL stays; polling is a composable with a floor, a cap and explicit stops

The address bar keeps `/agreement/<uuid>` -- `openFromLink` stops calling
`history.replaceState("/app")`; its existing rationale comment is carried into the new
code. Exposure vs today: the id is already a mailed bearer capability in the customer's
history and is already sent to Razorpay as the order receipt (`PaymentOrderService.java:
167,208`), so Razorpay's in-page script seeing it in `location` is nothing new; `index.html`'s
referrer policy keeps it same-origin. What is new is shoulder-surf / screenshot / tab-sync
exposure of a persistent URL -- accepted, the id grants what the mail already granted.

*Polling* is a `usePolling` composable (interval and `wait` injectable, unit-tested without
mounting): base 20 s, exponential backoff on error capped at 5 min, re-read on
`visibilitychange → visible` only if ≥ 20 s since the last read, paused while hidden,
cleared on unmount. Each `start()` is a generation and `stop()` releases a sleeping loop, so a
restart can never leave two loops reading. Each tick reads progress and payment with
`allSettled`: a `404` from **either** read means the agreement is no longer ours to see (claimed
meanwhile), a partial failure keeps the read that succeeded, and a read-sequence guard stops a
slow older response overwriting a newer one (a payment attempt's own re-read races the loop).
Once payment is settled the payment read is skipped -- it cannot un-settle, and the tab may sit
on the stamp and signing phases for days. It runs while **not** (`paymentState = UNPAID` and `orderStatus` null
and `stage = NOT_STARTED`) -- i.e. a draft with no order is not polled; a finalised-unpaid
agreement is, because that is the state the payment route leaves behind. It stops when
`terminal` is `true`, except `SIGNED && !signedDocumentReady`, where it continues at the
capped interval until the document is ready. After an in-page payment attempt returns, the
page re-reads payment and (re)starts polling. A re-read that answers `404` (claimed
meanwhile) switches to the existing "saved to an account, sign in" message and stops. A
staff re-issue after a terminal stage needs a reload -- acceptable and stated.

*Claimed / unknown:* the first read's catch branch keeps today's behaviour (`App.vue:175-182`),
with its copy lifted into one shared constant (`linkCopy.ts`) that the view's mid-session
branch renders too, so the two can never say different things; both show the sign-in button
only when there is no session. The server's `404` parity already makes the two
indistinguishable.

*Onward navigation:* the link page is chrome-less like the other public routes, but it is now
bookmarkable, so it carries a minimal nav -- the wordmark home, and "My agreements" for a
signed-in customer. A history move onto a different agreement link re-resolves that link rather
than showing the last-loaded agreement.

## Risks / Trade-offs

- **The page shows a problem the customer cannot fix.** Exactly today's behaviour, with a
  page that says so instead of a form that 409s. Accepted; registered.
- **Archive order.** `zoop-aadhaar-esign` first.
- **Client floor is a courtesy, not a control.** ~3 req/min per open in-flight tab; a
  hostile client ignores the floor. Bounded by the id being unguessable; named in the
  `signing-auth` register row so the eventual rate limit covers these reads.
- **Test wall-clock.** 1.5 adds a few Testcontainers cases to an existing class (no new
  container); frontend timers are injectable so no real sleeps. Report `check` time.

## Migration Plan

None. Three additive JSON fields; the current SPA ignores them, so backend-then-frontend
deploys are safe in either order. Rollback is a frontend revert.

## PII / security review

No Aadhaar number, OTP, VID, or eKYC-derived value is read, stored, transmitted, or logged by
anything in this change. No new column, route, or write. Progress gains only three
FSM/column-derived booleans-and-enum; the page renders party name + role and the terms, not
contacts. The download carries the session in a header, never a URL. Sandbox + dummy data
only is preserved.
