## 1. Schema and record

- [x] 1.1 Add `backend/src/main/resources/db/migration/V25__draft_retention.sql` (D4): `agreement_deletion.owner_identity_id` nullable; `reason VARCHAR(16)` backfilled `OWNER_DELETE` then default dropped; CHECK on the two values (no owner-presence CHECK — V23's erasure contract); `COMMENT ON TABLE agreement_deletion` covering both reasons; re-issued `COMMENT ON COLUMN stamp_intake_audit.agreement_id`; partial index `agreement (last_edited_at, id) WHERE payment_state = 'UNPAID' AND closure_state = 'OPEN'`; header on purpose, readers, retention
- [x] 1.1b Add `V26__payment_order_agreement_index.sql`: plain index `payment_order (agreement_id)` for the purge's lookups (separate from V25, which was already applied locally)
- [x] 1.2 Add `DeletionReason` enum; map `reason` on `AgreementDeletion` (`@Enumerated(STRING)`), owner nullable; `AgreementDeletion.of(agreement, reason, at)`; update its javadoc to cover the purge

## 2. Storage listing

- [x] 2.1 Add record `StoredObject(String key, Instant lastModified)` and `List<StoredObject> list(String prefix)` to `BlobStore` (D6)
- [x] 2.2 Implement `list` in `MinioBlobStore` with `listObjects(prefix, recursive)`; a failure on the call or on any `Result.get()` fails the whole listing with an exception whose message carries no object name

## 3. Purge

- [x] 3.1 Extract the shared delete core in `DraftService` (`remove(agreement, reason)`, record stamped `Instant.now()`); `deleteDraft` writes `OWNER_DELETE`, behaviour otherwise unchanged (D1)
- [x] 3.2 Add `AgreementRepository.findByIdForPurge` (`FOR UPDATE SKIP LOCKED`) and package-private `DraftService.purgeIfStale(id, cutoff)` re-checking `lastEditedAt < cutoff` and `isDeletableDraft` under it (D1, D2b)
- [x] 3.3 Add `AgreementRepository.findStaleDraftCandidates(cutoff, afterEditedAt, afterId, limit)` keyset native pre-filter (state literals inline, cursor starts at `(EPOCH, 0-UUID)`); add the "restated by the purge candidate query; change both" note to `Agreement.isDeletableDraft` javadoc (D2)
- [x] 3.4 Add package-private `DraftRetention` in `signing.agreement` (not `@Transactional`): `RETENTION = Duration.ofDays(90)`; `purge(now)` — keyset pages of 100, cursor advanced past every row, cap 10 000 attempts, per-id catch logging class name + redacted id; returns counts (D3, D5)
- [x] 3.5 Add `DraftService.draftStagePrefixes()` beside `draftStageKeys` and `DraftService.draftIdOf(key)` (UUID only if `draftStageKeys(id).contains(key)`), and `DraftRetention.sweep(now)` listing each prefix — 24 h grace, no agreement row, `agreement_deletion` row exists, exact listed key removed, cap 1 000; `run(now)` = purge then sweep, each in its own try/catch, one INFO count line incl. `sweepFailed` (D6, D7)
- [x] 3.6 Add `DraftRetentionJob` in `signing.agreement` (`@Scheduled` cron `0 30 3 * * *` Asia/Kolkata, `@ConditionalOnProperty signing.draft-retention.enabled`, `matchIfMissing = false`, catch-all logging class name only); `application.yml`: `signing.draft-retention` block and `spring.task.scheduling.pool.size: 4`; `application-test.yml`: `enabled: false`; `docs/DEPLOYMENT.md` and `deploy/env/backend.env.example` (the authoritative variable list): `SIGNING_DRAFT_RETENTION_ENABLED=true` for production, bucket-per-environment invariant, disable is incident-only (D7)

## 4. Backend tests

- [x] 4.1 Unit `DraftServiceTest`: purge of a stale unclaimed draft writes `RETENTION_PURGE` with null owner; not-stale, missing (or skip-locked) and non-deletable rows return false and delete nothing; boundary — `lastEditedAt` equal to cutoff is kept, 1 µs before is purged; owner delete writes `OWNER_DELETE`; `draftIdOf` accepts `drafts/{uuid}.pdf` and rejects other forms and non-canonical UUID text
- [x] 4.2 Unit `DraftRetentionTest` (mocked collaborators): keyset cursor advances past a failing / skipped id, which is attempted once while later ids are purged; loop ends on an empty page and at the 10 000 cap; sweep keeps young objects, other key forms, objects with a live agreement and objects with no deletion record, and stops at 1 000 removals; a `list` failure leaves purge counts intact and sets `sweepFailed`; a failed candidate page keeps the partial counts and sets `purgeAborted`; `RETENTION` equals 90 days (message names terms §10); log assertions via `support/LogCapture` — no exception message, no full id, no key
- [x] 4.3 Unit `DraftRetentionJobTest`: job built directly with a `DraftRetention` that throws returns normally and logs the class name only
- [x] 4.4 Integration `DraftRetentionIntegrationTest` (Testcontainers Postgres + MinIO, `BlobStore` as `@MockitoSpyBean` like `DeleteDraftAgreementIntegrationTest`; staleness set by JDBC `UPDATE agreement SET last_edited_at`): unclaimed stale draft purged with parties, PDF and `RETENTION_PURGE` record; claimed stale draft purged, record names owner, absent from owner's list; 89-day draft kept; signing-request / payment-order / PAID / WAIVED stale agreements kept; every candidate the query returns over the fixture set passes the lock-time check; stamp_intake_audit row survives with link cleared; failed blob removal does not stop the second purge; a DB failure on one id (spied collaborator throwing with the id in its message) is logged class-name-only in the run's loggers and the next id is purged; storage failing every call still purges rows and reports `sweepFailed` with clean logs
- [x] 4.5 Integration racing: committed edit and committed signing request before `purgeIfStale` → kept; latch-based variant — an open transaction holding `findByIdForUpdate` while the purge runs → purge returns without waiting, counted skipped, agreement kept with the edit
- [x] 4.6 Integration orphan sweep against real MinIO, calling `sweep(now + 25 h)` directly (MinIO cannot backdate `lastModified`), asserting each fixture's own objects only — never global counts, since the shared context may hold orphans left by `DeleteDraftAgreementIntegrationTest`: orphan with a deletion record removed; orphan without a deletion record kept; live unpaid draft's and paid agreement's objects kept; object younger than the grace kept (run with real `now`); `drafts/readme.txt`, `drafts/{uuid}.png`, upper-case UUID key kept
- [x] 4.7 Integration `MinioBlobStore.list` returns keys and last-modified under a prefix and nothing outside it; a failing listing's exception message carries no object name
- [x] 4.8 Integration V25 migration using the `FlywayMigrationIntegrationTest` target pattern (migrate to 24, insert a deletion row, migrate to 25): existing row reads `OWNER_DELETE`; `OWNER_DELETE` with null owner violates the CHECK; `RETENTION_PURGE` with null owner is accepted; an unknown reason violates the CHECK
- [x] 4.9 Integration: in the test profile `DraftRetentionJob` bean is absent (unit: `ApplicationContextRunner` without the property → absent, with `true` → present) and `DraftRetention` is present; `DeleteDraftAgreementIntegrationTest` record assertion extended to `OWNER_DELETE`

## 5. Policy texts and docs

- [x] 5.1 Terms §10 (`frontend/src/content/termsOfService.ts`) last paragraph per D8; privacy §8 (`privacyPolicy.ts`) renamed "8. Deleted drafts", record sentence reworded and purge sentence added, §7 gap names the 90-day period — all without "deleted after"
- [x] 5.2 Frontend unit tests (`termsOfService` / `privacyPolicy` content tests): terms `drafts` contains "90 days" and "finalised", not "a long time" / "may delete"; privacy `deleted-drafts` heading "8. Deleted drafts", mentions the 90-day deletion and not "the account that deleted it"; privacy `retention` gap mentions the 90-day period
- [x] 5.3 Frontend integration: existing `TermsOfService.test.ts` / privacy view test render the updated clauses
- [x] 5.4 Regenerate `docs/TERMS-OF-SERVICE.md` and `docs/PRIVACY-POLICY.md` with `npm run legal:doc`
- [x] 5.5 Append the 90-day question to `docs/COUNSEL-BRIEF.md` data-protection paragraph (b)
- [x] 5.6 `docs/ROADMAP.md`: delete register row `anonymous-draft-retain-and-purge` and remove it from the release-trigger list; append to row `withdraw-unpaid-finalised-agreement` that finalised-but-unpaid agreements are now the one unpaid state with no deletion and no stated period, and re-rate it from "no data exposure" to retention exposure

## 6. Gates

- [x] 6.1 `./run-tests.sh check` green from `backend/` (ModularityTests included); report wall-clock
- [x] 6.2 `npm run build` and `npm run lint` green from `frontend/`
- [x] 6.3 `DeleteDraftAgreementIntegrationTest` (existing) stays green unchanged except the 4.9 record assertion — it is the regression suite for the MODIFIED owner-delete requirement

## Coverage

15 + 18 + 3 = 36 scenarios. 0 WAIVED, 0 UNMAPPED.

| Capability | Scenario | Disposition | Where |
|---|---|---|---|
| agreement-management | The owner deletes an unpaid draft | COVERED | 6.3 `theOwnerDeletesAnUnpaidDraftIncludingAPdfLeftBehindByAnEdit` |
| agreement-management | A draft PDF left behind by an edit is still removed | GROUPED | same test as above (6.3) |
| agreement-management | A draft that never had a PDF is deleted | COVERED | 6.3 `aDraftThatNeverHadAPdfIsDeleted` |
| agreement-management | A delete leaves a record without personal data | COVERED | 4.9 (`anIntakeAuditRowSurvivesTheDeleteAndADeletionRecordIsWritten` asserts `OWNER_DELETE`), `aRefusedDeleteLeavesNoDeletionRecord`; 4.1 unit |
| agreement-management | Another identity's agreement is indistinguishable from an unknown one | COVERED | 6.3 `anotherIdentitysAgreementIsTheSameNotFoundAsAnUnknownOne` |
| agreement-management | An unowned draft cannot be deleted through the endpoint | COVERED | 6.3 `anUnownedDraftCannotBeDeletedThroughTheEndpoint` |
| agreement-management | A caller without a session is refused | COVERED | 6.3 `aCallerWithoutASessionIsRefused` |
| agreement-management | A finalised agreement is not deletable | COVERED | 6.3 `anythingButAnUnpaidDraftIsRefusedAndUnchangedAndListedNotDeletable` |
| agreement-management | A draft with a payment order is not deletable | GROUPED | same test (6.3) |
| agreement-management | A paid or waived draft is not deletable | GROUPED | same test (6.3) |
| agreement-management | A delete waits for a concurrent finalise and then refuses | COVERED | 6.3 `aDeleteWaitsForAConcurrentFinaliseAndThenRefuses` |
| agreement-management | A staff intake audit row survives the delete | COVERED | 6.3 `anIntakeAuditRowSurvivesTheDeleteAndADeletionRecordIsWritten` |
| agreement-management | A failed blob removal does not fail the delete | COVERED | 6.3 `aFailedObjectRemovalDoesNotFailTheDelete` |
| agreement-management | A delete without the CSRF token is refused | COVERED | 6.3 `aDeleteWithoutTheCsrfTokenIsRefused` |
| agreement-management | A body naming another agreement or owner is ignored | COVERED | 6.3 `aBodyNamingAnotherAgreementOrOwnerIsIgnored` |
| draft-retention | An unclaimed draft untouched for 90 days is purged | COVERED | 4.4; 4.1 unit |
| draft-retention | A claimed draft untouched for 90 days is purged | COVERED | 4.4 |
| draft-retention | A draft edited within the period is kept | COVERED | 4.4; 4.1 unit (boundary) |
| draft-retention | A stale agreement that has left the draft stage is kept | COVERED | 4.4; 4.1 unit |
| draft-retention | A staff intake audit row survives the purge | COVERED | 4.4 |
| draft-retention | A failed blob removal does not stop the purge | COVERED | 4.4 |
| draft-retention | A draft edited after it was selected is kept | COVERED | 4.5 |
| draft-retention | A draft finalised after it was selected is kept | COVERED | 4.5 |
| draft-retention | A draft locked by an in-flight edit is skipped | COVERED | 4.5 (latch variant) |
| draft-retention | A persistently failing candidate does not block later ones | COVERED | 4.2 unit; 4.4 (DB failure on one id) |
| draft-retention | A PDF left behind by a failed delete is removed | COVERED | 4.6; 4.2 unit |
| draft-retention | An object with no deletion record is kept | COVERED | 4.6; 4.2 unit |
| draft-retention | A live agreement's PDF is kept | COVERED | 4.6; 4.2 unit |
| draft-retention | A just-written object is kept | COVERED | 4.6; 4.2 unit |
| draft-retention | A key of another form is kept | COVERED | 4.6; 4.1 unit (`draftIdOf`) |
| draft-retention | A storage outage does not escape the scheduler | COVERED | 4.4 (storage failing every call, log assertions); 4.3 unit (job catch-all) |
| draft-retention | A database failure on one candidate is logged without its message | COVERED | 4.4; 4.2 unit |
| draft-retention | The job is off unless enabled | COVERED | 4.9 (context + `ApplicationContextRunner`) |
| legal-policy-pages | Terms §10 states the period | COVERED | 5.2 |
| legal-policy-pages | The privacy policy covers a draft we delete | COVERED | 5.2 |
| legal-policy-pages | The backend period matches the terms | COVERED | 4.2 |
