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
  CORS and the opaque session cookie works unmodified. **The Cloudflare Pages
  plan in that document is superseded.**
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
  renders exhausting CPU. A Cloudflare rate-limiting rule on `POST /api/*`
  (excluding the webhook path) bounds all of them without blocking legitimate
  anonymous use.
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

`provision.sh` is idempotent and splits into `harden`, `docker` and `firewall`
subcommands if you want to run them separately. It:

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

| File | Consumed by |
|---|---|
| `deploy/env/postgres.env` | `postgres` |
| `deploy/env/minio.env` | `minio` |
| `deploy/env/backend.env` | `backend` |

Generate the random values on the server (never on a workstation, never in a
chat window, never committed):

```sh
openssl rand -base64 32 | tr -d '/+=' | cut -c1-32
```

### Variables

`postgres.env` -- names fixed by the `postgres:17` image:

| Variable | Value |
|---|---|
| `POSTGRES_DB` | `agreementmitra` |
| `POSTGRES_USER` | `agreementmitra` |
| `POSTGRES_PASSWORD` | generated |

`minio.env` -- names fixed by the MinIO image:

| Variable | Value |
|---|---|
| `MINIO_ROOT_USER` | generated |
| `MINIO_ROOT_PASSWORD` | generated |

`backend.env`:

| Variable | Value | Notes |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://postgres:5432/agreementmitra` | compose network hostname |
| `DB_USER` | `agreementmitra` | must match `postgres.env` |
| `DB_PASSWORD` | generated | must match `postgres.env` |
| `S3_ENDPOINT` | `http://minio:9000` | |
| `S3_BUCKET` | `agreements` | created by the app on first use |
| `S3_ACCESS_KEY` | generated | must match `MINIO_ROOT_USER` |
| `S3_SECRET_KEY` | generated | must match `MINIO_ROOT_PASSWORD` |
| `GOTENBERG_URL` | `http://gotenberg:3000` | |
| `AUTH_HASH_PEPPER` | generated | **must** override the `dev-only-...-change-me` default in `application.yml` |
| `GOOGLE_OAUTH_CLIENT_ID` | blank or real | blank disables login; see below |
| `GOOGLE_OAUTH_SECRET` | blank or real | blank disables login |
| `GOOGLE_OAUTH_REDIRECT_URI` | `https://agreementmitra.com/api/auth/google/callback` | register verbatim in Google Cloud |
| `GOOGLE_OAUTH_SPA_CALLBACK_URI` | `https://agreementmitra.com/auth/callback` | |
| `LEEGALITY_BASE_URL` | blank | no sandbox account yet |
| `LEEGALITY_PROFILE_ID` | blank | |
| `LEEGALITY_AUTH_TOKEN` | blank | |
| `LEEGALITY_WEBHOOK_SECRET` | blank | |
| `DOCUMENT_FOOTER_PLATFORM_URL` | `agreementmitra.com` | non-secret |
| `LOGGING_LEVEL_IN_AGREEMENTMITRA` | `INFO` | overrides the `DEBUG` dev default |
| `SPRING_PROFILES_ACTIVE` | `sandbox` | **required** -- see below |

Lock the files down: `chmod 600 deploy/env/*.env`.

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

Both vendor integrations tolerate absent credentials at startup, which is what
makes a production bring-up possible before any vendor account exists:

- **Google login:** `OauthConfig` logs a one-line WARN and disables the
  handshake. The app boots and everything else works. (The comment in
  `application.yml` claiming it "fails fast" is stale -- the code does not.)
- **Leegality:** the adapter reads its config only at request time, so startup is
  unaffected. Signing requests will fail until a developer sandbox account
  exists. Repo policy is sandbox and dummy data only.

`AUTH_HASH_PEPPER` is different: it has a working dev default, so a missing value
fails silently into a known-weak pepper. It must be set.

---

## 4. Bring the stack up

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

---

## 6. Verify end to end

From your workstation:

```sh
dig +short agreementmitra.com          # Cloudflare IPs, NOT 217.217.250.135
curl -sI https://agreementmitra.com/   # Access redirect or 200 once authenticated
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

Postgres and the MinIO console are not published. Reach them by tunnel:

```sh
ssh -L 5432:localhost:5432 agreementmitra-vps   # then connect a client to localhost:5432
ssh -L 9001:localhost:9001 agreementmitra-vps   # MinIO console at http://localhost:9001
```

Those tunnels need the ports reachable on the server's loopback. Since compose
publishes nothing, forward into the container instead:

```sh
ssh -L 5432:localhost:15432 agreementmitra-vps
# on the server:
docker compose -f docker-compose.prod.yml exec postgres psql -U agreementmitra
```

In practice `docker compose exec` is the simpler admin path and avoids opening
anything at all.

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

**Not yet implemented.** See the gaps below.

---

## 9. Known gaps

Carried deliberately, in rough priority order:

1. **`signing-auth` has not landed.** `POST /api/signing/*/request` and
   `POST /api/agreements/*/draft` carry no ownership authorization, rate
   limiting, or redacted security-event logging in the application. Anonymous
   *creation* is intended; what is missing is the abuse bounding and the
   ownership checks on routes that act on an existing agreement. A Cloudflare
   rate-limiting rule stands in for the first; the second still needs the CR.
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
