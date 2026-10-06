## Context

`frontend/src/views/LandingPage.vue` (430 lines) is self-contained: no child components, no API
calls. After `landing-page-release-copy` (11422fe) and the compact pass (e54b9f7), `main` holds
seven stacked sections: hero, `#how`, `#price`, `#guarantees`, `#status`, `#faq` and the closing
CTA. The desktop page is about 3,100px tall, and the first screen holds only the headline, the
sub-line and two buttons.

What stays fixed comes from the spec of record (`openspec/specs/landing-page/spec.md`) and the
archived design (D1–D7):

- **Ownership.** "No login" belongs to the hero. "Free" and "includ…" belong to `#price`, and the
  FAQ may also use them.
- **Liveness.** The `#status` board, rendered from `releaseStatus.ts`, is the only statement of
  what is live. The flip test and the counsel-gate test depend on it.
- **Figures.** Every figure comes from `promises.ts` and is pinned to the ToS and `application.yml`.
- **FAQ.** The visible FAQ equals the `index.html` FAQPage JSON-LD.
- **Copy.** No forbidden claims, and no `v-html`.

`LandingPage.test.ts` encodes all of this with `textOutside(wrapper, selectors)`, which reads text
nodes. It therefore already counts the text of hidden elements.

ToS §2 and §7 refer to "the status board on our home page" in prose, not as a link. The nav and the
price card link to `#status` in-page. `App.vue` routes on `pathname` only, so `/#status` mounts the
landing page with the hash still present.

## Goals / Non-Goals

**Goals:**
- A first desktop screen with the headline, the CTA, the price and the four steps.
- A page about 1.5 viewports tall on desktop.
- Guarantees, status and FAQ disclosed on demand without losing an anchor, a test guarantee or
  crawlable text.

**Non-Goals:**
- Copy rewrites, ToS text, SEO meta, the JSON-LD and the backend.
- The animated hero (`landing-hero-live-preview`, a later change).
- Changing `promises.ts` or `releaseStatus.ts`.
- A new runtime dependency.

## Decisions

### D1. Page structure

nav → hero (`data-testid="hero"`) → `#price` band → `#how` → `section[data-testid="decide"]` →
footer. `main` holds exactly these four sections.

The tab panels are `div`s, so the section count stays four, and they carry the ids `guarantees`,
`status` and `faq`. The ownership and liveness selectors (`#price`, `#faq`, `#status`) still
resolve, so those tests keep their selectors.

**Nav links are unchanged:** How it works · Price · Guarantees · Status · FAQ. The last three open
their tab (D4).

### D2. Hero and the type scale

> **Revised 2026-10-05 after manual review.** The first build put a cropped image of a generated
> draft beside the headline. On screen it was unreadable at that size, hidden on phones, and it
> cost about 245px of the first screen plus an asset recipe and a by-eye PII and claim review. The
> user dropped it, and asked for the first screen to carry the steps as well. *Rejected now:* the
> draft image (the builder shows the real document one click away; `landing-hero-live-preview` may
> return a live one later).

- **Layout.** One column, `max-w-3xl`, padding `py-10 md:py-12`. In order: the unchanged H1, the
  unchanged sub-line (it still owns "No login to begin") and `hero-start` as the only control.
  The "See how it works" outline button stays removed.
- **Type scale.** One step per level, so the eye reads headline → price → section → body:

  | Element | Classes | Desktop size |
  |---|---|---|
  | H1 | `text-3xl md:text-[2.75rem] font-bold leading-[1.15] tracking-tight` | 44px, 2 lines at `max-w-3xl` |
  | Sub-line | `mt-4 max-w-2xl text-base md:text-lg leading-relaxed` | 18px |
  | Section H2 (`#how`, `decide`) | `text-xl md:text-2xl font-bold tracking-tight` — one shared class string | 24px |
  | Price headline (a statement, not a heading) | `text-lg md:text-xl font-bold` | 20px |
  | Section intro | `mt-1 text-sm md:text-base text-ink-600` | 16px |
  | Step title | `text-sm md:text-base font-semibold` | 16px |

  The H2s were `text-2xl md:text-3xl` (30px), close enough to a 48px H1 to read as a second
  headline; at 24px under a 44px H1 the hierarchy is unambiguous. The price headline drops from
  `md:text-2xl` to `md:text-xl` so it does not compete with the section headings, while bold
  type on the tinted band keeps it the most prominent fact under the hero.
- **Spacing rhythm.** Hero `py-10 md:py-12`; button `mt-6`. Price band `py-5`. `#how` and
  `decide` both `py-8 md:py-10`, heading → intro `mt-1`, intro → content `mt-6` (`#how`) or
  tablist `mt-5`. Consistent vertical steps replace the old mix of `py-12`/`py-16`/`py-20`.
- **Budget at 1280×800** (manual gate measures it): header ~58, hero ~340, price band ~90,
  `#how` to the end of the step titles ~150 — the step titles end at about 640px, leaving room
  for most of the step bodies too.
- **Phones.** The same single column; the H1 is `text-3xl`. Nothing is hidden by breakpoint.

### D3.### D3. Price band and `#how`

- **`#price` band.** One full-width band with `bg-ink-50` and `border-y`, and `py-6`.
  - An `sr-only` `<h2>What it costs</h2>` keeps the document outline without a visible heading.
  - **Left:** the headline, with "Included:" and the inclusions as one inline list separated by
    middle dots.
  - **Right:** the overflow line, "Drafting, previewing and downloading a draft are free." and the
    Status link.
  - It stacks below `md`. The inclusions stay a `ul`, laid out inline. The dot separators are
    drawn by CSS on every item but the first (`before:content-['·']` with
    `first:before:content-none`), so they add no text node. The wording is unchanged.
- **`#how`.** It keeps its heading and intro. It is a compact `ol` with `sm:grid-cols-2
  md:grid-cols-4` and no border, background or shadow on the items.
  - Each `li` starts with one row: a 20px inline SVG icon in a 36px brand-tinted circle
    (`aria-hidden="true"`) beside the title. The body sits under that row in `text-sm`.
  - The icons are hand-written Heroicons-outline-style paths inlined in the template: a form, an
    eye or document, a stamp or seal, and a phone with a check. That adds no dependency. The step
    number stays visually in the circle's corner.
  - *Rejected:* an icon package, which is a new runtime dependency for four glyphs.

### D4. The "Before you decide" panel

**Markup:**

```
<section data-testid="decide" id="decide" class="scroll-mt-20 …">
  <h2 id="decide-heading">Before you decide</h2>
  <div role="tablist" aria-labelledby="decide-heading">
    <button role="tab" id="tab-guarantees" aria-controls="guarantees" :aria-selected :tabindex>If something goes wrong</button>
    <button role="tab" id="tab-status"     aria-controls="status"     …>What's live</button>
    <button role="tab" id="tab-faq"        aria-controls="faq"        …>Questions</button>
  </div>
  <div role="tabpanel" id="guarantees" aria-labelledby="tab-guarantees" :hidden="active !== 'guarantees'"><div class="…layout…">…</div></div>
  <div role="tabpanel" id="status" …>…</div>
  <div role="tabpanel" id="faq" …>…</div>
</section>
```

- **State.** `const active = ref<PanelId>("guarantees")`.
  - `landingPanels.ts` exports `PANEL_IDS = ["guarantees", "status", "faq"] as const`, the
    derived `PanelId`, and `panelForHash`. It holds **no copy**.
  - The tab labels stay in the SFC beside all other landing copy, as the copy-discipline header
    assumes.
- **Panel elements carry no display utility.** This is a hard rule.
  - Tailwind preflight hides `[hidden]` with one attribute selector
    (`preflight.css:384`). It has the same specificity as `.grid`, `.flex` or `.block`, and
    utilities are emitted later.
  - So a layout class on a `role="tabpanel"` element would show all three panels at once, and
    jsdom (no CSS) could not see it.
  - Layout therefore goes on an inner wrapper.
  - A test reads each tabpanel's **rendered** `class` attribute, so `:class` bindings count. It
    splits the attribute on whitespace and strips every `variant:` prefix and a leading `!` from
    each token. It then fails on any token matching
    `^(block|flex|grid|contents|flow-root|list-item|hidden|inline(-\S+)?|table(-\S+)?|\[display:.*\])$`.
    - `md:grid` overrides `hidden` at `md`, exactly like `grid`.
    - Non-display utilities such as `space-*`, padding and `scroll-mt-*` are allowed.
  - The manual-test gate checks that only one panel is visible.
  - Only the status panel, which has no focusable content, gets `tabindex="0"` (APG). The other
    two are full of `summary` and link stops.
  - Each panel carries `scroll-mt-36`, which clears the sticky header plus the tablist. Any
    scroll the browser makes on its own to `#faq` (for example its load-time fragment scroll on a
    fresh `/#faq` tab) then leaves the tablist visible.
- **Hidden panels.** `:hidden` keeps every panel in the DOM, which a `v-if` would not. That
  matters for crawlers, the JSON-LD parity test and the ownership and liveness tests, which read
  text nodes. `v-show` was rejected: its inline `display:none` would also beat the utilities, but
  `hidden` is the semantic attribute and is what the spec asserts. The no-display-utility rule
  closes the gap instead. `hidden="until-found"` (find-in-page) was considered and rejected for
  now: it needs a `beforematch` handler and support differs across browsers.
- **Keyboard** (`@keydown` on the tablist):
  - ArrowRight and ArrowLeft move with wrap; Home and End go to the first and last.
  - Each calls `preventDefault()`, so Home and End do not also scroll the page. Each sets
    `active` and focuses the new tab, found by its id `tab-<PanelId>` through a function ref keyed
    by id, not the order of a `v-for` ref array (automatic activation).
  - **Selecting a tab**, by click or key, also calls
    `history.replaceState(history.state, "", "#" + id)`. This keeps the URL in step, so a reload
    or share reopens the tab the visitor is on. It also means typing the current tab's hash again
    still fires a `hashchange`, because the URL now carries that hash already. It never pushes.
  - Roving `tabindex`: 0 on the selected tab, -1 on the others.
- **Hash handling, a pure helper plus a thin wiring layer:**
  - `panelForHash(hash: string): PanelId | null` is pure. It maps `#guarantees`, `#status` and
    `#faq` and returns null for anything else.
  - `openPanel(id)` sets `active`, awaits `nextTick()`, and calls
    `decideEl.value?.scrollIntoView?.({ block: "start", behavior })`.
    - The method is optional-called because jsdom has none.
    - `scrollIntoView` returns `void` (lib.dom), so there is no promise to handle. Do **not**
      chain `.catch`: that fails `vue-tsc` (TS2339) and throws on `undefined` at runtime.
  - The scroll target is the `decide` **section**, not the panel, so the tablist stays visible
    under the sticky header (`scroll-mt-20` sits on the section). The spec names this target.
  - `behavior` is `"smooth"` unless
    `window.matchMedia?.("(prefers-reduced-motion: reduce)").matches`, in which case it is
    `"auto"`. The check is guarded for jsdom, which has no `matchMedia`.
    - Existing hover `transition` classes are colour fades, not motion, and are left as they are.
      The spec's Motion clause is scoped to scrolling.
  - `onMounted`: if `panelForHash(location.hash)` matches, `openPanel` runs. This covers a shared
    `/#faq` link and anyone who types the ToS's "status board on our home page" address.
    - `/#how` and `/#price` keep relying on the browser's native fragment scroll. That is
      existing behaviour and out of scope.
  - **In-page links:** one `@click` on the root `div` delegates.
    - It returns early on `event.defaultPrevented`, any modifier key, `button !== 0`, a `target`
      other than `_self`, or a `download` attribute. A Cmd-, Ctrl- or middle-click therefore still
      opens a new tab.
    - It reads `anchor.getAttribute("href")`, never `anchor.href` (absolute), passes it through
      `panelForHash`, and acts only on a non-null `PanelId`. It then calls `preventDefault()`,
      then `history.replaceState(history.state, "", "#" + id)`, then `openPanel(id)`.
    - Only an allowlisted `PanelId` ever reaches a sink. Hash or href text never reaches
      `querySelector`, `getElementById`, `innerHTML` or a `navigate()` call.
    - **Scope:** only the three panel anchors are intercepted. `#how` and `#price` stay native
      anchors, as today.
    - The default must be prevented. The browser would otherwise scroll before Vue un-hides the
      panel, so it would scroll to a hidden element and do nothing. Re-clicking the same hash also
      fires no `hashchange`.
    - `replaceState` rather than a push: `App.vue` listens to `popstate` for its own routing, and
      a pushed hash entry would be one more back-step on a single-page landing. It also keeps the
      URL shareable.
    - `history.state` is passed through so App's state is untouched.
  - A `hashchange` listener (added on mount, removed on unmount) covers a hash typed into the
    address bar.
- **Panel contents.** These are the existing blocks, moved unchanged, except:
  - **Guarantees:** the intro "These come from our terms of service, which are still a draft."
    stays. Each guarantee is a
    `<details data-testid="guarantee"><summary>{{ title }}</summary>…body, qualifier, link…</details>`.
    - All are collapsed by default, and the title is the one-line headline. The requirement asks
      for "three one-line headlines, each expandable".
    - The summary carries a decorative chevron, because `list-none` removes the native marker.
      - It is an **inline `<svg aria-hidden="true">`**, never a text glyph: `.text()` on
        `faq-q` feeds the JSON-LD parity test, and `aria-hidden` does not remove text.
      - It is rotated with `group-open:rotate-180` and `motion-reduce:transition-none`.
      - Each guarantee `details` therefore carries the `group` class, as the FAQ ones already do.
      - The FAQ items get the same chevron for consistency.
    - Moving the title from an `h3` into a `summary` drops its heading semantics. That is accepted
      on purpose: `summary` is the interactive control, and the tab is the heading.
  - **Status:** the intro "We update this board the day anything changes." stays. The `ul`
    structure stays: a label span, then a badge span, and **no other `span`** in a row.
    `LandingPage.flip.test.ts` reads the spans by position.
  - **FAQ:** the `details` list, the `faq-q`/`faq-a` ids and the footnote stay.
  - **Panel headings are dropped:** "If something goes wrong", "What is live today" and
    "Questions people actually ask". Each one repeats or paraphrases its tab label directly above
    it. The tab labels come from the requirement as given.
- **Height.** The page height changes with the selected tab, the FAQ being the tallest. The
  ~1.5-viewport target is measured with the default tab.
- **Tablist underline.** The divider is an inset box-shadow on the tablist, and the active tab's
  `border-b-2` sits inside its own box. A `-mb-px` overlap onto a `border-b` overflows the
  tablist by 1px, and because `overflow-x-auto` forces `overflow-y` to `auto`, that showed a
  vertical scrollbar (manual review, 2026-10-05). The tablist also sets `overflow-y-hidden`.
- **Mobile.** The tablist is `flex overflow-x-auto` with no wrap, and labels have
  `whitespace-nowrap`. All three fit at 360px with `text-sm px-3`. The scroll is a fallback for
  larger font settings.

### D5. Closing CTA removed

The section and `cta-start` are deleted. The sticky header's `nav-start` is always visible. The
footer keeps the support `mailto`, which was the closing CTA's "Questions? Write to…" job (already
moved to the footer by e54b9f7). `App.test.ts` does not reference `cta-start`, so it needs no
change beyond the new integration test (task 3.5). Removing `cta-start` is the one **breaking**
test-hook change, and the spec's entry-points requirement records it.

### D6. Tests

- **Harness rules.**
  - **`LandingPage.test.ts`:**
    - `enableAutoUnmount(afterEach)`, called once. It is file-global and throws on a second call.
    - Mounts that need focus, history or click navigation use `attachTo: document.body`.
    - `afterEach` resets the URL with `history.replaceState({}, "", "/")`.
    - `afterEach` removes stubs with `Reflect.deleteProperty(Element.prototype, "scrollIntoView")`
      and `vi.unstubAllGlobals()`. `window.matchMedia` is stubbed with `vi.stubGlobal`. A bare
      `delete` is TS2790, and `as any` fails lint.
    - A `mountLanding({ hash, attach })` helper does all of this.
  - **`App.test.ts`:** the new case calls `wrapper.unmount()` itself. No `enableAutoUnmount`
    there, because the existing cases unmount manually (`App.test.ts:310,362,446,464`).
  - **Await order.** After `mount`, `await flushPromises()` before reading the `scrollIntoView`
    stub's `mock.contexts[0]` or `mock.calls[0][0].behavior`, because `openPanel` awaits
    `nextTick` first.
  - **Reading `defaultPrevented`.** `wrapper.trigger()` cannot expose the event, so there is one
    recipe for all three tests that need it (link click, meta-click and Home/End).
    - Dispatch by hand, `const ev = new MouseEvent("click", { bubbles: true, cancelable: true,
      metaKey })` (or a `KeyboardEvent` with `bubbles: true`, which is mandatory because the
      handler sits on the tablist), then `el.dispatchEvent(ev)`, then `await nextTick()`, then
      read `ev.defaultPrevented`.
    - For any click the component may **not** prevent (the meta-click), also add a `window`
      bubble-phase `click` listener first. It records `e.defaultPrevented`, which is the
      component's decision because the root-div handler has already run, and then calls
      `e.preventDefault()`.
      - Without that, jsdom navigates on a `setTimeout(0)` regardless of modifiers
        (`HTMLHyperlinkElementUtils-impl.js:79-81`).
      - That navigation pushes an entry and fires `hashchange` into a later test.
      - The oracle is "our handler did not prevent it", not "the browser left the tab alone".
- **Changed only where the layout changed:**
  - section order (seven to four, using the existing direct-children-of-`main` filter);
  - CTA emit (two buttons; the comment "All three entry points" is updated);
  - the guarantee selectors (`details` instead of `article`);
  - the status anchor (`#status` is a `div[role=tabpanel]`, no longer `section#status`).
- **Unchanged assertions:** ownership, liveness, FAQ parity, figures, forbidden claims, `v-html`
  and the mailto constant. "At least one footer mailto" is added.
- **Added in `LandingPage.test.ts`:**
  - **Hero.**
    - The hero structure: one `h1`, the sub-line, `hero-start` as the only control, and no
      `img`, `picture` or `video`.
    - The source has no animation tokens.
    - Every visible `h2` has the same `class` string, and none carries the `h1`'s size classes.
  - **Layout.** The price band comes directly after the hero and `#how` after it; `#how` shows
    four steps with icons.
  - **Panel default.** The default tab is selected, and the other panels are `hidden` but
    populated. No tabpanel's own `class` holds a display utility.
  - **Hash on mount.** Run for each of `#guarantees`, `#status` and `#faq`: the tab is selected,
    and the `scrollIntoView` stub's receiver (`mock.contexts[0]`) is the `decide` section.
  - **Reduced motion.** With `matchMedia` stubbed to `matches: true`, the stub got
    `behavior: "auto"`; without the stub, it got `"smooth"`.
  - **In-page link clicks** (attached; App sees no `popstate`, because a prevented click pushes
    no entry). For `#price a[href="#status"]` then `nav a[href="#faq"]`,
    the tab switches, `event.defaultPrevented` is true, `location.hash` is updated and
    `history.length` is unchanged. A Cmd-click is **not** prevented and leaves the tab alone.
  - **Keyboard** (attached). The sequence, `document.activeElement`, the roving tabindex,
    `preventDefault` on Home and End, and `location.hash` following the selected tab.
  - **Wiring.** The aria wiring is consistent.
- **Pure helper:** `landingPanels.test.ts` covers `panelForHash` and `PANEL_IDS`.
- **Integration** (`App.test.ts`): mount `App` at `/#faq` (attached, unmounted after). The
  landing page renders with the "Questions" tab selected and no error, and `hero-start` still
  routes to `/start`.

## Risks / Trade-offs

- **[Risk] Content behind a tab is read less.** That is intended for a secondary reader. The
  guarantees, the strongest trust content, are the default tab, and the price, which is the
  decision-critical fact, is promoted into the first screen.
- **[Risk] Crawlers may weight hidden content lower.** The FAQ is also in the FAQPage JSON-LD,
  which is unchanged, and all text stays in the DOM.
- **[Trade-off] No picture of the product on the landing page.** The hero is text only. The
  promise "read the real document as it is written" is kept by the builder itself, one click
  away, and a static crop could not be read at hero size anyway.
- **[Risk] Guarantee conditions move behind a disclosure.** The archived design showed each
  qualifier beside its promise. The agreed design (2026-10-05) asks for one-line headlines that
  expand.
  - The qualifiers stay in the same `details`, one click from the headline, and never apart from
    it.
  - A headline alone is a summary of a promise whose conditions are one tap away, in the same
    element.
  - This is a deliberate trade for a quieter page. Recorded so a later reviewer does not read it
    as an accident.
- **[Trade-off] `hashchange` also fires on Back and Forward between fragment entries**, and a
  remount on Back from `/start` to `/#status` runs `onMounted`. In both cases the panel opens and
  the `decide` section scrolls into view, which can override the browser's scroll restoration.
  That is accepted: the visitor returns to the tab they left.
- **[Trade-off] `replaceState` on an in-page link** means "back" does not undo the tab switch.
  That is acceptable on a one-page landing, and it avoids interfering with `App.vue`'s `popstate`
  routing.
- **[Trade-off] Asserting Tailwind class names** (the panel display-utility rule, the shared
  `h2` class string) couples the tests to utility names. jsdom has no layout, so a class check is
  the only cheap proof; pixel checks are left to the manual test.

## Migration Plan

Frontend-only. Ships with the next static deploy, and rollback is reverting the commit. No data,
config or API change.

**Manual-test gate checks (pass/fail):**
- At 1280×800, the price headline and all four `#how` step titles are fully visible without
  scrolling.
- Only one tab panel is visible at a time.
- Typing `#status` into the address bar opens that tab.
- Cmd-clicking a nav link opens a new tab.
- A fresh load of `/#faq` in a new tab: the "Questions" tab is open and the tablist is visible
  below the sticky header.
- Lighthouse desktop LCP is recorded (reported, not gated).
- The page height with the default tab is recorded. The ~1.5-viewport target is reported, not
  gated.

## Open Questions

1. ~~Hero alt text~~ and 2. ~~visible caption~~ — **moot 2026-10-05**: the hero image was
   dropped after manual review (D2).
3. ~~Removed copy~~ **Resolved 2026-10-05 (user):** all three removals were accepted:
   - the hero's "See how it works" link;
   - the three in-panel headings;
   - the visible "What it costs" heading (kept `sr-only`).

   A quiet "See how it works ↓" text link under the CTA was offered and **deferred** ("revisit
   later if needed"). It is not in this CR.
