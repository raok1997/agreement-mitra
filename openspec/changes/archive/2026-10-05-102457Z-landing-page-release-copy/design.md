## Context

`frontend/src/views/LandingPage.vue` is self-contained, with no child components and no API
calls. Its copy lives in arrays in `<script setup>` (`steps`, `pillars`, `status`, `faqs`). The FAQ
is mirrored as static schema.org `FAQPage` JSON-LD in `frontend/index.html`, and
`LandingPage.test.ts` holds the two equal.

The new copy restates facts that already live elsewhere:

- **Price.** `application.yml:251-252` holds `payment.fee.base-minor-units: 49900` and
  `included-stamp-value-minor-units: 10000`. They are env-overridable, and `PaymentPricing`
  implements them on the *chosen stamp value*. ToS §7 (`src/content/termsOfService.ts`) states
  them in prose.
- **What a state actually offers.**
  - `rules/stamp-paper/TG.yaml:13-20` offers Telangana customers **only a single ₹100 stamp paper**.
    Where the duty is higher, the customer acknowledges an audited under-stamping warning. SHCIL
    does not list Telangana, so it has no e-stamp medium.
  - Karnataka pre-selects the exact duty.

  So "₹499 plus the excess" is what a KA customer pays, and never what a TG customer pays. This
  is the gap the register row `tos-below-duty-stamp-choice` records against §7.
- **Guarantees.**
  - §8: ₹400 refund for a wrong certificate through our fault.
  - §11: free re-send of a failed or expired signing request; up to ₹100 for repeated
    signer-side failure; a discount rule covering both §8's ₹400 and §14's credit.
  - §14: a target of stamping within one working day of payment, with orders paid out of hours
    counted from the next working day. ₹100 per working day beyond two days late, capped at ₹400.
    Excludes delays caused by the customer's details, an unreachable signer, or an outage. The
    clock stops on public holidays. Support hours are 9am to 5pm IST.
- **Registration.** `rules/stamp-duty/TG/*.yaml` sets `requiredWhenTermMonthsOver: 0`, marked
  UNVERIFIED and with counsel. Before payment, `StampQuoteStep.vue:234` shows
  `registration-notice` whenever the engine says so.
- **Picker.** `TemplatePicker.vue:19-21` hides the national (`IN`) templates. Customers can draft
  for Telangana and Karnataka only.
- **Disclaimer.** `LegalDisclaimer.vue` appears on capture, contact and payment confirmation, and
  `documents.footer.screen-notice` (`application.yml:335`) shows the same wording under the
  preview. Both say "no lawyer reviews it for your circumstances" today. The backend HTML-escapes
  the screen notice.
- **Liveness elsewhere.** ToS §2 says stamping and eSign "are still being integrated". ToS §5
  says Telangana is the stampable state "today", which is a statement of eligibility, and it still
  mentions a national template.

## Goals / Non-Goals

**Goals:**
- Give each recurring message one owner.
- Show the price and the guarantees on the page.
- Make the status board the only statement of what is live, flippable in one module edit.
- Correct the FAQ.
- Rewrite the disclaimer.
- Pin every figure this CR repeats from the ToS or the backend to its source with a test.

**Non-Goals:**
- Editing the ToS.
- Fetching price or status from the backend.
- Changing rule data or the TG offer policy.
- Adding `/terms` anchors.
- Changing the SEO `<meta>` descriptions.
- Visual redesign beyond reordering and the two new sections.

## Decisions

### D1. Page structure and message ownership

The page runs: nav → hero (`<section data-testid="hero">`) → `#how` → `#price` → `#guarantees` →
`#status` → `#faq` → closing CTA (`<section data-testid="closing-cta">`) → footer. The test ids
sit on the `<section>` elements themselves, and `main` holds exactly seven sections.

| Message | Owner | Removed from |
|---|---|---|
| No login to begin | hero sub-line | step 1, pillar 2, trust strip |
| Stamp duty included in the price | `#price` | hero sub-line, step 3, pillar 1, trust strip |
| Free to draft | `#price` | hero small print, nav button, step 2, closing CTA |

All page copy is written out here, so apply does not improvise:

- **Nav links:** How it works · Price · Guarantees · Status · FAQ.
- **Nav button** (`nav-start`): "Build my agreement".
- **Hero badge:** removed.
- **Hero H1:** unchanged, "Rental agreements, with nothing hidden until checkout."
- **Hero sub-line:** "Build a proper Indian rental agreement in a few minutes and read the real
  document as it is written. No login to begin."
- **Hero small print:** removed.
- **`#how`:** heading "How it works", intro "Four steps, and you can read the document at every
  one of them."
  1. *Answer a short form*: "Parties, property, rent, deposit and dates."
  2. *Watch the agreement build itself*: "The actual document updates as you type, so you know
     exactly what you are getting."
  3. *Pay, and we stamp it*: "We buy the stamp certificate and attach it to your agreement."
  4. *Sign with Aadhaar OTP*: "Both parties sign from their phones. The signed PDF and its audit
     trail come to your inbox."

  These describe how the product works. Which states and steps can be bought yet is the board's
  job, and the D3 header lists these step titles as a surface to re-read at a flip.
- **Trust strip:** removed.
- **Closing CTA:** heading "Draft one and see for yourself." Body: "It takes a few minutes, and
  you read the real document before you decide anything." Button `cta-start`. Then "Questions?
  Write to {CONTACT_EMAIL}."
- **Footer:** "© 2026 AgreementMitra. Online rental agreements for India." Links unchanged.
- **`#faq` heading:** unchanged, "Questions people actually ask".

### D2. One promises module, each figure cross-checked by test

`src/content/promises.ts`:

```ts
export const PRICE = { totalRupees: 499, includedDutyRupees: 100 } as const;
export const GUARANTEES = {
  certificateRefundRupees: 400,        // §8
  signerRetryChargeRupees: 100,        // §11
  stampTargetWorkingDaysText: "one",   // §14 target
  delayGraceWorkingDaysText: "two",    // §14 (ToS spells the number)
  delayCreditPerDayRupees: 100,        // §14
  delayCreditCapRupees: 400,           // §14
} as const;
export const SUPPORT_HOURS = "9am to 5pm IST on working days"; // §14
export const CONTACT_EMAIL = "support@agreementmitra.com";
export const formatRupees = (n: number): string => `₹${n}`;
```

- Day counts are stored as the words the ToS uses, so the page renders "two" from the constant,
  and the test checks the same word.
- `formatRupees` is a template literal, not `Intl`, which would produce "₹499.00".
- `LandingPage.vue` hand-types none of these values.

**Config helper.** `src/test-support/backendConfig.ts` has two parts:

- `parseYamlDefault(yamlText, leafKey)` is pure and is the unit-test target. It works in a fixed
  order:
  1. Match lines `^\s+<leafKey>:\s+(.*)$`, and throw unless exactly one line matches.
  2. Strip the YAML quotes around the whole value.
  3. Require `${` … `}` and strip them.
  4. Split at the first `:` and return the remainder.
- `readYamlDefault(leafKey)` reads `../backend/src/main/resources/application.yml` from
  `process.cwd()` and delegates to `parseYamlDefault`.

The leaf names `base-minor-units`, `included-stamp-value-minor-units` and `screen-notice` each
occur once today. Fail-closed matching keeps it that way.

**`promises.test.ts`.** Clauses are selected with `TERMS_CLAUSES.find(c => c.heading.startsWith("N. "))`.
The test asserts each clause is found, then joins `body` with spaces. It checks:

- `PRICE × 100` equals both fee defaults.
- Clause-local phrases built from the constants:
  - §7: `INR ${total} where the stamp duty on your agreement is INR ${included} or less`.
  - §8: `refund you INR ${certificateRefundRupees}`.
  - §11: `ask for INR ${signerRetryChargeRupees} before starting it again`.
  - §14: `within ${stampTargetWorkingDaysText} working day of payment`,
    `more than ${delayGraceWorkingDaysText} working days late`,
    `INR ${perDay} for each further working day, up to INR ${cap}`, and `SUPPORT_HOURS`.

When `terms-release-revision` rewords any of these, the build fails until the home page follows.

*Rejected alternatives:* a public fee endpoint (the page must render with no backend, and a prod
env typo would change the advertised price) and hand-typed figures. The test reads defaults only;
a prod env override is out of its reach, which is acceptable because the price is a ToS
commitment. `deploy/Dockerfile.web` runs `build:only`, which skips tests, so the image does not
depend on `../backend`. CR-7 CI must check out the whole repo.

**Price card** (`#price`, heading "What it costs"):

- Headline: "₹499 when your stamp duty is ₹100 or less". This is decision 1: the condition leads,
  and "all-in" is never used.
- Included: stamp duty up to ₹100 · buying and attaching the stamp certificate · Aadhaar eSign
- "Where the duty is higher, you see the stamp amount and the exact total before you pay. There is
  never a second bill."
- "Drafting, previewing and downloading a draft are free."
- "Available where we stamp and eSign. See [Status](#status)."

The overflow line deliberately does **not** say "₹499 plus only the difference":

- That holds in Karnataka, which pre-selects the exact duty.
- It never holds in Telangana, where only a ₹100 paper is offered.

Both states do show the amount and the total before payment, and neither bills twice (§7: "we
will not take payment and then come back to you for more"). A sentence appended to
`tos-below-duty-stamp-choice` records that the card states the overflow this way on purpose, and
that it must follow §7 when §7 is reworded.

### D3. Status board in `src/content/releaseStatus.ts`

```ts
export type ReleaseState = "live" | "soon" | "planned";
export const RELEASE_STATE_LABEL: Record<ReleaseState, string> = { live: "Live now", soon: "In integration", planned: "Planned" };
export interface ReleaseRow { label: string; state: ReleaseState; stampingState?: "TG" | "KA" }
export const RELEASE_STATUS: readonly ReleaseRow[] = [ … ];
```

| Row | State | `stampingState` |
|---|---|---|
| Guided agreement builder (Telangana, Karnataka) | live | |
| Live document preview | live | |
| Download a draft PDF | live | |
| Save and resume with Google | live | |
| Stamping: Telangana | soon | TG |
| Stamping: Karnataka | soon | KA |
| Aadhaar OTP eSign | soon | |
| Telugu and Hindi agreements | planned | |

**Board copy:** the heading "What is live today" is unchanged. The intro reads: "Each row says
whether it is live now, in integration or planned. We update it the day that changes." The
liveness blocklist does not apply inside `#status`. The intro contains none of the ownership
phrases ("free", "no login" and the like).

**Telangana stamping is `soon` today.** That matches ToS §2 and the stubbed rails. ToS §5's
"Today that is Telangana" describes eligibility, and the round-2 `terms-release-revision` line
gets a sentence asking for it to agree with the board. The same sentence covers §5's stale
national-template mention.

**The module header is the launch checklist.**

- This module is the only home-page statement of what is live, and a flip is one `state:` edit.
- Before flipping a stamping or eSign row to `live`:
  - the production rail is non-stub and has been observed live once (ROADMAP "Ops / config");
  - ToS §2/§5 agree with the board;
  - `#price` and FAQ 3 still match §7 and that state's `offer` policy in `rules/stamp-paper/<ST>.yaml`.
- The automated check below enforces counsel review.
- Other surfaces that state availability or scope: ToS §2 and §5, the `jurisdiction-eligibility`
  in-app disclosure, FAQ "Which cities" (drafting scope), and the `#how` step titles.

**Tests:**

- **Board render** (`LandingPage.test.ts`). Rows appear in module order, each badge reads
  `RELEASE_STATE_LABEL[row.state]`, and rows with `stampingState` TG and KA both exist.
- **Flip** (`LandingPage.flip.test.ts`, kept separate because `vi.mock` is hoisted file-wide).
  `vi.mock("../content/releaseStatus", async (importOriginal) => …)` keeps the labels and flips
  the eSign row to `live`. That row reads "Live now", and every other row still reads its own
  module label.
- **Counsel gate** (`releaseStatus.test.ts`). For every row with `stampingState` and
  `state: "live"`, every `backend/src/main/resources/rules/stamp-duty/<ST>/*.yaml` must have a
  non-null `counselReview`. The test is a plain file read, the same pattern as the config helper.
  It mirrors the backend's paid-fulfilment gate, so the board cannot claim stamping is live where
  counsel has not signed off. Today every TG and KA rule is `counselReview: null`, so the test
  passes only while both rows stay off `live`, which is correct.
- **Liveness blocklist:** the spec's phrase list, checked on the text outside `#status`.

The old literal "In integration" test is retired, because it turned every flip into a test edit.
Its protection now lives in the counsel-gate test, the header checklist and review of a one-line
diff.

### D4. Guarantees section

`#guarantees` replaces `#why`. Heading: "If something goes wrong". Intro: "These come from our
terms of service, which are still a draft." Each of the three cards has a title, a body, a
qualifier and a link "Terms, section N" (`href="/terms"`). All figures come from `GUARANTEES`.

1. **We fix a wrong stamp certificate, and refund ₹400.**
   - Body: "If a certificate we buy for you is rejected or wrongly denominated because we got it
     wrong, we put it right at our cost and refund you ₹400."
   - Qualifier: "Only when the mistake is ours. Reduced by any discount you received."
2. **Signing failed? We re-send it at no charge.**
   - Body: "If a signing request fails or expires, we send a fresh one."
   - Qualifier: "If it keeps failing at the signer's end, we may ask ₹100 before restarting. Where
     we can't tell whose failure it was, we treat it as ours."
3. **Late through our fault? We pay you back.**
   - Body: "We aim to stamp your agreement within one working day of payment; an order paid outside
     business hours counts from the next working day. If we are more than two working days late
     through something that was ours, we refund ₹100 for each further working day, up to ₹400."
   - Qualifier: "Not while we wait on details from you or a signer we can't reach, or during a
     stamp-portal or eSign outage or a public holiday. Reduced by any discount you received."

Test 4.6 asserts these literal substrings:

- "Only when the mistake is ours"
- "Reduced by any discount" (cards 1 and 3)
- "₹100 before restarting"
- "within one working day of payment"
- "more than two working days late"
- "₹100 for each further working day, up to ₹400"
- "a signer we can't reach"
- "outage"
- "public holiday"

The copy says "at no charge" and never "free".

Whether card 1's "wrongly denominated … our mistake" reaches a Telangana deed that is
under-stamped on the ₹100-only offer goes to counsel, as a sentence appended to
`tg-stamp-duty-counsel-review`.

### D5. FAQ content

The visible copy interpolates from `promises.ts`. The JSON-LD holds the resolved text, with a
literal UTF-8 `₹`. The page footnote is shortened to "General information, not legal advice." It
appears on the page only; the FAQPage JSON-LD has no field for it.

1. **Why are most rental agreements in India for 11 months?** "Because the Registration Act, 1908
   requires a lease for a term longer than a year to be registered with the sub-registrar, and an
   11-month term stays under that national line. States can add their own rules, and some may
   require registration for shorter leases. Before you pay, we show whether your agreement may need
   registering for its state and term. Registration is a separate step from stamping and is not
   part of our service."
2. **Is an Aadhaar OTP signature legally valid?** "Aadhaar eSign is an electronic signature
   recognised under the Information Technology Act, 2000, issued through a licensed eSign Service
   Provider working with a Certifying Authority. The signed PDF carries a digital signature
   certificate and an audit trail recording who signed, when, and from where." The First Schedule
   question is appended to `rental-deed-lease-vs-licence`.
3. **What does it cost?** "₹499 in total when the stamp duty on your agreement is ₹100 or less.
   Where the duty is higher, you see the stamp amount and the exact total before you pay, and
   there is never a second bill. Stamp duty is set by your state from the rent, deposit and term,
   and we cannot change it."
4. **Does a stamped agreement mean it is registered?** "No. Stamping pays the state's duty on the
   document. Registration is a separate act of recording the lease with the sub-registrar. A
   stamped, eSigned agreement is not a registered lease."
5. **Do I need an account?** Unchanged.
6. **Which cities do you serve?** "You can draft agreements for Telangana and Karnataka,
   residential or commercial. Where we can also stamp and eSign is on the status board on our home
   page. If your state is not listed, write to us."
7. **What happens if something goes wrong?** "The guarantees on our home page cover the main
   things that can go wrong on our side: a wrong stamp certificate, a failed signature and a late
   delivery. For anything else, write to support@agreementmitra.com. We answer 9am to 5pm IST on
   working days."

FAQ 6 states drafting scope, which is a fixed product scope and not gated on launch. The spec
carves it out from the liveness rule by name.

### D6. Disclaimer wording

Decision 2 keeps "we stand behind it". The "no lawyer reviews it for your circumstances" fact
stays as well. It mirrors ToS §3 and is the counterweight that keeps the line a disclaimer.

> The wording of this agreement is ours: we wrote the template and we stand behind it. The facts
> you enter and the choices you make are yours, so check them before you sign. We are not a law
> firm, no lawyer reviews your agreement for your circumstances, and this is not legal advice; see
> the [terms of service](/terms).

The forbidden phrase "reviewed by a lawyer" does not match "no lawyer reviews".

**Release condition:** this wording does not ship to real customers until counsel has reviewed it
with ToS §16. Drafting is public, so it is visible from merge. Production stays founding-team-only
beta until the release. The condition is recorded in three places:

- the release section's Counsel line in `docs/ROADMAP.md`, which names all three carriers
  (`LegalDisclaimer.vue`, the screen-notice default, and any `DOCUMENT_FOOTER_SCREEN_NOTICE`
  override);
- a sub-item under `docs/COUNSEL-BRIEF.md` Question 6(d);
- `docs/LEGAL-POSTURE.md` item 2.

The `application.yml` default becomes this text with the link rendered as "terms of service at
agreementmitra.com/terms". The whole `${DOCUMENT_FOOTER_SCREEN_NOTICE:…}` value is
**double-quoted**, because the wording contains `: `, which breaks an unquoted YAML plain scalar.
Spring does not strip quotes inside a placeholder default, so the quotes go outside `${…}`.
`DocumentProjectionServiceTest` uses its own constant, and the consumer tests assert only "not a
law firm" plus the link, so none of them change.

### D7. Docs and register, same CR

- **`docs/LEGAL-POSTURE.md`:**
  - "Honest marketing": the price, guarantees, board-only liveness, forbidden claims and the
    counsel gate.
  - Item 2: the new wording, the release condition, and that setting `DOCUMENT_FOOTER_SCREEN_NOTICE`
    forks "one wording".
- **`docs/COUNSEL-BRIEF.md`:** a sub-item under Q6(d) asking for review of the disclaimer wording
  against §16.
- **`docs/GO-TO-MARKET-HYDERABAD.md`:** a `[FLAG]` on the 11-month bullet.
- **`docs/ROADMAP.md`:** append sentences only, and add no new rows:
  - `tos-below-duty-stamp-choice`: the price card's overflow wording.
  - `rental-deed-lease-vs-licence`: the IT Act First Schedule question.
  - `tg-stamp-duty-counsel-review`: guarantee 1 vs the ₹100-only offer.
  - The round-2 `terms-release-revision` line: §5 must agree with the board, and its
    national-template sentence is stale.
  - The release Counsel line: the disclaimer condition.
  - Delete the round-1 `landing-page-release-copy` entry.

## Risks / Trade-offs

- **[Risk] A price is shown for a service the board marks "In integration".** The card scopes
  itself to where we stamp and eSign and links to the board. The spec states that a price is not an
  availability claim.
- **[Risk] A frontend flip shows "Live now" before the backend can sell it.** The counsel-gate test
  enforces the counsel half automatically. The live rail and ToS agreement stay in the manual
  checklist, because checking them would need a backend call or a prod observation.
- **[Trade-off] The JSON-LD stays hand-copied.** The parity test turns drift into a red build. A
  future generator must use `JSON.stringify` and escape `<`.
- **[Trade-off] Ownership and blocklist tests match phrases**, with the guards defined in the
  spec. They guard against this regression, not against every synonym.

## Open Questions

None. Both were resolved by the user on 2026-10-05:
1. **Price headline:** it leads with the condition and never says "all-in".
2. **Disclaimer:** "we stand behind it" stays, gated on counsel review before release.
