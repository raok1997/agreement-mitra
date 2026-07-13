## 1. Dependency & schema resource

- [x] 1.1 Add `com.networknt:json-schema-validator` to `backend/build.gradle.kts`
  (implementation dependency), then regenerate the lockfile:
  `./gradlew dependencies --write-locks`. Confirm `./gradlew securityScan` (OSV) passes on the new
  graph; if a transitive raises a finding, resolve it (bump) rather than suppress, or record a
  justified + time-boxed `[[IgnoredVulns]]` entry in `backend/config/osv-scanner.toml`.
- [x] 1.2 Add the JSON Schema resource
  `backend/src/main/resources/documents/template/template-definition.schema.json` describing the
  structural shape: required top-level keys (`meta`, `fields`, `sections`; `clauses` optional-empty),
  `meta` (`id`, `dimensions{state,type}`, `version`, `status` enum
  `draft|legal_approved|published|deprecated`), the field-type enum
  (`text|longtext|int|money|date|bool|enum`), and the clause (ref | inline) and section shapes.

## 2. Domain model (package `in.agreementmitra.documents.template`, all package-private)

- [x] 2.1 Add immutable records `TemplateDefinition`, `Meta`, `Dimensions`, `Field`,
  `FieldValidation`, `Clause` (ref | inline shape), and `Section`; use records with defensive copies
  of list fields so the model is unmodifiable. Slots on inline clauses are recorded as parsed field
  references, not filled.
- [x] 2.2 Add the closed enum `FieldType` (`TEXT, LONGTEXT, INT, MONEY, DATE, BOOL, ENUM`) and a
  `status` enum (`DRAFT, LEGAL_APPROVED, PUBLISHED, DEPRECATED`) with case-insensitive parsing from
  the definition tokens.
- [x] 2.3 Add `TemplateDefinitionException` (raised on any structural or semantic failure) carrying a
  structural-location message (JSON pointer / field key / clause id / section) and **no data value**.

## 3. Loader, canonicalizer, validator

- [x] 3.1 Add `TemplateDefinitionLoader`: read YAML via a **safe, type-restricted** Jackson YAML
  mapper (no polymorphic/arbitrary-type instantiation), convert to a JSON tree, run structural then
  semantic validation, and bind to the immutable model. Reject-or-nothing: on any failure raise
  `TemplateDefinitionException` and return no model.
- [x] 3.2 Add structural validation: validate the JSON tree against
  `template-definition.schema.json` using `json-schema-validator`; map each violation to a
  `TemplateDefinitionException` citing its JSON pointer.
- [x] 3.3 Add `TemplateDefinitionValidator` (semantic): unique field `key`s and clause `id`s; every
  `{{slot}}` in every inline clause resolves to a declared field; every section entry resolves to a
  declared field or clause; `enum` iff `options` non-empty; a present `default` is type-consistent
  (and enum-member). `showWhen` is **not** parsed or checked. `ref` clauses are **not** resolved.
- [x] 3.4 Add `CanonicalJson`: serialize the loaded definition to deterministic canonical JSON
  (stable key order, normalized scalars) and compute a SHA-256 content hash; expose the definition
  identity `(id, version, contentHash)`. Document the canonicalization rules in a class comment (the
  resolver CR must reuse the same canonicalizer).

## 4. Reference definition fixture

- [x] 4.1 Author `backend/src/main/resources/documents/template/examples/residential-rental.yaml`:
  the current residential rental agreement expressed as a definition (fields for the parties,
  property, term, rent/deposit; the standard clauses as inline plain-text-with-slots; sections
  ordering them). System-authored dummy content only. **Do not** wire it to `TemplateAssembler` /
  `DocumentRenderer`.

## 5. Tests -- unit (many, fast; no Spring context, no I/O)

- [x] 5.1 `FieldType` / status parsing: every token maps; an unknown token is rejected.
- [x] 5.2 Field semantics: valid typed field with validation metadata loads; unknown type rejected;
  `enum` without `options` (and non-`enum` with `options`) rejected; duplicate `key`s rejected;
  `default` inconsistent with type (and enum non-member) rejected.
- [x] 5.3 Clause semantics: inline clause with a valid slot loads with a recorded slot reference; a
  slot referencing an undeclared field is rejected naming the clause id + slot; a `ref` clause loads
  verbatim and is not resolved; `showWhen` is carried verbatim and never evaluated or identifier-
  checked.
- [x] 5.4 Section semantics: entry order preserved; every entry resolves; an unresolved entry is
  rejected naming the section + entry.
- [x] 5.5 Reject-or-nothing + error hygiene: a structural fault and a semantic fault each raise
  `TemplateDefinitionException` with **no** partial model; error messages contain only structural
  locations (pointer/key/id/section) and no data value.
- [x] 5.6 Canonicalization + hash: two equivalent-but-differently-formatted definitions produce equal
  canonical JSON and equal SHA-256 hash; a content change (e.g. flip `required`) changes the hash;
  the returned model is immutable (list mutation attempts fail / have no effect).

## 6. Tests -- integration (fewer; real resource I/O + module boundary)

- [x] 6.1 Full-pipeline load of the real reference resource: load
  `documents/template/examples/residential-rental.yaml` from the classpath through the entire chain
  (YAML parse -> structural JSON-Schema validation -> semantic validation -> canonical JSON -> SHA-256
  hash) and assert it loads cleanly, its canonical form is stable across two loads, and its content
  hash is deterministic. This exercises the checked-in schema and the real definition together (guards
  schema/record drift).
- [x] 6.2 Keep `ModularityTests` green: add/adjust the module test so the new
  `in.agreementmitra.documents.template` sub-package sits cleanly inside the `documents` module with
  no new cross-module dependency and no new public/named-interface surface.

## 7. Wrap-up

- [x] 7.1 `./gradlew spotlessApply` then `./gradlew check` (includes `securityScan`, `ModularityTests`,
  and the JaCoCo gate) all green. Verified green locally: `spotlessCheck`, `spotbugsMain` (SAST), the
  full `test` task (27 new template tests + the whole existing suite, `ModularityTests` included), and
  `jacocoTestReport`/coverage gate. The full `test` task must be run single-worker
  (`--max-workers=1`) on this host -- the default parallel run exhausts native memory across the many
  Spring/Testcontainers contexts (an environment limit, not a code issue). **OSV gate not run
  locally:** `osv-scanner` is not installed here, so `osvScan` fails closed by design; the lockfile
  was regenerated and the added deps (`json-schema-validator` 1.5.6 + transitive `ethlo:itu`,
  Boot-managed `jackson-dataformat-yaml`) must clear OSV on a host that has the binary before merge.
- [x] 7.2 The reference definition (`examples/residential-rental.yaml`) is a **fixture only** -- it is
  loaded end-to-end by the tests but is deliberately **not** wired to `TemplateAssembler` /
  `DocumentRenderer`; the existing Thymeleaf render path is untouched. Wiring the renderer to
  definitions (render parity), the layered composition resolver, `showWhen` DSL, dynamic form
  projection, and a persistence registry all remain named follow-on CRs per the
  `document-templating-platform` exploration.
