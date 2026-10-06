## Why

Production is deployed by hand from `docs/DEPLOYMENT.md`. The 2026-10-06 deploy took about twenty ad-hoc commands and still exposed what a manual run cannot hold:

- the server had drifted onto a feature branch, with a firewall fix that existed only there;
- new required `backend.env` keys had to be found by grepping key names one at a time;
- the MinIO release and the database backup were recorded by hand;
- the forged-header check returned a false negative because its requests were sent one at a time;
- `:latest` image tags overwrote the previous build, so there was no rollback.

Every deploy repeats this, and each step is one that can be skipped silently.

## What Changes

- **New `deploy/deploy.sh <commit>`**, versioned and run on the VPS from `/opt/agreementmitra`. Each step runs only if the previous one passed, and a failure stops the deploy.
  - **Preflight:** refuses unless all of these hold.
    - The deploy checkout has no tracked changes.
    - The commit is reachable from `origin/main`, checked before any of its code runs. Merge rights on `main` therefore mean root on prod.
    - The **env contract** holds for all four env files, checked against the target commit's templates. Every templated key is tagged secret or config, with a class (`fixed`, `setting`, `generated` or `vendor`) and a need (`required`, `optional` or `required-if`). The deploy refuses on any of these:
      - a missing key;
      - a required key left blank;
      - a placeholder secret;
      - a value that fails its pattern;
      - a `fixed` value that differs from the template;
      - backend credentials that differ from the store credentials they copy.

      Blank optional keys are reported, not refused. The deploy prints key names only and never prompts; `--dry-run` is the review.
    - The compose subnet does not collide with another network on the box.
    - The database's applied migrations do not exceed the target commit's.
    - No other deploy is running.
  - **Backup:** dumps Postgres inside its container to `/root/backups`, checks the dump before renaming it, and rotates by count and age.
  - **Checkout and build:** checks out the commit and builds the images tagged with its 12-character hash, keeping earlier tags. `--refresh-base` also pulls fresh base images.
  - **Start:** runs `up -d --pull never`. It runs `down` first only when the network definition changed, and only with `--allow-downtime`.
  - **Post-checks:** every service is healthy, every shipped migration ran, Caddy holds its static address, the MinIO release is unchanged, and startup logged no ERROR.
  - **Deploy log:** appends one line to a deploy log on the server. The deployed tag is written to `deploy/.env`, so manual compose commands use it too.
  - **Modes:**
    - `--dry-run` runs the preflight only.
    - `rollback` walks back through successful deploys, checks out the target, and refuses when a migration ran in between.
    - `accept` records a deploy that failed only its ERROR-log scan, after re-running the other checks.
    - Deploying an older `main` commit needs `--allow-downgrade`.
    - The first scripted deploy tags the running pre-script images as a rollback target.
- **New `deploy/smoke-prod.sh`**, run from outside the box, so it sees what Cloudflare sees. It exits non-zero on the first failed check.
  - The apex returns 200 and `www` returns 301.
  - An oversized POST returns 413.
  - The CSRF cookie is `__Host-`, `Secure` and `SameSite=Lax`.
  - `/api/templates` and the `public, max-age` template form are `cf-cache-status: DYNAMIC`. No response carries a Cloudflare challenge.
  - Both webhook paths reach the application (401) instead of a Cloudflare challenge.
  - An opt-in `--forged-header` check sends its burst in parallel and reads the lockout event over SSH. It warns first that it locks the caller's /24 out of default-class routes for about 5 minutes.
- **`deploy/docker-compose.prod.yml`:** the built services (`backend`, `caddy`, `gotenberg`) name their image with a required `DEPLOY_TAG` variable, so a build no longer overwrites the running one.
- **`deploy/env/*.env.example`:** every assigned key gains a `#@` tag line above it. A template lint fails on an untagged key, and the deploy refuses a target whose template has one.
- **`deploy/provision.sh secrets` becomes the one interactive fill step.**
  - It reads the tags through the shared library the preflight uses, so the two cannot disagree.
  - It still generates `generated` keys.
  - On a TTY it asks for each failing key: secrets without echo, optional keys skippable with Enter, and a `fixed` drift shown and applied only on `y`.
  - It writes atomically with `0600` and prints a summary. It exits non-zero while anything still fails.
  - The hard-coded vendor-key list goes; the tags replace it.
- **`docs/DEPLOYMENT.md`:** §4 "Bring the stack up" and the MinIO manual gate point at the script. The §5.6 outside-in check points at `smoke-prod.sh --forged-header`.

## Capabilities

### New Capabilities
- `production-deploy`: how a commit reaches production. It covers the preflight gates the deploy refuses on, the pre-change backup, the no-pull rule, downtime only by consent, the post-deploy verification, the deploy log, rollback, and the external smoke check.

### Modified Capabilities
None. No application behaviour changes.

## Impact

- **New files:** `deploy/deploy.sh`, `deploy/smoke-prod.sh`, `deploy/lib/checks.sh`, fixture-driven shell tests for the library, and a template lint.
- **Modified:** `deploy/docker-compose.prod.yml` (image names only), `deploy/provision.sh` (sources the library; `secrets` becomes the fill step), the four `deploy/env/*.env.example` templates (tag lines only) and `docs/DEPLOYMENT.md`.
- **Server state:**
  - New: `/var/lib/agreementmitra/deploys.log`, `/run/agreementmitra/deploy.lock` and `deploy/.env`.
  - `/root/backups/` gains rotation.
- **Signing FSM:** untouched. No transition, webhook or eSign path changes.
- **Register overlap:**
  - `prod-readiness-preflight`: this change is its deploy-time half, including blank and placeholder secrets. The fail-closed startup validation under a `prod` profile stays there, and gains a test that every `required` template key is validated by the app.
  - `prod-minio-image-pin`: the "release unchanged" check becomes "release equals the pin" once that pin lands.
  - CR-7 `ci-pipeline`: can later call `deploy.sh` instead of re-implementing it.
- **Out of scope:**
  - A storage health indicator in the backend, so that "healthy" proves a MinIO write. That is a separate CR.
  - CI (CR-7).
  - Pinning MinIO (`prod-minio-image-pin`).
  - Automating the Cloudflare dashboard settings in §5.6. The smoke script verifies only what is observable from outside.

## PII / security checklist

- **Aadhaar / OTP / VID:** none. The scripts touch no signing data.
- **Secrets:**
  - The deploy reads `deploy/env/*.env` values only to compute verdicts (blank, placeholder, pattern, drift, match). It compares them in-process and prints key names and verdicts only. It never prints, logs or copies a value.
  - The fill step reads secrets without echo and never puts them in argv. It may show `config` values when offering a `fixed` drift, but never a `secret`.
  - The deploy log records the commit hash, timestamps, the MinIO release, the Flyway version and the outcome. It contains no environment values.
- **PII in backups:** the pre-deploy dump contains customer data from production.
  - It is written `0600` into a `0700` root-owned directory.
  - It is rotated by count and by a 30-day age cap.
  - It stays unencrypted on the same disk, so it is a rollback point, not a backup.
- **Smoke check:**
  - It redacts cookie values before printing headers.
  - The forged-header check reads only the `rate_limit_lockout` event, which already carries a redacted source and no agreement id.
  - It sends no agreement id and creates no agreement.
- **Data:** the scripts create no data. Production holds real founding-team customer data, so the repo's sandbox-and-dummy-data rule covers the code here, not the server.
