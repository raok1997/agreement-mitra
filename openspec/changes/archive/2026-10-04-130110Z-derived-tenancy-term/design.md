## Context

See `proposal.md` -- Why. The design-relevant state of the code today:

- `Field` carries a nullable `FieldSource`, whose only value is `SYSTEM`. `FormProjector` **skips**
  a system-sourced field entirely (`continue`), and `TemplateDefinitionValidator` **rejects**
  `systemSourced() && required()`. So the existing provenance vocabulary has exactly two positions,
  "ask for it" and "do not show it", and no way to say "show it, but do not ask for it".
- `DocumentProjectionService.withSystemValues(...)` already implements the shape we want for the
  term: strip the submitted value for a non-user-sourced key, then substitute a server value. Its
  server values arrive from the **caller** (`systemValues`), which the stateless preview cannot
  supply -- the preview has no `Agreement`, which is precisely why the preview renders the typed
  term today.
- The `signing` module already derives the term correctly (`TenancyDuration.months`) and already
  strips `durationMonths` from inbound `captureData`. Nothing on the persisted path is wrong; the
  gap is entirely on the render/capture path.
- `Field.source` participates in the effective template's **content hash**, which
  `Agreement.pinEffectiveTemplate` uses for reproducibility. `FieldSource` normalizes the absent
  case to `null` specifically so templates without system fields keep their existing hash.

## Goals / Non-Goals

**Goals:**

- One place computes the tenancy term for rendering, and it is server-side, so preview and signed
  PDF cannot diverge no matter which path renders.
- The capture form stops presenting an input whose value the server discards.
- The user learns about compulsory registration while choosing the dates, not after paying attention
  to a quote screen.

**Non-Goals:**

- Changing how `signing` derives or stores `termMonths`. It is already right; this change must leave
  `Agreement`, `TenancyDuration`, and `AgreementDocumentMapper` untouched in behavior.
  **Superseded 2026-10-03 for `TenancyDuration` only:** commit `5d39b7f` changed its count to
  end-INCLUSIVE (`Period.between(start, end.plusDays(1))`) so that `signing`'s persisted
  `termMonths` and this change's rendered term agree case for case -- see D4. `Agreement` and
  `AgreementDocumentMapper` remain untouched in behavior.
- A general expression language for derived fields. Exactly one field is derived, by a rule the
  server hard-codes; a template cannot declare *how* a field is derived, only *that* it is.
- Reconciling the client-side month count with the server's by shipping shared code between Java and
  TypeScript. See Decision 4.

## Decisions

### 1. Add a `derived` field source rather than reusing `system`

`source: derived` joins `source: system` in `FieldSource`, the two JSON schemas
(`template-definition.schema.json`, `layer-patch.schema.json`), and `TemplateNodeBinder`.

*Why not reuse `system`:* a system-sourced field is dropped from the FormSchema, so the Term section
would lose its duration row and the capture form would have to special-case the `durationMonths` key
to draw it back. That is exactly the frontend key-special-casing this change exists to remove, and it
would put a legally meaningful number outside the schema contract that every other displayed value
flows through.

*Why not a `readOnly: true` boolean beside `source`:* two independent flags admit meaningless
combinations (`source: system` plus `readOnly: false`). Provenance is one question with three
answers, so it stays one field.

`TemplateDefinitionValidator` keeps rejecting `system` + `required`, and treats `derived` + `required`
as **acceptable but normalized**: the projector emits `required: false` for it. A derived field is
genuinely always present in the output, so declaring it required in a template is not an authoring
error the way a required system field is -- it is just not a statement about capture.

### 2. Derive the term inside document projection, not in the caller

`DocumentProjectionService` computes `durationMonths` from the submitted `startDate`/`endDate` and
substitutes it into the data map, alongside the existing `withSystemValues` substitution and before
validation and compilation.

*Why not extend the `systemValues` channel:* that channel is caller-supplied, and the stateless
preview controller has no agreement to supply from. Putting the rule where both paths already pass
means parity is structural rather than maintained by two callers agreeing.

*Why not have the frontend send the right value:* the frontend is not a trust boundary, and a second
implementation of the rule is a second thing that can drift -- which is the bug being fixed.

This makes the substitution unconditional: a submitted `durationMonths` is discarded even when it
agrees with the dates, so there is no path on which a client value reaches the document.

### 3. Bump `meta.version` on both layer sets

Changing `durationMonths` to `source: derived` changes `Field.source`, therefore the effective
template's content hash, therefore the reproducibility pin. More importantly it changes **rendered
output** for any agreement whose typed term disagreed with its dates. Per the precedent recorded in
`rental/base.yaml` (the v1 -> v2 leave-and-licence bump), two materially different deeds must not
report the same authored version. Both the rental and commercial sets get a version bump and a
comment recording why.

Previously generated agreements keep their pinned version and re-render as they did.

### 4. The client recomputes the month count; the server remains authoritative

`formModel.ts` gains a `tenancyMonths(start, end)` helper mirroring `Period.between(...)
.toTotalMonths()`: whole months, trailing partial month truncated. (Originally end-exclusive;
corrected to end-INCLUSIVE -- `Period.between(start, end + 1 day)` -- on 2026-10-03, after 1 Sep to
31 Jul rendered as 10 months.)

This is a deliberate duplication, accepted because the alternative is worse. The options were: (a)
round-trip the preview for every keystroke to learn the term -- the capture surface debounces preview
calls precisely to avoid that, and the duration must update as the user tabs between the two date
fields; (b) return the derived term in a preview response and read it back -- couples a number the UI
needs immediately to a debounced, failure-prone network call, and leaves the field blank whenever the
preview errors; (c) duplicate a pure, total, well-specified calendar function.

The duplication is bounded and testable: the rule is four lines, has no I/O, and is pinned on both
sides by tests over the same boundary cases (11 vs 12 months, month-end clamping such as 31 Jan to
28 Feb, leap years, a partial trailing month). Crucially, a drift is **cosmetic, not legal** -- the
document and the agreement record both take the server's number, so a client bug would show a wrong
number on screen but could never sign a wrong term. That asymmetry is what makes (c) acceptable.

JavaScript `Date` month arithmetic overflows (31 Jan + 1 month = 3 March), so the helper compares
year/month/day components directly rather than mutating a `Date`; the tests pin that case.

### 5. ~~The capture-time registration warning~~ -- REMOVED FROM THIS CHANGE (2026-10-04)

This decision described a warning in the Term section above the registration threshold. **The warning
has been removed from this CR.** It duplicated the stamp-quote step's registration notice -- already
server-side and computed from the stamp-duty rules -- as a second, less-informed assertion of the same
legal fact by a client that cannot reach `rules`. The threshold it quoted also risked contradicting
the registration clause in the deed being drafted: the shipped Karnataka clause reads "where the term
exceeds twelve (12) months" while the national line is eleven.

Attempting to fix that by making the client threshold state-aware (11 / KA 12 / TG 0, mirrored from
the engine) was tried and reverted: Karnataka and Telangana both exempt a term below twelve months,
which is the national line already, so it changed no customer-visible outcome while duplicating the
rules engine and raising a counsel question. That sequence is the worked example now recorded under
"Scope discipline" in CLAUDE.md.

What the thresholds actually are -- and whether the shipped Karnataka clause is wrong -- is tracked as
the existing `ka-stamp-duty-counsel-review` and `tg-stamp-duty-counsel-review` rows. A capture-time warning may still be worth building, as
its own proposal, whose first question is whether the stamp-quote notice already suffices.

### 6. Cross-field validation enters `formModel.ts` as a separate function

`validateField` stays per-field and pure. A new `sectionErrors(fields, data)` composes
`fieldErrors(...)` with the cross-field rules, and the modal binds to that instead; the cross-field
rules are also addressable alone, as `crossFieldErrors(fields, data)`. Keeping the layer separate
leaves the existing per-field call sites untouched and makes the new rules unit-testable without a
Vue component.

*(Revised 2026-10-04.)* The two classes of error deliberately block **different** things, which is
why `crossFieldErrors` is separately addressable: a cross-field error blocks the section save and
completeness, because a saved reversed range reaches the preview and renders a non-positive term;
a per-field "required" error must NOT block saving, because capture is progressive and a section may
be filled over more than one visit. So `isSectionComplete` does now consult the cross-field rules --
the original note that it was "unaffected" no longer holds.

The end-before-start error attaches to the **end date** field, matching where the user can fix it.

## Risks / Trade-offs

- **[The TypeScript and Java month counts drift]** -> Both are pinned by tests over the same
  boundary-case table (11 vs 12, 31 Jan to 28 Feb, leap day, trailing partial month). Blast radius is
  capped by Decision 2: the server's value is what renders and what is stored, so drift misleads on
  screen but cannot produce a wrong deed. A task explicitly cross-checks the two tables.
- **[Users who typed a term now see it change]** -> A user who typed 11 against 24-month dates will
  see the duration become 24. That is the bug being fixed and the number the signed PDF already used,
  so the change surfaces reality rather than altering it. No stored value changes meaning.
- **[The content-hash bump invalidates cached effective templates]** -> Expected and handled by the
  existing pin mechanism; previously generated agreements re-render from their pinned version.
- **[A third field provenance complicates the template contract]** -> Mitigated by making `derived`
  purely declarative -- a template says that a field is derived, never how -- so the schema surface
  grows by one enum token and no expression evaluator.

## Migration Plan

No data migration. `agreement.term_months` is already the server-derived count; no column, value, or
meaning changes, so there is no backfill and nothing to reconcile.

Deployment is a single unit: the template YAML version bump, the projection change, and the frontend
ship together. The backend change is safe ahead of the frontend (an old frontend would send a
`durationMonths` the server now discards -- which is the fix), so a backend-first rollout degrades
gracefully rather than breaking.

Rollback is a revert. Because no persisted value changes, reverting restores the previous rendering
behavior with no cleanup; agreements generated while the change was live keep their pinned template
version and continue to render the term they were signed with.
