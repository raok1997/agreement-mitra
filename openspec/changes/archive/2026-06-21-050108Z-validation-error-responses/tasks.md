# Tasks

Behavioral change → both a unit and an integration test task are included
(per the project testing-pyramid rule).

## 1. Error model + exception type

- [x] 1.1 Add `ResourceNotFoundException` (a `RuntimeException`) in the root
      package `in.agreementmitra`, message constructor. The message is for
      redacted server-side logging only — it MUST NOT reach the response body.
- [x] 1.2 Add a small `FieldErrorDetail` record (`{String field, String message}`)
      for the `errors[]` entries — colocated with the handler in the root package.
      (Named to avoid collision with `org.springframework.validation.FieldError`,
      which the handler also references.)

## 2. Global exception handler

- [x] 2.1 Add `GlobalExceptionHandler` (`@RestControllerAdvice extends
      ResponseEntityExceptionHandler`) in `in.agreementmitra` (root package, not a
      Modulith module). It MUST import no `signing`-internal type (root is not a
      module, so a root→module reach-in is not caught by `ModularityTests`).
- [x] 2.2 Override `handleMethodArgumentNotValid` → 400 ProblemDetail with the
      `errors` list: map each Spring `FieldError` to a `FieldErrorDetail`
      `{getField(), getDefaultMessage()}`;
      map each global `ObjectError` (class-level `@ValidSignerSet`) to the constant
      field token `"signers"`. NEVER read `getRejectedValue()`. Build a fresh
      ProblemDetail with a constant `detail` (do not enrich `super`/`ex.getBody()`).
      Add a code comment recording the single-class-level-constraint assumption.
- [x] 2.3 Override `handleHttpMessageNotReadable` → 400 ProblemDetail with a
      CONSTANT `detail`; never built from `ex.getMessage()`/cause. No parser
      internals, no raw-payload echo, no stack trace.
- [x] 2.4 Override `handleTypeMismatch` (the real `ResponseEntityExceptionHandler`
      hook covering `MethodArgumentTypeMismatchException`) → 400 ProblemDetail with
      a CONSTANT `detail` — NOT a parallel `@ExceptionHandler`, and never echo the
      offending value.
- [x] 2.5 Add `@ExceptionHandler(ResourceNotFoundException)` → 404 ProblemDetail
      with a CONSTANT `detail` ("Resource not found"); never the exception message
      or the requested id.
- [x] 2.6 Add a defensive `@ExceptionHandler(ConstraintViolationException)` → 400
      ProblemDetail with a constant `detail` (Spring otherwise maps it to 500). Not
      integration-reachable today (no `@Validated` param constraints); unit test
      only (see 4.4).
- [x] 2.7 Set a stable `type` URN + human `title` per category:
      `urn:agreementmitra:problem:{validation | malformed-request | type-mismatch |
      constraint-violation | resource-not-found}`.

## 3. Wire-up

- [x] 3.1 Change `AgreementController.get` to throw `ResourceNotFoundException`
      for an unknown id instead of `ResponseEntity.notFound().build()`.
- [x] 3.2 Set `spring.mvc.problemdetails.enabled=true` in `application.yml` so
      un-overridden built-in MVC exceptions also render as ProblemDetail. (Governs
      only the un-overridden path; overridden handlers own their bodies.)

## 4. Tests — unit (no Spring context)

- [x] 4.1 `GlobalExceptionHandlerTest`: a `MethodArgumentNotValidException` with
      field + global errors maps to the expected `errors[]` (field tokens +
      messages); cross-field global error surfaces as `field="signers"`, non-null.
- [x] 4.2 PII-redaction unit test: a `FieldError` carrying a rejected email value
      produces an `errors[]` entry with NO rejected value, and the serialized body
      does not contain the email.
- [x] 4.3 Multiple *field* violations → one `errors[]` entry per violated field
      constraint (verifies the "all listed" guarantee).
- [x] 4.4 `ConstraintViolationException` maps to a 400 ProblemDetail with the
      constant detail (no echoed value).

## 5. Tests — integration (real HTTP, Testcontainers)

- [x] 5.1 In `AgreementApiIntegrationTest`, tighten the existing status-only 400
      assertions: `duplicateEmailIsRejectedWith400AndPersistsNothing` →
      `application/problem+json`, `status=400`, `errors[]` present and the
      duplicated email absent from the body; `missingRoleIsRejectedWith400` →
      `errors[]` contains `field="signers"`.
- [x] 5.2 Add an integration test: POST an invalid-email body → 400 problem+json
      whose body does NOT contain the submitted email (end-to-end PII safety).
- [x] 5.3 Tighten `unknownIdReturns404` → `application/problem+json`, constant
      `detail`, and the requested id NOT reflected in the body.
- [x] 5.4 Add `GET /api/agreements/not-a-uuid` → 400 problem+json AND assert the
      body does NOT contain the string `not-a-uuid` (type-mismatch leak guard).
- [x] 5.5 Add a malformed-JSON POST (embedding a PII-looking value) → 400
      problem+json; assert no stack trace, no raw-payload echo, and the embedded
      value absent.

## 6. Verify

- [x] 6.1 `./run-tests.sh` (or `./gradlew check`) green, including
      `ModularityTests` (root-package handler is not a module) and the security
      scan gates.
