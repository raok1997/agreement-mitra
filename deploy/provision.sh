#!/usr/bin/env bash
#
# One-time provisioning for the AgreementMitra production VPS (Contabo, Ubuntu).
# Idempotent: safe to re-run. Run as root, from the repo checkout on the server.
#
#   ./provision.sh harden      OS updates, swap, fail2ban, auto-updates
#   ./provision.sh docker      Docker CE + compose plugin, deploy user
#   ./provision.sh firewall    ufw: deny inbound except SSH and Cloudflare-only 80/443
#   ./provision.sh secrets     generate deploy/env/*.env (idempotent, never rotates), then ask
#                              for every key that breaks its template tag (the one interactive
#                              env step; `--templates <dir>` checks against another commit's
#                              four *.env.example, as the deploy's refusal prints)
#   ./provision.sh ssh-keyonly DEFERRED: disable SSH password auth (see below)
#   ./provision.sh all         harden + docker + firewall (NOT ssh-keyonly)
#
# DEFERRED BY DECISION (2026-07-29): `ssh-keyonly` is deliberately NOT part of
# `all`. Password authentication stays enabled during build-out so the box is
# reachable by password from anywhere, not only from a machine holding the key.
# This is a real exposure -- port 22 on a public VPS is brute-forced continuously
# -- and fail2ban is the only thing standing in for it. Run `ssh-keyonly` before
# this box handles real user data.
#
# The firewall step deliberately admits 80/443 ONLY from Cloudflare's published
# ranges, which it fetches live rather than hardcoding. That is what makes the
# origin unreachable except through the Cloudflare edge -- the whole point of
# never publishing 217.217.250.135 in DNS.

set -euo pipefail

readonly DEPLOY_USER="deploy"
readonly SWAP_SIZE="4G"
readonly CF_V4_URL="https://www.cloudflare.com/ips-v4"
readonly CF_V6_URL="https://www.cloudflare.com/ips-v6"

log()  { printf '\n[provision] %s\n' "$*"; }
warn() { printf '\n[provision] WARNING: %s\n' "$*" >&2; }
die()  { printf '\n[provision] ERROR: %s\n' "$*" >&2; exit 1; }

require_root() {
  [ "$(id -u)" -eq 0 ] || die "must run as root"
}

# ---------------------------------------------------------------------------
# harden
# ---------------------------------------------------------------------------

harden() {
  log "Updating packages"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y
  apt-get upgrade -y

  log "Installing base packages"
  apt-get install -y --no-install-recommends \
    ca-certificates curl gnupg openssl git ufw fail2ban unattended-upgrades \
    apt-listchanges

  setup_swap

  # NOTE: harden_ssh is deliberately NOT called here. See the header comment --
  # key-only SSH is deferred and must be invoked explicitly via `ssh-keyonly`.

  log "Enabling unattended security upgrades"
  # Security updates only, applied automatically. This box runs unattended for
  # long stretches; an unpatched OpenSSH is a worse risk than an unexpected
  # package bump.
  cat >/etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF
  systemctl enable --now unattended-upgrades

  log "Configuring fail2ban for sshd"
  cat >/etc/fail2ban/jail.d/sshd.local <<'EOF'
[sshd]
enabled = true
mode = aggressive
maxretry = 5
findtime = 10m
bantime = 1h
EOF
  systemctl enable --now fail2ban
  systemctl restart fail2ban

  log "harden: done"
}

setup_swap() {
  if swapon --show | grep -q '/swapfile'; then
    log "Swap already configured, skipping"
    return
  fi
  log "Creating ${SWAP_SIZE} swapfile"
  # 12 GB of RAM is comfortable for this stack, but Chromium spikes hard during
  # concurrent renders. Swap is insurance against the OOM killer taking out
  # Postgres, which is the one container whose death actually costs data.
  fallocate -l "$SWAP_SIZE" /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >>/etc/fstab

  # Prefer reclaiming page cache over swapping out live JVM/Postgres pages.
  sysctl -w vm.swappiness=10
  grep -q '^vm.swappiness' /etc/sysctl.conf || echo 'vm.swappiness=10' >>/etc/sysctl.conf
}

harden_ssh() {
  # LOCKOUT GUARD: never disable password authentication unless a key is already
  # installed and usable. Contabo's VNC console is the recovery path if this goes
  # wrong, but it is much better not to need it.
  if [ ! -s /root/.ssh/authorized_keys ]; then
    warn "/root/.ssh/authorized_keys is empty or missing."
    warn "Skipping SSH hardening -- install your public key first, then re-run."
    return
  fi

  log "Hardening sshd (key-only auth, no root password login)"
  cat >/etc/ssh/sshd_config.d/99-agreementmitra.conf <<'EOF'
# Key-only authentication. Password auth is the single most-attacked surface on
# a public VPS, and fail2ban only slows it down rather than closing it.
PasswordAuthentication no
PermitEmptyPasswords no
KbdInteractiveAuthentication no
ChallengeResponseAuthentication no

# Root may log in by key only (this is how deploys and admin currently work).
PermitRootLogin prohibit-password

X11Forwarding no
AllowAgentForwarding no
MaxAuthTries 3
LoginGraceTime 30
EOF

  sshd -t || die "sshd config invalid; refusing to restart (existing session is safe)"
  systemctl reload ssh 2>/dev/null || systemctl reload sshd
}

# ---------------------------------------------------------------------------
# docker
# ---------------------------------------------------------------------------

docker_install() {
  if command -v docker >/dev/null 2>&1; then
    log "Docker already installed: $(docker --version)"
  else
    log "Installing Docker CE from the official repository"
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
      -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc

    local arch codename
    arch="$(dpkg --print-architecture)"
    codename="$(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")"
    echo "deb [arch=${arch} signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${codename} stable" \
      >/etc/apt/sources.list.d/docker.list

    apt-get update -y
    apt-get install -y \
      docker-ce docker-ce-cli containerd.io \
      docker-buildx-plugin docker-compose-plugin
  fi

  systemctl enable --now docker

  if ! id -u "$DEPLOY_USER" >/dev/null 2>&1; then
    log "Creating ${DEPLOY_USER} user"
    useradd --create-home --shell /bin/bash "$DEPLOY_USER"
  fi
  usermod -aG docker "$DEPLOY_USER"

  # Give the deploy user the same key root uses, so admin does not require root.
  if [ -s /root/.ssh/authorized_keys ]; then
    install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/${DEPLOY_USER}/.ssh"
    install -m 600 -o "$DEPLOY_USER" -g "$DEPLOY_USER" \
      /root/.ssh/authorized_keys "/home/${DEPLOY_USER}/.ssh/authorized_keys"
  fi

  log "docker: done"
}

# ---------------------------------------------------------------------------
# firewall
# ---------------------------------------------------------------------------

firewall() {
  log "Configuring ufw"

  # Fetch Cloudflare's ranges live. Hardcoding them means a silent drift into
  # either blocking legitimate edge traffic or trusting stale space, so fail
  # closed if the fetch does not work rather than guessing.
  local v4 v6
  v4="$(curl -fsS --max-time 20 "$CF_V4_URL")" \
    || die "could not fetch ${CF_V4_URL} -- refusing to open 80/443 to the world"
  v6="$(curl -fsS --max-time 20 "$CF_V6_URL")" \
    || die "could not fetch ${CF_V6_URL} -- refusing to open 80/443 to the world"

  [ -n "$v4" ] || die "Cloudflare IPv4 list came back empty"

  ufw --force reset
  ufw default deny incoming
  ufw default allow outgoing

  # SSH stays open to the internet, protected by fail2ban (and by key-only auth
  # once `ssh-keyonly` is run). Tighten to a static source address if you have
  # one, or move to a Cloudflare Tunnel and close this entirely.
  ufw allow OpenSSH

  ufw --force enable
  ufw status verbose

  # Cache the ranges so the boot-time unit does not depend on network reachability
  # during early startup. Fail closed if the cache is missing.
  mkdir -p /etc/agreementmitra
  printf '%s\n' "$v4" >/etc/agreementmitra/cloudflare-ips-v4
  printf '%s\n' "$v6" >/etc/agreementmitra/cloudflare-ips-v6

  install_docker_port_filter

  log "firewall: done"
}

# ---------------------------------------------------------------------------
# Docker published-port filtering
#
# CRITICAL: ufw does NOT protect Docker-published ports. Docker DNATs inbound
# traffic in nat/PREROUTING and filters it in the FORWARD path, so it never
# traverses ufw's INPUT chain -- ufw rules for 80/443 are inert while Caddy
# publishes them. DOCKER-USER is the documented hook Docker guarantees it will
# not clobber, and it is where host policy for container traffic belongs.
#
# Docker rebuilds its chains whenever the daemon restarts, so these rules are
# (re)applied by a systemd unit ordered after docker.service rather than being
# set once.
#
# NOTE: --dport here is the CONTAINER port, because DNAT has already rewritten
# the destination by the time FORWARD/DOCKER-USER runs. This stack maps 80->80
# and 443->443, so the numbers coincide; they would not if the mapping differed.
# ---------------------------------------------------------------------------

install_docker_port_filter() {
  log "Installing Cloudflare-only filter for Docker-published 80/443"

  cat >/usr/local/sbin/am-docker-firewall.sh <<'SCRIPT'
#!/usr/bin/env bash
# Restrict Docker-published 80/443 to Cloudflare. Fails closed: if the cached
# range lists are missing, everything is dropped rather than left open.
#
# SCOPED TO INBOUND, and that scoping is load-bearing. DOCKER-USER sits on the
# FORWARD path, so it sees BOTH directions: traffic the internet sends to a
# published port, and traffic a container sends out to the world. Unscoped, the
# final DROP also kills container EGRESS to any host on 80/443 -- the backend's
# calls to the eSign and payment vendors, the OAuth token exchange, and any
# image build that downloads a dependency. Only ESTABLISHED replies survive,
# which makes it look like a name-resolution or vendor outage rather than a
# local firewall rule.
#
# Inbound arrives on the default-route interface; container egress arrives on a
# docker bridge (docker0 / br-*). Matching "-i $WAN" therefore leaves egress
# alone while keeping the inbound policy byte-for-byte identical.
set -euo pipefail

V4_FILE=/etc/agreementmitra/cloudflare-ips-v4
V6_FILE=/etc/agreementmitra/cloudflare-ips-v6
PORTS=80,443

# The public interface. If it cannot be determined, fall back to unscoped rules:
# that breaks container egress, but it keeps the inbound restriction intact,
# which is the fail-closed direction to err in.
WAN="$(ip route show default 2>/dev/null | awk '{print $5; exit}' || true)"
if [ -n "$WAN" ]; then
  IN=(-i "$WAN")
else
  IN=()
fi

apply() {
  local ipt="$1" file="$2" cidr
  "$ipt" -F DOCKER-USER 2>/dev/null || return 0

  # Return traffic for connections the host/containers initiated.
  "$ipt" -A DOCKER-USER "${IN[@]}" -p tcp -m multiport --dports "$PORTS" \
         -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN

  if [ -s "$file" ]; then
    while read -r cidr; do
      [ -n "$cidr" ] || continue
      "$ipt" -A DOCKER-USER "${IN[@]}" -p tcp -m multiport --dports "$PORTS" -s "$cidr" -j RETURN
    done <"$file"
  fi

  # Everything else reaching a published 80/443 from outside is dropped.
  "$ipt" -A DOCKER-USER "${IN[@]}" -p tcp -m multiport --dports "$PORTS" -j DROP
  "$ipt" -A DOCKER-USER -j RETURN
}

apply iptables  "$V4_FILE"
apply ip6tables "$V6_FILE"
SCRIPT
  chmod 0755 /usr/local/sbin/am-docker-firewall.sh

  cat >/etc/systemd/system/am-docker-firewall.service <<'UNIT'
[Unit]
Description=Restrict Docker-published 80/443 to Cloudflare ranges
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/local/sbin/am-docker-firewall.sh

[Install]
WantedBy=multi-user.target
UNIT

  systemctl daemon-reload
  systemctl enable am-docker-firewall.service
  # restart, NOT "enable --now": the unit is Type=oneshot with RemainAfterExit=yes,
  # so it stays "active" forever after its first run and a "start" is a silent
  # no-op. Re-provisioning would then rewrite the script above and never execute
  # it, while still reporting success -- the stale rules stay loaded in the kernel
  # and only a reboot would pick up the change.
  systemctl restart am-docker-firewall.service
  # -v so the interface columns are visible: the inbound scoping is invisible
  # without them, which makes a failed apply look identical to a successful one.
  iptables -L DOCKER-USER -n -v --line-numbers | head -20
}

# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# secrets
#
# Generates deploy/env/{postgres,minio,backend,web-build}.env on the SERVER. Secrets are
# created here and never travel from a workstation -- see docs/DEPLOYMENT.md
# section 3.
#
# IDEMPOTENT AND NON-DESTRUCTIVE BY DESIGN. An existing value is never
# overwritten; only blank GENERATED keys are filled. This matters more than it
# looks:
#
#   - `postgres:17` honours POSTGRES_PASSWORD only at first initdb. Rotating it
#     in the env file after the volume exists does NOT change the database
#     password -- it just makes the app's DB_PASSWORD wrong, and the backend
#     fails authentication against a database you can no longer reach with the
#     credentials on disk. Rotation needs an `ALTER USER ... PASSWORD`.
#   - The two peppers key data AT REST. Rotating AUTH_HASH_PEPPER invalidates
#     every live session; rotating ESIGN_WEBHOOK_KEY_PEPPER makes the stored
#     per-transaction webhook keys undecryptable, so in-flight signings can no
#     longer accept their callbacks.
#
# So re-running this is always safe, and it will never silently rotate anything.
# ---------------------------------------------------------------------------

readonly ENV_DIR="env"
# The shared env-key parser and the env contract (deploy/lib/checks.sh), the same code the deploy's
# preflight runs, so the fill step and the deploy can never disagree about a key.
# shellcheck source=lib/checks.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/checks.sh"

gen_secret() {
  # 32 chars, alphanumeric only. Punctuation is deliberately stripped: these
  # land in a compose env_file (parsed literally to end of line) and in a JDBC
  # URL password, where `#`, `$` and `:` have each caused a bad day.
  openssl rand -base64 48 | tr -d '/+=' | cut -c1-32
}

# set_if_blank <file> <key> <value>
# Fills KEY= only when it is absent, blank or a placeholder -- the env contract's own rule
# (env_blank / env_placeholder), so a template copy's __GENERATED_ON_SERVER__ gets generated.
# Leaves a real value be. Writes through env_set: atomic, 0600, never a sed expression.
set_if_blank() {
  local file="$1" key="$2" value="$3" current
  if current="$(env_value "$file" "$key")" && ! env_blank "$current" && ! env_placeholder "$current"; then
    return 1
  fi
  # env_value strips quotes, so a reused value is re-quoted for compose before it is written.
  value="$(env_quote "$value")" || die "$key holds a single quote; set it by hand"
  ENV_SET_VALUE="$value" env_set "$file" "$key"
}

# merge_template_defaults <template> <target>
#
# Adds any assignment present in the template but ABSENT from the target,
# carrying the template's value across. Existing values are never touched.
#
# This is what makes `secrets` an upgrade path and not just a first-run tool. A
# backend.env written against an older runbook is missing whole features' worth
# of fixed values -- PUBLIC_BASE_URL, PAYMENT_MODE, MAIL_*, ZOOP_*,
# RULES_STAMP_DUTY_ALLOW_UNREVIEWED -- and every one of them fails SILENTLY on
# the application default rather than at boot. Generating the secrets while
# leaving those absent would report success and fix nothing.
merge_template_defaults() {
  local template="$1" target="$2" lines
  [ -f "$template" ] || return 0
  lines="$(env_merge_lines "$template" "$target")"

  if [ -z "$lines" ]; then
    log "$(basename "$target") already carries every variable in the template"
    return 0
  fi

  printf '\n# --- Added by `provision.sh secrets` from %s ---\n%s\n' \
    "$(basename "$template")" "$lines" >>"$target"
  log "Added $(printf '%s\n' "$lines" | wc -l | tr -d ' ') missing variable(s) to $(basename "$target") from the template:"
  printf '%s\n' "$lines" | sed 's/=.*//; s/^/  /'
}

secrets() {
  local tdir="$ENV_DIR" name
  while [ $# -gt 0 ]; do
    case "$1" in
      --templates)
        [ $# -ge 2 ] || die "--templates needs a directory"
        tdir="$2"
        shift 2
        ;;
      *) die "secrets: unknown argument (usage: secrets [--templates <dir of the four *.env.example>])" ;;
    esac
  done

  [ -d "$ENV_DIR" ] || die "run this from the deploy/ directory (no ./${ENV_DIR})"
  for name in backend postgres minio web-build; do
    [ -f "$tdir/$name.env.example" ] || die "template $tdir/$name.env.example not found"
  done
  local backend_template="$tdir/backend.env.example"
  command -v openssl >/dev/null 2>&1 || die "openssl not found -- run './provision.sh harden' first"

  log "Generating service env files in ${ENV_DIR}/"

  local pg_password minio_user minio_password
  local filled=0 skipped=0 key template failed=0

  # --- postgres.env -------------------------------------------------------
  touch "${ENV_DIR}/postgres.env"
  set_if_blank "${ENV_DIR}/postgres.env" POSTGRES_DB   agreementmitra >/dev/null || true
  set_if_blank "${ENV_DIR}/postgres.env" POSTGRES_USER agreementmitra >/dev/null || true
  pg_password="$(gen_secret)"
  if set_if_blank "${ENV_DIR}/postgres.env" POSTGRES_PASSWORD "$pg_password"; then
    filled=$((filled + 1))
  else
    skipped=$((skipped + 1))
    pg_password="$(env_value "${ENV_DIR}/postgres.env" POSTGRES_PASSWORD)"
    log "POSTGRES_PASSWORD already set -- reusing it for backend.env"
  fi

  # --- minio.env ----------------------------------------------------------
  touch "${ENV_DIR}/minio.env"
  minio_user="$(gen_secret)"
  if set_if_blank "${ENV_DIR}/minio.env" MINIO_ROOT_USER "$minio_user"; then
    filled=$((filled + 1))
  else
    skipped=$((skipped + 1))
    minio_user="$(env_value "${ENV_DIR}/minio.env" MINIO_ROOT_USER)"
  fi
  minio_password="$(gen_secret)"
  if set_if_blank "${ENV_DIR}/minio.env" MINIO_ROOT_PASSWORD "$minio_password"; then
    filled=$((filled + 1))
  else
    skipped=$((skipped + 1))
    minio_password="$(env_value "${ENV_DIR}/minio.env" MINIO_ROOT_PASSWORD)"
  fi

  # --- backend.env --------------------------------------------------------
  # Seeded from the tracked template so every fixed value and every explanatory
  # comment comes across, and the vendor keys are left blank to be asked for below.
  if [ ! -f "${ENV_DIR}/backend.env" ]; then
    (umask 077 && cp "$backend_template" "${ENV_DIR}/backend.env")
    log "Created ${ENV_DIR}/backend.env from the template"
  fi

  merge_template_defaults "$backend_template" "${ENV_DIR}/backend.env"

  # Credentials that must MATCH the two files above.
  set_if_blank "${ENV_DIR}/backend.env" DB_PASSWORD   "$pg_password"     >/dev/null || true
  set_if_blank "${ENV_DIR}/backend.env" S3_ACCESS_KEY "$minio_user"      >/dev/null || true
  set_if_blank "${ENV_DIR}/backend.env" S3_SECRET_KEY "$minio_password"  >/dev/null || true
  pg_password="" minio_user="" minio_password=""

  # Peppers: application-side only, nothing else needs to agree with them.
  for key in AUTH_HASH_PEPPER ESIGN_WEBHOOK_KEY_PEPPER; do
    if set_if_blank "${ENV_DIR}/backend.env" "$key" "$(gen_secret)"; then
      filled=$((filled + 1))
    else
      skipped=$((skipped + 1))
    fi
  done

  # --- web-build.env -----------------------------------------------------
  # Public operator identifiers baked into the caddy image (not secrets). Empty
  # is valid: the site then says "being issued". See web-build.env.example.
  touch "${ENV_DIR}/web-build.env"

  # The other three templates' keys too, so no file is short a key the deploy checks for.
  for name in postgres minio web-build; do
    merge_template_defaults "$tdir/${name}.env.example" "${ENV_DIR}/${name}.env"
  done

  chmod 600 "${ENV_DIR}"/*.env
  log "secrets: ${filled} value(s) generated, ${skipped} left as already set"

  # --- the fill step: the env contract, asked for on a TTY, reported off one ---
  # The same verdicts the deploy refuses on. Anything still failing exits non-zero.
  [ -t 0 ] || log "stdin is not a terminal: reporting only, nothing will be asked"
  for name in backend postgres minio web-build; do
    template="$tdir/${name}.env.example"
    env_fill "$template" "${ENV_DIR}/${name}.env" "$ENV_DIR" || failed=1
  done

  [ "$failed" -eq 0 ] || die "env files still break the contract above; re-run './provision.sh secrets' on a terminal"
  log "secrets: every env file meets its template's contract"
}

main() {
  require_root
  case "${1:-}" in
    harden)      harden ;;
    docker)      docker_install ;;
    firewall)    firewall ;;
    secrets)     shift; secrets "$@" ;;
    ssh-keyonly) harden_ssh ;;
    # Deliberately excludes ssh-keyonly; see the header comment.
    all)         harden; docker_install; firewall ;;
    *)           die "usage: $0 {harden|docker|firewall|secrets [--templates <dir>]|ssh-keyonly|all}" ;;
  esac
}

main "$@"
