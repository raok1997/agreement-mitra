## Context

Templates are composed at runtime from a layered YAML set: a `base.yaml` (a full standalone
`TemplateDefinition`) plus optional `type-<type>`, `state-<state>`, and `state_type-<state>-<type>`
patches, loaded by filename convention from the layer-set root a catalog row points at, and applied in
fixed precedence (`base -> type -> state -> state_type`, last-wins) by `TemplateResolver`. The resolver
is a pure, data-independent function; the `TemplateCompiler` renders the effective definition to
self-contained, HTML-escaped HTML shared byte-for-byte by the preview and the PDF. The residential
line lives under `documents/template/sets/rental/` and is exposed as two catalog rows (National +
Telangana) seeded by `TemplateCatalogSeeder`.

Two hard facts from the existing engine shape this change:

- **`meta` comes from the base, verbatim.** `TemplateResolver.resolve` builds the composed definition
  as `new TemplateDefinition(base.meta(), fields, clauses, sections, null)`, and the `Op` set has no
  operation that edits `meta`. So the document header (`meta.document.title` / `subtitle` /
  `executionLine`) and dimensions are fixed by the base and **cannot** be changed by any patch.
- **Generate maps a fixed eight field keys.** `AgreementDocumentMapper.toTemplateData` always emits
  `ownerName, tenantName, propertyAddress, monthlyRent, securityDeposit, durationMonths, startDate,
  endDate`; every other declared field is filled from template defaults inside the projection service.
  The base's `required` set must therefore be exactly those eight (the "parity contract") or a
  generated draft fails required-validation.

Constraints: Java 21 + Spring Boot 3.5.x + Spring Modulith; `documents` stays domain-agnostic (no
signing type, no brand literal in code); the resolver/compiler stay unchanged and pure; keep
`ModularityTests` green and preview<->PDF parity intact; sandbox + dummy data only; every rendered
value HTML-escaped; write all resource/text files in pure ASCII (the local PII/secret guard fails
closed on non-ASCII).

## Goals / Non-Goals

**Goals:**
- Add a commercial lease product line resolvable at `(TG, commercial)` and `(IN, commercial)` that
  compiles to a correctly-headed "Commercial Lease Agreement".
- Ship the full commercial clause set (business use, CAM, GST, lock-in, escalation, fit-out, signage,
  repairs split, deposit-as-months) plus the Telangana statutory overlay.
- Reuse the residential clause wording as the starting point where it fits; keep the residential-only
  fields out.
- Preserve generate/preview parity by reusing the eight aggregate-backed required keys.

**Non-Goals (this increment):**
- No new resolver/compiler/schema capability; no `meta`-override operation (a new base is the correct
  tool here, not an engine change).
- No other states beyond Telangana (national base + TG overlay only), no multilingual variant.
- No production Flyway seed of the catalog rows (sandbox repo; the seeder covers local/sandbox).
- No frontend change -- the catalog browse + capture screens already list whatever the catalog
  publishes and drive preview/generate by `(state, type)`.

## Decisions

### D1: A new commercial base, not a patch over the residential base
The composed template's `meta` (hence the header) is the base's `meta` verbatim and no `Op` edits
`meta`. Reusing `sets/rental/base.yaml` would render the commercial document as "Rental Agreement /
Residential Tenancy (Leave and Licence)" -- a mislabelled legal instrument -- and would drag
residential-only fields (BHK, furnishing, pets, occupants) that must then be stripped op-by-op. A new
`sets/commercial/base.yaml` gives a correct header and a clean commercial field/clause set directly;
it **reuses the residential clause wording** (term, rent, deposit, notice, service-of-notice,
governing law, severability, entire-agreement, handover, no-subletting) as its starting point.
Rejected -- (a) reuse the residential base as-is (mislabelled document); (b) add an
`OverrideDocumentMeta` op to the resolver + schema + validator (more engine plumbing than a new base
buys, and widens the patch surface for no other need).

### D2: The base's required set is exactly the eight aggregate-backed keys, relabelled
`AgreementDocumentMapper` supplies only `ownerName, tenantName, propertyAddress, monthlyRent,
securityDeposit, durationMonths, startDate, endDate`. The commercial base reuses those exact keys as
its only `required: true` fields, relabelled for commercial (`ownerName` -> "Lessor name",
`tenantName` -> "Lessee name", `propertyAddress` -> "Demised premises address", `monthlyRent` ->
"Monthly lease rent (INR)", `durationMonths` -> "Lease term (months)", etc.). This keeps
generate-as-draft working with no signing-module change and keeps the signed draft in parity with the
preview. The eSign anchors still derive from the keys (`ownerName -> esign:owner`,
`tenantName -> esign:tenant`); the Lessor/Lessee labels are display-only.
Rejected -- introducing commercial-named required keys (`lessorName`, ...): would need a signing-module
mapper change and break the domain-agnostic key contract.

### D3: Commercial-specific fields are optional-with-default; the type patch pins the use
Every commercial field beyond the eight (CAM, GST rate, lock-in months, escalation %, fit-out months,
deposit-months, signage, area figures, business description, repairs split) is `required: false` with
a system-authored dummy default, so gating any of them out of a generated draft never trips
required-validation -- the same rule the residential base follows. `type-commercial.patch.yaml`
overrides `permittedUse` to `required: true, default: commercial` (mirroring `type-residential`), so a
generated draft always carries the commercial use covenant even when the aggregate does not supply it.

### D4: Telangana overlay is an opt-in optional section; TG-commercial patch defaults + orders
Matching the residential decision, `state-TG.patch.yaml` adds the Telangana statutory content
(governing law under the applicable Telangana commercial-tenancy / Transfer of Property framework,
stamp duty, compulsory registration before the jurisdictional Sub-Registrar under the Registration Act
1908, charges borne-by) as an **optional, opt-in** "Statutory (Telangana)" section -- absent from
preview and a generated draft until the user adds it from the catalog.
`state_type-TG-commercial.patch.yaml` (highest precedence) removes the generic national stamp clause
in favour of the Telangana one, re-authors the covenant list without the dangling entry, defaults
`jurisdictionCity` to Hyderabad, keeps the execution/signature block mandatory (a draft must be
signable via Aadhaar eSign), and sets the full section order.

### D5: Ship both National and Telangana commercial rows
The base is inherently national (`state: IN`), and the residential line already exposes a National +
Telangana pair. Seeding both `(IN, commercial)` and `(TG, commercial)` mirrors that shape at no extra
cost (the national base must exist regardless) and lets the Telangana overlay read as an additive
layer over a national commercial base rather than a one-off.

### D6: Seeder discovers layer sets from the folder (requester directive), not a hardcoded row list
The requester's directive -- "the program should be able to see available templates in the configured
folder" -- means adding a template must not require a per-template code edit. So `TemplateCatalogSeeder`
is reworked from a hardcoded `List.of(create(...), ...)` into a **classpath discovery** loader: it
scans `classpath*:documents/template/sets/*/base.yaml`, dedupes by classpath-relative root, and for
each **published** base derives one catalog row from the base's national `(state, type)` plus one per
`state-<XX>.patch.yaml` overlay beside it. Row name = `document.title (StateDisplayName)`,
description = `document.subtitle`, version = `meta.version` -- all derived from the base, so catalog
metadata cannot drift, and dropping a `sets/<x>/` folder is all it takes to publish a template.
It stays idempotent per `(state, type)` (insert only absent pairs, re-runnable) and dedupes discovered
rows **first-wins per `(state, type)`** so two sets can never mint a duplicate catalog row (which would
make registry resolution ambiguous). In production exactly one set owns each pair; the dedup is a
guard.
Rejected -- (a) hardcoded rows + per-`(state, type)` idempotency (still a per-template code edit,
contrary to the directive); (b) a Flyway data insert (same objection, and version cannot derive from
the base in static SQL); (c) empty-only guard (new rows never appear on seeded dev DBs).

Consequence -- **test fixtures relocated.** The scan matches every `sets/*` on the classpath, and two
test-only fixture sets (`sets/formsection`, `sets/optional`) declared `(IN, residential)` /
`(TG, residential)`, colliding with the real `rental` set on the *test* classpath (the running app,
main-only, never sees them). They are moved to `documents/template/testsets/` (out of the scan path),
with their three `ClasspathLayerSource` references updated, so discovery sees only production sets --
which is also the correct home for test fixtures.

## Component shape

- **`documents/template/sets/commercial/base.yaml`** -- `meta.id: commercial-national`,
  `dimensions: { state: IN, type: commercial }`, `meta.document.title: "Commercial Lease Agreement"`.
  Fields: the eight required aggregate keys (relabelled) + commercial optionals (permittedUse enum
  incl. commercial default, permittedBusinessUse, carpetAreaSqft, superBuiltUpAreaSqft, lockInMonths,
  noticePeriodMonths, rentEscalationPercent, rentDueDay, paymentMode, camBorneBy, camAmount, gstRate,
  gstBorneBy, depositMonths, fitOutMonths, signageAllowed, structuralRepairsBorneBy,
  routineRepairsBorneBy, disputeResolution, jurisdictionCity, specialConditions, fixturesInventory,
  witnesses). Clauses + sections mirror the residential structure (Lessor / Lessee parties, Schedule
  of Premises, Term, Financial, optional Charges & Utilities / Occupancy & Use / Dispute Resolution,
  the "Now This Agreement Witnesseth" covenant list, optional Annexure, mandatory "In Witness
  Whereof" signatures, optional Witnesses).
- **`type-commercial.patch.yaml`** -- `overrideField permittedUse (required: true, default:
  commercial)`.
- **`state-TG.patch.yaml`** -- add TG statutory fields + clauses + an optional "Statutory (Telangana)"
  section.
- **`state_type-TG-commercial.patch.yaml`** -- remove generic stamp clause, replace the covenant
  section without it, override `jurisdictionCity` default to Hyderabad, keep signatures mandatory,
  reorder sections.
- **`TemplateCatalogSeeder`** -- reworked to discover layer sets from `documents/template/sets/*` on
  the classpath (via `PathMatchingResourcePatternResolver`) and derive one row per `(state, type)`
  from each published base + its state overlays; per-`(state, type)` idempotent + first-wins dedup.
  The commercial rows fall out of discovery with no per-template edit.

## Risks / Trade-offs

- **Two bases can drift** -- shared wording is copied, not shared, so a future edit to a common clause
  must be made in both bases. Accepted: the header constraint (D1) forces separate bases; the
  duplication is bounded to legal boilerplate and is visible in review.
- **Seeder idempotency change touches shared code** -- the residential rows now insert via the same
  per-row path; a unit test pins that a re-run adds only missing rows and never duplicates.
- **Legal wording is system-authored dummy content** -- the clauses are sandbox samples, not vetted
  legal drafting; acceptable for the sandbox repo and flagged as such.

## Migration Plan

**No database migration.** The catalog table is unchanged; the commercial rows are seeded
(local/sandbox) like the residential rows, now via a per-row idempotent guard so existing dev DBs pick
them up on restart. No dependency change -> no `gradle.lockfile` change, nothing new on the OSV /
SpotBugs surface. Rollback is deleting the new YAML + reverting the seeder rows.

## Open Questions

- **Production catalog seed.** When the repo grows past sandbox, the commercial rows will need a
  production seed path (Flyway insert or an admin publish flow); out of scope here.
- **Legal review of clause wording.** The commercial clauses are dummy sandbox drafting; a real
  deployment needs counsel review (tracked outside OpenSpec).
