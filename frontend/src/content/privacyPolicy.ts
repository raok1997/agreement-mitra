// The privacy policy, as data -- the only home of the privacy text. Rendered at /privacy and to the
// counsel-facing docs/PRIVACY-POLICY.md by the same renderer as the terms (see legalDocs.ts).
//
// DRAFT ONLY WHAT IS TRUE TODAY. Every drafted sentence was checked against the code when it was
// written (evidence: openspec/changes/archive/*-legal-policy-pages/.flow-journal.md, apply stage). A
// false "we do not" is worse than a gap, so anything we could not verify is a counsel gap instead.
// When an integration changes what we collect or who receives it, update the lists below first:
// privacyPolicy.test.ts holds the clause text to them.

import { CONTACT_EMAIL } from "./promises";
import type { LegalDocument } from "./legalDocument";

/** Every category of personal data `what-we-collect` must name. */
export const PRIVACY_COLLECTED_CATEGORIES = [
  "the details you enter about the parties",
  "the content of the agreement",
  "account sign-in details",
  "the signed PDF and the signing audit trail",
  "stamp scans",
  "payment records",
  "IP address",
] as const;

/** Every kind of recipient `who-receives-it` must name. By role, never by vendor: vendors change. */
export const PRIVACY_RECIPIENT_ROLES = [
  "eSign provider",
  "payment gateway",
  "Karnataka e-stamp issuer",
  "Telangana licensed stamp vendor",
  "courier, only if you ask us to send you a stamp paper original",
  "email delivery provider",
  "sign-in provider",
  "hosting and storage providers",
  "content delivery and network security provider",
  "web-font provider",
] as const;

export const PRIVACY_POLICY: LegalDocument = {
  title: "Privacy Policy",
  lastUpdated: "6 October 2026",
  banner:
    "This is a draft, published during a restricted beta and pending review by Indian counsel. " +
    "Sections marked below are deliberately incomplete. It describes what the service does with " +
    "personal data today; it is not yet the full notice the law requires.",
  clauses: [
    {
      id: "who-we-are",
      heading: "1. Who we are",
      status: "drafted",
      body: [
        'AgreementMitra is a service provided by KAVISAT TEK LABS LLP. Under the Digital Personal Data Protection Act, 2023, KAVISAT TEK LABS LLP is the data fiduciary for the personal data processed through AgreementMitra: we decide why and how it is processed. In this policy, "we" and "us" mean KAVISAT TEK LABS LLP.',
      ],
    },
    {
      id: "what-we-collect",
      heading: "2. What we collect",
      status: "drafted",
      body: [
        "We collect the details you enter about the parties (name, parentage and address), the email address and any telephone number you give for each party, and the content of the agreement itself, including every answer you give in the form.",
        "If you sign in, we keep your account sign-in details: the name and email address of the account you sign in with, whether that email address has been verified, and the identifier the sign-in provider assigns to the account.",
        "When an agreement is signed, we store the signed PDF and the signing audit trail exactly as the eSign provider issues them. They are the provider's evidence of the signing, and they may include identity details the provider obtains from Aadhaar, such as the signer's name and postal code and how well the name matched.",
        "When our staff buy the stamp for your agreement, we keep stamp scans: a scan of the stamp and the details written or printed on it, which include the parties' names. For a Telangana agreement we also keep the paper original for one year, and then shred it.",
        "When you pay, we keep payment records: the amount, the payment gateway's order and payment references, and the agreement the payment was for, which links the payment to your account if that agreement is saved to one.",
        "To prevent abuse of the service, we record the IP address that some requests come from, such as a request to recover an agreement link: the full address for IPv4, and only the network part for IPv6. Our security logs record a shortened form of it.",
      ],
    },
    {
      id: "what-we-do-not-hold",
      heading: "3. What we do not hold",
      status: "drafted",
      body: [
        "We never ask for or store your Aadhaar number, virtual ID or Aadhaar one-time password. Signers authenticate on the eSign provider's own page, and the provider handles them. The signature and audit trail the provider adds to the signed PDF may show part of an Aadhaar number in masked form, as the eSign framework provides. The terms of service's clause on electronic signature says more about what the provider does rather than us.",
        "We hold no card, UPI or bank details. The payment gateway collects them directly on its own checkout.",
      ],
    },
    {
      id: "purposes-and-basis",
      heading: "4. Why we process it",
      status: "counsel",
      gap: "The purposes for which we process personal data, and the lawful basis for each under the Digital Personal Data Protection Act, 2023, are with counsel and deliberately not drafted by us.",
      body: [],
    },
    {
      id: "who-receives-it",
      heading: "5. Who receives it",
      status: "drafted",
      body: [
        "We share personal data only with the service providers the service needs in order to work, and with whoever sells us the stamp your agreement legally needs. We describe them by role rather than by company, because the company that fills a role can change.",
        "The eSign provider receives each signer's name and email address and the agreement to be signed, and emails each party its signing invitation.",
        "The payment gateway receives the amount and our order reference, and collects your payment details on its own checkout page, which sets its own cookies.",
        "The Karnataka e-stamp issuer, from which we buy a Karnataka e-stamp certificate, receives the details its form requires, such as the parties' names, a description of the document and the amounts it covers. It keeps its own record under its own rules, and anyone holding the e-stamp certificate number can look that record up.",
        "A Telangana licensed stamp vendor, from which we buy Telangana stamp paper, receives the details the state's rules require it to record, such as the parties' names and the purpose of the stamp, and keeps them in its own register under those rules.",
        "A courier, only if you ask us to send you a stamp paper original, receives the name and address you give us for delivery, and nothing else.",
        "Our email delivery provider carries the emails we send: the draft agreement to every party once their contact details are confirmed, the signed agreement once everyone has signed, and links to recover an agreement.",
        "The sign-in provider you choose tells us your name and email address when you sign in.",
        "Our hosting and storage providers hold the data described in this policy on our behalf.",
        "Our content delivery and network security provider sits in front of our site. All traffic to the site passes through it, including what you type into the form.",
        "Our web-font provider serves the fonts our pages use. Your browser fetches them from it directly, which shares your IP address and browser details with that provider.",
      ],
    },
    {
      id: "cookies-and-storage",
      heading: "6. Cookies and browser storage",
      status: "drafted",
      body: [
        "The cookies we set on our own domain are strictly necessary for the service: a session cookie that keeps you signed in, a security cookie that protects forms against cross-site request forgery, and a short-lived sign-in binding cookie that ties a sign-in to the browser that started it. The network security provider in front of our site may set its own cookie to tell people from automated traffic. We set no advertising or analytics cookies.",
        "The payment gateway's checkout page sets its own, third-party cookies when you pay.",
        "While you draft, the form keeps your answers, including the parties' details, in your browser's local storage so that a reload does not lose them. They are cleared when you save and continue or reset the form, and a saved draft older than 24 hours is discarded when you come back to the form. Signing out does not clear them, so on a shared device, reset the form when you finish.",
      ],
    },
    {
      id: "retention",
      heading: "7. How long we keep it",
      status: "counsel",
      gap: "How long we keep each kind of personal data described here, and whether the periods in the terms of service are the right ones under the Act, is with counsel. Until it is settled, this policy states no period of its own, other than how long we keep a Telangana stamp-paper original, which is set out under what we collect and in the terms of service's stamp-duty clause, and the 90 days an unpaid draft may go without a change before we delete it, which is set out in the terms of service's drafts clause.",
      body: [
        "How long we keep an agreement and its signing records is set out in the terms of service's clause on how long we keep things and deletion.",
      ],
    },
    {
      id: "deleted-drafts",
      heading: "8. Deleted drafts",
      status: "drafted",
      body: [
        "When you delete a draft, we keep a record that it was deleted -- its reference, the account it was saved to, if any, and the time -- and none of the parties' details.",
        "We keep the same record when we delete an unpaid draft that has gone 90 days without a change.",
      ],
    },
    {
      id: "your-rights",
      heading: "9. Your rights",
      status: "counsel",
      gap: "Your rights as a data principal -- access, correction, erasure and nomination -- and how to exercise them are with counsel and deliberately not drafted by us.",
      body: [],
    },
    {
      id: "grievance",
      heading: "10. Grievances",
      status: "counsel",
      gap: `The grievance officer and the route to the Data Protection Board of India are with counsel. Until they are named here, you can write to ${CONTACT_EMAIL} about anything in this policy.`,
      body: [],
    },
    {
      id: "transfers",
      heading: "11. Processing outside India",
      status: "counsel",
      gap: "Whether any of our service providers processes personal data outside India, and what follows from that under the Act, is with counsel.",
      body: [],
    },
    {
      id: "changes",
      heading: "12. Changes to this policy",
      status: "drafted",
      body: [
        "We will change this policy as the service changes and as counsel completes the sections marked above. Changes are published on this page with a new last-updated date.",
      ],
    },
  ],
};
