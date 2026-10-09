## Why

Every deed we generate carries one fixed sub-letting covenant ("not without the Owner's prior written
consent"). Parties who want a flat ban, or who are happy to let the tenant sublet, cannot say so, and
the covenant cannot simply be made optional: under Transfer of Property Act s.108(j) a lease that is
silent lets the tenant sublet and assign, so dropping the clause silently flips the owner's position.
The fix is a mandatory choice between three explicit covenants.

## What Changes

- A new **required** enum field `subletting` with three options -- `with_owner_consent`, `not_allowed`,
  `allowed` -- and **no default**: the user must pick one. It sits in the mandatory **Term** section, so
  it renders as a row of the "Terms of Tenancy" table and appears in the capture form ("Select..." until
  answered; Save & continue stays disabled while blank, through the existing required-field gating).
- The fixed `noSublettingClause` is replaced by three `showWhen`-gated clauses in the
  `Now This Agreement Witnesseth` covenant list, in the same position. Exactly one renders once a choice
  is made:
  - `with_owner_consent` -- today's wording, unchanged.
  - `not_allowed` -- "...shall not sublet, assign, or part with possession ... under any circumstances."
  - `allowed` -- the tenant may sublet on prior written notice of the sub-tenant's name and stays liable;
    assignment still needs the owner's prior written consent.
- Applies to all six deeds: IN / TG / KA x residential (Owner/Tenant) and commercial (Lessor/Lessee).
  The four `state_type` overlays that re-author the covenant list swap the one clause id for the three.
- **The parity contract is widened.** Today a field may be `required` only if it is aggregate-backed or
  defaulted, so a generate fed only aggregate keys always succeeds. A third category is added: a
  **user-answered** field -- required, no default, not aggregate-backed, named in one shared allowlist,
  and shown as a required field in a mandatory capture section. Generate refuses such an agreement with
  a field error (`subletting: required`) until the choice is saved; so does the id-bound
  `GET /api/agreements/{id}/preview`, which validates as generate. The stateless preview the SPA uses
  shows `[ Sub-letting ]` and no sub-letting clause.
- **BREAKING (template content):** rental base `4 -> 5`, commercial base `3 -> 4`. Any agreement edited
  or re-generated after this ships must answer the question; agreements whose draft was already
  generated keep their stored PDF. An agreement with no capture state (API-only) can no longer generate
  until it supplies `subletting`.
- `docs/COUNSEL-BRIEF.md` Annexure A item 18 is rewritten as a three-way variant entry (as item 15,
  pets, already is) and moved out of "Covenants (always appear)". This keeps the record of operative
  wording true; it raises **no** new counsel question -- the wordings are decided.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `rental-agreement-document`: the witnesseth requirement no longer names a fixed no-subletting
  covenant; the parity contract admits user-answered fields in mandatory sections; a new requirement
  defines the sub-letting choice and its three covenants.
- `template-document-projection`: the commercial product line carries the same sub-letting choice; its
  "every required field is aggregate-backed or defaulted" scenario and the commercial type layer's
  "generate from aggregate keys alone" scenario admit the user-answered field.
- `agreement-management`: generate refuses an agreement whose capture state is null or lacks
  `subletting`; an API client sending only fixed fields can persist but not generate.
- `agreement-preview`: the id-bound preview returns `400 subletting: required` for such an agreement.
- `template-resolution`: `showWhen` validation also rejects an `enum` compared with a literal that is
  not a declared option (added at apply as the root-cause guard for the silent-drop risk).

## Impact

- **Templates:** `sets/rental/base.yaml`, `sets/commercial/base.yaml`, and the four
  `state_type-{TG,KA}-{residential,commercial}.patch.yaml` overlays. No engine, schema, or migration
  change -- templates load from the classpath (`RegistryLayerSource`). The catalog seeder never updates
  an already-seeded row's `version`, and production runs the `sandbox` profile, so the catalog API keeps
  reporting the old version (true since v2); handled at apply as its own change set (design, Migration
  Plan).
- **Backend tests:** the four parity guards (`ProductionRentalLayerSetTest`,
  `ProductionCommercialLayerSetTest`, `ProductionKarnatakaLayerSetsTest`,
  `AggregateBackedRequiredFieldsGuardIntegrationTest`) move onto one shared helper holding the rule, the
  allowlist, the aggregate key list (today copied four times) and a generate-ready fixture; tests that
  generate against the production sets switch to that fixture; the sandbox E2E's no-capture and
  missing-field expectations change; version assertions move to 5/4.
- **Frontend:** no code change expected -- `SelectWidget` already renders "Select..." for a required enum
  and `allRequiredDone` already gates Save & continue. A test pins that behaviour for this field.
- **Commercial is hidden in the v1 picker** (`TemplatePicker.vue`), so the commercial half ships but no
  user reaches it until commercial returns. A commercial-specific "allowed to group companies /
  affiliates" option is deferred to that release (noted on the ROADMAP's commercial paragraph).
- **Release checklist:** the manual template sign-off in `docs/ROADMAP.md` keys on template edits since
  the reviewed commit; this edit must land before that commit is recorded.
- **Terms of Service:** no change. ToS s.4 ("the wording ... every other customer of that template
  receives") still holds -- every customer choosing an option gets the same fixed wording, as with pets.
- **Signing FSM:** none touched. **PII/security:** none -- `subletting` is a non-personal enum token;
  no Aadhaar/OTP/VID/PII or secret flow is introduced or moved; sandbox + dummy data only is preserved.
