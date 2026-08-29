## Context

See `proposal.md` -- Why. The design-relevant facts about the codebase as it stands:

- `signing.payment.PaymentPricing.priceFor(UUID agreementId)` returns a flat `payment.amount.*` value
  and ignores its argument. Its javadoc names this change as the reason the seam exists. `Money` is
  already `(long minorUnits, String currency)`.
- `Agreement` holds `monthlyRent`, `securityDeposit`, `termMonths`, `startDate`, `endDate` and a
  pinned `templateId` -- everything the duty base needs except the property use and the jurisdiction,
  both of which come from the pinned template's `(state, type)` dimensions via
  `documents.api.TemplateCatalogApi`.
- Template layer sets seeded today are `IN` (national) and `TG`. There is no `KA` set. `TemplateCatalogEntry`
  carries `state` and `type` as strings.
- `rules` is a stub: `package-info.java` with an `@ApplicationModule` annotation and nothing else.
- Stamp intake is manual: staff buy a certificate out of band and upload it via `StampIntakeService`,
  which records whatever `dutyAmount` the request carries into `StampInfo`.
- The staff queue projection deliberately excludes rent and deposit (`estamp-intake`).

## Goals / Non-Goals

**Goals:**

- One duty calculation, driven by data, that produces correct results for two states whose statutory
  formulas have different **shapes** -- not one state's formula with the other's numbers.
- A quote whose every line the customer can see, and whose total cannot drift from its parts.
- A frozen record: what was quoted, what was paid, and what the operator must buy are the same
  number, permanently.
- `rules` gets a real, narrow public interface that `signing` can depend on without either module
  learning the other's internals.

**Non-Goals:**

- **No admin UI for rates or charges.** Configuration lands via Flyway-seeded rows in this change. A
  staff screen for editing them is a separate change with its own authorization and audit story.
- **No Drools.** Two states with slab-shaped rules do not justify a rule engine; see D2.
- **No automated duty remittance.** Staff still purchase certificates out of band. This change decides
  and collects the right amount; it does not pay a state.
- **No new state templates.** Karnataka duty rules ship without a Karnataka template layer set.
- **No registration workflow.** The assessment *reports* whether registration is compulsory; booking a
  Sub-Registrar appointment is not in scope.

## Decisions

### D1: Duty assessment lives in `rules`, behind a single public interface

`rules` exposes `StampDutyRules.assess(DutyRequest) -> DutyAssessment` and nothing else. Everything
underneath -- configuration entities, repositories, slab matching, the denomination master -- is
package-private.

*Why:* CLAUDE.md reserves `rules` for "multi-state legal-logic", and per-state stamp duty is precisely
that. Keeping it out of `signing` means `signing` never learns what Article 30 is; it asks a question
and prices the answer.

*Alternative rejected:* putting the calculation inside `signing.payment`. It would work today and be
one fewer module hop, but it puts statutory knowledge in the module that owns the signing FSM, and it
guarantees that the second consumer (the staff queue, a duty-explainer endpoint, an eventual
rules-engine migration) reaches into payment internals to get it.

**Boundary discipline:** `DutyRequest` carries only commercial terms and a jurisdiction code -- no
party, no address, no agreement id. That is deliberate: it makes it structurally impossible for the
rules module to see or log PII, rather than merely conventional.

### D2: The rule set is Flyway-seeded relational configuration, not YAML and not Drools

Three tables:

- `stamp_duty_rule_set` -- one row per (jurisdiction, `effective_from`), carrying the citation and the
  pending-verification marker.
- `stamp_duty_slab` -- child rows: property use, term range (`min_months` inclusive, `max_months`
  exclusive, null = unbounded), rate in basis points, base composition enum, an included-components
  bitmask/flags, optional cap and floor, and an optional flat-override qualifier.
- `stamp_denomination` -- the per-jurisdiction denomination master.

*Why relational, not classpath YAML like template layer sets:* charges and rates change on a gazette
notification or a commercial decision, and they need to be queryable and joinable to the frozen order
breakdown for reconciliation. YAML would also give every state's rules a redeploy cadence.

*Why Flyway-seeded rather than an ops-editable table:* a statutory rate is an auditable fact. A
forward-only migration is the audit trail, and it keeps a rate change reviewable in a PR rather than
applied by hand to production at 2am. Adding a state is an `INSERT` migration and zero Java -- which
is the "no code change per state" property that matters.

*Why `effective_from`:* Karnataka's registration fee doubled on a named date (31 Aug 2025). Rate
changes are dated events. The resolver selects the row effective at assessment time, so a future
change can be seeded ahead of its date instead of being deployed at midnight.

*Alternative rejected -- Drools:* a rule engine earns its keep when rules interact, chain, or are
authored by non-engineers. These are non-overlapping slab lookups. Drools would add a dependency, a
DRL authoring surface, and a debugging story for a `switch` over term ranges. Revisit when a state's
rules genuinely need inference.

### D3: The configuration model, and where it stops

Expressible today: `(property use, term range) -> rate % of a base`, where the base is
`AVERAGE_ANNUAL_RENT` or `TOTAL_TERM_RENT` over a selected set of components, bounded by an optional
cap and floor, with flat-rate overrides selected by a qualifier (Karnataka's family lease). That
covers both seeded states and, on inspection, most Indian lease articles.

**Where it does not stretch, stated plainly** -- these need a *model extension*, not a config row:

- **Area-banded duty** (Karnataka's own family-lease slab is already this shape: BBMP vs municipal
  vs outside). Handled here only because it is a *flat* override keyed by a qualifier; a state that
  bands its *percentage* by area needs a second selection axis on the slab.
- **Ready-reckoner / circle-rate-linked duty** (Maharashtra ties lease duty to a market-value table).
  That needs a per-locality rate table the model has no concept of.
- **Duty that depends on facts we do not capture** -- built-up area, floor count, tenant category.
- **Compounded or stepped escalation** where each year's rent differs; the base composition assumes a
  uniform monthly rent.

Load-time validation rejects overlapping or non-contiguous term ranges rather than letting a gap
silently return zero duty. That is the single highest-value guard in the model: a configuration typo
in a money calculation must fail loudly at startup, not quietly at checkout.

### D4: Jurisdiction resolution, and the national template

Jurisdiction is the pinned template's `state` dimension when it is a real state. When the template is
national (`IN`), the customer explicitly picks the property state from the supported list and it is
persisted on the agreement.

*Why not treat `IN` as a configured pseudo-jurisdiction:* it would invent a duty no state levies and
produce confidently wrong stamping -- the worst failure mode available here.

*Why not make `IN` unpurchasable:* it breaks the only general product line, and with no Karnataka
template yet it would make Karnataka unsellable despite this change shipping Karnataka's rules.

*Why not parse the address:* the address is free text, and `estamp-intake` already established that
duty follows the drafted-under state, not the address. An explicit choice is not address parsing.

The chosen jurisdiction is immutable once an order exists, for the same reason the denomination is:
the money has been attributed to a state.

### D5: `PaymentPricing` returns a quote; the total is derived, never stored independently

`priceFor(UUID)` becomes `quoteFor(UUID) -> Quote`, where `Quote` is an ordered list of typed line
items plus a total. The total is computed as the sum of the items -- there is no separate authored
total that could disagree with them.

Line item types: `STAMP_DUTY` (pass-through), `SERVICE_FEE`, `PROCUREMENT_FEE`, `DELIVERY_FEE`, `TAX`.
A `state_charge` table holds the per-jurisdiction fees, tax rate, and an optional per-template-type
service-fee override, also `effective_from`-dated.

**Arithmetic:** rates are basis points; the base is computed in paise as `BigDecimal`; each line item
is rounded **once**, to whole paise, `HALF_UP`; the total sums the already-rounded items. Rounding
each item once and summing afterwards is what makes the customer-visible breakdown add up exactly --
rounding the total independently would not.

Duty specifically is rounded **up to the whole rupee** before denomination matching, since duty is
never quoted in paise and the customer buys a denomination at or above it anyway.

`payment.amount.*` is superseded. It is left readable for one release as a fallback only for
agreements created before this change (see Migration).

### D6: GST on services, duty excluded -- with a named commercial dependency

Tax is computed on `SERVICE_FEE + PROCUREMENT_FEE + DELIVERY_FEE`. `STAMP_DUTY` is excluded from the
taxable value on the pure-agent reasoning: it is a statutory levy collected on the customer's behalf
and remitted as duty, not consideration for a service.

**This needs the client's CA to confirm before production**, and the pure-agent treatment carries
conditions (separate disclosure on the invoice, recovery at actual cost, no markup) that the invoice
design must satisfy. Modelling duty as its own line item and never marking it up is what keeps that
treatment available; making the rate configuration-driven is what makes a CA's correction a config
change rather than a release.

### D7: The frozen breakdown is stored with the order, not recomputed

`payment_order` gains the serialized line-item breakdown, the selected denomination, and the
jurisdiction. Reads return the frozen record; nothing re-derives a placed order's price.

*Why store the whole breakdown rather than the inputs plus a rule-set version:* replaying a
calculation to display an old order means the display depends on the calculation code staying
bug-compatible with itself forever. Storing the answer costs a JSON column and removes that coupling
entirely. The inputs are still on the agreement if anyone needs to audit the derivation.

### D8: Intake reconciles against the paid-for denomination, and refuses rather than fails the FSM

`StampIntakeService` gains two preconditions: the certificate's `dutyAmount` must be at least the
agreement's frozen denomination, and its jurisdiction must match the agreement's.

A violation is a **400 with a field-level error**, not a `STAMP_FAILED` transition. `STAMP_FAILED` is
for a stamp that cannot be produced; this is an operator holding the wrong certificate, which is
correctable by uploading the right one. The request stays in `PDF_GENERATED`, the durable
awaiting-stamp state, and the work stays in the queue.

Over-stamping is accepted: a more expensive certificate than paid for is the operator's or the
business's loss, never the customer's problem, and refusing it would strand fulfilment.

### D9: The queue shows the denomination, and that is the only money it shows

The queue projection currently excludes rent and deposit deliberately. It gains exactly one monetary
field: the frozen denomination the operator must purchase.

*The tension, acknowledged:* in an uncapped state like Telangana, duty is a fixed percentage of rent
plus deposit, so a duty figure does leak an aggregate of both. That is accepted because the
denomination **is the purchasing instruction** -- the queue exists to let an operator buy the right
certificate, and withholding the amount would defeat it. What is not accepted is showing rent and
deposit themselves, which remain excluded.

## Risks / Trade-offs

- **Seeded rates are not legally verified.** They come from the statutory schedules and secondary
  sources. A wrong rate is a customer-facing defect with legal consequences. -> Every rule row carries
  its citation and a pending-verification marker; the marker is a blocking item before any production
  launch, and the `effective_from` model makes a correction a dated config change rather than a
  rewrite.
- **GST treatment could be wrong.** -> Duty is a separate, never-marked-up line item and the tax rate
  and taxable base are configuration, so a CA's correction is a config change. Flagged as a named
  pre-production dependency in the proposal.
- **The configuration model will not fit some state.** -> D3 names the shapes it cannot express, so
  the failure is a known extension rather than a discovery mid-onboarding. Load-time validation means
  an ill-fitting configuration fails at startup, not at checkout.
- **Under-stamping is now a supported product path.** The business has chosen to allow it. -> Bounded
  to the state's real denominations, gated behind an explicit acknowledgement, and permanently
  audited with the warning version shown. We can prove what the customer was told.
- **Breaking price change.** Every agreement's price changes, in most cases upward. -> Migration
  below; the change is customer-visible by design (an itemised quote replacing an opaque flat fee).
- **`payment_order` gains a serialized breakdown.** A schema-in-a-column. -> It is a frozen historical
  record that is never queried by its parts, which is the case where serialization is appropriate;
  the queryable facts (total, currency, denomination, jurisdiction) stay as columns.
- **Two effective-dated tables add resolution complexity.** A query bug could pick the wrong-dated
  row. -> Resolution is one shared, unit-tested helper over both, with explicit boundary tests on the
  effective date itself.

## Migration Plan

1. **Schema first.** One forward-only migration creates `stamp_duty_rule_set`, `stamp_duty_slab`,
   `stamp_denomination`, `state_charge`, and `understamp_acknowledgement`; adds
   `duty_jurisdiction` and `stamp_denomination_minor_units` to `agreement`; adds the breakdown,
   denomination and jurisdiction columns to `payment_order`. Seeds Karnataka and Telangana rules,
   denominations, and charges in the same migration. `ddl-auto: validate` unchanged.
2. **Rules module, then pricing.** `StampDutyRules` and its tests land before `PaymentPricing`
   changes, so the calculation is proven against both states' slab boundaries before any money path
   depends on it.
3. **Existing agreements.** Agreements created before this change have no jurisdiction and no
   denomination. Orders **already placed** keep their stored amount and are unaffected -- the frozen
   record is read, never recomputed. Agreements **not yet paid** must pass through stamp selection
   before checkout; they cannot be priced by the old flat amount, because there is no denomination to
   attach and the intake reconciliation would have nothing to check against.
4. **Configuration cleanup.** `payment.amount.*` is retained but unused after step 3 and removed in a
   follow-up once no pre-change unpaid agreement remains.
5. **Rollback.** Reverting the application while the migration stands is safe: the new tables and
   columns are additive and the old flat-price path still reads its configuration. Reverting the
   *migration* is not supported (forward-only); a bad seeded rate is corrected by a new
   `effective_from` row, not by editing the applied migration.

## Open Questions

- **Denomination master content per state.** The exact set of e-stamp denominations SHCIL sells in
  Karnataka and Telangana needs confirming against the issuing portals. Deferrable: it is seed data in
  one table, and a wrong list changes no interface, no spec, and no task -- only the rows.
- **Whether over-stamping should surface a refund path.** If an operator attaches a certificate above
  the paid-for amount, today the business absorbs it. Deferrable: intake accepts it either way, and a
  refund flow is a separate change against `payment-processing`.
