## Context

See `proposal.md` -- Why. The constraints that shape the approach:

- **One HTML document, no router.** `App.vue` resolves a route from
  `window.location.pathname` by hand. That was a deliberate call (its flow-journal
  8.3: a router is more OSV surface than three paths are worth) and this change
  should not quietly reverse it to solve a marketing problem.
- **Static hosting.** The frontend deploys to Cloudflare Pages; `public/_redirects`
  rewrites `/*` to `index.html` and `public/_headers` sets caching and security
  headers. There is no Node server in front of the site, so server-side rendering
  is not available without introducing one.
- **Fail-on-any dependency scanning.** `npm run security:scan` covers the **whole**
  lockfile including devDependencies and transitives, and fails on any unsuppressed
  finding. Every dependency added here is a permanent tax on that gate, so
  transitive-dependency count is a first-class selection criterion, not a detail.
- **Two in-repo precedents to follow rather than reinvent.**
  `scripts/render-terms.mjs` establishes how to render typed content through Vite
  without a second copy of the content, and documents four load-bearing options
  that were each added after a real failure. `template-definition` and the `rules`
  counsel review establish how authored YAML is compiled, content-hashed, and bound
  to a recorded review.

## Goals / Non-Goals

**Goals:**

- Per-page head metadata and structured data present in the served HTML, without
  adding a routing library or a runtime dependency.
- An authoring path where publishing article number twenty costs the same as
  article number one.
- Gates that fail on their own, rather than steps a person must remember.

**Non-Goals:**

- **Prerendering the landing page body.** This change makes every page's *metadata*
  static; it does not make the landing page's *body* static. That needs a
  prerenderer and is deliberately deferred (see D6 and Risks).
- Migrating the application to a meta-framework.
- Writing the article copy, or the Google Business Profile / Search Console /
  analytics setup. Those are go-to-market execution, not site capability.

## Decisions

### D1: A static multi-page Vite build, not a router and not a framework

Vite takes explicit per-page inputs (`build.rollupOptions.input`) so each public
page is emitted as its own HTML file with its own `<head>`.

*Alternatives considered.* **vue-router plus a prerender plugin** reverses a
documented decision and adds runtime surface to solve a build-time problem.
**Migrating to Nuxt or Astro** is the textbook answer for a content-plus-app site
and would give prerendering for free -- but it is a rewrite of a working, tested
application, and Astro in particular would mean either two build systems or
porting every view. Neither is justified by a metadata problem. **Rendering the
marketing pages from Spring Boot** puts marketing content in the backend, crosses
the module boundary the architecture exists to protect, and couples a static site's
availability to the API's.

The cost of D1 is that it is a build-config feature, not a framework feature: page
registration is explicit, and adding a page means adding an input. The article
pipeline (D2) generates its inputs, so that cost lands only on the handful of
hand-built pages.

### D2: Articles are Markdown with YAML frontmatter, one file per article

An article is one file: YAML frontmatter carrying slug, title, description,
publication and revision dates, and the review declaration (D3); Markdown below it
for the body.

*Alternatives considered.* **Typed TypeScript modules**, mirroring
`src/content/termsOfService.ts`, would give compile-time checking of the metadata
-- but the terms are one legal document edited rarely by an engineer, whereas
articles are recurring prose that should not require touching TypeScript. **MDX**
buys component embedding nobody has asked for, at the cost of a large dependency
tree against a fail-on-any scan. **A headless CMS** adds an external service,
a network dependency in the build, and a second place where published legal claims
could originate outside review -- the opposite of D3.

Frontmatter loses compile-time validation, so validation becomes explicit and
build-failing (see the spec's required-metadata scenario). That is the same trade
`template-definition` already makes: authored in YAML, validated on load.

### D3: Review is bound by content hash, exactly as the rules engine binds counsel review

An article's frontmatter declares whether it makes legal assertions. If it does, it
must carry a review record containing a SHA-256 hash of the article body, and the
build fails unless that hash matches the body as it stands.

This is not a new mechanism. `rules` already gates paid fulfilment on a counsel
review "matching its hash", and `template-definition` pins
`(id, version, contentHash)` so that "was this content reviewed?" stays answerable
after an edit. Reusing the idiom means an article edited after approval loses its
approval automatically, which is the only property that makes a review record worth
anything.

*Alternative considered.* A simple `reviewed: true` boolean is one line of code and
survives exactly one careless edit, which is the failure mode
`template-counsel-signoff-gate` exists to complain about.

### D4: Markdown renderer chosen for transitive-dependency count

Select a renderer with **zero runtime dependencies** (`marked` is the leading
candidate) over a more configurable one with a transitive tree (`markdown-it`
pulls several). Under a whole-lockfile fail-on-any gate, every transitive package
is a future build break that somebody has to triage.

Raw HTML passthrough in Markdown SHALL be disabled. Article sources are
first-party and in-repo, so this is not a sanitization boundary in the untrusted-
input sense -- but disabling it keeps the rendered output predictable and means a
stray tag in prose cannot break a page's structure.

### D5: Discovery artifacts are generated from the built page set

`sitemap.xml` is emitted by the build from the pages it actually produced, with
`lastmod` taken from article frontmatter. `robots.txt` stays hand-written: its
`Disallow` rules are a security control (D7) and should be reviewed by a person,
not computed.

This is the asymmetry that matters: the *allow* list is generated so it cannot go
stale, and the *deny* list is hand-kept so it cannot be silently widened by a code
change.

### D6: Metadata goes static now; body prerendering is deferred

Every page gets a static `<head>`. Article pages are additionally static in the
body, because they are generated HTML with no interactivity. The landing page and
terms page keep client-rendered bodies for now: each gets its own entry HTML with
correct metadata, and continues to mount the existing Vue view.

This is a deliberate partial answer. Google renders JavaScript, so the landing body
is indexable today; link-preview and answer-engine clients frequently do not, which
is why the *metadata* half is the urgent half and is what this change fixes. Full
prerendering of the application-backed pages is a follow-up with its own dependency
(a headless renderer in the build), and folding it in here would make a metadata fix
contingent on standing up a prerenderer.

### D7: The security properties of the current single document must be copied, not inherited

Splitting one HTML file into several silently drops whatever the original carried.
Two items are load-bearing and become shared, tested scaffolding rather than text
repeated per page:

- `<meta name="referrer" content="same-origin">`, which exists because the emailed
  recovery link carries an agreement id -- a bearer capability -- in its path.
- The `robots.txt` `Disallow` prefixes, plus the new rule that the generated
  sitemap may never enumerate a capability-bearing URL.

`_headers` must also grow a rule covering generated HTML, or article pages inherit
asset-style immutable caching and an edit never reaches returning readers.

## Risks / Trade-offs

- **Cloudflare Pages may not resolve extensionless URLs to the new files as
  assumed.** The design relies on static assets being matched *before* the
  `/* -> /index.html 200` fallback is evaluated, and on `/terms` resolving to
  `terms.html`. If that ordering is wrong, the SPA fallback shadows every new page
  and the change silently does nothing. -> Verify on a preview deployment before
  any other task is considered done; this is the first implementation task, not the
  last. Rollback is deleting the added inputs, since the fallback restores the
  current behavior.
- **`/terms` gets a second definition.** It will exist both as a built page and as
  a branch in `App.vue`'s `routeFor`. Two sources of truth for one URL is exactly
  the kind of drift this repo has been bitten by. -> The entry HTML mounts the same
  Vue view rather than duplicating its content, so the page has one body and two
  wrappers, not two bodies.
- **Publishing legal content is a new liability surface.** D3 gates it, but a gate
  only checks that a review was recorded, not that it was competent. -> The review
  record names a reviewer; and the stamp-duty figures an article would most want to
  quote are the ones `tg-stamp-duty-counsel-review` already blocks. Sequence
  product and process articles ahead of duty-rate articles.
- **A new devDependency against a fail-on-any scanner.** -> D4 minimizes the tree;
  the lockfile is regenerated and scanned as part of the change, not after it.
- **Self-hosting fonts moves a maintenance burden in-house** (subsetting, updates)
  in exchange for LCP and one fewer third-party origin. -> Acceptable; the
  Devanagari family can simply be dropped until vernacular rendering actually
  ships, which is the larger win and costs nothing.
- **Articles are a cadence commitment, not a build artifact.** The mechanism is
  cheap; the editorial habit is what actually produces ranking, and nothing in this
  change creates it. -> Out of scope here, but it is the real dependency, and it
  belongs in the go-to-market plan rather than in a CR.

## Migration Plan

The change is additive to a static site and carries no data migration.

1. Verify asset-before-fallback resolution on a Cloudflare Pages preview
   deployment (see Risks) before building on the assumption.
2. Land the metadata split and the social card. At this point `/terms` stops
   canonicalizing to `/`, which is a correction of live behavior.
3. Land the article pipeline with one worked example article.
4. Regenerate `package-lock.json` and confirm `npm run security:scan` passes.

**Rollback:** revert the Vite inputs and the generated discovery artifacts; the
`/*` SPA fallback restores today's single-document behavior with no state to undo.
Search-engine effects of step 2 are reversible but slow to re-propagate, so it
should ship only once step 1 has actually been verified.

## Open Questions

- **Where do articles live in the URL space** -- `/guides/<slug>`, `/blog/<slug>`
  or bare `/<slug>`? Stability matters more than the choice (the spec requires a
  stable URL and redirects on change), so this can be settled at implementation
  without reopening the specs or the task breakdown. Recommendation: a prefixed
  path, so the article index has an obvious home and the bare namespace stays free
  for state landing pages.
- **Which sans-serif subset to self-host**, and whether to keep Google Fonts for
  the app surface while self-hosting for the marketing surface. Performance detail;
  does not affect the specs.
