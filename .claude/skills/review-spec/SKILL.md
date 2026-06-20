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

A single agent reviews sequentially through all active personas in one pass.

## Input

- Optional: change name (e.g. `add-auth`) or specific file path (e.g. `openspec/specs/signing/spec.md`)
- If change not provided: run `openspec list --json` to find active changes; if multiple exist, use **AskUserQuestion** to ask which to review
- If only a file path is given (no change): review that file directly

## Step 1 — Gather context

Use **Glob** to detect which artifact files are present before reading anything. Read only the files that exist. Do not start review without this context.

**If reviewing a change:**
- `openspec/config.yaml` (project context — tech stack, conventions) — always read
- Check for and read only those that exist:
  - `openspec/changes/<name>/proposal.md`
  - `openspec/changes/<name>/design.md`
  - `openspec/changes/<name>/specs/spec.md`
  - `openspec/changes/<name>/tasks.md`

**If reviewing a file path directly:**
- `openspec/config.yaml`
- The specified file(s)

## Step 2 — Run the review

Launch **one agent** with the full artifact content and this instruction:

> You are conducting a structured code and spec review. Work through the reviewer personas in sequence. For each persona, adopt that mindset fully before moving to the next. Do not let earlier persona findings influence later ones — treat each section independently.
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

Collect the agent's output and proceed to Step 3.

## Step 3 — Consolidate findings

1. Collect all findings from all three personas
2. Scan for near-duplicates: group findings that share the same root cause, referenced artifact/section, or core keyword cluster — even if framed differently by different personas
3. De-duplicate: for each group of near-duplicates, merge into one finding and note which personas flagged it; keep findings separate only if they represent genuinely distinct concerns
4. Group all findings by severity: **Critical → Major → Minor → Suggestions**
5. Attribute each finding to the persona(s) that raised it

## Step 4 — Output the consolidated report

```
## Review Report: <change name or file>
**Reviewers:** Principal Architect · Senior Backend Engineer · Senior Application Security Engineer

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

## Step 5 — Offer next steps

After the report, ask the user (via **AskUserQuestion**):

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
