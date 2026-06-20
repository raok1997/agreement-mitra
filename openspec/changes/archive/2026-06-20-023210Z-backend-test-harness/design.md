## Context

The backend ([backend/build.gradle.kts](../../../backend/build.gradle.kts), Kotlin DSL) has
Spring Boot 3.4, Spring Modulith 1.3, Postgres at runtime, and only `ModularityTests` in
test scope. Default profile runs `ddl-auto: validate` with no entities and no Flyway yet. The
dev-policy (CR-1, archived) now requires unit **and** integration tests per behavioral
story, but no integration-test infra exists. This CR builds that infra so the first
signing-feature CR can include real tests without first inventing a harness.

Constraints: Spring Modulith monolith (module boundaries must stay enforced); Postgres
for state, MinIO/S3 for blobs; sandbox + dummy data only; no production code change in
this CR. Integration tests need a Docker daemon (Testcontainers).

## Goals / Non-Goals

**Goals:**
- A reusable Testcontainers base (Postgres + object storage) wired via `@ServiceConnection`.
- Spring Modulith `@ApplicationModuleTest` slice-test support against that base.
- A JaCoCo report + verification gate wired into `check`, with a low initial floor.
- One smoke test proving the harness boots green; `ModularityTests` stays green.

**Non-Goals:**
- No signing/feature behavior or integration test (signing API stays stubbed — by design).
- No frontend test harness (separate CR-3).
- No security/dependency scanning (CR-4) or Spring Security (CR-5).
- Not setting a meaningful coverage *target* — the floor is a placeholder to ratchet later.

## Decisions

**Object-storage container: MinIO over LocalStack.** Prod uses MinIO locally / S3 in
prod, and `docker-compose` already runs MinIO. Testcontainers ships a `MinIOContainer`;
using the same engine the app targets keeps the test honest. LocalStack is heavier and
emulates all of AWS, which we don't need. Trade-off: MinIO has no Spring Boot
`@ServiceConnection` auto-config, so its endpoint/credentials are surfaced via a
**`DynamicPropertyRegistrar` bean** (the Boot 3.4 idiom — resolves `storage.*` from the
MinIO container bean; works uniformly across test types, unlike a static
`@DynamicPropertySource` method) while **Postgres** uses native `@ServiceConnection`.
Acceptable: the spec's "no hand-rolled wiring" constraint is scoped to the **datasource
only** — MinIO is explicitly allowed its few lines of property wiring because no starter
exists yet.

**`@ServiceConnection` for Postgres, not `@DynamicPropertySource`.** Spring Boot 3.1+
auto-configures the datasource from a `@ServiceConnection`-annotated `PostgreSQLContainer`.
Less boilerplate, less drift, the idiomatic path. This requires the
`org.springframework.boot:spring-boot-testcontainers` test dependency (it provides both
`@ServiceConnection` and Testcontainers integration) — not just the raw `testcontainers`
artifacts. The container is declared as a **`@Bean @ServiceConnection`** in a shared
`@TestConfiguration` (see below) rather than a `static` field, because a `@ServiceConnection`
field on a superclass is **not** picked up by `@ApplicationModuleTest` (implementation
revealed this: the slice test's datasource fell back to a dead `localhost` and Hibernate
could not determine a dialect). A `@Bean`-based service connection wires uniformly across
`@SpringBootTest` and `@ApplicationModuleTest`.

**Test schema from `create-drop`, not Flyway (this CR).** Production runs `ddl-auto:
validate`; against a virgin Testcontainers Postgres that passes today only because there
are no entities. The moment the first feature CR adds an `@Entity`, `validate` would fail
the empty container. To keep the harness genuinely reusable without dragging production
migration tooling into a test-infra CR, a **test profile sets `ddl-auto: create-drop`** so
integration tests build their schema from JPA entities per run. Production config is
untouched. Adopting Flyway/Liquibase as the real migration mechanism is a separate,
deliberate future decision — not folded in here (it would touch `src/main` startup, which
this CR explicitly avoids).

**Docker-availability guard on integration/smoke tests.** Testcontainers throws (it does
**not** auto-skip) when no Docker daemon is present, so a naive `check → test` wiring would
turn every Docker-less machine/CI runner red. Integration and smoke tests are therefore
guarded with **`@Testcontainers(disabledWithoutDocker = true)`** so they **skip**, not fail,
without Docker. Unit tests stay context-free and always run. (Verified both ways during
apply: with Docker all five tests run and pass; without it the three container-backed tests
skip and `check` stays green.)

**Modulith JPA slice support is scaffolding, not a behavioral test yet.** All current
modules are empty stubs (no beans, no persistence), so an `@ApplicationModuleTest` can only
prove the *slice mechanism boots in isolation* — it cannot make a meaningful behavioral
assertion. This CR delivers the slice-test **base**; the first real module test ships with
the feature that adds behavior. If/when entities land, `spring-modulith-starter-jpa` (event
publication registry / JPA module bootstrapping) is added by that CR.

**A single shared `@TestConfiguration`, imported (composition over inheritance).** One
`HarnessTestConfig` declares the Postgres + MinIO container beans, the
`DynamicPropertyRegistrar`, and the `MinioClient`; tests pull it in with `@Import`. The
container beans are Spring-managed (Boot auto-starts container beans) and reused via the
**test context cache** — shared across a test class's methods and across tests with an
identical context, rather than re-declared per test. This is **not** Testcontainers
`withReuse` (which needs `~/.testcontainers.properties` opt-in and is off in CI). The
inheritance/`static`-field variant was tried first and rejected: a `@ServiceConnection`
field on a shared superclass did not wire under `@ApplicationModuleTest`. Composition via
`@Import` works for both `@SpringBootTest` (full context, the smoke test) and
`@ApplicationModuleTest` (single module) and matches the project's "composition over
inheritance" convention.

**JaCoCo floor set to an explicit 0% (a present-but-vacuous gate), marked to ratchet.**
The codebase is stub-only (interfaces + methods that throw), so real instruction coverage
is ≈0; and the integration/smoke test *skips* without Docker, dropping covered code
further. A relative "near current reality" floor is therefore ambiguous and risks a red
build in a Docker-less run. So the floor is an explicit `0.00` with a `// ratchet me up`
comment — the gate exists and runs from day one (the roadmap's intent) but cannot fail
spuriously; it tightens as features add real, covered logic. JaCoCo counts exclude
`AgreementMitraApplication`, `package-info`, and generated Modulith docs so the eventual
ratcheted floor reflects real code. Alternative (no gate until features exist) rejected.

**Smoke test = full-context boot + MinIO round-trip.** `@SpringBootTest` on the harness
base asserts the context loads (proves Postgres `@ServiceConnection`) and does a
bucket-create/exists call against the MinIO container via a **test-scoped `MinioClient`
bean** (proves the container is reachable — there is no production storage bean yet, so the
test validates container reachability, not production wiring). It asserts nothing about
signing. The harness must boot with all `esign.*` and `storage.*` env secrets
empty/dummy — the test profile supplies dummy `storage.*` values that explicitly win over
any real `S3_*`/`LEEGALITY_*` env the CI runner might export, and never reads real secrets
into the test context. This is the spec's "proves the harness boots green" scenario.

## Risks / Trade-offs

- **[Docker required for integration tests]** → Testcontainers needs a running Docker
  daemon; CI and local dev must have it. Mitigation: document in Commands/test docs; the
  smoke/integration tests are skippable locally via the standard Testcontainers behavior
  while unit tests still run. JaCoCo `check` in a Docker-less env would fail the
  integration test — note this requirement explicitly.
- **[MinIO lacks `@ServiceConnection`]** → a few lines of manual property wiring for the
  object store. Mitigation: isolate it in the one base config so it doesn't spread.
- **[Coverage floor gives false comfort]** → a low floor passes trivially. Mitigation:
  it's documented as a regression floor to ratchet, not a quality bar; CR-4 adds real
  scanning gates.
- **[Container startup slows the build]** → first integration run pulls images. Mitigation:
  static singleton containers (per-JVM, not `withReuse`); unit tests stay context-free and fast.

## Migration Plan

Additive and test-scoped only — no runtime impact, no rollback concern. Revert is deleting
the new test/build config. Existing `ModularityTests` is untouched and gates the merge.

## Open Questions

- LocalStack vs MinIO if S3-specific behavior (presigned URLs) later needs testing — defer
  until the `documents`/blob-storage feature CR actually exercises S3; MinIO suffices now.
