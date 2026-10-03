## Why

`docs/GO-TO-MARKET-HYDERABAD.md` makes organic search the **first** of three
channels and the only one called an "Engine": paid is structurally unavailable
(MyGate's ad minimum is cited at ~INR 50,000+, more than the entire INR 25,000
monthly budget), so SEO plus content is what the plan actually rests on. It also
says start now *because* it is slow -- 3-6 months to measurable results -- and its
Month 1 line is "Ship trust-heavy landing pages + the 4 SEO articles".

**The site cannot execute that plan, and two parts of it are already working
against us.**

The frontend is a single `index.html` with a hand-rolled path switch in `App.vue`
(deliberately router-less; see its flow-journal 8.3 -- a router was judged more OSV
surface than three paths were worth), and `public/_redirects` rewrites `/*` to that
one file. Every consequence follows from that:

- **`/terms` is de-indexing itself.** `public/sitemap.xml` submits it as a page,
  while the file it is served declares `<link rel="canonical"
  href="https://agreementmitra.com/">`. Two checked-in files assert opposite
  things; the canonical wins, and the page is folded into the homepage. The same
  shared head means title, description and `og:url` also describe the landing page
  on every URL.
- **Every shared link renders a blank card.** `twitter:card` is
  `summary_large_image` and there is no `og:image` anywhere in the file. The GTM
  plan's ground game is RWA societies and WhatsApp referral loops -- channels that
  are *entirely* link previews.
- **There is no way to publish an article at all.** No second page can carry its
  own title, description, canonical or schema, so the four named articles and the
  planned state-specific landing pages have nothing to ship into. The sitemap's own
  comment already records this as pending ("state-specific landing pages are the
  planned SEO expansion").

Content is the part that decides ranking and it is the part with a cadence, so the
mechanism has to survive being used repeatedly by a non-specialist -- not be a
one-off hand-edit of HTML per article.

## What Changes

- **Per-URL head metadata becomes real.** Each public page is built as its own
  static HTML file carrying its own `<title>`, description, self-referencing
  canonical, `og:*` and `twitter:*`. This removes the sitemap/canonical
  contradiction on `/terms` rather than papering over it.
- **A static multi-page build.** Vite gains explicit per-page inputs so marketing
  pages are real files on disk, fully crawlable with JavaScript disabled. **This
  preserves the router-less decision** rather than reversing it: no routing
  library, no new runtime dependency, no added scan surface. It also stops the
  marketing page from shipping the whole 188 KB application bundle.
- **An article publishing pipeline for periodic rental/property content.**
  Articles are authored as typed source and rendered to static pages by a build
  script -- the shape `scripts/render-terms.mjs` already establishes for the terms
  of service. Adding an article means adding one content file, not editing HTML,
  the sitemap, and the index by hand.
- **The gate is a test, not the script.** `render-terms.mjs` documents this
  discipline in its own header, and the `terms-doc-ungated` register row records
  what happens without it: that generator silently broke when vitest 4 dropped
  `vite-node` and nothing failed. The article pipeline ships with its gate wired
  into an existing build chain from the start.
- **Legal assertions in articles are review-gated.** An article saying what stamp
  duty costs in Telangana is a legal claim published by a company selling legal
  documents -- and `tg-stamp-duty-counsel-review` records that those exact figures
  are still **unverified** and block charging real customers. Articles therefore
  carry a review status and the build refuses to publish an unreviewed legal
  assertion, mirroring the `template-counsel-signoff-gate` intent. Non-legal
  content (how the product works, process explainers) is unaffected.
- **Structured data is corrected and scoped.** `FAQPage` moves to being emitted
  only on the page that actually shows the FAQ -- today it is served on `/start`
  and `/terms` too, which is the visible-content mismatch the `index.html`
  maintenance comment warns against. `Organization` gains a postal address and
  legal name; articles emit `Article`/`BlogPosting` with dates and author.
- **Discovery artifacts are generated, not hand-kept.** `sitemap.xml` is emitted
  from the built page set with `lastmod`, so it cannot drift from what exists.
  `robots.txt` keeps its `Disallow` rules, which are load-bearing: `/start` URLs
  carry agreement ids, and an agreement id is a bearer capability.
- **Font cost is reduced.** Two render-blocking Google Fonts families load from a
  third-party origin, including Noto Sans Devanagari for a script the site does
  not yet render. Self-host a subset, or defer it, to recover mobile LCP.

**Not in scope:** the article *copy* itself (this change ships the mechanism and
one worked example), Google Business Profile setup, analytics/Search Console
provisioning, and any paid-search work. The `operating-entity-disclosure` register
row supplies the legal entity facts the `Organization` address needs; until it
closes, that block ships with what is known and is completed by that change.

## Capabilities

### New Capabilities
- `marketing-site-metadata`: what the public, crawlable surface must assert about
  itself -- per-page head metadata and a self-referencing canonical, social-card
  completeness, structured data that matches visible page content, and the
  robots/sitemap discovery contract including the rule that no capability-bearing
  URL is ever enumerated.
- `content-publishing`: how periodic articles are authored, review-gated,
  rendered to crawlable static pages, and indexed -- including the requirement
  that a build gate, not a manual step, is what proves the published output
  matches its source.

### Modified Capabilities

None. No existing spec under `openspec/specs/` covers the public marketing
surface; searching for landing/marketing/robots/sitemap/canonical returns only
unrelated uses (recovery referrer policy, canonical template hashing, canonical
signer).

## Impact

- **Frontend build**: `frontend/vite.config.ts` (multi-page inputs),
  `frontend/package.json` (article render + gate scripts chained into `build`,
  alongside the existing `security:scan && test` chain).
- **Public surface**: `frontend/index.html` (head split per page),
  `frontend/public/sitemap.xml` and `robots.txt` (generated), a new social card
  asset, `frontend/public/_redirects` -- **the SPA fallback must not shadow real
  files**, which is the one genuinely delicate interaction in this change.
- **New source**: an articles content directory plus its render script and tests,
  following `src/content/termsOfService.ts` + `scripts/render-terms.mjs`.
- **Existing views**: `LandingPage.vue` keeps the FAQ as the single source its
  structured data is generated from, so the `LandingPage.test.ts` parity assertion
  is strengthened rather than dropped.
- **Backend**: none. No API, module, migration or `SignatureStatus` transition is
  touched; `ModularityTests` is unaffected.
- **Dependencies**: a markdown renderer may be added as a **devDependency**. Any
  addition must pass `npm run security:scan` (whole-lockfile, fail-on-any) and
  requires regenerating `package-lock.json`.
- **Hosting**: Cloudflare Pages serves more static files; `_headers` caching rules
  need to cover generated HTML the same way `index.html` is covered today
  (`max-age=0, must-revalidate`), or a published article edit will not reach
  returning visitors.

## PII / security review

**This change introduces no PII flow.** It touches only the public,
unauthenticated marketing surface, which holds no customer data: no Aadhaar
number, OTP, VID, signer PII or secret is read, written, logged or transmitted,
and no backend code or credential is involved. Sandbox + dummy data only is
preserved -- the change adds no data at all.

Two existing protections are in scope and must be preserved rather than assumed:

1. **`robots.txt` must keep disallowing `/start` and `/auth/`, and the generated
   sitemap must never enumerate a URL bearing an agreement id.** Agreement ids are
   bearer capabilities; a generated sitemap is exactly the kind of automation that
   could start listing them. This becomes an explicit requirement with a scenario,
   not a convention.
2. **The `<meta name="referrer" content="same-origin">` rule must be carried onto
   every new page.** It exists because the emailed recovery link carries an
   agreement id in its path, and splitting one `index.html` into several is
   precisely how a protection like that gets dropped from the copies.

A third-party font origin is *removed* from the critical path if the self-hosting
option is taken, which narrows the outbound request surface slightly.

**Signing status FSM:** no transition is touched. This change does not reach the
`signing` module.
