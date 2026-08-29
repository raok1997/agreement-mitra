#!/usr/bin/env bash
#
# One-time provisioning for the AgreementMitra production VPS (Contabo, Ubuntu).
# Idempotent: safe to re-run. Run as root, from the repo checkout on the server.
#
#   ./provision.sh harden      OS updates, swap, fail2ban, auto-updates
#   ./provision.sh docker      Docker CE + compose plugin, deploy user
#   ./provision.sh firewall    ufw: deny inbound except SSH and Cloudflare-only 80/443
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
set -euo pipefail

V4_FILE=/etc/agreementmitra/cloudflare-ips-v4
V6_FILE=/etc/agreementmitra/cloudflare-ips-v6
PORTS=80,443

apply() {
  local ipt="$1" file="$2" cidr
  "$ipt" -F DOCKER-USER 2>/dev/null || return 0

  # Return traffic for connections the host/containers initiated.
  "$ipt" -A DOCKER-USER -p tcp -m multiport --dports "$PORTS" \
         -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN

  if [ -s "$file" ]; then
    while read -r cidr; do
      [ -n "$cidr" ] || continue
      "$ipt" -A DOCKER-USER -p tcp -m multiport --dports "$PORTS" -s "$cidr" -j RETURN
    done <"$file"
  fi

  # Everything else reaching a published 80/443 is dropped.
  "$ipt" -A DOCKER-USER -p tcp -m multiport --dports "$PORTS" -j DROP
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
  systemctl enable --now am-docker-firewall.service
  iptables -L DOCKER-USER -n --line-numbers | head -20
}

# ---------------------------------------------------------------------------

main() {
  require_root
  case "${1:-}" in
    harden)      harden ;;
    docker)      docker_install ;;
    firewall)    firewall ;;
    ssh-keyonly) harden_ssh ;;
    # Deliberately excludes ssh-keyonly; see the header comment.
    all)         harden; docker_install; firewall ;;
    *)           die "usage: $0 {harden|docker|firewall|ssh-keyonly|all}" ;;
  esac
}

main "$@"
