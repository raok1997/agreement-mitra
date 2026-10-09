## Context

Both template sets (`sets/rental`, `sets/commercial`) carry one fixed `noSublettingClause` in the
mandatory, field-less `Now This Agreement Witnesseth` section. The base lists it; the four
`state_type-{TG,KA}-{residential,commercial}` overlays re-author that list with `replaceSection` and
therefore list it too.

Three engine facts shape the design (all verified in code):

- **Blank is absent.** `SubmittedDataValidator.present` treats a blank string as missing. In PREVIEW a
  missing field renders as `[ <label> ]` (`TemplateCompiler`), and a `showWhen` over a missing field
  throws inside `ShowWhenEvaluator`, which the compiler treats as "drop the clause". In GENERATE a
  missing `required` field with no default raises `FieldErrorDetail(key, "required")`.
- **The parity contract** (spec `rental-agreement-document` "The parity contract is preserved";
  commercial scenario in `template-document-projection`) allows `required` only on aggregate-backed or
  defaulted fields, enforced by four tests. A required, undefaulted, user-only field violates it.
- **One live YAML per set.** No previous template version is retained; `Agreement.pinEffectiveTemplate`
  records identity at generate only, and `AgreementService.update` clears the pin. A version bump
  re-labels the content and changes the content hash.

## Goals / Non-Goals

**Goals:**
- A mandatory three-way sub-letting choice with no default, on all six deeds.
- Exactly one sub-letting covenant in a generated deed, matching the choice.
- The parity contract stays test-enforced: a required field is still guaranteed to be answerable.

**Non-Goals:**
- A placeholder clause in preview while the choice is blank (needs a new "is unset" predicate in the
  `showWhen` grammar; engine work for a transient state).
- A commercial "allowed to group companies / affiliates" option (deferred to the release that unhides
  commercial).
- Any engine, template-schema, migration, API, or frontend code change -- with one exception added at
  apply: the load-time `showWhen` enum-literal check (see Risks), since it is the cause-level guard
  for this change's own failure mode.

## Decisions

### D1. The field lives in the mandatory `Term` section, not its own section

`subletting` (enum, `required: true`, no `default`, options `[ with_owner_consent, not_allowed,
allowed ]`, label `Sub-letting`) is added to the `Term` section's entries in both bases, immediately
after `noticePeriodMonths` (rental) / the last Term field (commercial) and before the Term clauses --
entry order drives both the form's field order and the "Terms of Tenancy" row order.

It stays capture-map data, not an aggregate column: nothing queries it and no business logic reads it,
the same as every other add-on field. Because it is not aggregate-backed, the capture map is its only
source; if it ever became a fixed column, the mapper's "fixed columns win" overlay would take over.

- *Why:* each section is a capture step; a one-question step is friction. A new section would also
  have to be added to the `reorderSections` list in all four overlays. Sub-letting is a term of the
  tenancy and reads naturally as a row of the "Terms of Tenancy" table.
- *Alternative rejected:* a dedicated `Sub-letting` section. Revisit when the topic grows a second
  question (e.g. the commercial affiliates option).

Option tokens are chosen so `OptionLabels.humanize` yields the display text with no label map:
"With Owner Consent", "Not Allowed", "Allowed".

### D2. Three clauses, one per option, in the covenant list -- not one clause with a token

`noSublettingClause` is removed and replaced in every witnesseth list -- immediately after
`careOfPremisesClause`, before `inspectionClause` -- by:

| id | `showWhen` | text (residential; commercial swaps Owner/Tenant for Lessor/Lessee) |
|---|---|---|
| `sublettingWithConsentClause` | `subletting == "with_owner_consent"` | The Tenant shall not sublet, assign, or part with possession of the Premises, in whole or in part, without the Owner's prior written consent. |
| `sublettingProhibitedClause` | `subletting == "not_allowed"` | The Tenant shall not sublet, assign, or part with possession of the Premises, in whole or in part, under any circumstances. |
| `sublettingPermittedClause` | `subletting == "allowed"` | The Tenant may sublet the Premises, in whole or in part, after giving the Owner prior written notice of the sub-tenant's name, and shall remain liable to the Owner for the rent and every obligation under this Agreement. The Tenant shall not assign this Agreement without the Owner's prior written consent. |

- *Why:* a humanised token inside a sentence does not read as deed text (the rationale recorded on
  `disputeClause`), and each option changes the sentence, not one word. The precedent is
  `disputeClause` / `disputeAlternativeClause`: covenants in the always-on list gated on a value
  captured in another section.
- `allowed` deliberately does **not** permit assignment: assignment hands the whole tenancy to a third
  party, which no owner expects to be free.
- The wording is final (decided 2026-10-09); it is recorded in `docs/COUNSEL-BRIEF.md` Annexure A, not
  raised as a counsel question.
- The clause stays in the witnesseth list though its field lives in `Term`: a clause need not share a
  section with its field (`disputeClause` / `jurisdictionCity` already work this way), so the deed's
  layout is unchanged.

### D3. Parity contract: a third category, guarded by one allowlist and one rule

A required field with no default that the mapper does not supply is a **user-answered** field. It is
permitted only when **all** hold:

1. it is user-sourced (no `source: system|derived`);
2. its key is in the single shared **user-answered allowlist** (today `{subletting}`);
3. it appears in the projected capture `FormSchema` as a `required` `FormField` inside a `FormSection`
   with `optional` false -- judged on the projected field, not on section membership, because
   `FormProjector` drops system-sourced fields and SIGNATURES entries and forces derived fields
   non-required.

Separately, such a field (required, no default, not aggregate-backed) that appears in **no** projected
section -- listed in no section, or only in a field-less one -- is a violation: generate would demand
what the form never shows. Only the documents-side guards can see that case; the signing side reads the
projected schema alone.

- *Why an allowlist on top of placement:* placement alone turns the guard from deny-by-default into
  allow-by-default for every mandatory section; a stray `required: true` on, say, `carpetAreaSqft`
  would silently become a breaking change for every in-flight draft. The allowlist keeps each new
  user-answered field a deliberate, reviewed edit.
- *Why not an engine marker* (`source: user`, `answer: required`): it would encode the same intent as
  the allowlist but as a template-schema and engine concept, changing the definition schema for one
  field. Revisit if user-answered fields multiply.
- **One fact, one place.** The allowlist and the rule live in one test-support helper
  (`backend/src/test/java/in/agreementmitra/support/`, public `documents.api` types only):
  `violations(FormSchema, Set<String> requiredUndefaultedKeys, Set<String> aggregateKeys)` **returns**
  (key, reason) pairs rather than asserting, so guards assert it is empty and a negative test asserts it
  names the field. `requiredUndefaultedKeys` (required, no default, not derived, not system-sourced) is
  computed by the caller: documents-side tests filter `Field`s and project with `FormProjector` in their
  own package; the signing-side `AggregateBackedRequiredFieldsGuardIntegrationTest` reads both from
  `TemplateFormApi` and passes the **live mapper's** key set, and also asserts the helper's
  `AGGREGATE_KEYS` equals it -- so the copy can never become the authority over the mapper. The helper
  also holds `USER_ANSWERS` and `withUserAnswers(map)`, a merge rather than a fixed map, because each
  layer-set test seeds deliberately different data (corporate parties, a stale `durationMonths`, a
  Bengaluru address). `AGGREGATE_KEYS` SHALL NOT gain `subletting`: that would make the guard's new
  branch dead and erase the "Generate refuses a blank choice" input.
- **The server is the control.** The form's gating is UX; capture save performs no template
  validation, so a direct API client can store a blank or bogus value. Generate's `required`/`enum`
  check in `SubmittedDataValidator` fails closed and is what guarantees the deed.

### D4. Version bumps: rental `4 -> 5`, commercial `3 -> 4`

The rendered deed changes for every agreement, so both bases bump, with a `meta` comment in the
style of v2-v4. The overlays' own `version`s are untouched -- their content changes only by swapping
clause ids that the base defines, and the composed content hash already changes with the base.

## Risks / Trade-offs

- **In-flight drafts must answer.** An agreement started before release has no `subletting` value; its
  next Save & continue (blocked until answered) or generate (`subletting: required`) asks for it. →
  Acceptable in beta (founding team only); the form's required marker makes the ask self-explanatory.
- **API-only agreements (no capture state) can no longer generate.** → The SPA is the only client; tests
  that generate from aggregate-only data are updated to supply the answer (or assert the refusal).
- **Already-generated drafts** take the existing `PIN_DRIFT` path on stamp re-render (hash mismatch),
  compositing onto the stored PDF. → Unchanged behaviour; the stored PDF already carries the old clause.
- **Preview shows no sub-letting covenant while blank.** → The `[ Sub-letting ]` placeholder in the
  terms table plus the form's required marker signal the gap; a placeholder clause is a Non-Goal.
- **Commercial ships unreachable** (hidden picker). → Covered by the same guards and render tests, so it
  is correct when unhidden.
- **A `showWhen` literal typo silently drops its covenant** (`ShowWhenValidator` ignores string
  literals; `TemplateCompiler.included` swallows evaluation failure) -- a deed silent on sub-letting is
  the s.108(j) outcome this change exists to prevent. → Render tests iterate the field's **declared**
  options over all six effective templates and assert exactly one covenant each, so a typo or a new
  option without a clause fails. The cause-level fix -- `ShowWhenValidator` rejects a literal that is
  not a declared option, at load -- was folded in at apply (task 4.3, `template-resolution` delta).
- **The id-bound `GET /api/agreements/{id}/preview` validates as generate**, so it returns `400
  subletting: required` for any saved agreement without an answer (every pre-release row). → Accepted
  and specified (`agreement-preview` delta); the SPA uses only the stateless preview, which shows the
  placeholder.
- **The refusal reads as a bare "required"** (`describeProblem` joins `errors[].message` without the
  key). → Unreachable from the SPA because `allRequiredDone` gates Save & continue; not changed here.
- **Already-generated pre-release drafts** keep their stored PDF with the consent wording while the
  capture map has no answer -- wording and data disagree but the deed is the old, identical text; a
  re-generate is refused until answered, so the old draft stays signable. Stamp re-render checks the
  pin hash before generating and takes `PIN_DRIFT`, never the new required error.
- **Pre-existing generate race** (render, attach, pin in three transactions; an edit in between can
  pin a draft rendered from the previous capture) can now flip a covenant rather than a detail. →
  Not this CR; appended to the `draft-freeze-lock-vs-finalise` register row.

## Migration Plan

No data migration. Deploy is a normal release; rollback is reverting the template edits. Capture maps
holding the new tokens are ignored by the older template, which means an agreement whose answer was
`allowed` or `not_allowed` and is **re-generated after a rollback** silently gets the fixed consent
covenant, with the field no longer shown. Treat a rollback as "re-generated drafts revert to the consent
wording" and have those agreements re-reviewed. Stored drafts are unaffected (`PIN_DRIFT` path).

The catalog seeder never updates an existing row's `version`, and production runs the `sandbox`
profile, so the catalog API would report v4/v3 while serving v5/v4 content -- true since v2, and the
seeder javadoc claims the opposite. Fixed at apply time as its own change set if the catalog entity
allows a version refresh without a schema change; otherwise a register row.
