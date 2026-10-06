## Context

The quote is assembled in `signing`: `StampQuoting.evaluate` resolves the pinned template's
dimensions through `TemplateCatalogApi.find`, then `DutyBasisMapper.from` builds the `rules`
module's `DutyBasis` from aggregate columns (term, rent, deposit) plus two capture-state keys:
`rentEscalationPercent` and `agreementDate`.

The deed resolves the same two keys differently:

- **Escalation** -- `documents/template/SubmittedDataValidator.validateAndCoerce` substitutes the
  template field's `default` when the submitted value is absent or blank, and rejects a value that
  is not an `int` in `[0, 100]`. The rental (and commercial) base sets default it to 5. The capture
  form seeds that default into the working set, so most agreements store `"5"`, but a cleared
  field, an API client that omits it, or a partial `captureData` stores nothing -- deed 5%, quote
  0%.
- **Execution date** -- `AgreementDocumentService.reRenderStoredDraft` fills a blank
  `agreementDate` with `agreement.draftExecutionDate()`; the quote goes straight to today.

Capture values are stored unvalidated (`AgreementService.sanitizeCaptureData` strips only
server-managed keys), and the mapper parses escalation with `new BigDecimal(...)`, accepting any
magnitude or scale into the exact arithmetic of `RentSchedule.totalRent`.

The breakdown is a `List<DutyLine>` whose replay invariant (`BASE` + every `isDelta()` line =
quoted amount) is enforced by `RuleCasesTest.breakdownReplaysToTheQuotedAmount` and mirrored by
`isDelta` in `StampQuoteStep.vue`. A frozen copy is stored on each payment order
(`StampQuoteRecord`, kind stored as its name string).

## Goals / Non-Goals

**Goals:**
- The quote's escalation and execution date equal the deed's for every template-generated
  agreement whose capture the deed would accept.
- The breakdown names the escalation and the rupees it added whenever it moved the consideration.
- No captured escalation can reach the duty arithmetic outside the deed field's type and range.

**Non-Goals:**
- Changing any template default or clause (product decision 2026-10-06: defaults stay).
- BYO-uploaded agreements (sibling `byo-document-upload` requires the fact in the declaration, so
  no template default applies).
- Making `rules` aware of templates or capture state.
- Bounding the captured execution date, or re-pricing an order after the agreement is edited --
  both pre-existing, outside this slice (see the review journal).

## Decisions

### D1. Read the template default lazily, through a not-found-only `TemplateFormApi.findForm`

Add `Optional<FormSchema> findForm(String state, String type)` to `TemplateFormApi`, documented as
returning empty **only** when no published template exists for the pair -- the one data condition
(`RegistryLayerSource.java:50-55`). The resolver signals that case distinctly (a `ResolutionException`
subtype or reason); an invalid effective template, a wrong-kind patch, a loader's
`TemplateDefinitionException` or a projector failure are code/data defects and still throw -- the
deed could not render either, so failing loudly is correct, and swallowing them would silently
quote 0%. `formFor` becomes `findForm(...).orElseThrow(...)` with the existing
`ResourceNotFoundException`.

**Lazy.** A resolve is not cheap: per call it queries the catalog, parses up to four YAML layers,
validates, canonicalises and hashes (`TemplateResolver`, uncached), and `evaluate` runs on the
quote GET, checkout (twice), finalise, intake and `StampValueReference`. So `StampQuoting` passes
the mapper a `Supplier<DefaultLookup>` and the mapper calls it only when the captured value is not
usable -- which the capture form's seeded `"5"` makes rare. The usable-capture rule lives only in
the mapper (D2). `DefaultLookup` is `NOT_FOUND` (template gone → the mapper returns empty → the
quote is unavailable, the same `NO_JURISDICTION` path an unresolvable pin takes in `dimensionsOf`),
`NONE` (no default, or one that is not an integral `Number`), or a value
(`BigDecimal.valueOf(n.longValue())`; INT defaults arrive as `Long`).

The form resolves by the pinned template's `(state, type)` exactly as `TemplateDetail.Dimensions`
carries them -- not the upper-cased duty state, since the catalog lookup is case-sensitive -- the
dimensions the deed resolves its effective template by. **Pin drift is accepted:** if a layer changes this default after a draft was
rendered, the stored PDF carries the old value while the quote reads the new one. The fulfilment
re-render already treats drift as a degraded path (`PIN_DRIFT` falls back to the stored PDF), a
change to a default bumps the content hash, and the capture form persists the seeded value, so the
case needs a cleared field *and* a default change in between. Not worth a pin-aware lookup.

*Alternatives:* calling `formFor` and catching -- rejected, a throw inside the quoting transaction
marks it rollback-only. A documents-side `effectiveValue(state, type, data, key)` reusing
`SubmittedDataValidator` -- rejected, it validates the whole submission for one field.

### D2. The mapper takes resolved inputs and parses like the deed

`DutyBasisMapper.from(agreement, dimensions, escalationDefault, clock)` stays a static function;
`escalationDefault` is the `Supplier<DefaultLookup>` of D1. Escalation: the captured value when
`Long.parseLong(value.trim())` succeeds and lies in `[0, 100]` -- the exact parse the deed's
`SubmittedDataValidator.coerceInt` uses (`Long.valueOf(s.trim())`), so `"+7"`, `"07"` and `" 7 "`
are accepted and `"5.0"` is not, on both sides; else the supplier; else zero. No `BigDecimal`
parsing of customer text remains, which closes the unbounded-scale path (`1E+999999999`). The
`[0, 100]` range is the base set's; reading the bounds from the fetched field would force the
lookup on every quote, and no layer narrows them today -- accepted, noted under Risks.

Execution date: captured `agreementDate` that parses **and** passes `PlausibleDates` (the same
plausibility gate the deed's validator applies) → `agreement.draftExecutionDate()` → today in India
-- the order `reRenderStoredDraft` fills with. `executionDate(agreement, clock)` keeps its
signature. Every existing `DutyBasisMapperTest` call site gains the new argument.

### D3. A non-delta `DutyLine.Kind.ESCALATION`, amount = rupees escalation added

Add `DutyBasis.withoutEscalation()` in `rules` (canonical constructor, escalation `0, 0`, every other
component copied) so a future basis field cannot be dropped by a hand-built copy. `DutyEngine`, for a
rate slab, recomputes the consideration over `basis.withoutEscalation()` through the same
`Quantities.standard` + `extraQuantities` path (no `precheck` -- the basis already passed it), and
when the difference is positive emits
`DutyLine(ESCALATION, plain(p) + "% rent escalation every " + n + " months", uplift)` after the
quantity lines and before `BASE`, with the uplift rounded `HALF_UP` to two places (quantities are
`DECIMAL128` and would otherwise freeze a 34-digit amount). `Kind.isDelta()` excludes it.

**The server says which lines are deltas.** `StampQuoteResponse.Line` gains `delta: boolean`, set
from `Kind.isDelta()` on the live quote and from `DutyLine.Kind.valueOf(kind).isDelta()` on a frozen
one; the frontend's `isDelta` reads that flag instead of mirroring the enum. One copy of the rule,
so a future kind can neither mis-sign nor fail to add up on screen.

**Overflow fails closed.** At 100% escalation over the longest term `@PlausibleDate` admits (~3,599
months), compounding exceeds what `longValueExact()` can hold (`DutyEngine.java:244`) and the
`ArithmeticException` escapes `evaluate` as a 500 on the checkout path. This exists today for a
captured 100%; the default now reaches more agreements, so it is fixed here: the engine returns
`Unsupported` when the duty is not representable in paise.

*Why the rupee uplift as the amount:* `DutyLine.amount` is rupees and is formatted as currency; a
percentage there mixes units. *Why in the engine:* the line rides into the frozen quote, so an
order's audit record says what moved the number. *Alternative:* a separate response field --
rejected, not frozen with the order.

### D4. Parity is pinned at the integration level

A `StampDutyCheckoutIntegrationTest` case quotes two otherwise identical 24-month agreements, one
with `rentEscalationPercent` absent and one with it captured as the production template's default,
and asserts identical quotes. The "captured" value is read from `TemplateFormApi.findForm` in the
test, not hard-coded, so a changed default or a regression in either path fails the build. The deed side's blank → default substitution is already pinned by the documents validator
tests; task 3.4 confirms one exists and adds it if not.

## Risks / Trade-offs

- [Older frozen quotes have no `ESCALATION` line] → they render exactly as before; the line is
  additive. No backfill.
- [A new kind reaches the stored breakdown] → stored as its name string and passed straight
  through, so a backend rollback reads it unchanged. A rolled-back *frontend* would show it as a
  signed delta (old `isDelta`) -- cosmetic, and the server-side `delta` flag in D3 prevents it for
  any future kind.
- [The escalation range is hard-coded `[0, 100]`] → matches the only layer that defines it; a patch
  that narrows it would let the quote accept a value the deed rejects -- harmless, since no deed
  renders, but revisit if a state narrows it.
- [A template defect now fails the quote loudly] → only when the capture is unusable (lazy); and
  the deed cannot render from a defective template either.
- [Quote for an unescalated-capture agreement rises on terms > 12 months] → intended: it now
  matches the deed. Terms of 12 months or less are unaffected.
- [The 12-month interval is duplicated] → `DutyBasisMapper.ESCALATION_EVERY_MONTHS` and the clause's
  literal "every twelve (12) months". Unchanged by this CR and fixed in the template; noted.
- [Rounding/caps can make the uplift irrelevant to the final duty, e.g. KA's INR 500 cap] → the line
  still states a true fact about the consideration; the cap line explains the rest.

## Migration Plan

No schema change. Deploy as a normal release.

## Open Questions

None.
