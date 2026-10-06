## 1. Documents API: not-found-only form lookup

- [x] 1.1 Add `Optional<FormSchema> findForm(String state, String type)` to `documents/api/TemplateFormApi.java`, javadoc: empty **only** when no published template exists for the pair; every other failure throws. Make the resolver signal "no published template" distinctly (a `ResolutionException` subtype or reason, raised at `RegistryLayerSource.java:50-55`); `TemplateFormService.findForm` maps only that to empty; `formFor` delegates (`orElseThrow` the existing `ResourceNotFoundException`) (D1)
- [x] 1.2 Unit tests in the documents template tests: `findForm` returns the schema for a resolvable pair and empty for a pair with no published template; an invalid effective template or malformed layer still throws; `formFor` still throws `ResourceNotFoundException` for the not-found pair

## 2. Rules: escalation breakdown line and overflow

- [x] 2.1 Add `DutyBasis.withoutEscalation()` (canonical constructor, escalation `0, 0`, all else copied) with a unit test that it preserves every other component (D3)
- [x] 2.2 Add `ESCALATION` to `rules/DutyLine.Kind`, excluded from `isDelta()`; update the record and kind javadoc (escalation lines are informational and skipped by replay) (D3)
- [x] 2.3 In `DutyEngine`, for a rate slab, recompute the consideration over `basis.withoutEscalation()` via `Quantities.standard` + `extraQuantities` (no `precheck`); when the uplift is positive emit `DutyLine(ESCALATION, plain(p) + "% rent escalation every " + n + " months", uplift HALF_UP to 2dp)` after the quantity lines and before `BASE` (D3)
- [x] 2.4 In `DutyEngine`, return `Unsupported` when the duty is not representable in paise (the `longValueExact()` at `DutyEngine.java:244`) instead of letting `ArithmeticException` escape (D3)
- [x] 2.5 Unit tests in `DutyEngineTest`: 24 months at INR 10 / 5% every 12 under an `AVERAGE_ANNUAL_RENT` slab yields quantity 123 and an escalation line `5% rent escalation every 12 months` of 3.00 between quantity and base; KA-style `[AVERAGE_ANNUAL_RENT, REFUNDABLE_DEPOSIT]` consideration counts only the rent uplift; escalation with `rentFreeMonths > 0`; 11 months, zero escalation and a fixed-amount slab yield none; 3,599 months at the largest accepted rent with 100% every 12 under an uncapped slab yields `Unsupported`. Exact-kind-list assertions in `SlabBoundsTest`/`DutyEngineTest` on escalating bases must include `ESCALATION`
- [x] 2.6 Confirm `RuleCasesTest.breakdownReplaysToTheQuotedAmount` (already filters `BASE || isDelta()`, `RuleCasesTest.java:64-66`) stays green across every rule's worked cases -- no edit expected

## 3. Signing: quote from the deed's effective terms

- [x] 3.1 `DutyBasisMapper.from(agreement, dimensions, Supplier<DefaultLookup> escalationDefault, clock)`: escalation = `Long.parseLong(captured.trim())` in `[0, 100]` → else call the supplier (`NOT_FOUND` → return empty; `NONE` → zero; value → it) → no `BigDecimal` parsing of capture text; execution date = captured `agreementDate` that parses and passes `PlausibleDates` → `agreement.draftExecutionDate()` → today in India; update the class javadoc and every call site, including all `DutyBasisMapperTest` calls (D1, D2)
- [x] 3.2 `StampQuoting`: inject `TemplateFormApi`; build the supplier over `findForm(dimensions.state(), dimensions.type())` with the pinned dimensions as carried (not the upper-cased duty state), mapping the `rentEscalationPercent` field's `defaultValue` to `DefaultLookup` (integral `Number` → `BigDecimal.valueOf(n.longValue())`, else `NONE`; no field → `NONE`; empty form → `NOT_FOUND`). Update the direct constructions in `JurisdictionEligibilityTest.java:54` and `StampValueReferenceTest.java:42` with an `@Mock TemplateFormApi` (D1)
- [x] 3.3 Unit tests in `DutyBasisMapperTest`: blank capture + default 5 escalates 5% every 12; captured `0` beats the default; `+7`, `07`, ` 7 ` capture as 7; `NONE` → no escalation; `NOT_FOUND` → empty; `150`, `4.5`, `5.0`, `-5`, `abc`, `1E+999999999` each fall to the default; the supplier is not called when the capture is usable; draft execution date used when capture has none; an implausible captured date (e.g. `+99999-01-01`) falls to the draft date; captured date beats the draft date
- [x] 3.4 Integration parity test in `StampDutyCheckoutIntegrationTest` (D4): two 24-month Telangana residential agreements (a new fixture, not the 11-month one behind the `130_000` assertions), one with `rentEscalationPercent` absent, one captured as the default read from `TemplateFormApi.findForm` -- identical quotes. Confirm a documents validator test pins blank → default; add one if absent
- [x] 3.5 Integration test in `StampDutyCheckoutIntegrationTest`: the escalation-absent 24-month quote's `AVERAGE_ANNUAL_RENT` reflects 5% escalation and its breakdown carries an `ESCALATION` line with `delta: false` (amounts compared numerically -- the API strips trailing zeros); creating the order freezes that line and the frozen quote returns it with `delta: false`
- [x] 3.6 `StampQuoteResponse.Line` gains `delta: boolean`, set from `Kind.isDelta()` on the live quote (`PaymentOrderService.java:348`) and `DutyLine.Kind.valueOf(kind).isDelta()` on a frozen one (`:367`) (D3)
- [x] 3.7 Keep `ModularityTests` green (signing → documents only through `documents.api`)

## 4. Frontend: show the escalation line

- [x] 4.1 `src/api/stampQuote.ts`: add `delta: boolean` to the line type. `StampQuoteStep.vue`: `isDelta` reads `line.delta`; an `ESCALATION` line gets its own `lineLabel` branch rendering "Includes {label}" (not through the digit-grouping path) with an unsigned rupee amount; update the stale "every later kind is a signed delta" comment (`StampQuoteStep.vue:93-96`) (D3)
- [x] 4.2 Component tests in `StampQuoteStep.test.ts`: an escalation line renders "Includes 5% rent escalation every 12 months" with an unsigned INR 3.00; a breakdown without one renders no escalation text; signing follows `delta`, not `kind`

## 5. Docs

- [x] 5.1 `docs/ROADMAP.md`: update the v1 list entry (line ~108) to this change; at archive delete the `stamp-quote-capture-defaults` and `rental-default-commercial-terms` register rows
- [x] 5.2 Check the terms / FAQ / home page for any promise about how the stamp quote is computed or about default terms; update in this change if one is now inaccurate

## Coverage

| Scenario | Disposition | Test |
|---|---|---|
| stamp-selection / A cleared escalation field is quoted at the deed's default | COVERED | 3.3 `DutyBasisMapperTest`, 3.4/3.5 `StampDutyCheckoutIntegrationTest` |
| stamp-selection / A captured escalation wins over the default | COVERED | 3.3 `DutyBasisMapperTest` |
| stamp-selection / No template default means no escalation | COVERED | 3.3 `DutyBasisMapperTest` |
| stamp-selection / A template that cannot be found makes the quote unavailable | COVERED | 3.3 `DutyBasisMapperTest` (`NOT_FOUND` → empty), 1.2 `findForm` not-found |
| stamp-selection / An out-of-range or non-integer escalation never reaches the arithmetic | COVERED | 3.3 `DutyBasisMapperTest` |
| stamp-selection / A stored draft's execution date selects the rule | COVERED | 3.3 `DutyBasisMapperTest` |
| stamp-selection / Escalation is named in the breakdown | COVERED | 4.2 `StampQuoteStep.test.ts`, 3.5 `StampDutyCheckoutIntegrationTest` (line reaches the API) |
| stamp-selection / No escalation, no line | COVERED | 4.2 `StampQuoteStep.test.ts` |
| stamp-duty-calculation / Breakdown reconciles to the amount | COVERED | 2.6 `RuleCasesTest` |
| stamp-duty-calculation / Content hash tracks the rule | COVERED | existing rule-loading test (unchanged) |
| stamp-duty-calculation / Escalation that raised the consideration is itemised | COVERED | 2.5 `DutyEngineTest` |
| stamp-duty-calculation / A duty too large to represent is unsupported, not an error | COVERED | 2.5 `DutyEngineTest` |
| stamp-duty-calculation / Escalation that changed nothing is not itemised | COVERED | 2.5 `DutyEngineTest` |
