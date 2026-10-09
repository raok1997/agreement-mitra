## 1. Templates

- [x] 1.1 `sets/rental/base.yaml`: add field `subletting` (enum, label `Sub-letting`, `required: true`, no `default`, options `[ with_owner_consent, not_allowed, allowed ]`) to the Term field block and to the `Term` section's entries right after `noticePeriodMonths`; replace `noSublettingClause` with `sublettingWithConsentClause` / `sublettingProhibitedClause` / `sublettingPermittedClause` (design D2 text and `showWhen`s) in `clauses:` and in the witnesseth entries right after `careOfPremisesClause`; bump `meta.version` 4 -> 5 with a v5 comment; update the header PARITY CONTRACT comment to the three-category rule (design D3)
- [x] 1.2 `sets/commercial/base.yaml`: same as 1.1 with Lessor/Lessee wording (field after the last Term field, before the Term clauses); bump `meta.version` 3 -> 4 with a v4 comment; update its parity comment
- [x] 1.3 Swap `noSublettingClause` for the three clause ids (same position) in the witnesseth `replaceSection` of `state_type-TG-residential`, `state_type-KA-residential`, `state_type-TG-commercial`, `state_type-KA-commercial`
- [x] 1.4 Grep the repo for `noSublettingClause`, "no-subletting" and prose restating "only aggregate-backed or defaulted fields are required" (mapper javadoc, layer-set test helpers, set headers); update current-state prose to the new rule, but leave dated history notes (e.g. the v2 lease-character note at `rental/base.yaml:36`, `ProductionRentalLayerSetTest:236`) as written

## 2. Parity guard: one helper, one rule (design D3)

- [x] 2.1 Create the shared test-support helper in `backend/src/test/java/in/agreementmitra/support/` (public `documents.api` types only): `AGGREGATE_KEYS` (MUST NOT include `subletting`), `USER_ANSWERED` allowlist (`subletting`), `USER_ANSWERS` (one valid answer per allowlisted key), `withUserAnswers(Map<String,Object>)` (copy + merge the answers -- each test keeps its own deliberately-seeded data), and `violations(FormSchema schema, Set<String> requiredUndefaultedKeys, Set<String> aggregateKeys)` returning (key, reason) for each key in `requiredUndefaultedKeys` that is not in `aggregateKeys` and is (a) not in `USER_ANSWERED`, (b) not projected as a `required` field in an `optional=false` section, or (c) projected in no section. `requiredUndefaultedKeys` = fields that are required, have no default, and are neither derived nor system-sourced -- computed by the caller — *done as `support/TemplateParity`; `Violation(key, reason)`; a key failing two conditions yields two violations*
- [x] 2.2 Documents side (`in.agreementmitra.documents.template` tests): `ProductionRentalLayerSetTest` and `ProductionCommercialLayerSetTest` replace their inline parity predicates with `violations(new FormProjector().project(eff), <filtered eff.template().fields()>, AGGREGATE_KEYS)` asserted empty; `ProductionKarnatakaLayerSetsTest` gains the same assertion (it has only a compile check today). Keep rental's reverse check (every aggregate key except `durationMonths` is required). Each local aggregate-data map (`ProductionRental`/`Commercial`/`KarnatakaLayerSet`, `SystemSourcedFieldTest`, `DerivedFieldTest`) asserts its `keySet()` equals `AGGREGATE_KEYS` instead of being the authority — *`DerivedFieldTest`'s map omits the dates by design, so it asserts `isSubsetOf(AGGREGATE_KEYS)`; documents-side callers share `SublettingCovenants.parityViolations`*
- [x] 2.3 Unit (in package `in.agreementmitra.documents.template`, since resolving a fixture set needs the package-private resolver/projector): helper test over a new fixture set `src/test/resources/documents/template/testsets/useranswered/` proving `violations` names a required undefaulted field placed in an optional section, one listed in no section, and one in a mandatory section but missing from the allowlist -- and returns empty for an allowlisted field in a mandatory section — *`TemplateParityTest` over `testsets/useranswered/`*
- [x] 2.4 Integration: `AggregateBackedRequiredFieldsGuardIntegrationTest` asserts `AGGREGATE_KEYS` equals `AgreementDocumentMapper.toTemplateData(anAgreement()).keySet()` (keeps the helper's copy tied to the real mapper), then asserts `violations(schema, <schema fields required with no default>, mapperKeys)` is empty for every published `FormSchema`. Its required set comes from the projected schema, so branch (c) cannot fire here -- only the documents side enforces it; say so in the javadoc

## 3. Tests for the sub-letting choice

- [x] 3.1 Unit (`ProductionRentalLayerSetTest` for IN/TG, `ProductionKarnatakaLayerSetsTest` for KA): `subletting` is in `Term`, required, no default, exactly the three options; for every option the field **declares** (iterate `options()`, not a literal list), the compiled deed holds exactly one sub-letting covenant with that option's text and the humanised terms-table cell; each witnesseth list holds all three ids and no `noSublettingClause` — *shared assertions in `SublettingCovenants`; the Term/required/no-default/options check runs IN/TG/KA in `ProductionRentalLayerSetTest`*
- [x] 3.2 Unit (`ProductionCommercialLayerSetTest` for IN/TG, `ProductionKarnatakaLayerSetsTest` for KA commercial): same per declared option, asserting Lessor/Lessee wording
- [x] 3.3 Unit: PREVIEW with `subletting` blank renders `[ Sub-letting ]` and no covenant; GENERATE without it fails with `FieldErrorDetail(subletting, required)`; `subletting = "Allowed"` fails with `enum` in both modes and compiles no covenant
- [x] 3.4 Every GENERATE validation against a **production** set wraps its existing data in `withUserAnswers(...)` -- except tests that assert the refusal. Files: `ProductionRentalLayerSetTest`, `ProductionCommercialLayerSetTest` (incl. the commercial `permittedUse` default test), `ProductionKarnatakaLayerSetsTest` (incl. `compileWithNoOptionalSections`), `SystemSourcedFieldTest`, `DerivedFieldTest`. Find call sites by grep, not by line number. `StampDutyFromCertificateIntegrationTest` generates over HTTP: add `"captureData", Map.of("subletting", "with_owner_consent")` to the create body in `telanganaAgreement()`. `DocumentProjectionServiceTest` runs on the fixture set -- leave it. Move version assertions to 5 / 4 (`ProductionRentalLayerSetTest`, `ProductionCommercialLayerSetTest`, `TemplateCatalogSeederTest`, `TemplateCatalogRegistryIntegrationTest`)
- [x] 3.5 Integration (`AgreementDocumentFormatE2EIntegrationTest`, sandbox), per the `agreement-management` / `agreement-preview` deltas: the no-capture agreement (:451) now gets `400` with `subletting:required` on generate and on the id-bound GET preview; the blank-party case (:464, :546-553) expects `ownerFatherName`, `ownerAddress` **and** `subletting`; the capture case (:490) adds `subletting` and also asserts the party father's name and address reach the draft (the only end-to-end proof once :451 flips); new: capture `subletting=not_allowed` generates a draft carrying the not-allowed covenant and records the pin; a **claimed** agreement with no `subletting`, requested by a different identity, gets `404` with no `errors[]` on both `GET /{id}/preview` and `POST /{id}/document`; `POST /api/agreements/preview` no longer resolves (closes a pre-existing untested baseline scenario) — *the retired route answers 403 (SecurityConfig `denyAll()` on an unmatched route), so the test accepts 403/404/405*
- [x] 3.6 Frontend unit (`formModel.test.ts` / `CaptureForm.test.ts`): a required enum with no default renders "Select...", leaves its section incomplete and keeps Save & continue disabled until an option is chosen — *`CaptureForm.test.ts`*

## 4. Docs

- [x] 4.1 `docs/COUNSEL-BRIEF.md` Annexure A: keep number 18 (clauses 21/22/24 are cross-referenced by number) and move it under its own heading, "Sub-letting (always appears, in one of three forms chosen by the user)", listing the three final wordings in the style of item 15; no new question
- [x] 4.2 `docs/ROADMAP.md`: append one sentence to the "v1 is residential only" paragraph -- when commercial returns, consider a commercial "allowed to group companies / affiliates" sub-letting option (raised by `subletting-choice`); append to the `draft-freeze-lock-vs-finalise` row that generate's render/attach/pin run in three transactions, so an edit between them can pin a draft rendered from the previous capture -- now able to flip the sub-letting covenant, and if the edit nulled the capture state the stamp re-render fails closed with `subletting: required` (uncaught in `renderForStamp`) instead of compositing
- [x] 4.3 Root-cause guard for the silent-drop risk (design, Risks): `ShowWhenValidator` rejects at load an `enum` compared with a string literal that is not a declared option (`template-resolution` delta); unit cases in `ShowWhenDslTest`. Added at apply after validate flagged the code running ahead of its spec

## 5. Gates

- [x] 5.1 `./run-tests.sh check` from `backend/` green -- report wall-clock; over the 3-minute budget, raise it — *1832 tests, 0 failed, 81 s; osvScan needed the separate GHSA-j9f9-w8pj-32f8 suppression change set*
- [x] 5.2 `npm run build` and `npm run lint` from `frontend/` green

## Coverage

| # | Capability · Scenario | Disposition | Where |
|---|---|---|---|
| 1 | rental-agreement-document · Each option renders its own covenant and only that one | COVERED | 3.1 |
| 2 | rental-agreement-document · A value outside the options is rejected | COVERED | 3.3 |
| 3 | rental-agreement-document · The sub-letting choice is a required capture field with no default | COVERED | 3.1 (commercial twin in `ProductionCommercialLayerSetTest` / KA test) |
| 4 | rental-agreement-document · A blank choice previews with a placeholder and no covenant | COVERED | 3.3 |
| 5 | rental-agreement-document · Generate refuses a blank choice | COVERED | 3.3 (unit), 3.5 (HTTP) |
| 6 | rental-agreement-document · Every overlay lists the three covenants and none dangles | COVERED | 3.1 |
| 7 | rental-agreement-document · The capture form blocks Save and continue until the choice is made | COVERED | 3.6 |
| 8 | rental-agreement-document · The witnesseth section is field-less and rendered | GROUPED | existing `ProductionRentalLayerSetTest` witnesseth assertions, kept green by 5.1 |
| 9 | rental-agreement-document · The field-less witnesseth section is omitted from the capture form | GROUPED | existing `ProductionRentalLayerSetTest` form-projection assertion, kept green by 5.1 |
| 10 | rental-agreement-document · No clause dangles after the covenants move | GROUPED | #6 · 3.1 |
| 11 | rental-agreement-document · Only aggregate-backed, defaulted or user-answered fields are required | COVERED | 2.2, 2.4 |
| 12 | rental-agreement-document · A required undefaulted field outside a mandatory capture section is rejected | COVERED | 2.3 |
| 13 | rental-agreement-document · A required undefaulted field missing from the allowlist is rejected | COVERED | 2.3 |
| 14 | rental-agreement-document · The party father's name and address are required in the capture form | GROUPED | existing parity test (unchanged assertion), kept green by 5.1 |
| 15 | rental-agreement-document · The capture form does not report a party section complete while a party field is blank | GROUPED | existing `formModel.test.ts` (unchanged), kept green by 5.2 |
| 16 | rental-agreement-document · The mapper supplies the party father's name and address from the aggregate | GROUPED | existing `AgreementDocumentMapper` test (unchanged), kept green by 5.1 |
| 17 | rental-agreement-document · A generate projection with the aggregate keys and the user answers validates and compiles | COVERED | 3.4 |
| 18 | rental-agreement-document · Moving a field into an optional add-on keeps it non-required | GROUPED | #11 · 2.2 |
| 19 | template-document-projection · The commercial document declares the commercial header | GROUPED | existing `ProductionCommercialLayerSetTest` (unchanged), 5.1 |
| 20 | template-document-projection · Telangana overlays the commercial structure | GROUPED | existing `ProductionCommercialLayerSetTest` (unchanged), 5.1 |
| 21 | template-document-projection · Karnataka overlays the commercial structure | GROUPED | existing `ProductionKarnatakaLayerSetsTest` (unchanged), 5.1 |
| 22 | template-document-projection · Karnataka is served as a commercial dimension | GROUPED | existing `TemplateCatalogRegistryIntegrationTest` (unchanged), 5.1 |
| 23 | template-document-projection · The execution block renders for the commercial line too | GROUPED | existing `ProductionCommercialLayerSetTest.compilesToTheArtifactLayoutInvariantsForBothDimensions`, 5.1 |
| 24 | template-document-projection · The commercial deed carries the chosen sub-letting covenant in Lessor/Lessee terms | COVERED | 3.2 |
| 25 | template-document-projection · Every required commercial field is aggregate-backed, defaulted or user-answered | COVERED | 2.2, 2.4 |
| 26 | template-document-projection · A generated draft carries the commercial use covenant without user input | COVERED | 3.4 (`ProductionCommercialLayerSetTest` :250) |
| 27 | agreement-management · The stored draft renders the added optional sections | GROUPED | existing `AgreementDocumentFormatE2EIntegrationTest` capture case, updated in 3.5 |
| 28 | agreement-management · A legacy agreement with no capture state is refused for the missing sub-letting answer | COVERED | 3.5 |
| 29 | agreement-management · A legacy agreement with blank party details fails generate with field errors | COVERED | 3.5 |
| 30 | agreement-management · An agreement whose capture state carries the sub-letting answer generates | COVERED | 3.5 |
| 31 | agreement-management · Create stores the capture state | GROUPED | existing `AgreementCapturePersistenceIntegrationTest` (unchanged), 5.1 |
| 32 | agreement-management · A capture map cannot set server-managed fields | GROUPED | existing `AgreementCapturePersistenceIntegrationTest` (unchanged), 5.1 |
| 33 | agreement-management · A capture map cannot override a party's father's name or address | GROUPED | existing E2E/mapper test (unchanged), 5.1 |
| 34 | agreement-management · An API client sending only fixed fields persists but cannot generate | GROUPED | #28 · 3.5 |
| 35 | agreement-preview · The id-bound preview still returns the filled document inline | COVERED | 3.5 (capture case) |
| 36 | agreement-preview · The id-bound preview of an agreement with blank party details is refused | COVERED | 3.5 |
| 37 | agreement-preview · The id-bound preview of an agreement with no sub-letting answer is refused | COVERED | 3.5 |
| 38 | agreement-preview · The stateless preview renders an unsaved working set | GROUPED | existing stateless preview test (unchanged), 5.1 |
| 39 | agreement-preview · The old stateless preview route is gone | COVERED | 3.5 (no test existed; added) |
| 40 | agreement-preview · Preview of an unknown agreement is not found | GROUPED | existing test (unchanged), 5.1 |
| 41 | template-resolution · An enum compared with a literal that is not one of its options fails resolution | COVERED | 4.3 (`ShowWhenDslTest.validationRejectsAnEnumComparedWithALiteralThatIsNotAnOption`) |
