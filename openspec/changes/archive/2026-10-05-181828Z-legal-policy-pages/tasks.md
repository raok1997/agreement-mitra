Before starting, record the current `npm run lint` warning count in the flow journal as the 7.3 baseline.
The build is expected red only inside groups 1–3, which land together: 3.3 regenerates the documents that
1.3 and 2.2 change.

## 1. Shared clause model

- [x] 1.1 Create `frontend/src/content/legalDocument.ts`: `ClauseStatus`, `Clause` with `id`, `LegalDocument { title, lastUpdated, banner, clauses }`, and `clauseById(doc, id)`, which throws naming the missing id.
- [x] 1.2 In `termsOfService.ts`:
  - give every clause a kebab-case `id` (§1 `who-we-are`, §8 `stamp-duty`, §11 `refunds`, §14 `availability-and-support`, §15 `personal-data`, §19 `contact`);
  - export `TERMS_OF_SERVICE: LegalDocument`, plus `TERMS_REFUNDS_CLAUSE` and `TERMS_CONTACT_CLAUSE` resolved at module scope;
  - remove the `TERMS_CLAUSES` / `TERMS_LAST_UPDATED` / `TERMS_STATUS_BANNER` exports and migrate the importers `TermsOfService.vue`, `termsOfService.test.ts`, `promises.test.ts` and `TermsOfService.test.ts`. `termsMarkdown.ts` is not touched here; 3.1 replaces it in the same group.
- [x] 1.3 Switch the clause lookups to `clauseById`: `promises.test.ts` `clause(n)` (landing-page MODIFIED scenario), `TermsOfService.test.ts` `byHeading()`, and the §1 legal-name test in `termsOfService.test.ts` / `operatorFacts.test.ts` (operating-entity MODIFIED scenario).

## 2. Privacy policy content

- [x] 2.1 Verify every drafted fact in design D4 against the code, and record the evidence (file:line) in the flow journal. A fact that cannot be verified becomes a `counsel` gap. Check at least:
  - capture and auth fields stored, including the Google name and email (`V11`);
  - the eSign audit trail and signed PDF as stored. Inspect a sandbox artifact, if one exists, for a masked Aadhaar;
  - stamp-certificate scans;
  - `requester_fingerprint` (`V19`);
  - every cookie the backend sets (session, CSRF, login binding, in `SessionCookies.java` and `GoogleLoginService.java`);
  - `localStorage` / `sessionStorage` use. The draft TTL is checked **only on read** (`CaptureForm.vue:567-576`); drafts are keyed per state and type, and sign-out does not clear them. Word the clause exactly that way, never "deleted after 24 hours" (`authStore.ts:54`);
  - payment records (`V16`, `V17`), and that no card, UPI or bank detail is stored;
  - the Aadhaar-derived fields in the stored audit trail (`V18:53`, `ZoopEsignProvider.java:53,500`);
  - `requester_fingerprint` = IP address (`ClientSourceResolver.java:65-67`), and the truncated IP in security logs;
  - the deployment edge: proxying, TLS termination and bot-detection cookies (`docs/DEPLOYMENT.md:33-35,52`), from the deployment docs, not only the code;
  - third-party scripts and fonts (`index.html`, `payments.ts`);
  - the stamp-purchase data flow.
- [x] 2.2 Create `frontend/src/content/privacyPolicy.ts`:
  - `PRIVACY_POLICY: LegalDocument` with the clauses of design D4, its own banner and `lastUpdated`;
  - exported `PRIVACY_COLLECTED_CATEGORIES` and `PRIVACY_RECIPIENT_ROLES`.

  Old §15's three paragraphs go into `what-we-collect`, `what-we-do-not-hold` (reworded per D4) and `deleted-drafts`. Old §15's gap topics are split into the per-topic counsel gaps (D3). Cross-references name the other document in words.
- [x] 2.3 Rewrite ToS §15 per design D3: keep the heading, `counsel` status, one pointer paragraph, and a gap note naming Q6(b). Set the terms `lastUpdated` to the apply date, if it differs.
- [x] 2.4 Unit tests in `legalDocument.test.ts` and `privacyPolicy.test.ts`:
  - ids are unique per document;
  - every `counsel` / `product` clause has a gap;
  - `clauseById` throws on an unknown id;
  - ToS `contact` contains `CONTACT_EMAIL`;
  - ToS `personal-data` matches the "Terms §15 is a pointer" scenario;
  - the fiduciary is named;
  - the five counsel ids are `counsel`;
  - `retention` matches no `(\d+|one|two|three|…|ten|twelve)[\s-]*(hour|day|week|month|year)` (case-insensitive);
  - `cookies-and-storage` mentions local storage and sign-in binding;
  - `who-receives-it` mentions every `PRIVACY_RECIPIENT_ROLES` entry and no vendor name;
  - `what-we-collect` mentions every `PRIVACY_COLLECTED_CATEGORIES` entry;
  - no clause in either document contains `LLPIN` or `GSTIN`.

## 3. Generated counsel documents

- [x] 3.1 Replace `termsMarkdown.ts` with `legalMarkdown.ts` and `termsDocPath.ts` with the `legalDocs.ts` registry, per design D6:
  - `renderLegalMarkdown(entry)` reads `OPERATING_ENTITY_DEFAULTS` itself;
  - the H1 is built from `doc.title`;
  - the intro is generated from the statuses present;
  - the header names `sourcePath` and `testPath`.
- [x] 3.2 Replace `scripts/render-terms.mjs` with `scripts/render-legal-docs.mjs`, keeping the Vite-server comments and teardown, and writing both documents. Rename the npm script `terms:doc` → `legal:doc`, and update every reference:
  - comments and stale-file messages;
  - `docs/LEGAL-POSTURE.md` and `docs/DOMAIN-AND-EMAIL-SETUP.md:57`;
  - `openspec/changes/byo-document-upload/tasks.md:134`;
  - the `render-terms.mjs` mentions in `openspec/changes/seo-foundations` (proposal, design, tasks);
  - the `terms-doc-ungated` row in `docs/ROADMAP.md`, which now also covers `PRIVACY-POLICY.md`.

  Re-grep `terms:doc` / `render-terms` / `renderTermsMarkdown` outside `archive/` to confirm none remain.
- [x] 3.3 Run `npm run legal:doc`. Confirm the `docs/TERMS-OF-SERVICE.md` diff is only §15, the last-updated date and the generated header. Add `docs/PRIVACY-POLICY.md`.
- [x] 3.4 Unit tests:
  - a staleness test per document, whose message names `npm run legal:doc`;
  - the privacy markdown has a `GAP - FOR COUNSEL` line per counsel clause;
  - environment independence for both documents (the MODIFIED operating-entity scenario).

## 4. Pages and components

- [x] 4.1 Extract from `TermsOfService.vue`:
  - `LegalClause.vue` (keyed by `clause.id`);
  - `DraftBanner.vue` (lead-in and text props);
  - `OperatorDetails.vue`.

  Keep the existing `terms-*` test ids. `TermsOfService.test.ts` stays green, apart from the 1.2/1.3 import and lookup edits.
- [x] 4.2 Create `views/PrivacyPolicy.vue`: banner, clauses, last-updated, `OperatorDetails`, back, `SiteFooter`.
- [x] 4.3 Create `views/RefundPolicy.vue`: the terms banner and date, the number-free intro line linking `/terms`, `TERMS_REFUNDS_CLAUSE` via `LegalClause`, back, footer.
- [x] 4.4 Create `views/ContactPage.vue`: `CONTACT_PAGE_BANNER` (added to `promises.ts`), `mailto:CONTACT_EMAIL`, `SUPPORT_HOURS`, `TERMS_CONTACT_CLAUSE`, `OperatorDetails`, back, footer.
- [x] 4.5 Unit tests (`PrivacyPolicy.test.ts`, `RefundPolicy.test.ts`, `ContactPage.test.ts`, `LegalClause.test.ts`):
  - the banner is shown;
  - a gap box appears per non-drafted clause;
  - `/privacy` shows the operator details;
  - the refund page shows exactly §11's heading, paragraphs and gap;
  - the contact page shows the mailto, hours, legal name and being-issued LLPIN;
  - each component makes no `fetch` call;
  - each renders `SiteFooter` and its root / `legal-back` test id (design D7);
  - `LegalClause` renders `<b>x</b>` as literal text;
  - no new component uses `v-html`.

## 5. Routing, footer, discoverability

- [x] 5.1 `App.vue`: add `privacy`, `refunds` and `contact` to `Route` / `routeFor`, render them chrome-free, and replace `leaveTerms()` with one `leaveLegalPage()` used by all four.
- [x] 5.2 `SiteFooter.vue`: add links to `/privacy`, `/refunds` and `/contact`. Update `SiteFooter.test.ts` (the links, plus the existing no-fetch and operator assertions kept).
- [x] 5.3 `public/sitemap.xml`: add the three paths, mirroring `/terms`. `public/robots.txt`: add them to the path comment.
- [x] 5.4 Integration test in `App.test.ts`, mounting the whole app:
  - loaded at `/privacy`, `/refunds` and `/contact`, each renders its page chrome-free, with no template-list or agreement call;
  - back with no history lands on `/`. Stub `history.length` to 1 with `vi.spyOn(window.history, "length", "get")`, as jsdom accumulates entries, and `mockRestore()` it in a `finally` (the file has no `restoreAllMocks`, and the `/terms` back test needs the real length);
  - the footer on `/` links all four policy paths.
- [x] 5.5 Unit test: `public/sitemap.xml` contains `/privacy`, `/refunds` and `/contact`.

## 6. Sibling changes

- [x] 6.1 Append one task to `openspec/changes/mobile-otp-auth/tasks.md`: update privacy `what-we-collect` / `who-receives-it` and `PRIVACY_COLLECTED_CATEGORIES` / `PRIVACY_RECIPIENT_ROLES` for the mobile number and SMS-OTP provider, and regenerate with `npm run legal:doc`.
- [x] 6.2 Append one task to `openspec/changes/seo-foundations/tasks.md`: give `/privacy`, `/refunds` and `/contact` the per-route treatment planned for `/terms`.

## 7. Docs, release tracking, gates

- [x] 7.1 `docs/ROADMAP.md`, "First release":
  - add the "Policy pages: production readiness" checklist (design D9);
  - in the counsel paragraph, note that §15 now points to `/privacy` and that Q6(b) decides whether it is incorporated.

  Stage only this change's hunks: the file has other in-flight edits.
- [x] 7.2 `docs/COUNSEL-BRIEF.md`: list `docs/PRIVACY-POLICY.md` in Annexure C and Q6(b), together with the 2.1 evidence for each drafted privacy claim.
- [x] 7.3 Gates: `npm run build` and `npm run lint` from `frontend/`, with lint warnings not above the recorded baseline.

## Coverage

| # | Capability | Scenario | Disposition | By |
|---|---|---|---|---|
| 1 | legal-policy-pages | Ids are unique | COVERED | 2.4 |
| 2 | legal-policy-pages | Every non-drafted clause explains its gap | COVERED | 2.4 |
| 3 | legal-policy-pages | Unknown id fails loudly | COVERED | 2.4 |
| 4 | legal-policy-pages | Refund page renders the terms clause | COVERED | 4.5 |
| 5 | legal-policy-pages | Terms §15 is a pointer | COVERED | 2.4 |
| 6 | legal-policy-pages | Contact email agrees with the terms | COVERED | 2.4 |
| 7 | legal-policy-pages | Fiduciary is named | COVERED | 2.4 |
| 8 | legal-policy-pages | Counsel gaps are present | COVERED | 2.4 |
| 9 | legal-policy-pages | Retention states no period | COVERED | 2.4 |
| 10 | legal-policy-pages | Browser storage is disclosed | COVERED | 2.4 |
| 11 | legal-policy-pages | Recipients by role | COVERED | 2.4 |
| 12 | legal-policy-pages | Privacy page shows banner and gaps | COVERED | 4.5 |
| 13 | legal-policy-pages | Contact page shows support details | COVERED | 4.5 |
| 14 | legal-policy-pages | Page components issue no request | COVERED | 4.5 |
| 15 | legal-policy-pages | Text is not rendered as HTML | COVERED | 4.5 |
| 16 | legal-policy-pages | Back leaves the page | COVERED | 5.4 |
| 17 | legal-policy-pages | Privacy document is current | COVERED | 3.4 |
| 18 | legal-policy-pages | Gap is marked for counsel | COVERED | 3.4 |
| 19 | legal-policy-pages | Direct navigation | COVERED | 5.4 |
| 20 | legal-policy-pages | Sitemap lists the pages | COVERED | 5.5 |
| 21 | operating-entity-disclosure | Landing page footer | GROUPED | existing `SiteFooter.test.ts` / `LandingPage.test.ts`, kept green by 5.2 |
| 22 | operating-entity-disclosure | Terms page footer | GROUPED | existing `App.test.ts` "serves the terms of service at /terms…", kept green by 4.1 |
| 23 | operating-entity-disclosure | Footer links the policy pages | COVERED | 5.2 |
| 24 | operating-entity-disclosure | No request issued (footer) | GROUPED | existing `SiteFooter.test.ts` no-fetch test, kept by 5.2 |
| 25 | operating-entity-disclosure | Page shows operator details | GROUPED | existing `TermsOfService.test.ts`, kept green by 4.1 |
| 26 | operating-entity-disclosure | Generated documents are environment-independent | COVERED | 3.4 |
| 27 | operating-entity-disclosure | Clause text holds no identifier | COVERED | 2.4 |
| 28 | operating-entity-disclosure | Frontend and backend names agree | GROUPED | existing `operatorFacts.test.ts`, unchanged |
| 29 | operating-entity-disclosure | Terms name the same party | COVERED | 1.3 (lookup by id `who-we-are`) |
| 30 | operating-entity-disclosure | The environment cannot rename the operator | GROUPED | existing backend `OperatingEntity` config test, untouched |
| 31 | landing-page | Three guarantees, each expandable and linked to the terms | GROUPED | existing `LandingPage.test.ts`, untouched |
| 32 | landing-page | Each guarantee keeps its qualifiers | GROUPED | existing `LandingPage.test.ts`, untouched |
| 33 | landing-page | Guarantee figures match the terms | COVERED | 1.3 (`promises.test.ts` by id) |
| 34 | landing-page | The old pillar copy is gone | GROUPED | existing `LandingPage.test.ts`, untouched |
| 35 | landing-page | The band shows the price, the overflow rule and its scope | GROUPED | existing `LandingPage.test.ts`, untouched |
| 36 | landing-page | The constant matches the backend default fee | GROUPED | existing `promises.test.ts`, untouched |
| 37 | landing-page | The constant matches the published terms | COVERED | 1.3 (`promises.test.ts` "states the fee the terms state (§7)", by id `our-fee`) |
