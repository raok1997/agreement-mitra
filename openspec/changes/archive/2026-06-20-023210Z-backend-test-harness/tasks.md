> **Test-policy exemption:** This is a pure test/build-harness change — it adds no runnable
> production behavior, so the unit+integration-test-per-behavior rule does not apply. The
> harness smoke test (task 4) is itself the integration-level proof that the harness boots.
>
> All Gradle edits target **`backend/build.gradle.kts` (Kotlin DSL)** — use Kotlin DSL syntax.

## 1. Build wiring (build.gradle.kts)

- [x] 1.1 Add the `jacoco` plugin to the `plugins { }` block.
- [x] 1.2 Add test-scoped deps: `org.springframework.boot:spring-boot-testcontainers`
  (provides `@ServiceConnection`), `org.testcontainers:postgresql`,
  `org.testcontainers:minio`, `org.testcontainers:junit-jupiter`, and the MinIO Java
  client (`io.minio:minio`) for the bucket round-trip. Versions for the Testcontainers
  modules come from Spring Boot's managed BOM — no separate Testcontainers BOM.
- [x] 1.3 Configure `jacocoTestReport` (XML + HTML) and `jacocoTestCoverageVerification`
  with an explicit instruction floor of `0.00` (`// ratchet me up`), excluding
  `AgreementMitraApplication` and `package-info` from counts. The floor passes while code
  is stub-only and when Testcontainers tests are skipped.
- [x] 1.4 `test` is `finalizedBy(jacocoTestReport)`; `check` depends on
  `jacocoTestCoverageVerification`.

## 2. Testcontainers config (shared, imported)

- [x] 2.1 Add `HarnessTestConfig` (`@TestConfiguration`) with a `@Bean @ServiceConnection`
  `PostgreSQLContainer` so Spring auto-wires the datasource. (Bean, not a `static` field:
  a `@ServiceConnection` field on a superclass does not wire under `@ApplicationModuleTest`.)
- [x] 2.2 In the same config, add a `MinIOContainer` bean, a `MinioClient` bean pointing at
  it, and a `DynamicPropertyRegistrar` bean that surfaces the container endpoint + dummy
  `storage.*` so test values win over any real `S3_*` env. Container-default creds and
  container-mapped ports only — no fixed host binding, no literal key-like strings.
- [x] 2.3 Add `application-test.yml` setting `ddl-auto: create-drop` (+ empty `esign.*`)
  so JPA-backed integration tests build their schema per run; production `validate` stays
  untouched. Containers are shared via the test context cache (Boot auto-starts container
  beans), not `withReuse`.

## 3. Modulith / integration test wiring

- [x] 3.1 Tests import `HarnessTestConfig` and carry `@Testcontainers(disabledWithoutDocker
  = true)` + `@ActiveProfiles("test")` so they skip — not fail — without Docker. Usable by
  both `@SpringBootTest` and `@ApplicationModuleTest`.
- [x] 3.2 `SigningModuleSliceTest` (`@ApplicationModuleTest` in the `signing` package —
  which has beans) confirms the slice **boots in isolation**. Boot/compile proof of the
  mechanism only — NOT a behavioral assertion (modules are empty stubs).

## 4. Harness smoke test

- [x] 4.1 `HarnessSmokeTest` (`@SpringBootTest`) asserts the context loads against the
  Testcontainers Postgres, with `esign.*`/`storage.*` secrets empty/dummy.
- [x] 4.2 Same test creates a bucket via the `MinioClient` and asserts it exists
  (container-reachability round-trip — not production storage wiring, which doesn't exist
  yet). Asserts nothing about the signing API.

## 5. Verify & document

- [x] 5.1 `./gradlew check` with Docker running: all 5 tests pass (HarnessSmokeTest ×2,
  SigningModuleSliceTest, ModularityTests ×2) and the JaCoCo verification gate runs green.
- [x] 5.2 Without Docker the 3 container-backed tests skip (no red build); the only
  `src/main` diffs are Spotless `googleJavaFormat` reformatting churn from the format hook
  (no behavioral change, excluded from this CR's commit); no new runtime dependency added.
- [x] 5.3 `CLAUDE.md` Commands notes that backend integration tests require a running
  Docker daemon (and skip cleanly without one).
