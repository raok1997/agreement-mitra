## Why

We cannot say what stamp duty an agreement owes. `PaymentPricing.priceFor` returns one configured
flat price, `StampInfo.dutyAmount` only records what staff paid *after* buying the certificate, and
the `jurisdiction.eligible` allowlist exists precisely because nothing computes duty
(`docs/LEGAL-POSTURE.md`). `state-stamp-duty-quoting` is meant to fix that, but it bundles two very
different risks: **engine semantics** (slabs, min/max, rounding, rent schedules -- pure, testable
logic) and **legal content** (which Act, article and rate applies in a state -- blocked on counsel via
`rental-deed-lease-vs-licence`). Building the engine first, with no real state admitted, lets the
arithmetic land and be proven now while the legal content waits for its reviewer.

Stamp duty rules vary by state in **rates** far more than in **formula shape** (percentage of a
consideration, chosen by term slab, bounded, rounded, surcharged). One engine plus per-state rule
data is therefore the right unit; per-state Java classes are not.

## What Changes

- **New `StampDutyCalculator` public API in the `rules` module.** Input `DutyBasis` (state-agnostic
  facts: duty state, instrument kind, usage, execution date, term, rent schedule, deposits, premium,
  counterparts). Output sealed `DutyOutcome`: `Quoted` (amount in paise, line-by-line breakdown, rule
  reference, registration-required flag), `NeedsAdjudication`, or `Unsupported`.
- **A fixed calculation pipeline**: select rule by (state, instrument, usage, execution date) ->
  precheck -> named quantities (total rent and average annual rent derived from the full escalation
  schedule, deposits, premium, notional deposit interest) -> term slab -> consideration -> rate or
  fixed amount -> min/max -> surcharges -> counterpart duty -> round once. Exact decimal arithmetic
  throughout.
- **Declarative, versioned rule data** under `rules/stamp-duty/`: an **abstract** `base.yaml` holding
  shared defaults and **no rates**, and per-state rule files that `extends: base`. Each rule carries
  `effectiveFrom`/`effectiveTo`, a `legalReference`, a content hash, an optional counsel-review
  record, and embedded worked `cases:`.
- **Fail-closed by construction.** No matching rule, a national (`IN`) duty state, or an abstract
  rule yields `Unsupported` -- never a zero amount.
- **Payable stamp value is planned against the stamp paper a state actually issues.** Legal duty is
  rarely a purchasable amount: physical stamp paper comes in fixed denominations, and our stamp
  vendors fulfil from pre-stocked, per-denomination inventory (no on-demand exact-amount e-stamp API
  exists). Each state gets a versioned **stamp paper catalog** declaring one or more stamp media --
  either `DENOMINATIONS` (the values issued, and the most papers one instrument may carry) or
  `ANY_AMOUNT` (an exact-amount certificate with a minimum). A `Quoted` outcome carries, next to the
  legal duty, a **stamp plan per medium**: the paper combination whose total is the smallest
  achievable value at or above the duty (ties: fewest papers, then larger denominations first), its
  total, and the excess over duty. A medium that cannot reach the duty within its paper limit is
  reported unplannable, never silently under-stamped.
- **`DutyExtension` hook** for a state whose rule cannot be expressed as data: it may refuse
  (precheck), contribute a named quantity, or adjust the duty. Keyed by rule-set id.
- **Load-time validation** that fails application startup on a malformed rule: term-slab gaps or
  overlaps, unknown quantity names, blank `legalReference`, overlapping effective windows.
- **A fictional `ZZ` rule set in test resources** that exercises every engine feature. **No real
  state is seeded** in this change.
- **Engine choice: a typed evaluator, not Drools.** CLAUDE.md names Drools for `rules`; this
  proposal is the explicit decision point. Duty rules are closed-form piecewise-linear formulas, and
  an auditable breakdown plus content-hashed data matter more here than inference. The `rules`
  module's `package-info.java` and CLAUDE.md's module line are updated to match.

**Explicitly out of scope** (owned by `state-stamp-duty-quoting`): wiring into `PaymentPricing`,
replacing the `jurisdiction-eligibility` allowlist, the pre-payment confirmation screen, the
certificate-vs-quote reconciliation at intake, persisting a quote, choosing which stamp medium an
order is fulfilled through, and seeding any real state (TG/KA) or its real denominations. Also out:
planning against **live vendor inventory** (which denominations are in stock right now) -- the plan
here is against what the state issues; stock-aware planning belongs with `StampProvider`. Also out: market-value / guideline-rate based duty for long leases, registration fees, and
Model Tenancy Act filings.

## Capabilities

### New Capabilities

- `stamp-duty-calculation`: computing the stamp duty owed on a rental instrument from normalized
  agreement facts and versioned per-state rule data, with an auditable breakdown and fail-closed
  outcomes, and planning the payable stamp value against each state's available stamp paper media.

### Modified Capabilities

_None._ No existing requirement changes: nothing consumes the calculator yet, and
`jurisdiction-eligibility` remains the sole eligibility authority until `state-stamp-duty-quoting`.

## Impact

- **Code**: new public types and an internal `duty` package in `in.agreementmitra.rules` (currently a
  stub). No change to `signing`, `documents` or `identity`. `ModularityTests` must stay green; the
  new API has no inbound callers yet.
- **Resources**: `backend/src/main/resources/rules/stamp-duty/base.yaml`; test-only `ZZ` rule files
  and a `ZZ` stamp paper catalog.
- **Dependencies**: none new -- `jackson-dataformat-yaml` is already on the classpath.
- **Database**: no migration. Quotes are not persisted in this change.
- **Signing FSM**: no transition touched.
- **Docs**: CLAUDE.md module line for `rules`; `docs/ROADMAP.md` register row for
  `state-stamp-duty-quoting` updated to depend on this change.
- **PII / security review**: **none**. The calculator takes monetary amounts, dates, a state code and
  enum classifiers only -- no Aadhaar, OTP, VID, names, contacts or addresses -- and makes no
  outbound call. It logs no inputs beyond rule ids. No secrets. Sandbox + dummy data only is
  preserved; the only rule data is a fictional `ZZ` fixture.
