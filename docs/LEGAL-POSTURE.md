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

- **No approval gate.** Anyone can edit legal wording and it ships. See item 1.
- **The terms of service are a draft, not counsel-reviewed.** Published and honest about it,
  but item 2's other half — counsel completing the marked gaps — has not happened. See item 2.
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

**Note (2026-09-07):** the terms of service published under item 2 are legal wording that this
gate does not cover — it guards template content hashes, and the terms are a separate text with
its own source. Whatever the gate grows into should cover both.

_Status: not started._

### 1a. Implementing the pricing rule (and the National-template hazard under it)

The terms state the price as `total = INR 499 + max(0, duty − 100)`. The product does not do
this. `payment.amount.minor-units` is a flat 49900 and nothing computes duty —
`StampInfo.dutyAmount` only *records* what staff paid, after the fact.

**The real obstacle is ordering, not arithmetic.** Payment is taken before staff buy the
certificate (that is what the staff console's queue of "orders awaiting an e-stamp" is), so at
the moment we charge, nobody knows the duty. Any fix has to produce an *estimate* at checkout.

**And there is a live hazard beneath it: we sell a "National" agreement.** The catalog seeds
`IN` alongside `TG` for both types (`TemplateCatalogSeeder.STATE_DISPLAY_NAMES`), so a customer
can pick "National" and take it all the way to pay-and-stamp. **Stamp duty is state law — there
is no national rate**, so for an `IN` agreement there is no duty to compute and no defined state
in which staff should buy the certificate. Every fix below is undefined for that row, and any
promise to absorb duty above INR 100 is unbounded on it. Identified independently in a separate
stamp-duty session, which reached the same conclusion and put the gate first.

The terms were adjusted on 2026-09-08 so they no longer carry an open-ended absorption promise:
they now say we have been absorbing the difference and that if we ever cannot, we will state the
total before taking payment. The one thing they still guarantee unconditionally is **no second
bill after payment** — which is what makes step 3 below load-bearing rather than optional.

Four steps, smallest first. The seam already exists and is documented as such:
`PaymentPricing.priceFor(agreementId)` takes the agreement id today purely so a duty calculation
can read the state, rent and term from it later.

0. **Gate the jurisdiction — DONE (2026-09-08), `jurisdiction-checkout-gating`.** An `IN`
   agreement can no longer reach pay-and-stamp. The CR's open question was answered: **`IN` stays
   as a draft-and-download template** and does not leave the catalog. Two facts decided it — the
   unpaid `GET /api/agreements/{id}/preview` path already existed, so draft-only cost no new
   build; and `IN` is each layer set's own `base.yaml` state dimension with `TG` as an overlay on
   top of it, so removing it would have fought the architecture.

   Three things about the shipped shape are worth carrying forward:

   - **Four gates, not one.** Refusal happens at finalise, checkout, e-stamp intake and eSign
     initiation — every step that commits us to something real in a jurisdiction. Finalise matters
     because it is what places the order into the staff stamp queue; the two staff-facing steps
     matter because `PaymentGate` is otherwise their only control and **a staff `waive` satisfies
     it**, so "paid" never implied "fulfillable".
   - **An allowlist (`jurisdiction.eligible`, default `TG`), not an `IN` denylist**, and `IN`
     cannot be admitted by editing config. A denylist would have passed every test written that
     day and been silently wrong the moment Karnataka appeared — it would have defaulted to
     eligible with nothing computing its duty.
   - **It fails closed on an unknown jurisdiction**, which was a deliberate **breaking change**:
     `state`/`type` are documented optional at create, so a dimension-less agreement had a working
     path to paid fulfilment and no longer does. Drafting and preview are untouched.

   **This allowlist is temporary by design.** `state-stamp-duty-quoting` supersedes it with real
   per-state duty rules, and that change must explicitly modify or remove the
   `jurisdiction-eligibility` requirements when it lands, or the living specs will carry two
   sources of eligibility truth. The requirement text is written against the **duty jurisdiction**
   rather than the template's state dimension precisely so that change can still let a customer on
   a national template choose their property's state.
1. **Put the rule in the code (hours).** Replace the single `amount.minor-units` with
   `platform-fee` (39900) and `duty-allowance` (10000), and have `priceFor` return
   `max(fee + allowance, fee + duty)`. With duty unknown that is INR 499 — behaviour identical to
   today — but the rule lives in one named place instead of being implied by a magic 49900.
2. **Compute duty before payment (own CR).** The `rules` module resolves a mandatory, computed
   `stampDutyPayable` from the fields the agreement already carries (state, rent, deposit, term);
   `priceFor` reads it; and a pre-payment confirmation screen shows the customer the total and
   the duty inside it. That screen is what makes clause 7's "you are shown the total, and the
   duty inside it, before you pay" true, so it closes the ToS gap rather than merely enabling
   pricing. The existing free-text `stampDutyAmount` field is retired as a step inside this CR,
   not as a proposal of its own.
3. **Reconcile against the real certificate.** When staff attach `StampInfo.dutyAmount`, compare
   it with the computed figure: refund a difference in the customer's favour, absorb one in ours.
   This is what lets step 2 be an estimate at all — the terms guarantee no second bill, so an
   under-estimate has to be absorbed and an over-estimate has to come back.

Step 0 has landed. Step 1 is worth doing regardless, being the difference between a
constant and a stated rule. Steps 2 and 3 must land before the first external customer, since
until they do we absorb every rupee of duty above INR 100 on every order.

_Status: not started._

### 2. Put the disclaimer where the product is, and write terms of service

Two halves of one job, and the disclaimer should point at the terms.

**Disclaimer — done (2026-09-07).** A `LegalDisclaimer` component, one wording, on the three
screens where the customer is committing to something: the capture/review shell, the contact
step before payment, and the payment confirmation. It also renders under the on-screen
document preview, as a screen-only sibling of the provenance line, configured as
`documents.footer.screen-notice` in `application.yml` so the `documents` module still holds
no legal copy of its own. Every instance links to `/terms`.

It is **not** printed inside the executed deed, and not in a downloaded draft PDF either: the
mechanism is print-suppression, and unlike the provenance line the notice has no per-page
print-footer counterpart. That was the instinct — a disclaimer inside a legal instrument is
strange and might weaken it — and it stays an instinct until counsel answers brief Q6(d).

**Terms of service — drafted and published, counsel completes.** The earlier plan was to wait
for counsel entirely. That was wrong about sequence, not about the final document: drafting
the half we know makes the engagement cheaper and faster, and it has turned brief Q6 from
"draft our terms" into "review our draft and fill the marked gaps."

- *We wrote:* what the service does; that we are not a law firm and generate documents from
  templates; that stamp duty is a separate statutory amount purchased on the user's behalf,
  and that stamping is not registration; that eSign identity is handled by a licensed
  provider and no Aadhaar number, VID or OTP reaches us; drafts and abandoned drafts, and
  what the recovery link is; acceptable use; the user's responsibility for what they enter.
- *Counsel completes,* marked **FOR COUNSEL** on the page: limitation of liability; the
  agency position on stamp duty; the DPDP privacy notice; dispute resolution and
  jurisdiction; any Consumer Protection Act 2019 e-commerce constraints.
- *Answered 2026-09-07, and now drafted:* the fee; cancellation before the certificate is
  bought (refund less INR 100); a free eSign retry, with INR 100 only for a repeat failure at
  the signer's end and never for one that is ours or the provider's; that we correct a
  certificate we got wrong at our own cost, plus compensation equal to the duty capped at
  INR 500; a one-working-day stamping target with a capped delay credit; retention of three
  years; deletion on request by the paying party; support hours.
- *We still owe,* marked **AWAITING PRODUCT INPUT** on one clause: what a customer gets back
  once we have already bought their certificate. It is blocked on counsel (brief Q6(a)) as
  much as on us, so it stays empty rather than acquiring a plausible number.

**The pricing rule, since every remedy is sized against it.** The customer pays one total with
duty inside it: INR 499 where duty is INR 100 or less, and INR 499 plus the excess where duty
is more (duty 100 → 499; duty 200 → 599; duty 1000 → 1399). **The GROSS margin is therefore a
flat INR 399** on any agreement whose duty exceeds INR 100, and between INR 399 and 499 below
that.

**INR 399 is gross, and the net figure is the one that matters.** Confirmed 2026-09-07: the
eSign provider's per-signature charge, SHCIL transaction costs and internal cost all come out
of that 399, and none of them is recorded here yet. **Both remedies in the terms are sized at
INR 400** — a flat INR 400 where we obtain a wrong certificate, and INR 100 per working day to
a maximum of INR 400 for a late stamping. That is the gross figure rounded, so a remedy still
slightly exceeds what an order nets. **Accepted deliberately:** the overspend is small, fixed
and knowable, where a duty-pegged remedy was none of those, and "we refund our whole charge for
the service" is the most defensible sentence available. Put the real per-order cost stack in
this file when it exists; revisit only if the gap turns out to be large.

**Decided 2026-09-07: the INR 399 add-on stays flat.** Revisit when the real per-order cost
stack is known; if it ever changes, clause 7 and both INR 400 figures change with it.

**Refunds never exceed what was actually paid.** Every fixed sum the terms promise (the INR 400
and the delay credit) is reduced by any discount the customer received, floored at nothing.
Without that, a promotion below INR 400 is an arbitrage: pay INR 300, be refunded INR 400.
Promotions do not exist yet, and building them has a constraint attached — `PaymentPricing`,
`CheckoutCallbackRequest` and `CheckoutSessionResponse` all deliberately carry **no amount,
currency or discount field**, so a tampered client cannot change what is charged. A discount
must therefore be resolved server-side from a code, never accepted from the browser.

**The margin is public by construction, so secrecy was never a constraint here.** Total minus
duty is always INR 399, and `docs/PRODUCT-FEATURE-SET.md` and the live landing copy both promise
duty shown "as its own line in the total". Any customer who subtracts sees the markup. An
earlier round of this decision avoided expressing a remedy as "a refund of the platform fee" on
the grounds that it would disclose the margin; it discloses nothing the price itemisation does
not already. Recorded because the same objection will otherwise be raised again.

**The rule to remember: no remedy may be pegged to stamp duty at all.** Duty is a pass-through
we never earn and it scales with the customer's rent, so a duty-pegged promise is a liability
keyed to a number we do not control — it crosses the entire margin at **duty > INR 399**, and
the first draft answer's own formula (`amount paid − 2 × duty`) went negative at exactly that
point, from the other direction. Both remedies are therefore **flat rupee amounts**, which is
also the simpler thing to explain to a customer and to operate. Two alternatives were considered
and rejected: duty capped at a ceiling (still rent-shaped below the cap, and two numbers to
explain instead of one), and "a refund of what you paid" (equals duty + 399 under this pricing,
so it scales with rent just as badly and costs INR 399 more in every case).

**One text, two faces.** The terms live in `frontend/src/content/termsOfService.ts`. The page
at `/terms` renders from it and `docs/TERMS-OF-SERVICE.md` — the copy counsel reads, and
Annexure C of the brief — is *generated* from it (`npm run terms:doc`), with a test that
fails the build when the two drift. Two hand-kept copies of a legal text is the same class of
defect as the Telangana clause above, and it was avoidable here.

_Status: **done**, in the sense the item scoped. What remains is not engineering: send the
brief, and answer the five AWAITING PRODUCT INPUT questions. The published terms stay a draft
until counsel has reviewed them, and they must be reviewed before the first real customer._

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
22/23). Brief Q6 now reads "review and complete our draft"; the draft is Annexure C.

## Near-term order

Full scheduling is in `docs/ROADMAP.md`. Legal-posture work specifically:

1. ~~Disclaimer + ToS draft (item 2)~~ — done 2026-09-07, brief Q6 updated with it
2. **Send the brief.** The one remaining AWAITING PRODUCT INPUT clause is blocked on its
   answer, not on us
3. Approval gate (item 1) — own CR, placement already decided above
4. Revisit CI (CR-7) once items 1 and 2 land — two more gates whose whole value is being
   unmissable, and its own revisit trigger is approaching

## Expiry

Several judgements here hold **only while production is founding-team beta**. Before the
first real customer, revisit all of:

- Counsel's actual signature required on the template hash, not just an acknowledgement
- Professional indemnity cover in force
- Terms of service counsel-reviewed (they are published, as a draft, since 2026-09-07) and
  their remaining AWAITING PRODUCT INPUT clause filled in
- **The published pricing rule actually implemented** — see "Implementing the pricing rule"
  below. The terms now say plainly that the calculation is still being built and that we absorb
  duty above INR 100 until it is, so they no longer overstate the product; what expires is the
  affordability of absorbing it once there are real customers
- **A delay credit we can actually pay.** The terms now promise INR 100 per working day past a
  two-day grace, capped at INR 500. There is no refund capability in the codebase at all
  (`RazorpayWebhookService` acknowledges and ignores refund events), so every refund the terms
  promise is a manual dashboard operation. Fine at founding-team volume, not fine at scale
- ~~Disclaimer live in the app~~ — done 2026-09-07
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
