#!/usr/bin/env bash
#
# A LINT, not a control: greps the deploy scripts for constructs that would leak a secret or
# destroy data. Line continuations and argument arrays can evade it; review backs it up.
#
#   /bin/bash deploy/test/forbidden.test.sh

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
files="$here/../deploy.sh $here/../smoke-prod.sh $here/../lib/checks.sh"
fail=0

# check <description> <ERE> [<ERE an offending line must NOT also match>]
check() {
  local hits
  # shellcheck disable=SC2086
  hits="$(grep -nE "$2" $files | grep -vE '^[^:]+:[0-9]+:[[:space:]]*#' || true)"
  if [ -n "${3:-}" ] && [ -n "$hits" ]; then
    hits="$(printf '%s\n' "$hits" | grep -vE -- "$3" || true)"
  fi
  if [ -n "$hits" ]; then
    printf 'FAIL %s\n%s\n' "$1" "$hits"
    fail=1
  else
    printf 'PASS no %s\n' "$1"
  fi
}

check 'shell tracing' 'set[[:space:]]+-[a-wyz]*x|set[[:space:]]+-o[[:space:]]+xtrace'
check 'destructive down flags' '(^|[[:space:]])down([[:space:]].*)?[[:space:]](-v|--volumes|--rmi|--remove-orphans)([[:space:]]|$)'
check 'forced image removal' 'image[[:space:]]+rm[[:space:]].*-f|rmi[[:space:]]+-f'
check 'docker inspect without a format' 'docker[[:space:]]+(network[[:space:]]+)?inspect' '[[:space:]]-f[[:space:]]'
check 'compose config without --no-env-resolution or --quiet' '(dc|compose)[[:space:]]+([^|]*[[:space:]])?config([[:space:]]|$)' '--no-env-resolution|--quiet'
check 'bare docker compose outside dc()' 'docker[[:space:]]+compose' 'deploy\.sh:[0-9]+:dc\(\)[[:space:]]*\{[[:space:]]*docker[[:space:]]+compose[[:space:]]+-f[[:space:]]+"\$DC_FILE"|smoke-prod\.sh:[0-9]+:.*printf .%q'
check 'sourcing deploy/.env' '(^|[[:space:];])(source|\.)[[:space:]]+[^[:space:]]*deploy/\.env|(source|\.)[[:space:]]+"?\$\{?DEPLOY_ENV'

exit "$fail"
