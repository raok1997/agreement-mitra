## 1. Public API (`in.agreementmitra.rules`)

- [x] 1.1 Add `DutyBasis` record with nested `InstrumentKind { LEASE, LEAVE_AND_LICENCE }` and `Usage { RESIDENTIAL, COMMERCIAL }`. The compact constructor validates: non-null state (trimmed, upper-cased), kind, usage and execution date; `termMonths >= 1`; non-negative money; `0 <= rentFreeMonths <= termMonths`; `escalationEveryMonths >= 0`; `counterparts >= 1`. Each failure names its field.
- [x] 1.2 Add `DutyLine(String label, BigDecimal amount)`, `RuleRef(String id, String legalReference, String contentHash)`, and the sealed `DutyOutcome` with `Quoted(long amountPaise, List<DutyLine> breakdown, RuleRef rule, boolean registrationRequired)`, `NeedsAdjudication(String reason, RuleRef rule)` and `Unsupported(String reason)`. All lists are defensive copies.
- [x] 1.3 Add the `StampDutyCalculator` interface with `DutyOutcome quote(DutyBasis basis)`.
- [x] 1.4 Update `rules/package-info.java`: replace "Drools, stub for now" with the stamp duty calculator description and design D1's engine decision.
- [x] 1.5 Unit test `DutyBasisTest`: each validation failure names its field; state normalization.

## 2. Rent schedule and quantities (`rules.duty`)

- [x] 2.1 `RentSchedule`: total rent computed month by month, applying escalation after every `escalationEveryMonths` and skipping rent-free months; average annual rent = total x 12 / term (`DECIMAL128`).
- [x] 2.2 `Quantities`: immutable name -> `BigDecimal` map; `standard(basis, params)` builds the eight standard quantities from design D4; `with(name, value)`; `get` throws on an unknown name.
- [x] 2.3 Unit test `RentScheduleTest`: flat rent; the spec's escalation scenario (252,000.00 / 126,000.00); the rent-free scenario (100,000.00); escalation interval longer than the term; zero rent. _(Implemented together with 2.4 as `RentScheduleAndQuantitiesTest`.)_
- [x] 2.4 Unit test `QuantitiesTest`: `DEPOSIT_NOTIONAL_INTEREST` for a 24-month term at 10 percent; asking for it without the parameter throws.

## 3. Rule model, loader and hash (`rules.duty`)

- [x] 3.1 Rule model records: `RuleSet`, `Slab` (month range, consideration names, `ratePercent` or `fixedAmount`), `Bounds`, `Surcharge`, `Rounding { UP, HALF_UP, DOWN } x unitRupees`, `RegistrationRule`, `RuleCase`.
- [x] 3.2 `RuleSetLoader`: scan `classpath*:rules/stamp-duty/**/*.yaml`, parse with Jackson YAML, reject numeric nodes in money and rate fields, and merge one level from `base` per design D3 (inherit rounding, registration, counterpart duty and surcharges; never inherit slabs, params or cases).
- [x] 3.3 Loader validation throwing `RuleSetDefinitionException` (file + rule id + defect): slab gap or overlap; unknown quantity, counting extension-provided quantities; missing `depositInterestRatePercent`; blank `legalReference`; overlapping effective windows per (state, kind, usage); a rule claiming state `IN`; a non-abstract rule with no slabs; `extends` other than `base`; an unknown `extension` id; an extension bean no rule references; a slab with both or neither of `ratePercent` and `fixedAmount`.
- [x] 3.4 `RuleHasher`: canonical JSON (sorted keys, normalized plain decimals) of the merged rule, excluding `cases` and `counselReview`, then SHA-256 hex.
- [x] 3.5 `RuleSetRegistry`: `find(state, kind, usage, executionDate)`, window-inclusive on `effectiveFrom`, with `effectiveTo` inclusive or open; abstract rules are never returned.
- [x] 3.6 Add `backend/src/main/resources/rules/stamp-duty/base.yaml` (abstract; rounding UP to 1 rupee, registration over 11 months, counterpart duty 0, no surcharges, no slabs).
- [x] 3.7 Unit test `RuleSetLoaderTest` with malformed fixtures under `src/test/resources/rules/stamp-duty-invalid/`, one per defect in 3.3, each asserting the message names the rule and defect. Also test that base inheritance and state override work. _(Fixtures are inline YAML resources in the test rather than files under `stamp-duty-invalid/`; one file there backs the startup-failure integration test.)_
- [x] 3.8 Unit test `RuleHasherTest`: rules differing only in a slab rate hash differently; rules differing only in `cases` or `counselReview` hash the same; a base change changes the state hash; `"0.40"` and `"0.4"` hash the same. _(3.8 and 3.9 are implemented together as `RuleHasherAndRegistryTest`.)_
- [x] 3.9 Unit test `RuleSetRegistryTest`: effective-window boundaries; version A vs version B selection by execution date; `IN` and abstract rules are never selected.

## 4. Engine and extensions (`rules.duty`)

- [x] 4.1 `DutyExtension` interface per design D5 (`id`, `providedQuantities`, `precheck`, `extraQuantities`, `adjust`) and a `NONE` default.
- [x] 4.2 `DutyEngine implements StampDutyCalculator`: the design D4 pipeline in fixed order, emitting a `DutyLine` per step; `IN` short-circuits to `Unsupported`; no slab or rule found gives `Unsupported`; at runtime, check that extension quantities match `providedQuantities`; logging is limited to rule id and outcome type.
- [x] 4.3 `StampDutyConfiguration`: `@Bean` registry built from the loader plus all `DutyExtension` beans, and `@Bean StampDutyCalculator`.
- [x] 4.4 Test fixtures: `src/test/resources/rules/stamp-duty/ZZ/lease-residential.yaml` (three slabs: rate, rate, fixed amount; minimum; maximum; one surcharge; counterpart duty; a `depositInterestRatePercent` param; two effective-window versions) and `ZZ/lease-commercial.yaml` bound to a test `ZzTestExtension` that exercises precheck, extra quantity and adjust. Both carry `cases:` covering every slab boundary and every outcome type.
- [x] 4.5 Unit test `DutyEngineTest` (no Spring context): each calculation-order scenario in the spec (minimum before rounding, surcharge on bounded duty, rounding once at 100.004 -> 10,100 paise); slab boundaries 12/13 and a term beyond every slab; the three rounding modes x units; counterparts; the registration flag leaves the amount unchanged; extension refusal gives `NeedsAdjudication`; an extension quantity shows as a breakdown line; an extension returning an undeclared quantity throws.
- [x] 4.6 Unit test `BreakdownReconciliationTest`: for every `ZZ` case, replaying the breakdown lines reproduces the quoted amount. _(Implemented as `RuleCasesTest.breakdownReplaysToTheQuotedAmount`, over every rule's cases.)_
- [x] 4.7 Parameterized unit test `RuleCasesTest`: loads every rule on the test classpath and asserts each `cases:` entry's outcome type and amount; the failure message names the rule and case. Also add a deliberately wrong case under `stamp-duty-invalid/` and assert the runner reports it. _(The wrong case is inline in the test; `RuleCasesTest` also enforces a case at every slab boundary.)_

## 5. Stamp paper catalog and planner (`rules.duty` + API)

- [x] 5.1 Public API: add `StampPlan` (with sealed `Planned`/`Unplannable` result and `Paper`) and `CatalogRef` records; extend `DutyOutcome.Quoted` with `List<StampPlan> stampPlans` and `CatalogRef catalog` (design D8).
- [x] 5.2 `StampPaperCatalog` model (medium id, `DENOMINATIONS` values + `maxPapers`, or `ANY_AMOUNT` minimum) and `StampPaperCatalogLoader` for `classpath*:rules/stamp-paper/*.yaml` (a directory separate from duty rules, so neither loader sees the other's files): string-only money, content hash, selection by execution date; startup failure on no media, empty denominations, non-positive denomination or `maxPapers`, duplicate medium ids, overlapping windows, or enumeration size over 100,000.
- [x] 5.3 `StampPaperPlanner.plan(legalDutyPaise, medium)`: enumeration with the (total, paper count, larger-denominations-first) ordering for `DENOMINATIONS`; max(duty, minimum) for `ANY_AMOUNT`; `Unplannable` when nothing reaches the duty.
- [x] 5.4 `DutyEngine`: after rounding, select the catalog (missing -> `Unsupported`) and attach one plan per medium; add a `rules/stamp-paper/ZZ.yaml` test fixture with both media kinds; extend `ZZ` rule `cases:` with expected plans.
- [x] 5.5 Unit test `StampPaperPlannerTest`: every planning scenario in the spec (exact 100; 660 -> 700 at limit 3; 660 -> 1000 at limit 1; 900 prefers one 1000; any-amount minimum; 2,500 unplannable while any-amount still plans); non-greedy optimum ({60, 50}, duty 100 -> 50+50); zero duty; plan total is never below duty (property-style over random catalogs and duties).
- [x] 5.6 Unit test `StampPaperCatalogLoaderTest`: one invalid fixture per startup defect in 5.2; catalog hash changes with a denomination and does not affect any duty rule hash.
- [x] 5.7 Unit test in `DutyEngineTest`: a rule with no catalog in effect -> `Unsupported`; Quoted legal duty is identical with and without planning.

## 6. Integration

- [x] 6.1 Integration test `StampDutyCalculatorIntegrationTest` (`@ApplicationModuleTest` on `rules`, no containers): the context starts with the shipped `base.yaml` plus the `ZZ` test fixtures; the injected `StampDutyCalculator` quotes a `ZZ` agreement end to end, including stamp plans for both media; `IN` and an unknown state return `Unsupported`. _(Uses `ApplicationContextRunner` over `StampDutyConfiguration` instead of `@ApplicationModuleTest`: the module has no infrastructure, and a Modulith slice would boot the app's datasource autoconfiguration.)_
- [x] 6.2 Integration test with `ApplicationContextRunner`: pointing the loader at a malformed rule location makes context startup fail with `RuleSetDefinitionException` naming the rule.
- [x] 6.3 Integration check: `ModularityTests` stays green, and `rules` exposes only the package-root API types.

## 7. Docs and close-out

- [x] 7.1 CLAUDE.md: change the `rules` module line from "future; Drools" to the typed stamp duty calculator (design D1), keeping it to one line.
- [x] 7.2 `docs/ROADMAP.md`: state in the `state-stamp-duty-quoting` context that it now builds on `stamp-duty-base-calculator` (wiring into `PaymentPricing`, replacing the allowlist, seeding TG/KA with sourced cases, real issued denominations and counsel review, choosing the fulfilment medium, stock-aware planning against vendor inventory). Record any follow-ups found during apply in the register before archive.
- [x] 7.3 Run `./run-tests.sh test spotbugsMain` (osv-scanner is not installed locally) and report the wall-clock time; `spotlessApply`. _(2026-09-16: `spotlessApply` done and `spotbugsMain` reports 0 findings. The full suite `./run-tests.sh test -Ptest.forks=1` passed after freeing memory: 131 classes, 1268 tests, 0 failures, 0 skipped, BUILD SUCCESSFUL in 4m 02s. That is over the 3-minute budget, but the test classes sum to only 83s, of which the new `rules` tests are 1.1s. The rest is Gradle, compilation and container startup on a single fork with about 2.3 GB free. An earlier attempt with 2 forks crashed from lack of memory.)_
