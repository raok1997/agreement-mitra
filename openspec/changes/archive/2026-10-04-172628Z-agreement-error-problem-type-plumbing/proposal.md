## Why

The backend gives every 409 its own RFC 9457 problem `type`, but the frontend reads that `type` at
only one of the six `AgreementHttpError` throw sites and at none of the three `PaymentHttpError`
sites. As a result, a customer whose payment step is refused for an unsupported jurisdiction, or
whose edit is refused because the order is already placed, sees the raw string
`Agreement request failed: 409`. That is register row `agreement-error-problem-type-plumbing`
(raised by `contacts-editable-until-payment`, 2026-09-10, High — customer-visible today).

Grounding corrects two points in the register row's premise:

- **What customers see today.** The customer-visible gap is mainly the frozen-terms save and the
  status-string fallback on any server failure. The jurisdiction refusal is a rare edge: the stamp
  step already hides payment for an ineligible jurisdiction, so the gate refuses only when
  eligibility changes mid-session. It still has to be explained correctly when it does fire.
- **Jurisdiction.** The copy exists in `CaptureForm.finaliseAndPay`, but nothing can reach it.
  The jurisdiction gate fires first at `POST /finalise`
  (`SigningRequestService.finalise` → `jurisdiction.require`), and `finaliseAgreement` throws
  with a null type. Checkout re-gates at `POST /payment/order` and throws a `PaymentHttpError`,
  which the branch does not accept. `AgreementStatus.payWith` covers the same refusal with
  "Contact support quoting your reference", which sends the customer to the wrong remedy.
- **Frozen terms.** No customer copy exists anywhere. This change writes new copy. Terms freeze
  when the order is placed: finalise creates the signing request, and `PUT /agreements/{id}`
  refuses with `draft-frozen` once one exists.

The underlying cause is that carrying the type is **opt-in at each throw site**, so most sites
leave it out. A second cause is the generic fallback `e instanceof Error && e.message ? e.message
: …` in the capture form. It renders the status string of *any* HTTP error the form has no copy
for, not just these two.

## What Changes

- A new `src/api/problems.ts` holds the one problem-type reader and the full-URN constants. The
  `http.ts` URNs move there too. The copies of `problemTypeOf` duplicated in `agreements.ts` and
  `staffQueue.ts` are removed, and the `endsWith` suffix matching is replaced by exact URN
  comparison.
- Every non-2xx throw site in `agreements.ts`, `payments.ts` and `staffQueue.ts` builds its error
  through one async path that always carries the problem type. A new site cannot leave it out.
- One predicate, `hasProblemType(error, TYPE)`, works on any of those errors. It replaces the
  per-class `contactsFrozen` / `jurisdictionUnsupported` / `paymentRequired` getters, so a
  `PaymentHttpError` answers the jurisdiction question the same way an `AgreementHttpError` does.
- The jurisdiction message moves to one shared place, used in three spots:
  - the capture form (on finalise or checkout);
  - the status view's "Complete payment";
  - the stamp-value step's "not available" notice, which today has a third wording.
- New copy covers a refused edit of an agreement whose order is already placed.
- A customer-facing error message is never the error's HTTP status string. An allowlist marker,
  `CustomerFacingError`, decides which errors may show their own message. Every other error,
  typed or not, gets the view's existing generic message.

## Capabilities

### New Capabilities
- `client-error-reporting`: how the SPA turns a server refusal into what the customer reads. The
  API client carries the problem type on every refusal, a refusal with a distinct remedy gets its
  own message, and a raw status string is never shown.

### Modified Capabilities
None. The server contract (`api-error-handling`, `jurisdiction-eligibility`, `agreement-management`)
is unchanged. This change only makes the client honour the types those specs already emit.

## Impact

- **Frontend only.** The files touched are:
  - `src/api/problems.ts` (new), `http.ts`, `client.ts`, `agreements.ts`, `payments.ts`,
    `staffQueue.ts`;
  - `src/views/refusalMessages.ts` (new), `CaptureForm.vue`, `AgreementStatus.vue`,
    `StampQuoteStep.vue`, `StaffConsole.vue`;
  - the matching tests.
- **Out of scope.** `stampQuote.ts`, `signingProgress.ts` and `recovery.ts` throw on GETs that
  have no typed refusal a view acts on, so they are left alone. No backend change, no new
  endpoint, no migration.
- **Signing-status FSM:** none. No transition is touched, and the client only reads refusals.
- **PII / security checklist:** none.
  - From the three typed modules the client reads only the problem `type` URN. It logs no response
    body, type or exception, and adds no rendering of server `detail`. The existing
    `describeProblem` text on the create path is unchanged.
  - The jurisdiction properties the server sends are server-derived and are not displayed.
  - The new copy creates no state oracle: the server checks ownership (404) before the terms
    freeze, so only the owner can see the frozen-terms message.
  - No Aadhaar, OTP, VID, PII or secret moves. Sandbox and dummy data only, as before.
- **Customer-facing promises:** the Terms and FAQ are unaffected. The new copy describes existing
  behaviour (terms lock at order placement; stamping is limited by jurisdiction) and does not
  change any promise.
