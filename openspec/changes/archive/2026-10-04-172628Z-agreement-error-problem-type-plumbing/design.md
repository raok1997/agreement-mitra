## Context

The backend renders every `ConflictException.Kind` as its own `urn:agreementmitra:problem:<kind>`
type (`GlobalExceptionHandler.java:178-260`).

The frontend reads that type in only two places, each with its own private `problemTypeOf` copy:
- `updateAgreementContacts` (`agreements.ts:157`);
- `uploadStampForEntry` (`staffQueue.ts:172`).

Per-class getters then match the type with `endsWith(...)`. Separately, `http.ts` holds two
full-URN constants (`csrf`, `render-busy`) and compares them exactly.

So the same fact, "which problem types exist and how to read one", lives in three places, written
two different ways. And reading the type is opt-in at each throw site: 9 of the 11 sites leave it
out.

The cases where customers hit this (the grounded call × kind table, as of 2026-10-04):

| Call (customer view) | Refusal kinds the server can return | Remedy differs from "try again"? | This change |
|---|---|---|---|
| `finaliseAgreement` (capture form) | `jurisdiction-unsupported`, `draft-required`, `agreement-closed` | jurisdiction: yes | jurisdiction copy; others → generic |
| `startCheckout` via `payForAgreement` (capture form, status view) | `jurisdiction-unsupported` (re-gate), `contact-required`, `stamp-choice-invalid` (400) | jurisdiction: yes; contact-required: yes | jurisdiction copy; others → generic (see Non-Goals) |
| `updateAgreement` (edit save) | `draft-frozen`, 400 validation | draft-frozen: yes | terms-frozen copy; 400 → generic (see Non-Goals) |
| `updateAgreementContacts` (contact step) | `contacts-frozen`, `agreement-closed` | contacts-frozen: yes | unchanged copy, now via the shared predicate |

Today every row except the last renders `… request failed: <status>`.

**How reachable each row is.**
- In the capture form, the jurisdiction rows are a **race path**. The stamp step hides the
  choice when `!quote.available`, and that flag comes from the same `payable()` predicate the gate
  uses. So finalise or checkout refuses only if eligibility changes after the quote loaded.
- In the status view, the row is reachable but narrow: an outstanding order inside its TTL whose
  eligibility was removed after it was placed.
- The frozen-terms row is reachable from a stale "My Agreements" list.
- The generic status-string fallback is reachable on any server failure. The status view's payment
action renders "Contact support quoting your reference", which is the wrong remedy for an
unsupported jurisdiction.

## Goals / Non-Goals

**Goals:**
- One place defines the problem-type URNs and how a type is read from a response.
- In the three modules it is structurally impossible to throw an error without its type, and a
  guard test fails the build if anyone tries.
- The jurisdiction and frozen-terms refusals show their own copy, each from a single definition.
- The listed customer actions show an error's own message only from an allowlist, never a status
  string.

**Non-Goals:**
- **`contact-required` at checkout.** In the capture form the contact step runs immediately before
  checkout, using the same reachability rule (`PaymentOrderService.java:140`). The status view
  cannot edit contacts, so its only remedy would be support. Both views get the generic message.
- **Edit-save 400 field messages.** The capture form's completeness rules already agree with the
  server's validation (`preview-centric-capture`), so a 400 on edit is not reachable in normal use.
  The generic message is enough. This is not a regression: today it shows a status string.
- **Other surfaces.** `stampQuote.ts`, `signingProgress.ts`, `auth.ts`, `recovery.ts`, preview and
  form-load errors are left alone. These are GETs with no typed refusal that any view branches on,
  and none of them reach the actions in the third requirement.
- **Rendering server data.** Server `detail` from the three typed modules, and the
  `jurisdiction` / `eligibleJurisdictions` properties, are not rendered.
- **The draft-only banner** (`CaptureForm.vue:1463-1471`). It is a disclosure shown before any
  action, with its own layout and emphasis, while the refusal message responds to an attempted
  payment. They are different moments, so they keep separate wording.
- Any backend change.

## Decisions

**D1 — A new `src/api/problems.ts` owns problem types.** It exports three things.
- `PROBLEM`, an `as const` map of full URNs: `jurisdictionUnsupported`, `draftFrozen`,
  `contactsFrozen`, `paymentRequired`, `csrf` and `renderBusy`.
- `ProblemType`, the union of its values.
- `problemTypeOf(res)`.
  - It calls only `res.json()`, never `clone()`, and does not check `Content-Type`. Test fakes
    carry neither, and the server's problem body is JSON regardless.
  - It returns the body's `type` when that is a string, and `null` otherwise, including on a
    parse failure. It never throws.
  - It never logs the body, the type, or the exception.
- `hasProblemType(e: unknown, type: ProblemType)`.
  - It returns `false` for `null`, `undefined`, primitives, and any object whose `problemType`
    is not a string.
  - Otherwise it compares with `===`.

`http.ts` imports its CSRF and render-busy URNs from here rather than declaring its own. Typing
the parameter as `ProblemType` means a mistyped URN fails `vue-tsc` rather than silently never
matching.

These constants are a deliberate client copy of the backend's `TYPE_*` constants: the wire
contract, not a duplicated rule.

Test fixtures build response bodies from **literal server URNs**, never from `PROBLEM.*`, and one
test pins each `PROBLEM` value to its literal. That catches a client-side typo. It does **not**
catch a backend rename: the server side of the contract is pinned by the `api-error-handling`
spec and the backend's own tests. That limit is accepted.

*Alternative considered:* putting all of this in `http.ts`. Rejected because `http.ts` is the
transport wrapper; its header scopes it to CSRF, the reconcile hook and load refusals.

**D2 — Errors are built only through `static async from(res)`.** `AgreementHttpError`,
`PaymentHttpError` and `StaffQueueHttpError` each gain:

```ts
static async from(res: Response): Promise<X> {
  return new this(res.status, await problemTypeOf(res));
}
```

`PaymentHttpError` also gains `problemType` (default `null`). Every throw site becomes
`throw await X.from(res)`. Constructors keep `(status, problemType = null)` so tests can build
instances.

**Guard test.** A guard test, a sibling of `apiFetchGuard.test.ts`, scans every non-test `.ts`
file under `src/api`, recursively. It fails on **any** occurrence of
`new (Agreement|Payment|StaffQueue)HttpError(`. Because `from` uses `new this`, the rule needs no
brace-aware parsing.

**Reading order.** `from(res)` is the last reader of a response. It runs only on the throw path,
after `apiFetch`'s CSRF and busy checks, which read `res.clone()`. It must never be called inside
`apiFetch`, or the CSRF retry would see a consumed body.

*Alternatives considered:*
- A shared base class. Rejected because views branch on `instanceof` per class
  (`AgreementStatus.vue:163`), and `hasProblemType` needs only a structural `problemType`.
- A shared `httpErrorFrom(Ctor, res)` helper. Rejected because a one-line `new this(...)` per class
  is already the single construction path, and the guard targets it directly.

**D3 — The per-class getters are removed in the same task as their call sites.** Three getters go:

| Getter | Call sites |
|---|---|
| `contactsFrozen` | `CaptureForm.vue:914` |
| `jurisdictionUnsupported` | `CaptureForm.vue:961` |
| `paymentRequired` | `StaffConsole.vue:258` |

Each call site becomes `hasProblemType(e, PROBLEM.x)`. A getter that lives on only one class is
exactly what left `PaymentHttpError` unable to answer the jurisdiction question. `StaffConsole`
keeps its branch order (busy → payment-required → 409 → 400) and its `instanceof` guards on the
status branches.

**D4 — An allowlist decides which errors show their own message.** In `http.ts`,
`export class CustomerFacingError extends Error` marks an error whose message is written for
customers. It lives in `http.ts`, not `problems.ts`, because `ServiceBusyError`, which is defined
there, extends it. It is an error class, not problem vocabulary. A guard rule allows
`new CustomerFacingError(` only in `http.ts`, `client.ts` and `payments.ts`. Three things use it:
- `ServiceBusyError` now extends it;
- the three `client.ts` `describeProblem` throws construct it;
- the three "Could not load the payment window." throws in `payments.ts` construct it.

The existing behaviour of those errors is unchanged; they are still `Error`s with the same
messages.

`src/views/refusalMessages.ts` exports:
- `JURISDICTION_UNSUPPORTED_MESSAGE`, moved verbatim from `CaptureForm.vue`;
- `TERMS_FROZEN_MESSAGE` (new): "This agreement's order has already been placed, so its terms can
  no longer be changed. Contact support if something in it is wrong.";
- `customerMessage(e, fallback)`, which returns `e.message` when `e` is a `CustomerFacingError`
  with a non-empty message, and `fallback` otherwise.

That leaves no rule that depends on `status`, on `problemType` being present, or on its value. A
null-typed 500, a status-only error from an out-of-scope module, and a `TypeError: Failed to fetch`
all reach the fallback.

Copy stays in `src/views`. `busyMessage` stays where it is.

Where the messages are used:
- `finaliseAndPay` checks the jurisdiction type on either error class first, then calls
  `customerMessage`.
- `saveAndContinue` checks draft-frozen first, then calls `customerMessage`.
- `confirmContacts` checks contacts-frozen first, then calls `customerMessage`.
- `AgreementStatus.payWith` checks the jurisdiction type first, then calls `customerMessage`
  with its existing "contact support" text as the fallback.
- `StampQuoteStep`'s unavailable notice (`:172-176`) renders the same jurisdiction constant.
  `quote.available` uses the same `payable()` predicate as the jurisdiction gate
  (`PaymentOrderService.stampQuote:325`), so it is the same refusal stated ahead of time. The
  one exception is quote status `UNPLANNABLE`: a supported state whose duty no stamp paper
  covers. It keeps the neutral "not available for this agreement yet" wording
  (`STAMP_UNPLANNABLE_MESSAGE`), because naming the jurisdiction there would be wrong.

`describeProblem`'s last-resort fallback (`client.ts:138`) drops its `(status)`, so no allowlisted
message carries a status code.

**D5 — The terms-frozen wording names order placement, not signing.**
`SigningRequestService.finalise` creates the signing request (`placeOrder`, `:236-238`), and
`AgreementService.update` refuses once one exists (`:268`). So from the customer's side, terms
lock when the order is placed. The server's `detail` ("after signing has been requested")
describes the data model, not the customer's experience. The client does not render it for this
call.

## Risks / Trade-offs

- [The guard regex is a text check and can be evaded with an alias] → It catches the realistic
  regression: the next endpoint copying `throw new X(res.status)`. A self-test pins the matcher,
  as `apiFetchGuard` does.
- [Generic fallback hides a new typed refusal the customer could act on] → This is deliberate.
  "Please try again" is better than a status string, and copy for a newly reachable kind is a
  one-line `hasProblemType` branch.
- [Component tests that mock the API module and hand-build typed errors stayed green while
  production was broken] → The integration tests run the real throw path of the function under
  test (`finaliseAgreement`, `updateAgreement`, `startCheckout` via the real `payForAgreement`)
  against a stubbed `fetch` returning a literal server problem body.
- [Retry wording on a payment path] → `payForAgreement` swallows callback and poll failures after
  checkout (`payments.ts:255-279`). So "Could not start payment. Please try again." can only
  follow a pre-checkout failure: finalise, order, or script load. Each of those is idempotent
  server-side. Do not route a post-capture error into that copy.
- [A refusal message as a state oracle] → None is added. `AgreementService.update` checks
  ownership (404) before the freeze (`:263-269`), so the terms-frozen copy reaches only the
  owner. The jurisdiction copy shows an agreement's own jurisdiction status only to whoever
  already holds it. If those gates are ever reordered, that is a security regression, not a copy
  change.
- [The edit flow's `generateAgreementDocument` can 409 draft-frozen in a race after a successful
  PUT] → It shows the `describeProblem` server text ("…after signing has been requested"). That is
  customer-safe and the window is narrow, so it is left as is.

## Migration Plan

Frontend-only, with no data or API contract change. Ship as a normal build. Rollback is reverting
the commit.

## Open Questions

None.
