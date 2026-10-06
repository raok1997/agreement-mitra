## Context

Roadmap release item 0. v1 ships TG + KA **residential**; commercial waits for counsel. Grounding
(2026-10-05):

- **Picker.** `TemplatePicker.vue:101-103` drops `IN` rows client-side
  (`NATIONAL_STATE`); the backend still publishes and lists them. Pinned by
  `TemplatePicker.test.ts` "hides the national (IN) templates". The type filter's options are derived
  from the remaining rows.
- **Copy.** Only customer-facing commercial wording: the FAQ "Which cities" answer
  (`LandingPage.vue:124`) and its JSON-LD twin (`index.html:122`). `CaptureForm.vue` has no type
  selector — `state`/`type` arrive as props from the picker (or from the pinned template in edit
  mode) — and no commercial copy. ToS §2 already says "residential rental agreements".
- **Eligibility.** Rule files are per `(state, usage)`:
  `rules/stamp-duty/{TG,KA}/lease-{residential,commercial}.yaml`, all with `counselReview: null`.
  `DutyEngine.isChargeable(rule) = rule.reviewed() || allowUnreviewed`. `StampQuoting.evaluate`
  resolves the agreement's own rule (state + usage via `DutyBasisMapper.usageOf`) and gates on it;
  `JurisdictionEligibility.require/requireForFulfilment` is called at finalise
  (`SigningRequestService:241`), checkout (`PaymentOrderService:189`, re-check :205), e-stamp intake
  (`StampIntakeService:224`) and eSign initiation (`SigningRequestService:137`).
  `DutyEngine.chargeableStates()` — the `/api/jurisdictions` list — is per state.
- **Config.** Default `false` (`application.yml:241`); local and test `true`; the prod
  `backend.env.example` sets `true` ("sandbox / founding-team beta only").

## Goals / Non-Goals

**Goals:** commercial not offered in the picker; no customer-facing promise of commercial; a
settled, test-pinned answer to "can an API caller pay for a commercial order at release?".

**Non-Goals:** unpublishing the commercial templates; refusing commercial **drafting** through the
API (draft-and-download stays open, as for `IN`); changing `chargeableStates()` to per-usage;
the ToS revision (`terms-release-revision`); flipping the prod flag (an ops step at release — this
CR records it, it does not perform it).

## Decisions

**D1 — One picker offering rule, not two filters.** Replace the `NATIONAL_STATE` filter with one
exported pure predicate `isOffered(t)` = state ≠ `IN` **and** type = `residential` (both trimmed and
case-normalised like today) — an allow-list, so a future type is not offered by accident, matching
the server, where an unknown type maps to no usage (`DutyBasisMapper.usageOf`) and is unpayable. Its
comment names the v1 decision, says it is **presentation only** (the API still accepts
`type=commercial`; the server gate is authoritative) and points at the backend tripwire (D4). Type
filter options, state filter options and search all read the filtered rows, so they follow
automatically; the type filter is hidden when it would offer a single type. *Why not derive hiding
from eligibility:* that needs per-usage `/api/jurisdictions`, and local/beta run with unreviewed
rules allowed, so it would show commercial exactly where we are hiding it. *Alternative rejected:* mark
templates "unlisted" in the set metadata and filter server-side — a catalog schema change and a
second mechanism beside the existing `IN` one; the roadmap asks for "the same way".

**D2 — No server refusal code; the rules already refuse per usage.** The roadmap's test: is paid
eligibility per `(state, type)` rule file? Yes for enforcement. So with unreviewed rules disallowed,
an unreviewed commercial rule is refused at all four gates even when residential in the same state is
reviewed. A parallel "commercial is withheld" config or hard-coded check would be a **second copy of
the eligibility rule** (CLAUDE.md: the same fact in two places) and contradict the
`jurisdiction-eligibility` rule that eligibility is derived from the duty rules alone.
*Alternative rejected:* unpublish commercial — breaks the "published versions are immutable"
contract and strands beta agreements pinned to those ids.

**D3 — The answer depends on `allow-unreviewed=false` at release; make that explicit.** With the flag
`true` (prod beta today) every rule is chargeable and commercial is payable via the API. The release
needs `false` anyway — residential is reviewed by counsel and `true` would also admit any unreviewed
rule — so this is a precondition the release already has, not new work. Record it in the release ops
paragraph of `docs/ROADMAP.md`, with three parts: (a) set `false`; a state whose residential rule is
not yet reviewed then becomes unpayable too, so a late KA review means a TG-only release, **not**
keeping `true`; (b) verify it on the running backend — the startup WARN from
`StampDutyConfiguration` ("allow-unreviewed=true: customers may be charged on UNREVIEWED stamp duty")
must be absent; (c) before the flip, list commercial agreements that are paid or have an open
checkout and cancel/refund them — a paid agreement with a frozen quote passes fulfilment on that quote
(`JurisdictionEligibility.requireForFulfilment`), and a checkout opened under `true` can settle after
the flip. Expected empty (founding team only), but checked, not assumed. Also reword the comment on
`RULES_STAMP_DUTY_ALLOW_UNREVIEWED` in `deploy/env/backend.env.example` to say the release sets it
`false` (the value stays `true` for the beta stack). No startup guard: whether prod is in beta is an
operator decision, and a guard keyed on a profile would refuse the beta config the team runs today.

**D4 — Tripwire on shipped rules.** A unit test loads the rules from
`TestRules.DEFAULT_RULE_LOCATIONS` (`classpath*:` — a single-root `classpath:` pattern can resolve
against the test root only), with the `ZzTestExtension` quantities the `ZZ` fixtures need, drops
state `ZZ`, and asserts the states holding a `COMMERCIAL` rule are exactly `{TG, KA}` and that
`rule.ref().reviewed()` is false for each (there is no `RuleSet.reviewed()`). Its failure message
says: commercial now carries a counsel review — lift the commercial exclusion in
`TemplatePicker.vue`, restore the FAQ wording, and retire this test. That makes D2's safety a
property of the repo rather than of memory. (`reviewed()` compares hashes, so a stale review also
reads as unreviewed — correct here, and the spec says "no counsel review matching its content hash".)

**D5 — The per-usage pin.** Unit (`DutyEngineTest`): two `ZZ` rules with distinct ids, residential
and commercial. `TestRules.rule` hard-codes `id: ZZ-test` / `usage: RESIDENTIAL` and the loader
rejects duplicate ids, so add a local builder (or a `TestRules` overload taking id + usage) without
changing the existing signature. Hash in two passes, as `RuleHasherAndRegistryTest` does: load the
residential rule, read `contentHash()` (the hash excludes `counselReview`), embed
`counselReview: { contentHash: <it> }`, reload. Strict engine → residential chargeable, commercial
not, `chargeableStates()` = `{ZZ}`. Integration (`UnreviewedStampDutyGateIntegrationTest`, already
`allow-unreviewed=false`, added there so no new Spring context): seed a TG commercial catalog entry
(`TemplateCatalogFixture` hard-codes `residential`, so add a `seed(jdbc, state, type)` overload;
additive, no `DELETE FROM template`), create the agreement through the API, assert its stamp quote
reports `NOT_CHARGEABLE` — every refusal shares one 409, so this is what proves usage resolution
reached the commercial **rule** rather than a basis that failed to build — then finalise and checkout
→ `409 JURISDICTION_UNSUPPORTED`. Every TG rule is unreviewed there, so per-usage *discrimination* is
proven by the unit test, by design. Intake and eSign initiation are not separately tested: for an
unpaid agreement they run the same `refuseUnlessPayable(evaluate(...))` path, and an unpaid agreement
cannot reach them.

## Risks / Trade-offs

- [Over-binding] → none: usage comes from the pinned template's catalog dimensions
  (`StampQuoting.dimensionsOf` via `agreement.templateId()`), not from a client field, so a caller
  cannot relabel commercial as residential.
- [Contradictory 409 for a beta commercial agreement once TG residential is reviewed] → the payload
  says rejected `TG` with `TG` in `eligible` and the client shows "not yet available for this
  jurisdiction". Founding-team agreements only; accepted for v1, revisit when commercial returns.

- [Prod release goes out with the flag still `true`] → commercial payable via API. Mitigation: the
  ROADMAP release checklist line (D3); the flag is already listed as a deliberate decision in
  `provision.sh`.
- [Picker shows a state as stampable whose commercial cannot be paid] → moot while commercial is
  hidden; recorded as why `chargeableStates()` stays per state.
- [Counsel reviews KA commercial alongside residential] → D4's tripwire fails on the hash being
  added, forcing the un-hide decision in the same commit.
- [Beta commercial drafts] → still open from My Agreements and draftable; under the release flag they
  cannot be paid. Acceptable — draft-and-download is the documented fallback.

## Migration Plan

None. Frontend redeploy; no schema or config default change. Rollback = revert.
