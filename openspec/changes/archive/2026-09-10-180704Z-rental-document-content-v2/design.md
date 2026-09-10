## Context

This is **module M5** of the `agreement-document-format` umbrella (flow-journal). M0
(`template-document-metadata`) added the declarative schema this content depends on: a `meta.document`
block `{ title, subtitle, executionLine }`, and per-section `optional` (bool, default false) + `render`
kind (`parties | keyvalue | clauses | annexure`, default `keyvalue`). M1 (`document-artifact-layout`)
reworks the one system-owned `TemplateCompiler` to draw the header and dispatch each section body by its
render kind. This CR authors the **rental content** those mechanisms operate over -- it is a
resources-only change under `backend/src/main/resources/documents/template/sets/rental/`
(`base.yaml` + the three patch layers), plus the tests that guard it.

The starting content (as landed) is a single flat national base with sections `Parties`, `Property`,
`Term`, `Financial`, `Charges & Utilities`, `Occupancy & Use`, `Covenants & General`; a residential
type patch pinning `permittedUse`; a Telangana state patch adding a statutory section; and a
Telangana-residential state_type patch that drops the national stamp clause, defaults jurisdiction to
Hyderabad, and orders the statutory section last. The target is the reference artifact's structure.

Constraints unchanged: layer precedence `base <- type <- state <- state+type`; the **parity contract**
(only the `AgreementDocumentMapper` aggregate keys are required, everything else optional/defaulted);
the markup/data boundary (bodies system-owned, escaped at compile; `showWhen` is the sandboxed DSL);
sandbox + dummy data only; `ModularityTests` green; pure-resolve tests must run without Docker.

## Goals / Non-Goals

**Goals**

- Re-author the rental layer set so the IN and TG documents read like the reference artifact: a header,
  split Owner/Tenant party cards, a Schedule of Property, a Terms-of-Tenancy key/value set, a numbered
  witnesseth clause list, an optional annexure, then the signature block.
- Declare the mandatory set (Owner, Tenant, Schedule of Property, Term, Financial) vs optional (the
  rest), and tag every section's render kind, purely in the template.
- Expand the Telangana optional add-on catalog so each reference-artifact item is individually addable
  with sensible defaults, gated opt-in.
- Preserve the parity contract and keep `ProductionRentalLayerSetTest` meaningful (updated, not broken).

**Non-Goals (owned by sibling CRs)**

- The schema fields (`meta.document`, `optional`, `render`) -- **M0**.
- The compiler that draws the header / party cards / render kinds and the "Terms of Tenancy" table
  composition -- **M1**. This CR only tags render kinds; it does not decide pixels.
- The `activeSections` opt-in mechanism -- **M2**. This CR marks sections `optional: true`; M2 gates
  their rendering.
- `FormSchema` omission of field-less sections -- **M3**. This CR authors the zero-field witnesseth
  section; M3 omits it from the capture form.
- The capture UX / add-optional rail / forced-save -- **M4**.

## Decisions

### D1: Split "Parties" into "Owner" and "Tenant", both render kind `parties`

The single `Parties` section (owner fields + tenant fields + recital) becomes two sections. `Owner`
carries `ownerName` (required), `ownerFatherName`, `ownerAddress` and the recital fragment; `Tenant`
carries `tenantName` (required), `tenantFatherName`, `tenantAddress`. Both are `render: parties` so M1
draws each as its own party card (the artifact's side-by-side Owner / Tenant blocks). Both are
`optional: false`. The recital clause text is retained (attached to the Owner section or the witnesseth
section -- an authoring detail settled at implementation, kept so the "made between ... and ..." recital
still renders). **Alternative rejected:** one `parties` section with both parties -- the artifact draws
two distinct cards and the mandatory/optional model marks each independently.

### D2: Mandatory vs optional section set (declared, not coded)

Mandatory (`optional: false`, always render): `Owner`, `Tenant`, `Schedule of Property` (the retitled
`Property`), `Term`, `Financial`, and the document-only `Now This Agreement Witnesseth` clauses section
(always part of the deed). Optional (`optional: true`, opt-in via M2 `activeSections`): the coarse
national `Charges & Utilities` and `Occupancy & Use`, the `Statutory (Telangana)` section, the optional
`Annexure`, and every fine-grained Telangana add-on (D5). This realises locked decision: mandatory set =
Owner, Tenant, Property, Term, Financial; the rest optional. Marking is per-section in the template, so
the compiler and frontend never hardcode it. **Note:** a section being mandatory means it always
*renders*; its individual fields may still be `required: false` (e.g. `ownerFatherName`) -- mandatory is
a section-render property, field-required is the parity property (D6).

### D3: A document-only "Now This Agreement Witnesseth" clauses section

The reference artifact aggregates the covenant clauses (care of premises, no subletting, inspection,
handover, permitted use, etc.) into one numbered "Now This Agreement Witnesseth" list. This CR authors a
dedicated section titled `Now This Agreement Witnesseth`, `render: clauses`, with **zero fields** (its
entries are clause ids only). Because it projects to zero fields, M3 omits it from the `FormSchema` (it
is document structure, not a capture step) while M1 still renders it. It is `optional: false` (always
renders). The clauses currently spread across `Covenants & General` (and the always-on covenants) move
here; the field-bearing tail of the old `Covenants & General` (dispute resolution, special conditions)
becomes optional add-ons (D5). **Alternative rejected:** leaving covenants inside capture sections --
the artifact reads them as a single recital block, and a clause-only section is the clean way to keep
them out of the form.

### D4: `meta.document` header, National base with an optional Telangana override

The base declares:

```
meta:
  document:
    title:         "Rental Agreement"
    subtitle:      "Residential Tenancy (Leave & Licence)"
    executionLine: "This Agreement is executed on {{agreementDate}} in respect of the property in the Schedule below."
```

`executionLine` is system-authored text with `{{agreementDate}}` filled and HTML-escaped at compile
(M1); when `agreementDate` is blank the projection layer resolves the execution date to SYSDATE (M1's
concern -- this CR only authors the line). Telangana **MAY** override the subtitle and/or execution line
(e.g. a Telangana-specific subtitle or a registration-before-the-Sub-Registrar phrasing) via the
`state` / `state_type` patch; the national wording is the default when TG does not override. Because M0
carries `meta.document` through the canonicalizer, a TG override changes the content hash deterministically.
**Alternative rejected:** a hardcoded header in the compiler -- locked decision #7 (config in templates,
not code) forbids it.

### D5: Telangana optional add-on catalog -- each artifact item individually addable, gated, defaulted

The Telangana layer expands the optional catalog so each reference item is its own opt-in optional
section (or clause), `optional: true`, gated by M2 `activeSections`, with a system-authored default so it
reads sensibly the moment it is added. Illustrative mapping (exact field/clause allocation is an
authoring detail, reusing existing base fields/clauses where present; the contract is "each item is
individually addable, defaulted, and opt-in"):

```
  add-on (optional section)      default(s)                    reuses field(s) / clause(s)
  -----------------------------  ----------------------------  ---------------------------------
  Rent Escalation                rentEscalationPercent = 5     escalationClause (showWhen > 0)
  Security Deposit Terms         (refund covenant)             depositClause
  Late-Payment Penalty           gracePeriodDays = 5           latePaymentPenalty, latePaymentClause
  Maintenance Charges            maintenanceBorneBy = tenant   maintenanceClause, maintenanceAmountClause
  Utilities Split                utilitiesBorneBy = tenant     utilitiesClause
  Lock-in                        lockInMonths = 6              lockInClause (showWhen > 0)
  Notice Period                  noticePeriodMonths = 1        noticeClause
  Permitted Occupants            maxOccupants (unset)          occupancyClause (showWhen > 0)
  Pets                           petsAllowed = false          petsAllowedClause / petsNotAllowedClause
  Parking                        parkingType = none           parkingClause (showWhen != none)
  Furnishing                     furnishingStatus = unfurnished  furnishing inventory clause
  Fixtures/Inventory Annexure    (empty inventory)             render: annexure (D7)
  Dispute Resolution             disputeResolution = courts    disputeClause
  Custom / Special Clause        specialConditions (unset)     specialConditionsClause (showWhen != "")
```

Two gating layers compose: **M2 `activeSections`** decides whether the section renders at all (opt-in);
the existing **`showWhen`** DSL still governs individual clauses within a rendered section (e.g. the
pets clause pair). An add-on absent from `activeSections` contributes no header, fields, or clauses.
**Note on the coarse national sections:** the national base keeps `Charges & Utilities` and
`Occupancy & Use` as coarse optional sections; the Telangana layer offers the finer-grained
individually-addable versions. Whether TG *replaces* the coarse sections with the fine-grained set or
*adds* them alongside is an authoring choice settled in implementation, provided the mandatory set and
parity contract are preserved and no entry dangles.

### D6: Parity contract preserved -- only aggregate-backed keys required

The `AgreementDocumentMapper` emits exactly `ownerName`, `tenantName`, `propertyAddress`, `monthlyRent`,
`securityDeposit`, `durationMonths`, `startDate`, `endDate`. Those stay the **only** `required: true`
fields; every other field (including any moved into an optional add-on, e.g. `lockInMonths`,
`noticePeriodMonths`) is `required: false` or defaulted. So a generate-as-draft fed only the aggregate
keys validates (defaults fill the rest) and compiles without a missing-required error. The mandatory
sections (Owner, Tenant, Schedule of Property, Term, Financial) hold all eight required keys; the
optional add-ons hold only optional/defaulted fields, so gating them out of generate (M2's default
active-set is empty) never trips required-validation. This is the invariant
`ProductionRentalLayerSetTest.generateParityHoldsWithOnlyTheAggregateBackedData` guards.

### D7: The optional fixtures/inventory Annexure, render kind `annexure`

A `render: annexure` section titled `Annexure` (fixtures / inventory schedule), `optional: true`, opt-in.
M1 draws `annexure` as a bulleted / itemised annexure block after the signature-block region. Its content
is a system-authored inventory scaffold (dummy default entries), reusing `furnishingStatus` context. It is
one of the D5 catalog items and the only one using the `annexure` render kind.

### D8: Update `ProductionRentalLayerSetTest` deliberately, do not silently break it

The existing pure test asserts `BASE_SECTIONS = [Parties, Property, Term, Financial, Charges & Utilities,
Occupancy & Use, Covenants & General]` and, for TG, `containsExactly(..., "Statutory (Telangana)")` with
size 8, plus that the compiled HTML for a generate projection contains statutory text. The new structure
changes the section list (Owner/Tenant split, Schedule of Property, witnesseth section, optional add-ons)
and moves the statutory/covenant content behind opt-in gating. The test is **updated deliberately** to
the new expectations:

- IN resolves with the new mandatory sections (Owner, Tenant, Schedule of Property, Term, Financial) +
  the witnesseth clauses section, and the optional sections marked `optional: true`.
- Section render kinds are as declared (parties / keyvalue / clauses / annexure).
- `meta.document` is present (title / subtitle / executionLine) for IN, and the TG override (if any)
  differs deterministically.
- The parity projection (aggregate-backed keys only) still validates + compiles; because M2's default
  active-set is empty, generate renders the mandatory sections and the witnesseth clauses -- the statutory
  and other optional content appears only when its section is in `activeSections`.

The compile-to-artifact-layout assertions (header markup, party-card structure) depend on **M1**; until
M1 lands they are authored but expected to pass only against the M1 compiler. The resolve + validate +
mandatory/optional/render-kind assertions depend only on **M0**.

## Risks / Trade-offs

- **Test coupling to M1.** M5's compile assertions about the artifact header / party cards only pass once
  M1's compiler renders those kinds. Mitigation: split the test's concerns -- the M0-only assertions
  (structure, flags, render kinds, parity validate) run immediately; the M1-dependent assertions
  (header/party-card HTML) are gated on M1 landing (flow-journal sequences M0 + M1 + M5 together for the
  visible win).
- **Statutory content now opt-in for generate.** Previously the TG statutory section always rendered in a
  generated draft. Under opt-in gating (M2), it renders only when in `activeSections`; GENERATE's
  active-set is deferred (M2 open point), so a signed draft may omit statutory content until the
  save/sign path records the active-set. Accepted per the umbrella's known parity caveat (flow-journal
  section 6). If statutory content must always appear in a Telangana deed, mark `Statutory (Telangana)`
  `optional: false` (mandatory) instead -- an authoring decision to confirm with legal before archive.
- **Coarse vs fine-grained overlap (D5).** Keeping both the coarse national optional sections and the
  fine-grained TG add-ons risks duplicate content if both are added. Mitigation: the TG layer decides
  replace-vs-augment at authoring time so no field/clause is listed twice and no entry dangles; the
  integration test asserts no duplicate section title and no dangling entry.
- **Recital placement.** Moving the recital between the Owner/Tenant split and the witnesseth section is
  an authoring choice; whichever is chosen, the "made between ... and ..." recital must still render.
  The test asserts the recital text is present.

## Open Questions

- **Is `Statutory (Telangana)` mandatory or optional?** The umbrella marks it optional; a Telangana
  Leave-and-Licence deed arguably must always carry the statutory overlay. Proposing `optional: true`
  (opt-in, consistent with the umbrella) but flag for legal confirmation before archive; if mandatory,
  flip its flag only (no other change).
- **Replace or augment the coarse national optional sections in TG (D5).** Proposing the TG layer offers
  the fine-grained add-ons; confirm whether it removes the coarse `Charges & Utilities` /
  `Occupancy & Use` for TG or leaves them alongside.
- **Where the recital lives (D1/D3).** Owner section vs the witnesseth section. Proposing the witnesseth
  section (it reads as a recital); confirm.
- **TG header override wording (D4).** Whether TG changes the subtitle / execution line and to what.
  Proposing the national wording as default with an optional TG subtitle; confirm the exact phrasing with
  legal.
