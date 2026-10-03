> **Running the gates on this machine.** `osv-scanner` is not installed locally, so `./gradlew check`
> fails closed at the `securityScan` task -- that is the gate working, not this change breaking. The
> runnable backend gate here is `./gradlew test` plus `./gradlew spotbugsMain`. The Gradle daemon OOMs
> on this 8 GB machine when Docker and Vite are both up, so run tests in class batches and read
> `backend/build/test-results/test/*.xml` as the evidence rather than trusting a single exit code.
> Report the elapsed wall-clock time for any full-suite run (CLAUDE.md budget: `check` <= 3 minutes).

## 1. Duty engine: per-slab bounds

- [x] 1.1 Add `minimumAmount` / `maximumAmount` to `RuleSet.Slab` (nullable `BigDecimal`), keeping the
      record's existing shape and nullability conventions.
- [x] 1.2 Add both keys to `SLAB_KEYS` in `RuleSetLoader` and parse them with the same
      `nonNegativeDecimal` helper the rule-level bounds use.
- [x] 1.3 Reject at load time, with the rule named in the message: a negative slab bound; a slab
      `minimumAmount` above its `maximumAmount`; a slab bound declared on a `fixedAmount` slab (there
      is no computed consideration to bound -- per design D1 this is a defect, not a no-op).
- [x] 1.4 Apply slab bounds in `DutyEngine.compute` immediately after the rate/fixed step and
      **before** the rule-level `bounds` block, emitting a distinguishable breakdown line per
      `DutyLine.Kind` (add `SLAB_MINIMUM` / `SLAB_MAXIMUM`, or carry the distinction in the existing
      MINIMUM/MAXIMUM label -- decide and state it in the code comment).
- [x] 1.5 Confirm the pipeline order in `DutyEngine`'s class javadoc still describes what the method
      does, and update it to name the new step.

## 2. Duty engine tests (unit)

- [x] 2.1 A slab maximum bounds a duty computed in that slab (1,700.00 -> 500.00) and the breakdown
      carries the attributing line.
- [x] 2.2 A slab maximum on one slab does **not** bound a duty computed in a neighbouring slab
      (600.00 stays 600.00). This is the specific regression the design exists to prevent.
- [x] 2.3 Slab bounds apply before rule bounds: a slab maximum of 500.00 with a rule minimum of 600.00
      yields 600.00.
- [x] 2.4 A slab minimum raises a duty inside its own slab and leaves neighbours alone (the symmetric
      case; per design D1 no shipped rule uses it, so this test is its only exercise -- say so in the
      test's own comment so it is not deleted as unused).
- [x] 2.5 Slab bounds compose correctly with surcharges, counterpart duty and the single rounding
      step -- the bounded value is what the surcharge is computed on, and nothing rounds early.
- [x] 2.6 Loader rejection tests for each defect in 1.3, asserting the message names the rule.
- [x] 2.7 Confirm the Telangana rules are unaffected: their existing worked `cases:` still pass
      unchanged (they declare no slab bounds, so this is a by-construction check made explicit).

## 3. Karnataka stamp duty rules (data)

- [x] 3.1 Write `backend/src/main/resources/rules/stamp-duty/KA/lease-residential.yaml`:
      `extends: base`, `state: KA`, `instrumentKind: LEASE`, `usage: RESIDENTIAL`; slabs 1-12 at
      0.5% with a slab `maximumAmount: "500"` and 13-60 at 1%, both over
      `[AVERAGE_ANNUAL_RENT, REFUNDABLE_DEPOSIT]`; `counterpartDuty: "500"`;
      `registration: { requiredWhenTermMonthsOver: 12 }`; `counselReview: null`.
- [x] 3.2 Write `rules/stamp-duty/KA/lease-commercial.yaml`: same shape, slabs 1-12 at 0.5% with **no**
      cap, 13-120 at 1%, 121-240 at 2%. Note in the file that above one year Karnataka does not split
      on usage -- the 13-120 rate is identical to residential's 13-60 -- so a future editor does not
      "fix" the apparent duplication.
- [x] 3.3 Header both files with the source block in the style of the Telangana rules: the primary
      IGR URL, its self-declared "Updated till 20th April, 2017" vintage, a per-figure confidence
      marker, and an explicit line that the Karnataka Stamp (Amendment) Act 2023 may have moved these
      rates and is the first thing counsel must check. Do not invent an `effectiveFrom` -- state what
      the chosen date actually represents.
- [x] 3.4 Annotate the three open readings inline where each one bites: average annual rent on a
      sub-year term (design D2), the Explanation's reach over clause (1) (D3), and the flat
      counterpart duty being a deliberate over-reading with no reachable call site (D4).

## 4. Karnataka rule worked cases (data, verified in the build)

- [x] 4.1 Residential `cases:`: 12-month boundary uncapped (INR 5,000/month, no deposit -> INR 300);
      13-month boundary (INR 5,000/month -> INR 600, proving the cap does not leak); 11-month with the
      cap biting (INR 20,000/month + INR 100,000 deposit -> INR 500); 60-month boundary; one case with
      escalation; 61 months -> `UNSUPPORTED`.
- [x] 4.2 Commercial `cases:`: 12-month uncapped (INR 20,000/month + INR 100,000 deposit -> INR 1,700,
      the deliberate contrast with 4.1's residential INR 500); 13-month; 120-month boundary
      (INR 9,000); 121-month boundary (INR 18,000); 240-month boundary; 241 months -> `UNSUPPORTED`.
- [x] 4.3 Include the expected `plans:` on at least one case per file so the catalog wiring is pinned
      by the rule data too, not only by the catalog test.

## 5. Karnataka stamp paper catalog (data)

- [x] 5.1 Write `backend/src/main/resources/rules/stamp-paper/KA.yaml` with two media: `e-stamp`
      (`kind: ANY_AMOUNT`) and `stamp-paper` (`kind: DENOMINATIONS`), and the default `PLANNED` offer
      policy -- explicitly **not** Telangana's `SINGLE_PAPERS`.
- [x] 5.2 Source and date-stamp the e-stamp claim (Kaveri Online / SHCIL) and mark its confidence
      honestly; mark the physical denominations UNVERIFIED per design D5, with a note that they affect
      only the override options and never the legal duty or the recommended value.
- [x] 5.3 Record in the file that an ops procurement channel for Karnataka e-stamp certificates does
      not exist yet, so nobody reads the catalog as evidence that fulfilment works.

## 6. Karnataka rule + catalog tests (unit / integration)

- [x] 6.1 The Karnataka rules load, validate and hash at startup alongside the existing rules.
- [x] 6.2 The Karnataka catalog's offer policy is `PLANNED` and a duty no paper can reach is still
      plannable via the e-stamp medium (INR 1,700 -> an exact INR 1,700 plan).
- [x] 6.3 `StampOptions` for a Karnataka quote: the recommended, pre-selected option is the exact duty
      and is **not** marked below duty; each denomination below the duty is offered and **is** marked
      below duty. Assert it does not behave like the Telangana single-paper case.
- [x] 6.4 Registration reporting: 12 months -> not required, 13 months -> required.
- [x] 6.5 `KA` appears in `chargeableStates()` / jurisdiction eligibility when unreviewed rules are
      allowed, and is absent when they are not.

## 7. Karnataka template layers (data)

- [x] 7.1 `documents/template/sets/rental/state-KA.patch.yaml`: add `stampDutyAmount`
      (`source: system`, placeholder `"Provision for stamp duty"`) and `registrationChargesBorneBy`
      (default `tenant`, options owner/tenant/shared); add the Karnataka governing-law, stamp-and-
      registration, stamp-amount (`showWhen: stampDutyAmount > 0`) and any tenancy-protection clauses;
      add the `Statutory (Karnataka)` section with `optional: false`. `version: 1`.
- [x] 7.2 `sets/rental/state_type-KA-residential.patch.yaml`: `removeClause stampRegistrationClause`;
      `replaceSection "Now This Agreement Witnesseth"` re-authored without it; `replaceSection
      "In Witness Whereof"` as the mandatory signature block; `overrideField jurisdictionCity` default
      `Bengaluru`; `reorderSections` using the **rental** set's section titles.
- [x] 7.3 `sets/commercial/state-KA.patch.yaml`: the same overlay in commercial terms
      (`registrationChargesBorneBy` default `lessee`, options lessor/lessee/shared), section
      `optional: false` -- deliberately unlike the Telangana commercial layer (design D6).
- [x] 7.4 `sets/commercial/state_type-KA-commercial.patch.yaml`: as 7.2 but with the **commercial**
      set's titles (Lessor / Lessee / Schedule of Premises) and `quietEnjoymentClause` retained in the
      covenant list.
- [x] 7.5 Make the registration threshold in the Karnataka clause text agree with the rule's
      `requiredWhenTermMonthsOver: 12` (design D7) -- do not copy Telangana's "eleven (11) months"
      wording.
- [x] 7.6 Head each file with the comment discipline the Telangana layers use, including why the
      statutory section is mandatory and the coupling to the `stampRegistrationClause` removal.

## 8. Template tests (integration)

- [x] 8.1 `(KA, residential)` resolves: the statutory section renders with no optional sections
      active; exactly one stamp-and-registration clause renders and it is the Karnataka one; the
      jurisdiction covenant names Bengaluru; the capture form carries no stamp-duty-amount input and a
      pre-stamp draft shows the provision placeholder.
- [x] 8.2 `(KA, commercial)` resolves with the equivalent assertions and the commercial section order.
- [x] 8.3 Section ordering: `Statutory (Karnataka)` sits after the covenant and annexure sections and
      before `In Witness Whereof`, in both sets.
- [x] 8.4 Parity: every required field in both Karnataka resolutions is aggregate-backed or defaulted,
      so generate-as-draft stays in parity with preview.
- [x] 8.5 `ModularityTests` stays green.

## 9. Supporting edits

- [x] 9.1 `TemplateCatalogSeeder.STATE_DISPLAY_NAMES`: add `"KA" -> "Karnataka"` (note `Map.of` grows
      past two entries -- keep the existing style).
- [x] 9.2 Seeder test: the discovered rows include `(KA, residential)` and `(KA, commercial)` with the
      `Karnataka` display name, and a re-run inserts no duplicate.
- [x] 9.3 Update `ProductionRentalLayerSetTest.theAlwaysOnJurisdictionCovenantIsUnfilledOutsideTelangana`:
      its assertions still hold, but its comment's claim that the gap "reaches KARNATAKA" is now false.
      Correct the comment, add a Karnataka assertion showing a named court, keep the national gap
      pinned, and rename the method if the new name is more honest.

## 10. Customer-facing copy

- [x] 10.1 Read `frontend/src/content/termsOfService.ts` and `scripts/render-terms.mjs` first: the
      terms are the single source and `docs/TERMS-OF-SERVICE.md` is **rendered** from them, with
      `termsOfService.test.ts` failing on drift. Edit the data module, never the markdown.
- [x] 10.2 Update clause 5 ("Where we can stamp and eSign") to state what is actually true. Karnataka
      is not stampable in a deployment that has not allowed unreviewed rules and has no e-stamp
      procurement channel -- do not write copy that implies fulfilment this change does not build.
- [x] 10.3 Bump `TERMS_LAST_UPDATED` and re-run the render script so the markdown face matches.
- [x] 10.4 Update the `LandingPage.vue` FAQ answer that says we are starting with Telangana, to the
      same standard of accuracy.
- [x] 10.5 Frontend tests covering the changed copy stay green (`npm run test`), plus `npm run lint`.

## 11. Register and documentation

- [x] 11.1 `docs/ROADMAP.md`: delete the `ka-stamp-duty-and-template` follow-up row. Do not add a
      "done" entry anywhere -- the archive is the completion record.
- [x] 11.2 `docs/ROADMAP.md`: correct the `national-jurisdiction-city-unfilled` row, which cites
      Karnataka as its evidence of impact. The national hole stays open; its Karnataka exposure does
      not.
- [x] 11.3 Add a follow-up register row for the Karnataka e-stamp procurement channel (ops), and any
      other follow-up this change deliberately does not fold in. Record the slug verbatim in the flow
      journal.
- [ ] 11.4 NOT DONE, deliberately. `docs/COUNSEL-BRIEF.md` is an unsent, formally structured brief
      (Questions 1-8 across Parts A-D, with a numbered ask section and annexures) about the
      INSTRUMENT -- its characterisation, clause placement, terms of service. Rate verification is
      not what it asks. The Telangana precedent agrees: `tg-stamp-duty-counsel-review` records the
      TG rate questions in the follow-up register, not in the brief. The five Karnataka questions
      are in design.md Open Questions and in the new `ka-stamp-duty-counsel-review` register row.
      Restructuring an outbound counsel document is the user's call, not a side effect of this CR.

## 12. Gates

- [x] 12.1 `./gradlew test` green in class batches; report elapsed wall-clock and cite the XML
      results, not just the exit code.
- [x] 12.2 `./gradlew spotbugsMain` clean.
- [x] 12.3 `npm run build` and `npm run lint` green from `frontend/`.
- [x] 12.4 State plainly in the summary that `./gradlew check` was not run to completion because
      `osv-scanner` is absent locally, and that this is the fail-closed gate behaving correctly.
