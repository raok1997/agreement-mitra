// The terms of service, as data.
//
// WHY THIS IS A DATA MODULE AND NOT PROSE IN A .vue FILE. The same text has two audiences: the
// customer, who reads it at /terms, and counsel, who reads it as a document (docs/TERMS-OF-SERVICE.md,
// attached to docs/COUNSEL-BRIEF.md Q6). Two hand-maintained copies of a legal text is precisely the
// failure docs/LEGAL-POSTURE.md exists to prevent, so there is one source -- this file -- and the
// markdown is RENDERED from it by scripts/render-legal-docs.mjs. legalDocs.test.ts fails if the two
// have drifted, which makes the drift unmissable rather than merely unlikely.
//
// What each clause status means, and why clauses are named by id: see legalDocument.ts.

import { clauseById, type Clause, type LegalDocument } from "./legalDocument";

const CLAUSES: Clause[] = [
  {
    id: "who-we-are",
    heading: "1. Who we are",
    status: "drafted",
    body: [
      'AgreementMitra is an online service that produces residential rental agreements for the Indian market and takes them through to electronic execution. AgreementMitra is a service provided by KAVISAT TEK LABS LLP, a limited liability partnership registered in India. In these terms, "we" and "us" mean KAVISAT TEK LABS LLP, and "you" mean the person using the service.',
      "These terms apply whenever you use the service, whether or not you create an account. Much of the service is deliberately usable without one.",
    ],
  },
  {
    id: "what-the-service-does",
    heading: "2. What the service does",
    status: "drafted",
    body: [
      "You answer a structured form about the parties, the premises, the term, the rent and the deposit. We generate a completed rental agreement from a stored template and show it to you before you pay anything.",
      "If you go ahead, we arrange for a stamp to be purchased and affixed to the agreement, and we invite each party to sign it electronically. When everybody has signed, we send the signed PDF and the signing audit trail to the parties.",
      "We record, for every agreement we generate, a cryptographic fingerprint of the exact template wording used to produce it. For any document we have issued we can state precisely what its template said on the day it was generated.",
      "Not all of that is available everywhere yet. The service is in a restricted beta. The status board on our home page summarises where each part stands, and it is kept honest.",
    ],
  },
  {
    id: "not-legal-advice",
    heading: "3. We are not a law firm, and this is not legal advice",
    status: "drafted",
    body: [
      "We are not a law firm and we do not practise law. Nobody at AgreementMitra acts as your advocate, and using the service does not create a lawyer-client relationship or any duty of confidence of that kind.",
      "Nothing the service produces or displays is legal advice. The agreement is generated from a template. No advocate reviews your agreement, your circumstances or your answers, and the service does not tell you which terms are appropriate for your tenancy.",
      "If your arrangement is unusual, disputed or valuable enough that getting it wrong would matter to you, take advice from a lawyer. The service is not a substitute for one.",
    ],
  },
  {
    id: "template-documents",
    heading: "4. Documents are generated from templates",
    status: "drafted",
    body: [
      "Every agreement comes from the same stored template for its jurisdiction and type. There is no per-customer drafting, and the wording you receive is the wording every other customer of that template receives.",
      "It follows that the template must fit your situation for the document to be right. You are responsible for reading the agreement before you sign it -- which is why we show it to you in full, free, before you pay.",
      "We update templates as the law changes and as we correct them. An agreement you have already executed is unaffected: it keeps the wording it was generated with.",
    ],
  },
  {
    id: "jurisdictions",
    heading: "5. Where we can stamp and eSign",
    status: "drafted",
    body: [
      "Stamp duty is levied by each state under its own law, and there is no single national rate. So we can stamp and eSign an agreement only for a state whose duty we can calculate and whose stamps we can obtain.",
      "You can draft residential rental agreements for Telangana and Karnataka here.",
      'We will not take payment for an agreement in a state we cannot stamp: the service refuses it. We normally tell you before you start filling in a template, by marking it "Draft and download only". Such an agreement can be previewed and downloaded free of charge, but it cannot be paid for, stamped or eSigned here. A document you draft this way is yours to use however you wish -- including having it stamped yourself -- but it has not been stamped by us and carries no signature from this service. The status board on our home page summarises where stamping and eSign stand in each state; for your own agreement, what the service tells you applies.',
      "We add jurisdictions as we are able to, and this clause is updated when we do.",
    ],
  },
  {
    id: "what-you-tell-us",
    heading: "6. What you tell us",
    status: "drafted",
    body: [
      "You are responsible for the accuracy of what you enter. We do not verify the identity of the parties beyond the electronic-signature check described below, we do not verify ownership of the premises, and we do not check that the commercial terms you have chosen are lawful, fair or what you agreed with the other party.",
      "You must be entitled to enter into the agreement you are creating, and to provide the details of any other party you enter.",
    ],
  },
  {
    id: "our-fee",
    heading: "7. Our fee",
    status: "drafted",
    body: [
      "You pay one total, and the stamp is inside it. There is no second bill later.",
      "That total is INR 499 where the stamp value on your agreement is INR 100 or less. Where the stamp value is more than INR 100, the total is INR 499 plus the amount by which it exceeds INR 100. So a higher stamp raises what you pay by exactly its extra value, and by nothing else.",
      // Paraphrases the under-stamp warning (under-stamp-v1, StampQuoteStep.vue): re-check this sentence when that warning changes.
      "We work out the stamp duty the law requires for your agreement and show it to you. Where the state's stamps allow it, we recommend a stamp of that value. In some states we can offer only a stamp of a fixed value, and that value can be below the duty. You can go ahead with a stamp below the duty only after we have shown you what that means: an under-stamped agreement cannot be relied on as evidence until the missing duty and a penalty are paid.",
      "You are shown the stamp, the duty and the total before you pay. Drafting, previewing and downloading a draft cost nothing, so you see the document and the price before any of it is due. What we will not do is take payment and then come back to you for more.",
    ],
  },
  {
    id: "stamp-duty",
    heading: "8. Stamp duty",
    status: "counsel",
    gap: "Our legal position when we buy a stamp for you -- whether we do so as your agent, and what follows from that -- is with counsel. Until that is settled, treat this clause as descriptive rather than as a statement of who bears what -- in particular, whether duty we have paid on your behalf is recoverable, and from whom. What we do when we get it wrong is settled and stated below. Also with counsel: whether stamp paper bought separately and attached to an electronically signed agreement stamps it validly, and whether the paper original must accompany the agreement.",
    body: [
      "Stamp duty is a tax levied by the state government on the document. It is not our fee and we do not keep it. We hold no franking licence of our own: our staff buy the stamp for your agreement through the ordinary channel for its state and attach it to your agreement on your behalf. For a Karnataka agreement that is an e-stamp certificate bought through the Stock Holding Corporation of India (SHCIL). For a Telangana agreement, which SHCIL does not serve, it is non-judicial stamp paper bought from a licensed stamp vendor.",
      "The amount of duty is fixed by the law of the relevant state and depends on the rent, the deposit and the term. We do not set it and we cannot reduce it.",
      "Stamping is not registration. Paying stamp duty on a document is a different thing from registering a lease with the sub-registrar, and a stamped, electronically signed agreement is not a registered lease.",
      "For a Telangana agreement, we keep the paper original of the stamp for one year from the day we buy it, and then shred it. The scan attached to your agreement is not the paper itself. If you want the original, write to us within that year and we will arrange to have it sent to you.",
      "If a stamp we obtain for you is rejected or wrongly denominated because we got it wrong, we put it right at our own cost. You are not asked for any further payment, and we do not treat our mistake as your problem to solve.",
      "On top of that we refund you INR 400 for the trouble -- in effect our whole charge for arranging the stamping, so the work costs you only the stamp itself. Where the stamp was rejected because of something you told us that was wrong, we will still help you put it right, but the replacement stamp is yours to pay for.",
    ],
  },
  {
    id: "electronic-signature",
    heading: "9. Electronic signature and identity",
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
    id: "drafts",
    heading: "10. Drafts, and drafts you abandon",
    status: "drafted",
    body: [
      "An unpaid draft is yours to abandon. You can leave at any point before payment and owe us nothing.",
      "If you are signed in, you can delete an unpaid draft yourself from My agreements, as long as you have not yet gone to payment. A deleted draft is removed from the service and cannot be restored. Copies in our backups are overwritten as those backups rotate. Copies of the draft we have already emailed to the parties cannot be recalled.",
      "While you are filling in the form, a copy of the draft is held in your own browser so that a reload does not lose your work. Once you save an agreement, it is held on our systems and you are given a reference and, if you gave us an email address, a link back to it.",
      "That link is a key: anyone holding it can open the agreement. It stops working once the agreement is saved to an account, after which you open the agreement by signing in.",
      "We may delete unpaid drafts that have been untouched for a long time. See the retention clause below.",
    ],
  },
  {
    id: "refunds",
    heading: "11. Refunds and cancellation",
    status: "product",
    gap: "One case is still open: what you get back once we have already bought your stamp. Duty paid to the state is not ours to return, whether any of it can be recovered is limited by law, and whether the stamp is yours rather than ours in the first place is a question we have put to counsel. We would rather leave this blank than state a rule we may have no right to apply. No external customer has yet paid us, so no refund has been asked for or refused.",
    body: [
      "Before we buy your stamp, you can cancel for any reason. We refund what you paid, less INR 100 towards handling the cancellation.",
      "If a signing attempt fails or expires, we re-send the signing request at no charge. Where signing repeatedly fails because of something at the signer's end -- an abandoned session, or details that do not match -- we may ask for INR 100 before starting it again. We do not charge you when the failure was ours or the eSign provider's, and where we cannot tell, we treat it as ours.",
      "One rule covers every fixed sum these terms promise you -- the INR 400 in the stamp-duty clause, and the delay credit further down. If you paid less than the full price because a discount or promotion was applied, we reduce that sum by the discount, to a minimum of nothing. We never pay you back more than you actually paid us.",
    ],
  },
  {
    id: "retention",
    heading: "12. How long we keep things, and deletion",
    status: "counsel",
    gap: "Three questions here are with counsel and their answers may change what this clause says. Whether one party may have a jointly executed instrument deleted when the other party's continued access to it depends on us. Whether three years is the right period, given that a tenancy dispute usually arises at or after the end of the term rather than when the agreement was made. And whether the eSign provider's audit trail carries a retention obligation of its own, separate from ours. See also the data-protection clause below.",
    body: [
      "We keep a signed agreement, its signed PDF and its signing audit trail for three years.",
      "Once everyone has signed, every party can download the signed agreement. Keep your own copy: it is your document, and it is the copy that does not depend on us.",
      "You can delete an unpaid draft yourself from My agreements (see the drafts clause above). For anything else, deletion can be asked for by the person who created and paid for the agreement, signed in to the account it is saved to: write to us and we will do it by hand.",
    ],
  },
  {
    id: "acceptable-use",
    heading: "13. Acceptable use",
    status: "drafted",
    body: [
      "Use the service to create genuine rental agreements for real arrangements you are party to or authorised to arrange.",
      "Do not use it to impersonate anyone, to enter another person's details without their authority, to create a document you intend to pass off as executed when it is not, or to produce anything unlawful.",
      "Do not attempt to interfere with the service, to access agreements that are not yours, to test its security without our written permission, or to extract our templates in bulk.",
      "We may suspend or refuse service where we reasonably believe this clause is being broken.",
    ],
  },
  {
    id: "availability-and-support",
    heading: "14. Availability and support",
    status: "drafted",
    body: [
      "We answer messages during business hours, 9am to 5pm IST on working days.",
      "We aim to have your agreement stamped within one working day of payment. An order paid for outside business hours is treated as received at the start of the next working day.",
      "That is a target we work to, not a guarantee, and one part of it is outside our hands: the stamp is bought through SHCIL or, in Telangana, from licensed stamp vendors, and the signing runs through a third party. Where SHCIL or the signing provider is down, where no licensed vendor can supply the stamp, or where a public holiday intervenes, the clock stops until it is back.",
      "If we are more than two working days late through something that was ours, we refund INR 100 for each further working day, up to INR 400. We do not pay this where the delay was caused by details we were waiting on from you, by a signer we could not reach, or by a hold-up of the kind just described.",
      "The service is currently in a restricted beta and is not offered with any wider availability guarantee.",
    ],
  },
  {
    id: "personal-data",
    heading: "15. Your personal data",
    status: "counsel",
    gap: "Whether the privacy notice under the Digital Personal Data Protection Act, 2023 must stand apart from these terms or form part of them is with counsel. Until that is answered, the privacy policy is a separate document and this clause only points to it.",
    body: [
      "What personal data we hold, why, and who receives it is set out in our privacy policy at agreementmitra.com/privacy.",
    ],
  },
  {
    id: "liability",
    heading: "16. Our liability",
    status: "counsel",
    gap: "The limitation of liability is with counsel and is deliberately not drafted by us. Nothing in these terms should be read as limiting our liability until this clause exists.",
    body: [],
  },
  {
    id: "disputes",
    heading: "17. Disputes and governing law",
    status: "counsel",
    gap: "Governing law, jurisdiction and the dispute-resolution route are with counsel, as is whether the Consumer Protection Act, 2019 and its e-commerce rules constrain what these terms may say or require us to publish anything further.",
    body: [],
  },
  {
    id: "changes",
    heading: "18. Changes to these terms",
    status: "drafted",
    body: [
      "We will change these terms as the service changes and as counsel completes the sections marked above. The version that applies to an agreement is the version published when you paid for it.",
      "The date this draft last changed is shown at the top of this page.",
    ],
  },
  {
    id: "contact",
    heading: "19. Contact",
    status: "drafted",
    body: [
      "Write to support@agreementmitra.com. If your message is about a specific agreement, quote its reference.",
    ],
  },
];

export const TERMS_OF_SERVICE: LegalDocument = {
  title: "Terms of Service",
  lastUpdated: "6 October 2026",
  banner:
    "This is a draft, published during a restricted beta and pending review by Indian counsel. " +
    "Sections marked below are deliberately incomplete. We publish it in this state because a draft " +
    "you can read beats terms that do not exist -- not because it is finished.",
  clauses: CLAUSES,
};

// Clauses other pages render, resolved here so a wrong id fails on import rather than at render.
/** §11, rendered whole at /refunds. */
export const TERMS_REFUNDS_CLAUSE = clauseById(TERMS_OF_SERVICE, "refunds");
/** §19, rendered whole at /contact. */
export const TERMS_CONTACT_CLAUSE = clauseById(TERMS_OF_SERVICE, "contact");
