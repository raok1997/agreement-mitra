#!/usr/bin/env bash
# Tolerant auto-format hook. Runs after Claude edits files.
# Skips silently if a formatter isn't installed, so it never blocks a session.
set -euo pipefail

# Anchor on the project dir so relative paths work regardless of the hook's CWD.
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"

# Format frontend files with prettier if available
if command -v npx >/dev/null 2>&1 && [ -d "$PROJECT_DIR/frontend" ]; then
  (cd "$PROJECT_DIR" && npx --no-install prettier --write "frontend/src/**/*.{ts,vue,css}") >/dev/null 2>&1 || true
fi

# Format Java via Gradle Spotless if the wrapper exists (best-effort, non-blocking).
# A full Gradle run per edit is slow; kept best-effort so a missing wrapper or a
# Spotless failure never blocks the session.
if [ -x "$PROJECT_DIR/backend/gradlew" ]; then
  (cd "$PROJECT_DIR/backend" && ./gradlew spotlessApply -q) >/dev/null 2>&1 || true
fi

exit 0
