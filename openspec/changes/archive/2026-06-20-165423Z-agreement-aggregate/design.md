## Context

CR-2 of the signing-flow roadmap, on `feat/signing-flow-v2`. The signing module is
all stubs and rests on the Flyway foundation shipped in `persistence-foundation`
(CR-1): `V1__baseline.sql` is a comment-only anchor, `ddl-auto: validate` in every
env, `flyway.clean` disabled. There is no persisted domain yet.

A prior `agreement-aggregate` (archived on `feat/best-practices`, **not merged**,
not on this branch) modelled singular landlord/tenant strings. It is discarded: real
Indian rental agreements have **N owners + N tenants**, each a distinct person who
signs via their own Aadhaar+OTP. This redo models that. The aggregate must be the
foundation CR-3 (create-signing-request, parallel multi-signer) builds on, so signers
must be individually addressable from day one.

Constraints: Java 21 + Spring Boot 3.5.15 + Spring Modulith 1.4.12 modular monolith;
records for DTOs; constructor injection; package-private by default, `public` only on
the module API; keep `ModularityTests` green; Flyway is the single schema source.

## Goals / Non-Goals

**Goals:**
- A persisted, **status-less** `Agreement` aggregate with rental terms + a collection
  of owner/tenant signers, each addressable (own id).
- Create + read HTTP endpoints with strict, anti-mass-assignment validation.
- A `V2__agreement.sql` migration whose schema the JPA mapping validates against.
- Unit-testable validation (role mix + email uniqueness) without a Spring context.

**Non-Goals:**
- No `SignatureStatus`/FSM field (CR-3). No eSign/webhook/Leegality logic (CR-3/CR-4).
- No edit/delete/list endpoints — only create + get-by-id.
- No object storage, no PDF, no document rendering (CR-5).
- Do not touch the existing single-signer `SignRequest` stub (widens in CR-3).
- No auth mechanism — `/api/agreements/**` is sandbox-permitted, tightened later.

## Decisions

### D1: Aggregate lives in `in.agreementmitra.signing.agreement` (Modulith-internal)

The `Agreement`/`Signer` entities, repository, and service go in a sub-package of
`signing`. Spring Modulith treats only `signing` and `signing.api` as the module's
public API (per `package-info`), so a sub-package is **internal** — invisible to other
*modules*. Alternative — entities in the root `signing` package — would leak them as
module API; rejected.

**Java visibility vs. Modulith-internal are different axes (review #1).** Modulith
"internal" is enforced by `ModularityTests`, not the Java compiler. The
`AgreementController` lives in `signing.api`; for it to constructor-inject the service,
the service type must be **Java-`public`** (a package-private class in
`signing.agreement` is invisible to `signing.api` and won't compile). So
`AgreementService` is Java-`public` but stays Modulith-internal (other modules still
can't depend on it — it's not in a `@NamedInterface` package). The entities,
repository, `Role` enum, and the cross-field validator stay package-private. Only the
service is promoted to `public`, and only because a same-module, different-package
caller needs it.

### D2: App-assigned UUIDs via factory, not `@GeneratedValue`

`Agreement.create(...)` and signer construction assign `UUID.randomUUID()` in the
factory, set once, final. Rationale: ids exist before persistence (cleaner aggregate
construction, testable without a DB), and id-based `equals`/`hashCode` are stable from
birth (no identity change on flush). Alternative — DB identity / `@GeneratedValue` —
rejected: forces a flush to learn identity and complicates equality. Matches the
discarded version's proven pattern.

**`isNew()` trap (review #4).** With a pre-populated id and no `@Version`, Spring Data
can't distinguish a *new* entity from a *detached* one, so `save()` issues a phantom
`SELECT` before each `INSERT` (and a wrong "row not found" path). `Agreement` therefore
implements `Persistable<UUID>` with a `@Transient boolean isNew = true` flag, flipped to
`false` in a `@PostPersist`/`@PostLoad` callback. `repository.save(agreement)` then
goes straight to `INSERT`. (Signers persist via cascade from the aggregate, so they
don't hit `save()` directly and don't need their own `Persistable`.)

### D3: `Signer` is a child `@Entity`, not `@ElementCollection` (user-confirmed)

Own table `signer`, own UUID. Rationale: CR-3 attaches per-signer provider-session-id +
signer-state to this row; an `@ElementCollection` of value objects has no identity and
would need promotion to an entity mid-flow (rework + a migration rewriting a
just-created table). Pay the small cost now.

**Mapping pinned (review #3):** **bidirectional** — `Signer` has
`@ManyToOne(optional=false) Agreement agreement` (the FK owner, matching
`agreement_id NOT NULL` in DDL), `Agreement` has
`@OneToMany(mappedBy="agreement", cascade=ALL, orphanRemoval=true) List<Signer>`. The
factory wires **both sides** (`signer.setAgreement(this)`) so the non-null FK is set on
the initial insert — this is the idiomatic aggregate mapping and avoids the
unidirectional-`@JoinColumn` extra-`UPDATE`/null-FK trap. `List`, not `Set` (id-based
equality makes either safe, but `List` is the conventional ordered aggregate collection
and keeps mapping simple). **No signer ordering semantics** are promised: signing is
*parallel* (CR-3), so order is non-significant; no `@OrderColumn`/`position` is added
(avoids schema surface we don't need).

**Lazy-init boundary (review #2):** `@OneToMany` is `LAZY` and `open-in-view: false`,
so the signer collection must be initialized **inside** the service transaction before
mapping to a DTO. Therefore the service methods are `@Transactional` and map
entity→response *before returning* — no entity (and no lazy proxy) ever crosses the
controller boundary. Without `@Transactional` this would throw
`LazyInitializationException`; it is mandatory, not optional.

**Forward-compat for CR-3 (review #18):** `findById` returns a *response DTO* for the
HTTP path. CR-3 will need the *entity* internally (to attach signing sessions); it can
add an entity-returning service method then. We do **not** add an unused entity-load
method now (YAGNI) — noted so CR-3 expects to add it, not refactor this one.

### D4: Role mix + email uniqueness as a class-level bean-validation constraint

Per-field rules (`@NotBlank` name, `@Email`, `@Size(min=1, max=20)` signers, `@Valid`
cascade, money/term bounds — see D8) are standard annotations. The two **cross-field**
rules — ≥1 OWNER && ≥1 TENANT, and no duplicate emails within the agreement — go in one
custom class-level constraint (`@ValidSignerSet`) on `CreateAgreementRequest`.
Rationale: declarative, runs at the controller boundary with the other constraints, and
is **unit-testable with a plain `Validator` — no Spring context**. Alternative — a
service-layer guard throwing on violation — rejected: needs the service (heavier test)
and splits validation across two layers.

**Validator null-safety (review #14):** `isValid` returns `true` on a null/empty signer
list and skips null emails — `@Size`/`@NotNull`/`@Email` own those messages; the
cross-field validator must never NPE on partial input. The unit test covers the
null/empty path explicitly, not just the "all owners" path. Email comparison for the
uniqueness check is **case-insensitive** (`a@x.com` and `A@x.com` are the same invite).

### D5: Two records for the HTTP surface, anti-mass-assignment by construction

`CreateAgreementRequest(propertyAddress, monthlyRent, securityDeposit, termMonths,
List<SignerRequest> signers)` where `SignerRequest(name, email, role)` — **no id, no
createdAt**. The server can't accept what the record can't hold. `AgreementResponse`
(+ nested `SignerResponse`) carries the server-assigned ids and `createdAt` outward.
Mapping entity↔record lives in the service (or a small mapper), keeping the controller
thin.

The "client-supplied id/createdAt is ignored" guarantee rests on the record shape
**plus** Jackson's default `fail-on-unknown-properties=false` (review #16): unknown JSON
keys are silently dropped. We rely on the Boot default and do not enable strict unknown-
property failure (which would turn "ignored" into a 400). `createdAt` serializes as
**ISO-8601** via Boot's default `JavaTimeModule` (`Instant` → e.g.
`2026-06-20T10:15:30Z`); `WRITE_DATES_AS_TIMESTAMPS` stays off (review #12).

### D6: `V2__agreement.sql`, schema matched to the JPA mapping

Forward-only, never edits V1. `agreement(id uuid pk, property_address text, monthly_rent
numeric(12,2), security_deposit numeric(12,2), term_months int, created_at
timestamptz)`. `signer(id uuid pk, agreement_id uuid not null references agreement(id),
name text, email text, role text)` with an index on `agreement_id`. Role persisted as
text via `@Enumerated(EnumType.STRING)` (stable across enum reordering; never ordinal).
`ddl-auto: validate` means a mismatch fails boot — covered by the boot/validate
integration test. Money is `BigDecimal`/`NUMERIC(12,2)` (never float).

Columns rely on Boot's default `CamelCaseToUnderscoresNamingStrategy` (review #13):
`propertyAddress → property_address`, `monthlyRent → monthly_rent`,
`securityDeposit → security_deposit`, `termMonths → term_months`,
`createdAt → created_at`. No custom `@Column(name=...)` overrides — keeping the default
mapping is what lets `ddl-auto: validate` line the entity up with `V2__agreement.sql`.

### D8: Rental-term + money bounds (review #9/#10)

The spec's create-time validation covered signers but not the rental terms. Pin
per-field bounds on `CreateAgreementRequest` so a nonsensical agreement is a clean 400,
not a DB error or a junk row: `propertyAddress` `@NotBlank`; `monthlyRent`
`@NotNull @Positive @Digits(integer=10, fraction=2)`; `securityDeposit`
`@NotNull @PositiveOrZero @Digits(integer=10, fraction=2)` (the `@Digits` guard makes
`1000.999` a 400 instead of a silent `NUMERIC(12,2)` truncation — integer=10 +
fraction=2 fits precision 12); `termMonths` `@Positive` (a 0/negative term is rejected).
Deposit is `PositiveOrZero` (a zero deposit is legitimate); **rent and term are strictly
positive** (a zero-rent agreement is rejected).

### D7: Security — two method+path-exact permits, NOT a wildcard (review #6)

The existing `SecurityConfig` deliberately permits `POST /api/signing/*/request`
method-and-path-exact and warns in-comment against `/**` so future sub-paths are
*denied by default*. A `/api/agreements/**` wildcard would contradict that fail-closed
posture (it would open a future `DELETE /api/agreements/{id}` the moment it's added).
So add **two scoped matchers**, mirroring the existing pattern and comment style:
`requestMatchers(POST, "/api/agreements").permitAll()` and
`requestMatchers(GET, "/api/agreements/*").permitAll()` — both TEMPORARY/sandbox,
tighten when auth lands. The root-package `SecurityConfig` is not a Modulith module
(deliberate, per CR-5), so editing it doesn't add a module.

### D9: PII hygiene under module-wide DEBUG logging (review #7)

`logging.level: in.agreementmitra: DEBUG` is set, and the new entities hold signer
**name + email** (ordinary contact PII). To avoid leaking it: `Agreement` and `Signer`
get an **id-only `toString()`** (never name/email) — explicitly, so no IDE/Lombok-
generated `toString` emits PII at DEBUG. No log statement in the service/controller logs
signer fields or the request body. (Hibernate SQL-param logging stays at its default
off; we don't enable `org.hibernate.orm.jdbc.bind=TRACE`.)

### D10: Accepted limitation — opaque 400 bodies (review #8)

`server.error.include-binding-errors: never` + `include-message: never` mean a
validation 400 returns an **empty body**: the client learns *that* it failed, not
*which* rule. For CR-2 this is **accepted as-is** — the integration tests assert on
status, and the Vue frontend isn't built yet, so there's no consumer needing field-level
detail. A safe field-level error contract (a global `@RestControllerAdvice` →
`ProblemDetail` that surfaces violation paths without echoing sensitive input) is
**deferred to a small follow-up CR** (`validation-error-responses`) rather than bolted
on here, because it is cross-cutting error infra affecting every endpoint, not this
aggregate's slice.

### D11: Accepted limitation — non-idempotent create (review #15)

`POST /api/agreements` has no idempotency key; a double-submit creates two distinct
agreements. Accepted for CR-2 (sandbox, no payment, no side effect beyond a DB row).
Flagged for CR-3: once an agreement drives a real eSign request, a duplicate becomes a
duplicate provider call — CR-3 owns idempotency for the signing-request path.

## Risks / Trade-offs

- **Email uniqueness is per-agreement, app-level, not a DB constraint** → enforced in
  the class-level validator; a DB unique index would need to be composite
  `(agreement_id, email)` and would reject legitimately-shared inboxes across *different*
  agreements only if mis-scoped. We keep it app-level for CR-2 (single-request scope, no
  concurrency window since signers are created with their parent in one insert); revisit
  if a DB guarantee is wanted later.
- **`@OneToMany` fetch/lazy-init** → `@Transactional` service maps to DTO inside the
  transaction; never return entities from the controller. Mitigated by D3.
- **Stock `@Email` is permissive** (review #11) → `a@b` (no TLD) passes Hibernate's
  default `@Email`. Accepted for CR-2 (spec only requires "a valid email"); flagged
  because that address would fail when handed to the eSign provider in CR-3. A stricter
  pattern is a CR-3 concern, not folded here.
- **Schema/mapping drift** → `ddl-auto: validate` + a boot integration test catch any
  mismatch at build time, not runtime in prod.
- **Permit `/api/agreements/**` is a real (if sandbox-bounded) open surface** → explicit
  TEMPORARY comment + roadmap note that auth tightening is owed before any non-sandbox
  use. Consistent with the existing posture, not a new precedent.

## Migration Plan

Forward-only Flyway `V2__agreement.sql` applied on startup after V1. No data migration
(new tables, empty). Rollback in this pre-production, sandbox phase = drop the new
tables and remove V2 (no deployed consumers; `flyway.clean` stays disabled, so a manual
drop, not `flyway clean`). No backfill.

## Open Questions

None — the three design forks (signer model, agreement fields, validation strictness)
were resolved with the user before proposing.
