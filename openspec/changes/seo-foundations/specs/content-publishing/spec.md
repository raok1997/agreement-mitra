## Purpose

Defines how the site publishes periodic editorial content about rental and
property matters: how an article is authored once and rendered to a crawlable
page, how legal assertions are held to a recorded review before they go live, and
how a build gate rather than a manual step proves the published output matches its
source.

## ADDED Requirements

### Requirement: An article is authored once and published from that single source

Publishing an article SHALL require adding or editing exactly one authored content
source, from which the system derives the article's rendered page, its metadata,
its structured data, and its entries in the site's discovery artifacts and article
index. Publishing SHALL NOT require hand-editing generated HTML, the sitemap, or
an index page.

Content has a recurring cadence, so the cost of publishing the twentieth article
determines whether the channel actually gets used.

#### Scenario: Adding an article requires one source edit
- **WHEN** an author adds one article source and runs the build
- **THEN** the article's page, its metadata, its structured data, its sitemap
  entry and its listing on the article index all appear, with no other file edited
  by hand

#### Scenario: Editing an article updates every derived surface
- **WHEN** an existing article's title or body is changed at its source
- **THEN** the rendered page, its metadata and its index entry all reflect the
  change after the next build

### Requirement: The build derives published output from source and fails rather than publishing stale content

The published output SHALL be derived from the authored sources by the build
itself, and the build SHALL fail rather than emit output that does not correspond
to the current sources. Article rendering and its validation SHALL run inside an
existing build or test chain, not as a separate command an author must remember to
invoke.

The project has already been bitten by the opposite arrangement: the terms-of-
service generator is invoked by no gate, so when it silently broke, nothing failed.
A generator that someone must remember to run is not a gate.

#### Scenario: A source change reaches the built output
- **WHEN** an article source is edited and the site is built
- **THEN** the built output reflects the edit, with no separate regeneration step
  run by hand

#### Scenario: A broken renderer fails the build
- **WHEN** the article rendering step cannot run or throws
- **THEN** the build exits non-zero rather than completing with previously
  generated or partial output

#### Scenario: An article missing required metadata fails the build
- **WHEN** an article source omits a required field such as its title,
  description, publication date or review declaration
- **THEN** the build fails and names the offending article and field

### Requirement: An article asserting legal facts SHALL NOT publish without recorded review

Each article SHALL carry a declared review status. An article that makes legal
assertions -- statements of stamp duty rates, registration obligations, statutory
thresholds, or the legal effect of an instrument -- SHALL NOT be published unless
its source records a completed review bound to the reviewed content, and the build
SHALL fail on an article that claims legal content without that record.

This is identity and legal infrastructure, and the same figures an article would
quote are recorded as unverified and blocking on the paid product. Publishing them
as editorial advice ahead of that review would assert publicly what the product
itself refuses to charge for.

#### Scenario: Unreviewed legal article fails the build
- **WHEN** an article declares that it contains legal assertions and carries no
  completed review record
- **THEN** the build fails and names the article

#### Scenario: Review record is bound to the reviewed content
- **WHEN** an article's body is changed after its review was recorded
- **THEN** the recorded review no longer matches the content and the build fails,
  rather than the edited text inheriting the earlier approval

#### Scenario: Non-legal content publishes without a review record
- **WHEN** an article declares that it makes no legal assertions
- **THEN** it publishes without requiring a review record

### Requirement: Article pages carry article structured data and publication dates

Each published article page SHALL emit structured data identifying it as an
article, including its headline, its publication date, its last-modified date when
it differs, and its publisher, and the dates emitted SHALL be the same dates shown
to a reader on the page.

Editorial content about rules that change by state and by year is only useful if a
reader and a crawler can both tell how current it is.

#### Scenario: Article declares its dates
- **WHEN** an article page is served
- **THEN** it emits article structured data with a publication date, and the same
  date is visible on the rendered page

#### Scenario: A revised article shows it was revised
- **WHEN** a published article is materially edited
- **THEN** its page and its structured data both carry a last-modified date later
  than its publication date

### Requirement: Published article URLs are stable

An article's URL SHALL be derived from its source and SHALL remain stable across
rebuilds. When an article's URL changes or an article is withdrawn, the system
SHALL serve a redirect from the previous URL to its replacement, or to the article
index when there is none.

Accumulated ranking and inbound links attach to a URL; silently changing or
dropping one discards the entire return on the article.

#### Scenario: Rebuilding does not move an article
- **WHEN** the site is rebuilt with no change to an article's source
- **THEN** the article is served at the same URL as before

#### Scenario: A withdrawn article does not dead-end
- **WHEN** a previously published article is withdrawn
- **THEN** its former URL redirects to a replacement article or to the article
  index rather than returning a not-found page

### Requirement: Published articles are discoverable from the site

The site SHALL provide a crawlable index page listing published articles, each
linking to its article page, and each published article page SHALL link back into
the site. Unpublished or draft articles SHALL NOT appear in the index, the
sitemap, or the built output.

#### Scenario: A published article is reachable by crawling
- **WHEN** a crawler starts at the site root
- **THEN** it can reach every published article by following links, without
  needing the sitemap

#### Scenario: A draft article is absent from the built site
- **WHEN** an article source is marked as draft
- **THEN** no page, index entry or sitemap entry for it exists in the built output
