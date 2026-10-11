> Test-level note: this change adds operator-run SQL files, a test harness and docs — no
> application behaviour. There is no unit-testable logic; the integration test that executes the
> scripts against Postgres is the deliverable, so no unit-test task is listed.

## 1. Rollback scripts

- [ ] 1.1 Create `backend/db/rollback/rollback-V30__unrecorded_payment_alert.sql` per D3/D4. Header: destructive; deletes alerts of the three `PAYMENT_` kinds; why the deployed build cannot run against V30; run the stuck-order query first and keep its output; a redeploy re-alerts orders inside the 24-hour window. Body: `BEGIN;` `SET LOCAL lock_timeout = '5s';` null-safe version guard `DO` block for 30; `LOCK TABLE staff_alert, payment_event IN ACCESS EXCLUSIVE MODE;` the `SELECT` of rows about to be deleted by `tracking_reference`, not id; the statements from `docs/DEPLOYMENT.md:641-649` in the same order; `COMMIT;`. Unqualified names only.
- [ ] 1.2 Create `backend/db/rollback/rollback-V29__payment_event.sql`. Header: destructive; drops the whole payment event ledger, which survives only in the pre-rollback dump, and confirmations recorded after that dump are lost. Body: `BEGIN;` `SET LOCAL lock_timeout = '5s';` guard for 29; `LOCK TABLE payment_event IN ACCESS EXCLUSIVE MODE;` `DROP TABLE payment_event;` delete history row 29; `COMMIT;`.
- [ ] 1.3 Confirm no applied migration changed: `git diff --quiet HEAD -- backend/src/main/resources/db/migration`.

## 2. Test harness

- [ ] 2.1 `backend/build.gradle.kts`: declare `db/rollback` as a named input directory of the `Test` tasks with relative path sensitivity (D2), so editing a script re-runs the tests and the cache key does not depend on the checkout path.
- [ ] 2.2 `HarnessTestConfig`: a static helper that copies a script into the Postgres container and runs `psql -X -f` in argv form, with the schema in `PGOPTIONS` via `ExecConfig`, taking database, schema and whether to pass `ON_ERROR_STOP=1`; returns exit code and stdout + stderr; refuses a schema that is `public` or not a plain identifier (D5).
- [ ] 2.3 Add `MigrationRollbackIntegrationTest` with `FlywayMigrationIntegrationTest`'s annotations (D9). Its script runner wraps 2.2: before, assert the scratch schema exists; after, assert the context schema's `flyway_schema_history` has a successful row for every migration on the classpath. Scratch schemas use the fixed names in D5 and are dropped in `finally`.
- [ ] 2.4 A catalog-snapshot helper per D6 (columns with rank among live columns/length/precision/default, constraints, indexes, sequences, views, triggers, routines; every list sorted by name; schema name normalised in every text column) plus applied versions.

## 3. Tests (integration)

- [ ] 3.1 Classpath guard: the resource `db/rollback` is absent from the classpath.
- [ ] 3.2 Enforcement: every `V<n>__<desc>.sql` with n ≥ 29 under `src/main/resources/db/migration` has `db/rollback/rollback-V<n>__<desc>.sql`, and every file there names such a migration; a missing or empty directory fails naming the working directory.
- [ ] 3.3 Shape: for each script, with `--` comment lines and blank lines stripped, the first line is `BEGIN;`, the last is `COMMIT;`, no other line contains `BEGIN;` or `COMMIT;`, and the second line sets `lock_timeout`.
- [ ] 3.4 Chain, up (D6 step 1): `rb_up` migrated to 28 then one version at a time to the latest, snapshot and applied versions recorded after each.
- [ ] 3.5 Chain, down (D6 step 2), all runs with `ON_ERROR_STOP=1`, assertions labelled with the script name: `rb_down` at the latest `L`; script `L` refuses with a synthetic successful `L+1` history row (`installed_rank` max+1 and the other NOT NULL columns filled; then remove it); for each `n` from `L` to 29 — script `n-1` (when n > 29) refuses and changes nothing, script `n` exits 0 and the snapshot and versions equal the reference before `n`, script `n` run again refuses and changes nothing.
- [ ] 3.6 Chain, re-apply (D6 step 3): migrate `rb_down` to `L`; versions 29…L recorded successful; snapshot equals the reference for `L`.
- [ ] 3.7 V30 with data, on a datasource whose URL carries `?currentSchema=<scratch>`: migrate to 30; seed an agreement and alerts of all five kinds, the three `PAYMENT_` alerts sharing the `DUPLICATE_PAYMENT` id. Negative control: the deployed build's insert (`… ON CONFLICT (id) DO NOTHING`) fails. Run the script (exit 0; output lists the three deleted alerts by tracking reference and contains no agreement id). `ORDER_PAID` and `DUPLICATE_PAYMENT` rows unchanged, the rest gone; the insert as `DUPLICATE_PAYMENT` gives 1 row for a new id and 0 for the existing id.
- [ ] 3.8 V30 failure is atomic: same seed, drop `idx_payment_event_last_received`; run the script with `ON_ERROR_STOP=1` (non-zero exit) and again without (`ERROR` in the output); after each, the `(id, kind)` key, all five rows and the version-30 history row are still there.

## 4. Docs

- [ ] 4.1 `docs/DEPLOYMENT.md`, under **Rollback** (:878-888): add "Rolling back across migrations" — D7's five steps, both commands (the dump command with its `dump ok` check), and the notes beside them, including the three failure cases. Adjust the existing paragraph's "Restoring it is manual" sentence to point at the new subsection for V29 and up.
- [ ] 4.2 `docs/DEPLOYMENT.md`, "A captured payment that was refused or not recorded": replace the paragraph and SQL block at :630-657 with two sentences — rolling back past this release follows "Rolling back across migrations"; what is specific to it is in `rollback-V30__unrecorded_payment_alert.sql`'s header.
- [ ] 4.3 `docs/DEPLOYMENT.md` §8 (backups): one sentence — a `pre-rollback-*.sql.gz` dump is a whole-database copy outside rotation; delete it once the rollback is settled.
- [ ] 4.4 `CLAUDE.md`, Flyway paragraph: one line — every migration ships `backend/db/rollback/rollback-V<n>__<desc>.sql` (enforced by `MigrationRollbackIntegrationTest`); procedure in `docs/DEPLOYMENT.md`.
- [ ] 4.5 `docs/ROADMAP.md` register: add row `rollback-with-stopped-backend` (after a deploy recorded `ok`, a stopped or crash-looping backend makes `deploy.sh rollback` target the release being left; recommended action: make `running_tag` read stopped containers, covered by `deploy/test`, then switch the runbook to stop-first); append one sentence to `prod-backups-and-disaster-recovery` gap (5) — rollback across V29 and later has tested scripts and a runbook; below V28, and putting dropped data back, is still the dump restore.

## 5. Verify

- [ ] 5.1 `./gradlew spotlessApply`, then `./run-tests.sh check` from `backend/`; report the wall-clock time against the 3-minute budget.
- [ ] 5.2 Prove 2.1: run `./run-tests.sh test --tests '*MigrationRollback*'` twice — the second run's `:test` is `UP-TO-DATE`; edit a comment in the V30 script and run the same command a third time — `:test` executes (neither `UP-TO-DATE` nor `FROM-CACHE`); revert the edit.

(At archive, not a task: delete the `migration-rollback-scripts` row from the register.)

## Coverage

| Scenario | Disposition | Covered by |
|---|---|---|
| Rollback scripts are not on the classpath | COVERED | 3.1 |
| Every migration from V29 has a rollback script, and every script a migration | COVERED | 3.2 |
| Every rollback script opens and commits one transaction with a lock timeout | COVERED | 3.3 |
| A rollback script refuses when its migration is not the latest applied | COVERED | 3.5 |
| Scripts run newest first restore each earlier catalog | COVERED | 3.4 + 3.5 |
| The migrations re-apply after their rollback | COVERED | 3.6 |
| The context schema is untouched | COVERED | 2.3 (asserted after every script run in 3.5–3.8) |
| The previous release's staff alert insert works only after rolling back V30 | COVERED | 3.7 |
| A rollback script that fails part-way changes nothing | COVERED | 3.8 |
