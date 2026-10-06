## Context

Production is one Contabo VPS: Ubuntu 24.04, git 2.43, Docker Compose v5.3.1, with `flock`, `jq` and `gzip` present. It runs `deploy/docker-compose.prod.yml` from a git checkout at `/opt/agreementmitra`.

- **Images:** the three built services (`backend`, `caddy`, `gotenberg`) take compose's implicit names `agreementmitra-<service>:latest`, so every build overwrites the image the stack is running.
- **Base images:** the Dockerfiles build from floating tags (`eclipse-temurin:21-jre`, `node:20-alpine`, `caddy:2-alpine`, `gotenberg/gotenberg:8`). `postgres:17` and `minio/minio:latest` are pulled only when someone runs `pull`.
- **Disk:** 168 GB free. Gotenberg's image is 2.5 GB, but a rebuild with unchanged layers shares them.

The 2026-10-06 deploy is the reference run. Its steps and gaps are listed in the proposal. The user approved git operations on the server (2026-10-06). The local `guard-main-branch` hook blocks a hand-typed `git checkout` in the operator's shell, so the git step lives inside a versioned script.

**Trust boundary:** anyone who can merge to `main` gets root code execution on production, because `deploy.sh` always runs the head of `main`'s deploy code as root (D2). Branch protection on `main` is a production control. Closing SSH password auth (DEPLOYMENT.md §9 item 2) matters more once deploys are routine.

**Scope of operation:** the script operates an already-provisioned box with the stack running. First bring-up on a fresh box stays the manual procedure in DEPLOYMENT.md §4. The script refuses when `postgres` is not running.

## Goals / Non-Goals

**Goals:**
- One command deploys a commit from `main`, and refuses before touching anything when the box is not in a deployable state.
- Every deploy leaves a verified dump, a log line, and a rollback target.
- An outside-in check proves what Cloudflare and Caddy actually do.

**Non-Goals:**
- CI-triggered deploys (CR-7).
- Zero-downtime deploys.
- Automatic database restore.
- Proving a storage write (the storage-health-indicator CR).
- Configuring Cloudflare.
- Validating config inside the JVM. Refusing to start on bad config stays with `prod-readiness-preflight`; this change is the deploy-time half (D14).
- Proving a credential works (an SMTP login, a Razorpay API call). That is the application's readiness, not a shell check.
- Fresh-box bring-up.
- Off-box or encrypted backups (DEPLOYMENT.md §8).

## Decisions

### D1. Bash on the server
The work is a sequence of `docker compose` and `git` calls on one host. Bash with `set -euo pipefail` needs nothing installed and is what CI would invoke over SSH.
- *Alternatives:* Ansible (a new dependency for one host), or a laptop-side script (a dropped connection leaves a half-run deploy).

### D2. Three steps; the trust code is always the head of `main`
- **Entry** (whatever file was invoked: the checkout's `deploy.sh`, or a piped copy). It is self-contained and sources nothing.
  1. Check the argument against an inlined `^[0-9a-f]{7,40}$`.
  2. Check the checkout and `.git` are root-owned and not group- or world-writable. `.git/config` and hooks run code as root on any git call.
  3. `git fetch origin`.
  4. Write `git show origin/main:deploy/deploy.sh` and `deploy/lib/checks.sh` to `/tmp/agreementmitra-main.XXXXXXXX` and `exec bash` it with `--from-main`, always (so stage 1, rollback and accept also source main's lib); on that second pass it must be byte-identical to `origin/main`'s copy, or it refuses rather than loops. (Changed at apply from "re-exec only on a mismatch", so the lib is main's too.)
  - So a stale, older or locally modified `deploy.sh` never decides trust.
- **Stage 1** (`main`'s head code), for a deploy:
  1. Run `git rev-parse --verify --end-of-options "<c>^{commit}"`, and check the resolved hash starts with the argument. A hex-looking tag name cannot then substitute another commit.
  2. Check `git merge-base --is-ancestor -- <full> origin/main`.
  3. If the target lacks `deploy/deploy.sh`, refuse: "target predates scripted deploy; use rollback".
  4. Extract into the work directory, `mktemp -d /tmp/agreementmitra-deploy.XXXXXXXX`, with this fixed layout:
     - `deploy.sh`
     - `lib/checks.sh`
     - `docker-compose.prod.yml`
     - `backend.env.example`
  5. Run `exec bash "$work/deploy.sh" --stage2 [flags] <full> </dev/null`. Use `exec bash` because extracted files are not executable, and redirect stdin so a piped bootstrap's text is not consumed.
- **Stage 2** (the target's code):
  1. Before anything else, assert that its own directory matches `^/tmp/agreementmitra-deploy\.[A-Za-z0-9]{8}$`. A direct `bash deploy/deploy.sh --stage2 …` therefore refuses, and can never run preflight on the checkout's files or install a cleanup trap over `deploy/`.
  2. Install `trap 'rm -rf -- "$work"'`, only after that assertion.
  3. Re-check the format and ancestry.
  4. Reject unknown flags.
  - **Stable contract:** `--stage2`, the flag set, one full hash, the fixed layout.
- **`rollback` and `accept`** run in stage 1 (`main`'s head code). They take no target commit, and must work when the target predates the script.
- **Every script** ends in `main "$@"; exit $?`.
- **Bootstrap:** `git fetch origin && git show origin/main:deploy/deploy.sh | bash -s -- <hash>`.

### D3. Images per tag; "running" is read from the containers
- **Tag:** the 12-character hash (`--short=12`). The exception is `--refresh-base`, which tags `<tag12>-r<YYYYMMDDHHMM>` so a refresh never overwrites a rollback target.
- **Compose:** each built service gets `image: agreementmitra-<service>:${DEPLOY_TAG:?…}`.
- **What is running** is read from the three built services' container image references (all agree → the tag; all pre-script → `unknown`; otherwise `mixed`, a deploy that failed part-way through `up` — deploy refuses, rollback targets the history's top). The backend's reference is:
  - `docker inspect -f '{{.Config.Image}}'` on `$(dc ps -q backend)`, parsed against `^agreementmitra-backend:([0-9a-f]{12}(-r[0-9]{12})?)$`;
  - a pre-script `:latest` image, or no container, means "unknown".
  - This is the single source. `deploy/.env` is derived from it.
- **`deploy/.env`** holds exactly one line, `DEPLOY_TAG=<tag>`. It is written atomically (temp file plus `mv`, `0600`) **before** every `up`, so compose, manual commands and a timed-out `up` all agree. It is read only with `^DEPLOY_TAG=…$` and never sourced.
- **One compose wrapper:** `dc()` always uses `-f "$work/docker-compose.prod.yml" --project-directory /opt/agreementmitra/deploy`. `DEPLOY_TAG` is exported at stage-2 entry as the running tag, which makes `:?` satisfiable for `ps`, `exec` and `logs` before any `.env` exists. It is replaced with the target tag at build time.
- **No manual builds:** a manual `--build` outside the script is forbidden by DEPLOYMENT.md (the compose header says the same). After a rollback (D9) HEAD equals what runs, so even a stray `--build` rebuilds the running code.
- *Alternative rejected:* `${DEPLOY_TAG:-latest}`, which silently downgrades after the first scripted deploy.

### D4. `up -d --no-build --pull never --wait --wait-timeout 300`; base images refresh only on request
- `DOCKER_BUILDKIT=1` is forced, because the legacy builder ignores the `Dockerfile.web.dockerignore` allowlist.
- `--refresh-base` runs `build --pull` with a distinct tag (D3). The monthly cadence goes in DEPLOYMENT.md.
- Deploying the running commit again is refused unless `--refresh-base` is given.

### D5. Network checks read the target's compose and normalise both shapes
- **Declared:** `dc config --no-env-resolution --format json | jq` over `.networks.default.ipam.config`, normalised to `subnet`/`ip_range`.
- **Running:** `docker network inspect -f '{{json .IPAM.Config}}'`, normalised from `Subnet`/`IPRange`.
- **Change check:** `network_change` compares the normalised pair. A missing network means no change. A change requires `--allow-downtime`, then `down` (never with `-v`, `--volumes`, `--rmi` or `--remove-orphans`).
- **Collision check:** `cidr_overlap` covers IPv4 subnets only; networks with no subnet or an IPv6 subnet are skipped.
- **Fixtures** are captured verbatim from both commands on the server.

### D6. Pure logic in `deploy/lib/checks.sh`, portable, fixture-tested
Every decision over text is a lib function, tested by `deploy/test/checks.test.sh` with fixtures.
- **Portable:** the tests run on the operator's Mac (bash 3.2, BSD tools) and on the server (bash 5, GNU). So the lib uses POSIX ERE (`[[:space:]]`, not `\s`), no `date -d`, no `mapfile` and no associative arrays.
  - Age rotation compares fixed-width name timestamps against a cutoff string computed by the caller.
- **Lint, not a control:** `deploy/test/forbidden.test.sh` greps for forbidden constructs. It is a lint: line continuations and argument arrays can evade it.
- **Integration tier:** the scripts run against production at the manual-test gate.

### D7. One env-key parser, shared with `provision.sh`
- **The lib owns three functions:**
  - `env_keys <file>`: names on lines matching `^[A-Z][A-Z0-9_]*=`. `export` and indentation are not recognised, because the example says "No `export`" and compose's env_file does not take it.
  - `env_missing_keys <template> <target>`.
  - `env_merge_lines <template> <target>`: the template's lines for the missing keys.
- `provision.sh secrets` uses both. Its `grep -qE "^${key}="` presence check and `${line%%=*}` parsing are replaced, so the two sides cannot diverge.
  - For every line in today's example the result is identical.
  - `provision.sh` sources the lib relative to its own directory.
  - `secrets` gains `--templates <dir>` (the four `*.env.example`), defaulting to the checkout's `env/`. Every file is checked against the given set, not only `backend.env`.
- **Preflight** compares against the **target's** example. On a miss it prints the names (filtered to `^[A-Z][A-Z0-9_]*$`) and a working fix:
  the four `git show <full>:deploy/env/<f>.env.example` into `/root/templates.<tag>/`, then `./provision.sh secrets --templates /root/templates.<tag>`.
  Extra keys print a count and the filtered names.
- D14 builds the env contract on this parser. Key presence is its first check.

### D14. The env contract: tagged templates, one fill step, a deploy that never asks
**Problem:** a presence check passes a blank secret, misses a changed value, and cannot tell optional from required. A prompt at deploy time cannot be the fix: a CI run cannot answer it, two runs can differ, and "looks good? [y]" verifies nothing.

**Tags.** Every assigned key in the four templates (`backend`, `postgres`, `minio`, `web-build` `.env.example`) carries a tag on the line directly above it. Compose reads a value to the end of its line, so a trailing tag is impossible.

```
#@ <kind> <class> <need> [pattern=<ERE>] [match=<file>:<KEY>]
kind   secret | config
class  fixed | setting | generated | vendor
need   required | optional | required-if=<KEY>=<VALUE>
```
- **`fixed`**: correct as written for this stack. The server value must equal the template's.
- **`setting`**: an operator choice whose template value is the default. Its value is checked against `pattern`, never against the template. This is a class of its own because the release checklist edits these on the server (`PAYMENT_MODE`, `RULES_STAMP_DUTY_ALLOW_UNREVIEWED`, `SIGNING_DRAFT_RETENTION_ENABLED`, `ESIGN_PROVIDER`, `ZOOP_BASE_URL`, `LOGGING_LEVEL_IN_AGREEMENTMITRA`). Treating them as `fixed` would make every one of those edits fail the deploy.
- **`generated`**: created on the server by `provision.sh secrets`. Always `secret required`.
- **`vendor`**: issued by a third party. Typed in by the operator.
- **`secret`** values are never printed by any script. **`config`** values may be shown by the fill step (old → new). The deploy prints key names only.
- **`required-if`** names a `config` key in the same file. It is evaluated against the server's value of that key, for example `RZP_*` against `PAYMENT_MODE=REQUIRED` and `LEEGALITY_*` against `ESIGN_PROVIDER=leegality`.
- **`pattern`** is an ERE matched with `[[ =~ ]]`. Nothing is evaluated, and it is never passed to `eval` or `grep -E` with the value in argv. Initial patterns:
  - generated: `^[A-Za-z0-9]{32,}$`, matching `gen_secret`;
  - `RAZORPAY_KEY_ID`: `^rzp_(test|live)_`, tightened to `^rzp_live_` by the first-release "switch test to live" step;
  - `OPERATOR_GSTIN` and `OPERATOR_LLPIN`: their formats.
- **`match`** asserts equality with a key in another env file, compared in-process and never printed: `DB_PASSWORD` against `postgres.env:POSTGRES_PASSWORD`, and `S3_ACCESS_KEY`/`S3_SECRET_KEY` against `minio.env`'s root credentials.
- **Lint:** an assigned key with no tag, an unknown word, a `fixed` key that is not `config`, a `generated` key that is not `secret required`, a `required-if` naming a `secret` key or a key absent from the same file, or a `fixed` key with a blank template value fails `deploy/test/templates.test.sh`. The deploy also refuses a target template with an untagged key, so the gate fails closed on a template nobody tagged.
- *Alternative rejected:* a separate schema file. That would hold the same keys in two places, which drift.

**Verdict per key** (`env_verdict`, pure, fixture-tested). Blank means empty, whitespace only, `""` or `''`.

| Condition | Verdict |
|---|---|
| key absent from the server file | `missing` (FAIL) |
| `required`, or `required-if` met, and blank | `blank` (FAIL) |
| `secret`, not blank, and a placeholder: `__GENERATED_ON_SERVER__`, `dev-only-*`, `change*me`, `placeholder`, `todo`, all `x` (case-insensitive) | `placeholder` (FAIL) |
| not blank and fails `pattern` | `pattern` (FAIL) |
| `fixed` and differs from the template | `drift` (FAIL) |
| `match` and unequal | `mismatch` (FAIL) |
| `optional`, or `required-if` not met, and blank | `blank-optional` (INFO) |
| otherwise | `ok` |

**Fill step: `provision.sh secrets` (the only interactive step).**
1. It keeps its current job: merge missing template lines, then generate blank `generated` keys.
2. It computes the verdicts. On a TTY it walks every non-`ok` key in template order:
   - **vendor `secret`:** prints the key's comment block from the template, then `read -rs`. The value is not echoed, never sits in argv, and is re-asked on a `pattern` failure.
   - **vendor `config`:** read with echo.
   - **`optional`:** Enter leaves it blank on purpose.
   - **`drift`:** shows the server value → the template value, then `[y/N]`. Only `fixed` keys drift, and `fixed` keys are `config`.
   - **`mismatch`:** refuses with the fix (rerun on a fresh template, or edit by hand). It never copies a secret between files on its own.
3. It writes through `env_set` (same directory, `umask 077`, then `mv`), single-quoting a typed value that holds `$`, `#`, a quote, a backslash or edge whitespace (compose interpolates and cuts unquoted values) and refusing one that holds a single quote; `env_value` strips one pair of surrounding quotes. The file is rebuilt line by line with `printf '%s=%s\n'`, never `sed` with the value in the expression. A value containing a newline is rejected.
4. It prints a summary: generated N, set N, optional left blank (names), still failing (names, as verdicts). It exits non-zero while any FAIL remains.
5. Off a TTY it only reports and exits on the verdicts. `report_missing_vendor_keys` and its hard-coded list are deleted; the tags replace them, and the "what each blank costs" prose already lives in the template comments.

**Deploy preflight** (D11 item 4) runs `env_verdict` over the four files against the **target's** templates. Any FAIL refuses, with key names and verdicts only, plus the fix: the D7 `secrets --templates` lines. `blank-optional` keys are listed as INFO. The deploy never prompts. `--dry-run` is the review.

**The app keeps the final say.** The shell gate is early feedback. `docker compose up` by hand skips it, so refusing to start on bad config stays in `prod-readiness-preflight`. That row gains a test that every `required` key in the template is validated by the app, so the two lists cannot drift.

### D8. Dumps: in-container, complete, atomic, strictly named, retained with their deploys
- **Command:** `dc exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"'`, single-quoted so the variables expand inside the container. No credential reaches the host. `psql` runs the same way.
- **Write:** through `gzip` under `umask 077` to `<name>.partial`. `PIPESTATUS` is checked for both commands.
- **Verify:**
  - `gzip -t`;
  - `gzip -dc | grep -c '^COPY '` reads the whole stream (no early exit, no SIGPIPE question) and must be non-zero;
  - one `gzip -dc | awk` pass counts `COPY` lines and keeps the last 20 lines, which must contain `-- PostgreSQL database dump complete` (pg_dump 17.6+ writes `\unrestrict <key>` after it, so not the last 3); gzip's own exit status proves the stream is whole. This catches a dump truncated after an error;
  - then `mv` to the final name. On failure the `.partial` file is deleted.
- **Name:** `pre-deploy-<YYYYMMDDTHHMMSSZ>-<tag>.sql.gz`, matched by exact regex. The hand-made `pre-deploy-20261006-1423.sql.gz` does not match. The migration plan deletes it once the first scripted dump is verified.
- **Rotation:**
  - keep the ten newest;
  - delete those older than 30 days;
  - never delete the dump just taken, nor any dump recorded on a log line whose tag is in the keep-set (D9), so a rollback's restore point survives.
- **The 30-day cap** runs only at deploy time, which DEPLOYMENT.md §8 states.
- **Directory:** `/root/backups` is set to `chmod 700` and checked to be root-owned before writing.
- **Wording:** every printed dump path says it is a same-disk rollback point that excludes object storage, and checks that the file exists.

### D9. Rollback over a running-version history
- **Log lines** that end in `ok` (`seed`, `deploy`, `accept`, `rollback`) record the tag running afterwards. They also record the post-run db max (`flyway=`), the image IDs and the dump.
- **History replay** builds a stack:
  - `seed`, `deploy` or `accept` pushes its tag, unless it equals the top;
  - `rollback` to T pops until the top is T.
- **Rollback target:**
  - if running equals the top, pop it, and the target is the new top;
  - if running differs (a failed deploy left B running while the top is A), the target is the top, with no pop.
  - Examples:
    - A, B, C ok, then two rollbacks → B, then A.
    - A, B ok, C failed, rollback → B; then D ok, rollback → B.
  - With nothing left to roll back to, rollback refuses.
- **Schema gate** (deploy and rollback):
  - db max = `select max(version::int) from flyway_schema_history where success and version is not null`;
  - the ceiling is the target's tree max, from `git ls-tree` full paths with a numeric max;
  - for a `seed` target, the ceiling is the `flyway=` recorded on the seed line, because its images may not have been built from HEAD;
  - refuse when db max > ceiling. No `--force`, by decision.
  - The refusal prints the dump recorded on the first line, whatever its outcome, whose `flyway=` exceeds the ceiling (a deploy that migrated and then failed still took the restore point), after checking the file exists.
- **Network:** rollback reads the target's compose; a different subnet/ip range needs `rollback --allow-downtime`, then `down` before `up`.
- **Downgrade:** a deploy whose target is a strict ancestor of the running commit is refused without `--allow-downgrade`. Older code goes back through `rollback`.
- **Rollback steps:**
  1. Take the lock.
  2. Check the target's images exist and their IDs match those logged.
  3. Run the clean-tree check.
  4. `git checkout --detach <target-full>` for a script-era target. A `seed` target stays on the current checkout, since its compose has no `image:` keys.
  5. Write `deploy/.env`.
  6. `up -d --no-build --pull never --wait`.
  7. Run the post-checks.
- **Seed:**
  - Taken when the log has no `ok` line (not when the file is missing), in a real run, with the stack running.
  - It tags each running built service's image, by container image ID, as `<HEAD tag12>`.
  - It logs `seed` with the current db max and the image IDs, then writes `deploy/.env` with the seed tag.
  - If HEAD equals the target, the deploy refuses ("already running") unless `--refresh-base` is given.
  - The seed's hash is HEAD as an assumption: DEPLOYMENT.md says to confirm HEAD before the first scripted deploy.
- **`accept`:**
  - Allowed only when the last log line is a `deploy` or `rollback` to the running tag whose outcome is `postcheck:errors`. In the history replay, an `accept` of a tag already in the stack acts as a rollback (pops to it).
  - It re-runs the other post-checks live first.
  - A failed migration, MinIO or Caddy check can never be accepted. A wrong Caddy address breaks forwarded-header trust.

### D10. Lock, state, trap
- **Lock:** `flock -n` on `/run/agreementmitra/deploy.lock` (root-owned `0700`, checked). Taken by deploy, rollback and accept.
- **State log:** `/var/lib/agreementmitra/deploys.log` (`0750` directory, `0640` file, root-owned). It is state, not a rotatable log; losing it means a re-seed.
  `<UTC> <seed|deploy|rollback|accept> prev=<tag|unknown> target=<full> tag=<tag> flyway=<post-run db max> minio=<release> images=<b,c,g ids> dump=<file|-> outcome=<ok|step>`
- **Dry runs** stop after preflight. Before the dry-run exit nothing is created except the lock directory: no state directory, no log, no trap.
- **The `EXIT` trap** is installed after that point.
  - It runs `set +e`, preserves and re-raises `$?`, and takes the outcome from `STEP`.
  - It prints the rollback command when the failure is at or after `up`.
  - The outcome is final before image cleanup, which is `|| warn`.
- **Lines per run:** each run writes one line, plus a `seed` line on the first deploy.
- **Stage-1 refusals** (bad argument, fetch failure, not on `main`) print and exit without a log line.
- **Implementation traps:** `if ! f; then` disables `errexit` inside `f`, and `local v=$(cmd)` masks `cmd`'s failure.

### D11. Preflight, completely
In order. Stage 2 runs each check against the target's files:
1. The tracked tree is clean.
2. No untracked or ignored files sit in the build contexts: `git status --porcelain --ignored --untracked-files=all -- backend frontend docker` is empty.
   - A stray `V27__*.sql` would otherwise ship and be applied, and a stray `frontend/public` file would be served.
   - On the server, builds run in Docker, so the host contexts are clean.
3. The target does not track production-only paths (`deploy/env/*.env`, `deploy/.env`, `deploy/certs/`), which checkout would overwrite.
4. The env contract (D14) over all four env files.
5. Network collision and change (D5).
6. Schema gate and downgrade (D9).
7. `postgres` is running.
8. The target is not the running tag, unless `--refresh-base` is given.

### D12. Post-checks
- **Health:** all services healthy (`--wait`).
- **Migrations:** db max equals the target tree max. Any `success = false` row is still reported, though on Postgres a failed migration rolls back and shows as an unhealthy backend instead.
- **Caddy address:** caddy is at `10.203.17.10`.
- **MinIO:** the release, parsed as one `RELEASE\.[0-9T-]+Z` token, equals the one recorded before.
- **ERROR scan:** zero lines at level ERROR since `StartedAt`, matched by column. Only the count and the sanitised logger names are printed.
- **Outcome names:** a failure sets `postcheck:migrations`, `postcheck:caddy`, `postcheck:minio` or `postcheck:errors`.

### D13. Smoke script, from outside
- **Location:** `deploy/smoke-prod.sh`.
- **Every request** asserts there is no `cf-mitigated` header. A 302 to `*.cloudflareaccess.com` fails as "Cloudflare Access is on". The apex answered 200 unauthenticated on 2026-10-06.
- **CSRF cookie:** read from `GET /api/templates`.
- **`--forged-header`:**
  1. Runs last.
  2. Warns that the caller's /24, including carrier-NAT neighbours, is locked out for about 5 minutes, and needs `y` or `--yes`.
  3. Validates the caller's IPv4 from `<base>/cdn-cgi/trace` against a strict dotted-quad with octets ≤ 255. Anything else is inconclusive.
  4. Sends 150 requests with `xargs -P 15`.
  5. Runs, on the server, a remote command over `ssh "${SMOKE_SSH:-agreementmitra-vps}"`, built only from validated tokens quoted with `printf %q`. It greps lockout lines since the burst start for the caller's or the forged /24.
  6. `forged_verdict` decides.
- **Output:** PASS/FAIL only.

## Risks / Trade-offs

- **[Merge rights on `main` = root on prod]** → the stated trust boundary. Branch protection is a production control.
- **[A long deploy dies with the SSH session]** → a warning outside `tmux` or `screen`, the `tmux` form in DEPLOYMENT.md, and the trap logs the interruption.
- **[`--wait` passes while storage is broken]** → known. Closed by the storage-health-indicator CR; until then the script prints the storage-write reminder.
- **[Root deletes images and dumps]** → only by exact tag or name regex, never `prune` or `-f`. The keep-set and the dump retention follow the history. The forbidden-construct grep is a lint, backed by review.
- **[A typed secret passes the pattern but is wrong]** → the shell proves shape, not validity. The first use fails loudly, the app's startup validation (`prod-readiness-preflight`) catches what it can, and the smoke check covers the edge.
- **[A new key ships untagged]** → `templates.test.sh` fails, and the deploy refuses a target template with an untagged key.
- **[Seed provenance is assumed]** → its schema ceiling is its recorded db max, not HEAD's tree, and DEPLOYMENT.md says to confirm HEAD first.
- **[Dumps are unencrypted on the same disk, and age-capped only at deploy time]** → stated in DEPLOYMENT.md §8.
- **[Base images go stale between `--refresh-base` runs]** → monthly cadence.

## Migration Plan

1. **Land the prerequisites.** Merge f9855e7 (the firewall fix) and this change to `main`.
2. **On the server:**
   - discard the modified `deploy/provision.sh`, which is identical to `main` once f9855e7 lands and is saved as `/root/backups/provision-firewall-fix.patch`;
   - confirm HEAD is the commit the running images were built from (`593086f`);
   - run `./provision.sh secrets` to add `MAIL_SMTP_SSL` and `ZOOP_ORG_NAME`.
   - run `./provision.sh secrets` again after the template is tagged. Expect it to surface any `fixed` drift on the box, for example a `DB_URL` without `logServerErrorDetail=false` (`prod-db-url-log-server-error-detail`), and the vendor keys still blank. Answer each, until its summary shows no FAIL.
3. **Dry run (bootstrap):** `git fetch origin && git show origin/main:deploy/deploy.sh | bash -s -- --dry-run <hash>`. Every check should pass, and no state is written.
4. **Real deploy.** It seeds the history from the running images, writes `deploy/.env`, and moves the services to hash tags.
5. **After the deploy:** run `deploy/smoke-prod.sh`, then delete the hand-made `pre-deploy-20261006-1423.sql.gz` once the scripted dump is verified.
6. **Clean up the old images:** after three scripted deploys (the seed then leaves the keep-set), remove the `agreementmitra-*:latest` images by hand.

**Rollback of this change:** revert the commit. This removes `image:` keys and the `:?`. Delete `deploy/.env` and `/var/lib/agreementmitra`, then `git checkout main && git pull` and use the manual procedure.

## Open Questions

None blocking. Whether CI should call `deploy.sh` belongs to CR-7.
