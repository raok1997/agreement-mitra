#!/usr/bin/env bash
# PreToolUse guard. Denies edits/commands that carry Aadhaar-like numbers, OTPs,
# or secrets. This is defense-in-depth (a reminder), NOT the authoritative
# control — encoded/split/downloaded secrets can evade content matching.
#
# Block mechanism: PreToolUse blocks only on exit code 2; any other non-zero exit
# is non-blocking. So we never block via exit code — we emit a structured
# decision on stdout and exit 0. Fail-closed: any internal error emits a deny.
set -euo pipefail

# Emit a deny decision and exit. $1 is a CONSTANT category-only reason — never
# interpolate the matched content, or the guard would leak the very data it caught.
deny() {
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"%s"}}\n' "$1"
  exit 0
}

# Any unexpected failure (parse error, missing tool, etc.) blocks rather than passes.
trap 'deny "PII/secret guard error - blocked to fail safe; retry or simplify the edit"' ERR

command -v python3 >/dev/null 2>&1 || \
  deny "PII/secret guard cannot run (python3 missing) - blocked to fail safe"

input="$(cat)"

# Extract the candidate text for the tool. The raw JSON is passed via an env var
# (not stdin) so the python source and the data never collide. A JSON parse
# failure exits non-zero, tripping the ERR trap above (fail-closed).
content="$(RAW_JSON="$input" python3 -c '
import os, json, sys
data = json.loads(os.environ["RAW_JSON"])
ti = data.get("tool_input") or {}
tool = data.get("tool_name", "")
parts = []
if tool == "Edit":
    parts.append(ti.get("new_string", ""))
elif tool == "Write":
    parts.append(ti.get("content", ""))
elif tool == "MultiEdit":
    for e in (ti.get("edits") or []):
        parts.append(e.get("new_string", ""))
elif tool == "Bash":
    parts.append(ti.get("command", ""))
sys.stdout.write("\n".join(p for p in parts if p))
' 2>/dev/null)"

# Nothing to inspect (unknown tool / empty payload) -> allow.
[ -n "$content" ] || exit 0

# grep -qE returning 1 (no match) inside an `if` condition does NOT trip set -e
# or the ERR trap, so these guarded checks are safe. POSIX classes only ([0-9]),
# never \d / -P (unsupported by stock macOS BSD grep).
# -e guards patterns that begin with '-' (e.g. private-key headers) from being
# parsed as grep options.
matches() { printf '%s' "$content" | grep -qiE -e "$1"; }

# Aadhaar-like: grouped 12 digits, or a bare 12-digit run alongside aadhaar/uid.
if printf '%s' "$content" | grep -qE -e '[0-9]{4}[ .-][0-9]{4}[ .-][0-9]{4}'; then
  deny "Aadhaar-like number detected - use dummy/redacted data, never real Aadhaar"
fi
if matches '(aadhaar|uid)[^0-9]*[0-9]{12}|[0-9]{12}[^0-9]*(aadhaar|uid)'; then
  deny "Aadhaar-like number detected - use dummy/redacted data, never real Aadhaar"
fi

# OTP: an otp / one-time-password identifier assigned a 4-8 digit literal.
if matches '(otp|one[-_ ]?time[-_ ]?password)[^a-z0-9]{1,6}[0-9]{4,8}'; then
  deny "OTP-like literal detected - never embed real OTPs"
fi

# Private key headers.
if matches '-----begin ((rsa|ec|openssh|dsa|pgp) )?private key-----'; then
  deny "Private key material detected - secrets come from env vars only"
fi

# api_key / secret / token assigned a long literal.
if matches '(api[_-]?key|secret|token)[^a-z0-9]{1,6}[a-z0-9/+=_-]{20,}'; then
  deny "Secret/credential assignment detected - secrets come from env vars only"
fi

# Clean: emit nothing, allow.
exit 0
