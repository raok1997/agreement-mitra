# Tasks -- Rental document content v2 (M5)

> **Prerequisite:** this change **depends on `template-document-metadata` (M0)** -- the schema must
> already carry `meta.document`, per-section `optional`, and per-section `render`. Its
> compile-to-artifact-layout assertions are **visually verified once `document-artifact-layout` (M1)**
> lands (the header / party-card HTML is M1's output); the resolve + validate + flag/render-kind
> assertions need only M0. This CR authors **content only** (YAML under
> `backend/src/main/resources/documents/template/sets/rental/`) plus its tests -- **no Java, no schema,
> no migration, no frontend.**
>
> **Windows dev note (project memory):** run tests via gradle directly with
> `TESTCONTAINERS_RYUK_DISABLED=true` and `-Duser.timezone=Asia/Kolkata`. Write all resource + test
> files in **pure ASCII** (the PII/secret guard fails closed on non-ASCII under Git Bash). Add a Java
> import in the same edit as its first use (the format hook prunes unused imports).
>
> **Implementation decisions (resolving the design Open Questions provisionally; confirm with legal
> per 9.4 before archive):**
> - **M0/M1/M2/M3 have all landed** -- the schema (`meta.document`/`optional`/`render`), the compiler
>   (header, party cards, keyvalue/clauses/annexure render kinds, `activeSections` gating), and the
>   field-less-section omission from `FormSchema` are all present. This CR is pure content, as scoped.
> - **Add-on catalog authored in the shared `base.yaml`, not the TG layer** (resolving D5 / Open
>   Question "replace-vs-augment / where" toward *augment-in-base*). The individually-addable add-ons
>   (rent escalation, lock-in, notice, maintenance, utilities, late-payment, occupants, pets, parking,
>   dispute, custom clause, furnishing/annexure) are universal to a rental deed, so they live in base
>   (available to IN **and** TG) as `optional: true` sections + `showWhen` clauses; the TG layers add
>   **only** the genuinely state-specific statutory overlay + Hyderabad jurisdiction. This guarantees
>   **no double-listing** (each field/clause in exactly one section) with far less patch machinery.
> - **`Statutory (Telangana)` authored MANDATORY** (`optional: false`, resolving Open Question #1
>   conservatively): a Telangana Leave-and-Licence deed must always carry the statutory overlay, and
>   under M2's deferred generate active-set an optional statutory section could be silently omitted
>   from a signed draft. Flip the one flag if legal decides it is opt-in.
> - **Recital + dispute/jurisdiction covenant live in the always-on witnesseth section** (resolving
>   the recital-placement Open Question): the party recital and the "courts at Hyderabad" covenant must
>   always render, so they sit in the mandatory `Now This Agreement Witnesseth` clause list. The
>   dispute/jurisdiction/special-conditions *fields* stay in the optional `Dispute Resolution` capture
>   section.
> - **TG header override:** none authored -- the national wording is the default (D4 latitude; confirm
>   any TG-specific subtitle/phrasing with legal).

## 1. Document header (`meta.document`) -- base + Telangana override

- [x] 1.1 Add a `meta.document` block to `base.yaml`: `title: "Rental Agreement"`, `subtitle:
  "Residential Tenancy (Leave & Licence)"`, `executionLine: "This Agreement is executed on
  {{agreementDate}} in respect of the property in the Schedule below."` (system-authored, `{{slot}}`
  fill, escaped at compile by M1).
- [x] 1.2 (Optional per D4/Open Questions) **Decision: no TG header override authored** -- the national
  wording is the default (D4 latitude). No subtitle/execution-line override in the TG layers; confirm any
  TG-specific phrasing with legal (see 9.4) and add an `overrideField`-style header patch only if they
  require distinct wording.

## 2. Split Parties into Owner + Tenant (render kind `parties`)

- [x] 2.1 In `base.yaml`, replace the single `Parties` section with two sections: `Owner`
  (`ownerName` required, `ownerFatherName`, `ownerAddress`) and `Tenant` (`tenantName` required,
  `tenantFatherName`, `tenantAddress`). Tag both `render: parties` and `optional: false`. Retain the
  recital text so the "made between ... and ..." recital still renders (placement per D1/D3).

## 3. Mandatory / optional flags + render kinds on the remaining sections

- [x] 3.1 Retitle `Property` -> `Schedule of Property`; tag `render: keyvalue`, `optional: false`.
- [x] 3.2 Tag `Term` and `Financial` `render: keyvalue`, `optional: false` (M1 composes them into the
  "Terms of Tenancy" table). Keep only the eight aggregate-backed keys required (D6); move lock-in /
  notice-period fields into their optional add-on sections (section 5) or leave defaulted-optional in
  Term per authoring choice, provided parity holds.
- [x] 3.3 Tag the coarse national `Charges & Utilities` and `Occupancy & Use` sections `optional: true`
  (opt-in; render kind `keyvalue` unless authored otherwise).

## 4. Document-only "Now This Agreement Witnesseth" clauses section

- [x] 4.1 Add a `Now This Agreement Witnesseth` section: `render: clauses`, `optional: false`, **zero
  fields** (entries are clause ids only). Move the covenant clauses (care-of-premises, no-subletting,
  inspection, handover, permitted-use, and the always-on covenants) into it so it reads as the numbered
  witnesseth recital list. Because it projects to zero fields, M3 omits it from the `FormSchema` (verify
  once M3 lands). Ensure no clause is orphaned and no section still lists a moved clause (no dangling
  entry).

## 5. Telangana optional add-on catalog (each item individually addable, gated, defaulted)

- [x] 5.1 In the Telangana layer(s) (`state-TG.patch.yaml` / `state_type-TG-residential.patch.yaml`),
  author each reference-artifact item as an individually-addable optional section (or clause),
  `optional: true`, gated by M2 `activeSections`, each with a system-authored default (D5 mapping): rent
  escalation, security-deposit terms, late-payment penalty, maintenance charges, utilities split,
  lock-in, notice period, permitted occupants, pets, parking, furnishing, dispute resolution,
  custom/special clause. Reuse existing base fields/clauses where present; preserve `showWhen` on the
  clauses that already have it.
- [x] 5.2 Add the optional fixtures/inventory `Annexure` section: `render: annexure`, `optional: true`,
  opt-in, with a system-authored (dummy) inventory scaffold.
- [x] 5.3 Decide replace-vs-augment for the coarse national optional sections in TG (D5 / Open
  Questions): ensure no field/clause appears twice and no entry dangles.
- [x] 5.4 Update `state_type-TG-residential.patch.yaml` `reorderSections` to the new order (Owner,
  Tenant, Schedule of Property, Term, Financial, [optional add-ons], Now This Agreement Witnesseth,
  Annexure, Statutory (Telangana)) so the document reads correctly; keep the statutory overlay in its
  intended slot.

## 6. Parity contract check (content-level)

- [x] 6.1 Confirm the only `required: true` fields across the whole composed set (IN and TG) are the
  eight aggregate-backed keys (`ownerName`, `tenantName`, `propertyAddress`, `monthlyRent`,
  `securityDeposit`, `durationMonths`, `startDate`, `endDate`); every other field is `required: false` or
  carries a default. Any field moved into an optional add-on must be optional/defaulted.

## 7. Tests -- unit (pure resolve + compile, no Spring, no Docker)

> These run in `./gradlew test` regardless of the Docker daemon (pure resource I/O). This is the
> "many, fast" base for this content change.

- [x] 7.1 Update `ProductionRentalLayerSetTest` (deliberately, D8): assert the new IN section set (Owner,
  Tenant, Schedule of Property, Term, Financial, Now This Agreement Witnesseth) with correct
  `optional` flags (Owner/Tenant/Schedule/Term/Financial/witnesseth mandatory; the rest optional) and
  render kinds (parties / keyvalue / clauses / annexure). Assert `meta.document` (title / subtitle /
  executionLine) is present for IN and that the recital text still renders.
- [x] 7.2 Unit: the parity projection still holds -- a GENERATE `validateAndCoerce` fed only the
  aggregate-backed keys (D6) validates (defaults fill the rest) and `TemplateCompiler.compile` does not
  throw, for both IN and TG. Assert defaulted fields (e.g. `permittedUse = residential`) are filled.
- [x] 7.3 Unit: optional add-ons are gated -- with an empty active-set the witnesseth + mandatory
  sections render and no optional add-on content appears; with a given add-on active (or, pre-M2, via the
  section's presence semantics), its content appears. (The activeSections wiring is M2; assert the
  template-level flag/structure that M2 keys off -- each add-on section is `optional: true` and
  self-contained so gating it out leaves no dangling reference.)
- [x] 7.4 Unit: the Telangana overlay still applies over the new structure -- statutory clauses present
  (`tgGoverningLaw`, `tgStampRegistration`, `tgEssentialServices`), the generic national stamp clause
  dropped, Hyderabad jurisdiction default -- and no entry dangles after the section re-org.

## 8. Tests -- integration (fewer; real module wiring)

> Mirror the existing `documents`-module tests. Use Testcontainers only if the assertion needs the DB;
> the resolve/projection path can run as a Spring `documents` slice. Keep `ModularityTests` green.

- [x] 8.1 Integration: over the resolved IN and TG set, the form/projection surface reports the correct
  per-section `optional` flag and `render` kind, and the zero-field `Now This Agreement Witnesseth`
  section is omitted from the capture `FormSchema` (M3 behaviour) while still present in the document
  structure. (Depends on M3 for the omission assertion; assert the render-kind/optional exposure
  regardless.)
- [x] 8.2 Integration: an optional add-on is opt-in end to end over the TG set -- a preview/compile with
  the add-on's section active differs from one without it by exactly that section's content, and a
  mandatory section always renders (depends on M2 `activeSections`; assert the gated difference once M2 is
  wired).
- [x] 8.3 Integration: `ModularityTests` stays green (resources-only change; no code moves, no new
  cross-module dependency).

## 9. Wrap-up

- [x] 9.1 `./gradlew spotlessApply` (formats the updated test). Run the pure suite:
  `./gradlew test --tests "in.agreementmitra.documents.template.ProductionRentalLayerSetTest" --tests
  "in.agreementmitra.ModularityTests"` (add `TESTCONTAINERS_RYUK_DISABLED=true`,
  `-Duser.timezone=Asia/Kolkata` on Windows). Run the `documents` integration tests with Docker up before
  proposing archive.
- [x] 9.2 M1 has landed; `ProductionRentalLayerSetTest.bothDimensionsCompileToTheArtifactLayoutInvariants`
  asserts the compiled layout for IN and TG (centred `meta.document` header, split Owner/Tenant party
  cards, key/value table, numbered witnesseth list, signature block, page margins, self-contained) and
  the gating/annexure tests assert each optional add-on is individually addable. Flow-journal section 5
  tracking row for `rental-document-content-v2` set to `applied`. **A manual PDF eyeball via
  `bootRun` + a live preview is still worth doing before archive** (automated tests assert markup, not
  pixels).
- [x] 9.3 Confirm **no new dependency and no `gradle.lockfile` change** (content + tests only).
- [ ] 9.4 **Resolved provisionally in code (see the decisions note atop this file); still needs legal
  sign-off before archive:** `Statutory (Telangana)` authored **mandatory**; coarse optional sections
  **augmented in the shared base** (not replaced in TG); recital placed in the **witnesseth** section;
  **no** TG header override (national wording). Each is a one-flag / one-line change if legal decides
  otherwise. This box stays open until legal confirms.
