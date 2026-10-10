## Context

The rental base (`sets/rental/base.yaml`, v7) declares an optional `Charges & Utilities` section with `render: keyvalue`. It holds five fields and four clauses:

- **Fields:** `maintenanceBorneBy`, `maintenanceAmount`, `utilitiesBorneBy`, `latePaymentPenalty`, `gracePeriodDays`.
- **Clauses:** `maintenanceClause`, `maintenanceAmountClause` (`showWhen: maintenanceAmount > 0`), `utilitiesClause`, `latePaymentClause` (`showWhen: latePaymentPenalty > 0`).

Facts that shape the design:

- **A key/value section prints every listed field as a row.** A blank user field prints `[ label ]` (`TemplateCompiler.valueOrPlaceholder`); only system-sourced fields are skipped (`omitted`, `TemplateCompiler.java:555`).
- **A `clauses` section prints only its included clauses.** `appendClauseList` ignores non-clause entries (`TemplateCompiler.java:477-491`). Form projection still projects every field entry, whatever the render kind, and omits only a section with no fields. The frontend reads `renderKind` only to pick the annexure hint (`CaptureForm.vue:215`).
- **`SubmittedDataValidator.validateAndCoerce` ignores unknown keys** and checks `validation.min` on money fields.
- **`formModel.crossFieldErrors` already carries one hard-coded cross-field rule** (the tenancy date range). `blocksSave` refuses a section save while any cross-field error stands.
- **`OptionLabels.humanize` title-cases enum values** for both the dropdown and the deed. There are no per-option labels.
- **`StampQuoteStep.vue` reads `GET /api/agreements/{id}`,** whose `AgreementView` already carries `captureData` (string map) and `activeSections` (section titles).

## Goals / Non-Goals

**Goals:**

- Four maintenance arrangements, each with a clause that says who pays whom.
- No bracketed blank or orphan row anywhere in the Charges & Utilities section of a signed deed.
- A Fixed choice cannot be saved without an amount above zero in the normal flow.
- The rent-plus-maintenance total is visible before payment.

**Non-Goals:**

- **Hiding the amount unless Fixed is chosen.** That is a follow-up (`capture-field-conditional-reveal`).
- **Server-side refusal of Fixed without an amount.** See D5.
- **Any template-engine change.** No new field attributes, schema, binder, validator or compiler change.
- **Commercial CAM.**
- **A total in the deed.** Escalation and revision would make a stated total go stale.

## Decisions

**D1 — The mode decides who is paid, so there are four modes and no payee field.**
- Fixed means the tenant pays the owner, with the rent.
- As billed means the tenant pays the society.
- Included in rent and Paid by owner both mean the owner pays the society.
- The rare case of a fixed sum paid straight to the society falls under As billed.

**D2 — The option values are `included_in_rent`, `fixed_amount`, `as_billed_by_society` and `paid_by_owner`, with default `as_billed_by_society`.**
- They humanize to "Included In Rent", "Fixed Amount", "As Billed By Society" and "Paid By Owner" in the dropdown.
- The default matches the old default (tenant bears the charges, no amount).
- A required, defaultless field was rejected: the section is optional, and the parity contract forbids an unanswered required field outside a mandatory section.

**D3 — Charges & Utilities switches to `render: clauses`.**
- This one-line template change removes three problems: every blank row, the grace-period-without-penalty row, and an amount row contradicting "included in rent".
- It also frees the labels from the deed, so the amount's label can carry its guidance ("– only if Fixed") without a separate hint attribute.
- The deed loses a table that only repeated the clauses.
- *Alternative considered (the earlier draft of this change):* keep the table and add `hint`, `renderWhen` and `requiredWhen` field attributes to the engine. Round-1 review showed that each attribute brought its own failure modes (section scoping, fail-open evaluation, hash and ETag coupling), all of them to manage a table the deed does not need.

**D4 — `maintenanceAmount` is meaningful only under Fixed.**
- `maintenanceFixedClause` and `maintenanceRevisionClause` are both gated `maintenanceMode == "fixed_amount" && maintenanceAmount > 0`.
- No other clause reads the amount, so a value left over under another mode never reaches the deed.
- The earlier "about INR X" figure for As billed is dropped: reviewers read it as a possible promise, and it was the amount's only other use.
- No `validation.min`. A server-side minimum would let a `0` typed under another mode pass the form and then fail generate, after create. The `> 0` gate already keeps a zero or negative amount out of the deed, and the form rule (D5) refuses them under Fixed.

**D5 — Fixed without an amount is refused by the capture form only.**
- `crossFieldErrors` gains a rule: if the section's fields include `maintenanceMode` and `maintenanceAmount`, the mode is `fixed_amount` and the amount is blank or not greater than zero, the error attaches to `maintenanceAmount`.
- This is the first template-specific rule in that otherwise generic module (the date-range rule applies to every template). Its doc comment says so, and the conditional-reveal follow-up is where such rules move to a projected, template-declared form.
- Commit `83d2ba5` (separate) makes any invalid number block a section save and limits money to a plain rupee amount. A malformed amount therefore also never reaches create.
- `blocksSave` then refuses the section save, so the value never reaches `working`, the create call or the deed.
- If the server does receive Fixed with no amount (a direct API call), both Fixed clauses drop. The section then says nothing about who pays maintenance, but still carries the levy and utilities clauses. That is incomplete but not false.
- *Alternative considered:* the server enforces it. There is no cross-field validation on the server today, and adding it is the engine work D3 avoids. Revisit with the conditional-reveal follow-up, which needs the same kind of rule on both sides.

**D6 — The key-terms line is computed in `StampQuoteStep.vue`.**
- **Inputs:** `captureData.maintenanceMode` and `captureData.maintenanceAmount`, only when `AgreementView.type` is `residential` (commercial reuses the section title) and `activeSections` contains exactly `"Charges & Utilities"`. An absent mode is read as the template default `as_billed_by_society`, because the server fills that default and the deed states it.
- **Parsing:**
  - the amount is accepted only as `^\d+(\.\d{1,2})?$`;
  - it must be greater than zero;
  - it is summed with the rent in integer paise: `Math.round(monthlyRent * 100)` plus the amount's paise, since `AgreementView.monthlyRent` is a JS `number`;
  - both figures are formatted with `formatRupees`, which keeps paise.
- **Under Fixed with an amount above zero that is not a plain rupee amount** (such as `1e3`, only reachable via the API since `83d2ba5`), the line still says a fixed monthly charge is payable to the owner with the rent, with no figure and no total. The summary never drops a term the deed carries.
- **No line** for an unknown mode or an inactive section, and none that the deed does not carry:
  - Fixed with a blank, zero or negative amount, where both Fixed clauses drop;
  - an agreement pinned before v8, which stores `maintenanceBorneBy` and no mode; its deed states that choice, and the default would contradict it.
  - Both were found in code review at apply.
- **Drift test.** The section title, two keys, four values and the default are a second copy of template facts.
  - They live in one shared module, `frontend/src/views/maintenanceTerms.ts`, which `StampQuoteStep.vue` and `formModel.ts` both import.
  - A frontend test reads `sets/rental/base.yaml` the way `src/test-support/backendConfig.ts` reads backend config: `readFileSync` plus a multi-line regex that fails closed, with no YAML dependency added. It asserts the constants match.
  - The test also asserts that `sets/commercial/base.yaml` declares no `maintenanceMode`, since commercial reuses the section title.
- *Alternative considered:* a server-computed key-terms DTO. It would be a new cross-module contract for one display line. The drift test covers the same risk.

**D7 — Clause ids and section entries.**

| Clause id | Gate |
|---|---|
| `maintenanceIncludedClause` | `maintenanceMode == "included_in_rent"` |
| `maintenanceFixedClause` | `maintenanceMode == "fixed_amount" && maintenanceAmount > 0` |
| `maintenanceRevisionClause` | `maintenanceMode == "fixed_amount" && maintenanceAmount > 0` |
| `maintenanceAsBilledClause` | `maintenanceMode == "as_billed_by_society"` |
| `maintenanceOwnerClause` | `maintenanceMode == "paid_by_owner"` |
| `societyLeviesClause` | `propertyType == "apartment" \|\| propertyType == "gated_community" \|\| propertyType == "villa"` (`propertyType` defaults to `apartment`, so it is always present) |
| `utilitiesClause` | unchanged |
| `latePaymentClause` | unchanged |

The section's entries list the fields first (`maintenanceMode`, `maintenanceAmount`, `utilitiesBorneBy`, `latePaymentPenalty`, `gracePeriodDays`), which sets the form order, then the clauses in the table's order, which sets the deed order.

## Risks / Trade-offs

- **[A pre-v8 draft, or a v7 agreement edited after deploy, renders the default As billed]** With `maintenanceBorneBy = owner`, the payer flips to the tenant. With `maintenanceBorneBy = tenant` and an amount (v7's only way to state a fixed sum), the amount drops out of the deed.
  → Accepted. Prod is a founding-team-only beta. The preview and the key-terms line both show the arrangement before payment.
- **[A select with title-cased labels is plainer than option cards]**
  → The four labels are short and distinct, and the amount label carries the Fixed guidance. Per-option descriptions are out of scope.
- **[The amount field stays visible under modes that ignore it]**
  → The label says "only if Fixed", and the value has no effect elsewhere. Closed by the reveal follow-up.
- **[The server does not refuse Fixed without an amount]**
  → See D5. The normal flow cannot reach it, and the resulting deed is incomplete but not false.
- **[A fixed sum paid to the owner may count as consideration for stamp duty]** Today the duty is computed on rent + deposit, and Fixed now states the sum plainly.
  → The question goes on both stamp-duty counsel rows as **blocking the paid TG+KA release**.
- **[The four mode clauses still mention "the society" for an independent house]**
  → The levy clause is gated off for those property types. The mode wording goes to counsel with the rest.
- **[Revision and society-levy wording is not yet counsel-reviewed]**
  → COUNSEL-BRIEF is updated in this change. The release checklist already requires counsel review of the rendered PDFs.

## Migration Plan

- No database migration.
- The template version is bumped (base v8). Created agreements keep their pinned hash.
- Rollback is a revert.
