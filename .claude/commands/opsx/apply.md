---
name: "OPSX: Apply"
description: Implement tasks from an OpenSpec change (Experimental)
skill: openspec-apply-change
category: Workflow
tags: [workflow, apply, experimental]
---

> **UI wrapper for `openspec-apply-change`** — the skill is the authoritative source of logic.

Implement tasks from an OpenSpec change.

**Input**: The argument after `/opsx:apply` is the change name (e.g., `/opsx:apply add-auth`). If omitted, the skill will infer from conversation context or prompt for selection.

**Invoke**: Use the **Skill tool** to run `openspec-apply-change` with the parsed change name.
