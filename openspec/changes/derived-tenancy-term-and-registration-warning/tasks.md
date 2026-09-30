## 1. Backend: the `derived` field source

- [x] 1.1 Add `DERIVED` to `FieldSource` and extend `from(String)` to parse the `derived` token; update the enum javadoc so it describes three provenances (user / system / derived) and states that `derived` means "the server computes it, show it but do not ask for it"
- [x] 1.2 Add a `derived()` accessor to `Field` beside `systemSourced()`
- [x] 1.3 Add `"derived"` to the `source` enum in `template-definition.schema.json` and `layer-patch.schema.json`
- [x] 1.4 Relax `TemplateDefinitionValidator`: keep rejecting `system` + `required`; allow `derived` + `required` (the projector normalizes requiredness -- see 2.1)
- [x] 1.5 Confirm `TemplateNodeBinder` needs no change beyond `FieldSource.from` accepting the new token, and that an absent `source` still normalizes to `null` so unrelated templates keep their content hash

## 2. Backend: project a derived field read-only

- [x] 2.1 In `FormProjector`, project a derived field instead of skipping it, marking it read-only and forcing `required: false`; leave the `systemSourced()` skip exactly as it is
- [x] 2.2 Add the read-only marker to the `FormField` API DTO in `documents/api`, emitted only when true so existing schema payloads are byte-identical

## 3. Backend: derive the tenancy term in document projection

- [x] 3.1 Add a package-private whole-month helper in `documents/template` (whole months, end-exclusive, trailing partial month truncated -- `Period.between(...).toTotalMonths()` semantics)
- [x] 3.2 In `DocumentProjectionService`, substitute a derived `durationMonths` computed from the submitted `startDate`/`endDate` into the data map on every render path, discarding any submitted value unconditionally; leave `durationMonths` unset when either date is absent or unparseable
- [x] 3.3 Verify the substitution runs before validation and compilation, and alongside (not instead of) the existing `withSystemValues` substitution
- [x] 3.4 Confirm nothing is logged by the derivation, per the existing never-log-submitted-values requirement

## 4. Backend: template sets

- [x] 4.1 In `sets/rental/base.yaml`, change `durationMonths` to `source: derived` and drop `required: true`; bump `meta.version` 2 -> 3 with a comment recording that the rendered term now follows the dates
- [x] 4.2 In `sets/commercial/base.yaml`, apply the same change to `durationMonths` ("Lease term (months)"); bump `meta.version` 1 -> 2 with the same rationale comment
- [x] 4.3 Confirm `termClause` / the commercial term clause still resolve their `{{durationMonths}}` slot, since the field remains declared

## 5. Backend tests

- [x] 5.1 Unit: `FieldSource` parses `derived`; `Field.derived()` is true only for it; an absent source still yields `null`
- [x] 5.2 Unit: `TemplateDefinitionValidator` accepts `derived` + `required` and still rejects `system` + `required`
- [x] 5.3 Unit (`FormProjectorTest`): a derived field is present in the schema, marked read-only, with `required: false`; a system-sourced field is still absent; a template with neither projects identically to before
- [x] 5.4 Unit: the whole-month helper over a boundary table -- 11 vs 12 months, 2026-01-08 to 2028-01-08 = 24, 2026-01-01 to 2026-12-01 = 11, 31 Jan to 28 Feb, a leap-year span, and a trailing partial month. **Keep this table identical to the frontend table in 8.2**
- [x] 5.5 Unit (`DocumentProjectionServiceTest`): a submitted `durationMonths` disagreeing with the dates is discarded; a submitted value agreeing with them is still substituted; a missing end date leaves the term unset
- [x] 5.6 Integration (`DocumentProjectionApiIntegrationTest`): a preview POST with dates spanning 24 months and a submitted `durationMonths` of 11 renders a document stating 24 months
- [x] 5.7 Integration (`TemplateFormApiIntegrationTest`): `GET /api/templates/form` for the rental and commercial dimensions returns a `durationMonths` field marked read-only and not required
- [x] 5.8 Integration: the preview path and the generate path render the same term for the same dates and data (the parity case this change exists to close)
- [x] 5.9 Update the production layer-set assertions (`ProductionRentalLayerSetTest`, `ProductionCommercialLayerSetTest`) for the new source and version numbers
- [x] 5.10 Keep `ModularityTests` green -- no new cross-module reach (the derivation stays inside `documents`)

## 6. Frontend: schema type and read-only rendering

- [x] 6.1 Add the read-only marker to `FormField` in `api/templateForm.ts`, mirroring the backend DTO
- [x] 6.2 Render a read-only field as a non-editable displayed value in `FieldWidget.vue`, with no keyboard entry
- [x] 6.3 In `CaptureForm.vue`, show the derived duration from the captured dates, and show "not yet determined" while either date is missing
- [x] 6.4 Exclude read-only fields from the values `saveSection` commits and from `isSectionComplete`, so a derived field can never block completeness or reach `captureData`

## 7. Frontend: cross-field validation and the registration warning

- [x] 7.1 Add `tenancyMonths(start, end)` to `formModel.ts` -- whole months, end-exclusive, truncating; compare year/month/day components directly rather than mutating a `Date`, so 31 Jan does not overflow to 3 March
- [x] 7.2 Add `sectionErrors(fields, data)` composing `fieldErrors(...)` with cross-field rules, leaving `validateField` per-field and pure; attach the end-on-or-before-start error to the end date field
- [x] 7.3 Bind the section modal to `sectionErrors` instead of `fieldErrors`
- [x] 7.4 Add the registration warning to the Term section when the derived term exceeds 11 months: advisory only, non-blocking, stating that registration with the Sub-Registrar is compulsory and is not included in the stamp duty quoted, and restating no captured value other than the term
- [x] 7.5 Confirm the existing stamp-quote registration notice in `StampQuoteStep.vue` is left untouched

## 8. Frontend tests

- [x] 8.1 Unit (`formModel.test.ts`): `sectionErrors` reports end-before-start and end-equals-start, accepts a valid range, and still reports every per-field error `fieldErrors` did
- [x] 8.2 Unit (`formModel.test.ts`): `tenancyMonths` over the boundary table from 5.4 -- **the two tables must match case for case**
- [x] 8.3 Unit: a read-only field is excluded from committed values and from completeness
- [x] 8.4 Component (`CaptureForm.test.ts`): opening Term with a 24-month date span shows the duration as 24, not the template default, and the duration input cannot be edited
- [x] 8.5 Component (`CaptureForm.test.ts`): the registration warning appears at 24 months, is absent at exactly 11 months, and does not prevent saving the section
- [x] 8.6 Component (`CaptureForm.test.ts`): the duration shows as undetermined when only the start date is set

## 9. Verification and close-out

- [x] 9.1 Run the backend suite via `./run-tests.sh` and report the elapsed wall-clock time against the 3-minute budget
- [x] 9.2 Run `npm run lint` and the frontend tests from `frontend/`
- [x] 9.3 Manually verify the reported case: start 2026-01-08, end 2028-01-08 shows 24 months, warns, and previews a deed stating 24 months that matches the generated draft
  - Verified 2026-09-18 against the running local stack: `POST /api/templates/document/preview` (TG, residential) with a submitted `durationMonths: 11` and those dates renders **"term of 24 month(s)"**; `GET /api/templates/form` for TG/KA residential and TG commercial serves `durationMonths` as `readOnly: true, required: false` with no default; `POST /api/agreements` returns a server-derived `durationMonths: 24` and strips the submitted 11 from `captureData`; the stateless preview-PDF leg renders a valid 53 KB PDF through real Gotenberg/Chromium.
  - **Not verified locally:** the stored-draft `POST /api/agreements/{id}/document` leg returns 500 at `Failed to ensure bucket agreements` -- `docker-compose.yml` pins MinIO as `minio/minio:latest`, which has drifted to `RELEASE.2025-09-07` and no longer answers minio-java 8.6.0's bucket calls with XML, while the test harness pins `RELEASE.2023-09-04`. Pre-existing local-environment drift, unrelated to this change (nothing here touches storage). Preview/generate term parity is covered instead by `DerivedFieldTest.previewAndGenerateStateTheSameTerm`.
- [x] 9.4 Add the state-aware-threshold follow-up row to the `## Follow-up register` in `docs/ROADMAP.md`, naming the TG `requiredWhenTermMonthsOver: 0` rules that a hard-coded 11 under-warns
- [x] 9.5 Run `openspec validate --strict derived-tenancy-term-and-registration-warning`
