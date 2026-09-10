---
name: "OPSX: Review"
description: Multi-persona review of a change's OpenSpec artifacts (architect · senior engineer · security)
skill: review-spec
category: Workflow
tags: [workflow, review, quality]
---

> **UI wrapper for `review-spec`** — the skill is the authoritative source of logic.

Review a change's OpenSpec artifacts from three perspectives — Principal Architect,
Senior Backend Engineer, Senior Application Security Engineer — consolidated into one
severity-ranked report. Critique only: it never rewrites artifacts.

**Input**: The argument after `/opsx:review` is a change name (e.g. `/opsx:review add-auth`)
or a spec file path (e.g. `/opsx:review openspec/specs/signing-request/spec.md`). If omitted,
the skill lists active changes and asks which to review.

**Invoke**: Use the **Skill tool** to run `review-spec` with the parsed change name or path.
