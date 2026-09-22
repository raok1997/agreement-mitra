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
> - **`Statutory (Telangana)` is MANDATORY** (`optional: false`). **This note was stale until
>   2026-09-07 and said the opposite of the code**: the section shipped `optional: true` (a
>   2026-07-13 requester decision), while this note claimed mandatory. Anyone reading the note --
>   including a lawyer asked to sign off 9.4 -- would have approved behaviour the product did not
>   have.
>
>   **The real reason it is now mandatory is NOT the rationale this note used to give** (the
>   deferred-active-set risk). It is a concrete hole found 2026-09-07:
>   `state_type-TG-residential.patch.yaml` re-authors the witnesseth list **without** the national
>   `stampRegistrationClause` (its own comment says so), and the TG replacement `tgStampRegistration`
>   lived only inside the opt-in statutory section. **So a default Telangana deed rendered with no
>   stamp/registration clause at all, while every national deed carries one** -- the one state with a
>   bespoke layer shipped strictly worse than the shared template.
>
>   Flipping the single flag restores three things at once, all already entries in that section: the
>   stamp/registration clause, the TG tenancy-law reference (`tgGoverningLaw`, the 1960 Act), and the
>   essential-services protection (`tgEssentialServices`). It costs the user no input -- both fields
>   are `required: false` and `tgStampAmount` is gated `showWhen: stampDutyAmount > 0`.
>
>   **If legal decides it really is opt-in, restoring `optional: true` is not enough** --
>   `stampRegistrationClause` must go back into the TG witnesseth list in the same edit, or the hole
>   returns.
> - **Recital + dispute/jurisdiction covenant live in the always-on witnesseth section** (resolving
>   the recital-placement Open Question): the party recital and the "courts at Hyderabad" covenant must
>   always render, so they sit in the mandatory `Now This Agreement Witnesseth` clause list. The
>   dispute/jurisdiction/special-conditions *fields* stay in the optional `Dispute Resolution` capture
>   section.
> - **TG header override:** none authored -- the national wording is the default (D4 latitude).
>   **Amended 2026-09-10:** the national wording itself was the problem. The subtitle and recital said
>   "(Leave & Licence)" -- the *Maharashtra* residential form -- while every other signal in the deed
>   said **lease**: `scheduleClause` uses "lets" (s.105 Transfer of Property Act), the parties are
>   Owner/Tenant, there is a no-subletting covenant, the TG overlay cites the Telangana Buildings
>   (**Lease**, Rent and Eviction) Control Act 1960, `state-stamp-duty-quoting` prices under Karnataka
>   Stamp Act Article 30 (the **lease** article), and the sibling commercial set is a "Commercial Lease
>   Agreement" with Lessor/Lessee. The licence label was the only licence-shaped thing in the document.
>   It was removed (`base.yaml` v2) as a **de-contradiction, not a ruling** -- courts read substance
>   over label (*Associated Hotels of India v. R.N. Kapoor*, 1959: the test is exclusive possession),
>   so this aligns what the deed says it is with what it already does. The substantive lease-vs-licence
>   question and the Lessor/Lessee vocabulary went to the follow-up register for counsel.

## 1. Document header (`meta.document`) -- base + Telangana override

- [x] 1.1 Add a `meta.document` block to `base.yaml`: `title: "Rental Agreement"`, `subtitle:
  "Residential Tenancy"` (**amended 2026-09-10** -- authored as `"Residential Tenancy (Leave &
  Licence)"`, the licence label removed under 9.4 below), `executionLine: "This Agreement is executed on
  {{agreementDate}} in respect of the property in the Schedule below."` (system-authored, `{{slot}}`
  fill, escaped at compile by M1).
- [x] 1.2 (Optional per D4/Open Questions) **Decision: no TG header override authored** -- the national
  wording is the default (D4 latitude). No subtitle/execution-line override in the TG layers.
  **Amended 2026-09-10:** the D4 question was framed as *TG-specific wording*, but the real defect was
  in the **national** wording, so no TG override is needed -- the national subtitle was corrected
  instead (`"Residential Tenancy (Leave & Licence)"` -> `"Residential Tenancy"`). Rationale in 9.4 and
  in the `base.yaml` v2 meta comment. A Maharashtra set, if one is ever added, carries leave-and-licence
  in its own **state** layer.

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
  - **2026-09-10:** the compiled deed HTML was dumped for all four combinations (IN/TG x default/all
    add-ons) and the **content** was read end to end: the v2 header renders "Rental Agreement /
    Residential Tenancy", the recital reads without the licence label, TG names Hyderabad in the
    jurisdiction covenant and carries the mandatory statutory overlay. **The pixel/pagination check was
    performed by the repo owner on 2026-09-10**, in Chrome over those rendered documents,
    and passed. (Chromium-via-`bootRun` PDF pagination was not separately re-checked; the browser
    render of the same compiled HTML was accepted as the eyeball.)
    One thing the content read surfaced: the national deed renders
    "the courts at [ Jurisdiction city ]" -- registered, see 9.4.
- [x] 9.3 Confirm **no new dependency and no `gradle.lockfile` change** (content + tests only).
- [x] 9.4 **CLOSED 2026-09-10 by descoping the legal sign-off to the follow-up register, on the repo
  owner's decision.** Original text: *"Resolved provisionally in code; still needs legal sign-off
  before archive: `Statutory (Telangana)` authored mandatory; coarse optional sections augmented in
  the shared base (not replaced in TG); recital placed in the witnesseth section; no TG header
  override (national wording). Each is a one-flag / one-line change if legal decides otherwise. This
  box stays open until legal confirms."*
  - **Why it closed rather than waited:** no counsel is engaged and the brief is unsent (see below),
    so the gate as written had no party who could satisfy it. Holding an otherwise-complete CR open
    on an unengaged third party is not a gate, it is an indefinitely-parked change. Per CLAUDE.md,
    work a CR identifies but deliberately does not fold in belongs in the `## Follow-up register` in
    `docs/ROADMAP.md` **before** the CR archives. It is recorded there, flagged as blocking the first
    real customer and as a dependency of `state-stamp-duty-quoting`.
  - **Risk accepted knowingly:** prod is beta with founding-team users only, no real customers, and
    each of the four calls is a one-flag / one-line reversal.
  - **Disposition of the four calls:**
    1. **`Statutory (Telangana)` mandatory -- CONFIRMED as shipped.** Barely a legal question:
       opt-in was demonstrably broken (a default TG deed rendered with no stamp/registration clause
       at all), mandatory costs the user no input, and it is strictly safer. If it is ever reverted,
       `stampRegistrationClause` must return to the TG witnesseth list in the same edit.
    2. **Coarse optional sections augmented in the shared base -- CONFIRMED as shipped.** An
       architecture call about where content lives; the rendered deed is identical either way. No
       legal content in the question.
    3. **Recital in the witnesseth section -- CONFIRMED as shipped.** Placement convention only.
       Slightly unconventional (Indian deeds usually put the recital above the witnesseth clause);
       one entry to move if anyone objects. Registered as a low-priority drafting nit.
    4. **No TG header override -- RESOLVED DIFFERENTLY.** The question was mis-framed as TG-specific
       *wording*; the defect was in the **national** wording. `base.yaml` v2 removes the
       "(Leave & Licence)" label from the subtitle and the recital. Full reasoning in the amended
       decisions note atop this file and in the `base.yaml` v2 meta comment. **The substantive
       lease-vs-licence ruling and the Lessor/Lessee vocabulary question are NOT decided here** --
       both are in the follow-up register for counsel.
  - **`base.yaml` version bumped 1 -> 2** with the label removal, for the same reason `state:TG` was
    bumped: agreements already generated pin `base: 1` and rendered the old wording, and
    `Agreement.pinEffectiveTemplate` exists to keep "which content did this deed render from"
    answerable. Tests updated with it: `ProductionRentalLayerSetTest` (subtitle + a
    `doesNotContain("Licence")` guard), `AgreementDocumentFormatE2EIntegrationTest` (rendered header;
    its incidental ampersand-escaping check was dropped -- escaping is covered as a unit by
    `TemplateCompilerTest.headerTextContainingMarkupRendersAsLiteralText`), and
    `ProductionCommercialLayerSetTest` (its `isNotEqualTo` guard repointed at the new string so it
    does not go vacuous).
  - **Two documentation-drift defects fixed in the same pass** (both are the exact hazard this task
    warns about -- a reviewer, counsel included, reading a file that describes behaviour the code no
    longer has):
    - `state_type-TG-residential.patch.yaml` still described `Statutory (Telangana)` as "OPT-IN
      OPTIONAL (2026-07-13 requester decision)" and said `tgGoverningLaw` applies "when the user adds
      it" -- **two months after the flag was flipped**. Both comments corrected.
    - This task previously claimed `ReferenceLayerSetResolutionIntegrationTest` "now asserts
      `state:TG -> 2`". **It asserts 1, and correctly so** -- that test runs over the *fixture* layer
      set under `documents/template/examples/layers/`, not the production set under
      `documents/template/sets/rental/`. No production test pins production layer versions
      (`TemplateResolverTest`'s version assertions are fixture-driven too), which is why the
      `base.yaml` 1 -> 2 bump above required no test change. Claim corrected here; see the erratum
      in the bullet below.
  - **One proposed fix was considered and REJECTED.** Removing the now-redundant national
    `governingLawClause` from the TG witnesseth list (TG carries both it and the broader
    `tgGoverningLaw`) would recreate the stamp-clause hole one clause over: revert the statutory flag
    to optional and a TG deed would have **no** governing-law clause at all. Redundancy where one
    clause subsumes the other is untidy; a missing choice-of-law clause is dangerous. Left in place,
    documented in the YAML, and registered with the safe fix (a `replaceClause` consolidation, not a
    removal).
  - **2026-09-07: the statutory flag was flipped `optional: true -> false`** on the repo owner's
    decision, after the missing-stamp-clause hole was found (full reasoning in the decisions note
    above and in `state-TG.patch.yaml`). Until this date the code and the note disagreed, so **any
    legal sign-off obtained before 2026-09-07 was against a misdescribed product.**
  - Tests updated with it: `AgreementDocumentFormatE2EIntegrationTest`
    (`statutoryAndSignatureBlockAreBothMandatoryForTelangana`, rewritten from the opt-in version and
    now asserting the stamp/registration, 1960-Act and essential-services clauses all render with no
    add-ons selected, and that the `showWhen`-gated stamp-amount sentence still does not) and
    `ProductionRentalLayerSetTest`. **Full suite re-run 2026-09-07: 924 tests, 0 skipped, 0
    failures.**
  - **2026-09-07: all five questions are now drafted for counsel** in `docs/COUNSEL-BRIEF.md`
    (Part A), as a standalone sendable document. **It renumbers them** into legal rather than
    task order, so when counsel replies, map the answers back as: brief Q1 = licence-vs-lease
    (the fifth question below); Q2 = mandatory statutory overlay; Q3 = shared add-on clauses;
    Q4 = recital + jurisdiction placement; Q5 = Telangana heading wording. **The brief is
    drafted but NOT sent, and no counsel is engaged** -- this box stays open.
  - **A fifth question for counsel, larger than the four above and not yet asked:** the deed is
    titled "Residential Tenancy (**Leave & Licence**)" nationally. Licence vs lease changes the legal
    effect and the stamp-duty basis, and it is baked into the shared base -- so if it is wrong for a
    Telangana residential tenancy it is wrong everywhere, not only in the TG layer.
  - **Remediation: LOW STAKES for now.** Were any Telangana deeds signed while the statutory section
    was opt-in, and therefore without a stamp/registration clause? Answerable because every agreement
    pins `templateContentHash` + `layerVersions` (`Agreement.pinEffectiveTemplate`); needs a
    production query, not checkable locally. **Repo owner confirmed 2026-09-07 that prod is beta with
    founding-team users only -- no real customers -- so any affected deed is internal.** Worth
    running once for completeness; not urgent.
  - **`state:TG` layer version bumped 1 -> 2.** The owner was content either way during
    founding-team beta; bumped because it is the historically accurate choice, not merely the tidy
    one. Agreements generated before this change pin `state:TG: 1` and were rendered from the
    **opt-in** content. Had the version stayed at 1, new agreements would pin the same number
    against **mandatory** content -- two materially different Telangana deeds reporting one authored
    version, which is exactly what `Agreement.pinEffectiveTemplate` exists to prevent. Bumping makes
    the existing pins truthful in retrospect as well as labelling the new content.
    Test updated with it: `ReferenceLayerSetResolutionIntegrationTest` now asserts `state:TG -> 2`.
    (`TemplateResolverTest`'s `state:TG` version assertions are fixture-driven, not the production
    layer set, and are unaffected.)
    **ERRATUM 2026-09-10:** the `ReferenceLayerSetResolutionIntegrationTest` claim above is wrong --
    that test asserts `state:TG -> 1` and always did, because it resolves the *fixture* layer set
    under `documents/template/examples/layers/`, not the production set. No production test pins
    production layer versions. Nothing is broken by this; the note was simply untrue.
  - **Still owed before the first real customer:** wire counsel sign-off to the
    `templateContentHash`, so an unreviewed authored template cannot ship silently. The pin makes
    this possible; nothing enforces it yet.
