---
name: "OPSX: Propose"
description: Propose a new change - create it and generate all artifacts in one step
skill: openspec-propose
category: Workflow
tags: [workflow, propose, experimental]
---

> **UI wrapper for `openspec-propose`** — the skill is the authoritative source of logic.

Propose a new change — create it and generate all artifacts in one step.

**Input**: The argument after `/opsx:propose` is a change name (kebab-case) or a description of what to build (e.g., `/opsx:propose add-user-auth` or `/opsx:propose "add user authentication"`). If omitted, the skill will ask what to build.

**Invoke**: Use the **Skill tool** to run `openspec-propose` with the parsed input.
