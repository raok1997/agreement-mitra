## Why

Rolling the application back across a migration has no tested route. `deploy/deploy.sh rollback`
refuses whenever the database holds a migration the target release does not, and the only way on
is a manual dump restore that discards every write since the deploy. The one exception, V30, has
its rollback as SQL typed into `docs/DEPLOYMENT.md`, never executed — and its steps do not work:
they say to stop the backend first, which makes `deploy.sh rollback` restart the release being
left (design, Context).

Flyway Community has no undo migrations and our migrations stay forward-only. What is missing is
the SQL to put the schema back, checked in and tested before the incident, for every migration a
release might carry — a release often carries several CRs.

## What Changes

- **Every migration ships a rollback script**, starting at V29 (production is at V28):
  `backend/db/rollback/rollback-V<n>__<desc>.sql`. No judgment about which migrations "need" one,
  so any number of CRs can deploy in one release and be rolled back by running their scripts
  newest first. The directory is off the classpath: not in the jar, and unreachable by any Flyway
  `locations` setting.
- **Each script** is one transaction, refuses to run unless its migration is the latest applied
  (so the order cannot be got wrong), fails fast on a lock it cannot get, restores the schema to
  what it was before its migration, and removes its own `flyway_schema_history` row — which is
  what lets `deploy.sh rollback`'s gate pass and lets the release be deployed again.
- **A test enforces and runs them.** Every migration from V29 must have a script. The test runs
  each script file through `psql` in the Postgres test container: walking down from the latest
  migration to V28 it checks, step by step, that the catalog matches the one recorded on the way
  up, that a script refuses out of order and on a second run, and that Flyway then re-applies
  everything.
- **The first two scripts:** V29 (drops the payment event ledger) and V30 (re-keys `staff_alert`,
  deleting the three alert kinds the old key cannot hold). The V30 SQL block moves out of the
  runbook into its file, with a test that seeds real alert rows.
- **One generic rollback procedure in `docs/DEPLOYMENT.md`**, replacing per-feature steps:
  pre-flight `deploy.sh rollback` (it refuses and names the versions) → fresh dump → scripts newest
  first → `deploy.sh rollback`. The backend stays running; if anything fails, restarting it
  re-applies the migrations. Manual by design — no automation.
- **`CLAUDE.md`**: one line stating the rule.
- **Register:** `migration-rollback-scripts` is deleted at archive; a new row,
  `rollback-with-stopped-backend`, records that `deploy.sh rollback` targets the wrong release when
  the backend is stopped; one sentence is appended to `prod-backups-and-disaster-recovery`.

Not changing:

- Migrations stay forward-only. A bad migration is still fixed by the next `V<n>`. Not Liquibase,
  not paid Flyway — either would hold the same statements in a different file.
- **No applied migration is edited — not V29, not V30, not even a comment.** A byte change alters
  the checksum and production's startup validation refuses to boot. (V30's comment still names
  the runbook; it stays.)
- `deploy/deploy.sh`, its gate and its messages.
- No scripts for V1–V28. A rollback below V28 remains the dump restore.
- No application code, no new migration, no env var.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `backend-persistence`: adds requirements that every migration from V29 ships a rollback script,
  what a script must do, and that each is executed by a test.

## Impact

- **New:** `backend/db/rollback/rollback-V29__payment_event.sql`,
  `backend/db/rollback/rollback-V30__unrecorded_payment_alert.sql`,
  `MigrationRollbackIntegrationTest` beside `FlywayMigrationIntegrationTest` (same Spring context,
  so no extra container or context start).
- **Edited:** `HarnessTestConfig` (one static helper that runs a script through `psql` in the
  shared Postgres container — test support only), `backend/build.gradle.kts` (the script
  directory becomes a `test` input), `docs/DEPLOYMENT.md`, `CLAUDE.md`, `docs/ROADMAP.md`.
- **Changes in flight:** any open change that adds a migration (`V31` and up) fails
  `MigrationRollbackIntegrationTest` until it adds its script. That is the rule taking effect.
- **Signing-status FSM:** no transition touched.
- **Async signing / webhook flow:** not touched; no sequence diagram applies.
- **PII / security checklist:** no new PII flow in the application or the scripts: no Aadhaar,
  OTP, VID or secret is introduced or moved. The rollback *procedure* does create one more copy of
  data at rest — the pre-rollback dump is a whole-database dump, signer names and emails
  included, unencrypted on the same disk like the deploy's own dumps, and unlike them it is not
  rotated: the runbook says to delete it once the rollback is settled, and `docs/DEPLOYMENT.md`
  §8 gains a sentence naming it. The scripts delete `staff_alert` rows of three kinds (ids, a status and
  timestamps; V27: "PII: none") and drop `payment_event` (V29: "no payer PII and no webhook
  payload"). The agreement id is a bearer capability, so the rows the V30 script prints before
  deleting identify an agreement by tracking reference. The pre-rollback dump is written `0600`
  to the root-owned backups directory and verified complete before any script runs. Sandbox + dummy data only is
  preserved; the tests run on throwaway schemas in the Testcontainers Postgres.
- **Suite time:** two scratch migrations plus a few `psql` runs per script. It grows by one small
  step per future migration; measured in apply against the 3-minute `check` budget.
