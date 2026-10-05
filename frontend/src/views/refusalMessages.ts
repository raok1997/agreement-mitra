// What a customer reads when a server refusal has a remedy other than "try again", and the one rule
// for when an error's own message may be shown at all (agreement-error-problem-type-plumbing D4).

import { CustomerFacingError } from "../api/http";

/**
 * Stamping is limited by jurisdiction. Shown wherever payment is started, and by the stamp step
 * ahead of time, so the same refusal reads the same everywhere.
 */
export const JURISDICTION_UNSUPPORTED_MESSAGE =
  "Stamping and eSign are not yet available for this agreement's jurisdiction. You can " +
  "still preview and download the draft free of charge.";

/**
 * The state is supported but no stamp paper combination covers the duty (quote status
 * UNPLANNABLE). Not a jurisdiction refusal, so it must not say one.
 */
export const STAMP_UNPLANNABLE_MESSAGE =
  "Stamping is not available for this agreement yet. You can still preview and download the " +
  "draft free of charge.";

/** Terms lock when the order is placed (finalise creates the signing request), not at signing. */
export const TERMS_FROZEN_MESSAGE =
  "This agreement's order has already been placed, so its terms can no longer be changed. " +
  "Contact support if something in it is wrong.";

/**
 * A capture-form pay-path call was refused 404: the agreement is unknown, or claimed by an account
 * this session is not signed in as. Reads the same for both and never names the account, so it is
 * not an ownership oracle. Not the status view's link message: here the customer pressed a button.
 */
export const AGREEMENT_UNAVAILABLE_MESSAGE =
  "This agreement isn't available here. If it has been saved to an account, sign in with that " +
  "account to continue.";

/**
 * The error's own message when it was written for customers, otherwise `fallback`. An allowlist:
 * an API HTTP error's message is a status string, and a runtime error's message is not ours.
 */
export function customerMessage(e: unknown, fallback: string): string {
  return e instanceof CustomerFacingError && e.message ? e.message : fallback;
}
