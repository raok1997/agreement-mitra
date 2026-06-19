---
name: openspec-validate
description: Validate that a change's implementation matches its spec artifacts. Cross-checks actual code changes against proposal requirements, design decisions, and specs to surface gaps before archiving.
license: MIT
compatibility: Requires openspec CLI and git.
command: opsx:validate
metadata:
  author: openspec
  version: "1.0"
  generatedBy: "1.2.0"
---

Validate that a change's implementation accurately reflects its spec artifacts.

**Input**: Optionally specify a change name. If omitted, check if it can be inferred from conversation context. If vague or ambiguous you MUST prompt for available changes.

**Steps**

1. **Select the change**

   If a name is provided, use it. Otherwise:
   - Infer from conversation context if the user mentioned a change
   - Auto-select if only one active change exists
   - If ambiguous, run `openspec list --json` to get available changes and use the **AskUserQuestion tool** to let the user select

   Always announce: "Validating change: <name>"

2. **Read spec artifacts**

   Run `openspec status --change "<name>" --json` to get artifact paths and schema.

   Read all available spec artifacts for the change:
   - `openspec/changes/<name>/proposal.md` — requirements, goals, scope
   - `openspec/changes/<name>/design.md` — design decisions, approach, constraints
   - `openspec/changes/<name>/tasks.md` — planned implementation steps
   - `openspec/changes/<name>/specs/` — any capability specs (if present)

   Skip artifacts that don't exist — not all schemas produce all artifacts.

3. **Examine the implementation**

   Get the code diff for this change:
   ```bash
   git diff main...HEAD
   ```

   If the diff is very large (>500 lines), also read the specific files most relevant to the change based on what the design/proposal describe.

   Build a mental model of:
   - What files were changed and how
   - What new behavior was introduced
   - What was removed or modified

4. **Extract checkpoints from spec artifacts**

   From the artifacts, extract a list of concrete checkpoints to verify. These are:

   **From proposal.md:**
   - Each stated goal or requirement
   - Explicit scope inclusions ("this change will...")
   - Explicit scope exclusions ("out of scope: ...") — verify these were NOT implemented

   **From design.md:**
   - Each named design decision (e.g., "use X pattern", "store Y in Z", "validate using W")
   - Architectural constraints ("must not...", "always...", "never...")
   - Integration points mentioned (APIs, models, services touched)

   **From specs/ (if present):**
   - Each specified behavior or rule

   **From tasks.md:**
   - Tasks are already tracked by `apply` — do NOT re-verify checkboxes
   - Only use tasks as hints to understand scope, not as validation criteria

5. **Cross-check each checkpoint against the diff**

   For each checkpoint:
   - Search the diff and changed files for evidence of implementation
   - Mark as **verified** if the code clearly satisfies it
   - Mark as **gap** if no evidence found or implementation contradicts it
   - Mark as **partial** if partially addressed but incomplete
   - Mark as **not applicable** if the checkpoint turned out irrelevant to this change

   Be pragmatic — look for intent and substance, not exact wording matches.

6. **Produce the validation report**

   Display the full report (see output format below).

   **After the report:**
   - If called standalone: suggest next steps based on findings
   - If called from archive: return findings summary for archive to use (gaps count + list)

**Output Format**

```
## Validation Report: <change-name>

**Schema:** <schema-name>
**Checked:** <N> requirements/decisions
**Status:** <N> verified, <N> gaps, <N> partial

---

### Gaps  ← show only if gaps exist
- [ ] **[proposal]** Requirement X: no evidence found in code
- [ ] **[design]** Decision Y: implementation uses Z instead of W
- [ ] **[spec]** Rule A: handler missing for edge case B

### Partial  ← show only if partial items exist
- [~] **[design]** Decision X: implemented for happy path but error case missing

### Verified
- [x] **[proposal]** Goal: <description>
- [x] **[design]** Decision: <description>
- [x] **[spec]** Rule: <description>
...

---

**Conclusion:** <one of the following>
- ✓ Implementation matches spec — ready to archive
- ⚠ Minor gaps found — review before archiving
- ✗ Significant gaps found — recommend fixing before archiving
```

**Output When No Spec Artifacts Found**

```
## Validation Report: <change-name>

No spec artifacts found to validate against.

The change may have been created without proposal/design artifacts,
or the schema does not produce them.

Skipping validation.
```

**Guardrails**
- Be pragmatic: look for intent, not literal matches
- Don't fail on missing tasks.md — tasks are tracked separately by apply
- Don't validate out-of-scope items — only what the spec claims this change does
- If diff is empty or very small, note it — may indicate no implementation yet
- Gaps are informational, not blockers — the user decides whether to fix or proceed
- Keep gap descriptions specific: what was specified, what was found (or not found)
- Scope exclusions in proposal are also checkpoints — verify they weren't accidentally implemented
