## 1. Schema (Flyway)

- [x] 1.1 Add `backend/src/main/resources/db/migration/V2__agreement.sql` creating
  `agreement(id uuid pk, property_address text, monthly_rent numeric(12,2),
  security_deposit numeric(12,2), term_months int, created_at timestamptz)` and
  `signer(id uuid pk, agreement_id uuid not null references agreement(id), name text,
  email text, role text)` with an index on `signer(agreement_id)`. Forward-only; do not
  edit V1.

## 2. Domain (internal package `in.agreementmitra.signing.agreement`)

- [x] 2.1 Add package-private `Role` enum (`OWNER`, `TENANT`).
- [x] 2.2 Add `Signer` child `@Entity` (table `signer`, package-private): app-assigned
  UUID id, `name`, `email`, `role` mapped `@Enumerated(EnumType.STRING)`,
  `@ManyToOne(optional=false) Agreement` (FK owner); id-based equals/hashCode;
  **id-only `toString()`** (no name/email — DEBUG-log PII hygiene, design D9);
  package-private constructor + factory.
- [x] 2.3 Add `Agreement` aggregate `@Entity` (table `agreement`, package-private):
  app-assigned UUID via `Agreement.create(...)` factory, `Instant createdAt`,
  `propertyAddress`, `monthlyRent`/`securityDeposit` as `BigDecimal`, `termMonths` int,
  **bidirectional** `@OneToMany(mappedBy="agreement", cascade=ALL, orphanRemoval=true)
  List<Signer>` with the factory wiring both sides; id-based equals/hashCode; id-only
  `toString()`. Implement `Persistable<UUID>` with a `@Transient` `isNew` flag flipped in
  `@PostPersist`/`@PostLoad` (avoid the save-time phantom SELECT, design D2). Status-less
  (no FSM field).
- [x] 2.4 Add package-private Spring Data `AgreementRepository`.
- [x] 2.5 Add `AgreementService` — **Java-`public`** (so the `signing.api` controller can
  inject it across packages) but Modulith-internal (not in a `@NamedInterface` package).
  `@Transactional create(...)` (assemble aggregate from validated input, persist, map to
  response) and `@Transactional(readOnly=true) findById(...)` (map to response or signal
  not-found). Initialize the lazy signer collection and map entity→response **inside** the
  transaction — `open-in-view: false`, so no entity/lazy proxy may cross the controller
  boundary (design D3).

## 3. HTTP surface (public package `in.agreementmitra.signing.api`)

- [x] 3.1 Add `CreateAgreementRequest` record (`propertyAddress`, `monthlyRent`,
  `securityDeposit`, `termMonths`, `List<SignerRequest> signers`) + nested `SignerRequest`
  record (`name`, `email`, `role`) — no id/createdAt fields (anti-mass-assignment). Add
  per-field constraints: `@NotBlank` property address + signer name; `@Email` email;
  `@Size(min=1, max=20)` + `@Valid` signers; `monthlyRent` `@NotNull @Positive
  @Digits(integer=10, fraction=2)`; `securityDeposit` `@NotNull @PositiveOrZero
  @Digits(integer=10, fraction=2)` (zero deposit allowed); `termMonths` `@Positive`
  (design D8).
- [x] 3.2 Add `AgreementResponse` + nested `SignerResponse` records carrying
  server-assigned ids, roles, and `createdAt` (`Instant`, serialized ISO-8601 — no custom
  format).
- [x] 3.3 Add the package-private class-level constraint (`@ValidSignerSet` + validator)
  on `CreateAgreementRequest`: ≥1 OWNER && ≥1 TENANT, and no duplicate emails within the
  agreement (**case-insensitive**). Validator is **null-safe**: returns `true` on a
  null/empty signer list or null email (those are owned by `@Size`/`@NotNull`/`@Email`) —
  must not NPE.
- [x] 3.4 Add `AgreementController`: `POST /api/agreements` (`@Valid` body → 201 with
  `AgreementResponse`) and `GET /api/agreements/{id}` (200 or 404). Constructor-inject the
  service.

## 4. Security

- [x] 4.1 Add **two scoped** permits to the root `SecurityConfig` —
  `requestMatchers(POST, "/api/agreements").permitAll()` and
  `requestMatchers(GET, "/api/agreements/*").permitAll()` (NOT a `/**` wildcard, so
  future sub-paths stay denied by default) — with a TEMPORARY/sandbox comment matching
  the existing `/api/signing/*/request` permit style (design D7).

## 5. Tests — unit (no Spring context)

- [x] 5.1 `Agreement.create(...)` assigns a non-null UUID and a `createdAt`; id-based
  equals/hashCode holds; aggregate owns its signers.
- [x] 5.2 Class-level signer-set constraint via a plain `jakarta.validation.Validator`:
  PASS for ≥1 owner + ≥1 tenant with distinct emails; FAIL for all-owners (no tenant),
  all-tenants (no owner), and duplicate emails (including case-only differences, e.g.
  `a@x.com`/`A@x.com`). Assert the validator is null-safe (no NPE) on a null/empty signer
  list. Also assert per-field constraints reject: a malformed `@Email`, >20 signers, a
  negative `monthlyRent`, a zero `termMonths`, and a 3-fraction-digit money amount.
- [x] 5.3 Entity↔response mapping: a built aggregate maps to an `AgreementResponse` whose
  signer ids/roles/emails round-trip correctly.

## 6. Tests — integration (Testcontainers Postgres slice)

- [x] 6.1 `POST /api/agreements` with two owners + one tenant persists one agreement row +
  three signer rows; response is 201 with server-assigned ids; `GET /api/agreements/{id}`
  returns all three signers with their ids/roles. Assert a duplicate-email / missing-role
  body returns 400 and persists nothing, and an unknown id returns 404.
- [x] 6.2 Confirm the app boots/validates against the Flyway-migrated schema
  (`ddl-auto: validate` passes — entity mapping matches `V2__agreement.sql`), assert
  `flyway_schema_history` has `V2` applied with `success=true`, and `ModularityTests`
  stays green.

## 7. Verify

- [x] 7.1 Run `./run-tests.sh` (or `./gradlew check` with Docker) — full suite green,
  including `securityScan`, JaCoCo gate, and `ModularityTests`.
