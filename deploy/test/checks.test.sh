#!/usr/bin/env bash
#
# Fixture tests for deploy/lib/checks.sh. Plain bash, no framework; exits non-zero on any
# failure. Runs on macOS (bash 3.2, BSD tools) and on the server (bash 5, GNU):
#
#   /bin/bash deploy/test/checks.test.sh
#
# Fixtures in deploy/test/fixtures/: the network JSON, the MinIO version text and the backend log
# were captured locally from the same commands the deploy runs (network inspect on a throwaway
# network with the prod subnet); re-capture them on the server at the manual-test gate.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fx="$here/fixtures"
# shellcheck source=../lib/checks.sh
. "$here/../lib/checks.sh"

tmp="$(mktemp -d "${TMPDIR:-/tmp}/checks-test.XXXXXXXX")"
trap 'rm -rf -- "$tmp"' EXIT

pass=0 fail=0
ok()  { pass=$((pass + 1)); }
bad() { fail=$((fail + 1)); printf 'FAIL: %s\n' "$1" >&2; }
# eq <desc> <expected> <actual>
eq() {
  if [ "$2" = "$3" ]; then ok; else bad "$1"$'\n'"  expected: [$2]"$'\n'"  actual:   [$3]"; fi
}
# yes <desc> <cmd...>: the command succeeds
yes() { local d="$1"; shift; if "$@" >/dev/null 2>&1; then ok; else bad "$d"; fi; }
# no <desc> <cmd...>: the command fails
no()  { local d="$1"; shift; if "$@" >/dev/null 2>&1; then bad "$d"; else ok; fi; }
nl=$'\n'

# ---------------------------------------------------------------------------
# env
# ---------------------------------------------------------------------------

SENTINEL="s3ntinel-VALUE-must-never-print"

cat >"$tmp/t.env.example" <<'EOF'
# header comment
#@ config fixed required
FIXED_URL=http://svc:1
#@ config setting required pattern=^(true|false)$
A_SETTING=true
#@ config setting required pattern=^(REQUIRED|DISABLED)$
MODE=REQUIRED
# the vendor's secret
#@ secret vendor required
VENDOR_KEY=
#@ secret vendor required-if=MODE=REQUIRED
COND_SECRET=
#@ config vendor required-if=MODE=REQUIRED pattern=^rzp_(test|live)_[A-Za-z0-9]+$
KEY_ID=
#@ secret vendor optional
OPT_SECRET=
#@ secret generated required pattern=^[A-Za-z0-9]{32,}$
PEPPER=
#@ secret generated required pattern=^[A-Za-z0-9]{32,}$ match=other.env:OTHER_PASSWORD
DB_PASSWORD=
# COMMENTED_KEY=x
EOF

good32="abcdefghijklmnopqrstuvwxyz012345"
other32="ZYXWVUTSRQPONMLKJIHGFEDCBA987654"
write_target() {
  cat >"$tmp/t.env" <<EOF
FIXED_URL=http://svc:1
A_SETTING=true
MODE=REQUIRED
VENDOR_KEY=$SENTINEL
COND_SECRET=$SENTINEL
KEY_ID=rzp_test_abc123
OPT_SECRET=
PEPPER=$good32
DB_PASSWORD=$good32
EOF
  printf 'OTHER_PASSWORD=%s\n' "$good32" >"$tmp/other.env"
}
# setv <key> <value>: rewrite one key in the target
setv() { ENV_SET_VALUE="$2" env_set "$tmp/t.env" "$1"; }
verdict_of() { env_verdict "$tmp/t.env.example" "$tmp/t.env" 2>&1 | awk -v k="$1" '$1 == k { print $2 }'; }

write_target
eq "all-good target verdicts ok" "0" "$(env_verdict "$tmp/t.env.example" "$tmp/t.env" >/dev/null; echo $?)"
eq "blank optional is info" "blank-optional" "$(verdict_of OPT_SECRET)"

# missing; commented key not required; export/indented/lower-case are not keys
grep -v '^VENDOR_KEY=' "$tmp/t.env" >"$tmp/t2" && mv "$tmp/t2" "$tmp/t.env"
eq "missing key reported" "VENDOR_KEY" "$(env_missing_keys "$tmp/t.env.example" "$tmp/t.env")"
eq "missing verdict" "missing" "$(verdict_of VENDOR_KEY)"
eq "merge line for missing key" "VENDOR_KEY=" "$(env_merge_lines "$tmp/t.env.example" "$tmp/t.env")"
printf 'export EXP=1\n  INDENT=1\nQUJD=x\nlower=1\nQ1_OK=1\n' >"$tmp/keys.env"
eq "only ^[A-Z][A-Z0-9_]*= lines are keys" "QUJD${nl}Q1_OK" "$(env_keys "$tmp/keys.env")"
eq "commented key is not a template key" "" "$(env_keys "$tmp/t.env.example" | grep COMMENTED || true)"
eq "extra keys" "QUJD${nl}Q1_OK" "$(env_extra_keys "$tmp/t.env.example" "$tmp/keys.env" | grep -E 'QUJD|Q1_OK')"

# D7's ^[A-Z][A-Z0-9_]*= cannot tell an all-caps base64 fragment (`QUJD=`) from a key, so it
# counts as one (above). Lower-case and digit-led names are not keys.
printf '0ABC=1\nqujd=1\n' >"$tmp/k2.env"
eq "digit-led and lower-case names are not keys" "" "$(env_keys "$tmp/k2.env")"

write_target
setv VENDOR_KEY ""
eq "blank required secret" "blank" "$(verdict_of VENDOR_KEY)"
setv VENDOR_KEY '""'
eq "\"\" is blank" "blank" "$(verdict_of VENDOR_KEY)"
setv VENDOR_KEY "   "
eq "whitespace is blank" "blank" "$(verdict_of VENDOR_KEY)"
yes "env_blank ''" env_blank "''"
no "env_blank x" env_blank "x"

write_target
setv COND_SECRET ""
eq "required-if met and blank" "blank" "$(verdict_of COND_SECRET)"
setv MODE DISABLED
eq "required-if not met and blank" "blank-optional" "$(verdict_of COND_SECRET)"
eq "a changed setting is ok" "ok" "$(verdict_of MODE)"
setv A_SETTING false
eq "setting differing from template is ok" "ok" "$(verdict_of A_SETTING)"
setv A_SETTING maybe
eq "setting failing its pattern" "pattern" "$(verdict_of A_SETTING)"

write_target
setv PEPPER "__GENERATED_ON_SERVER__"
eq "generated-on-server marker is a placeholder" "placeholder" "$(verdict_of PEPPER)"
setv PEPPER "dev-only-identity-pepper-change-me"
eq "dev-only pepper default is a placeholder" "placeholder" "$(verdict_of PEPPER)"
for p in CHANGEME change-me Placeholder TODO xxxx; do
  yes "placeholder: $p" env_placeholder "$p"
done
no "not a placeholder" env_placeholder "$good32"

write_target
setv KEY_ID "rzp_bogus_1"
eq "malformed Razorpay key id" "pattern" "$(verdict_of KEY_ID)"
setv FIXED_URL "http://svc:2"
eq "changed fixed value drifts" "drift" "$(verdict_of FIXED_URL)"

write_target
printf 'OTHER_PASSWORD=%s\n' "$other32" >"$tmp/other.env"
eq "credential mismatch" "mismatch" "$(verdict_of DB_PASSWORD)"
printf 'OTHER_PASSWORD=%s\n' "$good32" >"$tmp/other.env"
eq "credential match" "ok" "$(verdict_of DB_PASSWORD)"

# A sentinel never appears in any output, whatever verdict carries it: each key below holds a
# sentinel-bearing value that lands on a different verdict.
SENT32="S3ntinelS3ntinelS3ntinelS3ntinel"
write_target
ENV_SET_VALUE="dev-only-$SENTINEL" env_set "$tmp/t.env" VENDOR_KEY      # placeholder
ENV_SET_VALUE="$SENTINEL" env_set "$tmp/t.env" KEY_ID                   # pattern
ENV_SET_VALUE="$SENTINEL" env_set "$tmp/t.env" FIXED_URL                # drift
ENV_SET_VALUE="$SENT32" env_set "$tmp/t.env" DB_PASSWORD                # mismatch
ENV_SET_VALUE="$SENTINEL" env_set "$tmp/t.env" A_SETTING                # setting pattern
ENV_SET_VALUE="$SENTINEL" env_set "$tmp/t.env" OPT_SECRET               # ok
eq "sentinel verdicts" "placeholder pattern drift mismatch pattern ok" \
  "$(verdict_of VENDOR_KEY) $(verdict_of KEY_ID) $(verdict_of FIXED_URL) $(verdict_of DB_PASSWORD) $(verdict_of A_SETTING) $(verdict_of OPT_SECRET)"
out="$(env_verdict "$tmp/t.env.example" "$tmp/t.env" 2>&1 || true)"
out="$out$(env_missing_keys "$tmp/t.env.example" "$tmp/t.env" 2>&1)"
out="$out$(env_extra_keys "$tmp/t.env.example" "$tmp/t.env" 2>&1)"
out="$out$(env_fill "$tmp/t.env.example" "$tmp/t.env" </dev/null 2>&1 || true)"
case "$out" in
  *"$SENTINEL"* | *"$SENT32"* | *"$good32"*) bad "a value appeared in env-check output" ;;
  *) ok ;;
esac

# Quoting: compose interpolates $, cuts at " #" and strips quotes in unquoted values.
eq "plain value unquoted" "abc123" "$(env_quote abc123)"
eq "\$ is single-quoted" "'a\$b'" "$(env_quote 'a$b')"
eq "' #' is single-quoted" "'12 MG Road #3'" "$(env_quote '12 MG Road #3')"
no "a single quote is refused" env_quote "it's"
printf "Q1='a\$b #c'\nQ2=\"x\"\nQ3=''\nQ4='\n" >"$tmp/q.env"
eq "env_value strips single quotes" 'a$b #c' "$(env_value "$tmp/q.env" Q1)"
eq "env_value strips double quotes" "x" "$(env_value "$tmp/q.env" Q2)"
eq "env_value of '' is empty" "" "$(env_value "$tmp/q.env" Q3)"
eq "a lone quote is left alone" "'" "$(env_value "$tmp/q.env" Q4)"

# env_set: other lines byte-identical, 0600, newline refused, metacharacters literal
write_target
cp "$tmp/t.env" "$tmp/before.env"
chmod 644 "$tmp/t.env"
meta='a|b&c/d\e$f'
ENV_SET_VALUE="$meta" env_set "$tmp/t.env" VENDOR_KEY
eq "env_set writes the value literally" "$meta" "$(env_value "$tmp/t.env" VENDOR_KEY)"
eq "env_set leaves other lines byte-identical" \
  "$(grep -v '^VENDOR_KEY=' "$tmp/before.env")" "$(grep -v '^VENDOR_KEY=' "$tmp/t.env")"
eq "env_set writes 0600" "$tmp/t.env" "$(find "$tmp/t.env" -perm 600)"
env_set_nl() { ENV_SET_VALUE="a${nl}b" env_set "$tmp/t.env" VENDOR_KEY; }
no "env_set refuses a newline" env_set_nl
eq "refused newline left the value" "$meta" "$(env_value "$tmp/t.env" VENDOR_KEY)"
ENV_SET_VALUE="new" env_set "$tmp/t.env" BRAND_NEW
eq "env_set appends an absent key" "new" "$(env_value "$tmp/t.env" BRAND_NEW)"
eq "no temp files left" "" "$(find "$tmp" -name '.env_set.*')"

# env_fill off a TTY: asks nothing, names the failing key, exits non-zero, writes nothing.
write_target
setv VENDOR_KEY ""
cp "$tmp/t.env" "$tmp/before.env"
fill_out="$(env_fill "$tmp/t.env.example" "$tmp/t.env" </dev/null 2>&1)" && rc=0 || rc=$?
eq "fill off a TTY exits non-zero while a key fails" "1" "$rc"
case "$fill_out" in *"VENDOR_KEY: blank"*) ok ;; *) bad "fill off a TTY names the failing key" ;; esac
case "$fill_out" in *"OPT_SECRET"*) ok ;; *) bad "fill lists optional-blank keys" ;; esac
yes "fill off a TTY writes nothing" cmp "$tmp/before.env" "$tmp/t.env"
setv VENDOR_KEY "$good32"
env_fill "$tmp/t.env.example" "$tmp/t.env" </dev/null >/dev/null 2>&1 && rc=0 || rc=$?
eq "fill exits zero when only optional keys are blank" "0" "$rc"

# ---------------------------------------------------------------------------
# network
# ---------------------------------------------------------------------------

declared="$(ipam_from_compose "$(cat "$fx/compose-config.json")")"
running="$(ipam_from_inspect "$(cat "$fx/network-inspect.json")")"
eq "compose fixture normalises" "10.203.17.0/24 10.203.17.128/25" "$declared"
no "same network in both shapes is no change" network_change "$declared" "$running"
yes "an ip-range change is a change" network_change "$declared" "10.203.17.0/24 10.203.17.0/25"
no "no running network is no change" network_change "$declared" ""
eq "inspect without IPRange" "10.9.0.0/16 -" "$(ipam_from_inspect '[{"Subnet":"10.9.0.0/16","Gateway":"10.9.0.1"}]')"
eq "inspect with only IPv6" "" "$(ipam_from_inspect '[{"Subnet":"fd00::/64"}]')"
eq "compose with no ipam" "" "$(ipam_from_compose '{"networks":{"default":{}}}')"
yes "10.203.0.0/16 overlaps 10.203.17.0/24" cidr_overlap 10.203.0.0/16 10.203.17.0/24
yes "overlap is symmetric" cidr_overlap 10.203.17.0/24 10.203.0.0/16
no "10.204.0.0/24 does not overlap" cidr_overlap 10.204.0.0/24 10.203.17.0/24
no "IPv6 never overlaps" cidr_overlap fd00::/64 10.203.17.0/24
no "empty never overlaps" cidr_overlap "" 10.203.17.0/24
yes "/0 overlaps everything" cidr_overlap 0.0.0.0/0 10.203.17.0/24

# ---------------------------------------------------------------------------
# schema
# ---------------------------------------------------------------------------

eq "max migration over the real listing" "26" "$(max_migration_version <"$fx/ls-tree-migrations.txt")"
eq "V8 sorts numerically below V26" "26" "$(printf 'a/V8__x.sql\na/V26__y.sql\na/V9__z.sql\n' | max_migration_version)"
no "schema_gate 26 25 refuses" schema_gate 26 25
yes "schema_gate 26 26 passes" schema_gate 26 26
yes "schema_gate on an empty database passes" schema_gate 0 26

# ---------------------------------------------------------------------------
# dumps
# ---------------------------------------------------------------------------

trailer="--${nl}-- PostgreSQL database dump complete${nl}--"
# pg_dump 17.6+ (CVE-2025-8714) ends with \unrestrict AFTER the trailer; the deploy keeps 20 lines.
trailer17="--${nl}-- PostgreSQL database dump complete${nl}--${nl}${nl}\\unrestrict AbCdEf0123${nl}"
eq "dump_name" "pre-deploy-20261007T101500Z-0123456789ab.sql.gz" "$(dump_name 20261007T101500Z 0123456789ab)"
yes "script dump name" is_script_dump "pre-deploy-20261007T101500Z-0123456789ab.sql.gz"
yes "refresh-tag dump name" is_script_dump "pre-deploy-20261007T101500Z-0123456789ab-r202610071015.sql.gz"
no "hand-made dump is not a script dump" is_script_dump "pre-deploy-20261006-1423.sql.gz"
eq "dump ok" "ok" "$(dump_verdict 0 12 "$trailer" "0 0")"
eq "pg_dump 17.6+ tail with \\unrestrict is ok" "ok" "$(dump_verdict 0 12 "$trailer17" "0 0")"
no "dump missing trailer" dump_verdict 0 12 "COPY public.x" "0 0"
no "dump with zero COPY" dump_verdict 0 0 "$trailer" "0 0"
no "dump bad gzip" dump_verdict 1 12 "$trailer" "0 0"
no "dump pipeline failure" dump_verdict 0 12 "$trailer" "1 0"
no "dump gzip side failure" dump_verdict 0 12 "$trailer" "0 1"

listing=""
for d in 01 02 03 04 05 06 07 08 09 10 11; do
  listing="${listing}pre-deploy-202610${d}T000000Z-0123456789ab.sql.gz${nl}"
done
listing="${listing}pre-deploy-20261006-1423.sql.gz${nl}backend.env.20261006-1423${nl}provision-firewall-fix.patch${nl}"
eq "rotation deletes only the oldest of eleven script dumps" \
  "pre-deploy-20261001T000000Z-0123456789ab.sql.gz" \
  "$(printf '%s' "$listing" | rotation_victims 20260901T000000Z pre-deploy-20261011T000000Z-0123456789ab.sql.gz "")"
old="pre-deploy-20260801T000000Z-aaaaaaaaaaaa.sql.gz"
old2="pre-deploy-20260802T000000Z-bbbbbbbbbbbb.sql.gz"
eq "over-age dump deleted unless protected" "$old2" \
  "$(printf '%s\n%s\n' "$old" "$old2" | rotation_victims 20260906T000000Z x "$old")"
eq "the dump just taken is never deleted" "" \
  "$(printf '%s\n' "$old" | rotation_victims 20260906T000000Z "$old" "")"

# ---------------------------------------------------------------------------
# history
# ---------------------------------------------------------------------------

T_A=aaaaaaaaaaaa T_B=bbbbbbbbbbbb T_C=cccccccccccc T_D=dddddddddddd T_E=eeeeeeeeeeee T_S=555555555555
# L <action> <tag> <flyway> <dump> <outcome>
L() { printf '2026-10-07T00:00:00Z %s prev=- target=%s0000 tag=%s flyway=%s minio=- images=i1,i2,i3 dump=%s outcome=%s\n' "$1" "$2" "$2" "$3" "$4" "$5"; }
log="$tmp/deploys.log"

{ L deploy $T_A 24 dA ok; L deploy $T_B 25 dB ok; L deploy $T_C 26 dC ok; } >"$log"
eq "first rollback after A,B,C targets B" "$T_B" "$(rollback_target "$log" $T_C)"
L rollback $T_B 26 - ok >>"$log"
eq "second rollback targets A" "$T_A" "$(rollback_target "$log" $T_B)"

{ L deploy $T_A 24 dA ok; L deploy $T_B 25 dB ok; L deploy $T_C 25 dC postcheck:errors; } >"$log"
eq "rollback after a failed deploy keeps the good entry" "$T_B" "$(rollback_target "$log" $T_C)"
{ L rollback $T_B 25 - ok; L deploy $T_D 25 dD ok; } >>"$log"
eq "then D ok, rollback targets B again" "$T_B" "$(rollback_target "$log" $T_D)"

{ L seed $T_S 23 - ok; L deploy $T_A 24 dA ok; } >"$log"
eq "seed then A: rollback targets the seed" "$T_S" "$(rollback_target "$log" $T_A)"
eq "seed ceiling is its recorded flyway" "23" "$(rollback_ceiling "$log" $T_S)"
eq "a deploy's ceiling is the tree (empty)" "" "$(rollback_ceiling "$log" $T_A)"
yes "seed target is a seed" rollback_is_seed "$log" $T_S
no "deploy target is not a seed" rollback_is_seed "$log" $T_A
eq "recorded images" "i1,i2,i3" "$(rollback_images "$log" $T_A)"
eq "recorded full hash" "${T_A}0000" "$(rollback_target_full "$log" $T_A)"

L deploy $T_A 24 dA ok >"$log"
eq "only the running entry: nothing to roll back to" "" "$(rollback_target "$log" $T_A)"
{ L deploy $T_A 24 dA ok; L deploy $T_A 24 dA2 ok; } >"$log"
eq "a duplicate of the top is not pushed" "$T_A" "$(history "$log")"

{ L deploy $T_A 24 dA ok; L deploy $T_B 25 dB postcheck:migrations; L deploy $T_C 25 dC up;
  L deploy $T_D 25 dD postcheck:caddy; L deploy $T_E 25 dE ok; } >"$log"
eq "keep-set after A ok, B C D failed, E ok" "$T_E${nl}$T_A" "$(image_keep_set "$log" $T_E)"
case "${nl}$(protected_dumps "$log" "$(image_keep_set "$log" $T_E)")${nl}" in
  *"${nl}dA${nl}"*) ok ;; *) bad "protected_dumps includes A's dump" ;;
esac
case "${nl}$(protected_dumps "$log" "$(image_keep_set "$log" $T_E)")${nl}" in
  *"${nl}dB${nl}"*) bad "protected_dumps excludes B's dump" ;; *) ok ;;
esac

{ L deploy $T_A 24 dA ok; L deploy $T_B 25 dB ok; L deploy $T_C 26 dC ok; } >"$log"
eq "restore dump is the first line above the ceiling" "dB" "$(restore_dump_for "$log" 24)"
{ L deploy $T_A 26 dA ok; L deploy $T_B 27 dB postcheck:caddy; } >"$log"
eq "a deploy that migrated then failed still offers its dump" "dB" "$(restore_dump_for "$log" 26)"

{ L deploy $T_A 24 dA ok; L deploy $T_B 25 dB postcheck:errors; } >"$log"
yes "accept allowed after postcheck:errors on the running tag" accept_allowed "$log" $T_B
no "accept refused for another tag" accept_allowed "$log" $T_A
for o in postcheck:caddy postcheck:minio postcheck:migrations up ok; do
  { L deploy $T_A 24 dA ok; L deploy $T_B 25 dB "$o"; } >"$log"
  no "accept refused after $o" accept_allowed "$log" $T_B
done
{ L deploy $T_A 24 dA ok; L deploy $T_B 25 dB postcheck:errors; L accept $T_B 25 dB ok; } >"$log"
no "accept refused twice" accept_allowed "$log" $T_B
eq "accept pushes the tag" "$T_A${nl}$T_B" "$(history "$log")"
{ L deploy $T_A 24 dA ok; L deploy $T_B 25 dB ok; L rollback $T_A 25 - postcheck:errors; } >"$log"
yes "accept allowed after a rollback that failed only the ERROR scan" accept_allowed "$log" $T_A
L accept $T_A 25 - ok >>"$log"
eq "an accepted rollback pops to its tag" "$T_A" "$(history "$log")"
eq "and the next rollback has nowhere to go" "" "$(rollback_target "$log" $T_A)"
eq "a missing log has an empty history (the seed trigger)" "" "$(history "$tmp/no-such.log")"
: >"$tmp/empty.log"
eq "an empty log has an empty history" "" "$(history "$tmp/empty.log")"
{ L deploy $T_A 24 dA up; L deploy $T_B 25 dB postcheck:errors; } >"$log"
eq "a log of failures only has an empty history" "" "$(history "$log")"
{ L seed $T_S 23 - ok; L deploy $T_A 24 dA ok; L rollback $T_S 24 - postcheck:errors; L accept $T_S 23 - ok;
  L deploy $T_C 24 dC ok; } >"$log"
eq "after an accepted rollback to the seed, rollback targets the seed" "$T_S" "$(rollback_target "$log" $T_C)"
yes "it is still recognised as the seed" rollback_is_seed "$log" $T_S
eq "with the seed's ceiling" "23" "$(rollback_ceiling "$log" $T_S)"
{ L deploy $T_A 24 dA ok; L deploy $T_B 25 dB up; } >"$log"
eq "mixed running state rolls back to the top" "$T_A" "$(rollback_target "$log" mixed)"
yes "recorded images present" images_match "i1,i2,i3" "i1,i2,i3"
eq "a retagged image is named by position" "2" "$(images_match "i1,i2,i3" "i1,XX,i3" || true)"
eq "a missing image is named by position" "3" "$(images_match "i1,i2,i3" "i1,i2," || true)"
no "nothing recorded never matches" images_match "" "i1,i2,i3"

# ---------------------------------------------------------------------------
# text
# ---------------------------------------------------------------------------

eq "running tag" "0123456789ab" "$(running_tag_from_image agreementmitra-backend:0123456789ab)"
eq "running refresh tag" "0123456789ab-r202610071015" \
  "$(running_tag_from_image agreementmitra-backend:0123456789ab-r202610071015)"
eq "pre-script latest is unknown" "unknown" "$(running_tag_from_image agreementmitra-backend:latest)"
eq "per-service tag" "0123456789ab" "$(running_tag_from_image agreementmitra-caddy:0123456789ab caddy)"
eq "another service's image is not this one's tag" "unknown" "$(running_tag_from_image agreementmitra-caddy:0123456789ab gotenberg)"
eq "all three agree" "$T_A" "$(running_tag_of $T_A $T_A $T_A)"
eq "pre-script stack is unknown" "unknown" "$(running_tag_of unknown unknown unknown)"
eq "part-way up is mixed" "mixed" "$(running_tag_of $T_A $T_B $T_B)"
eq "other image is unknown" "unknown" "$(running_tag_from_image evil/agreementmitra-backend:0123456789ab)"
eq "minio release from real output" "RELEASE.2023-09-04T19-57-37Z" "$(minio_release "$(cat "$fx/minio-version.txt")")"
yes "minio changed" minio_changed RELEASE.2025-09-07T16-13-09Z RELEASE.2025-10-01T00-00-00Z
no "minio unchanged" minio_changed RELEASE.2025-09-07T16-13-09Z RELEASE.2025-09-07T16-13-09Z
yes "minio unreadable counts as changed" minio_changed "" RELEASE.2025-09-07T16-13-09Z

loggers="$(error_loggers <"$fx/backend-log.txt")"
eq "ERROR scan finds only level-ERROR lines, logger names only" "i.a.documents.GotenbergClient${nl}?" "$loggers"
case "$loggers" in *secret-message-text*|*WebhookController*) bad "error_loggers leaked a message or an INFO line" ;; *) ok ;; esac

yes "valid ipv4" valid_ipv4 203.0.113.7
no "ipv4 with shell" valid_ipv4 '1.2.3.4;curl x|sh'
no "ipv4 octet > 255" valid_ipv4 256.1.1.1
no "ipv4 too short" valid_ipv4 1.2.3
no "ipv6" valid_ipv4 2001:db8::1
eq "slash24" "203.0.113.0/24" "$(slash24 203.0.113.7)"

eq "forged pass" "PASS" "$(forged_verdict "203.0.113.0/24" 203.0.113.0/24 198.51.100.0/24)"
eq "forged fail" "FAIL" "$(forged_verdict "203.0.113.0/24${nl}198.51.100.0/24" 203.0.113.0/24 198.51.100.0/24 || true)"
eq "forged inconclusive" "INCONCLUSIVE" "$(forged_verdict "" 203.0.113.0/24 198.51.100.0/24 || true)"
no "inconclusive is not a pass" forged_verdict "" 203.0.113.0/24 198.51.100.0/24

yes "valid hash short" valid_hash 0123abc
yes "valid hash full" valid_hash 0123456789abcdef0123456789abcdef01234567
no "hash --output=x" valid_hash --output=x
no "hash origin/main" valid_hash origin/main
no "hash upper-case" valid_hash ABC1234
no "hash too long" valid_hash 0123456789abcdef0123456789abcdef012345678

yes "tag line" valid_tag_line DEPLOY_TAG=0123456789ab
yes "refresh tag line" valid_tag_line DEPLOY_TAG=0123456789ab-r202610071015
no "tag line latest" valid_tag_line DEPLOY_TAG=latest
no "tag line with extra" valid_tag_line "DEPLOY_TAG=0123456789ab; rm -rf /"

line="$(log_line deploy aaaaaaaaaaaa full0 bbbbbbbbbbbb 26 RELEASE.x i1,i2,i3 dump.gz ok)"
eq "log_line field order" \
  "deploy prev=aaaaaaaaaaaa target=full0 tag=bbbbbbbbbbbb flyway=26 minio=RELEASE.x images=i1,i2,i3 dump=dump.gz outcome=ok" \
  "${line#* }"
eq "log_line empty fields are -" "rollback prev=- target=- tag=- flyway=- minio=- images=- dump=- outcome=up" \
  "$(log_line rollback "" "" "" "" "" "" "" up | cut -d' ' -f2-)"

eq "sanitize strips escapes" "abc" "$(printf 'a\033[2Jb\007c' | sanitize)"

# ---------------------------------------------------------------------------

printf '%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
