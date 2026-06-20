#!/usr/bin/env bash
# Tolerant auto-format hook (PostToolUse). Formats ONLY the file Claude just
# edited — never the whole tree — so it can't churn unrelated, not-yet-clean
# sources into the diff. Skips silently if a formatter isn't installed or the
# edited path can't be determined, so it never blocks a session.
set -euo pipefail

# Anchor on the project dir so relative paths work regardless of the hook's CWD.
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"

# PostToolUse hooks receive the tool call as JSON on stdin; pull the edited path.
# Edit/Write/MultiEdit all carry tool_input.file_path. If we can't parse it,
# do nothing rather than fall back to a whole-tree format.
PAYLOAD="$(cat)"
FILE_PATH="$(printf '%s' "$PAYLOAD" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))' \
  2>/dev/null || true)"
[ -n "$FILE_PATH" ] && [ -f "$FILE_PATH" ] || exit 0

case "$FILE_PATH" in
  *.java)
    # Backend: Spotless scoped to just this file via -PspotlessFiles (a regex
    # matched against the absolute path). A full Gradle run per edit is slow but
    # kept best-effort; scoping means it only ever rewrites the file we edited.
    if [ -x "$PROJECT_DIR/backend/gradlew" ]; then
      file_re="$(printf '%s' "$FILE_PATH" | sed 's/[][\\.*^$(){}+?|]/\\&/g')"
      (cd "$PROJECT_DIR/backend" && ./gradlew spotlessApply -q -PspotlessFiles="$file_re") >/dev/null 2>&1 || true
    fi
    ;;
  *.ts|*.mts|*.cts|*.vue|*.css)
    # Frontend: prettier just the edited file, best-effort.
    if command -v npx >/dev/null 2>&1; then
      (cd "$PROJECT_DIR" && npx --no-install prettier --write "$FILE_PATH") >/dev/null 2>&1 || true
    fi
    ;;
esac

exit 0
