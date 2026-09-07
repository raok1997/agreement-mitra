# AgreementMitra — Legal posture and template governance

Team-shared, git-tracked. What protects us if a generated agreement turns out to be wrong,
what does not yet, and what remains open. Scheduling lives in `docs/ROADMAP.md`; the
questions for counsel live in `docs/COUNSEL-BRIEF.md`.

**Nothing here is legal advice.** It is an engineering and product plan.

_Written 2026-09-07; trimmed the same day once the counsel brief absorbed the open
questions. Status is tracked inline — update it here._

## Why this exists

On 2026-09-07 a defect was found in the Telangana rental template. The Telangana residential
layer re-authored the witnesseth clause list without the national `stampRegistrationClause`,
and its replacement lived only inside an opt-in "Statutory (Telangana)" section. A default
Telangana deed therefore rendered with **no stamp or registration clause at all**, while
every national deed carried one — the one state with a bespoke layer shipped strictly worse
than the shared template. Fixed the same day (`ffb7bee`) by making the section mandatory.

**The fix is not the lesson. The lesson is that nothing caught it.** No test, no review, no
person. "Be more careful" would not have caught it; a gate would have. (Same shape, same
day: nine byte-identical duplicate requirements sat in `agreement-management/spec.md` and
passed `openspec validate --strict`. Removed in `d8bc395`; the baseline is clean.)

## What already protects us

- **A reproducibility pin, and it is genuinely strong.** Every agreement records the
  template content hash plus resolved layer versions. For any deed ever generated we can
  prove which authored version produced it. Most document services cannot. Everything below
  leans on this.
- **Layer versioning.** Policy as of 2026-09-07: bump `meta.version` on any authored
  legal-content change — reusing a version across materially different content makes
  existing pins lie.
- **Beta scope.** Production is founding-team only, no real customers, as of 2026-09-07.
  This bounds exposure to roughly nil — and it expires. Several judgements below hold only
  while it does.
- **Honest marketing.** Verified 2026-09-07: the live landing copy leads on transparency
  ("nothing hidden until checkout"), says "early access… the unvarnished state of things",
  and marks each feature Live / In integration / Planned. **There is no correctness claim to
  walk back.** Keep it that way: "reviewed by Indian counsel" only becomes sayable after
  counsel has actually reviewed. Correctness stays *product strategy*
  (`docs/PRODUCT-FEATURE-SET.md`), not a claim in copy.

## What does not protect us yet

- **The disclaimer is in the wrong place.** `LandingPage.vue` disclaims *"the answers
  above"* — the FAQ. The generated agreement and the product flow carry nothing. The
  marketing is disclaimed and the product is not. See item 2.
- **No approval gate.** Anyone can edit legal wording and it ships. See item 1.
- **No terms of service at all.** See item 2.
- **No CI.** CR-7 (`ci-pipeline`) is deprioritised in `docs/ROADMAP.md` because the local
  build gates fail closed, with a revisit trigger of *"before any production / real-PII
  deployment."* Every gate runs only when someone runs the build. Revisit once item 1 lands.

## Open engineering work

### 1. A gate so unreviewed template wording cannot ship

Nothing checks that a lawyer ever saw the legal wording that shipped.

**Approach: an approvals file plus a build gate.** A list of approved template content
hashes checked into the repo, and a check that fails the build when the current hash is not
on it. Editing legal wording then forces a conscious update in the same commit — the same
fail-closed philosophy as `securityScan`. Roughly half a day.

**Adjustment for beta:** require the *acknowledgement* now ("yes, I am changing approved
legal content"); require counsel's actual signature on the hash from the first real
customer. The habit and the audit trail start immediately; the friction arrives when useful.

**Where it lives (decided 2026-09-07).** Repo-level gates
guard content that is neither Java nor npm, and there is no repo-root build. Register a
`repoContentGuards` task in the backend Gradle build, wired into `check`, resolving inputs
from `rootDir.parent`. Rejected: a repo-root Node script (nothing invokes it — a gate nobody
runs is not a gate); a Gradle task shelling out to Node (fail-closed then breaks the backend
build on a machine without Node); a git pre-commit hook (not cloned, bypassed with
`--no-verify`). The trade-off is real — the backend build reaches outside its own directory
— and accepted, because the alternative is a correct-looking gate that never runs. When CR-7
lands, CI becomes the authoritative invoker and the task itself does not change.

_Status: not started._

### 2. Put the disclaimer where the product is, and write terms of service

Two halves of one job, and the disclaimer should point at the terms.

**Disclaimer — do now.** On the review and confirmation screens, and on the on-screen
document preview (the provenance line already renders "PREVIEW — NOT FOR EXECUTION" on
screen and hides it in print, so this is nearly free). **Not** printed inside the executed
deed — the instinct is that a disclaimer inside a legal instrument is strange and might
weaken it; asked as brief Q6(d). ~1 hour.

**Terms of service — draft now, counsel completes.** The earlier plan was to wait for
counsel entirely. That was wrong about sequence, not about the final document: drafting the
half we know makes the engagement cheaper and faster, and turns brief Q6 from "draft our
terms" into "review our draft and fill the marked gaps."

- *We write:* what the service does; what the flat fee covers and what it does not; that
  stamp duty is a separate statutory amount purchased on the user's behalf; refunds and
  cancellation; that we are not a law firm and generate documents from templates; acceptable
  use; abandoned drafts; that eSign identity is handled by a licensed provider.
- *Counsel completes:* limitation of liability; the agency position on stamp duty; the DPDP
  privacy notice; dispute resolution and jurisdiction; any Consumer Protection Act 2019
  e-commerce constraints.

Publish the draft while in founding-team beta — a draft beats nothing. It must be
counsel-reviewed before the first real customer.

_Status: not started. Needs ~10 minutes of product input on refunds and fee scope before
the ToS draft can be written._

## With counsel

Everything requiring a lawyer is **one engagement**, drafted as `docs/COUNSEL-BRIEF.md`: the
five template questions (licence-vs-lease, the Telangana statutory addendum, the shared
commercial terms and their pre-filled defaults, recital and jurisdiction placement,
Telangana heading wording), plus terms of service (Q6), what a professional-indemnity
insurer will require of our review process (Q7), and where clause selection crosses into the
practice of law under the Advocates Act 1961 — asked before the rules engine is built,
because the answer changes its design (Q8).

**The brief is drafted but NOT sent, and no counsel is engaged.** This is the critical path:
weeks of latency, and it is the only thing unblocking `rental-document-content-v2` (stuck at
22/23). Update brief Q6 to "review our draft" once the ToS draft exists.

## Near-term order

Full scheduling is in `docs/ROADMAP.md`. Legal-posture work specifically:

1. Disclaimer + ToS draft (item 2) — half a day together
2. Update brief Q6, then **send the brief**
3. Approval gate (item 1) — own CR, placement already decided above
4. Revisit CI (CR-7) once items 1 and 2 land — two more gates whose whole value is being
   unmissable, and its own revisit trigger is approaching

## Expiry

Several judgements here hold **only while production is founding-team beta**. Before the
first real customer, revisit all of:

- Counsel's actual signature required on the template hash, not just an acknowledgement
- Professional indemnity cover in force
- Terms of service counsel-reviewed and published
- Disclaimer live in the app
- Prod log redaction verified (currently checked locally only)
- CI running the gates rather than trusting local build invocations

## Known, deliberately unfixed

- `openspec validate --specs --strict` is **red**: `signing-request` requirement index 7 has
  no SHALL/MUST keyword. A one-line spec-content fix, unrelated to the above — but it means
  `--strict` cannot be wired into a build gate until it is fixed.
- `--strict` does not detect duplicate requirement titles (confirmed on openspec 1.2.0 by
  appending a byte-identical requirement block and watching the spec report as passing).
  Worth reporting upstream — if it is fixed there, no local guard is needed at all. A
  dedicated CR for a local guard was proposed and **dropped 2026-09-07**: the check is spec
  hygiene, not legal risk, and detection is a three-line grep —

  ```bash
  for f in openspec/specs/*/spec.md; do
    grep "^### Requirement" "$f" | sort | uniq -d | sed "s|^|$f: |"
  done
  ```

  What a CR would buy is *unmissable enforcement*, and that is near-free once item 1's gate
  exists. **Fold it into item 1**; run the grep manually until then. Baseline verified clean
  2026-09-07.
