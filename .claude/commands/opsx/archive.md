---
name: "OPSX: Archive"
description: Archive a completed change in the experimental workflow
skill: openspec-archive-change
category: Workflow
tags: [workflow, archive, experimental]
---

> **UI wrapper for `openspec-archive-change`** — the skill is the authoritative source of logic.

Archive a completed change after implementation is complete.

**Input**: The argument after `/opsx:archive` is the change name (e.g., `/opsx:archive add-auth`). If omitted, the skill will prompt for selection from available active changes.

**Invoke**: Use the **Skill tool** to run `openspec-archive-change` with the parsed change name.
