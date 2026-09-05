#!/usr/bin/env bash
#
# Remove orphaned Testcontainers left behind by a test JVM that died without running its
# shutdown hook (OOM-kill, Ctrl-C, crash) while the Ryuk reaper was disabled.
#
# Why this exists: an orphan is invisible. It has a random name ("wonderful_wright"), belongs
# to no compose project, and never appears in `docker compose ps` - so it is not something you
# notice, it is something you eventually feel. On a 3.7 GiB Docker VM, 32 of them held ~1.8 GiB
# and one span at 72% CPU. The build now refuses to run with the reaper off (see the Ryuk guard
# in build.gradle.kts), so this script is for cleaning up what earlier runs already stranded.
#
# WHAT IT WILL NOT TOUCH: anything belonging to a docker-compose project. The local dev stack
# (agreement-mitra-postgres-1 / -minio-1 / -gotenberg-1) carries the compose project label, and
# every container that has one is skipped. Testcontainers containers carry the
# `org.testcontainers=true` label, which is what this script selects on - so identification is
# by label, never by image name or a name pattern that could match something of yours.
#
# Usage:
#   ./scripts/sweep-test-containers.sh          # list what would be removed, remove nothing
#   ./scripts/sweep-test-containers.sh --force   # actually remove them
set -euo pipefail

FORCE=false
[ "${1:-}" = "--force" ] && FORCE=true

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not reachable - nothing to sweep." >&2
  exit 0
fi

# Testcontainers labels everything it starts, including the Ryuk container itself.
mapfile -t CANDIDATES < <(docker ps -aq --filter "label=org.testcontainers=true" 2>/dev/null || true)

if [ "${#CANDIDATES[@]}" -eq 0 ]; then
  echo "No Testcontainers containers found. Nothing to sweep."
  exit 0
fi

# Belt and braces: drop anything carrying a compose project label, so a compose-managed
# container can never be swept even if it were somehow labelled as a test container.
ORPHANS=()
for id in "${CANDIDATES[@]}"; do
  project="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$id" 2>/dev/null || echo "")"
  if [ -n "$project" ] && [ "$project" != "<no value>" ]; then
    continue # compose-managed - not ours to remove
  fi
  ORPHANS+=("$id")
done

if [ "${#ORPHANS[@]}" -eq 0 ]; then
  echo "No orphaned test containers (all Testcontainers found are compose-managed). Nothing to sweep."
  exit 0
fi

echo "Orphaned Testcontainers containers (${#ORPHANS[@]}):"
docker ps -a --format '  {{.Names}}\t{{.Image}}\t{{.Status}}' --filter "id=$(printf '%s\n' "${ORPHANS[@]}" | paste -sd' ' - | sed 's/ / --filter id=/g')" 2>/dev/null ||
  for id in "${ORPHANS[@]}"; do
    docker ps -a --format '  {{.Names}}\t{{.Image}}\t{{.Status}}' --filter "id=$id"
  done

if [ "$FORCE" != true ]; then
  echo
  echo "Dry run - nothing removed. Re-run with --force to remove them."
  exit 0
fi

echo
docker rm -f "${ORPHANS[@]}" >/dev/null
echo "Removed ${#ORPHANS[@]} orphaned test container(s)."

# Their anonymous volumes outlive them and are pure waste once the container is gone.
# `docker volume prune` only ever removes volumes no container references, so the compose
# stack's named volumes (pgdata, miniodata) are safe while its containers exist - but they
# would NOT be safe if that stack were stopped, so this is opt-in rather than automatic.
echo
echo "Dangling volumes left by removed containers can be reclaimed with:"
echo "  docker volume prune -f     # only while 'docker compose ps' shows the dev stack UP"
