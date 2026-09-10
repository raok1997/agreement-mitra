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
- **Review** — `review-spec` **already spawns its own agents**: an Explore sweep for the
  grounding map, then **three persona agents in parallel**. Consume its returned report and
  apply the Issue policy. Do **not** wrap it in another agent — that would nest fan-out inside
  a subagent that cannot halt. It consolidates in fixed persona order, so its report and the
  journal entry do not depend on which agent finished first.
- **Validate** — run `openspec-validate` **inside a general-purpose subagent**, passing
  the **resolved change name explicitly**, and fold back only the gap list. The subagent
  has no `AskUserQuestion` and cannot halt to prompt, so validate must never reach its
  ambiguity branch — always hand it the name. It is read-heavy (it scopes and reads the CR's
  own evidence set — see its step 3 — then cross-checks it against all artifacts); isolating it
  keeps the spine small. The halts it implies are handled by the conductor
  in the Fix stage. (Subagents have the `Skill` tool, so invoking `openspec-validate`
  inside one is valid. Review is different: `review-spec` spawns its **own** agent, so a
  subagent would nest agents pointlessly and its findings need spine-side halt
  adjudication anyway — hence review stays in the spine.)
- **Code review (4c)** — run the `code-review` skill in a subagent, **handed this CR's file
  set as an explicit path target**. Fold back the findings only; the spine decides what to
  fold and what becomes a follow-up CR. Never let it default to the current diff or branch.
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
git/editors' way and signals "not a spec artifact." It archives with the change.

**Never hand-write the entry — generate it.** The heading format is load-bearing (resume reads
the last entry's status marker), and typing it by hand drifted in 5 of the first 21 runs. Use
the tool beside this skill, which emits the format and refuses a malformed entry:

```bash
node .claude/skills/openspec-flow/flow-journal.mjs append --change "<name>" <<'JSON'
{ "stage": "apply", "status": "ok",
  "outcome": "<1-3 plain-English sentences>",
  "decisions": "<what was decided + why, or omit>",
  "halts": "<what halted, the options, how it resolved, or omit>",
  "followUps": "`<register-slug>` — one-line scope, or omit",
  "modifiedFiles": ["backend/src/main/java/.../Foo.java", "CLAUDE.md"] }
JSON
```

- `stage` ∈ `explore | propose | review | apply | validate | fix | manual-test | archive`;
  add `"qualifier": "round 2"` for a repeated stage (→ `## review (round 2) — …`).
- `status` is `ok` (✅ clean) · `warn` (⚠️ completed with a concern) · `halt` (⛔ stopped
  partway). **Never leave an entry open** — a stage you did not finish is `halt`, saying what
  remains. There is no "in progress" status, by design.
- `modifiedFiles` is **required for apply and fix** and must be real repo-relative paths; the
  tool rejects "n/a" and "see above". It is what you reconcile against `tasks.md` when a stage
  ends — a path here that no task names is unplanned work, which validate's 3b independently
  re-checks against the working tree.
- `followUps` must name the **slug as it appears in the `## Follow-up register` table in
  `docs/ROADMAP.md`**, in backticks, not a prose description of it. Stage 7a's promotion gate
  matches on that text, so "the jurisdiction message thing" reads as unpromoted even when a
  row for it exists. If the follow-up is new, add the register row first, then cite its slug.

**Check the journal at every stage boundary**, so a malformed prior entry surfaces one stage
late rather than never. Run it *after* the append, so the entry you just wrote is included:

```bash
node .claude/skills/openspec-flow/flow-journal.mjs check --change "<name>"
node .claude/skills/openspec-flow/flow-journal.mjs audit          # every active change
node .claude/skills/openspec-flow/flow-journal.mjs followups      # not yet in the register
```

A non-zero exit from `check` means the journal's **integrity** is off — a heading it cannot
parse, or a file holding no stage entries. Fix the journal and carry on: it is **not** a
"hard error" under Operating mode and never halts the flow. An absent journal is not a
failure at all (`check` exits 0 and says so) — a change that predates the journal, or one on
its very first stage, has nothing to validate yet. Missing journals are `audit`'s business,
not `check`'s.

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
       - Build/scan gate commands (./run-tests.sh check; npm run build + lint) ... Stage 4a
       - eSign/webhook caution (async flow, HMAC-verify hooks, redact PII) ........ Stages 0/1/3 + Operating mode
       - Spec-review halting cap (≤5 rounds) ..................................... Stage 2
       - Scenario-coverage matrix is a gate; no UNMAPPED rows into Stage 3 ....... Stage 2
       - Coverage waiver rules (never waive money/PII/legal-validity paths) ...... Stage 2 + review-spec
       - Archive folds the spec of record — always via `openspec archive` ........ Stage 7
       - Git guardrails (no add/commit/push/branch) ............................. Git guardrails section

     When any of these change in CLAUDE.md or the auto-memory, update the copy here too. -->

Parse the user's input for a change name (kebab-case) and/or a requirement
description, and an optional starting stage. The legal `from:` tokens are exactly:
`explore | propose | review | apply | validate | fix | manual-test | archive` (the
implement stage's token is `apply`, not `implement`; the manual-test gate's token is
`manual-test`).

**Resume is journal-driven, not just `from:`-driven.** On entry, if
`openspec/changes/<name>/` already exists, **ask the journal tool where to re-enter** rather
than eyeballing the file:

```bash
node .claude/skills/openspec-flow/flow-journal.mjs last --change "<name>"
```

It returns `resumeAt` (⛔ → re-enter that same stage, since it did not finish; ✅/⚠️ → the
stage after it, a ⚠️ carrying forward as context), and exits non-zero with a `fallback` when
it cannot tell — no journal, no stage entries, or a last heading it cannot parse. **A
non-zero exit means use the artifact fallback below; it never means guess.** The tool
deliberately does not skip back to an older parseable entry: resuming from a stale stage is
worse than admitting the journal is unreadable. Then read the entry itself for the narrative.

**No journal? Infer from the artifacts instead — do not treat it as a new change.**
A change directory can exist with no `.flow-journal.md`: it was created outside the
flow (`/opsx:propose`), or predates the journal. This is the common case for changes
already in flight, so the fallback is not an edge case. Infer the resume point from
disk, and say which signal you used:

```bash
openspec status --change "<name>" --json    # artifact completion, applyRequires
```
- Some `applyRequires` artifact not `done` → resume at **propose**.
- All artifacts `done`, `tasks.md` all `- [ ]` → resume at **review** (it may already
  have been reviewed — say so and ask rather than silently re-running 5 rounds).
- `tasks.md` partly ticked → resume at **apply**.
- `tasks.md` fully ticked → resume at **validate**.

Never infer *past* the manual-test gate: Stage 6 is a hard halt and leaves no on-disk
trace, so a fully-ticked `tasks.md` means validate, never archive.

An explicit `from:<stage>` is an **override** of the inferred resume point, journal or
not. Only treat the run as a brand-new change when no directory exists for the name.
This makes resume self-healing after a `/clear` or auto-compaction — never assume
the prior transcript survives, always reconstruct from disk.

### Preconditions (verify once, on entry — before any stage)
Fail fast with a clean halt rather than erroring mid-flow:
- **`openspec` CLI present** (the validate/archive stages shell out to it). If missing, halt and say so.
- **Sibling skills** — this flow drives `openspec-explore | -propose | -apply-change | -validate` and `review-spec`. There's no reliable way to probe a skill's existence without invoking it, so don't fake an upfront check: if any Skill invocation fails to resolve at its stage, halt naming the missing skill rather than erroring on. **Archive is deliberately NOT a skill** — Stage 7 shells out to `openspec archive` (see Stage 7 for why).
- **Change name is valid kebab-case.** If not parseable from input, ask (don't guess).
- **No silent collision.** If `openspec/changes/<name>/` exists, or any `openspec/changes/archive/*-<name>/` exists (archived changes are date-prefixed, e.g. `2026-04-09-<name>`, so match the suffix — a bare `<name>` will never hit), and the user gave no `from:`, this is a **resume**, not a new change — confirm intent before writing. A brand-new run requires a free name.

### 0. Explore (conditional)
If the requirement is fuzzy, or the user said "explore", invoke the **openspec-explore**
skill first to clarify intent. Skip if the requirement is already concrete and bounded.
Carry the clarified requirement into Propose.

### 1. Propose
Invoke the **openspec-propose** skill.
- Default → full set (proposal + design + tasks).
- **There is no proposal-only mode here.** The flow drives a requirement to completion, and a
  proposal without `specs`/`design`/`tasks` cannot reach apply — the schema gates apply on
  `tasks`. If the user asks for a proposal alone, say so and point them at
  `/opsx:propose <name> only`, which is where that belongs; do not start a flow that cannot finish.
- Apply **CR scope heuristics**: a CR caps at roughly ONE of {one aggregate, one endpoint, one state-machine change}. If the requirement is bigger, propose a split into sequenced CRs and **halt** to confirm the slicing before generating artifacts for more than the first slice.
- For any UI-bearing change, wear the **UX hat** alongside the engineer hat (this is identity/legal infra — clarity and trust matter). Frontend is **Vue 3 + Vite + TypeScript + Tailwind**: composition API with `<script setup>`, Tailwind utilities for layout, API calls kept in `src/api/`.

### 2. Review
Invoke the **review-spec** skill on the proposed change, **passing the resolved change
name and telling it the caller is `openspec-flow`** (which suppresses its own
"how would you like to proceed?" prompt — that prompt otherwise routes around the
Issue policy and the round cap below). It runs its own review subagent — consume the
returned report and apply the Issue policy; do not re-wrap it.
- **Run the structural check first, and again at the end of every round:**
  ```bash
  openspec validate --strict --type change --no-interactive "<name>"
  ```
  It takes a second and catches a defect class prose review reliably misses — a
  requirement heading with no `SHALL`/`MUST`, a requirement with no scenario. Catching
  it here costs a line edit; catching it at Stage 4b costs a round trip, and missing it
  entirely puts an invalid requirement into the spec of record at archive.
- **Grounding is round 1's highest-yield input.** `review-spec` step 3 resolves every type,
  method, endpoint and property the artifacts name to what exists in the code *today* —
  existence, visibility, module, preconditions — and hands that map to the personas. This is
  what turns "the design assumes X is reachable" from a lucky catch into a systematic one; the
  two richest reviews in the archive are the two that did it, and both did it by improvisation.
  A spec claim contradicted by the map is a Critical finding before design is even discussed.
- **Front-load the load-bearing round-1 checks**: cross-CR continuity, audit destinations, lock interactions, transactions, write authority, idempotency vs vendor switch, ordering vs rejection, conditional config tasks, racing timeouts, model relations.
- **The scenario-coverage matrix is a gate, not advice.** `review-spec` step 2 assigns every
  `#### Scenario:` a disposition — `COVERED` / `GROUPED` / `MANUAL` / `WAIVED` — and writes the
  matrix into `tasks.md` as `## Coverage`. **Review does not close while any row is `UNMAPPED`.**
  This is the one gate that must be clean before Stage 3, because it is a contract agreed
  *before* code exists; discovering an untested scenario after implementation costs a round trip.
  A `WAIVED` row touching money, PII, or the document's legal validity is not acceptable —
  send it to `MANUAL` instead (see the skill for the full rules and the waiver-rate cap).
- **Cap at 5 rounds.** Each round: review → address findings (in explore mode) → re-review. If the final round still surfaces only fresh prose-drift with no clear oracle, **stop reviewing** and default to "start implementing" — do not loop beyond the cap. The cap does **not** license
  entering Stage 3 with `UNMAPPED` rows: that is a halt, not a finding to carry forward.
- Address findings between rounds via the **openspec-explore** skill (edit the artifacts), not ad-hoc.

### 3. Apply (implement)
Invoke the **openspec-apply-change** skill to work the tasks.
- **If it reports `state: "blocked"`** (missing artifacts), it — and the CLI's own
  `instruction` string — will tell you to use `openspec-continue-change`. That skill is
  **not installed here** and installing it is not the fix. Handle it in the spine: go
  back to Stage 1 and generate the missing artifacts with **openspec-propose**, then
  re-enter Stage 3.
- Respect **module boundaries**: this is a Spring Modulith monolith — modules talk through public interfaces / events only, never reach into another module's internal packages. Every module change must keep `ModularityTests` green.
- Backend conventions to respect: prefer records for DTOs/value objects; constructor injection (no field `@Autowired`); package-private by default, `public` only on the module API; drive aggregate state through its state machine, not ad-hoc setters.
- If the change touches **signing / eSign**: it is asynchronous (never block a request thread on a signature — create the request, return the URL, let the webhook drive completion); treat inbound webhooks as untrusted and verify HMAC before acting; never log Aadhaar numbers, OTPs, VIDs, or full signer PII.

### 4. Validate (self-review)
Three gates run here, and they answer different questions: **does it run** (4a), **is it what
we said we'd build** (4b), **is it any good** (4c). None has to be clean to *enter* Stage 5 —
Stage 5 is where their findings get resolved — but all three must be clean before the Stage 6
manual-test gate. Run them in order; 4b and 4c are subagents and can be launched together only
if you accept 4c reviewing code 4b may still find non-conformant.

**4a. Build gate — run in the spine, not the subagent** (subagents never write, and this
compiles/executes). For backend changes run `./run-tests.sh check` from `backend/` (it
resolves the Docker socket for Testcontainers and runs tests + coverage + `securityScan`);
for frontend changes run `npm run build` **and** `npm run lint` from `frontend/`.
`build` already chains `security:scan && test && vue-tsc -b && vite build`, so running
`npm run security:scan` separately afterwards is redundant — but it does **not** run
eslint, so `lint` is a separate command, not an optional extra.

A **build/test failure is a hard error → halt** (per Operating mode); it is not a
"gap" to be carried into Stage 5 alongside the spec findings. Skip this gate only for pure
**config/docs/harness** changes (the same exemption CLAUDE.md's `tasks:` rule uses), and
record the skip with a one-line reason in the summary and journal.

**4b. Spec conformance** — run **openspec-validate** inside a general-purpose subagent
(it's read-heavy; isolate it and fold back only the gap list). Pass the **resolved change
name explicitly**. It answers the three questions nothing else asks:

- **Scope exclusions** — the proposal's "out of scope: X", verified as NOT implemented. This
  is the check that keeps a CR from quietly growing, and it has no other home.
- **Design decisions vs code** — a task can be ticked while the code took a different approach.
  Nothing else reads `design.md`.
- **Task conformance** — for every ticked task naming a file, migration, class or test, that
  artifact must exist and do what the task says; every `COVERED` row in the `## Coverage`
  matrix must name a test that exists and ran green in 4a. A ticked box is a claim by the
  agent that wrote the code — the least independent evidence in the change.

**Changes that predate the coverage matrix.** A change whose `tasks.md` has no `## Coverage`
section was written before this gate existed — every change already in flight is in that
state. That is an **observation, not a gap**: do not report the contract as unfulfilled when
no contract was ever written. Offer to build the matrix (`review-spec` step 2 works on a
change at any stage) and let the user decide; if they decline, validate the rest and say the
coverage line is `n/a — pre-matrix change`.

It scopes itself from the change's **task manifest**, not from git. Do **not** hand it a diff
or tell it to compare against `main`: a commit range cannot isolate a CR that shares commits
with its siblings, misses everything uncommitted, and reads differently on a branch than on
the default branch. The manifest has none of those failure modes.

**4c. Code review** — run the **code-review** skill over this change's code, in a subagent.

**Pass it the CR's file set as an explicit path target.** Left to its own devices it reviews
the current diff or branch, which on a long-lived branch is many CRs and much archived work —
the exact failure 4b was rebuilt to avoid. Take the file set from the same task manifest 4b
uses. (The same caution applies to `security-review`, which reviews "pending changes on the
current branch" by default; run it for signing/eSign/PII-adjacent changes with the same
scoping discipline.)

Run 4c **after** 4b: there is no point reviewing the quality of code that does not yet match
what it was supposed to be.

**Bugs get folded; cleanups get noted, not fixed** — unless the cleanup is in code this CR
already touched. A quality review generates improvements, and acting on all of them is how a
CR quietly doubles in size and stops matching its commit. Anything larger goes through the
Issue policy as a follow-up CR.

Validate and code review are **paper and static** checks — 4a is what proves the code runs.
None of them substitutes for the manual-test gate in Stage 6.

### 5. Fix
Resolve gaps from all three Stage 4 gates under the Issue policy below. The fixes themselves
are spine writes; only the re-checks are isolated.

Re-run after fixing:
- **4a** always — may be scoped to the affected module/suite, unless the fix touched build or
  dependency config, in which case run the full gate.
- **4b** always — a fix changes the code the conformance check was reading.
- **4c** only if the fix changed code (skip it for an artifact-only fix, e.g. correcting a
  design decision the code already followed). Re-review the changed files, not the whole CR.

If a fix adds or removes a test, **update the `## Coverage` matrix in `tasks.md` to match** —
the matrix is the contract, and a stale one silently re-opens the gap it was written to close.

If clean, continue; if new non-minimal issues appear, **halt**.

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
Only after validate is clean **and the user has confirmed manual testing passed.**

Archiving folds this change's delta specs into `openspec/specs/` — the project's
spec of record. **Use the `openspec archive` CLI to do it.** Do not hand-merge the
deltas, and do not delegate the merge to a subagent: the CLI parses each delta,
rebuilds the target spec, validates the rebuilt text, and **aborts without writing
anything** if it does not hold (`ADDED` that already exists, `MODIFIED`/`REMOVED`/
`RENAMED` whose target is absent, a rebuilt spec that fails validation). A
hand-rolled `mv` has none of those gates — that is how six capabilities were once
archived without ever reaching the baseline (see `openspec/BASELINE-FOLD-GAP.md`)
and how a requirement missing its `SHALL` sat in the spec of record for two months.

**7a. Pre-archive checks.** Two, and both must pass before the CLI runs.

**(i) Promote follow-ups into the register.** The journal's `Follow-up CRs:` line
archives *with the change* — into `openspec/changes/archive/`, where nothing reads it
again. So a follow-up that lives only in the journal is durable and invisible: 70
follow-up mentions sit in the archived journals, and the only ones that survived say so
explicitly ("pre-recorded in the roadmap memory"). Promotion is what makes the flow's
`followUps` field mean anything.

```bash
node .claude/skills/openspec-flow/flow-journal.mjs followups --change "<name>"
```

Non-zero means this change records a follow-up the register does not hold. Add a row to
the **`## Follow-up register`** table in `docs/ROADMAP.md` — slug, one-line scope, raised-by,
date, priority — then re-run until clean. Do **not** archive over an unpromoted follow-up.

- **Write the register slug verbatim in the journal**, in backticks. The check is textual, so
  a paraphrase does not count: the first run of this command flagged a follow-up that *was*
  in the register, because the journal said "jurisdiction problem-type plumbing" while the
  register said `agreement-error-problem-type-plumbing`.
- **Record follow-ups before you reach 7a.** This gate reads the journal as it stands, and
  the archive-stage entry is written *after* the fold — so a follow-up first noticed during
  Stage 7 is not visible here. If Stage 7 itself surfaces one, promote it by hand and re-run
  `followups --change archive/<stamped-name>` after 7b, or it is lost the same way.
- `flow-journal.mjs followups` with no `--change` sweeps everything. Active changes fail it;
  archived ones are reported as historical only, since they can be mined but not fixed in
  place — a permanently-red gate is one people learn to ignore.

**(ii) Check delta ordering.** A `MODIFIED`/`REMOVED`/`RENAMED` delta
needs its target requirement to already exist in the baseline. When a sibling
change *adds* that requirement, the two must archive in dependency order — and a
teammate archiving on the default branch can consume the same capability.

```bash
# capabilities this change touches
ls openspec/changes/<name>/specs/
# for each capability: which other ACTIVE changes also hold a delta for it?
grep -rl . openspec/changes/*/specs/<capability>/spec.md 2>/dev/null | grep -v "/<name>/"
```

For each non-`ADDED` section in this change's deltas, confirm the named requirement
is present in `openspec/specs/<capability>/spec.md`. If it is missing **and** a
sibling active change `ADDED` it, **halt**: name the sibling and the required order.
Do not archive around it, and do not "fix" it by rewriting the delta to `ADDED` —
that lands a partial capability that looks folded.

**7b. Archive, then stamp.** The CLI names the directory `YYYY-MM-DD-<name>`
(date only). This project stamps archives with the full GMT date-time, so rename
after the CLI has done the merge:

```bash
openspec archive -y "<name>"        # merges deltas + validates + moves; prints the fold counts
# The CLI prints: Change '<name>' archived as '<YYYY-MM-DD>-<name>'.
# Take the date from the directory it actually created — never recompute it.
# Recomputing with `date -u +%F` can disagree with the CLI across UTC midnight.
created=$(ls -d openspec/changes/archive/[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]-<name>)
mv "$created" "$(dirname "$created")/$(basename "$created" "-<name>")-$(date -u +%H%M%SZ)-<name>"
```

- Never pass `--no-validate`. Never pass `--skip-specs` unless the change genuinely
  has no delta specs — that flag is the documented cause of the baseline fold gap.
- `-y` suppresses the interactive confirmations, so **read the CLI's output**: it
  reports which capabilities it will create vs update and the `+ ~ - →` fold totals.
  Put those totals in the summary and journal. If it prints `Aborted. No files were
  changed.`, that is a hard error → **halt** with the message; it means 7a missed
  something.
- If the CLI's own `YYYY-MM-DD-<name>` target already exists, it fails rather than
  overwriting — halt and report, don't work around it.
- **`-y` does not weaken validation.** `skipValidation` is set only by `--no-validate`
  (`archive.js:78`), so the rebuilt-spec check still runs and still aborts before any
  write. That gate is the whole reason to go through the CLI — never trade it away.
- **The fold rewrites `openspec/specs/` and is left uncommitted.** That diff — not the
  directory move — is the most consequential output of the entire flow, and nothing
  reviews it between the CLI and the baseline. Show `git diff --stat openspec/specs/`
  in the wrap-up and name the capabilities touched, so the user reviews the spec-of-record
  change before committing it (per the Git guardrails, you never commit it yourself).

Then produce the wrap-up (see Reporting).

## Issue policy (review + validation findings)

For each finding, in order:
1. **Minimal & clearly in-scope?** → fold the fix into the current change. No need to ask.
2. **Within the CR's slice but non-trivial?** → fix in-change, but note it in the halt/report.
3. **Crosses the scope heuristics** (another aggregate / another endpoint / a different state-machine change), or is risky/signing-flow-adjacent → **do NOT expand the current change.** Propose a new follow-up CR (name + one-line scope) and **halt** for the user to confirm before creating it.

Never co-ship unrelated work into one CR.

## Git guardrails (hard rules)

- **Never** `git add`, `git commit`, `git push`, or create/switch branches.
- Reading status/diff/log is fine.
- OpenSpec archive (`openspec archive`, which folds the delta specs and moves the change
  into `changes/archive/`, plus the GMT rename) is a file operation and is allowed — it is
  NOT a git commit. The user commits the result themselves.

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
