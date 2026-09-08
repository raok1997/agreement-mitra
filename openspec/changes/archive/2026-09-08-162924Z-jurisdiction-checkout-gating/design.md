## Context

`TemplateCatalogSeeder` emits one catalog row per `(state, type)` a layer set exposes. `IN`
comes from each set's `base.yaml` own state dimension; `TG` comes from a
`state-TG.patch.yaml` overlay beside it. So `IN` is not an extra row beside `TG` — it is
the **base dimension**, which is why removing it from the catalog would fight the
architecture and keeping it draft-only does not.

Today nothing stops an `IN` agreement progressing. The customer presses one button,
`finaliseAndPay()` (`CaptureForm.vue:806`) calls `POST /{id}/finalise` and then `POST
/{id}/payment/order`, and the agreement is now a placed order in `PDF_GENERATED` sitting in
the staff stamp queue, paid, with **no state whose duty we can compute and no state in
which staff can buy the certificate**.

Four existing facts shape the design:

1. **The client picks the jurisdiction.** `AgreementService.resolveSelectedTemplate(state,
   type)` resolves from the client-supplied pair, so any UI-side hiding is cosmetic. The
   refusal must be server-side.
2. **`PaymentGate` is the wrong seam.** Its javadoc places it at e-stamp intake and eSign
   initiation — both *after* money has moved. A jurisdiction gate there *alone* would refuse
   a customer we had already charged, which is precisely the outcome the ToS forbids.
3. **`PaymentGate` is also insufficient as a proxy.** It is the only control on `POST
   /api/staff/estamp` (`StampIntakeService:186`) and `POST /api/signing/*/request`
   (`SigningRequestService:121`), and a staff `waive` sets `WAIVED`, which satisfies it. So
   "paid" does not imply "fulfillable".
4. **There is an exact precedent for the shape.** `CONTACT_REQUIRED` is documented as
   "Raised at order creation (before money moves) and again at signing initiation as defence
   in depth — one rule, from `PartyReachability`, applied at both gates," implemented as
   `PaymentOrderService.requireReachableParties` (line 92), called from `startCheckout`
   (line 138) ahead of order creation. This change follows that shape rather than inventing
   one.

## Goals / Non-Goals

**Goals:**
- No agreement in a jurisdiction we cannot stamp reaches order placement, payment,
  certificate purchase, or a billable eSign call.
- Adding a jurisdiction is a **configuration change**, not a code change.
- An operator seeing the 409 can tell *this* failure apart from every other 409 on the
  pipeline.
- The customer learns the limit **before** filling in the form, not at checkout.
- `IN` remains fully usable for drafting, preview and download.
- The requirement text does not contradict `state-stamp-duty-quoting` once both archive.

**Non-Goals:**
- Computing stamp duty, or changing what is charged (`state-stamp-duty-quoting`;
  `LEGAL-POSTURE.md` item 1a steps 1–3).
- Removing `IN` from the catalog.
- Making `state`/`type` mandatory at agreement creation.
- Retro-fitting anything to an agreement already at or beyond finalise — see the open
  question.
- Gating the drafting surface (create / edit / generate / preview), which costs nothing and
  takes no money.

## Decisions

### D1 — One rule object, config-driven allowlist, in the `signing` module

A small `JurisdictionEligibility` component holding an **allowlist** of state codes eligible
for paid fulfilment, bound via `@ConfigurationProperties` (the pattern `PaymentProperties`
and `DeliveryChannelProperties` already establish), defaulted in `application.yml` to `TG`.

**Allowlist, not a denylist of `IN`.** A denylist — or worse, `if (state.equals("IN"))` —
would pass every test written today and be silently wrong the moment a third state appears:
Karnataka would default to *eligible* while nothing computes its duty. The allowlist inverts
that: a new jurisdiction is ineligible until deliberately admitted, the correct default for
a legal-fulfilment capability.

It lives in `signing`, not `documents`: **which jurisdictions we can fulfil is a
signing/fulfilment policy**, not a property of a template. The `documents` catalog must not
learn about payment eligibility.

### D2 — The rule is about the DUTY JURISDICTION, not the template's state dimension

This is the wording that keeps this change compatible with its successor.
`state-stamp-duty-quoting` deliberately lets an agreement on a **national template** reach
checkout *once the customer picks the property's state*, which then becomes the agreement's
duty jurisdiction; it refuses only when that choice is absent. If this CR's requirement said
"`IN` is ineligible" flatly, the two would archive into the living spec as contradictory
unconditional rules.

So the requirement is written as: **paid fulfilment requires an eligible duty jurisdiction,
and `IN` is not itself a duty jurisdiction** — which is exactly what the successor spec
says. Today the agreement's duty jurisdiction is simply its pinned template's state, because
nothing else can supply one. When the successor lands it supplies one for national
templates, and this requirement keeps holding without amendment.

### D3 — Enforced at four points, every step that commits us in a jurisdiction

- **`POST /{id}/finalise`** — the most important. It places the order and creates the
  signing request in `PDF_GENERATED`, which is what puts the agreement in front of staff.
- **`POST /{id}/payment/order`** — where the customer's money moves.
- **`POST /api/staff/estamp`** — where a real SHCIL certificate is bought.
- **`POST /api/signing/{id}/request`** — where a billable vendor transaction is incurred.

The last two are the answer to context fact 3: `PaymentGate` is their only control and a
staff `waive` satisfies it, so without them a staff member could still buy a certificate in
no defined state. Both call sites already invoke `PaymentGate.require`, so the seam exists
and the addition is one line each. **This is deliberately not "the staff are trusted, so
skip it":** a different threat model argues for a different *response*, not for none, and
the hazard here is undefined fulfilment rather than fraud. It also covers agreements
finalised *before* this change ships.

**Ungated by design, stated so it does not read as an oversight:** the Razorpay and eSign
webhooks, the reconciliation job, staff `extend`/`resend`, and the recovery link. Each
records or re-delivers work already committed; refusing there would strand money already
taken, which inverts the hazard.

### D4 — A settled order is reported, never refused

The check runs before an order is **created** or an **unsettled** order **resumed**.
`reusableOrder`'s settled branch (`PaymentOrderService:174-176`) exists to "report it rather
than opening checkout again", and that must survive: a customer who has already paid for an
`IN` agreement and reloads the payment page must see that they have paid, not a jurisdiction
409.

**Placement, precisely — this is the one place the `requireReachableParties` position is
wrong.** That call sits at line 138, *before* `reusableOrder` at line 143, so copying its
position would fire the jurisdiction check ahead of the settled branch and defeat this
decision entirely. The check therefore goes **after the settled order is reported, and
before order creation and before an outstanding order is resumed** — one call site covering
both remaining paths.

**Accepted side effect:** `reusableOrder` mutates on the way past, expiring an abandoned
outstanding order (`markExpired`, line 181). So an ineligible agreement with an abandoned
order has that order expired by a request that then refuses. Harmless — the order was
already dead, no new order is created, and the spec's "no payment order is created" still
holds.

This is where the `CONTACT_REQUIRED` analogy stops. Contacts freeze at settlement, so a
settled order's reachability cannot go stale; configuration *can* change, so re-checking an
**outstanding** order is right — but refusing a **settled** one would recreate the exact ToS
hazard this CR exists to close.

### D5 — Fail closed on an unknown jurisdiction; BREAKING, accepted

An agreement with no pinned template has no resolvable state and is refused.
`AgreementDocumentService.dimensionsFor` treats `null` as "fall back to the `documents`
default" — appropriate for *rendering*, and exactly wrong for *fulfilment*, so this path
must not reuse that leniency.

**This is a breaking change and was decided explicitly.** `CreateAgreementRequest` documents
`state`/`type` as optional and ships a pre-dimensions constructor, so a fixed-fields-only
client has a working path to paid fulfilment today, and this removes it. Accepted: no
jurisdiction means no computable duty and nowhere to buy a certificate, so "unknown" cannot
be "fine". The create surface stays permissive (making dimensions mandatory is a separate,
larger API change), drafting and preview are unaffected, and only the four gated steps
refuse. Consequence to absorb inside this CR: several existing integration fixtures create
bare agreements and call `finalise`, and are migrated to carry a jurisdiction.

**An empty or absent allowlist refuses everything.** A misconfigured deploy stops paid
fulfilment rather than opening it — the right failure direction for legal infrastructure.
Blast radius handled three ways: the default is committed in `application.yml`; the resolved
allowlist is logged once at startup, as `PaymentGate.announceMode()` does for its mode; and
the properties type **null-normalizes in its compact constructor** the way
`PaymentProperties:31` does. That last one matters — without it an absent config block
yields a `null` set and `.contains()` throws a 500 instead of refusing cleanly, so
fail-closed is an implementation obligation, not a free property of `@ConfigurationProperties`.

**State codes are normalized** (trimmed, upper-cased) on both sides of the comparison.
Seeder codes are upper-case, taken verbatim from the layer-set filename
(`TemplateCatalogSeeder.stateCodeOf`), so an un-normalized config value of `tg` would
silently refuse everything — a fail-closed outage caused by a lower-case letter.

### D6 — Read the jurisdiction through `find`, not `detail`

`TemplateCatalogApi#detail` throws `ResourceNotFoundException` for an unknown **or
non-published** id. An agreement pinned to a since-unpublished template would therefore
refuse with a **404** rather than the 409 the spec promises — even when its state is `TG`.
The non-throwing sibling `find` returns `Optional`, and `AgreementService.resolveTemplates`
already uses it for exactly this reason. Empty maps to the jurisdiction 409: an unresolvable
template is an unknown jurisdiction, which D5 already refuses.

`Agreement.templateId()` is package-private (`Agreement.java:494`) and `AgreementResponse`
carries no template id, so a rule living outside `signing.agreement` needs a read path
exposed through `AgreementService` — a real implementation step, not a detail.

### D7 — A distinct `ConflictException.Kind`, not `PAYMENT_REQUIRED`

`JURISDICTION_UNSUPPORTED`, with its own RFC 9457 `type` URN beside
`TYPE_CONTACT_REQUIRED`. `PaymentGate`'s javadoc already argues the principle: a refusal
must never be folded into another kind, "because a different person fixes each one." An
unpaid order is fixed by the customer paying; an unsupported jurisdiction is fixed by
product deciding to support that state — different people, different timescales.

The body carries the **rejected state code and the eligible list**, so the message is
actionable without a lookup. Both are server-derived and public by construction, so the
never-echo-input invariant holds. Carrying them needs a **structured field** beside the
existing `partyLabels` channel — `ConflictException` deliberately derives no client-facing
text from input, and that contract is preserved rather than worked around.

### D8 — A refusal is logged, not audited to a table

A refusal carries no money and no PII, and is a *pre-commitment* event — nothing happened.
A structured log line naming the rejected state code is proportionate; an audit table (as
`StampIntakeAudit` and `RecoveryAudit` have) would be over-engineering for a rejection.
Following `PaymentGate:88`, the line states the fact without an agreement id.

It also has product value: "how many customers hit this wall, and in which state" is the
input to deciding which jurisdiction to add next. D5's startup line covers *configuration*
observability; this covers *refusal* observability, which is the one that informs the
roadmap.

**Stamp intake is the exception: it already has an audit trail, and the refusal must name
itself in it.** `StampIntakeService.outcomeFor` switches on `ConflictException.kind()` with
`default -> OUTCOME_ERROR`, so without a new outcome token a jurisdiction refusal is
recorded as an unspecified error. That would silently defeat this CR's own promise that
staff can tell it apart from a payment-required refusal at the same step, and — unlike the
`GlobalExceptionHandler` switch, which has no `default` and so fails to compile — nothing
would flag it. The existing `PAYMENT_REQUIRED` branch carries a comment making exactly this
argument. No migration is needed: `outcome` is an unconstrained `VARCHAR(48)`.

### D8a — `IN` cannot be admitted by configuration

The allowlist admits **duty jurisdictions**, and the national dimension is not one. So it is
not merely absent from the default list — it SHALL NOT be admissible by adding it, because
no rate exists to admit. Otherwise a well-meaning config edit re-opens the exact hazard this
CR closes, and the change would be indistinguishable from the denylist D1 rejects.

This matches the successor spec, which states the same rule explicitly ("SHALL NOT be
configurable as one"). Recording it here means the two agree in the archived spec rather
than only in intent.

### D9 — The frontend reads eligibility from the server, never a hardcoded list

Hardcoding `IN` in the frontend would create a **second source of truth that silently
drifts** from the gate — the same defect class the generated-terms drift test exists to
prevent.

**Placement:** a new read-only route in `signing.api` (the allowlist is `signing` policy,
and `signing.api` is already the public HTTP seam). It is **anonymous**, and it must be
**static** — no agreement id, nothing per-agreement — so it can never become an
unauthenticated read on agreement data. It discloses only which states we sell in, which
the picker and the ToS clause publish anyway. `SecurityConfig` ends in
`anyRequest().denyAll()` and matches exact paths, so it needs its **own explicit matcher**;
without one the endpoint 403s and the disclosure silently degrades to nothing.

**The client join:** picker rows carry `state` from the `documents` catalog
(`TemplatePicker.vue:30,150`); eligibility comes from this `signing` route. The SPA joins on
the state code, normalizing case on the client so the join cannot fail on a casing
mismatch.

**If the eligibility call fails, the marking degrades to nothing** rather than marking
everything draft-only. Enforcement is unaffected — the server gate is authoritative and this
is disclosure only — whereas falsely labelling `TG` as draft-only would deter genuinely
payable customers over a transient network error.

Rejected alternatives: an `eligible` flag on the `documents` catalog API (pushes a `signing`
policy into `documents` and inverts the module dependency); a hardcoded frontend list
(drifts); discovering it by attempting checkout and handling the 409 (tells the customer
only after they have filled in the whole form, which is what this CR exists to prevent).

The copy must be honest about what `IN` still does: **draft and download**, not
"unavailable". The template is genuinely usable, just not stampable or signable.

### D9a — The agreement response carries its own jurisdiction

D9 gives the SPA the *eligible* list; joining it needs the other half — **this agreement's**
jurisdiction — and on the edit path the SPA does not have one. `CaptureForm`'s `state` prop is
defaulted (`DEFAULT_STATE = "IN"`), `App.vue`'s edit branch passes only the id and the loaded
agreement, and `AgreementResponse` carried no dimensions. So a reopened agreement — a recovery
link, a resumed draft, an edit — read as `IN` whatever it actually was, and every one of them was
labelled draft-only, **including a stampable `TG` one**. That is precisely the outcome D9's
degrade rule exists to avoid: mislabelling an eligible jurisdiction deters a customer we could in
fact serve, which is worse than saying nothing.

So `AgreementResponse` gains **`state` and `type`**: the pinned template's dimensions, resolved
server-side through the same non-throwing `TemplateCatalogApi#find` seam the gate uses (D6), so the
two cannot disagree — an agreement with no resolvable template reports `null` here and is refused
there. Both are server-derived, never echoed from the create request, and neither is the template
**id**, which stays internal to `signing` (D6's constraint is preserved: it says the response
carries no template id, not that it carries no dimensions). The values are the same two-letter
codes the picker and the published terms already disclose, so this adds no new disclosure.

The client reads the reopened agreement's own state in preference to the prop, so the marking is
correct even where the prop was never passed — the component does not depend on the view-switch
wiring being right. `App.vue` passes the dimensions through as well, which additionally fixes the
edit path resolving the *default* form schema rather than the agreement's own.

### D10 — Terms of service gain a supported-jurisdictions clause

Audited before writing: `LandingPage.vue:51` makes no national-coverage claim, and
`termsOfService.ts` has no supported-states clause at all. Nothing is being walked back —
this is an **addition** stating which jurisdictions can be stamped and eSigned versus
drafted only. `docs/TERMS-OF-SERVICE.md` is generated from that source and drift-tested, so
it is regenerated in the same change.

## Risks / Trade-offs

- **A customer who has already drafted an `IN` agreement now hits a wall.** Mitigated by D9
  moving the disclosure to the point of selection. Accepted: the alternative is taking money
  for something we cannot deliver.
- **Dimension-less agreements lose their path to fulfilment** (D5). A deliberate breaking
  change; the cost inside this CR is migrating the integration fixtures that create bare
  agreements and finalise them.
- **A misconfigured allowlist stops all checkout** (D5). Deliberate — fail closed — with
  three mitigations: committed default, startup log, null-normalization.
- **Four call sites mean the rule is invoked four times.** Accepted, and precedented: this
  is how `CONTACT_REQUIRED` and `PaymentGate` both behave, for the same reason.
- **This allowlist is temporary by design.** When `state-stamp-duty-quoting` lands, per-state
  duty rules become the source of eligibility and this component is superseded. The risk is
  that it instead becomes permanent and quietly diverges. Mitigation: say so in the code,
  naming the successor change; D2 keeps the requirement text compatible in the meantime.
- **The gate does not resolve history, and closes the manual route out.** It stops new
  commitments; an `IN` agreement already at or beyond finalise is untouched by it. But
  gates 3 and 4 mean staff can no longer stamp or sign such a row either — deliberately, and
  it is the point of those gates, but the consequence is that **manual fulfilment is no
  longer an available remedy** for a grandfathered row. Nor is there an operator lever to
  close one: no `POST` closure route is exposed. So a grandfathered `IN` agreement's only
  honest remedy is **refund and abandon**, and one already `STAMPED` (a real certificate
  spent) has no in-app route out at all. Acceptable only because the expected count is zero
  — which is exactly why the open question below is a **blocking** pre-archive check rather
  than a formality.
- **Supersession is an obligation on the successor, not a property of this change.** When
  `state-stamp-duty-quoting` lands it must explicitly `MODIFIED`/`REMOVED` the
  `jurisdiction-eligibility` requirements it replaces, or the living specs will carry two
  sources of eligibility truth. Noted here because that change is written but not applied,
  so the obligation can still be recorded in it.

## Open Questions

1. **Is there any `IN` agreement at or beyond finalise in the sandbox/beta environment?**
   Not resolvable from a dev machine — it needs a query against the deployed database.
   Deliberately wider than "paid": an `IN` row in `PDF_GENERATED` already sits in
   `awaitingStampQueue`, which is the real exposure, and counting only `PAID` would
   undercount it. Expected zero (founding-team-only beta; `termsOfService.ts` already states
   "No external customer has yet paid us"). **If non-zero, those rows need an explicit
   decision before this change is archived**, because the terms guarantee no second bill
   after payment. Note that the realistic remedy is **refund and abandon**: gates 3 and 4
   deliberately close manual fulfilment, and no operator closure route is exposed, so
   "fulfil it manually in a chosen state" is not available through the application. A row
   already `STAMPED` would need a decision taken outside the product entirely. Tracked as a
   blocking pre-archive check, not an assumption.
