## Context

The terms of service are data (`frontend/src/content/termsOfService.ts`). They render at `/terms`,
§11 renders at `/refunds`, and the whole document renders to `docs/TERMS-OF-SERVICE.md` for counsel.
The privacy policy uses the same model. The home page repeats several figures from the terms, and
`promises.test.ts` pins each one to its clause.

The product has moved past the text in three ways (see the proposal):
- the status board and the server's eligibility decision (derived from the duty rules) both say what is live;
- the price follows the chosen stamp value;
- the stamp channel differs by state. **Ops confirmed on 2026-10-06:**
  - Karnataka: SHCIL e-stamp certificate.
  - Telangana: physical non-judicial stamp paper from a licensed stamp vendor.

Counsel is engaged, and its answers are folded in later. This change makes the published text
true about the product as built. It does not settle the open legal questions.

## Goals / Non-Goals

**Goals:**
- Every sentence of §2, §5, §7, §8, §11 and §14, and the privacy policy's stamp lines, is true of
  the product and of ops practice today.
- The terms keep no list of their own of what is live.
- The home-page copy that mirrors §7 and §8 moves in the same change, and every existing pin keeps
  holding or is deliberately updated.

**Non-Goals:**
- Changing any price, eligibility or stamping behaviour.
- Renaming the SHCIL-shaped intake fields (`estamp-intake`, `StaffConsole.vue`).
- Editing `docs/COUNSEL-BRIEF.md`.
- Renaming `GUARANTEES.certificateRefundRupees`. The refund is still for a wrong stamp, and the
  name is internal. `PRICE.includedDutyRupees` **is** renamed (D5), because the concept it names
  is changing.

## Decisions

### D1. §5 states the drafting scope and defers stampability to the marking

**Decision.** §5 does four things:
- names the drafting scope: Telangana and Karnataka, residential;
- explains the two conditions for stamping;
- keys the no-payment promise to the **server's eligibility decision**, which is derived from the
  duty rules and enforced at finalise, checkout, intake and eSign (`PaymentOrderService.java:189`,
  `SigningRequestService.java:137,241`, `StampIntakeService.java:224`);
- names the **"Draft and download only"** marking (the UI's own name for it, `CaptureForm.vue:1489`)
  as how that decision is normally *shown* before a template is filled in. The marking fails open
  if its lookup fails (`TemplatePicker.vue:48-51`), so it discloses the decision but is not the
  decision itself;
- calls the status board a summary.

**Why not resync "Today that is …"?** It would be a third copy of a fact the board and the server
already hold, and a copy that drifts at every flip. That drift is why this change exists.

**Why the server gate and not the board?** In the founding-team beta, prod runs with
`allow-unreviewed=true`. So the server takes payment for both states while the board says "In
integration". A promise keyed to the board would be false there. The server gate checks duty
rules, not whether ops can buy that state's stamps. So "a state we cannot stamp" holds only while
every chargeable state has a confirmed channel. Both do today (Context), and the `releaseStatus.ts`
launch checklist gains "ops has a confirmed channel for the state's stamps" so that a future state
cannot become chargeable without one.

**Residual duplication, accepted and recorded.** The drafting scope ("Telangana and Karnataka,
residential") now appears in three places: §5, the FAQ "Which cities do you serve?", and the
picker filter. Each is pinned by its own test. Adding a state is a launch event that touches all
three, so the `releaseStatus.ts` launch checklist lists all three. A shared constant would couple
legal text to a UI filter for a fact that changes perhaps once a year.

Draft §5:
> Stamp duty is levied by each state under its own law, and there is no single national rate. So
> we can stamp and eSign an agreement only for a state whose duty we can calculate and whose
> stamps we can obtain.
>
> You can draft residential rental agreements for Telangana and Karnataka here.
>
> We will not take payment for an agreement in a state we cannot stamp: the service refuses it.
> We normally tell you before you start filling in a template, by marking it "Draft and download
> only". Such an agreement can be previewed and downloaded free of charge, but it cannot be paid
> for, stamped or eSigned here. A document you draft this way is yours to use however you wish --
> including having it stamped yourself -- but it has not been stamped by us and carries no
> signature from this service. The status board on our home page summarises where stamping and
> eSign stand in each state; for your own agreement, what the service tells you applies.
>
> We add jurisdictions as we are able to, and this clause is updated when we do.

§2's fourth paragraph:
> Not all of that is available everywhere yet. The service is in a restricted beta. The status
> board on our home page summarises where each part stands, and it is kept honest.

The "no live list" check compares **case-sensitively** against the exact `RELEASE_STATE_LABEL`
values, plus "Today that is".

### D2. §7 is worded on the stamp value; the duty is shown, not priced

Draft §7:
> You pay one total, and the stamp is inside it. There is no second bill later.
>
> That total is INR 499 where the stamp value on your agreement is INR 100 or less. Where the
> stamp value is more than INR 100, the total is INR 499 plus the amount by which it exceeds
> INR 100. So a higher stamp raises what you pay by exactly its extra value, and by nothing else.
>
> We work out the stamp duty the law requires for your agreement and show it to you. Where the
> state's stamps allow it, we recommend a stamp of that value. In some states we can offer only a
> stamp of a fixed value, and that value can be below the duty. You can go ahead with a stamp
> below the duty only after we have shown you what that means: an under-stamped agreement cannot
> be relied on as evidence until the missing duty and a penalty are paid.
>
> You are shown the stamp, the duty and the total before you pay. Drafting, previewing and
> downloading a draft cost nothing, so you see the document and the price before any of it is due.
> What we will not do is take payment and then come back to you for more.

**Why this wording:**
- Paragraphs 1–2 restate `payment-processing`'s rule.
- Paragraph 3 restates `stamp-selection`'s acknowledgement. "Go ahead with" fits Telangana, where
  the below-duty ₹100 paper is pre-selected rather than chosen.
- It deliberately does not say "the difference is the duty". That holds in Karnataka and never
  in Telangana.

**Coupling.** The consequence sentence paraphrases the versioned `under-stamp-v1` warning. A
one-line comment names ToS §7 in three places, so a v2 warning prompts a §7 check:
- beside the warning text (`StampQuoteStep.vue`);
- beside the version constant (`StampOptions.java:28`, where the version is bumped);
- beside §7 itself.

**Alternative rejected:** keep the duty wording and add "or the stamp you choose". Two price bases
in one clause invite the misreading this change exists to remove.

### D3. §8 names the channel per state

Draft first paragraph:
> Stamp duty is a tax levied by the state government on the document. It is not our fee and we do
> not keep it. We hold no franking licence of our own: our staff buy the stamp for your agreement
> through the ordinary channel for its state and attach it to your agreement on your behalf. For
> a Karnataka agreement that is an e-stamp certificate bought through the Stock Holding
> Corporation of India (SHCIL). For a Telangana agreement, which SHCIL does not serve, it is
> non-judicial stamp paper bought from a licensed stamp vendor.

Every remaining §8 "certificate" changes too: the gap's "when we buy a stamp certificate for
you" becomes "when we buy a stamp for you". The guarantee paragraphs read:
> If a stamp we obtain for you is rejected or wrongly denominated because we got it wrong, we put
> it right at our own cost. ...
>
> On top of that we refund you INR 400 for the trouble -- in effect our whole charge for arranging
> the stamping, so the work costs you only the stamp itself. Where the stamp was rejected because
> of something you told us that was wrong, we will still help you put it right, but the
> replacement stamp is yours to pay for.

That keeps the `refund you INR 400` pin. "The duty the state was always going to take" and "the
duty on the replacement" are false under stamp-value pricing.

**Karnataka's below-duty options (user, 2026-10-06): e-stamp.** Karnataka's catalog also offers
below-duty *paper* denominations (`KA.yaml:41-49`), which `StampQuoteStep.vue:148-153` labels
"Stamp paper". The user confirmed that every Karnataka stamp, whatever its value, is a SHCIL
e-stamp. So §8 is true as drafted, and the "Stamp paper" label is wrong. Fixing the label is a
catalog/UI change outside this release-text change, so it is appended to
`stamp-paper-plus-challan-plan`.

**The Telangana paper original (user, 2026-10-06).** v1 does not ship the paper original to the
customer; shipping comes in v1.1. Staff keep the original for about a year and then shred it. A
customer who wants it can ask support, and we help arrange delivery. A paragraph is added to §8:
> For a Telangana agreement, we keep the paper original of the stamp for one year from the day we
> buy it, and then shred it. The scan attached to your agreement is not the paper itself. If you
> want the original, write to us within that year and we will arrange to have it sent to you.

The text says "one year" as a definite term in place of "a year or so", because a published
retention period must be one figure. It deliberately says nothing about the delivery cost: no
price has been decided, and the clause does not promise free delivery. v1.1 shipping and the
shred schedule go on a new register row, `tg-stamp-paper-shipping`.

The counsel gap keeps the question in a narrower form: whether the paper original must accompany
the electronically signed agreement, for example to be produced as evidence. If it must, keeping
it for a year and then shredding it needs counsel's view.

One sentence is appended to the gap:
> Also with counsel: whether stamp paper bought separately and attached to an electronically
> signed agreement stamps it validly, and what should happen to the paper original.

**Why per state rather than neutral wording:** ops has confirmed the channels. A stamp-duty
clause that names how the customer's stamp is bought is better disclosure than a generic one.
The channel is an ops fact that the YAML catalogs do not hold (they hold what the state
*issues*), so naming it here duplicates nothing.

### D4. Vocabulary: "certificate" is Karnataka's e-stamp only

| Location | Before | After |
|---|---|---|
| §2 | "a stamp certificate to be purchased" | "a stamp to be purchased" |
| §5 | "buy a stamp certificate … certificates we can obtain" | D1 draft |
| §11 body and gap | "your stamp certificate", "the certificate is yours" | "your stamp", "the stamp is yours" |
| §14 dependency | "the stamp certificate is bought on a government portal and the signing runs through a third party. Where either is unavailable, or where a public holiday intervenes" | "the stamp is bought through SHCIL or, in Telangana, from licensed stamp vendors, and the signing runs through a third party. Where SHCIL or the signing provider is down, where no licensed vendor can supply the stamp, or where a public holiday intervenes" |
| §14 exclusion | "an outage of the kind just described" | "a hold-up of the kind just described". One vendor being shut is not one, because only *no* vendor being able to supply counts. |
| §8 gap, §8 para 5 | "stamp certificate", "the certificate was rejected", "the duty on the replacement" | D3 wording |
| Privacy categories | "stamp-certificate scans" | "stamp scans" |
| Privacy roles | "stamp-certificate issuing authority" | "Karnataka e-stamp issuer" and "Telangana licensed stamp vendor". These are roles, not companies, which keeps the policy's "by role, never by vendor" rule (`privacyPolicy.ts:24,86`, `privacyPolicy.test.ts:49`). Only the terms name SHCIL. |
| Privacy lead sentence | "only with the service providers the service needs in order to work" | "… needs in order to work, and with whoever sells us the stamp your agreement legally needs" |
| Privacy collect paragraph | "details printed on the certificate, which include the parties' names and details of the property" | "When our staff buy the stamp for your agreement, we keep a scan of it and the details written or printed on it, which include the parties' names. For a Telangana agreement we also keep the paper original for one year, and then shred it." |
| Privacy roles (on request) | — | Add "courier, only if you ask us to send you a stamp paper original". It receives the name and address you give us for delivery, and nothing else. |
| Privacy recipients paragraph | "issuing authority … enter on its portal" | Exact text below |

The privacy recipients paragraph, exact text. The role strings are verbatim and the field lists
are non-exhaustive, because ops confirmed the channels but not the forms:
> The Karnataka e-stamp issuer, from which we buy a Karnataka e-stamp certificate, receives the
> details its form requires, such as the parties' names, a description of the document and the
> amounts it covers. It keeps its own record under its own rules, and anyone holding the e-stamp
> certificate number can look that record up.
>
> A Telangana licensed stamp vendor, from which we buy Telangana stamp paper, receives the details
> the state's rules require it to record, such as the parties' names and the purpose of the stamp,
> and keeps them in its own register under those rules.

The home page changes as follows:
- step 3 body: "We buy the stamp and attach it to your agreement."
- inclusion: "buying and attaching the stamp"
- guarantee 1 title: "We fix a wrong stamp, and refund ₹400."
- guarantee 1 body: "a stamp we buy for you"
- delay qualifier, in full: "Not while we wait on details from you or a signer we can't reach, while SHCIL or eSign is down or no licensed vendor can supply the stamp, or on a public holiday. Reduced by any discount you received." 
- FAQ "goes wrong": "a wrong stamp"
- the same in the `index.html` JSON-LD.

The eSign FAQ's "digital signature certificate" is correct and stays.

**Enforcement.** A new content test (`content/stampWording.test.ts`) walks every clause body and
gap of both documents, plus the privacy categories and roles. Matching is case-insensitive. It
asserts:
- no "portal" and no "government channel";
- every "certificate" is inside "e-stamp certificate";
- "SHCIL" appears only in the terms' `stamp-duty` and `availability-and-support` clauses, and
  never in the privacy policy.

On the home page, `LandingPage.test.ts` asserts the same, allowing "digital signature
certificate".

### D5. Landing price card follows §7 and keeps its owning-section phrase

Changes to `#price`:
- headline: `₹499 when your stamp is ₹100 or less`
- inclusion: `a stamp of up to ₹100`
- overflow: "Where your stamp duty is more than ₹100, you see the stamp we can buy, the duty and
  the exact total before you pay. There is never a second bill." This is true in Telangana, where
  only a ₹100 paper is offered. It does not imply a stamp that matches the duty. It keeps the
  baseline scenario "Stamp duty in the price is stated once outside the FAQ" (`#price` contains
  "stamp duty") and the no-second-bill statement.

FAQ 3: "₹499 in total when the stamp on your agreement is ₹100 or less. Where your stamp duty is
more than ₹100, you see the stamp we can buy, the duty and the exact total before you pay, and
there is never a second bill. Stamp duty is set by your state from the rent, deposit and term, and
we cannot change it."

`LandingPage.vue:38`'s local `includedDuty` becomes `includedStamp`.

`PRICE.includedDutyRupees` becomes `includedStampRupees`. `promises.test.ts` pins the new §7
phrase, built from `PRICE`. The view test builds its §7 phrases from `PRICE` too.

### D6. Register and same-fact bookkeeping

- `tos-below-duty-stamp-choice`: **deleted**. This change does the §7 rewording and moves the card
  and FAQ 3 with it. Its slug in the release counsel list (ROADMAP "First release", Counsel) is
  removed. The counsel checkbox gains "including the §7 below-duty paragraph", so the specific ask
  survives.
- `stamp-paper-plus-challan-plan`: its "(ops question)" sentence on what staff buy for Telangana
  is now answered and is removed. One sentence is appended: `estamp-intake`/`document-stamping`
  and `StaffConsole.vue` name SHCIL certificate fields, and they must be generalised for
  Telangana's vendor stamp paper (serial number, vendor), which they do not fit today.
- `tg-stamp-duty-counsel-review`: append the stamp-paper validity question and the question of
  what happens to the paper original.
- Same fact, fixed at once:
  - The `KA.yaml` header's "NO OPS PROCUREMENT CHANNEL" becomes the SHCIL channel. It is a
    comment, outside `contentHash`.
  - The ROADMAP flow line "staff upload a purchased SHCIL e-stamp scan" and the
    `manual-estamp-upload` paragraph "staff now purchase a real SHCIL e-stamp" become per state.
  - `GO-TO-MARKET-HYDERABAD.md`'s claim that "Telangana supports online e-stamping" is
    corrected.
  - The `releaseStatus.ts` checklist: §2/§5 defer to the board, and adding a state touches §5,
    the FAQ and the picker.
- ROADMAP "First release" item 1 is deleted at archive.

- New row `tg-stamp-paper-shipping` (v1.1). It covers shipping the Telangana paper original to
  the customer as a product feature, in place of on-request help through support, plus the
  one-year keep-then-shred schedule, which nothing tracks today. Recommended action: record each
  original's purchase date at intake, so the shred date is known, and build shipping in v1.1.

Net register effect: 0 (one row deleted, one row added).

## Risks / Trade-offs

- **[Risk]** Naming SHCIL and vendors makes §8 and §14 depend on ops practice. → It is confirmed
  practice. A change of channel is a terms change, which is correct for a disclosure.
- **[Risk]** The validity question is disclosed only in a §8 gap, not at checkout. → The release
  does not reach real customers until counsel answers (ROADMAP counsel checklist). Until then
  only the founding team pays.
- **[Risk]** §7's under-stamp paragraph is new legal text we wrote ourselves. → It paraphrases the
  shipped, audited warning, adds no new consequence, and is named in the counsel checkbox.
- **[Trade-off]** The terms tell a beta user less precisely what is live. Accepted. A precise
  sentence that is wrong is worse.

## Migration Plan

Content only. Regenerate both legal docs **after** the date bumps and ship with the next frontend
build. Revert to roll back. Under §18, the version that applies is the one published at payment.
No external customer has paid.

## Open Questions

- None open for product or ops. Both review-halt questions were answered on 2026-10-06 (D3).
  Counsel's question about the paper original stays in the §8 gap and on
  `tg-stamp-duty-counsel-review`.
