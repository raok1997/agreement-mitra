---
name: openspec-flow
description: End-to-end OpenSpec change orchestrator. Drives one requirement through explore → propose → review → apply → validate → fix → manual-test → archive, running to the first real decision and halting for human judgment (including a mandatory manual-test gate before archive). Use when the user wants to take a requirement all the way through the OpenSpec lifecycle with minimal intervention.
license: MIT
compatibility: Requires openspec CLI and the openspec-* / review-spec skills.
command: opsx:flow
metadata:
  author: custom        # hand-authored orchestrator — NOT scaffolded by the openspec CLI
  version: "1.0"
  # Siblings carry `generatedBy: "1.2.0"`; intentionally omitted here. That field is a
  # tool-provenance stamp, and this skill was written by hand. `author: custom` (vs the
  # siblings' `author: openspec`) marks the same fact: do not "normalize" these to match.
---

# OpenSpec Flow — lifecycle orchestrator

Drive a single requirement through the full OpenSpec lifecycle by invoking the
authoritative skills in sequence. This skill owns **orchestration and policy
only** — each stage's logic lives in its own skill, which is the source of truth.

This skill runs in the **main thread**. It is interactive by design: it advances
automatically through stages but **runs only to the first real decision**, then
stops and asks the user.

## Execution model: thin spine, isolated read-heavy stages

The main thread is an **interactive spine** that holds the requirement, the running
plain-English narrative, and **every halt/decision**. Keep it small. A subagent runs
to completion and cannot stop to ask the user — so anything that must halt for human
judgment stays in the spine.

**Source of truth between stages is the change directory on disk** — `proposal.md`,
`design.md`, `tasks.md`, `specs/`, plus the working-tree diff — **not this transcript.**
Any stage can reconstruct its inputs by reading that directory. Treat every handoff as
"read the change files," never "remember the conversation." Consequence: resume is
lossless — if context is compacted mid-flow, re-enter with `from:<stage>` and the stage
re-reads disk and continues.

**Isolate read-heavy, report-producing work in subagents; fold back only the report +
recommendation. The conductor (main thread) owns the halt decision.**

**Subagents never write.** They return reports/findings only — the spine performs every
disk write: artifacts, code, and the flow journal. (Explore subagents lack write tools
anyway; the validate `general-purpose` subagent has write tools and must be told not to
use them.)
- **Review** — `review-spec` **already spawns its own review agent**. Consume its
  returned report and apply the Issue policy. Do **not** wrap it in another agent.
- **Validate** — run `openspec-validate` **inside a general-purpose subagent**, passing
  the **resolved change name explicitly**, and fold back only the gap list. The subagent
  has no `AskUserQuestion` and cannot halt to prompt, so validate must never reach its
  ambiguity branch — always hand it the name. It is read-heavy (diff vs. all artifacts);
  isolating it keeps the spine small. The halts it implies are handled by the conductor
  in the Fix stage. (Subagents have the `Skill` tool, so invoking `openspec-validate`
  inside one is valid. Review is different: `review-spec` spawns its **own** agent, so a
  subagent would nest agents pointlessly and its findings need spine-side halt
  adjudication anyway — hence review stays in the spine.)
- **Propose grounding** — when grounding the proposal in real code needs a broad
  codebase fan-out, delegate that sweep to an **Explore** subagent and draft the
  artifacts in the main thread.

**Keep in the main thread** (interactive or trivial): explore (live back-and-forth),
proposal drafting + scope-split halt, **apply/implement** (real decision points — the
signing flow, scope), fix decisions, the **manual-test gate** (a hard halt — the user tests and
confirms before archive), archive, reporting. Apply may delegate bounded mechanical
sub-steps (a verification sweep, a wide grep), but its halts are real — never hand the
whole stage to a subagent that can't stop to ask.

## Operating mode: run to first decision

Auto-advance stage to stage. **Halt and ask the user** the moment you hit any of:

- An **ambiguous or underspecified requirement** (can't proceed without an assumption that changes scope).
- A **review or validation finding** that is NOT clearly minimal/foldable (see Issue policy).
- Anything touching the **signing state machine or eSign/webhook** logic — enumerate the impacts and confirm before changing (this code is load-bearing).
- A **scope decision**: whether an issue belongs in-change or as a new CR.
- A **hard error**: failing tests, `openspec validate` errors, CLI failures, a skill that can't complete.
- A point where you'd otherwise **silently expand scope** beyond the current CR's slice.
- The **manual-test gate before Archive** — archive is never automatic. After validate is clean, halt and ask the user to manually test the change; only archive once they confirm it works (see Stage 6).

When you halt, state: the stage, what you found, the options, and your recommendation. Then wait.

## End-of-stage summary (every stage)

At the end of **every** stage — whether you halt or continue — give a short
**plain-English explanation of what just happened**, written for a non-engineer:

- 1–3 sentences, no jargon, framed as outcome ("I drafted the proposal describing
  X and why we need it" / "I implemented the change and all tests pass").
- Then the one-line status marker: **✅** stage completed cleanly · **⚠️** completed
  with a noted concern · **⛔** halted partway (stage **not** finished — paused for a
  decision). The marker is what resume keys on (see Stage sequence), so it must be
  accurate: use ⛔ whenever you stop to ask the user, even mid-stage.

If the stage completed cleanly with no judgment needed, give this summary and
immediately continue to the next stage. If you're halting, the summary precedes
the decision you're asking the user to make.

### Persist it to the flow journal

After emitting the summary, **append a block to `openspec/changes/<name>/.flow-journal.md`**
(create it on first write). This captures the transient narrative the spec artifacts
don't — decisions, halts, and why — so a `/clear` or auto-compaction loses nothing.
`openspec validate` only reads `proposal.md` / `design.md` / `tasks.md` / `specs/*/spec.md`
and never enumerates the change root, so the journal (like any extra file there) is simply
ignored — the dot-prefix isn't what hides it from validate; it just keeps the file out of
git/editors' way and signals "not a spec artifact." It archives with the change. Format:

```
## <stage> — <ISO timestamp>  [✅ / ⚠️ / ⛔]
Outcome: <1–3 sentence plain-English summary>
Decisions: <what was decided + why>   (or "none")
Halts: <what halted, the options, how it resolved>   (or "none")
Follow-up CRs: <name — one-line scope>   (or "none")
Modified files: <list>   (apply/fix stages only)
```

### Checkpoint-and-clear (backstop, not a halt)

The spine cannot `/clear` or `/compact` itself — those are user actions. So when context
is heavy at a **clean** stage boundary, after writing the journal, add a one-line non-halt
note: "Journal written — safe to `/clear` and resume with `from:<next-stage>`." Do not
stop for it; it's a recommendation, not a decision. Prevention (subagent delegation per
the Execution model) is the primary defense; this is the backstop.

## Stage sequence

<!-- DUPLICATED PROJECT FACTS — read before editing the stages below.

     Why they're here: this skill must survive a /clear or auto-compaction and resume
     from disk alone (see "Source of truth between stages" above). A subagent or a
     resumed spine may not have CLAUDE.md or the auto-memory in context, so the
     load-bearing project facts are inlined into the stages rather than referenced.
     That self-containment is deliberate.

     The cost is real: these are COPIES, and copies drift. CLAUDE.md + the auto-memory
     remain the source of truth — on any conflict, THEY win and this file is the stale
     one to correct. This is a maintenance burden, not a second source of truth.

     Embedded facts and where each lives (keep this map current when you touch them):
       - CR scope heuristics (one aggregate / one endpoint / one state change) .... Stage 1
       - UX hat + frontend stack (Vue 3 + Tailwind; composition API / script setup) Stage 1
       - Module boundaries (Spring Modulith; keep ModularityTests green) .......... Stage 3
       - Backend conventions (records for DTOs; constructor injection; state machine) Stage 3
       - eSign/webhook caution (async flow, HMAC-verify hooks, redact PII) ........ Stages 0/1/3 + Operating mode
       - Spec-review halting cap (≤5 rounds) ..................................... Stage 2
       - Git guardrails (no add/commit/push/branch) ............................. Git guardrails section

     When any of these change in CLAUDE.md or the auto-memory, update the copy here too. -->

Parse the user's input for a change name (kebab-case) and/or a requirement
description, and an optional starting stage. The legal `from:` tokens are exactly:
`explore | propose | review | apply | validate | fix | manual-test | archive` (the
implement stage's token is `apply`, not `implement`; the manual-test gate's token is
`manual-test`).

**Resume is journal-driven, not just `from:`-driven.** On entry, if
`openspec/changes/<name>/` already exists, **read `.flow-journal.md` first** (plus the
on-disk artifacts) and pick the resume point from the **last journal entry's status
marker** — even when no `from:` was given:
- **⛔ (halted partway)** → re-enter **that same stage** and act on the decision the
  user is now supplying in their resume message. The stage did not finish, so do not skip past it.
- **✅ / ⚠️ (stage completed)** → resume at the stage *after* it (a ⚠️ note carries forward as context).

An explicit `from:<stage>` is an **override** of that inferred resume point, not the only
way to resume. Only treat the run as a brand-new change when no directory exists for the
name. This makes resume self-healing after a `/clear` or auto-compaction — never assume
the prior transcript survives, always reconstruct from disk.

### Preconditions (verify once, on entry — before any stage)
Fail fast with a clean halt rather than erroring mid-flow:
- **`openspec` CLI present** (the validate/archive stages shell out to it). If missing, halt and say so.
- **Sibling skills** — this flow drives `openspec-explore | -propose | -apply-change | -validate | -archive-change` and `review-spec`. There's no reliable way to probe a skill's existence without invoking it, so don't fake an upfront check: if any Skill invocation fails to resolve at its stage, halt naming the missing skill rather than erroring on.
- **Change name is valid kebab-case.** If not parseable from input, ask (don't guess).
- **No silent collision.** If `openspec/changes/<name>/` exists, or any `openspec/changes/archive/*-<name>/` exists (archived changes are date-prefixed, e.g. `2026-04-09-<name>`, so match the suffix — a bare `<name>` will never hit), and the user gave no `from:`, this is a **resume**, not a new change — confirm intent before writing. A brand-new run requires a free name.

### 0. Explore (conditional)
If the requirement is fuzzy, or the user said "explore", invoke the **openspec-explore**
skill first to clarify intent. Skip if the requirement is already concrete and bounded.
Carry the clarified requirement into Propose.

### 1. Propose
Invoke the **openspec-propose** skill.
- Default → full set (proposal + design + tasks).
- If the user said "only" (e.g. "propose only") → proposal artifact only; skip design + tasks.
- Apply **CR scope heuristics**: a CR caps at roughly ONE of {one aggregate, one endpoint, one state-machine change}. If the requirement is bigger, propose a split into sequenced CRs and **halt** to confirm the slicing before generating artifacts for more than the first slice.
- For any UI-bearing change, wear the **UX hat** alongside the engineer hat (this is identity/legal infra — clarity and trust matter). Frontend is **Vue 3 + Vite + TypeScript + Tailwind**: composition API with `<script setup>`, Tailwind utilities for layout, API calls kept in `src/api/`.

### 2. Review
Invoke the **review-spec** skill on the proposed change. It runs its own review
subagent — consume the returned report and apply the Issue policy; do not re-wrap it.
- **Front-load the load-bearing round-1 checks**: cross-CR continuity, audit destinations, lock interactions, transactions, write authority, idempotency vs vendor switch, ordering vs rejection, conditional config tasks, racing timeouts, model relations.
- **Cap at 5 rounds.** Each round: review → address findings (in explore mode) → re-review. If the final round still surfaces only fresh prose-drift with no clear oracle, **stop reviewing** and default to "start implementing" — do not loop beyond the cap.
- Address findings between rounds via the **openspec-explore** skill (edit the artifacts), not ad-hoc.

### 3. Apply (implement)
Invoke the **openspec-apply-change** skill to work the tasks.
- Respect **module boundaries**: this is a Spring Modulith monolith — modules talk through public interfaces / events only, never reach into another module's internal packages. Every module change must keep `ModularityTests` green.
- Backend conventions to respect: prefer records for DTOs/value objects; constructor injection (no field `@Autowired`); package-private by default, `public` only on the module API; drive aggregate state through its state machine, not ad-hoc setters.
- If the change touches **signing / eSign**: it is asynchronous (never block a request thread on a signature — create the request, return the URL, let the webhook drive completion); treat inbound webhooks as untrusted and verify HMAC before acting; never log Aadhaar numbers, OTPs, VIDs, or full signer PII.

### 4. Validate (self-review)
Always run **openspec-validate** after Apply, **before** reporting done — inside a
general-purpose subagent (it's read-heavy; isolate it and fold back only the gap list).
Cross-check the actual diff against proposal + design + specs + tasks.

### 5. Fix
Resolve validation gaps under the Issue policy below. Re-run **openspec-validate**
once after fixing — **in a general-purpose subagent, same as Stage 4** (still
read-heavy; pass the resolved change name; fold back only the gap list). The fixes
themselves are spine writes; only the re-validation is isolated. If it's clean,
continue; if new non-minimal issues appear, **halt**.

### 6. Manual-test gate (hard halt — never skip)
Validate is a paper cross-check (diff vs. artifacts); it does **not** prove the change
works when run. So **before archiving, always halt and hand off to the user for manual
testing.** Archive is never automatic.

- State the change is implemented and validate is clean, then ask the user to **manually
  test it** and confirm before you archive.
- Give them a **concrete test recipe**: what to exercise, the relevant URLs/screens or
  console/test commands, and what a correct result looks like — derived from the change's
  tasks and specs (e.g. the screen/endpoint touched, the Gradle task or CLI command, the
  integration suite to run). For signing/eSign-adjacent work, call out the specific
  signing-flow states or webhook outcomes to eyeball.
- Mark the stage **⛔** in the summary and journal (it is a halt, the spine waits) and
  record the proposed test recipe under `Halts:`.
- **Resume:** the user replies that it works → proceed to Archive. If they report a
  defect, treat it as a new finding under the **Issue policy** (fold a minimal fix and
  re-validate, or split out a follow-up CR) — do **not** archive over a known break.

### 7. Archive
Only after validate is clean **and the user has confirmed manual testing passed**:
invoke the **openspec-archive-change** skill. Then produce the wrap-up (see Reporting).

## Issue policy (review + validation findings)

For each finding, in order:
1. **Minimal & clearly in-scope?** → fold the fix into the current change. No need to ask.
2. **Within the CR's slice but non-trivial?** → fix in-change, but note it in the halt/report.
3. **Crosses the scope heuristics** (another aggregate / another endpoint / a different state-machine change), or is risky/signing-flow-adjacent → **do NOT expand the current change.** Propose a new follow-up CR (name + one-line scope) and **halt** for the user to confirm before creating it.

Never co-ship unrelated work into one CR.

## Git guardrails (hard rules)

- **Never** `git add`, `git commit`, `git push`, or create/switch branches.
- Reading status/diff/log is fine.
- OpenSpec archive (moving files to `changes/archive/`) is a file operation and is allowed — it is NOT a git commit.

## Reporting

At every halt and at the end:
- One-line status per completed stage (✅ / ⚠️ / ⛔).
- Summary of all modified files.
- A **suggested commit message** (do not commit). Scope the commit to this change's
  slice — the user organizes by commit, not by thread, so bundle related work in the
  current change and keep follow-up CRs separate.
- If follow-up CRs were identified, list them with one-line scopes.

## Delegation

See **Execution model** above for what runs in a subagent vs. the main-thread spine.
In short: isolate read-heavy, report-producing work (validate; review already
self-isolates; propose's grounding fan-out) and fold back only the report. Keep every
halt/decision — and the apply stage itself — in the main thread.
