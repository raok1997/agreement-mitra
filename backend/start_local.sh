#!/usr/bin/env bash
#
# Run the API locally against the docker-compose infra (Postgres + MinIO).
#
# Why this exists: the object-storage MinioClient is built from S3_ACCESS_KEY/S3_SECRET_KEY,
# which default to EMPTY in application.yml (secrets are env-only — never committed to config).
# So a bare `./gradlew bootRun` fails at the minioClient bean with "AccessKey and SecretKey must
# not be empty". This script supplies the compose MinIO defaults (minioadmin/minioadmin — the same
# values already in docker-compose.yml) as env vars, then runs bootRun.
#
# Prereq: `docker compose up -d` (from the repo root) so Postgres + MinIO are running.
#
# Usage:
#   ./start_local.sh                 # runs `bootRun`
#   ./start_local.sh <gradle args>   # passes args through (e.g. --debug)
#
# Any S3_* / DB_* var already set in your environment wins (defaults only fill the gaps), so this
# never overrides a real configuration.
set -euo pipefail

cd "$(dirname "$0")"

# Local docker-compose defaults — only applied if unset (a real env always wins).
export S3_ACCESS_KEY="${S3_ACCESS_KEY:-minioadmin}"
export S3_SECRET_KEY="${S3_SECRET_KEY:-minioadmin}"

# Use the documented local profile (application-local.yml) for any local-only overrides.
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"

echo "SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}  S3_ENDPOINT=${S3_ENDPOINT:-http://localhost:9000}  S3_BUCKET=${S3_BUCKET:-agreements}"
echo "(MinIO creds taken from env; defaulting to the compose minioadmin values if unset)"
echo

if ! docker compose -f ../docker-compose.yml ps --status running 2>/dev/null | grep -q minio; then
  echo "WARNING: compose MinIO does not look running — run 'docker compose up -d' from the repo root first." >&2
fi

if [ "$#" -eq 0 ]; then
  exec ./gradlew bootRun
else
  exec ./gradlew bootRun "$@"
fi
