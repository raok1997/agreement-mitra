# Production deployment: Contabo VPS + Cloudflare

Runbook for the single-box production deployment of AgreementMitra. Companion to
`docs/DOMAIN-AND-EMAIL-SETUP.md`, which covers mail; this file covers the server
and the website.

**Host:** Contabo VPS, 6 vCPU / 12 GB RAM, `217.217.250.135`
(`vmi3472603.contaboserver.net`).

**Status:** in progress. Steps are marked DONE as they are completed.

---

## 0. Shape of the deployment (decided)

- **Everything on one box.** Caddy, the Spring Boot API, Postgres, MinIO and
  Gotenberg all run under Docker Compose on the VPS, and the same origin serves
  the SPA and `/api`. This resolves the open "`/api` problem" in
  `DOMAIN-AND-EMAIL-SETUP.md` section 4 by choosing option (c): one origin, so no
  CORS, and the HttpOnly session cookie (`__Host-am_session`) plus its CSRF
  companion (`__Host-XSRF-TOKEN`) work unmodified. Both are `__Host-` cookies
  (no `Domain`), so they depend on the apex-only single origin -- `www.`
  redirects to the apex and never receives them. **The Cloudflare Pages plan in
  that document is superseded.**
- **Never edge-cache `/api/*`.** Every API response may carry a per-browser
  `Set-Cookie: __Host-XSRF-TOKEN` (the CSRF token is issued eagerly), and
  `GET /api/templates/form` sets its own `Cache-Control: public, max-age`, so
  Spring Security writes no cache header on it. A Cloudflare cache rule over
  `/api/*` would serve one visitor's CSRF cookie to others. Leave `/api/*` on
  Cloudflare's default (bypass for dynamic content) and add no "Cache
  Everything" rule that matches it. Never log the `Cookie` or `Set-Cookie`
  headers either: the session cookie is the credential.
- **Cloudflare in front, proxied (orange cloud), from day one.** The origin IP is
  never published in DNS. TLS is terminated at the Cloudflare edge and
  re-originated to Caddy against a Cloudflare Origin CA certificate, with SSL/TLS
  mode **Full (strict)**.
- **The site is fully public. No Cloudflare Access.** Anonymous use is the
  product design, not a gap: an anonymous visitor creates an agreement without
  logging in, and `agreement-ownership` lets them **claim** it into an account
  later. `docs/ROADMAP.md` states it directly -- "Login stays optional: create +
  draft-upload + capability read remain fully anonymous."
- **The agreement id is a bearer capability.** `Agreement` ids are
  `UUID.randomUUID()` (UUIDv4, 122 bits of entropy), so they cannot be
  enumerated, and holding the id is what grants read access. This is what makes
  a public `GET /api/agreements/{id}` acceptable. Two consequences follow:
  never log an agreement id, and never let one leak through a `Referer` header
  or an analytics URL -- the id *is* the credential.
- **Rate limiting is the compensating control**, not authentication. The
  unauthenticated write endpoints are abuse-exposed rather than breach-exposed:
  unbounded row creation, 10 MB draft uploads filling the disk, and Gotenberg
  renders exhausting CPU. Two layers bound them without blocking legitimate
  anonymous use (`anonymous-surface-abuse-controls`): at the edge, Cloudflare Bot
  Fight Mode and a rate-limiting rule on `POST /api/*` (excluding the webhook
  paths), plus Caddy's request-body ceilings; in the application, per-route-class
  rate limits keyed on the real client address, a 1 MiB body guard, render
  admission control and redacted security-event logging. Section 5.6 lists the
  edge half and how to verify it.
- **Hostnames:** `agreementmitra.com` (canonical) and `www.agreementmitra.com`
  (permanent redirect to the apex).

### Why the origin IP must never appear in DNS

Hiding the origin is the main security benefit of the proxied setup. Passive-DNS
services archive every record they ever observe, so a single unproxied record --
even one added briefly during setup, and even on an unrelated subdomain like
`ssh.` or `direct.` -- permanently defeats it. Reverse DNS on the IP already
discloses the Contabo hostname; do not add to it.

SSH therefore connects to the raw IP, never to a hostname. See section 7.

---

## 1. Prerequisites

| | Item | Owner |
|---|---|---|
| [ ] | `agreementmitra.com` nameservers pointed at Cloudflare, zone Active | you |
| [ ] | SSH public key installed on the VPS for `root` | you |
| [ ] | Repo checked out on the VPS at `/opt/agreementmitra` | provisioning |

Install the SSH key from your workstation (not from the server):

```sh
ssh-copy-id -i ~/.ssh/id_ed25519.pub root@217.217.250.135
```

Adding the site to Cloudflare: **Add a site** -> `agreementmitra.com` -> Free
plan -> replace the registrar's nameservers with the two Cloudflare gives you.
Propagation is typically 1-6 hours. Do not create DNS records yet.

---

## 2. Provision the server

```sh
ssh root@217.217.250.135
git clone <repo-url> /opt/agreementmitra
cd /opt/agreementmitra/deploy
chmod +x provision.sh
./provision.sh all
```

### Line endings: normalize after any transfer from Windows

A repo checked out on Windows has CRLF line endings on disk. Copying that tree to
Linux (rather than cloning fresh) carries them over, and **`gradlew` then fails
with `./gradlew: not found`** -- exit 127, because its `#!/bin/sh\r` shebang does
not resolve. The error names the file, so it reads like a missing file rather
than a line-ending problem.

Normalizing only `*.sh` is not enough: `gradlew` has no extension. After any
non-git transfer:

```sh
apt-get install -y dos2unix
cd /opt/agreementmitra
find . -type f ! -name "*.jar" ! -name "*.png" ! -name "*.jpg" ! -name "*.ico" \
       ! -name "*.woff*" ! -name "*.pdf" -exec dos2unix -q {} \;
chmod +x backend/gradlew deploy/provision.sh
```

Cloning directly on the server avoids this entirely and is the better long-term
answer -- it requires the `deploy/` artifacts to be committed.

`provision.sh` is idempotent and splits into `harden`, `docker`, `firewall` and
`secrets` subcommands if you want to run them separately. `all` covers the first
three; run `secrets` separately (section 3). It:

- applies OS updates and enables unattended **security** upgrades;
- adds a 4 GB swapfile with `vm.swappiness=10` (insurance against the OOM killer
  reaping Postgres when Chromium spikes during concurrent renders);
- installs fail2ban with an aggressive `sshd` jail;
- installs Docker CE and creates a `deploy` user in the `docker` group;
- configures ufw to deny all inbound except SSH;
- restricts Docker-published 80/443 to **Cloudflare's published ranges** (fetched
  live, failing closed) via the `DOCKER-USER` chain plus a systemd unit that
  survives daemon restarts -- see the warning below on why ufw cannot do this.

### SSH remains password-accessible (deferred, by decision)

`provision.sh all` deliberately does **not** run `ssh-keyonly`. SSH password
authentication stays enabled during build-out so the box is reachable from any
machine, not only one holding the key. fail2ban is the only compensating control.

Close this before the box handles real user data:

```sh
./provision.sh ssh-keyonly
```

It refuses to run unless `/root/.ssh/authorized_keys` is already populated, so it
cannot lock you out. To reverse it, delete
`/etc/ssh/sshd_config.d/99-agreementmitra.conf` and reload `ssh`. Contabo's VNC
console is the out-of-band recovery path either way -- it does not go through
sshd.

With password auth on, fail2ban can lock **you** out after 5 failed attempts.
Clear a ban with:

```sh
fail2ban-client set sshd unbanip <your-ip>
```

### ufw does NOT protect Docker-published ports

This bit is counter-intuitive and cost a live exposure during bring-up, so it is
worth stating precisely.

Docker DNATs inbound traffic for published ports in `nat/PREROUTING` and filters
it on the FORWARD path. That traffic **never traverses ufw's INPUT chain**, so a
ufw rule such as `ufw allow from <cloudflare> to any port 443` is completely
inert while Caddy publishes 443. `ufw status` will happily show the rule while
the port is open to the entire internet -- which is exactly what happened here:
the origin answered `200` on its raw IP with ufw active and reporting Cloudflare
restrictions.

The correct hook is the **`DOCKER-USER`** chain, which Docker guarantees it will
not overwrite. `provision.sh firewall` installs:

- `/usr/local/sbin/am-docker-firewall.sh` -- allows established/related, then the
  Cloudflare ranges cached in `/etc/agreementmitra/cloudflare-ips-v{4,6}`, then
  DROPs everything else to 80/443. It **fails closed**: missing cache files mean
  drop, not allow.
- `am-docker-firewall.service` -- a systemd oneshot ordered `After=docker.service`
  that reapplies the rules. This is required because **Docker rebuilds its chains
  whenever the daemon restarts**, silently discarding one-shot `iptables`
  commands. Rules applied by hand will not survive a reboot.

Verify it is actually working -- do not trust `ufw status`:

```sh
# From a machine that is not Cloudflare. Both must fail.
curl -sk --max-time 15 --resolve agreementmitra.com:443:217.217.250.135 \
     https://agreementmitra.com/ -o /dev/null -w '%{http_code}\n'   # expect 000
curl -s  --max-time 15 http://217.217.250.135/ -o /dev/null -w '%{http_code}\n'  # expect 000

# And the chain itself
iptables -L DOCKER-USER -n --line-numbers
```

`--dport` in those rules is the **container** port, since DNAT has already
rewritten the destination by the time `DOCKER-USER` runs. This stack maps 80->80
and 443->443 so the numbers coincide; they would not under a different mapping.

**Never add a host port binding for Postgres, MinIO or Gotenberg** -- it would be
exposed by the same mechanism. Use an SSH tunnel (section 7).

---

## 3. Configuration and secrets

Each service reads its own gitignored env file from `deploy/env/`, so no service
receives another's credentials:

| File | Consumed by | Template |
|---|---|---|
| `deploy/env/postgres.env` | `postgres` | `postgres.env.example` |
| `deploy/env/minio.env` | `minio` | `minio.env.example` |
| `deploy/env/backend.env` | `backend` | `backend.env.example` |
| `deploy/env/web-build.env` | the `caddy` image **build** (baked into the public SPA bundle; optional, never a secret) | `web-build.env.example` |

Generate them **on the server**:

```sh
cd /opt/agreementmitra/deploy
./provision.sh secrets
```

That seeds all four files, generates every random value, keeps the three
cross-file credential pairs in agreement (`POSTGRES_PASSWORD`/`DB_PASSWORD`,
`MINIO_ROOT_USER`/`S3_ACCESS_KEY`, `MINIO_ROOT_PASSWORD`/`S3_SECRET_KEY`) and
`chmod 600`s them. It then checks every key against its template tag (below) and,
**on a terminal, asks for each one that fails**: a secret is read without echo,
an optional key is left blank with Enter, a malformed value is asked again, and a
`fixed` value that differs from the template is shown (server -> template) and
replaced only on `y`. Files are rewritten atomically, `0600`; a typed value holding
`$`, `#`, a quote or a backslash is stored single-quoted so compose reads it
literally, and one holding a single quote is refused. It ends with a
summary -- set, optional-blank, still failing -- and exits non-zero while
anything still fails. Off a terminal it asks nothing and only reports.

**This is the one interactive env step.** `deploy/deploy.sh` checks the same
contract and refuses on a failure, but never asks: its `--dry-run` is the review.
To check `backend.env` against a commit you are about to deploy rather than the
checkout's template, the deploy prints the exact command, of the form:

```sh
d=/root/templates.<tag> && mkdir -p $d
for f in backend postgres minio web-build; do git -C /opt/agreementmitra show <commit>:deploy/env/$f.env.example > $d/$f.env.example; done
./provision.sh secrets --templates $d
```

Secrets are generated **on the server** and never anywhere else -- not on a
workstation, not in a chat window, never committed.

### `secrets` is also the upgrade path for an existing box

Run it on a server that already has a `backend.env` and it backfills every
variable the template carries but that file is missing, using the template's
value -- then reports what it added. Existing values are never touched.

This matters because a `backend.env` written against an older version of this
runbook is missing whole features' worth of fixed values (`PUBLIC_BASE_URL`,
`PAYMENT_MODE`, `MAIL_*`, `ZOOP_*`, `RULES_STAMP_DUTY_ALLOW_UNREVIEWED`), and
every one of them fails **silently** on the application default rather than at
boot. Generating the secrets while leaving those absent would report success and
fix nothing.

Some backfilled values are business decisions rather than stack facts -- they are
the `setting` keys (`PAYMENT_MODE`, `RULES_STAMP_DUTY_ALLOW_UNREVIEWED`, ...).
Review them before restarting.

### Re-running `secrets` never rotates anything

It is idempotent and deliberately non-destructive: a value already set is left
alone, and only blank generated keys are filled. This is a correctness
requirement, not politeness.

- `postgres:17` honours `POSTGRES_PASSWORD` **only at first initdb**. Rotating
  it in the env file after the volume exists does not change the database
  password -- it only makes `DB_PASSWORD` wrong, and the backend then fails
  authentication against a database the on-disk credentials can no longer reach.
  Rotation needs an `ALTER USER ... PASSWORD`.
- Both peppers key data **at rest**. Rotating `AUTH_HASH_PEPPER` invalidates
  every live session; rotating `ESIGN_WEBHOOK_KEY_PEPPER` makes stored
  per-transaction webhook keys undecryptable, so in-flight signings can no
  longer accept their callbacks.

### Variables

**`deploy/env/backend.env.example` is the authoritative list** -- it carries
every variable that needs a non-default value in production, annotated inline.
It is deliberately not duplicated here: the table that used to live in this
section documented 27 of the 83 variables the app reads, and the gap is what
let the traps below go unnoticed.

Every assigned key in the four templates carries a tag on the line directly above
it -- the grammar and a "choosing a tag" guide are in `backend.env.example`'s
header, and `deploy/test/templates.test.sh` (and the deploy itself) refuse an
untagged key:

```
#@ <secret|config> <fixed|setting|generated|vendor> <required|optional|required-if=KEY=VALUE> [pattern=<ERE>] [match=<file>:<KEY>]
```

| Class | Meaning | Deploy-time check |
|---|---|---|
| `fixed` | Correct as written for this compose stack. | The server value must **equal** the template's; a difference fails the deploy (`drift`). A change comes from the template, via `provision.sh secrets`. |
| `setting` | An operator choice; the template holds the default. | Checked against its `pattern` only. **Changed on the server, never fails the deploy** -- payments (`PAYMENT_MODE`), the eSign provider and host, unreviewed rules, draft retention, mail provider, log level. |
| `generated` | Created on the server by `provision.sh secrets`. Never copied from a laptop. | Not blank, not a placeholder, 32+ alphanumerics, and equal to the key it `match`es in another file. |
| `vendor` | Issued by a third party. The only class legitimately copied in from elsewhere. | Not blank when `required` (or when its `required-if` mode holds); never a placeholder; its `pattern` if any. |

A `secret` value is never printed by any script; the deploy prints key names and
verdicts only.

The `vendor` distinction is the one that matters when porting config from a
development machine. Vendor credentials (ZOOP, Razorpay, Zoho, Google) cannot be
regenerated and must be copied; infrastructure credentials must **not** be --
local `S3_ACCESS_KEY`/`S3_SECRET_KEY` are `minioadmin`/`minioadmin`, hardcoded in
`backend/start_local.sh`, and both peppers have published dev defaults.

### Things that fail silently if unset

Every item below boots green and passes the section 4 smoke tests. None of them
fails at startup; they fail later, at request time, or not visibly at all. This
is the part of the configuration that actually needs reviewing.

| Variable | Default | What the default does in production |
|---|---|---|
| `AUTH_HASH_PEPPER` | `dev-only-identity-pepper-change-me` | Session/handoff/login-state hashes keyed by a published value. |
| `ESIGN_WEBHOOK_KEY_PEPPER` | `dev-only-webhook-key-pepper-change-me` | Per-transaction eSign webhook keys encrypted at rest under a published value, so a database read yields a working credential. That key authenticates inbound state changes. |
| `PUBLIC_BASE_URL` | `http://localhost:5173` | Recovery links emailed to the parties point at localhost. Unusable, and nothing warns. |
| `RULES_STAMP_DUTY_ALLOW_UNREVIEWED` | `false` | **No state is chargeable at all** -- see below. |
| `PAYMENT_MODE` | `REQUIRED` | Payment required while `RAZORPAY_KEY_ID`/`RZP_KEY_SECRET` default blank, so checkout fails at request time. Set `DISABLED` to bring the box up before the gateway account exists. |
| `MAIL_PROVIDER` | `stub` | The email channel is enabled by default, so delivery reports success and sends nothing. |
| `ESIGN_PROVIDER` | `zoop` | Correct, but `ZOOP_RESPONSE_URL`/`ZOOP_REDIRECT_URL` default **blank**: the callback never arrives and signatures complete only via the reconciliation job. |
| `LOGGING_LEVEL_IN_AGREEMENTMITRA` | `INFO` | Correct. Set `DEBUG` only deliberately, for a bounded diagnosis: application lines redact agreement ids to an 8-character prefix, but debug output is still more than production needs. **Do not** raise framework loggers instead -- `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB=DEBUG` logs request URIs (`/api/agreements/<id>/...`), Hibernate bind `TRACE` logs parameter values, root `DEBUG` does both, and enabling a Caddy `log` directive records request URIs; each writes raw agreement ids. |
| `SIGNING_DRAFT_RETENTION_ENABLED` | `false` | Unpaid drafts are never purged, breaking the 90-day deletion terms of service section 10 promises -- see below. |
| `DB_URL` | `...?logServerErrorDetail=false` | Correct. An override must keep `logServerErrorDetail=false`, or a unique violation logs the raw agreement id (Postgres `DETAIL`) at ERROR. |

### Paid fulfilment is off by default, and that is easy to miss

`rules` is the only source of paid-fulfilment eligibility (there is no
allowlist). A state is chargeable when its rule carries a counsel review
matching its hash, **or** `rules.stamp-duty.allow-unreviewed=true`.

Both shipped rule sets are unreviewed -- `rules/stamp-duty/TG/lease-residential.yaml`
and `.../KA/lease-residential.yaml` both carry the "sandbox / founding-team beta
only" marker. `application-local.yml` defaults the flag to `true`; the root
`application.yml` defaults it to `false`, and **there is no
`application-sandbox.yml`**, so the `sandbox` profile this deployment runs under
inherits `false`.

Net effect: with the runbook followed exactly, no state is chargeable and paid
fulfilment is blocked for every customer -- while the app boots clean and the
smoke tests pass. `backend.env.example` sets it to `true` to match the
founding-team-beta posture the rule files describe. Confirm that is what you
want before going live, and revisit it when counsel review lands.

Lock the files down (`provision.sh secrets` already does this):
`chmod 600 deploy/env/*.env`. `web-build.env` holds only public operator identifiers, but is
locked down with the rest; an existing server created before it existed runs
`touch deploy/env/web-build.env` once (absent also builds, as "being issued").

### Draft retention is opt-in

A daily job (03:30 IST) deletes every unpaid draft whose content has gone 90
days without an edit, and sweeps `drafts/` objects a failed delete left behind
(change `stale-draft-purge`). It runs **only** where
`SIGNING_DRAFT_RETENTION_ENABLED=true`; `backend.env.example` sets it, and
production must keep it set -- terms of service section 10 promises the
deletion. Unsetting it is an incident-only lever.

It is off by default because the purge deletes objects in whatever bucket it
is pointed at. A database restored or cloned from production and run against
the same bucket (`S3_BUCKET` defaults to the same name everywhere) would purge
agreements that are live in production and remove their PDFs. **Invariant: each
environment has its own bucket.** Never enable the job on a restored or cloned
database until its `S3_BUCKET` is confirmed to be that environment's own.

### Why the `sandbox` profile is required

Without it the app boots and serves the SPA, but the **template catalog is
empty**, so `/start` fails with "Could not load templates" and no agreement can
be created. Two beans are gated to `@Profile({"local", "sandbox"})`:

- `TemplateCatalogSeeder` -- discovers layer sets on the classpath and seeds the
  `template` rows;
- `RegistryLayerSource` -- resolves those layer sets at render time.

**There is no production template-seeding path in the codebase today.** The only
mechanism is this profile gate, which exists to enforce the repo's sandbox-and-
dummy-data-only policy. Running `sandbox` is therefore the only way to have a
working app, and it is consistent with that policy while no vendor accounts
exist.

Use `sandbox`, **not** `local`: there is no `application-sandbox.yml`, so the
profile enables exactly those two beans and changes nothing else. `local` would
additionally load `application-local.yml` and its development overrides.

Seeding is idempotent per `(state, type)` pair, so restarts never duplicate rows.
Expect four published rows: `IN`/`TG` x `residential`/`commercial`.

**Revisit this before real users.** A production catalog wants published,
reviewed templates loaded through a deliberate mechanism, not a dev seeder.

### What blank credentials actually do

Every vendor integration tolerates absent credentials at **startup** -- they fail at
request time instead -- so the deploy refuses them rather than the JVM. Which blanks
are allowed is the template's `required`/`required-if`/`optional` tag on each key,
with the cost of a blank in the comment above it: Google login is `optional`; ZOOP
is required while `ESIGN_PROVIDER=zoop`, Leegality while `ESIGN_PROVIDER=leegality`,
Razorpay while `PAYMENT_MODE=REQUIRED` (set `DISABLED` to bring the box up before
the gateway account exists), the Zoho password while `MAIL_PROVIDER=smtp`.

Repo policy remains sandbox and dummy data only.

The peppers are the exception to all of this: they have working dev defaults, so
a missing value does not fail at all -- it falls back to a published value. See
"Things that fail silently if unset" above, which covers both of them along with
the other defaults that are wrong for production.

---

## 4. Bring the stack up

**First bring-up on a fresh box stays the manual procedure below.** Every later
deploy goes through `deploy/deploy.sh` -- see "Deploying a change" at the end of
this section.

Before the Cloudflare zone is Active you have no Origin certificate, so generate
a self-signed placeholder. Caddy needs *a* certificate at the configured paths;
swapping in the real one later is a file replace and a restart, with no config
change.

```sh
cd /opt/agreementmitra/deploy
mkdir -p certs && chmod 700 certs
openssl req -x509 -newkey rsa:2048 -nodes -days 30 \
  -keyout certs/origin.key -out certs/origin.pem \
  -subj "/CN=agreementmitra.com"
chmod 600 certs/origin.key

# The compose file names each built image by the running tag and refuses to render
# without it. The first bring-up writes it by hand; deploy/deploy.sh owns it afterwards.
printf 'DEPLOY_TAG=%s\n' "$(git rev-parse --short=12 HEAD)" > .env && chmod 600 .env

docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
```

The first build compiles the backend and the SPA on the box; expect several
minutes. Watch it settle:

```sh
docker compose -f docker-compose.prod.yml logs -f backend
```

Health checks gate startup order: Caddy waits for the backend, which waits for
Postgres, MinIO and Gotenberg. The backend's `start_period` is 90s because Flyway
migrations run before it reports ready.

Smoke test from the box itself. Use `--resolve`, **not** `-H 'Host: ...'`: the
Caddyfile defines sites only for `agreementmitra.com`, so a request to
`https://localhost` sends SNI `localhost`, matches no site, and the TLS handshake
is rejected before the `Host` header is ever read. That failure looks like a
broken server (`curl` reports `000`) when the server is fine.

```sh
R="--resolve agreementmitra.com:443:127.0.0.1 --resolve www.agreementmitra.com:443:127.0.0.1"

curl -sk $R https://agreementmitra.com/        -o /dev/null -w 'spa      %{http_code}\n'
curl -sk $R https://agreementmitra.com/start   -o /dev/null -w 'fallback %{http_code}\n'
curl -sk $R "https://agreementmitra.com/api/templates/form?state=TG&type=residential" \
                                               -o /dev/null -w 'api      %{http_code}\n'
curl -sk $R https://www.agreementmitra.com/    -o /dev/null -w 'www      %{http_code} -> %{redirect_url}\n'

docker compose -f docker-compose.prod.yml exec backend curl -sf localhost:8090/actuator/health
```

Expect `200`, `200`, `200`, `301`, and `{"status":"UP"}`. Note that
`/api/templates/form` **requires** `state` and `type` query parameters; a bare
request correctly returns 400, which is easy to misread as a proxy fault.

Confirm the schema and catalog:

```sh
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U agreementmitra -d agreementmitra \
  -c "select version, description, success from flyway_schema_history order by installed_rank desc limit 5;"

docker compose -f docker-compose.prod.yml exec postgres \
  psql -U agreementmitra -d agreementmitra -c "select state, type, status from template;"
```

The template query must return rows. An empty catalog means the `sandbox` profile
is not active -- see the note in section 3.

### MinIO image -- recorded and checked by every deploy

`docker-compose.prod.yml` runs `minio/minio:latest`, which is **unpinned**
(register row `prod-minio-image-pin`). Two facts make that dangerous:

- **A newer MinIO can break storage.** `RELEASE.2025-09-07` answers minio-java
  8.6.0's bucket calls in a form it cannot parse, so every PDF write 500s
  (`Failed to ensure bucket`) while the backend still reports healthy.
  `RELEASE.2023-09-04T19-57-37Z` is the known-good release (dev and the test
  harness pin it).
- **An older MinIO cannot read a newer one's data.** Moving to an older release
  than the one that wrote the volume makes MinIO exit at startup with
  `Unknown xl header version 3`. So never "fix" the first problem by pinning
  lower than what is running.

`deploy/deploy.sh` records the running release before the restart, never pulls
(`up --pull never`), and fails the deploy (`postcheck:minio`) if the release
differs afterwards; the release is on every deploy-log line. What stays manual,
until the storage-health-indicator CR lands, is **one real storage write after
every deploy**: in the browser, start an agreement at `/start`, fill it in and
press **Generate**. A 500 there with MinIO up is this failure, not an application
bug. Never run `docker compose pull` on this box -- it fetches whatever `latest`
is today.

If the recorded release is `RELEASE.2025-09-07` or later, storage is likely
already broken -- check with step 3 before deploying anything else, and resolve
`prod-minio-image-pin` first.

### Deploying a change: `deploy/deploy.sh`

A one-page overview for humans is in [`DEPLOYING.md`](DEPLOYING.md); this section is the full reference.

One command deploys a commit from `main`, and refuses before touching anything
when the box is not in a deployable state. Run it as root **inside `tmux`**: a
dropped SSH session otherwise kills the deploy mid-way (the script warns, and the
deploy log records the interruption).

```sh
tmux new -s deploy                      # or: tmux attach -t deploy
cd /opt/agreementmitra
git fetch origin && git show origin/main:deploy/deploy.sh | bash -s -- --dry-run <hash>
git fetch origin && git show origin/main:deploy/deploy.sh | bash -s -- <hash>
```

That bootstrap line is the form to use; `deploy/deploy.sh <hash>` from the
checkout behaves the same, because whatever copy is invoked re-executes
**origin/main's** `deploy/deploy.sh` before trusting anything.

**Trust boundary:** the deploy runs `main`'s head deploy code as root, and builds
whatever commit on `main` you name. **Merge rights on `main` are root on
production** -- branch protection on `main` is a production control, and closing
SSH password auth (section 9, item 2) matters more once deploys are routine.

What it does, each step only if the previous one passed:

1. **Preflight** (`--dry-run` stops here and writes nothing):
   - the checkout has no uncommitted tracked changes, and the build contexts
     (`backend`, `frontend`, `docker`) hold no untracked or ignored files -- a
     stray `V27__x.sql` would otherwise ship and run;
   - the commit is on `origin/main` and does not track `deploy/env/*.env`,
     `deploy/.env` or `deploy/certs/`;
   - the **env contract** (section 3) holds for all four env files against the
     **target commit's** templates. On a failure it prints key names and verdicts
     and the `provision.sh secrets --templates` commands to fix them; it never asks;
   - the compose subnet collides with no other Docker network, and the network is
     unchanged (a change needs `--allow-downtime`: `down`, then `up`);
   - `postgres` is running (so first bring-up stays manual);
   - the database's applied migrations do not exceed the commit's;
   - the commit is not older than what runs (`--allow-downgrade`; normally use
     `rollback`) and is not what already runs (`--refresh-base`);
   - no other deploy, rollback or accept holds the lock.
2. **Dump** Postgres to `/root/backups/pre-deploy-<UTC>-<tag>.sql.gz` (verified
   before it is kept; see section 8) and rotate old ones.
3. **Check out** the commit -- the checkout is left **detached** at it -- and
   **build** `backend`, `caddy` and `gotenberg` as `agreementmitra-<svc>:<12-char hash>`.
   Earlier tags stay, so the previous build is a rollback target.
4. **Start**: write `deploy/.env`, then `up -d --no-build --pull never --wait`.
5. **Post-checks**: every service healthy; the database at the commit's highest
   migration; Caddy at `10.203.17.10`; the MinIO release unchanged; no backend
   line at level ERROR since start (only the count and logger names print).
6. **Record** one line in `/var/lib/agreementmitra/deploys.log` (state, not a
   rotatable log -- losing it means the next deploy re-seeds) and remove image tags
   no longer needed for rollback.

Then make the storage write above and run `deploy/smoke-prod.sh` from your
workstation (section 5.6).

**Flags.** `--dry-run`; `--allow-downtime` (network change); `--allow-downgrade`
(deploy an older `main` commit); `--refresh-base` (pull fresh base images and
rebuild under a distinct `<hash>-r<stamp>` tag -- do this **monthly**, since the
Dockerfiles build from floating base tags).

**`deploy/.env`** holds one line, `DEPLOY_TAG=<tag>`, written by the script before
every `up`. Manual commands from `deploy/` (`ps`, `logs`, `exec`, `up -d <svc>`)
therefore use what is deployed. Compose also reads any `COMPOSE_*` key in that
file, and an exported `DEPLOY_TAG` in your shell overrides it. **Never run a
manual `--build`**: it would build the checkout under the running tag.

**Rollback:** `deploy/deploy.sh rollback` walks back through successful deploys:
each run targets the previous good version (after a failed deploy, the last good
one). It checks out the target, restarts without building or pulling, and runs
the post-checks; if the target declares a different network (a later deploy changed
it with `--allow-downtime`), run it as `deploy/deploy.sh --allow-downtime rollback`.
After a deploy that failed part-way through `up` (services on different tags), it
restarts the last good version. It **refuses** when the database holds a migration the target
does not -- code-only rollback would then run old code on a newer schema -- and
prints the dump taken before that migration. Restoring it is manual, and the dump
is a same-disk rollback point that excludes object storage. It also refuses when
a target image is missing or not the one recorded.

**Accept:** when a deploy's (or a rollback's) only failure was the ERROR-line scan and the lines are
benign, `deploy/deploy.sh accept` re-runs the other post-checks and records the
deploy as good. A failed migration, MinIO or Caddy check can never be accepted --
a wrong Caddy address breaks forwarded-header trust.

**Moving an existing box onto the script (once):**

1. Merge the firewall fix (f9855e7) and this tooling to `main`.
2. On the server, discard the local `deploy/provision.sh` modification (it is
   identical to `main` once f9855e7 lands; a copy is in
   `/root/backups/provision-firewall-fix.patch`), and **confirm HEAD is the commit
   the running images were built from** (`593086f`) -- the first deploy tags the
   running images with HEAD's hash as the first rollback target.
3. Run `./provision.sh secrets` on a terminal until its summary shows nothing
   failing. Expect it to surface any `fixed` drift on the box (for example a
   `DB_URL` without `logServerErrorDetail=false`) and the vendor keys still blank.
4. Bootstrap a `--dry-run`; every check should pass, and no state is written.
5. Deploy for real. It seeds the history from the running images, writes
   `deploy/.env`, and moves the services to hash tags.
6. Run `deploy/smoke-prod.sh`, then delete the hand-made
   `pre-deploy-20261006-1423.sql.gz` once the scripted dump is verified.
7. After three scripted deploys (the seed then leaves the keep-set), remove the
   old `agreementmitra-*:latest` images by hand.

To abandon the script: revert its commit, delete `deploy/.env` and
`/var/lib/agreementmitra`, `git checkout main && git pull`, and use the manual
procedure.

### Build gates are NOT run by the image builds

The backend image runs `bootJar`, not `check`; the web image runs `build:only`,
not `build`. Both skip `securityScan` / `security:scan`, which shell out to
`osv-scanner` -- absent from a clean build image, and the gate is deliberately
fail-closed. **A green deploy is not a passed security scan.** Running the gates
remains a local and (eventually) CI responsibility; see `CLAUDE.md` and TD-1 in
`docs/TECH_DEBT.md`.

---

## 5. Cloudflare

Only once the zone shows **Active**.

### 5.1 DNS

**DNS** -> **Records**. Both records **Proxied (orange cloud)** -- this is not
optional, it is the entire security model:

| Type | Name | Content | Proxy |
|---|---|---|---|
| A | `@` | `217.217.250.135` | Proxied |
| A | `www` | `217.217.250.135` | Proxied |

Do not add any unproxied record pointing at this IP, ever.

If you have already applied the Zoho mail records from
`DOMAIN-AND-EMAIL-SETUP.md`, leave them alone: MX records cannot be proxied and
do not conflict with these.

### 5.2 Origin certificate

**SSL/TLS** -> **Origin Server** -> **Create Certificate**. Accept the defaults
(RSA, 15 years) with hostnames `agreementmitra.com` and `*.agreementmitra.com`.
Cloudflare shows the certificate and key **once**.

On the server, replace the placeholder:

```sh
cd /opt/agreementmitra/deploy
nano certs/origin.pem     # paste the certificate
nano certs/origin.key     # paste the private key
chmod 600 certs/origin.key
docker compose -f docker-compose.prod.yml restart caddy
```

### 5.3 Set Full (strict)

**SSL/TLS** -> **Overview** -> **Full (strict)**.

This is the setting that decides whether the padlock means anything:

- `Flexible` sends Cloudflare-to-origin traffic in **plaintext** while showing
  users a padlock. Never use it.
- `Full` encrypts but does not validate the origin certificate -- open to an
  active attacker between Cloudflare and Contabo.
- `Full (strict)` encrypts **and** validates. Required.

Also enable **Always Use HTTPS** and **Automatic HTTPS Rewrites**.

### 5.4 Cloudflare Access

**Zero Trust** -> **Access** -> **Applications** -> **Add an application** ->
**Self-hosted**.

Application domain: `agreementmitra.com` (and add `www.agreementmitra.com`).

Policy: **Allow**, with an `Emails` rule listing the addresses that may reach the
site. Google or one-time-PIN are both fine as identity providers.

**Not enforced on the apex today** (verified 2026-10-06: `https://agreementmitra.com/`
answers `200` unauthenticated). `deploy/smoke-prod.sh` expects that and fails, naming
Access, if a request is redirected to `*.cloudflareaccess.com`.

### 5.5 Webhook bypass -- do not skip this

The eSign webhook is a machine-to-machine POST from the vendor. It cannot
complete an Access login, and Cloudflare's bot protections may challenge it.

Add a **second Access policy** on the same application, ordered **above** the
allow-list policy:

- Action: **Bypass**
- Rule: **Everyone**
- Path: `/api/webhooks/esign`

Then **Security** -> **WAF** -> **Custom rules**: add a **Skip** rule for
`http.request.uri.path eq "/api/webhooks/esign"`, skipping Bot Fight Mode and
managed rules.

The endpoint is safe to expose: `WebhookController` verifies an HMAC before
acting, and `SigningController` re-reads authoritative status from the provider
rather than trusting payload contents.

**Why this matters more than it looks:** if webhooks are silently blocked, the
scheduled reconciliation job will quietly complete the signings anyway, several
minutes late. The system appears to work while the primary path is entirely
broken, which is considerably harder to notice than an outright failure.

### 5.6 Abuse controls at the edge -- a manual gate

The application enforces its own limits in every environment, but the edge half
is configuration, not code: nothing in the build proves it is on. **Until
`prod-readiness-preflight` gives these a production-gate row, they are a manual
gate -- check each one on every deploy that touches Cloudflare, `deploy/Caddyfile`
or `deploy/docker-compose.prod.yml`.**

**What makes any of this real:** the origin accepts only Cloudflare's ranges
(the `DOCKER-USER` chain, fetched live and failing closed -- section 2), so a
request cannot skip the edge. That allowlist admits **any** Cloudflare tenant,
whose traffic our zone's rules do not see; the source key stays honest because
Cloudflare always sets `CF-Connecting-IP`, and the application layer stands on
its own. **Authenticated Origin Pulls** (Cloudflare presents a client
certificate Caddy verifies) would close that gap and is not done yet.

1. **Bot Fight Mode** -- **Security** -> **Bots** -> on. The webhook Skip rule in
   5.5 must cover **both** `/api/webhooks/esign` and `/api/webhooks/razorpay`, or
   a vendor callback can be challenged.
   *Verify:* the toggle shows On; a signed test webhook from the vendor dashboard
   still answers `202`.
2. **Rate-limiting rule** -- **Security** -> **WAF** -> **Rate limiting rules**:
   match `http.request.method eq "POST" and starts_with(http.request.uri.path,
   "/api/") and not starts_with(http.request.uri.path, "/api/webhooks/")`, keyed
   on IP, block. **Check the current free-plan rule quota and the allowed
   period/threshold in the dashboard before choosing values** -- it changes. Set
   the threshold well above the application's own per-source limits so the edge
   only catches floods.
   *Verify:* the rule is listed as Deployed with the webhook exclusion visible in
   its expression.
3. **Pseudo IPv4 stays Off** -- **Network** -> **Pseudo IPv4**: `Off` (or `Add
   header`), **never `Overwrite headers`**, or IPv6 clients arrive as synthetic
   IPv4 addresses and the application's `/64` aggregation is silently defeated.
   *Verify:* the setting reads Off.
4. **Caddy forwarded-header overwrite and strip** -- `deploy/Caddyfile`'s `/api/*`
   proxy sets `X-Forwarded-For` to the address Caddy recovered from
   `CF-Connecting-IP` (overwrite, not append), pins `X-Forwarded-Proto https`, and
   strips `Forwarded`, `X-Forwarded-Host`, `X-Forwarded-Prefix` and
   `X-Forwarded-Port`.
   *Verify:* the outside-in check below.
5. **Caddy request-body ceilings** -- 11 MiB on `/api/agreements/*/draft` and
   `/api/staff/estamp`, 1 MiB on every other `/api/*` path; the two matchers must
   not overlap or uploads are capped at 1 MiB.
   *Verify:* `curl -s -o /dev/null -w '%{http_code}' -X POST
   https://agreementmitra.com/api/agreements -H 'Content-Type: application/json'
   --data-binary @<(head -c 1200000 /dev/zero | tr '\0' x)` answers `413`.
6. **Caddy's static address and `internal-proxies` deploy together.**
   `deploy/docker-compose.prod.yml` pins Caddy to `10.203.17.10` on the declared
   subnet `10.203.17.0/24`; the application trusts `X-Forwarded-For` only from
   that address (`server.tomcat.remoteip.internal-proxies`, overridable with
   `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`). Change one, change the other in the
   same deploy. **Never deploy the application half without the Caddy half:** the
   application would then trust a header the client can still seed, turning a
   shared-bucket bug into a bypass. Adding the subnet to an existing stack
   recreates the network, which needs `docker compose -f docker-compose.prod.yml
   down` then `up -d` (a plain `up` refuses to change an existing network's IPAM).
   If `10.203.17.0/24` collides with a network already on the box, pick another
   and update both halves.
   *Verify:* `docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'
   agreementmitra-caddy-1` prints `10.203.17.10`.

**Outside-in checks** -- run `deploy/smoke-prod.sh` from your workstation after
every deploy. It checks the apex (200) and `www` (301), the 413 body ceiling, the
`__Host-` / `Secure` / `SameSite=Lax` CSRF cookie, `cf-cache-status: DYNAMIC` on
the API, that both webhook paths reach the application (401) rather than a
challenge, and that no response carries `cf-mitigated`.

**That a forged header does not become the source** (once per deploy of items 4
or 6) is its opt-in last check:

```sh
deploy/smoke-prod.sh --forged-header
```

It **locks your /24 out of default-class routes for about 5 minutes** (anyone
sharing it too, carrier-NAT neighbours included) and asks before it starts
(`--yes` skips the question). It sends 150 requests in parallel with
`X-Forwarded-For` and `Forwarded` forged to `198.51.100.23`, then reads the
lockout events since the burst over `ssh ${SMOKE_SSH:-agreementmitra-vps}`. It
passes only when the lockout names **your** /24; one naming `198.51.100.0/24`
fails (the application trusted a client header), and none at all is inconclusive.

The event carries `route=default`, the redacted `source=` prefix and a count --
never a URI or an agreement id.

---

## 6. Verify end to end

From your workstation:

```sh
dig +short agreementmitra.com          # Cloudflare IPs, NOT 217.217.250.135
curl -sI https://agreementmitra.com/   # 200 (Access is not enforced on the apex today, see 5.4)
curl -sI https://www.agreementmitra.com/   # 301 to the apex
```

Confirm the origin is not reachable directly:

```sh
curl -sk --max-time 10 https://217.217.250.135/ -H 'Host: agreementmitra.com'
# expected: connection timeout (ufw drops non-Cloudflare sources)
```

Confirm real client IPs are recovered rather than Cloudflare's:

```sh
docker compose -f docker-compose.prod.yml logs caddy | tail -20
```

Then, in a browser, complete the Access login and walk `/` and `/start`.

---

## 7. Admin access

SSH is unaffected by the Cloudflare proxy -- port 22 is not proxied, and the
server keeps its real IP. Connect to the **IP**, never to a hostname:

```
Host agreementmitra-vps
  HostName 217.217.250.135
  User root
  IdentityFile ~/.ssh/id_ed25519
```

Postgres and the MinIO console are not published -- compose maps no port to the
host, so `ssh -L <port>:localhost:<port>` reaches nothing. Postgres needs no
tunnel at all:

```sh
# on the server, from deploy/:
docker compose -f docker-compose.prod.yml exec postgres psql -U agreementmitra
```

The MinIO console needs a browser, so tunnel to the **container's** address on the
compose network. That address is dynamic (from `10.203.17.128/25`) and can change
when the container is recreated, so look it up each time:

```sh
ssh agreementmitra-vps \
  'docker inspect -f "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}" agreementmitra-minio-1'
ssh -N -L 9001:<that-ip>:9001 agreementmitra-vps   # console at http://localhost:9001
```

Log in with `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` from `deploy/env/minio.env`.
Treat the console as **read-only**: the database records a key for every stored
PDF, and an object deleted or renamed by hand leaves an agreement pointing at
nothing. A desktop Postgres client can use the same tunnel shape against the
`postgres` container's address on port 5432.

### Hardening follow-up: Cloudflare Tunnel

Running `cloudflared` on the box makes an **outbound** connection to Cloudflare
and lets you SSH through Cloudflare Access, authenticated by identity rather than
a key. Port 22 can then be closed entirely, leaving no inbound surface. Recovery
if the tunnel breaks is Contabo's VNC console. Worth doing before real PII.

---

## 8. Backups

Backups must be **outbound pushes**, not inbound pulls: no open ports, and it
composes with closing SSH entirely later.

What needs backing up:

- **Postgres** -- state and the audit trail. `pg_dump` nightly.
- **MinIO** -- the PDF blobs. Signed artifacts are legal documents; losing them
  is not recoverable by regenerating.
- **`deploy/env/*.env` and `deploy/certs/`** -- store in a password manager, not
  in the same bucket as the data.

Cloudflare R2 is the natural target (no egress fees, same account you already
have); Backblaze B2 is equivalent. Restore drills matter more than backup jobs --
an untested backup is a hypothesis.

**Object storage must be unversioned, with no object lock.** A customer can delete an unpaid
draft, and the app then removes `drafts/{id}.pdf`; on a versioned bucket that only adds a
delete marker and the PDF stays. Nothing in this repo enables versioning — keep it that way.
The backup target must likewise be unversioned or expire old versions. A deleted draft does
survive in backups until they rotate out, which is why the product says a deleted draft is
removed *from the service*, not that it is gone permanently.

**Not yet implemented.** See the gaps below.

**Pre-deploy dumps are not backups.** Every `deploy/deploy.sh` run writes
`/root/backups/pre-deploy-<UTC>-<tag>.sql.gz` (`0600`, directory `0700`) and keeps
the ten newest, deleting any older than 30 days unless a rollback target still
needs it. They are **rollback points**: unencrypted, on the same disk as the
database, without object storage, and the 30-day cap is applied only when a
deploy runs. Other files in `/root/backups` are never touched by rotation.

---

## 9. Known gaps

Carried deliberately, in rough priority order:

1. **No ownership authorization on the capability routes.** `POST
   /api/agreements/*/draft` and the other routes that act on an existing
   agreement are rate limited and logged (`anonymous-surface-abuse-controls`),
   but holding the id is still the only authorization -- by design for the
   no-login product (`claim-bound-to-initiator` in the register is the nearest
   open item).
   `POST /api/signing/*/request` is now STAFF-only. The edge half of the abuse
   controls is a manual gate (section 5.6).
2. **SSH password authentication is enabled** (section 2). Deferred by decision
   on 2026-07-29 to keep the box reachable during build-out. Port 22 on a public
   VPS is brute-forced continuously; fail2ban is the only thing standing in.
   Run `./provision.sh ssh-keyonly` before real user data.
3. **No backups yet** (section 8).
4. **The template catalog is seeded by a dev-only seeder** under the `sandbox`
   profile (section 3). There is no production seeding path. Fine while the repo
   is sandbox-and-dummy-data-only; needs a deliberate mechanism before real
   users.
5. **No CI, so the security gates do not run on deploy** (section 4). CR-7 /
   TD-1.
6. **The backend authenticates to MinIO as root.** A bucket-scoped service
   account is the correct end state.
7. **Object storage is not hardened for production** -- no SSE, no
   block-public-access. Listed as a queued non-goal in `docs/ROADMAP.md`.
8. **Single box, no redundancy.** Postgres, object storage and the app share one
   VPS and one disk. Acceptable pre-revenue; a restore path (item 3) matters far
   more than replication at this stage.
9. **No monitoring or alerting.** Container health checks restart failures, but
   nothing tells you when that happens.
