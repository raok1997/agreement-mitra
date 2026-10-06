> Exemption note: this is deploy/harness tooling and adds no runnable backend or frontend behaviour, so the unit + integration test rule does not apply. Group 3 fixture-tests every pure decision (the unit tier), and the integration tier is the scripts run against production at the manual-test gate (D6).

## 1. Compose and shared env parsing

- [x] 1.1 In `deploy/docker-compose.prod.yml`:
  - give `backend`, `caddy` and `gotenberg` an explicit `image: agreementmitra-<service>:${DEPLOY_TAG:?set by deploy/deploy.sh in deploy/.env}`;
  - update the header comment: deploys go through `deploy/deploy.sh`, `deploy/.env` holds the running tag, and never run a manual `--build` (D3).
- [x] 1.2 Check locally that the file passes `DEPLOY_TAG=x docker compose -f deploy/docker-compose.prod.yml config --quiet --no-env-resolution`, and fails with the `:?` message when `DEPLOY_TAG` is unset. Compose still stats each `env_file`, so render against a scratch project directory holding empty `env/*.env` (`--project-directory`); `--no-env-resolution` keeps their values out of the output.
- [x] 1.3 `deploy/provision.sh`:
  - source `"$(dirname "${BASH_SOURCE[0]}")/lib/checks.sh"`;
  - replace `merge_template_defaults`' `${line%%=*}` parsing and its `grep -qE "^${key}="` presence check with `env_missing_keys` and `env_merge_lines`;
  - add `secrets --templates <dir>`, defaulting to the checkout's `env/` (D7);
  - verify that for today's `backend.env.example` the merged output is byte-identical before and after the refactor.
- [x] 1.4 Tag every assigned key in `deploy/env/{backend,postgres,minio,web-build}.env.example` (D14). Keep the existing `[FIXED]`/`[GENERATED]`/`[VENDOR]` prose; the `#@` line goes directly above the key. Classes to get right:
  - `setting`: `LOGGING_LEVEL_IN_AGREEMENTMITRA`, `SIGNING_DRAFT_RETENTION_ENABLED`, `ESIGN_PROVIDER`, `ZOOP_BASE_URL`, `PAYMENT_MODE`, `RULES_STAMP_DUTY_ALLOW_UNREVIEWED`, `MAIL_PROVIDER`;
  - `required-if=PAYMENT_MODE=REQUIRED`: `RAZORPAY_KEY_ID` (with a pattern accepting the test and live prefixes), `RZP_KEY_SECRET`, `RZP_WEBHOOK_SECRET`;
  - `required-if=ESIGN_PROVIDER=zoop`: the two ZOOP credentials;
  - `required-if=ESIGN_PROVIDER=leegality`: the four `LEEGALITY_*`;
  - `required-if=MAIL_PROVIDER=smtp`: `MAIL_SMTP_PASSWORD`;
  - `optional`: both Google OAuth keys, `OPERATOR_LLPIN` and `OPERATOR_GSTIN` (with their format patterns), and both `VITE_OPERATOR_*`;
  - `generated`, with the 32-character alphanumeric pattern: the three postgres/minio credentials, both peppers, and the three backend copies of the store credentials, each with a `match` naming the key it copies;
  - everything else in `backend.env.example` is `config fixed required`.
- [x] 1.5 `provision.sh secrets`, the fill step (D14):
  - after the merge and generation, compute `env_verdict` for all four files;
  - on a TTY, walk the non-`ok` keys in template order: print the key's template comment block; `read -rs` for a `secret` key, plain `read` for a `config` key; Enter keeps an `optional` key blank; re-ask on `pattern`; `drift` shows server and template values and applies only on `y`; `mismatch` prints its fix and is never auto-resolved;
  - write through `env_set` (atomic, `0600`, `printf '%s=%s\n'`, a newline in a value rejected);
  - print the summary (generated, set, optional-blank, failing, by name) and exit non-zero while any failing verdict remains;
  - off a TTY, report only;
  - delete `report_missing_vendor_keys` and its hard-coded key list.

## 2. Pure checks library (`deploy/lib/checks.sh`)

Portable to bash 3.2 and BSD tools: POSIX ERE, no `date -d`, `mapfile` or associative arrays (D6). No side effects, and no function echoes an env value.

- [x] 2.1 Env functions (D7):
  - `env_keys <file>`: names on lines matching `^[A-Z][A-Z0-9_]*=`;
  - `env_missing_keys <template> <target>`;
  - `env_extra_keys <template> <target>`;
  - `env_merge_lines <template> <target>`;
  - `env_tags <template>`: one `key kind class need pattern match` record per key, or a lint error;
  - `env_value <file> <key>`: the raw value, read line by line, never sourced; callers never echo it;
  - `env_blank <value>`: empty, whitespace, `""` or `''`;
  - `env_placeholder <value>`: the D14 list, case-insensitive;
  - `env_verdict <template> <target> <other-files...>`: one `key verdict` line per key (the D14 table), names only;
  - `env_set <file> <key>`: the value from a variable, atomic rewrite, `0600`.
- [x] 2.2 Network functions (D5):
  - `ipam_from_compose <json>` and `ipam_from_inspect <json>`: normalise to `subnet ip_range`;
  - `network_change <declared> <running>`: an empty running value means no change;
  - `cidr_overlap <a> <b>`: IPv4 only, and an empty or IPv6 input means no overlap.
- [x] 2.3 Schema functions (D9):
  - `max_migration_version <ls-tree-output>`: full paths, numeric max;
  - `schema_gate <db-max> <ceiling>`;
  - `DB_MAX_SQL`.
- [x] 2.4 Dump functions (D8):
  - `dump_name <utcstamp> <tag>`;
  - `is_script_dump <name>`;
  - `dump_verdict <gzip-ok> <copy-count> <tail-text> <pipestatus>`: ok or the reason;
  - `rotation_victims <cutoff-stamp> <just-taken> <protected-names> <listing>`: keeps ten newest, deletes those older than the cutoff by string comparison, never the just-taken or protected names, and ignores non-matching names.
- [x] 2.5 History functions (D9):
  - `history <log>`: replays `ok` lines, where seed, deploy and accept push unless equal to the top, and rollback to T pops until T is on top;
  - `rollback_target <log> <running-tag>`;
  - `rollback_ceiling <log> <target>`: the seed's recorded `flyway=`, or empty for "use the tree";
  - `restore_dump_for <log> <ceiling>`;
  - `image_keep_set <log> <running-tag>`: running plus the top three distinct entries;
  - `protected_dumps <log> <keep-set>`;
  - `accept_allowed <log> <running-tag>`.
- [x] 2.6 Text functions:
  - `running_tag_from_image <ref>`: `^agreementmitra-backend:([0-9a-f]{12}(-r[0-9]{12})?)$`, otherwise `unknown`;
  - `minio_release <version-output>`;
  - `minio_changed <before> <after>`;
  - `error_loggers <log-text>`: the level column matched with `[[:space:]]`, and logger names restricted to `^[A-Za-z0-9_.$]+$`;
  - `valid_ipv4 <s>`: dotted quad, octets ≤ 255;
  - `slash24 <ipv4>`;
  - `forged_verdict <matched-sources> <caller24> <forged24>`;
  - `valid_hash <arg>`;
  - `valid_tag_line <line>`;
  - `log_line <fields…>`;
  - `sanitize`.

## 3. Tests

- [x] 3.1 `deploy/test/checks.test.sh`: plain bash that exits non-zero on any failure and runs on macOS and the server. Fixtures go in `deploy/test/fixtures/`; the network ones are captured verbatim from `compose config` and `network inspect` on the server, and the migration list is real `ls-tree` output. Cover:
  - **env:**
    - a missing key is reported;
    - a commented key is not required;
    - `export KEY=` and an indented key are not keys;
    - `QUJD=` is not a key;
    - `env_merge_lines` returns the template line for a missing key;
    - a sentinel value never appears in captured `2>&1` output, for every verdict;
    - `env_verdict`: `blank` for a blank required key; `blank-optional` for a blank optional one; for `required-if`, `blank` when the condition holds and `blank-optional` when it does not;
    - `placeholder` for the generated-on-server marker and for a `dev-only-` pepper default;
    - `pattern` for a malformed Razorpay key id; `drift` for a changed `fixed` value; `ok` for a changed `setting`;
    - `mismatch` when the backend database password differs from the postgres fixture, `ok` when equal;
    - `env_blank` treats `""` and whitespace as blank;
    - `env_set` keeps every other line byte-identical, writes `0600`, rejects a newline, and handles a value containing `|`, `&`, `/` and `\`.
  - **network:**
    - the compose and inspect fixtures for the same network mean no change;
    - an ip-range change is a change;
    - an empty running value means no change;
    - `10.203.0.0/16` overlaps `10.203.17.0/24`, and `10.204.0.0/24` does not;
    - an IPv6 or empty subnet does not overlap.
  - **schema:**
    - `max_migration_version` over the real listing (V8 sorts after V26) gives 26;
    - `schema_gate 26 25` refuses and `26 26` passes.
  - **dumps:**
    - `dump_verdict` fails on a missing trailer, a zero COPY count, a bad gzip, and a non-zero pipestatus;
    - `rotation_victims`, over eleven script dumps plus `pre-deploy-20261006-1423.sql.gz`, `backend.env.20261006-1423` and `provision-firewall-fix.patch`, deletes only the oldest script dump;
    - an over-age dump is deleted unless protected;
    - the dump just taken is never deleted.
  - **history:**
    - A, B, C ok then two rollbacks → B, then A;
    - A, B ok, C failed (running C) → B; then D ok → B;
    - seed then A ok → seed, with the ceiling taken from the seed's `flyway=`;
    - only the running entry → empty (refuse);
    - a duplicate deploy of the top is not pushed;
    - `image_keep_set` for A ok, B, C, D failed and E ok keeps A and E;
    - `protected_dumps` includes A's dump;
    - `restore_dump_for` picks the first ok line above the ceiling;
    - `accept_allowed` is true only after `postcheck:errors` on the running tag.
  - **text:**
    - `running_tag_from_image` gives a tag, a refresh tag, and `unknown` for `:latest`;
    - `minio_release` on the real multi-line output;
    - `minio_changed`;
    - `error_loggers` on an INFO line containing ` ERROR ` (ignored) and a real ERROR line (logger only);
    - `valid_ipv4` rejects `1.2.3.4;curl x|sh` and `256.1.1.1`;
    - `forged_verdict` pass, fail and inconclusive;
    - `valid_hash` rejects `--output=x`, `origin/main` and `ABC`;
    - `valid_tag_line`;
    - `log_line` field order;
    - `sanitize` strips `\e[2J`.
- [x] 3.2 `deploy/test/forbidden.test.sh`: a lint, not a control (D6). It fails on any of the following in `deploy/deploy.sh`, `deploy/smoke-prod.sh` or `deploy/lib/checks.sh`:
  - `set -x`;
  - `down` with `-v`, `--volumes`, `--rmi` or `--remove-orphans`;
  - `image rm -f`;
  - `docker inspect` without `-f`;
  - compose `config` without `--no-env-resolution` or `--quiet`;
  - a bare `docker compose` outside `dc()`;
  - `source` or `.` of `deploy/.env`.
- [x] 3.2a `deploy/test/templates.test.sh`: run `env_tags` over the four real templates and fail on any D14 lint rule. Fixtures for an untagged key, a `required-if` naming a `secret`, and a `fixed` key with a blank value must each fail.
- [x] 3.2b Fill step on the Mac with throwaway env files: with stdin redirected (off a TTY) it reports, exits non-zero and writes nothing beyond generation. Exercise the TTY path by hand and keep the transcript; the typed secret answers must be absent from it.
- [x] 3.3 Run `bash -n` on every script and run both tests on macOS. If `shellcheck` is available, run it as well and fix its warnings. The tests are re-run on the server at the manual-test gate.

## 4. deploy.sh — entry, stage 1, stage 2 entry

- [x] 4.1 Create `deploy/deploy.sh` (mode 755).
  - Use `set -euo pipefail` and export `DOCKER_BUILDKIT=1`.
  - Keep every step in a function and end the file with `main "$@"; exit $?`.
  - Usage: `deploy.sh [--dry-run] [--allow-downtime] [--allow-downgrade] [--refresh-base] <hash>`, `deploy.sh rollback`, `deploy.sh accept`.
  - Warn when `$TMUX` and `$STY` are both unset.
  - Write in the implementation's header comment: `if ! f` disables errexit inside `f`, and `local v=$(cmd)` masks failure.
- [x] 4.2 Entry (D2), self-contained, sourcing nothing:
  - check root, the working directory `/opt/agreementmitra`, and root ownership and no group/world write on the checkout and `.git`;
  - check the inlined hash regex (for deploys);
  - `git fetch origin`;
  - compare itself with `git show origin/main:deploy/deploy.sh`, and on a mismatch re-exec `main`'s copy with `--from-main` (a second mismatch is an error).
- [x] 4.3 Stage 1, deploy path (D2), in this order:
  1. `rev-parse --verify --end-of-options "<c>^{commit}"`, and the resolved hash must start with the argument;
  2. `merge-base --is-ancestor --`;
  3. if the target lacks `deploy/deploy.sh`, refuse with the "use rollback" message;
  4. `mktemp -d /tmp/agreementmitra-deploy.XXXXXXXX` and extract the fixed layout (`deploy.sh`, `lib/checks.sh`, `docker-compose.prod.yml`, and the four `*.env.example` templates);
  5. `exec bash "$work/deploy.sh" --stage2 [flags] <full> </dev/null`.
- [x] 4.4 Stage 2 entry, in this order:
  1. assert its own directory matches `^/tmp/agreementmitra-deploy\.[A-Za-z0-9]{8}$`, and refuse otherwise;
  2. install the `rm -rf -- "$work"` trap;
  3. source `$work/lib/checks.sh`;
  4. re-check the format and ancestry, and reject unknown flags;
  5. create and check `/run/agreementmitra` (`0700`, root) and take `flock -n`;
  6. define `dc()` (D3) and export `DEPLOY_TAG` as the running tag (`running_tag_from_image`, or a placeholder when unknown, used only for `ps`/`exec`/`logs`).

## 5. deploy.sh — preflight, seed, dump

- [x] 5.1 Run the preflight checks in D11 order. Each prints PASS or FAIL, with names and paths passed through `sanitize`.
  - The clean-tree check: `git status --porcelain --untracked-files=no`.
  - The build-context check: `git status --porcelain --ignored --untracked-files=all -- backend frontend docker` must be empty.
  - The tracked-production-paths check: `git ls-tree -r --name-only <full> -- deploy/env deploy/.env deploy/certs` must not list `*.env`, `.env` or `certs/` files.
- [x] 5.2 The env check (D14): refuse if `env_tags` fails on any of the four target templates in `$work`, then run `env_verdict` per file. Any failing verdict refuses, printing `key: verdict` lines and the D7 fix command. `blank-optional` prints as INFO, and extra keys only warn. It never prompts.
- [x] 5.3 Network checks:
  - `dc config --no-env-resolution --format json | jq … | ipam_from_compose`;
  - `docker network inspect -f '{{json .IPAM.Config}}' … | ipam_from_inspect`;
  - `cidr_overlap` against every other network;
  - `network_change`, which fails unless `--allow-downtime` is given.
- [x] 5.4 Schema and target checks:
  - check `postgres` is running (`dc ps -q postgres` is non-empty and its state is running);
  - db max via `dc exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "…"'`;
  - the ceiling is the target's tree max;
  - `schema_gate`;
  - refuse a strict-ancestor target without `--allow-downgrade`;
  - refuse target == running tag without `--refresh-base`.

  `--dry-run` reports and exits here, having created nothing but `/run/agreementmitra`.
- [x] 5.5 After the dry-run exit:
  - create and check `/var/lib/agreementmitra` (`0750`) and `deploys.log` (`0640`);
  - install the `EXIT` trap (D10).

  Seed: if `history` is empty, `docker tag` each running built service's image ID as `agreementmitra-<svc>:<HEAD tag12>`, log `seed` with the db max and image IDs, write `deploy/.env` with that tag, and re-export `DEPLOY_TAG`.
- [x] 5.6 Dump (D8):
  - record the MinIO release;
  - `chmod 700 /root/backups` and check it is root-owned;
  - run `dc exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | gzip > <name>.partial` under `umask 077`, capturing `PIPESTATUS`;
  - in one `gzip -dc | awk` pass, the gzip status, the `COPY` count and the last 20 lines (the trailer precedes `\unrestrict` on pg_dump 17.6+);
  - `dump_verdict` decides, then `mv`, or delete the `.partial` file and fail;
  - delete the `rotation_victims`, with `protected_dumps` from the keep-set and a cutoff stamp computed portably by the caller.

## 6. deploy.sh — build, start, verify

- [x] 6.1 Build:
  - `git checkout --detach <full> --` (confirm the form on the server's git 2.43 at the gate);
  - set the tag (with the refresh suffix when `--refresh-base`) and export `DEPLOY_TAG`;
  - `dc build [--pull] backend caddy gotenberg`.
- [x] 6.2 Start:
  - write `deploy/.env` atomically with the target tag;
  - run `dc down` (no other flags) only on a network change with `--allow-downtime`;
  - `dc up -d --no-build --pull never --wait --wait-timeout 300`.
- [x] 6.3 Post-checks (D12), each setting its `postcheck:*` outcome:
  - db max equals tree max (`success = false` rows also reported);
  - the caddy IP via `docker inspect -f` on `$(dc ps -q caddy)` is `10.203.17.10`;
  - `minio_changed`;
  - `error_loggers` over `dc logs --since <StartedAt> backend`.
- [x] 6.4 On success:
  - finalise the outcome to `ok`, with the image IDs and dump;
  - then remove hash-tagged `agreementmitra-*` images outside `image_keep_set`, by exact name and without `-f`, with `|| warn`;
  - print the commit, the migration version and the storage-write reminder.

## 7. deploy.sh — rollback and accept

- [x] 7.1 `rollback` (stage 1, `main`'s code), in this order:
  - take the lock;
  - target = `rollback_target`, refusing when empty;
  - the ceiling is `rollback_ceiling`, or the target's tree max;
  - `schema_gate`, which on refusal prints `restore_dump_for`, checked to exist, with the D8 caveat;
  - check each target image exists and its ID matches the log;
  - run the clean-tree check;
  - `git checkout --detach <target> --` unless the target is the seed;
  - write `deploy/.env`;
  - `dc up -d --no-build --pull never --wait`;
  - run the 6.3 post-checks;
  - record a `rollback` line.
- [x] 7.2 `accept`:
  - take the lock;
  - refuse unless `accept_allowed`;
  - re-run the non-ERROR post-checks and refuse on any failure;
  - record `accept … outcome=ok`.

## 8. smoke-prod.sh

- [x] 8.1 Create `deploy/smoke-prod.sh [--forged-header] [--yes] [base-url]`, sourcing `deploy/lib/checks.sh`. It prints PASS or FAIL per check and exits non-zero on any failure. Checks:
  - the apex returns 200;
  - `www` returns 301 to the apex;
  - a 1.2 MB POST to `/api/agreements` returns 413;
  - `GET /api/templates` sets `__Host-XSRF-TOKEN` with `Secure` and `SameSite=Lax`, with the value never printed;
  - `cf-cache-status: DYNAMIC` on `/api/templates` and `/api/templates/form?state=KA&type=residential`;
  - both webhook paths return 401;
  - no response carries `cf-mitigated`;
  - an Access redirect fails, naming Access.
- [x] 8.2 `--forged-header` (D13):
  - runs last and prints the warning, including that carrier-NAT neighbours share the lockout;
  - requires `y` or `--yes`;
  - reads the caller's address from `cdn-cgi/trace` with `curl -4`, and if `valid_ipv4` fails the result is inconclusive;
  - records the burst start (UTC) and sends 150 requests with `xargs -P 15`;
  - over `ssh "${SMOKE_SSH:-agreementmitra-vps}"`, runs a remote command built from `printf %q` tokens only: `docker compose -f /opt/agreementmitra/deploy/docker-compose.prod.yml logs --since <start> backend | grep` for lockout lines from the caller's or the forged /24;
  - `forged_verdict` decides.

## 9. Docs and register

- [x] 9.1 `docs/DEPLOYMENT.md` §4: make `deploy/deploy.sh` the procedure. Cover:
  - the trust boundary and branch protection;
  - the bootstrap line;
  - the flags, with a monthly `--refresh-base`;
  - running inside `tmux`;
  - `deploy/.env`, and that compose honours `COMPOSE_*` keys from it while an exported `DEPLOY_TAG` overrides it;
  - never a manual `--build`;
  - the checkout being detached;
  - the rollback semantics, schema refusal and dump caveat;
  - `accept` and its limits;
  - the state-log location;
  - first bring-up staying manual;
  - the migration steps from design.md.

  Reduce the MinIO manual gate to "the script records and checks it". Keep the manual storage-write step.
- [x] 9.2 `docs/DEPLOYMENT.md`:
  - §5.6: use `deploy/smoke-prod.sh --forged-header`;
  - §5.4 and §6: Access is not enforced on the apex today (verified 2026-10-06);
  - §8: pre-deploy dumps are same-disk rollback points, age-capped at deploy time only, unencrypted;
  - §3: `secrets --templates`; the tag grammar and the four classes; `provision.sh secrets` as the fill step and `--dry-run` as the review; that a `setting` (payments, eSign host, unreviewed rules, retention) is changed on the server and never fails the deploy, while a `fixed` change comes from the template; replace the "What blank credentials actually do" list with a pointer to the tags.
- [x] 9.3 `docs/ROADMAP.md` register appends:
  - `prod-readiness-preflight`: the deploy-time half has landed, including the blank, placeholder, pattern and drift checks over the tagged templates; outside-the-JVM checks extend `smoke-prod.sh`; the forged-header gate now has a mechanism but stays opt-in; the startup validation remains, and gains a test that every `required` template key is validated by the app;
  - `prod-db-url-log-server-error-detail`: the deploy now refuses a `DB_URL` that drifts from the template, so the fill step applies it;
  - first-release "switch test to live" line: tighten the Razorpay key-id pattern to the live prefix in the same step;
  - `prod-minio-image-pin`: the release is recorded and checked on every deploy, with `--pull never`, so the manual gate reduces to the pin.
  - Add a `storage-health-indicator` row only if none exists, with its recommended action.
- [x] 9.4 Teach the tag where a new env var is added, not only in the ops runbook:
  - **Each template's header** (`deploy/env/*.env.example`): replace the three-class prose with the D14 grammar. Add a short "choosing a tag" guide:
    - `fixed` if the stack decides the value, `setting` if an operator may change it;
    - `generated` if the server creates it, `vendor` if a third party issues it;
    - `secret` unless the value may be displayed;
    - `required-if` when a mode key decides whether it matters.

    Add one worked example per class, and the rule that the lint and the deploy refuse an untagged key.
  - **`CLAUDE.md`**, under Conventions: one line saying a change that adds or changes an env var tags it in the template (pointing at the header), and that `fixed` vs `setting` decides whether the deploy refuses a server edit.
  - **`openspec/config.yaml` `context:`**: the same rule in one line, so every generated proposal and task list that introduces an env var includes its tag. Add a `tasks:` rule: a change that adds an env var lists a task to tag it in the template.

## Coverage

53 scenarios: 37 COVERED, 3 GROUPED, 12 MANUAL, 1 WAIVED, 0 UNMAPPED.

| # | Requirement → Scenario | Disposition | Where |
|---|---|---|---|
| 1 | Trust → Commit not on main | MANUAL | gate: real run with a `fix/new-fixes`-only hash; nothing extracted to `/tmp` |
| 2 | Trust → Option-shaped argument | COVERED | 3.1 text (`valid_hash`) |
| 3 | Trust → Stale or modified entry script | MANUAL | gate: run the checkout's script while it differs from `main`; output shows the re-exec |
| 4 | Trust → Direct second-stage call | MANUAL | gate: `bash deploy/deploy.sh --stage2 <main hash>` refuses and `deploy/` is intact |
| 5 | Preflight → Dirty deploy checkout | MANUAL | gate: first `--dry-run` while `provision.sh` is still modified |
| 6 | Preflight → Stray file in a build context | MANUAL | gate: `touch backend/stray.txt`, `--dry-run` refuses, remove it |
| 7 | Preflight → Missing env key from the target commit | COVERED | 3.1 env |
| 8 | Preflight → Older commit behind the schema | COVERED | 3.1 schema |
| 9 | Preflight → Downgrade without consent | MANUAL | gate: `--dry-run` with an older `main` hash |
| 10 | Preflight → Concurrent run | MANUAL | gate: hold `flock /run/agreementmitra/deploy.lock sleep 60`, then `--dry-run` |
| 11 | Preflight → Dry run writes nothing | MANUAL | gate: bootstrap dry run, then `ls /var/lib/agreementmitra deploy/.env` absent |
| 12 | Secrets → Env comparison output | COVERED | 3.1 env (sentinel) |
| 13 | Secrets → Forbidden constructs absent | COVERED | 3.2 |
| 14 | Secrets → Hostile trace response | COVERED | 3.1 text (`valid_ipv4`) |
| 15 | Dump → Written and verified | GROUPED | row 16's `dump_verdict` tests + gate `ls -l /root/backups` |
| 16 | Dump → Truncated dump | COVERED | 3.1 dumps (`dump_verdict`) |
| 17 | Dump → Rotation leaves other files alone | COVERED | 3.1 dumps |
| 18 | Dump → Age cap spares rollback restore points | COVERED | 3.1 dumps + history (`protected_dumps`) |
| 19 | Images → Manual up after a scripted deploy | COVERED | 1.2 (`:?` render) + 3.1 `valid_tag_line`; observed again at the gate |
| 20 | Images → Refresh keeps the rollback target | COVERED | 3.1 text (`running_tag_from_image` refresh tag) + history (distinct entries) |
| 21 | Images → MinIO release changes | COVERED | 3.1 text (`minio_release`, `minio_changed`) |
| 22 | Downtime → Network unchanged | COVERED | 3.1 network |
| 23 | Downtime → Network changed without consent | COVERED | 3.1 network |
| 24 | Post-verify → Healthy deploy | MANUAL | gate: real deploy, then `smoke-prod.sh` |
| 25 | Post-verify → Health timeout | WAIVED | needs a deliberately broken backend on prod; `.env`-before-`up` ordering is asserted by row 27's history case |
| 26 | Post-verify → ERROR line after start | COVERED | 3.1 text (`error_loggers`) |
| 27 | Record → Successful deploy recorded | COVERED | 3.1 text (`log_line`); observed at the gate |
| 28 | Record → Post-check failure recorded | GROUPED | row 27's `log_line` test + the D10 trap; needs a failing deploy to observe |
| 29 | Rollback → Repeated rollback walks back | COVERED | 3.1 history |
| 30 | Rollback → After a failed deploy keeps the good entry | COVERED | 3.1 history |
| 31 | Rollback → Across a migration | COVERED | 3.1 schema + `restore_dump_for` |
| 32 | Rollback → Retagged image | COVERED | 3.1 history (`images_match`: retagged and missing images named by position) |
| 33 | Rollback → Nothing to roll back to | COVERED | 3.1 history (single entry) |
| 34 | Seed → Survives a preceding dry run | COVERED | 3.1 history (missing, empty and failures-only logs give an empty `history`, the seed trigger); dry-run side observed at row 11 |
| 35 | Accept → After an ERROR line | COVERED | 3.1 history (`accept_allowed` true) |
| 36 | Accept → Refused for other failures | COVERED | 3.1 history (`accept_allowed` false cases) |
| 37 | Retention → Failed builds pruned, rollback target kept | COVERED | 3.1 history (`image_keep_set`) |
| 38 | Smoke → All checks pass | MANUAL | gate: `deploy/smoke-prod.sh` against prod after the real deploy |
| 39 | Smoke → API response edge-cached | GROUPED | row 38's run (the DYNAMIC assertion); a cached response needs a misconfigured Cloudflare |
| 40 | Forged → Ignored | COVERED | 3.1 text (`forged_verdict` pass) |
| 41 | Forged → Honoured | COVERED | 3.1 text (`forged_verdict` fail) |
| 42 | Forged → No lockout observed | COVERED | 3.1 text (`forged_verdict` inconclusive) |
| 43 | Templates → Untagged key | COVERED | 3.2a fixture + 5.2 refusal |
| 44 | Templates → Secret used as a condition | COVERED | 3.2a fixture |
| 45 | Env contract → Required secret blank | COVERED | 3.1 env (`blank`) |
| 46 | Env contract → Conditional key | COVERED | 3.1 env (`required-if` both ways) |
| 47 | Env contract → Fixed value changed by the template | COVERED | 3.1 env (`drift`); observed at the gate if the box's `DB_URL` drifts |
| 48 | Env contract → Setting changed on the server | COVERED | 3.1 env (`setting` ok) |
| 49 | Env contract → Placeholder left in | COVERED | 3.1 env (`placeholder`) |
| 50 | Env contract → Credentials out of step | COVERED | 3.1 env (`mismatch`) |
| 51 | Fill → Secret typed in | MANUAL | 3.2b TTY transcript + gate run of `provision.sh secrets`; `env_set` mode and atomicity in 3.1 |
| 52 | Fill → Optional skipped | MANUAL | 3.2b TTY transcript |
| 53 | Fill → Not a terminal | COVERED | 3.2b off-TTY path |
