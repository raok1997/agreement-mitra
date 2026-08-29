## 1. The edit window predicate (do this first -- everything else keys off it)

- [ ] 1.1 Add the terms-edit-window predicate to the `signing` module: open when there is no
      signing request or its `SignatureStatus` is `PDF_GENERATED`; closed for `STAMPED`,
      `SIGN_REQUESTED`, `SIGNED`, `EXPIRED`, `FAILED`, `STAMP_FAILED` (design D1). Exhaustive
      switch over `SignatureStatus`, so a future state cannot silently default to open.
- [ ] 1.2 Remove `AgreementDisplayStatus.editable()` and move every caller to the new predicate
      (design D1). Expect a compile break at `AgreementService.toSummary` -- resolve it by
      passing the most-recent `SignatureStatus`, not the display status.
- [ ] 1.3 **Unit test** the predicate: one case per `SignatureStatus` value plus the
      no-signing-request case; assert `PDF_GENERATED` is open and `STAMPED` is closed.
- [ ] 1.4 **Unit test** that `AgreementSummaryResponse.editable` now reports open for an
      agreement resting in `PDF_GENERATED` (the deliberate widening called out in Risks).

## 2. Finer-grained signing progress

- [ ] 2.1 Add `stage` (the `SignatureStatus` name, or `NOT_STARTED` when no request exists) and
      `editable` to `SigningProgressResponse`; leave the existing `status` field untouched
      (design D2).
- [ ] 2.2 Populate both in `SigningRequestService.progress`, deriving `editable` from the task-1
      predicate -- never from `status`.
- [ ] 2.3 **Unit test** the stage projection: `PDF_GENERATED`, `STAMPED` and `SIGN_REQUESTED`
      each produce a distinct `stage` and are not collapsed; absent request yields
      `NOT_STARTED`.
- [ ] 2.4 **Unit test** that the response carries no eKYC-derived field, no signing URL, and no
      provider credential (extend the existing assertion to the new fields).
- [ ] 2.5 **Integration test** `GET /api/signing/{id}/progress`: the new fields are present and
      correct for an agreement in `PDF_GENERATED` and one in `SIGN_REQUESTED`; ownership
      scoping is unchanged (unowned readable anonymously, claimed 404 to a non-owner).

## 3. Move the terms freeze from finalisation to stamping

- [ ] 3.1 Replace the `signingRequestQuery.existsForAgreement(id)` gate in
      `AgreementService.update` with the task-1 predicate; keep the `409` response shape.
- [ ] 3.2 Admit the anonymous caller: `update` accepts a null principal for an **unowned**
      agreement, keeps `404` for one owned by another identity, and keeps the owner path
      unchanged (design D3). Ownership resolution must stay before the freeze check so a
      non-owner never learns the stamp state.
- [ ] 3.3 Record the terms-last-changed timestamp on the agreement in `update` (feeds task 6).
- [ ] 3.4 **Unit test** the ownership/freeze decision table: unowned+open -> allowed,
      unowned+stamped -> 409, other-owner -> 404 (both open and stamped, so the refusal is not
      a stamp oracle), unknown -> 404, owner+open -> allowed.
- [ ] 3.5 **Integration test** `PUT /api/agreements/{id}` end to end: anonymous edit succeeds
      while `PDF_GENERATED`, returns `409` once `STAMPED`, returns `404` for a claimed
      agreement, and clears the pinned draft on success.
- [ ] 3.6 **Integration test** that server-managed fields (owner, payment state, stamp info,
      tracking reference) are unchanged by an edit body that attempts to set them.

## 4. Security posture: route authorization, rate limit, audit

- [ ] 4.1 Move `PUT /api/agreements/*` from `authenticated()` to `permitAll` in `SecurityConfig`,
      keeping it ordered after the authenticated `GET /api/agreements` and
      `POST /api/agreements/*/claim` matchers. Update the surrounding comment to say the
      scoping now lives in the handler and why (design D3).
- [ ] 4.2 Extract the sliding-window mechanism out of `RecoveryRateLimiter` into a shared type in
      the `signing` module; re-express the recovery limiter in terms of it with its existing
      limits unchanged (design D5).
- [ ] 4.3 Add the edit rate limiter (per source and per agreement) and apply it in the edit path
      **before** the agreement lookup, so a throttled response is not an existence oracle.
- [ ] 4.4 Add the anonymous-edit audit record + repository: agreement id, outcome, timestamp.
      No party PII, no contact address, no edited values (design D4.4).
- [ ] 4.5 Write it on every edit attempted without an authenticated owner -- successes and
      refusals alike.
- [ ] 4.6 Flyway `V20__agreement_edit_audit.sql`: the audit table plus the agreement's
      terms-last-changed column. Forward-only, additive, no backfill. Keep JPA at
      `ddl-auto: validate`.
- [ ] 4.7 **Unit test** the shared limiter (window, limit, lockout) and that the recovery
      limiter's behaviour is byte-for-byte unchanged after the extraction.
- [ ] 4.8 **Unit test** that the audit record contains no party name, contact address, or edited
      term value.
- [ ] 4.9 **Integration test** the chain posture: unauthenticated `PUT /api/agreements/{id}` is
      permitted by the filter chain (not 401/403) and refused by the handler with `404` for a
      claimed agreement; `GET /api/agreements` and `POST .../claim` still reject anonymously.
- [ ] 4.10 **Integration test** the rate limit: edits beyond the configured rate are refused, and
      a throttled response is identical in shape for an existing and a non-existent agreement.

## 5. Backend regression surface

- [ ] 5.1 Update `SigningControllerTest` / progress slice tests for the added fields.
- [ ] 5.2 Update the security-baseline test that pins `PUT /api/agreements/*` as authenticated --
      it now asserts permitted-in-chain, refused-in-handler.
- [ ] 5.3 Keep `ModularityTests` green (the shared limiter and the predicate must not create a
      cross-module dependency).
- [ ] 5.4 `./gradlew spotlessApply` and run the suite via `./run-tests.sh`.

## 6. Staff fulfilment signal

- [ ] 6.1 Add "terms changed after the order was placed" to `StampQueueEntry`, derived from the
      terms-last-changed timestamp against order placement (design D6). No new PII on the row.
- [ ] 6.2 Flag such a row in `StaffConsole.vue` so an operator re-checks duty before buying the
      certificate.
- [ ] 6.3 **Unit test** the derivation: edited-after-order true only when the timestamp is later
      than order placement; null timestamp is false.
- [ ] 6.4 **Integration test** that the queue row reports the flag after a post-finalisation edit.
- [ ] 6.5 **Frontend component test** that the console renders the flag.

## 7. Frontend: the status landing

- [ ] 7.1 Add `src/api/signingProgress.ts` -- `getSigningProgress(id)` returning the stage,
      `editable`, and per-party progress. No `fetch` in components.
- [ ] 7.2 Add `src/views/AgreementStatus.vue`: composes `getAgreement`, `getPaymentProgress`
      and `getSigningProgress`; renders the milestone timeline (Drafted, Paid, E-stamped,
      each party's signature, Completed) with done / current / not-reached states and the
      tracking reference.
- [ ] 7.3 Derive every milestone from server fields only. Payment shows settled only on
      `PAID`/`WAIVED`. Terminal outcomes (expired, rejected, stamp-failed) are stated, not
      shown as merely pending.
- [ ] 7.4 Offer the edit action from the server's `editable` flag alone; on choosing it, mount
      the existing `CaptureForm` in edit mode with the loaded agreement (design D7). Offer the
      signed-document download when signed, and the payment route when payment is outstanding.
- [ ] 7.5 Rework `App.vue`'s `openLink` route to mount the status view and **stop rewriting the
      URL to `/app`**, so the page reloads and bookmarks correctly. Keep the existing
      claimed-agreement message ("saved to an account, sign in") and its sign-in button.
- [ ] 7.6 Poll while the stage is non-terminal; stop on a terminal stage; pause while the tab is
      hidden. Clear the timer on unmount.
- [ ] 7.7 **Frontend component tests** for `AgreementStatus.vue`: each milestone condition;
      awaiting-stamp distinguishable from out-for-signature; per-party signature states; edit
      action shown when `editable` and withheld when not; signed-document download on signed;
      claimed-agreement sign-in message; no client-side payment optimism.
- [ ] 7.8 **Frontend test** for `App.vue` routing: `/agreement/<uuid>` mounts the status view,
      not `CaptureForm`, and the address bar still reads `/agreement/<uuid>` afterwards.
- [ ] 7.9 `npm run lint` and `npm run build`.

## 8. Documentation and close-out

- [ ] 8.1 Note in `docs/ROADMAP.md` that the terms freeze moved to stamping and that the
      anonymous edit surface widened, with a pointer to this change.
- [ ] 8.2 Record the deferred follow-ups so they are not lost: (a) notify all parties by email
      when terms change after the order is placed; (b) rename the two requirements whose
      headings were retained verbatim for delta matching, once `post-payment-continuity` and
      `manual-estamp-upload` are archived.
- [ ] 8.3 Manual test of the real flow: pay an agreement, open the emailed link, confirm the
      status page renders, edit before the stamp, upload a stamp as staff, confirm the edit is
      then refused and the page shows the signature milestones.
- [ ] 8.4 Run `openspec validate agreement-status-link-page --strict`.
