// The terms of service, as data.
//
// WHY THIS IS A DATA MODULE AND NOT PROSE IN A .vue FILE. The same text has two audiences: the
// customer, who reads it at /terms, and counsel, who reads it as a document (docs/TERMS-OF-SERVICE.md,
// attached to docs/COUNSEL-BRIEF.md Q6). Two hand-maintained copies of a legal text is precisely the
// failure docs/LEGAL-POSTURE.md exists to prevent, so there is one source -- this file -- and the
// markdown is RENDERED from it by scripts/render-terms.mjs. termsOfService.test.ts fails if the two
// have drifted, which makes the drift unmissable rather than merely unlikely.
//
// STATUS IS PART OF THE CONTENT, NOT A FOOTNOTE. Every clause carries a `status`:
//   "drafted"  -- we wrote it and we mean it, subject to counsel's review of the whole
//   "counsel"  -- a deliberate GAP. We have not written it because it is not ours to write.
//   "product"  -- a commercial term nobody has decided yet. NOT a guess, and never to be filled in
//                 by inference: a wrong number here is a number counsel then reviews as intended.
// A "counsel" or "product" clause renders as a visible gap on the page. That is the honest thing to
// publish during founding-team beta -- a marked hole beats a confident invention, and it beats
// having no terms at all (docs/LEGAL-POSTURE.md item 2).

export type ClauseStatus = "drafted" | "counsel" | "product";

export interface Clause {
  /** Section heading, rendered as an <h2> on the page and "## " in the markdown. */
  heading: string;
  /** Body paragraphs. Plain text: no markup, no interpolation -- it is a legal text, not a template. */
  body: string[];
  status: ClauseStatus;
  /** For a non-"drafted" clause: what is missing and who owes it. Rendered as the visible gap note. */
  gap?: string;
}

/** Shown at the head of both faces. The page must never look like a settled document. */
export const TERMS_STATUS_BANNER =
  "This is a draft, published during a restricted beta and pending review by Indian counsel. " +
  "Sections marked below are deliberately incomplete. We publish it in this state because a draft " +
  "you can read beats terms that do not exist -- not because it is finished.";

/** The date the draft last changed. Bumped by hand when a clause changes. */
export const TERMS_LAST_UPDATED = "7 September 2026";

export const TERMS_CLAUSES: Clause[] = [
  {
    heading: "1. Who we are",
    status: "drafted",
    body: [
      'AgreementMitra is an online service that produces residential rental agreements for the Indian market and takes them through to electronic execution. In these terms, "we" and "us" mean AgreementMitra, and "you" mean the person using the service.',
      "These terms apply whenever you use the service, whether or not you create an account. Much of the service is deliberately usable without one.",
    ],
  },
  {
    heading: "2. What the service does",
    status: "drafted",
    body: [
      "You answer a structured form about the parties, the premises, the term, the rent and the deposit. We generate a completed rental agreement from a stored template and show it to you before you pay anything.",
      "If you go ahead, we arrange for a stamp certificate to be purchased and affixed to the agreement, and we invite each party to sign it electronically. When everybody has signed, we send the signed PDF and the signing audit trail to the parties.",
      "We record, for every agreement we generate, a cryptographic fingerprint of the exact template wording used to produce it. For any document we have issued we can state precisely what its template said on the day it was generated.",
      "Not all of that is live yet. The service is in a restricted beta: drafting, previewing and downloading an agreement work today, and stamping and Aadhaar signing are still being integrated. The status board on our home page says what is live, what is in integration and what is planned, and it is kept honest.",
    ],
  },
  {
    heading: "3. We are not a law firm, and this is not legal advice",
    status: "drafted",
    body: [
      "We are not a law firm and we do not practise law. Nobody at AgreementMitra acts as your advocate, and using the service does not create a lawyer-client relationship or any duty of confidence of that kind.",
      "Nothing the service produces or displays is legal advice. The agreement is generated from a template. No advocate reviews your agreement, your circumstances or your answers, and the service does not tell you which terms are appropriate for your tenancy.",
      "If your arrangement is unusual, disputed or valuable enough that getting it wrong would matter to you, take advice from a lawyer. The service is not a substitute for one.",
    ],
  },
  {
    heading: "4. Documents are generated from templates",
    status: "drafted",
    body: [
      "Every agreement comes from the same stored template for its jurisdiction and type. There is no per-customer drafting, and the wording you receive is the wording every other customer of that template receives.",
      "It follows that the template must fit your situation for the document to be right. You are responsible for reading the agreement before you sign it -- which is why we show it to you in full, free, before you pay.",
      "We update templates as the law changes and as we correct them. An agreement you have already executed is unaffected: it keeps the wording it was generated with.",
    ],
  },
  {
    heading: "5. What you tell us",
    status: "drafted",
    body: [
      "You are responsible for the accuracy of what you enter. We do not verify the identity of the parties beyond the electronic-signature check described below, we do not verify ownership of the premises, and we do not check that the commercial terms you have chosen are lawful, fair or what you agreed with the other party.",
      "You must be entitled to enter into the agreement you are creating, and to provide the details of any other party you enter.",
    ],
  },
  {
    heading: "6. Our fee",
    status: "drafted",
    body: [
      "You pay one total, and stamp duty is inside it. There is no second bill later.",
      "That total is INR 499 where the stamp duty on your agreement is INR 100 or less. Where the duty is more than INR 100, the total is INR 499 plus the amount by which the duty exceeds INR 100. So a higher duty raises what you pay by exactly what the state charges, and by nothing else.",
      "You are shown the total, and the duty inside it, before you pay. Drafting, previewing and downloading a draft cost nothing, so you see the document and the price before any of it is due.",
      "We are still building the part that works the duty out automatically -- the status board on our home page says where it has got to. Until it is live you pay the flat INR 499, and where the duty turns out to be more than INR 100 we have been absorbing the difference. If that ever stops being something we can do, we will tell you the total before taking your money. What we will not do is take payment and then come back to you for more.",
    ],
  },
  {
    heading: "7. Stamp duty",
    status: "counsel",
    gap: "Our legal position when we buy a stamp certificate for you -- whether we do so as your agent, and what follows from that -- is with counsel. Until that is settled, treat this clause as descriptive rather than as a statement of who bears what -- in particular, whether duty we have paid on your behalf is recoverable, and from whom. What we do when we get it wrong is settled and stated below.",
    body: [
      "Stamp duty is a tax levied by the state government on the document. It is not our fee and we do not keep it. We hold no franking licence of our own: our staff purchase an e-stamp certificate through the ordinary Stock Holding Corporation of India (SHCIL) channel and affix it to your agreement on your behalf.",
      "The amount of duty is fixed by the law of the relevant state and depends on the rent, the deposit and the term. We do not set it and we cannot reduce it.",
      "Stamping is not registration. Paying stamp duty on a document is a different thing from registering a lease with the sub-registrar, and a stamped, electronically signed agreement is not a registered lease.",
      "If a certificate we obtain for you is rejected or wrongly denominated because we got it wrong, we put it right at our own cost. You are not asked for any further payment, and we do not treat our mistake as your problem to solve.",
      "On top of that we refund you INR 400 for the trouble -- in effect our whole charge for arranging the stamping, so the work costs you only the duty the state was always going to take. Where the certificate was rejected because of something you told us that was wrong, we will still help you put it right, but the duty on the replacement is yours.",
    ],
  },
  {
    heading: "8. Electronic signature and identity",
    status: "drafted",
    body: [
      "Signing is performed using Aadhaar-based electronic signature under section 3A of the Information Technology Act, 2000, through a licensed eSign Service Provider. We are not that provider and we do not perform the authentication.",
      "Each signer authenticates on the provider's own page. No Aadhaar number, virtual ID or one-time password passes through our systems, is stored by us, or is written to our logs.",
      "We receive and store the signed PDF and the provider's completion audit trail. We keep the audit trail as the provider issued it: we do not parse it or extract fields from it. It is the provider's evidence of the authentication, and it may contain identity evidence generated at the provider's end.",
      "We may ask the provider to check a signer's name against Aadhaar as part of signing. The comparison is performed by the provider; we receive only its outcome.",
      "A signature is complete only when the provider reports it as complete. A signing request can expire or fail at the provider's end, and if it does, the agreement is not signed.",
    ],
  },
  {
    heading: "9. Drafts, and drafts you abandon",
    status: "drafted",
    body: [
      "An unpaid draft is yours to abandon. You can leave at any point before payment and owe us nothing.",
      "While you are filling in the form, a copy of the draft is held in your own browser so that a reload does not lose your work. Once you save an agreement, it is held on our systems and you are given a reference and, if you gave us an email address, a link back to it.",
      "That link is a key: anyone holding it can open the agreement. It stops working once the agreement is saved to an account, after which you open the agreement by signing in.",
      "We may delete unpaid drafts that have been untouched for a long time. See the retention clause below.",
    ],
  },
  {
    heading: "10. Refunds and cancellation",
    status: "product",
    gap: "One case is still open: what you get back once we have already bought your stamp certificate. Duty paid to the state is not ours to return, whether any of it can be recovered is limited by law, and whether the certificate is yours rather than ours in the first place is a question we have put to counsel. We would rather leave this blank than state a rule we may have no right to apply. No external customer has yet paid us, so no refund has been asked for or refused.",
    body: [
      "Before we buy your stamp certificate, you can cancel for any reason. We refund what you paid, less INR 100 towards handling the cancellation.",
      "If a signing attempt fails or expires, we re-send the signing request at no charge. Where signing repeatedly fails because of something at the signer's end -- an abandoned session, or details that do not match -- we may ask for INR 100 before starting it again. We do not charge you when the failure was ours or the eSign provider's, and where we cannot tell, we treat it as ours.",
      "One rule covers every fixed sum these terms promise you -- the INR 400 in the stamp-duty clause, and the delay credit further down. If you paid less than the full price because a discount or promotion was applied, we reduce that sum by the discount, to a minimum of nothing. We never pay you back more than you actually paid us.",
    ],
  },
  {
    heading: "11. How long we keep things, and deletion",
    status: "counsel",
    gap: "Three questions here are with counsel and their answers may change what this clause says. Whether one party may have a jointly executed instrument deleted when the other party's continued access to it depends on us. Whether three years is the right period, given that a tenancy dispute usually arises at or after the end of the term rather than when the agreement was made. And whether the eSign provider's audit trail carries a retention obligation of its own, separate from ours. See also the data-protection clause below.",
    body: [
      "We keep a signed agreement, its signed PDF and its signing audit trail for three years.",
      "Once everyone has signed, every party can download the signed agreement. Keep your own copy: it is your document, and it is the copy that does not depend on us.",
      "Deletion can be asked for by the person who created and paid for the agreement, signed in to the account it is saved to. We do not have a deletion control in the product yet -- until we do, write to us and we will do it by hand.",
    ],
  },
  {
    heading: "12. Acceptable use",
    status: "drafted",
    body: [
      "Use the service to create genuine rental agreements for real arrangements you are party to or authorised to arrange.",
      "Do not use it to impersonate anyone, to enter another person's details without their authority, to create a document you intend to pass off as executed when it is not, or to produce anything unlawful.",
      "Do not attempt to interfere with the service, to access agreements that are not yours, to test its security without our written permission, or to extract our templates in bulk.",
      "We may suspend or refuse service where we reasonably believe this clause is being broken.",
    ],
  },
  {
    heading: "13. Availability and support",
    status: "drafted",
    body: [
      "We answer messages during business hours, 9am to 5pm IST on working days.",
      "We aim to have your agreement stamped within one working day of payment. An order paid for outside business hours is treated as received at the start of the next working day.",
      "That is a target we work to, not a guarantee, and one part of it is outside our hands: the stamp certificate is bought on a government portal and the signing runs through a third party. Where either is unavailable, or where a public holiday intervenes, the clock stops until it is back.",
      "If we are more than two working days late through something that was ours, we refund INR 100 for each further working day, up to INR 400. We do not pay this where the delay was caused by details we were waiting on from you, by a signer we could not reach, or by an outage of the kind just described.",
      "The service is currently in a restricted beta and is not offered with any wider availability guarantee.",
    ],
  },
  {
    heading: "14. Your personal data",
    status: "counsel",
    gap: "A privacy notice under the Digital Personal Data Protection Act, 2023 -- the purposes, the basis, the retention period, your rights and how to exercise them -- is with counsel, including whether it must be a separate notice rather than a clause in these terms. What appears here now is a description of what we hold, not the notice.",
    body: [
      "We collect the details you enter about the parties (name, parentage and address), the email address and any telephone number you give for each party, and the content of the agreement itself. We store the signed PDF and the signing audit trail.",
      "We do not hold Aadhaar numbers, virtual IDs or one-time passwords. See the electronic-signature clause above for what the eSign provider handles rather than us.",
    ],
  },
  {
    heading: "15. Our liability",
    status: "counsel",
    gap: "The limitation of liability is with counsel and is deliberately not drafted by us. Nothing in these terms should be read as limiting our liability until this clause exists.",
    body: [],
  },
  {
    heading: "16. Disputes and governing law",
    status: "counsel",
    gap: "Governing law, jurisdiction and the dispute-resolution route are with counsel, as is whether the Consumer Protection Act, 2019 and its e-commerce rules constrain what these terms may say or require us to publish anything further.",
    body: [],
  },
  {
    heading: "17. Changes to these terms",
    status: "drafted",
    body: [
      "We will change these terms as the service changes and as counsel completes the sections marked above. The version that applies to an agreement is the version published when you paid for it.",
      "The date this draft last changed is shown at the top of this page.",
    ],
  },
  {
    heading: "18. Contact",
    status: "drafted",
    body: [
      "Write to hello@agreementmitra.com. If your message is about a specific agreement, quote its reference.",
    ],
  },
];
