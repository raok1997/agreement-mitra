> **Rewritten 2026-09-16.** The 2026-08-29 version (its own `StampDutyRules` API and DB-seeded
> rules, line-item + GST pricing, Karnataka + Telangana) is in git history. It predated the published
> pricing terms and `stamp-duty-base-calculator`. This version is **Telangana only**, builds on that
> calculator, and prices by the published terms.

## Why

We charge a flat INR 499 for every agreement and cannot say what stamp duty it owes.
`PaymentPricing.priceFor` ignores its argument. Staff buy a certificate afterwards, and nothing checks
it against what the customer paid for. Meanwhile `docs/TERMS-OF-SERVICE.md` section 7 already promises:
**"total = INR 499 + the amount by which the duty exceeds INR 100"**, and **"you are shown the total,
and the duty inside it, before you pay"**. Neither is true in code. `docs/LEGAL-POSTURE.md` requires
this before the first external customer.

`stamp-duty-base-calculator` built the engine: `rules.StampDutyCalculator` computes a legal duty with
an auditable breakdown and plans it against the stamp paper a state issues. No real state is seeded,
and nothing calls it. Telangana is the launch state (`jurisdiction.eligible` defaults to `TG`), so this
change seeds Telangana and wires the calculator into checkout.

## What Changes

- **Telangana duty rules.** Residential and commercial lease rules go into `rules/stamp-duty/TG/`,
  under Indian Stamp Act Schedule I-A **Article 31** as applied in Telangana. A TG stamp paper catalog
  goes into `rules/stamp-paper/TG.yaml`, covering physical non-judicial paper (INR 10/20/50/100) and
  challan for the balance.
  - **Every figure is UNVERIFIED.** The rates come from a legal publisher's copy of the Schedule, not
    a Government Order, and secondary sources disagree. Each rule cites its source and carries
    `counselReview: null`.
- **Counsel gate on charging.** Paid fulfilment is refused for a rule with no recorded counsel review
  unless `rules.stamp-duty.allow-unreviewed=true`. That flag is for sandbox and founding-team beta
  only; it defaults to `false`. So we never charge real customers on unreviewed duty law, and nothing
  depends on anyone remembering that.
- **The allowlist is replaced by the calculator (BREAKING for configuration).**
  `jurisdiction.eligible` / `JurisdictionProperties` are removed. An agreement is eligible for paid
  fulfilment when its duty jurisdiction has a rule in effect that is reviewed (or allowed unreviewed),
  and the calculator quotes it with at least one plannable stamp option. That leaves one source of
  eligibility truth, as `jurisdiction-eligibility` required of this change. The national template
  (`IN`) stays draft-only.
- **Stamp quote before payment.** A new owner-scoped `GET /api/agreements/{id}/stamp-quote` returns:
  - the legal duty and its breakdown;
  - whether registration is required;
  - the rule's citation and review status;
  - the stamp options, each with the resulting total.

  A new checkout step between contact confirmation and the Razorpay window shows them.
- **Bounded stamp selection, including below the duty.** The recommended option is pre-selected: the
  cheapest plannable stamp value **at or above** the duty. The customer may also choose a single
  stamp paper **below** the duty, which is common local practice. That requires an explicit
  acknowledgement that an under-stamped instrument is inadmissible in evidence until duty and penalty
  are paid. The warning version, both amounts and the actor are persisted as an audit record.
- **Pricing follows the published terms (BREAKING for the charged amount).**
  `total = base fee (INR 499) + max(0, chosen stamp value - included stamp value (INR 100))`.
  - The stamp value is the chosen option's value, not the raw legal duty.
  - The amount is computed server-side; the client sends only its choice, which is validated against
    the recomputed options.
  - `payment.amount.*` becomes `payment.fee.base-minor-units` and
    `payment.fee.included-stamp-value-minor-units`.
- **The quote is frozen with the order.** Duty, chosen value, breakdown, rule id and hash, catalog
  hash, execution date and registration flag are stored with the payment order. A later rate change
  cannot move what was charged, and the choice is fixed once an order exists.
- **Stamp intake is reconciled.** The staff queue shows the paid-for stamp value and marks a
  below-duty choice. Intake refuses a certificate whose duty amount is below the paid-for stamp value.

**Out of scope, recorded as follow-ups before archive:**
- Karnataka rules and template.
- A property-state choice for agreements drafted from the national template.
- Planning a mixed stamp paper + challan combination.
- Live vendor stock.
- Refund or absorption when the certificate costs differently (LEGAL-POSTURE step 3).
- A registration workflow.

## Capabilities

### New Capabilities

- `stamp-duty-rules`: which jurisdictions have seeded duty rules and stamp paper catalogs, their
  provenance and verification markers, and the counsel-review gate that decides whether a rule may be
  charged.
- `stamp-selection`: the pre-payment stamp quote, the bounded stamp options with a recommended
  default, the audited acknowledgement for a below-duty choice, and fixing the choice once an order
  exists.

### Modified Capabilities

- `jurisdiction-eligibility`: eligibility comes from a reviewed, quotable duty rule instead of a
  configured allowlist. Modifies "Paid fulfilment requires an eligible duty jurisdiction", "The
  national dimension cannot be admitted by configuration" and "An unknown or unconfigured jurisdiction
  fails closed".
- `payment-processing`: adds that the payable total follows the published pricing rule from the chosen
  stamp value, that the quote is frozen with the order, and that an agreement without a valid stamp
  choice cannot be paid for.
- `estamp-intake`: the staff console shows the paid-for stamp value and below-duty marker (modifies
  "Staff have a console listing orders awaiting a stamp"), and intake refuses a certificate below the
  paid-for value (added).

## Impact

- **Depends on** `stamp-duty-base-calculator` being archived first; its `stamp-duty-calculation`
  capability is the API used here. It is also sequenced with the open
  `stamp-duty-amount-from-certificate`: both edit `StampIntakeService.attachResolved`, and both need a
  migration number.
- **Blocked for production** (not for build) on:
  - `rental-deed-lease-vs-licence`, since the instrument kind decides the article;
  - counsel review of the TG rule, the stamp options and the under-stamp warning text.
- **Backend**:
  - `rules`: TG resource files only, no engine change.
  - `signing.payment`: pricing, quote freezing, order request body.
  - `signing.agreement`: `JurisdictionEligibility` rewritten, `JurisdictionProperties` deleted, a new
    `DutyBasis` mapper from the agreement and template.
  - `signing.signingrequest`: stamp intake check.
  - Staff queue projection.
  - `signing` -> `rules` becomes a new module dependency on the root API only.
- **API**:
  - New `GET /api/agreements/{id}/stamp-quote`.
  - `POST /api/agreements/{id}/payment/order` gains a body `{ stampValueMinorUnits,
    underStampAcknowledgement? }`.
  - The checkout and progress responses carry the frozen quote summary.
  - Intake adds a distinct `409` for a certificate below the paid-for value.
- **Frontend**: new `api/stampQuote.ts`, a stamp step component in the `CaptureForm` pay flow, and
  `payForAgreement` sends the choice.
- **Schema**: one forward-only Flyway migration (next free number at apply time) adding a
  `stamp_quote` table keyed by payment order, with acknowledgement columns.
- **Config**: `jurisdiction.eligible` / `ELIGIBLE_JURISDICTIONS` removed; `payment.amount.*` replaced;
  `rules.stamp-duty.allow-unreviewed` added. **Deployment note:** with the default `false`, TG checkout
  is refused until counsel review is recorded. Set the flag deliberately for beta.
- **Docs**:
  - `LEGAL-POSTURE.md` steps 1-2 marked implemented, step 3 still open.
  - `TERMS-OF-SERVICE.md` section 7 gains the below-duty choice (counsel wording).
  - CLAUDE.md and ROADMAP follow-up rows.
- **Signing FSM**: no transition changes. A refused intake leaves the request in `PDF_GENERATED`, as
  any other intake refusal does.
- **PII / security review**:
  - **Personal data:** none new. `DutyBasis` carries amounts, dates, a term, a state code and enums;
    no party data reaches `rules`. The quote endpoint is owner-scoped like payment progress. The
    acknowledgement record stores agreement id, order id, amounts, warning version, actor identity id
    and timestamp, but no names, contacts or address.
  - **Logging:** only rule id and outcome type.
  - **Untrusted input:** the stamp choice is client-supplied, validated against the server-recomputed
    options, and the total is recomputed server-side.
  - **Secrets:** none new.
  - Sandbox and dummy data only is preserved; the TG figures are public statutory material.
