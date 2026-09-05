# Verification notes -- 2026-09-05

Written while triaging why this change sat at 71/88 with unchecked items in section 9
(**tests**, not manual drives). The question was whether those items are bookkeeping drift or
genuine gaps. Answer: **both**, split below. This file exists so step 2 does not restart from zero.

## Build evidence (this session)

`./gradlew spotlessApply` clean (no drift), then `./run-tests.sh` -> `check` **BUILD SUCCESSFUL**
in 3m22s: **911 tests, 0 skipped, 0 failures, 0 errors** across 116 result files.
`ModularityTests` green. JaCoCo gate passed. `osvScan` over `gradle.lockfile` (191 packages)
"No issues found"; SpotBugs/FindSecBugs clean. Frontend `npm run security:scan` clean
(367 packages), `npm run test` 156/156, `vue-tsc --noEmit` clean. Live stack up: `:8090` health
`200`, SPA `:5174` `200`.

That run resolved **6.7, 6.8, 9.27** (ticked, with evidence sub-bullets in `tasks.md`).

## Section 9 coverage triage

Sources read: `signing/recovery/{RecoveryDeliveryServiceTest,RecoveryIntegrationTest,
RecoveryRateLimiterTest}.java`, `signing/contact/{ContactGateIntegrationTest,
PartyReachabilityTest}.java`, `signing/agreement/TrackingReferenceTest.java`.

### Covered -- bookkeeping drift, safe to tick

- **9.3** -- `TrackingReferenceTest`: normalisation, checksum accept/reject (mistype + transposition),
  retired-format refusal. **Ticked.**
- **9.18** (E2E pay -> capture message -> open link) -- `RecoveryIntegrationTest.
  aPaidUnownedAgreementSendsTheLinkToEveryParty` + `theEmailedLinkOpensTheAgreement`.
  *Not yet ticked -- confirm the first test drives payment confirmation rather than the recovery
  endpoint before ticking.*
- **9.21b** (every party gets a link at confirmation) -- `aPaidUnownedAgreementSendsTheLinkToEveryParty`,
  reinforced by `RecoveryDeliveryServiceTest.sendsToEveryPartyNotJustOne`. Same caveat as 9.18.

### Partially covered -- needs a narrow addition, not a rewrite

- **9.4** eligibility matrix. `anUnpaidAgreementSendsNothing` and
  `aClaimedAgreementSendsNothingAndItsLinkStopsWorking` cover UNPAID and owned. **The `WAIVED` row
  has no test.**
- **9.23** terms/parties not editable by a link-only caller. `ContactGateIntegrationTest.
  theTermsFreezeDidNotMoveWithTheContactsFreeze` and `theContactsRouteCannotChangeAnythingButContacts`
  are adjacent but assert the contacts route, not a link-only caller against terms/parties.
- **9.25** no enabled channel -> nothing dispatched, responses unchanged. Unit side covered by
  `PartyReachabilityTest.aChannelAbsentFromConfigurationIsDisabledNotEnabled`; the **integration**
  assertion (responses unchanged) is missing.

### Not covered -- genuine gaps

- **9.9** send-on-confirmation idempotent across repeated confirmations. No test found. This is the
  guarantee task 5.2 claims; it is asserted nowhere.
- **9.15** order creation ignores contact details supplied in its own request body.
- **9.16** eSign initiation applies the reachability rule, rejecting a party it previously admitted.
- **9.20** a link keeps working after an arbitrary delay while paid and unowned.
- **9.21a** a non-paying party can open their link and complete remaining fulfilment steps.
- **9.22** a recovered agreement stays unowned and is still claimable afterwards.
- **9.26** a dispatch failure at payment confirmation does not fail the confirmation.
  **Do not mistake `RecoveryIntegrationTest.aTotalMailFailureStillAnswersTheSameWay` for this** --
  that asserts the *recovery endpoint's* response shape, not that confirmation survives a dispatch
  failure. Different producer, different guarantee.

## Archive blocker independent of the above

**10.5** -- `tasks.md` states outright that Q1a-Q4 in `design.md` must be resolved or explicitly
deferred *before archiving*. That precondition stands regardless of how section 9 lands.

## Disposition

This change is **not archive-ready**. Seven genuine test gaps plus three partials plus 10.5.
It belongs in its own implementation pass, not in the archive batch with the changes whose only
remaining work is a live drive.
