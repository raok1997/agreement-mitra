## Why

The backend has only `ModularityTests` — no way to write an integration test that
exercises real infra (Postgres + object storage). Every later feature story (starting
with the signing flow) is supposed to ship unit **and** integration tests from day one,
per the tightened dev-policy. That is impossible until the harness exists: a reusable
Testcontainers-backed base, Spring Modulith test wiring, and a coverage gate. This CR
builds that harness so the first feature CR inherits "tests from the first story."

## What Changes

- Add **Testcontainers** (Postgres + a MinIO/S3-compatible container) as test-scoped
  dependencies, exposed through a **reusable base test configuration** that future
  module/integration tests extend instead of re-declaring containers.
- Wire the containers into Spring via **`@ServiceConnection`** so the Spring context
  picks up the live Postgres (and S3/MinIO settings) automatically — no hand-rolled
  `@DynamicPropertySource`.
- Add **Spring Modulith test wiring**: a base for `@ApplicationModuleTest` slice tests
  (the `spring-modulith-starter-test` dependency is already present) so a single module
  can be booted in isolation against the harness.
- Add a **JaCoCo coverage gate** to the Gradle build (`jacocoTestReport` +
  `jacocoTestCoverageVerification`) wired into `check`, with a deliberately low initial
  threshold (a floor, not a target) so the gate exists from the start and ratchets up
  as features land.
- Add a **harness smoke test** that proves the harness works end to end: the Spring
  context boots against the Testcontainers Postgres, the MinIO/S3 container is reachable
  (bucket create/exists round-trip), and `ModularityTests` stays green.
- **Out of scope (deliberate):** no integration test against the signing API. The
  signing endpoints are stubs that throw `UnsupportedOperationException`
  ([SigningController](../../../backend/src/main/java/in/agreementmitra/signing/api/SigningController.java),
  [LeegalityEsignProvider](../../../backend/src/main/java/in/agreementmitra/signing/leegality/LeegalityEsignProvider.java));
  there is no behavior to assert. The real signing integration test ships with the
  first signing-feature CR (`create-signing-request`).

## Capabilities

### New Capabilities
- `backend-test-harness`: the backend integration-test harness — Testcontainers-backed
  Postgres + object storage, Spring Modulith slice-test wiring, a JaCoCo coverage gate,
  and a harness smoke test that proves all of the above boots green.

### Modified Capabilities
<!-- None. dev-policy is a separate capability and its requirements are unchanged;
     this CR satisfies that policy rather than altering it. -->

## Impact

- **Build**: `backend/build.gradle.kts` (Kotlin DSL) — add `jacoco` plugin,
  `spring-boot-testcontainers` + `postgresql`/`minio` test deps (versions managed by
  Spring Boot's BOM — no separate Testcontainers BOM), JaCoCo report + verification tasks
  wired into `check`.
- **Test schema provisioning**: a test profile sets `ddl-auto: create-drop` so
  JPA-backed integration tests build their schema from entities against the ephemeral
  container. Production `ddl-auto: validate` is left untouched. Real migration tooling
  (Flyway) is a deliberate future decision (its own CR), not this one.
- **Tests**: new `src/test/java` support classes (Testcontainers base config + Modulith
  test base) and one smoke test. Existing `ModularityTests` unchanged and must stay green.
- **No production code touched.** No `src/main` changes, no new runtime dependencies,
  no API or module-boundary changes.
- **Local dev**: running the integration test requires a Docker daemon (Testcontainers);
  note this in the test docs/Commands.

## PII / security review checklist

- **Introduces or moves Aadhaar / OTP / VID / PII or secrets?** None. This is test/build
  infrastructure only; it adds no runtime data flow and no production code path.
- **Redaction / securing:** N/A — no PII or secret data is handled.
- **Sandbox + dummy data only:** preserved. Testcontainers spin up ephemeral local
  Postgres/MinIO with throwaway dummy credentials generated per test run; nothing real,
  nothing committed.
- **Signing-status FSM transitions touched:** none — no signing behavior is added or
  changed (signing API stays stubbed, by design).
