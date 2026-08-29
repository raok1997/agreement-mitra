## Why

The platform charges one flat configured price for every agreement, in every state, regardless of
rent, deposit, or term. `PaymentPricing.priceFor(agreementId)` returns `payment.amount.minor-units`
and ignores its argument; its own javadoc names the gap: "when state-and-rent-dependent stamp duty
arrives, it changes this calculation and nothing else". That is this change.

The gap is not cosmetic, it is a correctness and money problem. Stamp duty is a statutory levy that
varies by state, property use, term, rent and deposit, and the two states we are launching in do not
even share a formula shape: Karnataka charges 0.5% of the average annual rent plus deposit but
**caps** residential leases of one year or less at Rs. 500, while Telangana charges 0.4% of the
aggregate rent for the **entire term** plus deposit with **no cap**. A flat price therefore
under-collects badly in Telangana (Rs. 55,200 of duty on a Rs. 80,000/month lease) and tells the
customer nothing about what they are paying for. Meanwhile staff purchase a real SHCIL certificate
out-of-band with no system record of which denomination the customer actually paid for, so nothing
prevents taking money for Rs. 500 of duty and attaching a Rs. 100 certificate.

## What Changes

**A configurable per-state duty capability in the `rules` module** (its first real inhabitant, and
the reason CLAUDE.md reserves it for "multi-state legal-logic"):

- A public module API `StampDutyRules.assess(DutyRequest) -> DutyAssessment`. The `signing` module
  calls it only through that interface; `ModularityTests` stays green. Not Drools -- the rule set is
  **declarative data**, not code.
- **Adding a state requires no Java change.** Per state the configuration expresses: slabs keyed by
  (property use, term range); a rate percentage; the base composition (`AVERAGE_ANNUAL_RENT` vs
  `TOTAL_TERM_RENT`) and which components enter it (rent, money advanced / security deposit, premium,
  fine); an optional cap and an optional floor; optional flat-rate overrides for special cases; and
  the term threshold above which registration is compulsory.
- **Two states seeded with real, cited rules.** Karnataka (Karnataka Stamp Act 1957, Article 30) and
  Telangana (0.4% of aggregate term rent plus advance, uncapped). The two were chosen because their
  shapes differ -- capped vs uncapped, average-annual vs total-term base -- which is what proves the
  configuration model is genuinely generic rather than Karnataka-with-parameters. Every seeded rate
  carries its statutory citation and an explicit "verify with counsel before production" marker: the
  rates come from the Act schedule plus secondary sources, not a lawyer's sign-off.
- **Unsupported states fail closed.** A state with no configured rules yields an explicit unsupported
  assessment; its templates do not reach checkout. There is no default duty to silently fall back to.

**A stamp denomination master and a bounded customer choice:**

- A per-state master of the e-stamp denominations actually purchasable in that state. The customer
  selects **from that list only** -- never a free-form amount.
- The system computes the statutory duty and pre-selects the correct denomination. Selecting a
  **higher** denomination is unremarkable. Selecting a **lower** one is permitted (it matches real
  Bangalore practice of using Rs. 100 or Rs. 200 paper) but **only** behind an explicit
  acknowledgement that an under-stamped instrument is inadmissible in evidence until impounded and
  carries a penalty of up to ten times the deficit. What was warned, what was chosen, and when, is
  persisted as an audit record.

**Pricing becomes a line-item quote (BREAKING for the payment amount):**

- **BREAKING**: `PaymentPricing` no longer returns a flat configured amount. The payable total is
  composed per state from: the chosen stamp duty denomination (a **pass-through** collected as pure
  agent, not our revenue), a service fee (ours, configurable per state and per template type), a
  procurement/vendor fee, a delivery fee (zero where no physical paper moves), and GST applied to the
  **service components only**. Existing `payment.amount.*` configuration is superseded by per-state
  charge configuration.
- Money stays integer minor units plus an explicit currency throughout, per the existing `Money`
  type. The amount is still computed server-side and never accepted from the client.
- **Single collection**: duty plus fees plus GST are collected in one Razorpay order through the
  existing `PaymentGate` / `PaymentOrderService` flow. No second payment surface.
- **The quote freezes at order creation.** The full line-item breakdown and the chosen denomination
  are persisted alongside the order amount, so a later configuration edit or rate change can never
  disagree with what was actually charged.

**Stamp intake is reconciled against what was paid for:**

- The staff queue entry shows the denomination the customer paid for, and intake rejects a
  certificate whose duty amount is below it.

## Capabilities

### New Capabilities

- `stamp-duty-rules`: Per-state, configuration-driven stamp duty assessment in the `rules` module --
  the declarative rule model, the seeded Karnataka and Telangana rule sets, the per-state denomination
  master, and fail-closed handling of unconfigured states.
- `stamp-selection`: The customer's bounded choice of stamp denomination from the state master, the
  pre-selection of the statutory denomination, and the audited acknowledgement required to select
  below statutory duty.

### Modified Capabilities

- `payment-processing`: The requirement "The amount is computed by the server and never accepted from
  the client" is extended -- the server-computed amount becomes a per-state, per-agreement line-item
  quote (duty pass-through + service fee + procurement fee + delivery fee + GST on services only)
  rather than a flat configured value, and the breakdown is frozen with the order.
- `estamp-intake`: The staff queue and the upload path gain the paid-for denomination as a
  precondition -- a certificate below the paid-for duty amount is rejected rather than silently
  recorded.

## Impact

**Backend code**

- `in.agreementmitra.rules` -- new public API (`StampDutyRules`, `DutyRequest`, `DutyAssessment`,
  `PropertyUse`) plus package-private configuration loading, slab matching, and the denomination
  master. First real code in this module.
- `in.agreementmitra.signing.payment` -- `PaymentPricing` reworked from flat amount to quote
  composition; `PaymentProperties.Amount` superseded; `PaymentOrder` gains the frozen breakdown.
- `in.agreementmitra.signing.agreement` -- `Agreement` gains the selected denomination and the
  duty jurisdiction; the under-stamp acknowledgement is a new audit record.
- `in.agreementmitra.signing.signingrequest.StampIntakeService` / `signing.api.StampIntakeRequest`,
  `StampQueueEntry` -- intake validation against the paid denomination.
- `in.agreementmitra.documents.api.TemplateCatalogApi` -- read-only consumer: the template's `state`
  and `type` supply the duty jurisdiction and property use. No documents-module behaviour change.

**API**

- The checkout/quote surface gains a line-item breakdown response and a denomination selection
  endpoint. `CheckoutSessionResponse` grows the breakdown; no client-supplied amount is introduced.

**Schema (Flyway, forward-only)**

- New tables for the per-state duty rule set, the denomination master, the per-state charge
  configuration, and the under-stamp acknowledgement audit. New columns on the agreement (selected
  denomination, duty jurisdiction) and on the payment order (frozen breakdown). `ddl-auto: validate`
  is unchanged.

**Dependencies**

- None. No new library; the rule model is plain Java over configuration rows. Drools remains a future
  option, deliberately not adopted here.

**Known scope boundary**

- Karnataka duty rules ship in this change, but **no Karnataka template layer set exists yet** (only
  `IN` and `TG` are seeded under `documents/template/sets/`). Until a Karnataka template lands, a
  Karnataka agreement is drafted from the **national** template and the customer selects Karnataka as
  the property state at checkout -- which is exactly the path below, and is why that path is needed
  now rather than later.
- The `IN` national dimension is **not** a stamp jurisdiction and cannot be configured as one. An
  agreement pinned to a national template therefore requires the customer to **explicitly choose the
  property's state** at stamp selection; that choice becomes the duty jurisdiction and is fixed once
  an order exists. A state-dimensioned template still resolves its jurisdiction automatically, with no
  question asked.

**Commercial dependency**

- The GST treatment below (duty excluded from the taxable value as a pure-agent pass-through) needs
  the client's CA to sign off before production. Getting it wrong either overcharges customers or
  creates a GST liability on money that was never revenue.

## PII / Security Review

- **Does this change introduce or move Aadhaar / OTP / VID / signer PII?** No new PII flow. The duty
  calculation consumes only commercial terms already held on the agreement -- monthly rent, security
  deposit, term in months, property use, and the state code. No Aadhaar number, virtual ID, OTP, or
  signer identity data enters the `rules` module, and the module API is deliberately shaped so it
  cannot: `DutyRequest` carries no party fields.
- **Redaction.** The under-stamp acknowledgement audit record stores the agreement id, the computed
  duty, the chosen denomination, the warning version shown, and the timestamp -- it does **not** store
  party names or the property address, and it is not logged verbatim. Quote and pricing logs emit
  amounts and the agreement id only, never the certificate number (already redacted to its last four
  characters by `StampInfo.toString()`), never party names.
- **Secrets.** No new secret. Per-state charge configuration (service fee, vendor fee, GST rate) is
  non-secret commercial configuration and lives in the database, not in environment variables;
  Razorpay credentials are untouched.
- **Untrusted input.** The denomination selection is a client-supplied value and is treated as
  untrusted: it is validated against the state's denomination master server-side, and the payable
  amount is recomputed server-side from the validated selection. A tampered client cannot introduce an
  arbitrary duty, an arbitrary fee, or an arbitrary total.
- **Sandbox and dummy data only** is preserved: the seeded rule sets are public statutory rates, and
  no real duty is remitted to any state by this repo -- staff still purchase certificates out of band.

## Signing FSM Impact

No new states and no changed transitions. The existing `DRAFT -> PDF_GENERATED -> STAMPED ->
SIGN_REQUESTED -> SIGNED | FAILED | EXPIRED` machine, with `PDF_GENERATED -> STAMP_FAILED` as the
terminal stamp branch, is untouched. This change alters what is computed **before** the order is
placed (the quote) and adds a **precondition check** at the existing stamp-intake step: a certificate
below the paid-for denomination is refused, so the request stays in `PDF_GENERATED` (the durable
awaiting-stamp state) instead of advancing to `STAMPED`. A refused upload is an operator-correctable
validation failure, not a `STAMP_FAILED` transition.
