## Context

`template-definition-model` fixed the declarative shape of a single template -- `meta`, `fields`,
`clauses`, `sections` -- with typed `Field`s, plain-text-slot `Clause`s, ordered `Section`s, a
two-stage (JSON-Schema then semantic) reject-or-nothing loader, and a deterministic `CanonicalJson`
+ SHA-256 identity. `template-resolution-engine` composes layers into an `EffectiveTemplate` reusing
that **same** canonicalizer and semantic validator. Everything lives package-private in the one
package `in.agreementmitra.documents.template`.

The `agreement-document-format` umbrella (flow-journal) wants the document **header**,
mandatory/optional, and section **layout** declared in the template rather than hardcoded (locked
decisions 2 and 3; requester item #7). This CR is **module M0**: it adds the format expressiveness --
schema + records + loader + validator + canonicalizer/resolver carry-through -- and freezes the
downstream contract shapes. It renders nothing (that is M1), projects nothing (M3), and adds no
database.

Constraints unchanged: Java 21 records; package-private by default; the markup/data boundary
(template text carries slots, never markup or code; filling/escaping is compile-time); Flyway is the
single schema source but **this CR adds no migration**; keep `ModularityTests` green; sandbox +
dummy data only.

## Goals / Non-Goals

**Goals**

- Add an optional `meta.document { title, subtitle, executionLine }` block to the format; when
  present, `title` is required.
- Add a per-section `optional` boolean (default `false`) and a per-section `render` kind enum
  (`parties | keyvalue | clauses | annexure`, default `keyvalue`).
- Update **both** JSON Schemas: the definition schema (document block + section fields) and the
  layer-patch schema (`$defs/section` so `addSection` / `replaceSection` carry the new fields).
- Bind, default, and semantically validate the additions: reject an unknown `render` kind; validate
  every `executionLine` `{{slot}}` names a declared field; default `optional` to `false` and `render`
  to `keyvalue`.
- Carry the new fields through `CanonicalJson` + `TemplateResolver` so the content hash changes
  deterministically when they are added and stays stable across equivalent (default-present vs
  omitted) inputs.
- Freeze the `Meta.document` and `Section { optional, render }` contract shapes for M1/M2/M3/M5.
- Keep every existing invariant (unique keys/ids, slot-to-field resolution, section-entry
  resolution, enum/options, default type-consistency, immutability) unchanged.

**Non-Goals (owned by later umbrella modules)**

- **Rendering / layout** -- the compiler that draws the header from `meta.document` and dispatches on
  `render` kind is **M1** (`document-artifact-layout`). This CR adds no HTML and no escaping.
- **HTML-escaping / filling `executionLine`** -- happens at **compile time (M1)**. Here the slots are
  recorded and validated only.
- **Opt-in optional rendering** -- `activeSections` and "render iff mandatory or added" is **M2**.
- **Surfacing `optional` / `renderKind` in the form schema** -- **M3**.
- **Capture UX / forced validation** -- **M4** (frontend).
- **Authoring the fields into the rental content** (`sets/rental/`, Owner/Tenant split, Telangana
  optional catalog) -- **M5**.
- **Any database change** -- none. The definition stays a pure in-memory model.

## Decisions

### D1: `meta.document` is an optional block; `title` required when present

`Meta` gains a nullable `DocumentMeta document` component; a new record
`DocumentMeta(String title, String subtitle, String executionLine)` holds the three strings.
Rationale: the header is template-owned (locked decision 3) and National vs Telangana differ, so it
belongs on `meta` next to `id` / `dimensions` / `version` / `status`. It is **optional** so
definitions that predate it (or never want a header) stay valid; but when the block is present a
`title` is mandatory (a header with no title is meaningless), enforced structurally by the schema
(`document.required: ["title"]`). `subtitle` and `executionLine` are optional strings; an absent one
binds to `null`. **Alternative rejected:** a top-level `document:` sibling of `meta`/`fields` -- the
header is identity/selection-adjacent metadata, so it sits on `meta`, and keeping it there means the
existing "base layer is a full definition" rule already carries it through resolution with no
meta-editing op needed.

### D2: `executionLine` is plain text with `{{slot}}` fills -- recorded and validated, never filled here

`executionLine` mirrors a clause's `text` exactly: **system-authored plain text** that MAY contain
`{{slot}}` placeholders (e.g. `Executed on {{agreementDate}} at {{placeOfExecution}}.`). It is
**not** HTML and **not** an expression. Binding reuses `TemplateNodeBinder.parseSlots(...)` (the same
`\{\{\s*([A-Za-z0-9_]+)\s*}}` pattern, deduplicated, first-appearance order) to extract the slot
names; semantic validation requires every extracted slot to be a declared field `key`. The value is
**never filled, escaped, or evaluated** in this capability -- HTML-escaping the filled line is
compile-time (M1). This keeps the markup/data boundary at the format level (text + slot references
only). **Alternative rejected:** treating `executionLine` as a clause id reference -- it is header
chrome, not a numbered clause, and inlining the string keeps the header self-contained on `meta`.

### D3: `optional` defaults `false`, `render` defaults `keyvalue`; `render` is a closed enum

`Section` gains `boolean optional` and `RenderKind render`. `RenderKind` is a new closed enum
`PARTIES | KEYVALUE | CLAUSES | ANNEXURE` mirroring `FieldType`: a case-insensitive `from(token)`
that throws `IllegalArgumentException` on an unknown token (the binder maps that to a definition
error; the schema `enum` is the first guard, the binder the defensive second). Defaults: an absent
`optional` binds to `false` (sections are mandatory unless declared optional -- the safe default,
since a mistakenly-optional mandatory section would silently drop from the document); an absent
`render` binds to `KEYVALUE` (the current label/value table layout, so existing sections render
unchanged under M1). Closed enum so M1's layout dispatch can `switch` exhaustively. **Alternative
rejected:** a free-form `render` string -- loses the exhaustive switch and lets a typo reach the
compiler as an unknown layout.

### D4: Both schemas updated; only `$defs/section` in the patch schema

`template-definition.schema.json`:
- `meta.properties.document` -- a new **optional** object: `additionalProperties: false`,
  `required: ["title"]`, `properties { title: string minLength 1, subtitle: string, executionLine:
  string }`. `meta.required` is unchanged (document not required).
- `sections.items.properties` gains `optional: { type: boolean }` and
  `render: { type: string, enum: ["parties", "keyvalue", "clauses", "annexure"] }`.
  `additionalProperties: false` on a section item still holds (the two new keys are now declared).

`layer-patch.schema.json`:
- `$defs/section.properties` gains the **same** `optional` + `render` -- this is the shape
  `addSection` and `replaceSection` payloads bind, so a patch that adds or replaces a section can set
  them. No other patch change: there is **no** op that edits `meta`, and a base layer is a full
  definition validated by `template-definition.schema.json`, so `meta.document` needs no patch-schema
  entry.

**Alternative rejected:** a new `setDocument` patch op -- out of scope; National vs Telangana headers
are expressed by each face's base definition (M5), not by patching meta.

### D5: Canonicalization/hash carry-through is automatic; the hash moves deterministically

`CanonicalJson.canonicalize(meta, fields, clauses, sections)` serializes the **loaded records** with
Jackson under `Include.ALWAYS` and alphabetical key sorting. Because `Meta` now has a `document`
component and `Section` has `optional` + `render` components, they are serialized automatically:

- a `Meta` with no document emits `"document": null`; with one, emits the sorted `DocumentMeta`
  object (`executionLine`, `subtitle`, `title` in alpha order, absent sub-fields as `null`);
- every `Section` emits `"optional": false|true` and `"render": "KEYVALUE"` (the enum's `name()`).

Consequences, all intended:
- **Deterministic change on add.** Adding a document block, flipping `optional`, or changing `render`
  changes the canonical JSON and therefore the SHA-256 content hash. This satisfies the umbrella's
  "hash changes deterministically with the new fields."
- **Stable across equivalent inputs.** A section with `optional: false` explicitly and one that omits
  `optional` bind to the **same** model, so they canonicalize and hash identically -- the existing
  "equivalent definitions hash identically" property is preserved (presence/absence of a defaulted
  field cannot move the hash).
- **All existing definitions' hashes shift once.** Because the fields are always emitted, the
  canonical form of every existing definition gains `"document": null`, `"optional": false`,
  `"render": "KEYVALUE"`, so their hashes change from the pre-M0 value. This is a one-time format-
  version move (see Trade-offs); any frozen-literal-hash assertion in a test is updated in this CR.

`TemplateResolver` needs **no structural change**: it already re-canonicalizes and re-validates the
composed template via the same `CanonicalJson` + `TemplateDefinitionValidator`, and it carries
`base.meta()` (hence `meta.document`) and the composed `Section`s (hence `optional` + `render`, set
by `addSection` / `replaceSection` payloads) straight through. So effective templates inherit the new
fields and the hash carry-through for free.

### D6: Validation additions, reject-or-nothing, no data values in messages

Two additions to `TemplateDefinitionValidator` (both run after structural validation, on the bound
model, first-fault-raises):
- **`executionLine` slot resolution.** If `meta.document` is present and its `executionLine` is
  non-null, every slot returned by `parseSlots(executionLine)` must be in the declared field-key set;
  otherwise raise `TemplateDefinitionException` naming the unresolved slot (never the line's text).
  This mirrors the existing clause slot-resolution rule.
- **`render` kind.** The schema `enum` rejects an unknown token structurally; the binder's
  `RenderKind.from(...)` is the defensive second guard (unreachable post-schema, raises a neutral
  error if reached). No separate semantic rule is needed beyond "the token is a known kind."

Everything else in the validator is untouched: unique field keys, unique clause ids, clause
slot-to-field resolution, section-entry resolution, enum/options consistency, default
type-consistency. Error messages still cite only structural locations.

## Exact schema deltas (for the implementer -- artifact, not code)

`template-definition.schema.json`:
- Under `properties.meta.properties`, add:
  `"document": { "type": "object", "additionalProperties": false, "required": ["title"],
  "properties": { "title": { "type": "string", "minLength": 1 }, "subtitle": { "type": "string" },
  "executionLine": { "type": "string" } } }`.
  Leave `meta.required` as `["id", "dimensions", "version", "status"]` (document optional).
- Under `properties.sections.items.properties`, add:
  `"optional": { "type": "boolean" }` and
  `"render": { "type": "string", "enum": ["parties", "keyvalue", "clauses", "annexure"] }`.

`layer-patch.schema.json`:
- Under `$defs.section.properties`, add the **same** `"optional"` and `"render"` properties as above.

## Frozen contracts (downstream CRs depend on these -- treat later changes as breaking)

- **`Meta.document`** -- `DocumentMeta { title: string (required when the block is present),
  subtitle: string?, executionLine: string? }`. `executionLine` is system-authored plain text with
  `{{slot}}` fills over declared fields; HTML-escaping/filling is compile-time (M1). (Consumed by M1.)
- **`Section`** -- `{ title, entries[], optional: bool = false, render: parties | keyvalue | clauses
  | annexure = keyvalue }`. (Consumed by M1 layout, M2 opt-in, M3 form schema, M5 content.)
- **Content hash** includes `meta.document`, `Section.optional`, and `Section.render`, computed by
  the **unchanged** `CanonicalJson`; the resolver reuses it, so effective-template hashes carry the
  fields too.

## Trade-offs / Risks

- **One-time hash shift for every existing definition.** Always-emitting the new fields moves the
  content hash of all current definitions. Accepted: it is the intended behaviour (the hash must move
  with the format), and pre-production there are no pinned consumers -- only test fixtures assert
  literal hashes, and this CR updates them. Mitigation: no CR consumes a cross-CR frozen literal hash;
  downstream identity is recomputed from the (template, data) inputs.
- **Optional defaulting to mandatory.** `optional` defaults `false`, so a section a template author
  forgets to mark stays mandatory -- the safe direction (a mandatory section that should have been
  optional is visible and fixable; the reverse silently drops content). M5 sets the flags explicitly.
- **`render` default `keyvalue` reaches M1 before M5 authors kinds.** Until M5 tags each section, all
  sections default to `keyvalue`, so M1's layout for an un-tagged section is the current label/value
  table -- a benign default, not a break.
- **Enum name in the canonical JSON.** `render` serializes as the enum `name()` (`"KEYVALUE"`), not
  the lowercase token. This is internal to the hash and deterministic; it is not exposed over any API
  in this CR. If a later CR surfaces `renderKind` as a lowercase string (M3), that is a separate
  projection concern and does not change the hash input.
- **`executionLine` slot rule is validation-only.** A blank or slot-less `executionLine` is valid; a
  slot naming an undeclared field is rejected. The rule does not require any particular slot (e.g.
  `agreementDate`) to be present -- reserved date-binding keys are M1's concern.

## Non-Goals recap

No rendering, no HTML escaping, no `activeSections`, no form-schema change, no capture UX, no rental
content authoring, and no database migration. Those are M1-M5. This CR is the format + pipeline
carry-through and the frozen contract only.
