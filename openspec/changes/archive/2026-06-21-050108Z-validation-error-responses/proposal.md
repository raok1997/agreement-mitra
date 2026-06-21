## Why

The JSON API has no error contract. Validation failures fall through to Spring
Boot's legacy default error JSON, and an unknown agreement returns an empty
404 body — both undocumented, both inconsistent, neither machine-readable by the
Vue frontend. Worse, Spring's default field-error rendering can echo the
*rejected value* back to the client; on identity/legal infra that risks leaking
submitted PII (email today, Aadhaar/OTP later) into an error response. Before the
signing endpoints land we want a single, PII-safe, standards-based error contract
in place so every endpoint reports failures the same way.

## What Changes

- Adopt **RFC 9457 `application/problem+json`** as the uniform error body for
  client-facing failures on the JSON API.
- Add a global `@RestControllerAdvice extends ResponseEntityExceptionHandler` in
  the **root package** (`in.agreementmitra`, alongside `SecurityConfig`) so Spring
  Modulith does not classify it as a module — cross-cutting web infra, same
  precedent as the security config.
- Map these failures to ProblemDetail (400 unless noted):
  - bean-validation field errors (`MethodArgumentNotValidException`),
  - the `@ValidSignerSet` cross-field rule (a class-level/global `ObjectError`,
    not field-bound — mapped deliberately into the errors list),
  - malformed / unparseable request JSON (`HttpMessageNotReadableException`),
  - path-variable type mismatch (`MethodArgumentTypeMismatchException`, e.g.
    `/api/agreements/not-a-uuid`),
  - **404** for an unknown agreement: `AgreementController` throws a new
    root-package `ResourceNotFoundException`, the advice renders it (replacing
    today's empty `notFound().build()`).
- Add a stable `errors: [{field, message}]` extension property on 400 bodies so
  the frontend can render field-level messages against one shape.
- **Security invariant:** error bodies carry `field + message` ONLY — never the
  rejected value or any submitted PII. Enforced by test.
- 500 / unexpected errors stay opaque (no body change, no leak) — out of scope.
- **BREAKING (contract):** the 404 body changes from empty to a ProblemDetail,
  and 400 bodies change from Boot's default shape to ProblemDetail. No client
  consumes these yet (no frontend wired to them), so impact is nil today; noted
  for completeness.

## Capabilities

### New Capabilities
- `api-error-handling`: how the JSON API reports client-facing failures — the
  RFC 9457 ProblemDetail contract, the set of mapped failure conditions, the
  `errors[]` extension shape, and the never-echo-PII invariant.

### Modified Capabilities
- `agreement-management`: the read-by-id requirement changes its not-found
  behavior from an empty 404 to a ProblemDetail 404 (spec-level behavior change).

## Impact

- **Code (backend):** new `GlobalExceptionHandler` + `ResourceNotFoundException`
  in `in.agreementmitra` (root package); `AgreementController.get` throws instead
  of returning empty 404; possible `application.yml` flag
  `spring.mvc.problemdetails.enabled=true` (decided in design).
- **Tests:** new unit tests for the advice mapping (field, cross-field/global,
  PII-redaction); new integration assertions on real 400/404 ProblemDetail
  bodies; existing status-only assertions in `AgreementApiIntegrationTest`
  tightened to assert body shape.
- **Modulith:** root-package placement keeps `ModularityTests` green (no new
  module, no cross-module reach-in).
- **Security posture:** `/error` permit rule in `SecurityConfig` already exists;
  no security-config change expected.
- **Frontend:** none in this CR; the `errors[]` shape is the forward contract.
- **Out of scope:** signing state machine, eSign/webhook logic, 5xx bodies,
  frontend rendering.
