## Context

See proposal.md -- Why. Requirements are in `specs/`. The facts this design builds on:

- **Calculator** (`stamp-duty-base-calculator`, must be archived first).
  `rules.StampDutyCalculator.quote(DutyBasis)` returns `Quoted | NeedsAdjudication | Unsupported`.
  `Quoted` carries the legal duty in paise, a replayable breakdown, `RuleRef(id, legalReference,
  contentHash)`, `registrationRequired`, one `StampPlan` per medium and a `CatalogRef`. Rules and
  catalogs are YAML under `rules/stamp-duty/**` and `rules/stamp-paper/*.yaml`, validated at startup.
  A rule's `counselReview` is loaded as an opaque map and excluded from its hash. **`RuleRef` does not
  expose it yet** (see D3).
- **Checkout.** `PaymentOrderService.startCheckout` (`signing/payment/PaymentOrderService.java:135`)
  runs: authorize -> reachable parties -> settled order returned -> `jurisdiction.require` -> reuse the
  outstanding order, or `pricing.priceFor` -> `razorpay.createOrder` -> `PaymentOrder.create`.
  `payment_order` rows are write-once (`V17`), and a partial unique index allows one CREATED order per
  agreement.
- **Eligibility.** `JurisdictionEligibility.require(UUID)` is called at four gates: finalise
  (`SigningRequestService:228`), checkout (`PaymentOrderService:161`), intake
  (`StampIntakeService:199`) and eSign (`SigningRequestService:134`). The duty jurisdiction is the
  pinned template's `state` via `TemplateCatalogApi`.
- **Agreement facts.** `Agreement` has typed `monthlyRent`, `securityDeposit`, `startDate`, `endDate`
  and `termMonths()`. The capture data holds `agreementDate` (optional), `durationMonths` and
  `rentEscalationPercent` (annual). The template type (`residential` / `commercial`) comes from
  `TemplateCatalogApi`. There are no keys for premium, advance or non-refundable deposit.
- **Frontend.** The flow is `CaptureForm.vue` -> `ContactConfirmation` -> `finaliseAndPay` ->
  `payForAgreement` (`api/payments.ts:196`), which opens Razorpay straight away. The price is never
  shown beforehand.
- **Latest migration** is `V19`; `stamp-duty-amount-from-certificate` also expects to take the next
  one.

## Goals / Non-Goals

**Goals:**

- One source of eligibility truth: the duty rules.
- The customer sees duty, options and total before any Razorpay call; the server alone computes the
  total.
- What was quoted, charged and must be bought is frozen together and cannot drift.
- No real customer is charged on unreviewed duty law unless someone deliberately allowed it, and that
  shows in the logs.

**Non-Goals:**

- Correcting the TG legal content. Figures are seeded as researched and marked unverified; counsel
  review is a data edit, not code.
- Refunding or absorbing a certificate price difference (LEGAL-POSTURE step 3).
- Planning mixed stamp paper + challan combinations.
- Choosing a property state for national-template agreements.
- Karnataka.

## Decisions

### D1 -- Build on `StampDutyCalculator`, not the previous `StampDutyRules` design

The earlier version of this change designed DB-seeded rule tables and its own API. That engine now
exists as YAML rules with startup validation, content hashes and worked cases, so this change only adds
data and wiring. The alternative, DB rows for rules, was rejected: rule changes would become migrations
with no load-time validation or cases, and it would duplicate a shipped capability.

### D2 -- Telangana rule and catalog data

Files: `rules/stamp-duty/TG/lease-residential.yaml`, `rules/stamp-duty/TG/lease-commercial.yaml` and
`rules/stamp-paper/TG.yaml`. Both rules have `instrumentKind: LEASE` (the rental base's own reading,
pending `rental-deed-lease-vs-licence`) and `effectiveFrom: "2021-09-02"`, the latest confirmed
department revision we could date. The actual Art. 31 order is not dated (see Open Questions).

| Field | Value | Source | Confidence |
|---|---|---|---|
| Article | Schedule I-A Art. 31 | legitquest copy; CAG AP report 2015 | MEDIUM |
| < 12 months | 0.4% of total rent | legitquest | MEDIUM (a secondary site says 0.5%) |
| 12-60 months | 0.5% AAR residential / 1% other | legitquest | MEDIUM |
| 61-120 months | 1% AAR residential / 2% other | legitquest | MEDIUM |
| 121-240 months | 6% AAR | legitquest | MEDIUM |
| Refundable deposit | **added to the consideration** | secondary sites disagree | LOW -- open conflict |
| Minimum / maximum | none | legitquest | MEDIUM |
| Rounding | up to the whole rupee | statute rounds in paise; whole rupee chosen | LOW -- conservative choice |
| Counterpart (Art. 22) | INR 50 per extra copy | G.O.Ms.120/2015 | MEDIUM |
| Registration | required for every term (`requiredWhenTermMonthsOver: 0`) | AP Act 4 of 1999 amending s.17(1)(d) | MEDIUM -- Telangana carry-over unconfirmed |
| Stamp paper | INR 10/20/50/100, `maxPapers: 5` | registration.telangana.gov.in FAQ (denominations HIGH); paper limit not found (LOW) | mixed |
| Challan | `ANY_AMOUNT`, minimum 0 | same FAQ | HIGH |

- **Residential slabs stop at 60 months**, the rental template's maximum. Commercial stops at 240.
  Anything beyond is `Unsupported`, never approximated.
- **Deposit.** Including the deposit is the **conservative** reading: over-collecting is refundable,
  but under-stamping makes the deed inadmissible. The conflict is recorded in the rule file.
- **Rounding** up to the whole rupee never under-stamps.
- **Provenance.** Every UNVERIFIED marker and source URL lives in YAML comments next to its figure,
  and `legalReference` names the article and says "UNVERIFIED". Worked `cases:` cover every slab
  boundary, using the spec's figures.

### D3 -- Counsel gate: a review bound to the content hash, plus an explicit allowance

A rule is **chargeable** when `counselReview.contentHash` equals the rule's computed hash, or when
`rules.stamp-duty.allow-unreviewed=true`.

- **Calculator change.** Add `boolean reviewed` to `RuleRef`, computed in the loader as "review
  present and its `contentHash` equals the rule hash". It is a small additive change inside `rules`
  and keeps the opaque map internal. `stamp-duty-calculation` needs no requirement change, because the
  review record's meaning belongs to this change's `stamp-duty-rules` capability.
- **Startup.** `StampDutyConfiguration` logs each loaded rule id, its reviewed flag and the allowance.
  With the allowance on, it also logs a WARN naming each unreviewed rule.

Alternatives rejected:

- **A `prod` profile guard.** No `prod` profile exists (`prod-readiness-preflight`).
- **Gating in `signing` by a list of reviewed ids.** That would be a second list, and the allowlist
  problem again.

### D4 -- Eligibility becomes a quote

`JurisdictionEligibility` keeps its public shape (`require(UUID)`, `eligible()`), and its internals
change:

```
require(agreementId):
  agreement missing                 -> pass (unchanged: callers 404)
  frozen quote exists for its order -> eligible iff that quote's rule id is still loaded   [intake/eSign]
  else basis = DutyBasisMapper.from(agreement, template)   (no template / unresolvable -> refuse)
       outcome = calculator.quote(basis)
       eligible iff Quoted && ruleRef chargeable && recommendedOption(outcome) present
  refuse with ConflictException.jurisdictionUnsupported (distinct kind, unchanged)
eligible():   -- the picker disclosure
  states with >= 1 loaded chargeable rule
```

- **Frozen quotes at intake and eSign.** A paid order must not be stranded by a later rate edit. The
  check that its rule still exists stops a paid order whose jurisdiction was deliberately removed.
- **Deletions.** `JurisdictionProperties` and `jurisdiction.*` config are removed, and the
  `jurisdiction-eligibility` spec is replaced as in the deltas.
- **Rejected alternative:** keeping the allowlist as an extra filter. That leaves two sources of truth,
  which the spec explicitly forbade.

### D5 -- `DutyBasisMapper` (signing, package-private)

| DutyBasis | From |
|---|---|
| `dutyState` | template `state` |
| `instrumentKind` | `LEASE` |
| `usage` | template `type`: `residential` -> RESIDENTIAL, `commercial` -> COMMERCIAL, else refuse |
| `executionDate` | capture `agreementDate`, else `LocalDate.now(clock)` in `Asia/Kolkata` |
| `termMonths` | `agreement.termMonths()` |
| `monthlyRent` | `agreement.monthlyRent()` |
| `escalation` | capture `rentEscalationPercent` (default 0 if absent) every 12 months |
| `rentFreeMonths` | 0 |
| `refundableDeposit` | `agreement.securityDeposit()` |
| other amounts | 0 |
| `counterparts` | 1 |

- **Rent-free months are 0**, even though the commercial template's `fitOutMonths` exists: fit-out is
  not necessarily rent-free, and 0 gives the higher, conservative duty.
- **Execution date.** `agreementDate` is the only date the deed prints as execution. When it is blank,
  the draft renders today (see `stamp-duty-amount-from-certificate` D4), and the quote uses the same
  rule.

### D6 -- Stamp options and pricing

```
options(outcome):
  planned  = plans with Planned result
  recommended = min(planned.totalPaise)   ties: catalog media order        (>= duty by construction)
  below    = { d | d in DENOMINATIONS media, d < duty }  sorted desc, each a single paper
  options  = [recommended] + below   (deduplicated by value)

total(stampValue) = fee.base + max(0, stampValue - fee.includedStampValue)   -- Money, minor units
```

`PaymentPricing.priceFor(agreementId)` becomes `price(StampQuoteSelection)`. `PaymentProperties.Amount`
becomes `Fee(baseMinorUnits = 49900, includedStampValueMinorUnits = 10000, currency = INR)`, bound
from `payment.fee.*`. Keeping the ToS numbers in config means a pricing change is not a deploy.

Rejected: offering every multi-paper combination below duty. The practice is a single token paper,
and a combinatorial list is unreadable.

### D7 -- API and persistence

**`GET /api/agreements/{id}/stamp-quote`.** It uses the same ownership check as
`GET /{id}/payment` and returns:

```json
{ "available": true,
  "dutyMinorUnits": 84000, "currency": "INR",
  "breakdown": [{ "kind": "QUANTITY", "label": "TOTAL_RENT", "amount": "165000" }, "..."],
  "registrationRequired": true,
  "rule": { "id": "TG-lease-residential", "legalReference": "...", "reviewed": false },
  "warningVersion": "under-stamp-v1",
  "options": [ { "stampValueMinorUnits": 84000, "belowDuty": false, "recommended": true,
                 "totalMinorUnits": 123900, "medium": "challan" },
               { "stampValueMinorUnits": 10000, "belowDuty": true, "recommended": false,
                 "totalMinorUnits": 49900, "medium": "stamp-paper" } ] }
```

- It is computed fresh on every call; nothing is persisted.
- If an order already exists, it returns that order's frozen quote with `"frozen": true`.

**`POST /api/agreements/{id}/payment/order`** takes the body `{ "stampValueMinorUnits": 84000,
"underStampAcknowledgement": { "warningVersion": "under-stamp-v1" } }`:

1. A settled order is reported as today; an outstanding one is resumed with its frozen quote (the body
   is ignored).
2. Otherwise eligibility is checked, then the quote and options are recomputed.
3. `stampValueMinorUnits` must equal an option's value, else `400`.
4. A below-duty option needs a matching `warningVersion`, else `400` carrying the current version.
5. The price is computed, `razorpay.createOrder` is called, then **`PaymentOrder.create` and
   `StampQuote.create` are saved in one transaction.**

**Migration `V<next>__stamp_quote.sql`.** The number is taken at apply time, coordinated with
`stamp-duty-amount-from-certificate`.

```sql
CREATE TABLE stamp_quote (
  payment_order_id       UUID PRIMARY KEY REFERENCES payment_order(id),
  agreement_id           UUID NOT NULL REFERENCES agreement(id),
  duty_minor_units       BIGINT NOT NULL,
  stamp_value_minor_units BIGINT NOT NULL,
  below_duty             BOOLEAN NOT NULL,
  medium_id              VARCHAR(64) NOT NULL,
  rule_id                VARCHAR(128) NOT NULL,
  rule_content_hash      CHAR(64) NOT NULL,
  rule_reviewed          BOOLEAN NOT NULL,
  catalog_content_hash   CHAR(64) NOT NULL,
  execution_date         DATE NOT NULL,
  registration_required  BOOLEAN NOT NULL,
  breakdown              JSONB NOT NULL,
  ack_warning_version    VARCHAR(64),
  ack_identity_id        UUID,
  ack_at                 TIMESTAMPTZ,
  created_at             TIMESTAMPTZ NOT NULL,
  CONSTRAINT ack_iff_below_duty CHECK (below_duty = (ack_warning_version IS NOT NULL AND ack_at IS NOT NULL))
);
CREATE INDEX stamp_quote_agreement ON stamp_quote(agreement_id);
```

- **Why a separate table.** `payment_order` stays write-once and provider-shaped; the quote is domain
  data. A 1:1 primary key on the order id gives "frozen with the order" by construction.
- **Acknowledgement actor.** `ack_identity_id` is the signed-in identity when present, else null.
  Anonymous owners use the link-scoped session, and the record still holds version and time.
- **Rejected:** columns on `agreement`, because a later order would overwrite the audit.
- `CheckoutSessionResponse` and `PaymentProgressResponse` gain `dutyMinorUnits` and
  `stampValueMinorUnits` (nullable for pre-migration orders).

### D8 -- Stamp intake reconciliation

In `StampIntakeService.attachResolved`, after `jurisdiction.require` and before scan validation:
`reference = frozenQuote.stampValue` when a paid order has one, else the legal duty recomputed now
(waived payments). If `command.dutyAmount` is below the reference, throw a new
`ConflictException.stampValueBelowPaid` (problem type `stamp-value-below-paid`) and audit the outcome
`REFUSED_STAMP_VALUE`. The staff queue projection (`StampQueueEntry`) gains `paidStampValue` and
`belowDutyChosen`. Because it reads only `stamp_quote`, it exposes no rent or deposit.

### D9 -- Frontend

- `src/api/stampQuote.ts`: a `getStampQuote(id)` type mirroring D7.
- `src/components/StampQuoteStep.vue` shows the duty, a collapsible breakdown, the registration notice,
  and the option radios with totals, with the recommended option pre-selected. Choosing a below-duty
  option reveals the warning text and an unchecked "I understand" checkbox, and the pay button stays
  disabled until it is ticked.
- `CaptureForm.vue` inserts the step between `ContactConfirmation` and `finaliseAndPay`.
- `payForAgreement(id, selection)` sends the body.
- Layout uses Tailwind utilities; the component has no responsive logic.

## Implementation notes (2026-09-16)

Where the code deliberately differs from the decisions above:

- **D3/D4 -- chargeability lives in `rules`.** `StampDutyCalculator.isChargeable(RuleRef)` and
  `chargeableStates()` own the `rules.stamp-duty.allow-unreviewed` policy next to the property, and
  `signing` has no `chargeable(...)` helper. `RuleRef.reviewed` is as designed.
- **D4 -- frozen-quote path.** `JurisdictionEligibility.requireForFulfilment` (intake, eSign) passes a
  PAID agreement that has a frozen quote without checking that its rule id is still loaded. A
  jurisdiction deliberately removed after payment therefore does not block fulfilment of an
  already-paid order.
- **D6 -- denominations on the plan.** Offering "single papers below the duty" needs each medium's
  issued denominations, so the public `StampPlan` gained `denominationsPaise` (empty for an
  any-amount medium).
- **D7 -- errors and routing.** An invalid choice is `StampChoiceInvalidException` -> 400
  `stamp-choice-invalid` with `reason` + `warningVersion`. `SecurityConfig` permits
  `GET /api/agreements/*/stamp-quote`, and the handler scopes it to the owner like payment progress.
- **D8 -- existing fixtures.** Fulfilment integration fixtures attach a INR 10,000 certificate
  (covering any fixture's recomputed duty). Payment fixtures choose an INR 100 stamp value through
  `support/StampChoices`, keeping their 49900-paise amounts.
- **D6 -- offer policy (2026-09-17 product decision).** A catalog may declare
  `offer: { mode: SINGLE_PAPERS, denominations, preselect }`, which the public `CatalogRef.offer`
  (`StampOffer`) carries and which is included in the catalog hash. Telangana offers only a single
  INR 100 paper, pre-selected. The INR 832-style exact value is no longer offered, because exact
  amounts need a challan or franking step outside the online flow. Below-duty still requires the
  audited acknowledgement. Other catalogs default to `PLANNED` (the original D6 behaviour).
- **D9 -- status page.** "Complete payment" resumes a frozen order on its stored choice, and opens
  the stamp step only when no order exists to resume.

## Risks / Trade-offs

- [The TG figures may be wrong (deposit treatment, the 0.4% vs 0.5% conflict)] -> the counsel gate
  defaults to off; rule data carries UNVERIFIED markers; a correction is a YAML edit plus cases, and
  frozen quotes keep past orders consistent.
- [**Registration required for every term** is shown to customers of 11-month agreements] -> it is
  what the researched law says (MEDIUM). Counsel must confirm the notice wording before production.
  Showing it is safer than hiding a compulsory registration.
- [A below-duty choice sells a knowingly under-stamped deed] -> an explicit, versioned, audited
  acknowledgement; ToS wording and warning text are counsel items; it is a user-directed product
  decision.
- [SHCIL e-stamp probably does not exist for TG, yet staff intake calls it an "e-stamp certificate"]
  -> no code impact (intake records any certificate), but an ops question for the ops owner. See Open
  Questions.
- [Paper + challan combinations are not planned] -> for any duty above INR 500 the challan medium
  already gives the exact value, so the recommended option is correct. Only the medium label may
  differ from what staff buy, and it is recorded as a follow-up.
- [Deployment silently refuses TG after upgrade] -> the proposal's deployment note says so; set
  `RULES_STAMP_DUTY_ALLOW_UNREVIEWED=true` deliberately for beta, which logs a WARN.
- [Two open changes edit `attachResolved` and take a migration number] -> land
  `stamp-duty-amount-from-certificate` first; this change rebases its intake edit and takes the next
  free `V<n>`.

## Migration Plan

1. Archive `stamp-duty-base-calculator`.
2. Land `stamp-duty-amount-from-certificate` (or rebase onto it).
3. Deploy this change with `RULES_STAMP_DUTY_ALLOW_UNREVIEWED=true` for the founding-team beta, and
   remove `ELIGIBLE_JURISDICTIONS` / `PAYMENT_AMOUNT_MINOR_UNITS` from the environment. Outstanding
   CREATED orders from before the upgrade have no `stamp_quote` row: they resume as today, their
   progress reports null duty, and intake falls back to the recomputed legal duty.
4. Record counsel review in the TG rule files and remove the allowance before the first external
   customer.

**Rollback:** revert the deploy. The migration only adds a table, so the previous version ignores it.
Orders placed with a quote keep their amounts.

## Open Questions

- Which Government Order and date set the current Art. 31 rates? Counsel item; it only changes
  `effectiveFrom` and cited sources.
- Is a refundable security deposit part of the Art. 31 consideration in Telangana? Data change only.
- Does the AP amendment making every lease compulsorily registrable apply in Telangana? It changes one
  rule field and the notice wording.
- What exactly do staff buy for TG today, given that SHCIL does not list Telangana? Ops question; it
  changes only which catalog medium label is shown.
