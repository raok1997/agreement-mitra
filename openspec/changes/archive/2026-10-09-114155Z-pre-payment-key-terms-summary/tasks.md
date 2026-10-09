## 1. Shared helpers

- [x] 1.1 Move `roleLabel` from `frontend/src/views/AgreementStatus.vue:95-96` into `frontend/src/views/agreementListFormat.ts` (Owner / Tenant / Party). Import it in AgreementStatus.vue (D6).
- [x] 1.2 Move CaptureForm's local `unavailable(e)` (`CaptureForm.vue:902`, not-found problem type plus `reconcile()`) into `frontend/src/views/refusalMessages.ts` as an exported helper. Use it from CaptureForm (D5).
- [x] 1.3 Correct the `getAgreement` JSDoc (`frontend/src/api/agreements.ts:76`): unclaimed agreements are readable by any holder of the id, claimed ones only by their owner (404 otherwise), and it is used on the pay path.

## 2. Summary in the stamp-quote step

- [x] 2.1 In `frontend/src/views/StampQuoteStep.vue`, fetch `getAgreement(props.agreementId)` on mount in its own `try/catch`, independent of `getStampQuote` (D2). Narrow the response to the D3 shape and store only that. A missing field counts as a failure.
- [x] 2.2 Render `data-testid="key-terms"` under the heading per D4 (pending, loaded, error) and D6/D7 (formatting, copy, `{{ }}` only, `whitespace-pre-line` address).
- [x] 2.3 Gate payment per D4: `canPay` also requires terms loaded; on a terms error, hide the options and Continue; Back stays. Map errors per D5.

## 3. Tests

- [x] 3.1 Unit, `frontend/src/views/StampQuoteStep.test.ts`. Mock `../api/agreements` `getAgreement` with a deferred promise where needed. Cover:
  - the summary shows stored rent, deposit, both dates, the server `durationMonths` ("1 month" and "12 months"), address, and names with Owner / Tenant / Party (null role);
  - a fixture with distinctive email, mobile, `currentAddress` (different from `propertyAddress`), `fatherName` and `captureData` values, none of which appear anywhere in the step's HTML;
  - terms pending with the quote loaded: "Loading the saved terms…" shows and Continue is disabled; resolving the terms shows the summary and enables Continue;
  - a terms rejection shows an alert, no options and no Continue, and Back still emits `cancel`. A not-found rejection shows `AGREEMENT_UNAVAILABLE_MESSAGE` and no status text;
  - the go-back line is present and there is no terms-editing control.
- [x] 3.2 Integration, `frontend/src/views/CaptureForm.test.ts`. The form saves ₹24,000 and the existing `getAgreement` mock resolves ₹25,000 for every call (the contact step calls it too, so no `…Once` sequence). Drive Finalise and pay → contacts → stamp step. Assert the summary shows ₹25,000, not ₹24,000, and that `getAgreement` was called with the saved id. The lock makes the divergence reachable only through mocks, which is intended.
- [x] 3.3 Integration, `frontend/src/views/AgreementStatus.test.ts`. Add a `../api/agreements` mock resolving the file's `agreement()` fixture. Assert the "Complete payment" path renders `key-terms` with that fixture's rent. Keep the existing `stamp-quote-pay` cases green.
- [x] 3.4 Keep `frontend/src/views/StampQuoteStepLoadError.test.ts` on the real client: route its stubbed `fetch` by URL so `/agreements/{id}` succeeds and the quote load error stays the case under test. Do not `vi.mock` the API there. Set per-test `getAgreement` values in `CaptureForm*.test.ts` wherever a test reaches the step.

## 4. Terms of service

- [x] 4.1 In `frontend/src/content/termsOfService.ts` §7 (`:84`), extend "You are shown the stamp, the duty and the total before you pay" to say the customer is also shown the main terms of the agreement as saved. Set `lastUpdated` (`:224`) to the change date. Run `npm run legal:doc` to re-render `docs/TERMS-OF-SERVICE.md` (`legalDocs.test.ts` fails on drift).

## 5. Gates

- [x] 5.1 From `frontend/`, `npm run build` (which runs `security:scan`, `test`, `vue-tsc` and `vite build`) and `npm run lint` are green.

## 6. Validate / code-review fixes

- [x] 6.1 `formatRupees` keeps paise when present; AgreementStatus uses it instead of its own rounding copy (unit test in `agreementListFormat.test.ts`, step test for ₹15,000.50).
- [x] 6.2 `agreementUnavailable` also covers the stamp quote's 404; AgreementStatus's `notAvailableToThisSession` and payment-404 branch use it; the step's quote load error maps it, and an identical message is shown once (step test).
- [x] 6.3 The step treats an empty party list or a blank party name as a load failure (step test).
- [x] 6.4 AgreementStatus's `status-terms` shows the security deposit, so the frozen-order resume path (which skips the step) also shows every term ToS §7 names (`AgreementStatus.test.ts`).
- [x] 6.5 ContactConfirmation uses the shared `roleLabel` (D6: one helper).

## Coverage

| Scenario (stamp-selection) | Disposition | By |
|---|---|---|
| The summary shows the stored terms, not the on-screen ones | COVERED | 3.2 (capture path), 3.3 (status path), 3.1 (field rendering) |
| Only the listed fields are shown | COVERED | 3.1 |
| Payment waits for the summary | COVERED | 3.1 |
| Unreadable terms block payment | COVERED | 3.1 |
| A wrong term sends the customer back, not into an edit | COVERED | 3.1 |
