# Tasks

Behavioural change: unit **and** integration test tasks are included (sections 8 and 9).

## 1. The eligibility rule

- [x] 1.1 Add a `@ConfigurationProperties` type for the eligible-jurisdiction allowlist in
      the `signing` module (record + constructor binding, no field injection), following
      `PaymentProperties`. **Null-normalize in the compact constructor** (`null → Set.of()`)
      as `PaymentProperties:31` does — without it an absent config block yields a null set
      and `.contains()` throws a 500 instead of refusing cleanly (design D5).
- [x] 1.2 Normalize state codes on both sides of the comparison (trim + upper-case). Seeder
      codes are upper-case verbatim from the filename (`TemplateCatalogSeeder.stateCodeOf`),
      so an un-normalized `tg` in config would silently refuse everything (design D5).
- [x] 1.3 Add the `JurisdictionEligibility` component: allowlist semantics, empty or absent
      list refuses everything (design D1, D5). The national dimension `IN` is **never
      admissible**, even if present in the configured allowlist — a property of the rule, not
      of the default config, so a config edit cannot re-open the hazard (design D8a).
- [x] 1.4 NOT NEEDED AS WRITTEN - the rule was placed in `signing.agreement`, so it reads the
      package-private `templateId()` directly and `AgreementService`'s API did not have to widen.
      Original task: expose a read path for the agreement's pinned template id —
      `Agreement.templateId()` is **package-private** (`Agreement.java:494`) and
      `AgreementResponse` carries none, so a rule outside `signing.agreement` cannot read it
      today. Add the accessor on `AgreementService` (design D6).
- [x] 1.5 Resolve the state via `TemplateCatalogApi#find` — the **non-throwing** sibling —
      not `detail`. `detail` throws for an unknown *or non-published* id, which would refuse
      a since-unpublished `TG` template with a misleading 404 instead of the 409 the spec
      promises. Map `Optional.empty()` and a null `templateId` to the jurisdiction refusal;
      no default fallback (design D5, D6).
- [x] 1.6 Log the resolved allowlist once at startup, as `PaymentGate.announceMode()` does.
- [x] 1.7 Log each refusal with the rejected state code and no personal data, following
      `PaymentGate:88` (fact only, no agreement id) — design D8.
- [x] 1.8 Add a class-level note that this allowlist is superseded by per-state duty rules
      when `state-stamp-duty-quoting` lands, naming that change.
- [x] 1.9 Add the `TG`-only default to `application.yml`.

## 2. The error contract

- [x] 2.1 Add `ConflictException.Kind.JURISDICTION_UNSUPPORTED` with a javadoc saying why it
      is distinct from `PAYMENT_REQUIRED` (a different person fixes each).
- [x] 2.2 Add a **structured field** for the rejected state code and eligible list beside the
      existing `partyLabels` channel, plus the factory method. The class deliberately derives
      no client-facing text from input; preserve that contract rather than working around it
      (design D7).
- [x] 2.3 Add the RFC 9457 problem `type` URN and mapping in `GlobalExceptionHandler`, beside
      `TYPE_CONTACT_REQUIRED`. Its `Kind` switch has no `default`, so this is a compile error
      until done — keep it that way. Confirm the body carries no party data.

## 3. Gate 1 — finalise (customer path)

- [x] 3.1 Apply the check in `SigningRequestService.finalise` **before** the agreement
      freezes and before `placeOrder`, alongside the existing preconditions — the path is
      reads-only until then, so a refusal leaves no partial state to roll back.

## 4. Gate 2 — checkout (customer path)

- [x] 4.1 Apply the check in `PaymentOrderService.startCheckout` at **one** call site, placed
      **after the settled order is reported and before order creation / outstanding-order
      resume**. Do **not** copy `requireReachableParties`' position (line 138): it precedes
      `reusableOrder` (line 143), so a check there would fire ahead of the settled branch
      (lines 174-176) and refuse a customer who has already paid — defeating design D4.
      Still ahead of `razorpay.createOrder` (line 149), so no provider call on refusal.
- [x] 4.2 Verify the settled branch still reports an already-paid order rather than refusing
      it — the one place the `CONTACT_REQUIRED` analogy does not carry (contacts freeze at
      settlement; configuration does not). Note `reusableOrder` expires an abandoned order on
      the way past (`markExpired`, line 181); that is accepted (design D4).

## 5. Gates 3 and 4 — staff path

- [x] 5.1 Apply the check in `StampIntakeService` beside `PaymentGate.require` (line 186),
      before the certificate blob is stored or attached.
- [x] 5.1a Add a `JURISDICTION_UNSUPPORTED` branch to `StampIntakeService.outcomeFor` with
      its own audit outcome token. Its switch ends `default -> OUTCOME_ERROR`, so without
      this the refusal is audited as an unspecified error — **silently**, since unlike
      `GlobalExceptionHandler`'s switch this one has a `default` and will not fail to
      compile. The existing `PAYMENT_REQUIRED` branch carries a comment making exactly this
      argument. No migration needed: `outcome` is `VARCHAR(48)`, unconstrained.
- [x] 5.2 Apply the check in `SigningRequestService` at eSign initiation beside
      `PaymentGate.require` (line 121), before any provider call.
- [x] 5.3 Confirm a staff `waive` (which sets `WAIVED` and satisfies `PaymentGate`) no longer
      opens a path to either step for an ineligible jurisdiction.

## 6. Eligible-jurisdictions endpoint

- [x] 6.1 Add the read-only route `GET /api/jurisdictions` in `signing.api` (not on the
      `documents` catalog API — design D9). **Static**: no agreement id, nothing
      per-agreement, no path variable.
- [x] 6.2 Add its explicit `SecurityConfig` matcher as anonymous/permitAll, following the
      `GET /api/templates` pattern (`SecurityConfig.java:297`). `SecurityConfig` ends in
      `.anyRequest().denyAll()` (line 320) and matches exact, order-sensitive paths, so
      without a matcher the endpoint 403s and the disclosure silently degrades to nothing.

## 7. Frontend disclosure

- [x] 7.1 Add the client call in `src/api/`, per the project convention.
- [x] 7.2 Mark a draft-only jurisdiction in `TemplatePicker.vue`, joining catalog rows
      (`state`, lines 30/150) to the eligibility list, normalizing case on the join. Wording
      says "draft and download", not "unavailable" (Vue 3 `<script setup>`, Tailwind).
- [x] 7.3 On a failed eligibility fetch, omit the marking rather than marking everything
      draft-only (design D9) — enforcement is server-side and unaffected.
- [x] 7.4 Surface the same disclosure in the capture shell, before the customer reaches
      contacts and payment.
- [x] 7.5 Map the `409` unsupported-jurisdiction response to a readable message in
      `finaliseAndPay` (`CaptureForm.vue:806`), which today catches everything into
      `payError` via `e.message`. Follow the `AgreementHttpError.contactsFrozen` precedent —
      this needs a new flag on `AgreementHttpError` in `src/api/client.ts`.

## 8. Unit tests

- [x] 8.1 Eligible jurisdiction permitted; ineligible refused.
- [x] 8.2 Null `templateId` refused — fails closed, no default fallback.
- [x] 8.3 Unresolvable (non-published) pinned template refused as a **jurisdiction** failure,
      not a 404.
- [x] 8.4 Empty and absent allowlist refuse every jurisdiction, cleanly (no NPE / 500).
- [x] 8.5 Case and whitespace differences in configured codes still match.
- [x] 8.6 Adding an ordinary state code to the configured allowlist admits it, with no code
      change.
- [x] 8.6a Adding `IN` to the configured allowlist does **not** admit it (design D8a).
- [x] 8.7 Frontend unit tests: draft-only marking, the failed-fetch degrade path, and the
      `409` message path.

## 9. Integration tests

- [x] 9.1 `POST /{id}/finalise` for an ineligible agreement → `409`
      `JURISDICTION_UNSUPPORTED`; no signing request created, agreement not frozen, not in
      the staff stamp queue.
- [x] 9.2 `POST /{id}/payment/order` → `409`; no order created, no provider call.
- [x] 9.3 The same refusal on the **outstanding**-order reuse path.
- [x] 9.4 A **settled** order is still reported, not refused (design D4).
- [x] 9.5 `POST /api/staff/estamp` → `409`; no blob stored, no stamp attached — including
      after a staff `waive`.
- [x] 9.6 `POST /api/signing/{id}/request` → `409`; no provider call, no status transition —
      including after a staff `waive`.
- [x] 9.7 A `TG` agreement passes all four gates unchanged, and finalise stays idempotent.
- [x] 9.8 An `IN` agreement can still be created, edited, generated and previewed
      (`GET /{id}/preview` returns a PDF).
- [x] 9.9 The problem body is `application/problem+json`, carries the state code and the
      eligible list, and contains no party data.
- [x] 9.10 The eligible-jurisdictions endpoint is reachable anonymously and returns no
      agreement data.
- [x] 9.11 `ModularityTests` stays green.

## 10. Fixture migration (consequence of the fail-closed decision)

- [x] 10.0 **Prerequisite — a published `TG` catalog row must exist in the test context.**
      Migrating a fixture to send `"state":"TG"` alone makes creation **404**, not pass:
      `AgreementService.resolveSelectedTemplate` (lines 146-156) throws
      `ResourceNotFoundException` when `publishedTemplateIdFor(state,type)` is empty, and
      `TemplateCatalogSeeder` is `@Profile({"local","sandbox"})` while tests run the `test`
      profile. Add a shared harness fixture that inserts the row, following the pattern at
      `StampQueueFulfilmentIntegrationTest.java:82-95`, rather than repeating the insert per
      test.
- [x] 10.1 Migrate integration fixtures that create a **bare** agreement (no `state`/`type`)
      and then finalise, pay, stamp or sign, so they carry an eligible jurisdiction. Known
      affected: `SigningRequestApiIntegrationTest` (`createBareAgreement`, ~line 171),
      `ContactGateIntegrationTest`, `RazorpayPaymentGateIntegrationTest`,
      `StampIntakeApiIntegrationTest`, `PaymentGateOptionalIntegrationTest`,
      `PaymentGateIntegrationTest` (bare creates ~lines 145/277, reaching finalise,
      `/request` and estamp), `SigningProgressApiIntegrationTest`,
      `SignedDeliveryIntegrationTest`, `ZoopSigningIntegrationTest`,
      `SigningCompletionIntegrationTest`. Re-run to find any the list misses.
      (`StampQueueFulfilmentIntegrationTest` already pins `TG` and needs no migration.)
- [x] 10.2 Keep at least one fixture that finalises a **dimension-less** agreement and
      asserts the `409`, so the accepted breaking change is pinned by a test rather than
      merely edited around.

## 11. Terms and docs

- [x] 11.1 Add the supported-jurisdictions clause to
      `frontend/src/content/termsOfService.ts`.
- [x] 11.2 Regenerate `docs/TERMS-OF-SERVICE.md` (`npm run terms:doc`); the drift test passes.
- [x] 11.3 Update `docs/LEGAL-POSTURE.md` item 1a step 0 status.
- [x] 11.4 Renumber the ToS clause cross-references the new clause 5 shifted:
      `docs/ROADMAP.md` (17 -> 18) and `docs/DOMAIN-AND-EMAIL-SETUP.md` (18 -> 19).

## 11a. The agreement's own jurisdiction (D9a)

- [x] 11a.1 `AgreementResponse` gains `state`/`type`, resolved in `AgreementService.toResponse`
      through the same non-throwing `TemplateCatalogApi#find` the gate uses, so the two agree on
      an unresolvable template. Old constructors kept, defaulting both to null.
- [x] 11a.2 `AgreementView` (`frontend/src/api/client.ts`) gains the two fields; `App.vue`'s edit
      branch passes them to `CaptureForm`.
- [x] 11a.3 `CaptureForm` reads the reopened agreement's own state in preference to the defaulted
      prop, so the marking does not depend on the view-switch wiring.
- [x] 11a.4 Unit: capture-screen banner marks a draft-only jurisdiction, leaves an eligible one
      unmarked, does NOT mislabel a reopened eligible agreement (fails without the fix), and marks
      nothing when eligibility cannot be fetched.
- [x] 11a.5 Integration: the agreement response carries its pinned jurisdiction and no template id,
      and reports null for an unpinned agreement.

## 12. Gates and the pre-archive check

- [x] 12.1 `./run-tests.sh` (backend `check`) green on a clean run; `npm run lint` clean (the two
      remaining errors are pre-existing in `frontend/scripts/security-scan.mjs`, untouched here) and
      frontend tests green (187/187, build clean). Earlier full runs failed only on Postgres
      container-start timeouts — `WaitAllStrategy`'s 30s default silently halved the stock 60s
      budget; raised in `HarnessTestConfig` (harness change, exempt from the tests rule).
- [x] 12.2 `./gradlew spotlessApply`.
- [x] 12.3 **Pre-archive, needs the user — CONFIRMED ZERO (2026-09-08, by the founder).** No `IN`
      agreement is at or beyond finalise in sandbox/beta, so nothing is stranded by the gate and no
      refund decision is owed. Original wording: confirm whether any `IN` agreement is at or
      beyond **finalise** in the sandbox/beta environment — deliberately wider than `PAID`,
      since a `PDF_GENERATED` row already sits in the staff stamp queue and is the real
      exposure. Expected zero. If non-zero, those rows need an explicit decision before
      archive, and the realistic remedy is **refund and abandon**: gates 3 and 4 deliberately
      close manual fulfilment, no operator closure route is exposed, and such a row can no
      longer be paid. A row already `STAMPED` (a real certificate spent) has no in-app route
      out at all and needs a decision taken outside the product (design, Open Questions).
