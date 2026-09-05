## Context

M5 of the document-templating-platform flow journal. CR-2 (another window) makes templates dynamic
(each declares its own `fields`) and versioned; the resolution/compile/form-schema engine lives behind
the `documents` module's public seams. This change is the **signing-side** binding: where dynamic
field values persist, how the template a signed agreement used is pinned for byte-stable reproduction,
and how the render data map is built from the pinned template rather than fixed getters.

Constraints (unchanged): Java 21 + Spring Boot 3.5.x + Spring Modulith; `signing` consumes `documents`
only through its public interface (`ModularityTests` guards it); Flyway forward-only + JPA
`ddl-auto: validate` + `flyway.clean` disabled; never edit an applied migration; sandbox + dummy data;
never log PII; the markup/data boundary (users supply data, never template markup).

Today: `Agreement` has fixed columns (property/rent/deposit/dates) + parties; `AgreementDocumentMapper`
maps those fixed getters to a data map already single-sourced across the persisted and preview paths
(`buildTemplateData`); `V8__template_catalog.sql` already added a nullable `template_id UUID` to
`agreement` and **instructs the pin migration to take V9 and add only the hash/layer-version columns**.

## Goals / Non-Goals

**Goals:**
- Persist **template-declared** field values with **no per-template migration**.
- Pin `{templateRef, templateHash, layerVersions}` on the agreement at generate-as-draft.
- Re-render (audit/re-download/reconciliation) resolves the **pinned** hash -> **byte-stable**.
- A **schema-driven** mapper (walk pinned declared fields; core column OR attribute store),
  **single-sourced** with the preview mapper (parity).

**Non-Goals:**
- The template engine itself (resolve/compile/form-schema = M0-M4, `documents`). Consumed, not built.
- Dynamic-form capture UI / the `(state,type)` picker (M6, frontend).
- New signing states, stamping, eSign/webhook changes. Clause library / state-language layers.
- Validation of attribute values against the schema beyond type coercion (M4 owns schema validation;
  M6 owns form-level validation) -- here attributes are stored/read, not re-validated.

## Depends on (frozen elsewhere -- inbound contracts, do NOT redefine)

Frozen with M0/M1/M4 in the CR-2 window (flow-journal §4). Design against them; stay tolerant:

- **Pin record** `{templateRef, templateHash, layerVersions}` (M5 seam).
- **`FormSchema`** -- typed `fields[]` with `key`/`type`/... The field **`key`** is the attribute-store
  key; the field **`type`** drives (de)serialization.
- **`documents` seams** -- `TemplateCatalog` (resolve + a **by-hash** lookup), `DocumentCompiler`
  (`compile(ref|hash, dataMap)`), `FormSchemaProvider.schemaFor(ref)`.

## Decisions

### D1: Storage shape -- **JSONB column on `agreement`** (recommended), not a side table

The attribute store is a single **`attributes JSONB`** column on `agreement` (a JSON object of
`{ fieldKey: value }`), not a dedicated `agreement_attribute(agreement_id, key, value, type)` side
table.

| Concern | JSONB column (chosen) | `agreement_attribute` side table (rejected) |
| --- | --- | --- |
| **Migration-free extension** | Yes -- new template's keys need zero DDL | Yes -- new keys are new rows |
| **Reproducibility** | **Strong** -- the whole attribute set is one value, snapshotted atomically with the pin; one row to read back for a byte-stable render | Weaker -- a multi-row set to reassemble; ordering/consistency to manage |
| **Typing** | JSON scalar types; the **precise** type is recovered from the **pinned `FormSchema`** (`field.type`) at read -- no redundant per-row `type` column | Explicit `type` column, but it duplicates what the pinned schema already declares (drift risk) |
| **Queryability** | JSONB operators + a GIN index cover the rare "find agreements where `petAllowed=true`" | Best for ad-hoc relational queries -- **not a need today** |
| **Write/read cost** | One column on the row already being read for render | Extra table + join per render |

Rationale: the dominant access pattern is **read the whole set to render**, and the dominant
non-negotiable is **byte-stable reproducibility**, both of which favour a single atomic value that
snapshots with the pin. Type is **not** stored redundantly -- it is recovered from the pinned schema
(`field.type`), which is itself pinned, so the pair `(attributes, pin)` is self-describing. The side
table's only real advantage (ad-hoc attribute analytics) is not a current requirement; if it becomes
one, a GIN index on the JSONB (or a later projection) covers it without reshaping the store. Core
fields stay first-class columns -- JSONB holds **only** template-declared extras, never parties/rent/
deposit/dates.

Shape: `attributes JSONB NOT NULL DEFAULT '{}'::jsonb`. Mapped in JPA as a String/JSON type
(`@JdbcTypeCode(SqlTypes.JSON)` on a `String`/`Map` field, or a `@Converter`); `ddl-auto: validate`
must pass, so the column type matches the mapping.

### D2: Version pin -- `templateHash` + `layerVersions`; `templateRef` reuses `template_id`

Pin columns added to `agreement`:
- `template_content_hash TEXT` -- the resolved effective-template content hash (`templateHash`).
- `template_layer_versions JSONB` -- the `layerVersions` map (which version of each composed layer).
- `templateRef` -- **reuses the existing `template_id UUID`** (added by `V8`) as the ref pointer.
  **Coordination point (D5):** if the frozen `templateRef` is a composite/textual ref (e.g.
  `residential/KA@v3`) rather than the catalog UUID, add a `template_ref TEXT` column instead of
  overloading `template_id`. **Assumption until frozen:** `templateRef == template_id` (the catalog
  row id), with `layerVersions` carrying the per-layer version detail.

All three are **server-set only** (anti-mass-assignment), populated at generate-as-draft via an
aggregate method (`pinTemplate(hash, layerVersions)`), never client-settable. Null until the first
generate; a re-generate before signing overwrites them (mirrors the draft overwrite rule).

### D3: Pin at generate-as-draft; re-render resolves the pinned hash (byte-stable)

`AgreementDocumentService`'s generate path resolves the effective template **once** (via
`TemplateCatalog` for the agreement's `(state,type)`/`template_id`), records
`{templateHash, layerVersions}` on the aggregate in the same transaction that stores the draft, then
compiles. **Any later render** (audit, re-download, reconciliation) MUST call the catalog's
**by-hash** seam with the pinned `templateHash` -- **never** re-resolve "current." An executed
agreement whose template's `(state,type)` later gets a new published version still reproduces its
original document byte-for-byte. This is the reproducibility non-negotiable made mechanical.

`preview` stays ephemeral and is **never** pinned (consistent with preview-centric D2) -- only the
deliberate generate-as-draft commits and pins.

### D4: Schema-driven mapper -- walk the pinned declared fields; single-sourced with preview

`AgreementDocumentMapper` evolves from **fixed getters** to **schema-walking**: given the pinned
`FormSchema` (its declared `fields[]`), for each field it pulls the value from **either** the core
column (a small fixed lookup for `propertyAddress`/`monthlyRent`/`securityDeposit`/dates/parties)
**or** the **attribute store** (by `field.key`), coercing per `field.type`. The existing
`buildTemplateData` single-source (both the persisted-PDF path and the stateless preview path funnel
through one builder) is **preserved and extended**: the schema-walk is the one builder both paths call,
so live preview and the signed PDF **cannot diverge** -- locked by a **parity test** (the same
`(declared fields + attributes)` input mapped by the preview path and the persisted path yields the
**same data map**). This is exactly preview-centric D3's single-source/parity rule, now over dynamic
fields instead of fixed getters.

Illustrative (design-only, not the implementation):

```
for (Field f : pinnedSchema.fields()) {
  Object v = CORE_FIELDS.contains(f.key())
      ? coreValue(agreement, f.key())          // fixed columns / parties
      : coerce(agreement.attributes().get(f.key()), f.type());  // attribute store
  data.put(f.key(), v);                          // blank -> null so template placeholders fire
}
```

Blank/absent attributes map to `null` (the template's placeholders fire, per preview-centric D3) --
never a bare `null` in the rendered output. The mapper emits only plain nested `Map`/`List` (no entity
or PII type crosses to `documents`), keeping that module domain-agnostic.

### D5: Coordination points against the frozen §4 contracts

Explicit seams where this change assumes-and-flags rather than hard-codes:

1. **`templateRef` shape** -- assume `== template_id` (UUID); if the frozen pin uses a textual composite
   ref, add `template_ref TEXT` (D2). Keep the migration tolerant (a nullable extra column is additive).
2. **Resolve-by-pinned-hash seam** -- byte-stable re-render (D3) **requires** `TemplateCatalog` (or
   `DocumentCompiler`) to accept a pinned `templateHash` and reproduce that exact effective template.
   If M4 exposes only resolve-by-`(state,type,version)`, coordinate to add a by-hash lookup, or store
   enough (`layerVersions` + ref) to re-resolve deterministically. **Assumption:** a by-hash lookup
   exists or `{ref + layerVersions}` deterministically re-resolves.
3. **`FormSchema` availability at render** -- the mapper needs the **pinned** schema, not the current
   one. Assume `FormSchemaProvider.schemaFor(pinnedRef/hash)` returns the schema as of the pin. If only
   "current" schema is retrievable, coordinate to key it by the pinned version.

These are recorded as coordination points, not resolved here -- resolving them belongs to the M4 window
that owns the seams.

## Data / schema model

Migration **`V9__agreement_attributes_and_pin.sql`** (next free slot after `V8__template_catalog.sql`;
`V8` reserves V9 for exactly this) -- **additive, forward-only**, adds to `agreement`:

- `attributes            JSONB NOT NULL DEFAULT '{}'::jsonb`
- `template_content_hash TEXT`   (nullable -- set at first generate)
- `template_layer_versions JSONB` (nullable -- set at first generate)

It **MUST NOT re-add `template_id`** (owned by `V8`) and MUST NOT edit `V1`..`V8`. Column types match
the JPA mapping so `ddl-auto: validate` passes. No dependency change -> **no Gradle lockfile regen**.

## Risks / Trade-offs

- **JSONB typing is loose** -- mitigated: the pinned `FormSchema` carries the authoritative `type`;
  coercion happens on read against the pinned schema, so the pair is self-describing.
- **Pin/schema drift** -- if the pinned schema can't be retrieved as-of-pin (D5 #3), reproducibility
  weakens; flagged as a coordination point, not hidden.
- **Attribute PII in a queryable column** -- values may be party data; controlled by the same no-log
  discipline as existing columns (never logged; `toString` id-only) and the existing DB trust boundary.
- **Mapper rewrite touches a hot path** -- both preview and signed-PDF; mitigated by keeping the one
  single-source builder and the parity test that already guards it.

## Migration Plan

Ship `V9` additive; backfill is trivial (`attributes` defaults to `{}`; pin columns null for
pre-existing/unsigned agreements -- pinned on next generate). The single template today (`(default)`)
resolves to a stable schema, so existing generate paths keep working; the schema-walk over the single
template's declared fields reproduces today's fixed data map. No data backfill of attributes is needed
(no template declared extras before CR-2).

## Open Questions (for review)

- Should `attributes` carry a GIN index now (speculative) or defer until an attribute-query need is
  real? (Lean: defer -- no query need today.)
- Does `layerVersions` need its own read model for audit, or is the JSONB blob enough for
  reproduce-and-display? (Lean: blob is enough for M5; a projection is a later concern.)
