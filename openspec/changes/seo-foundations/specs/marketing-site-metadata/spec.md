## Purpose

Defines what the public, crawlable surface of the site must assert about itself to
search engines and link-preview clients: per-page identity metadata, complete
social cards, structured data that matches what a visitor actually sees, and a
discovery contract that never exposes a capability-bearing URL.

## ADDED Requirements

### Requirement: Every public page declares its own identity metadata

Each publicly crawlable page SHALL be served HTML carrying metadata describing
**that page**: a unique `<title>`, a unique meta description, and a `<link
rel="canonical">` whose value is the page's own absolute URL. No page SHALL
declare a canonical pointing at a different page unless that page is deliberately
a duplicate being consolidated.

This exists because a single-document SPA serves one shared head to every route,
which makes every page claim to be the homepage.

#### Scenario: A secondary page canonicalizes to itself
- **WHEN** a crawler requests `/terms`
- **THEN** the returned HTML declares `<link rel="canonical">` with the absolute
  URL of `/terms`, and a title and description describing the terms of service --
  not the homepage's

#### Scenario: Homepage keeps its own canonical
- **WHEN** a crawler requests `/`
- **THEN** the returned HTML canonicalizes to the site root

#### Scenario: No two public pages share a title and description
- **WHEN** the built output is inspected across all publicly crawlable pages
- **THEN** each page's title and meta description are distinct from every other
  page's

### Requirement: Discovery artifacts agree with the built page set

The sitemap SHALL be generated from the set of pages actually produced by the
build, and every URL it lists SHALL return a page that canonicalizes to that same
URL. A page listed in the sitemap SHALL NOT be excluded by `robots.txt`, and a
page excluded by `robots.txt` SHALL NOT appear in the sitemap.

A hand-maintained sitemap drifts from the pages that exist; today it submits a URL
whose own canonical contradicts it.

#### Scenario: Sitemap and canonical cannot contradict each other
- **WHEN** the sitemap lists a URL
- **THEN** the page served at that URL declares that same URL as its canonical

#### Scenario: A new page reaches the sitemap without a manual edit
- **WHEN** a new public page is added to the build
- **THEN** the generated sitemap includes it, with a last-modified date, on the
  next build -- with no hand edit to the sitemap

#### Scenario: Disallowed paths are absent from the sitemap
- **WHEN** the sitemap is generated
- **THEN** no URL under a `robots.txt` `Disallow` prefix appears in it

### Requirement: Discovery never enumerates a capability-bearing URL

The generated sitemap SHALL contain only URLs that are safe to publish to the
world, and SHALL NOT contain any URL embedding an agreement identifier, a recovery
token, or any other bearer capability. `robots.txt` SHALL continue to disallow the
application paths that can carry such identifiers.

An agreement id in a URL path is a bearer capability: possession of the link is
access. Automating sitemap generation is exactly the mechanism by which such a URL
could begin being published, so the prohibition is a requirement rather than a
convention.

#### Scenario: Generated sitemap excludes application routes
- **WHEN** the sitemap is generated
- **THEN** it contains no URL under the agreement builder, authentication,
  recovery, or agreement-link paths

#### Scenario: Application paths stay disallowed
- **WHEN** `robots.txt` is served
- **THEN** it disallows the agreement builder and authentication path prefixes

### Requirement: Every public page carries the same referrer protection

Every publicly served page SHALL declare a referrer policy no weaker than
`same-origin`, so that following an outbound link never transmits the current URL
to a third-party origin.

The emailed recovery link carries an agreement id in its path, and that id is a
bearer capability. Splitting one shared document into several per-page documents
is precisely the operation that drops such a protection from the copies.

#### Scenario: A newly added page inherits the referrer policy
- **WHEN** any public page is served, including a page added after this change
- **THEN** its HTML declares a referrer policy of `same-origin` or stricter

### Requirement: Shared links render a complete preview card

Every publicly crawlable page SHALL declare a complete set of link-preview
metadata: title, description, canonical URL, site name, locale, and **an image**.
A page SHALL NOT declare a large-image card type without declaring an image to
fill it.

The product's distribution plan runs through WhatsApp groups and society
referrals, which are link previews end to end; the card is the first impression,
not a decoration.

#### Scenario: Large-image card declares an image
- **WHEN** a page declares a `summary_large_image` card type
- **THEN** it also declares an absolute image URL, and the image exists in the
  built output

#### Scenario: A page's preview describes that page
- **WHEN** a link-preview client fetches any public page
- **THEN** the preview title, description and URL describe that page rather than
  the homepage

### Requirement: Structured data matches visible page content

Structured data SHALL be emitted only on pages whose visible content it describes.
FAQ markup SHALL appear only on a page that visibly renders those same questions
and answers, and its questions and answers SHALL be derived from the same source
as the rendered content so the two cannot drift.

Search engines devalue -- and may penalize -- structured data asserting content a
visitor cannot see. Today the FAQ block is served on every URL, including the
application and the terms page, neither of which shows an FAQ.

#### Scenario: FAQ markup is absent where no FAQ is shown
- **WHEN** a page that does not render the FAQ is served
- **THEN** its HTML contains no FAQ structured data

#### Scenario: FAQ markup and rendered FAQ share one source
- **WHEN** an FAQ entry's text is changed
- **THEN** both the rendered page and the structured data change together, and a
  test fails if they diverge

### Requirement: The organization is identified by its legal identity

Organization structured data SHALL identify the operating entity, including its
legal name where it differs from the brand name, and its postal address and
contact details.

The operating legal entity has to be disclosed on the site for payment-gateway
onboarding and consumer-protection display rules regardless of search concerns;
the structured data and the visible disclosure SHALL name the same entity.

#### Scenario: Structured data and visible disclosure agree
- **WHEN** the site declares an operating entity in structured data
- **THEN** the same legal name appears in the site's visible entity disclosure

#### Scenario: Address is present for local search
- **WHEN** organization structured data is emitted
- **THEN** it carries a postal address and a contact email

### Requirement: Editorial pages are legible without client-side scripting

Every published editorial page -- an article page and the article index -- SHALL
deliver its primary content in the initially served HTML: its headings, body copy,
and links to other public pages, readable without executing JavaScript. Head
metadata, canonical and structured data SHALL likewise be present in the served
HTML on **every** public page, including ones whose body is client-rendered.

Editorial pages are the surface being ranked and the surface that link-preview and
answer-engine clients fetch, and many of those clients do not execute scripts at
all. The application surface is excluded because it is not publicly crawlable.

#### Scenario: Article content is present with scripting disabled
- **WHEN** an article page or the article index is fetched and its scripts are not
  executed
- **THEN** the headings, body text and internal links are present in the response
  body

#### Scenario: Metadata is static even where the body is not
- **WHEN** a page whose body is client-rendered is fetched without executing
  scripts
- **THEN** its title, description, canonical, preview-card metadata and structured
  data are still present in the response body

#### Scenario: The application surface is unaffected
- **WHEN** the agreement builder is loaded
- **THEN** it continues to render as a client-side application
