#!/usr/bin/env bash
# Tolerant auto-format hook. Runs after Claude edits files.
# Skips silently if a formatter isn't installed, so it never blocks a session.
set -euo pipefail

# Format frontend files with prettier if available
if command -v npx >/dev/null 2>&1 && [ -d frontend ]; then
  npx --no-install prettier --write "frontend/src/**/*.{ts,vue,css}" >/dev/null 2>&1 || true
fi

# Format Java via Gradle Spotless if the wrapper exists (best-effort, non-blocking)
# Left commented by default because a full Gradle run per edit is slow.
# Uncomment once you want enforced Java formatting on every edit:
# if [ -x backend/gradlew ]; then
#   (cd backend && ./gradlew spotlessApply -q) >/dev/null 2>&1 || true
# fi

exit 0
