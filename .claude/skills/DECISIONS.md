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

---

## Round C — 2026-09-10 · the follow-up register (first end-to-end flow run)

The flow's **first real end-to-end exercise**, driving `contacts-editable-until-payment` from
its last open task to archive. Round B's tooling held: `last` fell back correctly on a
journal-less change, `append`/`check` stayed canonical across five stages, Stage 7a's ordering
check cleared the change against two siblings, and manifest-scoped subagents kept both the
conformance and code-review passes out of the three sibling changes sharing the branch.

Two structural pieces landed (user-authorised, lifting the `8c776d3` freeze for these only —
**Round A stays parked**; its cuts to the facts block and war stories were not in scope).

| # | Finding | Verdict | Note |
|---|---|---|---|
| C.1 | `followUps` was write-only. The journal records it, then archives **with the change** into `openspec/changes/archive/`, where nothing reads it again — 70 follow-up mentions are buried there, and the only survivors say so explicitly ("pre-recorded in the roadmap memory"). Three registers existed (ROADMAP prose blob, per-user agent memory, archived journals), which is the same as none | **fixed** | `## Follow-up register` table in `docs/ROADMAP.md` (single home — in git, reaches a teammate on `main`, already canonical in `CLAUDE.md`); `flow-journal.mjs followups`; Stage 7a gate. Blast radius landed same round: `CLAUDE.md`, the `best-practices-hardening-roadmap` memory demoted to history with a pointer header, new `follow-up-register` memory, `MEMORY.md` index |
| C.2 | Stage 7 had no promotion step, so C.1's leak had no gate | **fixed** | Stage 7a is now two checks: (i) promote, (ii) delta ordering. **Deliberately not renumbered** — DECISIONS rows 2.1, 3.7 and 3.8 name "7b" as the archive step and rule 5 makes this file append-only, so 7b keeps its number |
| C.3 | Deferred **3.3 confirmed by observation**: 4a's build result has no channel to the 4b subagent. This run only produced the right verdict because the instruction was hand-written into the prompt | **deferred** | still the handoff round. Evidence upgraded from predicted to observed — the subagent correctly emitted `exists; green-status not supplied`, but nothing in the skill makes that happen |
| C.4 | No path for a gate that is red for reasons the CR did not cause. `npm run build` chains a repo-wide `security:scan`, so a `vitest` advisory fails 4a for **every** change until someone bumps it; Operating mode says hard error → halt, with no "not yours" branch | **deferred** | handoff round. Needs a "pre-existing failure, attributed and recorded" verdict alongside halt |
| C.5 | Chained gates hide their own later links. Both `npm run build` and `./run-tests.sh check` abort at the first failure, so a red scan means `vue-tsc`/`vite build` never ran and a red test means JaCoCo + `securityScan` never ran — "the build failed" then says nothing about the rest. Recovered by hand here | **deferred** | handoff round. Stage 4a should name `build:only` and a separate `securityScan` re-run |
| C.6 | Stage 5's re-run rule over-fires: "re-run 4c if the fix changed code" spawns a code-review agent over a test-only fix the spine wrote and verified itself | **deferred** | handoff round. Skipped deliberately on this run and disclosed |
| C.7 | The `## Coverage` gate proves a test **exists**, not that it **asserts** anything. This change's row 9 read `COVERED` while its terms-freeze test called an `.authenticated()` route anonymously, took the 403, and asserted only "not 200" — it would have passed with the freeze deleted | **accepted** | Real, and not fixable in the matrix: a disposition table cannot evaluate assertions. Recorded as a known limit of the gate; the defence is 4b reading what a named test actually does, which is what caught it. Do not re-raise as a matrix defect |
| C.8 | The post-edit formatter strips imports added ahead of their use, so a two-step "add import, then add the field" edit fails to compile in between | **accepted** | Environmental, not a skill defect. Add the usage first, or both in one edit |
| C.9 | Deferred **3.9 unchanged**: the `DUPLICATED PROJECT FACTS` block is still in `SKILL.md` despite being measured redundant in round 3 | **deferred** | Round A, still parked |

**Verified while building `followups` — do not re-derive.**

- The check is **textual coverage, not slug extraction**, by design. The archived lines are free
  prose (`frontend-security-scanning (CR-6) — frontend dep scan; ci-pipeline (CR-7) — …`) and
  would defeat any tokenizer; a wrong extractor is worse than an honest "this line names nothing
  in the register". Consequence: `followUps` must cite the slug **verbatim**, which is now stated
  in the skill, the field's inline doc, and the tool's own failure message.
- **First run proved the point on itself.** The change archived minutes earlier flagged as
  unpromoted because its journal said "jurisdiction problem-type plumbing" while the register row
  said `agreement-error-problem-type-plumbing`. True positive against the stated contract.
- **A missing register is fatal, never an empty set** — an empty set would reclassify every
  follow-up in the repo as unpromoted, reading as a catastrophic leak when the fault is a moved
  path. Both `die()` paths tested against a synthetic repo.
- **Exit codes split deliberately**: `--change` fails on any unpromoted follow-up (the Stage 7a
  gate); the repo-wide sweep fails only on **active** changes and reports archived ones as
  historical. Archived follow-ups can be mined but not fixed in place, and a permanently-red
  sweep is a gate people learn to ignore.
- **Ordering constraint, now written down in 7a:** the gate reads the journal as it stands, and
  the archive-stage entry is written *after* the fold — so a follow-up first noticed during Stage
  7 is invisible to it. 7a says to promote by hand and re-run against the stamped archive path.
- Verified against the real fixture set (51 archived changes, 9 active): 15 historical unpromoted
  follow-ups surfaced, including `bump-spring-boot-security-patches`, `ci-pipeline` and
  `frontend-security-scanning`; `audit`, `check`, `last` and the usage line all still behave.

**Baseline at landing:** register holds 6 slugs · 0 unpromoted on active changes · 15 unpromoted
in the archive (historical, available to mine).

---

## Round 6 — 2026-09-11 · session `9ecbca31` · token cost of the flow

Not a spec review — a **measured** cost audit of the refactored flow, prompted by two simple
CRs exhausting a usage limit on 2026-09-11. Evidence is the usage records in
`~/.claude/projects/-Users-janu-Desktop-code-agreement-mitra/*.jsonl`, 17 sessions from
2026-09-04 onward. Findings are numbered as elsewhere; verdicts as usual.

| # | Finding | Verdict | Note |
|---|---|---|---|
| 6.1 | Stage 2 had **no proportionality gate**: `frontend-dev-dep-refresh` (a dependency bump) drew 3 review rounds and 8 persona agents — more than the `jurisdiction-checkout-gating` feature got at 2. Stage 4a already defines a config/docs/harness exemption class; Stage 2 did not reuse it | **deferred** | Tiering (full three-persona for behavioural changes; one round, Security-only for config/docs/harness) was agreed but the edit was rejected twice at apply time and is not in the file. **Not the PII/secret guard** — the identical text writes cleanly under the hook, tested both in and out of the project tree — so the rejection was user-side and its intent is unresolved. Carry to round 7. **This was the round's largest saving**: without it a config CR still draws 3 personas per round |
| 6.2 | The ≤5-round cap is a direct multiplier — at three personas per round it authorises up to **15 subagents per CR** — and rounds past the second returned prose-drift | **fixed** | Lowered to **3**. All four copies updated: flow `SKILL.md` (Stage 2, the `DUPLICATED PROJECT FACTS` map, the resume note), `review-spec/SKILL.md` Step 7, `commands/opsx/flow.md`. Auto-memory `openspec-flow-review-cap` rewritten |
| 6.3 | Checkpoint-and-clear was **advisory and therefore never acted on**. Splitting both 2026-09-11 sessions at the `openspec-apply-change` invocation: apply began carrying **224k / 342k** of review context and re-sent it across ~200 further requests — **~40M / ~55M tokens**, the largest single line item in either session | **fixed** | Made a ⛔ halt at the review → apply boundary **only**; every other boundary keeps the advisory note. Resume is `/clear` then `/opsx:flow <name> from:apply`. Rests on the skill's own "source of truth is the change directory on disk" contract — so the fix is a transcript discard, not an information discard |
| 6.4 | The clear's one real exposure: a review decision that lives **only** in the transcript (a deferred finding, an approach settled by hand) does not survive it | **fixed** | Checkpoint-and-clear now requires a fuller journal entry before this halt, with those under `decisions:` |
| 6.5 | **Subagent token usage is not in the transcripts at all** — no `isSidechain` entries in any file, and the persona preamble appears only in parent sessions as the `Agent` prompt. The 122M / 94M per-session figures are **floors**, excluding 15 and 10 subagents respectively | **accepted** | Nothing to fix in the skills; recorded so no future round mistakes the logged totals for actual cost. It also means 6.1's saving is larger than the logged numbers can show |
| 6.6 | Fable 5.1 is an independent multiplier, not a flow defect: 3,927 output tokens/request against a 494–1,080 band across 15 Opus sessions, ~90% of it non-persisted thinking, driving average context to 352k (band: 91–255k) | **accepted** | Model choice, not tooling. Keep Fable off `opsx:flow` until round counts settle. The same-day Opus session was in-band on every *per-request* metric — its cost was **volume** (438 requests for a dep bump), which is what 6.1–6.3 target |

**Round cap note:** this round made three edits against rule 4's two-round budget for a single
change; 6.1 is explicitly carried rather than retried, keeping the round closed.

**Post-landing checks (same round):**
- `flow-journal.mjs last` returns `resumeAt: "apply"` for a journal ending in
  `## review (round 2) — … [✅]` — verified against a synthetic journal, so the mandatory
  clear resumes correctly on the journal path.
- The **no-journal artifact fallback** was the one hazard: "all artifacts done + no ticked
  tasks → resume at review" is the exact state the clear leaves behind. Hardened in the same
  round to ask rather than silently re-run the personas.
- The 6.1 rejection was tested against `.claude/hooks/pii-secret-guard.sh` and is **not** a
  hook deny; recorded so a future round does not read it as a technical block.
