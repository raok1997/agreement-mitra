> Test fixtures throughout build response bodies from **literal server URNs**
> (e.g. `"urn:agreementmitra:problem:jurisdiction-unsupported"`), never from `PROBLEM.*` (design D1).

## 1. Problem-type vocabulary and the customer-facing marker (D1, D4)

- [x] 1.1 Create `frontend/src/api/problems.ts` with:
  - `PROBLEM` (`as const`, full URNs: `jurisdictionUnsupported`, `draftFrozen`, `contactsFrozen`, `paymentRequired`, `csrf`, `renderBusy`) and the `ProblemType` union;
  - `problemTypeOf(res)`: `res.json()` only, no `Content-Type` check, a string `type` or `null`, never throws, never logs;
  - `hasProblemType(e: unknown, type: ProblemType)`: exact `===`; `false` for null, undefined, primitives, and a non-string `problemType`.
- [x] 1.2 `frontend/src/api/http.ts`:
  - import the CSRF and render-busy URNs from `problems.ts` and delete the local constants;
  - add `export class CustomerFacingError extends Error`;
  - make `ServiceBusyError` extend it.
- [x] 1.3 Mark the existing customer-text throws with `CustomerFacingError`:
  - `client.ts:105/150/166` (`describeProblem`). Also drop the status from `describeProblem`'s fallback (`client.ts:138`), which becomes "Sorry, that couldn't be saved. Please try again.";
  - the three "Could not load the payment window." throws in `payments.ts` `loadCheckoutScript`.
- [x] 1.4 Unit test `frontend/src/api/problems.test.ts`:
  - `problemTypeOf` returns the type for a problem body, `null` for a non-JSON body (`json()` rejects), and `null` for JSON without a string `type`;
  - `hasProblemType` is exact (a suffix-only URN is `false`);
  - `hasProblemType` is `false` for `null`, `undefined`, a string, and `{ problemType: 42 }`;
  - each `PROBLEM` value equals its literal server URN.

## 2. Every throw site carries the type (D2)

- [x] 2.1 `frontend/src/api/agreements.ts`: add `static async from(res)` (`return new this(res.status, await problemTypeOf(res))`); convert all six throw sites to `throw await AgreementHttpError.from(res)`; delete the private `problemTypeOf`. The getters stay until 3.x.
- [x] 2.2 `frontend/src/api/payments.ts`: give `PaymentHttpError` a `problemType` (default `null`) and `static async from(res)`; convert its three throw sites.
- [x] 2.3 `frontend/src/api/staffQueue.ts`: add `static async from(res)`; convert both throw sites; delete the private `problemTypeOf`.
- [x] 2.4 Guard test `frontend/src/api/httpErrorGuard.test.ts`:
  - scan every non-test `.ts` under `src/api`, recursively;
  - fail on any occurrence of `new (Agreement|Payment|StaffQueue)HttpError(`;
  - fail on any `new CustomerFacingError(` outside `http.ts`, `client.ts` and `payments.ts`, so the allowlist stays enforced;
  - include a matcher self-test: it catches `throw new AgreementHttpError(res.status)` and passes both `throw await AgreementHttpError.from(res)` and `new this(`.
- [x] 2.5 Unit tests in `agreements.test.ts`, `payments.test.ts` and `staffQueue.test.ts`, as `it.each` tables over `[fn, args]`:
  - for every exported call, a stubbed `fetch` returning `409` with a literal-URN JSON body yields that module's class, that status and that exact `problemType`;
  - per module, one non-JSON-body case yields `problemType === null`.

## 3. Views and customer copy (D3, D4, D5)

- [x] 3.1 Create `frontend/src/views/refusalMessages.ts` exporting:
  - `JURISDICTION_UNSUPPORTED_MESSAGE` (moved verbatim from `CaptureForm.vue`);
  - `TERMS_FROZEN_MESSAGE` (D4 wording);
  - `customerMessage(e, fallback)`: a `CustomerFacingError` with a non-empty message returns its message; anything else returns `fallback`.
- [x] 3.2 `CaptureForm.vue` `finaliseAndPay`:
  - if `hasProblemType(e, PROBLEM.jurisdictionUnsupported)` (either error class), show the shared message;
  - otherwise show `customerMessage(e, "Could not start payment. Please try again.")`.
- [x] 3.3 `CaptureForm.vue` `saveAndContinue`:
  - a `draft-frozen` refusal shows `TERMS_FROZEN_MESSAGE`;
  - otherwise show `customerMessage(e, "Could not save. Please try again.")`.
- [x] 3.4 `CaptureForm.vue` `confirmContacts`:
  - a `hasProblemType(e, PROBLEM.contactsFrozen)` refusal keeps its existing message;
  - otherwise show `customerMessage(e, <existing generic>)` in place of `busyMessage(e) ?? …`.
- [x] 3.5 `AgreementStatus.vue` `payWith`:
  - a jurisdiction refusal shows the shared message;
  - otherwise show `customerMessage(e, "Payment cannot be started yet. Contact support quoting your reference.")`, replacing `busyMessage(e) ?? …`, so the payment-window failure reads the same as in the capture form.
- [x] 3.6 `StaffConsole.vue:258`: replace `e.paymentRequired` with `hasProblemType(e, PROBLEM.paymentRequired)`. Keep the branch order (busy → payment-required → 409 → 400) and the `instanceof StaffQueueHttpError` guards.
- [x] 3.7 Delete the `contactsFrozen` / `jurisdictionUnsupported` getters from `AgreementHttpError` and `paymentRequired` from `StaffQueueHttpError`.
  - `vue-tsc` must be clean afterwards.
  - Convert the API tests that read the getters to `hasProblemType` assertions: `agreements.test.ts:129,145,161` and `staffQueue.test.ts:163,189`. `vue-tsc` does not type-check tests, so these show up only at runtime.
- [x] 3.9 `StampQuoteStep.vue:172-176`: the `!quote.available` text renders `JURISDICTION_UNSUPPORTED_MESSAGE`. `quote.available` uses the same `payable()` predicate as the jurisdiction gate, so this is one refusal with one wording. Exception: status `UNPLANNABLE` (a supported state, no stamp-paper plan) keeps the neutral wording as `STAMP_UNPLANNABLE_MESSAGE`, pinned by a `StampQuoteStep.test.ts` case (code review 4c).
- [x] 3.8 Unit test `frontend/src/views/refusalMessages.test.ts`. `customerMessage` returns:
  - the busy text for a `ServiceBusyError`;
  - the message of a plain `CustomerFacingError`;
  - the fallback for each of `AgreementHttpError` / `PaymentHttpError` / `StaffQueueHttpError`, constructed with a type **and** with `problemType: null`, and never containing `request failed:`;
  - the fallback for a status-only error (e.g. `StampQuoteHttpError(500)`);
  - the fallback for a plain `Error("x")`, a `TypeError("Failed to fetch")`, an empty-message `CustomerFacingError`, and a non-Error;
  - the message of a `CustomerFacingError` subclass (pins the `instanceof` that `ServiceBusyError` relies on).

## 4. Integration tests — real throw path, stubbed fetch

Common setup for both files: mock `../api/cookies` so `ensureCsrf` issues no bootstrap request (as `agreements.test.ts:15` does).

- [x] 4.1 `frontend/src/views/CaptureForm.refusals.test.ts`.
  - Start from the `CaptureForm.test.ts:48-61` mock set, but leave `finaliseAgreement`, `payForAgreement` / `startCheckout` and `updateAgreement` **real for the whole file**.
  - The stubbed `fetch` routes by method + path, using one routing table in the common setup and literal-URN bodies. Each case overrides one route:
    - (a) `POST …/finalise` → `409` jurisdiction: the jurisdiction message, and no `request failed:` text;
    - (b) finalise `200`, `POST …/payment/order` → `409` jurisdiction: the same message;
    - (c) edit mode, `PUT /agreements/{id}` → `409` draft-frozen: the terms-frozen message;
    - (d) finalise → `500` non-JSON: the generic payment message, no `request failed:`;
    - (e) finalise `200`, payment/order → `409` `contact-required`: the generic payment message;
    - (f) edit-save PUT → `500`: the generic save message;
    - (g) finalise → `429` with `Retry-After: 7` (a real `Response`): "try again in 7 seconds";
    - (h) edit save: PUT `200`, then `POST …/document` → a real `Response` `503` render-busy with `Retry-After: 7`: "try again in 7 seconds".
- [x] 4.2 `frontend/src/views/AgreementStatus.refusals.test.ts`.
  - Keep every existing mock in `AgreementStatus.test.ts:18-65` except `payForAgreement`, which stays real so `startCheckout` runs for real.
  - The quote is `frozen`, so "Complete payment" reaches `payWith` directly. Control polling with the existing `pollWait` prop.
  - payment/order → `409` jurisdiction: the jurisdiction message, not "Contact support".
- [x] 4.3 Run the existing view tests unchanged. These cases stay valid with the default-null constructor and should need no edits:
  - `CaptureForm.test.ts:712` (typed contacts-frozen, full URN) and `:752` (`AgreementHttpError(500)`);
  - `AgreementStatus.test.ts:480`, `:632`, `:644`.

  Update only the tests that relied on a removed getter. Also update any `StampQuoteStep` test that asserts on the unavailable text.

## 5. Gates

- [x] 5.1 `npm run build` and `npm run lint` pass from `frontend/`.
- [x] 5.2 Delete register row `agreement-error-problem-type-plumbing` from `docs/ROADMAP.md`. Closing it is the completion record.

## Coverage

| Requirement | Scenario | Disposition | Where |
|---|---|---|---|
| API client carries the problem type | A typed refusal keeps its type | COVERED | 2.5 |
| API client carries the problem type | A body that is not a problem document degrades to a null type | COVERED | 1.4, 2.5 |
| API client carries the problem type | The same refusal is recognised from either module | COVERED | 2.5 (both classes) + 4.1(a)(b) |
| Refusal explained in its own words | Jurisdiction refused at finalise in the capture form | COVERED | 4.1(a) |
| Refusal explained in its own words | Jurisdiction refused at checkout in the capture form | COVERED | 4.1(b) |
| Refusal explained in its own words | Jurisdiction refused from the status view | COVERED | 4.2 |
| Refusal explained in its own words | An edit refused because the order is placed | COVERED | 4.1(c) |
| Refusal explained in its own words | An unplannable duty is not called a jurisdiction refusal | COVERED | 3.9 (`StampQuoteStep.test.ts`) |
| Raw HTTP status never shown | An untyped or unrecognised refusal falls back to the generic message | COVERED | 4.1(d)(e)(f), 3.8 |
| Raw HTTP status never shown | A load refusal still shows when to retry | COVERED | 4.1(g)(h), 3.8 |
