#!/usr/bin/env bash
#
# Run the backend test suite with the Docker/Testcontainers environment set up.
#
# Why this exists: the integration tests use Testcontainers, which must reach the
# Docker daemon. On non-default Docker setups (rancher-desktop, colima, …) the socket
# is not at /var/run/docker.sock and DOCKER_HOST is unset, so Testcontainers can't find
# Docker and the container-backed tests silently *skip*. We also run Gradle with
# --no-daemon so the forked test JVM inherits these env vars (a reused daemon would not).
#
# Usage:
#   ./run-tests.sh            # runs `check` (tests + JaCoCo gate)
#   ./run-tests.sh test       # or any gradle task / args you pass through
set -euo pipefail

cd "$(dirname "$0")"

# Point Testcontainers at whatever Docker context is currently active.
if [ -z "${DOCKER_HOST:-}" ]; then
  DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true)"
  export DOCKER_HOST
fi

# When the socket is not the default host path, tell Testcontainers where the socket is
# mounted inside helper containers (Ryuk) so it can manage/clean them up.
#
# Windows/Docker Desktop (npipe) is deliberately EXCLUDED from the override. Git Bash MSYS
# path conversion rewrites the /var/run/docker.sock value into C:\Program Files\Git\var,
# so Ryuk fails to start ("mkdir C:\Program Files\Git\var: Access is denied") and every
# container-backed test silently SKIPS. Testcontainers already mounts the correct path on
# npipe, so no override is needed. Do NOT work around this with TESTCONTAINERS_RYUK_DISABLED:
# without Ryuk the only cleanup is a JVM shutdown hook, which never runs on a killed/crashed
# test JVM, and orphaned containers pile up until the Docker engine wedges.
case "${DOCKER_HOST:-}" in
  "" | unix:///var/run/docker.sock | npipe:*) : ;; # default path, or Windows npipe - no override
  *) export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-/var/run/docker.sock}" ;;
esac

echo "DOCKER_HOST=${DOCKER_HOST:-<default>}"
echo "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-<unset>}"
echo

# --no-daemon so the test JVM inherits the env above. Default task: check.
if [ "$#" -eq 0 ]; then
  exec ./gradlew --no-daemon check
else
  exec ./gradlew --no-daemon "$@"
fi
