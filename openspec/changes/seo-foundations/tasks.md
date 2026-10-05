Article URL space is settled as `/guides/<slug>` per design.md Open Questions
(prefixed path; keeps the bare namespace free for state landing pages). Change it
before task 3.1 if you disagree -- after publication it costs a redirect.

## 1. Verify the hosting assumption before building on it

- [ ] 1.1 Deploy a throwaway preview to Cloudflare Pages containing one extra
      static HTML file, and confirm it is served rather than shadowed by the
      `/* -> /index.html 200` fallback in `public/_redirects`
- [ ] 1.2 Confirm extensionless resolution: `/terms` serves `terms.html`, and
      record the observed behavior in `docs/DEPLOYMENT.md`
- [ ] 1.3 If either assumption fails, stop and revisit design D1 before
      continuing -- the rest of this change depends on it

## 2. Per-page metadata and the social card

- [ ] 2.1 Extract the shared head scaffolding (charset, viewport, referrer policy,
      theme color, font links) into one place both hand-built pages and generated
      article pages consume, so a protection cannot be dropped from a copy (D7)
- [ ] 2.2 Add per-page inputs to `frontend/vite.config.ts` for the landing page and
      the terms page; each entry HTML mounts the existing Vue view rather than
      duplicating its body
- [ ] 2.3 Give each page its own title, description and self-referencing canonical;
      remove the hardcoded homepage canonical from the shared head
- [ ] 2.4 Design and add the `og:image` social card asset; declare absolute
      `og:image` plus dimensions on every page
- [ ] 2.5 Move the `FAQPage` structured data onto the landing page only, generating
      it from the same `faqs` source `LandingPage.vue` renders
- [ ] 2.6 Extend `Organization` structured data with postal address, contact and
      `legalName`, reading entity facts from one config source (coordinate with
      the `operating-entity-disclosure` register row rather than retyping them)
- [ ] 2.7 Add a `_headers` rule so generated HTML gets
      `max-age=0, must-revalidate` rather than inheriting immutable asset caching

## 3. Article pipeline

- [ ] 3.1 Define the article source format: one Markdown file per article with YAML
      frontmatter (slug, title, description, published, updated, review
      declaration), under a content directory alongside `src/content/`
- [ ] 3.2 Add the Markdown renderer as a devDependency, selecting for zero
      transitive dependencies (D4), with raw HTML passthrough disabled
- [ ] 3.3 Write the frontmatter validator: fail the build naming the article and
      the offending field when a required field is missing or malformed
- [ ] 3.4 Implement the content-hash review binding (D3): an article declaring
      legal assertions must carry a review record whose SHA-256 matches its body,
      mirroring the `rules` counsel-review idiom
- [ ] 3.5 Write the render script (following `scripts/render-legal-docs.mjs` --
      reuse its documented Vite options, they are each load-bearing) emitting one
      static HTML page per article with its own head, canonical and `Article`
      structured data
- [ ] 3.6 Build the crawlable article index page listing published articles
- [ ] 3.7 Exclude draft articles from the built output, the index and the sitemap
- [ ] 3.8 Chain rendering and validation into `npm run build` alongside the
      existing `security:scan && test` chain, so no step depends on being
      remembered
- [ ] 3.9 Write one worked example article (a non-legal process explainer, so it
      needs no review record) to prove the path end to end

## 4. Discovery artifacts

- [ ] 4.1 Generate `sitemap.xml` from the built page set with `lastmod`, replacing
      the hand-maintained file
- [ ] 4.2 Assert in the generator that no capability-bearing URL (agreement id,
      recovery token) and no `robots.txt`-disallowed prefix can enter the sitemap
- [ ] 4.3 Keep `robots.txt` hand-written (D5) and update its comment to describe
      the generated sitemap and the article paths
- [ ] 4.4 Give `/privacy`, `/refunds` and `/contact` (added by `legal-policy-pages`) the
      same per-route treatment planned for `/terms`: title, metadata, prerendered entry and
      sitemap entry

## 5. Performance

- [ ] 5.1 Drop the Noto Sans Devanagari family until vernacular rendering ships
- [ ] 5.2 Self-host the remaining font subset, or make it non-render-blocking;
      measure LCP before and after and record the numbers

## 6. Unit tests

- [ ] 6.1 Frontmatter validator: missing required field, malformed date, unknown
      field, valid article
- [ ] 6.2 Review binding: unreviewed legal article rejected; review hash not
      matching an edited body rejected; non-legal article accepted without a record
- [ ] 6.3 Markdown rendering: headings and links render; raw HTML in source does
      not pass through
- [ ] 6.4 Sitemap generator: excludes drafts, excludes disallowed prefixes,
      rejects a capability-bearing URL, emits `lastmod`
- [ ] 6.5 FAQ structured data is generated from the same source the page renders,
      extending the existing `LandingPage.test.ts` parity assertion

## 7. Integration tests (against real built output)

- [ ] 7.1 Build the site, then assert over the emitted files: every public page has
      a unique title and description, and a canonical equal to its own URL
- [ ] 7.2 Assert every emitted page carries the `same-origin` referrer meta and a
      complete preview card including an `og:image` that exists in the output
- [ ] 7.3 Assert `FAQPage` markup appears on the landing page and on no other page
- [ ] 7.4 Assert every sitemap URL corresponds to an emitted page that
      canonicalizes to it, and that no sitemap URL matches a `Disallow` prefix
- [ ] 7.5 Assert an article page carries its body text, headings and internal
      links in the served HTML without script execution
- [ ] 7.6 Assert a deliberately stale or broken article source fails the build
      non-zero rather than emitting partial output

## 8. Close-out

- [ ] 8.1 Regenerate `frontend/package-lock.json` and confirm
      `npm run security:scan` passes with an empty suppression baseline
- [ ] 8.2 Run `npm run build` and `npm run lint`; report the wall-clock time of the
      build so the trend stays visible
- [ ] 8.3 Manual test: deploy to a preview, share the URL into WhatsApp and
      LinkedIn, and confirm the card renders with image, title and description
- [ ] 8.4 Manual test: fetch `/terms` and one article with scripts disabled and
      confirm the canonical and content are correct
- [ ] 8.5 Record follow-ups in the `docs/ROADMAP.md` follow-up register before
      archiving -- at minimum: prerendering the landing page body (design D6), and
      the editorial cadence itself, which no CR can create
- [ ] 8.6 Submit the sitemap in Google Search Console (owner action, outside this
      repo -- note it in the register if not yet possible)
