> Test tiers: the frontend half has a pure predicate (unit, 1.2) and the mounted picker against a
> mocked catalog API (integration at the component/API boundary, 1.3); FAQ copy is static and is
> pinned by the existing visible-FAQ = JSON-LD integration of `LandingPage.vue` with `index.html`
> (2.2). The backend half is tests only (3.x).

## 1. Picker: offer residential state templates only

- [x] 1.1 `frontend/src/components/TemplatePicker.vue`: replace the `NATIONAL_STATE` filter with one
      exported pure predicate `isOffered(t)` — state ≠ `IN` **and** type = `residential`, both
      trimmed and case-normalised (an allow-list, D1). Comment: the v1 residential-only decision; it
      is presentation only and the server gate is authoritative; lifting it goes with the backend
      tripwire (task 3.2). Hide the `filter-type` select when it would offer a single type, and
      reword the header copy so it does not invite choosing a type
- [x] 1.2 Unit — `isOffered`: TG residential offered; `IN` residential, TG commercial and an
      unknown type (e.g. `leave-and-license`) not offered; casing/whitespace normalised
- [x] 1.3 Integration (component) — `TemplatePicker.test.ts`: add "hides commercial templates" — no
      TG commercial card, no type filter offering `commercial`, a search for "commercial" shows no
      card; keep "hides the national (IN) templates" green; re-point the existing "commercial"
      search/select cases at residential rows; a comment on the new test names the backend
      tripwire (3.2) so whoever relaxes it sees the coupling

## 2. Customer-facing copy

- [x] 2.1 `frontend/src/views/LandingPage.vue` FAQ "Which cities do you serve?": "residential or
      commercial" → residential only (e.g. "You can draft residential rental agreements for
      Telangana and Karnataka. …"); mirror the identical text in the `FAQPage` JSON-LD in
      `frontend/index.html`
- [x] 2.2 Unit — `LandingPage.test.ts`: the cities answer names Telangana, Karnataka and
      "residential", and "commercial" appears nowhere in the FAQ; the existing visible-FAQ =
      JSON-LD test stays green
- [x] 2.3 Confirm `CaptureForm.vue` carries no customer-facing commercial copy (grounding: none —
      type is a prop; the only hit is a code comment); no edit expected

## 3. Server answer: pinned by tests (no production code)

- [x] 3.1 Unit — `backend/src/test/java/in/agreementmitra/rules/duty/DutyEngineTest.java`: two `ZZ`
      rules with distinct ids, residential and commercial (local builder or a new `TestRules`
      overload taking id + usage; the existing `rule(slabs, extra)` signature unchanged). Residential
      carries `counselReview: { contentHash: <its contentHash()> }` obtained by a first load; with
      `allowUnreviewed=false` the residential rule is chargeable, the commercial rule is not, and
      `chargeableStates()` = `{ZZ}` (D5)
- [x] 3.2 Unit — tripwire test in `backend/src/test/java/in/agreementmitra/rules/duty/`: load
      `TestRules.DEFAULT_RULE_LOCATIONS` (`classpath*:`) with the `ZzTestExtension` quantities,
      drop state `ZZ`; assert the states holding a `COMMERCIAL` rule are exactly `{TG, KA}` and
      `rule.ref().reviewed()` is false for each. Failure message names the un-hide steps: the
      `isOffered` predicate in `TemplatePicker.vue` and the FAQ "Which cities" wording (D4)
- [x] 3.3 Test support — `TemplateCatalogFixture`: add a `seed(jdbc, state, type)` overload
      (additive; existing callers unchanged)
- [x] 3.4 Integration — `UnreviewedStampDutyGateIntegrationTest` (already
      `allow-unreviewed=false`; same class, no new context): seed a TG commercial entry, create the
      agreement through the API; its stamp quote reports `NOT_CHARGEABLE`; finalise and checkout
      are each refused with `409 JURISDICTION_UNSUPPORTED`
- [x] 3.5 Integration — `TemplateCatalogApiIntegrationTest`: the unfiltered list still returns a
      national (`IN`) entry alongside the commercial one it already asserts (hiding is picker-only)

## 4. Docs and ops

- [x] 4.1 `docs/ROADMAP.md` release ops paragraph: add the precondition (D3) — set
      `RULES_STAMP_DUTY_ALLOW_UNREVIEWED=false` once the residential rules carry their counsel
      reviews (a state still unreviewed becomes unpayable: a late KA review means a TG-only release,
      not keeping `true`); verify the startup "allow-unreviewed=true" WARN is absent on the running
      backend; before the flip, list commercial agreements that are paid or have an open checkout
      and cancel/refund them (expected none)
- [x] 4.2 `deploy/env/backend.env.example`: reword the `RULES_STAMP_DUTY_ALLOW_UNREVIEWED` comment
      to say the release sets it `false` (value unchanged for the beta stack)
- [x] 4.3 `docs/ROADMAP.md`: delete release item 0 (`residential-only-release`) and re-point
      `terms-release-revision`'s "after `residential-only-release`" dependency

## 5. Gates

- [x] 5.1 Frontend: `npm run build` and `npm run lint` from `frontend/`
- [x] 5.2 Backend: `./run-tests.sh check` from `backend/` (report wall-clock; budget ≤ 3 min)

## Coverage

| # | Scenario | Disposition | Where |
|---|---|---|---|
| 1 | template-catalog · National templates are not offered | COVERED | 1.2, 1.3 (existing "hides the national (IN) templates", kept green) |
| 2 | template-catalog · Commercial templates are not offered | COVERED | 1.2, 1.3 |
| 3 | template-catalog · A hidden template stays published | COVERED | 3.5 |
| 4 | jurisdiction-eligibility · A reviewed residential rule does not admit commercial in the same state | COVERED | 3.1 |
| 5 | jurisdiction-eligibility · A commercial order is refused when unreviewed rules are disallowed | COVERED | 3.4 (finalise + checkout; intake/eSign share `refuseUnlessPayable` and are unreachable unpaid — D5) |
| 6 | jurisdiction-eligibility · Shipped commercial rules are unreviewed | COVERED | 3.2 |
| 7 | landing-page · The 11-month answer is state-relative | COVERED | existing `LandingPage.test.ts` (unchanged requirement text; 2.2 keeps it green) |
| 8 | landing-page · Cost and something-goes-wrong questions exist | COVERED | existing `LandingPage.test.ts` (2.2 keeps green) |
| 9 | landing-page · The e-signature answer is not an unconditional yes | COVERED | existing `LandingPage.test.ts` (2.2 keeps green) |
| 10 | landing-page · The cities answer offers residential agreements only | COVERED | 2.2 |
| 11 | landing-page · Visible FAQ equals the JSON-LD | COVERED | existing `LandingPage.test.ts:186` (2.2 keeps green) |
