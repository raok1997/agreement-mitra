# production-deploy Specification

## Purpose
TBD - created by archiving change scripted-prod-deploy. Update Purpose after archive.
## Requirements
### Requirement: Only the head of main decides trust
`deploy/deploy.sh` SHALL take its deploy logic from the head of `origin/main`. When invoked from any other copy, it SHALL fetch, and re-execute `origin/main:deploy/deploy.sh` unless that is byte-identical to itself. Before running any code taken from the target commit, it SHALL verify that:
- the argument is 7–40 lowercase hex characters;
- the argument resolves to a commit whose full hash starts with it;
- the commit is reachable from `origin/main`;
- the checkout and `.git` are root-owned and not group- or world-writable.

The second stage SHALL refuse to run unless its own directory is a deploy work directory under `/tmp`. It SHALL install its cleanup only after that directory check passes, and SHALL then repeat the format and ancestry check.

#### Scenario: Commit not on main
- **WHEN** the given hash is on a branch not merged into `origin/main`
- **THEN** the deploy exits non-zero before extracting or executing any file from that commit

#### Scenario: Option-shaped argument
- **WHEN** the argument is `--output=/tmp/x`, `origin/feature` or an upper-case hex string
- **THEN** the deploy exits non-zero on the format check, and git is never called with it

#### Scenario: Stale or modified entry script
- **WHEN** the checkout's `deploy/deploy.sh` differs from the head of `origin/main`
- **THEN** the head of `origin/main`'s copy is what runs

#### Scenario: Direct second-stage call
- **WHEN** `bash deploy/deploy.sh --stage2 <hash on main>` is run from the checkout
- **THEN** it exits non-zero before preflight, and deletes nothing

### Requirement: Deploy refuses on a failed preflight
The deploy SHALL run every preflight check against the target commit's files before it backs up, checks out, builds or restarts anything. It SHALL exit non-zero on the first failure, leaving the running stack untouched. The checks are:
- the tracked tree is clean;
- the build contexts (`backend`, `frontend`, `docker`) hold no untracked or ignored files;
- the target does not track `deploy/env/*.env`, `deploy/.env` or anything under `deploy/certs/`;
- the env contract holds for `backend`, `postgres`, `minio` and `web-build` env files against the target's templates (see "Env contract fails closed");
- the target's compose subnet overlaps no other Docker network's IPv4 subnet;
- the target's network subnet and ip range match the running network, or `--allow-downtime` is given;
- the database's applied migrations do not exceed the target's;
- the target is not a strict ancestor of the running commit, unless `--allow-downgrade` is given;
- `postgres` is running;
- the target is not the running tag, unless `--refresh-base` is given;
- no other deploy, rollback or accept holds the lock.

`--dry-run` SHALL run the preflight, report each check's result, and exit without writing any state or log line.

#### Scenario: Dirty deploy checkout
- **WHEN** a tracked file in `/opt/agreementmitra` has uncommitted changes
- **THEN** the deploy exits non-zero and names the modified paths

#### Scenario: Stray file in a build context
- **WHEN** an untracked `backend/src/main/resources/db/migration/V27__x.sql` exists
- **THEN** the deploy exits non-zero naming it, before any build

#### Scenario: Older commit behind the schema
- **WHEN** the target is a `main` commit whose highest migration is below the database's applied version
- **THEN** the deploy exits non-zero before backing up

#### Scenario: Downgrade without consent
- **WHEN** the target is a strict ancestor of the running commit and `--allow-downgrade` is absent
- **THEN** the deploy exits non-zero and points at `rollback`

#### Scenario: Concurrent run
- **WHEN** a deploy starts while another deploy, rollback or accept holds the lock
- **THEN** it exits non-zero at once

#### Scenario: Dry run writes nothing
- **WHEN** the deploy runs with `--dry-run` on a box with no deploy history
- **THEN** it reports each check and exits, and afterwards no state directory, log or `deploy/.env` exists

### Requirement: Env templates declare every key's contract
Every assigned key in `deploy/env/{backend,postgres,minio,web-build}.env.example` SHALL carry, on the line directly above it, a tag `#@ <kind> <class> <need> [pattern=<ERE>] [match=<file>:<KEY>]`, where kind is `secret` or `config`, class is `fixed`, `setting`, `generated` or `vendor`, and need is `required`, `optional` or `required-if=<KEY>=<VALUE>`. A `fixed` key SHALL be `config` with a non-blank template value. A `generated` key SHALL be `secret required`. A `required-if` SHALL name a `config` key in the same file; the tagged key itself may be `secret` or `config`. The template lint SHALL fail on any key that breaks these rules.

#### Scenario: Untagged key
- **WHEN** a template assigns a key with no tag line above it
- **THEN** the template lint fails naming the key, and a deploy to that commit refuses at preflight

#### Scenario: Secret used as a condition
- **WHEN** a `required-if` names a key tagged `secret`
- **THEN** the template lint fails

### Requirement: Env contract fails closed
The deploy SHALL compute a verdict for every templated key and refuse when any verdict is `missing`, `duplicate`, `blank`, `placeholder`, `pattern`, `drift` or `mismatch`:
- `missing`: the key is absent from the server file;
- `duplicate`: the key is assigned more than once in the server file (the last assignment would silently win);
- `blank`: a `required` key, or a `required-if` key whose condition holds on the server, is empty, whitespace, `""` or `''`;
- `placeholder`: a non-blank `secret` matches the placeholder list, including `__GENERATED_ON_SERVER__`;
- `pattern`: a non-blank value fails its `pattern`;
- `drift`: a `fixed` value differs from the template's;
- `mismatch`: a `match` key differs from the key it names.

A blank `optional` key, or a `required-if` key whose condition does not hold, SHALL be reported as information only. A `setting` key SHALL NOT be compared with its template value. The deploy SHALL print key names and verdicts only, SHALL print the `provision.sh secrets --templates` fix, for the target's templates, on refusal, and SHALL NOT prompt.

#### Scenario: Missing env key from the target commit
- **WHEN** the target commit's `backend.env.example` assigns a key that `backend.env` lacks, while the checkout's example does not assign it
- **THEN** the deploy exits non-zero, printing the key name and a `provision.sh secrets --templates` command for the target's templates, and no value

#### Scenario: Required secret blank
- **WHEN** `ZOOP_API_KEY` is tagged `secret vendor required` and is blank in `backend.env`
- **THEN** the deploy exits non-zero with `ZOOP_API_KEY: blank`, and no value is printed

#### Scenario: Conditional key
- **WHEN** `RZP_KEY_SECRET` is `required-if=PAYMENT_MODE=REQUIRED` and blank
- **THEN** the deploy refuses when `PAYMENT_MODE=REQUIRED`, and reports it as information only when `PAYMENT_MODE=DISABLED`

#### Scenario: Fixed value changed by the template
- **WHEN** the target template's `fixed` `DB_URL` differs from the server's
- **THEN** the deploy exits non-zero with `DB_URL: drift`

#### Scenario: Setting changed on the server
- **WHEN** the server sets `RULES_STAMP_DUTY_ALLOW_UNREVIEWED=false`, a `setting` whose template value is `true`
- **THEN** the env contract passes

#### Scenario: Placeholder left in
- **WHEN** `POSTGRES_PASSWORD=__GENERATED_ON_SERVER__` or `AUTH_HASH_PEPPER=dev-only-identity-pepper-change-me`
- **THEN** the deploy exits non-zero with verdict `placeholder`

#### Scenario: Credentials out of step
- **WHEN** `DB_PASSWORD` differs from `postgres.env`'s `POSTGRES_PASSWORD`
- **THEN** the deploy exits non-zero with `DB_PASSWORD: mismatch`, printing neither value

### Requirement: One interactive fill step
`provision.sh secrets` SHALL be the only interactive step. After merging missing template lines and generating blank `generated` keys, on a TTY it SHALL ask for each key whose verdict is not `ok`, in template order:
- a `secret` SHALL be read without echo and never placed in argv;
- an `optional` key SHALL accept an empty answer as intentionally blank;
- a value failing `pattern` SHALL be asked again;
- a `drift` SHALL show the server and template values and apply the template's only on `y`;
- a `mismatch` SHALL be reported with its fix and never resolved by copying a secret.

It SHALL write each env file atomically with mode `0600`, rejecting a value that contains a newline, and SHALL store a typed value so compose reads it literally: single-quoted when it holds `$`, `#`, a quote, a backslash or edge whitespace, and refused when it holds a single quote. Reading a value SHALL remove one pair of surrounding quotes, as compose does. It SHALL print a summary of generated, set, optional-blank and still-failing keys by name, and exit non-zero while any failing verdict remains. Off a TTY it SHALL only report.

#### Scenario: Secret typed in
- **WHEN** the operator answers the prompt for a blank `secret vendor required` key
- **THEN** the value is not echoed, the file is rewritten `0600` with it, and the summary lists the key as set

#### Scenario: Optional skipped
- **WHEN** the operator presses Enter for `GOOGLE_OAUTH_CLIENT_ID`
- **THEN** it stays blank, the summary lists it as optional-blank, and the exit is zero if nothing else fails

#### Scenario: Not a terminal
- **WHEN** `provision.sh secrets` runs with stdin not a TTY and a required key is blank
- **THEN** it prompts for nothing, names the key, and exits non-zero

### Requirement: Deploy never exposes secrets
The deploy and smoke scripts SHALL NOT print, log or store the value of any variable from `deploy/env/*.env`. The fill step SHALL NOT print the value of any `secret` key. They:
- SHALL NOT run with shell tracing;
- SHALL render compose config only with `--no-env-resolution`;
- SHALL call `docker inspect` only with a format template;
- SHALL run `pg_dump` and `psql` inside the Postgres container, with the credential variables expanded there.

They SHALL print key names only when they match `^[A-Z][A-Z0-9_]*$`, SHALL redact cookie values, and SHALL strip control characters from server-derived text. Any command they run on the server over SSH SHALL be built only from validated tokens.

#### Scenario: Env comparison output
- **WHEN** the env-key check reports missing or extra keys
- **THEN** its stdout and stderr contain key names only

#### Scenario: Forbidden constructs absent
- **WHEN** the scripts are linted
- **THEN** they contain no `set -x`, no compose `config` without `--no-env-resolution` or `--quiet`, and no `docker inspect` without `-f`

#### Scenario: Hostile trace response
- **WHEN** the address returned by `cdn-cgi/trace` is not a valid dotted-quad IPv4
- **THEN** the forged-header check reports inconclusive and runs nothing over SSH

### Requirement: Database dump precedes every change
After preflight, the deploy SHALL dump Postgres to a `.partial` file in `/root/backups`. The directory SHALL be made root-owned `0700` first. Both sides of the dump pipeline SHALL succeed, and the dump SHALL be valid gzip, contain a `COPY` section, and carry the `pg_dump` completion trailer within its last lines (pg_dump 17.6+ writes `\unrestrict` after it), before the deploy renames it to `pre-deploy-<YYYYMMDDTHHMMSSZ>-<tag>.sql.gz` (mode `0600`). A failed dump SHALL leave no file behind and stop the deploy.

Rotation SHALL consider only names matching that exact pattern. It SHALL keep the ten newest and delete any older than 30 days. It SHALL never delete the dump just taken, nor a dump recorded by a deploy whose images are still kept for rollback. Every printed dump path SHALL be checked to exist, and SHALL be described as a same-disk rollback point that excludes object storage.

#### Scenario: Dump written and verified
- **WHEN** preflight passes
- **THEN** a dump with the documented name and mode `0600` exists, and the log line records its file name

#### Scenario: Truncated dump
- **WHEN** the dump lacks the completion trailer, is invalid gzip, or either side of the pipeline fails
- **THEN** the deploy exits non-zero before checkout, and neither the final name nor the `.partial` file remains

#### Scenario: Rotation leaves other files alone
- **WHEN** rotation runs over eleven script dumps plus `pre-deploy-20261006-1423.sql.gz`, `backend.env.20261006-1423` and `provision-firewall-fix.patch`
- **THEN** only the oldest script dump is deleted

#### Scenario: Age cap spares rollback restore points
- **WHEN** a script dump is older than 30 days and is recorded by a deploy still in the rollback keep-set
- **THEN** rotation keeps it, while an equally old dump of a deploy outside the keep-set is deleted

### Requirement: Images are built per tag and what runs is read from the containers
The deploy SHALL build `backend`, `caddy` and `gotenberg` as `agreementmitra-<service>:<tag>`, where `<tag>` is the commit's 12-character hash, or that hash with a refresh suffix when `--refresh-base` is given. It SHALL take the running tag from the image references of the three built services' containers: the tag when all three agree, `unknown` for pre-script images, and `mixed` otherwise. A deploy SHALL refuse while the state is `mixed`, and SHALL refuse a refresh tag equal to the running tag. Before every `up`, it SHALL atomically write `DEPLOY_TAG=<tag>` as the sole line of `deploy/.env`. The compose file SHALL require `DEPLOY_TAG`. Builds SHALL use BuildKit. `up` SHALL neither build nor pull. Base images SHALL be pulled only with `--refresh-base`. The deploy SHALL record the MinIO release before the restart and fail the post-check if it differs afterwards.

#### Scenario: Manual up after a scripted deploy
- **WHEN** an operator runs `docker compose -f docker-compose.prod.yml up -d backend` after a scripted deploy
- **THEN** compose uses the deployed tag from `deploy/.env`

#### Scenario: Refresh keeps the rollback target
- **WHEN** the running commit is redeployed with `--refresh-base`
- **THEN** the new images carry a distinct refresh tag, and the previous images are untouched

#### Scenario: MinIO release changes
- **WHEN** the MinIO release parsed after the restart differs from the one recorded before it
- **THEN** the post-check fails and names both releases

### Requirement: Downtime only by consent
The deploy SHALL restart with `up -d`. When the target network's subnet or ip range differs from the running network's, it SHALL require `--allow-downtime`, then run `down` before `up`. It SHALL never pass `-v`, `--volumes`, `--rmi` or `--remove-orphans` to `down`.

#### Scenario: Network unchanged
- **WHEN** the running network matches the target's declared subnet and ip range
- **THEN** the deploy does not run `down`

#### Scenario: Network changed without consent
- **WHEN** the target's ip range differs from the running network's and `--allow-downtime` is absent
- **THEN** preflight fails, explaining that a full stop is needed

### Requirement: Post-deploy verification
After `up` the deploy SHALL wait, bounded by a timeout, for every service with a healthcheck to report healthy. It SHALL then verify:
- the database's highest applied migration equals the target's highest migration;
- the `caddy` container holds `10.203.17.10`;
- the MinIO release is unchanged;
- no backend log line at level ERROR since the container started. Only the count and the sanitised logger names are reported.

On any failure, including the wait timing out, it SHALL exit non-zero, record the failing check as the outcome, and print the rollback command.

#### Scenario: Healthy deploy
- **WHEN** all services report healthy and every check passes
- **THEN** the deploy exits zero and prints the deployed commit and migration version

#### Scenario: Health timeout
- **WHEN** the backend is not healthy within the timeout
- **THEN** the deploy exits non-zero, `deploy/.env` names the new tag, and the rollback command is printed

#### Scenario: ERROR line after start
- **WHEN** the backend logs a line at level ERROR after starting
- **THEN** the post-check fails with outcome `postcheck:errors`, printing the logger name and never the message

### Requirement: Every locked run is recorded
Every deploy that passes preflight, every rollback and accept that passes its gates, and the one-time seed, SHALL append one line to `/var/lib/agreementmitra/deploys.log` (directory `0750`, file `0640`, root-owned), including when a later step fails. A run refused at preflight or at its gates SHALL write no line. The line SHALL carry:
- the UTC time and the action;
- the previously running tag;
- the target's full hash and tag;
- the post-run migration version and the MinIO release;
- the built services' image IDs;
- the dump file name;
- the outcome (`ok`, or the failing step).

Image cleanup SHALL run after the outcome is final, and a cleanup failure SHALL only warn.

#### Scenario: Successful deploy recorded
- **WHEN** a deploy completes
- **THEN** the last line names the previous tag, the target, the image IDs and the dump, with outcome `ok`

#### Scenario: Post-check failure recorded
- **WHEN** a deploy fails at a post-check
- **THEN** the last line names that check as the outcome

### Requirement: Rollback walks back through the running-version history
`deploy/deploy.sh rollback` SHALL replay the log's `ok` lines as a history: seed, deploy and accept push their tag unless it equals the top, an accept of a tag already in the history acts as a rollback to it, and a rollback to T pops until T is on top. Whether a target is the seed, and its ceiling, SHALL be read from its seed line. If the running tag equals the top, rollback SHALL pop it and target the new top; otherwise it SHALL target the top. With no target it SHALL refuse. It SHALL refuse when:
- the database's applied migrations exceed the target's ceiling (the target's tree, or for a seed the migration version recorded with it), printing the recorded restore dump after checking it exists;
- any target image is missing or its ID differs from the one recorded.

When the target declares a different network subnet or ip range than the running one, it SHALL require `--allow-downtime` and then run `down` before `up`. The restore dump it prints SHALL be the first recorded line, whatever its outcome, whose migration version exceeds the ceiling. It SHALL check out the target commit (except for a seed), write `deploy/.env`, restart without building or pulling, run the post-deploy verification, and be recorded.

#### Scenario: Repeated rollback walks back
- **WHEN** deploys A, B, C succeeded and rollback runs twice
- **THEN** the first rollback targets B and the second targets A

#### Scenario: Rollback after a failed deploy keeps the good entry
- **WHEN** A and B succeeded, C failed after `up`, rollback runs, and then D succeeds and rollback runs again
- **THEN** the first rollback targets B, and the second also targets B

#### Scenario: Rollback across a migration
- **WHEN** the database has a migration the target does not contain
- **THEN** rollback exits non-zero without restarting anything, and prints the recorded dump path

#### Scenario: Retagged image
- **WHEN** a target image exists but its ID differs from the one recorded for that tag
- **THEN** rollback exits non-zero naming the image

#### Scenario: Nothing to roll back to
- **WHEN** the history holds only the running entry
- **THEN** rollback exits non-zero with a message and changes nothing

### Requirement: First scripted deploy seeds the history
When the log holds no `ok` line, a real deploy SHALL tag each running built service's image (by the container's image ID) with the checkout's 12-character tag. It SHALL record a `seed` line carrying the current migration version and image IDs, and write `deploy/.env` with that tag, before building. A dry run SHALL NOT seed.

#### Scenario: Seed survives a preceding dry run
- **WHEN** a dry run is followed by the first real deploy
- **THEN** the real deploy seeds, and a rollback straight after it targets the seed

### Requirement: Accept only a benign post-check failure
`deploy/deploy.sh accept` SHALL record the running tag as `ok` only when the last line is a deploy or rollback to the running tag that failed with outcome `postcheck:errors`, and SHALL first re-run the other post-checks and refuse if any fails.

#### Scenario: Accept after an ERROR line
- **WHEN** deploy B failed only the ERROR scan and the operator runs accept
- **THEN** B is recorded `accept … outcome=ok`

#### Scenario: Accept refused for other failures
- **WHEN** the last deploy failed with `postcheck:caddy`, `postcheck:minio` or `postcheck:migrations`
- **THEN** accept exits non-zero and records nothing

### Requirement: Image retention follows the history
After a successful deploy, the deploy SHALL remove `agreementmitra-<service>:<tag>` images whose tag is neither running nor among the top three distinct history entries. It SHALL remove them by exact name and without force.

#### Scenario: Failed builds pruned, rollback target kept
- **WHEN** A succeeded, B, C and D failed after building, and E succeeded
- **THEN** after E, A's images remain, and B, C and D's are removed

### Requirement: External smoke check
`deploy/smoke-prod.sh [base-url]` SHALL run from outside the server and exit non-zero on any failed check. It SHALL check that:
- the apex answers 200, and `www` answers a 301 to the apex;
- an `/api` POST over 1 MiB answers 413;
- `GET /api/templates` sets `__Host-XSRF-TOKEN` with `Secure` and `SameSite=Lax`;
- `/api/templates` and `/api/templates/form?state=KA&type=residential` answer `cf-cache-status: DYNAMIC`;
- unsigned POSTs to both webhook paths answer 401;
- no response carries `cf-mitigated`.

A redirect to Cloudflare Access SHALL fail with a message naming Access. The check SHALL create no agreement and send no agreement id.

#### Scenario: All checks pass
- **WHEN** the site is correctly deployed behind Cloudflare
- **THEN** each check prints PASS and the script exits zero

#### Scenario: API response edge-cached
- **WHEN** a checked `/api` response reports a `cf-cache-status` other than `DYNAMIC`
- **THEN** that check prints FAIL with the observed status, and the script exits non-zero

### Requirement: Forged-header check is opt-in
`deploy/smoke-prod.sh --forged-header` SHALL run after the other checks. It SHALL first warn that the caller's /24, including anyone sharing it, is locked out of default-class routes for about five minutes, and SHALL proceed only on confirmation or `--yes`. It SHALL send its burst with forged `X-Forwarded-For` and `Forwarded` headers concurrently. It SHALL read over SSH only the lockout events logged since the burst began whose source is the caller's /24 or the forged /24. It SHALL pass only when the caller's /24 was locked out.

#### Scenario: Forged header ignored
- **WHEN** a lockout event since the burst names the caller's /24
- **THEN** the check passes

#### Scenario: Forged header honoured
- **WHEN** a lockout event since the burst names the forged /24
- **THEN** the check fails, reporting that the application trusted a client-supplied header

#### Scenario: No lockout observed
- **WHEN** no matching lockout event appears
- **THEN** the check fails as inconclusive rather than passing

