## Context

The terms of service live as data in `frontend/src/content/termsOfService.ts`:

- an array of `Clause { heading, body[], status, gap? }`;
- rendered at `/terms` by `views/TermsOfService.vue`;
- rendered to `docs/TERMS-OF-SERVICE.md` by `content/termsMarkdown.ts`, written by
  `scripts/render-terms.mjs` (Vite `ssrLoadModule`);
- held equal to the committed file by the staleness test in `termsOfService.test.ts`.

Single-sourced facts already exist:

- `promises.ts`: `CONTACT_EMAIL`, `SUPPORT_HOURS`;
- `operatingEntity.ts`: the legal name, plus the build-time LLPIN and registered office.

Routing is by hand in `App.vue`: `routeFor()` maps a pathname to a `Route` union, a `v-else-if` chain picks
the view, and `/terms` renders with no app chrome. `components/SiteFooter.vue` is shared by the landing and
terms pages.

Constraints found while grounding (review round 1):

- **Clause numbers are load-bearing.** `promises.test.ts` finds clauses by `heading.startsWith("11. ")`,
  `LandingPage.vue` prints "Terms, section 11", and the landing-page spec names the "N. " headings. No
  clause is renumbered.
- **Clause bodies cross-refer by position**, not number ("the data-protection clause below" in §12, "the
  electronic-signature clause above" in §15).
- **`App.vue` `onMounted` calls `init()`**, which requests `/me` on every route, `/terms` included. The "no
  network request" guarantee therefore holds for the page **components**, not for the app shell; the
  existing `/terms` app test asserts only that no template or agreement call is made.
- **Data the system actually holds or sends is wider than old §15 says:**
  - the Google display name and email (`V11__identity_oauth.sql`);
  - the eSign audit trail and signed PDF stored verbatim from the vendor;
  - staff-uploaded stamp-certificate scans;
  - a `requester_fingerprint` for rate-limit forensics (`V19`);
  - party PII in `localStorage` while drafting (`CaptureForm.vue:518-622`);
  - a Google login-binding cookie besides the session and CSRF cookies;
  - Google Fonts loaded from Google (`index.html:36-40`), so every visitor's IP reaches Google;
  - Razorpay's checkout script.

## Goals / Non-Goals

**Goals:**
- Three pages (`/privacy`, `/refunds`, `/contact`) with no policy text held in two places.
- Draft only what is verifiably true; mark everything else as a visible gap; banner every page as a draft.
- Counsel receives the privacy text as a generated document, the same way as the terms.
- A production-readiness checklist in `docs/ROADMAP.md`.

**Non-Goals:**
- Writing counsel-gap text.
- Deciding whether the privacy notice is incorporated into the terms (counsel brief Q6(b)).
- Terms acceptance or versioning.
- Making `init()` route-aware.
- Self-hosting fonts.
- Per-route titles and prerendering (`seo-foundations`).
- Wiring the generator into a build gate (`terms-doc-ungated`).

## Decisions

**D1. A shared `legalDocument.ts` holds the model; every clause gets an `id`.**
- `Clause` gains `id: string` (kebab-case). `ClauseStatus` and `Clause` move to `content/legalDocument.ts`,
  together with `LegalDocument { title, lastUpdated, banner, clauses }` and `clauseById(doc, id)`, which
  throws naming the missing id.
- `termsOfService.ts` exports `TERMS_OF_SERVICE: LegalDocument` only. The old `TERMS_CLAUSES` /
  `TERMS_LAST_UPDATED` / `TERMS_STATUS_BANNER` names are **removed**, not aliased. All five importers are
  edited by this change anyway, so an alias would only be a second name for one fact.
- Ids for the clauses the specs of record name: §1 `who-we-are`, §8 `stamp-duty`, §11 `refunds`,
  §14 `availability-and-support`, §15 `personal-data`, §19 `contact`. The landing-page "Guarantees mirror
  the terms" and operating-entity "legal name" requirements are MODIFIED to look up by those ids, so the
  spec of record describes the lookup the tests actually perform.
- Clauses that a page renders from another module are resolved **at module scope**: `termsOfService.ts`
  exports `TERMS_REFUNDS_CLAUSE` and `TERMS_CONTACT_CLAUSE` via `clauseById`. A bad id then fails on import
  in every test, not at page render.
- `promises.test.ts` `clause(n)` and `TermsOfService.test.ts` `byHeading()` switch to `clauseById`.
- *Rejected:* lookup by heading prefix. That is what made §11's number load-bearing.

**D2. The refund page renders §11 itself.**
- `RefundPolicy.vue` renders `TERMS_REFUNDS_CLAUSE` with the shared clause component, under the terms'
  banner and `lastUpdated`.
- Its intro line names no number: "This is the refunds and cancellation section of our terms of service",
  linking `/terms`.
- *Rejected:* a `refundPolicy.ts` module. Any text in it would be a second copy.
- `/refunds` has no counsel document: counsel reads §11 in `TERMS-OF-SERVICE.md`.

**D3. Privacy owns its text; ToS §15 becomes a pointer that stays a counsel gap.**
- §15 keeps `heading: "15. Your personal data"` and `status: "counsel"`.
- Its body becomes exactly one paragraph: "What personal data we hold, why, and who receives it is set out
  in our privacy policy at agreementmitra.com/privacy."
- Its gap note is rewritten to the one open question that stays in the terms: whether the privacy notice
  must stand apart from the terms or be part of them (counsel brief Q6(b)).
- **No incorporation wording** ("forms part of these terms"). That is counsel's answer to give, and leaving
  it out keeps the two documents' versions independent: a privacy edit is not a terms edit, so §18's
  "terms published when you paid" is untouched.
- The old gap note's other topics (purposes, basis, retention, rights) are **split** into the privacy
  policy's per-topic counsel gaps, each with its own wording. It is not moved whole.

**D4. Privacy policy clauses (`privacyPolicy.ts`), in this order.** Every drafted sentence is checked
against code at apply time (task 2.1, evidence recorded). A claim that cannot be verified becomes a gap,
not a sentence.

| id | status | content |
|---|---|---|
| `who-we-are` | drafted | KAVISAT TEK LABS LLP is the data fiduciary for personal data processed by AgreementMitra |
| `what-we-collect` | drafted | Old §15's paragraph 1, plus: account sign-in details (the Google account's name and email); the eSign provider's audit trail and the signed PDF, which include identity details the provider obtains from Aadhaar (such as the name and postal code, and how well the name matched); stamp-certificate scans our staff upload; payment records (amount, gateway order and payment references, linked to the account); the requester's **IP address** (IPv4, or the IPv6 /64), kept for abuse prevention, and a shortened form of it in security logs. Only what exists today: phone sign-in is added by `mobile-otp-auth` (task 6.1) |
| `what-we-do-not-hold` | drafted | We never ask for or store your Aadhaar number, virtual ID or **Aadhaar** one-time password; the eSign provider handles them. The provider's signature and audit trail on the signed PDF may show part of the number in masked form, as the eSign framework provides. Worded to be true whether or not a masked number appears; task 2.1 inspects a sandbox artifact and tightens it if it can. We hold no card, UPI or bank details: the payment gateway collects them directly |
| `purposes-and-basis` | counsel | purposes and lawful basis under DPDP 2023 |
| `who-receives-it` | drafted | Recipients **by role**, not vendor name: eSign provider (which also emails each party its signing invitation); payment gateway (whose checkout page sets its own cookies); stamp-certificate vendor / issuing authority (party names, property address); email delivery; sign-in provider; hosting and storage; content delivery and network security (all traffic, including what you type into the form, passes through it); web-font provider (your browser fetches fonts from it, which shares your IP address and browser details). Vendor names are deployment facts that change behind interfaces (`EsignProvider`) |
| `cookies-and-storage` | drafted | Cookies on our domain are strictly necessary: session, security (CSRF), sign-in binding, and the network-security provider's bot-detection cookie. No advertising or analytics cookies. While you draft, the form keeps your answers, including party details, in your browser's local storage so a refresh does not lose them. It is cleared when you save and continue or reset the form; a saved draft older than 24 hours is discarded when you return; it is **not** cleared when you sign out. So on a shared device, reset the form. Never "deleted after 24 hours": the age is checked only on read (`CaptureForm.vue:567-576`). Cookies set by the payment checkout are named as third-party |
| `retention` | counsel | Points, by name not number, to the terms' clause on how long we keep things; states no period |
| `deleted-drafts` | drafted | Old §15's paragraph 3 |
| `your-rights` | counsel | Access, correction, erasure, nomination, how to exercise them |
| `grievance` | counsel | Grievance officer and the Data Protection Board route. The gap note says that until then you can write to the support email, built from `CONTACT_EMAIL` (`privacyPolicy.ts` is new, so it interpolates the constant rather than adding a pinned literal) |
| `transfers` | counsel | Whether data is processed outside India |
| `changes` | drafted | Changes are published on this page with a new last-updated date |

- Cross-references inside privacy text name the other document and clause in words ("the terms of
  service's clause on electronic signature"), never "above"/"below" across documents, and never a number.
- **Drift guard.** A test pins the `who-receives-it` role list and the `what-we-collect` category list as
  exported constants that the clause text is checked against. A new integration (e.g. phone OTP's SMS
  provider) then has an obvious place to update and a test that names it.

**D5. The contact page composes existing facts.**
- `ContactPage.vue` renders:
  - `mailto:CONTACT_EMAIL` (the only external scheme on the page) and `SUPPORT_HOURS`;
  - `TERMS_CONTACT_CLAUSE`;
  - `OperatorDetails`.
- No last-updated date: the page carries facts, not a policy text.
- §19's literal email stays a literal (clause bodies are plain strings); a test pins `CONTACT_EMAIL` in it.

**D6. One renderer, one registry, one staleness test per document.**
- `termsMarkdown.ts` becomes `legalMarkdown.ts` with `renderLegalMarkdown(entry)`. The renderer itself
  reads `OPERATING_ENTITY_DEFAULTS`; it is **not** a parameter, so no caller can pass the build-env entity
  and the environment-independence test proves what it claims.
- `termsDocPath.ts` becomes `legalDocs.ts`: a `LEGAL_DOCS` registry of `{ doc, docPath, sourcePath,
  testPath }`. The script and the staleness tests iterate it; the test re-imports it for the env check.
- Markdown H1 is `AgreementMitra — ${doc.title} (DRAFT)`. With `doc.title` = "Terms of Service" (also
  the page `<h1>`, now read from `doc.title`), this reproduces today's H1.
- The intro paragraph is generated from the statuses present: the "two kinds of gap" text unchanged when
  both `counsel` and `product` occur (the terms), and only the FOR COUNSEL sentence when only `counsel`
  occurs (privacy). The header comment names `sourcePath` and `testPath` per document.
- `scripts/render-terms.mjs` becomes `render-legal-docs.mjs`, which writes both documents. The npm script
  `terms:doc` becomes `legal:doc`; references are updated in comments, stale-file messages,
  `docs/LEGAL-POSTURE.md` and the `terms-doc-ungated` register row.
- The regenerated `TERMS-OF-SERVICE.md` changes in §15, the last-updated date (if it differs) and the
  generated header (regenerate command and test path). Every other line is byte-identical. §12's "See also
  the data-protection clause below" now leads to the §15 pointer, which leads on to `/privacy`. That is
  accepted and needs no edit.
- *Rejected:* one combined counsel file. Annexure C cites the terms file by name, and separate files can be
  signed off separately.

**D7. One view per page, shared components extracted from `TermsOfService.vue`.**
- `LegalClause.vue` (keyed by `clause.id`), `DraftBanner.vue` (text prop; the bold lead-in "Draft, pending
  legal review." is the shared constant `DRAFT_BANNER_LEAD` in `legalDocument.ts`) and `OperatorDetails.vue`.
- New view test ids: roots `privacy-policy`, `refund-policy`, `contact-page`; back buttons `legal-back`.
  `/terms` keeps `terms-of-service` / `terms-back`.
- The existing `terms-*` test ids are kept on the shared components and accepted on the new pages. Renaming
  them would churn every existing test for no behaviour change.
- The three views take the `entity` prop and emit `back`. `App.vue` routes them like `terms`, with one
  generalised `leaveLegalPage()` replacing `leaveTerms()`.
- `/privacy` also renders `OperatorDetails`, so the page matches its counsel document.

**D8. Banner text has one source.** All banner strings live in their content modules:
- `TERMS_OF_SERVICE.banner`;
- `PRIVACY_POLICY.banner`;
- `CONTACT_PAGE_BANNER` in `promises.ts`, beside the contact facts: "Our policies are drafts pending
  review by Indian counsel. The contact details below are current."

`/refunds` uses the terms banner. Removing banners is a release-checklist item.

**D9. Release checklist in `docs/ROADMAP.md`.** A "Policy pages: production readiness" checklist under
"First release":
- [ ] counsel reviews `TERMS-OF-SERVICE.md` and `PRIVACY-POLICY.md` (record the reviewer, the date and the commit);
- [ ] every `counsel` / `product` gap is closed or explicitly accepted;
- [ ] **blocking:** a grievance officer is named and the Data Protection Board route is stated;
- [ ] Q6(b) is answered and §15 is updated to match;
- [ ] the draft banners are removed;
- [ ] the LLPIN and registered office are set;
- [ ] the policy pages are checked live;
- [ ] the privacy inventory is re-checked against any change merged since review, `mobile-otp-auth` in particular.

The round-1 `legal-policy-pages` line is deleted at archive; the checklist stays until the release.

**D10. Sibling changes are told, not left to memory.**
- `mobile-otp-auth` gets a task to update `what-we-collect` / `who-receives-it` and the D4 pinned lists.
- `seo-foundations` gets a task to give `/privacy`, `/refunds` and `/contact` the per-route treatment it
  gives `/terms`.

Both are one-line appends to those changes' `tasks.md`.

## Risks / Trade-offs

- **[Drafted privacy text could overstate.]** A false "we do not" is worse than a gap. Mitigated by
  D4's verify-at-apply rule, wording true in either case, and pinned category lists.
- **[The app shell still calls `/me` on policy pages.]** Accepted. The page components make no request;
  making `init()` route-aware is a behaviour change outside this slice.
- **[Third-party font loading]** is disclosed rather than removed. Self-hosting is a separate decision.
- **[Generator still ungated]** (`terms-doc-ungated`). The staleness tests catch a stale document, not a
  broken script.
- **[Mid-flow red build]** Tasks are ordered so content lands before the §15 edit, and the documents are
  regenerated in the same group. Expect red only within a group.
