Order: groups 1–2 before 3; 3 before 4; 1.4 (URN) before 5. Integration tests that delete a draft with an
intake audit row need 2.1. Coordination: `byo-document-upload` still plans `V22__byo_document.sql`, already
taken — whichever lands second renumbers; that change must add its `uploads/` and `converted/` keys to
`DraftService.draftStageKeys` (design D6).

## 1. Rule, storage and seams

- [x] 1.1 Add `void delete(String key)` to `signing/BlobStore.java` (idempotent — a missing key is not an error) and implement it in `signing/storage/MinioBlobStore.java` via `removeObject`, wrapping failures as `IllegalStateException` with `AgreementIds.redactIn(key)` exactly like `put`/`get`
- [x] 1.2 Add `signing/PaymentOrderQuery.java` (public root seam, `boolean existsForAgreement(UUID)`) and a package-private adapter in `signing/payment/` over `PaymentOrderRepository.countByAgreementId` (design D4)
- [x] 1.3 Add `Agreement.isDeletableDraft(boolean hasSigningRequest, boolean hasPaymentOrder)` (package-private, pure; design D3) and `DraftService.draftStageKeys(UUID)` — the single list of draft-stage object keys (today `drafts/{id}.pdf`), whose draft key `attachDraft` also uses (design D6)
- [x] 1.4 Add `ConflictException.draftNotDeletable()` (new `Kind.DRAFT_NOT_DELETABLE`) and its `case` in `GlobalExceptionHandler.handleConflict` → `409` `urn:agreementmitra:problem:draft-not-deletable`

## 2. Migration

- [x] 2.1 Add `V23__draft_deletion.sql`: (a) create `agreement_deletion` (`agreement_id UUID PRIMARY KEY`, `tracking_reference VARCHAR(16) NOT NULL`, `owner_identity_id UUID NOT NULL`, `deleted_at TIMESTAMPTZ NOT NULL`; index on `tracking_reference`; no FKs) with a header stating: no party data but pseudonymous (owner identity + emailed reference), purpose, readers, retention pending counsel, why there are no FKs, that an account erasure must clear `owner_identity_id` here, and that reference lookups must also check `agreement` (design D10); (b) drop `stamp_intake_audit_agreement_id_fkey` and re-add it under the same name with `ON DELETE SET NULL`, plus `COMMENT ON COLUMN stamp_intake_audit.agreement_id` stating NULL means "resolved to nothing" or "agreement later deleted — join the trimmed, upper-cased `submitted_reference` to `agreement_deletion.tracking_reference`"; header explains why `recovery_audit` is untouched (design D7)

## 3. Delete service, list flag and endpoint

- [x] 3.1 Add `DraftService.deleteDraft(UUID agreementId, UUID callerIdentityId)` (`@Transactional`): `findByIdForDelete` (native `FOR UPDATE`, design D5) → null-safe strict owner check (404, same message as `update`) → `isDeletableDraft(signingRequestQuery…, paymentOrderQuery…)` else `draftNotDeletable` → `repository.delete` → save an `AgreementDeletion` (new package-private entity implementing `Persistable<UUID>` with `isNew = true`, + repository, in `signing.agreement`; design D10) → when synchronization is active register `afterCommit`, else delete inline with a warning; either way delete every `draftStageKeys(id)` key in try/catch, warn-logging the redacted id and the cause's simple class name, never the throwable (design D2, D3, D6); add a debug-level redacted "Draft deleted" line like `attachDraft`'s; update the two direct constructions (`DraftServiceTest:38`, `AgreementIdExceptionMessageTest:38`)
- [x] 3.2 Add `deletable` to `AgreementSummaryResponse` and compute it in `AgreementService.toSummary` via `isDeletableDraft`, taking `hasSigningRequest` from the existing `currentStatusForAgreement(...)` Optional (`isPresent()`) and calling `PaymentOrderQuery` only for a row with no signing request that is `UNPAID` and `OPEN` (design D3); inject `PaymentOrderQuery` into `AgreementService` and add `@Mock PaymentOrderQuery` to `AgreementServiceTest` and `AgreementIdExceptionMessageTest` (both `@InjectMocks`); add `"deletable"` to the exact key set in `AgreementOwnershipIntegrationTest.listCarriesPartyNamesByRoleInEntryOrderAndNothingElseOfAParty`
- [x] 3.3 Add `DELETE /api/agreements/{id}` to `AgreementController` returning `204`, reading no body (design D8)
- [x] 3.4 Permit `DELETE /api/agreements/*` as `authenticated()` in `SecurityConfig` next to `PUT /api/agreements/*`; add `"DELETE /api/agreements/{id}"` to `AbuseLimitsIntegrationTest`'s `DEFAULT_CLASS` set
- [x] 3.5 `docs/DEPLOYMENT.md`: the object-storage bucket must be unversioned with no object lock, and the §8 backup target must be unversioned or expire old versions; a deleted draft survives in backups until they rotate out (design D6)
- [x] 3.6 `docs/COUNSEL-BRIEF.md` (b): append the retention question for the deletion record (agreement id, reference, account id, time) to the existing DPDP retention question — no new counsel row

## 4. Backend tests

- [x] 4.1 Unit: `AgreementTest` (or the existing aggregate test) — `isDeletableDraft` truth table: true only for no signing request, no order, `UNPAID`, `OPEN`; false for each condition alone (signing request, order, `PAID`, `WAIVED`, `CLOSED`)
- [x] 4.2 Unit: `DraftServiceTest` — add a delete-specific `owned()` helper stubbing `ownerIdentityId()` (not the existing `admitted()`, which stubs `admits` and would trip strict stubs) and stub `isDeletableDraft` on the `@Mock Agreement`; owner delete calls `repository.delete`, saves one `AgreementDeletion` with id, tracking reference and owner, and deletes every `draftStageKeys` key after commit (`TransactionSynchronizationManager.initSynchronization()`, cleared in `@AfterEach`; trigger `afterCommit` from the registered synchronizations); with synchronization inactive it deletes inline and warns; non-owner, unowned, unknown and null caller → `ResourceNotFoundException` with `verifyNoInteractions` on both queries and on the deletion repository; refused rule → `draftNotDeletable`, no delete, no record, no blob call; a throwing `BlobStore.delete` is swallowed and, via `support/LogCapture`, the warning carries the cause class name and no throwable
- [x] 4.3 Unit: `GlobalExceptionHandlerTest` (`draftNotDeletable` → `409` + URN), `MinioBlobStoreTest` (`delete` calls `removeObject` with bucket + key; failure is wrapped with the redacted key), `AgreementServiceTest` (`toSummary` `deletable` true for an unpaid draft, false with an order, and `PaymentOrderQuery` not called for an `IN_PROGRESS` row)
- [x] 4.4 Integration: one class `DeleteDraftAgreementIntegrationTest` (one Spring context: `HarnessTestConfig`, customer sessions via `StaffSessions.customerSession`, `createTg()` + claim, `CsrfTestInterceptor`, and a `@MockitoSpyBean BlobStore` keeping real behaviour) holding 4.4–4.8. Happy path and authz: owner delete → `204`, agreement + signer rows gone (JDBC), `GET /{id}` → `404`, absent from the list, `drafts/{id}.pdf` gone — fixture: upload a PDF, then `PUT` an edit (clears the pin, keeps the object), then delete; draft with no PDF → `204`; other identity and unknown id → identical `404`; unowned draft → `404`; no session → `403`; missing CSRF token via `support/RawClient` → `403` `csrf`; a JSON body naming another id/owner deletes only the path's agreement
- [x] 4.5 Integration (same class): refusals — fixtures by JDBC so each clause is isolated: a `signing_request` row, a `payment_order` row with no signing request, `payment_state = 'PAID'` and `'WAIVED'` with no order → each `409` `draft-not-deletable` with the agreement, parties and draft blob unchanged; the same fixtures appear in `GET /api/agreements` with `deletable = false` alongside an unpaid draft with `deletable = true`
- [x] 4.6 Integration (same class): lock — open a JDBC transaction that inserts a `signing_request` for an owned draft, send the DELETE on another thread, poll `pg_stat_activity` until that backend shows `wait_event_type = 'Lock'`, then commit and assert `409` and the agreement still exists (test pool is 4 connections; precedent `StampIntakeApiIntegrationTest:561`)
- [x] 4.7 Integration (same class + `FlywayMigrationIntegrationTest`): audit and record — insert an `identity` row then a `stamp_intake_audit` row (JDBC; `staff_identity_id` is `NOT NULL REFERENCES identity`) referencing an owned draft; after the delete it survives with `agreement_id` NULL and its reference/outcome intact; a successful delete leaves exactly one `agreement_deletion` row (id, tracking reference, owner, timestamp) and a `404` or `409` delete leaves none; `FlywayMigrationIntegrationTest` asserts `stamp_intake_audit_agreement_id_fkey` has `delete_rule = 'SET NULL'`
- [x] 4.8 Integration (same class): blob failure — stub the spy `BlobStore.delete` to throw for this case only → `204` and the agreement row gone
- [x] 4.9 Integration: `AgreementForeignKeyGuardIntegrationTest` — list every FK referencing `agreement` or `signer` from `information_schema` and fail on any not in the classified set of design Context (excluded by the rule, `SET NULL`, or cascaded); its javadoc says FK-less agreement-id columns (e.g. `agreement_deletion`) are outside its reach
- [x] 4.10 Keep `ModularityTests`, `SecurityBaselineIntegrationTest`, `AbuseLimitsIntegrationTest`, `RouteClassifierTest` and `AgreementIdSourceScanTest` green; report `check` wall-clock against the 3-minute budget

## 5. Frontend

- [x] 5.1 `src/api/agreements.ts`: `deletable: boolean` on `AgreementSummary`; `deleteAgreement(id): Promise<void>` (DELETE via `apiFetch`, resolves on `204` without reading a body, throws `AgreementHttpError` otherwise); `PROBLEM.draftNotDeletable` in `src/api/problems.ts`
- [x] 5.2 Unit: `agreements.test.ts` — `deleteAgreement` sends `DELETE` with the CSRF header, resolves on an empty `204`, carries `problemType` on `409`; update `problems.test.ts`'s exact `PROBLEM` snapshot
- [x] 5.3 `components/ConfirmDialog.vue` (title, body slot, confirm/cancel labels, `busy` disables confirm): no `<Teleport>`; `role="dialog"`, `aria-modal`, `aria-labelledby`, focus to Cancel on open, Tab trapped by a `keydown` handler, Escape and backdrop cancel, emits `confirm`/`cancel` (design D9)
- [x] 5.4 Unit: `ConfirmDialog.test.ts` — focus moves in on open, Tab/Shift+Tab keydown wraps inside, Escape and backdrop emit `cancel`, `busy` disables confirm
- [x] 5.5 `views/MyAgreements.vue`: Delete on rows with `a.deletable` (`@click.stop`, `data-testid="delete-{id}"`); dialog names first owner / first tenant / tracking number by text interpolation with "—" fallback, using the D9 wording (removed, cannot be restored, emailed copies cannot be recalled); outcome messages in a separate `list-notice` ref (`role="status"`) that `load()` does not reset — never the existing `error` ref; outcomes and focus targets per design D9
- [x] 5.6 Unit: `MyAgreements.test.ts` (add `deleteAgreement` to the `vi.mock` factory; `deletable: false` in the `row()` default; also add it to `agreementListFormat.test.ts`'s `summary()` default) — Delete only on deletable rows (incl. a non-deletable `DRAFT`); Delete does not toggle; dialog text and markup-as-text; cancel (button/Escape) sends nothing and refocuses Delete; confirm calls once, confirm disabled while pending, row removed; last row → `list-empty`; 409 → notice + reload, notice still shown after the reload; 404 → notice + row removed; 500 → failure notice, row kept

## 6. Terms of Service

- [x] 6.1 Edit `frontend/src/content/termsOfService.ts` (never the generated `.md`) and bump `TERMS_LAST_UPDATED`: §10 — a signed-in customer can delete an unpaid draft from My agreements; it is removed from the service and cannot be restored, backup copies are overwritten as backups rotate, and copies already emailed cannot be recalled (keep "An unpaid draft is yours to abandon"); §12 — replace "We do not have a deletion control in the product yet…" with the accurate statement (unpaid drafts in-product; anything else on request); §15 — one sentence: when a draft is deleted we keep a record of that fact (the reference, the account and the time), with no party details. Run `npm run terms:doc` to regenerate `docs/TERMS-OF-SERVICE.md`; `termsOfService.test.ts` and `TermsOfService.test.ts` stay green

## Coverage

| # | Scenario | Disposition | Where |
|---|---|---|---|
| 1 | The owner deletes an unpaid draft | COVERED | 4.4, 4.2 |
| 1a | A delete leaves a record without personal data | COVERED | 4.7, 4.2 |
| 2 | A draft PDF left behind by an edit is still removed | COVERED | 4.4 (generate → PUT → delete), 4.2 (null key) |
| 3 | A draft that never had a PDF is deleted | COVERED | 4.4, 4.2 |
| 4 | Another identity's agreement is indistinguishable from an unknown one | COVERED | 4.4, 4.2 |
| 5 | An unowned draft cannot be deleted through the endpoint | COVERED | 4.4, 4.2 |
| 6 | A caller without a session is refused | COVERED | 4.4 |
| 7 | A finalised agreement is not deletable | COVERED | 4.5, 4.1 |
| 8 | A draft with a payment order is not deletable | COVERED | 4.5, 4.1 |
| 9 | A paid or waived draft is not deletable | COVERED | 4.5, 4.1 |
| 10 | A delete waits for a concurrent finalise and then refuses | COVERED | 4.6 |
| 11 | A staff intake audit row survives the delete | COVERED | 4.7 |
| 12 | A failed blob removal does not fail the delete | COVERED | 4.8, 4.2 |
| 13 | A delete without the CSRF token is refused | COVERED | 4.4 |
| 14 | A body naming another agreement or owner is ignored | COVERED | 4.4 |
| 15 | Only deletable rows offer Delete | COVERED | 5.6 |
| 16 | Confirming deletes and removes the row | COVERED | 5.6 |
| 17 | Deleting the last agreement shows the empty state | COVERED | 5.6 |
| 18 | The dialog warns that emailed copies cannot be recalled | COVERED | 5.6 |
| 19 | Cancelling changes nothing | COVERED | 5.6, 5.4 |
| 20 | A draft finalised elsewhere is explained, not silently dropped | COVERED | 5.6 |
| 21 | A draft already gone is removed with an explanation | COVERED | 5.6 |
| 22 | An unexpected failure keeps the row | COVERED | 5.6 |
| 23 | The list shows in-progress and signed agreements, scoped to the caller (MODIFIED req — existing test) | COVERED | `AgreementOwnershipIntegrationTest.listReturnsOnlyMineMostRecentlyEditedFirstWithDerivedStatus` (unchanged, kept green) |
| 24 | Editing an older agreement moves it to the top (MODIFIED req — existing test) | COVERED | `AgreementOwnershipIntegrationTest.editingAnOlderAgreementMovesItToTheTop` (unchanged) |
| 25 | Each summary carries every party's name by role, in entry order (MODIFIED req — exact field set now includes `deletable`) | COVERED | `AgreementOwnershipIntegrationTest.listCarriesPartyNamesByRoleInEntryOrderAndNothingElseOfAParty` — its exact key set gains `"deletable"` in 3.2 |
| 26 | Only an unpaid draft is reported deletable | COVERED | 4.5, 4.3 |
| 27 | estamp-intake: Intake attempts are audited (MODIFIED req — unchanged scenario) | COVERED | existing `StampIntakeApiIntegrationTest` audit assertions, kept green by 4.10 |
| 28 | estamp-intake: An intake audit record outlives a deleted draft | GROUPED | covered by scenario 11's test, 4.7 |
| 29 | estamp-intake: No certificate contents or PII in logs (MODIFIED req — unchanged scenario) | COVERED | existing `StampIntakeServiceTest` / `AgreementIdSourceScanTest` log assertions, unchanged |
