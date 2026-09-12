## Why

We sell a **"National" (`IN`) agreement**, and a customer can take it all the way to
pay-and-stamp. **Stamp duty is state law — there is no national rate.** So for an `IN`
agreement there is no duty to compute and no defined state in which staff should buy the
SHCIL certificate. The published terms of service guarantee **no second bill after
payment**, which makes taking money on an `IN` order an unbounded liability against a
fulfilment path that does not exist.

The hazard is **undefined fulfilment**, not merely unpaid fulfilment. That distinction sets
the scope: every step that commits us to a real-world act in a jurisdiction — placing the
order, taking the money, buying the certificate, calling the eSign provider — has to be
covered, not just the one where money moves.

`docs/LEGAL-POSTURE.md` item 1a names this **step 0**: the smallest change in the pricing
work, the only one that closes a hazard rather than adding a capability, and the one that
every later step (put the pricing rule in code → compute duty before payment → reconcile
against the real certificate) is **undefined until it lands**.

It is live, not theoretical. `TemplateCatalogSeeder` is `@Profile({"local","sandbox"})`
and `docs/DEPLOYMENT.md` requires `SPRING_PROFILES_ACTIVE=sandbox` in the deployed
environment, so the `IN` rows really are seeded there. Exposure is bounded only by
production being founding-team beta — a condition that expires.

**Relationship to `state-stamp-duty-quoting`** (4/4 artifacts complete, not applied): that
change already carries the general rule — *"Unsupported states fail closed… their templates
do not reach checkout."* This CR is its **narrow, shippable slice**: a config allowlist that
refuses now. Critically, that change also lets a customer holding a **national template**
pick the property's state, which then *becomes* the agreement's duty jurisdiction. So this
CR's requirement is written against the **duty jurisdiction**, not against the template's
state dimension — otherwise the two would archive into the living spec as contradictory
rules. When `state-stamp-duty-quoting` lands, real per-state duty rules become the source of
eligibility and this allowlist is superseded, not extended.

## What Changes

- **`IN` stays in the catalog as a draft-and-download-only template.** It remains
  selectable, fillable and previewable through the existing **unpaid** `GET
  /api/agreements/{id}/preview` path — a capability that already exists, so this costs no
  new build. It does **not** leave the catalog: `IN` is each layer set's `base.yaml` own
  state dimension and `TG` is a `state-TG.patch.yaml` overlay on top of it
  (`TemplateCatalogSeeder.java:113`), so removing it would fight the architecture.
- **A config-driven allowlist of jurisdictions eligible for paid fulfilment**, currently
  `{TG}` — deliberately **not** a hardcoded `IN` denylist. A denylist would pass every test
  written today and be silently wrong the moment a third state appears: Karnataka would
  default to *eligible* with nothing computing its duty.
- **The rule is enforced at four server-side points**, every step that commits us to
  something in a jurisdiction:
  1. `POST /api/agreements/{id}/finalise` — places the order, freezes the agreement and
     creates the signing request that **enters the staff stamp queue**.
  2. `POST /api/agreements/{id}/payment/order` — where the customer's money moves.
  3. `POST /api/staff/estamp` — where staff buy a real SHCIL certificate.
  4. `POST /api/signing/{id}/request` — where a billable eSign transaction is incurred.

  Steps 1–2 are the customer path; `finaliseAndPay()` (`CaptureForm.vue:806`) calls them in
  a single user action, so gating only checkout would still queue an `IN` agreement for a
  certificate nobody can buy. Steps 3–4 are the staff path: `PaymentGate` is their **only**
  control today, and a staff `waive` satisfies it — so without them a staff member could
  still buy a certificate in no defined state, which is precisely the hazard. Both already
  call `PaymentGate.require`, so the seam exists.
- **A new `ConflictException.Kind.JURISDICTION_UNSUPPORTED`** with its own RFC 9457
  ProblemDetail type — **not** folded into `PAYMENT_REQUIRED`. `PaymentGate`'s own javadoc
  states the reason: an operator staring at a 409 must be able to tell why the pipeline
  stopped, because a different person fixes each one.
- **It fails closed on an unknown jurisdiction.** An agreement with no pinned template has
  no resolvable state and is refused. **BREAKING, accepted deliberately:**
  `CreateAgreementRequest` documents `state`/`type` as optional and ships a pre-dimensions
  constructor, so a dimension-less agreement is a supported contract today with a working
  path to paid fulfilment. This change removes that path — no jurisdiction, no fulfilment.
  Creating, editing, generating and previewing such an agreement still work; only the four
  gated steps refuse. Existing integration fixtures that create bare agreements are migrated
  to carry a jurisdiction.
- **A settled order is still reported, never refused.** A customer who has already paid and
  reloads the payment page must see "you have paid", not a jurisdiction 409 — refusing there
  would invert the very ToS hazard this CR exists to close.
- **The UI discloses the limit up front.** `TemplatePicker` and the capture shell mark a
  draft-only jurisdiction, read from the server, so a customer does not fill in a whole
  National agreement and discover the wall at checkout.
- **The terms of service gain a supported-jurisdictions clause.** Audited: `LandingPage.vue:51`
  makes no national-coverage claim (it argues *against* "one national fill-in-the-blank PDF")
  and `termsOfService.ts` has no supported-states clause at all — so this is an **addition**,
  not a retraction. Per the project rule, customer-facing promises change in the CR that
  changes them. `docs/TERMS-OF-SERVICE.md` is generated from that source (`npm run terms:doc`,
  drift-tested), so it is regenerated here.

**Not in scope:** computing stamp duty, changing the price, and the pricing rule itself
(`platform-fee` + `duty-allowance`). Those are `state-stamp-duty-quoting` and
`docs/LEGAL-POSTURE.md` item 1a steps 1–3. This CR only refuses. Also out of scope: making
`state`/`type` mandatory at agreement creation — the create surface stays permissive and the
refusal happens at fulfilment.

## Capabilities

### New Capabilities
- `jurisdiction-eligibility`: which jurisdictions may reach paid fulfilment versus
  draft-and-preview only; how the allowlist is configured; how it fails closed on an unknown
  or unset jurisdiction; how a refusal is reported and observed; and how the limit is
  disclosed to the customer before they invest effort.

### Modified Capabilities
- `payment-processing`: order creation gains a second precondition beside party
  reachability — the agreement's duty jurisdiction must be eligible — while a **settled**
  order continues to be reported rather than refused.
- `signing-request`: order placement at finalise, and eSign initiation, gain the same
  precondition, so an ineligible jurisdiction never reaches `PDF_GENERATED`, the staff stamp
  queue, or a billable provider call.
- `estamp-intake`: staff stamp intake gains the same precondition, so no SHCIL certificate
  is bought for an agreement in an undefined jurisdiction.

## Impact

**Backend (`signing` module):**
- `ConflictException` — new `Kind.JURISDICTION_UNSUPPORTED` + factory. Carrying the state
  code and eligible list needs a structured field beside the existing `partyLabels` channel;
  the class deliberately derives no client text from input, and that contract is preserved.
- `GlobalExceptionHandler` — new ProblemDetail type beside `TYPE_CONTACT_REQUIRED`. Its
  `Kind` switch has no `default`, so the new kind is a compile error until handled — fail
  loud, kept.
- New jurisdiction-eligibility rule + `@ConfigurationProperties` (pattern:
  `PaymentProperties` / `DeliveryChannelProperties`), with `application.yml` defaults.
- `PaymentOrderService.startCheckout`, the finalise path (`SigningRequestService.finalise`),
  `StampIntakeService`, and eSign initiation in `SigningRequestService`.
- Jurisdiction is read via the **public** `TemplateCatalogApi#find` seam — the non-throwing
  sibling of `detail`, so an agreement pinned to a since-unpublished template yields the
  jurisdiction 409 rather than a misleading 404. `Agreement.templateId()` is
  package-private, so a read path through `signing.agreement` is required. No
  `documents.template` type crosses the boundary; `ModularityTests` stays green.
- A new read-only eligible-jurisdictions endpoint in `signing.api`, anonymous, static (no
  agreement id, nothing per-agreement), with its explicit `SecurityConfig` matcher —
  `anyRequest().denyAll()` means an unmatched path silently 403s.

**Frontend:** `TemplatePicker.vue`, the capture shell, `src/api/`,
`src/content/termsOfService.ts`.

**Docs:** `docs/TERMS-OF-SERVICE.md` (regenerated), `docs/LEGAL-POSTURE.md` item 1a step 0
status.

**Signing-status FSM:** **no new states and no changed transitions.** The gate is a
**precondition** on the existing `DRAFT → PDF_GENERATED` transition at finalise and on the
existing eSign-initiation step — it refuses before either is attempted, so the state machine
is untouched. No change to the asynchronous signing/webhook flow, so no sequence diagram is
required. The Razorpay and eSign **webhooks and the reconciliation job stay ungated by
design**: they record work already committed, and refusing there would strand money already
taken.

**PII / security review:** **None.** This change introduces and moves **no** Aadhaar
number, OTP, VID, KYC data, signer PII, or secret. It reads one field — a two-letter state
code, server-derived from the agreement's pinned template and never client-submitted — and
compares it against a configured allowlist. The new 409 body carries the **state code and
the eligible list only**; per `api-error-handling`, error bodies never echo submitted values
or PII, and no party name, address, email or mobile appears in it. The eligible list is
public by construction (the picker and the ToS clause publish it) and names only what *is*
eligible, never what is under consideration. Sandbox + dummy data only is preserved:
nothing here touches a provider, a credential, or real customer data.

**Open item for the user, to resolve before archive** (not resolvable from a dev machine —
no deployed DB access): **is there any `IN` agreement at or beyond finalise in the
sandbox/beta environment?** Deliberately wider than "paid": an `IN` row in `PDF_GENERATED`
is already sitting in the staff stamp queue, which is the real exposure, and `PAID` alone
undercounts it. Expected zero — production is founding-team-only beta, and
`termsOfService.ts` already states "No external customer has yet paid us." If it is not
zero, those rows need an explicit decision (refund, or deliberate manual fulfilment in a
chosen state) before archive. The gate stops new ones; it does not resolve an existing one.
