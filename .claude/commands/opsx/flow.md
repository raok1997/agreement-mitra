---
name: "OPSX: Flow"
description: Drive a requirement through the full OpenSpec lifecycle (explore → propose → review → apply → validate → fix → archive), running to the first real decision
skill: openspec-flow
category: Workflow
tags: [workflow, orchestrator, lifecycle, experimental]
---

> **UI wrapper for `openspec-flow`** — the skill is the authoritative source of logic.

Take one requirement all the way through the OpenSpec lifecycle with minimal
intervention: explore → propose → review (≤3 rounds) → apply →
validate → fix → archive. Auto-advances stage to stage, gives a plain-English
summary at the end of each stage, and **halts at the first real decision** (ambiguous requirement, non-minimal review/validation finding,
signing-flow change, in-change-vs-new-CR scope call, or hard error).

**Two halts are scheduled rather than discretionary.** After review closes, the flow stops
and asks you to clear the context — `/clear`, then `/opsx:flow <name> from:apply` — because
carrying review's context through implementation is the most expensive thing the flow does,
and nothing in apply needs it. And before archive, it stops for manual testing.

**Input**: After `/opsx:flow`, give a change name (kebab-case) and/or a
requirement description, optionally a starting stage. Examples:
- `/opsx:flow add-otp-resend "resend Aadhaar OTP during signing"`
- `/opsx:flow add-otp-resend from:apply` (resume an existing change at implement)
- `/opsx:flow` (will ask what to build)

For a proposal artifact on its own — no design, no tasks, and deliberately not
implementable — use `/opsx:propose <slug> only`. The flow drives a requirement to
completion, so it has no proposal-only mode.

**Invoke**: Use the **Skill tool** to run `openspec-flow` with the parsed input.
