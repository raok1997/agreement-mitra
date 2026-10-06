## Why

v1 is residential only (decided 2026-10-05): counsel is reviewing the TG and KA residential
templates and duty rules, and the commercial ones are off the critical path. Today the picker still
offers commercial templates and the home-page FAQ promises "residential or commercial", so a real
customer could draft, pay for and sign an agreement nobody has had reviewed. Hiding commercial in
the picker is UI-only, so the release also needs a settled answer on whether the server refuses a
commercial order — and a test that keeps that answer true.

## What Changes

- The template picker hides commercial templates, by the same mechanism that hides the national
  (`IN`) templates today. The two exclusions become one picker rule — the picker offers only the
  state-specific residential templates (an allow-list: any type other than residential is not offered).
- The home-page FAQ "Which cities do you serve?" drops "residential or commercial", in the visible
  FAQ and in the `FAQPage` JSON-LD in `index.html` (the two must stay equal).
- The capture form needs no copy change: it has no type selector and no commercial wording; its
  type comes from the picker. Recorded here so the roadmap item is closed rather than left open.
- **Server answer: no new refusal code.** Paid-fulfilment eligibility is enforced per duty rule,
  i.e. per `(state, usage)` (`StampQuoting.evaluate` → `isChargeable(rule)`), not per state. The
  shipped TG and KA commercial rules carry no counsel review, so once the release runs with
  unreviewed rules disallowed, a new commercial order is refused at finalise and checkout (and so
  never reaches e-stamp intake or eSign) with `409 JURISDICTION_UNSUPPORTED` — even when residential
  in the same state is reviewed and chargeable. A commercial agreement already paid under the beta
  flag would still pass fulfilment on its frozen quote, so the release clears any such order by hand
  (expected none). Tests pin both halves:
  - per-usage chargeability: a reviewed residential rule does not make commercial in the same state
    chargeable;
  - a tripwire: no shipped commercial rule carries a counsel review, so adding one is a visible,
    deliberate act that also has to un-hide commercial.
- **Release precondition made explicit**: prod beta runs `RULES_STAMP_DUTY_ALLOW_UNREVIEWED=true`
  (founding team only). That flag makes every rule chargeable, commercial included, so the release
  must run with it `false`. The release section of `docs/ROADMAP.md` gains that line in its ops
  checklist, with a check of the running value and of open/paid commercial orders; the
  `backend.env.example` comment says so too. (It is needed for the residential release anyway —
  `true` would also charge for unreviewed residential rules.)
- Not changed: commercial templates stay **published** server-side and draftable through the API
  (same as `IN`); an existing beta commercial agreement still opens from My Agreements.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `template-catalog`: adds the picker's offering rule — national and commercial templates are
  published but not offered.
- `jurisdiction-eligibility`: states that chargeability is decided per `(state, usage)` rule, and
  that commercial is withheld from paid fulfilment by shipping its rules unreviewed.
- `landing-page`: the FAQ "Which cities" answer offers residential only.

## Impact

- Frontend: `src/components/TemplatePicker.vue` (+ test), `src/views/LandingPage.vue`,
  `index.html` (JSON-LD), landing-page FAQ test.
- Backend: tests only — `rules` (per-usage chargeability; shipped-commercial-rules tripwire) and
  `signing` (commercial agreement refused at finalise/checkout with unreviewed rules disallowed).
  No production code, migration, endpoint or config default changes.
- Ops docs: `deploy/env/backend.env.example` (comment only).
- Docs: `docs/ROADMAP.md` — release ops checklist gains the `allow-unreviewed=false` precondition;
  item 0 is deleted on completion.
- Signing-status FSM: no transition touched.
- PII/security: none — no Aadhaar/OTP/VID/PII or secret flow is introduced or moved; the change is a
  picker filter, copy, and tests over existing refusals. Sandbox + dummy data only is preserved.
