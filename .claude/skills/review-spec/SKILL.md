---
name: review-spec
description: Multi-persona review of OpenSpec artifacts — architect, senior engineer, and security engineer perspectives consolidated into a single actionable report.
license: MIT
compatibility: Requires openspec CLI.
metadata:
  author: custom
  version: "1.2"
---

# Review Spec Skill

Perform a structured multi-persona review of OpenSpec artifacts or spec files, covering three perspectives by default: Principal Architect, Senior Backend Engineer, and Senior Application Security Engineer.

Three independent agents review in parallel — one per persona — and the spine consolidates their findings into a single deterministically-ordered report.

## Input

- Optional: change name (e.g. `add-auth`) or specific file path (e.g. `openspec/specs/signing/spec.md`)
- If change not provided: run `openspec list --json` to find active changes; if multiple exist, use **AskUserQuestion** to ask which to review
- If only a file path is given (no change): review that file directly

## Step 1 — Gather context

Use **Glob** to detect which artifact files are present before reading anything. Read only the files that exist. Do not start review without this context.

**If reviewing a change:**
- `openspec/config.yaml` (project context — tech stack, conventions) — always read
- **Ask the CLI where the artifacts are; do not hardcode paths.**
  ```bash
  openspec instructions apply --change "<name>" --json   # → .contextFiles
  ```
  `contextFiles` gives the authoritative path for each artifact, including
  `specs` as a **glob** (`.../specs/**/*.md`). Read every file it resolves to.
  It returns correct paths even when `state` is `blocked` (a change with no
  `tasks.md` yet) — it simply **omits the keys for artifacts that don't exist**,
  so an absent `design`/`tasks` key means "not written yet", not an error. A
  `blocked` state is fine to review: report what's missing and review the rest.
- Only if that command is unavailable, fall back to reading whichever exist:
  - `openspec/changes/<name>/proposal.md`
  - `openspec/changes/<name>/design.md`
  - `openspec/changes/<name>/specs/*/spec.md` — **one directory per capability.**
    There is **no** `specs/spec.md` in this project and never has been; a glob
    that stops at `specs/` reads zero spec deltas and the review silently
    proceeds on proposal + design alone.
  - `openspec/changes/<name>/tasks.md`
- **Report which spec deltas you read**, by capability name, at the top of the
  review. A review that read no deltas must say so rather than look complete.

**If reviewing a file path directly:**
- `openspec/config.yaml`
- The specified file(s)

## Step 2 — Build the scenario-coverage matrix

**Only when reviewing a change that has both delta specs and `tasks.md`.** Skip for a bare
file review, or when `tasks.md` does not exist yet (say so — the matrix is a precondition
for apply, so it has to exist before Stage 3 of the flow).

This runs **before** the persona review and is **deterministic** — it is a table, not a
judgment. Its purpose is to make coverage a *contract agreed before any code is written*,
rather than an audit performed after.

**Build it:**

1. Enumerate every `#### Scenario:` across `openspec/changes/<name>/specs/*/spec.md`.
2. Enumerate the test tasks in `tasks.md` (the `## Tests` section, or any task naming a test).
3. Assign every scenario **exactly one disposition**:

   | Disposition | Meaning | Must record |
   |---|---|---|
   | `COVERED` | a test task exists for it | the task id |
   | `GROUPED` | covered by another scenario's test | the covering scenario + task id |
   | `MANUAL` | verified by a human, not a suite | where (e.g. `openspec/MANUAL-DRIVE-CHECKLIST.md`, or the change's own manual gate) |
   | `WAIVED` | deliberately untested | a one-line reason |
   | `UNMAPPED` | nothing covers it and nobody decided | — |

4. Write the matrix into `tasks.md` as a `## Coverage` section. It archives with the change,
   and `openspec-validate` reads it later to check the contract was discharged.

**Rules — these are the point of the exercise:**

- **Every `UNMAPPED` row is a Major finding.** It must become one of the other four before
  the review can close. `GROUPED` is legitimate and usually the largest bucket — a single
  integration test can honestly cover several scenarios — but it must **name** the test, or
  it is `UNMAPPED` wearing a disguise.
- **100% automated coverage is not the goal; zero silent gaps is.** A scenario nobody tests
  because someone weighed it and said no is fine. A scenario nobody tests because it fell off
  the bottom of a 50-item list is not, and without this matrix the two are indistinguishable.
- **Legitimate grounds for `WAIVED`:** the cost is in the *harness* rather than the assertion
  (needs a real vendor, a real clock, a multi-node setup — prefer `MANUAL` there), or the case
  is genuinely rare **and** low blast radius.
- **Never waivable in this project:** anything touching money, PII (Aadhaar/OTP/VID), or the
  legal validity of the document. This is identity and legal infrastructure — for those paths
  rarity is the reason a defect survives to production, not a reason to skip the test. Route
  them to `MANUAL` if a suite genuinely cannot reach them, never to `WAIVED`.
- **A waiver dies when its requirement changes.** If a later change carries a `MODIFIED`
  delta for the requirement a scenario belongs to, any waiver on it is void and must be
  decided again. Check this when reviewing a change that modifies existing requirements.
- **Cap the waiver rate.** If much more than a fifth of scenarios end up `WAIVED`, the defect
  is usually in the spec rather than the tests — scenarios were written that nobody ever
  intended to verify. Raise that as a finding and cut scenarios; do not absorb it in waivers.

Carry the matrix summary into the report header (Step 6), and hand the matrix to every persona
agent in Step 4 as context — an `UNMAPPED` cluster around one requirement is a strong hint
that the requirement is underspecified.

## Step 3 — Ground the artifacts in the real code

**Skip only for a bare file review with no change.** Otherwise this is not optional: it is
the step that produces the findings worth having.

The personas critique *prose*. Prose is where a spec sounds right and is quietly impossible —
the type it needs is package-private, the method it names throws on the path it assumes, the
route it adds is unreachable because an existing matcher denies it. No amount of persona
prompting finds those. Reading the code does.

Two archived reviews carry a grounding pass, and they are the two richest in the set. From
`jurisdiction-checkout-gating`:

> `Agreement.templateId()` is package-private (`Agreement.java:494`) and `AgreementResponse`
> carries none — a rule outside `signing.agreement` cannot read it today.

That became an explicit task instead of an implementation surprise. It was improvised. Make it
systematic.

**Build the grounding map.** For every concrete thing the artifacts name — type, method, field,
endpoint, config property, table, migration, test — resolve it to what exists **today**:

```bash
grep -rn "<Identifier>" backend/src/main/java frontend/src   # definition + visibility
grep -rn "<path fragment>" backend/src/main/java             # endpoints, and their SecurityConfig matcher
```

Delegate the sweep to an **Explore** subagent when it is broad; it is read-only and fans out well.

Record, per item: **exists / does not exist**, its **visibility** (package-private vs public — this
is a Spring Modulith monolith, so a caller in another module cannot reach a package-private
member), the **module** it lives in, and any **precondition or throw** on the path the artifact
assumes. Note explicitly when something the artifacts treat as existing does **not** — that is
a Critical finding before anyone argues about design.

Pass this map to every persona in Step 4. Cite `file:line` in findings that rest on it.

## Step 4 — Run the review

Launch the personas as **three agents in parallel — one per persona** (send all three Agent
calls in a single message so they actually run concurrently). Give each one: the artifacts from
Step 1, the coverage matrix from Step 2, the grounding map from Step 3, and **only its own
persona block** from below.

**Why parallel rather than one sequential agent.** The old instruction asked a single context to
"not let earlier persona findings influence later ones" — that is asking it to un-see what it
just wrote, which it cannot do. Anchoring is precisely what three reviewers exist to avoid.
Separate contexts give the independence the instruction was only able to ask for. It costs more
tokens (each agent reads the artifacts) and returns less wall-clock; the fan-in is small because
each returns findings only.

**Determinism — the output must not depend on who finishes first.** Agents complete in arbitrary
order. That order must never reach the report or the journal:

- **Subagents never write.** They return findings; the spine does every write. So there is no
  interleaving in any file — only in the order results arrive in the spine.
- **Collect all three before consolidating.** Do not begin Step 5 with partial results.
- **Consolidate in fixed persona order — Architect, then Senior Engineer, then Security —
  regardless of completion order.** Within a severity band, order findings by that same persona
  sequence. Two runs over the same artifacts must produce the same report ordering.
- **One journal entry for the whole review round**, written by the spine after consolidation.
  Never one entry per persona, and never a running log as results land.
- If an agent fails or returns nothing, say so explicitly in the report header (`Security:
  no response`) rather than silently reporting a two-persona review as complete.

Each agent gets this preamble plus its persona block:

> You are conducting a structured spec review in a single named role. Adopt that mindset fully.
> You are one of three independent reviewers; you will not see the others' findings, and you
> should not speculate about them — cover your own remit thoroughly.
>
> Ground every finding you can in the supplied grounding map, citing `file:line`. A finding that
> contradicts the map is wrong; the map is the current code.
>
> **Critique only — no rewrites, no implementation. Return findings as structured markdown.**
>
> ---
>
> **Persona 1: Principal Architect**
> You have 15+ years designing backend systems. Skeptical of over-engineering and under-engineering equally. You do not accept vague requirements.
> Focus on:
> - Data model correctness: relationships, cardinality, foreign keys
> - Right abstractions chosen — nothing over- or under-engineered
> - Clean integration with existing system components
> - Missing or implicit requirements that will cause rework
> - Design decisions that are unjustified or unexplained
>
> Output as:
> ```
> ## Architect Review
> ### Critical / Major / Minor / Suggestions
> - [finding] — [artifact/section]
> ```
>
> ---
>
> **Persona 2: Senior Backend Engineer**
> Deep backend expertise in the project's stack. You've been burned by underspecified tasks and specs that look good but are painful to implement.
> Focus on:
> - Task atomicity — are tasks too large or ambiguous?
> - Idiomatic patterns for the project's framework: persistence/migrations, authorization, background jobs
> - Missing edge cases
> - Hidden complexity not reflected in task estimates
> - Testability and clarity of acceptance criteria
> - Implicit task dependencies not made explicit
>
> Output as:
> ```
> ## Senior Engineer Review
> ### Critical / Major / Minor / Suggestions
> - [finding] — [artifact/section]
> ```
>
> ---
>
> **Persona 3: Senior Application Security Engineer**
> Reviews every feature for vulnerabilities before implementation. Follows OWASP Top 10, expert in common web-app vulnerability patterns.
> Focus on:
> - AuthN/AuthZ: are access rules defined correctly? Every endpoint protected?
> - Input validation and sanitization — injection surface (SQL, command, etc.)
> - File upload risks: type/size/path checks
> - Data exposure: passwords, tokens, PII handling
> - OTP/2FA: race conditions, replay attacks, brute force
> - Mass assignment / over-binding of request payloads to persisted fields
> - Operations that should be idempotent but aren't
>
> Output as:
> ```
> ## Security Review
> ### Critical / Major / Minor / Suggestions
> - [finding] — [artifact/section]
> ```

Collect all three agents' output — waiting for every one — and proceed to Step 5.

## Step 5 — Consolidate findings

1. Collect all findings from all three personas
2. Scan for near-duplicates: group findings that share the same root cause, referenced artifact/section, or core keyword cluster — even if framed differently by different personas
3. De-duplicate: for each group of near-duplicates, merge into one finding and note which personas flagged it; keep findings separate only if they represent genuinely distinct concerns
4. Group all findings by severity: **Critical → Major → Minor → Suggestions**
5. Attribute each finding to the persona(s) that raised it

## Step 6 — Output the consolidated report

```
## Review Report: <change name or file>
**Reviewers:** Principal Architect · Senior Backend Engineer · Senior Application Security Engineer
                (parallel, independent — note any that returned nothing)
**Spec deltas read:** <capability names, or "none — review is proposal+design only">
**Grounded against code:** <N> symbols/endpoints resolved · <N> not found in the codebase
**Coverage:** <N> scenarios — <N> COVERED, <N> GROUPED, <N> MANUAL, <N> WAIVED, **<N> UNMAPPED**

---

### Summary
| Severity    | Count |
|-------------|-------|
| Critical    | N     |
| Major       | N     |
| Minor       | N     |
| Suggestions | N     |

---

### Critical
- **[Finding title]** — [Detail]. *Artifact: [file/section]. Flagged by: [Architect / Engineer / Security / multiple]*

### Major
- **[Finding title]** — [Detail]. *Artifact: [file/section]. Flagged by: [persona]*

### Minor
- **[Finding title]** — [Detail]. *Artifact: [file/section]. Flagged by: [persona]*

### Suggestions
- **[Finding title]** — [Detail]. *Artifact: [file/section]. Flagged by: [persona]*
```

Omit any severity section with no findings.

## Step 7 — Offer next steps

**Skip this step entirely when invoked from `openspec-flow`.** The flow's Stage 2
owns what happens next — it applies its Issue policy and enforces a ≤5-round cap.
Prompting here routes around both: the user picking "proceed to apply" or "update
artifacts" bypasses the policy and the round counter never advances. When the
caller is the flow, **return the consolidated report and stop.**

Otherwise (standalone invocation), ask the user (via **AskUserQuestion**):

> "How would you like to proceed?
> 1. **Update artifacts** — I'll revise the spec/design/tasks to address findings
> 2. **Explore mode** — Work through the findings together before deciding what to change
> 3. **Proceed to apply** — Skip updates and start implementation as-is
> 4. **Address specific findings** — Tell me which ones to fix"

Act on their choice.

## Guardrails

- Do NOT skip reading artifacts before starting — full context is required
- Do NOT invent findings — only report what the review actually surfaces
- Do NOT rewrite artifacts without user confirmation
- If a change has no artifacts yet, tell the user to run `/propose` first
- If only some artifacts exist (e.g. spec but no design), review what's available and note the gaps
