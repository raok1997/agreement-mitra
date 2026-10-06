## Why

The home page is the first thing a paying Telangana or Karnataka customer will read at the first
release (`docs/ROADMAP.md` "First release", round 1), and today it is not fit for that:

- It says the same three things 3–5 times each: no login, stamp duty shown up front, and free to draft.
- It never says what the service costs.
- It sells "why we built this" instead of what we promise when something goes wrong.
- Four places say what is live, and they disagree. The FAQ says "Stamping and eSign are live for
  Telangana first", while the status board says both are "In integration".
- The 11-month FAQ answer states one national registration threshold as fact. The Telangana rule
  file says `requiredWhenTermMonthsOver: 0`, and that question is still with counsel
  (`tg-stamp-duty-counsel-review`).

## What Changes

- **Layout and de-duplication.** Each of the three recurring messages gets one owning section:
  - no login → the hero;
  - stamp duty in the price → the price card;
  - free to draft → the price card.

  The trust strip, which repeats all three, is removed. The page order becomes: hero → how it
  works → price → guarantees → what is live → FAQ → closing CTA.
- **Price card (new section `#price`).** "₹499 when your stamp duty is ₹100 or less" leads the card (not "all-in", to avoid drip pricing). It covers stamp duty up to ₹100, buying and
  attaching the stamp certificate, and Aadhaar eSign. Where the duty is higher, the customer sees
  the stamp amount and the exact total before paying, with no second bill. It does not say "plus
  only the difference", because Telangana offers only a ₹100 paper (`rules/stamp-paper/TG.yaml`). Drafting, previewing and downloading a draft are free. The
  figures come from one frontend module, and a test cross-checks them against the
  backend fee defaults and ToS §7.
- **Guarantees replace "Why bother building another one of these".** There are three cards, each
  restating one ToS promise with its qualifier and a link to `/terms`:
  - §8: we fix a wrong stamp certificate at our cost and refund ₹400;
  - §11: we re-send a failed or expired signing request free;
  - §14: a delay credit of ₹100 per working day beyond two working days, up to ₹400.

  The "Correct for your state, in your language" card is dropped.
- **Status board is the single statement of what is live.** A unit test refuses a `live` stamping row while that state's rule files lack a counsel review. Its rows move into a typed module
  (`src/content/releaseStatus.ts`), so flipping a row at launch is a one-line edit. The rows name
  stamping per state (Telangana, Karnataka). The hero badge, the "Which cities" FAQ answer, the
  how-it-works steps, the closing CTA and the footer stop asserting availability.
- **FAQ.**
  - The 11-month answer is rewritten so it is not tied to any state's threshold. It also points to
    the registration notice the product shows before payment.
  - The stamped-vs-registered answer drops "the twelve-month rule".
  - "What does stamp duty cost?" becomes "What does it cost?".
  - A new question is added: "What happens if something goes wrong?".
  - The FAQPage JSON-LD in `index.html` is kept identical to the visible FAQ.
- **`LegalDisclaimer` rewritten** to lead with what we stand behind ("we wrote the template and we stand behind it"; gated on counsel review before release): the wording is ours, and the
  facts and choices are the customer's. It keeps a short "not a law firm / not legal advice" line.
  The on-preview screen notice (`documents.footer.screen-notice` in `application.yml`) gets the
  same wording, so the service keeps one disclaimer wording.
- **Copy guardrails.** No "100% legally valid" claim, and no "reviewed by counsel" claim. That
  claim waits until `template-counsel-signoff-gate` records the review.
- The data-testids that `LandingPage.test.ts` and `App.test.ts` depend on stay: `nav-start`,
  `hero-start`, `cta-start`, `faq-q`, `faq-a`, the `#status` section, and the
  `legal-disclaimer*` ids.

## Capabilities

### New Capabilities
- `landing-page`: the public home page's content contract. It covers:
  - one owning section per recurring message;
  - a visible price consistent with the published fee;
  - guarantees that mirror the terms;
  - the status board as the only statement of what is live;
  - a FAQ consistent with its structured-data copy and with the rules engine;
  - forbidden claims;
  - the legal-disclaimer wording.

### Modified Capabilities
- None. No existing spec covers the home page or `LegalDisclaimer`. `jurisdiction-eligibility`
  governs the in-app disclosure and the terms, and neither changes here.

## Impact

- **Frontend:**
  - `src/views/LandingPage.vue`
  - `src/views/LandingPage.test.ts`
  - `src/components/LegalDisclaimer.vue`
  - `src/components/LegalDisclaimer.test.ts`
  - `index.html` (FAQPage JSON-LD only)
  - new `src/content/releaseStatus.ts`, `src/content/promises.ts` (price, guarantee figures, support hours, contact address) and `src/test-support/backendConfig.ts`, with tests
- **Backend:** one config string, `documents.footer.screen-notice` in `application.yml`. There is
  no code change. `DocumentProjectionServiceTest` uses its own constant.
- **Docs:** `docs/LEGAL-POSTURE.md`, the "Honest marketing" paragraph and the disclaimer paragraph;
  `docs/GO-TO-MARKET-HYDERABAD.md`, the 11-month bullet that asserts the Telangana threshold.
- **Signing-status FSM:** untouched. No transition changes.
- **PII / security:** none. This is static marketing copy and one config string. It adds no
  Aadhaar/OTP/VID/PII flow and no secret, and it makes no network call. The repo stays sandbox +
  dummy data only.

## Out of scope

- **ToS text (§2, §5, §7, §8):** sequenced to `terms-release-revision`, round 2. The landing page
  links to the terms rather than editing them. ToS §2 already defers to "the status board on our
  home page", and that stays true.
- **"Reviewed by Indian counsel" copy:** gated on `template-counsel-signoff-gate`.
- **The Telangana registration threshold itself** (rule file vs national line): with counsel in
  `tg-stamp-duty-counsel-review`.
- **SEO `<meta>` descriptions and `Organization` JSON-LD in `index.html`:** these describe the
  product, not live status, so they are unchanged.
- **Per-clause anchors on `/terms`:** guarantee links go to `/terms` and name the section in text.
