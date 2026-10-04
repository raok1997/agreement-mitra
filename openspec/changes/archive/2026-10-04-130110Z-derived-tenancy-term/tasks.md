## 1. Backend: the `derived` field source

- [x] 1.1 Add `DERIVED` to `FieldSource` and extend `from(String)` to parse the `derived` token; update the enum javadoc so it describes three provenances (user / system / derived) and states that `derived` means "the server computes it, show it but do not ask for it"
- [x] 1.2 Add a `derived()` accessor to `Field` beside `systemSourced()`
- [x] 1.3 Add `"derived"` to the `source` enum in `template-definition.schema.json` and `layer-patch.schema.json`
- [x] 1.4 Relax `TemplateDefinitionValidator`: keep rejecting `system` + `required`; allow `derived` + `required` (the projector normalizes requiredness -- see 2.1)
- [x] 1.5 Confirm `TemplateNodeBinder` needs no change beyond `FieldSource.from` accepting the new token, and that an absent `source` still normalizes to `null` so unrelated templates keep their content hash

## 2. Backend: project a derived field read-only

- [x] 2.1 In `FormProjector`, project a derived field instead of skipping it, marking it read-only and forcing `required: false`; leave the `systemSourced()` skip exactly as it is
- [x] 2.2 Add the read-only marker to the `FormField` API DTO in `documents/api`, emitted only when true so existing schema payloads are byte-identical

## 3. Backend: derive the tenancy term in document projection

- [x] 3.1 Add a package-private whole-month helper in `documents/template` (whole months, END-INCLUSIVE -- the end date is the tenancy's last day -- trailing partial month truncated; `Period.between(start, end.plusDays(1)).toTotalMonths()` semantics. Originally specified end-exclusive; corrected 2026-10-03, see design D4)
- [x] 3.2 In `DocumentProjectionService`, substitute a derived `durationMonths` computed from the submitted `startDate`/`endDate` into the data map on every render path, discarding any submitted value unconditionally; leave `durationMonths` unset when either date is absent or unparseable
- [x] 3.3 Verify the substitution runs before validation and compilation, and alongside (not instead of) the existing `withSystemValues` substitution
- [x] 3.4 Confirm nothing is logged by the derivation, per the existing never-log-submitted-values requirement

## 4. Backend: template sets

- [x] 4.1 In `sets/rental/base.yaml`, change `durationMonths` to `source: derived` and drop `required: true`; bump `meta.version` 2 -> 3 with a comment recording that the rendered term now follows the dates
- [x] 4.2 In `sets/commercial/base.yaml`, apply the same change to `durationMonths` ("Lease term (months)"); bump `meta.version` 1 -> 2 with the same rationale comment
- [x] 4.3 Confirm `termClause` / the commercial term clause still resolve their `{{durationMonths}}` slot, since the field remains declared

## 5. Backend tests

- [x] 5.1 Unit: `FieldSource` parses `derived`; `Field.derived()` is true only for it; an absent source still yields `null`
- [x] 5.2 Unit: `TemplateDefinitionValidator` accepts `derived` + `required` and still rejects `system` + `required`
- [x] 5.3 Unit (`DerivedFieldTest`): a derived field is present in the schema, marked read-only, with `required: false`; an ordinary field omits the marker entirely (`readOnly()` is null, so existing payloads stay byte-identical). The system-sourced skip is covered pre-existing by `SystemSourcedFieldTest`, not re-asserted here
- [x] 5.4 Unit: the whole-month helper over a boundary table -- 11 vs 12 months, 2026-01-08 to 2028-01-08 = 24, 2026-01-01 to 2026-12-01 = 11, 31 Jan to 28 Feb, a leap-year span, and a trailing partial month. **Keep this table identical to the frontend table in 8.2**
- [x] 5.5 Unit (`DerivedFieldTest`): a submitted `durationMonths` disagreeing with the dates is discarded; a submitted value agreeing with them is still substituted; a missing end date leaves the term unset
- [x] 5.6 Integration (`AgreementDocumentFormatE2EIntegrationTest.previewRendersTheTermDerivedFromTheDatesNotTheSubmittedDuration`): a preview POST with dates spanning 24 months and a submitted `durationMonths` of 11 renders a document stating 24 months. **Must run under the `test,sandbox` profiles**: the plain-profile API tests resolve the FIXTURE layer set (`examples/layers`, where `durationMonths` is still required + bounded 1..60), so they cannot witness the derived behaviour at all
- [x] 5.7 Integration (`AgreementDocumentFormatE2EIntegrationTest.formSchemaServesTheDerivedTermReadOnlyAndNotRequired`): `GET /api/templates/form` for the production **rental** dimensions returns a `durationMonths` field marked read-only, not required, and carrying no default. Commercial is **descoped without a register row**: it would need a second layer-set root and therefore a second Spring context, it shares the one code path rental exercises, and it is already covered at unit tier by `DerivedFieldTest.bothProductionSetsDeclareTheTermDerivedAndProjectItReadOnly` -- not enough residual risk to be worth carrying as debt
- [x] 5.8 Unit (`DerivedFieldTest.previewAndGenerateStateTheSameTerm`): the preview path and the generate path render the same term for the same dates and data (the parity case this change exists to close). Unit-tier by decision -- see the note under 9.3
- [x] 5.9 Update the production layer-set assertions (`ProductionRentalLayerSetTest`, `ProductionCommercialLayerSetTest`) for the new source and version numbers
- [x] 5.10 Keep `ModularityTests` green -- no new cross-module reach (the derivation stays inside `documents`)

## 6. Frontend: schema type and read-only rendering

- [x] 6.1 Add the read-only marker to `FormField` in `api/templateForm.ts`, mirroring the backend DTO
- [x] 6.2 Render a read-only field as a non-editable displayed value -- a new `DerivedWidget.vue` (no `<input>`, so there is nothing to tab into), dispatched from `FieldWidget.vue` on `readOnly` ahead of the widget type
- [x] 6.3 In `CaptureForm.vue`, show the derived duration from the captured dates, and show "not yet determined" while either date is missing
- [x] 6.4 Exclude read-only fields from the values `saveSection` commits and from `isSectionComplete`, so a derived field can never block completeness or reach `captureData`

## 7. Frontend: cross-field validation and the registration warning

- [x] 7.1 Add `tenancyMonths(start, end)` to `formModel.ts` -- whole months, END-INCLUSIVE, truncating (mirrors 3.1); compare year/month/day components directly rather than mutating a `Date`, so 31 Jan does not overflow to 3 March
- [x] 7.2 Add `sectionErrors(fields, data)` composing `fieldErrors(...)` with cross-field rules, leaving `validateField` per-field and pure; attach the end-on-or-before-start error to the end date field
- [x] 7.3 Bind the section modal to `sectionErrors` instead of `fieldErrors`
- [~] 7.4 **REMOVED with the registration warning (2026-10-04)** -- the warning was split out of this CR (see design D5 and the proposal's Why). Original task: Add the registration warning to the Term section when the derived term exceeds the threshold of the state being drafted for (11 national / 12 KA / 0 TG -- originally a hard-coded 11, made state-aware by 10.1-10.2): advisory only, non-blocking, stating that registration with the Sub-Registrar is compulsory and is not included in the stamp duty quoted, and restating no captured value other than the term
- [x] 7.5 Confirm the existing stamp-quote registration notice in `StampQuoteStep.vue` is left untouched. (Now the ONLY registration notice, and the authoritative one: server-side, computed from the stamp-duty rules.)

## 8. Frontend tests

- [x] 8.1 Unit (`formModel.test.ts`): `sectionErrors` reports end-before-start and end-equals-start, accepts a valid range, and still reports every per-field error `fieldErrors` did
- [x] 8.2 Unit (`formModel.test.ts`): `tenancyMonths` over the boundary table from 5.4 -- **the two tables must match case for case**
- [x] 8.3 Unit: a read-only field is excluded from committed values and from completeness
- [x] 8.4 Component (`CaptureForm.test.ts`): opening Term with a 24-month date span shows the duration as 24, not the template default, and the duration input cannot be edited
- [~] 8.5 **REMOVED with the registration warning (2026-10-04)** -- the warning was split out of this CR (see design D5 and the proposal's Why). Original task: Component (`CaptureForm.test.ts`): the registration warning appears at 24 months, is absent at exactly 11 months, and does not prevent saving the section
- [x] 8.6 Component (`CaptureForm.test.ts`): the duration shows as undetermined when only the start date is set

## 10. Validate-stage fixes (round 2, 2026-10-04)

Raised by the Stage 4 gates and folded in-change under the Issue policy.

- [~] 10.1 **REMOVED with the registration warning (2026-10-04)** -- the warning was split out of this CR (see design D5 and the proposal's Why). Original task: Make the registration threshold **state-aware** in `formModel.ts`: `registrableOverMonths(state)` mirroring each state's `registration.requiredWhenTermMonthsOver` (11 national, 12 KA, 0 TG), and `requiresRegistration(months, state)`. A fixed 11 told a KA customer a 12-month tenancy was registrable while the KA clause in the same deed sets the line at twelve
- [~] 10.2 **REMOVED with the registration warning (2026-10-04)** -- the warning was split out of this CR (see design D5 and the proposal's Why). Original task: Reword the warning in `CaptureForm.vue` to quote the state's own threshold, and to say "every lease in this state must be registered" where the threshold is 0 rather than interpolating "more than 0 months"; pass `props.state` (already in scope, no new plumbing)
- [~] 10.3 **REMOVED with the registration warning (2026-10-04)** -- the warning was split out of this CR (see design D5 and the proposal's Why). Original task: Never warn on a non-positive term -- that is a date validation error, not a registration question
- [x] 10.4 Guard the derivation in `DocumentProjectionService.withDerivedValues`: substitute only a **positive** term, else leave the key unset. `durationMonths` lost its `validation: { min: 1 }` when it became derived, so a reversed range rendered "a term of -4 month(s)" into the document body. Deliberately NOT placed in `TermMonths`/`tenancyMonths`, whose boundary tables pin same-day to 0 and must stay identical
- [x] 10.5 Guard `TermMonths.between` against a year outside the representable range: `LocalDate.MAX` parses under ISO_LOCAL_DATE and then overflows on the end-inclusive `plusDays(1)`, returning 500 where the date validator would have returned 400 (it runs before validation)
- [x] 10.6 Extract `crossFieldErrors(fields, data)` from `sectionErrors`, and make a cross-field error **block** the section save and completeness (`saveSection` guard + `:disabled` on the save control + `isSectionComplete`). The rule previously rendered an error and saved anyway, which is what made 10.4 reachable from the UI. Per-field "required" errors stay non-blocking so capture remains progressive
- [~] 10.7 **REMOVED with the registration warning (2026-10-04)** -- the warning was split out of this CR (see design D5 and the proposal's Why). Original task: Unit: `registrableOverMonths` per state, case-insensitive, unknown/absent falling back to 11; `requiresRegistration` at the KA 12/13 boundary, TG at any positive term, and never on a non-positive or undetermined term
- [x] 10.8 Unit: `crossFieldErrors` returns the range rule alone and nothing for a part-filled section; `isSectionComplete` is false for a reversed range
- [x] 10.9 Unit (`DerivedFieldTest`): a negative, zero and same-day term leave `durationMonths` unset; a submitted value is still discarded when the derivation declines to substitute; a 1-month term is still substituted; an out-of-range year yields undetermined rather than throwing
- [~] 10.10 **REMOVED with the registration warning (2026-10-04)** -- the warning was split out of this CR (see design D5 and the proposal's Why). Original task: Component (`CaptureForm.test.ts`): KA does not warn at 12 and warns at 13 quoting twelve; TG warns at 6 months without "more than 0 months"; a reversed range disables save and leaves the section incomplete; a part-filled section still saves
- [x] 10.11 Integration (`AgreementDocumentFormatE2EIntegrationTest`): the API-level leg tasks 5.6/5.7 claimed but never had -- form GET serves `durationMonths` read-only, not required, no default; preview renders the date-derived 24 against a submitted 11; preview leaves a reversed range's term unstated. Runs under `test,sandbox` so the real production rental set reaches HTTP (the plain-profile tests resolve the draft fixture set)
- [x] 10.12 Pin `meta.version` explicitly in both production layer-set tests (rental 3, commercial 2) -- the "and version numbers" half of 5.9 was vacuous, since both read the version dynamically
- [x] 10.13 Correct the artifact drift: tasks 3.1/7.1 end-exclusive -> end-inclusive; 5.3/5.5/5.8 to name `DerivedFieldTest`; 6.2 to name `DerivedWidget.vue`; the design Non-Goals and proposal Impact to record that `5d39b7f` changed `TenancyDuration`; the stale "ONLY fields marked required" headers in both `base.yaml` files
- [x] 10.14 Update the `## Follow-up register` (superseded by 11.9 -- see that task for the rows that actually ended up there)

## 11. Validate-stage fixes (round 3, 2026-10-04)

Raised by the 4c code review and by the user challenging the Telangana threshold.

- [x] 11.1 **Revert the state-aware threshold entirely; keep one national line of 11.** Round 2 made the warning per-state (11 / KA 12 / TG 0) to stop a KA over-warn. That was the wrong fix: KA and TG both exempt a term below twelve months, which IS the national line, so the override changed no customer-visible outcome while adding a second copy of the rules engine in the frontend, a per-state copy variant, and an open legal question. Deleted `REGISTRABLE_OVER_MONTHS_BY_STATE`, `registrableOverMonths`, `requiresRegistration`'s `state` parameter, the `registrationThreshold` computed and the zero-threshold copy branch. **Superseded by 12.1**, which removed the warning from this CR entirely
- [x] 11.3 **Substitute a ZERO term rather than leaving the key unset.** Round 2's `months > 0` guard over-caught a lawful sub-month tenancy: leaving the key unset makes the compiler render `[ Duration (months) ]` into the GENERATED draft -- a form artifact inside the instrument that is then stamped and eSigned. Guard only the NEGATIVE case (`months >= 0`); "0 month(s)" is the long-standing, merely-imprecise behaviour. What a sub-month deed should say is folded into the existing `term-partial-month-rounding` row (same root: the truncated month count)
- [x] 11.5 Correct two tests that passed for the wrong reason: the reversed-range save test asserted modal visibility after clicking a DISABLED button, which dispatches no event in Vue Test Utils and would pass with the guard deleted -- narrowed to what it proves, plus a new complement asserting the control re-enables once corrected (no dead end); and the `crossFieldErrors` "error of its own" case documented as passing via parse failure, not via the `perFieldErrors` guard, which is unreachable until the sibling `dd-mm-yyyy-date-entry` adds date-field errors -- with a new test exercising the guard directly
- [x] 11.6 Delete the per-state unit and component tests along with the behaviour; keep and extend the zero-term substitution tests (unit + API level)
- [x] 11.7 Update the `preview-centric-capture` delta, design D5 and the design Risks to state the single national threshold and to record the state-aware attempt and why it was reverted (further revised by 12.1)
- [x] 11.9 **Prune the follow-up register rather than grow it.** Final net effect of this CR on the register: **one row removed (`capture-warning-state-aware-threshold`), none added.** Every point the review rounds raised either was fixed (the `TenancyDuration` overflow, at the input boundary; the local MinIO drift, by pinning compose to the harness's release) or fits an existing row as one sentence: the KA/TG thresholds and the possibly-wrong shipped KA clause in `ka-stamp-duty-counsel-review` / `tg-stamp-duty-counsel-review`, and the sub-month "0 month(s)" wording in `term-partial-month-rounding`. Five rows were added and removed along the way; that churn is the worked example in CLAUDE.md's scope-discipline section
- [x] 11.8 `signing`'s `TenancyDuration` overflow (500 on `POST /api/agreements` with a MAX-year `endDate`) and `(int)` wrap -- **resolved OUTSIDE this CR, at the input boundary**: a shared `PlausibleDates` range (1900-2199) enforced by `@PlausibleDate` on `CreateAgreementRequest` and by `SubmittedDataValidator.coerceDate`. With input bounded neither helper can overflow or wrap, so `TenancyDuration` is unchanged. Kept as its own change set (not folded here) because it touches `signing`; see the suggested commit `fix(dates): bound accepted dates at the input boundary`. The API test was verified to fail with the constraint removed

## 12. Scope correction: the registration warning is split out (2026-10-04)

The CR name carries an `and`, and that was the tell: this change bundled a rendering bug fix with a
new customer-facing legal advisory. The warning is removed; the derived term stays.

- [x] 12.1 **Remove the capture-time registration warning from this CR.** It duplicated the
  stamp-quote step's registration notice -- already server-side and computed from the stamp-duty
  rules -- as a second, less-informed assertion of the same legal fact by a client that cannot reach
  `rules`. It was also the entire source of this CR's churn (four fix rounds, a built-and-reverted
  state-aware threshold, a High-priority counsel question), while the derived-term work needed none
  of it. And the threshold it asserted risked contradicting the registration clause in the deed being
  drafted. Deleted `REGISTRABLE_OVER_MONTHS`, `requiresRegistration`, `modalNeedsRegistration`,
  `modalShowsTerm`, the warning markup and its unit + component tests; removed the
  `preview-centric-capture` warning requirement and its scenarios; annotated the proposal Why, the
  Capabilities line, the Impact list, design D5 and the design Risks
- [x] 12.2 Keep the end-before-start cross-field rule in this CR: with the term derived, a reversed
  range no longer shows a typed value but a nonsense computed duration, so the check belongs where
  the dates are entered. It is the one cross-field rule, and it blocks the section save
- [x] 12.3 Keep the two defect fixes the warning work surfaced, both of which are defects in the
  DERIVED TERM rather than in the warning: the negative-term guard in `withDerivedValues` (a reversed
  range rendered "a term of -4 month(s)") and the out-of-range-year guard in `TermMonths.between` (a
  500 where the validator answers 400)
- [x] 12.4 Re-run both gates after the strip
- [x] 12.5 Renamed the change `derived-tenancy-term-and-registration-warning` -> `derived-tenancy-term` (user decision, 2026-10-04): the old name described a warning this change no longer contains. `.openspec.yaml` stores no name, so the move was a plain directory rename; references updated in CLAUDE.md, `.claude/skills/DECISIONS.md`, `DerivedFieldTest`'s javadoc and the sibling `dd-mm-yyyy-date-entry` design. The journal keeps the old name in its historical entries

## 9. Verification and close-out

- [x] 9.1 Run the backend suite via `./run-tests.sh` and report the elapsed wall-clock time against the 3-minute budget
- [x] 9.2 Run `npm run lint` and the frontend tests from `frontend/`
- [x] 9.3 Manually verify the reported case: start 2026-01-08, end 2028-01-08 shows 24 months, warns, and previews a deed stating 24 months that matches the generated draft
  - Verified 2026-09-18 against the running local stack: `POST /api/templates/document/preview` (TG, residential) with a submitted `durationMonths: 11` and those dates renders **"term of 24 month(s)"**; `GET /api/templates/form` for TG/KA residential and TG commercial serves `durationMonths` as `readOnly: true, required: false` with no default; `POST /api/agreements` returns a server-derived `durationMonths: 24` and strips the submitted 11 from `captureData`; the stateless preview-PDF leg renders a valid 53 KB PDF through real Gotenberg/Chromium.
  - **Not verified locally:** the stored-draft `POST /api/agreements/{id}/document` leg returns 500 at `Failed to ensure bucket agreements` -- `docker-compose.yml` pins MinIO as `minio/minio:latest`, which has drifted to `RELEASE.2025-09-07` and no longer answers minio-java 8.6.0's bucket calls with XML, while the test harness pins `RELEASE.2023-09-04`. Pre-existing local-environment drift, unrelated to this change (nothing here touches storage). Preview/generate term parity is covered instead by `DerivedFieldTest.previewAndGenerateStateTheSameTerm`.
- [~] 9.4 **REMOVED with the registration warning (2026-10-04)** -- the warning was split out of this CR (see design D5 and the proposal's Why). Original task: Add the state-aware-threshold follow-up row to the `## Follow-up register` in `docs/ROADMAP.md`. (Narrowed by 10.14: the hard-coded 11 was fixed in-change, so the row now tracks only the remaining work of sourcing the thresholds from the `rules` module instead of duplicating them in the frontend)
- [x] 9.5 Run `openspec validate --strict derived-tenancy-term-and-registration-warning`
