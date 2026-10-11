## ADDED Requirements

### Requirement: Every migration from V29 ships a rollback script
Every migration `V<n>__<description>.sql` with `n` of 29 or higher SHALL ship, in the same change, a rollback script at `backend/db/rollback/rollback-V<n>__<description>.sql`.

The script SHALL:

- live outside the application classpath, so it is not packaged and no Flyway `locations` setting
  can resolve it;
- be plain SQL that `psql` runs from a file, opening with `BEGIN` and ending with `COMMIT`, so a
  failure part-way leaves the database as it found it whether or not `psql` stops on the error;
- refuse to run — raising an error before changing anything — unless `<n>` is the highest
  successfully applied version in `flyway_schema_history`;
- set a lock timeout as its first statement after `BEGIN`, so that run against a live application
  it fails rather than queues;
- use unqualified object names, so it acts on the connection's current schema;
- restore the schema `V<n>` changed to its state before `V<n>`, dropping what `V<n>` created and
  deleting rows the earlier schema cannot hold;
- delete the `flyway_schema_history` row for version `<n>`, so the release can be deployed again
  and Flyway re-applies `V<n>`;
- where it deletes rows from a table that remains, print them first, identifying an agreement by
  its tracking reference and never by its id;
- open with a comment stating that it is destructive, what data it destroys, and anything an
  operator must do or expect that is specific to this migration.

Migrations remain forward-only: a rollback script is an operator-run file for rolling the
application back, not a Flyway migration, and a defective migration is still corrected by a later
`V<n>`. Migrations below V29 have no rollback script.

#### Scenario: Rollback scripts are not on the classpath
- **GIVEN** the application's runtime classpath
- **WHEN** the resource `db/rollback` is looked up
- **THEN** it is absent

#### Scenario: Every migration from V29 has a rollback script, and every script a migration
- **GIVEN** the migrations under `db/migration` and the files under `backend/db/rollback`
- **WHEN** their names are compared
- **THEN** each migration `V<n>__<description>.sql` with `n` of 29 or higher has `rollback-V<n>__<description>.sql`
- **AND** each rollback script is named for such a migration

#### Scenario: Every rollback script opens and commits one transaction with a lock timeout
- **GIVEN** a rollback script
- **WHEN** its text is checked ignoring comment lines and blank lines
- **THEN** its first line is `BEGIN;`, its last is `COMMIT;`, and neither appears anywhere else
- **AND** its second line sets `lock_timeout`

#### Scenario: A rollback script refuses when its migration is not the latest applied
- **GIVEN** a schema whose latest applied migration is above `V<n>`, and a schema on which the script for `V<n>` has already run
- **WHEN** the rollback script for `V<n>` is run against each
- **THEN** each run fails
- **AND** each schema's catalog and its `flyway_schema_history` are unchanged

### Requirement: Every rollback script is executed by a test
The container-backed test suite SHALL run every rollback script from its file, through `psql`, newest first, against a scratch schema migrated to the latest version, and SHALL fail unless after each script the catalog equals the one the same migrations produced on the way up, and a forward migration then re-applies every rolled-back version.

Catalog equality SHALL compare, across the whole scratch schema with the schema name normalised:
columns (name, order among the table's current columns, type with length and precision,
nullability, default), constraints,
indexes, sequences, views, triggers and routines, and the applied versions in
`flyway_schema_history`. The test SHALL NOT alter the schema the application context uses.

Where a script deletes or rewrites rows in a table that remains, a test SHALL also run it against
seeded rows and, where the previous release fails on a specific statement, SHALL show that
statement failing before the script and succeeding after.

#### Scenario: Scripts run newest first restore each earlier catalog
- **GIVEN** the catalog recorded after each migration from V28 to the latest, and a scratch schema migrated to the latest
- **WHEN** the rollback scripts are run from their files, newest first, down to V29
- **THEN** after the script for `V<n>` the schema's catalog equals the one recorded after the migration before `V<n>`
- **AND** its `flyway_schema_history` holds no row for version `<n>` or above

#### Scenario: The migrations re-apply after their rollback
- **GIVEN** a scratch schema on which every rollback script has run
- **WHEN** Flyway migrates it forward to the latest version
- **THEN** every version from 29 up is applied and recorded as successful
- **AND** the catalog equals the one recorded for the latest version

#### Scenario: The context schema is untouched
- **GIVEN** the application context's schema is migrated to the latest version
- **WHEN** a rollback script has been run against a scratch schema
- **THEN** the context schema's `flyway_schema_history` still holds a successful row for every migration

#### Scenario: The previous release's staff alert insert works only after rolling back V30
- **GIVEN** a schema at V30 holding an `ORDER_PAID` alert, a `DUPLICATE_PAYMENT` alert, and `PAYMENT_NOT_RECORDED`, `PAYMENT_ID_REUSED` and `PAYMENT_AMOUNT_MISMATCH` alerts that share the `DUPLICATE_PAYMENT` alert's id
- **AND** an insert ending `ON CONFLICT (id) DO NOTHING` fails against it
- **WHEN** the V30 rollback script is run
- **THEN** the `ORDER_PAID` and `DUPLICATE_PAYMENT` rows remain unchanged and the other rows are gone
- **AND** the script's output lists the three deleted alerts by tracking reference and contains no agreement id
- **AND** that insert succeeds for a new id and inserts nothing for an existing id

#### Scenario: A rollback script that fails part-way changes nothing
- **GIVEN** a schema at V30 holding alerts of every kind, from which `idx_payment_event_last_received` has been dropped so the script's `DROP INDEX` fails after its earlier statements
- **WHEN** the V30 rollback script is run through `psql`, once stopping on the error and once not
- **THEN** each run reports the error
- **AND** `staff_alert` keeps its `(id, kind)` key and every row, and the `flyway_schema_history` row for version 30 remains
