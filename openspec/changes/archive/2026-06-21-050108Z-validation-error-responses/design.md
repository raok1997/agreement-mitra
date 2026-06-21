## Context

The backend exposes `POST /api/agreements` and `GET /api/agreements/{id}` but has
no error-handling layer: no `@ControllerAdvice`, no `ProblemDetail`,
`spring.mvc.problemdetails.enabled` unset. Today a 400 falls through to Boot's
legacy `BasicErrorController` JSON and a 404 is an empty body
(`ResponseEntity.notFound().build()`). The existing `AgreementApiIntegrationTest`
asserts status codes only, so the body shape is currently unspecified and
untested.

This is identity/legal infra (`CLAUDE.md` security rules): error bodies must never
log or echo PII or rejected values. The leak surface is wider than just bean
validation — `MethodArgumentTypeMismatchException.getMessage()` includes the
rejected value (e.g. `not-a-uuid`), `HttpMessageNotReadableException.getMessage()`
can include JSON payload fragments, and any `detail` built from an exception
message can reflect input. The contract must actively suppress all of these.

The signing endpoints (`create-signing-request`, webhook intake) are imminent;
landing a uniform, PII-safe error contract now means every later endpoint inherits
it. This CR is orthogonal to the signing state machine.

## Goals / Non-Goals

**Goals:**
- One global handler producing RFC 9457 `application/problem+json` for client
  (4xx) failures that arise from the **Spring MVC dispatch** across all endpoints.
- A stable `errors: [{field, message}]` extension on **bean-validation 400s**
  (the only category that has per-field detail), including cross-field rules,
  consumable by the Vue frontend.
- A hard guarantee that no rejected value / PII / payload fragment appears in ANY
  error body — across field errors, global errors, type-mismatch, malformed-JSON,
  and 404 — proven by test.
- Convert the agreement 404 from empty to ProblemDetail.

**Non-Goals:**
- 5xx body reshaping (stays opaque — no leak).
- Frontend rendering of `errors[]` (forward contract only).
- Internationalization of messages.
- **Filter-chain 401/403 bodies.** `SecurityConfig` rejects unauthenticated
  requests in the security filter chain, *before* the MVC dispatch, so the advice
  cannot shape them (no `AuthenticationEntryPoint` exists yet → default 403 body).
  Explicitly out of scope; the spec is scoped to MVC-dispatch 4xx accordingly.
- Content negotiation for non-JSON `Accept` (the API is JSON-only; ProblemDetail
  is emitted as `application/problem+json` regardless).
- Any signing / eSign / webhook behavior.

## Decisions

### D1 — One `@RestControllerAdvice extends ResponseEntityExceptionHandler`, in the root package
`ResponseEntityExceptionHandler` centralizes Spring MVC's built-in exceptions with
overridable hooks, so we extend it and override the handful we care about. Placement
is the **root package `in.agreementmitra`**, alongside `SecurityConfig`. Spring
Modulith treats *direct sub-packages* of the root as modules; root-package classes
are app-wide infra and are not a module (verified: `SecurityConfig` already lives
here with `ModularityTests` green). The handler MUST import no `signing`-internal
type — only framework types and the root-package `ResourceNotFoundException` —
because root is not a module, so a root→module reach-in would NOT be caught by
`ModularityTests`.

*Alternative considered:* a dedicated `web`/`shared` Modulith module. Rejected —
adds a boundary + `package-info` + ModularityTests surface for one cross-cutting
class; the root-package precedent is simpler and established.

### D2 — Enable `spring.mvc.problemdetails.enabled=true`, with a bounded contract claim
The flag governs **only the un-overridden path**: built-in MVC exceptions we do
*not* explicitly handle render as a base ProblemDetail instead of the legacy body,
so the contract has no "legacy shape" holes. It is **independent of our overrides** —
on an overridden handler *we* own the body and the flag is inert there (so the
field-error body and the flag do not "cooperate"; they cover disjoint paths).

Consequence made explicit in the spec: **`errors[]` is guaranteed only on the
bean-validation 400** (and the cross-field case). Other 400s (type mismatch,
malformed JSON) and un-overridden built-ins carry ProblemDetail **without**
`errors[]`. The contract is "4xx are problem+json; bean-validation 400s
additionally carry `errors[]`," not "all 400s carry `errors[]`."

Because the flag-driven default `detail` is derived from `ex.getMessage()`, the
un-overridden path is itself a potential leak surface — see D4: we override every
built-in whose default message can reflect input, leaving the flag to cover only
message-safe built-ins (e.g. `HttpRequestMethodNotSupportedException`).

### D3 — `errors: [{field, message}]`; cross-field rule gets a stable field token
Add `errors` via `ProblemDetail.setProperty("errors", ...)`. Each entry is a record
`{String field, String message}`. The `message` is the resolved **constraint
default message** (`getDefaultMessage()`, e.g. "must be a well-formed email
address") — developer/framework-authored text that does NOT contain the input —
never the rejected value.

`MethodArgumentNotValidException` exposes `FieldError`s (path-bound) and global
`ObjectError`s (class-level constraints like `@ValidSignerSet`). Map: `error
instanceof FieldError` → `getField()`; else a constant token `"signers"` (the only
class-level constraint today). A code comment records this assumption; the mapping
unit test fails loudly if a second class-level constraint is added without
extending the mapping. Note: `@ValidSignerSet` is a *single* constraint, so a
request breaking both the role-mix and uniqueness sub-rules yields ONE `"signers"`
entry — the "multiple violations" guarantee is therefore scoped to multiple
*field* constraints (which do produce multiple entries), not to cross-field
sub-rules.

### D4 — No client-facing text is ever derived from exception messages or input (PII safety)
The load-bearing security decision, applied uniformly:
- **`errors[]` entries:** `field + getDefaultMessage()` ONLY. Never call
  `FieldError.getRejectedValue()`.
- **`detail` / `title`:** a **fixed per-category constant string** for every
  handler — bean-validation, type-mismatch, malformed-JSON, constraint-violation,
  and 404. We NEVER build `detail` from `ex.getMessage()`, `ex.getMostSpecificCause`,
  the rejected value, or the requested id.
- **404:** `detail` is a constant ("Resource not found"), NOT
  `ResourceNotFoundException.getMessage()` and NOT the requested id. (UUID id is
  PII-safe today, but the constructor-message→detail pattern would leak when later
  lookups key on email/phone; we avoid the pattern now.) The exception message may
  be used for redacted server-side logging only, never the response.

This is covered by unit tests (assert rejected email absent; assert mapping) AND
integration tests on each path (invalid email, `not-a-uuid` type mismatch,
malformed JSON with a PII-looking value, 404) asserting the offending value/string
is absent from the body.

### D5 — 404 via a root-package `ResourceNotFoundException`
`AgreementController.get` throws a new `ResourceNotFoundException` (a
`RuntimeException`) defined in the root package; the advice maps it to a constant
ProblemDetail 404 (D4). Root placement keeps the advice from importing a
`signing`-internal type (the advice serves all modules; modules may depend on the
application root). It carries only a message (used for logging) — no resource-type
discriminator; richer 404s would need rework, acceptable for now.

*Alternative:* `@ResponseStatus(404)`. Rejected — yields Boot's default body, not
our constant ProblemDetail.

### D6 — Built-in coverage: override the real hooks, don't add parallel handlers
Use `ResponseEntityExceptionHandler`'s existing hooks rather than parallel
`@ExceptionHandler` methods that would create ambiguous resolution:
- `handleMethodArgumentNotValid(...)` — bean-validation 400 + `errors[]`.
- `handleHttpMessageNotReadable(...)` — malformed JSON 400, constant detail.
- `handleTypeMismatch(...)` — covers `MethodArgumentTypeMismatchException`
  (it extends `TypeMismatchException`); 400, constant detail. (NOT a separate
  `@ExceptionHandler(MethodArgumentTypeMismatchException)`.)
- `handleNoResourceFoundException(...)` is left to the flag/base (un-overridden):
  an unknown *route* renders as a safe ProblemDetail 404 automatically — distinct
  from the unknown-*resource* 404 from `ResourceNotFoundException`. Both are 404
  problem+json; only the resource case carries our constant detail.

Override signatures return `ResponseEntity<Object>` (not `ProblemDetail`); the
construction strategy is **build a fresh `ProblemDetail` with our constant detail**
(not enrich `super`/`ex.getBody()`), so no framework-populated text leaks in.

### D7 — Forward-looking `ConstraintViolationException` handler (defensive)
`ConstraintViolationException` (thrown by `@Validated` method-param validation) is
NOT a `ResponseEntityExceptionHandler` hook and Spring maps it to **500** by
default — a hole in the "uniform 4xx" claim once any endpoint adds `@PathVariable`/
`@RequestParam` constraints. No endpoint reaches it today, so we add a defensive
`@ExceptionHandler(ConstraintViolationException)` → 400 constant ProblemDetail with
a **unit test only** (the path is not integration-reachable yet; adding an
integration fixture would be testing a non-existent endpoint).

### D8 — `type` URN scheme
Stable URN-style `type` per category: `urn:agreementmitra:problem:validation`,
`:malformed-request`, `:type-mismatch`, `:constraint-violation`,
`:resource-not-found`. They need not resolve (RFC 9457 permits non-resolvable
`type`s); they give clients a discriminator beyond `status`. `about:blank` is the
implicit fallback for un-overridden built-ins.

## Risks / Trade-offs

- **[Most likely leak is the least obvious]** Type-mismatch / malformed-JSON
  default messages reflect input. → Mitigation: D4 mandates constant `detail`
  everywhere + a per-path "offending value absent" integration assertion.
- **[Cross-field mapping is heuristic]** `"signers"` for every global error is
  correct only while `@ValidSignerSet` is the sole class-level constraint. →
  Mitigation: unit test + code comment; a second constraint fails the test loudly.
- **[`errors[]` non-uniformity]** Only bean-validation 400s carry `errors[]`. →
  Mitigation: spec states this explicitly so the frontend treats `errors[]` as
  optional/absent on non-validation 400s.
- **[`problemdetails.enabled` global flip]** Changes every un-overridden built-in
  body app-wide. → Mitigation: intended (uniformity); message-reflecting built-ins
  are overridden (D6) so the flag covers only message-safe ones.
- **[Spec contract change]** `agreement-management` previously said the 400 body
  MAY be empty / rely on status. This CR overturns that + makes 404 a body. →
  Mitigation: captured as MODIFIED requirements; no client consumes the old shape
  (frontend not wired), so it's contract-only, zero runtime impact.
- **[403 inconsistency]** Filter-chain 401/403 are not problem+json. →
  Mitigation: documented Non-Goal; spec scoped to MVC-dispatch 4xx.

## Migration Plan

Additive, no data migration. Deploy handler + config flag + controller throw
together. Rollback = revert the commit. No persistence/schema impact.

## Open Questions

None — placement, 404 shape, coverage, PII stripping across all paths, the flag's
bounded claim, type-mismatch hook choice, and the `ConstraintViolationException`
gap are all resolved above.
