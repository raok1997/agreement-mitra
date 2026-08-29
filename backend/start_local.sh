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

# Load local-only secrets from a git-ignored .env.local if present (see .env.example for the keys):
# GOOGLE_OAUTH_CLIENT_ID / GOOGLE_OAUTH_SECRET for optional Google login, plus any S3_*/DB_* overrides.
# `set -a` exports every var the file defines; values already in the environment are NOT overwritten
# by a plain `KEY=value` here unless the file itself exports them, so a real env still wins by default.
if [ -f .env.local ]; then
  echo "Loading local env from backend/.env.local"
  set -a
  # Strip CR so a file saved from PowerShell/Windows (CRLF) sources cleanly -- otherwise a
  # trailing \r ends up inside each value (e.g. the client secret) and breaks the OAuth call.
  # shellcheck disable=SC1091
  . <(tr -d '\r' < .env.local)
  set +a
fi

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
