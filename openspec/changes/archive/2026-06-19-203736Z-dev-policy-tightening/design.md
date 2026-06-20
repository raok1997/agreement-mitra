## Context

The baseline audit (see roadmap) found the harness does not enforce the
project's own best practices: `openspec/config.yaml` has proposal rules but none
mandating tests or a security checklist; `CLAUDE.md` documents conventions but no
test-pyramid/scanning posture; `.claude/settings.json` has deny/ask/allow
permission lists and a `format.sh` PostToolUse hook, but no guard against PII or
secrets entering an edit; and the Java Spotless step in `format.sh` is commented
out. This CR is the first in the roadmap and is intentionally **config/tooling
only** so later CRs inherit the tightened policy. No application code, no module,
no FSM, no webhook is touched.

## Goals / Non-Goals

**Goals:**
- Make "tests at unit + integration level" and "a PII/security checklist" rules
  the harness shows on every future proposal.
- Document the test pyramid and scanning posture in `CLAUDE.md` once, as the
  reference later CRs implement against.
- Add a local PII/secret guard that blocks edits/commands carrying Aadhaar-like
  numbers, OTPs, or secrets, failing safe.
- Turn on Java auto-format (Spotless) to match the frontend's Prettier behavior.

**Non-Goals:**
- Building the actual test harnesses, coverage gates, or scanners — those are
  CRs 2–5. This CR only writes the *policy and guards*, not the CI tooling.
- Server-side / CI secret scanning. This guard is a local PreToolUse backstop,
  not a replacement for a real pre-commit / CI secret scanner.
- Changing permission lists or git guardrails already in `settings.json`.

## Decisions

### D1 — Enforce test + security policy via `config.yaml` rules, not a validator
OpenSpec injects per-artifact `rules:` into the AI's artifact-generation context.
*Verified empirically:* `openspec instructions tasks` and `… design` echo a
`tasks:` / `design:` rules block respectively, so a `tasks:` rule is **not**
inert — it is shown when the tasks artifact is generated. Adding a `tasks:` rule
and extending `proposal:` rules makes the policy visible at authoring time for
every future change. These rules are **advisory at authoring time, enforced by
the flow's review + validate stages** (a human/agent reading tasks against the
rule) — *not* a hard `openspec validate` gate, which does not parse `rules:`.
Escalate to a custom validator only if proposals start omitting tests in
practice. **Alternative considered:** a custom
`openspec validate` plugin that hard-fails proposals lacking test tasks —
rejected as over-engineered for a solo/AI workflow and brittle against the
legitimate pure-config exemption (this very CR). Rules + reviewer judgment is the
right altitude now; a hard validator can come later if drift appears.

### D2 — PII/secret guard as a standalone PreToolUse hook script
A new `.claude/hooks/pii-secret-guard.sh` reads the hook JSON on stdin and
decides allow/deny via Claude Code's **structured JSON decision output**, NOT via
exit codes.

**Block mechanism (corrected after review — this was the load-bearing bug).**
Claude Code's PreToolUse contract blocks the tool call only on **exit code 2**;
*any other non-zero exit is a non-blocking error and the tool proceeds.* A naive
`set -euo pipefail` "fail-safe" therefore fails **open** (a `set -e` abort or a
no-match `grep` exits 1 → write proceeds). To remove the exit-code footgun
entirely, the hook emits a structured decision on stdout and **always exits 0**:
- **Deny:** print
  `{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"<category-only message>"}}`
  and exit 0.
- **Allow:** print nothing (or the `allow` equivalent) and exit 0.
- **Every error path is a deny** (missing `python3`, malformed JSON, unreadable
  stdin) — true fail-**closed**, the opposite of the original design.

**Parsing.** Parse stdin JSON with `python3` (present in this repo's toolchain;
`jq` also available but `python3` is already used across the flow). If `python3`
is absent → emit deny. Extract candidate text per tool:
- `Edit` → `tool_input.new_string`
- `Write` → `tool_input.content`
- `MultiEdit` → concatenation of every `tool_input.edits[].new_string`
- `Bash` → `tool_input.command`

**Matcher set.** Wire the hook on `Edit|Write|MultiEdit|Bash`. This is the
formatter's set (`Edit|Write|MultiEdit`) **plus `Bash`** — Bash is a real
write/exfil path the formatter doesn't care about but the guard must, and adding
MultiEdit closes the bypass the formatter already covered.

**Fail-closed mechanism (not just intent).** `set -euo pipefail` alone is *not*
enough: a mid-script abort exits non-zero with no stdout, which is a non-blocking
PreToolUse error → the write proceeds (fail-open, the exact round-1 bug through
the back door). The script MUST install `trap 'deny "internal error"; exit 0' ERR`
(or check each step with `|| { deny …; exit 0; }`) so *every* abort path emits the
deny JSON and exits 0. The deny is the only blocking signal; nothing may rely on a
bare non-zero exit.

**Patterns (POSIX ERE, `grep -E`, `[0-9]` not `\d` — stock macOS BSD grep has no
`\d`/`-P`).** Match using guarded `if grep -qE …; then` so a no-match (exit 1)
never aborts the script under `set -e`:
- Aadhaar-like: grouped `[0-9]{4}[ .-]?[0-9]{4}[ .-]?[0-9]{4}`, **or** a bare
  12-digit run within a few chars of an `aadhaar`/`uid` context word. No Verhoeff
  checksum — accepted (favor a few false positives over a missed leak).
- OTP literals: an `otp`/`one.time.password` identifier assigned a 4–8 digit
  literal.
- Secrets: `-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----`, and
  `api[_-]?key`/`secret`/`token` assignments to a long (≥20-char) literal.

**Reason message MUST name the category only** ("Aadhaar-like number detected"),
never the matched substring — otherwise the guard would write the very PII it
caught into the transcript/Claude context (a data-exposure regression).

**Alternative considered:** exit-code-2 blocking — rejected for the JSON decision
because it is the unambiguous documented mechanism and lets *every* error path
deny without the exit-1-vs-2 ambiguity. **Alternative considered:** folding the
check into `format.sh` (PostToolUse) — rejected because PostToolUse runs *after*
the write, so it can't block; the guard must be PreToolUse.

### D3 — Spotless enabled but kept best-effort and non-blocking
Uncomment the Spotless block in `format.sh` but keep `|| true` and the
`-x backend/gradlew` existence check so a missing wrapper or a Spotless failure
never blocks a session. A per-edit Gradle run is slow; we accept that for now and
note it as a trade-off, leaving room for a future scoped/incremental approach.
**Alternative considered:** only format on a pre-commit hook — rejected to keep
parity with the existing on-edit Prettier behavior.

### D4 — Test-task rule carries an explicit pure-config exemption
The unit+integration rule must not create false friction for config/docs-only
changes (like this one). The `config.yaml` rule states the exemption and requires
the change to record *why* it's exempt, preserving the signal for behavioral
changes without blocking config ones.

## Risks / Trade-offs

- **The guard is defense-in-depth, NOT a guarantee — and must not be sold as one.**
  It is a *reminder/backstop* against the obvious mistake (pasting a real-looking
  Aadhaar/secret into an edit), not a control. Known, accepted bypasses (documented
  as non-goals so no one mistakes them for coverage): MultiEdit is now matched, but
  base64/hex/string-split secrets evade content grep; a Bash command that
  *downloads* or *decodes* a secret to a file (`curl … > k.pem`) hides the content
  from `tool_input.command`; non-edit write paths beyond the matched set are
  unguarded. The real enforcement is a server-side pre-commit / CI secret scanner
  (a later concern, explicit non-goal here). → Mitigation: state this plainly in
  spec + CLAUDE.md so the guard never breeds false confidence.
- **PII guard false positives** (a legitimate grouped 12-digit number, a dummy key
  in a fixture) → Mitigation: require the grouped Aadhaar form or an `aadhaar`/`uid`
  context word; scope secret matching to long literals; document how to phrase dummy
  data so it doesn't trip the guard. No Verhoeff checksum — we deliberately tune
  toward a few false positives over a missed leak, accepting mild alarm risk.
- **Spotless slows edits** (full Gradle run per Java edit) → Mitigation:
  best-effort/non-blocking; revisit with incremental formatting if it hurts.
- **Rules are advisory, not hard-enforced** (D1) → Mitigation: review + validate
  stages check tasks against the rule; escalate to a custom validator only if
  proposals start omitting tests in practice.
- **Parser/portability** → `python3` parses the hook JSON (present in toolchain);
  POSIX ERE with `grep -E` + `[0-9]` (never `\d`/`-P`, unsupported by stock macOS
  BSD grep); guarded `if grep -qE` so no-match never aborts under `set -e`. Any
  missing dependency or parse error → **deny** (fail-closed).
