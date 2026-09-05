# Tasks -- agreement-attributes-and-pinning (M5)

Signing-side persistence + reproducibility backbone. All backend, `signing` module only; `documents`
consumed through its public seams. Keep `ModularityTests` green. No frontend in this change.

## 1. Schema / migration (`backend/src/main/resources/db/migration`)

- [ ] 1.1 Add `V9__agreement_attributes_and_pin.sql` (next free slot; `V8__template_catalog.sql`
  reserves V9 for the pin). **Additive, forward-only:** on `agreement` add
  `attributes JSONB NOT NULL DEFAULT '{}'::jsonb`, `template_content_hash TEXT` (nullable),
  `template_layer_versions JSONB` (nullable). **MUST NOT re-add `template_id`** (owned by `V8`); MUST
  NOT edit `V1`..`V8`. Column types match the JPA mapping so `ddl-auto: validate` passes. No dep change
  -> no lockfile regen.

## 2. Aggregate -- attribute store + pin (`in.agreementmitra.signing.agreement`)

- [ ] 2.1 `Agreement`: add a **server-managed** `attributes` field (JSON map, JPA `@JdbcTypeCode(JSON)`
  or `@Converter`) and pin fields `templateContentHash` + `templateLayerVersions`. All server-set only
  (anti-mass-assignment) -- never on `create`/client bodies. Keep the aggregate **status-less**;
  `toString` stays **id-only** (no attribute/PII/pin echo).
- [ ] 2.2 Aggregate methods: `putAttributes(Map)` (or per-key setter) and `pinTemplate(hash,
  layerVersions)` -- the only ways these are set. Read accessors package-private.
- [ ] 2.3 `templateRef`: reuse the existing `template_id` as the ref pointer (see design D2/D5); if the
  frozen pin needs a textual ref, add `template_ref TEXT` in 1.1 instead -- record which was chosen.

## 3. Schema-driven mapper (`in.agreementmitra.signing.agreement`)

- [ ] 3.1 Evolve `AgreementDocumentMapper` to **walk the pinned `FormSchema` declared fields**: per
  field, read from the **core column** (fixed lookup for property/rent/deposit/dates/parties) OR the
  **attribute store** (by `field.key`), coercing per `field.type`; blank/absent -> `null` (template
  placeholders fire). Emit only plain `Map`/`List` (no entity/PII type crosses to `documents`).
- [ ] 3.2 Keep the **single-source** `buildTemplateData` invariant: the persisted-PDF path and the
  stateless preview path both funnel through the one schema-walk builder, so preview and the signed PDF
  cannot diverge (parity locked by 6.4).
- [ ] 3.3 Attribute (de)serialization helper: JSON map <-> typed values, coercion keyed by the pinned
  `field.type`. No logging of keys or values.

## 4. Pin at generate-as-draft + reproduce-from-pin (`in.agreementmitra.signing.agreement`)

- [ ] 4.1 `AgreementDocumentService`: on generate-as-draft, resolve the effective template once via
  `TemplateCatalog` for the agreement's template, record `{templateHash, layerVersions}` on the
  aggregate (in the same tx that stores the draft) via `pinTemplate(...)`, then compile. Never logs
  bytes/attributes/pin.
- [ ] 4.2 Any later render (audit/re-download/reconciliation) resolves the **pinned** `templateHash`
  via the catalog's by-hash seam (design D5 #2) -- **never** re-resolve "current." Executed agreements
  reproduce byte-stable.
- [ ] 4.3 Re-generate before signing overwrites the pin (mirrors the draft overwrite rule); once a
  signing request exists, generation is already `409`-locked (draft-ingestion) so the pin is frozen.

## 5. Boundary / wiring

- [ ] 5.1 Consume `documents` **only** through public seams (`TemplateCatalog` / `DocumentCompiler` /
  `FormSchemaProvider`); no reach-in. `ModularityTests` green.
- [ ] 5.2 `AgreementResponse` / public API: do **not** expose the pin or raw attributes unless a
  response field is explicitly required (keep the surface unchanged for this CR).

## 6. Tests -- unit (no Spring context, no I/O)

- [ ] 6.1 **Schema-driven mapper over dynamic fields**: a pinned schema declaring a mix of core +
  attribute fields maps each from the right source; an unknown/absent attribute -> `null` (placeholder,
  not bare null); markup in an attribute value is treated as escaped data.
- [ ] 6.2 **Attribute (de)serialization**: JSON map round-trips; type coercion per `field.type`
  (`int`/`money`/`bool`/text); malformed/absent value degrades to `null` without error.
- [ ] 6.3 **Pin recording**: `pinTemplate(hash, layerVersions)` sets the pin server-side only; the pin
  is not client-settable (anti-mass-assignment); `toString` reveals no attribute/PII/pin.
- [ ] 6.4 **Single-source / parity**: the same `(declared fields + attributes)` input mapped by the
  preview path and by the persisted path yields the **same data map** -- guards preview/final
  divergence over dynamic fields.

## 7. Tests -- integration (Testcontainers Postgres; skips cleanly if Docker absent)

- [ ] 7.1 **Persist attributes + pin**: create an agreement, store template-declared attributes + a pin
  via the aggregate, reload -> attributes and `{templateHash, layerVersions}` round-trip; app boots
  under `ddl-auto: validate` against the `V9` schema.
- [ ] 7.2 **Generate draft pins the template**: `POST /api/agreements/{id}/document` records the pin on
  the agreement and stores the draft (existing draft key); no bytes/attributes/PII in logs.
- [ ] 7.3 **Re-render byte-stable from the pin**: rendering again resolves the **pinned** hash (not
  "current") and reproduces the identical document even after a newer template version is published for
  the same `(state,type)`.
- [ ] 7.4 `ModularityTests` stays green (no reach into `documents` internals).

## 8. Verify

- [ ] 8.1 Backend: unit + integration + `spotbugsMain` + `ModularityTests` green (Windows: gradle
  directly, Ryuk disabled, `-Duser.timezone=Asia/Kolkata`; `osvScan` per the gate).
- [ ] 8.2 Confirm no dependency change -> Gradle lockfile unchanged (no regen needed).
- [ ] 8.3 Manual: generate a draft, confirm the pin is recorded; publish a newer template version for
  the same `(state,type)`, re-render the executed agreement, confirm the document is byte-identical to
  the original.

## Coordination (blocking on the CR-2 window -- see design D5)

- `templateRef` shape (== `template_id` UUID vs a textual composite ref).
- A `TemplateCatalog` **resolve-by-pinned-hash** seam (required for 7.3 byte-stable re-render).
- `FormSchemaProvider.schemaFor(pinnedRef/hash)` returning the schema **as of the pin**, not "current."
