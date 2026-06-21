## ADDED Requirements

### Requirement: Client errors use the RFC 9457 ProblemDetail format

The JSON API SHALL report client-facing failures (4xx) that arise from the Spring
MVC request dispatch as an RFC 9457 `application/problem+json` body. The body SHALL
carry at least `type`, `title`, `status`, and `detail`. A single global handler
SHALL produce these responses so the contract is uniform across every endpoint,
present and future.

This requirement scopes to failures reaching the MVC dispatch. Authentication /
authorization rejections produced by the security filter chain (401/403) occur
before the dispatch and are out of scope for this format (no authentication
mechanism exists yet).

#### Scenario: A 400 response is problem+json

- **WHEN** a request fails client-side validation
- **THEN** the response `Content-Type` is `application/problem+json`
- **AND** the body contains `type`, `title`, `status` (= 400), and `detail`

#### Scenario: A 404 response is problem+json

- **WHEN** a request targets a resource that does not exist
- **THEN** the response `Content-Type` is `application/problem+json`
- **AND** the body contains `status` (= 404) and a `detail`

### Requirement: Bean-validation failures carry a field-level errors list

A bean-validation failure (`400 Bad Request` from request-body validation) SHALL include an `errors` extension property: an array of `{field, message}`
objects, one entry per violated constraint, where `message` is the constraint's
resolved default message (not the rejected input). A class-level / cross-field
constraint (e.g. the owner+tenant and unique-email rules carried by one
`@ValidSignerSet` constraint) — which Bean Validation reports as a global error not
bound to a single field — SHALL still appear in `errors`, with a stable, non-null
`field` token identifying the affected group (`signers`) rather than being dropped.

`errors[]` is guaranteed only for this bean-validation category. Other 4xx
problem+json responses (malformed JSON, type mismatch, unknown resource, and any
un-overridden built-in) MAY omit `errors[]`; clients SHALL treat it as optional.

#### Scenario: Field constraint violation is listed

- **WHEN** a client POSTs an agreement with a blank property address
- **THEN** the `errors` array contains an entry whose `field` is `propertyAddress`
  and whose `message` is the constraint message

#### Scenario: Cross-field constraint violation is listed

- **WHEN** a client POSTs an agreement whose signers are all `OWNER` (no `TENANT`)
- **THEN** the `errors` array contains an entry whose `field` is the non-null token
  `signers` (not an unbound global error)

#### Scenario: Multiple field violations are all listed

- **WHEN** a single request violates more than one *field* constraint (e.g. blank
  property address and a non-positive monthly rent)
- **THEN** the `errors` array contains an entry for each violated field constraint

### Requirement: Error bodies never echo submitted values or PII

No error body SHALL contain the rejected input value, a request payload fragment,
the requested resource id, or any submitted personally identifiable information
(name, email, and — in later signing features — Aadhaar number, OTP, or virtual
id). This holds across every path: `errors[]` entries expose only `field` +
`message`; the `detail`/`title` of every handled failure SHALL be a fixed
per-category string, never derived from the exception message, the rejected value,
or the requested id. The handler MUST strip these even though the underlying
framework exposes them by default.

#### Scenario: Rejected email value is not echoed (validation)

- **WHEN** a client POSTs an agreement with an invalid email and the request is
  rejected `400`
- **THEN** the response body does not contain the submitted email string
- **AND** each `errors` entry exposes only `field` and `message`

#### Scenario: Mistyped path value is not echoed (type mismatch)

- **WHEN** a client GETs `/api/agreements/not-a-uuid`
- **THEN** the `400` response body does not contain the string `not-a-uuid`

#### Scenario: Malformed payload fragment is not echoed

- **WHEN** a client POSTs a malformed JSON body containing a PII-looking value
- **THEN** the `400` response body does not contain that submitted value

#### Scenario: Requested id is not reflected (not found)

- **WHEN** a client GETs an agreement by an id that does not exist
- **THEN** the `404` response `detail` is a constant string and does not reflect
  the requested id

### Requirement: Malformed and mistyped requests are mapped to 400

The handler SHALL map an unparseable / malformed request body
(`HttpMessageNotReadableException`) and a path-variable type mismatch
(`MethodArgumentTypeMismatchException`, e.g. a non-UUID id) to `400 Bad Request` as
ProblemDetail with a constant `detail`. The body SHALL NOT leak parser internals,
the raw payload, the offending value, or a stack trace.

#### Scenario: Malformed JSON body is rejected

- **WHEN** a client POSTs a body that is not valid JSON
- **THEN** the system responds `400 Bad Request` as `application/problem+json`
- **AND** the body contains no stack trace and no echo of the raw payload

#### Scenario: Non-UUID path id is rejected

- **WHEN** a client GETs `/api/agreements/not-a-uuid`
- **THEN** the system responds `400 Bad Request` as `application/problem+json`

### Requirement: Unexpected server errors stay opaque

A `5xx` / unexpected error SHALL NOT be reshaped by this contract into a body that
exposes internal detail. Server faults SHALL continue to return without a stack
trace, message dump, or other internal information in the body.

#### Scenario: Server fault leaks nothing

- **WHEN** an unexpected server-side error occurs
- **THEN** the response body contains no stack trace, exception class, or SQL/
  internal message
