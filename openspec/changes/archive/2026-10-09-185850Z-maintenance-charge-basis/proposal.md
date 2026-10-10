## Why

A rental deed captures maintenance as "borne by Owner / Tenant" plus an optional amount. That cannot express how maintenance is actually handled in Indian rentals:

- included in the rent;
- a fixed sum paid on top of the rent;
- the society's bill paid directly.

The deed's Charges & Utilities section also prints a key/value table, which signs avoidable defects into the deed:

- A blank amount or penalty prints `[ Maintenance amount (INR / month) ]` or `[ Late-payment penalty (INR) ]`.
- A grace period row prints when there is no penalty to apply it to.
- The amount clause reads "The maintenance charges payable amount to INR X per month", which says neither who pays nor to whom.
- An amount entered with "Owner" selected states a figure the tenant has no part in.

Nothing in the deed covers society levies (sinking fund, corpus, major repairs, non-occupancy charges), which are the most common maintenance dispute.

## What Changes

- **"How is maintenance handled?"** replaces "Maintenance borne by". It is a dropdown with four choices, and each renders its own clause:

  | Choice | Clause says |
  |---|---|
  | Included In Rent | The rent includes maintenance, and the Owner pays the society |
  | Fixed Amount | The Tenant pays the Owner INR X a month on top of the rent, with the rent. A second clause revises it when the society revises its charges, on written notice |
  | As Billed By Society | The Tenant pays the society directly, as billed |
  | Paid By Owner | The Owner bears the charges and pays the society directly |

- **A society-levy clause** says one-time and capital society levies are borne by the Owner, even where the society bills them to the Tenant. It appears for apartments, gated communities and villas, not for an independent house or a PG room. Counsel reviews the wording.
- **The Charges & Utilities section prints as numbered clauses only, with no key/value table.** The template engine already supports this (`render: clauses` ignores field entries in the deed while the form still shows them). That removes every bracketed blank and orphan row in the section, and labels no longer reach the deed.
- **The amount belongs to Fixed only.**
  - Its label says so: "Maintenance amount (INR / month) – only if Fixed".
  - The Fixed clause renders only with a positive amount.
- **The capture form refuses to save the section when Fixed has no amount above zero.** This uses the existing cross-field rule mechanism (the tenancy date-range rule), so the error shows on the amount field.
- **The key-terms summary before payment gains a maintenance line**, and for Fixed it adds the monthly total of rent plus maintenance. The deed states no total. Terms of Service §7, which lists what the summary shows, is updated to match.
- **BREAKING (template content):** `maintenanceBorneBy` and its two clauses are removed. A stored draft carrying that key is unaffected, because unknown keys are ignored on validation. The rental base goes from v7 to v8.

**Done separately, not part of this change:**

- Commit `83d2ba5`: an invalid number blocks a section save, and money fields accept only plain rupee amounts. This change relies on it.

**Out of scope:**

- Hiding the amount field unless Fixed is chosen. A follow-up is recorded.
- A server-side refusal of Fixed without an amount.
- Commercial CAM, already noted in `docs/ROADMAP.md`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `rental-agreement-document`:
  - the rental Charges & Utilities section captures maintenance as one of four arrangements, each with its own clause, plus the always-on society-levy clause;
  - the section renders as clauses only;
  - the render-kind requirement names it.
- `stamp-selection`: the key-terms summary may show the maintenance arrangement and, for a fixed amount, the rent-plus-maintenance total.

## Impact

- **Template:** `backend/src/main/resources/documents/template/sets/rental/base.yaml` only (v8). No Java change. The KA and TG rental layers are untouched, and commercial is out of scope.
- **Frontend:**
  - `frontend/src/views/formModel.ts` gains one cross-field rule;
  - `frontend/src/views/StampQuoteStep.vue` shows the maintenance line from the stored agreement's `captureData` and `activeSections`, which `GET /api/agreements/{id}` already returns.
- **Docs:**
  - `docs/COUNSEL-BRIEF.md` Annexure A items 9–12;
  - `docs/TERMS-OF-SERVICE.md` §7 and `frontend/src/content/termsOfService.ts`;
  - `docs/ROADMAP.md`:
    - append the stamp-base question to `tg-stamp-duty-counsel-review` and `ka-stamp-duty-counsel-review`;
    - add a follow-up row for the conditional field reveal.
- **Existing agreements:** a created agreement keeps its pinned template. A re-render after this change takes the existing hash-mismatch path (`PIN_DRIFT`), not the new text.
- **No API, database, migration, or module-boundary change.**
- **Signing FSM:** no transition touched.
- **PII/security:** none. The change adds no outbound flow and no new PII. The maintenance figures are already part of the captured terms. Sandbox and dummy data only.
