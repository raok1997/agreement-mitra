## Why

The first paid release (paid Telangana + Karnataka residential orders, `docs/ROADMAP.md`
"First release", item 1) cannot ship terms that describe a different product. Today several
parts of the published terms are untrue:

- **§5** says "Today that is Telangana", but the home-page status board shows Telangana stamping
  as "In integration". The terms and the board are two statements of what is live, and they
  already disagree.
- **§7** prices on the legal *duty* and says the duty calculation is "still being built". The
  shipped rule prices the **chosen stamp value** (`payment-processing`: INR 499 + max(0, chosen
  stamp value − INR 100)). A customer may proceed with a value below the duty after an audited
  warning (`stamp-selection`).
- **§8** says staff buy "an e-stamp certificate through the ordinary SHCIL channel" for every
  agreement. That is true only for Karnataka. **Ops confirmed on 2026-10-06** that Karnataka
  stamps are bought as SHCIL e-stamp certificates, and Telangana stamps as physical
  non-judicial stamp paper from a licensed stamp vendor. SHCIL does not list Telangana
  (`rules/stamp-paper/TG.yaml`).
- **§5** still mentions "our national template", which the picker no longer offers.
- The word "certificate" (§2, §5, §8, §11, §14 and the privacy policy) and "bought on a
  government portal" (§14) assume the e-stamp medium. That wording also reaches the new
  `/refunds` page, which renders §11 verbatim.
- The privacy policy names one recipient for stamp data, "the issuing authority … its portal".
  For Telangana the actual recipient is a licensed stamp vendor, who is a private third party.

## What Changes

- **§2 and §5 stop restating what is live.**
  - §5 names the drafting scope (Telangana and Karnataka, residential) and the two conditions
    for stamping.
  - The no-payment promise is keyed to the server's eligibility decision, which is derived from
    the duty rules.
  - The "Draft and download only" marking is described as how that decision is normally shown
    (it fails open). The status board is described as a summary.
  - The national-template sentence is dropped.
- **§7 is restated on the chosen stamp value.** The total is INR 499 when the stamp value is
  INR 100 or less, plus the excess above that. We work out the legal duty and show it, and
  recommend a stamp of that value where the state's stamps allow it. A customer can proceed below
  the duty only after the under-stamping warning. The "still building" paragraph is dropped.
- **§8 names the channel per state:**
  - Karnataka: an e-stamp certificate bought through the Stock Holding Corporation of India
    (SHCIL).
  - Telangana: physical non-judicial stamp paper bought from a licensed stamp vendor.

  Every "certificate" and "duty" sentence that stamp-value pricing makes false is corrected
  ("costs you only the stamp itself", "the replacement stamp is yours to pay for"). The counsel
  gap gains two questions: whether stamp paper attached to an electronically signed agreement
  stamps it validly, and what happens to the paper original.
- **Stamp vocabulary.** "Certificate" means only the Karnataka e-stamp certificate. Elsewhere the
  text says "stamp".
  - §11 (and therefore `/refunds`) changes.
  - §14 names its real dependencies in place of "a government portal". The clock stops only when
    SHCIL or the signing provider is down, or when *no* licensed vendor can supply the stamp.
    One shut vendor does not stop it.
- **§8 accounts for the Telangana paper original (user, 2026-10-06).** It is kept for one year and
  then shredded, and sent on request through support. Shipping as a feature is v1.1, on the new
  register row `tg-stamp-paper-shipping`.
- **The privacy policy names both stamp recipients by role, and says what each receives and how
  each keeps it:**
  - the "Karnataka e-stamp issuer", which keeps its own record, looked up by certificate number;
  - the "Telangana licensed stamp vendor", which keeps its own register under the state's rules.

  It also discloses the one-year paper original, and a courier that is used only on request. The
  field lists are non-exhaustive because the forms are not confirmed. The policy keeps its
  "by role, not by company" rule; only the terms name SHCIL. Its `lastUpdated` moves.
- **The home page follows §7 and §8.**
  - `#price` and FAQ 3 move to the stamp-value basis. `#price` keeps "stamp duty" (the
    owning-section rule) and "never a second bill".
  - The certificate guarantee becomes a stamp guarantee.
  - `PRICE.includedDutyRupees` is renamed `includedStampRupees`.
- **Same-fact fixes.**
  - The `releaseStatus.ts` launch checklist now says that §2/§5 defer to the board.
  - The `KA.yaml` "no ops procurement channel" note now records the SHCIL channel.
  - The ROADMAP flow diagram and the `manual-estamp-upload` prose, and the
    `GO-TO-MARKET-HYDERABAD.md` claim that Telangana has e-stamping, now state the per-state
    channel.
- **Register.**
  - `tos-below-duty-stamp-choice` is deleted, because this change does what it asks. Its
    counsel ask moves into the release's counsel-review checkbox, by name.
  - `stamp-paper-plus-challan-plan` loses its answered "what do staff buy for TG" question. It
    gains one sentence: the SHCIL-named intake fields must be generalised for stamp paper.
  - `tg-stamp-duty-counsel-review` gains the stamp-paper validity question and asks whether the
    paper original must accompany the agreement.
  - A new row, `tg-stamp-paper-shipping`, covers v1.1 shipping and the one-year shred schedule.
    Net register effect: 0.

Out of scope:
- The `estamp-intake`/`document-stamping` specs and `StaffConsole.vue` field names, which are an
  intake-behaviour change.
- `docs/COUNSEL-BRIEF.md`, which is with counsel now. The user sends a correction.
- Any pricing, eligibility or stamping code.

## Capabilities

### New Capabilities
- (none)

### Modified Capabilities
- `jurisdiction-eligibility`: in "The published terms state which jurisdictions can be stamped",
  the terms state the drafting scope and defer per-agreement stampability to the in-product
  marking, with the status board as a summary. They keep no list of their own.
- `landing-page`:
  - "The price card states the published fee" moves to the stamp-value basis and its §7 pin.
  - "Guarantees mirror the terms of service" speaks of a stamp, not a certificate.
  - A new requirement keeps the home page's stamp wording medium-neutral.
- `legal-policy-pages`: a new requirement says the policy texts state the stamp channel per
  state, and use "certificate" only for the e-stamp certificate.

## Impact

- Frontend content and tests:
  - `src/content/termsOfService.ts`, `privacyPolicy.ts`, `promises.ts`, `releaseStatus.ts`
    (comment)
  - `src/views/LandingPage.vue`, `StampQuoteStep.vue` (a coupling comment only), `index.html`
  - the tests that pin the old wording
- Generated documents: `docs/TERMS-OF-SERVICE.md`, `docs/PRIVACY-POLICY.md`.
- Docs: `docs/ROADMAP.md`, `docs/GO-TO-MARKET-HYDERABAD.md`.
- Rules: a comment in `backend/.../rules/stamp-paper/KA.yaml`. Comments are outside the rule
  `contentHash`, which hashes computational content only (`RuleRef.java:6`).
- No backend logic, API, schema or signing-state-machine change. The signing FSM is untouched.

**PII / security checklist.**
- No Aadhaar number, OTP, VID or secret is introduced, moved or logged. This change edits
  published text only.
- The privacy policy **newly discloses** an existing flow it described inaccurately. For
  Telangana, staff give a licensed stamp vendor the parties' names and the nature of the
  document, and the vendor records them in its own register under the state's rules. This is
  not a new flow: staff have to supply these details to buy any stamp. Also newly disclosed: the
  one-year Telangana paper original, and a courier that receives a delivery name and address only
  when the customer asks for the original. The change corrects the
  recipient and its retention.
- Sandbox + dummy data only is preserved.
