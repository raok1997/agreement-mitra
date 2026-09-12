# Rental document content v2 (M5)

> **Prerequisite / coordination.** This change is **module M5** of the
> `agreement-document-format` umbrella (flow-journal section 3). It edits the
> production rental layer set content (`backend/src/main/resources/documents/template/sets/rental/`)
> to consume the schema added by **`template-document-metadata` (M0)** and to be rendered by
> **`document-artifact-layout` (M1)**. It **depends on M0** (the `meta.document` block and the
> per-section `optional` + `render` fields must exist in the schema/loader/validator/canonicalizer
> first). Its resolve/validate assertions need only M0; its compile-to-artifact-layout assertions
> (centred header, Owner/Tenant party cards) are **visually verified once M1 lands** and can be
> authored in parallel. Obey the FROZEN CONTRACTS (flow-journal section 4) and LOCKED DECISIONS
> (section 2). This CR authors **content only** -- no schema, compiler, projection, or frontend code.
> (Written in pure ASCII: the local PII/secret edit guard fails closed on non-ASCII under Windows Git
> Bash.)

## Why

The just-landed production rental layer set renders a functional-but-plain document: a single "Parties"
section lumps Owner and Tenant together, every section is implicitly mandatory, there is no document
header (title / subtitle / execution line), sections carry no declared render style, and the covenant
clauses sit inside a capture section rather than reading as a signed agreement's numbered
"witnesseth" recitals. The reference artifact
(`claude.ai/code/artifact/fd23f28f-cdcb-4883-83ca-ac966285c12a`) reads like a real Leave and Licence
deed: a centred header, separate Owner and Tenant party cards, a "Schedule of Property", a
"Terms of Tenancy" key/value table, a single numbered "Now This Agreement Witnesseth" clause list, an
optional fixtures annexure, then the signature block -- with a clear split between the few **mandatory**
sections a tenancy must have and a catalog of **optional** add-ons a user opts into.

M0 moved the *mechanism* for this into the template schema: `meta.document` (header text), per-section
`optional` (bool, default false), and per-section `render` kind (`parties | keyvalue | clauses |
annexure`). M1 teaches the one system-owned compiler to draw each render kind and the header. **This
change supplies the missing half: the actual content** -- it re-authors the rental layer set so the
National (IN) and Telangana (TG) documents declare the header, the split Owner/Tenant party sections,
the mandatory-vs-optional flags, the render kinds, the document-only witnesseth clause section, the
optional annexure, and the full Telangana optional add-on catalog (each artifact item individually
addable). It is pure template content over the M0 schema; it changes no Java.

## What Changes

- **Split "Parties" into "Owner" and "Tenant".** The single `Parties` section becomes two sections --
  `Owner` (owner/lessor fields + recital fragment) and `Tenant` (tenant/licensee fields) -- each tagged
  `render: parties` so M1 draws them as separate party cards.
- **Declare the mandatory set.** `Owner`, `Tenant`, `Property` (retitled "Schedule of Property"),
  `Term`, and `Financial` are marked `optional: false` (always render). Every other section --
  `Charges & Utilities`, `Occupancy & Use`, `Statutory (Telangana)`, and every fine-grained Telangana
  add-on -- is marked `optional: true` (opt-in via M2 `activeSections`).
- **Add a `meta.document` header.** Title `Rental Agreement`, subtitle
  `Residential Tenancy (Leave & Licence)`, and execution line
  `This Agreement is executed on {{agreementDate}} in respect of the property in the Schedule below.`
  National (IN base) and Telangana (TG state / state_type) MAY differ in wording (e.g. a TG subtitle or
  execution/statutory phrasing) -- the header is carried by the base and MAY be overridden by the TG
  layers.
- **Tag section render kinds.** `Owner` / `Tenant` -> `parties`; `Schedule of Property`, `Term`,
  `Financial` -> `keyvalue` (M1 composes `Term` + `Financial` visually into the "Terms of Tenancy"
  table); a **document-only** `Now This Agreement Witnesseth` section (`render: clauses`, **zero
  fields**) aggregates the covenant clauses and is therefore omitted from the capture form per M3; an
  **optional** fixtures/inventory `Annexure` section (`render: annexure`).
- **Expand the Telangana optional add-on catalog.** Each reference-artifact item -- rent escalation,
  security-deposit terms, late-payment penalty, maintenance charges, utilities split, lock-in, notice
  period, permitted occupants, pets, parking, furnishing, fixtures/inventory annexure, dispute
  resolution, and a custom/special clause -- becomes an **individually-addable** optional section or
  clause with **system-authored sensible defaults**, gated so its content appears only when added
  (opt-in), consistent with M2 `activeSections` and the existing `showWhen` DSL.
- **Preserve the parity contract.** Every field NOT backed by the `AgreementDocumentMapper` aggregate
  keys (`ownerName`, `tenantName`, `propertyAddress`, `monthlyRent`, `securityDeposit`,
  `durationMonths`, `startDate`, `endDate`) stays `required: false` or carries a system-authored
  default, so a generate-as-draft fed only the aggregate keys still validates and compiles.
- **Update `ProductionRentalLayerSetTest` deliberately.** The existing pure resolve+compile test
  asserts the old flat section list (`Parties`, `Property`, ...); it is updated to the new structure
  (Owner/Tenant split, mandatory/optional flags, render kinds, witnesseth clause section, header
  present, optional add-ons gated) rather than silently broken.

**Explicitly not in this change** (each owned elsewhere): the schema fields themselves
(`template-document-metadata`, M0); the compiler that draws the header / party cards / render kinds
(`document-artifact-layout`, M1); the opt-in `activeSections` projection mechanism
(`optional-section-opt-in`, M2); the `FormSchema` omission of field-less sections
(`form-schema-section-semantics`, M3); and the capture UX / add-optional catalog rail
(`capture-mandatory-optional-ux`, M4). This CR only authors the rental **content** those mechanisms
operate over.

## Capabilities

### New Capabilities

- `rental-agreement-document`: the **content contract** of the production rental layer set -- the
  document structure a rendered rental agreement must have. It declares a configurable `meta.document`
  header (title / subtitle / execution line, National vs Telangana wording); a split **Owner** and
  **Tenant** party set (render kind `parties`); a **Schedule of Property**, a **Term**, and a
  **Financial** key/value set (render kind `keyvalue`, composing the "Terms of Tenancy" table); a
  **document-only** "Now This Agreement Witnesseth" covenant-clause section (render kind `clauses`, zero
  fields, omitted from the capture form); an **optional** fixtures/inventory **Annexure** (render kind
  `annexure`); a fixed **mandatory** section set (Owner, Tenant, Schedule of Property, Term, Financial)
  versus **optional** opt-in sections; and a **Telangana optional add-on catalog** in which each
  reference-artifact item (rent escalation, security-deposit terms, late-payment penalty, maintenance
  charges, utilities split, lock-in, notice period, permitted occupants, pets, parking, furnishing,
  fixtures/inventory annexure, dispute resolution, custom/special clause) is individually addable with
  sensible defaults and gated to appear only when added. The parity contract is preserved: only the
  aggregate-backed field keys are required; every other field is optional or defaulted.

## Impact

- **`documents` module resources**: content-only edits under
  `backend/src/main/resources/documents/template/sets/rental/` -- `base.yaml` (national header, Owner /
  Tenant split, mandatory/optional flags, render kinds, witnesseth clause section, coarse optional
  sections, optional annexure), `type-residential.patch.yaml` (unchanged residential pin, revisited only
  if the section re-org needs it), `state-TG.patch.yaml` (Telangana header override MAY, the
  fine-grained optional add-on catalog), and `state_type-TG-residential.patch.yaml` (section ordering
  for the new set). **No Java, no schema, no migration, no frontend.**
- **Tests**: `ProductionRentalLayerSetTest` (pure resolve+compile, no Spring, no Docker) updated to the
  new structure; a `documents`-module integration test asserts the resolved set surfaces the mandatory /
  optional flags + render kinds and that optional add-ons are gated (absent until added). `ModularityTests`
  stays green (no code moves; resources only).
- **Dependencies**: **none added.** No `gradle.lockfile` change; nothing new enters the OSV
  `securityScan` surface.
- **Reuse, not fork**: consumes the M0 schema fields and the M1 render kinds; does not re-implement the
  resolver, compiler, projection, or form. The layer-set composition + precedence
  (`base <- type <- state <- state+type`) is unchanged.
- **No** change to: the signing-status FSM, `EsignProvider` / webhook flow, stamping, object storage,
  the reconciliation job, the resolver, the compiler, or the projection services.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **None.** This change edits **system-authored
  template content** -- section titles, field labels, clause text, render kinds, mandatory/optional
  flags, and a document header. It carries **no signer data** (no government identity number, one-time
  code, virtual id, name, address, or party PII) and **no secret material**. The template describes the
  *shape* of a rental agreement, never an instance of anyone's data. All example values referenced in
  tests are **dummy** (e.g. "Asha Owner", a Jubilee Hills plot).
- **Markup/data boundary held.** Template bodies stay **system-owned** and are HTML-escaped at compile
  (M1); users pick and fill, never author markup. The `meta.document.executionLine` is system-authored
  text with `{{slot}}` fills (e.g. `{{agreementDate}}`), escaped at compile. `showWhen` stays the
  existing sandboxed DSL; this change adds no new expression surface. No user-authored markup enters.
- **Sandbox + dummy data only?** Preserved -- content and tests use dummy, system-authored values;
  nothing connects to a live provider or real data; no credential or env var is added.
- **Never-log discipline.** No logging is added or changed; previews/renders persist and log nothing
  (unchanged). No webhook payload is touched.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** -- no signing sequence diagram required.
- **Bodies never enter Postgres.** Unchanged: template/layer bodies stay classpath resources; this
  change adds no persistence.
