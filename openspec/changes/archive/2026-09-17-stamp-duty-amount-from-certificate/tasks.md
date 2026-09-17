## 1. Reproduce the bug

- [x] 1.1 Integration reproduction, `StampDutyFromCertificateIntegrationTest` (real Postgres + MinIO + Gotenberg, production `sets/rental`): a TG draft generated with no amount, paid and stamped. Assert that the stamped PDF text states the certificate duty and never contains `[ Stamp duty paid (INR) ]`.

## 2. `documents`: system-sourced fields

- [x] 2.1 Add `FieldSource` and `Field.source`, `null` for user-sourced and serialized `NON_NULL` so existing content hashes are unchanged. Keep the 8-argument constructor. Bind `source:` in `TemplateNodeBinder`, and add `"source": {"enum":["system"]}` to both JSON schemas.
- [x] 2.2 `TemplateResolver.applyOverride` carries `source` through (it is not overridable).
- [x] 2.3 `TemplateDefinitionValidator` rejects a field that is both `source: system` and `required: true`.
- [x] 2.4 `FormProjector` excludes system-sourced fields from `FormSchema`.
- [x] 2.5 `DocumentProjectionApi.generate(request, systemValues)`, a Java-only overload, not a field on the HTTP-bound request record. `generate(request)` delegates to it with no values.
- [x] 2.6 `DocumentProjectionService.withSystemValues`: on every projection, strip system-sourced keys from the submitted data, then add the server values for system-sourced keys only.
- [x] 2.7 `TemplateCompiler`: omit the key/value, party-card and annexure entries of a blank system-sourced field that declares no placeholder. Clause slots are unchanged.
- [x] 2.7a `Field.placeholder` (schema, binder, resolver pass-through, canonical form only when set). A blank value renders `[ placeholder ]` instead of `[ label ]`, and a system field that declares one keeps its row as a provision.
- [x] 2.8 `DocumentProjectionResult.executionDate` (ISO), populated by `generate`. Keep the 2-argument constructor.
- [x] 2.9 Unit tests in `SystemSourcedFieldTest`:
  - binding; the required-system field rejected; an unknown token rejected;
  - hash changes with `source`, and a user field's canonical form carries no `source`;
  - an unset row omitted with its gated clause dropped; an unset row with a placeholder shown as a provision; a supplied value renders;
  - submitted values discarded and only system keys overlaid;
  - TG rental and commercial fields system-sourced and absent from the form;
  - a TG draft shows `[ Provision for stamp duty ]`, never the old placeholder or a submitted amount;
  - a TG generate with the certificate duty states it and reports the execution date;
  - every clause slotting a system field is `showWhen`-gated on it.

## 3. Template layers

- [x] 3.1 `rental/state-TG.patch.yaml` (v2 → v3) and `commercial/state-TG.patch.yaml` (v1 → v2): mark `stampDutyAmount` as `source: system` with `placeholder: "Provision for stamp duty"`, add dated comments, and rewrite the misleading rental comment.
- [x] 3.2 Covered by `SystemSourcedFieldTest` (task 2.9) over both production layer sets. The existing `ProductionRentalLayerSetTest` and `ProductionCommercialLayerSetTest` stay green.

## 4. `signing`: record the rendered draft

- [x] 4.1 Migration `V21__draft_execution_date.sql`: nullable `agreement.draft_execution_date DATE`, forward-only, no backfill. Mapped on `Agreement`.
- [x] 4.2 Record it through `Agreement.pinEffectiveTemplate(hash, versions, date)` and `AgreementDocumentService.pinEffectiveTemplate(id, identity, executionDate)`. `AgreementController.generateDocument` passes `result.executionDate()`.
- [x] 4.3 Clear it in `Agreement.attachDraft` (every store, uploads included) and in `Agreement.clearDraftPin`.
- [x] 4.4 Unit tests:
  - `AgreementDocumentServiceTest.pinningRecordsTheDraftExecutionDateAndStoringADraftClearsIt`;
  - `AgreementControllerTest` pins with the execution date.

## 5. `signing`: re-render at stamp intake

- [x] 5.1 `AgreementDocumentService.renderForStamp(agreementId, dutyAmount)` returns `Optional<byte[]>`.
  - It re-renders only when the date and pin are recorded, a template is selected, and `TemplateFormApi` reports the pinned hash.
  - It passes the draft date as `agreementDate` when none was captured.
  - It re-checks the rendered identity.
  - It logs each fallback reason code: `NOT_A_RECORDED_RENDER`, `NO_TEMPLATE` or `PIN_DRIFT`.
  - It translates `DocumentRenderException` to `StampRenderUnavailableException`.
- [x] 5.2 `StampIntakeService.attachResolved`: after every gate and the draft-exists check, use the re-render or fall back to the stored draft. Audit a renderer outage as `REJECTED_RENDER_UNAVAILABLE`, outside the `STAMP_FAILED` branch.
- [x] 5.3 `GlobalExceptionHandler`: `StampRenderUnavailableException` maps to `503` with `urn:agreementmitra:problem:stamp-render-unavailable` and a constant detail.
- [x] 5.4 Unit tests:
  - `AgreementDocumentServiceTest`: re-render with the certificate duty and the draft date; a captured date is kept; an uploaded draft is not re-rendered; a drifted template is not; a render resolving another template is discarded; an outage is translated.
  - `StampIntakeServiceTest`: re-rendered bytes are what get stamped; a renderer outage writes nothing, does not mark `STAMP_FAILED`, does not close, and is audited.
- [x] 5.5 Integration tests in `StampDutyFromCertificateIntegrationTest`:
  - The stamped deed states the certificate duty in both the row and the clause, keeps the recorded draft execution date rather than the intake date, never prints a client-saved amount, and leaves the stored draft untouched.
  - A generated-then-uploaded draft is stamped via fallback.
  - The existing `StampIntakeApiIntegrationTest`, which uses uploaded drafts, stays green.
- [x] 5.6 `ModularityTests` stays green: `signing` uses only `documents.api` and the `documents` root exception.

## 6. Single draft with a provision (product owner, 2026-09-17)

- [x] 6.1 The paid-draft email, download endpoint and status-page button were built, then withdrawn: the owner wants one draft, sent before payment. They were removed with their tests and spec deltas (`payment-processing`, `agreement-status-view`).
- [x] 6.2 The pre-payment draft shows `Stamp duty paid (INR) [ Provision for stamp duty ]`. `StampDutyFromCertificateIntegrationTest` asserts the provision in the draft and its absence from the stamped deed.

## 7. Docs and close-out

- [x] 7.1 `docs/COUNSEL-BRIEF.md` Annexure B: the amount comes from the attached certificate, and the emailed draft differs from the executed instrument by that line. The question for counsel is recorded.
- [x] 7.2 `docs/ROADMAP.md`: the bug row is updated to "fix implemented, awaiting review and archive" (delete it when this archives). Follow-ups registered: `draft-upload-clears-template-pin` and `document-stamping-spec-header-drift`. `docs/LEGAL-POSTURE.md` records the retirement of the free-text field.
- [x] 7.3 Full `./run-tests.sh` (`check`, all gates) green, with the wall-clock time reported against the 3-minute budget; `openspec validate stamp-duty-amount-from-certificate --strict` passes.
  - Run 2 (2026-09-17): 1,337 tests green and the JaCoCo gate passed in 11m 22s.
  - `osvScan` could not run because `osv-scanner` was not on the shell PATH (a local environment issue). Re-run with it on PATH, `securityScan` (OSV + SpotBugs) passed in 61s.
  - The wall-clock time is ~4x the 3-minute budget. The earlier run of the same suite before the slice-test fix took 10m 10s, so this change is not the main cause. It does add one Gotenberg-backed test class, likely with its own Spring context: a small, unmeasured addition. Raise the budget overrun with the team.
  - Run 4 (2026-09-17, single draft with provision): `check` green in 13m 25s, with every gate run: tests, JaCoCo, `osvScan` (no issues) and SpotBugs. That is ~4.5x the 3-minute budget, and the time varies widely between runs on this machine (7-13 min); raise it with the team.
- [x] 7.4 Product-owner review of the change, then commit and `openspec archive -y stamp-duty-amount-from-certificate`.
