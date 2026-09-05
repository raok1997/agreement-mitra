# Baseline spec-fold gap -- found 2026-09-05

**Six capabilities were never folded into `openspec/specs/`.** Archiving a change is supposed to fold
its spec delta into the baseline (the pattern commit `3f4d71a` follows). For the 2026-07-12/13 batch
that did not happen: the changes were archived, but the baseline never gained their capabilities.

## Evidence

Archived changes carrying a `specs/<capability>/` delta with **no** matching `openspec/specs/<capability>/`:

| Missing baseline capability | Archived change that should have created it |
|---|---|
| `template-definition` | `2026-07-13-092856Z-template-definition-model` |
| `template-resolution` | `2026-07-13-092856Z-template-resolution-engine` |
| `template-catalog` | `2026-07-13-092856Z-template-catalog` |
| `template-form-projection` | `2026-07-13-092856Z-template-form-projection`, `...-form-schema-section-semantics` |
| `preview-centric-capture` | `2026-07-13-092856Z-capture-mandatory-optional-ux`, `2026-07-12-152551Z-document-capture-shell-wiring` |
| `agreement-attributes` | `2026-07-12-superseded-agreement-attributes-and-pinning` |

`template-definition-model`'s delta is `## ADDED Requirements` for "Declarative template-definition
format". `git log -- openspec/specs/template-definition` is **empty** -- that directory has never
existed in history. So the fold did not happen and was not later reverted; it was skipped
(`openspec archive --skip-specs`, most likely) or it failed silently.

Corroborating: the baseline matches **no** occurrence of `meta.document`, `executionLine`, or
`render kind`, all of which are shipped, tested behaviour.

## Why it matters now

`template-document-metadata` (M0) is otherwise archive-ready. Its delta is
**`## MODIFIED Requirements`** against "Declarative template-definition format" -- a requirement that
is not in the baseline to modify. `openspec validate template-document-metadata --strict` passes
(it validates the change artifact, not the fold), so the problem will surface at archive time, not
before.

The ordering makes this worse rather than better: SUPERSEDED.md gives the dependency order as
M0 -> M1 -> M2 -> M4, but M1-M4 were archived on 2026-07-13, **before** M0. M0 is the last of the six
to archive and the only one whose delta is MODIFIED rather than ADDED.

## Options (needs a decision -- do not guess)

1. **Backfill the baseline.** Reconstruct `openspec/specs/<capability>/spec.md` for the six from
   their archived ADDED deltas, then archive M0 normally so its MODIFIED delta lands on real text.
   Most correct, most work, and the reconstruction must be reviewed -- it is the project's spec of
   record.
2. **Archive M0 with `--skip-specs`.** Consistent with how its five siblings were archived, and
   unblocks M0 today. Leaves the baseline gap in place for all six.
3. **Convert M0's delta to ADDED and archive normally.** Lands only M0's view of
   `template-definition` -- a partial capability spec that omits everything M1-M4 added. Cheapest to
   run, worst for the baseline: it looks folded while being incomplete.

Option 2 preserves the status quo; option 1 fixes it. Either way this is a **pre-existing** gap that
M0 exposed rather than caused, and it affects six capabilities, not one.
