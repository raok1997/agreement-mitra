# Skill decisions register

Adjudication record for `.claude/skills/` and `.claude/commands/opsx/`. These are prose
instruction files with no test suite, so review is their only quality gate — and an
unbounded one: a reviewer asked to critique ~500 lines of dense prose will always find
something. This file is what makes a review *close*.

## Rules

1. **Read this file before reviewing any skill.** A finding listed here as `accepted` or
   `deferred` must **not** be re-raised without new evidence — a concrete failure, or a
   change to the surrounding code that invalidates the verdict. Say which entry you are
   overturning and why.
2. **Review the diff since the last commit, not the whole file.** Three of these skills went
   uncommitted from 2026-06-20 to 2026-09-10 while accumulating +606/−224; with no baseline,
   every round re-reviewed the whole file and no finding was ever recorded as settled.
3. **Land a fix's blast radius in the same round.** This is the rule that would have prevented
   most of round 3. When you:
   - delete a skill or command → grep the repo, `README.md`, `CLAUDE.md`, the auto-memory
     (`~/.claude/projects/*/memory/`), and `~/.config/openspec/config.json` for references;
   - add or split a stage → define its input handoff explicitly (what the subagent is given);
   - fix a duplicated fact → fix **every** copy (`CLAUDE.md`, `openspec/config.yaml`,
     the flow skill's `DUPLICATED PROJECT FACTS` block, the auto-memory).
4. **Cap tooling review at 2 rounds per change**, mirroring what Stage 2 imposes on CRs.
   Then commit and stop. Remaining findings land here as `deferred`, not as more edits.
5. **Append, don't rewrite.** This file is the audit trail for the tooling, the way
   `.flow-journal.md` is for a change.

Verdicts: `fixed` · `accepted` (real, deliberately not changing) · `deferred` (real, scheduled
for a named later round).

---

## Round 1 — 2026-09-09 · session `e29956a9`

| # | Finding | Verdict | Note |
|---|---|---|---|
| 1.1 | `openspec-validate` step 3 scoped its evidence with a hardcoded `git diff` — a commit range cannot isolate a CR that shares commits with siblings, misses uncommitted work, and reads differently per branch | **fixed** | replaced by the task-manifest scope; guardrail added banning `git diff <base>` |

## Round 2 — 2026-09-09 · session `79bd53f9`

| # | Finding | Verdict | Note |
|---|---|---|---|
| 2.1 | Stage 7 delegated archive to a skill that hand-rolled `mv`, skipping the CLI's validated delta fold | **fixed** | Stage 7b now runs `openspec archive -y`, then renames for the GMT stamp |
| 2.2 | No archive-ordering precheck; `MODIFIED` deltas can name a requirement only a sibling active change `ADDED` | **fixed** | Stage 7a |
| 2.3 | Three handoffs broken: Stage 7 name, frontend `npm run lint`, `review-spec` prompt bypassing the Issue policy | **fixed** | |
| 2.4 | Resume undefined when a change has no `.flow-journal.md` — 21 archived journals, zero across active changes | **fixed** | artifact-state fallback via `openspec status --json` |
| 2.5 | `review-spec` step 1 hardcoded `specs/spec.md`, which has never existed → reviews silently read zero deltas | **fixed** | takes the glob from `openspec instructions apply --json` |
| 2.6 | `openspec-validate` frontmatter still stamped `generatedBy`, implying the CLI owns a hand-maintained fork | **fixed** | re-stamped `author: custom` with a comment |
| 2.7 | `review-spec` had no `opsx:` command | **fixed** | added `commands/opsx/review.md` |
| 2.8 | `openspec/config.yaml` FSM missing `STAMPED`/`STAMP_FAILED` — injected into every generated artifact | **fixed** | corrected against `SignatureStatus.java` |

## Round 3 — 2026-09-10 · session `061eca93`

Six of the seven findings below are second-order consequences of round 2's own fixes, not
regressions and not repeats. That is what motivated rule 3 above.

| # | Finding | Verdict | Note |
|---|---|---|---|
| 3.1 | Auto-memory `archive-skill-gmt-stamp` said "do NOT archive via the `openspec archive` CLI" — the inverse of the new Stage 7 — and loaded every session | **fixed** | rewritten as `archive-gmt-stamp`; CLI-first, rename after |
| 3.2 | `~/.config/openspec/config.json` still listed `archive` in `workflows`, so the next `openspec update` would regenerate the deleted skill and command | **fixed** | `archive` deselected; backup at `config.json.bak-20260910`. Deselection also makes `update` *remove* the artifacts if they reappear |
| 3.3 | Stage 4b requires every `COVERED` test to have "run green in 4a", but the 4b subagent never receives 4a's result | **deferred** | handoff round. Fix: 4b's prompt carries the 4a outcome; `openspec-validate` step 5 gains an `exists; green-status not supplied` verdict instead of *verified* |
| 3.4 | The `only` keyword (proposal artifact alone) has no terminal halt — Stage 2 then validates a change missing artifacts, and Stage 3's blocked-handler sends it back to generate exactly what `only` excluded | **fixed — by removal, 2026-09-10** | Not fixed by adding a halt: `only` is incoherent *inside the flow* regardless of use. The flow exists to drive to completion, and `only` makes apply structurally unreachable, so a halt would be code supporting a mode that should not exist here. Deleted from Stage 1 and `commands/opsx/flow.md`, which now point at `/opsx:propose <name> only`. The user's **global** `~/.claude/CLAUDE.md` "Proposal Scope" rule is untouched and still governs `/opsx:propose` — different scope, and coherent there |
| 3.5 | `commands/opsx/review.md` says "Critique only: it never rewrites artifacts", but `review-spec` step 2.4 mandates writing `## Coverage` into `tasks.md`; the pre-existing guardrail "Do NOT rewrite artifacts without user confirmation" contradicts it too | **deferred** | handoff round. Two-line fix, but it is an edit to a skill under a stop-editing freeze |
| 3.6 | `CLAUDE.md`'s FSM line read `DRAFT → PDF_GENERATED → STAMPED → …`; `DRAFT` is reserved and unused per `SignatureStatus.java:4-6`. Round 2 fixed `config.yaml`'s copy only — while *adding* the instruction to keep them in sync | **fixed** | **Verdict overturned 2026-09-10 under rule 1.** Originally deferred as cosmetic. The 3.9 probe showed subagents receive the whole `CLAUDE.md` but *not* `openspec/config.yaml`, so the corrected copy was unreachable and the stale one was the only FSM any flow subagent ever saw. Actively misdirecting → fixed outside the round cap |
| 3.7 | `openspec archive` returns exit 0 on both abort paths (`archive.js:139`, `:236`), so `$?` cannot detect a failed fold. Stage 7b's "read the CLI's output" advice is right but does not say why | **deferred** | handoff round. Add one sentence to 7b |
| 3.8 | Minors: orphaned `/opsx:archive` in `README.md:241`; `openspec-validate:208` still says "if called from archive"; `code-review` undeclared in the flow's `compatibility:`/preconditions; caller identity is an unnamed parameter in `review-spec`'s Input; `commands/opsx/flow.md` omits the manual-test gate; 7b `ls -d` glob and `date -u +%H%M%SZ` edge cases; `.flow-journal.md` vs legacy `flow-journal.md`; version stamps not bumped consistently | **deferred** | batch into the handoff round |
| 3.9 | The `DUPLICATED PROJECT FACTS` block is a finding generator by construction — its own comment concedes "these are COPIES, and copies drift" — and its stated justification (surviving a `/clear`) is already wrong for the spine, since `CLAUDE.md` auto-loads every session | **deferred** | its own scoped round — but the gating check is **settled (2026-09-10, probe agent `afff52b1`, no tools used)**. A `general-purpose` subagent receives the **whole** project `CLAUDE.md` (verified by verbatim quotation of late-file passages), the user's global `CLAUDE.md`, and the auto-memory **index**. Therefore the entire `DUPLICATED PROJECT FACTS` block is redundant and goes. See "Subagent context" below for what they do *not* get |
| 3.10 | The flow journal's existence and heading format depend on the model following a paragraph: zero journals across 10 active changes, and the skill records 5-of-21 heading drift (~76% compliance at 265 lines; the file is now 475) | **fixed** | Round B, below — `flow-journal.mjs` |

### Subagent context — what a spawned agent is and isn't given

Measured 2026-09-10 by a `general-purpose` probe agent instructed to use no tools and answer
only from context. Re-measure if the harness changes; until then treat this as settled.

**Given:** the whole project `CLAUDE.md` (not a summary — it quoted passages from the end of
the file); the user's global `~/.claude/CLAUDE.md`; the auto-memory `MEMORY.md` **index**; the
environment block, git-status snapshot, scratchpad path, date; the skills roster as
*descriptions only*.

**Not given:** individual memory **bodies** (only the index lines); any `openspec/` content,
including `config.yaml`'s `context:` block — that reaches an agent only via an
`openspec instructions` call; skill bodies (a skill's text arrives only when invoked); any
source files, docs, or diff bodies.

Consequences for the skills:
- Inlining project facts into a skill "so subagents have them" is **unnecessary** — hence 3.9.
- A fact that lives **only** in `openspec/config.yaml` or **only** in a memory body is *not*
  in subagent context. `CLAUDE.md` is the reliable channel; that is what made 3.6 harmful.
- Handing a subagent a *skill name* does not hand it that skill's rules — it must invoke it.

### Verified as correct in round 3 — do not re-derive

- `skipValidation` is set only by `--no-validate` (`archive.js:78`); `-y` does not weaken validation.
- `-y` leaves spec updates on (`archive.js:198`).
- `WORKFLOW_TO_SKILL_DIR` (`profile-sync-drift.js:10-22`) omits `openspec-flow`, `review-spec`
  and `openspec-validate` — they are durable across `openspec update`.
- Archive aborts before writing on both the pre-flight and rebuilt-spec checks.
- `npm run build` chains `security:scan && test && vue-tsc -b && vite build`; `lint` is separate.
- `openspec instructions apply --change X --json` returns `contextFiles.specs` as a
  `specs/**/*.md` glob, with its progress preamble on stderr (safe to pipe to `jq`).

---

## Round B — 2026-09-10 · mechanize the flow journal (closes 3.10)

`.claude/skills/openspec-flow/flow-journal.mjs`. Node (not bash) because entries carry
multi-line prose and status markers are emoji — JSON on stdin survives both; the precedent is
`frontend/scripts/security-scan.mjs`.

| Command | Purpose |
|---|---|
| `audit` | sweep every active change — **the command that actually closes 3.10** |
| `append --change <n>` | write one validated entry from JSON on stdin |
| `last --change <n>` | where resume should re-enter, as JSON; non-zero = use the artifact fallback |
| `check --change <n>` | validate one journal |

**Why `audit` is the headline, not `append`.** A script the agent must remember to call has the
same failure mode as a format it must remember to follow — it only validates the calls that
happen. `audit` needs nobody to remember anything: it makes a missing journal visible. The
failure being fixed was *zero journals across ten active changes*, which `append` cannot detect.
The skill now runs `check` at every stage boundary so a gap surfaces one stage late, not never.

**Three drift classes, deliberately distinguished** (measured against the archives):

- `canonical` — what the tool writes.
- `tolerated` — parseable but non-canonical: `[✅ skipped]`, `[⛔ halt: confirm scope split]`.
  `last` accepts these so resume never breaks on an existing archive; `check`/`audit` still
  report them, which is how you tell whether drift is *still happening* after this landed.
- `drifted` — no marker where one belongs: `## explore — skipped`, `## apply — [done]`.

**`last` refuses rather than guesses.** If the final heading is unparseable it returns
`resolvable: false` with a fallback, and does **not** skip back to an older parseable entry —
resuming from a stale stage is worse than admitting the journal is unreadable. Verified against
a fixture whose tail is `## apply — [done]`: it declines rather than resuming at `review`.

**Bug found and fixed while building.** The first classifier dropped `## propose (grounding) — …`
and `## review (resolution) — …` as non-stage headings, undercounting a real journal 8/10 — and
had such an entry been last, `last` would have silently skipped backwards. A parenthetical
qualifier is now a supported shape (`"qualifier": "round 2"`), which also gives multi-round
review stages a proper form instead of freehand text after the marker.

**Verdict corrected — 3.8's journal-filename item.** The six undotted `flow-journal.md` files in
the archive are **not** legacy journals: they hold no stage headings at all (`## 1. The login
journey`) — they are misnamed design/handoff notes. So `check` must *not* fall back to reading
them, or it would parse a design doc as a journal. It reports "no stage entries" instead. Drop
the "dotted vs legacy undotted" item from 3.8; there is no naming variant to support.

**Baseline at landing:** 10 active changes, **10 with no journal**. Archived journals: 21
dotted (5 with drift), 6 misnamed design docs.

**Scope note for Round A.** Round B already rewrote two sections Round A had planned to shrink —
"Persist it to the flow journal" (the format block and the drift war story are gone, replaced by
the tool call) and the journal-driven resume paragraph. Do not re-plan those. The file is 486
lines; Round A's cuts still apply to the facts block, the remaining war stories, and the
sibling-skill re-explanations.

**Not done, deliberately:** no enforcement hook. A `Stop` hook could flag a session that touched
a change directory without appending an entry, but it would misfire on read-only sessions and
on multi-session stages. `audit` gives the same signal on demand without the false positives.
Revisit only if audit-on-demand proves insufficient.
