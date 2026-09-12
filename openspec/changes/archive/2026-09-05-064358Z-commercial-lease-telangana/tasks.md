> **Windows test notes (project memory):** run gradle directly with `TESTCONTAINERS_RYUK_DISABLED=true`
> and `-Duser.timezone=Asia/Kolkata`; write all files in pure ASCII (the PII/secret guard fails closed
> on non-ASCII -- no em-dash, arrows, or curly quotes in the YAML or Java). Integration tests use
> Testcontainers (`@Testcontainers(disabledWithoutDocker = true)`) and need a running Docker daemon;
> without Docker they skip cleanly.
>
> **Scope:** additive template content (classpath YAML) + a catalog seeder change + tests. No
> resolver, compiler, schema, dependency, or DB-migration change. Reuse the residential clause wording
> as the starting point; new commercial header; residential-only fields (BHK, furnishing, pets,
> occupants) do not carry over. Every required field is aggregate-backed OR defaulted (parity).
>
> **Approach change (requester directive):** the program must "see available templates in the
> configured folder" -- so the seeder was reworked from a hardcoded row list to **folder
> auto-discovery**: it scans `documents/template/sets/*` on the classpath and derives one catalog row
> per `(state, type)` from each set's `base.yaml` meta + its `state-<XX>.patch.yaml` overlays. Adding a
> template is now a pure folder drop -- no per-template Java edit. Test fixtures that lived under
> `sets/` (`formsection`, `optional`) were relocated to `documents/template/testsets/` so discovery
> only sees production sets (they collided on `(IN, residential)`).

## 1. Commercial base layer (`documents/template/sets/commercial/base.yaml`)

- [x] 1.1 Author `base.yaml`: `meta.id: commercial-national`, `dimensions: { state: IN, type:
  commercial }`, `version: 1`, `status: published`, and `meta.document` with
  `title: "Commercial Lease Agreement"`, a commercial `subtitle`, and an `executionLine`. Validate it
  loads and composes (schema `template-definition.schema.json`).
- [x] 1.2 Fields: declare **only** the eight aggregate-backed keys as `required: true` -- `ownerName`
  ("Lessor name"), `tenantName` ("Lessee name"), `propertyAddress` ("Demised premises address"),
  `monthlyRent` ("Monthly lease rent (INR)"), `securityDeposit`, `durationMonths` ("Lease term
  (months)"), `startDate`, `endDate`. Every commercial-specific field is `required: false` with a
  dummy default: `permittedUse` (enum, incl. commercial), `permittedBusinessUse`, `carpetAreaSqft`,
  `superBuiltUpAreaSqft`, `agreementDate`, `lockInMonths`, `noticePeriodMonths`, `rentDueDay`,
  `paymentMode`, `rentEscalationPercent`, `camBorneBy`, `camAmount`, `gstRate`, `gstBorneBy`,
  `depositMonths`, `fitOutMonths`, `signageAllowed`, `structuralRepairsBorneBy`,
  `routineRepairsBorneBy`, `disputeResolution`, `jurisdictionCity`, `specialConditions`,
  `fixturesInventory`, `witness1Name/Address`, `witness2Name/Address`.
- [x] 1.3 Clauses + sections: reuse the residential wording where it fits (term, rent, deposit,
  notice, service-of-notice, governing law, severability, entire-agreement, handover, no-subletting,
  inspection, care-of-premises) and add the commercial clauses -- permitted business use with an
  explicit **no-residential** covenant, CAM, GST on rent, lock-in, escalation, fit-out / rent-free,
  deposit-as-months, signage, structural-vs-routine repairs split, quiet enjoyment. Sections: Lessor,
  Lessee (render: parties); Schedule of Premises, Term, Financial (render: keyvalue, mandatory);
  optional Charges & Utilities, Occupancy & Use, Dispute Resolution; "Now This Agreement Witnesseth"
  (render: clauses, mandatory, zero fields); optional Annexure; mandatory "In Witness Whereof"
  (render: signatures, entries `ownerName, tenantName`); optional Witnesses.

## 2. Commercial patches (`documents/template/sets/commercial/`)

- [x] 2.1 `type-commercial.patch.yaml` -- `meta.kind: type`, `dimensions: { type: commercial }`;
  `overrideField permittedUse (required: true, default: commercial)`.
- [x] 2.2 `state-TG.patch.yaml` -- `meta.kind: state`, `dimensions: { state: TG }`; add the Telangana
  statutory fields (`stampDutyAmount`, `registrationChargesBorneBy`) + clauses (governing law, stamp/
  registration before the Sub-Registrar under the Registration Act 1908, stamp amount showWhen) and an
  **optional** "Statutory (Telangana)" section bundling them (opt-in, mirroring residential).
- [x] 2.3 `state_type-TG-commercial.patch.yaml` -- `meta.kind: state_type`, `dimensions: { state: TG,
  type: commercial }`; remove the generic national stamp clause, `replaceSection` the covenant list
  without it, `overrideField jurisdictionCity (default: Hyderabad)`, keep "In Witness Whereof"
  mandatory, and `reorderSections` to the full commercial order.

## 3. Catalog seeder -- folder auto-discovery (`documents.template.TemplateCatalogSeeder`)

- [x] 3.1 Rework the seeder to **discover** layer sets: scan `classpath*:documents/template/sets/*/
  base.yaml`, dedupe by classpath-relative root, and for each **published** base derive one catalog
  row from its national `(state, type)` plus one per `state-<XX>.patch.yaml` overlay beside it. Row
  name/description/version derive from the base's `meta.document` + `meta.version` (no drift). The
  commercial set is thereby cataloged as "Commercial Lease Agreement (National|Telangana)" with no
  per-template code -- and future templates are a pure folder drop.
- [x] 3.2 Idempotency + safety: insert only rows whose `(state, type)` is absent from the catalog
  (per-pair, re-runnable), and dedupe discovered rows first-wins per `(state, type)` so two sets can
  never produce a duplicate catalog row. Relocate the test-only fixture sets (`formsection`,
  `optional`) from `documents/template/sets/` to `documents/template/testsets/` (updating their three
  `ClasspathLayerSource` references) so discovery sees only production sets.

## 4. Unit tests (fast, no Spring context / I/O)

- [x] 4.1 **Resolution unit test** (`ProductionCommercialLayerSetTest`): resolving `(TG, commercial)`
  composes without error; the effective template's `meta.document.title` is "Commercial Lease
  Agreement"; every **required** field is aggregate-backed OR defaulted (parity -- `permittedUse` is
  required + defaulted to commercial); a residential-only key (e.g. `bhkConfiguration`, `petsAllowed`)
  is **absent**; the Telangana "Statutory (Telangana)" section is present and optional;
  `jurisdictionCity` defaults to Hyderabad.
- [x] 4.2 **Compile unit test:** compiling the resolved `(TG, commercial)` template with the eight
  mapped values (defaults filling the rest) yields HTML whose header reads "Commercial Lease
  Agreement", carries the commercial covenants (business use / no-residential, CAM, GST), the signature
  zones + `esign:owner` / `esign:tenant` anchors, and every data value HTML-escaped.
- [x] 4.3 **Seeder unit test** (`TemplateCatalogSeederTest`, mocked repo, real classpath discovery):
  an empty catalog discovers the production rows (residential IN/TG + commercial IN/TG) with no
  duplicate `(state, type)`, all published + version-derived, commercial rows pointing at the
  commercial set; a re-run with every discovered row present inserts nothing; a pre-existing
  `(state, type)` is skipped.

## 5. Integration test (Testcontainers + Spring slice)

- [x] 5.1 **Registry resolution integration test** (`TemplateCatalogRegistryIntegrationTest`, real
  Postgres via Testcontainers, `test,sandbox`): the seeder-discovered `(TG, commercial)` row resolves
  via the registry to the "Commercial Lease Agreement" document with the Telangana overlay, and to a
  content hash + provenance **identical** to a direct classpath resolution of the commercial set
  (only the lookup source differs). The pre-existing seed/pointer-integrity assertions stay green.
- [x] 5.2 **`ModularityTests` stays green** -- no new cross-module surface; `documents` holds no brand
  literal; the commercial content is classpath resources + a seed row only.

## 5b. Manual-test finding: cross-template draft leak (frontend)

- [x] 5b.1 **Bug found during manual preview of `(TG, commercial)`:** live preview returned
  `Preview failed (400)`. Root cause: the localStorage capture draft used a single **global** key
  (`am.preview.draft.v1`), so a prior **housing** draft resumed into the commercial form; shared enum
  keys whose vocabulary differs across templates (`utilitiesBorneBy`: tenant/owner vs lessee/lessor;
  `registrationChargesBorneBy`: owner/tenant vs lessor/lessee) carried an invalid value the commercial
  template's server-side validation rejects (confirmed: `utilitiesBorneBy=tenant` -> 400,
  `=lessee` -> 200). Not a backend/template defect -- a latent frontend draft-scoping bug the new
  template dimension exposed.
- [x] 5b.2 **Fix (`CaptureForm.vue`):** scope the draft key per `(state, type)`
  (`am.preview.draft.v1.<state>.<type>`) so a draft never resumes into another template; guard the
  draft merge to drop a stored value that is not a valid option for the current template's enum
  (defense-in-depth); purge the legacy global key on mount (client-side PII-at-rest hygiene). Updated
  the affected `CaptureForm.test.ts` draft-key assertions; `vue-tsc` clean; the only remaining test
  failures are the two pre-existing Save-gating tests unrelated to this change.

## 6. Wrap-up

- [x] 6.1 `./gradlew spotlessApply` green; run the `documents.*` suites + `ModularityTests`
  (`TESTCONTAINERS_RYUK_DISABLED=true`, `-Duser.timezone=Asia/Kolkata`). Confirm **no new dependency and
  no `gradle.lockfile` change** and **no DB migration**.
- [x] 6.2 Confirm the invariants: composed commercial header is "Commercial Lease Agreement"; the
  `required` set is the eight aggregate keys (generate parity holds); residential-only fields absent;
  the Telangana overlay is opt-in; the signature block is mandatory and signable
  (`esign:owner`/`esign:tenant`); every value HTML-escaped; the stateless preview persists nothing and
  stays `no-store`; nothing logged.
- [x] 6.3 Manual eyeball (optional, left to reviewer): browse the catalog, pick "Commercial Lease
  Agreement (Telangana)", preview + download a PDF on `:8090` with the compose infra up and the
  frontend on `npm run dev`.
