> **Module M0** of the `agreement-document-format` umbrella. Pure `documents` -- schema + records +
> loader + validator + canonicalizer/resolver carry-through. **No database migration, no rendering,
> no HTTP.** Freeze the section-4 contracts (`Meta.document`, `Section { optional, render }`) here.
> Windows notes (memory): run gradle directly with `TESTCONTAINERS_RYUK_DISABLED=true` and
> `-Duser.timezone=Asia/Kolkata`; write every file in pure ASCII (the PII/secret guard fails closed on
> non-ASCII). Per the CLAUDE.md OpenSpec tasks rule, every behavioral change below lists **both** a
> unit and an integration test task.


> **RECONCILED 2026-09-05.** This file read `0/28` while its code had shipped months earlier -- stale
> bookkeeping parked by the umbrella's "ARCHIVE HELD" entry (see
> `archive/2026-09-05-superseded-agreement-document-format/SUPERSEDED.md`), not unstarted work. Every
> box below was verified against the tree rather than taken on trust; the three deviations from the
> task text as written are recorded inline at 1.2/1.3, 4.1, 6.1 and 8.3. Task 8.3 was a **real gap**
> and is now closed with two new tests. Backend suite green.


## 1. JSON Schemas (structural shape)

- [x] 1.1 `template-definition.schema.json`: under `meta.properties`, add an **optional** `document`
  object (`additionalProperties: false`, `required: ["title"]`, properties `title` string minLength 1,
  `subtitle` string, `executionLine` string). Leave `meta.required` unchanged (document not required).
- [x] 1.2 `template-definition.schema.json`: under `sections.items.properties`, add `optional`
  (boolean) and `render` (string enum `parties | keyvalue | clauses | annexure`). Keep the section
  item `additionalProperties: false`.
  - Verified in tree. **Deviation:** the `render` enum ships **five** kinds, not four -- `signatures`
    was added by the post-integration requester change recorded in the umbrella's `SUPERSEDED.md`
    (the witness / execution block became opt-in optional). The task text predates that decision.
- [x] 1.3 `layer-patch.schema.json`: under `$defs/section.properties`, add the **same** `optional` +
  `render` so `addSection` / `replaceSection` payloads carry them. (No `meta.document` in the patch
  schema -- there is no meta-editing op and a base layer uses the definition schema.)

## 2. Model records + enum (package `in.agreementmitra.documents.template`, package-private)

  - Verified in tree, and it does carry the same five-kind enum. Confirmed `$defs` holds no
    `meta.document`, exactly as the task specifies (no meta-editing op exists).
- [x] 2.1 Add a package-private `DocumentMeta` record `{ String title, String subtitle, String
  executionLine }` (subtitle/executionLine nullable).
- [x] 2.2 Add a `document` component (nullable `DocumentMeta`) to the `Meta` record.
- [x] 2.3 Add a package-private `RenderKind` enum `{ PARTIES, KEYVALUE, CLAUSES, ANNEXURE }` mirroring
  `FieldType`: a case-insensitive `from(token)` that throws `IllegalArgumentException` on an unknown
  token.
- [x] 2.4 Add `boolean optional` and `RenderKind render` components to the `Section` record (keep the
  defensive `entries = List.copyOf(entries)` copy).

## 3. Binder + loader (parse + default)

- [x] 3.1 `TemplateNodeBinder`: bind `meta.document` when present into `DocumentMeta` (absent
  subtitle/executionLine -> `null`); reuse `parseSlots(...)` semantics for `executionLine` at
  validation time (see 4.2).
- [x] 3.2 `TemplateNodeBinder.bindSection`: bind `optional` (default `false` when absent) and `render`
  (default `KEYVALUE` when absent) via `RenderKind.from(...)`; keep entry binding unchanged.
- [x] 3.3 `TemplateDefinitionLoader.bindMeta`: wire the bound `DocumentMeta` into the `Meta` it
  constructs; no other loader change (the pipeline order -- structural, bind, semantic, canonical --
  is unchanged).

## 4. Semantic validation (reject-or-nothing, no data values in messages)

- [x] 4.1 `TemplateDefinitionValidator`: reject an unknown `render` kind defensively (the schema enum
  is the first guard; the binder's `RenderKind.from` is the second) -- error names the offending
  section title / token, never a data value.
  - Satisfied, but **located differently**: the defensive rejection lives in `TemplateNodeBinder`
    (it catches `RenderKind.from`'s `IllegalArgumentException` and rethrows naming the section
    title, no data value), not in the validator. The task text itself names the binder as the second
    guard, so this is placement, not a missing check.
- [x] 4.2 `TemplateDefinitionValidator`: when `meta.document` is present and `executionLine` is
  non-null, extract its `{{slot}}`s (same pattern as clauses) and require each to be a declared field
  `key`; otherwise raise `TemplateDefinitionException` naming only the unresolved slot. Leave all
  existing invariants (unique keys/ids, clause slot resolution, section-entry resolution, enum/options,
  default type-consistency) unchanged.

## 5. Canonicalization + resolver carry-through

- [x] 5.1 Confirm `CanonicalJson` needs **no change**: the new `Meta.document` and
  `Section.optional` / `Section.render` components serialize automatically under `Include.ALWAYS` +
  alphabetical sorting (a null document / `false` / `KEYVALUE` are emitted), so the content hash
  changes deterministically when they are added and stays stable across default-present-vs-omitted
  inputs. Add a code comment if any nuance needs recording; do not alter the canonicalizer's rules.
- [x] 5.2 Confirm `TemplateResolver` needs **no structural change**: it already re-canonicalizes and
  re-validates the composed template via the shared `CanonicalJson` + `TemplateDefinitionValidator`,
  carries `base.meta()` (document) through, and applies `addSection` / `replaceSection` payloads
  (optional + render). Verify by test (8.3), not by editing the resolver.

## 6. Fixtures

- [x] 6.1 Add/extend a **test** definition fixture that declares a `meta.document` block (with an
  `executionLine` naming a declared field), at least one `optional: true` section, and one section of
  each `render` kind, for the loader/validator/canonical tests. (Authoring the fields into the real
  `sets/rental/` content is **M5**, not this CR.)

## 7. Tests -- unit (many, fast; no Spring context, no I/O)

  - Satisfied **inline** rather than as a resource file: the fixtures are Java text blocks in
    `DocumentMetadataAndSectionSemanticsTest`. Equivalent for the purpose; noted so nobody hunts for
    a fixture file that does not exist.
- [x] 7.1 **Loader parses the additions.** A definition with a `meta.document`
  `{ title, subtitle, executionLine }`, an `optional: true` section, and each `render` kind loads into
  the model with those values intact.
- [x] 7.2 **Defaults.** A section omitting `optional` loads as `optional == false`; a section omitting
  `render` loads as `render == KEYVALUE`. A `meta` omitting `document` loads with `document == null`.
- [x] 7.3 **Document block requires title.** A `document` block missing `title` is rejected by
  structural (schema) validation, reject-or-nothing (no model returned).
- [x] 7.4 **Unknown render kind rejected.** A section declaring a `render` outside the four kinds is
  rejected (schema enum; and `RenderKind.from` defensively), naming the section, no data value.
- [x] 7.5 **`executionLine` slot resolves / is rejected.** An `executionLine` whose `{{slot}}` names a
  declared field loads; one naming an undeclared field is rejected naming only the unresolved slot; a
  slot-less or blank `executionLine` is accepted.
- [x] 7.6 **Content hash carry-through (unit).** Two definitions differing only by adding a
  `meta.document` block (or flipping a section `optional`, or changing a `render` kind) produce
  **different** content hashes; two definitions equivalent up to an explicit-vs-omitted defaulted
  `optional`/`render` produce the **same** hash. (`CanonicalJson` + `TemplateDefinition.identity()`.)
- [x] 7.7 **Existing invariants unchanged.** Re-assert (or keep green) the existing validator unit
  tests: unique keys/ids, clause slot resolution, section-entry resolution, enum/options, default
  type-consistency still behave identically with the new fields present.

## 8. Tests -- integration (fewer; real load/resolve wiring, module boundary)

- [x] 8.1 **Reference/production sets still resolve + hash.** With the schema + records updated, the
  existing reference definition and the `sets/rental/` layer set(s) still load, validate, resolve, and
  produce a content hash (the hash **moves** from its pre-M0 literal; update any frozen-literal
  assertion in this CR). This is the "existing definitions still round-trip" done-criterion.
- [x] 8.2 **Effective-template carries the fields.** Resolving a layer set whose base declares a
  `meta.document` and whose sections declare `optional` + `render` yields an `EffectiveTemplate`
  exposing `meta.document`, and each composed `Section`'s `optional` + `render`; an `addSection` /
  `replaceSection` patch payload carrying `optional` + `render` is reflected in the composed template.
- [x] 8.3 **Resolver hash includes the new fields deterministically.** Two layer sets differing only
  by a section's `render` kind (or `optional`, or a `meta.document`) resolve to **different**
  effective-template content hashes; the same layer set resolves to a stable hash across runs.
  - **This was the one genuine gap.** The resolver's existing determinism tests
    (`repeatResolutionIsByteIdentical`, `aContentBearingLayerEditChangesTheHashAndVersionMap`) vary
    clause TEXT, so nothing asserted that the M0 fields reach the effective hash. Closed 2026-09-05 by
    two new tests in `TemplateResolverTest`: `aSectionRenderKindOrOptionalFlagChangesTheEffectiveHash`
    (two layer sets differing only in `render`, and only in `optional`, hash differently; same set
    hashes stably across runs) and `aDocumentHeaderOnTheBaseChangesTheEffectiveHash` (`meta.document`
    has no patch op, so it varies on the base). `TemplateResolverTest` 17/17 green.
- [x] 8.4 **`ModularityTests` stays green.** No record visibility widens, no new public surface, no
  cross-module reach-in -- the additions stay package-private in `documents.template`.

## 9. Wrap-up

- [x] 9.1 `./gradlew spotlessApply` green; run the documents suite (`--tests
  "in.agreementmitra.documents.*"`) plus `ModularityTests` (`TESTCONTAINERS_RYUK_DISABLED=true`,
  `-Duser.timezone=Asia/Kolkata`). Confirm **no new dependency and no `gradle.lockfile` change**.
- [x] 9.2 Freeze/record the section-4 contracts consumed by M1/M2/M3/M5 (`Meta.document`,
  `Section { optional, render }`); mark this CR `applied` in the umbrella flow-journal section 5
  tracking table, then `archived` on `/opsx:archive`.
  - The `applied` half IS done: the umbrella's section 5 tracking table records this CR applied (see
    `SUPERSEDED.md`), and the section-4 contracts (`Meta.document`, `Section { optional, render }`)
    are frozen and consumed by M1/M2/M3/M5 in tree. **Left unticked deliberately** because the second
    clause -- mark it `archived` on `/opsx:archive` -- is the pending action this box gates. It ticks
    when the archive runs, not before.
