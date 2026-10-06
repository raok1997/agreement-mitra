// Every figure the home page repeats from the terms of service or the backend fee config.
// LandingPage.vue hand-types none of them; promises.test.ts pins each one to its source
// (application.yml defaults and the ToS clause bodies), so a reworded clause fails the build
// until the page follows.

export const PRICE = { totalRupees: 499, includedStampRupees: 100 } as const;

export const GUARANTEES = {
  certificateRefundRupees: 400, // §8
  signerRetryChargeRupees: 100, // §11
  stampTargetWorkingDaysText: "one", // §14 target
  delayGraceWorkingDaysText: "two", // §14 (the ToS spells the number)
  delayCreditPerDayRupees: 100, // §14
  delayCreditCapRupees: 400, // §14
} as const;

export const SUPPORT_HOURS = "9am to 5pm IST on working days"; // §14

export const CONTACT_EMAIL = "support@agreementmitra.com";

// Not Intl: it renders "₹499.00".
export const formatRupees = (n: number): string => `₹${n}`;

/** The /contact banner: the policies are drafts, the contact facts are not (legal-policy-pages D8). */
export const CONTACT_PAGE_BANNER =
  "Our policies are drafts pending review by Indian counsel. The contact details below are current.";
