> **Prerequisite:** this change reuses the `template-definition` model records, `CanonicalJson`, and
> semantic validator. Apply `template-definition-model` first.

## 1. Patch format & schema resource

- [x] 1.1 Add the patch JSON Schema
  `backend/src/main/resources/documents/template/layer-patch.schema.json`: `meta { kind:
  type|state|state_type|language, dimensions{state,type}, version }` and an `ops` array whose items
  are the supported operations (`addField`, `overrideField`, `removeField`, `addClause`,
  `replaceClause`, `removeClause`, `addSection`, `replaceSection`, `removeSection`,
  `reorderSections`, `reorderEntries`). `overrideField` SHALL NOT permit `type`. (`removeField` was
  added during apply so the spec's "removal orphans a slot" scenario is reachable — a field a
  surviving clause slot still references can now actually be removed and rejected on re-validation.)
- [x] 1.2 Confirm no new dependency is needed (reuse `json-schema-validator`); do **not** regenerate
  the lockfile unless a dep actually changes.

## 2. Patch & layer model (package `in.agreementmitra.documents.template` — same package as the definition model so package-private records/helpers are reusable; all new types package-private)

- [x] 2.1 Add immutable records `LayerPatch` (+ `PatchMeta`) and an `Op` sealed hierarchy /
  record-per-op (`AddField`, `OverrideField`, `RemoveField`, `AddClause`, `ReplaceClause`,
  `RemoveClause`, `AddSection`, `ReplaceSection`, `RemoveSection`, `ReorderSections`,
  `ReorderEntries`), each carrying its target key/id/title and payload. Reuse `template-definition`
  `Field`/`Clause`/`Section` records for payloads — the field/clause/section JSON binding is shared
  with the definition loader via an extracted `TemplateNodeBinder` (reuse, not fork) so a patched
  element and a defined one bind byte-identically and hash consistently.
- [x] 2.2 Add `LayerKind` enum (`BASE, TYPE, STATE, STATE_TYPE, LANGUAGE`) and `LayerRef`
  (`kind`, `dimensions`, `version`, resource path).
- [x] 2.3 Add a `LayerPatchLoader` mirroring `TemplateDefinitionLoader`: safe YAML mapper -> JSON tree
  -> structural (schema) validation -> immutable patch model; reject-or-nothing with a location-only
  `ResolutionException`.

## 3. LayerSource seam

- [x] 3.1 Add the `LayerSource` interface: `layersFor(state, type) -> ordered { base, patches[] }`
  with versions, in precedence order (`base -> type -> state -> state_type`; `language` excluded).
- [x] 3.2 Add a classpath-backed `ClasspathLayerSource` that discovers the reference layer set under
  `documents/template/examples/layers/` and returns the ordered layers for a `(state, type)`.

## 4. Resolver

- [x] 4.1 Add `TemplateResolver.resolve(dimensions) -> EffectiveTemplate`: start from the base
  definition, apply each patch's ops in author order, layers in precedence order, last-wins. Pure and
  data-independent (no clock/IO/random/user-data input).
- [x] 4.2 Implement each op's application over the accumulating `TemplateDefinition`
  (add/replace/remove/reorder clauses & sections; add/override fields), targeting existing elements by
  key/id/title; an op targeting a missing element raises `ResolutionException`.
- [x] 4.3 Re-validate the composed result: run the `template-definition` semantic validator plus
  resolution checks (no orphaned slot/section entry after a removal). Reject-or-nothing.
- [x] 4.4 Add `EffectiveTemplate` (materialized `TemplateDefinition` + `dimensions` + provenance
  `{layerId -> version}` + `contentHash`), computing the hash via the shared `CanonicalJson`.

## 5. showWhen DSL

- [x] 5.1 Add `ShowWhenExpr` AST (comparison, and, or, not, field-ref, literal) and a hand-written
  recursive-descent `ShowWhenParser` for the closed grammar (comparisons, `&& || !`, parentheses,
  field identifiers, number/string/bool literals) -- **no** method/property/index/function/assignment;
  those are syntax errors. No expression library.
- [x] 5.2 Add `ShowWhenValidator`: parse every `showWhen` in the effective template and assert each
  identifier is a declared field key; wire it into resolution's re-validation (data-independent).
- [x] 5.3 Add `ShowWhenEvaluator.evaluate(expr, fieldValues) -> boolean`: pure, reads only the value
  map, defined operand-type rules (ordering comparisons numeric/date; equality across matching types;
  bool coercion). **Not** wired to any render.

## 6. Reference layer set (fixture)

- [x] 6.1 Author under `documents/template/examples/layers/`: a `base` definition, a `type` patch, a
  `state` patch, and a `state+type` patch (dummy system-authored content) that together exercise
  add/replace/remove/reorder and field overrides and at least one `showWhen` (one in the base, and one
  introduced via a `replaceClause` patch). Plus a `language-en.patch.yaml` that must **not** be
  applied, so the reserved-but-ignored language layer is proven by test. Do **not** wire to
  `TemplateAssembler` / `DocumentRenderer`.

## 7. Tests -- unit (many, fast; no Spring context, no I/O)

- [x] 7.1 Patch loading: each supported op loads; an unsupported op and an `overrideField` changing
  `type` are rejected with a location-only error.
- [x] 7.2 Composition semantics: `add`/`replace`/`remove`/`reorder` for clauses and sections;
  `addField`/`overrideField`; author-order within a layer; **last-wins across precedence** (type vs
  state vs state+type); the `language` layer is ignored.
- [x] 7.3 Resolution failure modes: op targeting a missing element fails; a removal orphaning a slot
  or section entry fails; a composed result violating a definition invariant fails -- each
  reject-or-nothing with no partial template and no data value in the message.
- [x] 7.4 Determinism + identity: repeat resolution is byte-identical (same canonical JSON + hash); a
  content-bearing layer edit changes the hash and the layer-version map; a base-only `(state,type)`
  resolves to a template whose hash equals the `template-definition` hash of that base.
- [x] 7.5 showWhen parser: valid conditions parse; method call / property access / index / function /
  assignment are syntax errors; precedence and parentheses parse correctly.
- [x] 7.6 showWhen validator + evaluator: an undeclared-field reference fails validation; the
  evaluator returns correct booleans over a value map for comparison/boolean cases and is
  side-effect-free; operand-type rules enforced.

## 8. Tests -- integration (fewer; real resource I/O + module boundary)

- [x] 8.1 Full-pipeline resolve of the real reference layer set: via `ClasspathLayerSource`, resolve
  `(state, type)` end-to-end (load base + patches from the classpath -> compose -> re-validate ->
  showWhen-validate -> canonical JSON -> SHA-256) and assert it composes cleanly, the effective
  template reflects every op, resolution is stable across two calls, and the content hash is
  deterministic. Guards schema/record/canonicalizer drift across the two capabilities.
- [x] 8.2 Keep `ModularityTests` green: the new resolution types sit in the existing
  `documents.template` package inside the `documents` module, all package-private, with no new
  cross-module dependency and no new public/named-interface surface.

## 9. Wrap-up

- [x] 9.1 `./gradlew spotlessApply` applied; full `./gradlew test` (all unit + integration +
  `ModularityTests`, Testcontainers included with Docker up) green. **`securityScan` not run in this
  environment** — the `osv-scanner` binary is not installed here and the gate is fail-closed by design;
  no dependency changed (no lockfile edit), so there is no new OSV surface to scan. Run
  `./gradlew check` on a machine with `osv-scanner` (`brew install osv-scanner`) before merge to close
  the SpotBugs/OSV + JaCoCo gates.
- [x] 9.2 Note that the effective-template evaluator is delivered but unwired; **document projection**
  (fill slots + evaluate `showWhen` with user data + HTML/PDF), **dynamic form projection**, and the
  **layer registry** remain follow-on CRs per the `document-templating-platform` exploration.
