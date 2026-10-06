## ADDED Requirements

### Requirement: Each recurring message has one owning section
The home page SHALL state each recurring selling point in exactly one section outside the FAQ:
- no login to begin, in the hero;
- stamp duty included in the price, in the price card;
- free to draft, in the price card.

A FAQ answer MAY address the same subject when a visitor's question asks about it.

Two definitions apply to every text check in this capability:
- **Phrase matching** is case-insensitive. Each phrase is escaped and wrapped in `(?<![A-Za-z0-9])…(?![A-Za-z0-9])`. A phrase marked *prefix* keeps only the leading guard. Figures such as "₹499" or "100%" are plain substring checks.
- **Page text outside a set of nodes** is the page's text nodes, joined with spaces, after every listed node is removed. The nav, the header and the footer are part of the page.

#### Scenario: No login is stated once outside the FAQ
- **GIVEN** the home page is rendered
- **WHEN** the page text outside `#faq` and the hero, and the hero's own text, are searched for "no login", "no account", "without an account" and "no OTP"
- **THEN** the page text outside `#faq` and the hero has no match
- **AND** the hero has at least one match

#### Scenario: Free to draft is stated once outside the FAQ
- **GIVEN** the home page is rendered
- **WHEN** the page text outside `#faq` and `#price`, and the text of `#price`, are searched for "free"
- **THEN** the page text outside `#faq` and `#price` has no match
- **AND** `#price` has at least one match

#### Scenario: Stamp duty in the price is stated once outside the FAQ
- **GIVEN** the home page is rendered
- **WHEN** the page text outside `#faq` and `#price` is searched for "includ" (prefix), and `#price` is searched for "stamp duty" and "includ" (prefix)
- **THEN** the page text outside `#faq` and `#price` has no match
- **AND** `#price` matches both
- **AND** "all-in" appears nowhere on the page

#### Scenario: The trust strip is gone
- **GIVEN** the home page is rendered
- **WHEN** the sections in `main` are listed in order
- **THEN** there are exactly seven: hero, `#how`, `#price`, `#guarantees`, `#status`, `#faq` and the closing CTA

### Requirement: The price card states the published fee
The home page SHALL show a price card (`#price`) whose headline leads with its condition, as in "₹499 when your stamp duty is ₹100 or less". The card states:
- a total of ₹499 that includes stamp duty up to ₹100;
- that where the duty is higher, the stamp amount and the exact total are shown before payment, and there is no second bill;
- that drafting, previewing and downloading a draft cost nothing;
- that the price applies where stamping and eSign are offered, pointing to the status board without itself stating what is live.

The rupee figures SHALL come from a single frontend constant, which SHALL equal the backend's default fee configuration and the figures in ToS §7. A price statement is not an availability claim.

#### Scenario: The card shows the price, the overflow rule and its scope
- **GIVEN** the home page is rendered
- **WHEN** the `#price` section is read
- **THEN** it contains all of the following:
  - "₹499" and the included "₹100";
  - "exact total before you pay";
  - a statement that drafting is free;
  - a link to `#status`

#### Scenario: The constant matches the backend default fee
- **GIVEN** `application.yml` declares `payment.fee.base-minor-units` and `payment.fee.included-stamp-value-minor-units` defaults
- **WHEN** the pricing constant is compared with those defaults divided by 100
- **THEN** both the total and the included duty are equal

#### Scenario: The constant matches the published terms
- **GIVEN** the ToS clause whose heading starts "7. " in `termsOfService.ts`
- **WHEN** its body is searched for "INR <total> where the stamp duty on your agreement is INR <included duty> or less", built from the constant
- **THEN** it is found

### Requirement: Guarantees mirror the terms of service
The home page SHALL present a `#guarantees` section in place of "Why bother building another one of these". It SHALL hold exactly three guarantees, each restating one ToS promise with all its qualifying conditions and linking to `/terms`:
- a wrong stamp certificate that is our fault is fixed at our cost, plus a ₹400 refund (§8);
- a failed or expired signing request is re-sent at no charge (§11);
- a delay of more than two working days through our fault earns ₹100 per further working day, up to ₹400 (§14).

The section SHALL NOT promise more than those clauses. Its rupee figures and day counts SHALL come from one frontend constant, and that constant SHALL be checked against the ToS clause bodies.

#### Scenario: Three guarantees, each linked to the terms
- **GIVEN** the home page is rendered
- **WHEN** the `#guarantees` section is read
- **THEN** it contains three guarantee cards, each with a link `href="/terms"`:
  - a certificate card naming ₹400;
  - a signing card naming a re-send "at no charge";
  - a delay card naming "₹100 for each further working day, up to ₹400".

#### Scenario: Each guarantee keeps its qualifiers
- **GIVEN** the `#guarantees` section
- **WHEN** each card's text is read
- **THEN** the cards carry these qualifiers:
  - certificate card: the mistake must be ours, and the refund is reduced by any discount;
  - signing card: repeated signer-side failures may cost ₹100;
  - delay card: it names the one-working-day stamping target and that out-of-hours orders count from the next working day; it applies only beyond two working days late through our fault; it excludes waiting on the customer's details, an unreachable signer, portal or eSign outages and public holidays; and it is reduced by any discount.

#### Scenario: Guarantee figures match the terms
- **GIVEN** the guarantees constant
- **WHEN** the ToS clauses headed "8. ", "11. " and "14. " are searched for clause-local phrases built from the constant: "refund you INR <n>" (§8), "ask for INR <n> before starting it again" (§11), and for §14 "within <word> working day of payment", "more than <word> working days late", "INR <n> for each further working day, up to INR <cap>" and the support hours
- **THEN** every phrase is found in its clause

#### Scenario: The old pillar copy is gone
- **GIVEN** the home page is rendered
- **WHEN** its text is searched for "Why bother building another one of these" and "in your language"
- **THEN** neither is found

### Requirement: The status board is the only statement of what is live
The home page SHALL state what is live, in integration or planned only in the `#status` board. The board's rows, including stamping per state, SHALL come from a single typed module, so that a row's state changes the page with one edit to that module.

No other part of the home page SHALL assert or deny the availability of a feature or a state. That covers the nav, hero, steps, price, guarantees, FAQ, closing CTA and footer. Two things are not claims:
- a reference to the board by name (e.g. "status");
- the drafting scope stated in the FAQ answer to "Which cities do you serve?".

A stamping row SHALL NOT be `live` while any stamp-duty rule file for its state has `counselReview: null`.

#### Scenario: Board rows render from the module
- **GIVEN** the release-status module lists rows with states `live`, `soon` or `planned`
- **WHEN** the home page is rendered
- **THEN** `#status` shows one row per module entry, in order, each with the label for its state ("Live now", "In integration", "Planned")
- **AND** stamping rows exist for Telangana and for Karnataka

#### Scenario: Flipping a row changes only that row
- **GIVEN** the page is rendered with the module replaced by a copy in which one row is flipped from `soon` to `live`
- **WHEN** the board is read
- **THEN** that row shows "Live now", and every other row still shows its own module label; because the other landing tests derive their expectations from the module, none of them pins a row's state

#### Scenario: A stamping row cannot be live without counsel review
- **GIVEN** the release-status module and the rule files under `backend/src/main/resources/rules/stamp-duty/`
- **WHEN** a row with a stamping state is `live`
- **THEN** every rule file for that state has a non-null `counselReview`

#### Scenario: No liveness claim outside the board
- **GIVEN** the home page is rendered
- **WHEN** the text outside `#status` is searched, ignoring case, for "live now", "is live", "are live", "in integration", "early access", "coming soon", "starting in" and "in your city"
- **THEN** none is found

#### Scenario: The board's anchor is stable
- **GIVEN** ToS §2 points readers to "the status board on our home page"
- **WHEN** the home page is rendered
- **THEN** the board is the element with `id="status"`, and the nav links to `#status`

### Requirement: The FAQ is consistent with the rules engine and with its structured data
The home page FAQ SHALL NOT state a registration threshold as applying in every state. It SHALL NOT use "the twelve-month rule" as a universal statement, and SHALL NOT answer the e-signature question with an unconditional "Yes". It SHALL include a question on what the service costs and a question on what happens if something goes wrong. The visible FAQ SHALL equal the FAQPage JSON-LD in `index.html`, question for question and answer for answer, in the same order.

#### Scenario: The 11-month answer is state-relative
- **GIVEN** the FAQ question "Why are most rental agreements in India for 11 months?"
- **WHEN** its answer is read
- **THEN** it contains "term longer than a year", "may require registration for shorter leases" and "Before you pay, we show whether your agreement may need registering"
- **AND** none of "does not need to be registered", "simply does not need", "twelve-month rule" and "below that threshold" appears anywhere in the FAQ

#### Scenario: Cost and something-goes-wrong questions exist
- **GIVEN** the FAQ
- **WHEN** its questions are listed
- **THEN** "What does it cost?" and "What happens if something goes wrong?" are present, and "What does stamp duty cost?" is not

#### Scenario: The e-signature answer is not an unconditional yes
- **GIVEN** the FAQ question "Is an Aadhaar OTP signature legally valid?"
- **WHEN** its answer is read
- **THEN** it does not begin with "Yes"

#### Scenario: Visible FAQ equals the JSON-LD
- **GIVEN** the FAQ rendered under `[data-testid="faq-q"]` and `[data-testid="faq-a"]`
- **WHEN** both are compared with the `FAQPage` node in `index.html`, whitespace collapsed
- **THEN** the questions and answers are equal in content and order

### Requirement: The home page makes no unearned legal claims
The home page and the legal disclaimer SHALL NOT claim the agreement is "100% legally valid", legally guaranteed, or court- or government-approved. They SHALL NOT claim the template or agreement was reviewed by a lawyer or counsel until `template-counsel-signoff-gate` records that review. Neither SHALL render copy through `v-html`.

#### Scenario: Forbidden phrases are absent
- **GIVEN** the home page and `LegalDisclaimer` are rendered
- **WHEN** their text is searched, ignoring case, for any of the following:
  - "100%", "legally guaranteed", "guaranteed valid", "legally valid agreement";
  - "court-approved", "government-approved";
  - "reviewed by counsel", "reviewed by a lawyer", "lawyer-reviewed".
- **THEN** none is found
- **AND** neither component's source contains `v-html`

### Requirement: The legal disclaimer leads with what the service stands behind
`LegalDisclaimer` SHALL state, in this order:
1. that the agreement's wording is AgreementMitra's;
2. that the facts entered and the choices made are the customer's to check before signing;
3. a short line that AgreementMitra is not a law firm, that no lawyer reviews the agreement for the customer's circumstances, and that this is not legal advice, linking to `/terms`.

The on-preview screen notice (the `documents.footer.screen-notice` default) SHALL carry the same wording as plain text, with the link rendered as "terms of service at agreementmitra.com/terms".

#### Scenario: Disclaimer order and content
- **GIVEN** `LegalDisclaimer` is mounted in either variant
- **WHEN** its text is read
- **THEN** the sentences appear in this order: the wording-is-ours sentence, the facts-and-choices sentence, then "not a law firm", "no lawyer reviews" and "not legal advice"
- **AND** the source of `LegalDisclaimer.vue` contains no `v-html`
- **AND** the `legal-disclaimer-terms-link` points to `/terms`, and the root stays `print:hidden`

#### Scenario: The preview notice matches
- **GIVEN** the `documents.footer.screen-notice` default in `application.yml`, with its surrounding quotes removed
- **WHEN** it is compared with the disclaimer's text after two changes: the link text is replaced by "terms of service at agreementmitra.com/terms", and whitespace is collapsed on both sides
- **THEN** the two strings are equal

### Requirement: Landing entry points and test hooks are preserved
The home page SHALL keep:
- the `nav-start`, `hero-start` and `cta-start` buttons, each emitting `start`;
- the `faq-q` and `faq-a` test ids;
- the `#status` list structure: a label span, then a badge span.

Every `mailto:` link, and the support address in FAQ copy, SHALL use the single support-address constant.

#### Scenario: All three CTAs start the builder
- **GIVEN** the home page is rendered
- **WHEN** `nav-start`, `hero-start` and `cta-start` are each clicked
- **THEN** `start` is emitted three times

#### Scenario: Every contact address is the constant
- **GIVEN** the home page is rendered
- **WHEN** its `mailto:` links and FAQ answers are read
- **THEN** every `mailto:` href is `mailto:` + `CONTACT_EMAIL`, and the "something goes wrong" answer contains `CONTACT_EMAIL`

#### Scenario: Route from `/` still reaches the picker
- **GIVEN** the app is mounted at `/`
- **WHEN** `hero-start` is clicked
- **THEN** the route becomes `/start` and the template picker renders, with no backend list call made from the landing page
