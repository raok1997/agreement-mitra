> Test note: this is a frontend content change with no backend runtime behaviour.
> - "Unit" means content-module tests: they read the clause data directly and use no DOM.
> - "Integration" means mounted-view tests (`/terms`, `/refunds`, the home page) that render the
>   same data through the real components.
> - No Testcontainers test applies.

## 1. Terms of service text (`frontend/src/content/termsOfService.ts`)

- [x] 1.1 §2 (`what-the-service-does`): change "stamp certificate" to "stamp". Replace paragraph 4 with the D1 text, which has no live list of its own.
- [x] 1.2 §5 (`jurisdictions`): replace the body with the D1 draft. Remove "Today that is Telangana", the "Karnataka … draft-only" paragraph and the national-template sentence.
- [x] 1.3 §7 (`our-fee`): replace the body with the D2 draft. Status stays `drafted`. Add a source comment naming `under-stamp-v1` and `StampQuoteStep.vue` as the coupled wording.
- [x] 1.4 §8 (`stamp-duty`), per D3:
  - per-state first paragraph;
  - every remaining "certificate" goes, including the gap's "stamp certificate" (`termsOfService.ts:90`) and paragraph 5's "the certificate was rejected";
  - the guarantee paragraphs use the D3 text: "costs you only the stamp itself" and "the replacement stamp is yours to pay for";
  - "refund you INR 400" stays;
  - add the D3 paper-original paragraph (one year, then shredded; sent on request; no delivery price promised);
  - append the validity question and the question of whether the paper original must accompany the agreement to the gap.
- [x] 1.5 §11 (`refunds`) body and gap, and §14 (`availability-and-support`): the D4 rows, including the §14 dependency sentence and "a hold-up of the kind just described". Keep every pinned §11/§14 phrase intact.
- [x] 1.6 `TERMS_OF_SERVICE.lastUpdated` is set to the landing date.

## 2. Privacy policy text (`frontend/src/content/privacyPolicy.ts`)

- [x] 2.1 Apply the D4 privacy rows:
  - the category "stamp scans";
  - the role "stamp-certificate issuing authority" is replaced by "Karnataka e-stamp issuer" and "Telangana licensed stamp vendor";
  - the lead sentence of `who-receives-it` gains "and with whoever sells us the stamp your agreement legally needs";
  - the collect paragraph and the recipients paragraph use the exact D4 text, with the roles verbatim as `privacyPolicy.test.ts:49-53` requires.
- [x] 2.3 Disclose the Telangana paper original (user answer, design D3):
  - the collect paragraph says it is kept for one year and then shredded;
  - add the `PRIVACY_RECIPIENT_ROLES` entry "courier, only if you ask us to send you a stamp paper original", verbatim in `who-receives-it`, with what it receives (the delivery name and address).
- [x] 2.2 The privacy policy's `lastUpdated` is set to the landing date.

## 3. Home page (`frontend/src/views/LandingPage.vue`, `frontend/index.html`, `src/content/promises.ts`)

- [x] 3.1 Rename `PRICE.includedDutyRupees` to `includedStampRupees` (promises.ts and every reference), and rename `LandingPage.vue:38`'s local `includedDuty` to `includedStamp`.
- [x] 3.2 `#price`, per D5:
  - headline "₹499 when your stamp is ₹100 or less";
  - inclusions "a stamp of up to ₹100" and "buying and attaching the stamp";
  - the overflow line is the D5 text: "Where your stamp duty is more than ₹100, you see the stamp we can buy, the duty and the exact total before you pay. There is never a second bill."
- [x] 3.3 FAQ 3 (D5), and "a wrong stamp" in the FAQ answer to "What happens if something goes wrong?".
- [x] 3.4 Step 3 body, guarantee 1 title and body, and the delay qualifier (full D4 sentence). Update the header comment at `LandingPage.vue:8-9` if it restates any of these.
- [x] 3.5 Mirror 3.3 into the FAQPage JSON-LD in `index.html`.

## 4. Same-fact fixes outside the policy texts

- [x] 4.1 `src/content/releaseStatus.ts` launch-checklist comment:
  - §2 and §5 defer to the board and the server's decision, so they are no longer "surfaces that state availability";
  - adding a state touches §5, the FAQ "Which cities do you serve?" and the `TemplatePicker` filter;
  - new precondition before a state's stamping row goes live or its rules become chargeable: ops has a confirmed channel for that state's stamps, and ToS §8/§14 and the privacy roles name it.
- [x] 4.2 A one-line comment naming ToS §7 as a paraphrase to re-check when the under-stamp warning changes, in two places: beside the warning text in `src/views/StampQuoteStep.vue`, and beside `UNDER_STAMP_WARNING_VERSION` in `backend/.../signing/.../StampOptions.java:28`. Comments only. Run `./gradlew compileJava spotlessCheck` for the Java file.
- [x] 4.3 `backend/src/main/resources/rules/stamp-paper/KA.yaml`: the header's "NO OPS PROCUREMENT CHANNEL" note now records the SHCIL channel (ops, 2026-10-06). Comment only: confirm that no computational key changes.
- [x] 4.4 `docs/GO-TO-MARKET-HYDERABAD.md`: correct "Telangana supports online e-stamping" to licensed-vendor non-judicial stamp paper (SHCIL does not list Telangana).

## 5. Tests

- [x] 5.1 Unit, `promises.test.ts`:
  - the §7 pin becomes "INR <total> where the stamp value on your agreement is INR <includedStamp> or less", built from `PRICE`;
  - the backend-default pin uses the renamed field.
- [x] 5.2 Unit, `termsOfService.test.ts` (jurisdiction-eligibility delta):
  - §5 contains "Telangana", "Karnataka", "residential", "We will not take payment for an agreement in a state we cannot stamp", "Draft and download only" and "status board on our home page";
  - §2 and §5 contain neither "Today that is" nor any exact `RELEASE_STATE_LABEL` value (case-sensitive);
  - §5 does not contain "national template".
- [x] 5.3 Unit, new `src/content/stampWording.test.ts` (legal-policy-pages ADDED). The scan is case-insensitive over every clause body and gap of both documents, plus the categories and roles. It asserts:
  - no "portal" or "government channel";
  - every "certificate" is inside "e-stamp certificate";
  - in the terms, "SHCIL" appears only in `stamp-duty` and `availability-and-support`, and the privacy policy does not contain it;
  - the `stamp-duty` clause ties SHCIL/e-stamp certificate to Karnataka and vendor/non-judicial stamp paper to Telangana, and its gap carries the validity question;
  - the roles include "Karnataka e-stamp issuer", "Telangana licensed stamp vendor" and the on-request courier;
  - §8 states that the Telangana original is kept for one year and then shredded, and can be sent on request, and the privacy collect clause mentions the one-year original.
- [x] 5.4 Integration, `views/TermsOfService.test.ts`:
  - remove the duty-basis, "still building" and "we correct the total … corrected figure" assertions;
  - assert the stamp-value phrases built from `PRICE`, "You can go ahead with a stamp below the duty only after" and "take payment and then come back to you for more";
  - `our-fee` stays `drafted`.
- [x] 5.5 Integration, `views/PolicyPages.test.ts` `RefundPolicy` block: the whole `/refunds` page text contains no "certificate" (case-insensitive).
- [x] 5.6 Integration, `views/LandingPage.test.ts`:
  - the guarantee assertions are named for a stamp, and the delay qualifier list adds "SHCIL" and "licensed vendor";
  - a new case scans the page text and the JSON-LD and finds no "portal", with every "certificate" inside "e-stamp certificate" or "digital signature certificate";
  - the existing `#price` "stamp duty" owning-section and price assertions still pass.
- [x] 5.7 After 1.6 and 2.2, run `npm run legal:doc` from `frontend/` and confirm `legalDocs.test.ts` passes.
- [x] 5.8 Gate: `npm run build` and `npm run lint` from `frontend/`.

## 6. Register (`docs/ROADMAP.md`)

- [x] 6.1 Delete the row `tos-below-duty-stamp-choice`. Remove its slug from the "First release" Counsel list, and add "including the §7 below-duty paragraph" to the "Counsel reviews `docs/TERMS-OF-SERVICE.md`" checkbox.
- [x] 6.2 `stamp-paper-plus-challan-plan`:
  - remove the answered "(ops question)" sentence;
  - append that the `estamp-intake`/`document-stamping` specs and `StaffConsole.vue` name SHCIL certificate fields, and need generalising for Telangana vendor stamp paper;
  - append that Karnataka's below-duty options are SHCIL e-stamps (user, 2026-10-06), but `StampQuoteStep.vue` labels them "Stamp paper", from the `KA.yaml` `stamp-paper` medium. The label is wrong; fix it in the catalog or medium label.
- [x] 6.3 `tg-stamp-duty-counsel-review`: append the stamp-paper validity question and the question of what happens to the paper original.
- [x] 6.4 Make the ROADMAP prose per state: the flow line "staff upload a purchased SHCIL e-stamp scan" and the `manual-estamp-upload` sentence "staff now purchase a real SHCIL e-stamp".
- [x] 6.4a Add the register row `tg-stamp-paper-shipping` (design D6): v1.1 shipping of the Telangana paper original, plus the untracked one-year shred schedule. Recommended action: record the purchase date at intake, and build shipping in v1.1.
- [x] 6.5 At archive, delete "First release" item 1 (`terms-release-revision`).

## Coverage

| # | Capability | Scenario | Disposition | Where |
|---|---|---|---|---|
| 1 | jurisdiction-eligibility | The terms carry a supported-jurisdictions clause | COVERED | 5.2 |
| 2 | jurisdiction-eligibility | The terms do not restate the live list | COVERED | 5.2 |
| 3 | jurisdiction-eligibility | The terms do not mention a template the picker hides | COVERED | 5.2 |
| 4 | jurisdiction-eligibility | The generated document matches its source | COVERED | 5.7 (`legalDocs.test.ts`) |
| 5 | legal-policy-pages | The stamp-duty clause names the channel per state | COVERED | 5.3 |
| 6 | legal-policy-pages | No text assumes one medium | COVERED | 5.3 |
| 7 | legal-policy-pages | The privacy policy names both stamp recipients | COVERED | 5.3 + existing `privacyPolicy.test.ts:49-53` |
| 8 | legal-policy-pages | The refunds page follows the clause | COVERED | 5.5 |
| 8a | legal-policy-pages | The Telangana paper original is accounted for | COVERED | 5.3 + existing `privacyPolicy.test.ts:49-53` (courier role verbatim) |
| 9 | landing-page | The band shows the price, the overflow rule and its scope | COVERED | 5.6 (existing `#price` case) |
| 10 | landing-page | The constant matches the backend default fee | COVERED | 5.1 |
| 11 | landing-page | The constant matches the published terms | COVERED | 5.1 |
| 12 | landing-page | Three guarantees, each expandable and linked to the terms | COVERED | 5.6 |
| 13 | landing-page | Each guarantee keeps its qualifiers | COVERED | 5.6 (existing qualifier case, extended with "SHCIL" and "licensed vendor") |
| 14 | landing-page | Guarantee figures match the terms | COVERED | 5.1 (existing `promises.test.ts`) |
| 15 | landing-page | The old pillar copy is gone | GROUPED | existing `LandingPage.test.ts` case, run under 5.8 |
| 16 | landing-page | The home page does not assume the stamp medium | COVERED | 5.6 |
| — | landing-page (baseline, unmodified) | Stamp duty in the price is stated once outside the FAQ | GROUPED | existing `LandingPage.test.ts:265-273`, which must still pass (5.6) |
