## Context

- Flyway 11.7.2 Community, forward-only migrations at `classpath:db/migration` (Boot's default; no
  `spring.flyway.locations` is set). `flyway.clean` is disabled.
- `deploy/deploy.sh rollback` compares the database's highest applied version
  (`select max(version::int) from flyway_schema_history where success`, `deploy/lib/checks.sh:487`)
  to the *target deployed release's* highest `V<n>` (its "ceiling") and **refuses** when the
  database is ahead, printing both numbers and the pre-deploy dump (`deploy/deploy.sh:635-646`).
  The gate runs before the image, network and dirty-tree checks, `rollback` has no `--dry-run`
  (`:89`), and it does not pause after printing its target (`:633`).
- `rollback()` picks its target from the running tag (`:623-629`). `running_tag` reads
  `docker compose ps -q <svc>`, which lists running containers only. With the backend stopped,
  the three services disagree, the tag is `mixed`, and `rollback_target` returns the history's
  **top** — the release being left (`deploy/lib/checks.sh:622-632`, `739-747`). That is right for
  a part-way failed deploy and wrong for "stop the backend, then roll back".
- **`main` and production stop at V28** (`git ls-tree origin/main`; deployed 2026-10-10). V29
  (`5548df0`) and V30 (`28dcb09`) both sit on this branch, undeployed, and will go out together.
- V29 creates `payment_event` (identity PK, two indexes; no foreign key in or out). It is written
  outside the confirmation transaction and holds no payer PII.
- V30 re-keys `staff_alert` on `(id, kind)`, widens `staff_alert_kind_check` to five kinds and
  adds `idx_payment_event_last_received`. The deployed build's
  `StaffAlertRepository.insertPendingIfAbsent` ends `ON CONFLICT (id) DO NOTHING`, which Postgres
  rejects once no unique index covers `id` alone.
- Staff alert rows are written only by the 30-second sweep, each step its own transaction
  (`StaffAlertPersistence`). On a schema it does not expect the sweep fails twice per run: the
  unrecorded-payment step is caught and logged at **ERROR** (`StaffAlertDispatcher.java:99-111`),
  then the paid/surplus step throws, aborts that sweep's dispatch, and is logged at WARN
  (`StaffAlertDispatchJob.sweep`).
- The V30 rollback SQL is a fenced block in `docs/DEPLOYMENT.md:630-657`. Nothing executes it.
- The deploy's own dump is `pg_dump` through `docker compose exec -T postgres`, gzipped to
  `/root/backups/pre-deploy-<UTC>-<tag>.sql.gz`, mode `0600` (`deploy/deploy.sh:540-569`).
  Rotation considers only names of that pattern.
- `FlywayMigrationIntegrationTest` migrates scratch schemas to a target version over a
  `DriverManagerDataSource` (a new connection per call) and drops them in `finally`. Each Spring
  context has its own database `test_<n>` on one Postgres container per fork JVM; the container is
  a private static in `HarnessTestConfig`, `postgres:16-alpine`. Production and local compose run
  `postgres:17`.
- `backend/gradle.properties` enables the build cache, and the `Test` task declares no inputs
  beyond its classpath.

## Goals / Non-Goals

**Goals:**

- A release carrying any number of migrations can be rolled back by running checked-in, tested
  SQL files in a fixed order, with one written procedure.
- The rule has no judgment in it and is enforced by a test.
- Adding the next script needs no new test plumbing.

**Non-Goals:**

- Automating the rollback. An operator runs the files by hand.
- Undo migrations, Liquibase, paid Flyway.
- Editing any applied migration, including its comments.
- Changing `deploy/deploy.sh`, its gate or its messages (the stopped-backend defect goes to the
  register).
- Scripts for V1–V28.
- Preserving the data a rolled-back migration added. The pre-rollback dump holds it; putting it
  back is manual.

## Decisions

**D1. Every migration from V29 ships a script; a test enforces it.** *(user decision, 2026-10-11)*
The first two drafts scoped scripts to "migrations the previous release cannot run against". That
needed a judgment per migration, and it did not work for the release in hand: V29 (additive, no
script) and V30 ship together, the rollback target's ceiling is 28, and after the V30 script the
gate still refuses at 29. The fixes on offer — deploy V29 alone first, or a rule that breaking
migrations deploy alone — constrain every future release. A script for every migration removes
the judgment, the deploy-ordering rule and the mixed-release case together: scripts N…M+1 take the
database to M, whatever the release carried. The floor is 29 because production is at 28; it is
one constant in the test.

**D2. Location `backend/db/rollback/`, name `rollback-V<n>__<desc>.sql` — off the classpath.**
The register row suggested `src/main/resources/db/rollback/R30__*.sql`. `R__` is Flyway's
repeatable-migration prefix, and anything under `src/main/resources/db` is one
`locations: classpath:db` away from being scanned at startup — an auto-applied rollback would drop
a table on boot. Outside the classpath the question does not arise: the file is not in the jar or
the image (`backend/Dockerfile` copies only `src`, `config` and the Gradle files), and no Flyway
setting can reach it. It is in the server checkout, where an operator needs it. `deploy.sh`'s
`tree_max` reads only `db/migration` paths, so the directory does not affect the gate. Cost: the
directory is no longer a `test` input, so `build.gradle.kts` declares it — otherwise an edited
script leaves `:test` up to date and is never re-run.

**D3. Each script is one self-contained transaction that guards its version and its locks.**
- `BEGIN; … COMMIT;` in the file. A bare file plus `psql --single-transaction` would make
  atomicity depend on a flag typed during an incident; with `BEGIN` in the file a failed statement
  aborts the transaction and `COMMIT` becomes a rollback even with no flags.
- `SET LOCAL lock_timeout = '5s'` is the first statement after `BEGIN`, ahead of the guard's own
  read, so nothing in the script can queue.
- A `DO` block raises unless `max(version::int)` over successful rows of `flyway_schema_history`
  `IS NOT DISTINCT FROM <n>` — null-safe, and "highest", not "present". This is what makes "newest
  first" a property of the files instead of the operator: script 29 run before script 30 refuses,
  and so does any script run twice.
- `LOCK TABLE … IN ACCESS EXCLUSIVE MODE` on every application table it changes, together, before
  changing anything (not `flyway_schema_history`, and not tables it only reads). The scripts run
  against a live backend (D7); a script that cannot get its locks fails fast and whole, and the
  operator runs it again.
- The header says what is destroyed and anything specific to this migration. Script-specific
  facts live in the script; the generic steps live in the runbook; neither repeats the other.

**D4. The two scripts.**
- *V29:* guard; lock `payment_event`; `DROP TABLE payment_event` (its indexes and identity
  sequence go with it); delete history row 29. Destroys the whole payment event ledger. Header:
  the ledger is evidence of gateway confirmations and survives only in the pre-rollback dump;
  confirmations recorded after that dump was taken are lost with the table.
- *V30:* guard; lock `staff_alert` and `payment_event`; a `SELECT` of the rows about to go — kind,
  the agreement's `tracking_reference`, status, created and sent times; not the agreement id,
  which is a bearer capability (V27) — so the deleted delivery history is captured atomically with
  the delete and the kind list exists once; then the runbook block's statements in the same order
  (delete the three `PAYMENT_` kinds; restore `PRIMARY KEY (id)` and the two-kind check; drop
  `idx_payment_event_last_received`; delete history row 30). Header: run the stuck-order query
  first and keep its output; a redeploy re-alerts every order still inside the 24-hour window.

**D5. The test runs the files through `psql` in the Postgres container, not through JDBC.**
A first draft used Spring's `ScriptUtils`. It splits on `;`, so the guard's `DO $$ … $$` block
cannot be written; it stops at the first error and never sends `COMMIT`, so "nothing changed"
would be the harness closing the connection — and until then it would hold the script's table
lock and hang the assertions. The test executor has to be the operator's.

`HarnessTestConfig` gains one static helper: copy the file into the container and run
`psql -X -U <user> -d <database> [-v ON_ERROR_STOP=1] -f <file>`, with the scratch schema passed
as the `PGOPTIONS=-c search_path=<schema>` environment variable through Testcontainers'
`ExecConfig` (argv form, no shell string). It returns the exit code and stdout **and stderr** —
`psql` writes `ERROR:` to stderr. The helper itself refuses a schema named `public` or one that is
not a plain identifier. The test class wraps it: before a run it asserts the scratch schema
exists; after every run it asserts the context schema's `flyway_schema_history` still has a
successful row for every migration (so that check does not depend on test order). A `search_path`
naming only the scratch schema has no fall-through to `public`. Scratch schema names are fixed
literals (`rb_up`, `rb_down`, `rb_v30_data`, `rb_v30_fail`), distinct from the `v<n>_…` names the
existing class uses.

What still differs from the operator: `-f` on a copied file instead of stdin through
`docker compose exec -T` (same parser, same exit codes), and the server major (Risks).

**D6. One chain test for all scripts, plus V30's data tests.**
Flyway cannot migrate down, so a reference catalog for "before `V<n>`" has to come from going up.
The chain test (`MigrationRollbackIntegrationTest`, all `psql` runs with `ON_ERROR_STOP=1`):
1. *Up:* schema `rb_up` — migrate to 28 and snapshot; then to 29, 30, … latest `L`, snapshotting
   after each. That is the reference for every step.
2. *Down:* schema `rb_down` — migrate to `L`. For `n` from `L` to 29:
   - *refuses above:* if `n < L` this was shown already — while the schema stood at `n+1`, script
     `n` was run and failed, leaving it unchanged; for `n = L`, insert a synthetic successful
     history row for `L+1` (`installed_rank` max+1, plus the NOT NULL `description`, `type`,
     `script`, `installed_by`, `execution_time`, `success`), run script `L`, expect failure and
     no change, remove the row;
   - *restores:* run script `n` → exit 0; snapshot and applied versions equal the reference for
     the migration before `n`;
   - *refuses a second run:* run script `n` again → non-zero exit, unchanged.
3. *Re-apply:* migrate `rb_down` to `L`; every version from 29 is recorded successful and the
   snapshot equals the reference for `L`.

Every assertion is labelled with the script's file name. Cost: two scratch migrations in total,
plus about three `psql` runs per script — not two migrations per script.

The snapshot, every list sorted by name, is `information_schema.columns` (name, rank among the
table's current columns — not the raw ordinal, which Postgres never reuses, so a dropped and
re-added column would differ on re-apply — data type, character
length, numeric precision/scale, nullability, default), `pg_constraint` with
`pg_get_constraintdef`, `pg_indexes`, `information_schema.sequences`, `.views`, `.triggers`,
`.routines` — with the schema name replaced in every text column (constraint and default text
qualify cross-schema references). It does not cover extensions, enum types or grants; the spec
lists what is compared rather than claiming the schema is identical.

*Enforcement:* every `V<n>` (n ≥ 29) under `src/main/resources/db/migration` has a script and
every script names such a migration; a missing or empty `db/rollback` (relative to the Gradle
working directory, `backend/`) fails naming the working directory. *Shape:* with `--` comment
and blank lines stripped, the first line is `BEGIN;`, the last `COMMIT;`, no other line contains
either, and the second line sets `lock_timeout` (a plpgsql `BEGIN` inside the guard has no
semicolon and does not match). *Classpath:* no `db/rollback` resource.

*V30 with data* (the chain runs on empty tables and would pass a script that breaks on real
rows): migrate a schema to 30; seed an agreement and alerts of all five kinds, the three
`PAYMENT_` alerts sharing the `DUPLICATE_PAYMENT` alert's id — the case that breaks
`ADD PRIMARY KEY (id)` if the `DELETE` misses a kind. Statements run on a datasource whose URL
carries `?currentSchema=<scratch>`, because a `SET search_path` does not survive
`DriverManagerDataSource`'s per-call connections and the deployed build's insert is unqualified.
Negative control: that insert fails. Run the script; assert which rows survive and what it
printed; the insert now works (`DUPLICATE_PAYMENT`, new id → 1 row, existing id → 0).
*V30 failure:* same seed, drop `idx_payment_event_last_received`; run with `ON_ERROR_STOP` (non-zero
exit) and without (`ERROR` in the output); after each, the key, all five rows and the history row
remain. V29 needs no data test: it drops a table, and the chain proves the drop.

**D7. One generic rollback procedure in the runbook; the backend is not stopped.** *(user decisions, 2026-10-11)*
A new subsection under **Rollback**, "Rolling back across migrations". On the server, from
`deploy/`, with no deploy in flight:

1. **Pre-flight, changes nothing:** `./deploy.sh rollback`. If it rolls back, there was no schema
   step and you are done. Otherwise it refuses with "the database is at migration **N**, past
   *target*'s **M**". If M is below 28, stop: there are no scripts below V29 and the route is the
   dump restore it prints.
2. **Fresh dump** — the scripts destroy what the migrations added:
   ```sh
   f=/root/backups/pre-rollback-$(date -u +%Y%m%dT%H%M%SZ).sql.gz
   ( set -o pipefail; umask 077
     docker compose -f docker-compose.prod.yml exec -T postgres \
       sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | gzip > "$f.partial" ) \
     && gzip -dc "$f.partial" | tail -n 20 | grep -q 'PostgreSQL database dump complete' \
     && mv "$f.partial" "$f" && echo "dump ok: $f"
   ```
   **Go on only if it prints `dump ok`.** A failed `pg_dump` still leaves a small, valid gzip
   file, so size proves nothing; this checks both ends of the pipe and the completion trailer,
   as the deploy's own dump does (the trailer is looked for in the tail because pg_dump 17.6+
   writes a line after it). The dump is the whole database, unencrypted, on the same disk. Its
   name is outside the deploy's rotation pattern, so nothing rotates it away: delete it by hand
   once the rollback is settled. Anything the application writes after this point is not in it.
3. **Read the header** of each script from N down to M+1 and do what it asks first.
4. **Run the scripts, newest first, N … M+1**, keeping the output with the dump:
   ```sh
   docker compose -f docker-compose.prod.yml exec -T postgres \
     sh -c 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' \
     < ../backend/db/rollback/rollback-V<n>__<desc>.sql
   ```
   A script run out of order, or twice, refuses and changes nothing. One that times out on a lock
   changed nothing either — run it again.
5. **At once,** `./deploy.sh rollback`.

Stated beside the steps:
- **Do not stop the backend first.** `deploy.sh rollback` would then restart the release being
  left (register: `rollback-with-stopped-backend`).
- **Between 4 and 5** the running build is on a schema older than it expects and logs errors — for
  V29/V30, the staff-alert ledger ERROR and a sweep WARN every 30 seconds, and failed ledger
  writes. That is expected here.
- **If step 5 refuses before starting anything** (image missing, network change, lock — the
  stack is still on the release being left), restart the backend:
  `docker compose -f docker-compose.prod.yml restart backend`. Its Flyway re-applies every
  rolled-back migration, empty; the data is in the step-2 dump. Fix the cause and start again from
  step 1.
- **If step 5 fails after starting the target** (during `up` or a post-check), the target build is
  already running on the rolled-back schema. Do **not** restart and do **not** go back to step 1
  — the history's top is now a different release and step 1 would act on it. Follow what
  `deploy.sh` printed: when only the ERROR-line scan failed and the lines are benign,
  `./deploy.sh accept`; otherwise treat it as any failed rollback (section above).
- **If the backend restarts between 4 and 5** (`restart: unless-stopped`), it re-applies the
  migrations and step 5 refuses: run step 4 again. **If it is crash-looping**, that race cannot
  be won with the backend up. Look at the last line of `/var/lib/agreementmitra/deploys.log`:
  when the release being left never recorded `ok` (the usual case — a deploy that failed its
  wait), `docker compose -f docker-compose.prod.yml stop backend` first is safe, because
  `deploy.sh rollback` then targets the last good release. When it did record `ok`, stopping
  makes `rollback` restart that same release; there is no scripted route yet
  (`rollback-with-stopped-backend`) and the fallback is the dump restore.
- Redeploying the release later re-applies the migrations in the ordinary way.

Running against a live backend was chosen over stopping it because a stop needs a `deploy.sh`
change first, a rollback already means the release is bad, and the window is about a minute. The
V30 feature section loses its SQL block and its own steps, keeping two sentences: rolling back
past this release follows the generic procedure, and what is specific is in the script's header.

**D8. The scripts and the gate compose; nothing in `deploy/` changes.**
Each script deletes its history row, so after scripts N…M+1 the gate's `max(version)` is M and the
existing gate passes for a target whose ceiling is M. No automated test covers the composition
(the gate's tests live in `deploy/test` and know nothing of scripts); the Stage 6 manual recipe
runs both scripts against the local compose Postgres and checks the version the gate would read.

**D9. A new test class, `MigrationRollbackIntegrationTest`.**
Same annotations as `FlywayMigrationIntegrationTest`, so Spring's context cache reuses the context.

## Risks / Trade-offs

- [A script is right for an empty schema but wrong for production's rows] → the spec requires a
  seeded test wherever a script deletes or rewrites rows in a surviving table; V30 has one.
- [Rolling back an additive migration destroys what it added — V29 drops the ledger] → the fresh
  dump in step 2; each header says what is lost. *(user accepted, 2026-10-11)*
- [The destructive steps commit before `deploy.sh rollback` is known to succeed] → the pre-flight
  proves the target and the gate; the remaining failures have a written recovery that needs no
  restore of the schema — restart the backend.
- [The live build errors between the scripts and the rollback] → about a minute; for a future
  migration it could be user-facing. `rollback-with-stopped-backend` is the route to a stop-first
  procedure.
- [Every open change with a migration now needs a script] → intended; the test fails with the
  missing file's name.
- [The harness runs Postgres 16, production 17] → pre-existing drift, not this CR's; none of the
  statements or catalog views used differ between the two. Pinning the harness to 17 is a one-line
  change tried as a separate change set during apply, kept only if the suite stays green.
- [Suite time grows with every migration] → one incremental migrate and about three `psql` runs
  per script. Measure in apply; prune scripts for long-superseded releases if it ever shows.

## Migration Plan

No schema change. The scripts reach the server checkout with the release that carries V29 and
V30, so they are present whenever those migrations are applied. After a rollback the checkout is
detached at the target, where they do not exist — and are not needed.

## Open Questions

None.
