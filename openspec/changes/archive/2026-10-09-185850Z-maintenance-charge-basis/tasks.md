Depends on commit `83d2ba5` (already on the branch): an invalid number blocks a section save, and money fields accept only a plain rupee amount.

## 1. Rental template (`sets/rental/base.yaml` → v8)

- [x] 1.1 Replace `maintenanceBorneBy` with `maintenanceMode`:
  - enum `included_in_rent | fixed_amount | as_billed_by_society | paid_by_owner`;
  - default `as_billed_by_society`;
  - label "How is maintenance handled?".
- [x] 1.2 Relabel `maintenanceAmount` to "Maintenance amount (INR / month) – only if Fixed". No `validation` (design D4).
- [x] 1.3 Remove `maintenanceClause` and `maintenanceAmountClause`. Add the six clauses in design D7, with the wording from the `rental-agreement-document` delta.
- [x] 1.4 Change the Charges & Utilities section to `render: clauses`, with entries ordered per D7 (fields first, then clauses). Update the base file's header comment, which describes this section as key/value.
- [x] 1.5 Bump `meta.version` 7 → 8 with a changelog comment. Update the pinned version in `ProductionRentalLayerSetTest` (:139), `TemplateCatalogSeederTest` (:73) and `TemplateCatalogRegistryIntegrationTest` (:57).
- [x] 1.6 Unit tests in `ProductionRentalLayerSetTest`. Add a small helper that slices the Charges & Utilities section out of the compiled HTML: TG and KA print `[ Provision for stamp duty ]` elsewhere, so whole-document bracket assertions would fail. Cases:
  - for IN, TG and KA, each mode renders its own clause and no other;
  - Fixed renders "In addition to the rent, the Tenant shall pay the Owner …" plus the revision clause, and never "payable amount to";
  - Fixed with no amount, or with `0`, renders neither Fixed clause;
  - Included in rent with `maintenanceAmount = 3500` renders no "3500" in the section;
  - the society-levy clause renders for every mode on an `apartment`, and not on an `independent_house`;
  - with no amount and no penalty, the section has no table and no `[ … ]` or "Grace period" text;
  - render kinds: add `Charges & Utilities` → `clauses` to the render-kind assertions (around :332-339);
  - flip the form-schema assertion at :491 from `keyvalue` to `clauses`;
  - the form schema still lists the five fields in the section;
  - stored data carrying `maintenanceBorneBy` validates and the key is dropped;
  - parity guards pass;
  - the gated-out assertion at :463 uses the society-levy clause text as its witness;
  - `chargesBorneByOffersOnlyOwnerOrTenant` now covers `utilitiesBorneBy` only.
- [x] 1.7 Integration tests in `AgreementDocumentFormatE2EIntegrationTest`:
  - the form schema's Charges & Utilities section lists `maintenanceMode` with its four options, and has render kind `clauses`;
  - a preview (`previewBody`, :120) with the section active and `maintenanceMode = fixed_amount`, `maintenanceAmount = 3500` returns HTML containing the Fixed clause and no `[ Maintenance amount` or `[ Late-payment penalty` placeholder.

## 2. Shared constants and capture form rule

- [x] 2.1 Create `frontend/src/views/maintenanceTerms.ts`. It exports the section title, the two field keys, the four mode values and the default, as the single frontend copy of these template facts.
- [x] 2.2 In `formModel.ts` `crossFieldErrors`, add a rule using those constants. When the fields include both keys and the mode is `fixed_amount`, an amount that is blank or not greater than zero puts an error on `maintenanceAmount`: "Enter the monthly amount for a fixed maintenance charge." Stay silent while `maintenanceAmount` has a per-field error of its own. Update the function's doc comment: the date range is no longer the only rule, and this is the first template-specific one.
- [x] 2.3 Unit tests in `formModel.test.ts`:
  - the rule fires for Fixed with a blank amount and with `0`;
  - it is silent for Fixed with an amount, for every other mode (including with `0`), and when the fields are absent;
  - `blocksSave` is true while it fires.
- [x] 2.4 Component test in `CaptureForm.test.ts`: in the Charges & Utilities modal, choosing Fixed Amount with a blank amount shows the error on the amount field and the section is not saved. Entering an amount saves it.

## 3. Key-terms line (`frontend/src/views/StampQuoteStep.vue`)

- [x] 3.1 Compute the maintenance line per design D6 using `maintenanceTerms.ts`, with `data-testid="key-terms-maintenance"` and `key-terms-maintenance-total`:
  - read an absent mode as the default;
  - the amount must match `^\d+(\.\d{1,2})?$` and be greater than zero;
  - the total is `Math.round(monthlyRent * 100)` plus the amount's paise, formatted with `formatRupees`;
  - under Fixed with an unreadable amount, show the arrangement with no figure and no total;
  - show no line unless `type === "residential"`, and none for an unknown mode or an inactive section.
- [x] 3.2 Unit tests in `StampQuoteStep.test.ts`:
  - Fixed: ₹3,500 and ₹28,500 on rent ₹25,000;
  - paise: `3500.50` on `25000.00` gives ₹3,500.50 and ₹28,500.50;
  - Included in rent: no total;
  - each of the other two modes shows its text;
  - an absent mode shows the as-billed text;
  - Fixed with `1e3` shows the arrangement with no figure or total;
  - no line for an inactive section or an unknown mode;
  - no line for a commercial agreement (`type: "commercial"`, section active, `camBorneBy`, no `maintenanceMode`).
- [x] 3.3 Integration-level frontend test in `StampQuoteStep.test.ts`: mount against a mocked `getAgreement` response mirroring a real `AgreementView` payload (with `captureData` holding party details too, and `activeSections`). Assert the summary displays only the listed fields plus the maintenance line.
- [x] 3.4 Drift test (`maintenanceTerms.test.ts`): read `backend/src/main/resources/documents/template/sets/rental/base.yaml` with `readFileSync` and a multi-line regex that fails closed, following `src/test-support/backendConfig.ts`. Add no YAML dependency. Assert:
  - the section title, both keys, the option list and the default match `maintenanceTerms.ts`;
  - `sets/commercial/base.yaml` declares no `maintenanceMode`.

## 4. Docs and register

- [x] 4.1 `docs/COUNSEL-BRIEF.md` Annexure A, Charges and utilities.
  - Replace items 9–10 with:
    - 9(a)–(d): the four mode clauses;
    - 10: the revision clause (Fixed only);
    - 10A: the society-levy clause (apartment, gated community and villa only).
  - Keep items 11 onward numbered as they are.
  - Update the group note: the section prints as clauses only; the Fixed clauses appear only with an amount; the late-payment clause appears only with a penalty.
  - Mark 10 and 10A for counsel. Add three questions:
    - Does 10A settle a sinking-fund line on a society bill the Tenant pays under 9(c)?
    - Should unpaid Fixed maintenance be adjustable against the deposit (item 8 names only arrears of rent and utility charges)?
    - Is 9(a)–(d)'s "society" wording right for an independent house?
- [x] 4.2 Terms of Service: edit `frontend/src/content/termsOfService.ts` §7 to add maintenance to the key terms shown before payment, then run `npm run legal:doc` to regenerate `docs/TERMS-OF-SERVICE.md` and bump `lastUpdated`. §7 already uses "the total" for the price, so call the new figure "rent and maintenance together each month", never "total".
- [x] 4.3 `docs/ROADMAP.md`:
  - Append one sentence each to `tg-stamp-duty-counsel-review` and `ka-stamp-duty-counsel-review`. Ask whether a fixed maintenance paid to the owner counts toward the rent or consideration on which stamp duty is computed (today the basis is `monthlyRent` + deposit). This **blocks the paid TG+KA release**. A "yes" means a typed amount must reach `rules` through the `signing`→`rules` interface.
  - Add the follow-up row `capture-field-conditional-reveal`: show `maintenanceAmount` only under Fixed, by projecting a form-side condition into the reserved `FormField.showWhen` slot; move template-specific `crossFieldErrors` rules there; decide whether the server should also refuse Fixed without an amount (design D5). Include a recommended action.
  - Append to `agreement-capability-token` that `GET /api/agreements/{id}` returns the full `captureData`, including party details, to any holder of the id (raised in this change's review; not widened by it).

## 5. Gates

- [x] 5.1 `./run-tests.sh` from `backend/` is green (report the wall-clock time).
- [x] 5.2 `npm run build` and `npm run lint` from `frontend/` are green.

## Coverage

| # | Capability | Scenario | Disposition | Where |
|---|---|---|---|---|
| 1 | rental-agreement-document | Each mode renders its own clause and only that one | COVERED | 1.6 |
| 2 | rental-agreement-document | A fixed amount states who pays whom, on top of the rent | COVERED | 1.6, 1.7 |
| 3 | rental-agreement-document | An amount typed under another mode is not printed | COVERED | 1.6 |
| 4 | rental-agreement-document | Society levies are always the Owner's | COVERED | 1.6 |
| 5 | rental-agreement-document | No society-levy clause for an independent house | COVERED | 1.6 |
| 6 | rental-agreement-document | A fixed mode with a zero amount prints no amount | COVERED | 1.6 |
| 7 | rental-agreement-document | The form will not save Fixed without an amount | COVERED | 2.3, 2.4 |
| 8 | rental-agreement-document | A stored draft carrying the removed key still validates | COVERED | 1.6 |
| 9 | rental-agreement-document | The defaulted mode keeps parity | COVERED | 1.6 |
| 10 | rental-agreement-document | Render kinds are declared per section | COVERED | 1.6, 1.7 |
| 11 | rental-agreement-document | Term and Financial compose the Terms of Tenancy table | GROUPED | existing `ProductionRentalLayerSetTest.bothDimensionsCompileToTheArtifactLayoutInvariants`, re-run in 5.1 |
| 12 | rental-agreement-document | Charges & Utilities prints clauses and no blanks | COVERED | 1.6, 1.7 |
| 13 | rental-agreement-document | Charges & Utilities still asks its fields | COVERED | 1.6, 1.7 |
| 14 | stamp-selection | The summary shows the stored terms, not the on-screen ones | GROUPED | existing `StampQuoteStep.test.ts` case, re-run in 5.2 |
| 15 | stamp-selection | A fixed maintenance shows the amount and the monthly total | COVERED | 3.2 |
| 16 | stamp-selection | Paise are shown exactly | COVERED | 3.2 |
| 17 | stamp-selection | Other maintenance modes show their arrangement without a total | COVERED | 3.2 |
| 18 | stamp-selection | No maintenance line without the section or with an unknown mode | COVERED | 3.2 |
| 19 | stamp-selection | An absent mode is summarised as the default | COVERED | 3.2 |
| 20 | stamp-selection | An unreadable fixed amount still states the arrangement | COVERED | 3.2 |
| 20a | stamp-selection | A fixed mode the deed does not state shows no line | COVERED | 3.2 (`StampQuoteStep.test.ts` "shows no line for a fixed mode whose amount the deed does not print") |
| 20b | stamp-selection | An agreement from before the maintenance mode shows no line | COVERED | 3.2 (`StampQuoteStep.test.ts` "shows no line for an agreement pinned before the mode existed") |
| 21 | stamp-selection | Only the listed fields are shown | COVERED | 3.3 |
| 22 | stamp-selection | Payment waits for the summary | GROUPED | existing `StampQuoteStep.test.ts` case, re-run in 5.2 |
| 23 | stamp-selection | Unreadable terms block payment | GROUPED | existing `StampQuoteStepLoadError.test.ts`, re-run in 5.2 |
| 24 | stamp-selection | A wrong term sends the customer back, not into an edit | GROUPED | existing `StampQuoteStep.test.ts` case, re-run in 5.2 |

26 scenarios: 21 COVERED, 5 GROUPED, 0 MANUAL, 0 WAIVED, 0 UNMAPPED. The rendered deed wording is also checked by eye at the Stage 6 manual-test gate.
