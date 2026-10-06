# Deploying to production

How code gets to production. This page is the overview. For the full procedure, flags and edge
cases, see [`DEPLOYMENT.md`](DEPLOYMENT.md) §4.

## The short version

1. **Merge to `main`.** Production deploys only commits on `main`.
2. **SSH to the box and start `tmux`.** A dropped connection then cannot kill a deploy half-way.
3. **Check first, then deploy:**

   ```sh
   cd /opt/agreementmitra && git fetch origin
   git show origin/main:deploy/deploy.sh | bash -s -- --dry-run <commit>   # checks only, changes nothing
   git show origin/main:deploy/deploy.sh | bash -s -- <commit>             # the real deploy
   ```

4. **Run the smoke check from your laptop:** `deploy/smoke-prod.sh`. Then make one real
   agreement in the browser (Generate) to prove storage works.

That is all. Expect 5–10 minutes, most of it the image build, and about a minute of downtime
while the backend restarts.

## What the deploy does

```
checks ──► backup ──► build ──► restart ──► verify ──► record
  │           │          │          │           │          │
  refuses     database   images     about a     health,    one line in
  before      dump,      tagged     minute of   schema,    the deploy log
  touching    verified   with the   downtime    Caddy,
  anything    first      commit                 MinIO,
                                                errors
```

- **Checks** refuse the deploy before anything changes. They cover:
  - local edits on the box;
  - a commit that is not on `main`;
  - a broken env file (see below);
  - a database newer than the code;
  - another deploy already running.
- **Backup:** a database dump in `/root/backups`. It is a quick undo point on the same disk, not
  a real backup.
- **Every build keeps its own image tag**, so the previous version is always ready to go back to.

## When something goes wrong

| Situation | Do this |
|---|---|
| The deploy failed before the restart | Nothing is affected. Fix the cause and re-run. |
| The deploy failed after the restart | `deploy/deploy.sh rollback`. The script prints this command for you. |
| The new version misbehaves | `deploy/deploy.sh rollback` goes back one good version. Run it again to go back further. |
| It failed only because of ERROR lines you judge harmless | `deploy/deploy.sh accept` |
| Rollback refuses because of a database migration | The code cannot safely go back. Restoring the printed dump is a manual decision. |

## Env files: the one interactive step

Each service's secrets and settings live on the box in `deploy/env/*.env`, and only there. Every
key in the matching template (`deploy/env/*.env.example`) carries a one-line tag. The tag says
whether the key is secret, who sets it, and whether it is required.

- **The deploy never asks questions.** It checks every key and refuses if any key is missing,
  blank, a placeholder, malformed, duplicated, or differs from a fixed value. It prints key names,
  never values.
- **To fix or fill keys, run `./provision.sh secrets` from `deploy/` on the box.** It asks only for
  what is wrong, and secrets are not echoed.
- **Adding a new env var in a change?** Tag it in its template. An untagged key blocks every
  deploy. The template header explains how to choose a tag.

## Where things live (on the box)

| What | Where |
|---|---|
| Code checkout (left at the deployed commit) | `/opt/agreementmitra` |
| Running version | `deploy/.env` (written by the script; never edit by hand) |
| Deploy history | `/var/lib/agreementmitra/deploys.log` |
| Pre-deploy dumps | `/root/backups/pre-deploy-*.sql.gz` (the 10 newest; none older than 30 days) |

## Rules

- **Never run `docker compose ... --build` by hand.** It would overwrite the running image.
- **Never run `docker compose pull`.** It can silently upgrade MinIO, and a newer MinIO can break storage.
- **Merge rights on `main` are, in effect, root on production.** Treat branch protection accordingly.

<!-- practice deploy 2026-10-07 -->
