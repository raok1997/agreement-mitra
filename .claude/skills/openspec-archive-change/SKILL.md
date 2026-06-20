---
name: openspec-archive-change
description: Archive a completed change in the experimental workflow. Use when the user wants to finalize and archive a change after implementation is complete.
license: MIT
compatibility: Requires openspec CLI.
command: opsx:archive
metadata:
  author: openspec
  version: "1.0"
  generatedBy: "1.2.0"
---

Archive a completed change in the experimental workflow.

**Input**: Optionally specify a change name. If omitted, check if it can be inferred from conversation context. If vague or ambiguous you MUST prompt for available changes.

**Steps**

1. **If no change name provided, prompt for selection**

   Run `openspec list --json` to get available changes. Use the **AskUserQuestion tool** to let the user select.

   Show only active changes (not already archived).
   Include the schema used for each change if available.

   **IMPORTANT**: Do NOT guess or auto-select a change. Always let the user choose.

2. **Check artifact completion status**

   Run `openspec status --change "<name>" --json` to check artifact completion.

   Parse the JSON to understand:
   - `schemaName`: The workflow being used
   - `artifacts`: List of artifacts with their status (`done` or other)

   **If any artifacts are not `done`:**
   - Display warning listing incomplete artifacts
   - Use **AskUserQuestion tool** to confirm user wants to proceed
   - Proceed if user confirms

3. **Check task completion status**

   Read the tasks file (typically `tasks.md`) to check for incomplete tasks.

   Count tasks marked with `- [ ]` (incomplete) vs `- [x]` (complete).

   **If incomplete tasks found:**
   - Display warning showing count of incomplete tasks
   - Use **AskUserQuestion tool** to confirm user wants to proceed
   - Proceed if user confirms

   **If no tasks file exists:** Proceed without task-related warning.

4. **Optionally validate implementation against spec**

   Use the **AskUserQuestion tool** to ask:
   > "Validate implementation against spec before archiving? (recommended)"

   Options: "Yes, validate", "Skip"

   **If user chooses validate:**
   - Use Task tool (subagent_type: "general-purpose", prompt: "Use Skill tool to invoke openspec-validate for change '<name>'. Return the full validation report including gap count and list of gaps.")
   - When the agent returns, display the validation report summary
   - If gaps or partial items were found:
     - Show gap count and list
     - Use **AskUserQuestion tool** to confirm: "Gaps found. Proceed with archive anyway?"
     - Proceed if user confirms
   - If no gaps: proceed to archive

   **If user chooses skip:** Proceed without validating.

6. **Assess delta spec sync state**

   Check for delta specs at `openspec/changes/<name>/specs/`. If none exist, proceed without sync prompt.

   **If delta specs exist:**
   - Compare each delta spec with its corresponding main spec at `openspec/specs/<capability>/spec.md`
   - Determine what changes would be applied (adds, modifications, removals, renames)
   - Show a combined summary before prompting

   **Prompt options:**
   - If changes needed: "Sync now (recommended)", "Archive without syncing"
   - If already synced: "Archive now", "Sync anyway", "Cancel"

   If user chooses sync, use Task tool (subagent_type: "general-purpose", prompt: "Use Skill tool to invoke openspec-sync-specs for change '<name>'. Delta spec analysis: <include the analyzed delta spec summary>"). Proceed to archive regardless of choice.

7. **Perform the archive**

   Create the archive directory if it doesn't exist:
   ```bash
   mkdir -p openspec/changes/archive
   ```

   Generate target name using the current **GMT date + time** so the archive
   records when it happened, not just the day. Compute it in UTC:
   ```bash
   STAMP="$(date -u +"%Y-%m-%d-%H%M%SZ")"   # e.g. 2026-06-20-143205Z
   ```
   Target name: `<STAMP>-<change-name>` (e.g. `2026-06-20-143205Z-add-auth`).
   The `YYYY-MM-DD` date prefix and the `-<change-name>` suffix are preserved, so
   date-prefix and suffix matching elsewhere still work; the GMT time component
   also makes same-day collisions effectively impossible.

   **Check if target already exists:**
   - If yes: Fail with error, suggest renaming existing archive (a fresh GMT
     timestamp should otherwise avoid collisions)
   - If no: Move the change directory to archive

   ```bash
   mv "openspec/changes/<name>" "openspec/changes/archive/${STAMP}-<name>"
   ```

8. **Display summary**

   Show archive completion summary including:
   - Change name
   - Schema that was used
   - Archive location
   - Validation status (verified / gaps found + archived anyway / skipped)
   - Whether specs were synced (if applicable)
   - Note about any warnings (incomplete artifacts/tasks)

**Output On Success**

```
## Archive Complete

**Change:** <change-name>
**Schema:** <schema-name>
**Archived to:** openspec/changes/archive/<STAMP>-<name>/
**Archived at (GMT):** <STAMP>  (the full UTC date-time the archive ran)
**Specs:** ✓ Synced to main specs (or "No delta specs" or "Sync skipped")

All artifacts complete. All tasks complete.
```

**Guardrails**
- Always prompt for change selection if not provided
- Use artifact graph (openspec status --json) for completion checking
- Don't block archive on warnings - just inform and confirm
- Preserve .openspec.yaml when moving to archive (it moves with the directory)
- Show clear summary of what happened
- If sync is requested, use openspec-sync-specs approach (agent-driven)
- If delta specs exist, always run the sync assessment and show the combined summary before prompting
