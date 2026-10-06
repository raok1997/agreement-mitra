#!/usr/bin/env bash
#
# Template lint: every assigned key in the four deploy/env/*.env.example templates carries a
# well-formed `#@` tag (grammar in backend.env.example's header). The deploy runs the same
# env_tags over the target commit's templates and refuses on a failure, so a key that ships
# untagged blocks the next deploy -- this test catches it first.
#
#   /bin/bash deploy/test/templates.test.sh

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/checks.sh
. "$here/../lib/checks.sh"

fail=0

for name in backend postgres minio web-build; do
  if env_tags "$here/../env/$name.env.example" >/dev/null; then
    printf 'PASS %s.env.example\n' "$name"
  else
    printf 'FAIL %s.env.example (see above)\n' "$name"
    fail=1
  fi
done

# Each fixture breaks exactly one rule and must fail the lint.
for f in "$here"/fixtures/templates/*.env.example; do
  if env_tags "$f" >/dev/null 2>&1; then
    printf 'FAIL lint accepted %s\n' "$(basename "$f")"
    fail=1
  else
    printf 'PASS lint rejects %s\n' "$(basename "$f")"
  fi
done

untagged="$(env_tags "$here/fixtures/templates/untagged.env.example" 2>&1 >/dev/null || true)"
case "$untagged" in
  *"B has no #@ tag"*) printf 'PASS untagged key is named\n' ;;
  *) printf 'FAIL untagged key is not named\n'; fail=1 ;;
esac

exit "$fail"
