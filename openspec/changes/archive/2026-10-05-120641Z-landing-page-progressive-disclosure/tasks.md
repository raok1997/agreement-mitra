## 1. Panel helper

- [x] 1.1 Create `frontend/src/views/landingPanels.ts` exporting `PANEL_IDS = ["guarantees", "status", "faq"] as const`, the derived `PanelId`, and pure `panelForHash(hash)`. No copy in this module; tab labels stay in the SFC (D4)
- [x] 1.2 Unit test `landingPanels.test.ts`: each of `#guarantees`/`#status`/`#faq` maps to its id; `""`, `#`, `#how`, `#price`, `#STATUS`, `status`, `#status?x`, `https://x/#status` return null; `PANEL_IDS` order
- [x] 1.3 Delete `frontend/src/assets/hero-draft.webp` and its import: the hero image was built, then dropped after manual review on 2026-10-05 (D2). No scratch file from its recipe sits in the repo.

## 2. LandingPage restructure

- [x] 2.1 Hero (D2, revised): one column `max-w-3xl`, padding `py-10 md:py-12`, no image or caption; "See how it works" stays removed.
- [x] 2.2 Move `#price` directly under the hero as the slim band (D3):
  - an `sr-only` "What it costs" heading;
  - the inclusions as an inline `ul` with CSS dot separators (`before:content-['·']` + `first:before:content-none`);
  - wording unchanged.
- [x] 2.3 `#how`, directly after `#price`: one compact `md:grid-cols-4` row of four steps without cards, each with an inline `aria-hidden` SVG icon beside its title and the body below; titles and bodies unchanged (D3)
- [x] 2.4 Replace `#guarantees`, `#status`, `#faq` with the `data-testid="decide"` section (D4):
  - an `h2#decide-heading` "Before you decide" and a tablist `aria-labelledby` it;
  - tabs `tab-<id>` with `aria-controls`/`aria-selected` and a roving tabindex;
  - tabpanels with `aria-labelledby`, `:hidden`, `scroll-mt-36`, and **no display utility (including variants) on the panel element** (layout on an inner wrapper); `tabindex="0"` only on the status panel;
  - keyboard: ArrowLeft/Right/Home/End with `preventDefault`, focus by id through a function ref;
  - selecting a tab (click or key) does `replaceState(history.state, "", "#" + id)`.
- [x] 2.5 Panel contents (D4):
  - Guarantees: keep the intro; each guarantee as `details.group[data-testid="guarantee"]` with the title in `summary` plus a decorative inline `<svg aria-hidden="true">` chevron (never a text glyph; `group-open:rotate-180`, `motion-reduce:transition-none`); body, qualifier and "Terms, section N" link inside.
  - FAQ: gets the same chevron.
  - Status and FAQ: existing content moved unchanged (no extra `span` in status rows); in-panel headings dropped.
- [x] 2.6 Hash wiring (D4):
  - `openPanel`: nextTick, then `decideEl.value?.scrollIntoView?.(…)` on the section (no `.catch`: it returns void); `"auto"` under reduced motion, guarded `matchMedia`.
  - `onMounted` reads the hash.
  - Delegated click: bail on `defaultPrevented`, modifiers, `button !== 0`, non-`_self` `target` or `download`; use `getAttribute("href")` through `panelForHash`; then `preventDefault`, `replaceState(history.state, …)`, `openPanel`.
  - `hashchange` listener added and removed on mount and unmount.
- [x] 2.7 Delete the closing CTA section and `cta-start`; update the header comment's section list (D5)

- [x] 2.8 Apply the D2 type scale and spacing rhythm: H1, sub-line, price headline, one shared `h2` class string for `#how` and `decide`, intros, step titles; `#how` and `decide` at `py-8 md:py-10`

## 3. Tests

- [x] 3.1 `LandingPage.test.ts` harness and layout-bound rewrites (D6):
  - Add the D6 harness: `enableAutoUnmount(afterEach)` once; an `afterEach` URL reset; stub cleanup via `Reflect.deleteProperty` and `vi.unstubAllGlobals`; a `mountLanding({ hash, attach })` helper; the D6 `defaultPrevented` recipe (manual `dispatchEvent` with `bubbles: true`, plus a `window` recorder-and-preventer for unprevented clicks); `flushPromises()` before reading stubs.
  - Rewrite only these assertions:
    - four direct-child sections in order, and no `closing-cta`/`cta-start`;
    - `nav-start` + `hero-start` emit `start` twice (update the "three entry points" comment);
    - guarantees via `#guarantees details[data-testid="guarantee"]`, with `summary` titles and `/terms` links;
    - `#status` is the tab panel.
  - Keep ownership, liveness, FAQ parity, figures, forbidden-claim, `v-html` and mailto unchanged; add "at least one footer mailto".
- [x] 3.2 `LandingPage.test.ts` — first screen:
  - the hero has one h1, the sub-line, exactly one control (`hero-start`) and no `img`, `picture` or `video`;
  - the source has no `animate-`, `<video`, `@keyframes` or `<Transition`;
  - `#price` is `main`'s second section and `#how` its third;
  - every visible `h2` has the same class string, and none carries the h1's size classes.
- [x] 3.3 `LandingPage.test.ts` — `#how ol > li` is four items with the four titles in order, each with `svg[aria-hidden="true"]`
- [x] 3.4 `LandingPage.test.ts` — panel:
  - the default tab is selected, and the other panels are `hidden` but populated;
  - no tabpanel's rendered class holds a display utility after variant stripping (D4 regex);
  - the aria wiring is consistent;
  - hash on mount for `#guarantees`/`#status`/`#faq` selects the tab, with the stub receiver being the `decide` section;
  - reduced motion gives `"auto"`, otherwise `"smooth"`;
  - attached in-page clicks (`#price a[href="#status"]`, then `nav a[href="#faq"]`) switch the tab, are default-prevented, update `location.hash`, and leave `history.length` unchanged;
  - a meta-click is not prevented by the component (`window` recorder) and the tab is unchanged;
  - the attached keyboard sequence checks focus, the roving tabindex, Home/End `defaultPrevented`, and `location.hash` following the tab.
- [x] 3.5 Integration `App.test.ts`: mount `App` at `/#faq` (attached, `scrollIntoView` stubbed and restored, manual `wrapper.unmount()`; no `enableAutoUnmount` in this file) — landing renders with the "Questions" tab selected; `hero-start` still routes to `/start` with no backend list call
- [x] 3.6 Run `LandingPage.flip.test.ts`, `promises.test.ts` and `releaseStatus.test.ts` unchanged and green (they must need no edit)

## 4. Gates

- [x] 4.1 `npm run build` and `npm run lint` from `frontend/` pass; report the test count
- [x] 4.2 In the dev server, measure at 1280×800 and record the results in the journal:
  - pass/fail: the price headline and all four `#how` step titles are fully visible without scrolling;
  - reported, not gated: the page height with the default tab; the Lighthouse desktop LCP is recorded at the Stage 6 manual-test gate.

## Coverage

| # | Requirement → Scenario | Disposition | Where |
|---|---|---|---|
| 1 | Ownership → No login is stated once outside the FAQ | COVERED | 3.1 (kept assertion) |
| 2 | Ownership → Free to draft is stated once outside the FAQ | COVERED | 3.1 (kept) |
| 3 | Ownership → Stamp duty in the price is stated once outside the FAQ | COVERED | 3.1 (kept) |
| 4 | Ownership → The page has four sections in order | COVERED | 3.1 |
| 5 | Price → The band shows the price, the overflow rule and its scope | COVERED | 3.1 (kept) |
| 6 | Price → The constant matches the backend default fee | COVERED | 3.6 (`promises.test.ts`, unchanged) |
| 7 | Price → The constant matches the published terms | COVERED | 3.6 (`promises.test.ts`) |
| 8 | Guarantees → Three guarantees, each expandable and linked to the terms | COVERED | 3.1 |
| 9 | Guarantees → Each guarantee keeps its qualifiers | COVERED | 3.1 |
| 10 | Guarantees → Guarantee figures match the terms | COVERED | 3.6 (`promises.test.ts`) |
| 11 | Guarantees → The old pillar copy is gone | COVERED | 3.1 (kept) |
| 12 | Status → Board rows render from the module | COVERED | 3.1 (kept) |
| 13 | Status → Flipping a row changes only that row | COVERED | 3.6 (`LandingPage.flip.test.ts`) |
| 14 | Status → A stamping row cannot be live without counsel review | COVERED | 3.6 (`releaseStatus.test.ts`) |
| 15 | Status → No liveness claim outside the board | COVERED | 3.1 (kept) |
| 16 | Status → The board's anchor is stable | COVERED | 3.1 |
| 17 | Entry points → Both CTAs start the builder | COVERED | 3.1 |
| 18 | Entry points → Every contact address is the constant | COVERED | 3.1 |
| 19 | Entry points → Route from `/` still reaches the picker | COVERED | existing `App.test.ts` route test, re-run in 3.5 |
| 20 | First screen → Hero holds the headline, sub-line and one button | COVERED | 3.2 |
| 21 | First screen → The price band and the steps follow the hero | COVERED | 3.2 |
| 22 | First screen → The price and the steps are on the first desktop screen | MANUAL | 4.2 + Stage 6 manual-test gate (jsdom has no layout) |
| 23 | First screen → Section headings share one style | COVERED | 3.2 |
| 24 | First screen → No animation in the hero | COVERED | 3.2 |
| 25 | How it works → Four steps with icons and unchanged copy | COVERED | 3.3 |
| 26 | Panel → The guarantees tab is open by default | COVERED | 3.4 |
| 27 | Panel → Only one panel can show | COVERED | 3.4 (class check) + Stage 6 manual gate (visual) |
| 28 | Panel → A #status or #faq hash opens its tab | COVERED | 3.4 (unit) + 3.5 (`App` at `/#faq`) |
| 29 | Panel → An in-page link opens its tab | COVERED | 3.4 |
| 30 | Panel → A modified click is left to the browser | COVERED | 3.4 |
| 31 | Panel → Reduced motion scrolls instantly | COVERED | 3.4 |
| 32 | Panel → Keyboard moves between tabs | COVERED | 3.4 |
| 33 | Panel → Tab wiring is consistent | COVERED | 3.4 |

33 scenarios — 32 COVERED, 0 GROUPED, 1 MANUAL, 0 WAIVED, 0 UNMAPPED.
