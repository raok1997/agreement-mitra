> **Umbrella:** this is module **M0** of the `agreement-document-format` decomposition
> (`openspec/changes/agreement-document-format/flow-journal.md`). It carries requester item **#7**
> ("configure in templates, not code") and lays the schema/semantics foundation the other modules
> (M1 layout compiler, M2 opt-in optional, M3 form projection, M5 rental content) build on. Freeze the
> section 4 contracts here before those CRs consume them. This CR is **pure `documents`**: schema +
> records + loader + validator + canonicalizer + resolver. **No database migration.**

## Why

The rental-agreement document today hardcodes three things the template should own:

- the **document header** (title / subtitle / the "executed on {{date}} ..." execution line) is
  drawn in code, so National vs Telangana cannot differ without a code change;
- whether a section is **mandatory or optional** is decided in the compiler / frontend, not declared
  by the template;
- how a section is **laid out** (party card vs key/value table vs numbered clause list vs annexure)
  is inferred in code rather than declared.

The `agreement-document-format` umbrella (locked decisions 2 and 3) moves all three **into the
template definition** so one system-owned compiler can render National and Telangana from declared
data, and mandatory/optional becomes a template fact. That is requester item #7. Before any of the
downstream modules (the layout compiler M1, opt-in optional M2, form projection M3, rental content
M5) can consume these facts, the **template-definition format must be able to express them** and the
loader / validator / canonicalizer / resolver must carry them through the content hash. This CR adds
exactly that expressiveness -- three declarative additions -- and nothing else. It renders nothing,
projects nothing, and touches no database.

## What Changes

Three **declarative** additions to the `template-definition` format, wired through the existing
load -> structural-validate -> bind -> semantic-validate -> canonicalize -> hash pipeline and through
the resolver's re-canonicalization, so an effective template's content hash reflects them:

- **`meta.document` block** -- an optional `{ title, subtitle, executionLine }` on `meta`. When
  present, `title` is required; `subtitle` and `executionLine` are optional. `executionLine` is
  **system-authored plain text** that MAY contain `{{slot}}` fills (e.g. `{{agreementDate}}`), exactly
  like a clause's text. Every `{{slot}}` in `executionLine` MUST name a declared field `key`. The slot
  is **recorded, not filled** -- HTML-escaping and value substitution happen at **compile time (M1)**,
  never in this capability (the markup/data boundary holds: the format carries text + slot references
  only).
- **Per-section `optional` flag** -- a boolean, **default `false`** (mandatory). An `optional` section
  is a template fact consumed later (M2/M3/M4); this CR only parses, defaults, canonicalizes, and
  hashes it.
- **Per-section `render` kind** -- an enum `parties | keyvalue | clauses | annexure`, **default
  `keyvalue`**. It declares how M1 lays the section out; an unknown kind is rejected.

Wired consistently across:

- `template-definition.schema.json` -- `meta` gains an optional `document` object (`title` required,
  `subtitle` / `executionLine` optional strings, `additionalProperties: false`); each `sections` item
  gains optional `optional` (boolean) and `render` (string enum of the four kinds).
- `layer-patch.schema.json` -- the shared `$defs/section` (used by `addSection` / `replaceSection`)
  gains the same `optional` + `render` properties, so a patch that adds or replaces a section carries
  them. (`meta.document` lives only on a base layer, which is a full definition validated by
  `template-definition.schema.json`; there is no meta-editing op, so the patch schema needs no
  document block.)
- the `Meta` record (gains a nullable `document` component) and a new small `DocumentMeta` record
  `{ title, subtitle, executionLine }`; the `Section` record (gains `optional` + a new `RenderKind`
  enum component); a new `RenderKind` enum mirroring `FieldType` (closed set, case-insensitive
  `from(token)`).
- `TemplateDefinitionLoader` / `TemplateNodeBinder` -- bind `meta.document`, and bind each section's
  `optional` (default `false`) and `render` (default `keyvalue`).
- `TemplateDefinitionValidator` -- reject an unknown `render` kind (defensive; the schema enum is the
  first guard); validate that every `executionLine` `{{slot}}` resolves to a declared field `key`
  (reusing the same slot-extraction as clauses); the existing invariants (unique keys/ids,
  slot-to-field resolution, section-entry resolution, enum/options, default type-consistency) are
  **unchanged**.
- `CanonicalJson` + `TemplateResolver` -- because the canonical form is serialized from the loaded
  `Meta` / `Section` records with `Include.ALWAYS`, the new fields flow into the canonical JSON
  automatically (a `null` `document`, `optional: false`, and `render: KEYVALUE` are emitted
  explicitly), so the SHA-256 content hash **changes deterministically** when a document block, an
  `optional` flag, or a `render` kind is added -- and two equivalent inputs (default present vs
  omitted) still hash identically. The resolver re-canonicalizes and re-validates the composed
  template through the same code, so effective templates inherit all of the above with no resolver
  rewrite.

**Explicitly not in this change** (each owned by a later module in the umbrella): the artifact
**layout compiler** that consumes `meta.document` + `render` (M1); **opt-in optional** rendering via
`activeSections` (M2); the **form schema** surfacing `optional` + `renderKind` (M3); the **capture
UX** (M4); and the **production rental content** that authors the new fields into `sets/rental/`
(M5). No database migration; no rendering; no HTML escaping (that is compile-time, M1).

## Capabilities

### Modified Capabilities

- `template-definition`: the declarative format gains an optional `meta.document`
  `{ title, subtitle, executionLine }` block, a per-section `optional` flag (default `false`), and a
  per-section `render` kind (`parties | keyvalue | clauses | annexure`, default `keyvalue`). Structural
  (JSON-Schema) and semantic validation cover the additions -- an unknown `render` kind is rejected,
  and every `executionLine` `{{slot}}` must resolve to a declared field. The canonical form and
  SHA-256 content hash include the new fields and change deterministically when they are added; all
  existing invariants are unchanged. The definition stays a pure in-memory model -- not persisted,
  rendered, or exposed over HTTP by this capability.

## Impact

- **`documents` module only.** Touches the package-private definition records and pipeline in
  `in.agreementmitra.documents.template` (`Meta`, `Section`, a new `DocumentMeta` record, a new
  `RenderKind` enum, `TemplateNodeBinder`, `TemplateDefinitionLoader`, `TemplateDefinitionValidator`,
  `CanonicalJson` is reused unchanged, `TemplateResolver` re-uses the same canonicalizer/validator)
  and the two checked-in JSON Schemas under
  `backend/src/main/resources/documents/template/`. **No record visibility widens**; no new public
  surface; `ModularityTests` stays green.
- **No database, no HTTP, no rendering.** No Flyway migration, no entity, no endpoint, no controller.
  The definition model remains pure and in-memory.
- **Content-hash impact (intended + breaking for pinned hashes).** Adding the always-emitted fields
  changes the canonical JSON of **every** definition, so the SHA-256 content hash of existing
  definitions **changes** even when their authored content is otherwise identical (a `null` document,
  `optional: false`, and `render: KEYVALUE` are now part of the canonical form). This is a deliberate
  format-version change. Any test that asserts a **frozen literal hash** must be updated; any recorded
  pin from a prior render is superseded. This is acceptable pre-production (sandbox + dummy data, no
  deployed consumers) and is the point of the change -- the hash must move with the format.
- **Downstream contracts frozen here** (section 4 of the umbrella): `Meta.document`
  `{ title, subtitle, executionLine }`; `Section { title, entries[], optional=false,
  render=keyvalue }`. M1/M2/M3/M5 consume these shapes; treat later shape changes as breaking.
- **Dependencies**: **none added.** Uses the Jackson + json-schema-validator already present. **No
  `gradle.lockfile` change**; nothing new enters the OSV `securityScan` surface.
- **No** change to: field/clause semantics, `showWhen` (still the sandboxed DSL, untouched), the
  resolver's precedence or identity computation, the form/document projection code, the catalog, the
  signing FSM, or any security matcher.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **None.** The three additions are
  **system-owned template structure** -- a document title/subtitle, a system-authored execution-line
  template string with `{{slot}}` references, a boolean, and a layout enum. No signer name, address,
  government identity number, one-time code, virtual id, or secret material is introduced anywhere.
- **Markup/data boundary held.** `executionLine` is **plain text with `{{slot}}` references only** --
  it is **not** HTML and **not** an expression or code, exactly like a clause's `text`. This
  capability **records** the slots (deduplicated, first-appearance order) and **validates** they name
  declared fields; it does **not** fill, escape, or evaluate them. HTML-escaping of the filled line
  happens at **compile time (M1)**, where all data + template text is escaped. No new user-authored
  markup surface and no new expression/DSL surface is introduced (`showWhen` is untouched).
- **No logging of values.** Validation errors continue to name only structural locations (field
  `key`, clause `id`, section title, the offending `render` token, or the unresolved slot name) and
  **never** a data value -- consistent with the existing reject-or-nothing error contract.
- **No new I/O surface.** No database table, no HTTP endpoint, no webhook, no external call. The
  loader still reads only system-owned classpath definition/schema resources; nothing is persisted or
  logged.
- **Sandbox + dummy data only?** Preserved -- only the reference/production definitions (dummy,
  system-authored) exercise the new fields; no live provider, credential, or env var is added.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** -- no signing sequence diagram required.
