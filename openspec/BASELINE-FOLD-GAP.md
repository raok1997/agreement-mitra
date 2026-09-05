# RESOLVED 2026-09-05 -- see the resolution note at the end of this file.

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

---

# Addendum -- the archive ORDER is constrained (found 2026-09-05, after the push)

Auditing every active change's delta against the baseline turned up a second, separate issue: the
active changes must be archived in a **particular order**, because several `MODIFIED` a requirement
that another *active* change `ADDED`.

A `MISSING` capability is only a problem when the delta is `MODIFIED`. An `ADDED` delta creates the
capability at archive time, which is normal and fine. The rows that matter:

| Change | Capability | Delta | Creator |
|---|---|---|---|
| `contacts-editable-until-payment` | `payment-processing` | MODIFIED | **`post-payment-continuity`** |
| `agreement-status-link-page` | `agreement-recovery` | MODIFIED | **`post-payment-continuity`** |
| `template-document-metadata` | `template-definition` | MODIFIED | already archived, never folded |

Verified for the first row: `contacts-editable-until-payment` modifies **"Confirmed contacts are
saved against the agreement"**, and the only delta that ADDs that requirement is
`post-payment-continuity/specs/payment-processing/spec.md`. It is **not** razorpay -- that ADDs a
disjoint set of payment requirements.

## Consequences

1. **`contacts-editable-until-payment` is NOT archive-ready**, even once its manual drive passes. It
   is gated behind `post-payment-continuity`, which needs an implementation pass (7 test gaps + 3
   partials + task 10.5). Correcting an earlier assessment that put it in the archive-ready bucket on
   the strength of its 19/20 task count alone.
2. **`post-payment-continuity` is the keystone.** It gates two other changes and is the largest piece
   of remaining agent-doable work.
3. **`template-document-metadata` remains the unique dead-end**: every other MODIFIED-on-missing has
   a creator sitting in the active set, so ordering fixes it. M0's creator is already archived
   without its fold, so no ordering can help -- it needs one of the three options above.
4. The `payment-processing` capability itself is created by whichever of `razorpay-payment`,
   `post-payment-continuity` or `state-stamp-duty-quoting` archives first; all three carry ADDED
   deltas against it.

## Safe archive order

```
post-payment-continuity  ->  contacts-editable-until-payment
                         ->  agreement-status-link-page (backlog; 0/47)

template-document-metadata   -- independent, blocked on the fold decision above

agreement-capture-persistence, agreement-ownership, google-oauth-login,
preview-centric-capture, rental-document-content-v2,
staff-queue-fulfilment-context   -- independent; archive in any order once driven
```

---

# Resolution (2026-09-05) -- backfilled, and the scope was smaller than stated above

The table near the top of this file listed **six** missing capabilities. Verified one by one before
writing anything, **only four were backfill targets.** The other two must NOT be backfilled, for
different reasons:

- **`preview-centric-capture`** -- not a fold failure. Its `ADDED` delta lives in the **active**
  change `preview-centric-capture`, which has not archived yet. The two archived changes that touch it
  (`capture-mandatory-optional-ux`, `document-capture-shell-wiring`) carry only `MODIFIED` deltas. So
  this is an **ordering** problem, fixed by archiving the active change, not by backfilling. Writing a
  baseline for it now would pre-empt that change's own fold.
- **`agreement-attributes`** -- **never implemented.** Its own `SUPERSEDED.md` says it was "retired
  before implementation ... No M5 code landed (no `V9`, no attribute/pin fields on `Agreement`)".
  Backfilling it would put behaviour that does not exist into the spec of record -- strictly worse
  than the gap it fills.

## What was backfilled

| Capability | Source | Result |
|---|---|---|
| `template-definition` | `...template-definition-model` ADDED | 6 requirements |
| `template-resolution` | `...template-resolution-engine` ADDED | 6 requirements |
| `template-catalog` | `...template-catalog` ADDED | 7 requirements |
| `template-form-projection` | `...template-form-projection` ADDED, then `...form-schema-section-semantics` MODIFIED applied in archive order | 5 requirements, 3 modified in place |

Method: the archived delta text **verbatim**, not re-derived from code. Each file carries a Purpose
noting it was backfilled and from where, so a later reader can tell reconstructed text from
originally-authored text. `openspec validate --specs --strict` passes on all four.

## Known-orphan delta, deliberately not applied

`...template-catalog`'s delta also carries a `MODIFIED` block for a requirement named **"Anonymous
agreement drafting"**, filed under `specs/template-catalog/`. No requirement by that name exists in
any baseline capability, and its content is plainly about the **agreement** (recording `template_id`
on a drafted agreement), not the catalog -- so it was misfiled when authored. It is **skipped**, not
guessed at. Its substance (server-sourced `template_id`, anti-mass-assignment, no duplicate column)
appears to be satisfied in the shipped code, so this is a bookkeeping loose end rather than missing
behaviour. **Decide where it belongs before it is folded anywhere** -- most likely a MODIFIED against
`agreement-management`'s "Create a multi-party rental agreement".

## Effect on M0

`template-document-metadata`'s delta MODIFIES four requirements in `template-definition`. All four
now exist in the backfilled baseline. **The dead-end is cleared -- M0 can archive normally, with its
spec fold running, no `--skip-specs`.**

## Pre-existing, unrelated

`openspec validate --specs --strict` reports two failures I did not touch and did not cause:
`estamp-intake` (missing a `## Requirements` section) and `signing-request` (requirement 7 lacks a
SHALL/MUST keyword). Both predate this work. Worth fixing, separately.


---

# Second orphan set, found while archiving `preview-centric-capture` (2026-09-05)

Archiving `preview-centric-capture` created the `preview-centric-capture` capability from its `ADDED`
delta, then applied `capture-mandatory-optional-ux`'s never-folded `MODIFIED` delta over it (all three
requirement names matched exactly). That change's fold is now caught up.

**`document-capture-shell-wiring` (archived 2026-07-12) has two `MODIFIED` requirements with no
target, and they were NOT applied:**

- "The capture shell previews live from the document-projection endpoint"
- "The capture flow is constrained to the parity-safe template until the draft path is dimension-aware"

Neither name exists in the capability's ADDED set. Most likely they were renamed or absorbed as the
shell design settled between 2026-07-12 and the final `preview-centric-capture` authoring -- but that
is a guess, so nothing was written. Same disposition as the "Anonymous agreement drafting" orphan
above: **decide where these belong (or that they are obsolete) before folding them anywhere.**

# Running tally of never-folded deltas

| Fold | Status |
|---|---|
| `template-definition` / `-resolution` / `-catalog` / `-form-projection` | backfilled 2026-09-05 |
| `template-document-metadata` (M0) MODIFIED | folded on archive 2026-09-05 |
| `preview-centric-capture` ADDED + `capture-mandatory-optional-ux` MODIFIED | folded on archive 2026-09-05 |
| `staff-queue-fulfilment-context` MODIFIED into `estamp-intake` | folded on archive 2026-09-05 |
| `agreement-attributes` | **must not be folded** -- never implemented |
| "Anonymous agreement drafting" (from `template-catalog`) | **orphan, undecided** |
| 2 requirements from `document-capture-shell-wiring` | **orphan, undecided** |


---

# Fourth orphan -- likely a RENAME, blocking `agreement-capture-persistence`'s archive (2026-09-05)

`agreement-capture-persistence` is 22/22 and valid, but its delta MODIFIES
**"Generate the signable draft from the agreement"** in `agreement-management`, and no requirement by
that name exists in any baseline capability.

**Unlike the three orphans above, this one has a strong candidate.** The baseline holds
**"Generate-as-draft pins the effective-template identity for reproducibility"**, and the delta's text
describes the same endpoint (`POST /api/agreements/{id}/document`), the same storage behaviour, the
same `409` freeze, and explicitly keeps the pin ("then pins the effective template's identity"). It
reads as that requirement **renamed and broadened** -- the pin clause plus the new capture-state feed
that gives preview/draft parity.

**Not applied, because a rename in the spec of record is the owner's call**, and guessing wrong would
silently drop the pin requirement's identity. Three ways forward:

1. **Treat it as a rename** -- replace "Generate-as-draft pins the effective-template identity for
   reproducibility" with the delta's text under the new name. Most likely correct; the pin clause
   survives inside the new wording.
2. **Treat it as a new requirement** -- fold it as ADDED and keep the pin requirement alongside. Safe
   but leaves two overlapping requirements describing one endpoint.
3. **Archive without this fold** -- consistent with how the earlier orphans were handled, but the
   baseline then misses the preview/draft parity behaviour, which is the whole point of the change.

Recommendation: **option 1**, after the owner reads both texts side by side.

**RESOLVED 2026-09-05: the owner chose option 1 (rename).** Applied by replacing the old requirement
**in place at its original position** (index 8 in `agreement-management`) under the new name, so the
surrounding order is unchanged. The pin clause survives inside the new wording -- verified: the file
still carries 35 occurrences of "pin". The change's two ADDED requirements
(capture-state persistence, capture-state round-trip on read) were appended in the same fold, and
`agreement-capture-persistence` is archived at `2026-09-05-181511Z-`.

**Consequence to know about:** a future delta that says `MODIFIED: Generate-as-draft pins the
effective-template identity for reproducibility` will no longer find a target. That name is retired.
