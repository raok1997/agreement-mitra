## ADDED Requirements

### Requirement: Proposals mandate unit and integration test tasks

The OpenSpec harness SHALL require that every change which adds or modifies
runnable behavior include explicit test tasks at **both** the unit and
integration levels. This rule lives in `openspec/config.yaml` so it is shown to
the AI when generating artifacts for any future change.

#### Scenario: Behavioral change without test tasks is off-policy
- **WHEN** a proposal's `tasks.md` adds or changes runnable backend or frontend
  behavior but lists no unit test task and no integration test task
- **THEN** the change is off-policy and the harness rule directs the author to
  add both a unit-level and an integration-level test task before applying

#### Scenario: Pure-config change is exempt
- **WHEN** a change touches only configuration, documentation, or harness files
  and adds no runnable behavior
- **THEN** the unit + integration test-task rule does not require new test tasks,
  and the change records why it is exempt

### Requirement: Proposals carry a PII/security review checklist

The OpenSpec harness SHALL require every proposal to address a PII/security
review checklist: whether the change introduces or moves Aadhaar/OTP/VID/PII or
secrets, how such data is redacted/secured, and confirmation that sandbox +
dummy data only is preserved. This rule lives in `openspec/config.yaml`.

#### Scenario: Proposal states its PII/security posture
- **WHEN** a proposal is generated for any change
- **THEN** it explicitly states whether new PII/secret flow is introduced and how
  it is redacted/secured (or states "none" with justification)

### Requirement: Project guidance documents the test pyramid and scanning posture

`CLAUDE.md` SHALL contain a "Testing & Scanning" section that documents the test
pyramid (many fast unit tests, fewer integration tests, few end-to-end tests) and
the dependency-vulnerability and SAST scanning expectations that later roadmap
CRs implement.

#### Scenario: Contributor reads the testing & scanning policy
- **WHEN** a contributor (human or AI) reads `CLAUDE.md`
- **THEN** they find a section describing the unit > integration > e2e balance
  and that dependency-vuln scanning and SAST are required build gates

### Requirement: PII/secret guard blocks edits containing identity data or secrets

A local `PreToolUse` hook SHALL scan the candidate content of `Edit`, `Write`,
`MultiEdit`, and `Bash` tool calls (the format hook's set plus `Bash`) for
Aadhaar-like 12-digit numbers, OTP-shaped literals, and common secret/private-key
patterns, and block the operation with a category-only redaction reminder when a
pattern matches.

The hook SHALL block using Claude Code's structured decision output
(`permissionDecision: "deny"` on stdout, exit 0), NOT exit-code signalling, since
PreToolUse blocks only on exit code 2 and treats other non-zero exits as
non-blocking. The hook MUST fail **closed**: any internal error (missing JSON
parser, malformed input, unreadable stdin) SHALL result in a deny, never a silent
allow. The reminder message MUST name only the category of match (e.g.
"Aadhaar-like number detected") and MUST NOT echo the matched value, so the guard
never leaks the very data it caught into the transcript.

This guard is defense-in-depth, not a guarantee: it does not defeat encoded,
split, or downloaded secrets, and a server-side pre-commit/CI scanner remains the
authoritative control.

#### Scenario: Edit containing an Aadhaar-like number is denied
- **WHEN** an `Edit`, `Write`, or `MultiEdit` would introduce a grouped 12-digit
  Aadhaar-shaped number (e.g. `1234 5678 9012`) into a file
- **THEN** the hook emits a `deny` decision with a category-only message telling
  the author to use dummy/redacted data, and exits 0

#### Scenario: Edit containing a private key or secret is denied
- **WHEN** an `Edit` or `Write` would introduce a private-key header
  (e.g. `-----BEGIN PRIVATE KEY-----`) or a long API-secret assignment
- **THEN** the hook emits a `deny` decision reminding the author secrets come from
  env vars only

#### Scenario: Hook error fails closed
- **WHEN** the hook receives malformed JSON or no JSON parser is available
- **THEN** the hook emits a `deny` decision rather than allowing the write

#### Scenario: Clean edit is allowed
- **WHEN** an edit contains no PII or secret pattern
- **THEN** the hook emits no deny decision (exit 0) and the edit proceeds

### Requirement: Java edits are auto-formatted on save

The `format.sh` PostToolUse hook SHALL run Spotless on Java edits so backend code
is auto-formatted, matching the existing Prettier behavior for the frontend. The
step MUST be best-effort: if the Gradle wrapper or Spotless is unavailable, the
hook SHALL skip silently rather than block the session.

#### Scenario: Java file edit triggers Spotless
- **WHEN** Claude edits a `.java` file and the backend Gradle wrapper is present
- **THEN** the format hook runs `spotlessApply` so the file is formatted

#### Scenario: Formatter absent does not block
- **WHEN** the Gradle wrapper or Spotless is not available
- **THEN** the format hook skips the Java step silently and the session continues
