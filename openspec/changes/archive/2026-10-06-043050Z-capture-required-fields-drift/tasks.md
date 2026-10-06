Order matters: do §1 (mapper) before §2 (templates). Flipping the templates first fails every GENERATE
test at once and makes the failures hard to triage.

## 1. Mapper — make the party fields aggregate-backed

- [x] 1.1 In `signing/agreement/AgreementDocumentMapper.java`, replace `firstNameByRole` with one
      `firstSignerByRole(agreement, role)` returning the `Signer` (or null), and emit `ownerFatherName` /
      `ownerAddress` / `tenantFatherName` / `tenantAddress` from it next to the names (raw value — `''`
      for a legacy row, null when the role is absent; D4). Rewrite the class javadoc: twelve keys, and the
      party fields now come from the aggregate (drop the stale "filled from system-authored defaults"
      claim for them); keep the "no entity or PII type crosses to `documents`" invariant.
- [x] 1.2 Unit: `AgreementDocumentMapperTest` — change `containsOnlyKeys(...)` to the twelve keys and
      assert the stored father's name / address values; `missingRoleMapsToNullName` asserts all three
      tenant keys are null.
- [x] 1.3 Unit: `AgreementDocumentServiceTest` (extend `rendersFromTheStoredCaptureStateWithFixedColumnsWinning`, ~l.134) — a stored capture map holding a different
      `ownerFatherName` / `ownerAddress` is overridden by the signer columns in the projection request
      (captor on `DocumentProjectionRequest`).

## 2. Templates — mark the four fields required, bump versions

- [x] 2.1 `documents/template/sets/rental/base.yaml`: `required: true` on `ownerFatherName`, `ownerAddress`,
      `tenantFatherName`, `tenantAddress`; bump `meta.version` 3 → 4 with a version-history comment
      (D5); update the PARITY CONTRACT header comment (eight → twelve keys).
- [x] 2.2 `documents/template/sets/commercial/base.yaml`: the same four keys `required: true`; bump
      `meta.version` 2 → 3 with a history comment; update its PARITY CONTRACT header comment (D2, D5).
- [x] 2.3 Unit: `ProductionRentalLayerSetTest` — add the four keys to the shared `aggregateBackedData()`
      helper (used by ~11 tests, including the Karnataka resolve); update the base-version pin to 4.
- [x] 2.4 Unit: `ProductionRentalLayerSetTest` — for IN, TG and KA residential, assert every required
      field is aggregate-backed or defaulted (subset, not equality — `durationMonths` is derived/optional,
      `permittedUse` is required+default) and every aggregate-backed key except `durationMonths` —
      including the four party fields — is `required` true; in
      `mandatoryAndOptionalFlagsAndRenderKindsAreDeclaredPerSection`, assert `Schedule of Property` holds
      `carpetAreaSqft` / `furnishingStatus` as `required` false.
- [x] 2.5 Unit: `ProductionCommercialLayerSetTest` — the key lists (~l.125, l.148-157) gain the four keys;
      "eight" → "twelve" in the javadoc/comments; base-version pin (l.102) → 3.
- [x] 2.6 Run `./gradlew test` and fix the remaining fallout — never by relaxing a required assertion.
      Expected: `ProductionKarnatakaLayerSetsTest` (its own eight-key helper, GENERATE at ~l.168/188);
      eight-key helper copies in `SystemSourcedFieldTest` (~l.273) and `DerivedFieldTest` (~l.375) if
      they target a production set; version pins in `TemplateCatalogSeederTest` (~l.62-73),
      `TemplateCatalogRegistryIntegrationTest` (~l.54-57) and `AgreementTest` (~l.126, if it pins the
      production base rather than a fixture); `StampDutyFromCertificateIntegrationTest` (sandbox).
      Plain-`test`-profile suites resolve the fixture set and should be unaffected.

## 3. Integration (all in `test,sandbox` suites — the plain `test` profile resolves the fixture set)

- [x] 3.1 Integration: `AgreementDocumentFormatE2EIntegrationTest` — the served `(TG, residential)` form
      schema marks the four party fields `required` true.
- [x] 3.2 Integration: same suite — an agreement created anonymously through the API with no
      `captureData`, `state: TG, type: residential`, and **distinct sentinel** father's names / addresses
      (not substrings of the property address), generates a draft whose recital carries them; assert on
      whitespace-normalised PDF text, as `AgreementPreviewIntegrationTest` does.
- [x] 3.3 Integration: same suite — on such an agreement, seed the owner's `father_name` /
      `current_address` to `''` via `JdbcTemplate` (`signer` table, `role = 'OWNER'`). Generate returns
      `400` with `errors[]` exactly `{ownerFatherName: required, ownerAddress: required}` (no tenant keys),
      and the body contains none of the agreement's other party values (owner name, tenant father's name
      / address); `draft_pdf_key`, `template_content_hash`, `template_layer_versions` and
      `draft_execution_date` stay null. `GET /api/agreements/{id}/preview` returns the same `400`.
- [x] 3.4 Integration: same suite — a create whose `captureData.ownerFatherName` differs from the owner
      signer's `fatherName` generates a draft carrying the signer's value and not the map value.
- [x] 3.5 Integration (guard): a `@SpringBootTest` + `@ActiveProfiles({"test","sandbox"})` test in
      `signing.agreement` that, for each published `(IN|TG|KA) × residential` and `(TG|KA) × commercial`
      form schema from the public `TemplateFormApi.formFor`, asserts every `required` field with no
      `defaultValue` is a key `AgreementDocumentMapper.toTemplateData` emits (design Risks).

## 4. Docs + gates

- [x] 4.1 Stale comments: `GlobalExceptionHandler.handleDocumentDataInvalid` "Passive today: no endpoint
      raises this yet"; `AgreementDocumentService.renderPreview` javadoc "a persisted agreement always
      carries its required fields" (now false for blank-party legacy rows).
- [x] 4.2 Delete the `capture-required-fields-drift` row from the `## Follow-up register` in
      `docs/ROADMAP.md` and its entry in the release sequencing list ("Small fixes as direct commits").
- [x] 4.3 `./run-tests.sh check` green (incl. `ModularityTests`); `npm run build` (chains vitest) +
      `npm run lint` in `frontend/` green — no frontend code change expected.

## Coverage

| Scenario | Disposition | By |
|---|---|---|
| **rental-agreement-document** | | |
| The mandatory sections are marked non-optional | COVERED | existing `mandatoryAndOptionalFlagsAndRenderKindsAreDeclaredPerSection`, kept green in 2.6 |
| A mandatory section may hold optional fields | COVERED | 2.4 |
| Only aggregate-backed or defaulted fields are required | COVERED | 2.4 + 3.5 |
| The party father's name and address are required in the capture form | COVERED | 2.4 (resolved set) + 3.1 (served schema) |
| The capture form does not report a party section complete while a party field is blank | GROUPED | existing `formModel.test.ts` "derives section completeness from required fields only" (l.152) over the flag asserted in 3.1 |
| The mapper supplies the party father's name and address from the aggregate | COVERED | 1.2 + 1.3 |
| A generate projection with only aggregate keys validates and compiles | COVERED | 2.3 (`generateParityHoldsWithOnlyTheAggregateBackedDataForBothDimensions`) |
| Moving a field into an optional add-on keeps it non-required | GROUPED | "Only aggregate-backed or defaulted fields are required" — 2.4 |
| **agreement-management** | | |
| The stored draft renders the added optional sections | COVERED | existing generate-from-capture-state test, kept green in 2.6 |
| A legacy agreement with no capture state renders from its fixed columns | COVERED | 3.2 |
| A legacy agreement with blank party details fails generate with field errors | COVERED | 3.3 |
| Create stores the capture state | COVERED | existing create-with-capture test, kept green in 2.6 |
| A capture map cannot set server-managed fields | COVERED | existing `AgreementServiceTest` hostile-map test (~l.175), kept green in 2.6 |
| A capture map cannot override a party's father's name or address | COVERED | 3.4 (+ 1.3 unit) |
| An API client sending only fixed fields is unaffected | GROUPED | 3.2 |
| **agreement-preview** | | |
| The id-bound preview still returns the filled document inline | COVERED | existing `AgreementPreviewIntegrationTest`, kept green in 2.6 |
| The id-bound preview of an agreement with blank party details is refused | COVERED | 3.3 |
| The stateless preview renders an unsaved working set | COVERED | existing, unchanged requirement text — kept green in 2.6 |
| The old stateless preview route is gone | COVERED | existing, kept green in 2.6 |
| Preview of an unknown agreement is not found | COVERED | existing, kept green in 2.6 |
