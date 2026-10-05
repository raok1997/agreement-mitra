## Why

The first release takes paid orders from real customers. The site publishes terms of service, but it has
no Privacy Policy, no Refund/Cancellation policy and no Contact page. Customers expect those three, the
payment gateway's merchant review checks for them, and under the DPDP Act, 2023 KAVISAT TEK LABS LLP is
the data fiduciary for what customers enter. Most of the text either already exists in the terms or is
still with counsel. So the risk is not missing words. The risk is a second copy of a legal text that
drifts from the first, which `docs/LEGAL-POSTURE.md` item 2 exists to prevent.

## What Changes

- **One model for every policy document.** The `Clause` type (status `drafted` / `counsel` / `product`
  plus a gap note) moves out of `termsOfService.ts` into a shared module. Every clause gets a stable
  `id`, so a page or test can name a clause without depending on its number or heading.
- **Privacy Policy at `/privacy`** from a new `privacyPolicy.ts`, which becomes the only home of the
  privacy text. It is drafted from facts the system already holds:
  - the LLP is the data fiduciary;
  - what we collect, and what we do not hold (no Aadhaar number, VID or OTP);
  - the deleted-draft record;
  - the kinds of service provider that receive data;
  - the cookies the site sets.

  What is not ours to write becomes marked counsel gaps: purposes and lawful basis, retention, data
  principal rights, the grievance contact and cross-border transfer. Retention points to the terms'
  retention clause rather than restating it.
- **ToS §15 becomes a pointer.** Its "what we hold" paragraphs move into the Privacy Policy. §15 keeps
  its number and heading, so §8/§11/§14 references are unaffected, and its body points to `/privacy`.
- **Refund/Cancellation page at `/refunds`.** It renders ToS §11 itself, looked up by id: the same
  object, the same gap note and the same status. It holds no text of its own beyond a title and a line
  saying the text is part of the terms.
- **Contact page at `/contact`.** It renders the support email and hours from `promises.ts`, the operator
  details from `operatingEntity.ts`, and ToS §19's guidance. It adds no new copy of any of them. §19's
  literal email is pinned to `CONTACT_EMAIL` by a test.
- **Every page carries a visible "draft, pending review by Indian counsel" banner**, like the terms page.
- **Counsel documents.** The generator renders every document that has its own text (the terms and the
  privacy policy) to `docs/`, with one staleness test per document. `docs/PRIVACY-POLICY.md` is new.
  The npm script becomes `legal:doc`. `/refunds` and `/contact` produce no document, because they
  carry no text the terms do not already hold.
- **Site wiring.** Routes in `App.vue`, links from the shared footer, and `sitemap.xml` / `robots.txt`
  entries.
- **Release tracking.** A "Policy pages: production readiness" checklist in the `docs/ROADMAP.md` "First
  release" section lists what must be true before real customers see the pages: counsel sign-off per
  page, the banners removed, the gaps closed and the operator details set. `docs/COUNSEL-BRIEF.md`
  Annexure C gains the privacy document.

Out of scope:
- Writing any counsel-gap text ourselves.
- A terms-acceptance checkpoint.
- Per-route `<title>` / prerendering. The open `seo-foundations` change owns that and picks the new paths
  up from the sitemap.
- Wiring the generator into a build gate. That stays with the `terms-doc-ungated` register row, which
  this change widens to cover the privacy document.

## Capabilities

### New Capabilities
- `legal-policy-pages`: the shared policy-document model, the `/privacy`, `/refunds` and `/contact` pages,
  their single-source rules (no clause text in two places), the draft banner and the generated counsel
  documents.

### Modified Capabilities
- `operating-entity-disclosure`:
  - the shared footer covers the new pages and links to them;
  - the operator-details requirement covers the privacy page and document, and names `renderLegalMarkdown`;
  - the legal-name requirement looks up §1 by id.
- `landing-page`: "Guarantees mirror the terms" looks up §8/§11/§14 by clause id instead of by heading
  prefix.

## Impact

- **Frontend only.** No backend, API, database or signing-flow change. No signing-status FSM transition
  is touched.
- New files:
  - `src/content/legalDocument.ts`
  - `src/content/privacyPolicy.ts`
  - three views and their tests
  - `docs/PRIVACY-POLICY.md` (generated)
- Changed files:
  - `termsOfService.ts` (ids, §15)
  - `termsMarkdown.ts` → a generic renderer
  - `scripts/render-terms.mjs` → `render-legal-docs.mjs`
  - `package.json` (`legal:doc`)
  - `App.vue`
  - `SiteFooter.vue`
  - `public/sitemap.xml`, `public/robots.txt`
  - `promises.test.ts` and `TermsOfService.test.ts` clause lookups (by id)
  - `docs/TERMS-OF-SERVICE.md` (regenerated)
  - `docs/ROADMAP.md`, `docs/COUNSEL-BRIEF.md`
- **PII/security:** none introduced or moved. The pages are static text, read no customer data and make
  no network request. The privacy text describes existing data flows and adds none.
