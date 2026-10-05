# AgreementMitra — Roadmap

Team-shared, git-tracked source of truth for where the product is and what's
next. Update this in a PR like any other doc. Per-feature intent lives in
`openspec/` (specs + archived changes); deep architecture in
`docs/ARCHITECTURE.md`; vendor specifics in `docs/integrations/`; legal exposure,
template-approval governance and the open questions for counsel in
`docs/LEGAL-POSTURE.md`; the bring-your-own-document direction in
`docs/BYO-DOCUMENT-UPLOAD.md`.

_Last updated: 2026-10-05_

**What lives here:** only work that is **pending** — where we are, what's next, and
the follow-up register. **Completed work is removed from this file**, not moved to a
"done" list: `openspec/changes/archive/` is the authoritative, self-maintaining record
of what shipped, and `git log` says when and by whom. A hand-kept completion list is a
second source of truth that drifts silently — the previous one did, by 42 changes.
The test for a line staying here: *does it tell you something true about the system
today that the archive cannot?* Narrative about current behaviour stays; "we finished
X" does not.

## First release — paid Telangana + Karnataka orders from real customers

Decided 2026-10-05. This section only **sequences** work: the detail lives in the register
row or OpenSpec change each slug names. Delete a line when its item ships, and delete the
section when the release goes out. Founding-team beta ends with this release.

**Counsel (engaged, review in progress).** `tg-stamp-duty-counsel-review`,
`ka-stamp-duty-counsel-review`, `rental-deed-lease-vs-licence`,
`tos-below-duty-stamp-choice`, `term-partial-month-rounding` (how a state counts a part-month),
and the ToS gaps marked for counsel (§8, §11, §12, §15 DPDP notice, §16 liability, §17
disputes). The ToS status banner ("a draft … pending review") cannot stay up for real
customers. "Reviewed by Indian counsel" may appear in copy only once
`template-counsel-signoff-gate` records the review (`docs/LEGAL-POSTURE.md`). The disclaimer's
"we wrote the template and we stand behind it" (user decision 2026-10-05) does not reach real
customers until counsel has reviewed it against §16 (`docs/COUNSEL-BRIEF.md` Q6(d)(i)) — in all
three carriers: `LegalDisclaimer.vue`, the `documents.footer.screen-notice` default, and any
`DOCUMENT_FOOTER_SCREEN_NOTICE` override. Not yet written, each needing new legal text that
names KAVISAT TEK LABS LLP: a **Privacy Policy** (the LLP is the data fiduciary under the DPDP
Act), a **Refund/Cancellation policy** and a **Contact** page. Also ask: may ToS §1 name the LLP
as the contracting party while its LLPIN is still being issued (the site says "LLPIN: being
issued" until then)?

**Template sign-off checklist (manual; v1 form of `template-counsel-signoff-gate`).** No code
enforces template review for this release (decided 2026-10-05); this checklist does, and the
release does not go out until every box is ticked:
- [ ] Counsel reviews the rendered PDF of each template the picker offers — TG and KA
      residential (commercial is hidden for v1) — **including** the hard-coded execution paragraph ("IN
      WITNESS WHEREOF … Aadhaar eSign", `TemplateCompiler.java`) and the footer screen notice,
      neither of which lives in the template files.
- [ ] Record here the reviewer, the date and the **git commit** the PDFs were rendered from.
- [ ] At release, no template edit has landed since that commit — this must print nothing:
      `git log <commit>..HEAD -- backend/src/main/resources/documents/template/sets backend/src/main/java/in/agreementmitra/documents/template/TemplateCompiler.java`
      plus `documents.footer.screen-notice` in `application.yml` and any
      `DOCUMENT_FOOTER_SCREEN_NOTICE` override unchanged. Anything printed goes back to counsel.

**Ops / config: switch test to live, then observe one live run of each.** Razorpay
(payment status above), `zeptomail-production-provisioning`,
`zoop-callback-e2e-on-public-host`, `prod-db-url-log-server-error-detail`,
`prod-minio-image-pin`. When the LLPIN / registered office / GSTIN are issued, set them in
**all three places** — `deploy/env/backend.env` (`OPERATOR_LLPIN`, `OPERATOR_GSTIN`),
`deploy/env/web-build.env` (`VITE_OPERATOR_LLPIN`, `VITE_OPERATOR_REGISTERED_OFFICE`) and the
Cloudflare Pages variables of the same names — then rebuild `caddy` and Pages and restart the
backend. A missed place shows "being issued", never a wrong value; a malformed value refuses
startup / fails the build.

**Ops / config: stop charging on unreviewed rules.** Commercial is kept off paid fulfilment by
its rules carrying no counsel review, which holds only while unreviewed rules are disallowed;
prod beta runs with them allowed. So, in this order:
- [ ] Before the flip, list commercial agreements that are paid or have an open checkout, and
      cancel/refund them (expected none — founding team only, but checked). A paid agreement
      passes fulfilment on its frozen quote, and a checkout opened under `true` can settle after
      the flip.
- [ ] Once the residential rules carry their counsel reviews, set
      `RULES_STAMP_DUTY_ALLOW_UNREVIEWED=false` in `deploy/env/backend.env`. A state whose
      residential rule is still unreviewed becomes unpayable: a late KA review means a TG-only
      release, **not** keeping `true`.
- [ ] Verify on the running backend: the startup WARN "allow-unreviewed=true: customers may be
      charged on UNREVIEWED stamp duty" is absent.

**Code: one OpenSpec change each, in this order. Changes on the same line can run in
parallel.**

**v1 is residential only (decided 2026-10-05).** Commercial templates are hidden for the
release, which takes `tg-commercial-statutory-section-optional` and the commercial half of
`ka-stamp-duty-counsel-review` (the uncapped commercial rate) off the critical path. The rows
stay on the register for the release that brings commercial back.

1. `terms-release-revision` (new; §1 already names KAVISAT
   TEK LABS LLP as the contracting party, and "residential" there is correct for v1):
   §2/§5 (live status), §7 (price basis),
   and §8 (SHCIL vs Telangana), with counsel answers folded in as they arrive. §5's "Today
   that is Telangana" must agree with the home-page status board (`src/content/releaseStatus.ts`,
   TG stamping "In integration" today), and its national-template sentence is stale (the picker
   hides `IN` templates) ·
   `legal-policy-pages` (new): the Privacy Policy,
   Refund/Cancellation and Contact pages named in the counsel paragraph above. Generate them
   from the same data-module pattern as the terms, so refund wording is ToS §11 rather than a
   second copy, and the privacy text renders as a marked counsel gap until §15 is answered.
2. Terms-acceptance checkpoint (under "Other queued non-goals"; after
   `terms-release-revision`, because it records which version was accepted) ·
   `rental-default-commercial-terms` together with `stamp-quote-capture-defaults` (same root;
   needs a product decision first)
3. Small fixes as direct commits, any time: `capture-required-fields-drift` (product to confirm
   which side is right)

**Triggers this release reaches: decide before launch, do or explicitly defer.**
CR-7 `ci-pipeline` and the OSV build-tool scope ("revisit before any production / real-PII
deployment"), `anonymous-draft-retain-and-purge`, `spa-content-security-policy`.

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
  _2026-10-05: the live Razorpay account is reported ready. What remains is the
  production config switch from test to live keys, then one real payment observed
  settling via the webhook._
- **The webhook is authoritative; the browser callback is not.** The value
  Checkout returns to the browser is a UX signal only. A customer who pays and
  closes the tab is marked `PAID` by the webhook, never by the callback.

Unpaid orders are deliberately **not filtered out** of the staff stamp queue - the
console shows them as "Awaiting payment" with the upload disabled. Hiding them
would turn "waiting on payment" into "vanished", which is harder to diagnose.

Everything runs against WireMock with fabricated secrets - **test mode only**, no
live Razorpay account exists in this repository. The price follows the published terms
(section 7): INR 499 plus the amount by which the customer's chosen stamp value exceeds INR 100
(`PAYMENT_FEE_BASE_MINOR_UNITS`, `PAYMENT_FEE_INCLUDED_STAMP_VALUE_MINOR_UNITS`, integer paise).
The stamp value comes from the Telangana stamp duty rules, whose figures are UNVERIFIED: a
deployment can charge on them only with `RULES_STAMP_DUTY_ALLOW_UNREVIEWED=true`, which logs a WARN.

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

1. **Frontend signing-flow** — no-login self-serve UI per
   `docs/PRODUCT-FEATURE-SET.md`. Surfaces two backend gaps to fill alongside:
   a **status endpoint** and a **signed-artifact fetch endpoint**.
2. **Bring-your-own document** — let the customer upload their own PDF instead of
   picking a template, then carry it through the existing stamp + eSign flow.
   Direction agreed 2026-09-12 and **not yet proposed**; the reasoning, the
   rejected alternatives and the mock screens are in
   `docs/BYO-DOCUMENT-UPLOAD.md`. Two changes in dependency order, after
   `anonymous-upload-byte-budget` (the draft route is now rate limited per source
   and per agreement, but has no global byte budget, and a public upload UI widens
   that):
   - **`byo-document-upload`** — BYO end to end, deliberately **block-only**
     (signatures on the appended page, none on the customer's pages). Carries
     the instrument-type declaration and the ToS delta; neither may be split out.
   - **`byo-every-page-signatures`** — detect whether every page's footer band is
     free of text, decide once per document, and disclose the outcome with a
     remedy. Needs the one above.

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
| `national-jurisdiction-city-unfilled` | `disputeClause`/`disputeAlternativeClause` sit in the **mandatory** witnesseth list gated only on `disputeResolution` (base default `courts`), so an operative exclusive-jurisdiction covenant renders on every deed -- but `jurisdictionCity` is `required: false` with **no national default** and lives in the *optional* `Dispute Resolution` section. TG patches the default to Hyderabad; nothing patches the base. So a deed generated without that optional section reads "...exclusive jurisdiction of the courts at **[ Jurisdiction city ]**". **Corrected 2026-09-18:** this used to reach Karnataka, which matched no state patch; `ka-rental-and-commercial-templates` gave KA its own layers defaulting the city to Bengaluru, so the hole is now confined to the **national (`IN`) deed** and to any future state shipped without a layer. Pinned as a characterization test (`ProductionRentalLayerSetTest.theAlwaysOnJurisdictionCovenantIsUnfilledOnlyOnTheNationalDeed`), which now asserts TG and KA name a court, so the scope cannot widen again silently. Likely fix is a fallback clause ("courts of competent jurisdiction") when no city is set -- a drafting decision, hence not folded in. | `rental-document-content-v2` | 2026-09-10 | Medium -- a placeholder in an operative clause of every national deed |
| `draft-upload-clears-template-pin` | Uploading a draft (`POST /api/agreements/{id}/draft`) after generating one replaces the draft but **keeps** `template_content_hash` / `template_layer_versions`, so the agreement claims to be a reproducible render of a template it no longer contains. `stamp-duty-amount-from-certificate` stopped trusting the pin (it keys on `draft_execution_date`, which every store clears) but did not clear it. Clear the pin in `DraftService.attachDraft` for uploads, and check the other pin readers (status page, `AgreementResponse`). Relevant to BYO upload. | `stamp-duty-amount-from-certificate` | 2026-09-17 | Low-medium — misleading integrity metadata, no deed impact today |
| `document-stamping-spec-header-drift` | The `document-stamping` living spec still requires "a per-page header carrying the certificate number" on every draft page, which `PdfStampComposer` deliberately removed (its javadoc explains why). Code and spec of record disagree; amend the requirement via a small spec-only change. | `stamp-duty-amount-from-certificate` | 2026-09-17 | Low — documentation drift, but it mis-specs any future stamping change |
| `tg-stamp-duty-counsel-review` | **The Telangana stamp duty rules are charged on UNVERIFIED figures** (`rules/stamp-duty/TG/*.yaml`, `rules/stamp-paper/TG.yaml`). Counsel must confirm, against primary sources: the Art. 31 rates (0.4% vs 0.5% under one year is an open conflict), whether a refundable security deposit enters the consideration (included conservatively), the Government Order and date for the current rates, the INR 50 counterpart duty (G.O.Ms.120/2015), the paise-vs-rupee rounding practice, the 5-paper limit, whether every lease is compulsorily registrable in Telangana (the registration notice every customer now sees), and the under-stamp warning text (`under-stamp-v1`). Then record `counselReview.contentHash` in each file and stop setting `RULES_STAMP_DUTY_ALLOW_UNREVIEWED`. Also (`landing-page-release-copy`): does home-page guarantee 1 / ToS §8 ("wrongly denominated because we got it wrong") reach a TG deed that is under-stamped on the ₹100-only offer after the customer acknowledged the warning? | `state-stamp-duty-quoting` | 2026-09-16 | High -- blocks charging real customers |
| `tos-below-duty-stamp-choice` | TERMS-OF-SERVICE section 7 still says the total is based on "the stamp duty" and that duty automation is "still being built". The shipped rule prices the **chosen stamp value**, and a customer may choose a value below the duty after acknowledging the under-stamping consequence. Section 7 needs counsel wording for both, and `TermsOfService.vue` must be regenerated from it (jurisdiction-eligibility spec). Deliberately not edited by the implementing change: it is published legal text. The home-page price card (`landing-page-release-copy`) deliberately does not say "₹499 plus only the difference" — true in KA, never in TG, where only a ₹100 paper is offered — and says instead that the stamp amount and exact total are shown before payment, with no second bill; when §7 is reworded, the card and FAQ 3 must follow (`promises.test.ts` pins the §7 sentence). | `state-stamp-duty-quoting` | 2026-09-16 | High -- the terms describe a different price basis |
| `stamp-certificate-price-reconciliation` | LEGAL-POSTURE step 3's remaining half, narrowed 2026-10-05. Upfront quoting freezes the chosen stamp value with the order and intake refuses a certificate below it, so the customer can no longer be over-collected and **no Razorpay refund path is needed**. What remains: a certificate staff buy **above** the frozen value (denomination out of stock, paper plus challan, staff error) is absorbed silently -- nothing records what the stamp actually cost us. **Recommended:** persist the certificate's duty amount against the frozen value per order so ops/accounting can see overspend; pick it up with `stamp-paper-plus-challan-plan` or `stamp-inventory-aware-planning`, whichever lands first. Refunds for a failed or cancelled order are ToS §11, not this row. | `state-stamp-duty-quoting` | 2026-09-16 | Low -- internal bookkeeping; no customer is billed twice or over-charged |
| `ka-stamp-duty-counsel-review` | **The Karnataka stamp duty rules are seeded on UNVERIFIED figures** (`rules/stamp-duty/KA/*.yaml`, `rules/stamp-paper/KA.yaml`), so Karnataka is draft-only until counsel signs off. First question, before any arithmetic: **did the Karnataka Stamp (Amendment) Act 2023 (notified 2024-02-03) move Art. 30?** The rates were transcribed from the IGR Karnataka schedule copy, which is self-dated "Updated till 20th April, 2017", and secondary sites disagree with it and with each other. Then confirm: the 0.5%/1%/2% slab rates and the INR 500 residential cap; whether "average annual rent" annualises a **sub-year** term (an 11-month lease is charged on 12 months' rent as implemented -- capped for residential, uncapped for commercial); whether the Art. 30 Explanation defining "money advanced" reaches clause (1) (deposit included conservatively); the Art. 22 counterpart duty, modelled flat at INR 500 as a deliberate over-reading; the 12-month registration threshold (unamended Registration Act s.17(1)(d)); and the physical stamp paper denominations. If the threshold proves to be the national 11 (registration at twelve months or more, the ordinary published position), the clause this product ships in `sets/rental/state-KA.patch.yaml` -- "where the term exceeds twelve (12) months" -- is wrong too and must change in the same pass; better still, make the clause read the threshold from the rule so the two cannot drift apart again. Then record `counselReview.contentHash` in each file. | `ka-rental-and-commercial-templates` | 2026-09-18 | High -- blocks charging Karnataka customers |
| `tg-commercial-statutory-section-optional` | The Telangana **commercial** `state_type` layer removes the national `stampRegistrationClause` while its `Statutory (Telangana)` section is still `optional: true`, so a default Telangana commercial deed renders with **no** stamp or registration clause at all. This is the same defect the residential set fixed on 2026-09-07 by making its section mandatory; the commercial set was not fixed with it. Karnataka's two sets both ship mandatory and are not affected. Fix is one flag plus a render assertion -- not folded in because it changes an existing Telangana deed's content and wants the same version-bump reasoning the residential flip got. | `ka-rental-and-commercial-templates` | 2026-09-18 | High -- a Telangana commercial deed today has no stamp clause |
| `national-template-property-state` | An agreement drafted from the national (`IN`) template has no duty jurisdiction, so it is draft-only. Letting the customer name the property's state at the stamp step (fixed once an order exists) would admit it to paid fulfilment for that state's rules. | `state-stamp-duty-quoting` | 2026-09-16 | Medium |
| `stamp-paper-plus-challan-plan` | The TG catalog plans stamp paper **or** challan, not the real practice of a stamp paper plus a challan for the balance. The recommended option is correct in value but its medium label can differ from what staff buy. Needs a combined medium in the planner. Also: what do staff actually buy for TG today, given SHCIL e-stamp does not list Telangana? (ops question) Related: `JurisdictionEligibility.refuseUnlessPayable` refuses an `UNPLANNABLE` quote with the `jurisdiction-unsupported` type, so a finalise/checkout race on an unplannable duty is worded as a jurisdiction refusal (the stamp step itself words it neutrally since `agreement-error-problem-type-plumbing`). A distinct problem type would fix it; it is near-unreachable while challan covers any amount. | `state-stamp-duty-quoting` | 2026-09-16 | Medium |
| `stamp-quote-capture-defaults` | `DutyBasisMapper` reads `rentEscalationPercent` from capture state only. When the customer never touched the field, the deed renders the template default (5%) but the quote uses 0%, which under-quotes terms of 24+ months. Resolve the effective value through the documents projection, or close it with `rental-default-commercial-terms`. | `state-stamp-duty-quoting` | 2026-09-16 | Medium -- under-quotes long escalating terms |
| `capture-required-fields-drift` | The TG template form marks owner/tenant father's name and current address **optional** (`ownerFatherName`/`ownerAddress`/`tenantFatherName`/`tenantAddress`), so the capture form shows "6 of 6 required sections ready" — but `CreateAgreementRequest` validation 400s the save ("Current address is required. Father's name is required.") without naming the party or section. Same fact in two places. Found in the `agreement-error-problem-type-plumbing` browser run; it also means an edit that blanks those fields shows the generic "Could not save" rather than field messages. **Recommended:** if the fields are genuinely mandatory (they name parties on the stamp vendor's form), mark them `required: true` in the template layer so the form agrees; otherwise relax the server rule. Product to confirm which. | `agreement-error-problem-type-plumbing` | 2026-10-04 | Medium — every first-time customer meets it |
| `term-partial-month-rounding` | The tenancy term is a **truncated** whole-month count (end date inclusive since 2026-10-03), and that same number drives the duty slab, the rent total and `requiredWhenTermMonthsOver`. So a tenancy a few days past a threshold is classified below it: 1 Jan 2026 to 15 Jan 2027 counts as 12 months, so Karnataka (registration over 12) reports **no registration required** for a term that legally exceeds twelve months, and the duty is computed on 12 months' rent. Likely fix: the `rules` module rounds a partial month **up** for threshold and slab tests (a `DutyBasis` carrying the dates, or a separate "months, rounded up" figure) while the deed keeps stating whole months. Needs counsel on how each state counts a part-month before choosing. Same root, other end: a lawful sub-month tenancy (1 to 20 Jan) truncates to 0, so the deed states "a term of 0 month(s)" -- the same decision should say what a term under a month reads as. | duration-count bug fix (direct, `fix/new-fixes`) | 2026-10-03 | Medium -- under-classifies terms just past a threshold |
| `stamp-inventory-aware-planning` | Plans use the denominations the state issues, not what the stamp vendor has in stock. Pass a stock-narrowed medium from `StampProvider` once a vendor inventory API exists. | `state-stamp-duty-quoting` | 2026-09-16 | Low |
| `lease-registration-workflow` | Quotes now tell customers that registration may be required (every lease, per the researched TG law). Nothing helps them do it: no Sub-Registrar booking, and no registration fee (0.2% in TG, from 2021-09-02) in the quote. | `state-stamp-duty-quoting` | 2026-09-16 | Medium |
| `zeptomail-production-provisioning` | ZeptoMail is chosen for production sending but **no account exists**. Two external, sequential steps, neither startable from this repo: (a) submit the **Customer Validation form** — ZeptoMail enforces a transactional-only policy and reviews new accounts, typically **2–3 business days**; (b) publish **SPF + DKIM** for the sending domain against ZeptoMail and verify alignment before the first production send. Until both pass, `MAIL_PROVIDER=smtp` against a production host sends nothing (or sends unauthenticated mail that lands in spam). Descoped from `signed-delivery-and-closure` tasks 7.6/7.7: lead-time work on a third party, not code. **2026-10-05: product owner reports the external steps complete; what remains is switching the production config from test to live** (`MAIL_PROVIDER=smtp`, the ZeptoMail host and token) and one real send that lands in an inbox, not spam. | `signed-delivery-and-closure` | 2026-09-11 | High — blocks the first production send; now a config switch, no lead time |
| `terms-doc-ungated` | `npm run terms:doc` regenerates `docs/TERMS-OF-SERVICE.md`, customer-facing legal text — and **no gate invokes it**. `npm run build` does not, and the parity test (`termsOfService.test.ts:15-20`) reads the committed file in-process, so it passes with the generator completely broken. That is exactly how it broke unnoticed when vitest 4 dropped `vite-node`. Wire the generator (or a regenerate-and-diff check) into a gate so a broken generator fails something. | `frontend-dev-dep-refresh` | 2026-09-11 | Medium-high — a silent-failure path on a legal document |
| `zeptomail-bounce-webhook` | Wire ZeptoMail's bounce webhook into the delivery permanent-failure path, so `SENT` can mean "arrived" rather than "the provider accepted it". **The CR shipped calling this "additive"; it is not** — `EmailSender.send` returns `void` and `signed_document_delivery` holds no provider message id, so **no correlation key exists** to join a bounce back to a recipient row. Scope: a forward-only migration for the correlation key, a changed `EmailSender` signature (both adapters + `markSent`), an HMAC/secret-verified inbound endpoint, and a bounce payload contract **that cannot be established without a live ZeptoMail account** — so it is blocked on `zeptomail-production-provisioning`, and may force ZeptoMail's HTTP API over SMTP, reopening design D7's one-adapter choice. A new inbound webhook must follow the existing webhooks' pattern from `anonymous-surface-abuse-controls`: listed as excluded in `RouteClassifier` (HMAC is its control) and emitting `SecurityEvents.webhookVerificationFailed` on a failed verification. Until it lands, treat delivery status as best-effort and rely on the durable in-app copy. | `signed-delivery-and-closure` | 2026-09-11 | High — blocks the first production send; a hard bounce is silent today |
| `rental-deed-lease-vs-licence` | **Counsel ruling owed on the instrument type.** `rental-document-content-v2` removed the "(Leave & Licence)" label (a Maharashtra form) from the national subtitle and recital because it contradicted the rest of the deed -- but that was a *de-contradiction, not a ruling*. Still open: (a) is a residential tenancy in KA/TG properly a **lease**, and should the vocabulary move to **Lessor/Lessee** to match the sibling commercial set (customer-visible across form labels + frontend copy, so not a drift fix); (b) confirm the stamp basis follows from that -- **this is a dependency of `state-stamp-duty-quoting`**, which seeds Karnataka Stamp Act 1957 **Article 30, the lease article**, and an instrument assessed under the wrong article is under-stamped and inadmissible under s.35 Indian Stamp Act until duty + penalty is paid; (c) low-priority drafting nit, ask in the same pass: the recital sits *inside* `Now This Agreement Witnesseth` where Indian deeds conventionally place it above. `docs/COUNSEL-BRIEF.md` Part A holds the drafted questions (**unsent, no counsel engaged**); its Q1 is this one. Same pass (`landing-page-release-copy`): does the IT Act 2000 First Schedule exclusion of instruments transferring an interest in immovable property reach an Aadhaar-eSigned lease? The home-page FAQ dropped its unconditional "Yes" pending this. | `rental-document-content-v2` | 2026-09-10 | High -- blocks the first real customer; blocks `state-stamp-duty-quoting` |
| `template-counsel-signoff-gate` | Nothing prevents an unreviewed authored template from shipping. Every agreement pins `templateContentHash` + `layerVersions` (`Agreement.pinEffectiveTemplate`), which makes "was this content reviewed?" answerable -- but no gate consults it. Wire counsel sign-off to the content hash so a template edited after review cannot generate a deed silently. Raised by the CR as "still owed before the first real customer". **2026-10-05: deferred past v1** -- the first release is covered by the manual "Template sign-off checklist" above, and no code is added for it now. Grounding for the automated version (done 2026-10-05, not yet proposed): the 2026-09-07 placement in `docs/LEGAL-POSTURE.md` item 1 (a Gradle task hashing raw YAML) cannot work, because agreements pin the canonical *effective-model* hash computed inside package-private `TemplateResolver`/`CanonicalJson`, so the build-time half must be a test inside `documents.template`; the runtime half belongs beside `jurisdiction.require` at finalise and checkout (drafting untouched), with an `allow-unreviewed` flag plumbed like the stamp-duty one; approvals live in a separate file (the template `meta` is inside the hash); `POST /{id}/draft` keeps a stale pin today. | `rental-document-content-v2` | 2026-09-10 | Medium -- manual checklist covers v1; the risk it leaves is a template edit slipping past the checklist after release |
| `prod-readiness-preflight` | **Nothing enumerates or enforces what must be true before the first production send / first real customer.** Today the answer is scattered across two archived `tasks.md` files, `docs/DOMAIN-AND-EMAIL-SETUP.md` §"Before production sending works", `docs/DEPLOYMENT.md` and register prose — and a gate that passes has nowhere to record that it passed, because register rows leave by deletion. Two mechanisms, one CR: **(a) `scripts/prod-preflight.sh`** for gates outside the JVM — SPF + DKIM published and aligned for the sending domain (`dig`, assert don't print), ZeptoMail account approved and the send token live, tunnel/callback URL publicly reachable for both webhook endpoints; **(b) fail-closed startup validation** under a new `prod` profile (no `application-prod.yml` exists yet) for gates the JVM can see — `MAIL_PROVIDER` not `stub`, `MAIL_FROM` set, production SMTP host explicitly configured rather than defaulted, every webhook secret non-blank, `ddl-auto: validate`. Same fail-closed idiom as `securityScan`, `MinioClient` and `RazorpaySignatures`. **A script alone is opt-in and therefore still a promise — the startup half is what makes a gate unforgettable, so do not drop it to ship the script sooner.** Also add a `## Production gates` section to this file (`Gate │ Enforced by │ Status │ Verified on │ Evidence`) so a passed gate keeps its evidence instead of being deleted, and an OpenSpec rule that a CR's production-readiness task may close only as implemented, converted to a named enforced gate, or descoped to a production-gate row — never to a plain follow-up. That rule is what would have caught `signed-delivery-and-closure` 7.4 at proposal time instead of at close-out. **Carried in from `anonymous-surface-abuse-controls` (2026-10-04)** -- three edge gates that change could not close because they live in the Cloudflare dashboard and in production, each with its verification step already written in `docs/DEPLOYMENT.md` §5.6: (1) Cloudflare **Bot Fight Mode** on, with the webhook Skip rule covering both `/api/webhooks/esign` and `/api/webhooks/razorpay`; (2) a Cloudflare **rate-limiting rule** on `POST /api/*` excluding `/api/webhooks/*` (check the free-plan quota first; Pseudo IPv4 stays Off); (3) the **outside-in forged-header check** -- send a forged `X-Forwarded-For` and `Forwarded` naming another /24, trip a cheap lockout, and confirm the logged `source=` is the tester's /24, not the forged one, a Cloudflare range or the Docker gateway. Until they are verified the application-layer limits stand alone, which they are designed to do. **Carried in from `prod-minio-image-pin` (2026-10-05)** -- the MinIO image gate in `docs/DEPLOYMENT.md` §4 (record the release before and after, never pull, one real storage write) is manual until it gets a production-gate row; once that row's pin lands, the gate reduces to "the running release equals the pin". | `signed-delivery-and-closure` | 2026-09-11 | Medium — the gates it enforces are High and carry their own rows; this is the mechanism, and prod is still founding-team beta |
| `pii-lint-custom-rules` | Nothing automated enforces CLAUDE.md's two non-negotiables — never-log Aadhaar/OTP/VID/full signer PII, and verify-HMAC-before-acting. Stock FindSecBugs does not model them, and `.claude/hooks/pii-secret-guard.sh` is self-declared "defense-in-depth — a reminder, not the authoritative control" (local, evadable, does not run in any shared build). Needs custom SpotBugs detectors or an equivalent PII-lint wired into `securityScan`. | `backend-security-scanning` | 2026-06-20 | Medium — a non-negotiable rule with no gate behind it |
| `terms-correctable-until-stamping` | Let the owner correct terms after payment and before the stamp. **Explored and deliberately dropped from `agreement-status-link-page`** (its `.flow-journal.md` review rounds 1–2 and the pre-rescope `design.md` in git history are the prior art — read them first). The freeze move alone generated every finding across two review rounds, so the minimum viable shape is already known: draft regeneration **outside** the agreement row lock (render is a 30 s Gotenberg call; two transactions), a `draft_generation` counter (the blob key is deterministic, so a key compare cannot detect a superseded draft) checked under `findByIdForUpdate` on the `STAMPED` transition with a distinct audited 409, server-preserved party contacts (the edit form sends none and the contacts freeze at payment must hold), party notification via the existing draft delivery with corrected copy, a staff queue timestamp, `(state,type)` immutable post-order, attribution columns, and `MODIFIED` deltas for `payment-processing` and the `estamp-intake` console requirement. | `agreement-status-link-page` | 2026-09-11 | Medium — customer-visible gap, but today's behaviour; needs `zoop-aadhaar-esign` archived and a Gotenberg-backed test harness in four more classes |
| `zoop-callback-e2e-on-public-host` | `zoop-aadhaar-esign` task 8.5 — prove the real ZOOP callback end to end: the webhook arrives with the `webhook-security-key` header, the transaction completes to `SIGNED`, artifacts are stored, and (deliberately) the 5-minute reconciliation fallback advances a request whose callback was missed. The 2026-09-11 sandbox run signed the document but **no callback reached the app** because it ran on a local host ZOOP cannot reach; persisted state proved it (`SIGN_REQUESTED`, invitees `PENDING`, null artifact keys). Every piece the app controls is integration-tested (`ZoopSigningIntegrationTest` drives init → webhook → fetch → `SIGNED` + artifacts; `SigningProgressApiIntegrationTest` pins the read model after it); what is unproven is the *vendor's* delivery to *our* URL. To close: run on a publicly reachable host with `ZOOP_RESPONSE_URL` set **before** start (it is baked into `/init`), then check `signing_request`, not the inbox. Also closes `agreement-status-link-page`'s step 4 (signed-document download after a real signing). Overlaps the tunnel/callback gate in `prod-readiness-preflight`. **2026-10-05:** the ZOOP production account is reported ready, so this now runs on the production host as soon as the eSign config is switched to live. | `zoop-aadhaar-esign` | 2026-09-11 | High — must pass before the first real signing; blocked on a public host, not on code |
| `claim-bound-to-initiator` | Claim is "first signed-in link holder wins": `Agreement` records no initiator (no creator field; `owner_identity_id` is null until claimed) and `Identity` is Google-email only, so any recipient of the emailed link who signs in and claims first owns the agreement and revokes the link for everyone else — including the drafter. Pre-existing (`agreement-management` D2), surfaced by `agreement-status-link-page`'s review, and untouched there because the fix needs an initiator contact captured at draft time plus a verified identity to match against (mobile once `mobile-otp-auth` lands). Related: claim has no state precondition, so a party can claim after `SIGNED` and lock the counterparty out of the signed-document route — gate claim on the pipeline not having ended, in the same CR. **Wider since `draft-attach-owner-gate` (2026-10-05):** the drafting surface (generate, draft upload, preview, finalise) is now owner-only too, so a first claimant also controls the document the parties sign and whether the order is placed. Recovery emails `/agreement/{id}` to every party of a paid, unowned agreement, so each of them is a potential first claimant. A draft replaced by a link holder while the agreement was still unclaimed survives the claim as the owner's draft. | `agreement-status-link-page` | 2026-09-11 | Medium — bounded today (one named claimant, the drafter sees "saved to an account" at once), but it decides who controls a paid legal document |
| `progress-read-hot-path` | `GET /api/signing/{id}/progress` and `GET /api/agreements/{id}/payment` are now polled every 20 s per open status tab. Per tick, progress builds a full `AgreementResponse` (two JSON columns decoded, lazy signers, a template-catalog lookup for `state`/`type` the caller discards) plus the `SigningRequest` aggregate with lazy invitees — ~5 queries for four scalars — and payment loads the same `Agreement` row twice (`isAccessibleBy` then `paymentState`). Pre-existing shapes; the status page is what put them on a loop. Fix: a narrow owner-scoped signers/status projection (`@EntityGraph` or JPQL) and one `paymentStateForReader` used by both checks. Measure before optimising — at founding-team scale this is noise. | `agreement-status-link-page` | 2026-09-11 | Low — cost scales with open tabs × 3/min; revisit before real customers |
| `agreement-status-detail` | "My agreements" collapses `PDF_GENERATED`/`STAMPED`/`SIGN_REQUESTED` into one "In progress" badge and omits `payment_state` entirely, so awaiting-payment, paid-awaiting-stamp and out-for-signature are indistinguishable. Separately, `@view` and `@edit` both call `openForEdit`, so "View/Download" opens an editable form on a frozen agreement whose only feedback is a raw 409. **`agreement-status-link-page` (2026-09-11) added `FulfilmentStage` + `terminal` + `signedDocumentReady` to `GET /api/signing/{id}/progress` — the list can reuse that projection rather than invent one; the `@view`/`@edit` conflation is untouched and is the remaining scope.** | `contacts-editable-until-payment` | 2026-09-10 | Medium |
| `rental-default-commercial-terms` | Three commercial terms are silently defaulted and always render: `lockInMonths` **6**, `rentEscalationPercent` **5**, `noticePeriodMonths` **1** (and `noticeClause` carries no `showWhen`, so it renders regardless). A customer who never opens those fields signs a six-month lock-in and 5% annual escalation they were never asked about. Decide whether these should be defaulted at all, surfaced explicitly in the capture form, or gated off when untouched. **Observed in manual testing 2026-10-04:** rent INR 10 over 24 months shows "Average annual rent INR 123" under the stamp quote's "How this was calculated". The figure is CORRECT -- the default 5% escalation lifts year two to INR 10.50, so (120 + 126) / 2 = 123 -- but the breakdown lists no escalation line, so to the customer it reads as a bug, and it is the first place they discover the 5% they never chose. Whichever way this row is decided, the breakdown must state the escalation (and any other default) that moved the number. | `rental-document-content-v2` | 2026-09-10 | Medium -- customer signs terms they were not shown |
| `tg-governing-law-duplication` | A Telangana deed carries two choice-of-law clauses: the national `governingLawClause` ("laws of India") in the witnesseth list and the broader `tgGoverningLaw` ("laws of India **and** the tenancy laws applicable in Telangana, including the 1960 Act") in the now-mandatory statutory section. The second subsumes the first. **Do not fix by plain removal** -- that recreates the stamp-clause hole one clause over: revert the statutory flag to `optional: true` and a TG deed would have no governing-law clause at all. The safe shape is a `replaceClause` of `governingLawClause` with the TG text, keeping it in the always-on witnesseth list and dropping `tgGoverningLaw` from the statutory section. Reasoning is also inline in `state_type-TG-residential.patch.yaml`. | `rental-document-content-v2` | 2026-09-10 | Low -- redundant, not wrong |
| `frontend-coverage-gate` | The frontend has no coverage threshold. The backend fails `check` on a JaCoCo gate; `vitest` runs without `--coverage` and `vite.config.ts` declares no thresholds, so frontend coverage can regress to zero silently. Add a `vitest --coverage` threshold and chain it into `npm run build` alongside `security:scan`. | `frontend-test-harness` | 2026-06-20 | Low |
| `frontend-contract-test-msw` | No cross-stack contract test. Frontend API tests mock `fetch` by hand, so a backend DTO rename (e.g. the `SignSession` / signing-progress shape) compiles clean on both sides and fails only in the browser. Introduce MSW handlers generated from — or asserted against — the real backend response shape. | `frontend-test-harness` | 2026-06-20 | Low |
| `frontend-config-driven-test-teardown` | Set `restoreMocks`/`clearMocks` in `vite.config.ts` and retire the **47 hand-written `.mockReset()` calls across 9 test files** (`CaptureForm.test.ts` alone has 22, in a bare `beforeEach`). Config-driven teardown makes the next vitest major cheap instead of a 9-file audit. Cheaper now that the suite is on v4 semantics, so it is sequenced work rather than unrelated cleanup. | `frontend-dev-dep-refresh` | 2026-09-11 | Medium — pays for itself at the next runner bump |
| `frontend-engines-node-narrowing` | `frontend/package.json` declares `engines.node >= 20.19.0`, but vitest 4 supports `^20 \|\| ^22 \|\| >=24`. A developer on Node 21 or 23 satisfies ours and violates the runner's, with no warning until something breaks oddly. Narrowing `engines` affects every developer, so it was not folded into a build-gate fix. | `frontend-dev-dep-refresh` | 2026-09-11 | Low-medium — latent, environment-dependent |
| `frontend-vue-lint-warnings` | 26 `vue/html-indent` + `vue/html-closing-bracket-newline` **warnings** across four `.vue` views, from the Prettier vs `eslint-plugin-vue` stylistic overlap the config's existing off-block only partly covers. Advisory only — `eslint .` exits non-zero on errors, so they fail nothing — but a permanently noisy lint run is where a real new warning goes unnoticed. Extend the off-block or reformat. **2026-10-05: grown to 67 warnings, and `prettier --check src` now flags 15 files.** `eslint --fix` clears the 67 but leaves Prettier flagging 24 files, so the two tools are fighting. Reformatting cannot fix it; only extending the off-block can. | `frontend-dev-dep-refresh` | 2026-09-11 | Low — cosmetic, but it normalises noise |
| `frontend-root-config-node-globals` | The Node-globals fix in `frontend-dev-dep-refresh` is scoped to `frontend/scripts/`, but `eslint .` also lints four Node programs at the frontend root — `eslint.config.js`, `vite.config.ts`, `postcss.config.js`, `tailwind.config.js` — which keep the inverted globals (`window` defined, `process` not). Adding ordinary `process.env` gating to `tailwind.config.js` would fail lint with no hint why. Pre-existing, not a regression, and widening it needs a spec-scope change rather than a config tweak — hence not folded in. Extend the scoped block's `files` to the root configs. | `frontend-dev-dep-refresh` | 2026-09-11 | Low-medium — a confusing false error waiting for whoever edits a root config |
| `anonymous-draft-retain-and-purge` | Unclaimed anonymous drafts accumulate forever. No retention window, no purge job — so PII-bearing draft rows from abandoned sessions are kept indefinitely, which is the wrong default for identity/legal infra. Deferred from `agreement-ownership` (CR-B); rescued from ROADMAP prose 2026-09-11 when the completion narrative was removed. **Also owns orphaned `drafts/` objects** (appended by `delete-draft-agreement`, 2026-10-05): an owner's draft delete removes `drafts/{id}.pdf` after commit, best effort, so a failed removal leaves a PII-bearing PDF with no row pointing at it -- the purge job should list the `drafts/` prefix and remove any object whose agreement id no longer exists. | `agreement-ownership` | 2026-09-11 | Medium — data-retention exposure that grows with traffic |
| `spa-content-security-policy` | The SPA ships no Content-Security-Policy (`deploy/Caddyfile` sets XFO, nosniff and HSTS only). `cookie-session-auth` takes the session out of JS reach but accepts that any XSS can still *ride* the session while the page is open; a `script-src`-restricted CSP is the mitigation that gives that accepted risk an owner. Mind Razorpay Checkout's script and frame origins. | `cookie-session-auth` | 2026-10-04 | Medium — defence in depth for the residual XSS risk |
| `auth-expired-row-purge` | Expired `auth_session`, `login_handoff` and `oauth_login_state` rows are never purged, so the tables grow without bound. Separately, `SessionService.authenticate` writes `last_seen_at` on **every** authenticated request, and since `cookie-session-auth` the SPA's boot `GET /api/auth/me` adds one more write per page load. Fix: a scheduled purge of expired rows, and throttle or drop the per-request `last_seen_at` touch. Also tighten `browser_binding_hash` on `oauth_login_state` and `login_handoff` (V24, `login-browser-binding`) to NOT NULL once rollback past V24 is off the table; it is nullable only so the previous build can still insert, and "null never matches" holds either way. | `cookie-session-auth` | 2026-10-04 | Low–Medium — unbounded growth plus write amplification; no correctness impact |
| `shared-limiter-store` | The anonymous-surface rate limiter is per-instance (in memory), so it weakens proportionally once the app runs on more than one instance. Move it to shared storage when the app is actually scaled out. This was tracked by `session-store-not-durable` until `cookie-session-auth` deleted that row as stale: sessions were already durable in Postgres since V11, but the limiter half of the concern was real and is kept here. | `cookie-session-auth` (re-homed for `anonymous-surface-abuse-controls`) | 2026-10-04 | Low — single instance today |
| `render-outside-transaction` | `AgreementDocumentService.renderPreview`, `renderForDraft` and `renderForStamp` run the whole Gotenberg render inside `@Transactional(readOnly = true)`, so every in-flight render holds a Hikari connection (default pool 10) for up to the 30 s render timeout. `anonymous-surface-abuse-controls` bounds this with a 2 s admission wait and a fixed slot count, but the fix is to load the data in the transaction and render after it commits. | `anonymous-surface-abuse-controls` | 2026-10-04 | Medium — render load can still pin connections DB endpoints need |
| `anonymous-upload-byte-budget` | The anonymous draft upload (`POST /api/agreements/*/draft`, 10 MB) is rate limited per source and per agreement only, so one source can still write ~300 MB/min and many sources more; there is no global byte or object budget. Add one (or a per-day cap) before `byo-document-upload` adds a LibreOffice conversion behind the same route. | `anonymous-surface-abuse-controls` | 2026-10-04 | Medium — disk-fill exposure; must be revisited before `byo-document-upload` |
| `prod-db-url-log-server-error-detail` | **Deploy step, not code.** On the production server, append `?logServerErrorDetail=false` to `DB_URL` in `deploy/env/backend.env`, then restart the backend. `provision.sh secrets` backfills only *missing* variables, so an existing `DB_URL` keeps the old value and Postgres' unique-violation `DETAIL` (the raw agreement id) is still logged at ERROR. Also confirm `LOGGING_LEVEL_IN_AGREEMENTMITRA` is `INFO` there. Delete this row once done. | `agreement-id-debug-logging` | 2026-10-04 | High — do at the next deploy of `fix/new-fixes` |
| `agreement-id-vendor-references` | The raw agreement id (a bearer capability) is sent to vendors: as the Razorpay order `receipt` (`PaymentOrderService.nextReceipt`, visible in the Razorpay dashboard and echoed in webhooks) and as the ZOOP client reference (`ZoopEsignProvider` init body, from `SignRequest.agreementId`). Send the tracking reference or a non-capability value instead. | `agreement-id-debug-logging` | 2026-10-04 | Medium — credential held by third parties |
| `agreement-capability-token` | The agreement primary key doubles as the anonymous bearer credential, so every sink that sees the PK (logs, DB dumps, vendor references, support screenshots) holds a working credential and must be redacted case by case. Evaluate a separate, rotatable capability token for the anonymous routes and recovery links. | `agreement-id-debug-logging` | 2026-10-04 | Medium — removes the leak class `agreement-id-debug-logging` patches site by site |
| `security-event-alerting` | `anonymous-surface-abuse-controls` emits redacted security events (rate-limit lockouts, webhook verification failures) to the `in.agreementmitra.security` logger but sends them nowhere. Route them to an alert channel once one exists. | `anonymous-surface-abuse-controls` | 2026-10-04 | Low-medium — abuse is now visible but not noticed |
| `per-id-access-logging` | There is no record of which sources access a given agreement id, so a leaked capability link cannot be detected after the fact. Add hashed per-id access logging (no raw id), with retention. | `anonymous-surface-abuse-controls` | 2026-10-04 | Low-medium — detection only; the id remains the credential |
| `my-agreements-session-ended-message` | When the session ends elsewhere (signed out in another tab, or TTL reached), the header correctly flips to signed out, but the open My agreements view shows the generic "Could not load your agreements. Please try again." It should say the session ended and offer Sign in (react to `isSignedIn` going false in `MyAgreements.vue`). Found in the `cookie-session-auth` browser run, step 7. | `cookie-session-auth` | 2026-10-04 | Low — UX copy; no security or data impact |
| `owner-scoped-artifact-download` | No owner-scoped surface for downloading a signed artifact — deferred from `agreement-ownership` (CR-B). Partly overtaken by `signed-delivery-and-closure`'s party-authenticated in-app copy; **confirm what remains before scheduling** rather than assuming it is still open. Rescued from ROADMAP prose 2026-09-11. | `agreement-ownership` | 2026-09-11 | Low — may be largely superseded; verify first |
| `capture-state-normalized-columns` | `capture_state` is stored as an opaque `jsonb` blob (migration `V13`). Sufficient to round-trip and render, but not queryable or constrainable per attribute. Deferred from `agreement-capture-persistence` (M5); rescued from ROADMAP prose 2026-09-11. | `agreement-capture-persistence` | 2026-09-11 | Low — deliberate trade-off, revisit only if querying is needed |
| `capture-state-write-time-validation` | The capture map is validated only at **render** time by the `documents` projection, not on write, so an invalid map can be persisted and fails later. Deferred from `agreement-capture-persistence` (M5); rescued from ROADMAP prose 2026-09-11. | `agreement-capture-persistence` | 2026-09-11 | Low-medium — moves a failure from write to render |
| `openspec-archive-partial-write-on-abort` | **`openspec archive` can write some specs and still print `Aborted. No files were changed.`** It folds capabilities one at a time and validates each rebuilt spec as it goes, so a failure on the second capability leaves the first already written; the retry then fails with "already exists". Observed 2026-10-04: `rental-agreement-document` was written before `stamp-duty-calculation` aborted on a pre-existing first-line-SHALL defect. `openspec-flow` Stage 7b and the archive memory both treat that message as proof of a clean abort. Fix: after any abort, check `git status openspec/specs/` and revert partial writes before retrying; pre-validate every target baseline spec (`openspec validate --strict --type spec`) in 7a. | `ka-rental-and-commercial-templates` | 2026-10-04 | Medium — a silent partial fold of the spec of record |
| `withdraw-unpaid-finalised-agreement` | A customer who finalised and then never paid cannot remove the agreement: the capture form finalises immediately before checkout, so "pressed Pay and left" is `IN_PROGRESS`, and `delete-draft-agreement` deletes only never-finalised unpaid drafts. Withdrawing one must deal with its `signing_request` (`PDF_GENERATED`), any unpaid Razorpay `payment_order` and the write-once `stamp_quote`. **Recommended:** a customer "withdraw" that closes the agreement with a new `ClosureReason` (`WITHDRAWN_UNPAID`) rather than deleting rows, refused once payment is `PAID`/`WAIVED` or an order is captured. Start only when abandoned finalised agreements are seen cluttering My agreements. | `delete-draft-agreement` | 2026-10-05 | Low — UX clutter; no data or money exposure |
| `draft-freeze-lock-vs-finalise` | **Draft upload and terms edit do not serialise with a concurrent finalise.** Both load the agreement through `findByIdForUpdate`, which Hibernate renders on PostgreSQL as `FOR NO KEY UPDATE`; that does not conflict with the `FOR KEY SHARE` a `signing_request` insert takes, so `placeOrder` can commit between their "no signing request yet" check and their write — a draft changed after it was frozen. Found by `delete-draft-agreement`'s lock test, which hit the same fact. **Recommended:** load both through a full `FOR UPDATE` (the native `AgreementRepository.findByIdForDelete` pattern), plus a race test like `DeleteDraftAgreementIntegrationTest.aDeleteWaitsForAConcurrentFinaliseAndThenRefuses`. Touches the signing freeze, so it gets its own CR. | `delete-draft-agreement` | 2026-10-05 | Low — only the owner racing themselves across two tabs can hit it |
| `prod-minio-image-pin` | **Production runs `minio/minio:latest`** (`deploy/docker-compose.prod.yml`) while dev and the test harness pin `RELEASE.2023-09-04T19-57-37Z`. `latest` is known to break minio-java 8.6.0 (from RELEASE.2025-09-07 every bucket call 500'd locally), so any prod redeploy that pulls a newer image can break all PDF storage, and the suite cannot see it. A pin cannot simply be copied from dev either: a volume written by a newer MinIO will not start under an older one (`Unknown xl header version 3`, hit locally 2026-10-05). **Recommended:** on the box, read the running image's release (`docker inspect`). Pin prod to exactly that release if it works with minio-java, or else to the oldest release ≥ it that does — never lower. Then move dev + harness to the same pin in one change, so all three run one MinIO. Until then, every deploy runs the manual gate in `docs/DEPLOYMENT.md` §4 "MinIO image". | `delete-draft-agreement` | 2026-10-05 | High — a routine redeploy can silently break storage; prod is founding-team beta |

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
