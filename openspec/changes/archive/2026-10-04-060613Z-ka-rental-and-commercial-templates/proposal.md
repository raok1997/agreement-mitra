## Why

Karnataka is the second-largest source of demand we expect after Telangana, and it is the one state
the codebase already half-promises: the rental base template's own comment says "this set ships KA +
TG", `ProductionRentalLayerSetTest` pins a known defect on the grounds that it "reaches KARNATAKA",
and `docs/ROADMAP.md` carries `ka-stamp-duty-and-template` in the follow-up register -- raised when
Karnataka was dropped from `state-stamp-duty-quoting` during its rewrite for Telangana. Today a
Karnataka customer can draft a deed, but it is priced by no rule, matches no state layer, and renders
an operative exclusive-jurisdiction covenant reading "the courts at [ Jurisdiction city ]".

This change makes Karnataka a real jurisdiction: rules that price it, templates that state its law,
and a stamp offer that reflects the fact that -- unlike Telangana -- Karnataka issues e-stamp
certificates for any amount.

## What Changes

**One CR, not two.** This touches no aggregate, no endpoint and no state-machine transition. It is
additive rules data, additive template-layer data, and one bounded engine schema addition without
which the Karnataka residential rule cannot be expressed. Neither half ships alone: a rule without a
template prices a deed that names a placeholder instead of a court; a template without a rule cannot
be stamped or paid for. Telangana shipped the same way.

### Stamp duty rules (data; no code change to register them)

- Add `rules/stamp-duty/KA/lease-residential.yaml` and `rules/stamp-duty/KA/lease-commercial.yaml`
  encoding Karnataka Stamp Act 1957 Schedule Article 30(1), and `rules/stamp-paper/KA.yaml`.
  `StampDutyConfiguration` discovers all three by classpath glob, so Karnataka registers with no Java
  change and -- because `jurisdiction-eligibility` has no allowlist -- becomes a chargeable
  jurisdiction wherever `rules.stamp-duty.allow-unreviewed=true` and stays ineligible elsewhere.
- Every figure ships **UNVERIFIED** with `counselReview: null`, exactly as the Telangana rules do.
  The primary source (the IGR Karnataka schedule PDF) is self-dated "Updated till 20th April, 2017",
  while the Karnataka Stamp (Amendment) Act 2023 revised rates for 50+ instruments -- so Article 30
  may have moved and secondary sites disagree with each other. Counsel checks the 2023 amendment
  first.

### Duty engine: a per-slab maximum

- Article 30(1)(i) caps the **residential under-one-year** duty at INR 500 -- and that slab only. The
  engine's `maximumAmount` is rule-level, so putting the cap there would also cap the 13-60 month 1%
  slab. Add an optional per-slab `maximumAmount`/`minimumAmount`, applied to the slab's computed duty
  **before** the rule-level bounds, with its own breakdown line. The fixed pipeline order becomes:
  slab rate -> **per-slab bounds** -> rule-level bounds -> extension adjust -> surcharges ->
  counterpart -> single rounding.

### Stamp offer: exact duty by default, overridable

- Karnataka uses the default `PLANNED` offer over an `ANY_AMOUNT` e-stamp medium plus a
  `DENOMINATIONS` paper medium, so the **exact legal duty is the pre-selected option** and each paper
  denomination below it is an override carrying the existing audited under-stamping acknowledgement.
  This deliberately does **not** copy Telangana's `SINGLE_PAPERS` INR 100 policy, which exists only
  because Telangana has no e-stamp at all.

### Templates (data; two existing layer sets, no new base)

- `sets/rental/`: add `state-KA.patch.yaml` + `state_type-KA-residential.patch.yaml`.
- `sets/commercial/`: add `state-KA.patch.yaml` + `state_type-KA-commercial.patch.yaml`.
- Karnataka statutory overlay (Karnataka Stamp Act 1957 Art. 30 stamping, Registration Act 1908
  registration before the jurisdictional Sub-Registrar, applicable tenancy/property law), the
  system-sourced `stampDutyAmount` treatment the Telangana v3 layer uses, and `jurisdictionCity`
  defaulted to Bengaluru.
- The Karnataka statutory section is **mandatory** in both sets. Telangana's own files record why:
  while its statutory section was opt-in, the `state_type` layer had already removed the national
  `stampRegistrationClause`, so a default Telangana deed rendered with **no** stamp/registration
  clause at all. Karnataka's layers make the same removal, so mandatory is a correctness requirement
  here, not a preference.

### Supporting edits

- `TemplateCatalogSeeder.STATE_DISPLAY_NAMES`: add `"KA" -> "Karnataka"`.
- Customer-facing copy that hard-codes Telangana as the only stampable state
  (`frontend/src/content/termsOfService.ts` clause 5, `frontend/src/views/LandingPage.vue` FAQ).
- `ProductionRentalLayerSetTest.theAlwaysOnJurisdictionCovenantIsUnfilledOutsideTelangana`: its
  assertions still hold (it resolves only `IN` and `TG`), but its comment claims the placeholder gap
  "reaches KARNATAKA", which this change makes false. Correct the comment, add a Karnataka assertion
  showing a named court, and keep the national gap pinned.
- `docs/ROADMAP.md`: delete the `ka-stamp-duty-and-template` register row, correct the Karnataka
  claim in the `national-jurisdiction-city-unfilled` row.

### Explicitly out of scope

- **Ops procurement of Karnataka e-stamp certificates.** The catalog describes what Karnataka issues;
  it does not build a purchase channel. Until ops confirms a Kaveri Online / SHCIL route, Karnataka is
  priced and drafted but not fulfilled in production -- which is also what the counsel gate enforces.
  Recorded as a follow-up.
- Counsel verification of the rates. Gated, not performed here.
- Any change to the national (`IN`) jurisdiction-city placeholder defect. Karnataka stops being
  exposed to it; the national hole stays open under its existing register row.
- Registration **fee** calculation. The engine reports whether registration is required, not its cost,
  and that stays true for Karnataka.

## Capabilities

### New Capabilities

None. Karnataka is a new jurisdiction inside capabilities that already exist.

### Modified Capabilities

- `stamp-duty-rules`: ADD a Karnataka lease-duty requirement (residential + commercial slab table)
  and a Karnataka stamp-paper-catalog requirement (e-stamp any-amount + physical paper, planned
  offer). The existing source-marker and counsel-gate requirements already govern Karnataka
  generically and are unchanged.
- `stamp-duty-calculation`: MODIFY "Duty follows one fixed calculation order" to place per-slab
  bounds before rule-level bounds, and ADD a requirement that a slab may bound the duty it computes.
- `rental-agreement-document`: ADD a requirement that the Karnataka layers contribute a mandatory
  statutory overlay to the residential set, stating the stamp/registration clause that the layer's
  own removal of the national clause would otherwise leave absent.
- `template-document-projection`: MODIFY "A commercial lease product line resolves from its own layer
  set" to serve `(KA, commercial)` alongside `(IN, commercial)` and `(TG, commercial)`, and ADD the
  Karnataka commercial statutory overlay requirement -- mandatory, in deliberate contrast to the
  existing Telangana opt-in requirement.
- `stamp-selection`: MODIFY "Stamp options are bounded by the jurisdiction's offer policy and one
  option is pre-selected" to add the Karnataka scenario -- the exact legal duty pre-selected, lower
  denominations offered as acknowledged overrides.

## Impact

**Signing status FSM: none.** No transition is added, removed or re-ordered; the active path
(`PDF_GENERATED -> STAMPED -> SIGN_REQUESTED -> SIGNED | FAILED | EXPIRED`, with `STAMP_FAILED` off
the stamp step) is untouched. Karnataka agreements traverse the identical path a Telangana agreement
does. No change to the async eSign/webhook flow, so no sequence diagram is owed.

**PII / security review checklist.**

- **Does this change introduce or move Aadhaar, OTP, VID, or other PII?** No. The duty calculator is
  specified to handle no personal data (`stamp-duty-calculation`: "The calculator handles no personal
  data") and `DutyBasis` carries only amounts, dates, a term, a state code and enum classifiers. The
  new rule files, stamp-paper catalog and template layers are static legal text and rate tables.
- **Does it introduce or move secrets?** No. No new credential, endpoint or outbound call; the
  Karnataka e-stamp channel is explicitly out of scope, so nothing new leaves the process.
- **New outbound flows?** None. Rules and templates are classpath resources loaded at startup.
- **Redaction:** unchanged. The engine already logs only a rule id and an outcome type, never facts;
  nothing here adds a log statement over customer data.
- **Sandbox + dummy data only:** preserved. Worked `cases:` in the rule files use invented amounts;
  the template layers ship no real party data.

**Code and data touched**

- `backend/src/main/resources/rules/stamp-duty/KA/*.yaml`, `rules/stamp-paper/KA.yaml` (new).
- `backend/src/main/resources/documents/template/sets/{rental,commercial}/state-KA.patch.yaml` and
  `state_type-KA-*.patch.yaml` (new).
- `rules` module: `RuleSetLoader` (`SLAB_KEYS` + validation), `RuleSet.Slab`, `DutyEngine` (apply
  per-slab bounds), `DutyLine.Kind` if a new breakdown kind is warranted. Module-internal only; the
  `rules` public API (`StampDutyCalculator`, `DutyBasis`, `DutyOutcome`) is unchanged, so no module
  boundary moves and `ModularityTests` is unaffected.
- `documents` module: `TemplateCatalogSeeder.STATE_DISPLAY_NAMES` (one map entry).
- `frontend/src/content/termsOfService.ts`, `frontend/src/views/LandingPage.vue`.
- `docs/ROADMAP.md` (close one register row, correct another).

**Dependencies:** none added. No migration -- Karnataka introduces no schema change, and the catalog
seeder inserts its rows idempotently per `(state, type)` dimension on startup in `local`/`sandbox`.

**Operational:** Karnataka appears as a chargeable jurisdiction only where
`rules.stamp-duty.allow-unreviewed=true` (local/test). In production it stays draft-only until both
counsel review and an e-stamp procurement channel exist -- the customer-facing copy must say what is
actually true rather than implying fulfilment.
