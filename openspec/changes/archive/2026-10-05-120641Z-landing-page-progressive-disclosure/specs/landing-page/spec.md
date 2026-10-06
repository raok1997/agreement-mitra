## MODIFIED Requirements

### Requirement: Each recurring message has one owning section
The home page SHALL state each recurring selling point in exactly one section outside the FAQ:
- no login to begin, in the hero;
- stamp duty included in the price, in the price band;
- free to draft, in the price band.

A FAQ answer MAY address the same subject when a visitor's question asks about it.

Two definitions apply to every text check in this capability:
- **Phrase matching** is case-insensitive. Each phrase is escaped and wrapped in `(?<![A-Za-z0-9])…(?![A-Za-z0-9])`. A phrase marked *prefix* keeps only the leading guard. Figures such as "₹499" or "100%" are plain substring checks.
- **Page text outside a set of nodes** is the page's text nodes, joined with spaces, after every listed node is removed. The nav, the header and the footer are part of the page. Text inside a hidden tab panel is part of the page.

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

#### Scenario: The page has four sections in order
- **GIVEN** the home page is rendered
- **WHEN** the `section` elements that are direct children of `main` are listed in order
- **THEN** there are exactly four: the hero, `#price`, `#how` and the "Before you decide" panel (`data-testid="decide"`)
- **AND** no element with `data-testid="closing-cta"` or `data-testid="cta-start"` exists

### Requirement: The price card states the published fee
The home page SHALL show the price as a slim band (`#price`) directly under the hero, whose headline leads with its condition, as in "₹499 when your stamp duty is ₹100 or less". The band states:
- a total of ₹499 that includes stamp duty up to ₹100;
- that where the duty is higher, the stamp amount and the exact total are shown before payment, and there is no second bill;
- that drafting, previewing and downloading a draft cost nothing;
- that the price applies where stamping and eSign are offered, pointing to the status board without itself stating what is live.

The rupee figures SHALL come from a single frontend constant, which SHALL equal the backend's default fee configuration and the figures in ToS §7. A price statement is not an availability claim.

#### Scenario: The band shows the price, the overflow rule and its scope
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
The home page SHALL present the guarantees as the `#guarantees` tab panel of the "Before you decide" panel. It SHALL hold exactly three guarantees, each restating one ToS promise with all its qualifying conditions and linking to `/terms`:
- a wrong stamp certificate that is our fault is fixed at our cost, plus a ₹400 refund (§8);
- a failed or expired signing request is re-sent at no charge (§11);
- a delay of more than two working days through our fault earns ₹100 per further working day, up to ₹400 (§14).

Each guarantee SHALL show its title as a one-line headline that expands to its body, its qualifier and its "Terms, section N" link. The collapsed body, qualifier and link SHALL stay in the DOM.

The panel SHALL NOT promise more than those clauses. Its rupee figures and day counts SHALL come from one frontend constant, and that constant SHALL be checked against the ToS clause bodies.

#### Scenario: Three guarantees, each expandable and linked to the terms
- **GIVEN** the home page is rendered
- **WHEN** the `#guarantees` panel is read
- **THEN** it contains three `details` elements marked `data-testid="guarantee"`, each with its title in the `summary` and a link `href="/terms"` in its body:
  - a certificate guarantee naming ₹400;
  - a signing guarantee naming a re-send "at no charge";
  - a delay guarantee naming "₹100 for each further working day, up to ₹400".

#### Scenario: Each guarantee keeps its qualifiers
- **GIVEN** the `#guarantees` panel
- **WHEN** each guarantee's text, including its collapsed body, is read
- **THEN** the guarantees carry these qualifiers:
  - certificate: the mistake must be ours, and the refund is reduced by any discount;
  - signing: repeated signer-side failures may cost ₹100;
  - delay: it names the one-working-day stamping target and that out-of-hours orders count from the next working day; it applies only beyond two working days late through our fault; it excludes waiting on the customer's details, an unreachable signer, portal or eSign outages and public holidays; and it is reduced by any discount.

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

No other part of the home page SHALL assert or deny the availability of a feature or a state. That covers the nav, hero, steps, price band, guarantees, the panel heading and tab labels, FAQ and footer. Two things are not claims:
- a reference to the board by name (e.g. "status", "What's live");
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
- **THEN** the board is the tab panel with `id="status"`, and the nav links to `#status`

### Requirement: Landing entry points and test hooks are preserved
The home page SHALL keep:
- the `nav-start` and `hero-start` buttons, each emitting `start`;
- the `faq-q` and `faq-a` test ids;
- the `#status` list structure: a label span, then a badge span.

Every `mailto:` link, and the support address in FAQ copy, SHALL use the single support-address constant.

#### Scenario: Both CTAs start the builder
- **GIVEN** the home page is rendered
- **WHEN** `nav-start` and `hero-start` are each clicked
- **THEN** `start` is emitted twice

#### Scenario: Every contact address is the constant
- **GIVEN** the home page is rendered
- **WHEN** its `mailto:` links and FAQ answers are read
- **THEN** every `mailto:` href is `mailto:` + `CONTACT_EMAIL`, at least one such link exists in the footer, and the "something goes wrong" answer contains `CONTACT_EMAIL`

#### Scenario: Route from `/` still reaches the picker
- **GIVEN** the app is mounted at `/`
- **WHEN** `hero-start` is clicked
- **THEN** the route becomes `/start` and the template picker renders, with no backend list call made from the landing page

## ADDED Requirements

### Requirement: The first screen is a complete first impression
The hero SHALL be a single column holding, in order, the headline "Rental agreements, with nothing hidden until checkout.", the sub-line, and the `hero-start` button as its only control, with no image. The `#price` band SHALL be the section directly after the hero and `#how` the section after it. At a 1280×800 viewport, the price headline and all four step titles of `#how` SHALL be visible without scrolling. The visible section headings (`h2`) SHALL share one style, smaller than the `h1`.

#### Scenario: Hero holds the headline, sub-line and one button
- **GIVEN** the home page is rendered
- **WHEN** the hero is read
- **THEN** it has one `h1` with the unchanged headline, the sub-line containing "No login to begin", and exactly one `button` or `a` element, which is `hero-start`
- **AND** it has no `img`, `picture` or `video` element

#### Scenario: The price band and the steps follow the hero
- **GIVEN** the home page is rendered
- **WHEN** the sections of `main` are listed in order
- **THEN** the first is the hero, the second is `#price` and the third is `#how`

#### Scenario: The price and the steps are on the first desktop screen
- **GIVEN** the home page in a desktop browser at 1280×800, scrolled to the top
- **WHEN** the `#price` band and the `#how` step titles are located (manual-test gate)
- **THEN** the price headline and all four step titles are fully visible above 800px

#### Scenario: Section headings share one style
- **GIVEN** the home page is rendered
- **WHEN** the `class` attribute of every `h2` that is not `sr-only` is read
- **THEN** all are identical, and none carries the `h1`'s text-size classes

#### Scenario: No animation in the hero
- **GIVEN** the `LandingPage.vue` source
- **WHEN** it is searched for `animate-`, `<video`, `@keyframes` and `<Transition`
- **THEN** none is found

### Requirement: How it works is one row of four steps
`#how` SHALL show the four steps as one compact ordered list of four items, each with a decorative icon (`aria-hidden="true"`) beside its unchanged title, and its unchanged body below, laid out as a single row from the `md` breakpoint up, without card borders.

#### Scenario: Four steps with icons and unchanged copy
- **GIVEN** the home page is rendered
- **WHEN** `#how ol > li` is read
- **THEN** there are four items whose titles are, in order, "Answer a short form", "Watch the agreement build itself", "Pay, and we stamp it" and "Sign with Aadhaar OTP"
- **AND** each item has an `svg` with `aria-hidden="true"`

### Requirement: Before-you-decide panel discloses guarantees, status and FAQ on demand
The home page SHALL replace the separate guarantees, status and FAQ sections with one section (`data-testid="decide"`) headed "Before you decide", holding a tab list with three tabs in this order: "If something goes wrong" (controls `#guarantees`), "What's live" (controls `#status`) and "Questions" (controls `#faq`).

- **Default:** the guarantees tab SHALL be selected when the page loads without a matching hash.
- **In the DOM:** every tab panel SHALL stay rendered. An unselected panel is hidden with the `hidden` attribute, never unmounted. A tab panel element SHALL carry no CSS display utility class, with or without a responsive or state variant, because one would override the `hidden` attribute.
- **Accessibility:** the tabs SHALL use `role="tablist"`, `role="tab"` and `role="tabpanel"`. Each tab SHALL set `aria-selected` and `aria-controls`, and each panel `aria-labelledby`. Only the selected tab is in the tab order. ArrowLeft and ArrowRight move to and select the previous or next tab, wrapping. Home and End select the first and last.
- **Hash:** when the page loads with `#guarantees`, `#status` or `#faq`, or when an in-page link to one of them is followed with a plain primary click, the page SHALL select the matching tab and scroll the `decide` section (not the panel) into view. Following such a link, or selecting a tab, SHALL set the URL hash to the panel id without adding a browser history entry. A click with a modifier key or a non-primary button, or on a link with a non-`_self` `target` or a `download` attribute, SHALL be left to the browser.
- **Motion:** that scroll SHALL be instant when the user prefers reduced motion, and smooth otherwise.

#### Scenario: The guarantees tab is open by default
- **GIVEN** the home page is rendered with no hash
- **WHEN** the tabs are read
- **THEN** "If something goes wrong" has `aria-selected="true"` and the other two have `aria-selected="false"`
- **AND** `#guarantees` is not `hidden`, while `#status` and `#faq` are `hidden` and still contain their rows and questions

#### Scenario: Only one panel can show
- **GIVEN** the home page is rendered
- **WHEN** each `role="tabpanel"` element's own `class` attribute is read
- **THEN** no class token, after stripping any `variant:` prefixes and a leading `!`, is a display utility (`block`, `flex`, `grid`, `contents`, `flow-root`, `list-item`, `hidden`, `inline`/`inline-*`, `table`/`table-*`, or an arbitrary `[display:…]`)
- **AND** at the manual-test gate, only one panel is visible at a time

#### Scenario: A #status or #faq hash opens its tab
- **GIVEN** the page URL hash is `#guarantees`, `#status` or `#faq` (each separately) when the page mounts
- **WHEN** the page has rendered
- **THEN** the matching tab is selected, its panel is not `hidden`, and `scrollIntoView` was called on the `decide` section

#### Scenario: An in-page link opens its tab
- **GIVEN** the home page is rendered with the guarantees tab selected
- **WHEN** the price band's `a[href="#status"]` is clicked, and then the nav's `a[href="#faq"]`
- **THEN** the "What's live" tab is selected after the first click and "Questions" after the second
- **AND** each click's default was prevented, the URL hash reads `#status` then `#faq`, and the history length has not grown

#### Scenario: A modified click is left to the browser
- **GIVEN** the home page is rendered with the guarantees tab selected
- **WHEN** the nav's `a[href="#faq"]` is clicked with the meta key held
- **THEN** the click's default is not prevented and the guarantees tab stays selected

#### Scenario: Reduced motion scrolls instantly
- **GIVEN** `prefers-reduced-motion: reduce` matches (and, separately, does not)
- **WHEN** the page mounts with `#status`
- **THEN** the section is scrolled with `behavior: "auto"` (and, separately, `"smooth"`)

#### Scenario: Keyboard moves between tabs
- **GIVEN** the guarantees tab has focus
- **WHEN** ArrowRight is pressed twice, then ArrowRight once more, then End, then Home
- **THEN** the selected tab is "What's live", then "Questions", then "If something goes wrong" (wrapped), then "Questions", then "If something goes wrong"
- **AND** the selected tab has `tabindex="0"` and focus, and the others have `tabindex="-1"`
- **AND** the Home and End key events had their default prevented
- **AND** the URL hash follows the selected tab, and the history length has not grown

#### Scenario: Tab wiring is consistent
- **GIVEN** the home page is rendered
- **WHEN** each tab's `aria-controls` and each panel's `aria-labelledby` are read
- **THEN** every tab controls an existing panel id, and that panel is labelled by that tab's id
