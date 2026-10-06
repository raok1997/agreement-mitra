# shellcheck shell=bash
#
# Pure decision logic for deploy/deploy.sh, deploy/smoke-prod.sh and deploy/provision.sh.
# Sourced, never executed. Every function here decides over text it is handed; the callers do
# the I/O. Fixture-tested by deploy/test/checks.test.sh.
#
# PORTABLE: runs under bash 3.2 + BSD tools (the operator's Mac) and bash 5 + GNU (the server).
# So: POSIX ERE ([[:space:]], not \s), no `date -d`, no `mapfile`, no associative arrays, no
# ${v,,}, no `sed -i`. Regexes live in variables before `[[ =~ ]]`. Empty arrays are expanded as
# ${a[@]+"${a[@]}"} so `set -u` holds on 3.2.
#
# SECRETS: no function here prints an env value. env_value returns one for the caller to compare
# in-process; every other function prints key names, verdicts, tags or file names only.

# ---------------------------------------------------------------------------
# env
# ---------------------------------------------------------------------------

ENV_KEY_RE='^([A-Z][A-Z0-9_]*)='
ENV_NAME_RE='^[A-Z][A-Z0-9_]*$'

# env_keys <file>: the names assigned on lines matching ^[A-Z][A-Z0-9_]*=, in file order.
# `export KEY=`, an indented key and a lower-case key are not keys (compose's env_file does not
# take them either).
env_keys() {
  local line re="$ENV_KEY_RE"
  [ -f "$1" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    if [[ $line =~ $re ]]; then
      printf '%s\n' "${BASH_REMATCH[1]}"
    fi
  done <"$1"
}

# _has_line <needle> <newline-separated haystack>
_has_line() {
  case $'\n'"$2"$'\n' in
    *$'\n'"$1"$'\n'*) return 0 ;;
  esac
  return 1
}

# env_missing_keys <template> <target>: template keys the target does not assign.
env_missing_keys() {
  local have key
  have="$(env_keys "$2")"
  while IFS= read -r key; do
    [ -n "$key" ] || continue
    _has_line "$key" "$have" || printf '%s\n' "$key"
  done <<EOF
$(env_keys "$1")
EOF
}

# env_extra_keys <template> <target>: target keys the template does not assign.
env_extra_keys() {
  env_missing_keys "$2" "$1"
}

# env_merge_lines <template> <target>: the template's assignment lines for the missing keys.
env_merge_lines() {
  local missing line re="$ENV_KEY_RE"
  missing="$(env_missing_keys "$1" "$2")"
  [ -n "$missing" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    if [[ $line =~ $re ]] && _has_line "${BASH_REMATCH[1]}" "$missing"; then
      printf '%s\n' "$line"
    fi
  done <"$1"
}

# env_value <file> <key>: the value of the last assignment of <key>, with one pair of surrounding
# single or double quotes removed (compose's env_file reads 'x' and "x" as x). Read line by line,
# never sourced. Callers compare it in-process and never echo it. Returns 1 when the key is absent.
env_value() {
  local line found=1 value=""
  [ -f "$1" ] || return 1
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      "$2="*) value="${line#*=}"; found=0 ;;
    esac
  done <"$1"
  [ "$found" -eq 0 ] || return 1
  case "$value" in
    \'*\' | \"*\") [ "${#value}" -ge 2 ] && value="${value:1:${#value}-2}" ;;
  esac
  printf '%s' "$value"
}

# env_quote <value>: the form to write so compose's env_file reads the value literally. Unquoted
# (and double-quoted) values are interpolated ($), cut at " #" and stripped of surrounding quotes,
# so anything holding $, #, a quote, a backslash or edge whitespace is single-quoted. A value
# holding a single quote cannot be written literally and is refused (returns 1).
env_quote() {
  local v="$1" re='[$#"\\]|^[[:space:]]|[[:space:]]$'
  case "$v" in *"'"*) return 1 ;; esac
  if [[ $v =~ $re ]]; then
    printf "'%s'" "$v"
  else
    printf '%s' "$v"
  fi
}

# env_blank <value>: empty, whitespace only, "" or ''.
env_blank() {
  local v="$1" re='^[[:space:]]*$'
  [[ $v =~ $re ]] && return 0
  [ "$v" = '""' ] || [ "$v" = "''" ]
}

# env_placeholder <value>: a value that stands in for a secret nobody set. Case-insensitive.
env_placeholder() {
  local v re='^x+$'
  v="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  case "$v" in
    __generated_on_server__ | dev-only-* | change*me | placeholder | todo) return 0 ;;
  esac
  [[ $v =~ $re ]]
}

# env_tags <template>: one record per assigned key, in template order:
#   <key> <kind> <class> <need> <pattern|-> <match|->
# The tag is the `#@` line directly above the key. Lint failures go to stderr naming the key, and
# the function then returns 1 (records for the well-formed keys are still printed).
env_tags() {
  local file="$1" line tag="" rc=0 key kind class need pattern match word extra
  local key_re="$ENV_KEY_RE" tag_re='^#@[[:space:]]'
  local records="" tvals="" words=()
  [ -f "$file" ] || { printf 'env_tags: %s: no such template\n' "$file" >&2; return 1; }

  while IFS= read -r line || [ -n "$line" ]; do
    if [[ $line =~ $tag_re ]]; then
      [ -z "$tag" ] || { printf 'env_tags: %s: a #@ tag is not directly above a key\n' "$file" >&2; rc=1; }
      tag="$line"
      continue
    fi
    if [[ $line =~ $key_re ]]; then
      key="${BASH_REMATCH[1]}"
      if [ -z "$tag" ]; then
        printf 'env_tags: %s: %s has no #@ tag\n' "$file" "$key" >&2
        rc=1
        continue
      fi
      kind="" class="" need="" pattern="-" match="-" extra=""
      # read -a, not a bare $tag expansion: a pattern like [A-Za-z0-9] must never glob.
      read -r -a words <<<"${tag#\#@}"
      for word in ${words[@]+"${words[@]}"}; do
        case "$word" in
          secret | config) [ -z "$kind" ] && kind="$word" || extra="$word" ;;
          fixed | setting | generated | vendor) [ -z "$class" ] && class="$word" || extra="$word" ;;
          required | optional | required-if=*=*) [ -z "$need" ] && need="$word" || extra="$word" ;;
          pattern=?*) pattern="${word#pattern=}" ;;
          match=?*:?*) match="${word#match=}" ;;
          *) extra="$word" ;;
        esac
      done
      if [ -n "$extra" ] || [ -z "$kind" ] || [ -z "$class" ] || [ -z "$need" ]; then
        printf 'env_tags: %s: %s has a malformed #@ tag\n' "$file" "$key" >&2
        rc=1
      else
        records="${records}${key} ${kind} ${class} ${need} ${pattern} ${match}"$'\n'
        env_blank "${line#*=}" && tvals="${tvals}${key}"$'\n'
      fi
      tag=""
      continue
    fi
    if [ -n "$tag" ]; then
      printf 'env_tags: %s: a #@ tag is not directly above a key\n' "$file" >&2
      rc=1
      tag=""
    fi
  done <"$file"

  # Cross-key rules, over the records just built.
  local ckey cval ckind
  while read -r key kind class need pattern match; do
    [ -n "$key" ] || continue
    if [ "$class" = fixed ]; then
      [ "$kind" = config ] || { printf 'env_tags: %s: %s is fixed but not config\n' "$file" "$key" >&2; rc=1; }
      ! _has_line "$key" "$tvals" || { printf 'env_tags: %s: %s is fixed with a blank template value\n' "$file" "$key" >&2; rc=1; }
    fi
    if [ "$class" = generated ] && { [ "$kind" != secret ] || [ "$need" != required ]; }; then
      printf 'env_tags: %s: %s is generated but not secret required\n' "$file" "$key" >&2
      rc=1
    fi
    case "$need" in
      required-if=*)
        cval="${need#required-if=}"
        ckey="${cval%%=*}"
        ckind="$(printf '%s' "$records" | awk -v k="$ckey" '$1 == k { print $2 }')"
        if [ "$ckind" != config ]; then
          printf 'env_tags: %s: %s is required-if on %s, which is not a config key in this file\n' \
            "$file" "$key" "$ckey" >&2
          rc=1
        fi
        ;;
    esac
  done <<EOF
$records
EOF

  printf '%s' "$records"
  return "$rc"
}

# env_verdict <template> <target> [envdir]: one `<key> <verdict>` line per templated key, in
# template order. Verdicts: missing blank placeholder pattern drift mismatch (FAIL),
# blank-optional (INFO), ok. A `match=<file>:<KEY>` reads <envdir>/<file>, envdir defaulting to the
# target's directory. Returns 1 when any verdict is a FAIL, 2 when the template does not lint.
env_verdict() {
  local template="$1" target="$2" envdir="${3:-}" records rc=0
  local key kind class need pattern match value tval cond ckey cwant cval required mfile mkey mval
  [ -n "$envdir" ] || envdir="$(dirname "$target")"
  records="$(env_tags "$template")" || return 2

  while read -r key kind class need pattern match; do
    [ -n "$key" ] || continue
    if ! value="$(env_value "$target" "$key")"; then
      printf '%s missing\n' "$key"; rc=1; continue
    fi

    required=0
    case "$need" in
      required) required=1 ;;
      required-if=*)
        cond="${need#required-if=}"
        ckey="${cond%%=*}"
        cwant="${cond#*=}"
        cval="$(env_value "$target" "$ckey" || true)"
        [ "$cval" = "$cwant" ] && required=1
        ;;
    esac

    if env_blank "$value"; then
      if [ "$required" -eq 1 ]; then
        printf '%s blank\n' "$key"; rc=1
      else
        printf '%s blank-optional\n' "$key"
      fi
      continue
    fi
    if [ "$kind" = secret ] && env_placeholder "$value"; then
      printf '%s placeholder\n' "$key"; rc=1; continue
    fi
    if [ "$pattern" != "-" ] && ! [[ $value =~ $pattern ]]; then
      printf '%s pattern\n' "$key"; rc=1; continue
    fi
    if [ "$class" = fixed ]; then
      tval="$(env_value "$template" "$key" || true)"
      if [ "$value" != "$tval" ]; then
        printf '%s drift\n' "$key"; rc=1; continue
      fi
    fi
    if [ "$match" != "-" ]; then
      mfile="${match%%:*}"
      mkey="${match#*:}"
      mval="$(env_value "$envdir/$mfile" "$mkey" || true)"
      if [ "$value" != "$mval" ]; then
        printf '%s mismatch\n' "$key"; rc=1; continue
      fi
    fi
    printf '%s ok\n' "$key"
  done <<EOF
$records
EOF
  return "$rc"
}

# env_verdict_fails <verdict-lines>: the FAIL lines only.
env_verdict_fails() {
  printf '%s\n' "$1" | awk '$2 != "ok" && $2 != "blank-optional" && NF == 2'
}

# env_set <file> <key>: set <key> to the value in $ENV_SET_VALUE. The value travels in a variable,
# never argv. The file is rebuilt line by line (every other line byte-identical), written to a
# temp file in the same directory under umask 077, then renamed: 0600 and atomic. A value holding
# a newline or carriage return is refused.
env_set() {
  local file="$1" key="$2" value="${ENV_SET_VALUE-}" dir tmp line done=0 nl=$'\n' cr=$'\r'
  case "$value" in
    *"$nl"* | *"$cr"*) printf 'env_set: %s: value holds a line break; refused\n' "$key" >&2; return 1 ;;
  esac
  [[ $key =~ $ENV_NAME_RE ]] || { printf 'env_set: bad key name\n' >&2; return 1; }
  dir="$(dirname "$file")"
  tmp="$(umask 077 && mktemp "$dir/.env_set.XXXXXXXX")" || return 1
  {
    if [ -f "$file" ]; then
      while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
          "$key="*) printf '%s=%s\n' "$key" "$value"; done=1 ;;
          *) printf '%s\n' "$line" ;;
        esac
      done <"$file"
    fi
    [ "$done" -eq 1 ] || printf '%s=%s\n' "$key" "$value"
  } >"$tmp" || { rm -f -- "$tmp"; return 1; }
  chmod 600 "$tmp" && mv -- "$tmp" "$file" || { rm -f -- "$tmp"; return 1; }
}

# env_comment_block <template> <key>: the contiguous comment lines directly above the key, minus
# the `#@` tag. Template text only -- never a server value.
env_comment_block() {
  local line block=""
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      "#@"*) ;;
      "#"*) block="${block}${line}"$'\n' ;;
      "$2="*) printf '%s' "$block"; return 0 ;;
      *) block="" ;;
    esac
  done <"$1"
}

# env_fill <template> <target> [envdir]: the interactive half of `provision.sh secrets`.
# Walks every key whose verdict is not `ok`, in template order. On a TTY it asks: a secret without
# echo, a config with echo; Enter keeps an optional key blank; a pattern failure re-asks; a drift
# shows server -> template and applies on `y` only; a mismatch is reported, never resolved. Off a
# TTY it asks nothing. Prints a summary by name and returns 1 while any FAIL remains.
# Sets ENV_FILL_SET to the number of keys it wrote.
env_fill() {
  local template="$1" target="$2" envdir="${3:-}" verdicts records rec answer tty=0
  local key verdict kind class need pattern match tval
  local set_names="" optional_names="" failing="" again
  [ -t 0 ] && tty=1
  ENV_FILL_SET=0
  records="$(env_tags "$template")" || return 2
  verdicts="$(env_verdict "$template" "$target" ${envdir:+"$envdir"})" || true

  # The walk reads its list on fd 3, so every answer below comes from stdin (the terminal).
  while read -r key verdict <&3; do
    [ -n "$key" ] || continue
    [ "$verdict" != ok ] || continue
    rec="$(printf '%s\n' "$records" | awk -v k="$key" '$1 == k')"
    read -r _ kind class need pattern match <<EOF
$rec
EOF
    if [ "$tty" -eq 0 ]; then
      [ "$verdict" = blank-optional ] && optional_names="${optional_names} ${key}"
      continue
    fi

    case "$verdict" in
      mismatch)
        printf '\n%s: does not match %s. Never copied between files automatically:\n' "$key" "$match" >&2
        printf '  edit %s by hand, or regenerate both on a fresh box.\n' "$(basename "$target")" >&2
        continue
        ;;
      drift)
        tval="$(env_value "$template" "$key" || true)"
        printf '\n%s differs from the template (a fixed value):\n  server:   %s\n  template: %s\n' \
          "$key" "$(env_value "$target" "$key" || true)" "$tval" >&2
        printf 'Apply the template value? [y/N] ' >&2
        IFS= read -r answer || answer=""
        if { [ "$answer" = y ] || [ "$answer" = Y ]; } && tval="$(env_quote "$tval")"; then
          ENV_SET_VALUE="$tval" env_set "$target" "$key" && set_names="${set_names} ${key}"
        fi
        continue
        ;;
    esac

    printf '\n' >&2
    env_comment_block "$template" "$key" >&2
    again=1
    while [ "$again" -eq 1 ]; do
      again=0
      if [ "$kind" = secret ]; then
        printf '%s (input hidden%s): ' "$key" "$( [ "$need" = required ] || printf ', Enter to leave blank')" >&2
        IFS= read -rs answer || answer=""
        printf '\n' >&2
      else
        printf '%s%s: ' "$key" "$( [ "$need" = required ] || printf ' (Enter to leave blank)')" >&2
        IFS= read -r answer || answer=""
      fi
      if [ -z "$answer" ]; then
        break
      fi
      if [ "$pattern" != "-" ] && ! [[ $answer =~ $pattern ]]; then
        printf '%s: does not match its required format; try again.\n' "$key" >&2
        again=1
        continue
      fi
      if [ "$kind" = secret ] && env_placeholder "$answer"; then
        printf '%s: that is a placeholder; try again.\n' "$key" >&2
        again=1
        continue
      fi
      if ! answer="$(env_quote "$answer")"; then
        printf "%s: a value holding a single quote (') cannot be stored literally; try again.\n" "$key" >&2
        again=1
        continue
      fi
      ENV_SET_VALUE="$answer" env_set "$target" "$key" && set_names="${set_names} ${key}"
    done
    answer=""
  done 3<<EOF
$verdicts
EOF

  ENV_FILL_SET=$(printf '%s' "$set_names" | wc -w | tr -d ' ')
  verdicts="$(env_verdict "$template" "$target" ${envdir:+"$envdir"})" || true
  failing="$(env_verdict_fails "$verdicts")"
  optional_names="$(printf '%s\n' "$verdicts" | awk '$2 == "blank-optional" { printf " %s", $1 }')"

  printf '%s: set:%s\n' "$(basename "$target")" "${set_names:- none}"
  printf '%s: optional, left blank:%s\n' "$(basename "$target")" "${optional_names:- none}"
  if [ -n "$failing" ]; then
    printf '%s: still failing:\n' "$(basename "$target")"
    printf '%s\n' "$failing" | awk '{ printf "  %s: %s\n", $1, $2 }'
    return 1
  fi
  printf '%s: no failing keys\n' "$(basename "$target")"
}

# ---------------------------------------------------------------------------
# network
# ---------------------------------------------------------------------------

# ipam_from_compose <json>: `compose config --format json` -> "<subnet> <ip_range>" for the
# default network's first IPv4 entry, "-" for an absent field. Empty when nothing is declared.
ipam_from_compose() {
  printf '%s' "$1" | jq -r '
    [ (.networks.default.ipam.config // [])[] | select((.subnet // "") | test("^[0-9.]+/")) ][0]
    | if . == null then empty else "\(.subnet) \(.ip_range // "-")" end'
}

# ipam_from_inspect <json>: `network inspect -f '{{json .IPAM.Config}}'` -> "<subnet> <ip_range>".
ipam_from_inspect() {
  printf '%s' "$1" | jq -r '
    [ (. // [])[] | select((.Subnet // "") | test("^[0-9.]+/")) ][0]
    | if . == null then empty else "\(.Subnet) \(if (.IPRange // "") == "" then "-" else .IPRange end)" end'
}

# network_change <declared> <running>: 0 when the running network differs from the declaration.
# An empty running value (no network yet) is no change.
network_change() {
  [ -n "$2" ] || return 1
  [ "$1" != "$2" ]
}

# _ip4_int <a.b.c.d>
_ip4_int() {
  local IFS=.
  # shellcheck disable=SC2086
  set -- $1
  printf '%s' "$(( ($1 << 24) + ($2 << 16) + ($3 << 8) + $4 ))"
}

# cidr_overlap <a> <b>: 0 when two IPv4 CIDRs overlap. Empty or IPv6 input never overlaps.
cidr_overlap() {
  local re='^([0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3})/([0-9]{1,2})$'
  local a b pa pb p mask
  [[ $1 =~ $re ]] || return 1
  a="$(_ip4_int "${BASH_REMATCH[1]}")"; pa="${BASH_REMATCH[2]}"
  [[ $2 =~ $re ]] || return 1
  b="$(_ip4_int "${BASH_REMATCH[1]}")"; pb="${BASH_REMATCH[2]}"
  p=$(( pa < pb ? pa : pb ))
  mask=$(( p == 0 ? 0 : (0xFFFFFFFF << (32 - p)) & 0xFFFFFFFF ))
  [ $(( a & mask )) -eq $(( b & mask )) ]
}

# ---------------------------------------------------------------------------
# schema
# ---------------------------------------------------------------------------

# The highest successfully applied versioned migration. Run inside the postgres container.
# shellcheck disable=SC2034
DB_MAX_SQL='select coalesce(max(version::int), 0) from flyway_schema_history where success and version is not null'

# max_migration_version: `git ls-tree -r --name-only` output on stdin -> numeric max of V<n>__*.sql.
max_migration_version() {
  local path base max=0 re='^V([0-9]+)__.*\.sql$'
  while IFS= read -r path; do
    base="${path##*/}"
    if [[ $base =~ $re ]] && [ "$((10#${BASH_REMATCH[1]}))" -gt "$max" ]; then
      max=$((10#${BASH_REMATCH[1]}))
    fi
  done
  printf '%s\n' "$max"
}

# schema_gate <db-max> <ceiling>: 0 when the database is not ahead of the code.
schema_gate() {
  [ "${1:-0}" -le "$2" ]
}

# ---------------------------------------------------------------------------
# dumps
# ---------------------------------------------------------------------------

DUMP_RE='^pre-deploy-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}(-r[0-9]{12})?\.sql\.gz$'

# dump_name <YYYYMMDDTHHMMSSZ> <tag>
dump_name() {
  printf 'pre-deploy-%s-%s.sql.gz\n' "$1" "$2"
}

# is_script_dump <name>
is_script_dump() {
  [[ $1 =~ $DUMP_RE ]]
}

# dump_verdict <gzip-ok 0|1> <copy-count> <tail-text> <pipestatus "a b">: prints ok or the reason.
dump_verdict() {
  local ps
  for ps in $4; do
    [ "$ps" = 0 ] || { printf 'pipeline failed (%s)\n' "$4"; return 1; }
  done
  [ "$1" = 0 ] || { printf 'not valid gzip\n'; return 1; }
  [ "${2:-0}" -gt 0 ] 2>/dev/null || { printf 'no COPY section\n'; return 1; }
  case "$3" in
    *"-- PostgreSQL database dump complete"*) ;;
    *) printf 'completion trailer missing\n'; return 1 ;;
  esac
  printf 'ok\n'
}

# rotation_victims <cutoff YYYYMMDDTHHMMSSZ> <just-taken> <protected names, newline-separated>
#   listing on stdin -> names to delete. Considers script dumps only; keeps the ten newest, deletes
#   any older than the cutoff, never the just-taken or a protected name.
rotation_victims() {
  local cutoff="$1" taken="$2" protected="$3" name stamp n=0 sorted
  sorted="$(while IFS= read -r name; do
    is_script_dump "$name" && printf '%s\n' "$name"
  done | sort -r)"
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    n=$((n + 1))
    stamp="${name#pre-deploy-}"
    stamp="${stamp%%-*}"
    [ "$name" != "$taken" ] || continue
    ! _has_line "$name" "$protected" || continue
    if [ "$n" -gt 10 ] || [[ $stamp < $cutoff ]]; then
      printf '%s\n' "$name"
    fi
  done <<EOF
$sorted
EOF
}

# ---------------------------------------------------------------------------
# history (deploys.log)
#
#   <UTC> <seed|deploy|rollback|accept> prev=<tag> target=<full> tag=<tag> flyway=<n>
#     minio=<release> images=<b,c,g> dump=<file|-> outcome=<ok|step>
# ---------------------------------------------------------------------------

# log_field <line> <name>: the value of name=... on a log line.
log_field() {
  local f
  for f in $1; do
    case "$f" in
      "$2="*) printf '%s' "${f#*=}"; return 0 ;;
    esac
  done
  return 1
}

# log_line <action> <prev> <full> <tag> <flyway> <minio> <images> <dump> <outcome>: one log line,
# stamped now (UTC).
log_line() {
  printf '%s %s prev=%s target=%s tag=%s flyway=%s minio=%s images=%s dump=%s outcome=%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "${2:--}" "${3:--}" "${4:--}" "${5:--}" "${6:--}" \
    "${7:--}" "${8:--}" "${9:--}"
}

# history <log>: replays the `ok` lines into the running-version stack, bottom to top.
history() {
  local line action tag outcome stack=() n top
  [ -f "$1" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    outcome="$(log_field "$line" outcome || true)"
    [ "$outcome" = ok ] || continue
    action="$(printf '%s' "$line" | awk '{ print $2 }')"
    tag="$(log_field "$line" tag || true)"
    [ -n "$tag" ] || continue
    n=${#stack[@]}
    # An accept of a tag already in the history is an accepted rollback: pop to it.
    if [ "$action" = accept ] && _has_line "$tag" "$(for top in ${stack[@]+"${stack[@]}"}; do printf '%s\n' "$top"; done)"; then
      action=rollback
    fi
    case "$action" in
      seed | deploy | accept)
        if [ "$n" -eq 0 ] || [ "${stack[$((n - 1))]}" != "$tag" ]; then
          stack[n]="$tag"
        fi
        ;;
      rollback)
        while [ "$n" -gt 0 ] && [ "${stack[$((n - 1))]}" != "$tag" ]; do
          unset "stack[$((n - 1))]"
          n=$((n - 1))
        done
        [ "$n" -gt 0 ] || stack[0]="$tag"
        ;;
    esac
  done <"$1"
  for top in ${stack[@]+"${stack[@]}"}; do
    printf '%s\n' "$top"
  done
}

# rollback_target <log> <running-tag>: the tag to roll back to, or nothing.
rollback_target() {
  local h top
  h="$(history "$1")"
  [ -n "$h" ] || return 0
  top="$(printf '%s\n' "$h" | tail -n 1)"
  if [ "$top" = "$2" ]; then
    printf '%s\n' "$h" | sed '$d' | tail -n 1
  else
    printf '%s\n' "$top"
  fi
}

# _last_push_line <log> <tag>: the last ok seed/deploy/accept line for a tag.
_last_push_line() {
  awk -v t="tag=$2" '($2 == "seed" || $2 == "deploy" || $2 == "accept") && / outcome=ok$/ {
    for (i = 3; i <= NF; i++) if ($i == t) l = $0 } END { if (l != "") print l }' "$1"
}

# _seed_line <log> <tag>: the ok seed line for a tag, if the tag was seeded. Decided from the seed
# line itself, not the last push: an accepted rollback to the seed writes a later accept line.
_seed_line() {
  awk -v t="tag=$2" '$2 == "seed" && / outcome=ok$/ { for (i = 3; i <= NF; i++) if ($i == t) l = $0 }
    END { if (l != "") print l }' "$1"
}

# rollback_ceiling <log> <target>: a seed's recorded flyway=, or nothing ("use the tree").
rollback_ceiling() {
  local line
  line="$(_seed_line "$1" "$2")"
  [ -z "$line" ] || { log_field "$line" flyway || true; printf '\n'; }
}

# rollback_target_full <log> <target>: the target= hash recorded with a tag.
rollback_target_full() {
  log_field "$(_last_push_line "$1" "$2")" target
}

# rollback_is_seed <log> <target>
rollback_is_seed() {
  [ -n "$(_seed_line "$1" "$2")" ]
}

# rollback_images <log> <target>: the image IDs recorded with a tag (b,c,g).
rollback_images() {
  log_field "$(_last_push_line "$1" "$2")" images
}

# restore_dump_for <log> <ceiling>: the dump on the first line, whatever its outcome, whose
# flyway= exceeds the ceiling -- the restore point taken before the migration that blocks the
# rollback. A deploy that migrated and then failed a post-check counts: its dump is the one needed.
restore_dump_for() {
  local line fw dump
  [ -f "$1" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    fw="$(log_field "$line" flyway || true)"
    dump="$(log_field "$line" dump || true)"
    case "$fw" in '' | *[!0-9]*) continue ;; esac
    if [ "$fw" -gt "$2" ] && [ -n "$dump" ] && [ "$dump" != "-" ]; then
      printf '%s\n' "$dump"
      return 0
    fi
  done <"$1"
}

# image_keep_set <log> <running-tag>: running plus the top three distinct history entries.
image_keep_set() {
  {
    [ -z "$2" ] || [ "$2" = unknown ] || printf '%s\n' "$2"
    history "$1" | awk '{ a[NR] = $0 } END { for (i = NR; i > 0; i--) print a[i] }' \
      | awk '!seen[$0]++' | head -n 3
  } | awk '!seen[$0]++'
}

# protected_dumps <log> <keep-set, newline-separated>: dumps recorded on lines whose tag is kept.
protected_dumps() {
  local line tag dump
  [ -f "$1" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    tag="$(log_field "$line" tag || true)"
    dump="$(log_field "$line" dump || true)"
    if [ -n "$tag" ] && _has_line "$tag" "$2" && [ -n "$dump" ] && [ "$dump" != "-" ]; then
      printf '%s\n' "$dump"
    fi
  done <"$1"
}

# accept_allowed <log> <running-tag>: the last line is a deploy or rollback to the running tag that
# failed only the ERROR scan.
accept_allowed() {
  local last action
  [ -f "$1" ] || return 1
  last="$(tail -n 1 "$1")"
  action="$(printf '%s' "$last" | awk '{ print $2 }')"
  [ "$action" = deploy ] || [ "$action" = rollback ] || return 1
  [ "$(log_field "$last" tag || true)" = "$2" ] || return 1
  [ "$(log_field "$last" outcome || true)" = postcheck:errors ]
}

# ---------------------------------------------------------------------------
# text
# ---------------------------------------------------------------------------

TAG_RE='^[0-9a-f]{12}(-r[0-9]{12})?$'

# running_tag_from_image <image-ref> [service]: the deploy tag, or `unknown`.
running_tag_from_image() {
  local re="^agreementmitra-${2:-backend}:([0-9a-f]{12}(-r[0-9]{12})?)\$"
  if [[ $1 =~ $re ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  else
    printf 'unknown\n'
  fi
}

# running_tag_of <backend-tag> <caddy-tag> <gotenberg-tag>: the running tag when all three built
# services agree, `unknown` when none carries a tag (pre-script images), otherwise `mixed` (a
# deploy that failed part-way through `up`).
running_tag_of() {
  if [ "$1" = "$2" ] && [ "$1" = "$3" ]; then
    printf '%s\n' "$1"
  elif [ "$1$2$3" = unknownunknownunknown ]; then
    printf 'unknown\n'
  else
    printf 'mixed\n'
  fi
}

# images_match <recorded b,c,g ids> <present b,c,g ids>: 0 when every recorded image is present
# and unchanged; otherwise prints the 1-based position of the first that is missing or differs.
images_match() {
  local i=1 want got
  [ -n "$1" ] || { printf '1\n'; return 1; }
  while [ "$i" -le 3 ]; do
    want="$(printf '%s' "$1" | cut -d, -f"$i")"
    got="$(printf '%s' "$2" | cut -d, -f"$i")"
    if [ -z "$want" ] || [ -z "$got" ] || [ "$want" != "$got" ]; then
      printf '%s\n' "$i"
      return 1
    fi
    i=$((i + 1))
  done
}

# minio_release <`minio --version` output>: the single RELEASE.<stamp>Z token, or nothing.
minio_release() {
  printf '%s\n' "$1" | grep -oE 'RELEASE\.[0-9T-]+Z' | head -n 1
}

# minio_changed <before> <after>: 0 when the release changed. An unreadable side counts as changed.
minio_changed() {
  [ -n "$1" ] && [ -n "$2" ] || return 0
  [ "$1" != "$2" ]
}

# error_loggers: Spring Boot console log text on stdin -> one sanitised logger name per line logged
# at level ERROR. The level is matched as the second column of a timestamped line, so a message
# that merely contains " ERROR " does not count. Messages are never printed.
error_loggers() {
  local line logger
  local re='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[^[:space:]]+[[:space:]]+ERROR[[:space:]]'
  # Boot pads the logger column, so the name is the first token followed by spaces and ": ".
  local logger_re='[[:space:]]([^[:space:]]+)[[:space:]]+:[[:space:]]'
  local name_re='^[A-Za-z0-9_.$]+$'
  while IFS= read -r line || [ -n "$line" ]; do
    [[ $line =~ $re ]] || continue
    logger="?"
    if [[ $line =~ $logger_re ]]; then
      logger="${BASH_REMATCH[1]}"
    fi
    [[ $logger =~ $name_re ]] || logger="?"
    printf '%s\n' "$logger"
  done
}

# valid_ipv4 <s>: a dotted quad, every octet <= 255, nothing else.
valid_ipv4() {
  local re='^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$' i
  [[ $1 =~ $re ]] || return 1
  for i in 1 2 3 4; do
    [ "$((10#${BASH_REMATCH[$i]}))" -le 255 ] || return 1
  done
}

# slash24 <ipv4>: a.b.c.0/24, the form the lockout event logs.
slash24() {
  printf '%s.0/24\n' "${1%.*}"
}

# forged_verdict <matched sources, newline-separated> <caller24> <forged24>: prints PASS, FAIL or
# INCONCLUSIVE; returns 0 only on PASS.
forged_verdict() {
  if _has_line "$3" "$1"; then
    printf 'FAIL\n'; return 1
  fi
  if _has_line "$2" "$1"; then
    printf 'PASS\n'; return 0
  fi
  printf 'INCONCLUSIVE\n'
  return 2
}

# valid_hash <arg>: 7-40 lower-case hex, nothing that git could read as an option or a ref name.
valid_hash() {
  local re='^[0-9a-f]{7,40}$'
  [[ $1 =~ $re ]]
}

# valid_tag_line <line>: the only line deploy/.env may hold.
valid_tag_line() {
  local re='^DEPLOY_TAG=[0-9a-f]{12}(-r[0-9]{12})?$'
  [[ $1 =~ $re ]]
}

# sanitize: strip ANSI escape sequences and control characters (keeps tab and newline) from
# server-derived text before it reaches a terminal.
sanitize() {
  local esc
  esc="$(printf '\033')"
  LC_ALL=C sed "s/${esc}\[[0-9;?]*[A-Za-z]//g" | LC_ALL=C tr -d '\000-\010\013-\037\177'
}
