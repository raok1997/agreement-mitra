## Context

See `proposal.md` -- Why. The constraints that shape the approach:

- **Karnataka registers itself.** `StampDutyConfiguration` resolves rules and catalogs by classpath
  glob (`classpath*:rules/stamp-duty/**/*.yaml`, `classpath*:rules/stamp-paper/*.yaml`) and
  `TemplateCatalogSeeder` discovers layer sets under `documents/template/sets/*/`, emitting one
  catalog row per `state-<XX>.patch.yaml`. Dropping files is the registration mechanism.
- **Eligibility is derived, not configured.** `jurisdiction-eligibility` removed the allowlist;
  `DutyEngine.isChargeable()` is `rule.reviewed() || allowUnreviewed`. Nothing needs to be told
  about Karnataka.
- **The duty pipeline is fixed and specified.** `stamp-duty-calculation` pins the order, so a new
  bound must be placed in it deliberately rather than wherever it is convenient.
- **The legal source is dated.** The primary Article 30 text available is the IGR Karnataka schedule
  PDF, self-dated "Updated till 20th April, 2017"; the Karnataka Stamp (Amendment) Act 2023 revised
  rates for many instruments. This design does not resolve that -- it makes the uncertainty explicit
  and machine-gated.

## Goals / Non-Goals

**Goals:**

- Express Karnataka entirely as data, with exactly one engine capability added -- the per-slab bound
  -- and no Karnataka-specific Java.
- Keep the Karnataka layer sets as thin overlays on the shared national bases, so the deed wording
  has one home.
- Make the offer policy reflect what the jurisdiction can actually issue.

**Non-Goals (design-level, beyond the proposal's scope list):**

- No new `DutyExtension`. If Karnataka needs Java, the modelling is wrong.
- No change to `DutyBasis`, `DutyOutcome` or `StampDutyCalculator` -- the `rules` module's public
  API stays fixed, so no module boundary moves.
- No second rounding step, no per-slab rounding. Rounding remains once, at the end.

## Decisions

### D1. Per-slab bounds in the rule schema, not a Karnataka extension

Article 30(1)(i) caps the residential **first slab** at INR 500 and leaves the 13-60 month slab
uncapped. `maximumAmount` today is rule-level (`RuleSet.Bounds`), so encoding the cap there would
also cap the second slab -- a 13-month Karnataka lease would quote INR 500 instead of INR 600, an
under-stamped instrument.

**Chosen:** add optional `minimumAmount` / `maximumAmount` to `SLAB_KEYS` in `RuleSetLoader` and to
`RuleSet.Slab`, applied to the slab's computed duty immediately after the rate or fixed amount and
**before** the rule-level bounds.

- **Why both, not just `maximumAmount`:** the pair is what the rule-level bound already is, the
  loader validation is the same code path, and a one-sided bound invites a later asymmetric
  addition. A slab minimum has no Karnataka user today and is not exercised by KA data -- it is
  covered by a unit test only, and that is stated in the tasks so it is not mistaken for dead code
  that slipped in.
- **Why before the rule bounds:** the rule bound is the outer bound on the quote. Reversing the order
  would let a slab cap override a rule minimum, which no rate table means.
- **Breakdown line:** a slab bound is reported distinguishably from a rule bound. A customer seeing a
  duty of INR 500 against a consideration of INR 340,000 is owed the reason, and "slab maximum" is a
  different fact from "rule maximum".

**Alternative rejected -- a `DutyExtension` bean (the design D5 escape hatch).** It works and needs
no schema change, but it puts a plain rate-table fact into per-state Java. The extension hook exists
for rules that *cannot* be expressed as data; a cap on a band is the most ordinary thing a stamp
schedule does, and the next state with one would add a second bean. The schema addition is roughly
the same size and is reusable.

**Alternative rejected -- splitting the rule in two** (a residential rule for 1-12 months with a rule
maximum, and another for 13-60). `RuleSetRegistry.find` selects on `(state, kind, usage, date)` and
requires non-overlapping effective windows -- there is no term dimension in selection, so two rules
for the same state/usage/date would be ambiguous by construction.

### D2. Average annual rent on every slab, including terms under a year

The Article 30(1) rate column reads "on the total amount or value of the average annual rent,
premium, fine and money advanced" for **every** sub-clause, including (i) and (ii). Telangana's
sub-year slab uses total rent; Karnataka's does not. This is data, not code -- `AVERAGE_ANNUAL_RENT`
already exists as a standard quantity (`totalRent * 12 / termMonths`).

The consequence is deliberate and must not be "fixed" later by someone who assumes it is a typo: for
a term under twelve months the average annual rent is **higher** than the total rent -- an 11-month
lease at INR 20,000 yields an average annual rent of INR 240,000 against a total rent of INR 220,000.
Whether the statute intends annualisation for a sub-year lease is a real reading question and is
recorded as an open question below, not silently resolved. For residential the INR 500 cap makes the
question moot at any realistic rent; for commercial it does not.

### D3. Refundable deposit is in the consideration

Clause (1)'s chargeable-event text names "rent is fixed, or fine or premium or money advanced or
security deposit (as the case may be) is paid or delivered", and the Article's Explanation defines
"money advanced" to include the security deposit "whether refundable or adjustable towards the rent".
The Explanation is printed under clause (2) in the available copy, so whether it governs clause (1)
is arguable -- but clause (1) names the deposit on its own, so inclusion is the better-supported
reading here than it is for Telangana. Consistent with the project's standing choice, the
conservative direction wins: over-collection is refundable, an under-stamped instrument is
inadmissible under s.35 until duty plus penalty is paid.

### D4. Counterpart duty is flat INR 500, and that is an over-reading

Article 22 makes counterpart duty conditional -- the same duty as the original where the original's
duty is at most INR 500, otherwise INR 500. `RuleSet.counterpartDuty` is a flat amount and will not
be extended, because `DutyBasis.counterparts` has **no call site above 1** anywhere in the signing
module: the product never asks for a counterpart, so the conditional form is unreachable. Flat 500 is
recorded with a comment stating plainly that it over-states when the original duty is at or below
INR 500, and that the conditional form becomes real only if counterparts ever become a product
feature.

### D5. Karnataka's catalog is planned over an any-amount e-stamp medium

Karnataka issues e-stamp certificates for an arbitrary amount (Kaveri Online / SHCIL); Telangana does
not, which is the sole reason Telangana carries the `SINGLE_PAPERS` INR 100 policy. So Karnataka uses
the default `PLANNED` policy over two media:

- `e-stamp`, `ANY_AMOUNT` -- plans exactly the duty, and is therefore the recommended, pre-selected
  option at every duty value.
- `stamp-paper`, `DENOMINATIONS` -- supplies the below-duty options the customer may override to,
  each carrying the existing audited under-stamping acknowledgement.

The denominations medium is **load-bearing for the override**, not decorative: under `PLANNED`,
`StampOptions.of` builds the lower options from `DENOMINATIONS` media only. Without it Karnataka would
offer exactly one value and the customer could not choose a lesser one, which is not what was asked
for.

**Honesty about the denominations:** no primary source for the current Karnataka physical
non-judicial denominations was found. They ship marked UNVERIFIED with the same confidence discipline
as the Telangana `maxPapers` figure, and the catalog says so.

### D6. Both Karnataka statutory sections are mandatory

The Karnataka `state_type` layers remove the national `stampRegistrationClause` because the Karnataka
clause supersedes it. Telangana's files record what happens when that removal is paired with an
opt-in section: a default Telangana deed rendered with **no** stamp or registration clause at all --
the one state with a bespoke layer shipped strictly worse than the national default. The Telangana
*commercial* set still carries that shape. Karnataka does not reproduce it: `optional: false` on both
sections. The coupling is stated in the spec so a future edit cannot flip one without the other.

### D7. Registration threshold is twelve months, and the clause must agree

Telangana reports registration required for every lease because of a state amendment to s.17 of the
Registration Act 1908. Karnataka has no such amendment, so the unamended s.17(1)(d) applies -- leases
from year to year, for a term exceeding one year, or reserving a yearly rent. Hence
`requiredWhenTermMonthsOver: 12`.

The template clause and the rule must state the same threshold. Telangana's clause says "where the
term exceeds eleven (11) months" while its rule reports registration required at every term -- a
drift this change does not inherit. The spec makes agreement between clause and rule testable.

### D8. Layer-set shape mirrors Telangana, with each set's own section titles

Four files, no new base. The rental set's sections are Owner / Tenant / Schedule of Property; the
commercial set's are Lessor / Lessee / Schedule of Premises and its covenant list includes
`quietEnjoymentClause`. Each `reorderSections` op must name **that set's** titles -- a copied
Telangana ordering with the wrong set's titles is the likeliest mechanical error in this change, and
the render tests exist to catch it.

`stampDutyAmount` is carried forward exactly as the Telangana v3 layer has it: `source: system`, a
`"Provision for stamp duty"` placeholder, and the amount clause gated `showWhen: stampDutyAmount > 0`
so an unstamped draft prints neither a blank row nor a false figure.

### D9. Version numbers start at 1

Every Karnataka layer is new, so nothing has been rendered from an earlier version and there is no
pinned agreement to keep answerable. `version: 1` on all four files. (Telangana's v2 and v3 bumps
exist because deeds already pinned v1.)

## Risks / Trade-offs

- **The rates may be stale (2017 source vs the 2023 amending Act).** -> Every figure ships
  `counselReview: null`, so Karnataka is refused for paid fulfilment in any deployment that does not
  set `allow-unreviewed`. The provenance names the 2023 Act as the first thing to check, so the
  review does not start from scratch. This is the same gate Telangana ships behind.
- **The average-annual-rent reading could be wrong for sub-year terms (D2).** -> Capped to INR 500 for
  residential, so the exposure is commercial only. Recorded as an open question with the exact
  alternative reading, so counsel is asked a specific question rather than "please check the maths".
- **Per-slab bounds change a pipeline every existing rule flows through.** -> The field is optional and
  absent from every Telangana rule, so their computed duty is unchanged by construction; the
  Telangana worked `cases:` in the build are the regression net. A unit test pins that a slab bound
  does not leak into a neighbouring slab, which is the specific way this could go wrong.
- **The e-stamp medium promises a fulfilment route ops may not have.** -> The catalog describes what
  the *state* issues, which is a fact; it does not claim we can buy one. Paid fulfilment is separately
  gated by counsel review, and the procurement channel is recorded as a follow-up rather than implied.
  The customer-facing copy must not say Karnataka is stampable before that is true.
- **Denominations are unverified (D5).** -> They affect only the override options, never the legal duty
  or the recommended value; a wrong denomination offers a customer a stamp value that does not exist,
  which is visible and correctable, not a silent under-stamping.
- **Two sets, four files, much copied structure.** -> The likeliest defect is a mechanical one
  (a Telangana title left in a Karnataka reorder, a clause id colliding). Render tests for both sets
  resolve and compile the real layer sets rather than asserting on the YAML.

## Migration Plan

No data migration and no schema change. Karnataka introduces no table, column or Flyway migration.

- **Deploy:** the rule, catalog and layer files are classpath resources read at startup. On boot the
  rule loader validates and hashes them and fails the context on a defect, so a malformed Karnataka
  file cannot start a server in a half-registered state. `TemplateCatalogSeeder` (local/sandbox only)
  inserts the new `(KA, residential)` and `(KA, commercial)` rows idempotently per dimension pair, so
  an existing seeded database picks them up on the next start without duplicating Telangana's.
- **Rollback:** delete the four template files, the two rule files and the catalog file. Nothing else
  references Karnataka, and no row written by this change is depended on by another -- catalog rows
  for a removed dimension simply stop resolving. Agreements already created against a Karnataka
  template pin their effective template, so an existing document keeps rendering the wording it was
  generated with.
- **Production posture:** with `rules.stamp-duty.allow-unreviewed` at its default `false`, Karnataka
  deploys as draft-only -- visible, previewable, downloadable, not payable -- until counsel review
  lands. That is the intended first state, not an incomplete rollout.

## Open Questions

These are deferrable: each is a legal reading recorded for counsel, none changes the specs, the
approach or the task breakdown, because every figure is already gated unverified.

1. **Did the Karnataka Stamp (Amendment) Act 2023 move Article 30?** The consulted schedule is dated
   2017. This is the first question, and it may change every rate in both rule files -- which is why
   they ship unchargeable.
2. **Is "average annual rent" annualised for a term under one year?** Under the reading implemented,
   an 11-month lease at INR 20,000 has an average annual rent of INR 240,000. The alternative reading
   is that the base is simply the rent for the term (INR 220,000). Residential is capped either way;
   commercial is not.
3. **Does the Article 30 Explanation defining "money advanced" govern clause (1)?** It is printed
   under clause (2) in the available copy (D3).
4. **Is a Karnataka residential tenancy properly a lease under Article 30, or a licence?** This is
   the existing `rental-deed-lease-vs-licence` question, now with a second state depending on the
   answer. An instrument assessed under the wrong article is inadmissible under s.35 until duty and
   penalty are paid.
5. **What are the current Karnataka physical non-judicial stamp paper denominations?** Affects only
   the override options (D5).
