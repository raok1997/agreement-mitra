#!/usr/bin/env bash
#
# Deploy a commit from `main` to production, roll back, or accept a deploy. Run as root on the
# VPS, inside tmux (docs/DEPLOYMENT.md section 4):
#
#   deploy/deploy.sh [--dry-run] [--allow-downtime] [--allow-downgrade] [--refresh-base] <hash>
#   deploy/deploy.sh [--allow-downtime] rollback
#   deploy/deploy.sh accept
#
# Bootstrap (no trust in the checkout's copy):
#   cd /opt/agreementmitra && git fetch origin && git show origin/main:deploy/deploy.sh | bash -s -- <hash>
#
# TRUST: whichever copy is invoked, the logic that decides trust is always origin/main's head
# (the entry re-executes it). Merge rights on `main` are therefore root on production.
#
# STAGES (design D2):
#   entry   self-contained, sources nothing: root, ownership, fetch, then re-exec main's copy.
#   stage 1 main's head code: verifies the target commit, extracts its deploy files to
#           /tmp/agreementmitra-deploy.XXXXXXXX and execs them. Runs rollback and accept itself.
#   stage 2 the TARGET's code (--stage2): preflight, seed, dump, checkout, build, up, post-checks.
#           Stable contract: --stage2, the flag set, one full hash, the work-dir layout.
#
# IMPLEMENTATION TRAPS (set -e): `if ! f; then` disables errexit inside f, and `local v=$(cmd)`
# masks cmd's failure -- declare locals first, assign on their own line.

set -euo pipefail
export DOCKER_BUILDKIT=1

readonly CHECKOUT=/opt/agreementmitra
readonly STATE_DIR=/var/lib/agreementmitra
readonly STATE_LOG=$STATE_DIR/deploys.log
readonly RUN_DIR=/run/agreementmitra
readonly LOCK_FILE=$RUN_DIR/deploy.lock
readonly BACKUPS=/root/backups
readonly CADDY_IP=10.203.17.10
readonly NETWORK=agreementmitra_default
readonly BUILT_SERVICES="backend caddy gotenberg"
readonly ENV_NAMES="backend postgres minio web-build"
readonly MIGRATIONS=backend/src/main/resources/db/migration
readonly WORK_RE='^/tmp/agreementmitra-deploy\.[A-Za-z0-9]{8}$'
readonly MAIN_RE='^/tmp/agreementmitra-main\.[A-Za-z0-9]{8}$'

say()  { printf '[deploy] %s\n' "$*"; }
pass() { printf '[deploy] PASS %s\n' "$*"; }
warn() { printf '[deploy] WARNING: %s\n' "$*" >&2; }
die()  { printf '[deploy] FAIL %s\n' "$*" >&2; exit 1; }

usage() {
  cat >&2 <<'EOF'
usage: deploy/deploy.sh [--dry-run] [--allow-downtime] [--allow-downgrade] [--refresh-base] <hash>
       deploy/deploy.sh [--allow-downtime] rollback
       deploy/deploy.sh accept
EOF
  exit 2
}

# Git with hooks disabled: .git is root-owned and checked, but a hook is still code we never need.
g() { git -c core.hooksPath=/dev/null -C "$CHECKOUT" "$@"; }

# ---------------------------------------------------------------------------
# arguments
# ---------------------------------------------------------------------------

ACTION=deploy DRY_RUN=0 ALLOW_DOWNTIME=0 ALLOW_DOWNGRADE=0 REFRESH_BASE=0 FROM_MAIN=0 ARG=""

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --dry-run) DRY_RUN=1 ;;
      --allow-downtime) ALLOW_DOWNTIME=1 ;;
      --allow-downgrade) ALLOW_DOWNGRADE=1 ;;
      --refresh-base) REFRESH_BASE=1 ;;
      --from-main) FROM_MAIN=1 ;;
      rollback | accept)
        [ -z "$ARG" ] && [ "$ACTION" = deploy ] || usage
        ACTION="$1"
        ;;
      -*) usage ;;
      *)
        [ -z "$ARG" ] || usage
        ARG="$1"
        ;;
    esac
    shift
  done
  if [ "$ACTION" = deploy ]; then
    [ -n "$ARG" ] || usage
  else
    [ -z "$ARG" ] && [ "$DRY_RUN$ALLOW_DOWNGRADE$REFRESH_BASE" = 000 ] || usage
    [ "$ACTION" = rollback ] || [ "$ALLOW_DOWNTIME" -eq 0 ] || usage
  fi
}

flags() {
  [ "$DRY_RUN" -eq 0 ] || printf '%s\n' --dry-run
  [ "$ALLOW_DOWNTIME" -eq 0 ] || printf '%s\n' --allow-downtime
  [ "$ALLOW_DOWNGRADE" -eq 0 ] || printf '%s\n' --allow-downgrade
  [ "$REFRESH_BASE" -eq 0 ] || printf '%s\n' --refresh-base
}

# ---------------------------------------------------------------------------
# entry: self-contained, sources nothing
# ---------------------------------------------------------------------------

entry() {
  local hex_re='^[0-9a-f]{7,40}$' unsafe self main_dir
  parse_args "$@"
  if [ "$ACTION" = deploy ] && ! [[ $ARG =~ $hex_re ]]; then
    die "the commit must be 7-40 lower-case hex characters"
  fi

  [ "$(id -u)" -eq 0 ] || die "must run as root"
  [ -d "$CHECKOUT/.git" ] || die "$CHECKOUT is not a git checkout"
  cd "$CHECKOUT"
  # .git/config and hooks run code as root on any git call, so nobody else may write them.
  unsafe="$(find "$CHECKOUT" "$CHECKOUT/.git" "$CHECKOUT/.git/config" "$CHECKOUT/.git/hooks" -maxdepth 0 \
    \( ! -user root -o -perm -g+w -o -perm -o+w \) -print)"
  [ -z "$unsafe" ] || die "not root-owned or group/world-writable: $unsafe"

  if [ "$FROM_MAIN" -eq 0 ] && [ -z "${TMUX:-}" ] && [ -z "${STY:-}" ]; then
    warn "not inside tmux or screen: a dropped SSH session kills the deploy mid-way"
  fi

  g fetch --quiet origin || die "git fetch origin failed"

  self="${BASH_SOURCE[0]:-}"
  if [ "$FROM_MAIN" -eq 1 ]; then
    # Second pass: this must BE main's head, or something is rewriting it under us.
    [ -f "$self" ] && g show origin/main:deploy/deploy.sh | cmp -s - "$self" \
      || die "re-executed copy differs from origin/main:deploy/deploy.sh; refusing to loop"
    stage1
    return
  fi

  main_dir="$(mktemp -d /tmp/agreementmitra-main.XXXXXXXX)"
  mkdir -p "$main_dir/lib"
  g show origin/main:deploy/deploy.sh >"$main_dir/deploy.sh"
  g show origin/main:deploy/lib/checks.sh >"$main_dir/lib/checks.sh"
  say "running origin/main's deploy logic"
  if [ "$ACTION" = deploy ]; then
    # shellcheck disable=SC2046
    exec bash "$main_dir/deploy.sh" --from-main $(flags) "$ARG"
  fi
  # shellcheck disable=SC2046
  exec bash "$main_dir/deploy.sh" --from-main $(flags) "$ACTION"
}

# ---------------------------------------------------------------------------
# shared by stage 1 and stage 2
# ---------------------------------------------------------------------------

DC_FILE="" DC_PROJECT="$CHECKOUT/deploy"
dc() { docker compose -f "$DC_FILE" --project-directory "$DC_PROJECT" "$@"; }

take_lock() {
  mkdir -p "$RUN_DIR"
  chmod 700 "$RUN_DIR"
  [ -z "$(find "$RUN_DIR" -maxdepth 0 \( ! -user root -o -perm -g+r -o -perm -o+r \) -print)" ] \
    || die "$RUN_DIR is not root-owned 0700"
  exec 9>"$LOCK_FILE"
  flock -n 9 || die "another deploy, rollback or accept is running (lock $LOCK_FILE)"
}

ensure_state() {
  mkdir -p "$STATE_DIR"
  chmod 750 "$STATE_DIR"
  [ -z "$(find "$STATE_DIR" -maxdepth 0 ! -user root -print)" ] || die "$STATE_DIR is not root-owned"
  [ -f "$STATE_LOG" ] || (umask 027 && : >"$STATE_LOG")
  chmod 640 "$STATE_LOG"
}

container() { dc ps -q "$1"; }

# The running tag, read from the built services' container image references: the tag when all
# three agree, `unknown` for pre-script :latest images, `mixed` after a deploy that failed part-way
# through `up` (some services recreated, some not).
running_tag() {
  local svc cid ref tags=""
  for svc in $BUILT_SERVICES; do
    cid="$(container "$svc" 2>/dev/null || true)"
    ref=""
    [ -z "$cid" ] || ref="$(docker inspect -f '{{.Config.Image}}' "$cid")"
    tags="$tags $(running_tag_from_image "$ref" "$svc")"
  done
  # shellcheck disable=SC2086
  running_tag_of $tags
}

# write_tag <tag>: deploy/.env holds exactly DEPLOY_TAG=<tag>, written atomically before every up.
write_tag() {
  local tmp
  valid_tag_line "DEPLOY_TAG=$1" || die "refusing to write a malformed tag"
  tmp="$(umask 077 && mktemp "$CHECKOUT/deploy/.env.XXXXXXXX")"
  printf 'DEPLOY_TAG=%s\n' "$1" >"$tmp"
  chmod 600 "$tmp"
  mv -- "$tmp" "$CHECKOUT/deploy/.env"
  export DEPLOY_TAG="$1"
}

psql_q() {
  dc exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$1"' sh "$1"
}

db_max() { psql_q "$DB_MAX_SQL" | tr -d '[:space:]'; }

tree_max() { g ls-tree -r --name-only "$1" -- "$MIGRATIONS" | max_migration_version; }

minio_now() {
  local out
  out="$(dc exec -T minio minio --version 2>/dev/null || true)"
  minio_release "$out"
}

image_ids() {
  local svc id ids=""
  for svc in $BUILT_SERVICES; do
    id="$(docker image inspect -f '{{.Id}}' "agreementmitra-$svc:$1" 2>/dev/null || true)"
    ids="${ids:+$ids,}${id#sha256:}"
  done
  printf '%s\n' "$ids"
}

postgres_running() {
  local cid state
  cid="$(container postgres 2>/dev/null || true)"
  [ -n "$cid" ] || return 1
  state="$(docker inspect -f '{{.State.Status}}' "$cid")"
  [ "$state" = running ]
}

clean_tree() {
  local dirty
  dirty="$(g status --porcelain --untracked-files=no | sanitize)"
  [ -z "$dirty" ] || die "the deploy checkout has uncommitted changes:"$'\n'"$dirty"
  pass "deploy checkout is clean"
}

# --- post-checks (D12); each sets STEP to its outcome name before it can fail ---

postcheck_migrations() {
  local want="$1" have failed
  STEP=postcheck:migrations
  have="$(db_max)"
  failed="$(psql_q 'select count(*) from flyway_schema_history where not success' | tr -d '[:space:]')"
  [ "${failed:-0}" = 0 ] || warn "flyway_schema_history holds $failed failed migration row(s)"
  [ "$have" = "$want" ] || die "database is at migration $have, expected $want"
  pass "database at migration $have"
}

postcheck_caddy() {
  local ip
  STEP=postcheck:caddy
  ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(container caddy)")"
  [ "$ip" = "$CADDY_IP" ] || die "caddy is at '$(printf '%s' "$ip" | sanitize)', not $CADDY_IP (forwarded-header trust depends on it)"
  pass "caddy holds $CADDY_IP"
}

postcheck_minio() {
  local before="$1" after
  STEP=postcheck:minio
  after="$(minio_now)"
  ! minio_changed "$before" "$after" || die "MinIO release changed: ${before:-unreadable} -> ${after:-unreadable}"
  pass "MinIO release unchanged ($after)"
}

postcheck_errors() {
  local cid started loggers count
  STEP=postcheck:errors
  cid="$(container backend)"
  started="$(docker inspect -f '{{.State.StartedAt}}' "$cid")"
  loggers="$(dc logs --no-log-prefix --no-color --since "$started" backend 2>/dev/null | error_loggers)"
  if [ -n "$loggers" ]; then
    count="$(printf '%s\n' "$loggers" | wc -l | tr -d ' ')"
    die "backend logged $count ERROR line(s) since start, from: $(printf '%s\n' "$loggers" | sort -u | tr '\n' ' ')
       If they are benign, record the deploy with: deploy/deploy.sh accept"
  fi
  pass "no ERROR lines since backend start"
}

# --- run record (D10) ---

LOG_ARMED=0 LOGGED=0 STEP=preflight
L_PREV="" L_FULL="" L_TAG="" L_MINIO="" L_DUMP="" WORK=""

record() {
  local outcome="$1" flyway images
  flyway="$(db_max 2>/dev/null || true)"
  images="$(image_ids "$(running_tag)" 2>/dev/null || true)"
  log_line "$ACTION" "$L_PREV" "$L_FULL" "$L_TAG" "$flyway" "$L_MINIO" "$images" "$L_DUMP" "$outcome" >>"$STATE_LOG"
  LOGGED=1
}

on_exit() {
  local rc=$?
  set +e
  if [ "$LOG_ARMED" -eq 1 ] && [ "$LOGGED" -eq 0 ]; then
    if [ "$rc" -eq 0 ]; then record ok; else record "$STEP"; fi
    if [ "$rc" -ne 0 ]; then
      case "$STEP" in
        up | postcheck:*)
          warn "the stack may be running the new tag. To go back: cd $CHECKOUT && deploy/deploy.sh rollback"
          ;;
      esac
    fi
  fi
  if [ -n "$WORK" ] && [[ $WORK =~ $WORK_RE || $WORK =~ $MAIN_RE ]]; then
    rm -rf -- "$WORK"
  fi
  exit "$rc"
}

# ---------------------------------------------------------------------------
# stage 1: origin/main's head code
# ---------------------------------------------------------------------------

stage1() {
  local here
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
  [[ $here =~ $MAIN_RE ]] || die "stage 1 runs only from a re-executed copy of main"
  WORK="$here"
  trap on_exit EXIT
  trap 'exit 130' INT HUP TERM
  # shellcheck source=lib/checks.sh
  . "$here/lib/checks.sh"
  DC_FILE="$CHECKOUT/deploy/docker-compose.prod.yml"

  case "$ACTION" in
    deploy) stage1_deploy ;;
    rollback) rollback ;;
    accept) accept ;;
  esac
}

stage1_deploy() {
  local full work f
  full="$(g rev-parse --verify --quiet --end-of-options "$ARG^{commit}")" || die "$ARG is not a commit"
  case "$full" in "$ARG"*) ;; *) die "$ARG resolved to a different object; refusing" ;; esac
  g merge-base --is-ancestor -- "$full" origin/main || die "$full is not on origin/main"
  g cat-file -e "$full:deploy/deploy.sh" 2>/dev/null \
    || die "target predates scripted deploy; use deploy/deploy.sh rollback"

  work="$(mktemp -d /tmp/agreementmitra-deploy.XXXXXXXX)"
  mkdir -p "$work/lib" "$work/env"
  g show "$full:deploy/deploy.sh" >"$work/deploy.sh"
  g show "$full:deploy/lib/checks.sh" >"$work/lib/checks.sh"
  g show "$full:deploy/docker-compose.prod.yml" >"$work/docker-compose.prod.yml"
  for f in $ENV_NAMES; do
    g show "$full:deploy/env/$f.env.example" >"$work/env/$f.env.example"
  done
  say "verified $full is on origin/main; handing over to its deploy code"
  rm -rf -- "$WORK"
  WORK=""
  trap - EXIT
  # shellcheck disable=SC2046
  exec bash "$work/deploy.sh" --stage2 $(flags) "$full" </dev/null
}

# ---------------------------------------------------------------------------
# stage 2: the target's code
# ---------------------------------------------------------------------------

FULL="" TAG="" RUNNING="" RUNNING_FULL="" TREE_MAX="" NET_CHANGED=0

stage2() {
  local here
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
  # Before anything else: never run from the checkout, never install a cleanup over deploy/.
  [[ $here =~ $WORK_RE ]] || die "stage 2 runs only from a deploy work directory"
  WORK="$here"
  trap on_exit EXIT
  trap 'exit 130' INT HUP TERM
  # shellcheck source=lib/checks.sh
  . "$here/lib/checks.sh"

  parse_args "$@"
  [ "$ACTION" = deploy ] && [ "$FROM_MAIN" -eq 0 ] || die "stage 2 deploys only"
  FULL="$ARG"
  [[ $FULL =~ ^[0-9a-f]{40}$ ]] || die "stage 2 needs a full commit hash"
  [ "$(id -u)" -eq 0 ] || die "must run as root"
  cd "$CHECKOUT"
  g merge-base --is-ancestor -- "$FULL" origin/main || die "$FULL is not on origin/main"

  take_lock

  DC_FILE="$WORK/docker-compose.prod.yml"
  RUNNING="$(DEPLOY_TAG=000000000000 running_tag)"
  [ "$RUNNING" != mixed ] \
    || die "the built services run different tags (a deploy failed part-way); run deploy/deploy.sh rollback first"
  # A placeholder satisfies compose's ${DEPLOY_TAG:?} for ps/exec/logs before any deploy/.env.
  if [ "$RUNNING" = unknown ]; then export DEPLOY_TAG=000000000000; else export DEPLOY_TAG="$RUNNING"; fi
  TAG="${FULL:0:12}"

  preflight
  [ "$REFRESH_BASE" -eq 0 ] || TAG="$TAG-r$(date -u +%Y%m%d%H%M)"
  # A build must never land on the running tag (two refreshes in one minute would).
  [ "$TAG" != "$RUNNING" ] || die "$TAG is the running tag; wait a minute and retry"
  if [ "$DRY_RUN" -eq 1 ]; then
    say "dry run: every check passed; nothing was changed"
    return 0
  fi

  ensure_state
  LOG_ARMED=1
  L_PREV="$RUNNING" L_FULL="$FULL"
  L_TAG="$TAG"

  seed_if_first
  dump
  build
  start
  STEP=postcheck
  postcheck_migrations "$TREE_MAX"
  postcheck_caddy
  postcheck_minio "$L_MINIO"
  postcheck_errors

  STEP=ok
  record ok
  prune_images
  say "deployed $FULL as $TAG (migration $TREE_MAX)"
  say "now make one real storage write: start an agreement at /start and press Generate --"
  say "a healthy stack does not yet prove MinIO writes work (storage-health-indicator)"
}

preflight() {
  local out f template verdicts fails info extra declared running_net nets name subnets s
  local dbmax

  STEP=preflight
  clean_tree

  out="$(g status --porcelain --ignored --untracked-files=all -- backend frontend docker | sanitize)"
  [ -z "$out" ] || die "untracked or ignored files in a build context would ship:"$'\n'"$out"
  pass "build contexts hold only tracked files"

  out="$(g ls-tree -r --name-only "$FULL" -- deploy/env deploy/.env deploy/certs \
    | grep -E '^deploy/env/[^/]*\.env$|^deploy/\.env$|^deploy/certs/' | sanitize || true)"
  [ -z "$out" ] || die "the target tracks production-only paths a checkout would overwrite:"$'\n'"$out"
  pass "target tracks no production-only paths"

  # Env contract (D14), against the TARGET's templates. Names and verdicts only, never a value.
  fails=""
  for f in $ENV_NAMES; do
    template="$WORK/env/$f.env.example"
    env_tags "$template" >/dev/null || die "the target's $f.env.example has an untagged or malformed key"
    [ -f "$CHECKOUT/deploy/env/$f.env" ] || { fails="$fails $f.env:absent"; continue; }
    verdicts="$(env_verdict "$template" "$CHECKOUT/deploy/env/$f.env" "$CHECKOUT/deploy/env" || true)"
    out="$(env_verdict_fails "$verdicts")"
    [ -z "$out" ] || fails="$fails"$'\n'"$(printf '%s\n' "$out" | awk -v f="$f.env" '{ printf "  %s %s: %s\n", f, $1, $2 }')"
    info="$(printf '%s\n' "$verdicts" | awk '$2 == "blank-optional" { printf " %s", $1 }')"
    [ -z "$info" ] || say "INFO $f.env optional, blank:$info"
    extra="$(env_extra_keys "$template" "$CHECKOUT/deploy/env/$f.env" | grep -E "$ENV_NAME_RE" | tr '\n' ' ' || true)"
    [ -z "$extra" ] || warn "$f.env sets keys its template does not: $extra"
  done
  if [ -n "$fails" ]; then
    printf '[deploy] FAIL env contract:%s\n' "$fails" >&2
    cat >&2 <<EOF
[deploy] Fix on this box against the TARGET's templates, then re-run with --dry-run:
  d=/root/templates.$TAG && mkdir -p \$d && for f in $ENV_NAMES; do git -C $CHECKOUT show $FULL:deploy/env/\$f.env.example > \$d/\$f.env.example; done
  cd $CHECKOUT/deploy && ./provision.sh secrets --templates \$d
EOF
    exit 1
  fi
  pass "env contract holds for $ENV_NAMES"

  # Network (D5): collision with any other network, and a change to ours.
  declared="$(ipam_from_compose "$(dc config --no-env-resolution --format json)")"
  [ -n "$declared" ] || die "the target's compose declares no IPv4 subnet"
  running_net="$(ipam_from_inspect "$(docker network inspect -f '{{json .IPAM.Config}}' "$NETWORK" 2>/dev/null || true)")"
  nets="$(docker network ls -q)"
  for name in $nets; do
    out="$(docker network inspect -f '{{.Name}}' "$name")"
    [ "$out" != "$NETWORK" ] || continue
    subnets="$(docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}} {{end}}' "$name")"
    for s in $subnets; do
      ! cidr_overlap "${declared%% *}" "$s" \
        || die "subnet ${declared%% *} overlaps network $(printf '%s' "$out" | sanitize) ($s)"
    done
  done
  pass "subnet ${declared%% *} collides with no other network"
  if network_change "$declared" "$running_net"; then
    [ "$ALLOW_DOWNTIME" -eq 1 ] \
      || die "the network changes ($running_net -> $declared): that needs a full stop (down, then up). Re-run with --allow-downtime"
    NET_CHANGED=1
    warn "network changes ($running_net -> $declared): the stack will be stopped and started"
  else
    pass "network unchanged"
  fi

  postgres_running || die "postgres is not running (first bring-up stays manual: docs/DEPLOYMENT.md section 4)"
  pass "postgres is running"

  dbmax="$(db_max)"
  TREE_MAX="$(tree_max "$FULL")"
  schema_gate "$dbmax" "$TREE_MAX" \
    || die "the database is at migration $dbmax but $TAG ships only up to $TREE_MAX"
  pass "schema: database $dbmax <= target $TREE_MAX"

  if [ "$RUNNING" = unknown ]; then
    RUNNING_FULL="$(g rev-parse --verify HEAD)"
  else
    RUNNING_FULL="$(g rev-parse --verify --quiet "${RUNNING%%-r*}^{commit}" || true)"
  fi
  if [ -n "$RUNNING_FULL" ] && [ "$RUNNING_FULL" != "$FULL" ] \
      && g merge-base --is-ancestor -- "$FULL" "$RUNNING_FULL"; then
    [ "$ALLOW_DOWNGRADE" -eq 1 ] \
      || die "$TAG is older than what runs (${RUNNING_FULL:0:12}); go back with deploy/deploy.sh rollback, or pass --allow-downgrade"
    warn "deploying an older commit (--allow-downgrade)"
  fi
  if [ "$RUNNING_FULL" = "$FULL" ] && [ "$REFRESH_BASE" -eq 0 ]; then
    die "$TAG is already running; pass --refresh-base to rebuild it on fresh base images"
  fi
  pass "target differs from what runs"
}

seed_if_first() {
  local head svc cid id ids=""
  [ -z "$(history "$STATE_LOG")" ] || return 0
  STEP=seed
  head="$(g rev-parse --verify HEAD)"
  say "first scripted deploy: tagging the running images as ${head:0:12} (a rollback target)"
  for svc in $BUILT_SERVICES; do
    cid="$(container "$svc")"
    [ -n "$cid" ] || die "$svc is not running; cannot seed a rollback target"
    id="$(docker inspect -f '{{.Image}}' "$cid")"
    docker tag "$id" "agreementmitra-$svc:${head:0:12}"
    ids="${ids:+$ids,}${id#sha256:}"
  done
  log_line seed unknown "$head" "${head:0:12}" "$(db_max)" "$(minio_now)" "$ids" - ok >>"$STATE_LOG"
  write_tag "${head:0:12}"
  RUNNING="${head:0:12}" L_PREV="$RUNNING"
}

dump() {
  local stamp name partial ps gz copies trail verdict keep protected cutoff victim
  STEP=dump
  L_MINIO="$(minio_now)"
  [ -n "$L_MINIO" ] || die "could not read the MinIO release"
  say "MinIO release before the deploy: $L_MINIO"

  mkdir -p "$BACKUPS"
  chmod 700 "$BACKUPS"
  [ -z "$(find "$BACKUPS" -maxdepth 0 ! -user root -print)" ] || die "$BACKUPS is not root-owned"
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  name="$(dump_name "$stamp" "$TAG")"
  partial="$BACKUPS/$name.partial"

  set +e
  dc exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | (umask 077 && gzip >"$partial")
  ps="${PIPESTATUS[0]} ${PIPESTATUS[1]}"
  # One decompression: gzip's own status proves the stream is whole; awk counts COPY sections and
  # keeps the last 20 lines. pg_dump 17.6+ writes `\unrestrict <key>` AFTER the completion trailer,
  # so the trailer is looked for in that tail, not on the last line.
  gzip -dc "$partial" 2>/dev/null \
    | awk '/^COPY / { c++ } { t[NR % 20] = $0 } END { print c + 0; for (i = NR - 19; i <= NR; i++) if (i > 0) print t[i % 20] }' \
      >"$WORK/dumpcheck"
  gz="${PIPESTATUS[0]}"
  set -e
  copies="$(head -n 1 "$WORK/dumpcheck")"
  trail="$(tail -n +2 "$WORK/dumpcheck")"
  if ! verdict="$(dump_verdict "$gz" "$copies" "$trail" "$ps")"; then
    rm -f -- "$partial"
    die "database dump failed: $verdict"
  fi
  mv -- "$partial" "$BACKUPS/$name"
  chmod 600 "$BACKUPS/$name"
  L_DUMP="$name"
  [ -f "$BACKUPS/$name" ] || die "dump vanished"
  pass "dump $BACKUPS/$name (same-disk rollback point; excludes object storage)"

  keep="$(image_keep_set "$STATE_LOG" "$RUNNING")"
  protected="$(protected_dumps "$STATE_LOG" "$keep")"
  cutoff="$(date -u -d "@$(( $(date -u +%s) - 30 * 86400 ))" +%Y%m%dT%H%M%SZ)"
  for victim in $(ls -1 "$BACKUPS" | rotation_victims "$cutoff" "$name" "$protected"); do
    rm -- "$BACKUPS/$victim" && say "rotated out $victim" || warn "could not remove $victim"
  done
}

build() {
  STEP=checkout
  g checkout --quiet --detach "$FULL" --
  [ "$(g rev-parse HEAD)" = "$FULL" ] || die "checkout did not land on $FULL"
  STEP=build
  export DEPLOY_TAG="$TAG"
  if [ "$REFRESH_BASE" -eq 1 ]; then
    dc build --pull $BUILT_SERVICES
  else
    dc build $BUILT_SERVICES
  fi
  pass "built $BUILT_SERVICES as $TAG"
}

start() {
  STEP=up
  write_tag "$TAG"
  if [ "$NET_CHANGED" -eq 1 ]; then
    say "stopping the stack for the network change (--allow-downtime)"
    dc down
  fi
  dc up -d --no-build --pull never --wait --wait-timeout 300
  pass "every service is healthy"
}

prune_images() {
  local keep ref tag
  keep="$(image_keep_set "$STATE_LOG" "$TAG")"
  for ref in $(docker image ls --filter 'reference=agreementmitra-*' --format '{{.Repository}}:{{.Tag}}'); do
    tag="${ref##*:}"
    [[ $tag =~ $TAG_RE ]] || continue
    case "${ref%%:*}" in agreementmitra-backend | agreementmitra-caddy | agreementmitra-gotenberg) ;; *) continue ;; esac
    _has_line "$tag" "$keep" && continue
    docker image rm "$ref" >/dev/null && say "removed image $ref" || warn "could not remove image $ref"
  done
}

# ---------------------------------------------------------------------------
# rollback and accept (stage 1, main's head code)
# ---------------------------------------------------------------------------

rollback() {
  local target full ceiling dbmax restore want svc i ids declared running_net
  take_lock
  RUNNING="$(DEPLOY_TAG=000000000000 running_tag)"
  case "$RUNNING" in
    unknown | mixed) export DEPLOY_TAG=000000000000 ;;
    *) export DEPLOY_TAG="$RUNNING" ;;
  esac
  # `mixed` never equals the history's top, so a part-way failed deploy rolls back to the top.
  target="$(rollback_target "$STATE_LOG" "$RUNNING")"
  [ -n "$target" ] || die "nothing to roll back to (the history holds only what runs)"
  full="$(rollback_target_full "$STATE_LOG" "$target")"
  [[ $full =~ ^[0-9a-f]{40}$ ]] || die "the history records no commit for $target"
  say "rolling back $RUNNING -> $target"

  ceiling="$(rollback_ceiling "$STATE_LOG" "$target")"
  [ -n "$ceiling" ] || ceiling="$(tree_max "$full")"
  dbmax="$(db_max)"
  if ! schema_gate "$dbmax" "$ceiling"; then
    restore="$(restore_dump_for "$STATE_LOG" "$ceiling")"
    if [ -n "$restore" ] && [ -f "$BACKUPS/$restore" ]; then
      die "the database is at migration $dbmax, past $target's $ceiling. Code-only rollback is unsafe.
       The restore point taken before that migration is $BACKUPS/$restore
       (a same-disk rollback point; it excludes object storage). Restoring it is manual."
    fi
    die "the database is at migration $dbmax, past $target's $ceiling, and no recorded restore dump exists on disk"
  fi
  pass "schema: database $dbmax <= $target's $ceiling"

  want="$(rollback_images "$STATE_LOG" "$target")"
  ids="$(image_ids "$target")"
  if ! i="$(images_match "$want" "$ids")"; then
    svc="$(printf '%s\n' $BUILT_SERVICES | sed -n "${i}p")"
    die "image agreementmitra-$svc:$target is missing or not the one recorded (retagged?)"
  fi
  pass "target images present and unchanged"

  # The target's network declaration: a later deploy may have changed it with --allow-downtime.
  if rollback_is_seed "$STATE_LOG" "$target"; then
    declared="$(ipam_from_compose "$(dc config --no-env-resolution --format json)")"
  else
    g show "$full:deploy/docker-compose.prod.yml" >"$WORK/target-compose.yml"
    declared="$(ipam_from_compose "$(DC_FILE="$WORK/target-compose.yml" dc config --no-env-resolution --format json)")"
  fi
  [ -n "$declared" ] || die "could not read $target's network declaration"
  running_net="$(ipam_from_inspect "$(docker network inspect -f '{{json .IPAM.Config}}' "$NETWORK" 2>/dev/null || true)")"
  if network_change "$declared" "$running_net"; then
    [ "$ALLOW_DOWNTIME" -eq 1 ] \
      || die "$target declares a different network ($running_net -> $declared): re-run as deploy/deploy.sh --allow-downtime rollback"
    NET_CHANGED=1
  fi

  clean_tree
  ensure_state
  LOG_ARMED=1
  L_PREV="$RUNNING" L_FULL="$full" L_TAG="$target"
  L_MINIO="$(minio_now)"

  if rollback_is_seed "$STATE_LOG" "$target"; then
    say "the target is the seed: staying on the current checkout (its compose names the seed tag)"
  else
    STEP=checkout
    g checkout --quiet --detach "$full" --
  fi
  STEP=up
  write_tag "$target"
  if [ "$NET_CHANGED" -eq 1 ]; then
    say "stopping the stack for the network change (--allow-downtime)"
    dc down
  fi
  dc up -d --no-build --pull never --wait --wait-timeout 300
  pass "every service is healthy"
  postcheck_migrations "$ceiling"
  postcheck_caddy
  postcheck_minio "$L_MINIO"
  postcheck_errors
  STEP=ok
  record ok
  say "rolled back to $target"
}

accept() {
  local last full minio ceiling
  take_lock
  RUNNING="$(DEPLOY_TAG=000000000000 running_tag)"
  [ "$RUNNING" != unknown ] || die "cannot tell what is running"
  export DEPLOY_TAG="$RUNNING"
  accept_allowed "$STATE_LOG" "$RUNNING" \
    || die "accept records only a deploy or rollback to the running tag that failed just its ERROR scan"
  last="$(tail -n 1 "$STATE_LOG")"
  full="$(log_field "$last" target)"
  minio="$(log_field "$last" minio)"
  ceiling="$(rollback_ceiling "$STATE_LOG" "$RUNNING")"
  [ -n "$ceiling" ] || ceiling="$(tree_max "$full")"
  postcheck_migrations "$ceiling"
  postcheck_caddy
  postcheck_minio "$minio"
  ensure_state
  LOG_ARMED=1
  L_PREV="$(log_field "$last" prev)" L_FULL="$full" L_TAG="$RUNNING" L_MINIO="$minio"
  L_DUMP="$(log_field "$last" dump)"
  record ok
  say "accepted $RUNNING"
}

# ---------------------------------------------------------------------------

main() {
  case "${1:-}" in
    --stage2) shift; stage2 "$@" ;;
    *) entry "$@" ;;
  esac
}

main "$@"; exit $?
