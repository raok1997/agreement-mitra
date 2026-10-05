## 1. Content modules

- [x] 1.1 Create `frontend/src/content/promises.ts` with `PRICE`, `GUARANTEES` (day counts stored as words), `SUPPORT_HOURS`, `CONTACT_EMAIL` and `formatRupees`, a template literal rather than `Intl` (design D2)
- [x] 1.2 Create `frontend/src/test-support/backendConfig.ts`:
  - pure `parseYamlDefault(yamlText, leafKey)` in the D2 order: match exactly one indented `leafKey:` line, strip the YAML quotes, strip `${`/`}`, split at the first `:`
  - thin `readYamlDefault(leafKey)` that reads `../backend/src/main/resources/application.yml`

  `frontend/tsconfig.json` excludes `src/test-support/**` from vue-tsc (it imports `node:fs` and the repo has no `@types/node`), so production code must never import from it.

  Unit tests for `parseYamlDefault` cover: a quoted value; an unquoted value; a default containing `: `; zero matches (throws); two matches (throws)
- [x] 1.3 Unit: `frontend/src/content/promises.test.ts`:
  - `PRICE` × 100 equals both fee defaults (via `readYamlDefault`)
  - for §7, §8, §11 and §14, find the clause by `heading.startsWith("N. ")`, assert that it was found, join the `body` array, then assert the D2 clause-local phrases built from the constants
- [x] 1.4 Create `frontend/src/content/releaseStatus.ts` (D3 rows, including `stampingState` on the two stamping rows). Its header comment is the launch checklist and lists the other surfaces
- [x] 1.5 Unit: `frontend/src/content/releaseStatus.test.ts` (counsel gate): for each row with `stampingState` and `state: "live"`, every `backend/src/main/resources/rules/stamp-duty/<ST>/*.yaml` must carry a non-null `counselReview`

## 2. Landing page

- [x] 2.1 Restructure `frontend/src/views/LandingPage.vue` to the D1 order:
  - put `data-testid="hero"` and `data-testid="closing-cta"` on the `<section>` elements themselves
  - remove the trust strip and the hero badge
  - apply the D1 copy verbatim (nav, hero, steps, closing CTA, footer)
  - import `CONTACT_EMAIL` from `promises.ts`
- [x] 2.2 Add `#price` per D2: the heading "What it costs", the conditional headline, the inclusions, the overflow line, the free line, and the scope line linking to `#status`. Render from `PRICE` / `formatRupees`
- [x] 2.3 Replace `#why` with `#guarantees` per D4 (verbatim, figures from `GUARANTEES`) and drop the pillars array
- [x] 2.4 Render `#status` from `RELEASE_STATUS` / `RELEASE_STATE_LABEL` with the D3 heading and intro verbatim. Keep the label-span-then-badge-span structure and `id="status"`
- [x] 2.5 Replace the FAQ array per D5 (verbatim, interpolating from `promises.ts`) and shorten the page footnote
- [x] 2.6 Update the `FAQPage` JSON-LD in `frontend/index.html` to the identical resolved Q/A text and order, with a literal UTF-8 `₹`. The footnote does not go there
- [x] 2.7 Keep `nav-start`, `hero-start`, `cta-start`, `faq-q` and `faq-a`. Use no `v-html`

## 3. Legal disclaimer

- [x] 3.1 Rewrite `frontend/src/components/LegalDisclaimer.vue` to the D6 wording, keeping the `/terms` link, `print:hidden` and both variants. Update the header comment, including the release condition
- [x] 3.2 Set the `documents.footer.screen-notice` default in `backend/src/main/resources/application.yml` to the same wording, rendering the link as "terms of service at agreementmitra.com/terms". Double-quote the whole `${DOCUMENT_FOOTER_SCREEN_NOTICE:…}`
- [x] 3.3 Unit: in `frontend/src/components/LegalDisclaimer.test.ts`:
  - assert the sentence order: wording-is-ours, then facts-and-choices, then "not a law firm", "no lawyer reviews" and "not legal advice"
  - assert the spec's forbidden phrases are absent
  - read the source and assert it has no `v-html`
  - keep the existing link, `print:hidden` and variant assertions
- [x] 3.4 Unit: in the same file, `readYamlDefault("screen-notice")` equals the component text once the link text is replaced by "terms of service at agreementmitra.com/terms" and whitespace is collapsed on both sides
- [x] 3.5 Integration: `./run-tests.sh check` loads Spring contexts that bind `DocumentFooterProperties` from the new quoted default. Gate 6.2 covers this; it proves the YAML parses and binds

## 4. Landing tests

Shared helpers live in `LandingPage.test.ts`:
- `textOutside(wrapper, selectorList)` clones the root, removes every node matching the list, and joins the text nodes with spaces
- `matches(text, phrase, {prefix})` applies the spec's phrase guards; ₹ figures use plain `includes`

The FAQ parity test keeps `el.text()` + `squash`, as it does today.

- [x] 4.1 Unit: rewrite `frontend/src/views/LandingPage.test.ts`:
  - keep the CTA test and the FAQ ↔ JSON-LD parity test
  - retarget the mailto test to `CONTACT_EMAIL`, and assert that FAQ 7 contains it
  - replace the literal "In integration" test with a board test: `id="status"` is linked from the nav, the rows appear in `RELEASE_STATUS` order with `RELEASE_STATE_LABEL` badges, and stamping rows for TG and KA exist
- [x] 4.2 Unit: `frontend/src/views/LandingPage.flip.test.ts` uses `vi.mock(..., async (importOriginal) => …)` to flip the eSign row to `live`. Assert that row reads "Live now" and every other row reads its module label
- [x] 4.3 Unit: section order. `main` holds exactly 7 sections, in this order: `[data-testid=hero]`, `#how`, `#price`, `#guarantees`, `#status`, `#faq`, `[data-testid=closing-cta]`
- [x] 4.4 Unit: ownership tests, following the spec scenarios:
  - no-login phrases: no match outside `#faq` and the hero, and at least one match in the hero
  - "free": no match outside `#faq` and `#price`, and at least one match in `#price`
  - "includ" (prefix): no match outside `#faq` and `#price`; `#price` has "stamp duty" and "includ"
  - "all-in": appears nowhere on the page
- [x] 4.5 Unit: liveness blocklist over the text outside `#status`, using the spec's phrase list
- [x] 4.6 Unit: `#price` and `#guarantees` content:
  - `#price`: "₹499", "₹100", "exact total before you pay", "free", a link to `#status`
  - `#guarantees`: three cards, each with a `/terms` link, carrying the D4 literal qualifier list
  - absent: "Why bother" and "in your language"
- [x] 4.7 Unit: FAQ content:
  - the 11-month answer contains the three spec literals
  - none of the forbidden threshold strings appears anywhere in the FAQ
  - the cost and something-goes-wrong questions are present; "What does stamp duty cost?" is absent
  - the e-signature answer does not begin with "Yes"
- [x] 4.8 Unit: forbidden-claims test over the page (spec list). A source check confirms `LandingPage.vue` has no `v-html`
- [x] 4.9 Integration: `frontend/src/App.test.ts` "landing → /start" passes unchanged against the rewritten page. It mounts the full app at `/` with the router and a mocked API, which is the closest cross-component check this copy change has; the cross-stack check is 3.5

## 5. Docs and register

- [x] 5.1 Update `docs/LEGAL-POSTURE.md`:
  - "Honest marketing": the price, guarantees, board-only liveness, forbidden claims, counsel gate
  - item 2: the new wording, the release condition, and the `DOCUMENT_FOOTER_SCREEN_NOTICE` fork line
- [x] 5.2 Add a sub-item under Q6(d) in `docs/COUNSEL-BRIEF.md`: review the disclaimer's "we stand behind it" against §16, across all three carriers
- [x] 5.3 Add a one-line `[FLAG]` to the 11-month bullet in `docs/GO-TO-MARKET-HYDERABAD.md`
- [x] 5.4 In `docs/ROADMAP.md`, append sentences per D7 (no new rows) to:
  - `tos-below-duty-stamp-choice`
  - `rental-deed-lease-vs-licence`
  - `tg-stamp-duty-counsel-review`
  - the round-2 `terms-release-revision` line (§5 vs the board; the stale national-template sentence)
  - the release Counsel line (disclaimer condition, three carriers)

  Then delete the round-1 `landing-page-release-copy` entry.

## 6. Gates

- [x] 6.1 From `frontend/`, `npm run build` and `npm run lint` pass
- [x] 6.2 From `backend/`, `./run-tests.sh check` passes with Docker running, so context-loading tests execute. Report the wall-clock time

## Coverage

| Scenario | Disposition | Where |
|---|---|---|
| No login is stated once outside the FAQ | COVERED | 4.4 |
| Free to draft is stated once outside the FAQ | COVERED | 4.4 |
| Stamp duty in the price is stated once outside the FAQ | COVERED | 4.4 |
| The trust strip is gone | COVERED | 4.3 |
| The card shows the price, the overflow rule and its scope | COVERED | 4.6 |
| The constant matches the backend default fee | COVERED | 1.3 |
| The constant matches the published terms | COVERED | 1.3 |
| Three guarantees, each linked to the terms | COVERED | 4.6 |
| Each guarantee keeps its qualifiers | COVERED | 4.6 |
| Guarantee figures match the terms | COVERED | 1.3 |
| The old pillar copy is gone | COVERED | 4.6 |
| Board rows render from the module | COVERED | 4.1 |
| Flipping a row changes only that row | COVERED | 4.2; the "no other test pins state" half holds by construction (4.1 derives from the module) |
| A stamping row cannot be live without counsel review | COVERED | 1.5 |
| No liveness claim outside the board | COVERED | 4.5 |
| The board's anchor is stable | COVERED | 4.1 |
| The 11-month answer is state-relative | COVERED | 4.7 |
| Cost and something-goes-wrong questions exist | COVERED | 4.7 |
| The e-signature answer is not an unconditional yes | COVERED | 4.7 |
| Visible FAQ equals the JSON-LD | COVERED | 4.1 |
| Forbidden phrases are absent | COVERED | 4.8 (page), 3.3 (disclaimer) |
| Disclaimer order and content | COVERED | 3.3 |
| The preview notice matches | COVERED | 3.4; YAML parse + bind proven by 3.5 / 6.2 |
| All three CTAs start the builder | COVERED | 4.1 |
| Every contact address is the constant | COVERED | 4.1 |
| Route from `/` still reaches the picker | COVERED | 4.9 |
