> Archive **after** `zoop-aadhaar-esign` (it `ADDS` the progress requirement this change modifies).

## 1. Backend: fulfilment stage on progress

- [x] 1.1 Add `FulfilmentStage` at the `signing` module root (beside `SignatureStatus`):
      `NOT_STARTED`, `AWAITING_STAMP`, `STAMPED`, `OUT_FOR_SIGNATURE`, `SIGNED`, `EXPIRED`,
      `FAILED`, `STAMP_FAILED`; `static from(Optional<SignatureStatus>)` by exhaustive switch
      (reserved `DRAFT` -> `NOT_STARTED`), mirroring `AgreementDisplayStatus.from(Optional)`;
      `boolean terminal()` (design D1).
- [x] 1.2 Add `stage`, `terminal` and `signedDocumentReady` to `SigningProgressResponse`;
      populate in `SigningRequestService.progress` from the same `Optional<SignatureStatus>`
      that feeds `status`; `signedDocumentReady` comes from a `signedPdfStored` flag on
      `SigningRequestPersistence.Progress` (one query; the key itself never leaves persistence).
      Leave `status` untouched.
- [x] 1.3 **Unit test** `FulfilmentStage`: every `SignatureStatus` maps; `PDF_GENERATED`,
      `STAMPED`, `SIGN_REQUESTED` are distinct; empty -> `NOT_STARTED`; `terminal()` true for
      exactly `SIGNED`, `EXPIRED`, `FAILED`, `STAMP_FAILED`; `AgreementDisplayStatus` is
      derivable one-way from `FulfilmentStage` (so the two projections cannot drift).
- [x] 1.4 **Unit test** (new -- no unit-level no-leak test exists today) that
      `SigningProgressResponse` and `PartyProgress` have exactly the expected record components
      (agreementId, status, stage, terminal, signedDocumentReady, parties; signerId, role,
      status) and that none matches a denylist (url, otp, aadhaar, vid, token, secret, reason,
      certificate). The `signedDocumentReady` mapping is pinned by the integration case in 1.5.
- [x] 1.5 **Integration test** (`SigningProgressApiIntegrationTest`, existing class, no new
      container): `stage`/`terminal`/`signedDocumentReady` correct for `PDF_GENERATED` (set via
      the injected `JdbcTemplate` -- not reachable through the API without a stamp),
      `SIGN_REQUESTED` (existing fixture), and `SIGNED` with and without a stored key (JdbcTemplate),
      read as owner and anonymously; ownership scoping unchanged (unowned readable anonymously,
      claimed 404 to a non-owner).
- [x] 1.6 Keep `ModularityTests` green; `./gradlew spotlessApply`; `./run-tests.sh`; report
      wall-clock.

## 2. Frontend: the status landing

- [x] 2.1 Add `src/api/signingProgress.ts` -- `getSigningProgress(id)` returning `status`,
      `stage`, `terminal`, `signedDocumentReady`, and per-party progress, sending `authHeader()`
      when present (as `getAgreement` does); and `downloadSignedDocument(id)`: `fetch` with
      `authHeader()` -> `Blob` -> `URL.createObjectURL` -> click -> revoke (the
      `CaptureForm.vue:885-900` pattern). Never a bare URL, never a token in the query string.
      No `fetch` in components. **Unit test** (`signingProgress.test.ts`) that both calls send the
      Bearer header and the download never puts the session in the URL.
- [x] 2.2 Add `src/composables/usePolling.ts`: `interval` and `wait` injectable; base 20 s,
      exponential backoff on error capped at 5 min, `visibilitychange` pause/resume honouring the
      20 s floor, `stop()`, cleanup on unmount (design D3). **Unit test** it without mounting a
      view: schedule, backoff and cap, floor on visible, stop, cleanup.
- [x] 2.3 Add `src/views/AgreementStatus.vue`: composes `getAgreement`, `getPaymentProgress`
      and `getSigningProgress`; renders the tracking reference, the terms (address, rent,
      dates), each party's name + role only (null role -> "Party"), and the milestone timeline
      per the explicit-set table in design D2 (done / current / failed / not reached; e-stamp
      not current until paid; only the next party in signing order current; `PENDING` under
      `FAILED`/`EXPIRED` shown as halted, the responsible party named). Never compare enum order.
      Never render contacts, father's name, current address, or `captureData`.
- [x] 2.4 Actions: download via `downloadSignedDocument` only when `stage = SIGNED &&
      signedDocumentReady`, else "being prepared"; payment route (`payForAgreement`) while
      outstanding, generic copy on its 409, and after the attempt returns re-read payment and
      resume polling. No edit action.
- [x] 2.5 Wire `usePolling` into the view: poll unless (`UNPAID` and `orderStatus` null and
      `NOT_STARTED`); stop on `terminal` unless `SIGNED && !signedDocumentReady` (then continue
      at the cap); on 404 switch to the saved-to-an-account message and stop.
- [x] 2.6 Rework `App.vue`'s `openLink` route to mount the status view and **stop rewriting the
      URL to `/app`**; carry the existing rationale comment (`App.vue:171-173`) forward. Keep the
      catch branch (`linkNeedsSignIn`, message, sign-in button) as is, with its copy lifted into
      `src/views/linkCopy.ts` so the status view's mid-session branch renders the same words. The
      link page carries a minimal nav (wordmark home; "My agreements" for a signed-in viewer) and a
      history move onto a different link re-resolves it.
- [x] 2.7 **Frontend component tests** for `AgreementStatus.vue` (inject a zero `wait`): terms,
      reference, party name+role shown and contacts/father/address/captureData absent; each
      milestone rule in D2 including order-placed-unpaid, first-pending-current, halted parties,
      `STAMP_FAILED` as failed not done; download only when ready and sends the header; "being
      prepared" when not ready; payment route when outstanding; no edit; no client-side payment
      optimism; nothing from progress beyond the five fields; polling: not scheduled for a draft
      with no order, scheduled for finalised-unpaid, stops on terminal, continues when signed and
      not ready, stops on 404 with the message. The hidden/visible floor is pinned at the
      composable level in 2.2 (injectable `isHidden`), not by mounting the view.
- [x] 2.8 **Frontend tests** for `App.vue`: `/agreement/<uuid>` mounts the status view, not
      `CaptureForm`, and the address bar still reads `/agreement/<uuid>` afterwards; the
      claimed/unknown branch still shows the sign-in message.
- [x] 2.9 `npm run lint` and `npm run build`. **Note:** `lint` reports only the two pre-existing
      `security-scan.mjs` `no-undef` errors and `build` stops at `security:scan` on the pre-existing
      vitest 3.2.7 advisory GHSA-82fw-gwwq-j7x9 -- both are register row `frontend-dev-dep-refresh`,
      not this change. Ticked on `vitest run` (218) + `vue-tsc -b` + `vite build` green.

## 3. Close-out

- [x] 3.1 `docs/ROADMAP.md` follow-up register: add `terms-correctable-until-stamping` -- allow
      the owner to correct terms after payment and before the stamp; prior art is this change's
      `.flow-journal.md` review rounds 1-2 and its pre-rescope `design.md` (git history): needs
      draft regeneration outside the row lock, a draft-generation guard on the `STAMPED`
      transition, server-preserved contacts, party notification, a staff queue signal, and a
      `payment-processing` delta. Widen the `signing-auth` row's scope to name the anonymous
      reads (`GET /agreements/*`, `/payment`, `/signing/*/progress`) now that a page polls them.
      Trim the `agreement-status-detail` row: `FulfilmentStage` now exists for the list to reuse.
- [x] 3.2 Manual test (steps 1/2/3/5 by hand; step 4 on integration evidence -- see .flow-journal.md): pay an agreement anonymously; open the emailed link; confirm terms,
      reference, party names, "awaiting e-stamp"; reload -- same page; upload a stamp as staff
      and confirm the page moves to "out for signature" without a reload; open a link for a
      claimed agreement and confirm the sign-in message; on a signed agreement confirm the
      download works both anonymously (unowned) and signed in (claimed).
- [x] 3.3 Run `openspec validate agreement-status-link-page --strict`.

## Coverage

Scenario-coverage matrix (contract agreed before implementation; `openspec-validate` checks it
was discharged). 30 scenarios — 28 COVERED, 1 GROUPED, 1 MANUAL, 0 WAIVED, 0 UNMAPPED.

| # | Capability | Scenario | Disposition | Evidence |
|---|---|---|---|---|
| 1 | signing-request | Per-party progress is visible | GROUPED | existing `SigningProgressApiIntegrationTest` cases (retained) + 1.5 |
| 2 | signing-request | Awaiting-stamp is distinguishable from out-for-signature | COVERED | 1.3, 1.5 |
| 3 | signing-request | Terminality is reported by the server | COVERED | 1.3, 1.5 |
| 4 | signing-request | Signed-document readiness is reported by the server | COVERED | 1.4, 1.5 |
| 5 | signing-request | Progress does not leak PII or capabilities | COVERED | 1.4 (unit, component list), existing integration no-leak case |
| 6 | signing-request | Progress is not readable across customers | COVERED | 1.5 |
| 7 | agreement-status-view | The link opens the status view | COVERED | 2.8 |
| 8 | agreement-status-view | The status view is reloadable | COVERED | 2.8 (address bar retained) |
| 9 | agreement-status-view | The tracking reference, terms and parties are shown | COVERED | 2.7 |
| 10 | agreement-status-view | Mid-flight progress is legible | COVERED | 2.7 |
| 11 | agreement-status-view | Awaiting the e-stamp is distinguishable from out for signature | COVERED | 2.7 |
| 12 | agreement-status-view | An order placed but unpaid shows payment as the current step | COVERED | 2.7 |
| 13 | agreement-status-view | A terminal failure is stated, not hidden | COVERED | 2.7 |
| 14 | agreement-status-view | A closed payment window does not claim payment | COVERED | 2.7 |
| 15 | agreement-status-view | Server-confirmed payment settles the milestone | COVERED | 2.7 |
| 16 | agreement-status-view | Polling stops on a terminal stage | COVERED | 2.2, 2.7 |
| 17 | agreement-status-view | A signed agreement keeps polling until its document is ready | COVERED | 2.7 |
| 18 | agreement-status-view | A refused re-read switches to the saved-to-an-account message | COVERED | 2.7 |
| 19 | agreement-status-view | A draft with no order is not polled | COVERED | 2.7 |
| 20 | agreement-status-view | A finalised but unpaid agreement is polled | COVERED | 2.7 |
| 21 | agreement-status-view | A hidden page pauses and a visible page resumes within the floor | COVERED | 2.2, 2.7 |
| 22 | agreement-status-view | The signed document is offered on completion | COVERED | 2.7 (offered only when ready, via the helper), 2.1 `signingProgress.test.ts` (Bearer header asserted, no token in the URL) |
| 23 | agreement-status-view | The download is withheld until the document is ready | COVERED | 2.7 |
| 24 | agreement-status-view | Outstanding payment is actionable | COVERED | 2.7 |
| 25 | agreement-status-view | No edit is offered | COVERED | 2.7 |
| 26 | agreement-status-view | Provider-derived and staff-only detail is absent | COVERED | 2.7, 1.4 |
| 27 | agreement-status-view | Party contact details are absent | COVERED | 2.7 |
| 28 | agreement-status-view | The view is scoped to one agreement | MANUAL | 3.2 |
| 29 | agreement-status-view | A claimed agreement directs the holder to sign in | COVERED | 2.8 |
| 30 | agreement-status-view | Claimed and unknown are indistinguishable | COVERED | existing `AgreementOwnershipIntegrationTest` 404 parity (pre-existing) + 2.8 (one message) |
