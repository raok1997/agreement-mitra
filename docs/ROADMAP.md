# AgreementMitra — Roadmap

Team-shared, git-tracked source of truth for where the product is and what's
next. Update this in a PR like any other doc. Per-feature intent lives in
`openspec/` (specs + archived changes); deep architecture in
`docs/ARCHITECTURE.md`; vendor specifics in `docs/integrations/`; legal exposure,
template-approval governance and the open questions for counsel in
`docs/LEGAL-POSTURE.md`.

_Last updated: 2026-09-11_

**What lives here:** only work that is **pending** — where we are, what's next, and
the follow-up register. **Completed work is removed from this file**, not moved to a
"done" list: `openspec/changes/archive/` is the authoritative, self-maintaining record
of what shipped, and `git log` says when and by whom. A hand-kept completion list is a
second source of truth that drifts silently — the previous one did, by 42 changes.
The test for a line staying here: *does it tell you something true about the system
today that the archive cannot?* Narrative about current behaviour stays; "we finished
X" does not.

## Where we are

The **signing vertical slice is complete end-to-end on a stubbed eSign
provider**. Hardening (security scanning, SAST, test harness, Spring Security
baseline) and the core signing flow are shipped and tested:

```
agreement -> draft PDF upload -> finalise (order placed) -> payment
          -> staff upload a purchased SHCIL e-stamp scan
          -> STAMPED -> SIGN_REQUESTED -> webhook (HMAC) / reconciliation
          -> signed-artifact storage
          -> signed agreement emailed to both parties + durable in-app copy
          -> agreement CLOSED
```

**The journey has an end.** On completion each party is emailed the signed
agreement as an attachment, a party-authenticated in-app copy stays retrievable
indefinitely, and the agreement reaches a terminal **fulfilment** state. Terminal
signing failures (`FAILED`, `EXPIRED`, `STAMP_FAILED`) close as **abandoned**, so
dead work leaves the staff queue instead of accumulating in it. Key properties:

- **Delivery goes only to signing-verified addresses** -- the address the
  invitation was issued to and at which that party completed signing. There is
  no draft-time fallback; an unresolvable party is escalated to staff, never
  guessed at.
- **Exactly once per recipient.** The completion path is re-entered by both the
  webhook and the reconciliation job, so each recipient's record is claimed by a
  guarded conditional update *before* any message is sent.
- **Delivery never blocks or rolls back completion**, and never touches the
  signing FSM -- `SIGNED` stays terminal. Closure is agreement-level fulfilment
  state alongside payment state.
- **The audit trail is never emailed**; it is retained and produced on request.
- Outbound mail goes through a vendor-neutral seam with **one SMTP adapter**
  (free Zoho Mail in dev, ZeptoMail in production) and a **stub active by
  default**, so no test or local run sends anything. See
  `docs/DOMAIN-AND-EMAIL-SETUP.md` section 5b -- including the ZeptoMail account
  review, which is a **2-3 business day lead-time item, not a switch**.

- FSM: `PDF_GENERATED → STAMPED → SIGN_REQUESTED → SIGNED | FAILED | EXPIRED`
  (terminal `STAMP_FAILED` branch off the stamp step).
- 131+ tests green (Testcontainers Postgres + MinIO + WireMock); all build
  gates passing (OSV deps, SpotBugs/FindSecBugs, JaCoCo, ModularityTests).
- Everything runs against a **WireMock/stub** Leegality provider — no live
  vendor credentials have been required to reach this point.

## Payment status — a gateway now exists

**Payment is no longer a missing dependency of the fulfilment pipeline.** The
payment gate (state `UNPAID`/`PAID`/`WAIVED`, enforced at e-stamp intake and eSign
initiation) previously had only two producers of a confirmation: a STAFF-recorded
manual payment and a waiver. `razorpay-payment` adds a real one - server-side
order creation with a server-computed amount, Razorpay Standard Checkout inside
the SPA, and confirmation by **verified webhook** (with a reconciliation job as the
fallback). The manual path is unchanged and still works: an out-of-band payment
must remain recordable.

Three facts to carry forward:

- **The gate ships `REQUIRED`.** This reverses the plan the change was written
  under. The product owner's call: "razorpay should be involved before estamping
  by staff". They were told the risk and accepted it - until a live payment has
  been observed settling, a payment bug hard-blocks **all** fulfilment rather than
  merely leaking unpaid work. The escape hatches are operational, not a redeploy:
  a STAFF **waiver** on one agreement (`POST /api/staff/payments/{id}/waive`) for
  money arriving out of band, and `PAYMENT_MODE=OPTIONAL` to relax the gate
  globally if the integration itself breaks. `OPTIONAL` remains a supported,
  separately tested mode - it is just no longer the default.
- **Live-mode verification is still owed.** Task S7.4 of `razorpay-payment` (one
  real payment settling end to end in live mode) has NOT been done, and the gate is
  now enforced regardless. That inverts the usual order of operations, so the first
  live payment is now on the critical path for every order, not just for a
  configuration decision. Watch the stamp queue closely on the first day.
- **The webhook is authoritative; the browser callback is not.** The value
  Checkout returns to the browser is a UX signal only. A customer who pays and
  closes the tab is marked `PAID` by the webhook, never by the callback.

Unpaid orders are deliberately **not filtered out** of the staff stamp queue - the
console shows them as "Awaiting payment" with the upload disabled. Hiding them
would turn "waiting on payment" into "vanished", which is harder to diagnose.

Everything runs against WireMock with fabricated secrets - **test mode only**, no
live Razorpay account exists in this repository. The agreed flat price is a
commercial decision and is a configuration value (`PAYMENT_AMOUNT_MINOR_UNITS`,
integer paise); it has not been set beyond a sandbox placeholder.

## Vendor status (eSign) — read before planning live work

**Decision: the eSign vendor is ZOOP (eSign v5).** See `docs/integrations/zoop.md`.
The adapter (`ZoopEsignProvider`) is implemented and is the **default** provider
(`esign.provider=zoop`); Leegality is retained behind the same `EsignProvider`
seam and selected by configuration, so a rollback is `ESIGN_PROVIDER=leegality`.

**Track B is no longer gated on a vendor account.** ZOOP developer/test access is
**free and self-serve** through their dashboard (a capped transaction count),
against `https://test.zoop.plus/contract/esign` - unlike Leegality's Basic Plan,
which is production-only. The one remaining live item is the **test-environment
tracer** (below), which needs only a self-serve signup, not a support request.

Commercially: Aadhaar eSign is **Rs.10/signature** on ZOOP's Lite tier against
Leegality's Rs.25/signatory - Rs.20 vs Rs.50 for a two-party agreement.

### Legacy: Leegality vendor status (retained adapter)

See `docs/integrations/leegality.md` for full detail. The load-bearing facts:

- **The Basic Plan runs on production only — it is NOT a sandbox.** Signing up
  for Basic does not give test/dummy-data access. A **dedicated developer
  sandbox account must be requested explicitly** (via support@leegality.com /
  the Enquiry Team).
- Repo policy is **sandbox + dummy data only**, so this codebase cannot point
  at the Basic Plan production endpoint. **All live e2e is blocked on the
  developer sandbox account.**
- Real digital stamping (auto-affix, 31 States/UTs) **is** a Leegality feature —
  so eventual real stamping is a swap behind our existing `StampProvider` seam,
  not a separate SHCIL/state-portal build.

## Strategy

Getting a sandbox or real account will take time. **We proceed with everything
that does not need live credentials (Track A) and swap the real integrations in
behind their seams (`EsignProvider`, `StampProvider`) when accounts arrive
(Track B).** The stub/WireMock provider keeps Track A fully testable.

## Track A — proceed now (no live credentials needed) — **PRIORITY**

1. **`signing-auth`** — ownership authZ + rate-limiting + redacted
   security-event logging on the currently-unauthenticated `/api/signing/*` and
   `/api/agreements/*/draft` endpoints. _Highest priority — these are open
   today._
2. **Frontend signing-flow** — no-login self-serve UI per
   `docs/PRODUCT-FEATURE-SET.md`. Surfaces two backend gaps to fill alongside:
   a **status endpoint** and a **signed-artifact fetch endpoint**.

## Track B — ZOOP test access is free and self-serve (no longer blocked)

3. **ZOOP test-environment tracer** — the one deferred manual-test item, and the
   acceptance gate for signature placement. Sign up for free staging access, set
   `ZOOP_APP_ID` / `ZOOP_API_KEY` from env, and run one real `/v5/init` against
   `https://test.zoop.plus/contract/esign` with two dummy Aadhaar signers,
   `SEQUENTIAL`, `send_invite: true`, on a stamped fixture document. Then
   **visually confirm both signatures land inside their intended signature
   zones** — ZOOP measures `x_coord` from the **right** edge while PDF's origin is
   bottom-left, and a mirroring mistake produces confidently wrong output with no
   error, so an assertion over our own arithmetic is not sufficient. Also confirm
   both invitation emails arrive, the 7-day expiry holds, and the webhook arrives
   carrying the `webhook-security-key` header. Record the outcome in
   `docs/integrations/zoop.md` section 5.
   _(The equivalent Leegality sandbox tracer remains blocked on a support-issued
   developer account; it is no longer on the critical path.)_
4. **`leegality-real-stamp`** — **SUPERSEDED** by `manual-estamp-upload`.
   Synthetic stamping is gone; staff now purchase a real SHCIL e-stamp
   out-of-band, scan it, and upload it through the STAFF-only intake endpoint,
   and signing refuses with `409 stamp-required` until one is attached. The
   `StampProvider` seam survives, so Leegality auto-affix (or a real SHCIL
   procurement API) is still a one-adapter swap if it ever becomes worthwhile.
   **Follow-up hardening, not yet scheduled:** SHCIL **online certificate
   verification**. Today staff *attest* to what they uploaded — nothing proves
   the scan matches the certificate number, or that the certificate is genuine.
   That is the single largest residual legal risk in the stamping flow.

## Deprioritized

- **CR-7 `ci-pipeline` (GitHub Actions)** — deprioritized for now. The default
  build goals already fail-close on the gates locally (`./gradlew check` runs
  OSV + SpotBugs + JaCoCo + ModularityTests; the frontend `build` chains
  `security:scan` + tests). CI remains the authoritative long-term home (see
  `docs/TECH_DEBT.md` TD-1) but is not blocking. Revisit before any
  production / real-PII deployment.

## Follow-up register (raised by changes, not yet scheduled)

The single list of follow-ups spun out of an OpenSpec change. Anything a change
identifies but deliberately does not fold in belongs here **before that change is
archived** — the `followUps` line in a change's `.flow-journal.md` moves into
`openspec/changes/archive/` with it and is not a durable record.

**Sorted by:** what unblocks work soonest — (1) blocking the repo *today*, (2) blocking the
first real customer, (3) everything else. Two clocks, deliberately interleaved; re-sort by
whichever one matters to you.

| Slug | Scope | Raised by | Date | Priority |
|---|---|---|---|---|
| `agreement-error-problem-type-plumbing` | `AgreementHttpError` carries the RFC 9457 problem `type` at only 1 of 6 throw sites, so the client cannot tell which 409 it got. Customers see the raw `Agreement request failed: 409` for a frozen-terms save and for an ineligible jurisdiction, where written copy exists but is unreachable. Wire the remaining five sites; carry the type on `PaymentHttpError` too. | `contacts-editable-until-payment` | 2026-09-10 | High — customer-visible today |
| `national-jurisdiction-city-unfilled` | `disputeClause`/`disputeAlternativeClause` sit in the **mandatory** witnesseth list gated only on `disputeResolution` (base default `courts`), so an operative exclusive-jurisdiction covenant renders on every deed -- but `jurisdictionCity` is `required: false` with **no national default** and lives in the *optional* `Dispute Resolution` section. TG patches the default to Hyderabad; nothing patches the base. So a deed generated without that optional section reads "...exclusive jurisdiction of the courts at **[ Jurisdiction city ]**". **This reaches Karnataka**, one of the two seeded stamp-duty states -- KA matches no state patch and resolves to the base alone. Pinned as a characterization test (`ProductionRentalLayerSetTest.theAlwaysOnJurisdictionCovenantIsUnfilledOutsideTelangana`), so it cannot regress silently. Likely fix is a fallback clause ("courts of competent jurisdiction") when no city is set -- a drafting decision, hence not folded in. | `rental-document-content-v2` | 2026-09-10 | High -- a placeholder in an operative clause of every Karnataka deed |
| `zeptomail-production-provisioning` | ZeptoMail is chosen for production sending but **no account exists**. Two external, sequential steps, neither startable from this repo: (a) submit the **Customer Validation form** — ZeptoMail enforces a transactional-only policy and reviews new accounts, typically **2–3 business days**; (b) publish **SPF + DKIM** for the sending domain against ZeptoMail and verify alignment before the first production send. Until both pass, `MAIL_PROVIDER=smtp` against a production host sends nothing (or sends unauthenticated mail that lands in spam). Descoped from `signed-delivery-and-closure` tasks 7.6/7.7: lead-time work on a third party, not code. | `signed-delivery-and-closure` | 2026-09-11 | High — blocks the first production send; 2–3 business days of external lead time |
| `terms-doc-ungated` | `npm run terms:doc` regenerates `docs/TERMS-OF-SERVICE.md`, customer-facing legal text — and **no gate invokes it**. `npm run build` does not, and the parity test (`termsOfService.test.ts:15-20`) reads the committed file in-process, so it passes with the generator completely broken. That is exactly how it broke unnoticed when vitest 4 dropped `vite-node`. Wire the generator (or a regenerate-and-diff check) into a gate so a broken generator fails something. | `frontend-dev-dep-refresh` | 2026-09-11 | Medium-high — a silent-failure path on a legal document |
| `zeptomail-bounce-webhook` | Wire ZeptoMail's bounce webhook into the delivery permanent-failure path, so `SENT` can mean "arrived" rather than "the provider accepted it". **The CR shipped calling this "additive"; it is not** — `EmailSender.send` returns `void` and `signed_document_delivery` holds no provider message id, so **no correlation key exists** to join a bounce back to a recipient row. Scope: a forward-only migration for the correlation key, a changed `EmailSender` signature (both adapters + `markSent`), an HMAC/secret-verified inbound endpoint, and a bounce payload contract **that cannot be established without a live ZeptoMail account** — so it is blocked on `zeptomail-production-provisioning`, and may force ZeptoMail's HTTP API over SMTP, reopening design D7's one-adapter choice. A new inbound webhook also inherits the rate-limit / verify-failure-logging gap tracked in `signing-auth`. Until it lands, treat delivery status as best-effort and rely on the durable in-app copy. | `signed-delivery-and-closure` | 2026-09-11 | High — blocks the first production send; a hard bounce is silent today |
| `signing-auth` | `POST /api/signing/*/request` is still `permitAll` with no ownership check, no rate limit, and no security-event logging — it egresses signer PII to the eSign vendor and can burn provider quota. Deferred in June because "no auth mechanism exists yet"; that blocker is gone (`google-oauth-login` + `agreement-ownership` shipped, and `GET /*/progress` already does service-level ownership authZ). `mobile-otp-auth` states in its proposal and D-note that it leaves this permit untouched — **nobody is holding it.** Scope: ownership authZ on create, rate limit on create + webhook, redacted security-event logging of webhook verify-failures / documentId enumeration. Fix the stale "unauthenticated today" javadoc on `SigningController` and `SecurityConfig` in the same CR. **Widened 2026-09-11 by `agreement-status-link-page`:** the rate limit should also cover the three anonymous, owner-scoped reads the status page now polls — `GET /api/agreements/{id}`, `/{id}/payment`, `/api/signing/{id}/progress` (~3 req/min per open in-flight tab; the client floor is a courtesy, not a control). | `create-signing-request` | 2026-06-21 | High — must land before the first real user (CLAUDE.md: sandbox + dummy data only) |
| `rental-deed-lease-vs-licence` | **Counsel ruling owed on the instrument type.** `rental-document-content-v2` removed the "(Leave & Licence)" label (a Maharashtra form) from the national subtitle and recital because it contradicted the rest of the deed -- but that was a *de-contradiction, not a ruling*. Still open: (a) is a residential tenancy in KA/TG properly a **lease**, and should the vocabulary move to **Lessor/Lessee** to match the sibling commercial set (customer-visible across form labels + frontend copy, so not a drift fix); (b) confirm the stamp basis follows from that -- **this is a dependency of `state-stamp-duty-quoting`**, which seeds Karnataka Stamp Act 1957 **Article 30, the lease article**, and an instrument assessed under the wrong article is under-stamped and inadmissible under s.35 Indian Stamp Act until duty + penalty is paid; (c) low-priority drafting nit, ask in the same pass: the recital sits *inside* `Now This Agreement Witnesseth` where Indian deeds conventionally place it above. `docs/COUNSEL-BRIEF.md` Part A holds the drafted questions (**unsent, no counsel engaged**); its Q1 is this one. | `rental-document-content-v2` | 2026-09-10 | High -- blocks the first real customer; blocks `state-stamp-duty-quoting` |
| `template-counsel-signoff-gate` | Nothing prevents an unreviewed authored template from shipping. Every agreement pins `templateContentHash` + `layerVersions` (`Agreement.pinEffectiveTemplate`), which makes "was this content reviewed?" answerable -- but no gate consults it. Wire counsel sign-off to the content hash so a template edited after review cannot generate a deed silently. Raised by the CR as "still owed before the first real customer". | `rental-document-content-v2` | 2026-09-10 | High -- blocks the first real customer |
| `prod-readiness-preflight` | **Nothing enumerates or enforces what must be true before the first production send / first real customer.** Today the answer is scattered across two archived `tasks.md` files, `docs/DOMAIN-AND-EMAIL-SETUP.md` §"Before production sending works", `docs/DEPLOYMENT.md` and register prose — and a gate that passes has nowhere to record that it passed, because register rows leave by deletion. Two mechanisms, one CR: **(a) `scripts/prod-preflight.sh`** for gates outside the JVM — SPF + DKIM published and aligned for the sending domain (`dig`, assert don't print), ZeptoMail account approved and the send token live, tunnel/callback URL publicly reachable for both webhook endpoints; **(b) fail-closed startup validation** under a new `prod` profile (no `application-prod.yml` exists yet) for gates the JVM can see — `MAIL_PROVIDER` not `stub`, `MAIL_FROM` set, production SMTP host explicitly configured rather than defaulted, every webhook secret non-blank, `ddl-auto: validate`. Same fail-closed idiom as `securityScan`, `MinioClient` and `RazorpaySignatures`. **A script alone is opt-in and therefore still a promise — the startup half is what makes a gate unforgettable, so do not drop it to ship the script sooner.** Also add a `## Production gates` section to this file (`Gate │ Enforced by │ Status │ Verified on │ Evidence`) so a passed gate keeps its evidence instead of being deleted, and an OpenSpec rule that a CR's production-readiness task may close only as implemented, converted to a named enforced gate, or descoped to a production-gate row — never to a plain follow-up. That rule is what would have caught `signed-delivery-and-closure` 7.4 at proposal time instead of at close-out. | `signed-delivery-and-closure` | 2026-09-11 | Medium — the gates it enforces are High and carry their own rows; this is the mechanism, and prod is still founding-team beta |
| `pii-lint-custom-rules` | Nothing automated enforces CLAUDE.md's two non-negotiables — never-log Aadhaar/OTP/VID/full signer PII, and verify-HMAC-before-acting. Stock FindSecBugs does not model them, and `.claude/hooks/pii-secret-guard.sh` is self-declared "defense-in-depth — a reminder, not the authoritative control" (local, evadable, does not run in any shared build). Needs custom SpotBugs detectors or an equivalent PII-lint wired into `securityScan`. | `backend-security-scanning` | 2026-06-20 | Medium — a non-negotiable rule with no gate behind it |
| `terms-correctable-until-stamping` | Let the owner correct terms after payment and before the stamp. **Explored and deliberately dropped from `agreement-status-link-page`** (its `.flow-journal.md` review rounds 1–2 and the pre-rescope `design.md` in git history are the prior art — read them first). The freeze move alone generated every finding across two review rounds, so the minimum viable shape is already known: draft regeneration **outside** the agreement row lock (render is a 30 s Gotenberg call; two transactions), a `draft_generation` counter (the blob key is deterministic, so a key compare cannot detect a superseded draft) checked under `findByIdForUpdate` on the `STAMPED` transition with a distinct audited 409, server-preserved party contacts (the edit form sends none and the contacts freeze at payment must hold), party notification via the existing draft delivery with corrected copy, a staff queue timestamp, `(state,type)` immutable post-order, attribution columns, and `MODIFIED` deltas for `payment-processing` and the `estamp-intake` console requirement. | `agreement-status-link-page` | 2026-09-11 | Medium — customer-visible gap, but today's behaviour; needs `zoop-aadhaar-esign` archived and a Gotenberg-backed test harness in four more classes |
| `zoop-callback-e2e-on-public-host` | `zoop-aadhaar-esign` task 8.5 — prove the real ZOOP callback end to end: the webhook arrives with the `webhook-security-key` header, the transaction completes to `SIGNED`, artifacts are stored, and (deliberately) the 5-minute reconciliation fallback advances a request whose callback was missed. The 2026-09-11 sandbox run signed the document but **no callback reached the app** because it ran on a local host ZOOP cannot reach; persisted state proved it (`SIGN_REQUESTED`, invitees `PENDING`, null artifact keys). Every piece the app controls is integration-tested (`ZoopSigningIntegrationTest` drives init → webhook → fetch → `SIGNED` + artifacts; `SigningProgressApiIntegrationTest` pins the read model after it); what is unproven is the *vendor's* delivery to *our* URL. To close: run on a publicly reachable host with `ZOOP_RESPONSE_URL` set **before** start (it is baked into `/init`), then check `signing_request`, not the inbox. Also closes `agreement-status-link-page`'s step 4 (signed-document download after a real signing). Overlaps the tunnel/callback gate in `prod-readiness-preflight`. | `zoop-aadhaar-esign` | 2026-09-11 | High — must pass before the first real signing; blocked on a public host, not on code |
| `claim-bound-to-initiator` | Claim is "first signed-in link holder wins": `Agreement` records no initiator (no creator field; `owner_identity_id` is null until claimed) and `Identity` is Google-email only, so any recipient of the emailed link who signs in and claims first owns the agreement and revokes the link for everyone else — including the drafter. Pre-existing (`agreement-management` D2), surfaced by `agreement-status-link-page`'s review, and untouched there because the fix needs an initiator contact captured at draft time plus a verified identity to match against (mobile once `mobile-otp-auth` lands). Related: claim has no state precondition, so a party can claim after `SIGNED` and lock the counterparty out of the signed-document route — gate claim on the pipeline not having ended, in the same CR. | `agreement-status-link-page` | 2026-09-11 | Medium — bounded today (one named claimant, the drafter sees "saved to an account" at once), but it decides who controls a paid legal document |
| `progress-read-hot-path` | `GET /api/signing/{id}/progress` and `GET /api/agreements/{id}/payment` are now polled every 20 s per open status tab. Per tick, progress builds a full `AgreementResponse` (two JSON columns decoded, lazy signers, a template-catalog lookup for `state`/`type` the caller discards) plus the `SigningRequest` aggregate with lazy invitees — ~5 queries for four scalars — and payment loads the same `Agreement` row twice (`isAccessibleBy` then `paymentState`). Pre-existing shapes; the status page is what put them on a loop. Fix: a narrow owner-scoped signers/status projection (`@EntityGraph` or JPQL) and one `paymentStateForReader` used by both checks. Measure before optimising — at founding-team scale this is noise. | `agreement-status-link-page` | 2026-09-11 | Low — cost scales with open tabs × 3/min; revisit before real customers |
| `agreement-status-detail` | "My agreements" collapses `PDF_GENERATED`/`STAMPED`/`SIGN_REQUESTED` into one "In progress" badge and omits `payment_state` entirely, so awaiting-payment, paid-awaiting-stamp and out-for-signature are indistinguishable. Separately, `@view` and `@edit` both call `openForEdit`, so "View/Download" opens an editable form on a frozen agreement whose only feedback is a raw 409. **`agreement-status-link-page` (2026-09-11) added `FulfilmentStage` + `terminal` + `signedDocumentReady` to `GET /api/signing/{id}/progress` — the list can reuse that projection rather than invent one; the `@view`/`@edit` conflation is untouched and is the remaining scope.** | `contacts-editable-until-payment` | 2026-09-10 | Medium |
| `rental-default-commercial-terms` | Three commercial terms are silently defaulted and always render: `lockInMonths` **6**, `rentEscalationPercent` **5**, `noticePeriodMonths` **1** (and `noticeClause` carries no `showWhen`, so it renders regardless). A customer who never opens those fields signs a six-month lock-in and 5% annual escalation they were never asked about. Decide whether these should be defaulted at all, surfaced explicitly in the capture form, or gated off when untouched. | `rental-document-content-v2` | 2026-09-10 | Medium -- customer signs terms they were not shown |
| `tg-governing-law-duplication` | A Telangana deed carries two choice-of-law clauses: the national `governingLawClause` ("laws of India") in the witnesseth list and the broader `tgGoverningLaw` ("laws of India **and** the tenancy laws applicable in Telangana, including the 1960 Act") in the now-mandatory statutory section. The second subsumes the first. **Do not fix by plain removal** -- that recreates the stamp-clause hole one clause over: revert the statutory flag to `optional: true` and a TG deed would have no governing-law clause at all. The safe shape is a `replaceClause` of `governingLawClause` with the TG text, keeping it in the always-on witnesseth list and dropping `tgGoverningLaw` from the statutory section. Reasoning is also inline in `state_type-TG-residential.patch.yaml`. | `rental-document-content-v2` | 2026-09-10 | Low -- redundant, not wrong |
| `frontend-coverage-gate` | The frontend has no coverage threshold. The backend fails `check` on a JaCoCo gate; `vitest` runs without `--coverage` and `vite.config.ts` declares no thresholds, so frontend coverage can regress to zero silently. Add a `vitest --coverage` threshold and chain it into `npm run build` alongside `security:scan`. | `frontend-test-harness` | 2026-06-20 | Low |
| `frontend-contract-test-msw` | No cross-stack contract test. Frontend API tests mock `fetch` by hand, so a backend DTO rename (e.g. the `SignSession` / signing-progress shape) compiles clean on both sides and fails only in the browser. Introduce MSW handlers generated from — or asserted against — the real backend response shape. | `frontend-test-harness` | 2026-06-20 | Low |
| `frontend-config-driven-test-teardown` | Set `restoreMocks`/`clearMocks` in `vite.config.ts` and retire the **47 hand-written `.mockReset()` calls across 9 test files** (`CaptureForm.test.ts` alone has 22, in a bare `beforeEach`). Config-driven teardown makes the next vitest major cheap instead of a 9-file audit. Cheaper now that the suite is on v4 semantics, so it is sequenced work rather than unrelated cleanup. | `frontend-dev-dep-refresh` | 2026-09-11 | Medium — pays for itself at the next runner bump |
| `frontend-engines-node-narrowing` | `frontend/package.json` declares `engines.node >= 20.19.0`, but vitest 4 supports `^20 \|\| ^22 \|\| >=24`. A developer on Node 21 or 23 satisfies ours and violates the runner's, with no warning until something breaks oddly. Narrowing `engines` affects every developer, so it was not folded into a build-gate fix. | `frontend-dev-dep-refresh` | 2026-09-11 | Low-medium — latent, environment-dependent |
| `frontend-vue-lint-warnings` | 26 `vue/html-indent` + `vue/html-closing-bracket-newline` **warnings** across four `.vue` views, from the Prettier vs `eslint-plugin-vue` stylistic overlap the config's existing off-block only partly covers. Advisory only — `eslint .` exits non-zero on errors, so they fail nothing — but a permanently noisy lint run is where a real new warning goes unnoticed. Extend the off-block or reformat. | `frontend-dev-dep-refresh` | 2026-09-11 | Low — cosmetic, but it normalises noise |
| `frontend-root-config-node-globals` | The Node-globals fix in `frontend-dev-dep-refresh` is scoped to `frontend/scripts/`, but `eslint .` also lints four Node programs at the frontend root — `eslint.config.js`, `vite.config.ts`, `postcss.config.js`, `tailwind.config.js` — which keep the inverted globals (`window` defined, `process` not). Adding ordinary `process.env` gating to `tailwind.config.js` would fail lint with no hint why. Pre-existing, not a regression, and widening it needs a spec-scope change rather than a config tweak — hence not folded in. Extend the scoped block's `files` to the root configs. | `frontend-dev-dep-refresh` | 2026-09-11 | Low-medium — a confusing false error waiting for whoever edits a root config |
| `anonymous-draft-retain-and-purge` | Unclaimed anonymous drafts accumulate forever. No retention window, no purge job — so PII-bearing draft rows from abandoned sessions are kept indefinitely, which is the wrong default for identity/legal infra. Deferred from `agreement-ownership` (CR-B); rescued from ROADMAP prose 2026-09-11 when the completion narrative was removed. | `agreement-ownership` | 2026-09-11 | Medium — data-retention exposure that grows with traffic |
| `session-store-not-durable` | The server-side session is **in-memory only**, so every restart or redeploy signs every logged-in user out, and it cannot survive more than one app instance. A cookie/persistent session store was deferred from `agreement-ownership` (CR-B); rescued from ROADMAP prose 2026-09-11. | `agreement-ownership` | 2026-09-11 | Medium — blocks horizontal scaling and makes deploys user-visible |
| `owner-scoped-artifact-download` | No owner-scoped surface for downloading a signed artifact — deferred from `agreement-ownership` (CR-B). Partly overtaken by `signed-delivery-and-closure`'s party-authenticated in-app copy; **confirm what remains before scheduling** rather than assuming it is still open. Rescued from ROADMAP prose 2026-09-11. | `agreement-ownership` | 2026-09-11 | Low — may be largely superseded; verify first |
| `capture-state-normalized-columns` | `capture_state` is stored as an opaque `jsonb` blob (migration `V13`). Sufficient to round-trip and render, but not queryable or constrainable per attribute. Deferred from `agreement-capture-persistence` (M5); rescued from ROADMAP prose 2026-09-11. | `agreement-capture-persistence` | 2026-09-11 | Low — deliberate trade-off, revisit only if querying is needed |
| `capture-state-write-time-validation` | The capture map is validated only at **render** time by the `documents` projection, not on write, so an invalid map can be persisted and fails later. Deferred from `agreement-capture-persistence` (M5); rescued from ROADMAP prose 2026-09-11. | `agreement-capture-persistence` | 2026-09-11 | Low-medium — moves a failure from write to render |

## Other queued non-goals (not scheduled)

**Terms-of-service acceptance checkpoint** — nothing today records that a user
agreed to the terms. `/terms` is linked (landing footer, `LegalDisclaimer` on
the capture/contact/payment screens) but a link is not assent: we store no
timestamp, no terms version, and no per-agreement record of what the customer
accepted. Clause 18 says "the version that applies to an agreement is the
version published when you paid for it", which we currently cannot evidence.
Needs deciding before the first external customer — a checkbox or
click-through at save/pay, persisting the accepted version (the template
fingerprint pattern already in `signing` is the model). Added 2026-09-08.


STAMP_FAILED orphan recovery (**re-scoped**: `PDF_GENERATED` is now a
**durable** state — a request rests there for however long staff take to buy
the e-stamp, potentially days — so it must NEVER be reaped as an orphan; only
the terminal `STAMP_FAILED` branch is a recovery candidate); multi-instance scheduler
distributed lock (ShedLock); prod object-store hardening (SSE +
block-public-access); `.docx` ingestion (LibreOffice-headless → PDF);
our-template generation (Chromium render); multi-state stamp templates;
`draft-revision-after-signing-request` paid supersede flow (see
`docs/future-features/`).
