## Why

Today the catalog ships one product line -- the residential rental agreement (Leave and Licence) --
as a national base plus a Telangana statutory overlay, composed at runtime from the layer set under
`documents/template/sets/rental/`. There is no commercial offering: a user who wants to let shop,
office, or warehouse space has no template that speaks in commercial terms (business use, common-area
maintenance, GST on rent, lock-in, fit-out, signage) and the residential document actively mislabels
the arrangement ("Residential Tenancy (Leave and Licence)").

This change adds a **Commercial Lease Agreement** product line, mirroring the residential template
system: a new self-contained layer set with its own correct **"Commercial Lease Agreement"** document
header, the full commercial clause set, and the Telangana statutory overlay -- selectable from the
catalog as `state=TG, type=commercial` (with a national `state=IN, type=commercial` sibling, exactly
as residential ships National + Telangana).

A new layer set (not a patch over the residential base) is required because the composed template
takes its `meta` -- including the document title/subtitle -- **verbatim from the base**
(`TemplateResolver` builds the effective definition with `base.meta()`), and no patch operation edits
`meta`. Reusing the residential base would leave the commercial document titled "Residential Tenancy".
So the commercial base is a new file that **reuses the residential clause wording as its starting
point** where it fits, with a commercial header and commercial-specific fields/clauses; the
residential-only fields (BHK, furnishing, pets, occupants) do not carry over.

No new engine capability, no schema change, no dependency change: this is additive template content
(classpath YAML) plus one catalog seed row, resolved and compiled by the existing pipeline.

## What Changes

- **New commercial layer set** at `backend/src/main/resources/documents/template/sets/commercial/`,
  mirroring the residential set's four-file shape:
  - `base.yaml` -- national commercial lease (`state: IN, type: commercial`), a full standalone
    definition with a correct `meta.document` header (`title: "Commercial Lease Agreement"`), Lessor /
    Lessee party cards, a Schedule of Premises, Term, and Financial key/value set, the commercial
    covenant list, an optional fit-out/inventory annexure, the execution/signature block, and optional
    witnesses.
  - `type-commercial.patch.yaml` -- pins the commercial character (permitted use required + defaulted
    to commercial), mirroring how `type-residential` pins residential, so a generated draft always
    carries the commercial use covenant.
  - `state-TG.patch.yaml` -- Telangana statutory overlay for commercial leases (governing law, stamp
    duty, compulsory registration before the jurisdictional Sub-Registrar under the Registration Act
    1908), added as an opt-in optional "Statutory (Telangana)" section (same opt-in treatment as the
    residential TG overlay).
  - `state_type-TG-commercial.patch.yaml` -- highest-precedence Telangana-commercial specifics: drop
    the generic national stamp clause in favour of the Telangana one, default the jurisdiction city to
    Hyderabad, keep the execution/signature block mandatory, and fix the section order.
- **Full commercial clause set** in the base: permitted business use with an explicit no-residential
  covenant; premises area as carpet **and** super-built-up; common-area maintenance (CAM); GST on
  rent; lock-in period; annual rent escalation; fit-out / rent-free period; security deposit expressed
  as a number of months of rent; signage rights; maintenance/repairs split (structural to the Lessor,
  routine to the Lessee); no-subletting / no-assignment; quiet enjoyment; and handover on
  expiry. Commercial-specific fields are optional-with-default so they never break generate parity.
- **Parity contract preserved.** The commercial base declares **only** the eight aggregate-backed
  field keys as `required` -- `ownerName`, `tenantName`, `propertyAddress`, `monthlyRent`,
  `securityDeposit`, `durationMonths`, `startDate`, `endDate` -- relabelled for commercial (Lessor
  name, Lessee name, Demised premises, Monthly lease rent, etc.). These are the exact keys
  `AgreementDocumentMapper` supplies at generate-as-draft, so a generated commercial draft populates
  and stays in parity with the live preview. Every other (commercial-specific) field is optional or
  system-defaulted.
- **Catalog seeder becomes folder auto-discovery** (per the requester directive that the program
  "see available templates in the configured folder"). `TemplateCatalogSeeder` is reworked from a
  hardcoded row list into a classpath scanner: it discovers every published layer set under
  `documents/template/sets/*` and derives one published catalog row per `(state, type)` (the base's
  national dimensions + each `state-<XX>` overlay), with name/description/version derived from the
  base's `meta`. The commercial set is thereby cataloged as "Commercial Lease Agreement
  (National|Telangana)" with **no per-template code** -- and any future template is a pure folder drop.
  Discovery is idempotent per `(state, type)` (re-runnable; an already-seeded DB gains only missing
  rows) and dedupes first-wins per pair. Test-only fixture sets are relocated out of the scan path
  (see Impact).

## Capabilities (Modified: template-document-projection)

- **template-document-projection (catalog + resolution + compile).** ADDED -- a new `(state, type) =
  (TG, commercial)` and its national `(IN, commercial)` sibling resolve to a new commercial layer set
  and compile to a "Commercial Lease Agreement" document. No engine change: the existing resolver,
  compiler, catalog, and projection API serve the new dimensions purely from added classpath content
  and a seed row. The catalog remains the dimension-validation authority (an unpublished dimension
  404s).

## Impact

- **`documents` module (resources + the seeder).** New classpath YAML under
  `documents/template/sets/commercial/` (base + three patches). `TemplateCatalogSeeder` is reworked
  into a folder-discovery loader (scans `sets/*`, derives rows from each published base + overlays;
  per-`(state, type)` idempotent, first-wins dedup). Test fixture sets (`formsection`, `optional`)
  move from `documents/template/sets/` to `documents/template/testsets/` (three test
  `ClasspathLayerSource` references updated) so discovery sees only production sets. No Java engine
  change (resolver, compiler, catalog repo, projection service untouched); `ModularityTests` stays
  green (no new cross-module surface, no brand literal in code).
- **`signing` module.** None. `AgreementDocumentMapper` already maps the eight aggregate keys the
  commercial base reuses; a TG-commercial agreement renders the commercial overlay via the existing
  `dimensionsFor(...)` catalog seam.
- **Database.** No migration. The catalog table is unchanged; the new rows are seeded (local/sandbox)
  exactly as the residential rows are. (A production catalog -- when this repo grows past sandbox --
  would insert the rows via Flyway; out of scope here, sandbox + dummy data only.)
- **Configuration / dependencies.** None. No new property, no `gradle.lockfile` change, nothing new on
  the OSV / SpotBugs surface.
- **No change to:** the effective-template identity/pin mechanism, the resolver or compiler code, the
  signing FSM, the eSign/webhook flow, stamping, object storage, or the stateless preview contract.

## Signing-status FSM

**No FSM transition is touched.** A commercial agreement flows through the same
`DRAFT -> PDF_GENERATED -> STAMPED -> SIGN_REQUESTED -> SIGNED | FAILED | EXPIRED` path; only the
document content differs.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **None.** This change adds template YAML
  (system-authored legal wording with dummy defaults) and two catalog metadata rows. No Aadhaar
  number, OTP, virtual id, biometric, government identifier, or secret is added, logged, or persisted.
- **How redacted/secured?** Unchanged and inherited: every data-derived value is HTML-escaped by the
  compiler at the existing markup/data boundary; the composed HTML/PDF and submitted values are never
  logged; the stateless preview persists nothing and stays `Cache-Control: no-store`. The new YAML
  contains only system-owned clause text and dummy sample defaults.
- **Sandbox + dummy data only?** Preserved -- no live provider, credential, or secret env var is
  added; all defaults are dummy sample values.
- **Async signing / webhook flow touched?** **None** -- no sequence diagram required.
