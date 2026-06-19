## Why

Best practices (tests at every layer, no PII/secret leakage, consistent
formatting) currently depend on remembering to apply them each session. The
baseline audit found the harness does not enforce them: OpenSpec proposals
aren't required to include test tasks or a security checklist, `CLAUDE.md` has
no test-pyramid or scanning guidance, there is no PII/secret guard on edits, and
the Java format check is commented out. Tightening the harness **first** means
every later roadmap CR inherits these rules instead of re-deriving them.

This change touches the development harness only. It does **not** touch the
signing-status FSM, the eSign/webhook flow, or any application code, and it
introduces **no** new outbound PII flow — it reduces PII-leak risk by adding a
guard.

## What Changes

- **OpenSpec policy** (`openspec/config.yaml`): add per-artifact rules so every
  proposal/tasks set must (a) include explicit **unit AND integration** test
  tasks for the behavior it adds, and (b) carry a **PII/security review
  checklist** item. Future proposals inherit these automatically.
- **Project guidance** (`CLAUDE.md`): add a "Testing & Scanning" section
  documenting the test pyramid (many unit > fewer integration > few e2e) and the
  dependency-vulnerability + SAST scanning expectations that later CRs implement.
- **PII/secret guard hook** (`.claude/settings.json` + new
  `.claude/hooks/pii-secret-guard.sh`): a `PreToolUse` hook on
  `Edit|Write|MultiEdit|Bash` that scans the proposed content for Aadhaar-like
  12-digit numbers, OTP-shaped literals, and common secret/private-key patterns,
  and **denies** the operation (structured decision, fail-closed) with a
  category-only redaction reminder when matched. Sandbox + dummy data only is
  non-negotiable here, so the guard is a **defense-in-depth backstop** — not a
  guarantee (encoded/split/downloaded secrets can evade it; a CI/pre-commit
  scanner is the authoritative control) — against committing real-looking
  identity data or credentials.
- **Java format check** (`.claude/hooks/format.sh`): enable the
  currently-commented Spotless step so Java edits are auto-formatted, matching the
  existing Prettier behavior for the frontend.

## Capabilities

### New Capabilities
- `dev-policy`: the enforced engineering policy baked into the harness — what
  every OpenSpec change must include (tests at unit + integration level, a
  security/PII checklist), the documented test-pyramid and scanning posture, and
  the local guards (PII/secret edit guard, auto-format on edit) that keep work
  inside the project's non-negotiable constraints.

### Modified Capabilities
<!-- None — no existing specs; no requirement-level behavior of existing capabilities changes. -->

## Impact

- **Config/tooling only** — no application code, no module changes, no
  `ModularityTests` impact, no FSM or webhook impact.
- Files: `openspec/config.yaml`, `CLAUDE.md`, `.claude/settings.json`,
  `.claude/hooks/format.sh`, new `.claude/hooks/pii-secret-guard.sh`.
- **Workflow effect**: future OpenSpec proposals that omit unit/integration test
  tasks or a security checklist are now off-policy; edits containing PII/secret
  patterns are blocked locally; Java edits are auto-formatted (a Gradle Spotless
  run per edit — best-effort, non-blocking on failure).
- Inherited by roadmap CRs `backend-test-harness`, `frontend-test-harness`,
  `backend-security-scanning`, `spring-security-baseline`.
