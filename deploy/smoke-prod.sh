#!/usr/bin/env bash
#
# Outside-in smoke check of production, run from a workstation (NOT the server) so it sees what
# Cloudflare sees. Prints PASS/FAIL per check and exits non-zero on any failure.
#
#   deploy/smoke-prod.sh [--forged-header] [--yes] [base-url]     (default https://agreementmitra.com)
#
# --forged-header is opt-in: it LOCKS YOUR /24 OUT of default-class routes for about 5 minutes
# (and anyone sharing it, carrier-NAT neighbours included), then reads the lockout event over SSH
# (${SMOKE_SSH:-agreementmitra-vps}) to prove the app keyed the lockout on your address and not on
# a forged X-Forwarded-For / Forwarded header.
#
# Creates no agreement and sends no agreement id. Cookie values are redacted before printing.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/checks.sh
. "$here/lib/checks.sh"

readonly FORGED_IP=198.51.100.23
readonly REMOTE_COMPOSE=/opt/agreementmitra/deploy/docker-compose.prod.yml

FORGED=0 YES=0 BASE=https://agreementmitra.com
while [ $# -gt 0 ]; do
  case "$1" in
    --forged-header) FORGED=1 ;;
    --yes) YES=1 ;;
    https://*) BASE="${1%/}" ;;
    *) printf 'usage: %s [--forged-header] [--yes] [https://base-url]\n' "$0" >&2; exit 2 ;;
  esac
  shift
done
HOST="${BASE#https://}"

tmp="$(mktemp -d "${TMPDIR:-/tmp}/smoke-prod.XXXXXXXX")"
trap 'rm -rf -- "$tmp"' EXIT
H="$tmp/headers"
failed=0

pass() { printf 'PASS %s\n' "$*"; }
fail() { printf 'FAIL %s\n' "$*"; failed=1; }

# req <curl args...>: fetch into $H (headers), body discarded; sets CODE.
req() {
  CODE="$(curl -sS --max-time 30 -o /dev/null -D "$H" -w '%{http_code}' "$@" || printf '000')"
}

# header <name>: the value of a response header in $H (CR stripped, sanitised).
header() {
  grep -i "^$1:" "$H" | head -n 1 | cut -d: -f2- | tr -d '\r' | sed 's/^[[:space:]]*//' | sanitize
}

# edge <label>: every response is checked for a Cloudflare challenge or an Access redirect.
edge() {
  local loc
  if grep -qi '^cf-mitigated:' "$H"; then
    fail "$1: Cloudflare challenged the request (cf-mitigated: $(header cf-mitigated))"
    return 1
  fi
  loc="$(header location)"
  case "$loc" in
    *cloudflareaccess.com*)
      fail "$1: redirected to Cloudflare Access -- Access is on for this path"
      return 1
      ;;
  esac
  return 0
}

# --- apex and www -----------------------------------------------------------
req "$BASE/"
if edge "apex"; then
  [ "$CODE" = 200 ] && pass "apex answers 200" || fail "apex answered $CODE, expected 200"
fi

req "https://www.$HOST/"
if edge "www"; then
  loc="$(header location)"
  if [ "$CODE" = 301 ] && { [ "$loc" = "$BASE/" ] || [ "$loc" = "$BASE" ]; }; then
    pass "www answers 301 to the apex"
  else
    fail "www answered $CODE to '$loc', expected 301 to $BASE/"
  fi
fi

# --- request-body ceiling -----------------------------------------------------
head -c 1200000 /dev/zero | tr '\0' x >"$tmp/big"
req -X POST "$BASE/api/agreements" -H 'Content-Type: application/json' --data-binary @"$tmp/big"
if edge "oversized POST"; then
  [ "$CODE" = 413 ] && pass "1.2 MB POST to /api/agreements answers 413" || fail "1.2 MB POST answered $CODE, expected 413"
fi

# --- CSRF cookie + edge caching -------------------------------------------------
req "$BASE/api/templates"
if edge "/api/templates"; then
  cookie="$(grep -i '^set-cookie:[[:space:]]*__Host-XSRF-TOKEN=' "$H" | head -n 1 | tr -d '\r' || true)"
  # Redact the value before anything is printed.
  shown="$(printf '%s' "$cookie" | sed -E 's/(__Host-XSRF-TOKEN=)[^;]*/\1<redacted>/' | sanitize)"
  if [ -z "$cookie" ]; then
    fail "/api/templates set no __Host-XSRF-TOKEN cookie"
  elif printf '%s' "$cookie" | grep -qi '; *secure' && printf '%s' "$cookie" | grep -qi '; *samesite=lax'; then
    pass "CSRF cookie is __Host-, Secure, SameSite=Lax"
  else
    fail "CSRF cookie attributes wrong: $shown"
  fi
  status="$(header cf-cache-status)"
  [ "$status" = DYNAMIC ] && pass "/api/templates is cf-cache-status: DYNAMIC" \
    || fail "/api/templates cf-cache-status is '${status:-absent}', expected DYNAMIC"
fi

req "$BASE/api/templates/form?state=KA&type=residential"
if edge "/api/templates/form"; then
  status="$(header cf-cache-status)"
  [ "$status" = DYNAMIC ] && pass "/api/templates/form is cf-cache-status: DYNAMIC" \
    || fail "/api/templates/form cf-cache-status is '${status:-absent}', expected DYNAMIC"
fi

# --- webhooks reach the app, not a challenge ----------------------------------------
for path in /api/webhooks/esign /api/webhooks/razorpay; do
  req -X POST "$BASE$path" -H 'Content-Type: application/json' --data '{}'
  if edge "$path"; then
    [ "$CODE" = 401 ] && pass "unsigned POST to $path answers 401 (reached the app)" \
      || fail "unsigned POST to $path answered $CODE, expected 401"
  fi
done

# --- forged forwarded header (opt-in, last) ---------------------------------------
forged_header() {
  local answer trace ip caller24 forged24 start remote matched verdict re
  cat >&2 <<EOF

WARNING: the forged-header check sends 150 requests from this machine. The application will
lock YOUR /24 out of its default-class routes for about 5 minutes -- including anyone else
sharing that /24 (carrier-NAT neighbours, the office network).
EOF
  if [ "$YES" -ne 1 ]; then
    printf 'Proceed? [y/N] ' >&2
    IFS= read -r answer </dev/tty || answer=""
    [ "$answer" = y ] || [ "$answer" = Y ] || { fail "forged-header check skipped (not confirmed)"; return; }
  fi

  trace="$(curl -4 -sS --max-time 15 "$BASE/cdn-cgi/trace" || true)"
  ip="$(printf '%s\n' "$trace" | sed -n 's/^ip=//p' | head -n 1 | tr -d '\r')"
  if ! valid_ipv4 "$ip"; then
    fail "forged header: INCONCLUSIVE -- cdn-cgi/trace returned no valid IPv4; nothing run over SSH"
    return
  fi
  caller24="$(slash24 "$ip")"
  forged24="$(slash24 "$FORGED_IP")"

  start="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  seq 1 150 | xargs -P 15 -I{} curl -4 -s -o /dev/null --max-time 20 "$BASE/api/auth/me" \
    -H "X-Forwarded-For: $FORGED_IP" -H "Forwarded: for=$FORGED_IP" || true
  sleep 3

  # The remote command is built only from validated tokens, each quoted with printf %q.
  re="event=rate_limit_lockout .*source=(${caller24//./\\.}|${forged24//./\\.}) "
  remote="$(printf '%q ' docker compose -f "$REMOTE_COMPOSE" logs --no-log-prefix --no-color --since "$start" backend) | $(printf '%q ' grep -E -- "$re")"
  matched="$(ssh -o BatchMode=yes "${SMOKE_SSH:-agreementmitra-vps}" "$remote" 2>/dev/null \
    | sed -nE 's/.*source=([0-9.]+\/24) .*/\1/p' | sanitize | sort -u || true)"

  verdict="$(forged_verdict "$matched" "$caller24" "$forged24" || true)"
  case "$verdict" in
    PASS) pass "forged header ignored: the lockout names your /24 ($caller24)" ;;
    FAIL) fail "forged header HONOURED: the lockout names $forged24 -- the application trusted a client-supplied header" ;;
    *) fail "forged header: INCONCLUSIVE -- no lockout event for $caller24 or $forged24 since $start" ;;
  esac
}

[ "$FORGED" -eq 0 ] || forged_header

if [ "$failed" -eq 0 ]; then
  printf 'smoke: all checks passed\n'
else
  printf 'smoke: FAILED\n'
fi
exit "$failed"
