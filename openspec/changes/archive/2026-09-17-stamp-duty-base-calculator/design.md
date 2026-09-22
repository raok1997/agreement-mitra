## Context

See proposal.md -- Why. Requirements are in `specs/stamp-duty-calculation/spec.md`; this document
only covers how.

- **`rules` is an empty module** today: a `package-info.java` declaring an `@ApplicationModule`
  and saying "Drools, stub for now". Nothing depends on it, so this change sets its shape.
- **The repo already has a layered-YAML precedent.** `documents` loads `base.yaml` plus
  `state-XX.patch.yaml` layers, hashes the result with `CanonicalJson`, and pins that hash on the
  agreement. People reviewing duty rules will expect the same pattern. `CanonicalJson` is
  package-private to `documents.template`, so `rules` cannot reuse it without breaking a module
  boundary.
- **`jackson-dataformat-yaml`** is already a dependency. No new libraries.
- **Consumers come later.** `state-stamp-duty-quoting` will call the API from
  `signing.payment.PaymentPricing` and from the eligibility check. The public API must suit those
  callers, but nothing calls it in this change.

## Goals / Non-Goals

**Goals:**

- A pure calculation core (facts in, outcome out) that unit tests exercise without a Spring
  context.
- The whole calculation for a state is visible in one reviewable, hashed rule file.
- A malformed rule stops the application at startup, never mid-checkout.

**Non-Goals:**

- Persisting quotes, or any database table.
- Durations in days. Terms are whole months, matching the capture form's `durationMonths` (see
  Risks).
- Deciding whether a counsel-review record makes a rule eligible. The record is loaded and
  exposed, and `state-stamp-duty-quoting` decides what it gates.

## Decisions

### D1 -- A typed evaluator, not Drools

The calculation is a fixed pipeline over closed-form formulas: slab lookup, sum, multiply, clamp,
surcharge, round. The rule-engine features Drools adds (forward chaining, agenda, conflict
resolution) do nothing for this problem. They also cost things this domain needs: a step-by-step
breakdown that adds up to the amount, a content hash over reviewable data, and fast plain-JUnit
tests.

Alternatives considered:

- **Drools DRL or decision tables.** Rejected for the reasons above. The large runtime and
  compiler also expand the dependency-scan surface that `securityScan` gates.
- **A general expression language in YAML (SpEL, MVEL, JEXL).** Rejected. Arbitrary expressions
  in legal data make every rule file a small program, cannot be validated before runtime, and bring
  expression-injection concerns. Named quantities plus a fixed pipeline cover the formula shapes we
  know of. The spec's extension hook covers the rest.

This decision updates the `rules` line in CLAUDE.md and the module's `package-info.java`.

### D2 -- Package layout and visibility

```
in.agreementmitra.rules                      (public module API)
  StampDutyCalculator    interface
  DutyBasis              record + nested enums InstrumentKind { LEASE, LEAVE_AND_LICENCE },
                         Usage { RESIDENTIAL, COMMERCIAL }
  DutyOutcome            sealed interface: Quoted | NeedsAdjudication | Unsupported
  DutyLine, RuleRef, StampPlan, CatalogRef   records
in.agreementmitra.rules.duty                 (module-internal, package-private)
  DutyEngine             implements StampDutyCalculator
  RentSchedule           total rent / average annual rent
  Quantities             immutable name -> BigDecimal map
  RuleSet, Slab, Bounds, Surcharge, Rounding, RegistrationRule, RuleCase
  RuleSetLoader          YAML -> merged RuleSet, validation, content hash
  RuleSetRegistry        lookup by (state, kind, usage, executionDate)
  RuleHasher             canonical JSON + SHA-256 (see D6)
  StampPaperCatalog, StampPaperCatalogLoader, StampPaperPlanner   (see D8)
  DutyExtension          hook interface; implementations live in this package
  StampDutyConfiguration @Configuration wiring
```

State extensions live in `rules.duty` next to the engine, so everything stays package-private and
`ModularityTests` sees one exposed surface.

### D3 -- Rule file format and base merge

```yaml
# resources/rules/stamp-duty/base.yaml
abstract: true
id: base
rounding: { mode: UP, unitRupees: 1 }        # UP | HALF_UP | DOWN ; unit 1 | 10 | 100
registration: { requiredWhenTermMonthsOver: 11 }
counterpartDuty: "0"
surcharges: []
```

```yaml
# test resources: rules/stamp-duty/ZZ/lease-residential.yaml  (fictional state)
extends: base
id: ZZ-lease-residential
state: ZZ
instrumentKind: LEASE
usage: RESIDENTIAL
effectiveFrom: 2024-04-01
effectiveTo: null
legalReference: "Fictional Stamp Act, Article 99 (test fixture)"
counselReview: null
extension: null                                # or a registered extension id
params: { depositInterestRatePercent: "10" }
slabs:
  - { minMonths: 1,  maxMonths: 12, consideration: [TOTAL_RENT, REFUNDABLE_DEPOSIT], ratePercent: "0.4" }
  - { minMonths: 13, maxMonths: 60, consideration: [AVERAGE_ANNUAL_RENT, PREMIUM], ratePercent: "0.5" }
  - { minMonths: 61, maxMonths: 120, fixedAmount: "5000" }
minimumAmount: "100"
maximumAmount: null
surcharges: [ { name: "ZZ infrastructure cess", percentOfDuty: "10" } ]
cases:
  - name: "12-month boundary uses slab 1"
    basis: { termMonths: 12, monthlyRent: "10000", refundableDeposit: "30000", executionDate: 2025-01-10 }
    expect: { outcome: QUOTED, amountPaise: 66000 }
```

- **Money and rates are strings** parsed to `BigDecimal`. YAML floats are binary, so `0.1` would
  already be wrong on load. The loader rejects a numeric node in any money or rate field.
- **Merge.** A state rule inherits `rounding`, `registration`, `counterpartDuty` and `surcharges`
  from its `extends` target, and replaces a key it declares. `slabs`, `cases` and `params` are
  never inherited. Only one level of inheritance is allowed, and `base` is the only valid target.
- **Location.** Rules load from `classpath*:rules/stamp-duty/**/*.yaml`, configurable through
  `rules.stamp-duty.locations` so tests can point the loader at invalid fixtures. The `rules` module
  ships no other configuration. Main resources ship only
  `base.yaml`. The `ZZ` fixture lives in test resources, so it can never reach the production jar.
- **Discovery.** A file with `abstract: true` is registered as a merge target and never as a
  selectable rule.

Alternative: reuse the `documents` layer-patch ops (`replaceClause` and similar). Rejected. Those
ops are tree edits suited to a document. A duty rule is a flat record, and key-level replacement is
simpler to reason about.

### D4 -- The pipeline and numeric policy

```
quote(basis):
  if basis.state == "IN"                         -> Unsupported
  rule = registry.find(state, kind, usage, executionDate)   else -> Unsupported
  ext  = extensions[rule.extension] or NONE
  ext.precheck(basis, rule)                      -> NeedsAdjudication | Unsupported | continue
  q    = Quantities.standard(basis, rule.params) + ext.extraQuantities(...)
  slab = rule.slabFor(termMonths)                else -> Unsupported
  c    = sum(q[name] for name in slab.consideration)        line per quantity
  d    = slab.fixedAmount ?: c * ratePercent / 100          line
  d    = clamp(d, min, max)                                 line if a bound bites
  d    = ext.adjust(d, ...)                                 line if changed
  d    = d + sum(d * s.percentOfDuty / 100)                 line per surcharge
  d    = d + counterpartDuty * (counterparts - 1)           line if > 0
  paise = round(d, rule.rounding) * 100                     line
  catalog = catalogs.find(state, executionDate)  else -> Unsupported
  plans = catalog.media.map(m -> planner.plan(paise, m))    (D8)
  -> Quoted(paise, lines, rule.ref, termMonths > registration threshold, plans, catalog.ref)
```

- **Precision.** Intermediate `BigDecimal` values use `MathContext.DECIMAL128`. The only division
  that does not terminate is average annual rent (`total * 12 / term`), and at 34 significant
  digits the error sits far below a paisa. The spec forbids rounding to a *currency unit* before
  the final step, and this policy satisfies it.
- **Counterparts.** The first copy is the original, so counterpart duty applies to
  `counterparts - 1`. Surcharges apply to the duty and not to counterpart duty.
- **Breakdown lines** are `DutyLine(label, amount)`. A replay test adds the lines back up to the
  quoted amount (spec: "Breakdown reconciles").
- **Standard quantities**: `TOTAL_RENT`, `AVERAGE_ANNUAL_RENT`, `MONTHLY_RENT`,
  `REFUNDABLE_DEPOSIT`, `NON_REFUNDABLE_DEPOSIT`, `ADVANCE_RENT`, `PREMIUM`, and
  `DEPOSIT_NOTIONAL_INTEREST` = refundable deposit x `params.depositInterestRatePercent` / 100 x
  term / 12. If a rule references `DEPOSIT_NOTIONAL_INTEREST` without that parameter, the loader
  rejects it.

### D5 -- Extensions are Spring beans bound by id

```java
interface DutyExtension {
  String id();
  default Set<String> providedQuantities() { return Set.of(); }
  default Optional<DutyOutcome> precheck(DutyBasis b, RuleSet r) { return Optional.empty(); }
  default Quantities extraQuantities(Quantities q, DutyBasis b, RuleSet r) { return q; }
  default BigDecimal adjust(BigDecimal duty, DutyBasis b, Quantities q, RuleSet r) { return duty; }
}
```

`providedQuantities()` exists so the loader can validate slab references **at startup** without
running the extension. The engine checks at runtime that `extraQuantities` returned exactly the
declared names. The rule names its extension through `extension: <id>`. This change ships no
production extension. The `ZZ` fixture ships one test extension to exercise all three hooks.

Alternative: a map keyed by rule id, filled in by hand. Rejected, because a missing entry fails
silently. With bean discovery, a dangling `extension:` reference or an unused extension bean fails
startup.

### D6 -- Content hash over the merged computational content

`RuleHasher` hashes the **merged** rule with SHA-256, over canonical JSON (keys sorted, decimals
as normalized plain strings via `stripTrailingZeros().toPlainString()`). It excludes only `cases`
and `counselReview`, and includes `legalReference`, because a changed citation is a changed rule. A
change to the base therefore changes every state rule's hash.

- `cases` are excluded because adding a test must not invalidate a review.
- `counselReview` is excluded because the review record cites the hash, and including it would be
  circular.

A small duplicate of `documents`' canonicalizer is accepted to preserve the module boundary. Making
`CanonicalJson` a shared public utility would be a separate refactor.

### D8 -- Stamp paper planning is a separate step after the legal duty

Legal duty (what the law demands) and payable stamp value (what can be bought) are different numbers
with different owners, so they are computed and reported separately. The duty pipeline (D4) is
unchanged and ends with the legal duty; a `StampPaperPlanner` then runs once per medium.

Why this matters here specifically: no vendor offers on-demand exact-amount e-stamps -- Leegality and
Digio both fulfil from pre-stocked physical inventory per state and denomination. So the planned
denominations are what drive the customer's price and the inventory we must stock.

**Catalog data** -- one file per state, versioned independently of duty rules (a treasury changing
issued denominations is not a change in duty law, and must not invalidate a reviewed rule's hash):

```yaml
# test resources: rules/stamp-paper/ZZ.yaml  (fictional; own directory so the duty-rule glob never
# picks it up)
state: ZZ
effectiveFrom: 2024-04-01
effectiveTo: null
source: "Fictional treasury notification (test fixture)"
media:
  - id: physical
    kind: DENOMINATIONS
    denominations: ["10", "20", "50", "100", "500", "1000"]
    maxPapers: 3
  - id: e-stamp
    kind: ANY_AMOUNT
    minimumAmount: "10"
```

**Output shape:**

```java
record StampPlan(String mediumId, Result result) {
  sealed interface Result {
    record Planned(List<Paper> papers, long totalPaise, long excessPaise) implements Result {}
    record Unplannable(String reason) implements Result {}
  }
  record Paper(long denominationPaise, int count) {}
}
// Quoted gains: List<StampPlan> stampPlans, CatalogRef catalog (id, source, contentHash)
```

**Algorithm.** Denominations and paper limits are small (a state issues roughly a dozen values; an
instrument carries a handful of papers), so enumerate multisets of size 1..`maxPapers` with
repetition -- C(12+5-1, 5) = 4,368 at twelve values and five papers -- and pick by
(total ascending, paper count ascending, denominations descending lexicographically). This is exact,
trivially deterministic and easy to test. The loader rejects a medium whose enumeration would exceed
100,000 combinations, so a data typo cannot turn a quote into a CPU spike.

Alternatives considered:

- **Greedy largest-first.** Rejected: not optimal for arbitrary denomination sets (e.g. {60, 50} with
  duty 100 greedily gives 60+60=120, optimum is 50+50=100), and the excess is real customer money.
- **Coin-change DP over rupee amounts.** Rejected: optimal but sized by the duty amount, not the
  catalog, and needs a separate paper-count dimension. Enumeration is simpler at our scale.
- **Folding denominations into the duty rule's `rounding`.** Rejected: it would conflate legal
  rounding with purchasability, lose the excess figure, and tie the rule hash to treasury stock.
- **Planning against live vendor inventory.** Deferred (proposal, out of scope). The planner takes
  the medium as a value, so a later stock-aware caller can pass a medium narrowed to in-stock
  denominations and counts without changing the algorithm's contract beyond per-denomination caps.

### D7 -- Validation at startup, cases in the test suite

`RuleSetLoader` runs inside a `@Bean` factory. Any defect throws `RuleSetDefinitionException` naming
the file, rule id and defect, which stops the context. The checks are the list in the spec's
startup requirement, plus rejecting numeric YAML nodes (D3).

Worked `cases:` run in a parameterized unit test (`RuleCasesTest`) that loads every rule on the
test classpath. They do **not** run at startup: a startup self-test would put test logic on the
production boot path, and the build gate already enforces the cases.

## Risks / Trade-offs

- [Whole-month terms cannot express "11 months 29 days" style drafting] -> Capture already uses
  `durationMonths`. If a state's slab boundary depends on days, `DutyBasis` gains `termDays` in a
  later change, and slabs keep their month form.
- [One-level inheritance may prove too shallow, e.g. a state-wide base shared by residential and
  commercial] -> A deliberate limit for now. A second level is an additive loader change once a
  real state needs it.
- [The fictional `ZZ` fixture proves the engine, not any real law] -> Intended. Real rules arrive
  with sourced cases and counsel review in `state-stamp-duty-quoting`.
- [Engine features no real state uses yet (surcharges, counterparts, extensions) are speculative]
  -> Each maps to a pattern called out in the design discussion, and each is small and covered by
  the `ZZ` fixture. If none is used by the second real state, remove it.
- [Duplicated canonical-JSON logic between `documents` and `rules`] -> About 40 lines, both
  unit-tested. A shared-kernel extraction is noted as a possible follow-up, not required.

## Migration Plan

This change is purely additive. It adds no migration and no required configuration, and nothing calls the
new bean. To roll back, revert the commit.
