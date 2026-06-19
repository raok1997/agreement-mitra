<!-- Test-task note: this is a pure-config/harness CR (no runnable app behavior),
     so per the new config.yaml exemption it carries no unit/integration app
     tests. Instead, section 5 verifies the new guards behave correctly. -->

## 1. OpenSpec policy rules

- [x] 1.1 Add a `tasks:` rule block to `openspec/config.yaml` mandating explicit unit AND integration test tasks for any change adding/modifying runnable behavior, with an explicit pure-config/docs exemption. The rule SHALL specify the exemption is recorded as a one-line note at the top of `tasks.md` stating why it applies (the convention this CR itself follows).
- [x] 1.2 Extend the `proposal:` rules in `openspec/config.yaml` with a PII/security review checklist item (introduces/moves Aadhaar/OTP/VID/PII or secrets? how redacted/secured? sandbox+dummy-only preserved?).
- [x] 1.3 Run `openspec validate dev-policy-tightening --strict` to confirm the edited config still parses.

## 2. Project guidance — Testing & Scanning

- [x] 2.1 Add a "Testing & Scanning" section to `CLAUDE.md` documenting the test pyramid (many unit > fewer integration > few e2e).
- [x] 2.2 Document the scanning posture in the same section: dependency-vulnerability scan + SAST as required build gates (implemented by later roadmap CRs), and note the local PII/secret edit guard is defense-in-depth (a reminder), not the authoritative control.

## 3. PII/secret guard hook

- [x] 3.1 Create `.claude/hooks/pii-secret-guard.sh` (executable, `set -euo pipefail`) that reads PreToolUse hook JSON on stdin and parses it with `python3`, extracting candidate text per tool: Edit→`tool_input.new_string`, Write→`tool_input.content`, MultiEdit→concat of `tool_input.edits[].new_string`, Bash→`tool_input.command`. Define a `deny()` helper (static category-only reason) and install `trap 'deny "internal error"; exit 0' ERR` (or guard each step with `|| { deny …; exit 0; }`) so any abort — missing `python3`, malformed JSON, unreadable stdin — emits the deny JSON and exits 0 (fail-closed; never a bare non-zero exit).
- [x] 3.2 Implement pattern matching with `grep -E` and POSIX classes (`[0-9]`, never `\d`/`-P` — stock macOS BSD grep lacks them), using guarded `if grep -qE …; then` so a no-match (exit 1) never aborts the script under `set -e`. Patterns: grouped Aadhaar-like `[0-9]{4}[ .-]?[0-9]{4}[ .-]?[0-9]{4}`, or a bare 12-digit run on the **same line** as an `aadhaar`/`uid` word; OTP-shaped `otp`/`one-time-password` identifier followed by an assignment operator (`=`, `:`, `=>`, with optional quotes/space) and a 4–8 digit literal; `-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----`; and `api[_-]?key`/`secret`/`token` followed by the same assignment-operator set and a ≥20-char literal.
- [x] 3.3 On match (or via the `deny()`/trap error path), emit Claude Code's structured deny on stdout — `{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"<category-only message>"}}` — and `exit 0`. The reason MUST be a constant naming only the category (e.g. "Aadhaar-like number detected"), with NO interpolation of the matched substring. On clean input, emit nothing and `exit 0`. Do NOT rely on non-zero exit codes to block (only exit 2 blocks; the JSON decision is the unambiguous mechanism).
- [x] 3.4 Register the hook as a `PreToolUse` matcher on `Edit|Write|MultiEdit|Bash` in `.claude/settings.json` (the format hook's `Edit|Write|MultiEdit` set plus `Bash`; leave existing permissions and the PostToolUse format hook intact). Register this **last**, after the section-5 fixture checks, since once active it will deny any edit carrying these patterns — including this CR's own example-bearing artifacts.

## 4. Enable Java format check

- [x] 4.1 Uncomment the Spotless block in `.claude/hooks/format.sh`, anchoring paths on `$CLAUDE_PROJECT_DIR` (e.g. `[ -x "$CLAUDE_PROJECT_DIR/backend/gradlew" ]`) and keeping the existence check and `|| true` so it stays best-effort and non-blocking regardless of the hook's CWD.

## 5. Verification

- [x] 5.1 Pipe a fake Edit payload with a grouped Aadhaar-like number through `pii-secret-guard.sh`; assert stdout contains `"permissionDecision":"deny"`, exit 0, and that the reason does NOT contain the digits.
- [x] 5.2 Pipe payloads containing `-----BEGIN PRIVATE KEY-----`, a long `api_key=` assignment, and a `secret:`/`token =` form; assert each produces a `deny`.
- [x] 5.3 Pipe a MultiEdit payload whose `edits[].new_string` carries an Aadhaar-like number, AND a Bash payload whose `command` carries an inline secret; assert both `deny` (MultiEdit and Bash arms exercised).
- [x] 5.4 Pipe malformed JSON (and simulate missing `python3`); assert the hook fails closed with a `deny` and exit 0.
- [x] 5.5 Pipe a clean payload; assert no `deny` and exit 0.
- [x] 5.6 Confirm `format.sh` runs without error and skips the Java step silently when the Gradle wrapper is absent.
- [x] 5.7 Confirm `openspec validate dev-policy-tightening --strict` passes, and (sanity) that `openspec instructions tasks --json` echoes the new `tasks:` rule.
