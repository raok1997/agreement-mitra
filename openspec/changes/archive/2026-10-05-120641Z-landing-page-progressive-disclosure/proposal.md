## Why

The home page is about 3,100px tall on desktop. The first screen shows only the headline and
two buttons, and the price, guarantees, status board and FAQ follow as four full sections. A
visitor has to scroll through all of it to see what the product looks like or what it costs.
`landing-page-release-copy` fixed *what* the page says; this change fixes *how much of it is in
front of the visitor at once*, so the first screen is a complete first impression and the rest
of the page is short.

## What Changes

- **Hero** is one tight column: the unchanged headline (smaller, on two lines), sub-line and
  `hero-start` button. The "See how it works" secondary link is removed. (A draft image beside
  the headline was built and dropped after manual review: unreadable at that size.)
- **Type scale and spacing**: one size step per level (headline, price, section headings, body)
  and one vertical rhythm, so the first desktop screen holds the headline, the price and the
  four steps.
- **Price band**: the `#price` section becomes a slim band directly under the hero, inside the
  first desktop viewport. Its content, its figures from `promises.ts` and its ownership of "free"
  and "includ…" are unchanged.
- **`#how`** follows the price band as one compact row of four icon steps, with no cards. Step
  titles and bodies are unchanged.
- **"Before you decide" panel** replaces the `#guarantees`, `#status` and `#faq` sections with
  three accessible tabs: "If something goes wrong" (default), "What's live" and "Questions".
  - Each guarantee becomes a one-line headline that expands to its body, qualifier and terms link.
  - All three panels stay in the DOM when hidden.
  - A `#guarantees`, `#status` or `#faq` hash, on load or on click, opens the matching tab and
    scrolls to it.
- **Closing CTA removed.** The sticky `nav-start` button covers it. **BREAKING** for the
  `cta-start` test hook.
- `main` goes from seven sections to four: hero, `#price`, `#how`, the panel.

Copy is unchanged except the new panel heading and the three tab labels. The in-panel headings that would repeat a tab label are dropped.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `landing-page`:
  - the section-order scenario (seven → four sections);
  - the guarantees requirement (cards → expandable rows inside a tab panel);
  - the status-board anchor scenario (the board is a tab panel, and a hash opens it);
  - the entry-points requirement (`cta-start` removed).
  - New requirement: the "Before you decide" panel's tabs, default, hash behaviour and
    accessibility.
  - New requirement: the first screen (a text-only hero, then the price band and the steps;
    one heading style).

## Impact

- **Code:** `frontend/src/views/LandingPage.vue`, `LandingPage.test.ts`, a new
  `landingPanels.ts` helper with its test, and one integration case in `App.test.ts`. No assets.
- **Unchanged:** `promises.ts`, `releaseStatus.ts`, `index.html` JSON-LD, the ToS, SEO meta, and
  the backend.
- **Dependencies:** none added. The page still makes no network call.
- **Signing FSM:** not touched.
- **PII / security:** no runtime PII and no image. No Aadhaar, OTP, VID or secret is introduced
  or moved.
