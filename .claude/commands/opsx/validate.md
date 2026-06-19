---
name: "OPSX: Validate"
description: Validate that a change's implementation matches its spec artifacts
skill: openspec-validate
category: Workflow
tags: [workflow, validate, quality]
---

> **UI wrapper for `openspec-validate`** — the skill is the authoritative source of logic.

Validate that a change's implementation accurately reflects its spec artifacts.

**Input**: The argument after `/opsx:validate` is the change name (e.g., `/opsx:validate add-auth`). If omitted, the skill will infer from conversation context or prompt for selection.

**Invoke**: Use the **Skill tool** to run `openspec-validate` with the parsed change name.
