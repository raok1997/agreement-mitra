# legal-policy-pages Specification

## Purpose
TBD - created by archiving change legal-policy-pages. Update Purpose after archive.
## Requirements
### Requirement: Policy documents share one clause model with stable ids
Every policy document SHALL be held as data: a list of clauses of one shared `Clause` type. Each clause carries:

- a stable `id`;
- a heading;
- plain-text body paragraphs;
- a status of `drafted`, `counsel` or `product`;
- for a non-`drafted` clause, a gap note saying what is missing and who owes it.

Clause ids SHALL be unique within a document. Code and tests that refer to a specific clause SHALL find it by `id`, not by its number or heading text. A clause rendered outside its own document SHALL be resolved by id when the content module loads, so a missing id fails on import.

#### Scenario: Ids are unique
- **WHEN** the frontend test suite runs
- **THEN** no two clauses of the terms of service share an `id`, and no two clauses of the privacy policy share an `id`

#### Scenario: Every non-drafted clause explains its gap
- **WHEN** the frontend test suite runs
- **THEN** every clause of every policy document whose status is `counsel` or `product` has a non-empty gap note

#### Scenario: Unknown id fails loudly
- **WHEN** `clauseById` is called with an id the document does not contain
- **THEN** it throws an error naming that id

### Requirement: Each policy text has exactly one source
A piece of policy text SHALL live in exactly one document module. A page that shows text owned by another document SHALL render that document's clause object itself, not a copy of its wording. In particular:

- the refund/cancellation text SHALL be terms-of-service clause `refunds` (§11);
- the privacy text SHALL be the privacy policy. Terms-of-service §15 SHALL keep its heading and `counsel` status, and its body SHALL be one paragraph pointing to `/privacy`;
- the support email, support hours, operator details and every banner string SHALL come from a content module, not from a view template.

The literal support email in terms-of-service §19 SHALL be pinned equal to `CONTACT_EMAIL` by a test.

#### Scenario: Refund page renders the terms clause
- **WHEN** `/refunds` renders
- **THEN** it shows the heading, every body paragraph and the gap note of the terms-of-service clause with id `refunds`, and no refund paragraph that clause does not contain

#### Scenario: Terms §15 is a pointer
- **WHEN** the frontend test suite runs
- **THEN** terms-of-service clause `personal-data` has the heading "15. Your personal data", status `counsel`, exactly one body paragraph referring to `/privacy`, and no body paragraph shared with the privacy policy

#### Scenario: Contact email agrees with the terms
- **WHEN** the frontend test suite runs
- **THEN** terms-of-service clause `contact` contains `CONTACT_EMAIL`

### Requirement: The privacy policy names the data fiduciary and marks what counsel owes
The privacy policy SHALL name KAVISAT TEK LABS LLP as the data fiduciary in plain text. It SHALL draft only what the system verifiably does today:

- the personal data collected;
- what is not held (Aadhaar numbers, virtual IDs and Aadhaar one-time passwords);
- the record kept when a draft is deleted;
- the roles of the service providers that receive data;
- the cookies the site sets and the data it keeps in browser storage.

The following SHALL be clauses with status `counsel` and a gap note, and SHALL NOT be drafted by us:

- the purposes and lawful basis of processing;
- the retention period;
- data principal rights and how to exercise them;
- the grievance contact;
- cross-border transfer.

The retention clause SHALL refer to the terms of service in words and SHALL NOT state a period. Service providers SHALL be described by role, not by vendor name. The collected-data categories and the recipient roles SHALL each be an exported list, and a test SHALL hold the clause text to it.

#### Scenario: Fiduciary is named
- **WHEN** the frontend test suite runs
- **THEN** a drafted privacy clause contains `KAVISAT TEK LABS LLP` and the phrase "data fiduciary"

#### Scenario: Counsel gaps are present
- **WHEN** the frontend test suite runs
- **THEN** the privacy clauses with ids `purposes-and-basis`, `retention`, `your-rights`, `grievance` and `transfers` each have status `counsel`

#### Scenario: Retention states no period
- **WHEN** the frontend test suite runs
- **THEN** privacy clause `retention` contains no number, in digits or in words, followed (with a space or hyphen) by "hour", "day", "week", "month" or "year"

#### Scenario: Browser storage is disclosed
- **WHEN** the frontend test suite runs
- **THEN** privacy clause `cookies-and-storage` mentions local storage and the sign-in binding cookie

#### Scenario: Recipients by role
- **WHEN** the frontend test suite runs
- **THEN** privacy clause `who-receives-it` mentions every role in the exported recipient-role list and names no vendor (Zoop, Leegality, Razorpay, ZeptoMail, Cloudflare, Google)

### Requirement: Every policy page shows a draft banner and its gaps
`/terms`, `/privacy`, `/refunds` and `/contact` SHALL each show a visible banner saying the text is a draft pending review by Indian counsel. They SHALL render every non-`drafted` clause they show with its gap note in a visibly distinct box labelled by status.

The page components SHALL:

- render text as escaped text, never as HTML;
- make no network request themselves;
- render without the app chrome;
- offer a way back;
- render the shared site footer.

#### Scenario: Privacy page shows banner and gaps
- **WHEN** `/privacy` renders
- **THEN** the draft banner is visible, a gap box appears for each `counsel` clause, and the operator details section is shown

#### Scenario: Contact page shows support details
- **GIVEN** an operator entity with no LLPIN
- **WHEN** `/contact` renders
- **THEN** it shows the draft banner, a `mailto:` link to `CONTACT_EMAIL`, `SUPPORT_HOURS`, the legal name and the being-issued LLPIN statement

#### Scenario: Page components issue no request
- **GIVEN** the global `fetch` is spied
- **WHEN** the `PrivacyPolicy`, `RefundPolicy` and `ContactPage` components each mount
- **THEN** `fetch` is never called

#### Scenario: Text is not rendered as HTML
- **GIVEN** a clause whose body contains `<b>x</b>`
- **WHEN** the shared clause component renders it
- **THEN** the markup appears as literal text and no `b` element is created

#### Scenario: Back leaves the page
- **GIVEN** the app was opened at `/privacy` with no earlier history entry
- **WHEN** the back control is pressed
- **THEN** the app shows the home page at `/`

### Requirement: Counsel reads a generated copy of each policy document that has its own text
For each policy document that owns clause text, a generated markdown document SHALL be committed under `docs/`:

- `docs/TERMS-OF-SERVICE.md`;
- `docs/PRIVACY-POLICY.md`.

Each is rendered from its data module by one shared renderer and regenerated by `npm run legal:doc`. Each SHALL carry the draft banner, mark every gap with its status label, and render the operator details from the committed defaults. A test SHALL fail when a committed document differs from its renderer's output. Pages that only render other documents' text (`/refunds`, `/contact`) SHALL produce no document.

#### Scenario: Privacy document is current
- **WHEN** the frontend test suite runs
- **THEN** `docs/PRIVACY-POLICY.md` equals the renderer's output for the privacy policy, or the test fails naming `npm run legal:doc`

#### Scenario: Gap is marked for counsel
- **WHEN** the privacy policy is rendered to markdown
- **THEN** each `counsel` clause is preceded by a `GAP - FOR COUNSEL` line carrying its gap note

### Requirement: The policy pages are discoverable
`/privacy`, `/refunds` and `/contact` SHALL be routed by the app. They SHALL be linked from the shared site footer, and listed in `public/sitemap.xml` and in the `public/robots.txt` path comment alongside `/terms`.

#### Scenario: Direct navigation
- **WHEN** the app loads at `/refunds`
- **THEN** the refund page renders without app chrome, not the home page, and no template or agreement API call is made

#### Scenario: Sitemap lists the pages
- **WHEN** the frontend test suite runs
- **THEN** `public/sitemap.xml` contains entries for `/privacy`, `/refunds` and `/contact`

### Requirement: Policy texts state the stamp channel per state

The terms of service and the privacy policy SHALL describe how the stamp on an agreement is
obtained per state, as ops actually buys it:
- for Karnataka, an e-stamp certificate bought through the Stock Holding Corporation of India
  (SHCIL);
- for Telangana, which SHCIL does not serve, physical non-judicial stamp paper bought from a
  licensed stamp vendor.

Neither text SHALL describe one medium or one channel as the way every stamp is bought.
"Certificate" SHALL appear only as part of "e-stamp certificate". Neither text SHALL say a stamp
is bought on a portal.

The privacy policy SHALL name both stamp recipients by role, not by company: the "Karnataka
e-stamp issuer" and the "Telangana licensed stamp vendor". It SHALL say what each receives,
worded as non-exhaustive where the form's fields are not confirmed, and how each keeps it: the
issuer under its own rules, where it can be looked up by e-stamp certificate number, and the
vendor in its own register under the state's rules. Only the terms SHALL name SHCIL.

Terms-of-service §8 SHALL state, for a Telangana agreement, that the paper original of the stamp
is kept for one year from purchase and then shredded, that the attached scan is not the paper
itself, and that a customer who asks within that year can have it sent. It SHALL NOT promise free
delivery. The privacy policy SHALL disclose that the original is kept for one year, and SHALL name
a courier as a recipient only for a delivery the customer asks for.

The counsel gap of terms-of-service §8 SHALL include two questions: whether stamp paper bought
separately and attached to an electronically signed agreement stamps it validly, and whether the
paper original must accompany the agreement.

#### Scenario: The stamp-duty clause names the channel per state

- **GIVEN** the terms-of-service clause with id `stamp-duty`
- **WHEN** its body is read
- **THEN** it ties "SHCIL" and "e-stamp certificate" to Karnataka, and "non-judicial stamp paper" and "licensed stamp vendor" to Telangana
- **AND** its gap asks counsel whether stamp paper attached to an electronically signed agreement stamps it validly

#### Scenario: The Telangana paper original is accounted for

- **GIVEN** the terms-of-service clause with id `stamp-duty` and the privacy policy
- **WHEN** they are read
- **THEN** §8 says the Telangana paper original is kept for one year and then shredded, and can be sent on request within that year
- **AND** the privacy policy's collected-data clause says the paper original is kept for one year
- **AND** the privacy recipient roles include a courier used only when the customer asks for the original

#### Scenario: No text assumes one medium

- **GIVEN** every clause body and gap of the terms of service and the privacy policy, and the privacy policy's collected-data categories and recipient roles
- **WHEN** they are searched case-insensitively
- **THEN** "portal" and "government channel" are not found
- **AND** every occurrence of "certificate" is part of "e-stamp certificate"
- **AND** "SHCIL" occurs in the terms only in the clauses with ids `stamp-duty` and `availability-and-support`, and does not occur in the privacy policy

#### Scenario: The privacy policy names both stamp recipients

- **GIVEN** the privacy policy's recipient roles
- **WHEN** they are read
- **THEN** they include "Karnataka e-stamp issuer" and "Telangana licensed stamp vendor"
- **AND** each appears verbatim in the privacy clause that says who receives data, together with how that recipient keeps the data

#### Scenario: The refunds page follows the clause

- **GIVEN** `/refunds` renders the terms-of-service clause with id `refunds`
- **WHEN** the whole page text is searched case-insensitively for "certificate"
- **THEN** it is not found

